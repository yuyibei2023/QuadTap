package com.study.quadtap;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private int dp(float value) { return (int)(value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFFF7F8FA);
        getWindow().setNavigationBarColor(0xFFF7F8FA);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(28), dp(22), dp(24));
        page.setBackgroundColor(0xFFF7F8FA);

        TextView title = new TextView(this);
        title.setText("四格自动校准"); title.setTextSize(27); title.setTextColor(0xFF172033); title.setTypeface(null, 1);
        page.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("把屏幕选项映射到固定的四格悬浮面板。第一版使用无障碍控件定位，并在必要时尝试中文 OCR。\n\n所有识别都在本机完成；不会自动选择答案，只有你主动点击悬浮按钮时才会尝试点击目标选项。 ");
        subtitle.setTextSize(15); subtitle.setTextColor(0xFF657086); subtitle.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2); sp.topMargin = dp(12); page.addView(subtitle, sp);

        TextView steps = new TextView(this);
        steps.setText("使用步骤\n1. 开启无障碍服务。\n2. 返回本应用，再打开驾考题目页面。\n3. 屏幕边缘会出现 A/B/C/D 四格面板。\n4. 点击 A/B/C/D，工具会先匹配页面控件；未找到时尝试 OCR 识别。\n5. 题目布局变化后会重新扫描。\n\n注意：不同驾考应用的控件结构可能不同，第一版无法保证所有界面都能准确识别。请先在非正式页面测试。 ");
        steps.setTextSize(15); steps.setTextColor(0xFF30394A); steps.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2); tp.topMargin = dp(22); page.addView(steps, tp);

        Button enable = button("开启无障碍服务");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(52)); bp.topMargin = dp(28); page.addView(enable, bp);
        enable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button refresh = button("查看权限 / 返回使用");
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(52)); rp.topMargin = dp(12); page.addView(refresh, rp);
        refresh.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        TextView foot = new TextView(this);
        foot.setText("隐私说明：屏幕内容仅用于本机识别选项位置。请仅在你有权操作的应用中使用，并遵守目标应用的规则。");
        foot.setTextSize(12); foot.setTextColor(0xFF8992A3); foot.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2); fp.topMargin = dp(22); page.addView(foot, fp);

        ScrollView scroll = new ScrollView(this); scroll.addView(page); setContentView(scroll);
    }

    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(0xFFFFFFFF); b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF3478F6)); return b;
    }
}
