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
import android.provider.OpenableColumns;

import com.ergoushushu.toolbox.audio.AudioExtractor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ExtractionService extends Service {
    public static final String ACTION_START = "com.ergoushushu.toolbox.START";
    public static final String ACTION_CANCEL = "com.ergoushushu.toolbox.CANCEL";
    public static final String EXTRA_INPUT = "input";
    public static final String EXTRA_OUTPUT = "output";
    public static final String EXTRA_NAME = "name";
    private static final String CHANNEL = "audio_extraction";
    private static final int NOTIFICATION_ID = 10;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private CancellationSignal cancellation;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean running;
    private int lastProgress = Integer.MIN_VALUE;
    private volatile String outputName;
    private Uri output;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW));
    }

    @Override @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            if (cancellation != null) cancellation.cancel();
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        Uri input = intent.getParcelableExtra(EXTRA_INPUT);
        Uri requestedOutput = intent.getParcelableExtra(EXTRA_OUTPUT);
        if (running) {
            OutputFiles.deleteCreatedOutput(this, requestedOutput);
            return START_NOT_STICKY;
        }
        output = requestedOutput;
        outputName = intent.getStringExtra(EXTRA_NAME);
        if (outputName == null) outputName = "提取的音频.m4a";
        if (input == null || output == null || input.equals(output)) {
            finish(ExtractionStore.Phase.FAILED, "未能取得文件，请重新选择。", 0, 0, false);
            return START_NOT_STICKY;
        }
        cancellation = new CancellationSignal();
        try {
            Notification notification = notification(-1);
            if (Build.VERSION.SDK_INT >= 35) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (RuntimeException error) {
            finish(ExtractionStore.Phase.FAILED, "系统暂时不允许开始提取，请稍后重试。", 0, 0, true);
            return START_NOT_STICKY;
        }
        running = true;
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Toolbox:AudioExtraction");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        publishProgress(-1);
        worker.execute(() -> {
            try {
                try (Cursor cursor = getContentResolver().query(output,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                        outputName = cursor.getString(0);
                    }
                } catch (RuntimeException ignored) { /* Keep the suggested filename if unavailable. */ }
                AudioExtractor.Result result = AudioExtractor.extract(getApplicationContext(),
                        input, output, cancellation, percent -> main.post(() -> publishProgress(percent)));
                main.post(() -> finish(ExtractionStore.Phase.SUCCESS, "音频已保存",
                        result.durationUs, result.outputBytes, false));
            } catch (OperationCanceledException error) {
                boolean deleted = OutputFiles.deleteCreatedOutput(this, output);
                main.post(() -> finish(ExtractionStore.Phase.CANCELLED,
                        "已取消提取" + cleanupWarning(deleted), 0, 0, false));
            } catch (Exception error) {
                boolean deleted = OutputFiles.deleteCreatedOutput(this, output);
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) message = "无法处理这个视频，请换一个文件重试。";
                final String finalMessage = message + cleanupWarning(deleted);
                main.post(() -> finish(ExtractionStore.Phase.FAILED, finalMessage, 0, 0, false));
            }
        });
        return START_NOT_STICKY;
    }

    private String cleanupWarning(boolean deleted) {
        return deleted ? "" : "\n保存位置可能留下未完成的文件，请手动删除。";
    }

    private void publishProgress(int percent) {
        if (!running || percent == lastProgress) return;
        lastProgress = percent;
        ExtractionStore.update(new ExtractionStore.State(ExtractionStore.Phase.WORKING,
                percent, "正在提取音频", outputName, output, 0, 0));
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(percent));
    }

    private Notification notification(int percent) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 1,
                new Intent(this, ExtractionService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_audio)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(percent < 0 ? outputName : outputName + " · " + percent + "%")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, percent), percent < 0)
                .addAction(new Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
                .build();
    }

    private void finish(ExtractionStore.Phase phase, String message, long durationUs,
            long bytes, boolean cleanup) {
        if (cleanup) message += cleanupWarning(OutputFiles.deleteCreatedOutput(this, output));
        running = false;
        ExtractionStore.update(new ExtractionStore.State(phase,
                phase == ExtractionStore.Phase.SUCCESS ? 100 : 0, message,
                outputName == null ? "" : outputName, output, durationUs, bytes));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTimeout(int startId, int fgsType) {
        if (cancellation != null) cancellation.cancel();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (cancellation != null && running) cancellation.cancel();
        worker.shutdown();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
