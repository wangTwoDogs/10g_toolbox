package com.ergoushushu.toolbox;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.graphics.Bitmap;
import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class CompressionBatchInstrumentedTest {
    private Context context;
    private Instrumentation instrumentation;
    private Activity activity;
    private File fixtures, historyFile;
    private Uri folder, parent;
    private CompressionHistory history, previous;
    private Field singleton;
    private boolean rootCreated;
    private final Set<Uri> outputs = new HashSet<>();

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        String id = "batch-" + UUID.randomUUID();
        fixtures = new File(context.getCacheDir(), id); assertTrue(fixtures.mkdir());
        historyFile = new File(fixtures, "history.json");
        history = new CompressionHistory(context, historyFile);
        singleton = CompressionHistory.class.getDeclaredField("instance"); singleton.setAccessible(true);
        previous = (CompressionHistory) singleton.get(null); singleton.set(null, history);
        folder = DocumentsContract.buildTreeDocumentUri(TestOutputProvider.AUTHORITY, id);
        parent = DocumentsContract.buildDocumentUriUsingTree(folder, id);
        activity = instrumentation.startActivitySync(new Intent(context, CompressionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.waitForIdleSync();
    }

    @After public void cleanOnlyRegisteredFixtures() throws Exception {
        if (activity != null) {
            if (history.hasWork()) instrumentation.runOnMainSync(() -> activity.startService(
                    new Intent(context, CompressionService.class).setAction(CompressionService.CANCEL)));
            long deadline = SystemClock.elapsedRealtime() + 15_000;
            while (history.hasWork() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
            assertFalse("Worker still owns test files", history.hasWork());
            instrumentation.runOnMainSync(() -> { activity.stopService(new Intent(context, CompressionService.class)); activity.finish(); });
            instrumentation.waitForIdleSync();
        }
        if (history != null) for (CompressionHistory.Job job : history.snapshot()) if (!job.output.isEmpty()) outputs.add(Uri.parse(job.output));
        for (Uri output : outputs) {
            try { DocumentsContract.deleteDocument(context.getContentResolver(), output); }
            catch (Exception ignored) { /* A recovery test may already have removed this registered output. */ }
        }
        if (rootCreated) assertTrue("Fixture provider still contains unregistered output", DocumentsContract.deleteDocument(context.getContentResolver(), parent));
        if (singleton != null) singleton.set(null, previous);
        if (fixtures != null && fixtures.exists()) {
            for (File file : fixtures.listFiles()) assertTrue("Could not remove test file: " + file, file.delete());
            assertTrue(fixtures.delete());
        }
    }

    @Test(timeout = 60_000) public void batchContinuesAfterFailureKeepsOriginalsAndPersistsResults() throws Exception {
        File first = file("first.wav"), bad = file("bad.wav"), last = file("last.wav");
        MediaFixtures.writePcmWav(first, 2); MediaFixtures.writePcmWav(last, 2);
        Files.write(bad.toPath(), new byte[]{1,2,3});
        byte[] original = Files.readAllBytes(first.toPath());
        Uri existing = createOutput("first-压缩.m4a");
        try (OutputStream stream = context.getContentResolver().openOutputStream(existing)) { stream.write(new byte[]{9,8,7}); }
        start(first, bad, last);
        waitForComplete(3);
        List<CompressionHistory.Job> jobs = history.snapshot();
        assertEquals(CompressionHistory.Status.SUCCESS, jobs.get(0).status);
        assertEquals(CompressionHistory.Status.FAILED, jobs.get(1).status);
        assertEquals(CompressionHistory.Status.SUCCESS, jobs.get(2).status);
        assertNotEquals(existing.toString(), jobs.get(0).output);
        assertArrayEquals(original, Files.readAllBytes(first.toPath()));
        try (InputStream stream = context.getContentResolver().openInputStream(existing)) { assertArrayEquals(new byte[]{9,8,7}, stream.readAllBytes()); }
        assertTrue(jobs.get(0).outputBytes < original.length / 3);
        assertTrue(jobs.get(2).durationUs > 0);
        CompressionHistory reopened = new CompressionHistory(context, historyFile);
        assertEquals(3, reopened.snapshot().size());
        assertEquals(jobs.get(0).output, reopened.snapshot().get(0).output);
        assertEquals(CompressionHistory.Status.SUCCESS, reopened.snapshot().get(2).status);
        assertFalse(WorkFiles.directory(context, jobs.get(0).id).exists());
        assertFalse(WorkFiles.directory(context, jobs.get(1).id).exists());
        ArrayList<Uri> shared = new ArrayList<>(Arrays.asList(Uri.parse(jobs.get(0).output), Uri.parse(jobs.get(2).output)));
        Intent share = AudioSharing.intent(context.getContentResolver(), shared);
        assertEquals(Intent.ACTION_SEND_MULTIPLE, share.getAction());
        assertEquals(2, share.getParcelableArrayListExtra(Intent.EXTRA_STREAM).size());
        assertEquals(2, share.getClipData().getItemCount());
        assertEquals(shared.get(1), share.getClipData().getItemAt(1).getUri());
        assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        if ("true".equals(InstrumentationRegistry.getArguments().getString("captureUi"))) {
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                View collapse = findText(activity.getWindow().getDecorView(), "收起添加区域");
                if (collapse != null) collapse.performClick();
                View selectAll = findText(activity.getWindow().getDecorView(), "全选已完成");
                assertNotNull(selectAll); assertTrue(selectAll.performClick());
                assertNotNull(findText(activity.getWindow().getDecorView(), "分享已选（2）"));
            });
            instrumentation.getUiAutomation().waitForIdle(250, 3_000);
            File screenshot = new File(context.getExternalCacheDir(), "batch-ui-" + UUID.randomUUID() + ".png");
            try (FileOutputStream stream = new FileOutputStream(screenshot)) {
                Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); bitmap.recycle();
            }
            android.util.Log.i("ToolboxUiArtifact", screenshot.getAbsolutePath());
        }
    }

    @Test(timeout = 60_000) public void stoppingQueueCleansCurrentOutputAndNeverStartsRemainingFiles() throws Exception {
        File first = file("long.wav"), second = file("waiting.wav");
        MediaFixtures.writeLongPcmWav(first, 120); MediaFixtures.writePcmWav(second);
        start(first, second);
        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            List<CompressionHistory.Job> jobs = history.snapshot();
            if (!jobs.isEmpty() && jobs.get(0).progress > 0) break;
            SystemClock.sleep(10);
        }
        instrumentation.runOnMainSync(() -> activity.startService(new Intent(context, CompressionService.class).setAction(CompressionService.CANCEL)));
        waitForComplete(2);
        List<CompressionHistory.Job> jobs = history.snapshot();
        assertEquals(CompressionHistory.Status.CANCELLED, jobs.get(0).status);
        assertEquals(CompressionHistory.Status.CANCELLED, jobs.get(1).status);
        assertTrue(jobs.get(0).output.isEmpty()); assertTrue(jobs.get(1).output.isEmpty());
        assertFalse(WorkFiles.directory(context, jobs.get(0).id).exists());
        assertEquals(21_168_044, first.length()); assertTrue(second.exists());
    }

    @Test(timeout = 60_000) public void queueFinishesAfterItsActivityCloses() throws Exception {
        File first = file("background.wav"), second = file("next.wav");
        MediaFixtures.writeLongPcmWav(first, 120); MediaFixtures.writePcmWav(second);
        start(first, second);
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (history.snapshot().isEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10);
        instrumentation.runOnMainSync(() -> activity.finish());
        waitForComplete(2);
        assertEquals(CompressionHistory.Status.SUCCESS, history.snapshot().get(0).status);
        assertEquals(CompressionHistory.Status.SUCCESS, history.snapshot().get(1).status);
        CompressionHistory reopened = new CompressionHistory(context, historyFile);
        assertEquals(2, reopened.snapshot().size());
        assertTrue(reopened.snapshot().get(0).outputBytes > 0);
    }

    @Test(timeout = 20_000) public void interruptedJobRecoveryCleansOwnedFilesAndPreservesCompletedFiles() throws Exception {
        List<Uri> inputs = Arrays.asList(Uri.fromFile(file("done.wav")), Uri.fromFile(file("interrupted.wav")), Uri.fromFile(file("queued.wav")));
        history.enqueue(inputs, folder);
        CompressionHistory.Job done = history.next();
        Uri completed = createOutput("completed.m4a");
        history.output(done.id, completed);
        history.finish(done.id, CompressionHistory.Status.SUCCESS, "已保存", 1000, 100, 1000000, true);
        CompressionHistory.Job interrupted = history.next();
        Uri incomplete = createOutput("incomplete.m4a"); history.output(interrupted.id, incomplete);
        File work = WorkFiles.directory(context, interrupted.id); assertTrue(work.mkdir());
        File temporary = new File(work, "audio-extract-owned.m4a"); assertTrue(temporary.createNewFile());
        CompressionHistory recovered = new CompressionHistory(context, historyFile);
        assertEquals(CompressionHistory.Status.SUCCESS, recovered.snapshot().get(0).status);
        assertEquals(completed.toString(), recovered.snapshot().get(0).output);
        assertEquals(CompressionHistory.Status.FAILED, recovered.snapshot().get(1).status);
        assertEquals(CompressionHistory.Status.FAILED, recovered.snapshot().get(2).status);
        assertFalse(work.exists());
        try (InputStream ignored = context.getContentResolver().openInputStream(completed)) { assertNotNull(ignored); }
        assertThrows(java.io.FileNotFoundException.class, () -> context.getContentResolver().openInputStream(incomplete));
        // The old in-memory object is deliberately replaced just as it would be after process death.
        history = recovered; singleton.set(null, recovered);
    }

    private File file(String name) { return new File(fixtures, name); }
    private static View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = findText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private Uri createOutput(String name) throws Exception {
        Uri output = DocumentsContract.createDocument(context.getContentResolver(), parent, "audio/mp4", name);
        assertNotNull(output); rootCreated = true; outputs.add(output); return output;
    }
    private void start(File... inputs) {
        ArrayList<Uri> uris = new ArrayList<>(); for (File input : inputs) uris.add(Uri.fromFile(input));
        rootCreated = true;
        instrumentation.runOnMainSync(() -> ContextCompat.startForegroundService(activity,
                new Intent(context, CompressionService.class).setAction(CompressionService.ENQUEUE)
                        .putParcelableArrayListExtra(CompressionService.INPUTS, uris).putExtra(CompressionService.FOLDER, folder)));
    }
    private void waitForComplete(int expected) {
        long deadline = SystemClock.elapsedRealtime() + 40_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (history.snapshot().size() == expected && !history.hasWork()) return;
            SystemClock.sleep(20);
        }
        fail("Batch did not finish");
    }
}
