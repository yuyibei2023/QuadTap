package com.study.quadtap;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 轻量设置存储：悬浮面板位置、手动校准得到的选项坐标，以及各项开关。
 *
 * 校准坐标以「屏幕宽高的相对比例」保存，这样换机、旋转屏幕或改变分辨率后仍然可用。
 */
final class Prefs {
    private static final String NAME = "quadtap_settings";

    private static final String K_PANEL_X = "panel_x";
    private static final String K_PANEL_Y = "panel_y";
    private static final String K_USE_OCR = "use_ocr";
    private static final String K_SHOW_MARKS = "show_marks";
    private static final String K_MANUAL_ONLY = "manual_only";
    private static final String K_LEARNED = "learned_";
    private static final String K_ALLOWED = "allowed_packages";
    private static final String K_CELL_SIZE = "cell_size_dp";
    private static final String K_HIDE_ON_IME = "hide_on_ime";
    private static final String K_AUTO_UPDATE = "auto_check_update";
    private static final String K_IGNORED_VERSION = "ignored_version";
    private static final String K_THEME_MODE = "theme_mode";
    // 运行控制相关的独立新字段，不覆盖也不改变任何现有配置的键名与默认值
    private static final String K_ANSWER_COUNT = "answer_count";
    private static final String K_ASSIST_RUNNING = "assist_running";

    // ---- 答题统计：题型数量、对错、连续天数。独立字段，不引入 JSON 依赖 ----
    private static final String K_STAT_TOTAL = "stat_total";
    private static final String K_STAT_CORRECT = "stat_correct";
    private static final String K_STAT_WRONG = "stat_wrong";
    private static final String K_STAT_SINGLE = "stat_single";
    private static final String K_STAT_SINGLE_CORRECT = "stat_single_correct";
    private static final String K_STAT_JUDGE = "stat_judge";
    private static final String K_STAT_JUDGE_CORRECT = "stat_judge_correct";
    private static final String K_STAT_LAST_DAY = "stat_last_day";
    private static final String K_STAT_STREAK = "stat_streak";
    // 颜色识别判定对错（截图取色，较慢，默认关闭）
    private static final String K_COLOR_CHECK = "color_check";

    /** 主题：跟随系统 / 强制白天 / 强制黑夜。 */
    static final int THEME_SYSTEM = 0;
    static final int THEME_LIGHT = 1;
    static final int THEME_DARK = 2;
    private static final String[] LABELS = {"A", "B", "C", "D"};

    /** 方块边长（dp）的允许范围与默认值。 */
    static final int MIN_CELL_SIZE_DP = 34;
    static final int MAX_CELL_SIZE_DP = 76;
    static final int DEFAULT_CELL_SIZE_DP = 48;

    private final SharedPreferences sp;

    Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    int panelX(int fallback) { return sp.getInt(K_PANEL_X, fallback); }

    int panelY(int fallback) { return sp.getInt(K_PANEL_Y, fallback); }

    void setPanelPosition(int x, int y) {
        sp.edit().putInt(K_PANEL_X, x).putInt(K_PANEL_Y, y).apply();
    }

    /** 无障碍节点定位失败时，是否允许退回「截屏 + OCR」识别。 */
    boolean useOcr() { return sp.getBoolean(K_USE_OCR, true); }

    void setUseOcr(boolean value) { sp.edit().putBoolean(K_USE_OCR, value).apply(); }

    /** 点击悬浮格时，是否在屏幕上短暂画出识别到的选项范围。 */
    boolean showMarks() { return sp.getBoolean(K_SHOW_MARKS, true); }

    void setShowMarks(boolean value) { sp.edit().putBoolean(K_SHOW_MARKS, value).apply(); }

    /** 只使用手动校准的位置，完全跳过自动识别。 */
    boolean manualOnly() { return sp.getBoolean(K_MANUAL_ONLY, false); }

    void setManualOnly(boolean value) { sp.edit().putBoolean(K_MANUAL_ONLY, value).apply(); }

    /** 返回 {相对X, 相对Y}，未校准时返回 null。 */
    float[] learned(String label) {
        float rx = sp.getFloat(K_LEARNED + label + "_x", -1f);
        float ry = sp.getFloat(K_LEARNED + label + "_y", -1f);
        if (rx < 0f || ry < 0f || rx > 1f || ry > 1f) return null;
        return new float[]{rx, ry};
    }

