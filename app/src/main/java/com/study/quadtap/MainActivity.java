package com.study.quadtap;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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

    /** 界面上最多显示多少行日志。完整日志仍然可以复制/分享。 */
    private static final int LOG_TAIL_LINES = 150;

    private Prefs prefs;
    private TextView statusLine;
    private TextView detailLine;
    private TextView logView;
    private TextView appScopeLine;

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(0xFFF7F8FA);
        getWindow().setNavigationBarColor(0xFFF7F8FA);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(28), dp(22), dp(28));
        page.setBackgroundColor(0xFFF7F8FA);

        TextView title = new TextView(this);
        title.setText("四格自动校准");
        title.setTextSize(27);
        title.setTextColor(0xFF172033);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        page.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("把屏幕选项映射到固定的四格悬浮面板。识别优先使用无障碍控件定位，"
                + "失败时退回本机中文 OCR，也可以长按格子手动校准。\n\n"
                + "所有识别都在本机完成；不会自动判断答案，只有你主动点击悬浮按钮时才会尝试点击目标选项。");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFF657086);
        subtitle.setLineSpacing(dp(4), 1f);
        page.addView(subtitle, margins(-1, -2, 0, 12));

        // ---- 状态卡片 ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cardBackground = new GradientDrawable();
        cardBackground.setColor(Color.WHITE);
        cardBackground.setCornerRadius(dp(14));
        cardBackground.setStroke(dp(1), 0xFFE3E8F0);
        card.setBackground(cardBackground);

        statusLine = new TextView(this);
        statusLine.setTextSize(16);
        statusLine.setTextColor(0xFF172033);
        statusLine.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(statusLine);

        detailLine = new TextView(this);
        detailLine.setTextSize(13);
        detailLine.setTextColor(0xFF657086);
        detailLine.setLineSpacing(dp(3), 1f);
        card.addView(detailLine, margins(-1, -2, 0, 6));

        page.addView(card, margins(-1, -2, 18, 0));

        // ---- 使用步骤 ----
        TextView steps = new TextView(this);
        steps.setText("使用步骤\n"
                + "1. 开启无障碍服务，并按系统提示允许相关权限。\n"
                + "2. 打开题目页面，屏幕边缘会出现 A/B/C/D 四格面板。\n"
                + "3. 拖动面板可以移到不挡题目的位置，松手后自动吸附到边缘。\n"
                + "4. 点击 A/B/C/D 尝试点击对应选项；识别成功时会短暂画出识别范围。\n"
                + "5. 长按某一格进入校准：面板会暂时隐藏，你直接点屏幕上该选项的真实位置即可。\n"
                + "6. 题目布局变化后会自动重新扫描。");
        steps.setTextSize(15);
        steps.setTextColor(0xFF30394A);
        steps.setLineSpacing(dp(5), 1f);
        page.addView(steps, margins(-1, -2, 22, 0));

        Button enable = primaryButton("开启无障碍服务");
        page.addView(enable, margins(-1, dp(52), 28, 0));
        enable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // ---- 开关 ----
        page.addView(sectionTitle("识别选项"), margins(-1, -2, 26, 0));

        Switch ocr = toggle("启用 OCR 回退（Android 11 及以上）", prefs.useOcr());
        // 截屏 API 只有 Android 11（API 30）起才提供。
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
        page.addView(ocr, margins(-1, -2, 10, 0));

        Switch marks = toggle("点击后显示识别范围", prefs.showMarks());
        marks.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setShowMarks(isChecked);
            }
        });
        page.addView(marks, margins(-1, -2, 6, 0));

        Switch manual = toggle("只使用已校准的位置（跳过自动识别）", prefs.manualOnly());
        manual.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.setManualOnly(isChecked);
            }
        });
        page.addView(manual, margins(-1, -2, 6, 0));

        Button clear = secondaryButton("清除全部校准记录");
        page.addView(clear, margins(-1, dp(48), 18, 0));
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.clearLearned();
                refreshStatus();
                Toast.makeText(MainActivity.this, "已清除校准记录", Toast.LENGTH_SHORT).show();
            }
        });

        // ---- 限定显示的应用 ----
        page.addView(sectionTitle("限定显示的应用"), margins(-1, -2, 26, 0));

        TextView scopeHint = new TextView(this);
        scopeHint.setText("默认面板在所有应用上都显示。选定应用后，面板只在那些应用位于前台时出现，"
                + "切到其它应用会被真正移除（不是变透明），既不遮挡也不会抢触摸。");
        scopeHint.setTextSize(13);
        scopeHint.setTextColor(0xFF657086);
        scopeHint.setLineSpacing(dp(3), 1f);
        page.addView(scopeHint, margins(-1, -2, 8, 0));

        appScopeLine = new TextView(this);
        appScopeLine.setTextSize(13);
        appScopeLine.setTextColor(0xFF172033);
        appScopeLine.setLineSpacing(dp(3), 1f);
        page.addView(appScopeLine, margins(-1, -2, 6, 0));

        Button pickApps = secondaryButton("选择应用");
        page.addView(pickApps, margins(-1, dp(48), 8, 0));
        pickApps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAppPicker(); }
        });

        Button clearApps = secondaryButton("取消限定（所有应用都显示）");
        page.addView(clearApps, margins(-1, dp(48), 8, 0));
        clearApps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.clearAllowedPackages();
                refreshStatus();
                Toast.makeText(MainActivity.this, "已取消限定", Toast.LENGTH_SHORT).show();
            }
        });

        TextView foot = new TextView(this);
        foot.setText("隐私说明：屏幕内容仅用于本机识别选项位置，不会上传。"
                + "请仅在你有权操作的应用中使用，并遵守目标应用的规则。");
        foot.setTextSize(12);
        foot.setTextColor(0xFF8992A3);
        foot.setLineSpacing(dp(3), 1f);
        page.addView(foot, margins(-1, -2, 24, 0));

        // ---- 诊断日志 ----
        page.addView(sectionTitle("诊断日志"), margins(-1, -2, 26, 0));

        TextView logHint = new TextView(this);
        logHint.setText("自动识别不准时，每次点击悬浮格都会记录当次扫描的完整节点树和判定过程。"
                + "下面显示最近 " + LOG_TAIL_LINES + " 行，复制或分享发给我就能定位问题。"
                + "日志只存在本机内存里，退出应用即清空。");
        logHint.setTextSize(13);
        logHint.setTextColor(0xFF657086);
        logHint.setLineSpacing(dp(3), 1f);
        page.addView(logHint, margins(-1, -2, 8, 0));

        logView = new TextView(this);
        logView.setTextSize(9);
        logView.setTextColor(0xFFD5DCE8);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable logBackground = new GradientDrawable();
        logBackground.setColor(0xFF1B2130);
        logBackground.setCornerRadius(dp(10));
        logView.setBackground(logBackground);
        page.addView(logView, margins(-1, -2, 8, 0));

        LinearLayout logButtons = new LinearLayout(this);
        logButtons.setOrientation(LinearLayout.HORIZONTAL);
        logButtons.addView(logButton("刷新", new View.OnClickListener() {
            @Override public void onClick(View v) {
                refreshStatus();
                Toast.makeText(MainActivity.this, "已刷新", Toast.LENGTH_SHORT).show();
            }
        }), logButtonParams(0));
        logButtons.addView(logButton("复制", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = TapLog.dump();
                if (text.isEmpty()) { Toast.makeText(MainActivity.this, "日志为空", Toast.LENGTH_SHORT).show(); return; }
                TapLog.copyToClipboard(MainActivity.this, text, "QuadTap 日志");
                Toast.makeText(MainActivity.this, "已复制 " + TapLog.size() + " 行到剪贴板", Toast.LENGTH_SHORT).show();
            }
        }), logButtonParams(dp(8)));
        logButtons.addView(logButton("分享", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String text = TapLog.dump();
                if (text.isEmpty()) { Toast.makeText(MainActivity.this, "日志为空", Toast.LENGTH_SHORT).show(); return; }
                TapLog.share(MainActivity.this, text);
            }
        }), logButtonParams(dp(8)));
        logButtons.addView(logButton("清空", new View.OnClickListener() {
            @Override public void onClick(View v) {
                TapLog.clear();
                refreshLog();
                Toast.makeText(MainActivity.this, "已清空", Toast.LENGTH_SHORT).show();
            }
        }), logButtonParams(dp(8)));
        page.addView(logButtons, margins(-1, -2, 10, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        refreshLog();
        if (statusLine == null) return;

        boolean enabled = isAccessibilityEnabled();
        QuadTapAccessibilityService service = QuadTapAccessibilityService.get();
        boolean connected = service != null;

        if (connected) {
            statusLine.setText("服务运行中");
            statusLine.setTextColor(0xFF1F8A4C);
        } else if (enabled) {
            statusLine.setText("服务已开启，但尚未连接");
            statusLine.setTextColor(0xFFB07A16);
        } else {
            statusLine.setText("服务未开启");
            statusLine.setTextColor(0xFFC0392B);
        }

        StringBuilder detail = new StringBuilder();
        detail.append("已校准选项：").append(prefs.learnedCount()).append("/4");
        if (connected) {
            int found = service.lastFoundCount();
            detail.append("\n上次扫描识别到：").append(found).append(" 个选项");
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

    private LinearLayout.LayoutParams margins(int width, int height, int topMarginDp, int bottomMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = dp(topMarginDp);
        params.bottomMargin = dp(bottomMarginDp);
        return params;
    }

    private TextView sectionTitle(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(13);
        view.setTextColor(0xFF8992A3);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setLetterSpacing(0.06f);
        return view;
    }

    private Switch toggle(String text, boolean checked) {
        Switch view = new Switch(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTextColor(0xFF30394A);
        view.setChecked(checked);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF3478F6));
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(0xFF30394A);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE3E8F0));
        return button;
    }

    private void refreshLog() {
        if (logView == null) return;
        String text = TapLog.tail(LOG_TAIL_LINES);
        if (text.isEmpty()) {
            text = "(暂无日志。开启无障碍服务后点击悬浮格，这里就会出现当次扫描记录。)";
        }
        logView.setText(text);
    }

    private LinearLayout.LayoutParams logButtonParams(int startMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1f);
        params.leftMargin = startMarginDp;
        return params;
    }

    private Button logButton(String text, View.OnClickListener listener) {
        Button button = secondaryButton(text);
        button.setTextSize(14);
        button.setOnClickListener(listener);
        return button;
    }
}
