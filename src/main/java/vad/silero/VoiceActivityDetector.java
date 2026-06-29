package vad.silero;

public interface VoiceActivityDetector {
    float speechProbability(float[] samples);
}
