package com.drivinglessons.drivelog;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;

    private ValueCallback<Uri[]> filePathCallback;

    private static final int FILECHOOSER_RESULTCODE = 1;
    private static final int BACKUP_FILE_RESULTCODE = 2;

    // Stores backup JSON temporarily while Android's Save dialog is open
    private String pendingBackupJson = null;
    private String pendingBackupFileName = "driving_lessons_backup.json";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        // ---------------------------------------------------------
        // 1. SAFE AREA / STATUS BAR
        // ---------------------------------------------------------
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {

            webView.setOnApplyWindowInsetsListener(
                    new View.OnApplyWindowInsetsListener() {

                        @Override
                        public WindowInsets onApplyWindowInsets(
                                View v,
                                WindowInsets insets) {

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                v.setPadding(
                                        0,
                                        insets.getSystemWindowInsetTop(),
                                        0,
                                        0
                                );
                            }

                            return insets;
                        }
                    }
            );
        }

        // ---------------------------------------------------------
        // 2. WEBVIEW SETTINGS
        // ---------------------------------------------------------
        WebSettings webSettings = webView.getSettings();

        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);

        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);

        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        // IMPORTANT:
        // Required for localStorage data used by your app.
        webSettings.setDatabaseEnabled(true);

        // ---------------------------------------------------------
        // 3. JAVASCRIPT -> ANDROID BRIDGE
        // ---------------------------------------------------------
        webView.addJavascriptInterface(
                new AndroidBackupInterface(),
                "Android"
        );

        // ---------------------------------------------------------
        // 4. WEBVIEW CLIENT
        // ---------------------------------------------------------
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request) {

                String url = request.getUrl().toString();

                if (url.startsWith("http://")
                        || url.startsWith("https://")
                        || url.startsWith("file://")) {

                    return false;
                }

                try {

                    Intent intent = new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url)
                    );

                    startActivity(intent);
                    return true;

                } catch (Exception e) {

                    Toast.makeText(
                            MainActivity.this,
                            "No app found to handle this link",
                            Toast.LENGTH_SHORT
                    ).show();

                    return true;
                }
            }
        });

        // ---------------------------------------------------------
        // 5. FILE PICKER FOR RESTORE
        // ---------------------------------------------------------
        webView.setWebChromeClient(new WebChromeClient() {

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {

                if (MainActivity.this.filePathCallback != null) {

                    MainActivity.this.filePathCallback
                            .onReceiveValue(null);
                }

                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();

                try {

                    startActivityForResult(
                            intent,
                            FILECHOOSER_RESULTCODE
                    );

                } catch (Exception e) {

                    MainActivity.this.filePathCallback = null;

                    Toast.makeText(
                            MainActivity.this,
                            "Unable to open file picker",
                            Toast.LENGTH_SHORT
                    ).show();

                    return false;
                }

                return true;
            }
        });

        // ---------------------------------------------------------
        // 6. DOWNLOAD LISTENER
        // ---------------------------------------------------------
        // Keep this for normal HTTP/HTTPS downloads.
        //
        // IMPORTANT:
        // Backup JSON generated by index.html will NOT use this.
        // Backup will use Android.saveBackup() instead.
        // ---------------------------------------------------------

        webView.setDownloadListener(
                (url, userAgent, contentDisposition, mimetype, contentLength) -> {

                    Toast.makeText(
                            MainActivity.this,
                            "Please use the Backup button to save app data.",
                            Toast.LENGTH_SHORT
                    ).show();
                }
        );

        // ---------------------------------------------------------
        // 7. LOAD APP
        // ---------------------------------------------------------
        webView.loadUrl(
                "file:///android_asset/index.html"
        );
    }

    // ============================================================
    // JAVASCRIPT INTERFACE
    // ============================================================

    public class AndroidBackupInterface {

        /**
         * Called from index.html:
         *
         * Android.saveBackup(fileName, jsonData);
         */
        @JavascriptInterface
        public void saveBackup(
                String fileName,
                String jsonData) {

            runOnUiThread(() -> {

                try {

                    if (jsonData == null || jsonData.trim().isEmpty()) {

                        Toast.makeText(
                                MainActivity.this,
                                "No data available to backup",
                                Toast.LENGTH_LONG
                        ).show();

                        return;
                    }

                    pendingBackupJson = jsonData;

                    if (fileName == null
                            || fileName.trim().isEmpty()) {

                        pendingBackupFileName =
                                "driving_lessons_backup.json";

                    } else {

                        pendingBackupFileName = fileName;
                    }

                    // Android Save File dialog
                    Intent intent = new Intent(
                            Intent.ACTION_CREATE_DOCUMENT
                    );

                    intent.addCategory(
                            Intent.CATEGORY_OPENABLE
                    );

                    intent.setType(
                            "application/json"
                    );

                    intent.putExtra(
                            Intent.EXTRA_TITLE,
                            pendingBackupFileName
                    );

                    startActivityForResult(
                            intent,
                            BACKUP_FILE_RESULTCODE
                    );

                } catch (Exception e) {

                    pendingBackupJson = null;

                    Toast.makeText(
                            MainActivity.this,
                            "Backup failed: " + e.getMessage(),
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        }
    }

    // ============================================================
    // ACTIVITY RESULT
    // ============================================================

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        // --------------------------------------------------------
        // RESTORE FILE RESULT
        // --------------------------------------------------------
        if (requestCode == FILECHOOSER_RESULTCODE) {

            if (filePathCallback == null) {
                return;
            }

            filePathCallback.onReceiveValue(
                    WebChromeClient.FileChooserParams
                            .parseResult(resultCode, data)
            );

            filePathCallback = null;

            return;
        }

        // --------------------------------------------------------
        // BACKUP FILE RESULT
        // --------------------------------------------------------
        if (requestCode == BACKUP_FILE_RESULTCODE) {

            if (resultCode != RESULT_OK
                    || data == null
                    || data.getData() == null) {

                pendingBackupJson = null;

                Toast.makeText(
                        MainActivity.this,
                        "Backup cancelled",
                        Toast.LENGTH_SHORT
                ).show();

                return;
            }

            Uri uri = data.getData();

            saveBackupToUri(uri);

            return;
        }
    }

    // ============================================================
    // WRITE BACKUP JSON TO SELECTED FILE
    // ============================================================

    private void saveBackupToUri(Uri uri) {

        if (pendingBackupJson == null) {

            Toast.makeText(
                    MainActivity.this,
                    "No backup data available",
                    Toast.LENGTH_LONG
            ).show();

            return;
        }

        try {

            OutputStream outputStream =
                    getContentResolver().openOutputStream(uri);

            if (outputStream == null) {

                throw new Exception(
                        "Unable to open selected file"
                );
            }

            byte[] data =
                    pendingBackupJson.getBytes(
                            StandardCharsets.UTF_8
                    );

            outputStream.write(data);
            outputStream.flush();
            outputStream.close();

            pendingBackupJson = null;

            Toast.makeText(
                    MainActivity.this,
                    "Backup saved successfully",
                    Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {

            Toast.makeText(
                    MainActivity.this,
                    "Backup failed: " + e.getMessage(),
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    // ============================================================
    // HARDWARE BACK BUTTON
    // ============================================================

    @Override
    public void onBackPressed() {

        if (webView != null
                && webView.canGoBack()) {

            webView.goBack();

        } else {

            super.onBackPressed();
        }
    }

    // ============================================================
    // CLEANUP
    // ============================================================

    @Override
    protected void onDestroy() {

        if (webView != null) {

            webView.stopLoading();
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
