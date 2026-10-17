package com.ergoushushu.toolbox;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import android.util.AtomicFile;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable queue and results. Only documents created for an unfinished job are cleaned up. */
final class CompressionHistory {
    enum Status { QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED }

    static final class Job {
        final String id, input, folder;
        String name, output = "", message = "";
        Status status = Status.QUEUED;
        int progress = -1;
        long inputBytes = -1, outputBytes, durationUs, startedAt, elapsedMs;

        Job(String input, String folder) {
            id = UUID.randomUUID().toString();
            this.input = input;
            this.folder = folder;
            name = Uri.parse(input).getLastPathSegment();
            if (name == null) name = "录音";
        }
        Job(JSONObject json) throws Exception {
            id = UUID.fromString(json.getString("id")).toString();
            input = json.getString("input");
            folder = json.getString("folder");
            name = json.getString("name");
            output = json.optString("output");
            message = json.optString("message");
            status = Status.valueOf(json.getString("status"));
            progress = json.optInt("progress", -1);
            inputBytes = json.optLong("inputBytes", -1);
            outputBytes = json.optLong("outputBytes");
            durationUs = json.optLong("durationUs");
            startedAt = json.optLong("startedAt");
            elapsedMs = json.optLong("elapsedMs");
        }
        Job(Job source) {
            id = source.id; input = source.input; folder = source.folder;
            name = source.name; output = source.output; message = source.message;
            status = source.status; progress = source.progress;
            inputBytes = source.inputBytes; outputBytes = source.outputBytes;
            durationUs = source.durationUs; startedAt = source.startedAt; elapsedMs = source.elapsedMs;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("input", input).put("folder", folder)
                    .put("name", name).put("output", output).put("message", message)
                    .put("status", status.name()).put("progress", progress).put("inputBytes", inputBytes)
                    .put("outputBytes", outputBytes).put("durationUs", durationUs)
                    .put("startedAt", startedAt).put("elapsedMs", elapsedMs);
        }
    }

    private static CompressionHistory instance;
    private final Context context;
    private final AtomicFile file;
    private final ArrayList<Job> jobs = new ArrayList<>();
    private final MutableLiveData<List<Job>> changes = new MutableLiveData<>();

    static synchronized CompressionHistory get(Context context) {
        if (instance == null) instance = new CompressionHistory(context,
                new File(context.getFilesDir(), "compression-history.json"));
        return instance;
    }

    CompressionHistory(Context context, File path) {
        this.context = context.getApplicationContext();
        file = new AtomicFile(path);
        try {
            boolean recovered = false;
            if (file.getBaseFile().exists() || new File(path + ".bak").exists()) {
                JSONArray data = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
                for (int i = 0; i < data.length(); i++) {
                    Job job = new Job(data.getJSONObject(i));
                    if (job.status == Status.RUNNING || job.status == Status.QUEUED) {
                        boolean cleaned = job.output.isEmpty()
                                || OutputFiles.deleteCreatedOutput(context, Uri.parse(job.output));
                        cleaned &= WorkFiles.clean(context, job.id);
                        job.status = Status.FAILED;
                        job.message = "上次处理被中断，可以重试。" + (cleaned ? "" : "未完成文件清理失败，请检查保存位置。");
                        if (cleaned) job.output = "";
                        recovered = true;
                    }
                    jobs.add(job);
                }
            }
            if (recovered) persist();
            publish();
        } catch (Exception error) {
            throw new IllegalStateException("无法读取处理记录，原记录已保留。", error);
        }
    }

    LiveData<List<Job>> observe() { return changes; }
    synchronized List<Job> snapshot() {
        ArrayList<Job> result = new ArrayList<>();
        for (Job job : jobs) result.add(new Job(job));
        return result;
    }
    synchronized boolean hasWork() {
        for (Job job : jobs) if (job.status == Status.RUNNING || job.status == Status.QUEUED) return true;
        return false;
    }
    synchronized void enqueue(List<Uri> inputs, Uri folder) {
        for (Uri input : inputs) {
            boolean duplicate = false;
            for (Job job : jobs) {
                if (job.input.equals(input.toString()) && (job.status == Status.RUNNING || job.status == Status.QUEUED)) {
                    duplicate = true; break;
                }
            }
            if (!duplicate) jobs.add(new Job(input.toString(), folder.toString()));
        }
        saveAndPublish();
    }
    synchronized Job next() {
        for (Job job : jobs) if (job.status == Status.QUEUED) {
            job.status = Status.RUNNING;
            job.startedAt = System.currentTimeMillis();
            saveAndPublish();
            return new Job(job);
        }
        return null;
    }
    synchronized void metadata(String id, String name, long inputBytes) {
        Job job = find(id); job.name = name; job.inputBytes = inputBytes;
        saveAndPublish();
    }
    synchronized void output(String id, Uri output) {
        find(id).output = output.toString();
        saveAndPublish();
    }
    synchronized void progress(String id, int progress) {
        Job job = find(id);
        if (job.status != Status.RUNNING || job.progress == progress) return;
        job.progress = progress;
        publish(); // Progress is transient; avoid rewriting history for every audio packet.
    }
    synchronized void finish(String id, Status status, String message, long inputBytes,
                             long outputBytes, long durationUs, boolean cleaned) {
        Job job = find(id);
        job.status = status; job.message = message;
        if (inputBytes >= 0) job.inputBytes = inputBytes;
        job.outputBytes = outputBytes; job.durationUs = durationUs;
        job.elapsedMs = Math.max(0, System.currentTimeMillis() - job.startedAt);
        job.progress = status == Status.SUCCESS ? 100 : 0;
        if (status != Status.SUCCESS && cleaned) job.output = "";
        saveAndPublish();
    }
    synchronized void cancelQueued() {
        for (Job job : jobs) if (job.status == Status.QUEUED) {
            job.status = Status.CANCELLED; job.message = "已停止，未开始处理。";
        }
        saveAndPublish();
    }
    synchronized void failWaiting(String message) {
        for (Job job : jobs) if (job.status == Status.QUEUED || job.status == Status.RUNNING) {
            job.status = Status.FAILED;
            job.message = message;
        }
        try { persist(); }
        catch (IllegalStateException error) { android.util.Log.e("ToolboxQueue", "Could not persist queue failure", error); }
        publish();
    }
    private Job find(String id) {
        for (Job job : jobs) if (job.id.equals(id)) return job;
        throw new IllegalArgumentException("Unknown job");
    }
    private void saveAndPublish() { persist(); publish(); }
    private void persist() {
        FileOutputStream output = null;
        try {
            JSONArray data = new JSONArray();
            for (Job job : jobs) data.put(job.json());
            output = file.startWrite();
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception error) {
            if (output != null) file.failWrite(output);
            throw new IllegalStateException("处理记录保存失败，请检查手机存储空间。", error);
        }
    }
    private void publish() {
        List<Job> snapshot = snapshot();
        if (Looper.myLooper() == Looper.getMainLooper()) changes.setValue(snapshot);
        else changes.postValue(snapshot);
    }
}
