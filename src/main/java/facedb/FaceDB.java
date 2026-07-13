package facedb;

import json.Json;
import json.JsonFields;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FaceDB {
    private static final Pattern DESCRIPTOR_PATTERN = Pattern.compile("\"descriptor\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);
    private static final Pattern PERSON_OBJECT_PATTERN = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);
    private static final double MATCH_DISTANCE_THRESHOLD = 0.45;
    private final static String TRACK_ID_PREFIX="trak-";
    private final static String FACE_ID_PREFIX="face-";
    /**
     * 顔の特徴を蓄積、検索するクラス
     */
    private final Path dbpath; // デフォルトは .local/facedb/のどこかにする
    private long maxTrackIdx;
    private long maxFaceIdx;
    private HashMap<String,FaceTrack> tracks = new HashMap<>();
    private final ArrayList<String> persons = new ArrayList<>();
    
    public FaceDB( Path dbpath ) {
        this.dbpath = dbpath;
    }

    private static final boolean is_numchar(char cc ) {
        return '0'<=cc && cc<='9';
    }
    private static final long to_number( String prefix, String value ) {
        try {
            if( value!=null && value.startsWith(prefix)) {
                int st=prefix.length();
                int ed=value.length();
                // 後ろの拡張子部分をスキップ
                for( ; ed>0 && !is_numchar(value.charAt(ed-1)); ed--);
                // 先頭のプレフィックスをスキップ
                for( ; st<ed && value.charAt(st)=='0'; st++);
                try {
                    return Long.parseLong(value.substring(st,ed));
                } catch( Error ex ) {}
            }
        } catch( Throwable ex ) {
        }
        return -1;
    }
    private static final long to_trackIdx( String trackId ) {
        return to_number(TRACK_ID_PREFIX, trackId );
    }
    private static final String to_trackId( long trackIdx ) {
        return TRACK_ID_PREFIX + String.format("%06d", trackIdx);
    }
    private static final long to_faceIdx( String faceId ) {
        return to_number(FACE_ID_PREFIX, faceId );
    }
    private static final String to_faceId( long faceIdx ) {
        return FACE_ID_PREFIX + String.format("%06d", faceIdx);
    }

    private synchronized String createTrackId() throws IOException {
        try {
            // ディレクトリが作成できたら成功
            for( int i=0; i<1000; i++) {
                String trackId = to_trackId( this.maxTrackIdx++ );
                Path trackDIr = this.dbpath.resolve(trackId);
                try {
                    Files.createDirectory(trackDIr);
                    FaceTrack track = new FaceTrack(trackId);
                    this.tracks.put(trackId,track);
                    return trackId;
                }catch(FileAlreadyExistsException ex ) {
                    continue;
                }
            }
            throw new IOException("can not create trackId");
        } catch( IOException ex ) {
            throw ex;
        } catch( Exception ex ) {
            throw new IOException("can not create trackId",ex);
        }
    }
    private synchronized String createFaceId(long trackIdx) throws IOException {
        String trackId = to_trackId(trackIdx);
        FaceTrack track = this.tracks.get(trackId);
        if( track==null ) {
            throw new IOException(trackId+" is not found");
        }
        try {
            Path trackDir = this.dbpath.resolve(to_trackId(trackIdx));
            if( Files.isDirectory(trackDir) ) {
                // ディレクトリが作成できたら成功
                for( int i=0; i<1000; i++) {
                    String faceId = to_faceId( this.maxFaceIdx++ );
                    Path faceDir = trackDir.resolve(faceId);
                    try {
                        Files.createDirectory(faceDir);
                        return faceId;
                    }catch(FileAlreadyExistsException ex ) {
                        continue;
                    }
                }
            }
            throw new IOException("can not create trackId");
        } catch( IOException ex ) {
            throw ex;
        } catch( Exception ex ) {
            throw new IOException("can not create trackId",ex);
        }
    }
    public synchronized void load() throws IOException{
        this.persons.clear();
        this.tracks.clear();
        try{
            Path path = this.dbpath.resolve( "persons.json" );
            if (Files.isRegularFile(path)) {
                loadPersons(Files.readString(path, StandardCharsets.UTF_8));
            }
        } catch( Throwable ex ) {
            ex.printStackTrace();
        }
        // 最後の番号を探す
        try( Stream<Path> st = Files.list(this.dbpath) ) {
            for( Iterator<Path> it = st.iterator(); it.hasNext(); ) {
                Path trackDir = it.next();
                if( !Files.isDirectory(trackDir) ) continue;
                String name = trackDir.getFileName().toString();
                long trackIdx = to_trackIdx(name);
                if( trackIdx>=0 ) {
                    FaceTrack track = new FaceTrack(trackIdx);
                    this.tracks.put(track.trackId,track);
                    try( Stream<Path> stt = Files.list(trackDir) ) {
                        for( Iterator<Path> iit = stt.iterator(); iit.hasNext(); ) {
                            Path faceFile = iit.next();
                            if( !Files.isRegularFile(faceFile) ) continue;
                            long faceIdx = to_faceIdx( faceFile.getFileName().toString() );
                            if( faceIdx>=0 ) {
                                if( faceIdx>this.maxFaceIdx ) {
                                    this.maxFaceIdx = faceIdx;
                                }
                                String json = Files.readString(faceFile, StandardCharsets.UTF_8);
                                FaceSample face = new FaceSample(trackIdx, faceIdx, json );
                                track.add(face);
                            }
                        }
                    }
                }
                if( trackIdx>this.maxTrackIdx ) {
                    this.maxTrackIdx = trackIdx;
                }
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
    // private synchronized Path saveFaceSample(int faceIdx) {
    //     try {
    //         FaceSample faceSample = this.faceSamples.get(faceIdx);
    //         double descriptor[] = this.descriptors.get(faceIdx);
    //         Path trackDir = this.dbpath.resolve( to_trackId(faceSample.trackId) );
    //         Files.createDirectories(trackDir);
    //         Path path = trackDir.resolve( faceSample.trackId + ".json");
    //         String json = Json.object(Json.fields(
    //                 "trackId", faceSample.trackId,
    //                 "faceId", externalFaceId(faceSample.faceIdx),
    //                 "createdAt", faceSample.createdAt,
    //                 "personId", externalPersonIdOrUnknown(faceSample.getPersonIdx()),
    //                 "descriptor", Json.raw(descriptorJson(descriptor))));
    //         Files.writeString(path, json + System.lineSeparator(), StandardCharsets.UTF_8);
    //         return path.toAbsolutePath().normalize();
    //     } catch( Throwable ex ) {
    //         ex.printStackTrace();
    //         return this.dbpath.resolve(externalFaceId(faceIdx) + ".json").toAbsolutePath().normalize();
    //     }
    // }

    /**
     * 顔サンプルに対応する JPEG 画像を this.dbpath 配下の face000000.jpg 形式のファイルへ保存します。
     */
    // private synchronized Path saveFaceImage(int faceIdx, String base64) {
    //     try {
    //         if (base64 == null || base64.isBlank()) {
    //             throw new IllegalArgumentException("base64 is required");
    //         }
    //         Files.createDirectories(this.dbpath);
    //         Path path = this.dbpath.resolve(externalFaceId(faceIdx) + ".jpg");
    //         Files.write(path, decodeJpegBase64(base64));
    //         return path.toAbsolutePath().normalize();
    //     } catch( Throwable ex ) {
    //         ex.printStackTrace();
    //         return this.dbpath.resolve(externalFaceId(faceIdx) + ".jpg").toAbsolutePath().normalize();
    //     }
    // }

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

    // private static String externalFaceId(int faceId) {
    //     return "face%06d".formatted(faceId);
    // }

    private static String externalPersonId(int personId) {
        return "person%04d".formatted(personId);
    }

    private static String externalPersonIdOrUnknown(int personId) {
        return personId < 0 ? "unknown" : externalPersonId(personId);
    }

    private static final class FaceTrack {
        private final long trackIdx;
        private final String trackId;
        private final long createdAt;
        private int personId = -1;
        private final ArrayList<FaceSample> faceSamples = new ArrayList<>();
        public FaceTrack(long trackIdx) {
            this.trackIdx = trackIdx;
            this.trackId = to_trackId(trackIdx);
            this.createdAt = System.currentTimeMillis();
        }
        public FaceTrack(String trackId) {
            this.trackId = trackId;
            this.trackIdx = to_trackIdx(trackId);
            this.createdAt = System.currentTimeMillis();
        }
        public FaceTrack(long trackIdx, String json ) {
            this.trackIdx = trackIdx;
            this.trackId = to_trackId(trackIdx);
            this.createdAt = JsonFields.longOrDefault(json, "createdAt", 0L);
            String personIdText = JsonFields.stringOrDefault(json, "personId", "");
            this.personId = parseOptionalPersonId(personIdText);
        }
        public String to_json() {
            String json = Json.object(Json.fields(
                    "trackId", this.trackId,
                    "createdAt", this.createdAt,
                    "personId", this.personId));
            return json;
        }
        public void save(Path dir ) throws IOException {
            Path trackDir = dir.resolve( this.trackId );
            Path jsonPath = trackDir.resolve( this.trackId + ".json");
            String json = this.to_json();
            Files.writeString(jsonPath, json + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        public void add(FaceSample face ) {
            this.faceSamples.add(face);
        }

        private void setPersonId(int personId) {
            this.personId = personId;
        }

        private int getPersonIdx() {
            return this.personId;
        }
        public double min_distance( double[] descriptor ) {
            double min = Double.MAX_VALUE;
            for( FaceSample face : this.faceSamples ) {
                min = Math.min(min,face.distance(descriptor));
            }
            return min;
        }
    }
    public synchronized FacePossibility.PersonPossibility[] predict( double[] descriptor ) {
        ArrayList<FacePossibility.PersonPossibility> possibilities = new ArrayList<>();
        for( FaceTrack track : this.tracks.values() ) {
            int personIdx = track.getPersonIdx();
            String name = personIdx < this.persons.size() ? this.persons.get(personIdx) : "";
            if (name == null || name.isBlank()) {
                continue;
            }
            double distance = track.min_distance(descriptor);
            if (distance <= MATCH_DISTANCE_THRESHOLD) {
                possibilities.add(new FacePossibility.PersonPossibility(
                        externalPersonId(personIdx),
                        name,
                        (float) distance));
            }
        }
        possibilities.sort(Comparator.comparingDouble(possibility -> possibility.distance));
        return possibilities.toArray(new FacePossibility.PersonPossibility[possibilities.size()]);
    }

    private static final class FaceSample {
        private final long trackIdx;
        private final long faceIdx;
        private final String trackId;
        private final String faceId;
        private final long createdAt;
        private double[] descriptor;

        private FaceSample( long trackIdx, long faceIdx) {
            this.trackIdx = trackIdx;
            this.trackId = to_trackId(trackIdx);
            this.faceIdx = faceIdx;
            this.faceId = to_faceId(faceIdx);
            this.createdAt = System.currentTimeMillis();
        }
        private FaceSample(long trackIdx, long faceIdx, String json ) {
            this.trackIdx = trackIdx;
            this.trackId = to_trackId(trackIdx);
            this.faceIdx = faceIdx;
            this.faceId = to_faceId(faceIdx);
            this.createdAt = JsonFields.longOrDefault(json, "createdAt", 0L);
            this.descriptor = descriptor(json);
        }
        public String to_json() {
            String json = Json.object(Json.fields(
                "trackId", this.trackId,
                    "faceId", this.faceIdx,
                    "createdAt", this.createdAt,
                    "descriptor", Json.raw(descriptorJson(this.descriptor))));
            return json;
        }
        public double distance(double[] right) {
            return FaceDB.distance(this.descriptor,right);
        }

    }

    public synchronized FacePossibility register( String trackId, double[] descriptor, String picture ) throws IOException {
        FaceTrack track = this.tracks.get(trackId);
        if( track==null ) {
            return null;
        }
        FacePossibility.PersonPossibility[] possibilities = predict(descriptor);
        // メモリに保存
        String faceId = this.createFaceId(track.trackIdx);
        FaceSample faceSample = new FaceSample(track.trackIdx,to_faceIdx(faceId));
        track.add(faceSample);
        // ファイルに保存する
        Path trackDir = this.dbpath.resolve( track.trackId );
        Path faceDir = trackDir.resolve( faceSample.faceId );
        Path jsonPath = faceDir.resolve( faceSample.faceId + ".json");
        String json = faceSample.to_json();
        Files.writeString(jsonPath, json + System.lineSeparator(), StandardCharsets.UTF_8);
        if(picture!=null&&picture.length()>0) {
            Path picturePath = faceDir.resolve( faceSample.faceId + ".jpg");
            Files.write(picturePath, decodeJpegBase64(picture));
        }
        return new FacePossibility( trackId, faceId, possibilities );
    }
    public synchronized void assign( String trackId, String name ) {
        try {
            FaceTrack track = this.tracks.get(trackId);
            track.setPersonId(personId(name));
            // ファイルに保存する
            track.save(this.dbpath);   
        } catch( Throwable ex ) {
            return;
        }
    }

    /**
     * 顔 ID から登録済み人物 ID を探し、その人物名を変更します。
     */
    public synchronized void rename(String trackId, String newName) {
        try {
            FaceTrack track = this.tracks.get(trackId);
            if( track==null ) {
                return;
            }
            int personIdx = track.getPersonIdx();
            String normalizedName = normalizeName(newName);
            if (personIdx < 0 || personIdx >= this.persons.size() || normalizedName.isEmpty()) {
                return;
            }
            this.persons.set(personIdx, normalizedName);
            this.savePersons();
        } catch( Throwable ex ) {
            return;
        }
    }

    /**
     * 顔特徴量の近さをユークリッド距離で計算します。
     */
    public static double distance(double[] left, double[] right) {
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
