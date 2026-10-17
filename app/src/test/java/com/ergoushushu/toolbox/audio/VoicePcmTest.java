package com.ergoushushu.toolbox.audio;

import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class VoicePcmTest {
    @Test public void oneSecondStereoBecomesOneSecondMonoAcrossUnevenChunks() {
        VoicePcm pcm = new VoicePcm(44_100, 2);
        int produced = 0;
        for (int start = 0; start < 44_100; start += 137) {
            int frames = Math.min(137, 44_100 - start);
            ByteBuffer input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) input.putShort((short) 0).putShort((short) 6000);
            input.flip();
            ByteBuffer output = pcm.convert(input).order(ByteOrder.LITTLE_ENDIAN);
            produced += output.remaining() / 2;
            if (start > 1000 && output.hasRemaining()) assertEquals(3000, output.getShort(), 2);
        }
        assertEquals(16000, produced);
        assertEquals(16000, pcm.outputRate);
    }

    @Test public void preservesSpeechBandAndSuppressesHighFrequencyAliasing() {
        double voice = rms(1000);
        assertTrue("Speech must remain audible", voice > 3500);
        assertTrue("High frequencies must be filtered before downsampling", rms(12000) < voice * 0.06);
    }

    @Test public void lowRateRecordingIsNotUpsampled() {
        VoicePcm pcm = new VoicePcm(8000, 1);
        ByteBuffer input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        input.putShort((short) 1234).putShort((short) -4567).flip();
        ByteBuffer output = pcm.convert(input).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(8000, pcm.outputRate);
        assertEquals(1234, output.getShort());
        assertEquals(-4567, output.getShort());
    }

    @Test public void rejectsInvalidFramesAndMixesFullScaleStereoWithoutOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new VoicePcm(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new VoicePcm(44100, 0));
        VoicePcm pcm = new VoicePcm(8000, 2);
        assertThrows(IllegalArgumentException.class, () -> pcm.convert(ByteBuffer.allocate(3)));
        ByteBuffer input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        input.putShort(Short.MAX_VALUE).putShort(Short.MAX_VALUE).flip();
        assertEquals(Short.MAX_VALUE, pcm.convert(input).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    private static double rms(int frequency) {
        ByteBuffer input = ByteBuffer.allocate(44_100 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 44_100; i++) input.putShort((short) (Math.sin(i * 2 * Math.PI * frequency / 44_100) * 6000));
        input.flip();
        ByteBuffer output = new VoicePcm(44_100, 1).convert(input).order(ByteOrder.LITTLE_ENDIAN);
        double total = 0;
        int count = 0;
        while (output.hasRemaining()) {
            double sample = output.getShort();
            if (count++ > 100) total += sample * sample;
        }
        return Math.sqrt(total / (count - 101));
    }
}
