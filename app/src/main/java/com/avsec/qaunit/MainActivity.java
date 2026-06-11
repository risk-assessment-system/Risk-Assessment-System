package com.avsec.qaunit;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.PrintManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.SafeBrowsingResponse;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "avsec_settings";
    private static final String PREF_WEB_URL = "web_app_url";
    private static final int FILE_CHOOSER_REQUEST = 9101;
    private static final long AUTO_REFRESH_AFTER_BACKGROUND_MS = 60_000L;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> fileChooserCallback;
    private SharedPreferences preferences;
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private long backgroundAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        createMainLayout();
        configureWebView(webView);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else if (isConfigured()) {
            loadRemotePage();
        } else {
            showUrlSetupDialog(true);
        }
    }

    private void createMainLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(ProgressBar.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        ));

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        setContentView(root);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView(WebView target) {
        WebSettings settings = target.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WebView.startSafeBrowsing(this, ignored -> { });
        }

        target.addJavascriptInterface(new AndroidBridge(target), "AndroidBridge");
        target.setWebViewClient(createWebViewClient(target));
        target.setWebChromeClient(createWebChromeClient(target));
        target.setDownloadListener(createDownloadListener());
    }

    private WebViewClient createWebViewClient(WebView target) {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(ProgressBar.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(ProgressBar.GONE);
                injectAndroidHelpers(view);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showOfflinePage(view);
                }
            }

            @Override
            public void onSafeBrowsingHit(WebView view, WebResourceRequest request, int threatType, SafeBrowsingResponse callback) {
                callback.backToSafety(true);
                Toast.makeText(MainActivity.this, "Unsafe page blocked", Toast.LENGTH_LONG).show();
            }
        };
    }

    private WebChromeClient createWebChromeClient(WebView owner) {
        return new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? ProgressBar.GONE : ProgressBar.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;
                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileChooserCallback = null;
                    Toast.makeText(MainActivity.this, "No file picker found", Toast.LENGTH_LONG).show();
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                Dialog dialog = new Dialog(MainActivity.this);
                FrameLayout container = new FrameLayout(MainActivity.this);
                WebView popup = new WebView(MainActivity.this);
                configureWebView(popup);
                container.addView(popup, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                TextView close = new TextView(MainActivity.this);
                close.setText("  CLOSE  ");
                close.setTextSize(15);
                close.setGravity(Gravity.CENTER);
                close.setPadding(dp(10), dp(10), dp(10), dp(10));
                close.setBackgroundColor(0xDD08233F);
                close.setTextColor(0xFFFFFFFF);
                close.setOnClickListener(v -> {
                    popup.destroy();
                    dialog.dismiss();
                });
                FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.END
                );
                closeParams.setMargins(dp(8), dp(8), dp(8), dp(8));
                container.addView(close, closeParams);

                dialog.setContentView(container);
                dialog.setOnDismissListener(ignored -> popup.destroy());
                dialog.show();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                }

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        };
    }

    private DownloadListener createDownloadListener() {
        return (url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null) return;
            if (url.startsWith("blob:")) {
                Toast.makeText(this, "Preparing generated file...", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!url.startsWith("https://")) {
                Toast.makeText(this, "Only secure downloads are allowed", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                String name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setTitle(name);
                request.setDescription("Downloading AVSEC export");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                manager.enqueue(request);
                Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        };
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || "about".equalsIgnoreCase(scheme) || "data".equalsIgnoreCase(scheme) || "blob".equalsIgnoreCase(scheme)) {
            return false;
        }
        if ("https".equalsIgnoreCase(scheme) && isTrustedHost(uri.getHost())) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open link", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private boolean isTrustedHost(String host) {
        String configuredHost = Uri.parse(getConfiguredUrl()).getHost();
        return configuredHost != null && configuredHost.equalsIgnoreCase(host);
    }

    private void injectAndroidHelpers(WebView target) {
        String js = "javascript:(function(){" +
                "if(window.__avsecAndroidBridgeInstalled)return;" +
                "window.__avsecAndroidBridgeInstalled=true;" +
                "var originalPrint=window.print;" +
                "window.print=function(){try{AndroidBridge.printPage();}catch(e){if(originalPrint)originalPrint();}};" +
                "document.addEventListener('click',function(event){" +
                "var node=event.target;while(node&&node.tagName!=='A')node=node.parentElement;" +
                "if(!node||!node.href||node.href.indexOf('blob:')!==0)return;" +
                "event.preventDefault();event.stopPropagation();" +
                "fetch(node.href).then(function(r){return r.blob();}).then(function(blob){" +
                "var reader=new FileReader();reader.onloadend=function(){" +
                "var data=String(reader.result||'');var comma=data.indexOf(',');" +
                "AndroidBridge.saveBase64(node.download||'AVSEC_export.bin',blob.type||'application/octet-stream',comma>=0?data.substring(comma+1):data);" +
                "};reader.readAsDataURL(blob);" +
                "}).catch(function(err){console.error('Android blob export failed',err);});" +
                "},true);" +
                "})();";
        target.evaluateJavascript(js, null);
    }

    private void showOfflinePage(WebView target) {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<style>body{font-family:Arial;padding:28px;background:#f4f8fb;color:#08233f}.card{background:white;padding:22px;border-radius:16px;box-shadow:0 3px 15px #0002}button{padding:12px 18px;background:#0b3d91;color:white;border:0;border-radius:8px;font-weight:bold}</style>" +
                "</head><body><div class='card'><h2>AVSEC QA Unit</h2><p>The online workspace could not be loaded. Check the internet connection and retry.</p>" +
                "<button onclick='location.href=\"" + escapeJs(getConfiguredUrl()) + "\"'>Retry</button></div></body></html>";
        target.loadDataWithBaseURL(getConfiguredUrl(), html, "text/html", "UTF-8", null);
    }

    private String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isConfigured() {
        String url = getConfiguredUrl();
        return url.startsWith("https://") && !url.contains("YOUR_GITHUB_USERNAME") && !url.contains("YOUR_REPOSITORY_NAME");
    }

    private String getConfiguredUrl() {
        return preferences.getString(PREF_WEB_URL, BuildConfig.DEFAULT_WEB_APP_URL).trim();
    }

    private String withCacheBuster(String rawUrl) {
        Uri uri = Uri.parse(rawUrl);
        return uri.buildUpon().appendQueryParameter("_avsec", String.valueOf(System.currentTimeMillis())).build().toString();
    }

    private void loadRemotePage() {
        if (!isConfigured()) {
            showUrlSetupDialog(true);
            return;
        }
        webView.loadUrl(withCacheBuster(getConfiguredUrl()));
    }

    private void syncViewerData() {
        webView.evaluateJavascript(
                "(async function(){if(window.avsecAndroidSyncNow){return await window.avsecAndroidSyncNow();}return false;})()",
                result -> Toast.makeText(this, "Viewer data sync requested", Toast.LENGTH_SHORT).show()
        );
    }

    private void showUrlSetupDialog(boolean required) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://username.github.io/repository/");
        input.setText(isConfigured() ? getConfiguredUrl() : "");
        input.setSelectAllOnFocus(true);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Set GitHub Pages URL")
                .setMessage("Paste the HTTPS address of the deployed AVSEC web app. Example: https://username.github.io/repository/")
                .setView(input)
                .setPositiveButton("Save", null)
                .setNegativeButton(required ? "Close app" : "Cancel", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String url = input.getText().toString().trim();
                if (!url.startsWith("https://")) {
                    input.setError("Use an HTTPS GitHub Pages URL");
                    return;
                }
                if (!url.endsWith("/")) url += "/";
                preferences.edit().putString(PREF_WEB_URL, url).apply();
                dialog.dismiss();
                loadRemotePage();
            });
            if (required) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> finish());
            }
        });
        dialog.setCancelable(!required);
        dialog.show();
    }

    private void openInBrowser() {
        if (!isConfigured()) {
            showUrlSetupDialog(true);
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getConfiguredUrl())));
    }

    private void printWebView(WebView target) {
        runOnUiThread(() -> {
            PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String jobName = "AVSEC_QA_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            printManager.print(jobName, target.createPrintDocumentAdapter(jobName), null);
        });
    }

    private final class AndroidBridge {
        private final WebView owner;

        AndroidBridge(WebView owner) {
            this.owner = owner;
        }

        @JavascriptInterface
        public void printPage() {
            printWebView(owner);
        }

        @JavascriptInterface
        public void saveBase64(String fileName, String mimeType, String base64Payload) {
            ioExecutor.execute(() -> {
                try {
                    String safeName = sanitizeFileName(fileName);
                    byte[] bytes = Base64.decode(base64Payload, Base64.DEFAULT);
                    String resolvedMime = mimeType == null || mimeType.isBlank() ? guessMime(safeName) : mimeType;
                    String savedTo;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                        values.put(MediaStore.MediaColumns.MIME_TYPE, resolvedMime);
                        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AVSEC_QA");
                        Uri targetUri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (targetUri == null) throw new IllegalStateException("Could not create download file");
                        try (OutputStream stream = getContentResolver().openOutputStream(targetUri)) {
                            if (stream == null) throw new IllegalStateException("Could not open download file");
                            stream.write(bytes);
                        }
                        savedTo = "Downloads/AVSEC_QA/" + safeName;
                    } else {
                        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        if (dir == null) throw new IllegalStateException("Downloads folder unavailable");
                        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create Downloads folder");
                        File file = new File(dir, safeName);
                        try (OutputStream stream = new FileOutputStream(file)) {
                            stream.write(bytes);
                        }
                        savedTo = file.getAbsolutePath();
                    }
                    String finalSavedTo = savedTo;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Saved: " + finalSavedTo, Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    private String sanitizeFileName(String name) {
        String value = name == null ? "AVSEC_export.bin" : name.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        return value.isEmpty() ? "AVSEC_export.bin" : value;
    }

    private String guessMime(String fileName) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime == null ? URLConnection.guessContentTypeFromName(fileName) : mime;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add("Refresh web app").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add("Sync viewer data").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add("Print current page").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add("Open in browser").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add("Set web app URL").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        String title = item.getTitle().toString();
        switch (title) {
            case "Refresh web app":
                loadRemotePage();
                return true;
            case "Sync viewer data":
                syncViewerData();
                return true;
            case "Print current page":
                printWebView(webView);
                return true;
            case "Open in browser":
                openInBrowser();
                return true;
            case "Set web app URL":
                showUrlSetupDialog(false);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && fileChooserCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileChooserCallback.onReceiveValue(result);
            fileChooserCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        backgroundAt = System.currentTimeMillis();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (backgroundAt > 0 && System.currentTimeMillis() - backgroundAt > AUTO_REFRESH_AFTER_BACKGROUND_MS && isConfigured()) {
            loadRemotePage();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
