package server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import audio.AudioProcessor;
import audio.tts.TextToSpeech;
import facedb.FaceDB;
import json.JsonFields;
import llm.LLM;
import llm.LlmOpenAI;

/**
 * 実際のローカル LLM を使い、顔認識から始まる ChatClient の複数ターン会話を検証します。
 */
class ChatClientLocalLlmFaceConversationTest {
    private static final int RETRY_COUNT = 10;
    private static final URI DEFAULT_BASE_URI = URI.create("http://127.0.0.1:8767/v1");
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(120);
    private static final String MODEL_PATTERN = "LFM2\\.5.*JP";
    private static final String UNKNOWN_BROWSER_TRACK_ID = "browser-track-unknown";
    private static final String KNOWN_BROWSER_TRACK_ID = "browser-track-known";

    public static boolean contains( String text, String key1, String key2 ) {
        return text!=null && (text.contains(key1) || text.contains(key2));
    }
    public static boolean contains( String text, String key1, String key2, String key3 ) {
        return text!=null && (text.contains(key1) || text.contains(key2) || text.contains(key3));
    }
    private static class Logger {
        private StringBuilder sb = new StringBuilder();
        private boolean hasError;
        public void println(String text) {
            sb.append(text).append('\n');
            System.err.println(text);
        }
        public void error(String error ) {
            hasError = true;
            sb.append("ERROR:").append(error).append('\n');
            System.err.println("ERROR:"+error);
        }
        public boolean hasError() {
            return hasError;
        }
        public String toString() {
            return sb.toString();
        }
    }
    @Test
    void unknownUserIsAskedNameThenRegisteredByToolAndConversationContinues(@TempDir Path tempDir)
            throws Exception {
        URI baseUri = localLlmBaseUri();
        assumeLocalLlmAvailable(baseUri);
        repeatTenTimes(tempDir, attempt -> {
            FaceDB faceDB = new FaceDB(tempDir);
            Logger logger = new Logger();
            try (MlServer server = new MlServer(0, faceDB)) {
                ChatGroup group = new ChatGroup("group-local-llm-test", server);
                ChatClient listener = group.join("listener");
                ChatClient client = newClient(group, baseUri);
                listener.events().clear();

                FaceEventResult result = client.handleFacePresence(
                        faceDB,
                        faceRequest("person-entered", UNKNOWN_BROWSER_TRACK_ID, descriptor(0.1)));
                assertFalse(result.known());

                String greeting = awaitAssistantTurn(client, listener);
                logger.println( "AI:"+greeting);
                if(contains(greeting,"trackId","track-0","trak-0")) {
                    logger.error("trackIdを出力しています");
                }
                if( !containsGreeting(greeting) ) {
                    logger.error("挨拶がありません" );
                }
                if( !greeting.contains("なまえ") && !greeting.contains("名前") ) {
                    logger.error("名前を聞いていません");
                }
                String inp = "私の名前は太郎です。";
                logger.println("User:"+inp);
                client.handle(textRequest(inp));
                String reply = awaitAssistantTurn(client, listener);
                logger.println("AI:"+reply);
                if( reply.isBlank() ) {
                    logger.error("名前登録後の会話応答がありません");
                } else {
                    Path x = tempDir.resolve("persons.json" );
                    if( !Files.isRegularFile(x) ) {
                        logger.error("名前が登録されていません");
                    }
                    String personsJson = Files.readString(tempDir.resolve("persons.json"), StandardCharsets.UTF_8);
                    if(!contains(personsJson,"\"name\":\"太郎\"","\"name\":\"たろう\"") ) {
                        logger.error("名前が登録されてません");
                    }
                    if(contains(reply,"trackId","track-0","trak-0")) {
                        logger.error("trackIdを出力しています");
                    }
                    if(!contains(reply,"太郎","たろう") ) {
                        logger.error("登録名を使って会話を継続していません");
                    }
                }
                assertFalse(logger.hasError(),logger.toString());
            }
        });
    }

    @Test
    void knownUserIsGreetedByNameWithoutBeingAskedNameThenConversationContinues(@TempDir Path tempDir)
            throws Exception {
        URI baseUri = localLlmBaseUri();
        assumeLocalLlmAvailable(baseUri);
        repeatTenTimes(tempDir, attempt -> {
            FaceDB faceDB = new FaceDB(tempDir);
            Logger logger = new Logger();
            try (MlServer server = new MlServer(0, faceDB)) {
                String registeredTrackId = faceDB.createTrackId();
                faceDB.register(registeredTrackId, descriptor(0.2), jpegDataUrl());
                faceDB.assign(registeredTrackId, "花子");

                ChatGroup group = new ChatGroup("group-local-llm-test", server);
                ChatClient listener = group.join("listener");
                ChatClient client = newClient(group, baseUri);
                listener.events().clear();

                FaceEventResult result = client.handleFacePresence(
                        faceDB,
                        faceRequest("person-entered", KNOWN_BROWSER_TRACK_ID, descriptor(0.2)));
                assertTrue(result.known());

                String greeting = awaitAssistantTurn(client, listener);
                logger.println("AI:"+greeting);
                if( greeting==null||greeting.isBlank() ) {
                    logger.error("挨拶がありません" );
                } else {
                    if( !containsGreeting(greeting)) {
                        logger.error("挨拶がありません");
                    }
                    if(!greeting.contains("花子")) {
                        logger.error("登録名を使っていません");
                    }
                    if(greeting.contains("名前")) {
                        logger.error("登録済みユーザーへ名前を尋ねています");
                    }
                    if(contains(greeting,"trackId","track-0","trak-0")) {
                        logger.error("trackIdを出力しています");
                    }
                }

                String inp = "今日は元気です。りりは元気ですか？";
                logger.println("User:"+inp);
                client.handle(textRequest(inp));
                String reply = awaitAssistantTurn(client, listener);
                logger.println("AI:"+reply);
                if( reply==null||reply.isBlank() ) {
                    logger.error("ユーザー回答後の会話応答がありません");
                } else {
                    Path x = tempDir.resolve("trak-000001").resolve("trak-000001.json");
                    if(Files.exists(x)) {
                        logger.error("登録済みユーザーに対して名前登録ツールを呼び出しています");
                    }
                    if(contains(reply,"trackId","track-0","trak-0")) {
                        logger.error("trackIdを出力しています");
                    }
                }
                assertFalse(logger.hasError(),logger.toString());
            }
        });
    }

