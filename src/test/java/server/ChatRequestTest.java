package server;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class ChatRequestTest {
    private static final String PCM_VAD = "audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256";

    @Test
    void parsesPcmVadBodyAndExposesPcmAndVadBytes() {
        byte[] pcm = new byte[1024];
        pcm[0] = 1;
        pcm[1] = 2;
        byte[] vad = new byte[] {0, (byte) (0x80 | 50)};

        ChatRequest request = ChatRequest.from(PCM_VAD, pcmVadBody(pcm, vad, 256, 1, 0));

        assertEquals("audio", request.type());
        assertEquals("audio/pcm-vad", request.contentType());
        assertArrayEquals(pcm, request.body());
        assertArrayEquals(vad, request.vadBytes());
    }

    @Test
    void rejectsInvalidPcmVadMagic() {
        byte[] body = pcmVadBody(new byte[1024], new byte[] {0, 1}, 256, 1, 0);
        body[0] = 'X';

        assertThrows(HttpRequestException.class, () -> ChatRequest.from(PCM_VAD, body));
    }

    @Test
    void rejectsInvalidPcmVadVersion() {
        byte[] body = pcmVadBody(new byte[1024], new byte[] {0, 1}, 256, 2, 0);

        assertThrows(HttpRequestException.class, () -> ChatRequest.from(PCM_VAD, body));
    }

    @Test
    void rejectsPcmVadBodyLengthMismatch() {
        byte[] body = pcmVadBody(new byte[1024], new byte[] {0, 1}, 256, 1, 0);
        byte[] truncated = new byte[body.length - 1];
        System.arraycopy(body, 0, truncated, 0, truncated.length);

        assertThrows(HttpRequestException.class, () -> ChatRequest.from(PCM_VAD, truncated));
    }

    @Test
    void rejectsPcmVadValueOver100InLower7Bits() {
        byte[] body = pcmVadBody(new byte[1024], new byte[] {101, 1}, 256, 1, 0);

        assertThrows(HttpRequestException.class, () -> ChatRequest.from(PCM_VAD, body));
    }

    private static byte[] pcmVadBody(byte[] pcm, byte[] vadBytes, int vadFrameSamples, int version, int flags) {
        ByteBuffer buffer = ByteBuffer
                .allocate(32 + pcm.length + vadBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'M');
        buffer.put((byte) 'V');
        buffer.put((byte) 'A');
        buffer.put((byte) 'D');
        buffer.putShort((short) version);
        buffer.putShort((short) flags);
        buffer.putInt(16_000);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(pcm.length / 2);
        buffer.putInt(vadFrameSamples);
        buffer.putInt(vadBytes.length);
        buffer.putInt(0);
        buffer.put(pcm);
        buffer.put(vadBytes);
        return buffer.array();
    }
}
