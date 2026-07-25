package server;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** LLM に送るプロンプトテンプレートと変数展開を担当します。 */
record PromptTemplates(
        String botName, String systemPrompt, String firstMeetingPrompt, String knownPersonPrompt,
        String unknownPersonMessageFormat, String knownPersonMessageFormat, String assignedPersonMessageFormat,
        Clock clock
    ) {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("uuuu年M月d日 HH時mm分ss秒");
    
    PromptTemplates {
        botName = text(botName);
        systemPrompt = text(systemPrompt);
        firstMeetingPrompt = text(firstMeetingPrompt);
        knownPersonPrompt = text(knownPersonPrompt);
        unknownPersonMessageFormat = text(unknownPersonMessageFormat);
        knownPersonMessageFormat = text(knownPersonMessageFormat);
        assignedPersonMessageFormat = text(assignedPersonMessageFormat);
    }

    PromptTemplates(
        String botName, String systemPrompt, String firstMeetingPrompt, String knownPersonPrompt,
        String unknownPersonMessageFormat, String knownPersonMessageFormat, String assignedPersonMessageFormat
    ) {
        this(botName,systemPrompt,firstMeetingPrompt,knownPersonPrompt,
            unknownPersonMessageFormat,assignedPersonMessageFormat,assignedPersonMessageFormat,null);
    }

    String expandedSystemPrompt() { return common(systemPrompt); }
    String expandKnownPersonPrompt() { return common(knownPersonPrompt); }
    String expandFirstMeetingPrompt() { return common(firstMeetingPrompt); }
    String encounterPrompt(boolean known) { return common(known ? knownPersonPrompt : firstMeetingPrompt); }
    String faceMessage(String faceId) {
        if( faceId==null || faceId.isBlank() ) {
            throw new IllegalArgumentException("invalid username or faceId");
        }
        return common(unknownPersonMessageFormat)
                .replace("${USER_NAME}", "不明")
                .replace("${FACE_ID}", text(faceId));
    }
    String faceMessage(String userName, String faceId) {
        if( userName==null || userName.isBlank() || faceId==null || faceId.isBlank() ) {
            throw new IllegalArgumentException("invalid username or faceId");
        }
        return common(knownPersonMessageFormat)
                .replace("${USER_NAME}", text(userName))
                .replace("${FACE_ID}", text(faceId));
    }
    /** 人物名の登録成功を LLM へ通知するメッセージを生成します。 */
    String assignedPersonMessage(String userName, String faceId) {
        return common(assignedPersonMessageFormat)
                .replace("${USER_NAME}", text(userName).isEmpty() ? "不明" : text(userName))
                .replace("${FACE_ID}", text(faceId));
    }
    String personLeft(String userName, String faceId ) {
        return "人物認識通知\n認識結果: 不在\n相手の名前: 不在\ntrackId: 不在";
    }

    /** 本日の日付と時刻 */
    private String today() {
        Clock clock = this.clock == null ? Clock.system(TOKYO) : this.clock;
        String tm = DATETIME_FORMAT.format(ZonedDateTime.now(clock).withZoneSameInstant(TOKYO));
        return tm;
    }

    private String common(String template) {
        return text(template)
                .replace("${BOT_NAME}", botName)
                .replace("${DATETIME}", today() );
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }
}
