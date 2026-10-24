package com.study.quadtap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 四格悬浮点击面板。
 *
 * 定位优先级：无障碍节点树 -> 手动校准记录 -> 截屏 + 中文 OCR。
 * 本服务不判断答案，只把用户按下的 A/B/C/D 映射为屏幕上的一个点击坐标。
 */
public class QuadTapAccessibilityService extends AccessibilityService {

    private static final String[] LABELS = {"A", "B", "C", "D"};

    /** 判断题没有 A-D 字母时使用的伪标签：正确 -> A，错误 -> B。 */
    private static final String KEY_TRUE = "Y";
    private static final String KEY_FALSE = "N";

    /** 单独一个字母，允许带括号："A"、"(A)"。 */
    private static final Pattern P_BARE = Pattern.compile("^\\(?([A-D])\\)?$");
    /** 括号形式："(A)加速通过"。 */
    private static final Pattern P_PAREN = Pattern.compile("^\\(([A-D])\\)(.*)$");
    /** 中文形式："选项A"。 */
    private static final Pattern P_CN = Pattern.compile("^选项([A-D])(.*)$");
    /** 前缀形式，这是真实题库最常见的写法："A.加速通过"、"B、停车让行"。 */
    private static final Pattern P_PREFIX = Pattern.compile("^([A-D])[.、．,:：)）\\-—](.*)$");

    /** 超过这个长度的文本不可能是选项标签，用来过滤题目正文。 */
    private static final int MAX_LABEL_TEXT = 80;
    /** 前缀匹配时，选项正文的长度上限，用来过滤"A、B、C、D四个选项"这类正文。 */
    private static final int MAX_OPTION_BODY = 40;
    /** 位置兜底时，一个"选项行"文字的长度上限。 */
    private static final int MAX_ROW_TEXT = 60;
    /** 位置兜底时，一个"选项行"至少要有多少高度。 */
    private static final int MIN_ROW_HEIGHT_DP = 24;
    /** 同一个标签最多保留几个候选参与组合评分。 */
    private static final int MAX_CANDIDATES_PER_LABEL = 3;

    /** OCR 超时兜底：超过这个时间还没收尾就强制结束，避免 busy 永久为 true。 */
    private static final long OCR_TIMEOUT_MS = 15000L;

    // ---- 悬浮面板配色。A/B 用主色蓝，C/D 用同族的低饱和蓝灰，面板整体更协调。
    private static final int CELL_BLUE = 0xFF3478F6;
    private static final int CELL_BLUE_PRESSED = 0xFF255FC9;
    private static final int CELL_BLUE_GRAY = 0xFF6B7B95;
    private static final int CELL_BLUE_GRAY_PRESSED = 0xFF56637A;

    private WindowManager windowManager;
    private GridLayout panel;
    private WindowManager.LayoutParams panelParams;
    private FrameLayout markLayer;
    private View picker;

    /** 当前前台应用包名，用来决定面板是否显示。 */
    private String foregroundPackage;
    /** 输入法当前是否可见。 */
    private boolean imeVisible = false;
    /** 面板当前是否已经挂在 WindowManager 上。 */
    private boolean panelAttached = false;

    private final Map<String, Rect> targetBounds = new HashMap<>();
    private final Map<String, String> targetText = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private TextRecognizer recognizer;
    private Prefs prefs;

    /** 同一时间只允许一次识别流程，避免连点叠出多路截屏。 */
    private volatile boolean busy = false;
    private volatile int lastFoundCount = 0;

    // ---- 运行控制（应用内开关）。注意它和「系统无障碍是否连接」是两件事。----
    /** 辅助答题功能本次是否在运行。false 时所有点击入口一律拒绝。 */
    private volatile boolean assistRunning = false;
    /** 本次运行起点（elapsedRealtime 毫秒），0 表示未运行。 */
    private volatile long runStartedAt = 0L;
    /**
     * 当前题目**已确证**的最大选项序号：只会因为「正向识别到 A/B/C/D 中的某一项」而增大。
     * 0 表示什么都没确证 —— 按需求「识别失败或选项数量不确定时，不执行辅助点击」，
     * 此时一律不派发点击。
     *
     * 注意：它不是「题目总共有几项」，而是「已经确证到第几项」。
     * 所以只识别到 A、B 时它是 2（A、B 可用，C、D 属于可能越界，拒绝），
     * 绝不被解释为「这是判断题」。
     */
    private volatile int allowedLabelCount = 0;

    // allowedLabelCount 的来源，仅用于日志诊断。
    // 常量先声明，避免字段初始化器引用后面才声明的常量（非法前向引用风险）。
    private static final int TYPE_NONE = 0;        // 未知
    private static final int TYPE_JUDGE = 1;       // 正向识别到「正确/错误」
    private static final int TYPE_LABELS = 2;      // 正向识别到字母标签
    private static final int TYPE_POSITIONAL = 3;  // 位置兜底
    private static final int TYPE_OCR = 4;         // OCR

    private static String typeSourceName(int source) {
        if (source == TYPE_JUDGE) return "判断题(正确/错误)";
        if (source == TYPE_LABELS) return "字母标签";
        if (source == TYPE_POSITIONAL) return "位置兜底";
        if (source == TYPE_OCR) return "OCR";
        return "未知";
    }

    private volatile int typeSource = TYPE_NONE;

    /**
     * OCR 任务代次。每次发起 OCR 或在停止/重新开始时自增，
     * 旧代次的回调一律丢弃 —— 只靠 assistRunning 不够，
     * 因为停止后重新开启时它又会变回 true。
     */
    private volatile int ocrToken = 0;
    private long lastScanAt = 0;
    private long lastShotAt = 0;
    private long lastImeCheckAt = 0;

    private float downRawX, downRawY;
    private int downPanelX, downPanelY;
    private boolean dragging = false;
    private int pressedIndex = -1;
    private String learningLabel = null;

    private static volatile QuadTapAccessibilityService instance;

    /** 供设置页查询服务是否已连接。 */
    static QuadTapAccessibilityService get() { return instance; }

    /** 最近一次扫描识别到几个选项，供设置页展示。 */
    int lastFoundCount() { return lastFoundCount; }

