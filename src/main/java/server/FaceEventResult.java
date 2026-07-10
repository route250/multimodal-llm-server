package server;

import json.Json;

/**
 * 顔イベントの保存結果と人物推定結果です。
 */
public record FaceEventResult(
        String status,
        String personId,
        String personName,
        Double distance,
        boolean known,
        String jsonPath,
        String imagePath,
        String presenceState) {
    public static FaceEventResult unknown() {
        return unknown(null);
    }

    public static FaceEventResult unknown(Double distance) {
        return new FaceEventResult("accepted", "unknown", "unknown", distance, false, null, null, "person-entered");
    }

    public static FaceEventResult left() {
        return new FaceEventResult("accepted", "none", "none", null, false, null, null, "person-left");
    }

    public FaceEventResult withFiles(String jsonPath, String imagePath) {
        return new FaceEventResult(status, personId, personName, distance, known, jsonPath, imagePath, presenceState);
    }

    public FaceEventResult withPresenceState(String presenceState) {
        return new FaceEventResult(status, personId, personName, distance, known, jsonPath, imagePath, presenceState);
    }

    public String toJson() {
        return Json.object(Json.fields(
                "status", status,
                "personId", personId,
                "personName", personName,
                "distance", distance,
                "known", known,
                "presenceState", presenceState,
                "jsonPath", jsonPath,
                "imagePath", imagePath)) + "\n";
    }
}
