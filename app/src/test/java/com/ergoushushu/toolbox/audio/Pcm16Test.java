package com.ergoushushu.toolbox.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public final class Pcm16Test {
    @Test public void mixesBothStereoChannelsWithCorrectEndianAndFrameCount() {
        ByteBuffer input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        input.putShort((short) 0).putShort((short) 6000);
        input.putShort((short) -6000).putShort((short) 0);
        input.putShort(Short.MAX_VALUE).putShort(Short.MAX_VALUE);
        input.flip();
        ByteBuffer mono = Pcm16.mono(input, 2).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(6, mono.remaining());
        assertEquals(3000, mono.getShort());
        assertEquals(-3000, mono.getShort());
        assertEquals(Short.MAX_VALUE, mono.getShort());
    }

    @Test public void monoPreservesTheSelectedBufferRange() {
        ByteBuffer input = ByteBuffer.wrap(new byte[]{99, 1, 2, 3, 4, 99});
        input.position(1);
        input.limit(5);
        ByteBuffer mono = Pcm16.mono(input, 1);
        assertEquals(4, mono.remaining());
        assertEquals(1, mono.get());
        assertEquals(2, mono.get());
        assertEquals(3, mono.get());
        assertEquals(4, mono.get());
    }

    @Test public void rejectsIncompleteFramesAndInvalidChannelCounts() {
        assertThrows(IllegalArgumentException.class, () -> Pcm16.mono(ByteBuffer.allocate(3), 2));
        assertThrows(IllegalArgumentException.class, () -> Pcm16.mono(ByteBuffer.allocate(2), 0));
    }
}