    private final Runnable scanRunnable = new Runnable() {
        @Override public void run() {
            refreshWindowState();
            if (assistRunning && panelAttached) scanAccessibilityTree(false);
        }
    };
    private final Runnable hideMarksRunnable = new Runnable() {
        @Override public void run() { if (markLayer != null) markLayer.removeAllViews(); }
    };
    private final Runnable pickerTimeout = new Runnable() {
        @Override public void run() { cancelPicker(); }
    };

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------------- 生命周期

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        prefs = new Prefs(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        } catch (Throwable t) {
            // 没有 OCR 也能靠无障碍节点和手动校准工作。
            recognizer = null;
        }
        createMarkLayer();
        createOverlay();
        Point size = screenSize();
        TapLog.add("===== 服务已连接 =====");
        TapLog.add("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        TapLog.add("屏幕 " + size.x + "x" + size.y + "  密度 " + getResources().getDisplayMetrics().density);
        TapLog.add("截屏能力 API30+ = " + (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                + "  OCR 客户端 = " + (recognizer != null));
        TapLog.add("开关: OCR=" + prefs.useOcr() + " 标记=" + prefs.showMarks()
                + " 仅手动=" + prefs.manualOnly() + " 已校准=" + prefs.learnedCount() + "/4");
        TapLog.add("限定应用: " + (prefs.restrictingToApps()
                ? prefs.allowedPackages().toString()
                : "未限制(所有应用都显示)"));
        TapLog.add("方块大小: " + prefs.cellSizeDp() + "dp   输入法弹出时隐藏: " + prefs.hideOnIme());
        // 恢复用户上次的开关意图：开则直接运行，关则保持停止（不擅自自动启动）
        assistRunning = false;
        runStartedAt = 0L;
        setAssistRunning(prefs.assistRunning(), false);
        refreshWindowState();
        scanAccessibilityTree(false);
        if (assistRunning) toast("面板已就绪：点击定位，长按手动校准");
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        cachedScreenSize = null;
        handler.post(new Runnable() {
            @Override public void run() {
                if (panel != null && panelParams != null) {
                    panelParams.x = clampX(panelParams.x);
                    panelParams.y = clampY(panelParams.y);
                    try { windowManager.updateViewLayout(panel, panelParams); } catch (Exception ignored) { }
                }
            }
        });
        scanAccessibilityTree(false);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();
        boolean windowChanged = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        boolean contentChanged = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED;

        // 切换应用、输入法弹出或收起都会走到这里。
        if (windowChanged) refreshWindowState();

        if (picker != null) return;
        if (!windowChanged && !contentChanged) return;

        // 输入法不一定每次都发 typeWindowsChanged，这里做一层节流的兜底复核。
        if (contentChanged && prefs.hideOnIme()) {
            long now = System.currentTimeMillis();
            if (now - lastImeCheckAt > 500) {
                lastImeCheckAt = now;
                boolean ime = isImeVisible();
                if (ime != imeVisible) {
                    imeVisible = ime;
                    applyPanelVisibility();
                }
            }
        }

        // 面板没挂上就没必要扫描节点树，省电。
        if (!panelAttached) return;

        long now = System.currentTimeMillis();
        if (now - lastScanAt > 400) {
            lastScanAt = now;
            // 合并短时间内的连续事件，只保留最后一次扫描。
            handler.removeCallbacks(scanRunnable);
            handler.postDelayed(scanRunnable, 250);
        }
    }

    @Override public void onInterrupt() { }

    @Override public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        removeWindow(picker);
        picker = null;
        removeWindow(markLayer);
        markLayer = null;
        removeWindow(panel);
        panel = null;
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) { }
            recognizer = null;
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    private void removeWindow(View view) {
        if (view != null && windowManager != null) {
            try { windowManager.removeView(view); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- 悬浮面板

    private void createOverlay() {
        if (panel != null || windowManager == null) return;

        panel = new GridLayout(this);
        panel.setColumnCount(2);
        panel.setRowCount(2);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF7FFFFFF);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0xFFDCE3F1);
        panel.setBackground(background);
        panel.setElevation(dp(6));

        int cellSize = prefs.cellSizeDp();
        int cellTextSize = Math.max(11, Math.round(cellSize * 0.355f));
        int cellRadius = Math.round(cellSize * 0.26f);
        int[] colors = {CELL_BLUE, CELL_BLUE, CELL_BLUE_GRAY, CELL_BLUE_GRAY};
        int[] pressedColors = {CELL_BLUE_PRESSED, CELL_BLUE_PRESSED,
                CELL_BLUE_GRAY_PRESSED, CELL_BLUE_GRAY_PRESSED};
        for (int i = 0; i < LABELS.length; i++) {
            TextView cell = new TextView(this);
            cell.setText(LABELS[i]);
            cell.setTextSize(cellTextSize);
            cell.setTextColor(Color.WHITE);
            cell.setGravity(Gravity.CENTER);
            cell.setTypeface(null, android.graphics.Typeface.BOLD);
            GradientDrawable cellBackground = new GradientDrawable();
            cellBackground.setColor(colors[i]);
            cellBackground.setCornerRadius(dp(cellRadius));
            cell.setBackground(cellBackground);
            // 按下时变色，和轻微的缩放一起构成按下反馈
            cell.setBackgroundTintList(new ColorStateList(
                    new int[][]{ new int[]{android.R.attr.state_pressed}, new int[]{} },
                    new int[]{ pressedColors[i], colors[i] }));

            GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams();
            cellParams.width = dp(cellSize);
            cellParams.height = dp(cellSize - 2);
            cellParams.setMargins(dp(3), dp(3), dp(3), dp(3));
            panel.addView(cell, cellParams);
        }

        // 注意：格子本身不注册 OnClickListener，否则子 View 会吃掉触摸事件，
        // 面板就收不到拖动和长按了。点击/长按由面板统一按坐标判定。
        panel.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                return onPanelTouch(event);
            }
        });

        panelParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;

        Point size = screenSize();
        int defaultX = Math.max(0, size.x - estimatedPanelWidth() - dp(6));
        int defaultY = dp(200);
        panelParams.x = clampX(prefs.panelX(defaultX));
        panelParams.y = clampY(prefs.panelY(defaultY));

        try {
            windowManager.addView(panel, panelParams);
            panelAttached = true;
        } catch (Exception e) {
            panel = null;
            panelAttached = false;
        }
    }

    /** 从当前活动窗口读前台包名。自己的界面不参与判定，避免面板在设置页里被误隐藏。 */
    private void refreshForegroundPackage() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            CharSequence pkg = root.getPackageName();
            if (pkg == null) return;
            String name = pkg.toString();
            if (name.equals(getPackageName())) return;
            foregroundPackage = name;
        } catch (Exception ignored) { }
    }

    /** 输入法窗口是否已经弹出来。getWindows() 依赖 flagRetrieveInteractiveWindows。 */
    private boolean isImeVisible() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** 前台应用和输入法状态一起刷新，然后决定面板去留。 */
    private void refreshWindowState() {
        refreshForegroundPackage();
        boolean ime = isImeVisible();
        if (ime != imeVisible) {
            imeVisible = ime;
            if (prefs.hideOnIme()) {
                TapLog.add("输入法" + (ime ? "弹出 -> 隐藏面板" : "收起 -> 恢复判定"));
            }
        }
        applyPanelVisibility();
    }

    /** 设置页改动后由 Activity 调过来（同进程静态调用），重建面板并重新判定显示。 */
    // ---------------------------------------------------------------- 运行控制

    /** 辅助功能本次是否在运行（供设置页读取真实状态，而不是看开关外观）。 */
    static boolean isAssistRunning() {
        QuadTapAccessibilityService service = instance;
        return service != null && service.assistRunning;
    }

    /** 本次运行起点（elapsedRealtime）。0 表示未运行。 */
    static long runStartedAt() {
        QuadTapAccessibilityService service = instance;
        return service == null ? 0L : service.runStartedAt;
    }

    /** 当前题型允许的标签数，0 表示未确定。 */
    static int allowedLabelCount() {
        QuadTapAccessibilityService service = instance;
        return service == null ? 0 : service.allowedLabelCount;
    }

    /**
     * Activity 改完开关后调用：把持久化的意图应用到已连接的服务实例上。
     *
     * 关键点：主线程调用时**同步**执行。
     * 之前用 handler.post 异步应用，调用方紧接着 refreshStatus() 读到的还是旧状态，
     * 会把开关弹回原样，而且之后界面再也不刷新 —— 这就是 S1。
     * setAssistRunning 会操作 WindowManager / View，本来就必须在主线程执行，
     * 所以主线程直接调用是安全的。
     */
    static void applyAssistRunningFromPrefs() {
        final QuadTapAccessibilityService service = instance;
        if (service == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            service.setAssistRunning(service.prefs.assistRunning(), true);
        } else {
            service.handler.post(new Runnable() {
                @Override public void run() {
                    service.setAssistRunning(service.prefs.assistRunning(), true);
                }
            });
        }
    }

    /** 切换运行状态：开启时开始计时，关闭时执行完整停止流程。 */
    private void setAssistRunning(boolean on, boolean announce) {
        if (on == assistRunning) {
            if (on) applyPanelVisibility();
            return;
        }
        assistRunning = on;
        if (on) {
            // 新运行周期：让上一周期遗留的 OCR 回调全部作废
            ocrToken++;
            runStartedAt = SystemClock.elapsedRealtime();
            TapLog.add("辅助功能已开启，本次运行计时开始");
            applyPanelVisibility();
            scanAccessibilityTree(false);
            if (announce) toast("辅助功能已开启");
        } else {
            stopAssisting();
            if (announce) toast("辅助功能已停止");
        }
    }

    /**
     * 完整停止流程：拒绝新点击 → 取消排队任务 → 摘除悬浮窗 → 停止计时。
     *
     * 只停 QuadTap 自己的辅助行为，不去关闭系统无障碍 ——
     * Android 不允许应用自行开启无障碍服务，所以开关无法做到完全对称；
     * 系统无障碍可能仍保持连接，但 QuadTap 不再执行任何操作。
     */
    private void stopAssisting() {
        runStartedAt = 0L;
        allowedLabelCount = 0;
        typeSource = TYPE_NONE;
        busy = false;
        // 让所有正在进行的 OCR 任务作废：停止后重新开启也不复用旧结果
        ocrToken++;
        // 顺带复位截屏节流时间戳：否则重新开启后 400ms 内首按会被误报「截屏太频繁」（B5）
        lastShotAt = 0L;
        // 取消所有延迟任务：扫描合并、OCR 启动、标记隐藏、校准超时
        handler.removeCallbacksAndMessages(null);
        cancelPicker();
        if (markLayer != null) markLayer.removeAllViews();
        setOverlaysVisible(false);
        targetBounds.clear();
        targetText.clear();
        TapLog.add("辅助功能已停止：取消排队任务并移除悬浮窗");
    }

    /**
     * 候选点击是否允许：只有「序号 ≤ 已确证的最大序号」才放行。
     * 什么都没确证（0）时一律拒绝，这是需求要求的保守策略。
     */
    private boolean isLabelAllowed(String label) {
        int index = 0;
        for (int i = 0; i < LABELS.length; i++) {
            if (LABELS[i].equals(label)) index = i + 1;
        }
        if (index == 0) return false;
        if (allowedLabelCount <= 0) {
            TapLog.add("尚未确证任何选项（来源=" + typeSourceName(typeSource)
                    + "），拒绝点击 " + label);
            return false;
        }
        if (index > allowedLabelCount) {
            TapLog.add("仅确证到 " + allowedLabelCount + " 项（来源=" + typeSourceName(typeSource)
                    + "），" + label + " 可能越界，拒绝点击");
            return false;
        }
        return true;
    }

    /**
     * 已确证的最大选项序号：遍历 A→D，取存在的最高那一位。
     * 用「最高序号」而不是「命中个数」，可以避免
     * 「只识别到 A、C 两项却把 C 当越界拒掉」这种错误。
     */
    private static int highestLabelIndex(Map<String, Rect> map) {
        if (map == null) return 0;
        int highest = 0;
        for (int i = 0; i < LABELS.length; i++) {
            if (map.containsKey(LABELS[i])) highest = i + 1;
        }
        return highest;
    }

    static void notifySettingsChanged() {
        final QuadTapAccessibilityService service = instance;
        if (service == null) return;
        service.handler.post(new Runnable() {
            @Override public void run() {
                service.rebuildOverlay();
                service.refreshWindowState();
            }
        });
    }

    /** 方块大小等外观设置变了：摘掉旧面板按新尺寸重建，位置沿用已保存的坐标。 */
    private void rebuildOverlay() {
        if (windowManager == null || prefs == null) return;
        boolean wasAttached = panelAttached;
        if (panel != null) {
            try { windowManager.removeView(panel); } catch (Exception ignored) { }
            panel = null;
            panelAttached = false;
        }
        createOverlay();
        if (!wasAttached && panel != null) {
            // 重建之前本来就是隐藏状态，那就重新摘掉。
            try { windowManager.removeView(panel); } catch (Exception ignored) { }
            panelAttached = false;
        }
        TapLog.add("按新设置重建面板: 方块 " + prefs.cellSizeDp() + "dp  已显示=" + panelAttached);
    }

    /** 面板布局完成之前的估算尺寸，用来夹紧位置。 */
    private int estimatedPanelWidth() {
        return dp(prefs.cellSizeDp() * 2 + 22);
    }

    private int estimatedPanelHeight() {
        return dp(prefs.cellSizeDp() * 2 + 18);
    }

    /**
     * 按「限定显示的应用」决定面板挂上还是摘掉。
     *
     * 这里用真正 addView/removeView，而不是 setVisibility：INVISIBLE 的窗口仍然存在，
     * 依然会吃掉它覆盖区域的触摸事件，会让下面的应用点不动。
     */
    private void applyPanelVisibility() {
        if (panel == null || windowManager == null || panelParams == null) return;
        if (busy) return;   // 正在截屏识别，等 finishOcr 再统一处理

        // 停止状态下根本不挂窗口（不是隐藏）
        boolean shouldShow = assistRunning && prefs.isPackageAllowed(foregroundPackage);
        if (shouldShow && prefs.hideOnIme() && imeVisible) shouldShow = false;
        if (shouldShow == panelAttached) return;

        if (shouldShow) {
            panel.setVisibility(View.VISIBLE);
            try {
                windowManager.addView(panel, panelParams);
                panelAttached = true;
            } catch (Exception e) {
                panelAttached = false;
            }
        } else {
            cancelPicker();
            if (markLayer != null) markLayer.removeAllViews();
            try {
                windowManager.removeView(panel);
            } catch (Exception ignored) { }
            panelAttached = false;
        }
        if (!shouldShow) {
            // 摘掉面板时顺手把过期坐标清掉，避免下次拿旧结果去点。
            targetBounds.clear();
            targetText.clear();
        }
        TapLog.add("面板" + (shouldShow ? "显示" : "隐藏")
                + " (前台=" + foregroundPackage
                + (imeVisible ? ", 输入法已弹出" : "")
                + (prefs.restrictingToApps() ? "" : ", 未限定应用") + ")");
    }

    private boolean onPanelTouch(MotionEvent event) {
        if (panelParams == null || windowManager == null) return false;
        if (!assistRunning) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = false;
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downPanelX = panelParams.x;
                downPanelY = panelParams.y;
                pressedIndex = cellIndexAt(event.getX(), event.getY());
                animateCellPress(pressedIndex, true);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    dragging = true;
                    // 开始拖动就不该再显示按下态
                    animateCellPress(pressedIndex, false);
                }
                if (dragging) {
                    panelParams.x = clampX(downPanelX + (int) dx);
                    panelParams.y = clampY(downPanelY + (int) dy);
                    try { windowManager.updateViewLayout(panel, panelParams); } catch (Exception ignored) { }
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                animateCellPress(pressedIndex, false);
                if (dragging) {
                    snapToEdge();
                    prefs.setPanelPosition(panelParams.x, panelParams.y);
                } else if (pressedIndex >= 0 && pressedIndex < LABELS.length) {
                    String label = LABELS[pressedIndex];
                    long held = event.getEventTime() - event.getDownTime();
                    if (held >= ViewConfiguration.getLongPressTimeout()) beginLearning(label);
                    else performMappedTap(label);
                }
                pressedIndex = -1;
                dragging = false;
                return true;
            }

            default:
                return false;
        }
    }

    /**
     * 格子的按下/抬起视觉反馈（轻微缩放）。
     *
     * 只改绘制缩放，不参与任何点击判定：cellIndexAt 用的是 getLeft/getRight 原始边界，
     * 不受 scale 影响，所以判定逻辑一行都没动。
     */
    private void animateCellPress(int index, boolean pressed) {
        if (panel == null || index < 0 || index >= panel.getChildCount()) return;
        View cell = panel.getChildAt(index);
        cell.animate().cancel();
        cell.animate()
                .scaleX(pressed ? 0.90f : 1f)
                .scaleY(pressed ? 0.90f : 1f)
                .setDuration(pressed ? 70 : 120)
                .start();
    }

    /** 按子 View 的实际布局判断按到了哪一格。 */
    private int cellIndexAt(float x, float y) {
        if (panel == null) return -1;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                return i;
            }
        }
        return -1;
    }

    private void snapToEdge() {
        if (panel == null) return;
        Point size = screenSize();
        int width = panel.getWidth() > 0 ? panel.getWidth() : estimatedPanelWidth();
        int center = panelParams.x + width / 2;
        panelParams.x = center < size.x / 2 ? dp(8) : Math.max(0, size.x - width - dp(8));
        try { windowManager.updateViewLayout(panel, panelParams); } catch (Exception ignored) { }
    }

    private int clampX(int x) {
        Point size = screenSize();
        int width = (panel != null && panel.getWidth() > 0) ? panel.getWidth() : estimatedPanelWidth();
        return Math.max(0, Math.min(x, Math.max(0, size.x - width)));
    }

    private int clampY(int y) {
        Point size = screenSize();
        int height = (panel != null && panel.getHeight() > 0) ? panel.getHeight() : estimatedPanelHeight();
        return Math.max(0, Math.min(y, Math.max(0, size.y - height)));
    }

    // ---------------------------------------------------------------- 手动校准

    private void beginLearning(String label) {
        if (picker != null) { toast("请先完成或取消当前校准"); return; }
        startPicker(label);
    }

    /**
     * 全屏透明取点层。
     *
     * 用「用户直接点真实位置」而不是「让用户点一次真选项再记录」，原因是：
     * 1) 不会误触目标应用、不会真的作答；
     * 2) WebView / Compose / 自绘界面不发无障碍点击事件时同样可用。
     */
    private void startPicker(final String label) {
        if (windowManager == null) return;
        learningLabel = label;
        if (panel != null) panel.setVisibility(View.INVISIBLE);
        if (markLayer != null) markLayer.removeAllViews();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x1A000000);
        root.setClickable(true);

        TextView hint = new TextView(this);
        hint.setText("正在校准「" + label + "」\n请点击屏幕上该选项的位置\n（这一步不会真的点到应用）");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(3), 1f);
        hint.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable hintBackground = new GradientDrawable();
        hintBackground.setColor(0xE6172033);
        hintBackground.setCornerRadius(dp(12));
        hint.setBackground(hintBackground);
        FrameLayout.LayoutParams hintParams =
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintParams.topMargin = dp(48);
        root.addView(hint, hintParams);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setTextColor(Color.WHITE);
        cancel.setBackgroundTintList(ColorStateList.valueOf(0xFF5A6B85));
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancelPicker(); }
        });
        FrameLayout.LayoutParams cancelParams =
                new FrameLayout.LayoutParams(dp(104), dp(46), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cancelParams.bottomMargin = dp(56);
        root.addView(cancel, cancelParams);

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    String target = learningLabel;
                    if (target != null) {
                        saveLearned(target, event.getRawX(), event.getRawY());
                        toast("已记录 " + target + " 的位置");
                    }
                    cancelPicker();
                }
                return true;
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(root, params);
            picker = root;
            handler.postDelayed(pickerTimeout, 15000);
        } catch (Exception e) {
            picker = null;
            learningLabel = null;
            if (panel != null) panel.setVisibility(View.VISIBLE);
            toast("无法开启校准层");
        }
    }

    private void cancelPicker() {
        handler.removeCallbacks(pickerTimeout);
        removeWindow(picker);
        picker = null;
        learningLabel = null;
        if (panel != null) panel.setVisibility(View.VISIBLE);
    }

    private void saveLearned(String label, float rawX, float rawY) {
        Point size = screenSize();
        if (size.x <= 0 || size.y <= 0) return;
        prefs.setLearned(label, clamp01(rawX / size.x), clamp01(rawY / size.y));
        TapLog.add("校准记录 " + label + " 原始=(" + (int) rawX + "," + (int) rawY + ") 屏幕="
                + size.x + "x" + size.y + " 相对=("
                + String.format(Locale.US, "%.4f", rawX / size.x) + ","
                + String.format(Locale.US, "%.4f", rawY / size.y) + ")");
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private Rect learnedRect(String label) {
        float[] relative = prefs.learned(label);
        if (relative == null) return null;
        Point size = screenSize();
        int x = Math.round(relative[0] * size.x);
        int y = Math.round(relative[1] * size.y);
        if (x <= 0 || y <= 0 || x >= size.x || y >= size.y) return null;
        int pad = dp(2);
        return new Rect(x - pad, y - pad, x + pad, y + pad);
    }

    // ---------------------------------------------------------------- 点击流程

    private void performMappedTap(String label) {
        TapLog.add("");
        TapLog.add("===== 点击 " + label + " =====");
        if (!assistRunning) {
            TapLog.add("辅助功能已停止，忽略本次点击");
            toast("辅助功能已停止，请先在首页打开开关");
            return;
        }
        if (busy) {
            TapLog.add("忽略: 上一次识别还在进行中");
            toast("正在识别中，请稍候");
            return;
        }

        // 无论哪种模式都扫描一次：manualOnly 只用它刷新「已确证选项」，
        // 绝不取它的坐标，所以原有坐标映射逻辑没有改变。
        // 这样 manualOnly 也不会再用上一次题目的过期题型数据。
        scanAccessibilityTree(true);

        // allowedLabelCount 是「已确证到的最高序号」，0 表示什么都没确证。
        final boolean typeKnown = allowedLabelCount > 0;

        // C1 修复：门禁只在题型已确知时前置拦截。
        // 题型未知时如果也在这里 return，runOcrFallback 就永远走不到，
        // 等于把 OCR 回退整个删掉。未知时改为放行到后续流程，
        // 但下面的点击分支都要求 typeKnown，所以不会产生「未经确认的点击」。
        if (typeKnown && !isLabelAllowed(label)) {
            toast("当前题型没有 " + label + " 项，已忽略");
            return;
        }
        if (!typeKnown) {
            TapLog.add("尚未确证题型：不做节点/校准点击，转由 OCR 重新识别");
        }

        // ① 节点定位：节点命中必须在题型已确知的前提下，不能绕过门禁
        if (!prefs.manualOnly() && typeKnown) {
            Rect rect = targetBounds.get(label);
            if (rect != null) {
                TapLog.add("来源=节点定位  中心=(" + rect.centerX() + "," + rect.centerY() + ")");
                if (prefs.showMarks()) showMarks();
                tap(rect.centerX(), rect.centerY());
                String text = targetText.get(label);
                toast("已定位 " + label + (text == null ? "" : "（" + shorten(text) + "）"));
                return;
            }
            TapLog.add("节点定位失败: 该标签没有可用候选");
        } else if (prefs.manualOnly() && typeKnown) {
            TapLog.add("已开启「仅用校准位置」: 扫描仅用于刷新题型，坐标仍取校准记录");
        }

        // ② 校准坐标：manualOnly 的原有映射保持不变，但同样只在题型已确知时使用
        if (typeKnown) {
            Rect learned = learnedRect(label);
            if (learned != null) {
                TapLog.add("来源=已校准位置  中心=(" + learned.centerX() + "," + learned.centerY() + ")");
                tap(learned.centerX(), learned.centerY());
                toast("使用已校准位置点击 " + label);
                return;
            }
            if (prefs.manualOnly()) {
                TapLog.add("无校准记录, 且已开启仅手动模式 -> 放弃");
                toast("尚未校准 " + label + "，请长按该格取点");
                return;
            }
        }

        // ③ OCR 回退：题型未知时的唯一出路。OCR 会用自己的识别结果
        //    重新建立题型并再过一次门禁（applyOcrResult），不可靠就仍然不点。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && prefs.useOcr() && recognizer != null) {
            runOcrFallback(label);
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            TapLog.add("无 OCR 可用: 系统低于 Android 11 -> 放弃");
            toast("未找到 " + label + "；本机系统版本不支持截屏识别，请长按该格取点");
        } else {
            TapLog.add("无 OCR 可用: OCR 开关=" + prefs.useOcr() + " 客户端=" + (recognizer != null) + " -> 放弃");
            toast("未找到 " + label + "，请长按该格取点");
        }
    }

    private String shorten(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        return trimmed.length() <= 10 ? trimmed : trimmed.substring(0, 10) + "…";
    }

    // ---------------------------------------------------------------- 无障碍节点扫描

    private void scanAccessibilityTree(boolean verbose) {
        targetBounds.clear();
        targetText.clear();
        Map<String, List<Candidate>> found = new HashMap<>();
        for (String label : LABELS) found.put(label, new ArrayList<Candidate>());
        found.put(KEY_TRUE, new ArrayList<Candidate>());
        found.put(KEY_FALSE, new ArrayList<Candidate>());

        List<String> dump = verbose ? new ArrayList<String>() : null;
        int[] counters = new int[]{0, 0};   // {访问节点数, 带文本节点数}
        List<Candidate> rows = new ArrayList<>();   // 位置兜底用的"可选行"原料

        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) roots.add(active);
            // 配置里声明了 flagRetrieveInteractiveWindows，这里真正用上：
            // 题目渲染在独立窗口 / 弹窗里时，仅看当前活动窗口是找不到的。
            List<AccessibilityWindowInfo> windows = getWindows();
            if (verbose) TapLog.add("窗口共 " + (windows == null ? 0 : windows.size()) + " 个:");
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                // 必须排除自己的悬浮窗，否则面板上那四个 "A".."D" 会被当成选项。
                boolean own = isOwnNode(root);
                if (verbose) {
                    TapLog.add("  win type=" + window.getType()
                            + " layer=" + window.getLayer()
                            + " active=" + window.isActive()
                            + " pkg=" + root.getPackageName()
                            + (own ? "   <-- 自己的窗口,已跳过" : ""));
                }
                if (own) continue;
                boolean duplicate = false;
                for (AccessibilityNodeInfo existing : roots) {
                    if (root.equals(existing)) { duplicate = true; break; }
                }
                if (!duplicate) roots.add(root);
            }
        } catch (Exception e) {
            if (verbose) TapLog.add("取窗口失败: " + e);
        }

        for (AccessibilityNodeInfo root : roots) collect(root, 0, false, found, rows, dump, counters);
        if (verbose) {
            TapLog.add("节点树: 访问 " + counters[0] + " 个节点, 其中 " + counters[1] + " 个带文本");
            if (dump != null) {
                for (String line : dump) TapLog.add(line);
            }
            TapLog.add("可选行原料: " + rows.size() + " 个");
            for (Candidate row : rows) {
                TapLog.add("  row " + rectToString(row.rect) + " d=" + row.depth
                        + " txt=\"" + shorten(row.text) + "\"");
            }
        }
        pickBest(found, rows, verbose);
    }

    private void collect(AccessibilityNodeInfo node, int depth, boolean clickableAncestor,
                         Map<String, List<Candidate>> out, List<Candidate> rows,
                         List<String> dump, int[] counters) {
        if (node == null || depth > 40) return;
        try {
            // 自己的悬浮窗不属于"屏幕上的选项"，直接跳过整棵子树。
            if (isOwnNode(node)) return;

            counters[0]++;
            boolean clickable = clickableAncestor || node.isClickable();

            CharSequence text = node.getText();
            if (text == null || text.length() == 0) text = node.getContentDescription();
            if (text != null && text.length() > 0) {
                counters[1]++;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                String raw = text.toString();

                if (dump != null) {
                    // 原样记录，用来确认真实题库的选项文字到底长什么样。
                    String shown = raw.length() > 60 ? raw.substring(0, 60) + "…" : raw;
                    dump.add("  d" + depth
                            + (clickable ? " click" : "     ")
                            + " [" + bounds.left + "," + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]"
                            + " txt=\"" + shown + "\""
                            + (raw.length() > MAX_LABEL_TEXT ? "  <-- 超长,已忽略" : ""));
                }

                if (raw.length() <= MAX_LABEL_TEXT) {
                    String normalized = normalize(raw);
                    String key = labelOf(normalized);
                    boolean letterLabel = key != null;
                    if (!letterLabel) key = judgeKeyOf(normalized);
                    if (key != null) {
                        if (!bounds.isEmpty()) {
                            Rect row = expandToRow(node, bounds);
                            if (row != null && !row.isEmpty()) {
                                out.get(key).add(new Candidate(row, raw, clickable, depth));
                                // 判断题的「正确/错误」两行也塞进位置兜底的原料。
                                // 原先它们只进 out 不进 rows，等于只有一条路，
                                // 那条路一被过滤就彻底没戏。
                                if (!letterLabel && clickable
                                        && raw.length() <= MAX_ROW_TEXT
                                        && row.height() >= dp(MIN_ROW_HEIGHT_DP)) {
                                    rows.add(new Candidate(new Rect(row), raw, true, depth));
                                }
                            }
                        }
                    } else if (clickable && !bounds.isEmpty()
                            && raw.length() <= MAX_ROW_TEXT
                            && bounds.height() >= dp(MIN_ROW_HEIGHT_DP)) {
                        // 位置兜底的原料：可点击（或位于可点击容器内）且有文字的节点。
                        // 驾考宝典这类应用把 A/B/C/D 画成图片，文字里只有选项正文，
                        // 只能靠"一列等宽等距的行"这个几何特征把它们找出来。
                        rows.add(new Candidate(new Rect(bounds), raw, true, depth));
                    }
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                collect(node.getChild(i), depth + 1, clickable, out, rows, dump, counters);
            }
        } catch (Exception ignored) {
            // 节点可能在遍历过程中失效，跳过即可。
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", "").trim().toUpperCase(Locale.ROOT);
    }

    /** 该节点是否属于 QuadTap 自己（悬浮面板、校准层、标记层）。 */
    private boolean isOwnNode(AccessibilityNodeInfo node) {
        try {
            CharSequence pkg = node.getPackageName();
            return pkg != null && getPackageName().contentEquals(pkg);
        } catch (Exception e) {
            return false;
        }
    }

    /** 从控件文本里解析出选项字母，识别不出来返回 null。 */
    private static String labelOf(String text) {
        if (text == null || text.isEmpty()) return null;

        Matcher matcher = P_BARE.matcher(text);
        if (matcher.matches()) return matcher.group(1);

        matcher = P_PAREN.matcher(text);
        if (matcher.matches()) return matcher.group(1);

        matcher = P_CN.matcher(text);
        if (matcher.matches()) return matcher.group(1);

        matcher = P_PREFIX.matcher(text);
        if (matcher.matches() && matcher.group(2).length() <= MAX_OPTION_BODY) return matcher.group(1);

        return null;
    }

    /** 判断题按钮文本 -> 伪标签，识别不出来返回 null。 */
    private static String judgeKeyOf(String text) {
        if (text.equals("正确") || text.equals("对") || text.equals("是")) return KEY_TRUE;
        if (text.equals("错误") || text.equals("错") || text.equals("否")) return KEY_FALSE;
        return null;
    }

    /**
     * 尽量把点击目标扩大到可点击的选项行，这样点的是整行中心，
     * 而不是最左边那个字母。超过尺寸上限就退回控件自身范围，
     * 避免误选整个列表容器。
     */
    private Rect expandToRow(AccessibilityNodeInfo node, Rect fallback) {
        Point size = screenSize();
        // 同样放到整屏宽，否则满屏宽的选项行在第一轮就被 break 掉。
        int maxWidth = size.x;
        int maxHeight = dp(160);
        AccessibilityNodeInfo current = node;
        try {
            for (int i = 0; i < 4 && current != null; i++) {
                Rect bounds = new Rect();
                current.getBoundsInScreen(bounds);
                if (bounds.isEmpty()) break;
                if (bounds.width() > maxWidth || bounds.height() > maxHeight) break;
                if (current.isClickable()) return bounds;
                current = current.getParent();
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    private void pickBest(Map<String, List<Candidate>> found, List<Candidate> rows, boolean verbose) {
        Point size = screenSize();
        int source = TYPE_NONE;
        if (verbose) TapLog.add("--- 标签候选与评分 ---");

        for (String label : LABELS) {
            List<Candidate> list = found.get(label);
            if (verbose) {
                TapLog.add(label + ": " + (list == null ? 0 : list.size()) + " 个候选");
                if (list != null) {
                    for (Candidate candidate : list) {
                        TapLog.add("    " + rectToString(candidate.rect)
                                + (candidate.clickable ? " click" : "      ")
                                + " d=" + candidate.depth
                                + " score=" + scoreOf(candidate, size)
                                + " txt=\"" + shorten(candidate.text) + "\"");
                    }
                }
            }
            Candidate best = bestCandidate(list, size);
            if (best != null) {
                targetBounds.put(label, best.rect);
                targetText.put(label, best.text);
                source = TYPE_LABELS;
                if (verbose) TapLog.add("  -> 选中 " + label + " " + rectToString(best.rect));
            } else if (verbose && list != null && !list.isEmpty()) {
                TapLog.add("  -> 候选全部被尺寸过滤");
            }
        }

        // 判断题兜底：完全没有 A-D 字母时，用「正确/错误」补上 A/B。
        if (!targetBounds.containsKey("A") && !targetBounds.containsKey("B")) {
            Candidate yes = bestCandidate(found.get(KEY_TRUE), size);
            Candidate no = bestCandidate(found.get(KEY_FALSE), size);
            if (yes != null && no != null) {
                targetBounds.put("A", yes.rect);
                targetText.put("A", yes.text);
                targetBounds.put("B", no.rect);
                targetText.put("B", no.text);
                source = TYPE_JUDGE;
                if (verbose) TapLog.add("判断题兜底生效: 正确->A 错误->B");
            }
        }

        // 位置兜底：字母一个都没匹配上时启用。
        // 驾考宝典把 A/B/C/D 渲染成图片，文字节点里只有选项正文（如"左侧通行"），
        // 任何要求字母的正则都不可能命中，只能靠几何特征把这一列选项行找出来。
        if (targetBounds.size() < 2) {
            List<Candidate> column = findPositionalColumn(rows, size, verbose);
            if (column != null && column.size() >= 2) {
                targetBounds.clear();
                targetText.clear();
                StringBuilder detail = new StringBuilder();
                for (int i = 0; i < column.size() && i < LABELS.length; i++) {
                    Candidate candidate = column.get(i);
                    targetBounds.put(LABELS[i], candidate.rect);
                    targetText.put(LABELS[i], candidate.text);
                    detail.append(LABELS[i]).append("=\"").append(shorten(candidate.text)).append("\" ");
                }
                if (verbose) TapLog.add("位置兜底生效(" + column.size() + " 行): " + detail);
                source = TYPE_POSITIONAL;
            }
        }

        // 四个标签都找到时检查纵坐标顺序。
        // 这里只记录异常，绝不丢弃结果 —— 早先的版本一旦不满足就清空全部四个，
        // 结果 4 选项题目整体失效（2 选项时 size != 4 不触发，所以看起来还能用）。
        if (targetBounds.size() == LABELS.length) {
            int previous = Integer.MIN_VALUE;
            boolean ordered = true;
            StringBuilder order = new StringBuilder();
            for (String label : LABELS) {
                Rect rect = targetBounds.get(label);
                if (rect == null) { ordered = false; break; }
                if (rect.top < previous) ordered = false;
                previous = rect.top;
                order.append(label).append(".top=").append(rect.top).append(' ');
            }
            if (verbose) {
                TapLog.add("顺序检查: "
                        + (ordered ? "通过" : "不满足 A->D 递增(仅记录, 不再丢弃)")
                        + "   " + order);
            }
        }

        lastFoundCount = targetBounds.size();
        // 记「已确证到的最高序号」，而不是「命中个数」——
        // 只识别到 A、B 意味着 A、B 可用、C、D 可能越界需拒绝，
        // 但绝不把它解释成「这是判断题」。
        allowedLabelCount = highestLabelIndex(targetBounds);
        typeSource = source;
        if (verbose) {
            TapLog.add("扫描结果: 定位到 " + lastFoundCount + " 个标签，"
                    + "已确证到第 " + allowedLabelCount + " 项（来源="
                    + typeSourceName(typeSource) + "）");
        }
    }

    private int scoreOf(Candidate candidate, Point size) {
        Rect rect = candidate.rect;
        int score = 0;
        if (candidate.clickable) score += 1000;
        // 偏好紧凑的选项行，压制整屏容器。
        score += Math.max(0, 400 - rect.height());
        // 选项通常在题目下方。
        if (rect.top > size.y * 0.15f) score += 200;
        // 浅层节点更可能是选项本身。
        score -= candidate.depth;
        return score;
    }

    /** 在同一个标签的多个候选里挑最像"选项行"的那个。 */
    private Candidate bestCandidate(List<Candidate> candidates, Point size) {
        if (candidates == null || candidates.isEmpty()) return null;

        // 宽度上限放到整屏宽：真实题库的选项行常常是满屏宽（实测 1256/1256），
        // 早先用 90% 当上限，把整行选项全过滤掉了 —— 判断题因此完全失效。
        // 真正能挡住"整屏容器"的是高度上限和后面的评分。
        int maxWidth = size.x;
        int maxHeight = (int) (size.y * 0.4f);

        Candidate best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Candidate candidate : candidates) {
            Rect rect = candidate.rect;
            if (rect.width() > maxWidth || rect.height() > maxHeight) continue;
            if (rect.width() < dp(4) || rect.height() < dp(4)) continue;

            int score = scoreOf(candidate, size);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static String rectToString(Rect rect) {
        if (rect == null) return "null";
        return "[" + rect.left + "," + rect.top + "][" + rect.right + "," + rect.bottom + "]"
                + " " + rect.width() + "x" + rect.height();
    }

    /**
     * 按几何位置找出一列选项行。
     *
     * 适用场景：题库把 A/B/C/D 画成图片，无障碍树里的文字节点只有选项正文。
     * 这时选项行仍然有很稳定的几何特征 —— 左右边界相同、纵向等距、高度接近。
     * 按 (left, right) 在容差内聚类，再对每一簇的"等距程度 / 高度一致性 / 屏幕位置"打分，
     * 取最像选项列的那一簇，按从上到下依次对应 A、B、C、D。
     */
    private List<Candidate> findPositionalColumn(List<Candidate> rows, Point size, boolean verbose) {
        if (rows == null || rows.size() < 2) {
            if (verbose) TapLog.add("位置兜底: 可选行不足 2 个, 跳过");
            return null;
        }

        int tolerance = Math.max(dp(12), (int) (size.x * 0.03f));

        List<List<Candidate>> clusters = new ArrayList<>();
        for (Candidate candidate : rows) {
            List<Candidate> target = null;
            for (List<Candidate> cluster : clusters) {
                Rect anchor = cluster.get(0).rect;
                if (Math.abs(anchor.left - candidate.rect.left) <= tolerance
                        && Math.abs(anchor.right - candidate.rect.right) <= tolerance) {
                    target = cluster;
                    break;
                }
            }
            if (target == null) {
                target = new ArrayList<>();
                clusters.add(target);
            }
            target.add(candidate);
        }

        List<Candidate> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (List<Candidate> cluster : clusters) {
            if (cluster.size() < 2 || cluster.size() > LABELS.length) continue;
            Collections.sort(cluster, new Comparator<Candidate>() {
                @Override public int compare(Candidate a, Candidate b) {
                    return Integer.compare(a.rect.top, b.rect.top);
                }
            });
            double score = scoreColumn(cluster, size);
            if (verbose) {
                TapLog.add("  簇 " + cluster.size() + " 行 left=" + cluster.get(0).rect.left
                        + " right=" + cluster.get(0).rect.right
                        + " score=" + (int) score
                        + " 首行=\"" + shorten(cluster.get(0).text) + "\"");
            }
            if (score > bestScore) {
                bestScore = score;
                best = cluster;
            }
        }

        if (best == null && verbose) TapLog.add("位置兜底: 没有任何簇符合 2.." + LABELS.length + " 行");
        return best;
    }

    /** 给一列候选行打分：越像"一列等宽等距的选项"分越高。 */
    private double scoreColumn(List<Candidate> column, Point size) {
        double score = 0;

        // 4 行是最常见的单选题，2 行是判断题。
        if (column.size() == 4) score += 120;
        else if (column.size() == 2) score += 60;
        else score += 20;

        // 竖直间距应当均匀。
        double spacingSum = 0;
        for (int i = 1; i < column.size(); i++) {
            spacingSum += column.get(i).rect.top - column.get(i - 1).rect.top;
        }
        double spacingMean = spacingSum / (column.size() - 1);
        double spacingDeviation = 0;
        for (int i = 1; i < column.size(); i++) {
            spacingDeviation += Math.abs(
                    (column.get(i).rect.top - column.get(i - 1).rect.top) - spacingMean);
        }
        spacingDeviation /= (column.size() - 1);
        score += 80 * (1 - Math.min(1, spacingDeviation / Math.max(1, spacingMean)));

        // 高度应当接近。
        double heightSum = 0;
        for (Candidate candidate : column) heightSum += candidate.rect.height();
        double heightMean = heightSum / column.size();
        double heightDeviation = 0;
        for (Candidate candidate : column) {
            heightDeviation += Math.abs(candidate.rect.height() - heightMean);
        }
        heightDeviation /= column.size();
        score += 60 * (1 - Math.min(1, heightDeviation / Math.max(1, heightMean)));

        // 选项通常在屏幕中部偏下。
        double centerY = column.get(column.size() / 2).rect.centerY();
        score += 40.0 * centerY / Math.max(1, size.y);

        // 太窄的多半是标签而不是整行选项。
        if (column.get(0).rect.width() < size.x * 0.3f) score -= 40;

        // 行与行之间不该有巨大空隙（说明中间夹了无关内容）。
        double gapLimit = size.y * 0.25f;
        for (int i = 1; i < column.size(); i++) {
            if (column.get(i).rect.top - column.get(i - 1).rect.bottom > gapLimit) {
                score -= 60;
                break;
            }
        }
        return score;
    }

    // ---------------------------------------------------------------- OCR 回退

    @TargetApi(Build.VERSION_CODES.R)
    private void runOcrFallback(final String label) {
        long now = System.currentTimeMillis();
        if (now - lastShotAt < 400) {
            TapLog.add("OCR 放弃: 距上次截屏不足 400ms");
            toast("截屏太频繁，请稍候");
            return;
        }
        lastShotAt = now;

        // 本次 OCR 的代次：每一步回调都先比对它。
        // 只靠 assistRunning 不够 —— 停止后重新开启时它又会变回 true，
        // 旧任务会误认为自己是有效的。
        final int token = ++ocrToken;
        TapLog.add("进入 OCR 回退 (目标 " + label + "，代次 " + token + ")");
        busy = true;
        setOverlaysVisible(false);

        // 超时兜底：万一系统两个回调都不触发，busy 会永远卡住。
        // 只结束「当前仍然有效」的那一代任务，不会误伤新任务。
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (token != ocrToken) return;
                if (!busy) return;
                TapLog.add("OCR 超时兜底：强制结束代次 " + token);
                finishOcr(token);
                // 作废该代次，避免超时结束后迟到的回调又点一次
                if (token == ocrToken) ocrToken++;
            }
        }, OCR_TIMEOUT_MS);

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!isOcrTokenValid(token)) {
                    TapLog.add("OCR 代次已失效，取消本次截屏识别");
                    finishOcr(token);
                    return;
                }
                try {
                    takeScreenshot(Display.DEFAULT_DISPLAY, worker, new TakeScreenshotCallback() {
                        @Override public void onSuccess(ScreenshotResult result) {
                            // 这个回调体运行在 worker 线程，外层的 try/catch 只能包住
                            // takeScreenshot() 的同步调用。InputImage.fromBitmap /
                            // recognizer.process 抛异常时必须在这里接住，
                            // 否则 finishOcr 不执行 -> busy 永久为 true（C2）。
                            Bitmap image = null;
                            try {
                                if (!isOcrTokenValid(token)) {
                                    TapLog.add("OCR 代次已失效，丢弃截屏结果");
                                    finishOcr(token);
                                    return;
                                }
                                image = toSoftwareBitmap(result);
                                if (image == null) {
                                    TapLog.add("OCR 失败: 截图无法转成 ARGB_8888 软件位图");
                                    boolean current = isOcrTokenValid(token);
                                    finishOcr(token);
                                    if (current) toast("截图转换失败");
                                    return;
                                }
                                TapLog.add("截屏成功 " + image.getWidth() + "x" + image.getHeight()
                                        + " config=" + image.getConfig());
                                final Bitmap bitmap = image;
                                InputImage input = InputImage.fromBitmap(bitmap, 0);
                                recognizer.process(input)
                                        .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Text>() {
                                            @Override public void onSuccess(Text text) {
                                                Map<String, Rect> ocr;
                                                try {
                                                    ocr = findOcrTargets(text);
                                                } catch (Throwable t2) {
                                                    TapLog.add("OCR 结果解析异常: " + t2);
                                                    recycleQuietly(bitmap);
                                                    finishOcr(token);
                                                    return;
                                                }
                                                recycleQuietly(bitmap);
                                                if (!isOcrTokenValid(token)) {
                                                    TapLog.add("OCR 代次已失效，丢弃识别结果");
                                                    finishOcr(token);
                                                    return;
                                                }
                                                finishOcr(token);
                                                applyOcrResult(label, ocr, token);
                                            }
                                        })
                                        .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                                            @Override public void onFailure(Exception e) {
                                                TapLog.add("OCR 失败: " + e);
                                                recycleQuietly(bitmap);
                                                boolean current = isOcrTokenValid(token);
                                                finishOcr(token);
                                                if (current) toast("OCR 识别失败");
                                            }
                                        });
                            } catch (Throwable t) {
                                TapLog.add("OCR 回调异常: " + t);
                                recycleQuietly(image);
                                boolean current = isOcrTokenValid(token);
                                finishOcr(token);
                                // 作废仍在途的回调，避免异常之后又被点一次
                                if (token == ocrToken) ocrToken++;
                                if (current) toast("屏幕识别失败");
                            }
                        }

                        @Override public void onFailure(int errorCode) {
                            TapLog.add("截屏失败: " + describeScreenshotError(errorCode)
                                    + " (code=" + errorCode + ")");
                            boolean current = isOcrTokenValid(token);
                            finishOcr(token);
                            if (current) toast("无法读取屏幕：" + describeScreenshotError(errorCode));
                        }
                    });
                } catch (Throwable t) {
                    TapLog.add("截屏抛异常: " + t);
                    boolean current = isOcrTokenValid(token);
                    finishOcr(token);
                    if (current) toast("屏幕识别失败");
                }
            }
        }, 220);
    }

    /** 释放位图；已释放过就跳过，避免重复 recycle。 */
    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            if (!bitmap.isRecycled()) bitmap.recycle();
        } catch (Exception ignored) { }
    }

    /** 代次仍然有效且仍在运行，才允许继续处理这次 OCR 结果。 */
    private boolean isOcrTokenValid(int token) {
        return token == ocrToken && assistRunning;
    }

    /**
     * 截屏返回的是 HARDWARE 位图，ML Kit 需要 ARGB_8888 软件位图。
     * 必须先 copy 出软件位图，再关闭 HardwareBuffer。
     */
    @TargetApi(Build.VERSION_CODES.R)
    private Bitmap toSoftwareBitmap(ScreenshotResult result) {
        HardwareBuffer buffer = null;
        try {
            buffer = result.getHardwareBuffer();
            if (buffer == null) return null;
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) return null;
            return hardware.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable t) {
            return null;
        } finally {
            if (buffer != null) {
                try { buffer.close(); } catch (Exception ignored) { }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private String describeScreenshotError(int errorCode) {
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return "请求过于频繁";
        if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) return "无障碍权限已失效";
        if (errorCode == ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY) return "显示设备无效";
        return "系统返回错误码 " + errorCode;
    }

    /**
     * OCR 收尾。代次不符说明期间停止过、或已发起新一轮 OCR，
     * 此时不要复位别人的 busy，也不要把悬浮窗恢复出来。
     */
    private void finishOcr(int token) {
        if (token != ocrToken) {
            TapLog.add("OCR 代次 " + token + " 已过期，跳过收尾");
            return;
        }
        busy = false;
        handler.post(new Runnable() {
            @Override public void run() {
                // 停止后绝不重新挂出悬浮窗（H1）
                if (!assistRunning) return;
                setOverlaysVisible(true);
                // 识别期间可能切换了应用，这里统一补一次显示判定。
                applyPanelVisibility();
            }
        });
    }

    /**
     * 截屏前后收起/恢复悬浮窗 —— 自己的窗口会被一起截进图里。
     *
     * 这里用真正的 addView/removeView，而不是 setVisibility(INVISIBLE)：
     * 不可见的窗口依然存在于 WindowManager 上，依然会吃掉它覆盖区域的触摸事件。
     * 之前 OCR 那 1～2 秒里，面板位置下面的应用是点不动的。
     */
    private void setOverlaysVisible(boolean visible) {
        if (markLayer != null && !visible) markLayer.removeAllViews();
        if (panel == null || windowManager == null || panelParams == null) return;

        if (visible) {
            if (panelAttached) return;
            panel.setVisibility(View.VISIBLE);
            try {
                windowManager.addView(panel, panelParams);
                panelAttached = true;
            } catch (Exception ignored) { }
        } else {
            if (!panelAttached) return;
            try {
                windowManager.removeView(panel);
            } catch (Exception ignored) { }
            panelAttached = false;
        }
    }

    private void applyOcrResult(String requested, Map<String, Rect> ocr, int token) {
        if (!isOcrTokenValid(token)) {
            TapLog.add("OCR 代次已失效，丢弃结果 " + requested);
            return;
        }
        if (!assistRunning) {
            TapLog.add("已停止，丢弃本次 OCR 结果");
            return;
        }
        if (ocr.isEmpty()) {
            TapLog.add("OCR 结论: 没有一个标签被识别出来 -> 放弃");
            toast("未找到 " + requested + "，请长按该格取点");
            return;
        }

        // 题型状态更新规则（H3）：
        // 只有「当前还没有任何确证」且「OCR 至少认出 2 项」时才用 OCR 建立题型；
        // 已经有更可靠的节点判定时，OCR 的部分结果一律不得覆盖，
        // 更不能用 Math.max 把两者混合。
        if (allowedLabelCount <= 0 && ocr.size() >= 2) {
            allowedLabelCount = highestLabelIndex(ocr);
            typeSource = TYPE_OCR;
            TapLog.add("OCR 建立题型：已确证到第 " + allowedLabelCount + " 项");
        } else if (allowedLabelCount > 0) {
            TapLog.add("保留已有题型判定（已确证到第 " + allowedLabelCount
                    + " 项，来源=" + typeSourceName(typeSource) + "），OCR 结果不覆盖");
        } else {
            TapLog.add("OCR 只认出 " + ocr.size() + " 项，不足以确证题型，保守拒绝");
        }

        if (!isLabelAllowed(requested)) {
            toast("当前题型没有 " + requested + " 项，已忽略");
            return;
        }
        Rect rect = ocr.get(requested);
        if (rect != null) {
            TapLog.add("来源=OCR  中心=(" + rect.centerX() + "," + rect.centerY() + ")");
            tap(rect.centerX(), rect.centerY());
            toast("OCR 定位到 " + requested + "（共识别 " + ocr.size() + " 项）");
        } else {
            TapLog.add("OCR 结论: 没找到 " + requested + ", 只有 " + ocr.keySet());
            toast("OCR 只识别到 " + ocr.keySet() + "，未找到 " + requested + "，可长按该格取点");
        }
    }

    /** 收集所有选项锚点，再挑出最自洽的一组。 */
    private Map<String, Rect> findOcrTargets(Text text) {
        Map<String, List<Rect>> raw = new LinkedHashMap<>();
        for (String label : LABELS) raw.put(label, new ArrayList<Rect>());

        List<Rect> yes = new ArrayList<>();
        List<Rect> no = new ArrayList<>();
        int lineCount = 0;

        for (Text.TextBlock block : text.getTextBlocks()) {
            if (block == null) continue;
            for (Text.Line line : block.getLines()) {
                if (line == null) continue;
                Rect box = line.getBoundingBox();
                if (box == null || box.isEmpty()) continue;
                String normalized = normalize(line.getText());
                if (normalized.isEmpty()) continue;
                lineCount++;

                String label = labelOf(normalized);
                String shown = normalized.length() > 40 ? normalized.substring(0, 40) + "…" : normalized;

                if (label != null) {
                    raw.get(label).add(box);
                    TapLog.add("  OCR行[" + lineCount + "] " + rectToString(box)
                            + " -> " + label + "  \"" + shown + "\"");
                    continue;
                }
                if (normalized.equals("正确") || normalized.equals("对") || normalized.equals("是")) {
                    yes.add(box);
                    TapLog.add("  OCR行[" + lineCount + "] " + rectToString(box) + " -> 正确");
                } else if (normalized.equals("错误") || normalized.equals("错") || normalized.equals("否")) {
                    no.add(box);
                    TapLog.add("  OCR行[" + lineCount + "] " + rectToString(box) + " -> 错误");
                } else {
                    TapLog.add("  OCR行[" + lineCount + "] " + rectToString(box)
                            + " -> 无标签  \"" + shown + "\"");
                }
            }
        }
        TapLog.add("OCR 共 " + lineCount + " 行有效文本");

        // 判断题没有 A-D 字母，用"正确/错误"补上 A/B。
        if (raw.get("A").isEmpty() && raw.get("B").isEmpty()) {
            Rect topYes = topmost(yes);
            Rect topNo = topmost(no);
            if (topYes != null && topNo != null) {
                raw.get("A").add(topYes.top <= topNo.top ? topYes : topNo);
                raw.get("B").add(topYes.top <= topNo.top ? topNo : topYes);
            }
        }

        Point size = screenSize();
        return chooseConsistent(raw, size.y);
    }

    private static Rect topmost(List<Rect> rects) {
        Rect best = null;
        for (Rect rect : rects) if (best == null || rect.top < best.top) best = rect;
        return best;
    }

    /**
     * 候选组合评分。
     *
     * 选项在真实界面里几乎总是「上下排列、左边界对齐、高度接近」，
     * 所以这里穷举每个标签的前几个候选（最多 3^4 = 81 种），
     * 选出最符合这三个特征的一组，而不是随便挑一个。
     */
    private Map<String, Rect> chooseConsistent(Map<String, List<Rect>> raw, int screenHeight) {
        List<String> present = new ArrayList<>();
        List<List<Rect>> options = new ArrayList<>();

        for (String label : LABELS) {
            List<Rect> list = raw.get(label);
            if (list == null || list.isEmpty()) continue;
            List<Rect> sorted = new ArrayList<>(list);
            Collections.sort(sorted, new Comparator<Rect>() {
                @Override public int compare(Rect a, Rect b) { return Integer.compare(a.top, b.top); }
            });
            if (sorted.size() > MAX_CANDIDATES_PER_LABEL) {
                sorted = new ArrayList<>(sorted.subList(0, MAX_CANDIDATES_PER_LABEL));
            }
            present.add(label);
            options.add(sorted);
        }

        Map<String, Rect> result = new LinkedHashMap<>();
        if (present.isEmpty()) return result;

        int[] index = new int[options.size()];
        Map<String, Rect> best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        while (true) {
            Map<String, Rect> combination = new LinkedHashMap<>();
            for (int i = 0; i < options.size(); i++) {
                combination.put(present.get(i), options.get(i).get(index[i]));
            }
            double score = scoreCombination(combination, present, screenHeight);
            if (score > bestScore) {
                bestScore = score;
                best = combination;
            }
            int position = options.size() - 1;
            while (position >= 0) {
                index[position]++;
                if (index[position] < options.get(position).size()) break;
                index[position] = 0;
                position--;
            }
            if (position < 0) break;
        }

        if (best != null) result.putAll(best);
        TapLog.add("OCR 组合评分: 有候选的标签=" + present + " 最终选中 " + result.size() + " 个");
        for (Map.Entry<String, Rect> entry : result.entrySet()) {
            TapLog.add("  " + entry.getKey() + " " + rectToString(entry.getValue()));
        }
        return result;
    }

    private double scoreCombination(Map<String, Rect> combination, List<String> present, int screenHeight) {
        double score = 0;

        // 纵坐标应当 A -> D 递增。
        Rect previous = null;
        boolean ordered = true;
        for (String label : LABELS) {
            Rect rect = combination.get(label);
            if (rect == null) continue;
            if (previous != null && rect.top < previous.top) ordered = false;
            previous = rect;
        }
        if (ordered) score += 100;

        int count = present.size();
        if (count > 0) {
            double heightSum = 0;
            double leftSum = 0;
            for (String label : present) {
                Rect rect = combination.get(label);
                heightSum += rect.height();
                leftSum += rect.left;
            }
            double heightMean = heightSum / count;
            double leftMean = leftSum / count;
            double heightDeviation = 0;
            double leftDeviation = 0;
            for (String label : present) {
                Rect rect = combination.get(label);
                heightDeviation += Math.abs(rect.height() - heightMean);
                leftDeviation += Math.abs(rect.left - leftMean);
            }
            heightDeviation /= count;
            leftDeviation /= count;
            score += 60 * (1 - Math.min(1, heightDeviation / Math.max(1, heightMean)));
            score += 40 * (1 - Math.min(1, leftDeviation / Math.max(1, leftMean)));
        }

        for (String label : present) {
            Rect rect = combination.get(label);
            // 选项在题目下方，越靠下越可能是选项而不是题干里的字母。
            score += 30.0 * rect.centerY() / Math.max(1, screenHeight);
            // 过大的框很可能是整片内容区。
            if (rect.height() > screenHeight * 0.25) score -= 80;
        }

        return score;
    }

    // ---------------------------------------------------------------- 定位标记

    private void createMarkLayer() {
        if (markLayer != null || windowManager == null) return;
        markLayer = new FrameLayout(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(markLayer, params);
        } catch (Exception e) {
            markLayer = null;
        }
    }

    /** 短暂画出本次识别到的四个选项范围，让用户能确认映射是否正确。 */
    private void showMarks() {
        if (markLayer == null) return;
        markLayer.removeAllViews();
        for (String label : LABELS) {
            Rect rect = targetBounds.get(label);
            if (rect == null || rect.isEmpty()) continue;
            View box = new View(this);
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(0x223478F6);
            outline.setStroke(dp(2), 0xFF3478F6);
            outline.setCornerRadius(dp(6));
            box.setBackground(outline);
            FrameLayout.LayoutParams params =
                    new FrameLayout.LayoutParams(rect.width(), rect.height());
            params.leftMargin = rect.left;
            params.topMargin = rect.top;
            markLayer.addView(box, params);
        }
        handler.removeCallbacks(hideMarksRunnable);
        handler.postDelayed(hideMarksRunnable, 1800);
    }

    // ---------------------------------------------------------------- 工具方法

    private void tap(float x, float y) {
        // 最后一道关卡：停止后连已经走到这里的点击也不派发
        if (!assistRunning) {
            TapLog.add("已停止，忽略点击派发");
            return;
        }
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 80));
        try {
            boolean dispatched = dispatchGesture(builder.build(), null, null);
            if (dispatched) {
                // 只有真正派发成功才计一次辅助答题，失败不计
                prefs.setAnswerCount(prefs.answerCount() + 1);
                TapLog.add("辅助答题 +1，累计 " + prefs.answerCount() + " 次");
                // 点击成功后再观察目标应用的反馈，判定这题对不对（见 scheduleAnswerCheck）
                scheduleAnswerCheck(x, y);
            } else {
                TapLog.add("手势派发被系统拒绝，不计数");
            }
        } catch (Exception e) {
            toast("点击派发失败");
        }
    }

    /**
     * 点击派发成功后，延迟一会儿重扫题面，判断是否「答对自动跳题」。
     *
     * 关键：不自己判对错，只观察目标应用给出的反馈 ——
     * 题库答对会自动跳到下一题（题面文本随之变化）；答错停在原题（题面不变）。
     * 因此「题面指纹变化」≈ 答对。颜色识别是可选增强，默认关闭。
     */
    private void scheduleAnswerCheck(final float x, final float y) {
        // 点击前立刻抓题面指纹：此刻手势刚派发，目标应用还没判分，题面仍是原题。
        final String beforeFingerprint = questionFingerprint();
        // 题型快照：typeSource 在重扫后会被更新，必须先存下来。
        final boolean isJudge = (typeSource == TYPE_JUDGE);

        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!assistRunning) return;   // 期间停止了，不再统计
                String afterFingerprint = questionFingerprint();
                boolean advanced = !beforeFingerprint.isEmpty()
                        && !beforeFingerprint.equals(afterFingerprint);
                if (advanced) {
                    prefs.recordAnswer(isJudge, 1);
                    TapLog.add("统计: 题面已变化 -> 判定答对（"
                            + (isJudge ? "判断" : "单选") + "题）");
                } else if (prefs.colorCheck()) {
                    judgeByColor(isJudge, x, y);
                } else {
                    prefs.recordAnswer(isJudge, 0);
                    TapLog.add("统计: 题面未变化，颜色识别已关闭 -> 记未知（"
                            + (isJudge ? "判断" : "单选") + "题）");
                }
            }
        }, 1200);
    }

    /**
     * 抓当前活动窗口的「题面指纹」：把窗口内所有短文本按遍历顺序拼接。
     * 跳题后题面与选项文本都会变，指纹随之变化；答错时文本不变（只是颜色变红），
     * 指纹不变。单个文本截断到 120 字符，既覆盖长题干，又不被整段解析拖累。
     *
     * 已知局限：如果某个题库答错后会弹出带文字的解析面板（且题面不变），
     * 指纹也可能变化从而误判成答对 —— 这正是「颜色识别」开关想补的场景。
     */
    private String questionFingerprint() {
        StringBuilder sb = new StringBuilder();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) collectFingerprintText(root, 0, sb);
        } catch (Exception ignored) { }
        return sb.toString();
    }

    private void collectFingerprintText(AccessibilityNodeInfo node, int depth, StringBuilder sb) {
        if (node == null || depth > 40) return;
        try {
            if (isOwnNode(node)) return;
            CharSequence text = node.getText();
            if (text != null && text.length() > 0) {
                String raw = text.toString().trim();
                if (!raw.isEmpty()) {
                    String shown = raw.length() > 120 ? raw.substring(0, 120) : raw;
                    sb.append(shown).append('\n');
                }
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                collectFingerprintText(node.getChild(i), depth + 1, sb);
            }
        } catch (Exception ignored) {
            // 节点可能在遍历中失效，跳过即可。
        }
    }

    /**
     * 颜色识别判定：截图后在点击坐标附近采样，判断该选项变绿（对）还是变红（错）。
     * 慢速可选路径，开关默认关闭。色值阈值未经真机校准，仅供打开开关的用户试用。
     */
    @TargetApi(Build.VERSION_CODES.R)
    private void judgeByColor(final boolean isJudge, final float x, final float y) {
        TapLog.add("统计: 题面未变化，走颜色识别");
        // 截图会把悬浮面板一并截进去，先收起，结束后恢复。
        setOverlaysVisible(false);
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    Bitmap bitmap = null;
                    try {
                        bitmap = toSoftwareBitmap(result);
                        if (bitmap == null) {
                            TapLog.add("统计: 颜色识别截图转换失败，记未知");
                            prefs.recordAnswer(isJudge, 0);
                            return;
                        }
                        int verdict = sampleVerdict(bitmap, x, y);
                        recycleQuietly(bitmap);
                        prefs.recordAnswer(isJudge, verdict);
                        TapLog.add("统计: 颜色识别 -> "
                                + (verdict == 1 ? "答对" : verdict == -1 ? "答错" : "未知")
                                + "（" + (isJudge ? "判断" : "单选") + "题）");
                    } catch (Throwable t) {
                        TapLog.add("统计: 颜色识别异常 " + t);
                        recycleQuietly(bitmap);
                        prefs.recordAnswer(isJudge, 0);
                    } finally {
                        restoreOverlaysAfterColorCheck();
                    }
                }

                @Override public void onFailure(int errorCode) {
                    TapLog.add("统计: 颜色识别截屏失败 " + errorCode);
                    prefs.recordAnswer(isJudge, 0);
                    restoreOverlaysAfterColorCheck();
                }
            });
        } catch (Throwable t) {
            TapLog.add("统计: 颜色识别截屏抛异常 " + t);
            prefs.recordAnswer(isJudge, 0);
            restoreOverlaysAfterColorCheck();
        }
    }

    private void restoreOverlaysAfterColorCheck() {
        handler.post(new Runnable() {
            @Override public void run() {
                if (assistRunning) setOverlaysVisible(true);
            }
        });
    }

    /** 在 (x,y) 附近采样像素，判绿=1 / 红=-1 / 其他=0。 */
    private int sampleVerdict(Bitmap bitmap, float x, float y) {
        if (bitmap == null) return 0;
        int cx = (int) x;
        int cy = (int) y;
        int radius = Math.max(12, (int) (screenSize().y * 0.02f));
        int green = 0;
        int red = 0;
        int sampled = 0;
        for (int dy = -radius; dy <= radius; dy += 3) {
            for (int dx = -radius; dx <= radius; dx += 3) {
                int px = cx + dx;
                int py = cy + dy;
                if (px < 0 || py < 0 || px >= bitmap.getWidth() || py >= bitmap.getHeight()) continue;
                int color = bitmap.getPixel(px, py);
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;
                sampled++;
                if (g > r + 40 && g > b + 40) green++;
                else if (r > g + 40 && r > b + 40) red++;
            }
        }
        if (sampled == 0) return 0;
        if (green > sampled * 0.30f) return 1;
        if (red > sampled * 0.30f) return -1;
        return 0;
    }

    /** 缓存屏幕尺寸：拖动面板时每个 MOVE 事件都会用到，避免反复调用 getRealSize。 */
    private Point cachedScreenSize;

    private Point screenSize() {
        if (cachedScreenSize != null) return cachedScreenSize;
        Point point = new Point();
        try {
            Display display = windowManager != null ? windowManager.getDefaultDisplay() : null;
            if (display != null) display.getRealSize(point);
        } catch (Throwable ignored) { }
        if (point.x <= 0 || point.y <= 0) {
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            point.set(metrics.widthPixels, metrics.heightPixels);
        }
        cachedScreenSize = point;
        return point;
    }

    private void toast(final String message) {
        handler.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(QuadTapAccessibilityService.this, message, Toast.LENGTH_SHORT).show(); }
                catch (Exception ignored) { }
            }
        });
    }

    private static final class Candidate {
        final Rect rect;
        final String text;
        final boolean clickable;
        final int depth;

        Candidate(Rect rect, String text, boolean clickable, int depth) {
            this.rect = rect;
            this.text = text;
            this.clickable = clickable;
            this.depth = depth;
        }
    }
}
