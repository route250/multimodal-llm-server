package server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record ChatRequest(String type, String responseText, String contentType, Map<String, String> contentTypeParameters,
                          byte[] body, byte[] vadBytes, byte[] rmsBytes) {
    private static final int PCM_VAD_HEADER_BYTES = 32;
    private static final int PCM_VAD_VERSION = 2;
    private static final int PCM_VAD_FORMAT_PCM16LE = 1;
    private static final int PCM_VAD_SAMPLE_RATE = 16_000;
    private static final int PCM_VAD_CHANNELS = 1;
    private static final int PCM_VAD_FRAME_SAMPLES = 256;

    public ChatRequest {
        contentTypeParameters = Map.copyOf(contentTypeParameters);
        body = body.clone();
        vadBytes = vadBytes.clone();
        rmsBytes = rmsBytes.clone();
    }

    public static ChatRequest from(String contentType, byte[] body) {
        ParsedContentType parsedContentType = parseContentType(contentType);
        String normalizedType = parsedContentType.mediaType();
        if (normalizedType.startsWith("text/") || "application/json".equals(normalizedType)) {
            String text = new String(body, StandardCharsets.UTF_8);
            return new ChatRequest(
                    "text",
                    "received text: " + text,
                    normalizedType,
                    parsedContentType.parameters(),
                    body,
                    new byte[0],
                    new byte[0]);
        }
        if ("audio/pcm-vad".equals(normalizedType)) {
            PcmVadPayload payload = parsePcmVad(parsedContentType, body);
            return new ChatRequest(
                    "audio",
                    "received audio chunk: " + payload.pcm().length + " bytes (" + normalizedType + ")",
                    normalizedType,
                    parsedContentType.parameters(),
                    payload.pcm(),
                    payload.vadBytes(),
                    payload.rmsBytes());
        }
        if (normalizedType.startsWith("audio/")) {
            return new ChatRequest("audio", "received audio chunk: " + body.length + " bytes (" + normalizedType + ")",
                    normalizedType, parsedContentType.parameters(), body, new byte[0], new byte[0]);
        }
        return new ChatRequest("binary", "received binary chunk: " + body.length + " bytes (" + normalizedType + ")",
                normalizedType, parsedContentType.parameters(), body, new byte[0], new byte[0]);
    }

    public ServerEvent toEvent() {
        return ServerEvent.message(responseText);
    }

    public String textBody() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public byte[] body() {
        return body.clone();
    }

    @Override
    public byte[] vadBytes() {
        return vadBytes.clone();
    }

    @Override
    public byte[] rmsBytes() {
        return rmsBytes.clone();
    }

    public boolean isPcm16LeAudio() {
        if (!"audio/pcm-vad".equals(contentType)) {
            return false;
        }
        return isPcm16LeAudioParameters();
    }

    public boolean isPcmVadAudio() {
        return "audio/pcm-vad".equals(contentType) && isPcm16LeAudioParameters();
    }

    private boolean isPcm16LeAudioParameters() {
        if (!"audio/pcm".equals(contentType) && !"audio/pcm-vad".equals(contentType)) {
            return false;
        }
        return "16000".equals(contentTypeParameters.get("rate"))
                && "1".equals(contentTypeParameters.get("channels"))
                && "s16le".equals(contentTypeParameters.get("format"));
    }

    /**
     * ブラウザで計算した VAD 値と RMS 値を同梱した音声リクエストを検証し、PCM、VAD、RMS へ分割する。
     *
     * @param contentType Content-Type の解析結果
     * @param body 32 byte header、PCM16LE、VAD byte array、RMS byte array の順に並ぶリクエスト本文
     * @return PCM、VAD byte array、RMS byte array
     */
    private static PcmVadPayload parsePcmVad(ParsedContentType contentType, byte[] body) {
        requireContentParameter(contentType, "rate", "16000");
        requireContentParameter(contentType, "channels", "1");
        requireContentParameter(contentType, "format", "s16le");
        requireContentParameter(contentType, "vad-frame-samples", String.valueOf(PCM_VAD_FRAME_SAMPLES));
        if (body.length < PCM_VAD_HEADER_BYTES) {
            throw new HttpRequestException(400, "audio/pcm-vad body is shorter than 32 byte header");
        }

        ByteBuffer header = ByteBuffer.wrap(body, 0, PCM_VAD_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        if (header.get() != 'M' || header.get() != 'V' || header.get() != 'A' || header.get() != 'D') {
            throw new HttpRequestException(400, "audio/pcm-vad magic must be MVAD");
        }
        int version = Short.toUnsignedInt(header.getShort());
        if (version != PCM_VAD_VERSION) {
            throw new HttpRequestException(400, "audio/pcm-vad version must be 2");
        }
        int flags = Short.toUnsignedInt(header.getShort());
        if (flags != 0) {
            throw new HttpRequestException(400, "audio/pcm-vad flags must be 0");
        }
        long sampleRate = Integer.toUnsignedLong(header.getInt());
        int channels = Short.toUnsignedInt(header.getShort());
        int pcmFormat = Short.toUnsignedInt(header.getShort());
        long pcmSampleCount = Integer.toUnsignedLong(header.getInt());
        long vadFrameSamples = Integer.toUnsignedLong(header.getInt());
        long reserved1 = Integer.toUnsignedLong(header.getInt());
        long reserved2 = Integer.toUnsignedLong(header.getInt());

        if (sampleRate != PCM_VAD_SAMPLE_RATE) {
            throw new HttpRequestException(400, "audio/pcm-vad sampleRate must be 16000");
        }
        if (channels != PCM_VAD_CHANNELS) {
            throw new HttpRequestException(400, "audio/pcm-vad channels must be 1");
        }
        if (pcmFormat != PCM_VAD_FORMAT_PCM16LE) {
            throw new HttpRequestException(400, "audio/pcm-vad pcmFormat must be 1");
        }
        if (vadFrameSamples != PCM_VAD_FRAME_SAMPLES) {
            throw new HttpRequestException(400, "audio/pcm-vad vadFrameSamples must be 256");
        }
        if (reserved1 != 0 || reserved2 != 0) {
            throw new HttpRequestException(400, "audio/pcm-vad reserved fields must be 0");
        }
        if (pcmSampleCount > (Integer.MAX_VALUE / Short.BYTES)) {
            throw new HttpRequestException(400, "audio/pcm-vad pcmSampleCount is too large");
        }
        if (pcmSampleCount % PCM_VAD_FRAME_SAMPLES != 0) {
            throw new HttpRequestException(400, "audio/pcm-vad pcmSampleCount must be divisible by 256");
        }
        long metricByteCount = pcmSampleCount / PCM_VAD_FRAME_SAMPLES;
        long expectedLength = PCM_VAD_HEADER_BYTES
                + pcmSampleCount * Short.BYTES
                + metricByteCount
                + metricByteCount;
        if (expectedLength != body.length) {
            throw new HttpRequestException(400, "audio/pcm-vad body length does not match header");
        }

        int pcmByteCount = Math.toIntExact(pcmSampleCount * Short.BYTES);
        int vadOffset = PCM_VAD_HEADER_BYTES + pcmByteCount;
        int rmsOffset = Math.toIntExact(vadOffset + metricByteCount);
        byte[] vadBytes = Arrays.copyOfRange(body, vadOffset, rmsOffset);
        for (byte vadByte : vadBytes) {
            int value = Byte.toUnsignedInt(vadByte);
            if ((value & 0x7f) > 100) {
                throw new HttpRequestException(400, "audio/pcm-vad VAD byte must use 0..100 in lower 7 bits");
            }
        }
        byte[] rmsBytes = Arrays.copyOfRange(body, rmsOffset, body.length);
        for (byte rmsByte : rmsBytes) {
            int value = Byte.toUnsignedInt(rmsByte);
            if (value > 100) {
                throw new HttpRequestException(400, "audio/pcm-vad RMS byte must use 0..100");
            }
        }
        return new PcmVadPayload(
                Arrays.copyOfRange(body, PCM_VAD_HEADER_BYTES, vadOffset),
                vadBytes,
                rmsBytes);
    }

    /**
     * Content-Type パラメータが期待値と一致することを確認する。
     *
     * @param contentType Content-Type の解析結果
     * @param name パラメータ名
     * @param expectedValue 期待する値
     */
    private static void requireContentParameter(ParsedContentType contentType, String name, String expectedValue) {
        String actualValue = contentType.parameters().get(name);
        if (!expectedValue.equals(actualValue)) {
            throw new HttpRequestException(400, "audio/pcm-vad " + name + " must be " + expectedValue);
        }
    }

    private static ParsedContentType parseContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return new ParsedContentType("application/octet-stream", Map.of());
        }
        String[] parts = contentType.split(";");
        String mediaType = parts[0].trim().toLowerCase();
        Map<String, String> parameters = new ConcurrentHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].trim();
            int separator = parameter.indexOf('=');
            if (separator > 0) {
                String name = parameter.substring(0, separator).trim().toLowerCase();
                String value = parameter.substring(separator + 1).trim().toLowerCase();
                parameters.put(name, unquote(value));
            }
        }
        return new ParsedContentType(mediaType, parameters);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record ParsedContentType(String mediaType, Map<String, String> parameters) {
    }

    private record PcmVadPayload(byte[] pcm, byte[] vadBytes, byte[] rmsBytes) {
    }
}
