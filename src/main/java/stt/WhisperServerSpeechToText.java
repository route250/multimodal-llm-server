package stt;

import audio.AudioBuffer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public class WhisperServerSpeechToText implements SpeechToText {
    public static final URI DEFAULT_ENDPOINT = URI.create("http://localhost:8766/inference");
    private static final int SAMPLE_RATE = 16_000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final Pattern VTT_TIMING = Pattern.compile(
            "(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+-->\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})(?:\\s+.*)?");

    private final URI endpoint;
    private final HttpClient httpClient;

    public WhisperServerSpeechToText() {
        this(DEFAULT_ENDPOINT);
    }

    public WhisperServerSpeechToText(URI endpoint) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        return transcribeWithSegments(audioBuffer, startSampleIndex, endSampleIndexExclusive).text();
    }

    @Override
    public String transcribe(
            AudioBuffer audioBuffer,
            long startSampleIndex,
            long endSampleIndexExclusive,
            String prompt) {
        return transcribeWithSegments(audioBuffer, startSampleIndex, endSampleIndexExclusive, prompt).text();
    }

    @Override
    public Transcription transcribeWithSegments(
            AudioBuffer audioBuffer,
            long startSampleIndex,
            long endSampleIndexExclusive) {
        return transcribeWithSegments(audioBuffer, startSampleIndex, endSampleIndexExclusive, "");
    }

    @Override
    public Transcription transcribeWithSegments(
            AudioBuffer audioBuffer,
            long startSampleIndex,
            long endSampleIndexExclusive,
            String prompt) {
        if (endSampleIndexExclusive <= startSampleIndex) {
            return Transcription.empty();
        }
        byte[] wav = wav(audioBuffer, startSampleIndex, endSampleIndexExclusive);
        String body = requestWhisper(wav, "vtt", prompt);
        return parseWebVtt(body);
    }

    private String requestWhisper(byte[] wav, String responseFormat, String prompt) {
        String boundary = "----java-whisper-" + UUID.randomUUID();
        List<FormPart> parts = new ArrayList<>();
        parts.add(FormPart.file("file", "speech.wav", "audio/wav", wav));
        parts.add(FormPart.field("language", "ja"));
        parts.add(FormPart.field("temperature", "0.0"));
        if (prompt != null && !prompt.isBlank()) {
            parts.add(FormPart.field("prompt", prompt));
        }
        parts.add(FormPart.field("response_format", responseFormat));
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart(boundary, parts))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new SpeechToTextException("whisper-server returned HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new SpeechToTextException("failed to request whisper-server at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechToTextException("interrupted while requesting whisper-server at " + endpoint, e);
        }
    }

    static Transcription parseWebVtt(String body) {
        List<TranscriptSegment> segments = new ArrayList<>();
        List<String> lines = body.lines()
                .map(String::stripTrailing)
                .toList();

        for (int i = 0; i < lines.size(); i++) {
            var matcher = VTT_TIMING.matcher(lines.get(i));
            if (!matcher.matches()) {
                continue;
            }

            Duration start = parseWebVttTimestamp(matcher.group(1));
            Duration end = parseWebVttTimestamp(matcher.group(2));
            StringBuilder text = new StringBuilder();
            i++;
            while (i < lines.size() && !lines.get(i).isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(lines.get(i));
                i++;
            }

            String segmentText = text.toString().trim();
            if (!segmentText.isEmpty()) {
                segments.add(new TranscriptSegment(start, end, segmentText));
            }
        }

        String text = segments.stream()
                .map(TranscriptSegment::text)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new Transcription(text, segments);
    }

    private static Duration parseWebVttTimestamp(String value) {
        String[] hourAndRest = value.split(":", 3);
        if (hourAndRest.length != 3) {
            throw new SpeechToTextException("invalid WEBVTT timestamp: " + value);
        }
        String[] secondAndMillis = hourAndRest[2].split("\\.", 2);
        if (secondAndMillis.length != 2) {
            throw new SpeechToTextException("invalid WEBVTT timestamp: " + value);
        }
        return Duration.ofHours(Long.parseLong(hourAndRest[0]))
                .plusMinutes(Long.parseLong(hourAndRest[1]))
                .plusSeconds(Long.parseLong(secondAndMillis[0]))
                .plusMillis(Long.parseLong(secondAndMillis[1]));
    }

    private static byte[] wav(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        if (!audioBuffer.contains(startSampleIndex, endSampleIndexExclusive)) {
            throw new SpeechToTextException("audio range is no longer available");
        }

        int samples = Math.toIntExact(endSampleIndexExclusive - startSampleIndex);
        int dataBytes = samples * Short.BYTES;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataBytes);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) CHANNELS);
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);
        header.putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8));
        header.putShort((short) BITS_PER_SAMPLE);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataBytes);

        ByteBuffer body = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        body.put(header.array());
        for (long sampleIndex = startSampleIndex; sampleIndex < endSampleIndexExclusive; sampleIndex++) {
            body.putShort(audioBuffer.sampleAt(sampleIndex));
        }
        return body.array();
    }

    private static HttpRequest.BodyPublisher multipart(String boundary, List<FormPart> parts) {
        List<byte[]> body = new ArrayList<>();
        byte[] newline = "\r\n".getBytes(StandardCharsets.UTF_8);
        for (FormPart part : parts) {
            body.add(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.add(part.header().getBytes(StandardCharsets.UTF_8));
            body.add(newline);
            body.add(part.body());
            body.add(newline);
        }
        body.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArrays(body);
    }

    private record FormPart(String name, String filename, String contentType, byte[] body) {
        static FormPart field(String name, String value) {
            return new FormPart(name, null, null, value.getBytes(StandardCharsets.UTF_8));
        }

        static FormPart file(String name, String filename, String contentType, byte[] body) {
            return new FormPart(name, filename, contentType, body);
        }

        String header() {
            StringBuilder header = new StringBuilder("Content-Disposition: form-data; name=\"")
                    .append(name)
                    .append('"');
            if (filename != null) {
                header.append("; filename=\"").append(filename).append('"');
            }
            header.append("\r\n");
            if (contentType != null) {
                header.append("Content-Type: ").append(contentType).append("\r\n");
            }
            return header.toString();
        }
    }
}
