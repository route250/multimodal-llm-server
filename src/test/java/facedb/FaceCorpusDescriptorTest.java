package facedb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaceCorpusDescriptorTest {
    private static final Path DESCRIPTORS_PATH = Path.of("src/test/test-data/faces/descriptors.json");
    private static final Pattern IMAGE_PATTERN = Pattern.compile(
            "\\{\\s*\"imageId\"\\s*:\\s*\"([^\"]+)\"\\s*,"
                    + "\\s*\"personId\"\\s*:\\s*\"([^\"]+)\"\\s*,"
                    + "\\s*\"filePath\"\\s*:\\s*\"([^\"]+)\"\\s*,"
                    + "\\s*\"split\"\\s*:\\s*\"([^\"]+)\"\\s*,"
                    + "\\s*\"sourceImageId\"\\s*:\\s*\"([^\"]+)\"\\s*,"
                    + "\\s*\"initialDetectionScore\"\\s*:\\s*[-+0-9.eE]+\\s*,"
                    + "\\s*\"descriptorDetectionScore\"\\s*:\\s*[-+0-9.eE]+\\s*,"
                    + "\\s*\"descriptor\"\\s*:\\s*\\[([^]]+)]\\s*}",
            Pattern.DOTALL);

    @TempDir
    Path tempDir;

    @Test
    void precomputedDescriptorsContainTenPeopleAndOneHundredImages() throws Exception {
        List<FaceVector> vectors = loadVectors();
        Map<String, Integer> imagesPerPerson = new HashMap<>();

        for (FaceVector vector : vectors) {
            imagesPerPerson.merge(vector.personId(), 1, Integer::sum);
            assertEquals(128, vector.descriptor().length, vector.imageId());
            for (double value : vector.descriptor()) {
                assertTrue(Double.isFinite(value), vector.imageId());
            }
        }

        assertEquals(100, vectors.size());
        assertEquals(10, imagesPerPerson.size());
        assertTrue(imagesPerPerson.values().stream().allMatch(count -> count == 10));
    }

    @Test
    void faceDbIdentifiesAllThirtyHeldOutImages() throws Exception {
        List<FaceVector> vectors = loadVectors();
        FaceDB db = new FaceDB(tempDir);
        Map<String, String> trackByPerson = new HashMap<>();

        for (FaceVector vector : vectors) {
            if (!vector.split().equals("train")) {
                continue;
            }
            String trackId = trackByPerson.computeIfAbsent(vector.personId(), ignored -> createTrack(db));
            db.register(trackId, vector.descriptor(), jpegBase64());
        }
        for (Map.Entry<String, String> entry : trackByPerson.entrySet()) {
            db.assign(entry.getValue(), entry.getKey());
        }

        int testImages = 0;
        int correct = 0;
        for (FaceVector vector : vectors) {
            if (!vector.split().equals("test")) {
                continue;
            }
            testImages++;
            FacePossibility.PersonPossibility[] possibilities = db.predict(vector.descriptor());
            if (possibilities.length > 0 && possibilities[0].name.equals(vector.personId())) {
                correct++;
            }
        }

        assertEquals(30, testImages);
        assertEquals(30, correct);
    }

    private static String createTrack(FaceDB db) {
        try {
            return db.createTrackId();
        } catch (Exception ex) {
            throw new IllegalStateException("テスト用トラックを作成できません", ex);
        }
    }

    private static List<FaceVector> loadVectors() throws Exception {
        String json = Files.readString(DESCRIPTORS_PATH, StandardCharsets.UTF_8);
        Matcher matcher = IMAGE_PATTERN.matcher(json);
        List<FaceVector> vectors = new ArrayList<>();
        while (matcher.find()) {
            vectors.add(new FaceVector(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(4),
                    descriptor(matcher.group(6))));
        }
        return vectors;
    }

    private static double[] descriptor(String values) {
        String[] parts = values.split(",");
        double[] descriptor = new double[parts.length];
        for (int index = 0; index < parts.length; index++) {
            descriptor[index] = Double.parseDouble(parts[index].trim());
        }
        return descriptor;
    }

    private static String jpegBase64() {
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9};
        return Base64.getEncoder().encodeToString(jpeg);
    }

    private record FaceVector(String imageId, String personId, String split, double[] descriptor) {
    }
}
