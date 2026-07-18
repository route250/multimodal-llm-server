package facedb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

    @Test
    void thresholdPropertiesUseDefaultsAndStoreConfiguredValues() {
        FaceDB db = new FaceDB(tempDir);

        assertEquals(FaceDB.DEFAULT_RECOGNITION_DISTANCE_THRESHOLD, db.getRecognitionDistanceThreshold());
        assertEquals(FaceDB.DEFAULT_TRACK_LINK_DISTANCE_THRESHOLD, db.getTrackLinkDistanceThreshold());

        db.setRecognitionDistanceThreshold(0.25);
        db.setTrackLinkDistanceThreshold(0.3);

        assertEquals(0.25, db.getRecognitionDistanceThreshold());
        assertEquals(0.3, db.getTrackLinkDistanceThreshold());
    }

    @Test
    void predictIncludesDistanceEqualToThresholdAndExcludesNextLargerValue() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        db.register(trackId, new double[] {0.0}, jpegBase64());
        db.assign(trackId, "alice");
        db.setRecognitionDistanceThreshold(0.5);

        assertEquals(1, db.predict(new double[] {0.5}).length);
        assertEquals(0, db.predict(new double[] {Math.nextUp(0.5)}).length);
    }

    @Test
    void assignUsesExclusiveTrackLinkDistanceBoundary() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String sourceTrackId = finishedTrack(db, jpegBase64(), 0.0, 2.0);
        String candidateTrackId = finishedTrack(db, jpegBase64(), 1.0, 3.0);

        db.assign(sourceTrackId, "alice", 1.0);
        assertFalse("person0000".equals(
                JsonFields.string(Files.readString(trackJson(candidateTrackId)), "personId")));

        db.assign(sourceTrackId, "alice", Math.nextUp(1.0));
        assertEquals("person0000", JsonFields.string(Files.readString(trackJson(candidateTrackId)), "personId"));
    }

    @Test
    void registerRejectsUnknownTrackId() {
        FaceDB db = new FaceDB(tempDir);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> db.register("trak-999999", new double[] {0.0}, jpegBase64()));

        assertTrue(error.getMessage().contains("trackId is not found"));
    }

    @Test
    void assignRejectsMissingTrackIdOrName() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();

        assertThrows(IllegalArgumentException.class, () -> db.assign(null, "alice"));
        assertThrows(IllegalArgumentException.class, () -> db.assign(" ", "alice"));
        assertThrows(IllegalArgumentException.class, () -> db.assign(trackId, null));
        assertThrows(IllegalArgumentException.class, () -> db.assign(trackId, "  "));
        assertThrows(IllegalArgumentException.class, () -> db.assign("trak-999999", "alice"));
    }

    @Test
    void assignTrimsNamesAndReusesExistingPerson() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String firstTrackId = db.createTrackId();
        db.register(firstTrackId, new double[] {0.0}, jpegBase64());
        db.assign(firstTrackId, " alice ");
        String secondTrackId = db.createTrackId();
        db.register(secondTrackId, new double[] {10.0}, jpegBase64());

        db.assign(secondTrackId, "alice");

        assertEquals("person0000", JsonFields.string(Files.readString(trackJson(firstTrackId)), "personId"));
        assertEquals("person0000", JsonFields.string(Files.readString(trackJson(secondTrackId)), "personId"));
        String personsJson = Files.readString(tempDir.resolve("persons.json"));
        assertEquals(1, personsJson.split("\"personId\"", -1).length - 1);
    }

    @Test
    void renameIgnoresUnknownUnassignedAndBlankNames() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String unassignedTrackId = db.createTrackId();
        db.register(unassignedTrackId, new double[] {0.0}, jpegBase64());
        String assignedTrackId = db.createTrackId();
        db.register(assignedTrackId, new double[] {1.0}, jpegBase64());
        db.assign(assignedTrackId, "alice");
        String before = Files.readString(tempDir.resolve("persons.json"));

        assertDoesNotThrow(() -> db.rename("trak-999999", "nobody"));
        assertDoesNotThrow(() -> db.rename(unassignedTrackId, "nobody"));
        assertDoesNotThrow(() -> db.rename(assignedTrackId, null));
        assertDoesNotThrow(() -> db.rename(assignedTrackId, " "));

        assertEquals(before, Files.readString(tempDir.resolve("persons.json")));
    }

    @Test
    void registerWithoutPictureDoesNotCreateJpeg() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();

        FacePossibility withoutPicture = db.register(trackId, new double[] {0.0}, null);
        FacePossibility withEmptyPicture = db.register(trackId, new double[] {0.1}, "");

        assertFalse(Files.exists(sampleImage(trackId, withoutPicture.sampleId)));
        assertFalse(Files.exists(sampleImage(trackId, withEmptyPicture.sampleId)));
    }

    @Test
    void loadRestoresRecentUnnamedTrackSamplesAndFeatureRange() throws Exception {
        FaceDB source = new FaceDB(tempDir);
        String trackId = source.createTrackId();
        source.register(trackId, new double[] {0.0}, jpegBase64());
        source.register(trackId, new double[] {2.0}, jpegBase64());
        source.finish(trackId);

        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();

        FacePossibility sample = loaded.register(trackId, new double[] {1.0}, jpegBase64());
        assertEquals("sample-000002", sample.sampleId);
        assertEquals("trak-000001", loaded.createTrackId());
    }

    @Test
    void loadKeepsUnnamedTrackJustInsideThreeDaysAndDeletesOneJustOutside() throws Exception {
        long now = System.currentTimeMillis();
        writeTrackFixture(0, "unknown", now - 3L * 24 * 60 * 60 * 1000 + 60 * 60 * 1000, new double[] {0.0});
        writeTrackFixture(1, "unknown", now - 3L * 24 * 60 * 60 * 1000 - 60 * 60 * 1000, new double[] {1.0});

        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();

        assertTrue(Files.isDirectory(tempDir.resolve("trak-000000")));
        assertFalse(Files.exists(tempDir.resolve("trak-000001")));
        assertEquals("trak-000001", loaded.createTrackId());
    }

    @Test
    void loadRestoresSparsePersonIdAndIgnoresPlaceholderPersonIds() throws Exception {
        Files.writeString(
                tempDir.resolve("persons.json"),
                "[{\"personId\":\"unknown\",\"name\":\"ignored\"},"
                        + "{\"personId\":\"none\",\"name\":\"ignored\"},"
                        + "{\"personId\":\"person0002\",\"name\":\"carol\"}]");
        writeTrackFixture(0, "person0002", System.currentTimeMillis(), new double[] {0.25});

        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();

        FacePossibility.PersonPossibility[] possibilities = loaded.predict(new double[] {0.25});
        assertEquals(1, possibilities.length);
        assertEquals("person0002", possibilities[0].personId);
        assertEquals("carol", possibilities[0].name);
    }

    @Test
    void predictIgnoresAssignedPersonMissingFromPersonsFile() throws Exception {
        writeTrackFixture(0, "person9999", System.currentTimeMillis(), new double[] {0.25});
        FaceDB loaded = new FaceDB(tempDir);
        loaded.load();

        assertEquals(0, loaded.predict(new double[] {0.25}).length);
    }

    @Test
    void loadRejectsInvalidPersonIdFormatWithoutRestoringThatName() throws Exception {
        Files.writeString(tempDir.resolve("persons.json"), "[{\"personId\":\"person12\",\"name\":\"alice\"}]");
        FaceDB loaded = new FaceDB(tempDir);
        PrintStream originalError = System.err;
        try {
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            loaded.load();
        } finally {
            System.setErr(originalError);
        }

        assertEquals(0, loaded.predict(new double[] {0.0}).length);
    }

    @Test
    void loadRejectsSampleWithoutDescriptor() throws Exception {
        Path trackDir = Files.createDirectory(tempDir.resolve("trak-000000"));
        Files.writeString(trackDir.resolve("trak-000000.json"), "{\"createdAt\":1,\"personId\":\"unknown\"}");
        Files.writeString(trackDir.resolve("sample-000000.json"), "{\"createdAt\":1}");

        FaceDB loaded = new FaceDB(tempDir);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, loaded::load);
        assertEquals("descriptor is required", error.getMessage());
    }

    @Test
    void createTrackIdSkipsExistingTrackDirectory() throws Exception {
        Files.createDirectory(tempDir.resolve("trak-000000"));
        FaceDB db = new FaceDB(tempDir);

        assertEquals("trak-000001", db.createTrackId());
    }

    @Test
    void createTrackIdReportsStorePathThatIsAFile() throws Exception {
        Path storeFile = tempDir.resolve("store-file");
        Files.writeString(storeFile, "not a directory");
        FaceDB db = new FaceDB(storeFile);

        assertThrows(IOException.class, db::createTrackId);
    }

    @Test
    void createTrackIdFailsAfterOneThousandDirectoryCollisions() throws Exception {
        for (int trackIndex = 0; trackIndex < 1000; trackIndex++) {
            Files.createDirectory(tempDir.resolve("trak-" + String.format("%06d", trackIndex)));
        }
        FaceDB db = new FaceDB(tempDir);

        IOException error = assertThrows(IOException.class, db::createTrackId);

        assertEquals("can not create trackId", error.getMessage());
    }

    @Test
    void assignReportsPersonsFileWriteFailure() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        db.register(trackId, new double[] {0.0}, null);
        Files.createDirectory(tempDir.resolve("persons.json"));

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> db.assign(trackId, "alice"));

        assertEquals("can not save persons", error.getMessage());
    }

    @Test
    void assignReportsTrackFileWriteFailure() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        FacePossibility sample = db.register(trackId, new double[] {0.0}, null);
        replaceTrackDirectoryWithFile(trackId, sample.sampleId);

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> db.assign(trackId, "alice"));

        assertTrue(error.getMessage().contains("can not save assigned tracks"));
    }

    @Test
    void finishEmptyTrackStoresEmptyFeatureRangeAndUnknownFinishIsIgnored() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();

        assertDoesNotThrow(() -> db.finish("trak-999999"));
        db.finish(trackId);

        String json = Files.readString(trackJson(trackId));
        assertTrue(json.contains("\"featureRange\":{\"center\":[],\"radius\":0.0}"));
    }

    @Test
    void finishReportsTrackFileWriteFailure() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        FacePossibility sample = db.register(trackId, new double[] {0.0}, null);
        replaceTrackDirectoryWithFile(trackId, sample.sampleId);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> db.finish(trackId));

        assertTrue(error.getMessage().contains("can not save finished track"));
    }

    @Test
    void finishRejectsInconsistentDescriptorDimensions() throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String trackId = db.createTrackId();
        db.register(trackId, new double[] {0.0}, jpegBase64());
        db.register(trackId, new double[] {0.0, 1.0}, jpegBase64());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> db.finish(trackId));

        assertTrue(error.getMessage().contains("dimensions are inconsistent"));
    }

    @Test
    void distanceHandlesZeroLengthEqualLengthAndDifferentLengthDescriptors() {
        assertEquals(0.0, FaceDB.distance(new double[0], new double[0]));
        assertEquals(5.0, FaceDB.distance(new double[] {0.0, 0.0}, new double[] {3.0, 4.0}));
        assertEquals(Double.MAX_VALUE, FaceDB.distance(new double[] {0.0}, new double[] {0.0, 1.0}));
    }

    @Test
    void nearestReturnsFirstPossibilityOrNull() {
        FacePossibility.PersonPossibility first =
                new FacePossibility.PersonPossibility("person0000", "alice", 0.1f);

        assertNull(new FacePossibility("trak-000000", "sample-000000", null).nearest());
        assertNull(new FacePossibility(
                "trak-000000",
                "sample-000000",
                new FacePossibility.PersonPossibility[0]).nearest());
        assertEquals(first, new FacePossibility(
                "trak-000000",
                "sample-000000",
                new FacePossibility.PersonPossibility[] {first}).nearest());
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

    private void writeTrackFixture(int trackIndex, String personId, long createdAt, double[] descriptor)
            throws Exception {
        String trackId = "trak-" + String.format("%06d", trackIndex);
        Path trackDir = Files.createDirectory(tempDir.resolve(trackId));
        Files.writeString(
                trackDir.resolve(trackId + ".json"),
                "{\"trackId\":\"" + trackId + "\",\"createdAt\":" + createdAt
                        + ",\"personId\":\"" + personId
                        + "\",\"featureRange\":{\"center\":" + descriptorJson(descriptor)
                        + ",\"radius\":0.0}}");
        Files.writeString(
                trackDir.resolve("sample-000000.json"),
                "{\"trackId\":\"" + trackId + "\",\"sampleId\":\"sample-000000\",\"createdAt\":" + createdAt
                        + ",\"descriptor\":" + descriptorJson(descriptor) + "}");
    }

    private void replaceTrackDirectoryWithFile(String trackId, String sampleId) throws Exception {
        Files.delete(sampleJson(trackId, sampleId));
        Files.delete(tempDir.resolve(trackId));
        Files.writeString(tempDir.resolve(trackId), "not a directory");
    }

    private static String descriptorJson(double[] descriptor) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < descriptor.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(descriptor[index]);
        }
        return json.append(']').toString();
    }

    private static String jpegBase64() {
        return jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
    }

    private static String jpegBase64(byte[] jpeg) {
        return Base64.getEncoder().encodeToString(jpeg);
    }
}
