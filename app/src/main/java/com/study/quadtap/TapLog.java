package com.study.quadtap;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 环形缓冲诊断日志。
 *
 * Activity 与 AccessibilityService 默认运行在同一个进程里，所以这个静态缓冲
 * 两边都能直接读写，不需要任何 IPC。
 */
final class TapLog {

    /** 保留的最大行数，防止长时间运行占满内存。 */
    private static final int MAX_LINES = 800;

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private TapLog() { }

    static synchronized void add(String message) {
        if (message == null) return;
        if (LINES.size() >= MAX_LINES) LINES.pollFirst();
        LINES.addLast(TIME.format(new Date()) + "  " + message);
    }

    static synchronized String dump() {
        StringBuilder builder = new StringBuilder();
        for (String line : LINES) builder.append(line).append('\n');
        return builder.toString();
    }

    static synchronized String tail(int maxLines) {
        StringBuilder builder = new StringBuilder();
        int skip = Math.max(0, LINES.size() - maxLines);
        int index = 0;
        for (String line : LINES) {
            if (index++ < skip) continue;
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    static synchronized void clear() { LINES.clear(); }

    static synchronized int size() { return LINES.size(); }

    static void copyToClipboard(Context context, String text, String label) {
        try {
            ClipboardManager manager =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) manager.setPrimaryClip(ClipData.newPlainText(label, text));
        } catch (Exception ignored) { }
    }

    /** 纯文本分享，不需要 FileProvider。 */
    static void share(Context context, String text) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "QuadTap 诊断日志");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            context.startActivity(Intent.createChooser(intent, "分享诊断日志"));
        } catch (Exception ignored) { }
    }
}
