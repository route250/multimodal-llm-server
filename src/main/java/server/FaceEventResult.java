package server;

import json.Json;

/**
 * 顔イベントの保存結果と人物推定結果です。
 */
public record FaceEventResult(
        // HTTP レスポンス JSON に出す処理結果です。現状は accepted 固定で内部分岐には使いません。
        String status,
        // 推定した人物 ID です。SSE、HTTP レスポンス、保存 JSON に出します。未知は unknown、退出は none です。
        String personId,
        // 推定した人物名です。SSE、HTTP レスポンス、保存 JSON、会話履歴テキストに使います。
        String personName,
        // 入力顔特徴量と最近傍の既知顔特徴量の距離です。人物推定、SSE、HTTP レスポンス、保存 JSON に使います。
        Double distance,
        // distance がしきい値以下で既知人物として扱えるかを表します。SSE、HTTP レスポンス、保存 JSON に出します。
        boolean known,
        // FaceDB が採番した顔サンプル ID です。未保存の退出イベントでは none です。
        String faceId,
        // 顔イベントを保存した JSON ファイルの絶対パスです。HTTP レスポンスに出します。SSE には出しません。
        String jsonPath,
        // 顔イベント画像を保存した JPEG ファイルの絶対パスです。HTTP レスポンスに出します。SSE には出しません。
        String imagePath,
        // 顔の在室状態です。SSE の state と会話履歴追加判定に使います。
        String presenceState) {
    public static FaceEventResult unknown() {
        return unknown(null);
    }

    public static FaceEventResult unknown(Double distance) {
        return new FaceEventResult("accepted", "unknown", "unknown", distance, false, "unknown", null, null, "person-entered");
    }

    public static FaceEventResult unknownFace(String faceId) {
        return new FaceEventResult("accepted", "unknown", "unknown", null, false, faceId, null, null, "person-entered");
    }

    public static FaceEventResult left() {
        return new FaceEventResult("accepted", "none", "none", null, false, "none", null, null, "person-left");
    }

    public FaceEventResult withFiles(String jsonPath, String imagePath) {
        return new FaceEventResult(status, personId, personName, distance, known, faceId, jsonPath, imagePath, presenceState);
    }

    public FaceEventResult withPresenceState(String presenceState) {
        return new FaceEventResult(status, personId, personName, distance, known, faceId, jsonPath, imagePath, presenceState);
    }

    public String toJson() {
        return Json.object(Json.fields(
                "status", status,
                "personId", personId,
                "personName", personName,
                "distance", distance,
                "known", known,
                "faceId", faceId,
                "presenceState", presenceState,
                "jsonPath", jsonPath,
                "imagePath", imagePath)) + "\n";
    }
}
