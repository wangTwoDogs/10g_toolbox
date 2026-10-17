package com.ergoushushu.toolbox;

import android.content.Context;
import java.io.File;
import java.util.UUID;

final class WorkFiles {
    private WorkFiles() { }
    static File directory(Context context, String jobId) {
        return new File(context.getCacheDir(), "compression-" + UUID.fromString(jobId));
    }
    static boolean clean(Context context, String jobId) {
        File directory = directory(context, jobId);
        if (!directory.exists()) return true;
        File[] files = directory.listFiles();
        if (files == null) return false;
        boolean cleaned = true;
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("audio-extract-") && file.getName().endsWith(".m4a")) {
                cleaned &= file.delete();
            } else cleaned = false;
        }
        return cleaned && directory.delete();
    }
}
