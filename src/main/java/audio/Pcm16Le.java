package audio;

public final class Pcm16Le {
    private Pcm16Le() {
    }

    public static short[] decode(byte[] bytes) {
        if ((bytes.length & 1) != 0) {
            throw new IllegalArgumentException("PCM16LE body length must be even");
        }
        short[] samples = new short[bytes.length / 2];
        for (int i = 0; i < samples.length; i++) {
            int low = bytes[i * 2] & 0xff;
            int high = bytes[i * 2 + 1];
            samples[i] = (short) ((high << 8) | low);
        }
        return samples;
    }
}
