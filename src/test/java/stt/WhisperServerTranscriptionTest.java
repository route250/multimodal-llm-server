package stt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class WhisperServerTranscriptionTest {
    private static final URI DEFAULT_ENDPOINT = URI.create("http://localhost:8767/inference");
    private static final Path DEFAULT_AUDIO_FILE = Path.of("src/test/test-data/voice_mosimosi.wav");
    private static final Pattern JSON_TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    @Test
    void transcribesMosimosiAudioWithLocalWhisperServer() throws Exception {
        URI endpoint = URI.create(System.getProperty("whisper.server.url", DEFAULT_ENDPOINT.toString()));
        Path audioFile = Path.of(System.getProperty("whisper.audio.file", DEFAULT_AUDIO_FILE.toString()));

        assertTrue(Files.isRegularFile(audioFile), "Audio file not found: " + audioFile.toAbsolutePath());

        HttpResponse<String> response = transcribeOrSkip(endpoint, audioFile);
        assumeTrue(!isLfm2AudioServer(response),
                "lfm2-audio-server is running at " + endpoint + ", not whisper-server");
        assertTrue(response.statusCode() / 100 == 2,
                () -> "whisper-server returned HTTP " + response.statusCode() + "\n" + response.body());

        String text = extractText(response.body());
        System.out.println("endpoint: " + endpoint);
        System.out.println("audio: " + audioFile.toAbsolutePath());
        System.out.println("transcription: " + text);

        assertFalse(text.isBlank(), () -> "Transcription text is blank. Raw response:\n" + response.body());
    }

    private static boolean isLfm2AudioServer(HttpResponse<?> response) {
        return response.headers()
                .firstValue("Server")
                .map(value -> value.contains("lfm2-audio-server"))
                .orElse(false);
    }

    private static HttpResponse<String> transcribeOrSkip(URI endpoint, Path audioFile)
            throws InterruptedException {
        try {
            return transcribe(endpoint, audioFile);
        } catch (ConnectException e) {
            assumeTrue(false, "whisper-server is not running at " + endpoint);
            throw new AssertionError("unreachable");
        } catch (IOException e) {
            throw new AssertionError("Failed to request whisper-server at " + endpoint, e);
        }
    }

    private static HttpResponse<String> transcribe(URI endpoint, Path audioFile)
            throws IOException, InterruptedException {
        String boundary = "----java-whisper-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart(boundary, List.of(
                        FormPart.file("file", audioFile, "audio/wav"),
                        FormPart.field("temperature", "0.0"),
                        FormPart.field("response_format", "json"))))
                .build();

        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpRequest.BodyPublisher multipart(String boundary, List<FormPart> parts) throws IOException {
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

        static FormPart file(String name, Path file, String contentType) throws IOException {
            return new FormPart(name, file.getFileName().toString(), contentType, Files.readAllBytes(file));
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
