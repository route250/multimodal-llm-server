package facedb;

import json.Json;
import json.JsonFields;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FaceDB {
    private static final Pattern DESCRIPTOR_PATTERN = Pattern.compile("\"descriptor\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);
    private static final Pattern PERSON_OBJECT_PATTERN = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);
    private static final double MATCH_DISTANCE_THRESHOLD = 0.45;

    /**
     * 顔の特徴を蓄積、検索するクラス
     */
    private final Path dbpath; // デフォルトは .local/facedb/のどこかにする
    private final ArrayList<double[]> descriptors = new ArrayList<>();
    private final ArrayList<FaceSample> faceSamples = new ArrayList<>();
    private final ArrayList<String> persons = new ArrayList<>();
    
    public FaceDB( Path dbpath ) {
        this.dbpath = dbpath;
    }

    public synchronized void load() {
        this.persons.clear();
        this.descriptors.clear();
        this.faceSamples.clear();
        try{
            Path path = this.dbpath.resolve( "persons.json" );
            if (Files.isRegularFile(path)) {
                loadPersons(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch( Throwable ex ) {
            ex.printStackTrace();
        }
        for (int faceIdx = 0; ; faceIdx++) {
            try{
            Path path = this.dbpath.resolve(externalFaceId(faceIdx) + ".json");
                if (!Files.isRegularFile(path)) {
                    return;
                }
                String json = Files.readString(path, StandardCharsets.UTF_8);
                this.descriptors.add(descriptor(json));
                this.faceSamples.add(faceSample(json, faceIdx));
            } catch( Throwable ex ) {
                ex.printStackTrace();
                return;
            }
        }
    }
    private synchronized void savePersons() {
        try {
            Path path = this.dbpath.resolve( "persons.json" );
            Files.createDirectories(this.dbpath);
            Files.writeString(path, personsJson() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch( Throwable ex ) {
                ex.printStackTrace();     
        }
    }
    /**
     * 顔サンプルと特徴量を this.dbpath 配下の face000000.json 形式のファイルへ保存します。
     */
    private synchronized Path saveFaceSample(int faceIdx) {
        try {
            FaceSample faceSample = this.faceSamples.get(faceIdx);
            double descriptor[] = this.descriptors.get(faceIdx);
            Files.createDirectories(this.dbpath);
            Path path = this.dbpath.resolve(externalFaceId(faceSample.faceIdx) + ".json");
            String json = Json.object(Json.fields(
                    "faceId", externalFaceId(faceSample.faceIdx),
                    "createdAt", faceSample.createdAt,
                    "personId", externalPersonIdOrUnknown(faceSample.getPersonIdx()),
                    "descriptor", Json.raw(descriptorJson(descriptor))));
            Files.writeString(path, json + System.lineSeparator(), StandardCharsets.UTF_8);
            return path.toAbsolutePath().normalize();
        } catch( Throwable ex ) {
            ex.printStackTrace();
            return this.dbpath.resolve(externalFaceId(faceIdx) + ".json").toAbsolutePath().normalize();
        }
    }

    /**
     * 顔サンプルに対応する JPEG 画像を this.dbpath 配下の face000000.jpg 形式のファイルへ保存します。
     */
    private synchronized Path saveFaceImage(int faceIdx, String base64) {
        try {
            if (base64 == null || base64.isBlank()) {
                throw new IllegalArgumentException("base64 is required");
            }
            Files.createDirectories(this.dbpath);
            Path path = this.dbpath.resolve(externalFaceId(faceIdx) + ".jpg");
            Files.write(path, decodeJpegBase64(base64));
            return path.toAbsolutePath().normalize();
        } catch( Throwable ex ) {
            ex.printStackTrace();
            return this.dbpath.resolve(externalFaceId(faceIdx) + ".jpg").toAbsolutePath().normalize();
        }
    }

    private static String descriptorJson(double[] descriptor) {
        StringBuilder json = new StringBuilder(descriptor.length * 8 + 2);
        json.append('[');
        for (int i = 0; i < descriptor.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(descriptor[i]);
        }
        json.append(']');
        return json.toString();
    }

    private static FaceSample faceSample(String json, int defaultSampleId) {
        String faceId = JsonFields.stringOrDefault(json, "faceId", externalFaceId(defaultSampleId));
        int faceIdx = parseFaceIdx(faceId);
        long createdAt = JsonFields.longOrDefault(json, "createdAt", 0L);
        String personIdText = JsonFields.stringOrDefault(json, "personId", "");
        int personId = parseOptionalPersonId(personIdText);
        return new FaceSample(faceIdx, createdAt, personId);
    }

    private static double[] descriptor(String json) {
        Matcher matcher = DESCRIPTOR_PATTERN.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("descriptor is required");
        }
        String[] parts = matcher.group(1).split(",");
        ArrayList<Double> numbers = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                numbers.add(Double.parseDouble(trimmed));
            }
        }
        double[] values = new double[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            values[i] = numbers.get(i);
        }
        return values;
    }

    private static byte[] decodeJpegBase64(String base64) {
        String jpegDataUrlPrefix = "data:image/jpeg;base64,";
        if (base64.startsWith(jpegDataUrlPrefix)) {
            return Base64.getDecoder().decode(base64.substring(jpegDataUrlPrefix.length()));
        }
        return Base64.getDecoder().decode(base64);
    }

    private void loadPersons(String json) {
        Matcher objectMatcher = PERSON_OBJECT_PATTERN.matcher(json);
        while (objectMatcher.find()) {
            String object = objectMatcher.group();
            String personIdText = JsonFields.stringOrDefault(object, "personId", "");
            String name = JsonFields.stringOrDefault(object, "name", "");
            int personId = parseOptionalPersonId(personIdText);
            if (personId >= 0 && !name.isBlank()) {
                ensurePersonCapacity(personId);
                this.persons.set(personId, name);
            }
        }
    }

    private String personsJson() {
        StringBuilder json = new StringBuilder(this.persons.size() * 48 + 2);
        json.append('[');
        for (int i = 0; i < this.persons.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(Json.object(Json.fields(
                    "personId", externalPersonId(i),
                    "name", this.persons.get(i))));
        }
        json.append(']');
        return json.toString();
    }

    private int personId(String name) {
        String normalizedName = normalizeName(name);
        if (normalizedName.isEmpty()) {
            return -1;
        }
        int existingIndex = this.persons.indexOf(normalizedName);
        if (existingIndex >= 0) {
            return existingIndex;
        }
        this.persons.add(normalizedName);
        this.savePersons();
        return this.persons.size() - 1;
    }

    private void ensurePersonCapacity(int personId) {
        while (this.persons.size() <= personId) {
            this.persons.add("");
        }
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private static int parseFaceIdx(String faceid) {
        String value = faceid == null ? "" : faceid.trim();
        if (!value.matches("face\\d{6}")) {
            throw new IllegalArgumentException("faceId must be face999999 format");
        }
        return Integer.parseInt(value.substring("face".length()));
    }

    private static int parseOptionalPersonId(String personId) {
        String value = personId == null ? "" : personId.trim();
        if (value.isEmpty() || "unknown".equals(value)) {
            return -1;
        }
        if (!value.matches("person\\d{4}")) {
            throw new IllegalArgumentException("personId must be person9999 format");
        }
        return Integer.parseInt(value.substring("person".length()));
    }

    private static String externalFaceId(int faceId) {
        return "face%06d".formatted(faceId);
    }

    private static String externalPersonId(int personId) {
        return "person%04d".formatted(personId);
    }

    private static String externalPersonIdOrUnknown(int personId) {
        return personId < 0 ? "unknown" : externalPersonId(personId);
    }

    private static final class FaceSample {
        private final int faceIdx;
        private final long createdAt;
        private int personId = -1;

        private FaceSample(int faceIdx, long createdAt ) {
            this.faceIdx = faceIdx;
            this.createdAt = createdAt;
        }

        private FaceSample(int faceIdx, long createdAt, int personId ) {
            this.faceIdx = faceIdx;
            this.createdAt = createdAt;
            this.setPersonId(personId);
        }

        private void setPersonId(int personId) {
            this.personId = personId;
        }

        private int getPersonIdx() {
            return this.personId;
        }
    }
    public synchronized FacePossibility.PersonPossibility[] predict( double[] descriptor ) {
        ArrayList<FacePossibility.PersonPossibility> possibilities = new ArrayList<>();
        for( int i=0,n=this.descriptors.size(); i<n; i++ ) {
            FaceSample faceSample = this.faceSamples.get(i);
            if( faceSample != null && faceSample.getPersonIdx() >= 0) {
                int personIdx = faceSample.getPersonIdx();
                String name = personIdx < this.persons.size() ? this.persons.get(personIdx) : "";
                if (name == null || name.isBlank()) {
                    continue;
                }
                double distance = distance(descriptor, this.descriptors.get(i));
                if (distance <= MATCH_DISTANCE_THRESHOLD) {
                    possibilities.add(new FacePossibility.PersonPossibility(
                            externalPersonId(personIdx),
                            name,
                            (float) distance));
                }
            }
        }
        possibilities.sort(Comparator.comparingDouble(possibility -> possibility.distance));
        return possibilities.toArray(new FacePossibility.PersonPossibility[possibilities.size()]);
    }
    public synchronized FacePossibility register( double[] descriptor, String picture ) {
        FacePossibility.PersonPossibility[] possibilities = predict(descriptor);
        // メモリに保存
        int faceId = this.descriptors.size();
        this.descriptors.add(descriptor);
        this.faceSamples.add(new FaceSample( faceId, System.currentTimeMillis() ));
        // ファイルに保存する
        Path jsonPath = this.saveFaceSample(faceId);
        Path imagePath = this.saveFaceImage(faceId,picture);
        return new FacePossibility(
                externalFaceId(faceId),
                jsonPath.toString(),
                imagePath.toString(),
                possibilities);
    }
    public synchronized void assign( String faceId, String name ) {
        int faceIdx = -1;
        try {
            faceIdx = parseFaceIdx(faceId);
            FaceSample old = this.faceSamples.get(faceIdx);
            old.setPersonId(personId(name));
            // ファイルに保存する
            this.saveFaceSample(faceIdx);
        } catch( Throwable ex ) {
            return;
        }
    }

    /**
     * 顔 ID から登録済み人物 ID を探し、その人物名を変更します。
     */
    public synchronized void rename(String faceId, String newName) {
        try {
            int faceIdx = parseFaceIdx(faceId);
            FaceSample faceSample = this.faceSamples.get(faceIdx);
            int personIdx = faceSample.getPersonIdx();
            String normalizedName = normalizeName(newName);
            if (personIdx < 0 || personIdx >= this.persons.size() || normalizedName.isEmpty()) {
                return;
            }
            this.persons.set(personIdx, normalizedName);
            this.savePersons();
            for (int i = 0; i < this.faceSamples.size(); i++) {
                if (this.faceSamples.get(i).getPersonIdx() == personIdx) {
                    this.saveFaceSample(i);
                }
            }
        } catch( Throwable ex ) {
            return;
        }
    }

    /**
     * 顔特徴量の近さをユークリッド距離で計算します。
     */
    private static double distance(double[] left, double[] right) {
        if (left.length != right.length) {
            return Double.MAX_VALUE;
        }
        double total = 0;
        for (int i = 0; i < left.length; i++) {
            double diff = left[i] - right[i];
            total += diff * diff;
        }
        return Math.sqrt(total);
    }
}
