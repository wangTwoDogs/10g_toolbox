package com.ergoushushu.toolbox;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FileNamesTest {
    @Test public void keepsChineseAndRemovesOnlyLastExtension() {
        assertEquals("旅行.第一天.m4a", FileNames.audioName("旅行.第一天.MP4"));
    }
    @Test public void normalizesMissingNamesAndUnsafeCharacters() {
        assertEquals("提取的音频.m4a", FileNames.audioName(null));
        assertEquals("a_b_c.m4a", FileNames.audioName("a/b:c.mov"));
    }
    @Test public void formatsLongDurationWithoutWrapping() {
        assertEquals("1:01:01", FileNames.duration(3_661_000_000L));
        assertEquals("时长未知", FileNames.duration(-1));
    }
    @Test public void compressedNameKeepsTheOriginalDistinct() {
        assertEquals("会议录音-压缩.m4a", FileNames.compressedName("会议录音.m4a"));
    }
    @Test public void limitsVeryLongNamesWithoutSplittingEmoji() {
        String name = "😀".repeat(100) + ".mp4";
        String output = FileNames.audioName(name);
        assertEquals(80, output.substring(0, output.length() - 4).codePointCount(0, output.length() - 4));
        assertFalse(Character.isHighSurrogate(output.charAt(output.length() - 5)));
    }
}
