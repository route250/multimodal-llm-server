package server;

import audio.AudioBuffer;
import audio.stt.Lfm2AudioSpeechToText;
import audio.stt.SpeechToText;
import audio.tts.Lfm2AudioTextToSpeech;
import audio.tts.TextToSpeech;
import audio.vad.TurnDetector;
import audio.vad.smartturn.LazySmartTurnV3;
import audio.vad.smartturn.SmartTurnV3;
import java.util.concurrent.atomic.AtomicInteger;
import llm.LLM;
import llm.LlmOpenAI;
import model.download.SmartTurnV3ModelDownloader;

/** サーバ起動前に外部の音声・言語モデルを実リクエストで検査します。 */
final class StartupCheck {
    private static final int SAMPLE_RATE = 16_000;
    private static final String CHECK_TEXT = "起動確認です。短く応答してください。";

    private final SpeechToText speechToText;
    private final TextToSpeech textToSpeech;
    private final LLM llm;
    private final TurnDetector turnDetector;

    StartupCheck() {
        this(
                new Lfm2AudioSpeechToText(),
                new Lfm2AudioTextToSpeech(),
                new LlmOpenAI(),
                new LazySmartTurnV3(SmartTurnV3ModelDownloader.MODEL_PATH.toAbsolutePath().normalize()));
    }

    StartupCheck(
            SpeechToText speechToText,
            TextToSpeech textToSpeech,
            LLM llm,
            TurnDetector turnDetector) {
        this.speechToText = speechToText;
        this.textToSpeech = textToSpeech;
        this.llm = llm;
        this.turnDetector = turnDetector;
    }

    /** 本番処理で使用する各クライアントの基本メソッドを順番に呼び出します。 */
    void verify() {
        verifySmartTurn();
        verifyStt();
        verifyTts();
        verifyLlm();
    }

    private void verifySmartTurn() {

        long start = 0;
        long end = 0;
        try {
            float[] samples = new float[SmartTurnV3.WINDOW_SECONDS];
            for( int i=1; i<samples.length; i+=4) {samples[i]=0.5f;}
            for( int i=3; i<samples.length; i+=4) {samples[i]=-0.5f;}
            for( int i=0; i<5; i++ ) {
                if( i==2 ) start = System.currentTimeMillis();
                turnDetector.isTurnComplete(samples);
            }
        } finally {
            end = System.currentTimeMillis();
            if (turnDetector instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    throw new IllegalStateException("failed to close SmartTurn startup check", e);
                }
            }
        }
        long elaps = (end-start)/3;
        System.out.println("Startup check passed: SmartTurn Avg."+elaps+"(ms)");
    }

    private void verifyStt() {
        AudioBuffer silence = new AudioBuffer(SAMPLE_RATE, SAMPLE_RATE);
        silence.append(new short[SAMPLE_RATE], 0, new byte[] {0}, new byte[] {0});
        speechToText.transcribe(silence, 0, SAMPLE_RATE, "");
        System.out.println("Startup check passed: STT");
    }

    private void verifyTts() {
        AtomicInteger deltaCount = new AtomicInteger();
        textToSpeech.synthesizeStreaming(CHECK_TEXT, delta -> deltaCount.incrementAndGet());
        if (deltaCount.get() == 0) {
            throw new IllegalStateException("TTS startup check returned no audio");
        }
        System.out.println("Startup check passed: TTS");
    }

    private void verifyLlm() {
        LLM.Verification response = llm.verify();
        if (response == null || response.response().isBlank()) {
            throw new IllegalStateException("LLM startup check returned no text");
        }
        System.out.println("Startup check passed: LLM");
    }
}
