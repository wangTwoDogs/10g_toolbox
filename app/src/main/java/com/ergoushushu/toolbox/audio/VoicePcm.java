package com.ergoushushu.toolbox.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Bounded-memory PCM16 mono mixer and streaming, anti-aliased voice resampler. */
final class VoicePcm {
    private static final int TAPS = 32;
    private static final int PHASES = 256;
    final int outputRate;
    private final int sourceRate;
    private final int channels;
    private final float[] ring = new float[64];
    private final float[][] coefficients;
    private long inputFrames;
    private long outputFrames;

    VoicePcm(int sampleRate, int channels) {
        if (sampleRate <= 0 || channels < 1 || channels > 8) throw new IllegalArgumentException("Invalid PCM format");
        this.sourceRate = sampleRate;
        this.channels = channels;
        outputRate = Math.min(16_000, sampleRate);
        coefficients = outputRate < sourceRate ? filter() : null;
    }

    long framesWritten() { return outputFrames; }

    ByteBuffer convert(ByteBuffer input) {
        if (input.remaining() % (channels * 2) != 0) throw new IllegalArgumentException("Incomplete PCM frame");
        input.order(ByteOrder.LITTLE_ENDIAN);
        int frames = input.remaining() / (channels * 2);
        ByteBuffer output = ByteBuffer.allocate((int) ((long) frames * outputRate / sourceRate + 2) * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (input.hasRemaining()) {
            int sum = 0;
            for (int channel = 0; channel < channels; channel++) sum += input.getShort();
            float mono = sum / (float) channels;
            ring[(int) (inputFrames & 63)] = mono;
            if (outputFrames * sourceRate / outputRate == inputFrames) {
                float value = mono;
                if (coefficients != null) {
                    int phase = (int) ((outputFrames * sourceRate % outputRate) * PHASES / outputRate);
                    float[] weights = coefficients[phase];
                    value = 0;
                    for (int tap = 0; tap < TAPS; tap++) {
                        value += ring[(int) ((inputFrames - tap) & 63)] * weights[tap];
                    }
                }
                output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value))));
                outputFrames++;
            }
            inputFrames++;
        }
        output.flip();
        return output;
    }

    private float[][] filter() {
        float[][] result = new float[PHASES][TAPS];
        double cutoff = 0.45 * outputRate / sourceRate;
        for (int phase = 0; phase < PHASES; phase++) {
            double total = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                double distance = tap - (TAPS - 1) / 2.0 + phase / (double) PHASES;
                double angle = 2 * Math.PI * cutoff * distance;
                double sinc = Math.abs(angle) < 1e-10 ? 1 : Math.sin(angle) / angle;
                double window = 0.42 - 0.5 * Math.cos(2 * Math.PI * tap / (TAPS - 1))
                        + 0.08 * Math.cos(4 * Math.PI * tap / (TAPS - 1));
                result[phase][tap] = (float) (2 * cutoff * sinc * window);
                total += result[phase][tap];
            }
            for (int tap = 0; tap < TAPS; tap++) result[phase][tap] /= total;
        }
        return result;
    }
}
