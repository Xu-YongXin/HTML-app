package com.dsh.htmlviewer;

import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 单 Activity 外壳：宿主页面是打包在 assets/www/index.html 里的纯前端界面，
 * 原生层只负责「调用系统浏览器打开」「保存到下载」「分享」「读剪贴板」这类网页做不了的事。
 */
public class MainActivity extends android.app.Activity {

    private static final String TAG = "HtmlPreviewer";
    private static final String UI_URL = "file:///android_asset/www/index.html";

    private WebView webView;
    private volatile boolean pageReady;
    private String pendingText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIntent(getIntent());
        webView.loadUrl(UI_URL);
    }

    private void buildUi() {
        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0B0F14);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                flushPending();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(this), "Android");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F14);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                queueText(text.toString());
            }
            return;
        }

        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            final Uri uri = intent.getData();
            new Thread(() -> {
                try {
                    ContentResolver cr = getContentResolver();
                    try (InputStream in = cr.openInputStream(uri)) {
                        if (in == null) return;
                        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        int n;
                        while ((n = in.read(chunk)) > 0) {
                            buf.write(chunk, 0, n);
                        }
                        queueText(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "open uri failed", t);
                }
            }, "read-uri").start();
        }
    }

    private synchronized void queueText(final String text) {
        if (text == null || text.trim().isEmpty()) return;
        pendingText = text;
        flushPending();
    }

    private synchronized void flushPending() {
        if (pendingText == null || !pageReady || webView == null) return;
        final String text = pendingText;
        pendingText = null;
        final String js = "window.__RECEIVE_SHARED__ && window.__RECEIVE_SHARED__("
                + JSONObject.quote(text) + ");void 0;";
        webView.post(() -> {
            try {
                webView.evaluateJavascript(js, null);
            } catch (Throwable t) {
                Log.w(TAG, "deliver failed", t);
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
