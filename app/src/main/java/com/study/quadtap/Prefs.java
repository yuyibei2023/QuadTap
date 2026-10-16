package com.study.quadtap;

import android.content.Context;
import android.content.SharedPreferences;

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
    private static final String[] LABELS = {"A", "B", "C", "D"};

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
}
