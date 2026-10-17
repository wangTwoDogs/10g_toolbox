package com.ergoushushu.toolbox;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ergoushushu.toolbox.audio.AudioExtractor;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService inspector = Executors.newSingleThreadExecutor();
    private Uri selectedVideo;
    private String selectedName = "";
    private boolean inputReady;
    private int inspectGeneration;
    private LinearLayout screen;
    private TextView sourceName;
    private TextView sourceDetails;
    private TextView statusTitle;
    private TextView statusDetails;
    private MaterialButton selectButton;
    private MaterialButton extractButton;
    private MaterialButton cancelButton;
    private LinearLayout resultActions;
    private LinearLayout statusPanel;
    private LinearProgressIndicator progress;
    private boolean awaitingOutput;
    private boolean pendingChooseOutput;
    private boolean compressionMode;
    private MaterialButtonToggleGroup tools;
    private TextView toolLabel;
    private TextView toolTitle;
    private TextView introduction;
    private TextView format;
    private TextView note;

    private final ActivityResultLauncher<String[]> openVideo = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    selectedVideo = uri;
                    takePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    ExtractionStore.reset();
                    inspectSelectedVideo();
                }
            });

    private final ActivityResultLauncher<String> saveAudio = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("audio/mp4"), uri -> {
                awaitingOutput = false;
                if (uri != null && selectedVideo != null) {
                    takePermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    startExtraction(uri);
                } else {
                    render(ExtractionStore.current());
                }
            });

    private final ActivityResultLauncher<String> notifications = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> chooseOutput());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        compressionMode = getPreferences(MODE_PRIVATE).getBoolean("compressionMode", false);
        if (savedInstanceState != null) {
            compressionMode = savedInstanceState.getBoolean("compressionMode", compressionMode);
            String input = savedInstanceState.getString("selectedVideo");
            if (input != null) selectedVideo = Uri.parse(input);
            selectedName = savedInstanceState.getString("selectedName", "");
            awaitingOutput = savedInstanceState.getBoolean("awaitingOutput", false);
            pendingChooseOutput = savedInstanceState.getBoolean("pendingChooseOutput", false);
        }
        if (ExtractionStore.current().phase != ExtractionStore.Phase.IDLE) {
            compressionMode = ExtractionStore.current().compressed;
        }
        createScreen();
        updateToolLabels();
        tools.check(compressionMode ? R.id.tool_compress : R.id.tool_extract);
        ExtractionStore.observe().observe(this, this::render);
        if (selectedVideo != null) inspectSelectedVideo();
    }

    private void createScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(screen, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int contentWidth = right - left - scroll.getPaddingLeft() - scroll.getPaddingRight();
            int horizontal = Math.max(dp(24), (contentWidth - dp(640)) / 2);
            if (screen.getPaddingLeft() != horizontal) screen.setPadding(horizontal, dp(24), horizontal, dp(24));
        });

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.app_icon);
        avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView appName = text(getString(R.string.app_name), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        LinearLayout.LayoutParams brandText = new LinearLayout.LayoutParams(-2, -2);
        brandText.setMarginStart(dp(12));
        brand.addView(appName, brandText);
        screen.addView(brand);
        addSpace(screen, 24);

        tools = new MaterialButtonToggleGroup(this);
        // Tool state is restored from the active task / saved mode. Do not let Android's
        // later view-hierarchy restoration overwrite it with an older checked button.
        tools.setSaveFromParentEnabled(false);
        tools.setSingleSelection(true);
        tools.setSelectionRequired(true);
        MaterialButton videoTool = button("视频转音频", true);
        videoTool.setId(R.id.tool_extract);
        MaterialButton compressTool = button("录音压缩", true);
        compressTool.setId(R.id.tool_compress);
        tools.addView(videoTool, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addView(compressTool, new LinearLayout.LayoutParams(0, -2, 1));
        tools.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) switchTool(id == R.id.tool_compress);
        });
        screen.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        addSpace(screen, 28);

        toolLabel = text("工具 01 · 音视频", com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        toolLabel.setTextColor(color(androidx.appcompat.R.attr.colorPrimary));
        screen.addView(toolLabel);
        addSpace(screen, 8);
        toolTitle = text("视频转音频", com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium);
        screen.addView(toolTitle);
        addSpace(screen, 8);
        introduction = text("把视频里的声音，单独保存下来。", com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        introduction.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        screen.addView(introduction);
        addSpace(screen, 28);

        MaterialCardView fileCard = new MaterialCardView(this);
        fileCard.setRadius(dp(20));
        fileCard.setCardElevation(0);
        fileCard.setStrokeWidth(dp(1));
        fileCard.setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant));
        LinearLayout fileContent = new LinearLayout(this);
        fileContent.setOrientation(LinearLayout.VERTICAL);
        fileContent.setPadding(dp(20), dp(20), dp(20), dp(20));
        sourceName = text("还没有选择视频", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        fileContent.addView(sourceName);
        addSpace(fileContent, 8);
        sourceDetails = text("从手机文件中选择一个视频", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        sourceDetails.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        fileContent.addView(sourceDetails);
        addSpace(fileContent, 20);
        selectButton = button(getString(R.string.select_video), true);
        selectButton.setIconResource(R.drawable.ic_video);
        selectButton.setOnClickListener(view -> {
            try { openVideo.launch(new String[]{compressionMode ? "audio/*" : "video/*"}); }
            catch (ActivityNotFoundException error) { feedback("手机没有可用的文件选择器。"); }
        });
        fileContent.addView(selectButton, new LinearLayout.LayoutParams(-1, -2));
        fileCard.addView(fileContent);
        screen.addView(fileCard, new LinearLayout.LayoutParams(-1, -2));
        addSpace(screen, 20);

        format = text("保存格式：M4A", com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        screen.addView(format);
        addSpace(screen, 6);
        note = text("仅在手机本地处理，视频不会上传。", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        note.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        screen.addView(note);
        addSpace(screen, 20);
        extractButton = button(getString(R.string.extract_audio), false);
        extractButton.setIconResource(R.drawable.ic_audio);
        extractButton.setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS);
            } else chooseOutput();
        });
        screen.addView(extractButton, new LinearLayout.LayoutParams(-1, -2));
        addSpace(screen, 24);

        statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusTitle = text("", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        statusTitle.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusPanel.addView(statusTitle);
        addSpace(statusPanel, 8);
        statusDetails = text("", com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        statusDetails.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        statusPanel.addView(statusDetails);
        addSpace(statusPanel, 16);
        progress = new LinearProgressIndicator(this);
        progress.setTrackCornerRadius(dp(4));
        statusPanel.addView(progress, new LinearLayout.LayoutParams(-1, dp(8)));
        addSpace(statusPanel, 16);
        cancelButton = button(getString(R.string.cancel), true);
        cancelButton.setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setTitle(compressionMode ? "取消这次压缩？" : "取消这次提取？")
                .setMessage("未完成的音频文件会被清理。")
                .setNegativeButton("继续处理", null)
                .setPositiveButton("取消处理", (dialog, which) -> startService(
                        new Intent(this, ExtractionService.class).setAction(ExtractionService.ACTION_CANCEL)))
                .show());
        statusPanel.addView(cancelButton, new LinearLayout.LayoutParams(-1, -2));
        resultActions = new LinearLayout(this);
        resultActions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton listen = button("试听音频", true);
        MaterialButton share = button("分享音频", true);
        listen.setOnClickListener(view -> listen());
        share.setOnClickListener(view -> share());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(0, -2, 1);
        actionParams.setMarginEnd(dp(8));
        resultActions.addView(listen, actionParams);
        resultActions.addView(share, new LinearLayout.LayoutParams(0, -2, 1));
        statusPanel.addView(resultActions);
        screen.addView(statusPanel);
        addSpace(screen, 24);
        TextView footer = text("二狗叔叔，帮你省点事。", com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        footer.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant));
        screen.addView(footer);
    }

    private void switchTool(boolean compress) {
        if (compressionMode == compress) return;
        if (ExtractionStore.current().phase == ExtractionStore.Phase.WORKING || awaitingOutput) return;
        compressionMode = compress;
        getPreferences(MODE_PRIVATE).edit().putBoolean("compressionMode", compress).apply();
        ++inspectGeneration;
        selectedVideo = null;
        selectedName = "";
        inputReady = false;
        pendingChooseOutput = false;
        ExtractionStore.reset();
        updateToolLabels();
        render(ExtractionStore.current());
    }

    private void updateToolLabels() {
        toolLabel.setText(compressionMode ? "工具 02 · 音频" : "工具 01 · 音视频");
        toolTitle.setText(compressionMode ? "录音压缩" : "视频转音频");
        introduction.setText(compressionMode ? "录音小一点，传输轻松点。" : "把视频里的声音，单独保存下来。");
        selectButton.setIconResource(compressionMode ? R.drawable.ic_audio : R.drawable.ic_video);
        format.setText(compressionMode ? "保存格式：M4A · 单声道 · 32 kbps" : "保存格式：M4A");
        note.setText(compressionMode
                ? "适合人声录音，有损压缩，每小时约 14 MB。\n保留原文件，仅在手机本地处理。"
                : "仅在手机本地处理，视频不会上传。");
        if (selectedVideo == null) {
            sourceName.setText(compressionMode ? "还没有选择录音" : "还没有选择视频");
            sourceDetails.setText(compressionMode ? "从手机文件中选择一份录音" : "从手机文件中选择一个视频");
        }
    }

    private String outputName() {
        return compressionMode ? FileNames.compressedName(selectedName) : FileNames.audioName(selectedName);
    }

    private void chooseOutput() {
        if (selectedVideo == null || ExtractionStore.current().phase == ExtractionStore.Phase.WORKING) return;
        pendingChooseOutput = true;
        if (!inputReady) return;
        try {
            pendingChooseOutput = false;
            awaitingOutput = true;
            render(ExtractionStore.current());
            saveAudio.launch(outputName());
        } catch (ActivityNotFoundException error) {
            awaitingOutput = false;
            render(ExtractionStore.current());
            feedback("手机没有可用的保存文件选择器。");
        }
    }

    private void inspectSelectedVideo() {
        inputReady = false;
        int generation = ++inspectGeneration;
        Uri input = selectedVideo;
        sourceName.setText(compressionMode ? "正在读取录音…" : "正在读取视频…");
        sourceDetails.setText("请稍候");
        render(ExtractionStore.current());
        inspector.execute(() -> {
            String name = compressionMode ? "录音" : "视频";
            long bytes = -1;
            try (Cursor cursor = getContentResolver().query(input,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) bytes = cursor.getLong(sizeColumn);
                }
            } catch (RuntimeException ignored) { /* Media inspection below supplies a useful failure. */ }
            final String fileName = name;
            final long fileBytes = bytes;
            try {
                AudioExtractor.AudioInfo info = AudioExtractor.inspect(getApplicationContext(), input);
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != inspectGeneration) return;
                    selectedName = fileName;
                    inputReady = true;
                    sourceName.setText(fileName);
                    String details = FileNames.duration(info.durationUs);
                    if (fileBytes >= 0) details += " · " + FileNames.size(fileBytes);
                    if (compressionMode && info.durationUs > 0) {
                        long estimated = (long) (info.durationUs / 1_000_000d * 4_000);
                        details += "\n预计压缩后约 " + FileNames.size(estimated) + "，实际大小以结果为准";
                    } else {
                        details += compressionMode ? "\n将压缩为单声道 M4A"
                                : (info.willTranscode ? "\n将转换为 M4A 音频" : "\n原音质提取");
                    }
                    sourceDetails.setText(details);
                    render(ExtractionStore.current());
                    if (pendingChooseOutput) chooseOutput();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != inspectGeneration) return;
                    selectedName = fileName;
                    pendingChooseOutput = false;
                    sourceName.setText(fileName);
                    sourceDetails.setText(error.getMessage() == null ? "无法读取这个文件，请重新选择。" : error.getMessage());
                    render(ExtractionStore.current());
                });
            }
        });
    }

    private void startExtraction(Uri output) {
        String name = outputName();
        Intent intent = new Intent(this, ExtractionService.class).setAction(ExtractionService.ACTION_START)
                .putExtra(ExtractionService.EXTRA_INPUT, selectedVideo)
                .putExtra(ExtractionService.EXTRA_OUTPUT, output)
                .putExtra(ExtractionService.EXTRA_NAME, name)
                .putExtra(ExtractionService.EXTRA_COMPRESS, compressionMode);
        try {
            ExtractionStore.update(new ExtractionStore.State(ExtractionStore.Phase.WORKING, -1,
                    "正在准备处理", name, output, 0, 0, compressionMode, -1));
            ContextCompat.startForegroundService(this, intent);
        } catch (RuntimeException error) {
            boolean cleaned = OutputFiles.deleteCreatedOutput(this, output);
            ExtractionStore.update(new ExtractionStore.State(ExtractionStore.Phase.FAILED, 0,
                    "无法开始处理，请重试。" + (cleaned ? "" : "\n保存位置可能留下空文件，请手动删除。"),
                    name, output, 0, 0, compressionMode, -1));
        }
    }

    private void render(ExtractionStore.State state) {
        boolean busy = state.phase == ExtractionStore.Phase.WORKING;
        boolean success = state.phase == ExtractionStore.Phase.SUCCESS;
        for (int index = 0; index < tools.getChildCount(); index++) {
            tools.getChildAt(index).setEnabled(!busy && !awaitingOutput);
        }
        selectButton.setEnabled(!busy && !awaitingOutput);
        selectButton.setText(compressionMode
                ? (selectedVideo == null ? "选择录音" : "重新选择录音")
                : (selectedVideo == null ? "选择视频" : "重新选择视频"));
        extractButton.setEnabled(inputReady && !busy && !awaitingOutput);
        extractButton.setText(compressionMode ? (busy ? "正在压缩…" : "压缩并保存录音")
                : (busy ? "正在提取…" : "提取并保存音频"));
        statusPanel.setVisibility(state.phase == ExtractionStore.Phase.IDLE ? View.GONE : View.VISIBLE);
        cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        resultActions.setVisibility(success ? View.VISIBLE : View.GONE);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) {
            statusTitle.setTextColor(color(com.google.android.material.R.attr.colorOnSurface));
            progress.setIndeterminate(state.progress < 0);
            if (state.progress >= 0) progress.setProgressCompat(state.progress, false);
            String operation = compressionMode ? "正在压缩录音" : "正在提取音频";
            statusTitle.setText(state.progress < 0 ? operation + "…" : operation + " · " + state.progress + "%");
            statusDetails.setText(state.fileName);
        } else {
            statusTitle.setText(state.message);
            statusTitle.setTextColor(color(state.phase == ExtractionStore.Phase.FAILED
                    ? com.google.android.material.R.attr.colorError : com.google.android.material.R.attr.colorOnSurface));
            String details = state.fileName + "\n" + FileNames.duration(state.durationUs)
                    + " · " + FileNames.size(state.outputBytes);
            if (success && state.compressed && state.inputBytes > 0) {
                details += "\n" + FileNames.size(state.inputBytes) + " → " + FileNames.size(state.outputBytes)
                        + String.format(java.util.Locale.ROOT, "，减小 %.0f%%",
                        (1 - state.outputBytes / (double) state.inputBytes) * 100);
            }
            statusDetails.setText(success ? details : "可以重新选择文件或再次处理。");
        }
    }

    private void takePermission(Uri uri, int flags) {
        try { getContentResolver().takePersistableUriPermission(uri, flags); }
        catch (SecurityException ignored) { /* Some providers only offer a grant for this session. */ }
    }

    private void listen() {
        Uri output = ExtractionStore.current().output;
        if (output == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(output, "audio/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException error) { feedback("未找到音频播放器，可以通过分享用其他应用打开。"); }
    }

    private void share() {
        Uri output = ExtractionStore.current().output;
        if (output == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND).setType("audio/mp4")
                .putExtra(Intent.EXTRA_STREAM, output).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newUri(getContentResolver(), "提取的音频", output));
        try { startActivity(Intent.createChooser(intent, "分享音频")); }
        catch (ActivityNotFoundException error) { feedback("手机没有可用的分享应用。"); }
    }

    private TextView text(String value, int appearance) {
        TextView view = new TextView(this);
        view.setTextAppearance(appearance);
        view.setText(value);
        return view;
    }

    private MaterialButton button(String label, boolean outlined) {
        MaterialButton view = new MaterialButton(this, null,
                outlined ? com.google.android.material.R.attr.materialButtonOutlinedStyle
                        : com.google.android.material.R.attr.materialButtonStyle);
        view.setText(label);
        view.setMinHeight(dp(56));
        view.setCornerRadius(dp(16));
        return view;
    }

    private int color(int attribute) { return MaterialColors.getColor(screen, attribute); }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private void addSpace(LinearLayout parent, int size) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(size))); }
    private void feedback(String message) { Snackbar.make(screen, message, Snackbar.LENGTH_LONG).show(); }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (selectedVideo != null) outState.putString("selectedVideo", selectedVideo.toString());
        outState.putString("selectedName", selectedName);
        outState.putBoolean("awaitingOutput", awaitingOutput);
        outState.putBoolean("pendingChooseOutput", pendingChooseOutput);
        outState.putBoolean("compressionMode", compressionMode);
    }

    @Override protected void onDestroy() {
        inspector.shutdownNow();
        super.onDestroy();
    }
}
