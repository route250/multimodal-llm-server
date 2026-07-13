package facedb;

public class FacePossibility {
    public static class PersonPossibility{
        public final String personId;
        public final String name;
        public final float distance;
        public PersonPossibility(String personId, String name, float distance) {
            this.personId = personId;
            this.name = name;
            this.distance = distance;
        }
    }
    public final String trackId;
    public final String faceId;
    public final FacePossibility.PersonPossibility[] personPossibilities;
    public FacePossibility(String trackId, String faceId, FacePossibility.PersonPossibility[] p ) {
        this.trackId = trackId;
        this.faceId = faceId;
        this.personPossibilities = p;
    }

}