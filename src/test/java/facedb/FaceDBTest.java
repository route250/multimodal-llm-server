package facedb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import json.JsonFields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaceDBTest {
    @TempDir
    Path tempDir;

    @Test
    void registerWritesSampleFilesUnderTrackDirectory() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();

        FacePossibility registered = db.register(
                trackId,
                new double[] {0.1, 0.2, 0.3},
                jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9}));

        assertEquals("trak-000000", trackId);
        assertEquals("sample-000000", registered.sampleId);
        Path saved = sampleJson(trackId, registered.sampleId);
        assertTrue(Files.isRegularFile(saved));

        String json = Files.readString(saved, StandardCharsets.UTF_8);
        assertEquals(trackId, JsonFields.string(json, "trackId"));
        assertEquals("sample-000000", JsonFields.string(json, "sampleId"));
        assertTrue(JsonFields.longValue(json, "createdAt") > 0L);
        assertTrue(json.contains("\"descriptor\":[0.1,0.2,0.3]"));
    }

    @Test
    void assignAddsMissingPersonAndStoresPersonIdOnTrack() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        db.register(trackId, new double[] {0.1}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));

        db.assign(trackId, "alice");

        String trackJson = Files.readString(trackJson(trackId), StandardCharsets.UTF_8);
        assertEquals("person0000", JsonFields.string(trackJson, "personId"));
        String personsJson = Files.readString(tempDir.resolve("persons.json"), StandardCharsets.UTF_8);
        assertTrue(personsJson.contains("\"personId\":\"person0000\""));
        assertTrue(personsJson.contains("\"name\":\"alice\""));
    }

    @Test
    void loadRestoresAssignedTracksAndPredictsNamedPeople() throws Exception {
        FaceDB source = new FaceDB(tempDir);
        String aliceTrackId = source.createTrackId();
        source.register(aliceTrackId, new double[] {0.1, 0.1}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
        source.assign(aliceTrackId, "alice");
        String bobTrackId = source.createTrackId();
        source.register(bobTrackId, new double[] {0.3, 0.3}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 2, (byte) 0xff, (byte) 0xd9}));
        source.assign(bobTrackId, "bob");

        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();

        FacePossibility.PersonPossibility[] possibilities = loaded.predict(new double[] {0.1, 0.1});
        assertEquals(2, possibilities.length);
        assertEquals("person0000", possibilities[0].personId);
        assertEquals("alice", possibilities[0].name);
        assertEquals(0.0, possibilities[0].distance, 0.000001);
        assertEquals("person0001", possibilities[1].personId);
        assertEquals("bob", possibilities[1].name);
        assertEquals(Math.sqrt(0.08), possibilities[1].distance, 0.000001);
    }

    @Test
    void loadDeletesUnnamedTracks() throws Exception {
        FaceDB source = new FaceDB(tempDir);
        source.createTrackId();
        source.createTrackId();

        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();

        assertEquals("trak-000000", loaded.createTrackId());
    }

    @Test
    void registerSampleIdStartsFromZeroForEachTrack() throws Exception {
        FaceDB source = new FaceDB(tempDir);
        String sourceTrackId = source.createTrackId();
        source.register(sourceTrackId, new double[] {0.1}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));

        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();
        String loadedTrackId = loaded.createTrackId();
        FacePossibility registered = loaded.register(
                loadedTrackId,
                new double[] {0.2},
                jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 2, (byte) 0xff, (byte) 0xd9}));

        assertEquals("sample-000000", registered.sampleId);
    }

    @Test
    void renameUsesTrackIdToUpdatePersonName() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        db.register(trackId, new double[] {0.1}, jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
        db.assign(trackId, "alice");

        db.rename(trackId, "alicia");

        assertTrue(Files.readString(tempDir.resolve("persons.json"), StandardCharsets.UTF_8).contains("\"name\":\"alicia\""));
    }

    @Test
    void saveImageWritesJpegDataUrlUnderTrackDirectory() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9};
        String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg);

        FacePossibility registered = db.register(trackId, new double[] {0.1}, dataUrl);

        assertEquals(0, registered.personPossibilities.length);
        assertArrayEquals(jpeg, Files.readAllBytes(sampleImage(trackId, registered.sampleId)));
    }

    @Test
    void registerReturnsNamedPersonPossibilitiesBeforeSavingNewSample() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String image = jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9});
        String knownTrackId = db.createTrackId();
        db.register(knownTrackId, new double[] {0.1, 0.1}, image);
        db.assign(knownTrackId, "alice");

        String newTrackId = db.createTrackId();
        FacePossibility registered = db.register(newTrackId, new double[] {0.1, 0.1}, image);

        assertEquals("sample-000000", registered.sampleId);
        assertTrue(Files.isRegularFile(sampleJson(newTrackId, registered.sampleId)));
        assertTrue(Files.isRegularFile(sampleImage(newTrackId, registered.sampleId)));
        assertEquals(1, registered.personPossibilities.length);
        assertEquals("person0000", registered.personPossibilities[0].personId);
        assertEquals("alice", registered.personPossibilities[0].name);
        assertEquals(0.0, registered.personPossibilities[0].distance, 0.000001);
    }

    @Test
    void saveImageAcceptsRawBase64() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, 9, 8, (byte) 0xff, (byte) 0xd9};

        FacePossibility registered = db.register(trackId, new double[] {0.1}, Base64.getEncoder().encodeToString(jpeg));

        assertArrayEquals(jpeg, Files.readAllBytes(sampleImage(trackId, registered.sampleId)));
    }

    @Test
    void finishStoresFeatureRangeInTrackJson() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        String image = jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9});
        db.register(trackId, new double[] {0.0, 0.0}, image);
        db.register(trackId, new double[] {2.0, 0.0}, image);

        db.finish(trackId);

        String json = Files.readString(trackJson(trackId), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"featureRange\":{\"center\":[1.0,0.0],\"radius\":1.0}"));
    }

    @Test
    void assignPropagatesPersonThroughMatchingTrackChain() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String image = jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9});
        String firstTrackId = finishedTrack(db, image, 0.0, 0.4);
        String middleTrackId = finishedTrack(db, image, 0.3, 0.7);
        String lastTrackId = finishedTrack(db, image, 0.6, 1.0);

        db.assign(firstTrackId, "alice");

        assertEquals("person0000", JsonFields.string(Files.readString(trackJson(firstTrackId)), "personId"));
        assertEquals("person0000", JsonFields.string(Files.readString(trackJson(middleTrackId)), "personId"));
        assertEquals("person0000", JsonFields.string(Files.readString(trackJson(lastTrackId)), "personId"));
    }

    @Test
    void predictReturnsNamedFacesUnderThresholdByDistance() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String image = jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9});
        String aliceTrackId = db.createTrackId();
        db.register(aliceTrackId, new double[] {0.2, 0.2}, image);
        db.assign(aliceTrackId, "alice");
        String bobTrackId = db.createTrackId();
        db.register(bobTrackId, new double[] {0.1, 0.1}, image);
        db.assign(bobTrackId, "bob");
        String carolTrackId = db.createTrackId();
        db.register(carolTrackId, new double[] {0.6, 0.6}, image);
        db.assign(carolTrackId, "carol");
        String unknownTrackId = db.createTrackId();
        db.register(unknownTrackId, new double[] {0.0, 0.0}, image);

        FacePossibility.PersonPossibility[] possibilities = db.predict(new double[] {0.0, 0.0});

        assertEquals(2, possibilities.length);
        assertEquals("person0001", possibilities[0].personId);
        assertEquals("bob", possibilities[0].name);
        assertEquals(Math.sqrt(0.02), possibilities[0].distance, 0.000001);
        assertEquals("person0000", possibilities[1].personId);
        assertEquals("alice", possibilities[1].name);
        assertEquals(Math.sqrt(0.08), possibilities[1].distance, 0.000001);
    }

    private Path trackJson(String trackId) {
        return tempDir.resolve(trackId).resolve(trackId + ".json");
    }

    private Path sampleJson(String trackId, String sampleId) {
        return tempDir.resolve(trackId).resolve(sampleId + ".json");
    }

    private Path sampleImage(String trackId, String sampleId) {
        return tempDir.resolve(trackId).resolve(sampleId + ".jpg");
    }

    private String finishedTrack(FaceDB db, String image, double... descriptors) throws Exception {
        String trackId = db.createTrackId();
        for (double descriptor : descriptors) {
            db.register(trackId, new double[] {descriptor}, image);
        }
        db.finish(trackId);
        return trackId;
    }

    private static String jpegBase64(byte[] jpeg) {
        return Base64.getEncoder().encodeToString(jpeg);
    }
}
