package facedb;

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

import json.Json;
import json.JsonFields;

public class FaceDB {
    private static final Pattern DESCRIPTOR_PATTERN = Pattern.compile("\"descriptor\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);
    private static final Pattern PERSON_OBJECT_PATTERN = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);
    private static final Pattern FEATURE_RANGE_PATTERN = Pattern.compile(
            "\"featureRange\"\\s*:\\s*\\{\\s*\"center\"\\s*:\\s*\\[([^\\]]*)]\\s*,\\s*\"radius\"\\s*:\\s*([-+0-9.eE]+)\\s*}",
            Pattern.DOTALL);
    private static final double MATCH_DISTANCE_THRESHOLD = 0.45;
    private final static String TRACK_ID_PREFIX="trak-";
    private final static String SAMPLE_ID_PREFIX="sample-";
    private final static String PERSON_PREFIX="person";
    /**
     * 顔の特徴を蓄積、検索するクラス
     */
    private final Path store; // デフォルトは .local/facedb/のどこかにする
    private long maxTrackIdx;
    private HashMap<String,FaceTrack> tracks = new HashMap<>();
    private final ArrayList<String> persons = new ArrayList<>();
    private final Object assignmentSaveLock = new Object();

    public FaceDB( Path dbpath ) {
        this.store = dbpath;
    }

    private static boolean is_numchar(char cc ) {
        return '0'<=cc && cc<='9';
    }
    private static long to_number( String prefix, String value ) {
        try {
            if( value!=null && value.startsWith(prefix)) {
                int st=prefix.length();
                int ed=value.length();
                // 後ろの拡張子部分をスキップ
                for( ; ed>0 && !is_numchar(value.charAt(ed-1)); ed--);
                // 先頭のプレフィックスをスキップ
                for( ; st<ed && value.charAt(st)=='0'; st++);
                try {
                    if (st == ed) {
                        return 0;
                    }
                    return Long.parseLong(value.substring(st,ed));
                } catch( Exception ex ) {}
            }
        } catch( Throwable ex ) {
        }
        return -1;
    }
    private static long to_trackIdx( String trackId ) {
        return to_number(TRACK_ID_PREFIX, trackId );
    }
    private static String to_trackId( long trackIdx ) {
        return TRACK_ID_PREFIX + String.format("%06d", trackIdx);
    }
    private static long to_sampleIdx( String sampleId ) {
        return to_number(SAMPLE_ID_PREFIX, sampleId );
    }
    private static String to_sampleId( long sampleIdx ) {
        return SAMPLE_ID_PREFIX + String.format("%06d", sampleIdx);
    }
    private static int to_personIdx( String personId ) {
        return (int)to_number(PERSON_PREFIX,personId);
    }
    private static String to_personId( int personIdx ) {
        return PERSON_PREFIX + String.format("%04d",personIdx);
    }

