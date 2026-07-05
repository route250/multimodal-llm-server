package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import audio.AudioBuffer;
import audio.AudioDiagnostics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import llm.ChatMessage;
import llm.LanguageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import stt.SpeechToText;
import stt.Transcription;
import tts.AudioDelta;
import tts.TextToSpeech;
import vad.VadAudioProcessor;
import vad.VadAudioProcessor.SpeechState;
import vad.VadAudioProcessor.SpeechStateChange;

@Timeout(5)
class ChatClientAudioProcessingTest {
    private static final String PCM16LE = "audio/pcm; rate=16000; channels=1; format=s16le";
    private static final String PCM_VAD = "audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256";

    @Test
    void audioRequestReturnsBeforeAudioProcessorCompletes() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            BlockingAudioProcessor processor = new BlockingAudioProcessor();
            ChatClient client = new ChatClient("client-1", group, processor);

            client.handle(audioRequest(new byte[]{0, 0}));

            assertTrue(processor.started.await(1, TimeUnit.SECONDS));
            assertFalse(processor.finished.await(50, TimeUnit.MILLISECONDS));

            processor.release();
            assertTrue(processor.finished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void audioChunksAreProcessedInRequestOrder() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingAudioProcessor processor = new RecordingAudioProcessor();
            ChatClient client = new ChatClient("client-1", group, processor);

            client.handle(audioRequest(new byte[]{1, 0}));
            client.handle(audioRequest(new byte[]{2, 0}));
            client.handle(audioRequest(new byte[]{3, 0}));

            assertTrue(processor.awaitCalls(3));
            assertEquals(List.of(1, 2, 3), processor.firstBytes());
        }
    }

    @Test
    void frontendEnablesBrowserEchoCancellationAndLocalTenVadPause() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/html/index.html"));

        assertTrue(html.contains("echoCancellation: true"));
        assertTrue(html.contains("noiseSuppression: true"));
        assertTrue(html.contains("localPauseVadThreshold = 50"));
        assertTrue(html.contains("localPauseConsecutiveVadFrames = 3"));
        assertTrue(html.contains("localResumeVadThreshold = 35"));
        assertTrue(html.contains("localResumeSilenceVadFrames = 8"));
        assertTrue(html.contains("let localVadPlaybackPaused = false"));
        assertTrue(html.contains("let serverSttPlaybackPaused = false"));
        assertTrue(html.contains("return localVadPlaybackPaused || serverSttPlaybackPaused"));
        assertTrue(html.contains("audio-control-ignored-without-assistant-turn"));
        assertTrue(html.contains("X-Client-Mic-Start-Sample"));
        assertTrue(html.contains("/chat/playback"));
        assertTrue(html.contains("import createVADModule from \"/tenvad/ten_vad.js\""));
        assertTrue(html.contains("audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256"));
        assertTrue(html.contains("tenVadHopSamples = 256"));
        assertTrue(html.contains("const playbackFlag = (currentPlayback || pausedPlayback) ? 0x80 : 0"));
        assertTrue(html.contains("vadBytes[frameIndex] = (value & 0x7f) | playbackFlag"));
        assertTrue(html.contains("const speechValue = vadValue & 0x7f"));
        assertTrue(html.contains("analyzeLocalTenVad(vadBytes)"));
        assertFalse(html.contains("let playbackPaused"));
    }

    @Test
    void audioPcmWithoutBrowserVadIsRejected() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient client = new ChatClient("client-1", group, new RecordingBodyAudioProcessor());

            HttpRequestException error = assertThrows(
                    HttpRequestException.class,
                    () -> client.handleAudio(ChatRequest.from(PCM16LE, new byte[]{0, 0}), 0, 1));

            assertEquals(400, error.status());
        }
    }

    @Test
    void activePlaybackRangeIsKeptBeforeAudioProcessorReceivesPcm() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingBodyAudioProcessor processor = new RecordingBodyAudioProcessor();
            ChatClient client = new ChatClient("client-1", group, processor);

            client.handlePlayback(new ChatClient.PlaybackEvent(1, "start", 0));
            client.handleAudio(audioRequest(new byte[]{1, 0, 2, 0, 3, 0}), 0, 3);

            assertTrue(processor.awaitCalls(1));
            assertEquals(List.of(1, 0, 2, 0, 3, 0), processor.receivedBytes());
        }
    }

    @Test
    void browserVadBytesArePassedToAudioProcessor() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingBodyAudioProcessor processor = new RecordingBodyAudioProcessor();
            ChatClient client = new ChatClient("client-1", group, processor);

            client.handle(audioRequest(new byte[VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES], new byte[] {(byte) 0x80}));

            assertTrue(processor.awaitCalls(1));
            assertEquals(List.of(0x80), processor.receivedVadBytes());
        }
    }

    @Test
    void audioChunkProcessingIsWrittenToDiagnosticsLog() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingBodyAudioProcessor processor = new RecordingBodyAudioProcessor();
            String sessionId = "processor-log-" + System.nanoTime();
            ChatClient client = new ChatClient(sessionId, group, processor);

            client.handleAudio(audioRequest(new byte[]{1, 0, 2, 0}), 10, 12);

            assertTrue(processor.awaitCalls(1));
            String log = Files.readString(AudioDiagnostics.logFile());
            assertTrue(log.contains("\"event\":\"audio-chunk-process\""));
            assertTrue(log.contains("\"groupId\":\"group-test\""));
            assertTrue(log.contains("\"sessionId\":\"" + sessionId + "\""));
            assertTrue(log.contains("\"pcmBytes\":4"));
            assertTrue(log.contains("\"startSampleIndex\":10"));
            assertTrue(log.contains("\"endSampleIndexExclusive\":12"));
        }
    }

    @Test
    void transcriptIsPublishedAfterAsyncAudioProcessing() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient client = group.join("client-1");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("hello"),
                    text -> "llm response: " + text);
            drainJoinEvents(client);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent delta = pollUntil(client, event -> "assistant-audio-chunk".equals(event.type()));
            ServerEvent done = pollUntil(client, event -> "message-done".equals(event.type()));
            assertNotNull(delta);
            assertNotNull(done);
            assertEquals("assistant-audio-chunk", delta.type());
            assertTrue(delta.message().contains("\"text\":"));
            assertEquals("message-done", done.type());
        }
    }

    @Test
    void firstSttStartWithoutAssistantTurnDoesNotPauseBrowserPlayback() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new SttStartedThenTranscriptAudioProcessor(
                            "最初の発話",
                            VadAudioProcessor.TranscriptionKind.FINAL),
                    new StreamingLanguageModel("はい？"));
            drainJoinEvents(listener);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            assertNotNull(pollUntil(listener, event -> event.message().contains("\"text\":\"はい？\"")));
            assertNotNull(pollUntil(listener, event -> "message-done".equals(event.type())));
            assertFalse(containsMessageFragment(listener, "\"assistantTurnId\":0"));
            assertFalse(containsMessageFragment(listener, "\"reason\":\"stt-wait\""));
        }
    }

    @Test
    void llmDeltasAreSynthesizedAndPublishedAsAudioDeltas() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingTextToSpeech textToSpeech = new RecordingTextToSpeech();
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("hello"),
                    new StreamingLanguageModel("こんにちは。", "次です"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent chunk1 = pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type()));
            ServerEvent chunk2 = pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type()));
            ServerEvent done = pollUntil(listener, event -> "message-done".equals(event.type()));
            assertNotNull(chunk1);
            assertNotNull(chunk2);
            assertNotNull(done);
            assertEquals("assistant-audio-chunk", chunk1.type());
            assertTrue(chunk1.message().contains("\"text\":\"こんにちは。\""));
            assertTrue(chunk1.message().contains("\"data\":\"AAAA\""));
            assertTrue(chunk1.message().contains("\"sampleRate\":24000"));
            assertTrue(chunk1.message().contains("\"chunkId\":1"));
            assertEquals("assistant-audio-chunk", chunk2.type());
            assertTrue(chunk2.message().contains("\"text\":\"次です\""));
            assertTrue(chunk2.message().contains("\"chunkId\":2"));
            assertEquals("message-done", done.type());
            assertEquals(List.of("こんにちは。", "次です"), textToSpeech.texts);
        }
    }

    @Test
    void textRequestStartsLlmAndTtsTurn() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingTextToSpeech textToSpeech = new RecordingTextToSpeech();
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    new StreamingLanguageModel("テキスト応答。"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "こんにちは".getBytes()));

            ServerEvent chunk = pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type()));
            ServerEvent done = pollUntil(listener, event -> "message-done".equals(event.type()));
            assertNotNull(chunk);
            assertNotNull(done);
            assertEquals("assistant-audio-chunk", chunk.type());
            assertTrue(chunk.message().contains("\"text\":\"テキスト応答。\""));
            assertTrue(chunk.message().contains("\"audioDeltas\":[{\"data\":\"AAAA\""));
            assertEquals("message-done", done.type());
            assertEquals(List.of("テキスト応答。"), textToSpeech.texts);
        }
    }

    @Test
    void vadDetectionDoesNotPublishAudioPauseControl() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            StateChangeAudioProcessor processor = new StateChangeAudioProcessor(
                    VadAudioProcessor.SpeechState.UNDETECTED,
                    VadAudioProcessor.SpeechState.DETECTED);
            ChatClient processorClient = new ChatClient("processor", group, processor);
            drainJoinEvents(listener);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent event = listener.events().poll(100, TimeUnit.MILLISECONDS);
            assertFalse(event != null && event.message().contains("\"reason\":\"vad\""));
        }
    }

    @Test
    void vadStateChangePublishesSpeechStateEvent() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            StateChangeAudioProcessor processor = new StateChangeAudioProcessor(
                    VadAudioProcessor.SpeechState.UNDETECTED,
                    VadAudioProcessor.SpeechState.DETECTED);
            ChatClient processorClient = new ChatClient("processor", group, processor);
            drainJoinEvents(listener);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent event = pollUntil(listener, item -> "speech-state".equals(item.type()));
            assertNotNull(event);
            assertTrue(event.message().contains("\"previousState\":\"UNDETECTED\""));
            assertTrue(event.message().contains("\"currentState\":\"DETECTED\""));
            assertTrue(event.message().contains("\"speechSequenceId\":1"));
        }
    }

    @Test
    void sttStartPausesAndEmptyFinalResumesWithoutCallingLlm() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            BlockingTextToSpeech textToSpeech = new BlockingTextToSpeech();
            SttStartedThenTranscriptAudioProcessor processor = new SttStartedThenTranscriptAudioProcessor(
                    "",
                    VadAudioProcessor.TranscriptionKind.FINAL);
            CountingLanguageModel languageModel = new CountingLanguageModel("応答。");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    processor,
                    languageModel,
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "開始".getBytes()));
            assertTrue(textToSpeech.started.await(1, TimeUnit.SECONDS));

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent pause = pollUntil(listener, event -> event.message().contains("\"reason\":\"stt-wait\""));
            ServerEvent resume = pollUntil(listener, event -> event.message().contains("\"reason\":\"empty-stt\""));
            assertNotNull(pause);
            assertNotNull(resume);
            assertTrue(pause.message().contains("\"action\":\"pause\""));
            assertTrue(resume.message().contains("\"action\":\"resume\""));
            assertEquals(1, languageModel.calls);
            textToSpeech.release();
        }
    }

    @Test
    void transcriptCancelsActiveAssistantAudioAndDropsLateTtsDelta() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            BlockingTextToSpeech textToSpeech = new BlockingTextToSpeech();
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("interrupt"),
                    new StreamingLanguageModel("長い応答。"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "開始".getBytes()));
            assertTrue(textToSpeech.started.await(1, TimeUnit.SECONDS));

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            ServerEvent cancel = pollUntil(listener, event -> event.message().contains("\"action\":\"cancel\""));
            assertNotNull(cancel);
            assertEquals("audio-control", cancel.type());
            assertTrue(cancel.message().contains("\"action\":\"cancel\""));

            textToSpeech.release();
            Thread.sleep(100);
            assertFalse(containsMessageFragment(listener, "\"assistantTurnId\":1"));
        }
    }

    @Test
    void consecutiveNonEmptySttResultsKeepOnlyLatestAssistantTurn() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            QueuedSttStartedTranscriptAudioProcessor processor = new QueuedSttStartedTranscriptAudioProcessor(
                    "最初の割り込み",
                    "次の割り込み");
            RecordingConversationLanguageModel languageModel = new RecordingConversationLanguageModel(
                    "初期応答。",
                    "最初の応答。",
                    "次の応答。");
            BlockingTextToSpeech textToSpeech = new BlockingTextToSpeech();
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    processor,
                    languageModel,
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "開始".getBytes()));
            assertTrue(textToSpeech.started.await(1, TimeUnit.SECONDS));

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            ServerEvent firstCancel = pollUntil(listener, event -> event.message().contains("\"assistantTurnId\":1")
                    && event.message().contains("\"action\":\"cancel\""));
            assertNotNull(firstCancel);

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            ServerEvent secondCancel = pollUntil(listener, event -> event.message().contains("\"assistantTurnId\":2")
                    && event.message().contains("\"action\":\"cancel\""));
            assertNotNull(secondCancel);

            textToSpeech.release();
            assertNotNull(pollUntil(listener, event -> event.message().contains("\"text\":\"次の応答。\"")));
            assertFalse(containsAudioDeltaForTurn(listener, 1));
            assertFalse(containsAudioDeltaForTurn(listener, 2));
            assertEquals(3, languageModel.calls.size());
            assertEquals("user:次の割り込み", languageModel.calls.get(2).getLast());
        }
    }

    @Test
    void vadDetectionDuringAssistantTurnDoesNotSkipSpeechStartForStt() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            BlockingTextToSpeech textToSpeech = new BlockingTextToSpeech();
            StateChangeAudioProcessor processor = new StateChangeAudioProcessor(
                    VadAudioProcessor.SpeechState.UNDETECTED,
                    VadAudioProcessor.SpeechState.DETECTED);
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    processor,
                    new StreamingLanguageModel("応答。"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "開始".getBytes()));
            assertTrue(textToSpeech.started.await(1, TimeUnit.SECONDS));

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            assertFalse(containsMessageFragment(listener, "\"reason\":\"vad\""));

            assertEquals(0, processor.ignoredBeforeSampleIndex);
            textToSpeech.release();
        }
    }

    @Test
    void textTurnsSendConversationHistoryToLanguageModel() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingConversationLanguageModel languageModel = new RecordingConversationLanguageModel("太郎です", "太郎です");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel,
                    TextToSpeech.disabled());
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "私の名前は太郎です".getBytes()));
            assertNotNull(pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type())));
            processorClient.handlePlayback(new ChatClient.PlaybackEvent(1, 1, "end", true, 0, 0, 0));
            assertNotNull(pollUntil(listener, event -> "message-done".equals(event.type())));
            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "私の名前は何ですか".getBytes()));
            assertNotNull(pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type())));
            assertNotNull(pollUntil(listener, event -> "message-done".equals(event.type())));

            assertEquals(2, languageModel.calls.size());
            assertEquals(List.of("user:私の名前は太郎です"), languageModel.calls.get(0));
            assertEquals(List.of(
                    "user:私の名前は太郎です",
                    "assistant:太郎です",
                    "user:私の名前は何ですか"), languageModel.calls.get(1));
        }
    }

    @Test
    void assistantChunkIsNotSentToHistoryBeforePlaybackRecognition() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingConversationLanguageModel languageModel = new RecordingConversationLanguageModel("太郎です", "太郎です");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel,
                    new RecordingTextToSpeech());
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "私の名前は太郎です".getBytes()));
            assertNotNull(pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type())));
            assertNotNull(pollUntil(listener, event -> "message-done".equals(event.type())));
            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "私の名前は何ですか".getBytes()));
            assertNotNull(pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type())));

            assertEquals(2, languageModel.calls.size());
            assertEquals(List.of("user:私の名前は太郎です"), languageModel.calls.get(0));
            assertEquals(List.of("user:私の名前は何ですか"), languageModel.calls.get(1));
        }
    }

    @Test
    void recognizedAssistantChunkIsSentToNextLlmHistory() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingConversationLanguageModel languageModel = new RecordingConversationLanguageModel("太郎です", "太郎です");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel,
                    new RecordingTextToSpeech());
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "私の名前は太郎です".getBytes()));
            ServerEvent chunk = pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type()));
            assertNotNull(chunk);
            processorClient.handlePlayback(new ChatClient.PlaybackEvent(1, 1, "end", true, 0.4, 1.0, 0));
            assertNotNull(pollUntil(listener, event -> "message-done".equals(event.type())));

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "私の名前は何ですか".getBytes()));
            assertNotNull(pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type())));

            assertEquals(2, languageModel.calls.size());
            assertEquals(List.of(
                    "user:私の名前は太郎です",
                    "assistant:太郎です",
                    "user:私の名前は何ですか"), languageModel.calls.get(1));
        }
    }

    @Test
    void markdownSymbolPrefixIsMergedIntoNextSpokenChunk() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingTextToSpeech textToSpeech = new RecordingTextToSpeech();
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    new StreamingLanguageModel("---\n", "本文。"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "開始".getBytes()));

            ServerEvent chunk = pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type()));
            assertNotNull(chunk);
            assertTrue(chunk.message().contains("---\\n本文。"));
            assertEquals(List.of("---\n本文。"), textToSpeech.texts);
        }
    }

    @Test
    void trailingTextOnlyChunkIsPublishedAndRemembered() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingConversationLanguageModel languageModel = new RecordingConversationLanguageModel(
                    "本文です。```",
                    "次です。");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel,
                    new RecordingTextToSpeech());
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "開始".getBytes()));
            ServerEvent spoken = pollUntil(listener, event -> event.message().contains("\"text\":\"本文です。\""));
            ServerEvent textOnly = pollUntil(listener, event -> event.message().contains("\"text\":\"```\""));
            assertNotNull(spoken);
            assertNotNull(textOnly);
            assertTrue(textOnly.message().contains("\"audioDeltas\":[]"));
            processorClient.handlePlayback(new ChatClient.PlaybackEvent(1, 2, "end", true, 0, 0, 0));
            assertNotNull(pollUntil(listener, event -> "message-done".equals(event.type())));

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "次".getBytes()));
            assertNotNull(pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type())));

            assertEquals(2, languageModel.calls.size());
            assertTrue(languageModel.calls.get(1).contains("assistant:```"));
        }
    }

    @Test
    void asyncAudioFailureIsPublishedAndNextChunkContinues() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient client = group.join("client-1");
            FailingThenTranscriptAudioProcessor processor = new FailingThenTranscriptAudioProcessor();
            ChatClient processorClient = new ChatClient("processor", group, processor, text -> "llm response: " + text);
            drainJoinEvents(client);

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            processorClient.handle(audioRequest(new byte[]{1, 0}));

            ServerEvent failure = pollUntil(client, event -> "system".equals(event.type()));
            ServerEvent delta = pollUntil(client, event -> "assistant-audio-chunk".equals(event.type()));
            ServerEvent done = pollUntil(client, event -> "message-done".equals(event.type()));
            assertNotNull(failure);
            assertNotNull(delta);
            assertNotNull(done);
            assertEquals("system", failure.type());
            assertEquals("audio processing failed: boom", failure.message());
            assertEquals("assistant-audio-chunk", delta.type());
            assertTrue(delta.message().contains("\"text\":"));
            assertEquals("message-done", done.type());
        }
    }

    @Test
    void closedClientDoesNotPublishPendingAudioResult() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("listener");
            BlockingAudioProcessor processor = new BlockingAudioProcessor("late result");
            CountingLanguageModel languageModel = new CountingLanguageModel();
            ChatClient processorClient = group.join("processor", processor, languageModel);

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            assertTrue(processor.started.await(1, TimeUnit.SECONDS));

            group.leave(processorClient);
            processor.release();

            assertTrue(processor.finished.await(1, TimeUnit.SECONDS));
            drainJoinEvents(listener);
            assertFalse(containsMessage(listener, "late result"));
            assertEquals(0, languageModel.calls);
        }
    }

    private static ChatRequest audioRequest(byte[] body) {
        byte[] vadBytes = new byte[body.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)];
        return audioRequest(body, vadBytes);
    }

    private static ChatRequest audioRequest(byte[] body, byte[] vadBytes) {
        return ChatRequest.from(PCM_VAD, pcmVadBody(body, vadBytes));
    }

    private static byte[] pcmVadBody(byte[] pcm, byte[] vadBytes) {
        ByteBuffer buffer = ByteBuffer
                .allocate(32 + pcm.length + vadBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'M');
        buffer.put((byte) 'V');
        buffer.put((byte) 'A');
        buffer.put((byte) 'D');
        buffer.putShort((short) 1);
        buffer.putShort((short) 0);
        buffer.putInt(16_000);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(pcm.length / Short.BYTES);
        buffer.putInt(VadAudioProcessor.VAD_FRAME_SAMPLES);
        buffer.putInt(vadBytes.length);
        buffer.putInt(0);
        buffer.put(pcm);
        buffer.put(vadBytes);
        return buffer.array();
    }

    private static void drainJoinEvents(ChatClient client) {
        client.events().removeIf(event -> event.message().contains(" joined "));
    }

    private static boolean containsMessage(ChatClient client, String message) {
        return client.events().stream().anyMatch(event -> message.equals(event.message()));
    }

    private static boolean containsType(ChatClient client, String type) {
        return client.events().stream().anyMatch(event -> type.equals(event.type()));
    }

    private static boolean containsMessageFragment(ChatClient client, String fragment) {
        return client.events().stream().anyMatch(event -> event.message().contains(fragment));
    }

    private static boolean containsAudioDeltaForTurn(ChatClient client, long assistantTurnId) {
        String fragment = "\"assistantTurnId\":" + assistantTurnId;
        return client.events().stream()
                .anyMatch(event -> "audio-delta".equals(event.type()) && event.message().contains(fragment));
    }

    private static ServerEvent pollUntil(ChatClient client, Predicate<ServerEvent> predicate) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            ServerEvent event = client.events().poll(50, TimeUnit.MILLISECONDS);
            if (event != null && predicate.test(event)) {
                return event;
            }
        }
        return null;
    }

    private static SpeechToText emptySpeechToText() {
        return new SpeechToText() {
            @Override
            public Transcription transcribe(
                    AudioBuffer audioBuffer,
                    long startSampleIndex,
                    long endSampleIndexExclusive,
                    String prompt) {
                return Transcription.empty();
            }
        };
    }

    private static class RecordingAudioProcessor extends VadAudioProcessor {
        private final List<Integer> firstBytes = new ArrayList<>();
        private final CountDownLatch calls = new CountDownLatch(3);

        RecordingAudioProcessor() {
            super(samples -> true, emptySpeechToText(), Runnable::run);
        }

        @Override
        public synchronized Optional<Transcription> acceptPcm16Le(byte[] bytes) {
            return acceptPcm16LeWithVadDetailed(bytes, new byte[bytes.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)]).map(VadAudioProcessor.TranscriptionResult::transcription);
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            firstBytes.add(bytes[0] & 0xff);
            calls.countDown();
            return Optional.empty();
        }

        boolean awaitCalls(int expected) throws InterruptedException {
            return calls.await(1, TimeUnit.SECONDS) && firstBytes.size() == expected;
        }

        List<Integer> firstBytes() {
            return List.copyOf(firstBytes);
        }
    }

    private static class RecordingBodyAudioProcessor extends VadAudioProcessor {
        private final CountDownLatch calls = new CountDownLatch(1);
        private byte[] receivedBytes = new byte[0];
        private byte[] receivedVadBytes = new byte[0];

        RecordingBodyAudioProcessor() {
            super(samples -> true, emptySpeechToText(), Runnable::run);
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            receivedBytes = bytes.clone();
            receivedVadBytes = vadBytes.clone();
            calls.countDown();
            return Optional.empty();
        }

        boolean awaitCalls(int expected) throws InterruptedException {
            return calls.await(1, TimeUnit.SECONDS) && expected == 1;
        }

        List<Integer> receivedBytes() {
            return Arrays.stream(toUnsignedInts(receivedBytes)).boxed().toList();
        }

        List<Integer> receivedVadBytes() {
            return Arrays.stream(toUnsignedInts(receivedVadBytes)).boxed().toList();
        }

        private static int[] toUnsignedInts(byte[] bytes) {
            int[] values = new int[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                values[i] = bytes[i] & 0xff;
            }
            return values;
        }
    }

    private static class StateChangeAudioProcessor extends VadAudioProcessor {
        private final SpeechState previousState;
        private final SpeechState currentState;
        private long ignoredBeforeSampleIndex;
        private java.util.function.Consumer<SpeechStateChange> listener = change -> {
        };

        StateChangeAudioProcessor(SpeechState previousState, SpeechState currentState) {
            super(samples -> true, emptySpeechToText(), Runnable::run);
            this.previousState = previousState;
            this.currentState = currentState;
        }

        @Override
        public synchronized void setSpeechStateListener(java.util.function.Consumer<SpeechStateChange> listener) {
            this.listener = listener == null ? change -> {
            } : listener;
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            listener.accept(new SpeechStateChange(previousState, currentState, 1, 0));
            return Optional.empty();
        }

        @Override
        public synchronized void ignoreTranscriptionBefore(long sampleIndex) {
            ignoredBeforeSampleIndex = sampleIndex;
        }
    }

    private static class SttStartedThenTranscriptAudioProcessor extends VadAudioProcessor {
        private final String transcript;
        private final TranscriptionKind kind;
        private Consumer<VadAudioProcessor.TranscriptionStarted> listener = started -> {
        };

        SttStartedThenTranscriptAudioProcessor(String transcript, TranscriptionKind kind) {
            super(samples -> true, emptySpeechToText(), Runnable::run);
            this.transcript = transcript;
            this.kind = kind;
        }

        @Override
        public synchronized void setTranscriptionStartedListener(Consumer<VadAudioProcessor.TranscriptionStarted> listener) {
            this.listener = listener == null ? started -> {
            } : listener;
        }

        @Override
        public Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            listener.accept(new VadAudioProcessor.TranscriptionStarted(1, 0, 0, kind));
            Transcription transcription = transcript.isBlank()
                    ? Transcription.empty()
                    : Transcription.singleSegment(transcript, 0, 16_000);
            return Optional.of(new VadAudioProcessor.TranscriptionResult(
                    1,
                    0,
                    0,
                    kind,
                    transcription));
        }
    }

    private static class QueuedSttStartedTranscriptAudioProcessor extends VadAudioProcessor {
        private final List<String> transcripts;
        private int nextTranscript;
        private long nextSpeechSequenceId = 1;
        private Consumer<VadAudioProcessor.TranscriptionStarted> listener = started -> {
        };

        QueuedSttStartedTranscriptAudioProcessor(String... transcripts) {
            super(samples -> true, emptySpeechToText(), Runnable::run);
            this.transcripts = List.of(transcripts);
        }

        @Override
        public synchronized void setTranscriptionStartedListener(Consumer<VadAudioProcessor.TranscriptionStarted> listener) {
            this.listener = listener == null ? started -> {
            } : listener;
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(
                byte[] bytes,
                byte[] vadBytes) {
            long speechSequenceId = nextSpeechSequenceId++;
            String transcript = transcripts.get(nextTranscript++);
            listener.accept(new VadAudioProcessor.TranscriptionStarted(
                    speechSequenceId,
                    0,
                    0,
                    VadAudioProcessor.TranscriptionKind.PARTIAL));
            return Optional.of(new VadAudioProcessor.TranscriptionResult(
                    speechSequenceId,
                    0,
                    0,
                    VadAudioProcessor.TranscriptionKind.PARTIAL,
                    Transcription.singleSegment(transcript, 0, 16_000)));
        }
    }

    private static class TranscriptAudioProcessor extends VadAudioProcessor {
        private final String transcript;

        TranscriptAudioProcessor(String transcript) {
            super(samples -> true, emptySpeechToText(), Runnable::run);
            this.transcript = transcript;
        }

        @Override
        public Optional<Transcription> acceptPcm16Le(byte[] bytes) {
            return acceptPcm16LeWithVadDetailed(bytes, new byte[bytes.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)]).map(VadAudioProcessor.TranscriptionResult::transcription);
        }

        @Override
        public Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            Transcription transcription = Transcription.singleSegment(transcript, 0, 16_000);
            return Optional.of(new VadAudioProcessor.TranscriptionResult(
                    1,
                    0,
                    0,
                    VadAudioProcessor.TranscriptionKind.FINAL,
                    transcription));
        }
    }

    private static class FailingThenTranscriptAudioProcessor extends VadAudioProcessor {
        private int calls;

        FailingThenTranscriptAudioProcessor() {
            super(samples -> true, emptySpeechToText(), Runnable::run);
        }

        @Override
        public synchronized Optional<Transcription> acceptPcm16Le(byte[] bytes) {
            return acceptPcm16LeWithVadDetailed(bytes, new byte[bytes.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)]).map(VadAudioProcessor.TranscriptionResult::transcription);
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            calls++;
            if (calls == 1) {
                throw new IllegalArgumentException("boom");
            }
            return Optional.of(new VadAudioProcessor.TranscriptionResult(
                    1,
                    0,
                    0,
                    VadAudioProcessor.TranscriptionKind.FINAL,
                    Transcription.singleSegment("after failure", 0, 16_000)));
        }
    }

    private static class BlockingAudioProcessor extends VadAudioProcessor {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final String transcript;

        BlockingAudioProcessor() {
            this(null);
        }

        BlockingAudioProcessor(String transcript) {
            super(samples -> true, emptySpeechToText(), Runnable::run);
            this.transcript = transcript;
        }

        @Override
        public Optional<Transcription> acceptPcm16Le(byte[] bytes) {
            return acceptPcm16LeWithVadDetailed(bytes, new byte[bytes.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)]).map(VadAudioProcessor.TranscriptionResult::transcription);
        }

        @Override
        public Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                return Optional.ofNullable(transcript)
                        .map(value -> new VadAudioProcessor.TranscriptionResult(
                                1,
                                0,
                                0,
                                VadAudioProcessor.TranscriptionKind.FINAL,
                                Transcription.singleSegment(value, 0, 16_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } finally {
                finished.countDown();
            }
        }

        void release() {
            release.countDown();
        }
    }

    private static class CountingLanguageModel implements LanguageModel {
        private final String response;
        private int calls;

        CountingLanguageModel() {
            this("");
        }

        CountingLanguageModel(String response) {
            this.response = response;
        }

        @Override
        public String respond(String userText) {
            calls++;
            return response.isBlank() ? userText : response;
        }
    }

    private static class StreamingLanguageModel implements LanguageModel {
        private final List<String> deltas;

        StreamingLanguageModel(String... deltas) {
            this.deltas = List.of(deltas);
        }

        @Override
        public String respond(String userText) {
            return String.join("", deltas);
        }

        @Override
        public void respondStreaming(String userText, java.util.function.Consumer<String> onDelta) {
            deltas.forEach(onDelta);
        }
    }

    private static class RecordingConversationLanguageModel implements LanguageModel {
        private final List<String> responses;
        private final List<List<String>> calls = new ArrayList<>();
        private int nextResponse;

        RecordingConversationLanguageModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String respond(String userText) {
            return responses.get(nextResponse++);
        }

        @Override
        public void respondStreaming(List<ChatMessage> messages, java.util.function.Consumer<String> onDelta) {
            calls.add(messages.stream()
                    .map(message -> message.role() + ":" + message.text())
                    .toList());
            onDelta.accept(responses.get(nextResponse++));
        }
    }

    private static class RecordingTextToSpeech implements TextToSpeech {
        private final List<String> texts = new ArrayList<>();

        @Override
        public void synthesizeStreaming(String text, java.util.function.Consumer<AudioDelta> onDelta) {
            texts.add(text);
            onDelta.accept(new AudioDelta("AAAA", "pcm", 24000));
        }
    }

    private static class BlockingTextToSpeech implements TextToSpeech {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void synthesizeStreaming(String text, java.util.function.Consumer<AudioDelta> onDelta) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                onDelta.accept(new AudioDelta("AAAA", "pcm", 24000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void release() {
            release.countDown();
        }
    }
}
