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
    public final String faceId;
    public final String jsonPath;
    public final String imagePath;
    public final FacePossibility.PersonPossibility[] personPossibilities;
    public FacePossibility(String faceId, String jsonPath, String imagePath, FacePossibility.PersonPossibility[] p ) {
        this.faceId = faceId;
        this.jsonPath = jsonPath;
        this.imagePath = imagePath;
        this.personPossibilities = p;
    }

}