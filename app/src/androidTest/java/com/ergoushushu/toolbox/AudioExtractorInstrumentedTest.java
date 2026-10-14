package com.ergoushushu.toolbox;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.core.content.ContextCompat;

import com.ergoushushu.toolbox.audio.AudioExtractor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Runs against real Android extractors/codecs; fixtures contain no user media. */
@RunWith(AndroidJUnit4.class)
public final class AudioExtractorInstrumentedTest {
    private Context context;
    private File fixtureDirectory;
    private final List<File> createdFiles = new ArrayList<>();

    @Before
    public void createIsolatedFixtureDirectory() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixtureDirectory = new File(context.getCacheDir(), "audio-instrumentation-" + UUID.randomUUID());
        assertTrue("Could not create fixture directory", fixtureDirectory.mkdir());
    }

    @After
    public void removeOnlyThisTestsFixtures() {
        List<String> failures = new ArrayList<>();
        for (int index = createdFiles.size() - 1; index >= 0; index--) {
            File file = createdFiles.get(index);
            if (file.exists() && !file.delete()) failures.add(file.getAbsolutePath());
        }
        if (fixtureDirectory != null && fixtureDirectory.exists() && !fixtureDirectory.delete()) {
            failures.add(fixtureDirectory.getAbsolutePath());
        }
        assertTrue("Test fixtures could not be removed: " + failures, failures.isEmpty());
    }

    @Test(timeout = 30_000)
    public void aacExtractionPreservesEveryCompressedSample() throws Exception {
        File source = file("source.m4a");
        File output = file("extracted.m4a");
        MediaFixtures.writeAac(source);
        List<Integer> progress = new ArrayList<>();

        AudioExtractor.AudioInfo info = AudioExtractor.inspect(context, Uri.fromFile(source));
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, info.mimeType);
        assertFalse(info.willTranscode);
        AudioExtractor.Result result = extract(source, output, new CancellationSignal(), progress);

        assertFalse(result.transcoded);
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, result.sourceMime);
        assertEquals(output.length(), result.outputBytes);
        assertTrue(result.durationUs > 0);
        assertEquals(MediaFixtures.SAMPLE_RATE, result.sampleRate);
        assertEquals(1, result.channelCount);
        AudioSamples before = readAudioSamples(source);
        AudioSamples after = readAudioSamples(output);
        assertEquals(1, after.trackCount);
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, after.mime);
        assertTrue(before.samples.size() > 1);
        assertEquals(before.samples.size(), after.samples.size());
        for (int index = 0; index < before.samples.size(); index++) {
            assertArrayEquals("Compressed sample " + index + " was changed",
                    before.samples.get(index), after.samples.get(index));
            assertEquals(before.timestamps.get(index) - before.timestamps.get(0),
                    after.timestamps.get(index).longValue());
        }
        assertCompletedProgress(progress);
    }

    @Test(timeout = 30_000)
    public void actualVideoExtractionRemovesVideoAndPreservesAacPackets() throws Exception {
        File videoTrack = file("video-track.mp4");
        File audioTrack = file("audio-track.m4a");
        File source = file("video-with-audio.mp4");
        File output = file("video-extracted.m4a");
        MediaFixtures.writeVideoWithoutAudio(videoTrack);
        MediaFixtures.writeAac(audioTrack);
        MediaFixtures.combineVideoAndAudio(videoTrack, audioTrack, source);
        AudioSamples before = readAudioSamples(source);
        assertEquals("Fixture must include both video and audio", 2, before.trackCount);
        assertTrue(before.samples.size() > 1);

        AudioExtractor.Result result = extract(source, output, new CancellationSignal(), null);

        assertFalse(result.transcoded);
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, result.sourceMime);
        AudioSamples after = readAudioSamples(output);
        assertEquals("Output must contain only an audio track", 1, after.trackCount);
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, after.mime);
        assertEquals(before.samples.size(), after.samples.size());
        for (int index = 0; index < before.samples.size(); index++) {
            assertArrayEquals("Video extraction changed compressed sample " + index,
                    before.samples.get(index), after.samples.get(index));
            assertEquals(before.timestamps.get(index) - before.timestamps.get(0),
                    after.timestamps.get(index).longValue());
        }
    }

    @Test(timeout = 30_000)
    public void largeTimestampGapIsNotAddedAgainAsFinalPacketDuration() throws Exception {
        File audioTrack = file("gap-source.m4a");
        File source = file("sparse-audio.m4a");
        File output = file("sparse-extracted.m4a");
        MediaFixtures.writeAac(audioTrack);
        MediaFixtures.writeAacWithTimestampGap(audioTrack, source);
        AudioSamples before = readAudioSamples(source);
        assertEquals(2, before.samples.size());
        assertEquals(10_000_000L, before.timestamps.get(1).longValue());

        AudioExtractor.Result result = extract(source, output, new CancellationSignal(), null);

        long expectedDurationUs = 10_000_000L + 1_024_000_000L / MediaFixtures.SAMPLE_RATE;
        assertEquals("A ten-second gap must not make the last AAC packet ten seconds long",
                expectedDurationUs, result.durationUs);
        AudioSamples after = readAudioSamples(output);
        assertEquals(2, after.samples.size());
        for (int index = 0; index < 2; index++) {
            assertArrayEquals(before.samples.get(index), after.samples.get(index));
            assertEquals(before.timestamps.get(index), after.timestamps.get(index));
        }
        assertTrue("Saved M4A should also have the corrected duration",
                Math.abs(after.durationUs - expectedDurationUs) <= 1_000);
    }

    @Test(timeout = 30_000)
    public void pcmWavIsConvertedToPlayableAac() throws Exception {
        File source = file("source.wav");
        File output = file("converted.m4a");
        MediaFixtures.writePcmWav(source);
        List<Integer> progress = new ArrayList<>();

        AudioExtractor.Result result = extract(source, output, new CancellationSignal(), progress);

        assertTrue(result.transcoded);
        assertEquals(MediaFormat.MIMETYPE_AUDIO_RAW, result.sourceMime);
        assertEquals(MediaFixtures.SAMPLE_RATE, result.sampleRate);
        assertEquals(1, result.channelCount);
        assertEquals(output.length(), result.outputBytes);
        assertTrue(result.durationUs > 0);
        AudioSamples audio = readAudioSamples(output);
        assertEquals(1, audio.trackCount);
        assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, audio.mime);
        assertTrue(audio.samples.size() > 1);
        assertTrue("Decoded AAC should contain the fixture's tone", decodesToNonSilentPcm(output));
        assertCompletedProgress(progress);
    }

    @Test(timeout = 30_000)
    public void videoWithoutAudioIsRejectedBeforeCreatingDestination() throws Exception {
        File source = file("silent-video.mp4");
        File output = file("no-audio.m4a");
        MediaFixtures.writeVideoWithoutAudio(source);
        assertThrows(IOException.class, () -> extract(source, output, new CancellationSignal(), null));
        assertFalse(output.exists());
    }

    @Test(timeout = 10_000)
    public void damagedMediaIsRejectedBeforeCreatingDestination() throws Exception {
        File source = file("broken.mp4");
        File output = file("broken-output.m4a");
        try (FileOutputStream stream = new FileOutputStream(source)) {
            stream.write(new byte[] { 0, 1, 2, 3, 4 });
        }
        assertThrows(IOException.class, () -> extract(source, output, new CancellationSignal(), null));
        assertFalse(output.exists());
    }

    @Test(timeout = 10_000)
    public void preCanceledExtractionDoesNotReadInputOrCreateDestination() throws Exception {
        File source = file("not-created.mp4");
        File output = file("canceled.m4a");
        CancellationSignal cancellation = new CancellationSignal();
        cancellation.cancel();
        List<Integer> progress = new ArrayList<>();
        assertThrows(OperationCanceledException.class, () -> extract(source, output, cancellation, progress));
        assertFalse(output.exists());
        assertTrue(progress.isEmpty());
    }

    @Test(timeout = 45_000)
    public void visibleActivityCanRunRealForegroundExtractionService() throws Exception {
        File source = file("service-source.wav");
        File output = file("service-output.m4a");
        MediaFixtures.writePcmWav(source);
        long sourceBytes = source.length();
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Intent serviceIntent = new Intent(context, ExtractionService.class)
                .setAction(ExtractionService.ACTION_START)
                .putExtra(ExtractionService.EXTRA_INPUT, Uri.fromFile(source))
                .putExtra(ExtractionService.EXTRA_OUTPUT, Uri.fromFile(output))
                .putExtra(ExtractionService.EXTRA_NAME, output.getName());
        try {
            instrumentation.waitForIdleSync();
            AtomicReference<RuntimeException> startupError = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> {
                ExtractionStore.reset();
                try {
                    ContextCompat.startForegroundService(activity, serviceIntent);
                } catch (RuntimeException error) {
                    startupError.set(error);
                }
            });
            assertNull("Foreground service could not be started from the visible activity", startupError.get());
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            ExtractionStore.State state;
            do {
                state = ExtractionStore.current();
                if (state.phase == ExtractionStore.Phase.SUCCESS
                        || state.phase == ExtractionStore.Phase.FAILED
                        || state.phase == ExtractionStore.Phase.CANCELLED) break;
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < deadline);

            assertEquals("Service did not complete successfully: " + state.message,
                    ExtractionStore.Phase.SUCCESS, state.phase);
            assertEquals(Uri.fromFile(output), state.output);
            assertEquals(output.length(), state.outputBytes);
            assertTrue(state.outputBytes > 0);
            assertTrue(state.durationUs > 0);
            AudioSamples audio = readAudioSamples(output);
            assertEquals(1, audio.trackCount);
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, audio.mime);
            assertTrue(audio.samples.size() > 1);
            assertTrue("Source must remain present", source.exists());
            assertEquals("Source must not be modified", sourceBytes, source.length());
            instrumentation.waitForIdleSync();
        } finally {
            // Finish or cancel the worker before @After removes its registered files.
            if (ExtractionStore.current().phase == ExtractionStore.Phase.WORKING) {
                instrumentation.runOnMainSync(() -> activity.startService(
                        new Intent(context, ExtractionService.class).setAction(ExtractionService.ACTION_CANCEL)));
                long cleanupDeadline = SystemClock.elapsedRealtime() + 10_000;
                while (ExtractionStore.current().phase == ExtractionStore.Phase.WORKING
                        && SystemClock.elapsedRealtime() < cleanupDeadline) {
                    SystemClock.sleep(50);
                }
            }
            instrumentation.runOnMainSync(() -> {
                activity.stopService(serviceIntent);
                activity.finish();
            });
            instrumentation.waitForIdleSync();
            assertFalse("Service did not stop after cancellation; fixtures may still be in use",
                    ExtractionStore.current().phase == ExtractionStore.Phase.WORKING);
        }
    }

    private File file(String name) {
        File file = new File(fixtureDirectory, name);
        createdFiles.add(file);
        return file;
    }

    private AudioExtractor.Result extract(File input, File output, CancellationSignal cancellation,
                                          List<Integer> progress) throws IOException {
        return AudioExtractor.extract(context, Uri.fromFile(input), Uri.fromFile(output), cancellation,
                progress == null ? null : progress::add);
    }

    private static void assertCompletedProgress(List<Integer> progress) {
        assertFalse(progress.isEmpty());
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
        int previous = -1;
        for (int percent : progress) {
            assertTrue("Invalid progress " + percent, percent >= -1 && percent <= 100);
            assertTrue("Progress moved backwards", percent >= previous);
            previous = percent;
        }
    }

    private static AudioSamples readAudioSamples(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = findAudioTrack(extractor);
            assertTrue("No audio track in " + file.getName(), audioTrack >= 0);
            AudioSamples samples = new AudioSamples();
            samples.trackCount = extractor.getTrackCount();
            MediaFormat format = extractor.getTrackFormat(audioTrack);
            samples.mime = format.getString(MediaFormat.KEY_MIME);
            samples.durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : -1;
            extractor.selectTrack(audioTrack);
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            while (true) {
                buffer.clear();
                int length = extractor.readSampleData(buffer, 0);
                if (length < 0) break;
                byte[] packet = new byte[length];
                buffer.position(0);
                buffer.get(packet);
                samples.samples.add(packet);
                samples.timestamps.add(extractor.getSampleTime());
                extractor.advance();
            }
            return samples;
        } finally {
            extractor.release();
        }
    }

    private static boolean decodesToNonSilentPcm(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        boolean started = false;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = findAudioTrack(extractor);
            assertTrue(track >= 0);
            extractor.selectTrack(track);
            MediaFormat format = extractor.getTrackFormat(track);
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0);
            decoder.start();
            started = true;
            boolean inputEnded = false;
            boolean outputEnded = false;
            boolean audible = false;
            long decodedBytes = 0;
            long deadline = SystemClock.elapsedRealtime() + 10_000;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputEnded) {
                assertTrue("AAC decoder did not reach end of stream", SystemClock.elapsedRealtime() < deadline);
                if (!inputEnded) {
                    int inputIndex = decoder.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(inputIndex);
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = decoder.dequeueOutputBuffer(info, 10_000);
                if (outputIndex >= 0) {
                    ByteBuffer output = decoder.getOutputBuffer(outputIndex);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        decodedBytes += info.size;
                        for (int index = info.offset; index < info.offset + info.size; index++) {
                            audible |= output.get(index) != 0;
                        }
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }
            // Allow encoder delay/padding, while catching empty or severely truncated output.
            assertTrue("Too little decoded audio", decodedBytes >= MediaFixtures.SAMPLE_RATE / 2);
            return audible;
        } finally {
            if (decoder != null) {
                try {
                    if (started) decoder.stop();
                } finally {
                    decoder.release();
                }
            }
            extractor.release();
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            String mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return index;
        }
        return -1;
    }

    private static final class AudioSamples {
        int trackCount;
        String mime;
        long durationUs;
        final List<byte[]> samples = new ArrayList<>();
        final List<Long> timestamps = new ArrayList<>();
    }
}