    void setLearned(String label, float relativeX, float relativeY) {
        sp.edit()
                .putFloat(K_LEARNED + label + "_x", relativeX)
                .putFloat(K_LEARNED + label + "_y", relativeY)
                .apply();
    }

    void clearLearned() {
        SharedPreferences.Editor editor = sp.edit();
        for (String label : LABELS) {
            editor.remove(K_LEARNED + label + "_x").remove(K_LEARNED + label + "_y");
        }
        editor.apply();
    }

    int learnedCount() {
        int count = 0;
        for (String label : LABELS) if (learned(label) != null) count++;
        return count;
    }

    boolean anyLearned() { return learnedCount() > 0; }

    // ------------------------------------------------------------ 限定显示的应用

    /** 面板只在这些应用里显示。返回副本 —— getStringSet 的返回值不允许修改。 */
    Set<String> allowedPackages() {
        Set<String> stored = sp.getStringSet(K_ALLOWED, null);
        if (stored == null || stored.isEmpty()) return new LinkedHashSet<String>();
        return new LinkedHashSet<String>(stored);
    }

    void setAllowedPackages(Set<String> packages) {
        sp.edit().putStringSet(K_ALLOWED, new LinkedHashSet<String>(packages)).apply();
    }

    void clearAllowedPackages() { sp.edit().remove(K_ALLOWED).apply(); }

    int allowedCount() { return allowedPackages().size(); }

    /** 列表为空表示不限制（所有应用都显示）。 */
    boolean restrictingToApps() { return allowedCount() > 0; }

    /**
     * 当前前台应用是否允许显示面板。
     * 没选任何应用时一律允许；包名未知时也允许，避免误隐藏。
     */
    boolean isPackageAllowed(String packageName) {
        Set<String> allowed = allowedPackages();
        if (allowed.isEmpty()) return true;
        if (packageName == null) return true;
        return allowed.contains(packageName);
    }

    // ------------------------------------------------------------ 悬浮面板外观

    /** 单个方块边长（dp）。 */
    int cellSizeDp() {
        int value = sp.getInt(K_CELL_SIZE, DEFAULT_CELL_SIZE_DP);
        if (value < MIN_CELL_SIZE_DP) return MIN_CELL_SIZE_DP;
        if (value > MAX_CELL_SIZE_DP) return MAX_CELL_SIZE_DP;
        return value;
    }

    void setCellSizeDp(int dp) {
        sp.edit().putInt(K_CELL_SIZE, dp).apply();
    }

    /** 输入法弹出时是否隐藏面板。 */
    boolean hideOnIme() { return sp.getBoolean(K_HIDE_ON_IME, true); }

    void setHideOnIme(boolean value) { sp.edit().putBoolean(K_HIDE_ON_IME, value).apply(); }

    /**
     * 把悬浮面板的位置和大小恢复成默认值。
     * 清掉已保存的坐标后，下次重建面板会回落到贴右边缘的默认位置。
     */
    void resetPanelAppearance() {
        sp.edit().remove(K_PANEL_X).remove(K_PANEL_Y).remove(K_CELL_SIZE).apply();
    }

    // ------------------------------------------------------------ 应用内更新

    /** 打开应用时是否自动检查更新。 */
    boolean autoCheckUpdate() { return sp.getBoolean(K_AUTO_UPDATE, true); }

    void setAutoCheckUpdate(boolean value) { sp.edit().putBoolean(K_AUTO_UPDATE, value).apply(); }

    /** 用户点了「以后再说」的版本号，同一版本不再反复提示。 */
    String ignoredVersion() { return sp.getString(K_IGNORED_VERSION, null); }

    void setIgnoredVersion(String version) { sp.edit().putString(K_IGNORED_VERSION, version).apply(); }

    // ------------------------------------------------------------ 外观

    int themeMode() {
        int value = sp.getInt(K_THEME_MODE, THEME_SYSTEM);
        if (value < THEME_SYSTEM || value > THEME_DARK) return THEME_SYSTEM;
        return value;
    }

