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
        // 顔の在室状態です。SSE の state と会話履歴追加判定に使います。
        String presenceState,
        // ページ表示中だけ有効な、ブラウザが発行した顔トラッキング ID です。
        String trackId) {
    public static FaceEventResult unknown() {
        return unknown(null);
    }

    public static FaceEventResult unknown(Double distance) {
        return new FaceEventResult("accepted", "unknown", "unknown", distance, false, "unknown", "person-entered", "legacy");
    }

    public static FaceEventResult unknownFace(String faceId) {
        return new FaceEventResult("accepted", "unknown", "unknown", null, false, faceId, "person-entered", "legacy");
    }

    public static FaceEventResult left() {
        return new FaceEventResult("accepted", "none", "none", null, false, "none", "person-left", "legacy");
    }

    public FaceEventResult withPresenceState(String presenceState) {
        return new FaceEventResult(status, personId, personName, distance, known, faceId, presenceState, trackId);
    }

    public FaceEventResult withTrackId(String trackId) {
        return new FaceEventResult(status, personId, personName, distance, known, faceId, presenceState, trackId);
    }

    public String toJson() {
        return Json.object(Json.fields(
                "status", status,
                "personId", personId,
                "personName", personName,
                "distance", distance,
                "known", known,
                "trackId", trackId,
                "faceId", faceId,
                "presenceState", presenceState
            )) + "\n";
    }
}
