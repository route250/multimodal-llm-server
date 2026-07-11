package facedb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import json.JsonFields;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaceDBTest {
    @TempDir
    Path tempDir;

    @Test
    void saveWritesFaceSampleJsonUnderDbPath() throws Exception {
        FaceDB db = new FaceDB(tempDir);

        db.register(new double[] {0.1, 0.2, 0.3}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}));
        db.assign("face000000", "alice");

        Path saved = tempDir.resolve("face000000.json");
        assertTrue(Files.isRegularFile(saved));

        String json = Files.readString(saved, StandardCharsets.UTF_8);
        assertEquals("face000000", JsonFields.string(json, "faceId"));
        assertTrue(JsonFields.longValue(json, "createdAt") > 0L);
        assertEquals("person0000", JsonFields.string(json, "personId"));
        assertTrue(json.contains("\"descriptor\":[0.1,0.2,0.3]"));
    }

    @Test
    void loadReadsSequentialFaceFilesUntilFirstMissingIndex() throws Exception {
        FaceDB source = new FaceDB(tempDir);
        source.register(new double[] {0.1, 0.2}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
        source.assign("face000000", "alice");
        source.register(new double[] {0.3, 0.4}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 2, (byte) 0xff, (byte) 0xd9}));
        source.assign("face000001", "bob");

        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();

        ArrayList<double[]> descriptors = descriptors(loaded);
        ArrayList<?> faceSamples = faceSamples(loaded);
        ArrayList<String> persons = persons(loaded);
        assertEquals(2, descriptors.size());
        assertArrayEquals(new double[] {0.1, 0.2}, descriptors.get(0));
        assertArrayEquals(new double[] {0.3, 0.4}, descriptors.get(1));
        assertEquals("alice", persons.get(faceSamplePersonId(faceSamples.get(0))));
        assertEquals("bob", persons.get(faceSamplePersonId(faceSamples.get(1))));
    }

    @Test
    void loadStopsAtFirstMissingFaceFile() throws Exception {
        FaceDB source = new FaceDB(tempDir);
        source.register(new double[] {0.1}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
        source.assign("face000000", "alice");
        Files.writeString(tempDir.resolve("face000002.json"),
                "{\"faceId\":\"face000002\",\"createdAt\":300,\"personId\":\"person0001\",\"descriptor\":[0.3]}",
                StandardCharsets.UTF_8);

        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();

        assertEquals(1, descriptors(loaded).size());
        assertEquals(0, faceSamplePersonId(faceSamples(loaded).get(0)));
    }

    @Test
    void assignAddsMissingPersonAndStoresPersonId() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        db.register(new double[] {0.1}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));

        db.assign("face000000", "alice");

        assertEquals("alice", persons(db).get(0));
        assertEquals(0, faceSamplePersonId(faceSamples(db).get(0)));
        String json = Files.readString(tempDir.resolve("face000000.json"), StandardCharsets.UTF_8);
        assertEquals("person0000", JsonFields.string(json, "personId"));
        String personsJson = Files.readString(tempDir.resolve("persons.json"), StandardCharsets.UTF_8);
        assertTrue(personsJson.contains("\"personId\":\"person0000\""));
        assertTrue(personsJson.contains("\"name\":\"alice\""));
    }

    @Test
    void renameUsesFaceIdToUpdatePersonNameWithoutChangingFaceSamplePersonId() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        db.register(new double[] {0.1}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
        db.assign("face000000", "alice");

        db.rename("face000000", "alicia");

        assertEquals("alicia", persons(db).get(0));
        assertEquals(0, faceSamplePersonId(faceSamples(db).get(0)));
        assertTrue(Files.readString(tempDir.resolve("persons.json"), StandardCharsets.UTF_8).contains("\"name\":\"alicia\""));
    }

    @Test
    void saveImageWritesJpegDataUrlUnderDbPath() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);

        FacePossibility registered = db.register(new double[] {0.1}, dataUrl);

        assertEquals("face000000", registered.faceId);
        assertEquals(tempDir.resolve("face000000.json").toAbsolutePath().normalize().toString(), registered.jsonPath);
        assertEquals(tempDir.resolve("face000000.jpg").toAbsolutePath().normalize().toString(), registered.imagePath);
        assertEquals(0, registered.personPossibilities.length);
        assertArrayEquals(jpeg, Files.readAllBytes(tempDir.resolve("face000000.jpg")));
    }

    @Test
    void registerReturnsNamedPersonPossibilitiesBeforeSavingNewSample() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String image = jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9});
        db.register(new double[] {0.1, 0.1}, image);
        db.assign("face000000", "alice");

        FacePossibility registered = db.register(new double[] {0.1, 0.1}, image);

        assertEquals("face000001", registered.faceId);
        assertEquals(tempDir.resolve("face000001.json").toAbsolutePath().normalize().toString(), registered.jsonPath);
        assertEquals(tempDir.resolve("face000001.jpg").toAbsolutePath().normalize().toString(), registered.imagePath);
        assertEquals(1, registered.personPossibilities.length);
        assertEquals("person0000", registered.personPossibilities[0].personId);
        assertEquals("alice", registered.personPossibilities[0].name);
        assertEquals(0.0, registered.personPossibilities[0].distance, 0.000001);
    }

    @Test
    void saveImageAcceptsRawBase64() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, 9, 8, (byte) 0xff, (byte) 0xd9};

        db.register(new double[] {0.1}, Base64.getEncoder().encodeToString(jpeg));

        assertArrayEquals(jpeg, Files.readAllBytes(tempDir.resolve("face000000.jpg")));
    }

    @Test
    void predictReturnsNamedFacesUnderThresholdByDistance() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String image = jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9});
        db.register(new double[] {0.2, 0.2}, image);
        db.assign("face000000", "alice");
        db.register(new double[] {0.1, 0.1}, image);
        db.assign("face000001", "bob");
        db.register(new double[] {0.6, 0.6}, image);
        db.assign("face000002", "carol");
        db.register(new double[] {0.0, 0.0}, image);

        FacePossibility.PersonPossibility[] possibilities = db.predict(new double[] {0.0, 0.0});

        assertEquals(2, possibilities.length);
        assertEquals("person0001", possibilities[0].personId);
        assertEquals("bob", possibilities[0].name);
        assertEquals(Math.sqrt(0.02), possibilities[0].distance, 0.000001);
        assertEquals("person0000", possibilities[1].personId);
        assertEquals("alice", possibilities[1].name);
        assertEquals(Math.sqrt(0.08), possibilities[1].distance, 0.000001);
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<double[]> descriptors(FaceDB db) throws Exception {
        Field field = FaceDB.class.getDeclaredField("descriptors");
        field.setAccessible(true);
        return (ArrayList<double[]>) field.get(db);
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<?> faceSamples(FaceDB db) throws Exception {
        Field field = FaceDB.class.getDeclaredField("faceSamples");
        field.setAccessible(true);
        return (ArrayList<?>) field.get(db);
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<String> persons(FaceDB db) throws Exception {
        Field field = FaceDB.class.getDeclaredField("persons");
        field.setAccessible(true);
        return (ArrayList<String>) field.get(db);
    }

    private static int faceSamplePersonId(Object faceSample) throws Exception {
        Field field = faceSample.getClass().getDeclaredField("personId");
        field.setAccessible(true);
        return (int) field.get(faceSample);
    }

    private static String jpegBase64(byte[] jpeg) {
        return Base64.getEncoder().encodeToString(jpeg);
    }
}
