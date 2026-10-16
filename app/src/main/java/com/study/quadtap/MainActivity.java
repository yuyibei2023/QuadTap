package com.study.quadtap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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

    private static final int COLOR_BG = 0xFFF2F4F8;
    private static final int COLOR_CARD = 0xFFFFFFFF;
    private static final int COLOR_STROKE = 0xFFE4E9F2;
    private static final int COLOR_TITLE = 0xFF16203A;
    private static final int COLOR_TEXT = 0xFF33405A;
    private static final int COLOR_HINT = 0xFF7C879C;
    private static final int COLOR_ACCENT = 0xFF3478F6;
    private static final int COLOR_OK = 0xFF1F8A4C;
    private static final int COLOR_WARN = 0xFFB07A16;
    private static final int COLOR_BAD = 0xFFC0392B;

    private Prefs prefs;
    private TextView statusLine;
    private TextView detailLine;
    private TextView appScopeLine;
    private TextView sizeLabel;
    private TextView logView;
    private TextView versionLine;
    /** 自动检查更新每次启动只做一次，避免每次回到前台都请求网络。 */
    private boolean autoCheckDone = false;
    /** 正在检查时不要被 refreshStatus 覆盖掉提示文字。 */
    private boolean checkingUpdate = false;
    private AlertDialog downloadDialog;

    // 顶部选项卡：四个页面一次性建好，切换只改可见性
    private LinearLayout tabBar;
    private FrameLayout pageContainer;
    private final List<View> pages = new ArrayList<View>();
    private final List<TextView> tabLabels = new ArrayList<TextView>();
    private final List<View> tabIndicators = new ArrayList<View>();
    private int currentTab = 0;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        TextView appTitle = new TextView(this);
        appTitle.setText(R.string.app_name);
        appTitle.setTextSize(19);
        appTitle.setTextColor(COLOR_TITLE);
        appTitle.setTypeface(null, Typeface.BOLD);
        appTitle.setPadding(dp(18), dp(16), dp(18), dp(10));
        root.addView(appTitle, new LinearLayout.LayoutParams(-1, -2));

        root.addView(buildTabBar(), new LinearLayout.LayoutParams(-1, -2));

        pageContainer = new FrameLayout(this);
        root.addView(pageContainer, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 状态
        LinearLayout tabStatus = newPage();
        buildHeader(tabStatus);
        buildStatusCard(tabStatus);
        buildUpdateCard(tabStatus);
        buildFlowCard(tabStatus);
        addPage("状态", tabStatus);

        // 识别
        LinearLayout tabRecognition = newPage();
        buildScopeCard(tabRecognition);
        buildRecognitionCard(tabRecognition);
        buildPanelCard(tabRecognition);
        addPage("识别", tabRecognition);

        // 日志
        LinearLayout tabLog = newPage();
        buildLogCard(tabLog);
        addPage("日志", tabLog);

        // 关于
        LinearLayout tabAbout = newPage();
        buildAboutCard(tabAbout);
        buildFooter(tabAbout);
        addPage("关于", tabAbout);

        setContentView(root);
        selectTab(0);
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

    // ------------------------------------------------------------------ 顶部选项卡

    private LinearLayout buildTabBar() {
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(COLOR_CARD);
        tabBar.setPadding(dp(6), dp(4), dp(6), 0);
        GradientDrawable bottom = new GradientDrawable();
        bottom.setColor(COLOR_CARD);
        tabBar.setBackground(bottom);
        return tabBar;
    }

    /** 新增一个选项卡 + 对应页面。页面先全部建好，切换只改可见性。 */
    private void addPage(final String title, LinearLayout content) {
        final int index = pages.size();

        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(10), 0, dp(8));
        tab.addView(label, new LinearLayout.LayoutParams(-1, -2));

        View indicator = new View(this);
        LinearLayout.LayoutParams indicatorParams = new LinearLayout.LayoutParams(dp(28), dp(3));
        indicatorParams.gravity = Gravity.CENTER_HORIZONTAL;
        tab.addView(indicator, indicatorParams);

        tab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectTab(index); }
        });

        tabBar.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COLOR_BG);
        scroll.addView(content);
        scroll.setVisibility(View.GONE);
        pageContainer.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        pages.add(scroll);
        tabLabels.add(label);
        tabIndicators.add(indicator);
    }

    private void selectTab(int index) {
        if (index < 0 || index >= pages.size()) return;
        currentTab = index;
        for (int i = 0; i < pages.size(); i++) {
            boolean active = i == index;
            pages.get(i).setVisibility(active ? View.VISIBLE : View.GONE);
            tabLabels.get(i).setTextColor(active ? COLOR_ACCENT : COLOR_HINT);
            tabLabels.get(i).setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            GradientDrawable bar = new GradientDrawable();
            bar.setColor(active ? COLOR_ACCENT : 0x00000000);
            bar.setCornerRadius(dp(2));
            tabIndicators.get(i).setBackground(bar);
        }
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
        if (!autoCheckDone && prefs.autoCheckUpdate()) {
            autoCheckDone = true;
            checkForUpdate(false);
        }
    }

    // ------------------------------------------------------------------ 各区块

    private void buildHeader(LinearLayout page) {
        // 应用名已经放在顶部常驻标题栏里，这里只留一句说明
        TextView sub = new TextView(this);
        sub.setText("把屏幕上的选项位置映射到固定的 A/B/C/D 四格悬浮面板。"
                + "不判断答案，只在你主动点格子时点击对应选项。");
        sub.setTextSize(13);
        sub.setTextColor(COLOR_HINT);
        sub.setLineSpacing(dp(4), 1f);
        page.addView(sub);
    }

    private void buildStatusCard(LinearLayout page) {
        LinearLayout card = card();
        statusLine = new TextView(this);
        statusLine.setTextSize(17);
        statusLine.setTextColor(COLOR_TITLE);
        statusLine.setTypeface(null, Typeface.BOLD);
        card.addView(statusLine);

        detailLine = new TextView(this);
        detailLine.setTextSize(13);
        detailLine.setTextColor(COLOR_HINT);
        detailLine.setLineSpacing(dp(4), 1f);
        card.addView(detailLine, topMargin(6));

        Button enable = primaryButton("开启无障碍服务");
        card.addView(enable, topMarginSized(14, 46));
        enable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAccessibilitySettings(); }
        });

        page.addView(card, topMargin(16));
    }

    // ------------------------------------------------------------------ 版本与更新

    private void buildUpdateCard(LinearLayout page) {
        LinearLayout card = card();
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

        page.addView(card, topMargin(12));
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

    private void buildFlowCard(LinearLayout page) {
        LinearLayout card = card();
        card.addView(cardTitle("操作流程"));

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
        page.addView(card, topMargin(12));
    }

    private void buildScopeCard(LinearLayout page) {
        LinearLayout card = card();
        card.addView(cardTitle("限定显示的应用"));

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

        page.addView(card, topMargin(12));
    }

    private void buildRecognitionCard(LinearLayout page) {
        LinearLayout card = card();
        card.addView(cardTitle("识别选项"));

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

        page.addView(card, topMargin(12));
    }

    private void buildPanelCard(LinearLayout page) {
        LinearLayout card = card();
        card.addView(cardTitle("悬浮面板"));

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

        page.addView(card, topMargin(12));
    }

    private void buildLogCard(LinearLayout page) {
        CollapsibleCard section = new CollapsibleCard("诊断日志", false);

        section.content.addView(hintText("每次点击悬浮格都会记录当次扫描的完整节点树、候选评分和判定过程。"
                + "识别不准时点「复制」把内容发出来即可定位问题。日志只在内存里，退出应用即清空。"));

        logView = new TextView(this);
        logView.setTextSize(9);
        logView.setTextColor(0xFFD5DCE8);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable logBackground = new GradientDrawable();
        logBackground.setColor(0xFF1B2130);
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

        page.addView(section.root, topMargin(12));
    }

    private void buildAboutCard(LinearLayout page) {
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
        page.addView(tech, topMargin(12));

        CollapsibleCard log = new CollapsibleCard("完整更新日志", false);
        log.content.addView(hintText(
                "V1.8\n"
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
        page.addView(log.root, topMargin(12));
    }

    // ------------------------------------------------------------------ 状态刷新

    private void refreshStatus() {
        refreshLog();
        if (statusLine == null) return;

        boolean enabled = isAccessibilityEnabled();
        QuadTapAccessibilityService service = QuadTapAccessibilityService.get();
        boolean connected = service != null;

        if (connected) {
            statusLine.setText("服务运行中");
            statusLine.setTextColor(COLOR_OK);
        } else if (enabled) {
            statusLine.setText("服务已开启，但尚未连接");
            statusLine.setTextColor(COLOR_WARN);
        } else {
            statusLine.setText("服务未开启");
            statusLine.setTextColor(COLOR_BAD);
        }

        StringBuilder detail = new StringBuilder();
        detail.append("已校准选项：").append(prefs.learnedCount()).append("/4");
        detail.append("　·　方块大小：").append(prefs.cellSizeDp()).append("dp");
        if (connected) {
            detail.append("\n上次扫描识别到：").append(service.lastFoundCount()).append(" 个选项");
        }
        if (!enabled) {
            detail.append("\n请先开启无障碍服务，面板才会出现。");
        }
        detailLine.setText(detail.toString());

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
        if (versionLine != null && !checkingUpdate) versionLine.setText(versionSummary());
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
        layout.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_CARD);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), COLOR_STROKE);
        layout.setBackground(background);
        layout.setElevation(dp(1));
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

    private TextView subTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(COLOR_TITLE);
        view.setTypeface(null, Typeface.BOLD);
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

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(COLOR_TEXT);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE7ECF5));
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
