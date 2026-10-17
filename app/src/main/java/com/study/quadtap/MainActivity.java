package com.study.quadtap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    /** 日志区最多显示多少行。完整日志仍然可以复制/分享。 */
    private static final int LOG_TAIL_LINES = 120;

    /**
     * 调色板。做成实例字段而不是 static final，是为了能在深色/浅色之间切换 ——
     * onCreate 里先按主题赋值，之后各处的用法完全不变。
     */
    private int COLOR_BG;
    private int COLOR_CARD;
    private int COLOR_STROKE;
    private int COLOR_TITLE;
    private int COLOR_TEXT;
    private int COLOR_HINT;
    private int COLOR_ACCENT;
    private int COLOR_OK;
    private int COLOR_WARN;
    private int COLOR_BAD;
    private int COLOR_BTN_SECONDARY;
    private int COLOR_ACCENT_PRESSED;
    private int COLOR_BTN_PRESSED;
    private int COLOR_LOG_BG;
    private int COLOR_LOG_TEXT;

    private boolean darkTheme = false;

    private void applyPalette(boolean dark) {
        darkTheme = dark;
        if (dark) {
            COLOR_BG = 0xFF000000;
            COLOR_CARD = 0xFF1A212B;
            COLOR_STROKE = 0xFF2B3542;
            COLOR_TITLE = 0xFFE9EEF7;
            COLOR_TEXT = 0xFFC4CDDB;
            COLOR_HINT = 0xFF8994A7;
            COLOR_ACCENT = 0xFF5B93F7;
            COLOR_ACCENT_PRESSED = 0xFF7FAEFA;
            COLOR_OK = 0xFF4CAF7D;
            COLOR_WARN = 0xFFD9A544;
            COLOR_BAD = 0xFFE0705F;
            COLOR_BTN_SECONDARY = 0xFF2B3542;
            COLOR_BTN_PRESSED = 0xFF3C4856;
            COLOR_LOG_BG = 0xFF0A0E14;
            COLOR_LOG_TEXT = 0xFFBAC4D4;
        } else {
            COLOR_BG = 0xFFF2F4F8;
            COLOR_CARD = 0xFFFFFFFF;
            COLOR_STROKE = 0xFFE4E9F2;
            COLOR_TITLE = 0xFF16203A;
            COLOR_TEXT = 0xFF33405A;
            COLOR_HINT = 0xFF7C879C;
            COLOR_ACCENT = 0xFF3478F6;
            COLOR_ACCENT_PRESSED = 0xFF255FC9;
            COLOR_OK = 0xFF1F8A4C;
            COLOR_WARN = 0xFFB07A16;
            COLOR_BAD = 0xFFC0392B;
            COLOR_BTN_SECONDARY = 0xFFE7ECF5;
            COLOR_BTN_PRESSED = 0xFFD3DCEA;
            COLOR_LOG_BG = 0xFF1B2130;
            COLOR_LOG_TEXT = 0xFFD5DCE8;
        }
    }

    /** 系统当前是否处于深色模式。 */
    private boolean systemInDarkMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 按「设置里的选择 + 系统状态」算出实际该用深色还是浅色。 */
    private boolean resolveDark() {
        switch (prefs.themeMode()) {
            case Prefs.THEME_LIGHT: return false;
            case Prefs.THEME_DARK: return true;
            default: return systemInDarkMode();
        }
    }

    private Prefs prefs;
    private TextView appScopeLine;
    private TextView sizeLabel;
    private TextView logView;
    private TextView versionLine;
    /** 卡片 2 顶部的一行状态：已校准数量 / 上次识别数量 / 当前已确证选项数。 */
    private TextView recognizeInfoText;
    /** 自动检查更新每次启动只做一次，避免每次回到前台都请求网络。 */
    private boolean autoCheckDone = false;
    /** 正在检查时不要被 refreshStatus 覆盖掉提示文字。 */
    private boolean checkingUpdate = false;
    private AlertDialog downloadDialog;

    // 第一张运行控制卡的实时读数
    private TextView runStatusText;
    private View runStatusDot;
    private TextView runCountText;
    private TextView runTimeText;
    private TextView runStateText;
    private TextView runHintText;
    private Switch runSwitch;
    /** 程序化改开关状态时抑制监听回调，避免与真实状态互相打断。 */
    private boolean updatingRunSwitch = false;
    /** 每秒刷新运行时长。 */
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            updateRunTime();
            tickHandler.postDelayed(this, 1000);
        }
    };

    // ---- 钱包式卡片配色（固定品牌色，不随深浅主题变化）----
    private static final int CARD_BLUE = 0xFF1677FF;        // 卡片 1 运行控制
    private static final int CARD_LIGHT_BLUE = 0xFFD8E9FF;  // 卡片 2 识别与校准
    private static final int CARD_TEAL = 0xFF19C7C2;        // 卡片 3 运行日志
    private static final int CARD_DEEP_BLUE = 0xFF2867F0;   // 卡片 4 应用设置

    // 彩色卡片上的文字/按钮配色 —— 深色卡片一套，浅蓝卡片一套
    private static final int ON_DARK_TITLE = 0xFFFFFFFF;
    private static final int ON_DARK_TEXT = 0xFFE8F0FF;
    private static final int ON_DARK_HINT = 0xFFC2D8FF;
    private static final int ON_DARK_BTN = 0x33FFFFFF;
    private static final int ON_DARK_BTN_PRESSED = 0x5CFFFFFF;
    private static final int ON_DARK_STROKE = 0x33FFFFFF;

    private static final int ON_LIGHT_TITLE = 0xFF0A1F3D;
    private static final int ON_LIGHT_TEXT = 0xFF16324F;
    private static final int ON_LIGHT_HINT = 0xFF4A6480;
    private static final int ON_LIGHT_BTN = 0xFFFFFFFF;
    private static final int ON_LIGHT_BTN_PRESSED = 0xFFD3E2F2;
    private static final int ON_LIGHT_STROKE = 0x1F0A1F3D;

    /**
     * 把调色板切到某张卡片的配色。
     * 之后的 card() / cardTitle() / hintText() / 各种按钮都会自动跟随，
     * 因此原有那些 buildXxx 方法一行都不用改。
     */
    private void useCardStyle(int cardColor, boolean darkContent) {
        COLOR_CARD = cardColor;
        COLOR_STROKE = darkContent ? ON_DARK_STROKE : ON_LIGHT_STROKE;
        COLOR_TITLE = darkContent ? ON_DARK_TITLE : ON_LIGHT_TITLE;
        COLOR_TEXT = darkContent ? ON_DARK_TEXT : ON_LIGHT_TEXT;
        COLOR_HINT = darkContent ? ON_DARK_HINT : ON_LIGHT_HINT;
        COLOR_BTN_SECONDARY = darkContent ? ON_DARK_BTN : ON_LIGHT_BTN;
        COLOR_BTN_PRESSED = darkContent ? ON_DARK_BTN_PRESSED : ON_LIGHT_BTN_PRESSED;
        COLOR_ACCENT = darkContent ? ON_DARK_BTN : 0xFF1677FF;
        COLOR_ACCENT_PRESSED = darkContent ? ON_DARK_BTN_PRESSED : 0xFF255FC9;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        applyPalette(resolveDark());

        // targetSdk 35 起系统强制「边到边」：应用会被绘制到状态栏和导航栏下面，
        // setStatusBarColor 之类已经失效。所以必须自己把系统栏高度让出来，
        // 否则顶部标题会被状态栏压住（这正是之前的问题）。
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!darkTheme);
        controller.setAppearanceLightNavigationBars(!darkTheme);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // 单页纵向滚动 + 卡片堆叠。没有选项卡、没有滑动切换、没有拖动排序。
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(36));
        scroll.addView(page);

        buildRunCard(page);          // 卡片 1：运行控制（核心）
        buildRecognitionArea(page);  // 卡片 2：识别与校准
        buildLogArea(page);          // 卡片 3：运行日志
        buildSettingsArea(page);     // 卡片 4：应用设置

        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        applyWindowInsets(root);
        refreshStatus();
    }

    /**
     * 把状态栏和导航栏的高度变成根布局的内边距。
     * 边到边模式下不这么做，顶部内容就会钻到状态栏底下。
     */
    private void applyWindowInsets(final View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, new OnApplyWindowInsetsListener() {
            @Override public WindowInsetsCompat onApplyWindowInsets(View view, WindowInsetsCompat insets) {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            }
        });
        ViewCompat.requestApplyInsets(root);
    }

    private LinearLayout newPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(14), dp(16), dp(32));
        page.setBackgroundColor(COLOR_BG);
        return page;
    }

    private void buildFooter(LinearLayout page) {
        TextView foot = new TextView(this);
        foot.setText("隐私说明：屏幕内容仅用于本机识别选项位置，不会上传到任何服务器。"
                + "请仅在你有权操作的应用中使用，并遵守目标应用的规则。");
        foot.setTextSize(12);
        foot.setTextColor(COLOR_HINT);
        foot.setLineSpacing(dp(4), 1f);
        page.addView(foot, topMargin(22));
    }

    // ------------------------------------------------------------------ 卡片容器
    // 原「状态 / 识别 / 日志 / 设置」四个选项卡的导航已移除，
    // 四个页面的功能全部迁移到下面四张卡片里。

    /** 卡片 2：识别与校准（原「识别」页的识别开关 + 悬浮面板设置） */
    private void buildRecognitionArea(LinearLayout page) {
        useCardStyle(CARD_LIGHT_BLUE, false);
        LinearLayout card = card();
        card.addView(cardTitle("识别与校准"));

        // 恢复原先在状态页里展示的校准/识别数量
        recognizeInfoText = new TextView(this);
        recognizeInfoText.setTextSize(12);
        recognizeInfoText.setTextColor(COLOR_HINT);
        recognizeInfoText.setLineSpacing(dp(3), 1f);
        card.addView(recognizeInfoText, topMargin(6));

        buildRecognitionSection(card);
        buildPanelSection(card);
        page.addView(card, topMargin(16));
    }

    /** 卡片 3：运行日志（原「日志」页） */
    private void buildLogArea(LinearLayout page) {
        useCardStyle(CARD_TEAL, false);
        LinearLayout card = card();
        card.addView(cardTitle("运行日志"));
        buildLogSection(card);
        page.addView(card, topMargin(16));
    }

    /** 卡片 4：应用设置（原「识别」页的应用限定 + 原「设置」页的外观/更新/关于） */
    private void buildSettingsArea(LinearLayout page) {
        useCardStyle(CARD_DEEP_BLUE, true);
        LinearLayout card = card();
        card.addView(cardTitle("应用设置"));
        buildScopeSection(card);
        buildAppearanceSection(card);
        buildUpdateSection(card);
        buildFlowSection(card);
        buildAboutSection(card);
        buildFooter(card);
        page.addView(card, topMargin(16));
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        startTicking();
        if (!autoCheckDone && prefs.autoCheckUpdate()) {
            autoCheckDone = true;
            checkForUpdate(false);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        stopTicking();
    }

    /** 运行时长的秒级刷新：只在界面前台时跑，不影响服务里的计时起点。 */
    private void startTicking() {
        tickHandler.removeCallbacks(tickRunnable);
        tickHandler.post(tickRunnable);
    }

    private void stopTicking() {
        tickHandler.removeCallbacks(tickRunnable);
    }

    // ------------------------------------------------------------------ 卡片 1：运行控制

    private void buildRunCard(LinearLayout page) {
        useCardStyle(CARD_BLUE, true);

        LinearLayout card = card();
        card.setPadding(dp(20), dp(20), dp(20), dp(22));

        // 第一层：名称 + 真实状态
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText("QuadTap");
        name.setTextSize(20);
        name.setTextColor(ON_DARK_TITLE);
        name.setTypeface(null, Typeface.BOLD);
        topRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout statusWrap = new LinearLayout(this);
        statusWrap.setOrientation(LinearLayout.HORIZONTAL);
        statusWrap.setGravity(Gravity.CENTER_VERTICAL);
        runStatusDot = new View(this);
        statusWrap.addView(runStatusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        runStatusText = new TextView(this);
        runStatusText.setTextSize(13);
        runStatusText.setTextColor(ON_DARK_TEXT);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        statusParams.leftMargin = dp(6);
        statusWrap.addView(runStatusText, statusParams);
        topRow.addView(statusWrap);
        card.addView(topRow);

        // 第二层：辅助答题次数（卡内最醒目的数字）
        TextView countLabel = new TextView(this);
        countLabel.setText("辅助答题");
        countLabel.setTextSize(12);
        countLabel.setTextColor(ON_DARK_HINT);
        card.addView(countLabel, topMargin(20));

        runCountText = new TextView(this);
        runCountText.setTextSize(34);
        runCountText.setTextColor(ON_DARK_TITLE);
        runCountText.setTypeface(null, Typeface.BOLD);
        card.addView(runCountText, topMargin(2));

        // 第三层：运行时长 / 服务状态
        LinearLayout blocks = new LinearLayout(this);
        blocks.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout timeBlock = infoBlock();
        timeBlock.addView(blockLabel("运行时长"));
        runTimeText = blockValue("00:00:00");
        timeBlock.addView(runTimeText);
        blocks.addView(timeBlock, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout stateBlock = infoBlock();
        stateBlock.addView(blockLabel("服务状态"));
        runStateText = blockValue("已停止");
        stateBlock.addView(runStateText);
        LinearLayout.LayoutParams stateBlockParams = new LinearLayout.LayoutParams(0, -2, 1f);
        stateBlockParams.leftMargin = dp(10);
        blocks.addView(stateBlock, stateBlockParams);

        card.addView(blocks, topMargin(18));

        // 第四层：服务控制开关
        LinearLayout control = new LinearLayout(this);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);

        TextView controlLabel = new TextView(this);
        controlLabel.setText("服务控制");
        controlLabel.setTextSize(14);
        controlLabel.setTextColor(ON_DARK_TEXT);
        control.addView(controlLabel, new LinearLayout.LayoutParams(0, -2, 1f));

        runSwitch = new Switch(this);
        runSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (updatingRunSwitch) return;
                onRunSwitchChanged(isChecked);
            }
        });
        control.addView(runSwitch);
        card.addView(control, topMargin(18));

        // 权限提示 / 错误信息
        runHintText = new TextView(this);
        runHintText.setTextSize(12);
        runHintText.setTextColor(ON_DARK_HINT);
        runHintText.setLineSpacing(dp(4), 1f);
        card.addView(runHintText, topMargin(10));

        page.addView(card);
    }

    /** 卡内半透明信息块（运行时长 / 服务状态）。 */
    private LinearLayout infoBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x26FFFFFF);
        background.setCornerRadius(dp(14));
        box.setBackground(background);
        return box;
    }

    private TextView blockLabel(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11);
        view.setTextColor(ON_DARK_HINT);
        return view;
    }

    private TextView blockValue(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(ON_DARK_TITLE);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    /** 开关回调：只有用户操作才会走到这里，触发真实启停。 */
    private void onRunSwitchChanged(boolean wantOn) {
        if (wantOn) {
            if (!isAccessibilityEnabled()) {
                // 权限没开时绝不伪造运行状态，引导用户去系统设置
                setRunSwitchSilently(false);
                Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_SHORT).show();
                openAccessibilitySettings();
                refreshStatus();
                return;
            }
            prefs.setAssistRunning(true);
            QuadTapAccessibilityService.applyAssistRunningFromPrefs();
        } else {
            prefs.setAssistRunning(false);
            QuadTapAccessibilityService.applyAssistRunningFromPrefs();
        }
        refreshStatus();
    }

    private void setRunSwitchSilently(boolean checked) {
        if (runSwitch == null) return;
        updatingRunSwitch = true;
        runSwitch.setChecked(checked);
        updatingRunSwitch = false;
    }

    private void setRunDot(int color) {
        if (runStatusDot == null) return;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        runStatusDot.setBackground(dot);
    }

    /** 运行时长只在真正运行时累加；停止后显示 00:00:00。 */
    private void updateRunTime() {
        if (runTimeText == null) return;
        long startedAt = QuadTapAccessibilityService.runStartedAt();
        if (!QuadTapAccessibilityService.isAssistRunning() || startedAt <= 0L) {
            runTimeText.setText("00:00:00");
            return;
        }
        long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000L;
        if (seconds < 0) seconds = 0;
        runTimeText.setText(formatDuration(seconds));
    }

    /** HH:MM:SS，小时允许超过 24。 */
    private static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    // ------------------------------------------------------------------ 版本与更新

    private void buildUpdateSection(LinearLayout card) {
        card.addView(cardTitle("版本与更新"));

        versionLine = new TextView(this);
        versionLine.setTextSize(13);
        versionLine.setTextColor(COLOR_TEXT);
        versionLine.setLineSpacing(dp(4), 1f);
        card.addView(versionLine, topMargin(8));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(flexButton("检查更新", new View.OnClickListener() {
            @Override public void onClick(View v) { checkForUpdate(true); }
        }), flexParams(0));
        buttons.addView(flexButton("打开发布页", new View.OnClickListener() {
            @Override public void onClick(View v) { Updater.openReleasePage(MainActivity.this); }
        }), flexParams(dp(8)));
        card.addView(buttons, topMargin(12));

        Switch auto = toggle("启动时自动检查更新", prefs.autoCheckUpdate());
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setAutoCheckUpdate(isChecked);
            }
        });
        card.addView(auto, topMargin(6));

        card.addView(hintText("更新会先下载安装包再交给系统安装器。"
                + "Android 不允许应用静默安装，所以最后一定会弹一次系统确认框；"
                + "第一次使用还需要在系统设置里允许本应用安装未知应用。"), topMargin(0));

    }

    private String versionSummary() {
        return "当前版本：" + Updater.currentVersionName(this)
                + "（versionCode " + Updater.currentVersionCode(this) + "）";
    }

    private void checkForUpdate(final boolean userInitiated) {
        checkingUpdate = true;
        if (versionLine != null) versionLine.setText("正在检查更新…");
        Updater.check(new Updater.CheckCallback() {
            @Override public void onChecked(Updater.Release release, String error) {
                checkingUpdate = false;
                if (versionLine != null) versionLine.setText(versionSummary());

                if (error != null) {
                    if (userInitiated) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("检查更新失败")
                                .setMessage(error + "\n\n如果当前网络访问不了 GitHub，"
                                        + "可以点「打开发布页」用浏览器手动下载。")
                                .setPositiveButton("打开发布页", new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int w) {
                                        Updater.openReleasePage(MainActivity.this);
                                    }
                                })
                                .setNegativeButton("知道了", null)
                                .show();
                    }
                    return;
                }
                if (release == null || release.apkUrl == null) {
                    if (userInitiated) toast("这个版本没有附带 APK，请到发布页确认");
                    return;
                }
                String current = Updater.currentVersionName(MainActivity.this);
                if (Updater.compareVersions(release.versionName, current) <= 0) {
                    if (userInitiated) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("已是最新版本")
                                .setMessage("当前 " + current + "，没有更新的版本。")
                                .setPositiveButton("好", null)
                                .show();
                    }
                    return;
                }
                // 自动检查时，用户点过「以后再说」的版本不再打扰
                if (!userInitiated && release.versionName.equals(prefs.ignoredVersion())) return;
                showUpdateDialog(release);
            }
        });
    }

    private void showUpdateDialog(final Updater.Release release) {
        String notes = release.body == null ? "" : release.body.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "\n…";

        String message = "当前版本：" + Updater.currentVersionName(this)
                + "\n新版本：" + release.versionName + "（" + release.prettySize() + "）"
                + (notes.isEmpty() ? "" : "\n\n" + notes);

        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + release.tag)
                .setMessage(message)
                .setPositiveButton("立即更新", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startDownload(release); }
                })
                .setNegativeButton("以后再说", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        prefs.setIgnoredVersion(release.versionName);
                    }
                })
                .setNeutralButton("发布页", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Updater.openReleasePage(MainActivity.this);
                    }
                })
                .show();
    }

    private void startDownload(final Updater.Release release) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(10), dp(24), dp(10));

        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        final TextView label = new TextView(this);
        label.setTextSize(12);
        label.setTextColor(COLOR_HINT);
        label.setText("准备下载…");
        box.addView(label, topMargin(8));

        downloadDialog = new AlertDialog.Builder(this)
                .setTitle("正在下载 " + release.versionName)
                .setView(box)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { Updater.cancelDownload(); }
                })
                .setCancelable(false)
                .create();
        downloadDialog.show();

        Updater.download(this, release, new Updater.ProgressCallback() {
            @Override public void onProgress(int percent, long downloaded, long total) {
                bar.setProgress(percent);
                label.setText(percent + "%　" + String.format(Locale.US, "%.1f / %.1f MB",
                        downloaded / 1048576.0, total / 1048576.0));
            }

            @Override public void onFinished(File apk, String error) {
                dismissDownloadDialog();
                if (error != null) {
                    if ("已取消".equals(error)) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("下载失败")
                            .setMessage(error + "\n\n可以点「打开发布页」用浏览器手动下载。")
                            .setPositiveButton("打开发布页", new DialogInterface.OnClickListener() {
                                @Override public void onClick(DialogInterface d, int w) {
                                    Updater.openReleasePage(MainActivity.this);
                                }
                            })
                            .setNegativeButton("知道了", null)
                            .show();
                    return;
                }
                installApk(apk);
            }
        });
    }

    private void installApk(final File apk) {
        if (!Updater.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("还需要一次授权")
                    .setMessage("Android 要求由你手动允许应用安装未知来源的应用。\n\n"
                            + "接下来会打开设置页，请找到「" + getString(R.string.app_name)
                            + "」并打开「允许安装未知应用」，然后回到本页再点一次「检查更新」。")
                    .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Updater.openInstallPermissionSettings(MainActivity.this);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        String error = Updater.install(this, apk);
        if (error != null) {
            new AlertDialog.Builder(this)
                    .setTitle("无法启动安装")
                    .setMessage(error + "\n\n可以点「打开发布页」用浏览器手动下载。")
                    .setPositiveButton("打开发布页", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Updater.openReleasePage(MainActivity.this);
                        }
                    })
                    .setNegativeButton("知道了", null)
                    .show();
        }
    }

    private void dismissDownloadDialog() {
        if (downloadDialog != null) {
            try { downloadDialog.dismiss(); } catch (Exception ignored) { }
            downloadDialog = null;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private void buildFlowSection(LinearLayout container) {
        LinearLayout card = container;
        card.addView(sectionTitle("操作流程"));

        String[][] steps = {
                {"1", "开启无障碍服务", "系统设置里勾选「四格自动校准」"},
                {"2", "限定显示的应用", "选定后，面板只在那些应用里出现"},
                {"3", "打开题目页面", "面板出现在屏幕边缘，可拖动到不挡题的位置"},
                {"4", "点格子定位", "点 A/B/C/D，识别成功会画出识别范围"},
                {"5", "长按格子校准", "识别不准时用它：面板隐藏，你直接点真实选项位置"},
        };
        for (String[] step : steps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);

            TextView index = new TextView(this);
            index.setText(step[0]);
            index.setTextSize(12);
            index.setTextColor(Color.WHITE);
            index.setGravity(Gravity.CENTER);
            index.setTypeface(null, Typeface.BOLD);
            GradientDrawable badge = new GradientDrawable();
            badge.setColor(COLOR_ACCENT);
            badge.setShape(GradientDrawable.OVAL);
            index.setBackground(badge);
            row.addView(index, new LinearLayout.LayoutParams(dp(22), dp(22)));

            TextView body = new TextView(this);
            body.setText(step[1] + "\n" + step[2]);
            body.setTextSize(13);
            body.setTextColor(COLOR_TEXT);
            body.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(0, -2, 1f);
            bodyParams.leftMargin = dp(10);
            row.addView(body, bodyParams);

            card.addView(row, topMargin(10));
        }

    }

    private void buildScopeSection(LinearLayout container) {
        LinearLayout card = container;
        card.addView(sectionTitle("限定显示的应用"));

        TextView hint = hintText("默认在所有应用上都显示。选定应用后，面板只在那些应用位于前台时出现，"
                + "切到其它应用会被真正移除（不是变透明），既不遮挡也不会抢触摸。");
        card.addView(hint, topMargin(6));

        appScopeLine = new TextView(this);
        appScopeLine.setTextSize(13);
        appScopeLine.setTextColor(COLOR_TEXT);
        appScopeLine.setLineSpacing(dp(4), 1f);
        card.addView(appScopeLine, topMargin(8));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(flexButton("选择应用", new View.OnClickListener() {
            @Override public void onClick(View v) { showAppPicker(); }
        }), flexParams(0));
        buttons.addView(flexButton("取消限定", new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.clearAllowedPackages();
                applyToService();
                refreshStatus();
                Toast.makeText(MainActivity.this, "已取消限定", Toast.LENGTH_SHORT).show();
            }
        }), flexParams(dp(8)));
        card.addView(buttons, topMargin(12));


    }

    private void buildRecognitionSection(LinearLayout container) {
        LinearLayout card = container;
        card.addView(sectionTitle("识别选项"));

        Switch ocr = toggle("启用 OCR 回退（Android 11 及以上）", prefs.useOcr());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ocr.setEnabled(false);
            ocr.setChecked(false);
            ocr.setText("OCR 回退不可用（本机系统低于 Android 11）");
        }
        ocr.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setUseOcr(isChecked);
            }
        });
        card.addView(ocr, topMargin(8));

        Switch marks = toggle("点击后显示识别范围", prefs.showMarks());
        marks.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setShowMarks(isChecked);
            }
        });
        card.addView(marks, topMargin(2));

        Switch manual = toggle("只使用已校准的位置（跳过自动识别）", prefs.manualOnly());
        manual.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setManualOnly(isChecked);
            }
        });
        card.addView(manual, topMargin(2));

        Button clear = secondaryButton("清除全部校准记录");
        card.addView(clear, topMarginSized(12, 44));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.clearLearned();
                refreshStatus();
                Toast.makeText(MainActivity.this, "已清除校准记录", Toast.LENGTH_SHORT).show();
            }
        });


    }

    private void buildPanelSection(LinearLayout container) {
        LinearLayout card = container;
        card.addView(sectionTitle("悬浮面板"));

        sizeLabel = new TextView(this);
        sizeLabel.setTextSize(13);
        sizeLabel.setTextColor(COLOR_TEXT);
        card.addView(sizeLabel, topMargin(10));

        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(Prefs.MAX_CELL_SIZE_DP - Prefs.MIN_CELL_SIZE_DP);
        sizeBar.setProgress(prefs.cellSizeDp() - Prefs.MIN_CELL_SIZE_DP);
        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                updateSizeLabel(Prefs.MIN_CELL_SIZE_DP + progress);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                // 拖动过程中不重建，松手才写设置并让面板刷新，避免抖动。
                prefs.setCellSizeDp(Prefs.MIN_CELL_SIZE_DP + bar.getProgress());
                applyToService();
            }
        });
        card.addView(sizeBar, topMargin(2));

        TextView sizeHint = hintText("拖动后松手生效，悬浮面板会按新尺寸立即重建，位置保持不变。");
        card.addView(sizeHint, topMargin(2));

        Switch hideIme = toggle("输入法弹出时隐藏面板", prefs.hideOnIme());
        hideIme.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setHideOnIme(isChecked);
                applyToService();
            }
        });
        card.addView(hideIme, topMargin(8));

        TextView imeHint = hintText("打字时面板会挡住候选栏，开启后检测到输入法窗口就自动收起。");
        card.addView(imeHint, topMargin(0));

        Button reset = secondaryButton("恢复默认位置与大小");
        card.addView(reset, topMarginSized(14, 44));
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.resetPanelAppearance();
                applyToService();
                refreshStatus();
                toast("面板已恢复到右边缘默认位置，方块 " + Prefs.DEFAULT_CELL_SIZE_DP + "dp");
            }
        });

        TextView resetHint = hintText("面板被拖到屏幕外、或大小调乱了的时候用这个。"
                + "只重置位置和大小，已校准的选项坐标和开关都不受影响。");
        card.addView(resetHint, topMargin(6));


    }

    private void buildLogSection(LinearLayout container) {
        CollapsibleCard section = new CollapsibleCard("诊断日志", false);

        section.content.addView(hintText("每次点击悬浮格都会记录当次扫描的完整节点树、候选评分和判定过程。"
                + "识别不准时点「复制」把内容发出来即可定位问题。日志只在内存里，退出应用即清空。"));

        logView = new TextView(this);
        logView.setTextSize(9);
        logView.setTextColor(COLOR_LOG_TEXT);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable logBackground = new GradientDrawable();
        logBackground.setColor(COLOR_LOG_BG);
        logBackground.setCornerRadius(dp(10));
        logView.setBackground(logBackground);
        section.content.addView(logView, topMargin(10));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(flexButton("刷新", new View.OnClickListener() {
            @Override public void onClick(View v) {
                refreshStatus();
                Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
            }
        }), flexParams(0));
        buttons.addView(flexButton("复制", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = TapLog.dump();
                if (text.isEmpty()) { Toast.makeText(MainActivity.this, "日志为空", Toast.LENGTH_SHORT).show(); return; }
                TapLog.copyToClipboard(MainActivity.this, text, "QuadTap 日志");
                Toast.makeText(MainActivity.this, "已复制 " + TapLog.size() + " 行", Toast.LENGTH_SHORT).show();
            }
        }), flexParams(dp(8)));
        buttons.addView(flexButton("分享", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = TapLog.dump();
                if (text.isEmpty()) { Toast.makeText(MainActivity.this, "日志为空", Toast.LENGTH_SHORT).show(); return; }
                TapLog.share(MainActivity.this, text);
            }
        }), flexParams(dp(8)));
        buttons.addView(flexButton("清空", new View.OnClickListener() {
            @Override public void onClick(View v) {
                TapLog.clear();
                refreshLog();
                Toast.makeText(MainActivity.this, "已清空", Toast.LENGTH_SHORT).show();
            }
        }), flexParams(dp(8)));
        section.content.addView(buttons, topMargin(10));

        container.addView(section.root, topMargin(12));
    }

    private void buildAppearanceSection(LinearLayout container) {
        LinearLayout card = container;
        card.addView(sectionTitle("外观"));
        card.addView(hintText("选「跟随系统」时会随系统的深色模式自动切换。修改后界面会立即重建。"),
                topMargin(6));

        final int[] modes = {Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK};
        final String[] labels = {"跟随系统", "白天", "黑夜"};

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(0, dp(4), 0, 0);

        int checkedId = View.NO_ID;
        for (int i = 0; i < modes.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(1000 + i);
            button.setText(labels[i]);
            button.setTextSize(14);
            button.setTextColor(COLOR_TEXT);
            button.setPadding(0, dp(8), 0, dp(8));
            group.addView(button);
            if (prefs.themeMode() == modes[i]) checkedId = 1000 + i;
        }
        // 先 check 再挂监听，避免初始化时就触发一次重建
        if (checkedId != View.NO_ID) group.check(checkedId);

        group.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                int index = id - 1000;
                if (index < 0 || index >= modes.length) return;
                if (prefs.themeMode() == modes[index]) return;
                prefs.setThemeMode(modes[index]);
                recreate();
            }
        });
        card.addView(group);


    }

    private void buildAboutSection(LinearLayout container) {
        LinearLayout tech = card();
        tech.addView(cardTitle("使用的技术"));
        tech.addView(hintText(
                "· Java / Android SDK，minSdk 26，compileSdk / targetSdk 35\n"
                + "· AccessibilityService：读取节点树定位选项\n"
                + "· TYPE_ACCESSIBILITY_OVERLAY 悬浮窗（因此不需要 SYSTEM_ALERT_WINDOW 权限）\n"
                + "· dispatchGesture 派发手势点击\n"
                + "· takeScreenshot（API 30+）截屏 + Google ML Kit 中文文字识别作为回退\n"
                + "· androidx.core 的 FileProvider + 系统安装器，实现应用内更新\n"
                + "· SharedPreferences 保存设置与校准坐标\n"
                + "· 界面全部用原生 View 以代码构建，没有引入第三方 UI 框架\n"
                + "· 未申请 QUERY_ALL_PACKAGES，只用 <queries> 列出可启动的应用"), topMargin(8));
        container.addView(tech, topMargin(12));

        CollapsibleCard log = new CollapsibleCard("完整更新日志", false);
        log.content.addView(hintText(
                "V1.10\n"
                + "· 界面打磨：卡片与按钮圆角统一、去掉多余阴影、增加按下反馈\n"
                + "· 状态卡片新增状态色圆点；选项卡下加分隔线\n"
                + "· 悬浮面板更紧凑、描边更干净，格子加按下缩放与变色\n"
                + "· 仅外观改动，判定逻辑与操作方式未变\n\n"
                + "V1.9\n"
                + "· 修复顶部标题被状态栏挡住（Android 15 起强制边到边，需自己让出系统栏高度）\n"
                + "· 新增主题切换：跟随系统 / 白天 / 黑夜\n\n"
                + "V1.8\n"
                + "· 修复判断题完全失效：选项行是满屏宽，被 90% 宽度的过滤条件挡掉了\n"
                + "· 修复 OCR 期间面板位置下方点不动（改为真正移除窗口，而不是设为不可见）\n"
                + "· 首页拆成 状态 / 识别 / 日志 / 关于 四个顶部选项卡\n"
                + "· 悬浮面板新增「恢复默认位置与大小」\n\n"
                + "V1.7\n"
                + "· 新增应用内更新：检查新版本、下载、交给系统安装器\n"
                + "· 首页显示当前版本号，可开关启动时自动检查\n\n"
                + "V1.6\n"
                + "· 接入正式签名，发布的 APK 带签名校验\n\n"
                + "V1.5\n"
                + "· 卡片式首页、日志可折叠、方块大小可调、输入法弹出时隐藏面板\n\n"
                + "V1.4\n"
                + "· 新增「限定显示的应用」白名单\n\n"
                + "V1.3\n"
                + "· 位置兜底：选项字母被画成图片时，按几何特征映射 A/B/C/D\n\n"
                + "V1.2\n"
                + "· 新增诊断日志；修复 4 选项题目整体失效\n\n"
                + "V1.1\n"
                + "· 修复截屏能力位、硬件位图、选项匹配三处致命缺陷\n"
                + "· 新增可拖动吸附面板、长按手动校准、定位范围可视化"), topMargin(8));
        container.addView(log.root, topMargin(12));
    }

    // ------------------------------------------------------------------ 状态刷新

    private void refreshStatus() {
        refreshLog();
        // 运行控制卡：状态、开关、答题数、时长全部来自真实服务状态，不看开关外观
        refreshRunCard();

        if (appScopeLine != null) {
            Set<String> allowed = prefs.allowedPackages();
            if (allowed.isEmpty()) {
                appScopeLine.setText("当前：未限定，所有应用都会显示面板");
            } else {
                appScopeLine.setText("当前：仅在 " + allowed.size() + " 个应用中显示\n"
                        + describeAllowed(allowed));
            }
        }
        if (sizeLabel != null) updateSizeLabel(prefs.cellSizeDp());

        // 恢复原有的校准 / 识别数量展示（复用现有 getter，不新增数据源）
        if (recognizeInfoText != null) {
            int confirmed = QuadTapAccessibilityService.allowedLabelCount();
            QuadTapAccessibilityService service = QuadTapAccessibilityService.get();
            StringBuilder info = new StringBuilder();
            info.append("已校准选项 ").append(prefs.learnedCount()).append("/4");
            if (service != null) {
                info.append("　·　上次识别 ").append(service.lastFoundCount()).append(" 项");
                info.append("　·　当前已确证 ")
                        .append(confirmed > 0 ? confirmed + " 项(A–" + "ABCD".charAt(confirmed - 1) + ")" : "未知");
            } else {
                info.append("　·　服务未连接");
            }
            recognizeInfoText.setText(info.toString());
        }

        if (versionLine != null && !checkingUpdate) versionLine.setText(versionSummary());
    }

    /**
     * 刷新运行控制卡的四类信息：辅助答题数、运行时长、真实状态、开关位置。
     * 状态严格区分：运行中 / 已停止 / 需要无障碍权限 / 权限已开但服务未连接。
     */
    private void refreshRunCard() {
        if (runStatusText == null) return;

        boolean accessEnabled = isAccessibilityEnabled();
        boolean connected = QuadTapAccessibilityService.get() != null;
        boolean running = QuadTapAccessibilityService.isAssistRunning();

        String status;
        String stateLabel;
        String hint;
        int dotColor;

        if (!accessEnabled) {
            status = "需要开启无障碍权限";
            stateLabel = "权限未开启";
            dotColor = 0xFFFFC24B;
            hint = "点开关会跳到系统设置，请开启「" + getString(R.string.app_name)
                    + "」的无障碍服务后再回来。";
        } else if (!connected) {
            status = "已停止";
            stateLabel = "服务未连接";
            dotColor = 0xFFC2D8FF;
            hint = "无障碍已勾选但服务还没连上。稍等片刻，或在系统设置里重新开关一次。";
        } else if (running) {
            status = "运行中";
            stateLabel = "运行中";
            dotColor = 0xFF5BE39B;
            hint = "面板已就绪：点击格子定位，长按格子手动校准。";
        } else {
            status = "已停止";
            stateLabel = "已停止";
            dotColor = 0xFFC2D8FF;
            hint = "辅助答题已停止，不会再执行任何点击。打开开关即可恢复。";
        }

        runStatusText.setText(status);
        setRunDot(dotColor);
        runStateText.setText(stateLabel);
        runCountText.setText(prefs.answerCount() + " 道");
        runHintText.setText(hint);
        setRunSwitchSilently(running);
        updateRunTime();
    }

    private void updateSizeLabel(int value) {
        if (sizeLabel != null) sizeLabel.setText("方块大小：" + value + " dp");
    }

    private void refreshLog() {
        if (logView == null) return;
        String text = TapLog.tail(LOG_TAIL_LINES);
        if (text.isEmpty()) {
            text = "(暂无日志。开启无障碍服务后点击悬浮格，这里就会出现当次扫描记录。)";
        }
        logView.setText(text);
    }

    /** 改完设置通知服务重建面板（同进程静态调用，不需要 IPC）。 */
    private void applyToService() {
        QuadTapAccessibilityService.notifySettingsChanged();
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
        }
    }

    /** 从系统设置里确认本服务是否被勾选，即使此刻还没连接也有效。 */
    private boolean isAccessibilityEnabled() {
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null) return false;
            String full = getPackageName() + "/" + QuadTapAccessibilityService.class.getName();
            String shortForm = getPackageName() + "/.QuadTapAccessibilityService";
            for (String entry : enabledServices.split(":")) {
                if (entry.equalsIgnoreCase(full) || entry.equalsIgnoreCase(shortForm)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private String describeAllowed(Set<String> packages) {
        PackageManager manager = getPackageManager();
        StringBuilder builder = new StringBuilder();
        for (String pkg : packages) {
            if (builder.length() > 0) builder.append('、');
            String label = pkg;
            try {
                label = manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) { }
            builder.append(label);
        }
        return builder.toString();
    }

    /** 列出所有有启动图标的已安装应用，多选保存为白名单。 */
    private void showAppPicker() {
        Intent launcher = new Intent(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved;
        try {
            resolved = getPackageManager().queryIntentActivities(launcher, 0);
        } catch (Exception e) {
            resolved = null;
        }
        if (resolved == null || resolved.isEmpty()) {
            Toast.makeText(this, "没有读到可选择的引用", Toast.LENGTH_SHORT).show();
            return;
        }

        // 同一个包可能有多个入口，按包名去重。
        final LinkedHashMap<String, String> labelOf = new LinkedHashMap<String, String>();
        PackageManager manager = getPackageManager();
        for (ResolveInfo info : resolved) {
            if (info == null || info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            if (labelOf.containsKey(pkg)) continue;
            CharSequence label = info.loadLabel(manager);
            labelOf.put(pkg, label == null ? pkg : label.toString());
        }
        if (labelOf.isEmpty()) {
            Toast.makeText(this, "没有读到可选择的引用", Toast.LENGTH_SHORT).show();
            return;
        }

        final List<String> packages = new ArrayList<String>(labelOf.keySet());
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(packages, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                return collator.compare(labelOf.get(a), labelOf.get(b));
            }
        });

        Set<String> allowed = prefs.allowedPackages();
        final CharSequence[] items = new CharSequence[packages.size()];
        final boolean[] checked = new boolean[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            items[i] = labelOf.get(packages.get(i));
            checked[i] = allowed.contains(packages.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle("选择要显示面板的应用")
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        Set<String> result = new LinkedHashSet<String>();
                        for (int i = 0; i < packages.size(); i++) {
                            if (checked[i]) result.add(packages.get(i));
                        }
                        prefs.setAllowedPackages(result);
                        applyToService();
                        refreshStatus();
                        Toast.makeText(MainActivity.this,
                                result.isEmpty() ? "未选择应用，恢复为所有应用都显示"
                                        : "已限定 " + result.size() + " 个应用",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ 视图工具

    /** 可折叠分区：标题行始终可见，内容默认收起。 */
    private final class CollapsibleCard {
        final LinearLayout root;
        final LinearLayout content;
        private final TextView arrow;
        private boolean open;

        CollapsibleCard(String title, boolean startOpen) {
            root = card();

            LinearLayout header = new LinearLayout(MainActivity.this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            header.addView(cardTitle(title), new LinearLayout.LayoutParams(0, -2, 1f));

            arrow = new TextView(MainActivity.this);
            arrow.setTextSize(12);
            arrow.setTextColor(COLOR_HINT);
            header.addView(arrow);

            header.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setOpen(!open); }
            });
            root.addView(header, new LinearLayout.LayoutParams(-1, -2));

            content = new LinearLayout(MainActivity.this);
            content.setOrientation(LinearLayout.VERTICAL);
            root.addView(content, new LinearLayout.LayoutParams(-1, -2));

            setOpen(startOpen);
        }

        void setOpen(boolean value) {
            open = value;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setText(open ? "收起 ▲" : "展开 ▼");
        }
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_CARD);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), COLOR_STROKE);
        layout.setBackground(background);
        // 不加阴影：1dp 描边 + 干净的卡片底色已经足够区分层次，
        // 也和 Material 3 的 outlined card 观感一致。
        return layout;
    }

    private TextView cardTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(COLOR_TITLE);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }

    /** 卡片内部的小节标题，视觉层级比卡片标题低一级。 */
    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(COLOR_TITLE);
        view.setTypeface(null, Typeface.BOLD);
        view.setLetterSpacing(0.02f);
        return view;
    }

    private TextView hintText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(COLOR_HINT);
        view.setLineSpacing(dp(4), 1f);
        return view;
    }

    private Switch toggle(String text, boolean checked) {
        Switch view = new Switch(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(COLOR_TEXT);
        view.setChecked(checked);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    /** 按下时变色的 ColorStateList。 */
    private ColorStateList stateColor(int normal, int pressed) {
        return new ColorStateList(
                new int[][]{ new int[]{android.R.attr.state_pressed}, new int[]{} },
                new int[]{ pressed, normal });
    }

    /** 统一的按钮样式：12dp 圆角、按下变色、去掉默认的立体抬起动画。 */
    private void styleButton(Button button, int background, int pressedBackground, int textColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(12));
        button.setBackground(shape);
        button.setBackgroundTintList(stateColor(background, pressedBackground));
        button.setTextColor(textColor);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        // 主题默认 minHeight 约 48dp，会把 44dp 的按钮顶高，这里放开
        button.setMinimumHeight(0);
        button.setMinHeight(0);
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        styleButton(button, COLOR_ACCENT, COLOR_ACCENT_PRESSED, Color.WHITE);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        styleButton(button, COLOR_BTN_SECONDARY, COLOR_BTN_PRESSED, COLOR_TEXT);
        return button;
    }

    private Button flexButton(String text, View.OnClickListener listener) {
        Button button = secondaryButton(text);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams flexParams(int startMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.leftMargin = startMarginDp;
        return params;
    }

    private LinearLayout.LayoutParams topMargin(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private LinearLayout.LayoutParams topMarginSized(int topMarginDp, int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(heightDp));
        params.topMargin = dp(topMarginDp);
        return params;
    }
}
