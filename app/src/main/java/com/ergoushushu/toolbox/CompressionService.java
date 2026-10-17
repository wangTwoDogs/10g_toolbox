package com.ergoushushu.toolbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import com.ergoushushu.toolbox.audio.AudioExtractor;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One sequential encoder: failures do not block later files, and finished files are usable immediately. */
public final class CompressionService extends Service {
    static final String ENQUEUE = "com.ergoushushu.toolbox.COMPRESS_QUEUE";
    static final String CANCEL = "com.ergoushushu.toolbox.COMPRESS_CANCEL";
    static final String INPUTS = "inputs", FOLDER = "folder";
    private static final String CHANNEL = "recording_compression";
    private static final int NOTIFICATION = 20;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private CompressionHistory history;
    private CancellationSignal cancellation;
    private PowerManager.WakeLock wakeLock;
    private CompressionHistory.Job active;
    private boolean foreground, destroyed;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                CHANNEL, "录音压缩队列", NotificationManager.IMPORTANCE_LOW));
        history = CompressionHistory.get(this);
    }
    @Override @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (CANCEL.equals(intent.getAction())) {
            history.cancelQueued();
            if (cancellation != null) cancellation.cancel();
            if (active == null) stopQueue();
            return START_NOT_STICKY;
        }
        if (!ENQUEUE.equals(intent.getAction())) return START_NOT_STICKY;
        ArrayList<Uri> inputs = intent.getParcelableArrayListExtra(INPUTS);
        Uri folder = intent.getParcelableExtra(FOLDER);
        if (inputs == null || inputs.isEmpty() || folder == null) {
            if (!foreground) stopSelf();
            return START_NOT_STICKY;
        }
        try {
            history.enqueue(inputs, folder);
            if (!foreground) {
                Notification notification = notification("准备压缩录音", -1);
                if (Build.VERSION.SDK_INT >= 35) startForeground(NOTIFICATION, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
                else if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                else startForeground(NOTIFICATION, notification);
                foreground = true;
                wakeLock = getSystemService(PowerManager.class).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "Toolbox:CompressionQueue");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(6 * 60 * 60 * 1000L);
            }
            processNext();
        } catch (RuntimeException error) {
            if (active == null) {
                history.failWaiting("无法开始后台处理，请保持应用在前台后重试。\n"
                        + (error.getMessage() == null ? "请检查存储空间和系统限制。" : error.getMessage()));
                stopQueue();
            }
        }
        return START_NOT_STICKY;
    }

    private void processNext() {
        if (active != null || destroyed) return;
        try { active = history.next(); }
        catch (RuntimeException error) { history.failWaiting("无法开始处理，请检查手机存储空间。"); stopQueue(); return; }
        if (active == null) { stopQueue(); return; }
        CompressionHistory.Job job = active;
        cancellation = new CancellationSignal();
        CancellationSignal signal = cancellation;
        notifyProgress(job, -1);
        worker.execute(() -> process(job, signal));
    }

    private void process(CompressionHistory.Job job, CancellationSignal signal) {
        Uri output = null;
        File work = WorkFiles.directory(this, job.id);
        AudioExtractor.Result result = null;
        CompressionHistory.Status status = CompressionHistory.Status.FAILED;
        String message = "";
        boolean cleaned = true;
        try {
            signal.throwIfCanceled();
            Uri input = Uri.parse(job.input);
            String name = job.name;
            try (Cursor cursor = getContentResolver().query(input,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
            }
            final String sourceName = name;
            long bytes = AudioExtractor.sourceBytes(this, input);
            history.metadata(job.id, sourceName, bytes);
            signal.throwIfCanceled();
            Uri folder = Uri.parse(job.folder);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
            output = DocumentsContract.createDocument(getContentResolver(), parent,
                    "audio/mp4", FileNames.compressedName(sourceName));
            if (output == null || output.equals(input)) {
                output = null; // An invalid provider must never make cleanup target the input.
                throw new IOException("无法新建压缩文件，请重新选择保存文件夹。");
            }
            history.output(job.id, output); // Persist ownership before writing any audio.
            if (!work.mkdir()) throw new IOException("无法创建临时目录，请检查存储空间。");
            result = AudioExtractor.compress(this, input, output, signal, percent -> main.post(() -> {
                if (active != null && active.id.equals(job.id)) {
                    history.progress(job.id, percent);
                    notifyProgress(job, percent);
                }
            }), work);
            status = CompressionHistory.Status.SUCCESS;
            message = "";
        } catch (OperationCanceledException error) {
            status = CompressionHistory.Status.CANCELLED;
            message = "已停止处理";
        } catch (Exception error) {
            message = error.getMessage() == null ? "无法处理这份录音，可以重试。" : error.getMessage();
        } finally {
            if (status != CompressionHistory.Status.SUCCESS && output != null) {
                cleaned = OutputFiles.deleteCreatedOutput(this, output);
            }
            cleaned &= WorkFiles.clean(this, job.id);
            if (!cleaned) message += "\n未完成文件清理失败，请检查保存位置。";
        }
        final CompressionHistory.Status finalStatus = status;
        final String finalMessage = message;
        final AudioExtractor.Result finalResult = result;
        final boolean finalCleaned = cleaned;
        main.post(() -> {
            try {
                history.finish(job.id, finalStatus, finalMessage,
                        finalResult == null ? -1 : finalResult.inputBytes,
                        finalResult == null ? 0 : finalResult.outputBytes,
                        finalResult == null ? 0 : finalResult.durationUs, finalCleaned);
            } catch (RuntimeException error) {
                history.failWaiting("处理记录保存失败，请检查存储空间后重试。");
                active = null; cancellation = null; stopQueue(); return;
            }
            active = null;
            cancellation = null;
            processNext();
        });
    }

    private void notifyProgress(CompressionHistory.Job job, int percent) {
        if (!foreground) return;
        String name = job.name;
        for (CompressionHistory.Job current : history.snapshot()) {
            if (current.id.equals(job.id)) { name = current.name; break; }
        }
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(name, percent));
    }
    private Notification notification(String name, int progress) {
        PendingIntent open = PendingIntent.getActivity(this, 20,
                new Intent(this, CompressionActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 21,
                new Intent(this, CompressionService.class).setAction(CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_audio)
                .setContentTitle("正在批量压缩录音").setContentText(name)
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
                .setProgress(100, Math.max(0, progress), progress < 0)
                .addAction(new Notification.Action.Builder(null, "停止队列", stop).build()).build();
    }
    private void stopQueue() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        history.cancelQueued();
        if (cancellation != null) cancellation.cancel();
        stopQueue();
    }
    @Override public void onDestroy() {
        destroyed = true;
        if (cancellation != null) cancellation.cancel();
        worker.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