    public synchronized String createTrackId() throws IOException {
        try {
            Files.createDirectories(this.store);
            // ディレクトリが作成できたら成功
            for( int i=0; i<1000; i++) {
                String trackId = to_trackId( this.maxTrackIdx++ );
                Path trackDIr = this.store.resolve(trackId);
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
    /**
     * トラッキングが終了した時にコールされる
     */
    public final void finish( String trackId ) {
        FaceTrack track;
        synchronized(this) {
            track = this.tracks.get(trackId);
        }
        if( track != null ) {
            track.finish();
        }
    }

    private void removeAll( Path path ) {
        try {
            if( Files.isDirectory(this.store) && path.startsWith(this.store) ) {
                if( Files.isDirectory(path) ) {
                    try( Stream<Path> ds = Files.list(path) ) {
                        ds.forEach( child -> removeAll(child) );
                    }
                }
                Files.deleteIfExists(path);
            }
        } catch( IOException ex ) {}
    }

    public synchronized void load() throws IOException{
        this.persons.clear();
        this.tracks.clear();
        Path personsPath = this.store.resolve( "persons.json" );
        try{

            if (Files.isRegularFile(personsPath)) {
                loadPersons(Files.readString(personsPath, StandardCharsets.UTF_8));
            }
        } catch( Throwable ex ) {
            ex.printStackTrace();
        }
        // 最後の番号を探す
        if( Files.isDirectory(this.store) ) {
            try( Stream<Path> st = Files.list(this.store) ) {
                for( Iterator<Path> trackIt = st.iterator(); trackIt.hasNext(); ) {
                    Path trackDir = trackIt.next();
                    if( trackDir.equals(personsPath)) {
                        continue;
                    }
                    long trackIdx = to_trackIdx(trackDir.getFileName().toString());
                    Path trackFile = trackDir.resolve(to_trackId(trackIdx)+".json");
                    if( trackIdx>=0 && Files.isRegularFile(trackFile) ) {
                        FaceTrack track = new FaceTrack(trackIdx, Files.readString(trackFile, StandardCharsets.UTF_8));
                        track.load();
                        if( track.size()>0 && ( track.getPersonIdx()>=0 || ((System.currentTimeMillis()-track.updateAt)/1000/3600/24)<3 ) ) {
                            this.tracks.put(track.trackId,track);
                            this.maxTrackIdx = Math.max(this.maxTrackIdx, trackIdx + 1);
                            continue;
                        }
                    }
                    // ロードしないなら削除しちゃう
                    removeAll(trackDir);
                }
            }
        }
    }


    private void savePersons(String json) {
        try {
            Path path = this.store.resolve( "persons.json" );
            Files.createDirectories(this.store);
            Files.writeString(path, json + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("can not save persons", ex);
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

    private static double[] descriptor(String json) {
        Matcher matcher = DESCRIPTOR_PATTERN.matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("descriptor is required");
        }
        return descriptorValues(matcher.group(1));
    }

    private static double[] descriptorValues(String valuesJson) {
        String[] parts = valuesJson.split(",");
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
        for (int personIdx = 0; personIdx < this.persons.size(); personIdx++) {
            if (personIdx > 0) {
                json.append(',');
            }
            json.append(Json.object(Json.fields("personId", to_personId(personIdx),
                    "name", this.persons.get(personIdx))));
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

    /**
     * 顔特徴量群を包含する超球です。
     * 中心は各次元の算術平均、半径は中心から各特徴量までの最大距離です。
     */
    private static final class FeatureRange {
        private final double[] center;
        private final double radius;

        private FeatureRange(double[] center, double radius) {
            this.center = center;
            this.radius = radius;
        }

        private static FeatureRange from_json(String json) {
            Matcher matcher = FEATURE_RANGE_PATTERN.matcher(json);
            if (!matcher.find()) {
                return null;
            }
            return new FeatureRange(descriptorValues(matcher.group(1)), Double.parseDouble(matcher.group(2)));
        }

        private String to_json() {
            return Json.object(Json.fields(
                    "center", Json.raw(descriptorJson(this.center)),
                    "radius", this.radius));
        }

        /**
         * 2 個の超球が少なくとも 1 点を共有するか判定します。
         */
        private boolean isMatch(FeatureRange other) {
            return other != null && this.center.length > 0 && other.center.length > 0
                    && FaceDB.distance(this.center, other.center) <= this.radius + other.radius;
        }
    }

    private final class FaceTrack {
        private final Path trackDir;
        private final long trackIdx;
        private final String trackId;
        private final long createdAt;
        private long updateAt;
        private int personIdx = -1;
        private FeatureRange range;
        private final ArrayList<FaceSample> faceSamples = new ArrayList<>();

        private FaceTrack( long trackIdx,long createdAt, int personIdx ) {
            if( trackIdx<0 ) {
                throw new IllegalArgumentException("trackIdx:"+trackIdx+" is invalid");
            }
            this.trackIdx = trackIdx;
            this.trackId = to_trackId(trackIdx);
            this.trackDir = FaceDB.this.store.resolve(trackId).normalize();
            if( !Files.isDirectory(this.trackDir) ) {
                throw new IllegalArgumentException("trackDir:"+this.trackDir.toString()+" is not directory");
            }
            this.createdAt = createdAt>0 ? createdAt : System.currentTimeMillis();
            this.updateAt = this.createdAt;
            this.personIdx = personIdx>=0 ? personIdx : -1;
        }
        public FaceTrack(long trackIdx) {
            this(trackIdx,-1,-1);
        }
        public FaceTrack(String trackId) {
            this(to_trackIdx(trackId));
        }
        public FaceTrack(long trackIdx, String json ) {
            this(
                trackIdx,
                JsonFields.longOrDefault(json, "createdAt", 0L),
                to_personIdx(JsonFields.stringOrDefault(json, "personId", ""))
            );
            this.range = FeatureRange.from_json(json);
        }
        private String to_json() {
            String json = Json.object(Json.fields(
                    "trackId", this.trackId,
                    "createdAt", this.createdAt,
                    "personId", to_personId(this.personIdx),
                    "featureRange", this.range == null ? null : Json.raw(this.range.to_json())));
            return json;
        }
        public synchronized void save() throws IOException {
            Files.createDirectories(this.trackDir);
            Path jsonPath = this.trackDir.resolve( this.trackId + ".json");
            String json = this.to_json();
            Files.writeString(jsonPath, json + System.lineSeparator(), StandardCharsets.UTF_8);
        }
        public void load() throws IOException {
            this.faceSamples.clear();
            for(int i=0; i<1000; i++ ) {
                long sampleIdx = this.faceSamples.size();
                String sampleId = to_sampleId(sampleIdx);
                Path jsonPath = this.trackDir.resolve( sampleId+".json");
                if( !Files.isRegularFile(jsonPath) ) {
                    break;
                }
                String json = Files.readString(jsonPath, StandardCharsets.UTF_8);
                FaceSample sample = new FaceSample(sampleIdx, json);
                this.faceSamples.add(sample);
                if( this.updateAt <sample.createdAt ) {
                    this.updateAt = sample.createdAt;
                }
            }
        }

        public int size() {
            return this.faceSamples.size();
        }

        public synchronized FaceSample register( double[] descriptor, String picture ) throws IOException {
            long sampleIdx = this.faceSamples.size();
            FaceSample sample = new FaceSample(sampleIdx,descriptor,-1);
            this.faceSamples.add(sample);
            if( this.updateAt <sample.createdAt ) {
                this.updateAt = sample.createdAt;
            }
            sample.save(picture);
            return sample;
        }

        private synchronized void setPersonIdx(int personIdx) {
            this.personIdx = personIdx;
        }

        private synchronized int getPersonIdx() {
            return this.personIdx;
        }

        private synchronized FeatureRange getRange() {
            return this.range;
        }

        public synchronized double min_distance( double[] descriptor ) {
            double min = Double.MAX_VALUE;
            for( FaceSample face : this.faceSamples ) {
                min = Math.min(min,face.distance(descriptor));
            }
            return min;
        }

        /**
         * このトラックと指定トラックのサンプル対に、距離しきい値未満の組み合わせがあるか判定します。
         */
        private synchronized boolean matches(FaceTrack other, double threshold) {
            for (FaceSample sample : this.faceSamples) {
                if (other.min_distance(sample.descriptor) < threshold) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 登録済みの特徴ベクトルから中心と半径を計算します。
         */
        public synchronized FeatureRange feature_range() {
            if (this.faceSamples.isEmpty()) {
                return new FeatureRange(new double[0], 0.0);
            }

            int dimensions = this.faceSamples.get(0).descriptor.length;
            double[] center = new double[dimensions];
            for (FaceSample face : this.faceSamples) {
                if (face.descriptor.length != dimensions) {
                    throw new IllegalStateException("face descriptor dimensions are inconsistent");
                }
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    center[dimension] += face.descriptor[dimension];
                }
            }
            for (int dimension = 0; dimension < dimensions; dimension++) {
                center[dimension] /= this.faceSamples.size();
            }
            double radius = 0.0;
            for (FaceSample face : this.faceSamples) {
                radius = Math.max(radius, FaceDB.distance(center, face.descriptor));
            }
            return new FeatureRange(center, radius);
        }

        /**
         * トラッキングが終了した時にコールされる
         */
        public synchronized void finish() {
            this.range = this.feature_range();
            try {
                this.save();
            } catch (IOException ex) {
                throw new IllegalStateException("can not save finished track: " + this.trackId, ex);
            }
        }

        private final class FaceSample {
            private final long sampleIdx;
            private final String sampleId;
            private final long createdAt;
            private final double[] descriptor;

            private FaceSample( long sampleIdx, double[] descriptor, long createdAt) {
                this.sampleIdx = sampleIdx;
                this.sampleId = to_sampleId(sampleIdx);
                this.createdAt = createdAt>0 ? createdAt : System.currentTimeMillis();
                this.descriptor = descriptor;
            }
            private FaceSample(long sampleIdx, String json ) {
                this(sampleIdx,
                    descriptor(json),
                    JsonFields.longOrDefault(json, "createdAt", 0L)
                );
            }
            public String to_json() {
                String json = Json.object(Json.fields("trackId", FaceTrack.this.trackId,
                        "sampleId", this.sampleId,
                        "createdAt", this.createdAt,
                        "descriptor", Json.raw(descriptorJson(this.descriptor))));
                return json;
            }
            public void save(String picture) throws IOException {
                Files.createDirectories(FaceTrack.this.trackDir);
                Path jsonPath = FaceTrack.this.trackDir.resolve(this.sampleId+".json");
                String json = this.to_json();
                Files.writeString(jsonPath, json + System.lineSeparator(), StandardCharsets.UTF_8);
                if(picture!=null&&picture.length()>0) {
                    Path picturePath = FaceTrack.this.trackDir.resolve(this.sampleId+".jpg");
                    Files.write(picturePath, decodeJpegBase64(picture));
                }
            }
            public double distance(double[] right) {
                return FaceDB.distance(this.descriptor,right);
            }
        }
    }
    public synchronized FacePossibility.PersonPossibility[] predict( double[] descriptor ) {
        ArrayList<FacePossibility.PersonPossibility> possibilities = new ArrayList<>();
        for( FaceTrack track : this.tracks.values() ) {
            int personIdx = track.getPersonIdx();
            if (personIdx < 0) {
                continue;
            }
            String name = personIdx < this.persons.size() ? this.persons.get(personIdx) : "";
            if (name == null || name.isBlank()) {
                continue;
            }
            double distance = track.min_distance(descriptor);
            if (distance <= MATCH_DISTANCE_THRESHOLD) {
                possibilities.add(new FacePossibility.PersonPossibility(
                        to_personId(personIdx),
                        name,
                        (float) distance));
            }
        }
        possibilities.sort(Comparator.comparingDouble(possibility -> possibility.distance));
        return possibilities.toArray(new FacePossibility.PersonPossibility[possibilities.size()]);
    }

    public synchronized FacePossibility register( String trackId, double[] descriptor, String picture ) throws IOException {
        FaceTrack track = this.tracks.get(trackId);
        if( track==null ) {
            throw new IllegalArgumentException("trackId is not found: " + trackId);
        }
        FacePossibility.PersonPossibility[] possibilities = predict(descriptor);
        // メモリに保存
        FaceTrack.FaceSample sample = track.register(descriptor,picture);

        return new FacePossibility( trackId, sample.sampleId, possibilities );
    }

    /**
     * 人物名を割り当てる
     */
    public void assign( String trackId, String name ) {
        assign( trackId, name, 0.2 );
    }
    /**
     * 人物名を割り当てる
     */
    public void assign( String trackId, String name, double threshold ) {
        String normalizedName = normalizeName(name);
        if (trackId == null || trackId.isBlank() || normalizedName.isEmpty()) {
            throw new IllegalArgumentException("trackId and name are required");
        }
        synchronized (this.assignmentSaveLock) {
            ArrayList<FaceTrack> assignedTracks;
            String savedPersons;
            synchronized (this) {
                FaceTrack track = this.tracks.get(trackId);
                if (track == null) {
                    throw new IllegalArgumentException("trackId is not found: " + trackId);
                }
                int personIdx = personId(normalizedName);
                assignedTracks = assignMatchingTracks(track, personIdx, threshold);
                savedPersons = personsJson();
            }
            try {
                savePersons(savedPersons);
                for (FaceTrack assignedTrack : assignedTracks) {
                    assignedTrack.save();
                }
            } catch (IOException ex) {
                throw new IllegalStateException("can not save assigned tracks: " + trackId, ex);
            }
        }
    }

    /**
     * 指定トラックと一致する未割り当てトラックを幅優先探索で同じ人物へ割り当てます。
     */
    private ArrayList<FaceTrack> assignMatchingTracks(FaceTrack track, int personIdx, double threshold) {
        ArrayList<FaceTrack> assignedTracks = new ArrayList<>();
        ArrayList<FaceTrack> searchQueue = new ArrayList<>();
        track.setPersonIdx(personIdx);
        assignedTracks.add(track);
        searchQueue.add(track);

        for (int queueIndex = 0; queueIndex < searchQueue.size(); queueIndex++) {
            FaceTrack source = searchQueue.get(queueIndex);
            FeatureRange sourceRange = source == track ? source.feature_range() : source.getRange();
            if (sourceRange == null) {
                continue;
            }
            for (FaceTrack candidate : this.tracks.values()) {
                if (candidate.getPersonIdx() >= 0 || !sourceRange.isMatch(candidate.getRange())) {
                    continue;
                }
                if (!candidate.matches(source,threshold)) {
                    continue;
                }
                candidate.setPersonIdx(personIdx);
                assignedTracks.add(candidate);
                searchQueue.add(candidate);
            }
        }
        return assignedTracks;
    }

    /**
     * 顔 ID から登録済み人物 ID を探し、その人物名を変更します。
     */
    public void rename(String trackId, String newName) {
        synchronized (this.assignmentSaveLock) {
            String savedPersons;
            synchronized (this) {
                FaceTrack track = this.tracks.get(trackId);
                if (track == null) {
                    return;
                }
                int personIdx = track.getPersonIdx();
                String normalizedName = normalizeName(newName);
                if (personIdx < 0 || personIdx >= this.persons.size() || normalizedName.isEmpty()) {
                    return;
                }
                this.persons.set(personIdx, normalizedName);
                savedPersons = personsJson();
            }
            savePersons(savedPersons);
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