    void setThemeMode(int mode) { sp.edit().putInt(K_THEME_MODE, mode).apply(); }

    // ------------------------------------------------------------ 运行控制

    /** 累计成功辅助答题次数。只在真正派发成功一次点击后 +1。 */
    int answerCount() { return sp.getInt(K_ANSWER_COUNT, 0); }

    void setAnswerCount(int value) { sp.edit().putInt(K_ANSWER_COUNT, Math.max(0, value)).apply(); }

    /**
     * 用户期望的辅助功能开关状态。
     * 默认 true —— 保持原有「用户勾选无障碍后即可用」的行为，不改变既有体验。
     */
    boolean assistRunning() { return sp.getBoolean(K_ASSIST_RUNNING, true); }

    void setAssistRunning(boolean value) { sp.edit().putBoolean(K_ASSIST_RUNNING, value).apply(); }

    // ------------------------------------------------------------ 答题统计

    int statTotal() { return sp.getInt(K_STAT_TOTAL, 0); }

    int statCorrect() { return sp.getInt(K_STAT_CORRECT, 0); }

    int statWrong() { return sp.getInt(K_STAT_WRONG, 0); }

    int statSingle() { return sp.getInt(K_STAT_SINGLE, 0); }

    int statSingleCorrect() { return sp.getInt(K_STAT_SINGLE_CORRECT, 0); }

    int statJudge() { return sp.getInt(K_STAT_JUDGE, 0); }

    int statJudgeCorrect() { return sp.getInt(K_STAT_JUDGE_CORRECT, 0); }

    int statStreak() { return sp.getInt(K_STAT_STREAK, 0); }

    /** 是否用截图取色来判定答对/答错（较慢，默认关闭）。 */
    boolean colorCheck() { return sp.getBoolean(K_COLOR_CHECK, false); }

    void setColorCheck(boolean value) { sp.edit().putBoolean(K_COLOR_CHECK, value).apply(); }

    /**
     * 记录一次答题。
     *
     * @param isJudge 是否判断题（true=判断题，false=单选题）。题型来自识别时的 typeSource。
     * @param result  对错判定：1=正确、0=未知（未能判定）、-1=错误。
     *                不管对错，只要确实派发了一次点击，就计入总题数与对应题型数。
     */
    void recordAnswer(boolean isJudge, int result) {
        long today = LocalDate.now().toEpochDay();
        long lastDay = sp.getLong(K_STAT_LAST_DAY, -1L);
        int streak = statStreak();
        int newStreak;
        if (lastDay == today) {
            newStreak = streak;                       // 同一天多次答题，连续天数不变
        } else if (lastDay == today - 1) {
            newStreak = streak + 1;                   // 昨天也答过，连续
        } else {
            newStreak = 1;                            // 断档，从今天重新起算
        }

        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(K_STAT_TOTAL, statTotal() + 1);
        if (result == 1) editor.putInt(K_STAT_CORRECT, statCorrect() + 1);
        if (result == -1) editor.putInt(K_STAT_WRONG, statWrong() + 1);
        if (isJudge) {
            editor.putInt(K_STAT_JUDGE, statJudge() + 1);
            if (result == 1) editor.putInt(K_STAT_JUDGE_CORRECT, statJudgeCorrect() + 1);
        } else {
            editor.putInt(K_STAT_SINGLE, statSingle() + 1);
            if (result == 1) editor.putInt(K_STAT_SINGLE_CORRECT, statSingleCorrect() + 1);
        }
        editor.putLong(K_STAT_LAST_DAY, today);
        editor.putInt(K_STAT_STREAK, newStreak);
        editor.apply();
    }

    /** 清空全部答题统计，连续天数与日期一起复位。 */
    void resetStats() {
        sp.edit()
                .remove(K_STAT_TOTAL).remove(K_STAT_CORRECT).remove(K_STAT_WRONG)
                .remove(K_STAT_SINGLE).remove(K_STAT_SINGLE_CORRECT)
                .remove(K_STAT_JUDGE).remove(K_STAT_JUDGE_CORRECT)
                .remove(K_STAT_LAST_DAY).remove(K_STAT_STREAK)
                .apply();
    }
}
