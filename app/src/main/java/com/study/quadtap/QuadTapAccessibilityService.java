package com.study.quadtap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.PixelFormat;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class QuadTapAccessibilityService extends AccessibilityService {
    private WindowManager windowManager;
    private GridLayout panel;
    private final Map<String, Rect> targetBounds = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Executor worker = Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer;
    private boolean overlayVisible = true;
    private long lastScanAt = 0;

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager)getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        createOverlay();
        scanAccessibilityTree();
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }

    private void createOverlay() {
        if (panel != null || windowManager == null) return;
        panel = new GridLayout(this); panel.setColumnCount(2); panel.setRowCount(2); panel.setPadding(dp(5),dp(5),dp(5),dp(5));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xEFFFFFFF); bg.setCornerRadius(dp(16)); bg.setStroke(dp(1), 0xFFCFD8E8); panel.setBackground(bg); panel.setElevation(dp(8));
        String[] labels = {"A", "B", "C", "D"};
        int[] colors = {0xFF3478F6,0xFF3478F6,0xFF5A6B85,0xFF5A6B85};
        for (int i=0;i<4;i++) {
            final String label = labels[i];
            TextView cell = new TextView(this); cell.setText(label); cell.setTextSize(17); cell.setTextColor(Color.WHITE); cell.setGravity(Gravity.CENTER); cell.setTypeface(null,1);
            GradientDrawable cellBg = new GradientDrawable(); cellBg.setColor(colors[i]); cellBg.setCornerRadius(dp(11)); cell.setBackground(cellBg);
            GridLayout.LayoutParams cp = new GridLayout.LayoutParams(); cp.width=dp(48); cp.height=dp(46); cp.setMargins(dp(3),dp(3),dp(3),dp(3)); panel.addView(cell,cp);
            cell.setOnClickListener(v -> performMappedTap(label));
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL; lp.x = dp(8); lp.y = 0;
        try { windowManager.addView(panel, lp); } catch (Exception e) { panel = null; }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            long now = System.currentTimeMillis();
            if (now - lastScanAt > 450) { lastScanAt = now; handler.postDelayed(this::scanAccessibilityTree, 250); }
        }
    }

    @Override public void onInterrupt() { }

    private void scanAccessibilityTree() {
        targetBounds.clear();
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try { for (AccessibilityNodeInfo n : getWindowsRootNodes()) if (n != null) roots.add(n); } catch (Exception ignored) { }
        for (AccessibilityNodeInfo root : roots) walkNode(root, 0);
    }

    private List<AccessibilityNodeInfo> getWindowsRootNodes() {
        List<AccessibilityNodeInfo> list = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root != null) list.add(root);
        return list;
    }

    private void walkNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 35) return;
        CharSequence cs = node.getText(); if (cs == null) cs = node.getContentDescription();
        if (cs != null) {
            String text = normalize(cs.toString()); Rect r = new Rect(); node.getBoundsInScreen(r);
            if (!r.isEmpty() && r.width() > 0 && r.height() > 0) {
                if (isLabel(text, "A")) putIfBetter("A", r);
                if (isLabel(text, "B")) putIfBetter("B", r);
                if (isLabel(text, "C")) putIfBetter("C", r);
                if (isLabel(text, "D")) putIfBetter("D", r);
                if (text.equals("正确") || text.equals("正确答案") || text.equals("对")) putIfBetter("A", r);
                if (text.equals("错误") || text.equals("错误答案") || text.equals("错")) putIfBetter("B", r);
            }
        }
        for (int i=0;i<node.getChildCount();i++) walkNode(node.getChild(i), depth+1);
    }

    private String normalize(String s) { return s == null ? "" : s.replaceAll("\\s+", "").trim().toUpperCase(Locale.ROOT); }
    private boolean isLabel(String text, String label) { return text.equals(label) || text.equals(label + ".") || text.equals(label + "、") || text.equals("选项" + label); }
    private void putIfBetter(String key, Rect rect) {
        Rect old = targetBounds.get(key);
        if (old == null || (rect.width()*rect.height() > old.width()*old.height() && rect.width() < getResources().getDisplayMetrics().widthPixels * 0.8)) targetBounds.put(key, new Rect(rect));
    }

    private void performMappedTap(String label) {
        scanAccessibilityTree();
        Rect rect = targetBounds.get(label);
        if (rect != null) {
            // Prefer the center of the detected option label/control. Gesture dispatch can tap non-clickable text too.
            tap(rect.centerX(), rect.centerY());
            toast("已定位 " + label + "，正在点击");
        } else if (Build.VERSION.SDK_INT >= 30) {
            runOcrFallback(label);
        } else {
            toast("未识别到 " + label + "；请确认无障碍权限及题目界面");
        }
    }

    private void runOcrFallback(String label) {
        if (panel != null) panel.setVisibility(android.view.View.INVISIBLE);
        handler.postDelayed(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, worker, new TakeScreenshotCallback() {
                    @Override public void onSuccess( android.accessibilityservice.AccessibilityService.ScreenshotResult result) {
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (buffer != null) buffer.close();
                        if (bitmap == null) { restorePanel(); toast("截图识别不可用"); return; }
                        InputImage image = InputImage.fromBitmap(bitmap, 0);
                        recognizer.process(image).addOnSuccessListener(text -> {
                            Rect found = findOcrTarget(text, label);
                            bitmap.recycle(); restorePanel();
                            if (found != null) { tap(found.centerX(), found.centerY()); toast("OCR 已定位 " + label); }
                            else toast("未找到 " + label + "；此页面可能不支持自动校准");
                        }).addOnFailureListener(e -> { bitmap.recycle(); restorePanel(); toast("OCR 识别失败"); });
                    }
                    @Override public void onFailure(int errorCode) { restorePanel(); toast("无法读取屏幕，请检查系统权限"); }
                });
            } catch (Exception e) { restorePanel(); toast("屏幕识别失败"); }
        }, 180);
    }

    private Rect findOcrTarget(Text text, String label) {
        List<Rect> exact = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) for (Text.Line line : block.getLines()) {
            String s = normalize(line.getText()); Rect b = line.getBoundingBox(); if (b == null) continue;
            if (isLabel(s,label)) exact.add(b);
            if (label.equals("A") && (s.equals("正确") || s.equals("对"))) exact.add(b);
            if (label.equals("B") && (s.equals("错误") || s.equals("错"))) exact.add(b);
        }
        if (!exact.isEmpty()) {
            // Ignore topmost matches that could belong to the app chrome; options tend to be in the content area.
            exact.sort((a,b) -> Integer.compare(a.centerY(), b.centerY()));
            return exact.get(exact.size()-1);
        }
        return null;
    }

    private void tap(float x, float y) {
        Path path = new Path(); path.moveTo(x,y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 80));
        dispatchGesture(builder.build(), null, null);
    }
    private void restorePanel() { handler.post(() -> { if (panel != null) panel.setVisibility(android.view.View.VISIBLE); }); }
    private void toast(String s) { handler.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    @Override public void onDestroy() {
        if (panel != null && windowManager != null) { try { windowManager.removeView(panel); } catch (Exception ignored) {} panel = null; }
        if (recognizer != null) recognizer.close();
        super.onDestroy();
    }
}
