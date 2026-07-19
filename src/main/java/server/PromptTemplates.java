package server;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** LLM に送るプロンプトテンプレートと変数展開を担当します。 */
record PromptTemplates(
        String botName, String systemPrompt, String firstMeetingPrompt, String knownPersonPrompt,
        String unknownPersonMessageFormat, String knownPersonMessageFormat) {
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("uuuu年M月d日 HH時mm分ss秒");

    PromptTemplates {
        botName = text(botName);
        systemPrompt = text(systemPrompt);
        firstMeetingPrompt = text(firstMeetingPrompt);
        knownPersonPrompt = text(knownPersonPrompt);
        unknownPersonMessageFormat = text(unknownPersonMessageFormat);
        knownPersonMessageFormat = text(knownPersonMessageFormat);
    }

    String expandedSystemPrompt() { return expandedSystemPrompt(Clock.system(TOKYO)); }
    String expandedSystemPrompt(Clock clock) { return common(systemPrompt, clock); }
    String encounterPrompt(boolean known) { return encounterPrompt(known, Clock.system(TOKYO)); }
    String encounterPrompt(boolean known, Clock clock) { return common(known ? knownPersonPrompt : firstMeetingPrompt, clock); }
    String faceMessage(boolean known, String userName, String faceId) {
        return faceMessage(known, userName, faceId, Clock.system(TOKYO));
    }
    String faceMessage(boolean known, String userName, String faceId, Clock clock) {
        return common(known ? knownPersonMessageFormat : unknownPersonMessageFormat, clock)
                .replace("${USER_NAME}", text(userName).isEmpty() ? "不明" : text(userName))
                .replace("${FACE_ID}", text(faceId));
    }

    private String common(String template, Clock clock) {
        return text(template)
                .replace("${BOT_NAME}", botName)
                .replace("${DATETIME}", DATETIME_FORMAT.format(ZonedDateTime.now(clock).withZoneSameInstant(TOKYO)));
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }
}
