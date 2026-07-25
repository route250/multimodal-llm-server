package server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class PromptTemplatesTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-19T05:05:30Z"), ZoneId.of("UTC"));

    @Test
    void expandsSupportedVariablesAndKeepsUnknownVariables() {
        PromptTemplates templates = new PromptTemplates("テストAI", "${BOT_NAME} ${DATETIME} ${USER_NAME} ${UNKNOWN}",
                "初対面 ${BOT_NAME} ${DATETIME} ${FACE_ID}", "既知 ${BOT_NAME}", "登録直後 ${BOT_NAME}",
                "未登録 ${BOT_NAME} ${DATETIME} ${USER_NAME} ${FACE_ID} ${UNKNOWN}",
                "登録済み ${USER_NAME} ${FACE_ID}", "登録完了 ${USER_NAME} ${FACE_ID}",
                CLOCK
            );

        assertEquals("テストAI 2026年7月19日 14時05分30秒 ${USER_NAME} ${UNKNOWN}", templates.expandedSystemPrompt());
        assertEquals("初対面 テストAI 2026年7月19日 14時05分30秒 ${FACE_ID}", templates.expandFirstMeetingPrompt());
        assertEquals("既知 テストAI", templates.expandKnownPersonPrompt());
        assertEquals("登録直後 テストAI", templates.expandAssignedPrompt());
        assertEquals("未登録 テストAI 2026年7月19日 14時05分30秒 不明 trak-1 ${UNKNOWN}",templates.faceMessage("trak-1"));
        assertEquals("登録済み 花子 trak-2", templates.faceMessage("花子", "trak-2"));
        assertEquals("登録完了 花子 trak-3", templates.assignedPersonMessage("花子", "trak-3"));
    }
}
