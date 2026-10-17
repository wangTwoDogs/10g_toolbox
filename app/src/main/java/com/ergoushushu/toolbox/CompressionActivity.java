package com.ergoushushu.toolbox;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CompressionActivity extends AppCompatActivity {
    private CompressionHistory history;
    private LinearLayout screen;
    private TextView selectedText, summary, empty;
    private MaterialButton start, directory, stop, selectAll, share;
    private MaterialButton setupToggle;
    private LinearLayout setup;
    private boolean formExpanded = true, firstRender = true, restoredState;
    private final ArrayList<Uri> pending = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private List<CompressionHistory.Job> jobs = new ArrayList<>();
    private List<CompressionHistory.Job> visible = new ArrayList<>();
    private Uri folder;
    private String folderLabel = "已选文件夹";
    private boolean awaitingFolder;
    private int filter;
    private final JobsAdapter adapter = new JobsAdapter();

    private final ActivityResultLauncher<String[]> chooseInputs = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris.isEmpty()) return;
                pending.clear();
                for (Uri uri : uris) {
                    if (!pending.contains(uri)) pending.add(uri);
                    takePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                setFormExpanded(true);
                updateSelection();
            });
    private final ActivityResultLauncher<Uri> chooseFolder = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), uri -> {
                boolean shouldStart = awaitingFolder;
                awaitingFolder = false;
                if (uri == null) return;
                folder = uri;
                takePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                folderLabel = folderName();
                getPreferences(MODE_PRIVATE).edit().putString("folder", uri.toString()).putString("folderLabel", folderLabel).apply();
                updateSelection();
                if (shouldStart) requestStart();
            });
    private final ActivityResultLauncher<String> notifications = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> enqueuePending());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        EdgeToEdge.enable(this);
        getSharedPreferences("toolbox", MODE_PRIVATE).edit().putString("lastTool", "compression").apply();
        String remembered = getPreferences(MODE_PRIVATE).getString("folder", null);
        if (remembered != null) folder = Uri.parse(remembered);
        folderLabel = getPreferences(MODE_PRIVATE).getString("folderLabel", "已选文件夹");
        if (state != null) {
            restoredState = true;
            formExpanded = state.getBoolean("formExpanded", true);
            ArrayList<String> inputs = state.getStringArrayList("pending");
            if (inputs != null) for (String input : inputs) pending.add(Uri.parse(input));
            ArrayList<String> checked = state.getStringArrayList("selected");
            if (checked != null) selected.addAll(checked);
            filter = state.getInt("filter");
            awaitingFolder = state.getBoolean("awaitingFolder");
        }
        createScreen();
        try {
            history = CompressionHistory.get(this);
            history.observe().observe(this, records -> { jobs = records; render(); });
        } catch (IllegalStateException error) {
            start.setEnabled(false);
            summary.setText(error.getMessage());
        }
        updateSelection();
    }

    private void createScreen() {
        screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(20), dp(12), dp(20), dp(8));
        setContentView(screen);
        ViewCompat.setOnApplyWindowInsetsListener(screen, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int horizontal = Math.max(dp(20), (view.getWidth() - dp(680)) / 2);
            view.setPadding(horizontal + bars.left, dp(12) + bars.top, horizontal + bars.right, dp(8) + bars.bottom);
            return insets;
        });
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        ScrollView headerScroll = new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int available = View.MeasureSpec.getSize(heightSpec);
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(available * 2 / 3, View.MeasureSpec.AT_MOST));
            }
        };
        headerScroll.addView(controls);
        screen.addView(headerScroll, new LinearLayout.LayoutParams(-1, -2));
        TextView brand = text(getString(R.string.app_name), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        controls.addView(brand); space(controls, 12);
        MaterialButtonToggleGroup tools = new MaterialButtonToggleGroup(this);
        tools.setSingleSelection(true); tools.setSelectionRequired(true); tools.setSaveFromParentEnabled(false);
        MaterialButton video = button("视频转音频", true);
        video.setId(R.id.tool_extract);
        MaterialButton audio = button("录音压缩", true);
        audio.setId(R.id.tool_compress);
        tools.addView(video, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(audio, new LinearLayout.LayoutParams(0, -2, 1));
        tools.check(R.id.tool_compress);
        tools.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked && id == R.id.tool_extract) {
                getSharedPreferences("toolbox", MODE_PRIVATE).edit().putString("lastTool", "video").apply();
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("showVideo", true));
                finish();
            }
        });
        controls.addView(tools, new LinearLayout.LayoutParams(-1, -2)); space(controls, 14);
        controls.addView(text("录音批量压缩", com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall));
        TextView description = text("一次选多份，后台依次处理。完成一份就能分享。",
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        description.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        controls.addView(description); space(controls, 10);
        setupToggle = button("收起添加区域", true);
        setupToggle.setOnClickListener(view -> setFormExpanded(!formExpanded));
        controls.addView(setupToggle, new LinearLayout.LayoutParams(-1, -2));
        setup = new LinearLayout(this); setup.setOrientation(LinearLayout.VERTICAL);
        controls.addView(setup, new LinearLayout.LayoutParams(-1, -2));
        MaterialButton select = button("选择录音（可多选）", true);
        select.setIconResource(R.drawable.ic_audio);
        select.setOnClickListener(view -> {
            try { chooseInputs.launch(new String[]{"audio/*"}); }
            catch (ActivityNotFoundException error) { feedback("手机没有可用的文件选择器。"); }
        });
        setup.addView(select, new LinearLayout.LayoutParams(-1, -2));
        selectedText = text("", com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        selectedText.setMaxLines(2); selectedText.setEllipsize(TextUtils.TruncateAt.END);
        setup.addView(selectedText);
        directory = button("选择保存文件夹", true);
        directory.setOnClickListener(view -> {
            awaitingFolder = false;
            launchFolder();
        });
        setup.addView(directory, new LinearLayout.LayoutParams(-1, -2));
        start = button("开始批量压缩", false);
        start.setOnClickListener(view -> requestStart());
        setup.addView(start, new LinearLayout.LayoutParams(-1, -2)); space(setup, 8);
        TextView note = text("M4A · 单声道 · 32 kbps · 人声采样率最高 16 kHz\n保留原录音，约 14 MB / 小时，仅在手机本地处理。",
                com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        note.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        setup.addView(note); space(setup, 10);
        LinearLayout statusLine = new LinearLayout(this); statusLine.setGravity(Gravity.CENTER_VERTICAL);
        summary = text("处理记录", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusLine.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
        stop = button("停止队列", true);
        stop.setOnClickListener(view -> new MaterialAlertDialogBuilder(this).setTitle("停止剩余处理？")
                .setMessage("保留已完成的文件，清理当前未完成的文件。")
                .setNegativeButton("继续处理", null).setPositiveButton("停止", (dialog, which) ->
                        startService(new Intent(this, CompressionService.class).setAction(CompressionService.CANCEL))).show());
        statusLine.addView(stop, new LinearLayout.LayoutParams(-2, -2));
        controls.addView(statusLine, new LinearLayout.LayoutParams(-1, -2));
        ChipGroup filters = new ChipGroup(this); filters.setSingleSelection(true); filters.setSelectionRequired(true);
        filters.setSaveFromParentEnabled(false);
        String[] labels = {"全部", "处理中", "已完成", "未完成"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            Chip chip = new Chip(this); chip.setId(View.generateViewId());
            chip.setText(labels[i]); chip.setCheckable(true); chip.setEnsureMinTouchTargetSize(true);
            filters.addView(chip);
            chip.setChecked(i == filter);
            chip.setOnCheckedChangeListener((view, checked) -> { if (checked) { filter = index; render(); } });
        }
        controls.addView(filters);
        empty = text("还没有处理记录。选择录音后开始压缩。", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        controls.addView(empty);
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this)); list.setAdapter(adapter);
        list.setItemAnimator(null);
        screen.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(this);
        selectAll = button("全选已完成", true); share = button("分享已选", false);
        selectAll.setOnClickListener(view -> {
            boolean all = true;
            for (CompressionHistory.Job job : visible) if (job.status == CompressionHistory.Status.SUCCESS && !selected.contains(job.id)) all = false;
            for (CompressionHistory.Job job : visible) if (job.status == CompressionHistory.Status.SUCCESS) {
                if (all) selected.remove(job.id); else selected.add(job.id);
            }
            adapter.notifyItemRangeChanged(0, adapter.getItemCount()); updateSharing();
        });
        share.setOnClickListener(view -> {
            ArrayList<Uri> outputs = new ArrayList<>();
            for (CompressionHistory.Job job : jobs) if (job.status == CompressionHistory.Status.SUCCESS && selected.contains(job.id)) outputs.add(Uri.parse(job.output));
            share(outputs);
        });
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.setMarginEnd(dp(8));
        actions.addView(selectAll, left); actions.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        screen.addView(actions, new LinearLayout.LayoutParams(-1, -2));
    }

    private void updateSelection() {
        if (selectedText == null) return;
        selectedText.setText(pending.isEmpty() ? "支持 WAV、M4A 等手机可解码的录音" : getString(R.string.recordings_selected, pending.size()));
        directory.setText(folder == null ? "选择保存文件夹" : "保存到：" + folderLabel);
        start.setEnabled(history != null && !pending.isEmpty());
        start.setText(history != null && history.hasWork() ? "加入处理队列" : "开始批量压缩");
    }
    private String folderName() {
        try {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
            try (Cursor cursor = getContentResolver().query(document, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
            }
        } catch (RuntimeException ignored) { }
        return "已选文件夹";
    }
    private void launchFolder() {
        try { chooseFolder.launch(folder); }
        catch (ActivityNotFoundException error) { awaitingFolder = false; feedback("手机没有可用的文件夹选择器。"); }
    }
    private void requestStart() {
        if (pending.isEmpty() || history == null) return;
        if (ExtractionStore.current().phase == ExtractionStore.Phase.WORKING) { feedback("请等待当前视频提取结束。"); return; }
        if (folder == null) { awaitingFolder = true; launchFolder(); return; }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else enqueuePending();
    }
    private void enqueuePending() {
        if (pending.isEmpty() || folder == null) return;
        if (enqueue(new ArrayList<>(pending), folder)) { pending.clear(); setFormExpanded(false); updateSelection(); }
    }
    private boolean enqueue(ArrayList<Uri> inputs, Uri destination) {
        try {
            ContextCompat.startForegroundService(this, new Intent(this, CompressionService.class)
                    .setAction(CompressionService.ENQUEUE).putParcelableArrayListExtra(CompressionService.INPUTS, inputs)
                    .putExtra(CompressionService.FOLDER, destination));
            return true;
        } catch (RuntimeException error) { feedback("无法开始处理，请稍后重试。"); return false; }
    }
    private void render() {
        if (summary == null) return;
        if (firstRender) {
            if (!restoredState && !jobs.isEmpty() && pending.isEmpty()) formExpanded = false;
            firstRender = false;
        }
        setFormExpanded(formExpanded);
        int queued = 0, running = 0, done = 0, failed = 0;
        visible = new ArrayList<>();
        for (CompressionHistory.Job job : jobs) {
            if (job.status == CompressionHistory.Status.QUEUED) queued++;
            else if (job.status == CompressionHistory.Status.RUNNING) running++;
            else if (job.status == CompressionHistory.Status.SUCCESS) done++;
            else failed++;
            boolean work = job.status == CompressionHistory.Status.QUEUED || job.status == CompressionHistory.Status.RUNNING;
            if (filter == 0 || filter == 1 && work || filter == 2 && job.status == CompressionHistory.Status.SUCCESS
                    || filter == 3 && !work && job.status != CompressionHistory.Status.SUCCESS) visible.add(job);
        }
        visible.sort((a, b) -> {
            boolean aWork = a.status == CompressionHistory.Status.QUEUED || a.status == CompressionHistory.Status.RUNNING;
            boolean bWork = b.status == CompressionHistory.Status.QUEUED || b.status == CompressionHistory.Status.RUNNING;
            if (aWork != bWork) return aWork ? -1 : 1;
            return aWork ? 0 : Long.compare(b.startedAt, a.startedAt);
        });
        String counts = getString(R.string.queue_summary, queued, running, done, failed);
        if (!counts.contentEquals(summary.getText())) summary.setText(counts);
        stop.setVisibility(queued + running > 0 ? View.VISIBLE : View.GONE);
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(jobs.isEmpty() ? "还没有处理记录。选择录音后开始压缩。" : "暂无这类记录。");
        adapter.submitList(visible);
        updateSelection(); updateSharing();
    }
    private void updateSharing() {
        int count = 0, completed = 0;
        for (CompressionHistory.Job job : jobs) if (job.status == CompressionHistory.Status.SUCCESS && selected.contains(job.id)) count++;
        for (CompressionHistory.Job job : visible) if (job.status == CompressionHistory.Status.SUCCESS) completed++;
        share.setText(getString(R.string.share_selected, count)); share.setEnabled(count > 0);
        selectAll.setEnabled(completed > 0);
    }
    private void share(ArrayList<Uri> outputs) {
        if (outputs.isEmpty()) return;
        Intent intent = AudioSharing.intent(getContentResolver(), outputs);
        try { startActivity(Intent.createChooser(intent, "分享压缩录音")); }
        catch (ActivityNotFoundException error) { feedback("手机没有可用的分享应用。"); }
    }
    private void listen(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (ActivityNotFoundException error) { feedback("未找到音频播放器，可以尝试分享打开。"); }
    }
    private void takePermission(Uri uri, int flags) {
        try { getContentResolver().takePersistableUriPermission(uri, flags); }
        catch (SecurityException ignored) { }
    }
    private void setFormExpanded(boolean expanded) {
        formExpanded = expanded;
        if (setup == null) return;
        setup.setVisibility(expanded ? View.VISIBLE : View.GONE);
        setupToggle.setVisibility(jobs.isEmpty() && expanded ? View.GONE : View.VISIBLE);
        setupToggle.setText(expanded ? "收起添加区域" : "添加录音 / 保存位置");
    }
    private TextView text(String value, int style) { TextView text = new TextView(this); text.setTextAppearance(style); text.setText(value); return text; }
    private MaterialButton button(String value, boolean outlined) {
        MaterialButton button = new MaterialButton(this, null, outlined ? com.google.android.material.R.attr.materialButtonOutlinedStyle : com.google.android.material.R.attr.materialButtonStyle);
        button.setText(value); button.setMinHeight(dp(48)); button.setCornerRadius(dp(14)); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int attribute) { return MaterialColors.getColor(screen, attribute); }
    private void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private void feedback(String message) { Snackbar.make(screen, message, Snackbar.LENGTH_LONG).show(); }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        ArrayList<String> inputs = new ArrayList<>(); for (Uri uri : pending) inputs.add(uri.toString());
        state.putStringArrayList("pending", inputs); state.putStringArrayList("selected", new ArrayList<>(selected));
        state.putInt("filter", filter); state.putBoolean("awaitingFolder", awaitingFolder);
        state.putBoolean("formExpanded", formExpanded);
    }

    private final class JobsAdapter extends ListAdapter<CompressionHistory.Job, JobHolder> {
        JobsAdapter() { super(new DiffUtil.ItemCallback<CompressionHistory.Job>() {
            @Override public boolean areItemsTheSame(CompressionHistory.Job a, CompressionHistory.Job b) { return a.id.equals(b.id); }
            @Override public boolean areContentsTheSame(CompressionHistory.Job a, CompressionHistory.Job b) {
                return a.status.equals(b.status) && a.progress == b.progress && a.name.equals(b.name) && a.message.equals(b.message)
                        && a.output.equals(b.output) && a.inputBytes == b.inputBytes && a.outputBytes == b.outputBytes && a.elapsedMs == b.elapsedMs;
            }
        }); }
        @Override public JobHolder onCreateViewHolder(ViewGroup parent, int type) { return new JobHolder(); }
        @Override public void onBindViewHolder(JobHolder holder, int position) { holder.bind(getItem(position)); }
    }
    private final class JobHolder extends RecyclerView.ViewHolder {
        final TextView title, details;
        final MaterialCheckBox check;
        final LinearProgressIndicator progress;
        final LinearLayout actions;
        JobHolder() {
            super(new MaterialCardView(CompressionActivity.this));
            MaterialCardView card = (MaterialCardView) itemView;
            card.setRadius(dp(16)); card.setCardElevation(0); card.setStrokeWidth(dp(1));
            card.setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant));
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2); params.bottomMargin = dp(10); card.setLayoutParams(params);
            LinearLayout body = new LinearLayout(CompressionActivity.this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout heading = new LinearLayout(CompressionActivity.this); heading.setGravity(Gravity.CENTER_VERTICAL);
            title = text("", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
            title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END);
            heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            check = new MaterialCheckBox(CompressionActivity.this); heading.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
            body.addView(heading);
            details = text("", com.google.android.material.R.style.TextAppearance_Material3_BodySmall); body.addView(details); space(body, 6);
            progress = new LinearProgressIndicator(CompressionActivity.this); body.addView(progress, new LinearLayout.LayoutParams(-1, dp(4)));
            actions = new LinearLayout(CompressionActivity.this); body.addView(actions); card.addView(body);
        }
        void bind(CompressionHistory.Job job) {
            title.setText(job.name); title.setContentDescription(job.name);
            boolean success = job.status == CompressionHistory.Status.SUCCESS;
            boolean running = job.status == CompressionHistory.Status.RUNNING;
            check.setOnCheckedChangeListener(null); check.setVisibility(success ? View.VISIBLE : View.GONE);
            check.setChecked(selected.contains(job.id)); check.setContentDescription("选择 " + job.name);
            check.setOnCheckedChangeListener((button, checked) -> { if (checked) selected.add(job.id); else selected.remove(job.id); updateSharing(); });
            String status = job.status == CompressionHistory.Status.QUEUED ? "等待处理"
                    : running ? (job.progress < 0 ? "正在准备…" : "正在压缩 · " + job.progress + "%")
                    : success ? "已完成" : job.status == CompressionHistory.Status.CANCELLED ? "已停止" : "处理失败";
            String content = status;
            if (success) {
                content += " · " + FileNames.duration(job.durationUs) + "\n" + FileNames.size(job.inputBytes) + " → " + FileNames.size(job.outputBytes);
                if (job.inputBytes > 0) content += String.format(Locale.ROOT, "，减小 %.0f%%", (1 - job.outputBytes / (double) job.inputBytes) * 100);
                content += " · 用时 " + FileNames.duration(job.elapsedMs * 1000);
                if (!job.message.isEmpty()) content += "\n" + job.message;
            } else if (!running && job.status != CompressionHistory.Status.QUEUED) content += "\n" + job.message;
            else if (job.inputBytes >= 0) content += " · " + FileNames.size(job.inputBytes);
            details.setText(content); details.setTextColor(color(job.status == CompressionHistory.Status.FAILED
                    ? com.google.android.material.R.attr.colorError : com.google.android.material.R.attr.colorOnSurfaceVariant));
            progress.setVisibility(running ? View.VISIBLE : View.GONE); progress.setIndeterminate(job.progress < 0);
            if (job.progress >= 0) progress.setProgressCompat(job.progress, false);
            actions.removeAllViews();
            if (success) {
                MaterialButton listen = button("试听", true), send = button("分享", true);
                listen.setOnClickListener(view -> listen(Uri.parse(job.output)));
                send.setOnClickListener(view -> { ArrayList<Uri> uris = new ArrayList<>(); uris.add(Uri.parse(job.output)); share(uris); });
                LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1); first.setMarginEnd(dp(8));
                actions.addView(listen, first); actions.addView(send, new LinearLayout.LayoutParams(0, -2, 1));
            } else if (!running && job.status != CompressionHistory.Status.QUEUED) {
                MaterialButton retry = button("重试这份录音", true);
                retry.setOnClickListener(view -> { ArrayList<Uri> inputs = new ArrayList<>(); inputs.add(Uri.parse(job.input)); enqueue(inputs, Uri.parse(job.folder)); });
                actions.addView(retry, new LinearLayout.LayoutParams(-1, -2));
            }
        }
    }
}
