package com.dsh.htmlviewer;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 暴露给网页（window.Android）的原生能力。
 * 所有方法都在 WebView 的 JavaBridge 线程上被调用，涉及 UI / 系统组件时必须切回主线程。
 */
public class AndroidBridge {

    private static final String TAG = "HtmlPreviewer";
    private static final long CLIPBOARD_TIMEOUT_MS = 900;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    AndroidBridge(Activity activity) {
        this.activity = activity;
    }

    /** 读取系统剪贴板文本（网页在 file:// 下无法直接读剪贴板）。 */
    @JavascriptInterface
    public String readClipboard() {
        final String[] result = {""};
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                ClipboardManager cm =
                        (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence cs = clip.getItemAt(0).coerceToText(activity);
                        if (cs != null) result[0] = cs.toString();
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "readClipboard failed", t);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(CLIPBOARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    /** 把 HTML 发布到 127.0.0.1 上的本地服务，再交给系统浏览器打开。 */
    @JavascriptInterface
    public void openInBrowser(final String title, final String html) {
        main.post(() -> {
            try {
                int port = LocalWebServer.get().publish(html);
                String url = "http://127.0.0.1:" + port + "/?v=" + System.currentTimeMillis();
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(Intent.createChooser(
                        intent, activity.getString(R.string.open_with_browser)));
            } catch (Throwable t) {
                Log.e(TAG, "openInBrowser failed", t);
                toast("打开失败：" + t.getClass().getSimpleName());
            }
        });
    }

    /** 保存为 .html 到系统「下载」目录；返回是否成功。 */
    @JavascriptInterface
    public boolean saveHtml(final String title, final String html) {
        String fileName = buildFileName(title);
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        try {
            String where = writeDownloads(fileName, data);
            toast(where == null ? activity.getString(R.string.saved_fail)
                    : activity.getString(R.string.saved_ok) + "：" + where);
            return where != null;
        } catch (Throwable t) {
            Log.e(TAG, "saveHtml failed", t);
            toast(activity.getString(R.string.saved_fail) + "：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 通过系统分享面板把 HTML 作为文件发出去（微信 / QQ / 网盘等）。 */
    @JavascriptInterface
    public void shareHtml(final String title, final String html) {
        main.post(() -> {
            try {
                File dir = new File(activity.getCacheDir(), "share");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
                File file = new File(dir, buildFileName(title));
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(html.getBytes(StandardCharsets.UTF_8));
                }
                Uri uri = new Uri.Builder()
                        .scheme("content")
                        .authority(activity.getPackageName() + ".share")
                        .appendPath(file.getName())
                        .build();
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/html");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_SUBJECT, title);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(Intent.createChooser(
                        send, activity.getString(R.string.share_title)));
            } catch (Throwable t) {
                Log.e(TAG, "shareHtml failed", t);
                toast("分享失败：" + t.getClass().getSimpleName());
            }
        });
    }

    @JavascriptInterface
    public String appVersion() {
        try {
            PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "1.0" : info.versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    @JavascriptInterface
    public void toast(final String message) {
        main.post(() -> {
            try {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // Activity 已销毁时忽略
            }
        });
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    private String writeDownloads(String fileName, byte[] data) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/html");
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/HtmlPreviewer");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri item = activity.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) throw new IOException("insert failed");
            try (OutputStream out = activity.getContentResolver().openOutputStream(item)) {
                if (out == null) throw new IOException("open failed");
                out.write(data);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            activity.getContentResolver().update(item, values, null, null);
            return "下载/HtmlPreviewer/" + fileName;
        }
        File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) dir = activity.getFilesDir();
        File outFile = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(outFile)) {
            out.write(data);
        }
        return outFile.getAbsolutePath();
    }

    private String buildFileName(String title) {
        String base = (title == null ? "" : title).trim();
        if (base.isEmpty()) base = "page";
        base = base.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
        if (base.length() > 40) base = base.substring(0, 40);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        return base + "-" + stamp + ".html";
    }
}
