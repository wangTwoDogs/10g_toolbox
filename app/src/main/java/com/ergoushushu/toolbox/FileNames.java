package com.ergoushushu.toolbox;

import java.util.Locale;

final class FileNames {
    private FileNames() { }

    static String audioName(String videoName) {
        String base = videoName == null ? "" : videoName.trim();
        int extension = base.lastIndexOf('.');
        if (extension > 0) base = base.substring(0, extension);
        base = base.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_").trim();
        if (base.isEmpty() || base.equals(".")) base = "提取的音频";
        if (base.length() > 100) base = base.substring(0, base.offsetByCodePoints(0, Math.min(80, base.codePointCount(0, base.length()))));
        return base + ".m4a";
    }

    static String duration(long microseconds) {
        if (microseconds < 0) return "时长未知";
        long seconds = microseconds / 1_000_000;
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    static String size(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024d);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024));
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024d * 1024 * 1024));
    }
}
