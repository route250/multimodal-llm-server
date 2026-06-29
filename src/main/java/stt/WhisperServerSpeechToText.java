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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WhisperServerSpeechToText implements SpeechToText {
    public static final URI DEFAULT_ENDPOINT = URI.create("http://localhost:8766/inference");
    private static final int SAMPLE_RATE = 16_000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final Pattern JSON_TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

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
        if (endSampleIndexExclusive <= startSampleIndex) {
            return "";
        }
        byte[] wav = wav(audioBuffer, startSampleIndex, endSampleIndexExclusive);
        String boundary = "----java-whisper-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart(boundary, List.of(
                        FormPart.file("file", "speech.wav", "audio/wav", wav),
                        FormPart.field("language", "ja"),
                        FormPart.field("temperature", "0.0"),
                        FormPart.field("response_format", "json"))))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new SpeechToTextException("whisper-server returned HTTP " + response.statusCode());
            }
            return extractText(response.body());
        } catch (IOException e) {
            throw new SpeechToTextException("failed to request whisper-server at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechToTextException("interrupted while requesting whisper-server at " + endpoint, e);
        }
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

    private static String extractText(String body) {
        Matcher matcher = JSON_TEXT.matcher(body);
        if (matcher.find()) {
            return unescapeJsonString(matcher.group(1)).trim();
        }
        return body.trim();
    }

    private static String unescapeJsonString(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                result.append(ch);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        result.append("\\u");
                        break;
                    }
                    String hex = value.substring(i + 1, i + 5);
                    result.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
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