    /** 10 回すべてに合格することを確認し、不合格の試行内容を標準出力へ記録します。 */
    private static void repeatTenTimes(Path tempDir, ThrowingAttempt attempt) throws Exception {
        List<String> failures = new ArrayList<>();
        for (int index = 1; index <= RETRY_COUNT; index++) {
            resetTempDir(tempDir);
            try {
                attempt.run(index);
            } catch (Throwable failure) {
                String detail = "試行 " + index + "/" + RETRY_COUNT + " の不合格:\n"
                        + failure;
                System.out.println(detail);
                //failure.printStackTrace(System.out);
                failures.add(detail);
            }
        }
        assertTrue(failures.isEmpty(), () -> String.join("\n\n", failures));
    }

    /** 試行ごとに顔データベースを初期状態から作り直します。 */
    private static void resetTempDir(Path tempDir) throws IOException {
        try (var files = Files.walk(tempDir)) {
            files.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(tempDir))
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }

    @FunctionalInterface
    private interface ThrowingAttempt {
        void run(int index) throws Exception;
    }

    private static ChatClient newClient(ChatGroup group, URI baseUri) {
        LLM llm = new LlmOpenAI(new LLM.Config(baseUri, MODEL_PATTERN, LLM_TIMEOUT, ""));
        return new ChatClient(
                "face-conversation-client",
                group,
                disabledAudioProcessor(),
                llm,
                ChatClient.DEFAULT_SYSTEM_PROMPT,
                TextToSpeech.disabled());
    }

    private static AudioProcessor disabledAudioProcessor() {
        return new AudioProcessor(
                samples -> false,
                (buffer, start, end, prompt) -> {
                    throw new AssertionError("このテストでは音声認識を実行しません");
                },
                Runnable::run);
    }

    private static FaceEventRequest faceRequest(String eventType, String trackId, double[] descriptor) {
        return new FaceEventRequest(eventType, trackId, descriptor, jpegDataUrl());
    }

    private static ChatRequest textRequest(String text) {
        return ChatRequest.from(
                "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    private static String awaitAssistantTurn(ChatClient client, ChatClient listener) throws InterruptedException {
        long timeoutAt = System.nanoTime() + LLM_TIMEOUT.toNanos();
        StringBuilder text = new StringBuilder();
        List<String> failures = new ArrayList<>();
        while (System.nanoTime() < timeoutAt) {
            ServerEvent event = listener.events().poll(100, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            if ("assistant-audio-chunk".equals(event.type())) {
                long assistantTurnId = JsonFields.longValue(event.message(), "assistantTurnId");
                long chunkId = JsonFields.longValue(event.message(), "chunkId");
                text.append(JsonFields.string(event.message(), "text"));
                client.handlePlayback(new ChatClient.PlaybackEvent(
                        assistantTurnId, chunkId, "end", true, 0, 0, 0));
            } else if ("system".equals(event.type()) && event.message().contains("failed")) {
                failures.add(event.message());
            } else if ("message-done".equals(event.type())) {
                assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
                return text.toString();
            }
        }
        throw new AssertionError("ローカルLLMの応答がタイムアウトしました: " + String.join("\n", failures));
    }

    private static boolean containsGreeting(String text) {
        return text.contains("こんにちは")
                || text.contains("こんばんは")
                || text.contains("おはよう")
                || text.contains("はじめまして")
                || text.contains("やあ")
                || text.contains("お会いできて嬉しい")
                || text.contains("会えて嬉しい");
    }

    private static double[] descriptor(double value) {
        double[] descriptor = new double[128];
        java.util.Arrays.fill(descriptor, value);
        return descriptor;
    }

    private static String jpegDataUrl() {
        byte[] jpeg = {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9};
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);
    }

    private static URI localLlmBaseUri() {
        return URI.create(System.getProperty("llama.cpp.base.url", DEFAULT_BASE_URI.toString()));
    }

    private static void assumeLocalLlmAvailable(URI baseUri) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUri, "models"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assumeTrue(response.statusCode() / 100 == 2,
                    () -> "ローカルLLMのmodelsエンドポイントがHTTP " + response.statusCode() + "を返しました");
            assumeTrue(response.body().contains("LFM2.5"),
                    () -> "LFM2.5モデルがありません: " + baseUri);
        } catch (ConnectException e) {
            assumeTrue(false, () -> "ローカルLLMが起動していません: " + baseUri);
        } catch (IOException e) {
            throw new AssertionError("ローカルLLMへの接続に失敗しました: " + baseUri, e);
        }
    }

    private static URI endpoint(URI baseUri, String path) {
        String text = baseUri.toString();
        if (text.endsWith("/" + path)) {
            return baseUri;
        }
        return URI.create(text.endsWith("/") ? text + path : text + "/" + path);
    }
}
