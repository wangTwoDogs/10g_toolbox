package com.ergoushushu.toolbox.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Mix complete interleaved little-endian PCM16 frames without changing their sample rate. */
final class Pcm16 {
    private Pcm16() { }

    static ByteBuffer mono(ByteBuffer source, int channels) {
        if (channels < 1 || channels > 8 || source.remaining() % (channels * 2) != 0) {
            throw new IllegalArgumentException("Invalid PCM16 frames");
        }
        if (channels == 1) return source;
        source.order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocate(source.remaining() / channels).order(ByteOrder.LITTLE_ENDIAN);
        while (source.hasRemaining()) {
            int total = 0;
            for (int channel = 0; channel < channels; channel++) total += source.getShort();
            output.putShort((short) (total / channels));
        }
        output.flip();
        return output;
    }
}
