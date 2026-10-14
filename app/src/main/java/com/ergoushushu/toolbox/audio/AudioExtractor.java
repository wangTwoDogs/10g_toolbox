package com.ergoushushu.toolbox.audio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;

/** Offline audio extraction. Call on a worker thread; listener callbacks use that same thread. */
public final class AudioExtractor {
    private AudioExtractor() { }

    public interface Listener {
        /** 0..100, or -1 when the input has no duration. 100 means the destination is closed. */
        void onProgress(int percent);
    }

    public static final class AudioInfo {
        public final String mimeType;
        public final long durationUs;
        public final int sampleRate;
        public final int channelCount;
        public final boolean willTranscode;

        private AudioInfo(MediaFormat format) {
            mimeType = format.getString(MediaFormat.KEY_MIME);
            durationUs = getLong(format, MediaFormat.KEY_DURATION, -1);
            sampleRate = getInt(format, MediaFormat.KEY_SAMPLE_RATE, 0);
            channelCount = getInt(format, MediaFormat.KEY_CHANNEL_COUNT, 0);
            willTranscode = !MediaFormat.MIMETYPE_AUDIO_AAC.equals(mimeType);
        }
    }

    public static final class Result {
        public final String sourceMime;
        public final boolean transcoded;
        public final long durationUs;
        public final long outputBytes;
        public final int sampleRate;
        public final int channelCount;

        private Result(AudioInfo source, AudioPipeline.Stats stats, long outputBytes) {
            sourceMime = source.mimeType;
            transcoded = source.willTranscode;
            durationUs = stats.durationUs;
            this.outputBytes = outputBytes;
            sampleRate = stats.sampleRate;
            channelCount = stats.channelCount;
        }
    }

    /** Selects the first audio track, matching the track used by extract(). */
    public static AudioInfo inspect(Context context, Uri input) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            openInput(context, input, extractor, null);
            return new AudioInfo(firstAudioTrack(extractor).format);
        } catch (RuntimeException error) {
            throw new IOException("无法读取这个视频，请选择手机上可以播放的视频文件。", error);
        } finally {
            extractor.release();
        }
    }

    /**
     * AAC is copied without re-encoding. Other tracks use device decoders and an AAC-LC encoder.
     * A seekable local temporary file lets even pipe-based document providers receive M4A.
     * On failure/cancellation the caller must delete its newly created output document.
     */
    public static Result extract(Context context, Uri input, Uri output,
                                 CancellationSignal cancellation, Listener listener)
            throws IOException {
        if (input == null || output == null || input.equals(output)) {
            throw new IOException("请选择视频，并另存为一个新的音频文件。" );
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            checkCanceled(cancellation);
            openInput(context, input, extractor, cancellation);
            Track track = firstAudioTrack(extractor);
            AudioInfo source = new AudioInfo(track.format);
            extractor.selectTrack(track.index);
            Progress progress = new Progress(source.durationUs, listener);
            progress.initial();
            try (TemporaryFile temporary = new TemporaryFile(context.getCacheDir())) {
                AudioPipeline.Stats stats = source.willTranscode
                        ? AudioPipeline.transcode(extractor, track.format, temporary.file,
                                cancellation, progress)
                        : AudioPipeline.remux(extractor, track.format, temporary.file,
                                cancellation, progress);
                checkCanceled(cancellation);
                long bytes = temporary.file.length();
                copyToDocument(context, temporary.file, output, bytes, cancellation, progress);
                checkCanceled(cancellation);
                // Do not announce success until the cache file has also been cleaned up.
                temporary.close();
                progress.complete();
                return new Result(source, stats, bytes);
            }
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (SecurityException denied) {
            throw new IOException("文件访问权限已失效，请重新选择视频和保存位置。", denied);
        } catch (RuntimeException error) {
            throw new IOException("这个视频的音频格式暂不受当前手机支持，或文件已损坏。", error);
        } finally {
            extractor.release();
        }
    }

    private static void openInput(Context context, Uri input, MediaExtractor extractor,
                                  CancellationSignal cancellation)
            throws IOException {
        if (input == null || !("content".equals(input.getScheme())
                || "file".equals(input.getScheme()))) {
            throw new IOException("请通过文件选择器选择本地视频。" );
        }
        try (AssetFileDescriptor descriptor = context.getContentResolver()
                .openAssetFileDescriptor(input, "r", cancellation)) {
            if (descriptor == null) throw new IOException("无法打开所选视频。" );
            extractor.setDataSource(descriptor);
        }
    }

    private static Track firstAudioTrack(MediaExtractor extractor) throws IOException {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return new Track(index, format);
        }
        throw new IOException("这个视频没有音轨，无法提取音频。" );
    }

    private static void copyToDocument(Context context, File source, Uri output, long total,
                                       CancellationSignal cancellation, Progress progress)
            throws IOException {
        progress.saving(0, total);
        try (FileInputStream input = new FileInputStream(source);
             OutputStream stream = context.getContentResolver().openOutputStream(output, "wt")) {
            if (stream == null) throw new IOException("无法写入保存位置，请换一个文件夹。" );
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCanceled(cancellation);
                stream.write(buffer, 0, count);
                copied += count;
                progress.saving(copied, total);
            }
            stream.flush();
        }
    }

    static void checkCanceled(CancellationSignal cancellation) {
        if (cancellation != null) cancellation.throwIfCanceled();
        if (Thread.currentThread().isInterrupted()) throw new OperationCanceledException();
    }

    static int getInt(MediaFormat format, String key, int fallback) {
        return format.containsKey(key) ? format.getInteger(key) : fallback;
    }

    static long getLong(MediaFormat format, String key, long fallback) {
        return format.containsKey(key) ? format.getLong(key) : fallback;
    }

    private static final class Track {
        final int index;
        final MediaFormat format;
        Track(int index, MediaFormat format) { this.index = index; this.format = format; }
    }

    private static final class TemporaryFile implements AutoCloseable {
        final File file;
        TemporaryFile(File directory) throws IOException {
            file = File.createTempFile("audio-extract-", ".m4a", directory);
        }
        @Override public void close() throws IOException {
            Files.deleteIfExists(file.toPath());
        }
    }

    static final class Progress {
        private final long durationUs;
        private final Listener listener;
        private int lastPercent = Integer.MIN_VALUE;
        private long lastCallbackMs;

        Progress(long durationUs, Listener listener) {
            this.durationUs = durationUs;
            this.listener = listener;
        }
        void initial() { dispatch(durationUs > 0 ? 0 : -1, true); }
        void audio(long timestampUs) {
            if (durationUs > 0) {
                dispatch((int) Math.min(94, Math.max(0, timestampUs) * 94.0 / durationUs), false);
            }
        }
        void saving(long copied, long total) {
            dispatch(95 + (total > 0 ? (int) Math.min(4, copied * 4.0 / total) : 0), false);
        }
        void complete() { dispatch(100, true); }
        private void dispatch(int percent, boolean force) {
            if (listener == null || percent == lastPercent) return;
            long now = SystemClock.elapsedRealtime();
            if (!force && now - lastCallbackMs < 150) return;
            lastPercent = percent;
            lastCallbackMs = now;
            listener.onProgress(percent);
        }
    }
}
