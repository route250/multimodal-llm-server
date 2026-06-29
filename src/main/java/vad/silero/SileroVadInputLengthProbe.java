package vad.silero;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import java.nio.file.Path;
import java.util.Map;
import model.download.SileroVadModelDownloader;

public class SileroVadInputLengthProbe {
    private static final int SAMPLE_RATE = 16_000;

    public static void main(String[] args) throws OrtException {
        Path modelPath = args.length == 0 ? SileroVadModelDownloader.MODEL_PATH : Path.of(args[0]);
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                OrtSession session = createSession(environment, options, modelPath)) {
            printModelInfo(session);
            probeCandidates(environment, session);
            probeMaximumAcceptedLength(environment, session, 256, 4_096);
        }
    }

    private static OrtSession createSession(OrtEnvironment environment, OrtSession.SessionOptions options, Path modelPath)
            throws OrtException {
        options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
        return environment.createSession(modelPath.toString(), options);
    }

    private static void printModelInfo(OrtSession session) throws OrtException {
        System.out.println("inputs=" + session.getInputInfo().keySet());
        System.out.println("outputs=" + session.getOutputInfo().keySet());
    }

    private static void probeCandidates(OrtEnvironment environment, OrtSession session) {
        System.out.println("direct input tensor lengths:");
        for (int inputSamples : new int[]{256, 512, 576, 640, 704, 1024, 1536, 2048, 3072}) {
            System.out.printf("  input=%4d -> %s%n", inputSamples, accepts(environment, session, inputSamples) ? "OK" : "NG");
        }
        System.out.println("audio window plus 64-sample context:");
        for (int audioSamples : new int[]{512, 1024, 1536, 2048, 3072}) {
            int inputSamples = audioSamples + 64;
            System.out.printf("  audio=%4d input=%4d -> %s%n", audioSamples, inputSamples,
                    accepts(environment, session, inputSamples) ? "OK" : "NG");
        }
    }

    private static void probeMaximumAcceptedLength(OrtEnvironment environment, OrtSession session, int minKnownAccepted,
            int maxSamples) {
        if (!accepts(environment, session, minKnownAccepted)) {
            System.out.println("minimum known accepted input length is rejected: " + minKnownAccepted);
            return;
        }
        int lowAccepted = minKnownAccepted;
        int highRejected = maxSamples + 1;
        while (lowAccepted + 1 < highRejected) {
            int middle = lowAccepted + (highRejected - lowAccepted) / 2;
            if (accepts(environment, session, middle)) {
                lowAccepted = middle;
            } else {
                highRejected = middle;
            }
        }
        System.out.println("maximum accepted contiguous input length=" + lowAccepted);
        System.out.println("first rejected input length after that=" + highRejected);
    }

    private static boolean accepts(OrtEnvironment environment, OrtSession session, int inputSamples) {
        try (OnnxTensor input = OnnxTensor.createTensor(environment, new float[][]{new float[inputSamples]});
                OnnxTensor state = OnnxTensor.createTensor(environment, new float[2][1][128]);
                OnnxTensor sampleRate = OnnxTensor.createTensor(environment, (long) SAMPLE_RATE);
                OrtSession.Result ignored = session.run(Map.of("input", input, "state", state, "sr", sampleRate))) {
            return true;
        } catch (OrtException e) {
            return false;
        }
    }
}
