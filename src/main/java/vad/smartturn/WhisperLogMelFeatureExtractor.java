package vad.smartturn;

import java.util.Arrays;

public class WhisperLogMelFeatureExtractor {
    public static final int SAMPLE_RATE = 16_000;
    public static final int CHUNK_SECONDS = 8;
    public static final int CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_SECONDS;
    public static final int FEATURE_SIZE = 80;
    public static final int N_FFT = 400;
    public static final int HOP_LENGTH = 160;
    public static final int FRAME_COUNT = CHUNK_SAMPLES / HOP_LENGTH;
    private static final int FFT_BINS = N_FFT / 2 + 1;
    private static final double MEL_FLOOR = 1.0e-10;

    private final double[] hannWindow = hannWindow();
    private final double[][] melFilters = melFilterBank();
    private final double[][] cosTable = cosineTable();
    private final double[][] sinTable = sineTable();

    public float[][] extract(float[] samples) {
        float[] audio = normalize(padOrTrimToLastChunk(samples));
        double[] centered = reflectPad(audio, N_FFT / 2);
        float[][] features = new float[FEATURE_SIZE][FRAME_COUNT];
        double maxLog = Double.NEGATIVE_INFINITY;

        for (int frame = 0; frame <= FRAME_COUNT; frame++) {
            int start = frame * HOP_LENGTH;
            double[] powerSpectrum = powerSpectrum(centered, start);
            for (int mel = 0; mel < FEATURE_SIZE; mel++) {
                double energy = 0.0;
                for (int bin = 0; bin < FFT_BINS; bin++) {
                    energy += melFilters[bin][mel] * powerSpectrum[bin];
                }
                double log = Math.log10(Math.max(MEL_FLOOR, energy));
                if (frame < FRAME_COUNT) {
                    features[mel][frame] = (float) log;
                    if (log > maxLog) {
                        maxLog = log;
                    }
                }
            }
        }

        float floor = (float) (maxLog - 8.0);
        for (int mel = 0; mel < FEATURE_SIZE; mel++) {
            for (int frame = 0; frame < FRAME_COUNT; frame++) {
                features[mel][frame] = (Math.max(features[mel][frame], floor) + 4.0f) / 4.0f;
            }
        }
        return features;
    }

    private static float[] padOrTrimToLastChunk(float[] samples) {
        float[] chunk = new float[CHUNK_SAMPLES];
        int copied = Math.min(samples.length, CHUNK_SAMPLES);
        int sourceOffset = Math.max(0, samples.length - CHUNK_SAMPLES);
        int targetOffset = CHUNK_SAMPLES - copied;
        System.arraycopy(samples, sourceOffset, chunk, targetOffset, copied);
        return chunk;
    }

    private static float[] normalize(float[] samples) {
        double sum = 0.0;
        for (float sample : samples) {
            sum += sample;
        }
        double mean = sum / samples.length;

        double varianceSum = 0.0;
        for (float sample : samples) {
            double centered = sample - mean;
            varianceSum += centered * centered;
        }
        double scale = Math.sqrt(varianceSum / samples.length + 1.0e-7);

        float[] normalized = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            normalized[i] = (float) ((samples[i] - mean) / scale);
        }
        return normalized;
    }

    private double[] powerSpectrum(double[] paddedAudio, int start) {
        double[] power = new double[FFT_BINS];
        for (int bin = 0; bin < FFT_BINS; bin++) {
            double real = 0.0;
            double imaginary = 0.0;
            double[] cos = cosTable[bin];
            double[] sin = sinTable[bin];
            for (int sample = 0; sample < N_FFT; sample++) {
                double windowed = paddedAudio[start + sample] * hannWindow[sample];
                real += windowed * cos[sample];
                imaginary -= windowed * sin[sample];
            }
            power[bin] = real * real + imaginary * imaginary;
        }
        return power;
    }

    private static double[] reflectPad(float[] samples, int padding) {
        double[] padded = new double[samples.length + padding * 2];
        for (int i = 0; i < padded.length; i++) {
            padded[i] = samples[reflectIndex(i - padding, samples.length)];
        }
        return padded;
    }

    private static int reflectIndex(int index, int length) {
        if (length == 1) {
            return 0;
        }
        int period = 2 * length - 2;
        int value = Math.floorMod(index, period);
        if (value >= length) {
            return period - value;
        }
        return value;
    }

    private static double[] hannWindow() {
        double[] window = new double[N_FFT];
        for (int i = 0; i < window.length; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / N_FFT);
        }
        return window;
    }

    private static double[][] cosineTable() {
        double[][] table = new double[FFT_BINS][N_FFT];
        for (int bin = 0; bin < FFT_BINS; bin++) {
            for (int sample = 0; sample < N_FFT; sample++) {
                table[bin][sample] = Math.cos(2.0 * Math.PI * bin * sample / N_FFT);
            }
        }
        return table;
    }

    private static double[][] sineTable() {
        double[][] table = new double[FFT_BINS][N_FFT];
        for (int bin = 0; bin < FFT_BINS; bin++) {
            for (int sample = 0; sample < N_FFT; sample++) {
                table[bin][sample] = Math.sin(2.0 * Math.PI * bin * sample / N_FFT);
            }
        }
        return table;
    }

    private static double[][] melFilterBank() {
        double[] fftFrequencies = new double[FFT_BINS];
        for (int i = 0; i < fftFrequencies.length; i++) {
            fftFrequencies[i] = (double) i * SAMPLE_RATE / N_FFT;
        }

        double minMel = hertzToMel(0.0);
        double maxMel = hertzToMel(SAMPLE_RATE / 2.0);
        double[] melFrequencies = new double[FEATURE_SIZE + 2];
        for (int i = 0; i < melFrequencies.length; i++) {
            double mel = minMel + (maxMel - minMel) * i / (melFrequencies.length - 1);
            melFrequencies[i] = melToHertz(mel);
        }

        double[][] filters = new double[FFT_BINS][FEATURE_SIZE];
        for (int mel = 0; mel < FEATURE_SIZE; mel++) {
            double lower = melFrequencies[mel];
            double center = melFrequencies[mel + 1];
            double upper = melFrequencies[mel + 2];
            double enorm = 2.0 / (upper - lower);
            for (int bin = 0; bin < FFT_BINS; bin++) {
                double frequency = fftFrequencies[bin];
                double lowerSlope = (frequency - lower) / (center - lower);
                double upperSlope = (upper - frequency) / (upper - center);
                filters[bin][mel] = Math.max(0.0, Math.min(lowerSlope, upperSlope)) * enorm;
            }
        }
        return filters;
    }

    private static double hertzToMel(double frequency) {
        double mel = 3.0 * frequency / 200.0;
        if (frequency >= 1000.0) {
            mel = 15.0 + Math.log(frequency / 1000.0) * (27.0 / Math.log(6.4));
        }
        return mel;
    }

    private static double melToHertz(double mel) {
        double frequency = 200.0 * mel / 3.0;
        if (mel >= 15.0) {
            frequency = 1000.0 * Math.exp((Math.log(6.4) / 27.0) * (mel - 15.0));
        }
        return frequency;
    }

    @Override
    public String toString() {
        return "WhisperLogMelFeatureExtractor"
                + Arrays.asList(
                        "sampleRate=" + SAMPLE_RATE,
                        "chunkSamples=" + CHUNK_SAMPLES,
                        "featureSize=" + FEATURE_SIZE,
                        "frameCount=" + FRAME_COUNT);
    }
}
