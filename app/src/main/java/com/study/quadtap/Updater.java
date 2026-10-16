package com.study.quadtap;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 应用内更新：查 GitHub Releases 最新版本 -> 下载 APK -> 交给系统安装器。
 *
 * 注意 Android 的限制：非系统应用无法静默安装，最后一步一定会弹系统确认框，
 * 而且需要用户给本应用开启「允许安装未知应用」。
 */
final class Updater {

    static final String REPO = "yuyibei2023/QuadTap";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** 一次检查得到的最新版本信息。 */
    static final class Release {
        final String tag;
        final String versionName;
        final String body;
        final String apkUrl;
        final long apkSize;

        Release(String tag, String versionName, String body, String apkUrl, long apkSize) {
            this.tag = tag;
            this.versionName = versionName;
            this.body = body;
            this.apkUrl = apkUrl;
            this.apkSize = apkSize;
        }

        String prettySize() {
            if (apkSize <= 0) return "未知大小";
            return String.format(java.util.Locale.US, "%.1f MB", apkSize / 1048576.0);
        }
    }

    interface CheckCallback {
        /** release 为 null 表示已是最新；error 非 null 表示检查失败。 */
        void onChecked(Release release, String error);
    }

    interface ProgressCallback {
        void onProgress(int percent, long downloaded, long total);
        void onFinished(File apk, String error);
    }

    private Updater() { }

    /** 下载取消标记。44MB 的包不给取消入口体验太差。 */
    private static volatile boolean cancelled = false;

    static void cancelDownload() { cancelled = true; }

    // ------------------------------------------------------------------ 版本号

    static String currentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "?" : info.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static long currentVersionCode(Context context) {
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 比较 "1.10" 与 "1.9" 这类版本号，a 更新返回正数。 */
    static int compareVersions(String a, String b) {
        if (a == null) a = "0";
        if (b == null) b = "0";
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int x = i < left.length ? parseNumber(left[i]) : 0;
            int y = i < right.length ? parseNumber(right[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ 检查更新

    static void check(final CheckCallback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                Release release = null;
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(LATEST_API).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(20000);
                    connection.setRequestProperty("Accept", "application/vnd.github+json");
                    // GitHub API 强制要求 User-Agent，缺了会返回 403
                    connection.setRequestProperty("User-Agent", "QuadTap-Android");
                    int code = connection.getResponseCode();
                    if (code != 200) {
                        error = "服务器返回 HTTP " + code
                                + (code == 403 ? "（可能是访问频率限制）" : "");
                    } else {
                        String json = readAll(connection.getInputStream());
                        release = parse(json);
                        if (release == null) error = "返回内容无法解析";
                    }
                } catch (Exception e) {
                    error = describeNetworkError(e);
                } finally {
                    if (connection != null) connection.disconnect();
                }
                final Release result = release;
                final String failure = error;
                main.post(new Runnable() {
                    @Override public void run() { callback.onChecked(result, failure); }
                });
            }
        }, "quadtap-update-check").start();
    }

    private static Release parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        String tag = root.optString("tag_name", "");
        if (tag.isEmpty()) return null;
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        String body = root.optString("body", "");

        String apkUrl = null;
        long apkSize = 0;
        JSONArray assets = root.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) continue;
            String name = asset.optString("name", "");
            if (name.endsWith(".apk")) {
                apkUrl = asset.optString("browser_download_url", null);
                apkSize = asset.optLong("size", 0);
                break;
            }
        }
        return new Release(tag, version, body, apkUrl, apkSize);
    }

    // ------------------------------------------------------------------ 下载

    static void download(final Context context, final Release release, final ProgressCallback callback) {
        cancelled = false;
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                File target = null;
                String error = null;
                HttpURLConnection connection = null;
                FileOutputStream output = null;
                try {
                    if (release.apkUrl == null || release.apkUrl.isEmpty()) {
                        throw new IllegalStateException("该版本没有附带 APK");
                    }
                    File dir = new File(context.getCacheDir(), "updates");
                    if (!dir.exists() && !dir.mkdirs()) {
                        throw new IllegalStateException("无法创建缓存目录");
                    }
                    target = new File(dir, "quadtap-" + release.versionName + ".apk");
                    if (target.exists() && !target.delete()) {
                        throw new IllegalStateException("无法覆盖旧的下载文件");
                    }

                    connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setRequestProperty("User-Agent", "QuadTap-Android");
                    // GitHub 的下载地址会 302 跳到 objects.githubusercontent.com
                    connection.setInstanceFollowRedirects(true);
                    int code = connection.getResponseCode();
                    if (code != 200) throw new IllegalStateException("下载地址返回 HTTP " + code);

                    long total = connection.getContentLengthLong();
                    if (total <= 0) total = release.apkSize;

                    InputStream input = new BufferedInputStream(connection.getInputStream());
                    output = new FileOutputStream(target);
                    byte[] buffer = new byte[16384];
                    long done = 0;
                    int lastPercent = -1;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (cancelled) throw new InterruptedException("已取消下载");
                        output.write(buffer, 0, read);
                        done += read;
                        if (total > 0) {
                            int percent = (int) (done * 100 / total);
                            if (percent != lastPercent) {
                                lastPercent = percent;
                                final int p = percent;
                                final long d = done;
                                final long t = total;
                                main.post(new Runnable() {
                                    @Override public void run() { callback.onProgress(p, d, t); }
                                });
                            }
                        }
                    }
                    output.flush();
                    input.close();
                } catch (Exception e) {
                    error = (e instanceof InterruptedException) ? "已取消" : describeNetworkError(e);
                    if (target != null && target.exists()) target.delete();
                    target = null;
                } finally {
                    try { if (output != null) output.close(); } catch (Exception ignored) { }
                    if (connection != null) connection.disconnect();
                }
                final File apk = target;
                final String failure = error;
                main.post(new Runnable() {
                    @Override public void run() { callback.onFinished(apk, failure); }
                });
            }
        }, "quadtap-update-download").start();
    }

    private static String readAll(InputStream input) throws Exception {
        BufferedInputStream buffered = new BufferedInputStream(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = buffered.read(buffer)) != -1) out.write(buffer, 0, read);
        buffered.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private static String describeNetworkError(Exception e) {
        String message = e.getMessage();
        if (message == null) message = e.getClass().getSimpleName();
        if (e instanceof java.net.UnknownHostException) {
            return "无法解析域名，请检查网络（" + message + "）";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "连接超时，GitHub 在当前网络下可能被阻断（" + message + "）";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "HTTPS 握手失败，可能被中间设备干扰（" + message + "）";
        }
        return message;
    }

    // ------------------------------------------------------------------ 安装

    /** 是否已经获得「允许安装未知应用」授权。 */
    static boolean canInstall(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return context.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** 跳到「允许安装未知应用」的设置页。 */
    static void openInstallPermissionSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
    }

    /** 把下载好的 APK 交给系统安装器。会弹出系统确认框。 */
    static String install(Context context, File apk) {
        try {
            String authority = context.getPackageName() + ".fileprovider";
            Uri uri = FileProvider.getUriForFile(context, authority, apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return null;
        } catch (Exception e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    static void openReleasePage(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }
}
