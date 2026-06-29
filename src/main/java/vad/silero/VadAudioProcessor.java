package vad.silero;

import audio.AudioBuffer;
import audio.Pcm16Le;
import java.util.Optional;
import stt.SpeechToText;

public class VadAudioProcessor {
    public static final int SAMPLE_RATE = 16_000;
    public static final int VAD_FRAME_SAMPLES = SileroVad.CHUNK_SAMPLES;
    public static final int PRE_ROLL_SAMPLES = 9_600;
    public static final int END_SILENCE_SAMPLES = 9_600;
    public static final float START_THRESHOLD = 0.5f;
    public static final float END_THRESHOLD = 0.35f;

    private final AudioBuffer receiveBuffer = new AudioBuffer(SAMPLE_RATE * 6, VAD_FRAME_SAMPLES);
    private final AudioBuffer sttBuffer = new AudioBuffer(SAMPLE_RATE * 30, VAD_FRAME_SAMPLES);
    private final VoiceActivityDetector vad;
    private final SpeechToText speechToText;
    private long nextSampleIndex;
    private long nextVadStartSampleIndex;
    private boolean speaking;
    private long speechStartSampleIndex;
    private long lastSpeechSampleIndex;

    public VadAudioProcessor(VoiceActivityDetector vad, SpeechToText speechToText) {
        this.vad = vad;
        this.speechToText = speechToText;
    }

    public synchronized Optional<String> acceptPcm16Le(byte[] bytes) {
        short[] samples = Pcm16Le.decode(bytes);
        long chunkStartSampleIndex = nextSampleIndex;
        receiveBuffer.append(samples, chunkStartSampleIndex);
        nextSampleIndex += samples.length;
        return processAvailableWindows();
    }

    private Optional<String> processAvailableWindows() {
        Optional<String> transcript = Optional.empty();
        long latestFrameStart = receiveBuffer.endSampleIndexExclusive() - VAD_FRAME_SAMPLES;
        while (nextVadStartSampleIndex <= latestFrameStart) {
            if (nextVadStartSampleIndex < receiveBuffer.startSampleIndex()) {
                nextVadStartSampleIndex = alignToNextFrame(receiveBuffer.startSampleIndex());
                continue;
            }

            float probability = vad.speechProbability(receiveBuffer.floats(nextVadStartSampleIndex, VAD_FRAME_SAMPLES));
            receiveBuffer.putVadValue(nextVadStartSampleIndex, probability);
            Optional<String> completedTranscript = updateSpeechState(nextVadStartSampleIndex, probability);
            if (completedTranscript.isPresent()) {
                transcript = completedTranscript;
            }
            nextVadStartSampleIndex += VAD_FRAME_SAMPLES;
        }
        return transcript;
    }

    private Optional<String> updateSpeechState(long frameStartSampleIndex, float probability) {
        long frameEndSampleIndex = frameStartSampleIndex + VAD_FRAME_SAMPLES;
        if (!speaking) {
            if (probability >= START_THRESHOLD) {
                speaking = true;
                speechStartSampleIndex = Math.max(receiveBuffer.startSampleIndex(), frameStartSampleIndex - PRE_ROLL_SAMPLES);
                lastSpeechSampleIndex = frameEndSampleIndex;
            }
            return Optional.empty();
        }

        if (probability > END_THRESHOLD) {
            lastSpeechSampleIndex = frameEndSampleIndex;
            return Optional.empty();
        }

        if (frameEndSampleIndex - lastSpeechSampleIndex < END_SILENCE_SAMPLES) {
            return Optional.empty();
        }

        long speechEndSampleIndex = Math.min(receiveBuffer.endSampleIndexExclusive(), frameEndSampleIndex);
        if (receiveBuffer.contains(speechStartSampleIndex, speechEndSampleIndex)) {
            sttBuffer.appendRangeFrom(receiveBuffer, speechStartSampleIndex, speechEndSampleIndex);
        }
        speaking = false;
        return Optional.of(speechToText.transcribe(sttBuffer, speechStartSampleIndex, speechEndSampleIndex));
    }

    private static long alignToNextFrame(long sampleIndex) {
        long remainder = sampleIndex % VAD_FRAME_SAMPLES;
        if (remainder == 0) {
            return sampleIndex;
        }
        return sampleIndex + VAD_FRAME_SAMPLES - remainder;
    }
}
