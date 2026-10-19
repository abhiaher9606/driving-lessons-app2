package com.drivinglessons.drivelog;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * DriveLog v1.4
 *
 *  - App lock: phone fingerprint / PIN / pattern / password on open and after 30 s away
 *  - Finance tab unlock (same phone lock) via Android.authenticate()
 *  - Contact picker via Android.pickContact()   (no READ_CONTACTS permission needed)
 *  - Hardware / gesture BACK walks back through the app's own screens
 *  - Safe-area handling (status bar, camera cut-out, navigation bar, keyboard)
 *  - Backup to a file via Android.saveBackup()
 */
public class MainActivity extends Activity {

    private static final int FILECHOOSER_RESULTCODE = 1;
    private static final int BACKUP_FILE_RESULTCODE = 2;
    private static final int UNLOCK_APP_RESULTCODE = 3;
    private static final int UNLOCK_FINANCE_RESULTCODE = 4;
    private static final int PICK_CONTACT_RESULTCODE = 5;

    /** Ask for the phone lock again when the app was in the background longer than this. */
    private static final long RELOCK_AFTER_MS = 30000L;

    private static final int ASPHALT = 0xFF22262B;
    private static final int PAPER = 0xFFEEF0F2;
    private static final int ROAD_YELLOW = 0xFFF5B700;

    private FrameLayout root;
    private WebView webView;
    private LinearLayout lockOverlay;

    private ValueCallback<Uri[]> filePathCallback;

    // Stores backup JSON temporarily while Android's Save dialog is open
    private String pendingBackupJson = null;
    private String pendingBackupFileName = "driving_lessons_backup.json";

    // ---- lock state ----
    private boolean locked = true;            // the app always starts locked
    private boolean suppressRelock = false;   // true while WE opened a picker / save dialog / unlock screen
    private boolean autoPromptAllowed = true; // show the unlock screen automatically on next resume
    private long awayAt = 0;                  // when the app really went to the background

    // =====================================================================
    // CREATE
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        root.setBackgroundColor(ASPHALT);

        webView = new WebView(this);
        webView.setBackgroundColor(PAPER);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        lockOverlay = buildLockOverlay();
        root.addView(lockOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        setupInsets();
        setupWebView();

        webView.loadUrl("file:///android_asset/index.html");
    }

    // =====================================================================
    // SAFE AREA (status bar / camera cut-out / nav bar / keyboard)
    // =====================================================================

    private void setupInsets() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);

            // Light icons on our dark bars
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {

                int left = 0, top = 0, right = 0, bottom = 0;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                    // status bar + camera cut-out + navigation bar + on-screen keyboard
                    Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars()
                                    | WindowInsets.Type.displayCutout()
                                    | WindowInsets.Type.ime());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;

                    // Android 15+ enforces edge-to-edge. If the system reported nothing,
                    // fall back to the real status-bar height so the header never hides.
                    if (top == 0 && Build.VERSION.SDK_INT >= 35) {
                        top = statusBarHeightFallback();
                    }

                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }

                v.setPadding(left, top, right, bottom);
                return insets;
            }
        });

        root.post(new Runnable() {
            @Override
            public void run() {
                root.requestApplyInsets();
            }
        });
    }

    private int statusBarHeightFallback() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    // =====================================================================
    // WEBVIEW
    // =====================================================================

    private void setupWebView() {

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {

                String url = request.getUrl().toString();

                if (url.startsWith("http://")
                        || url.startsWith("https://")
                        || url.startsWith("file://")) {
                    return false;
                }

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                            "No app found to handle this link", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
        });

        // File picker (Restore)
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams fileChooserParams) {

                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = callback;

                if (!startSub(fileChooserParams.createIntent(), FILECHOOSER_RESULTCODE)) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this,
                            "Unable to open file picker", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                Toast.makeText(MainActivity.this,
                        "Please use the Backup button to save app data.",
                        Toast.LENGTH_SHORT).show());
    }

    // =====================================================================
    // JAVASCRIPT BRIDGE   (window.Android.*)
    // =====================================================================

    public class AndroidBridge {

        /** Android.saveBackup(fileName, jsonData) */
        @JavascriptInterface
        public void saveBackup(final String fileName, final String jsonData) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (jsonData == null || jsonData.trim().isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "No data available to backup", Toast.LENGTH_LONG).show();
                            return;
                        }

                        pendingBackupJson = jsonData;
                        pendingBackupFileName = (fileName == null || fileName.trim().isEmpty())
                                ? "driving_lessons_backup.json" : fileName;

                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("application/json");
                        intent.putExtra(Intent.EXTRA_TITLE, pendingBackupFileName);

                        if (!startSub(intent, BACKUP_FILE_RESULTCODE)) {
                            throw new Exception("Cannot open the save dialog");
                        }
                    } catch (Exception e) {
                        pendingBackupJson = null;
                        Toast.makeText(MainActivity.this,
                                "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }

        /** Android.pickContact()  ->  window.__contactPicked(name, number) */
        @JavascriptInterface
        public void pickContact() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_PICK,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
                    if (!startSub(i, PICK_CONTACT_RESULTCODE)) {
                        Toast.makeText(MainActivity.this,
                                "No contacts app found", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        /** Android.authenticate()  ->  window.__authResult(true|false)   (Finance tab) */
        @JavascriptInterface
        public void authenticate() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = credentialIntent("DriveLog – Finance",
                            "Confirm it's you to view payments");
                    if (i == null) {
                        Toast.makeText(MainActivity.this,
                                "Tip: set a screen lock (PIN / fingerprint) in phone Settings to protect payments.",
                                Toast.LENGTH_LONG).show();
                        callJs("window.__authResult && window.__authResult(true)");
                        return;
                    }
                    if (!startSub(i, UNLOCK_FINANCE_RESULTCODE)) {
                        callJs("window.__authResult && window.__authResult(false)");
                    }
                }
            });
        }

        /** Android.lockNow() */
        @JavascriptInterface
        public void lockNow() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    lockApp();
                    autoPromptAllowed = false;   // show the overlay; user taps Unlock
                }
            });
        }
    }

    private void callJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(js, null);
                }
            }
        });
    }

    // =====================================================================
    // APP LOCK
    // =====================================================================

    private LinearLayout buildLockOverlay() {

        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(ASPHALT);
        l.setClickable(true);          // swallow touches so nothing behind can be tapped
        l.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("DriveLog");
        title.setTextColor(ROAD_YELLOW);
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("Locked\nUse your fingerprint, PIN, pattern or password");
        sub.setTextColor(PAPER);
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 16, 0, 40);

        Button unlock = new Button(this);
        unlock.setText("Unlock");
        unlock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptUnlock();
            }
        });

        l.addView(title);
        l.addView(sub);
        l.addView(unlock);
        return l;
    }

    /** Phone-lock screen (fingerprint / PIN / pattern / password). Null if the phone has no lock. */
    private Intent credentialIntent(String title, String description) {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km == null || !km.isDeviceSecure()) {
            return null;
        }
        return km.createConfirmDeviceCredentialIntent(title, description);
    }

    private void promptUnlock() {
        Intent i = credentialIntent("DriveLog", "Unlock to open your students and payments");
        if (i == null) {
            // Phone has no screen lock set up – nothing to check against.
            Toast.makeText(this,
                    "Tip: set a screen lock (PIN / fingerprint) in phone Settings to protect DriveLog.",
                    Toast.LENGTH_LONG).show();
            unlockApp();
            return;
        }
        if (!startSub(i, UNLOCK_APP_RESULTCODE)) {
            Toast.makeText(this, "Cannot open the unlock screen", Toast.LENGTH_SHORT).show();
        }
    }

    private void unlockApp() {
        locked = false;
        awayAt = 0;
        lockOverlay.setVisibility(View.GONE);
    }

    private void lockApp() {
        locked = true;
        awayAt = 0;
        lockOverlay.setVisibility(View.VISIBLE);
        callJs("window.__onAppLocked && window.__onAppLocked()");
    }

    /** Starts another screen (picker / dialog / unlock) without triggering an app re-lock. */
    private boolean startSub(Intent intent, int code) {
        suppressRelock = true;
        try {
            startActivityForResult(intent, code);
            return true;
        } catch (Exception e) {
            suppressRelock = false;
            return false;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (suppressRelock) {
            return;
        }
        awayAt = SystemClock.elapsedRealtime();
        lockOverlay.setVisibility(View.VISIBLE);   // hides content in the recent-apps preview
        autoPromptAllowed = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (suppressRelock) {
            return;
        }

        if (!locked && awayAt != 0
                && SystemClock.elapsedRealtime() - awayAt > RELOCK_AFTER_MS) {
            lockApp();
        }
        awayAt = 0;

        if (locked) {
            lockOverlay.setVisibility(View.VISIBLE);
            if (autoPromptAllowed) {
                autoPromptAllowed = false;
                promptUnlock();
            }
        } else {
            lockOverlay.setVisibility(View.GONE);
        }
    }

    // =====================================================================
    // ACTIVITY RESULTS
    // =====================================================================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        suppressRelock = false;

        switch (requestCode) {

            case FILECHOOSER_RESULTCODE:
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(
                            WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                    filePathCallback = null;
                }
                break;

            case BACKUP_FILE_RESULTCODE:
                if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                    pendingBackupJson = null;
                    Toast.makeText(this, "Backup cancelled", Toast.LENGTH_SHORT).show();
                } else {
                    saveBackupToUri(data.getData());
                }
                break;

            case UNLOCK_APP_RESULTCODE:
                if (resultCode == RESULT_OK) {
                    unlockApp();
                }
                break;

            case UNLOCK_FINANCE_RESULTCODE:
                callJs("window.__authResult && window.__authResult("
                        + (resultCode == RESULT_OK ? "true" : "false") + ")");
                break;

            case PICK_CONTACT_RESULTCODE:
                if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                    readPickedContact(data.getData());
                }
                break;

            default:
                break;
        }
    }

    private void readPickedContact(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER},
                    null, null, null);

            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                String number = c.getString(1);
                callJs("window.__contactPicked && window.__contactPicked("
                        + JSONObject.quote(name == null ? "" : name) + ","
                        + JSONObject.quote(number == null ? "" : number) + ")");
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not read that contact", Toast.LENGTH_SHORT).show();
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    // =====================================================================
    // WRITE BACKUP JSON TO SELECTED FILE
    // =====================================================================

    private void saveBackupToUri(Uri uri) {

        if (pendingBackupJson == null) {
            Toast.makeText(this, "No backup data available", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            OutputStream outputStream = getContentResolver().openOutputStream(uri);
            if (outputStream == null) {
                throw new Exception("Unable to open selected file");
            }

            outputStream.write(pendingBackupJson.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            outputStream.close();

            pendingBackupJson = null;
            Toast.makeText(this, "Backup saved successfully", Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Toast.makeText(this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // =====================================================================
    // BACK BUTTON / GESTURE
    // =====================================================================

    @Override
    public void onBackPressed() {

        if (locked || webView == null) {
            super.onBackPressed();
            return;
        }

        String url = webView.getUrl();

        if (url != null && url.startsWith("file:///android_asset/")) {
            // Let the app go back one screen; exit only from the home screen.
            webView.evaluateJavascript(
                    "(function(){try{return window.__nativeBack?window.__nativeBack():false;}catch(e){return false;}})()",
                    new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (!"true".equals(value)) {
                                MainActivity.super.onBackPressed();
                            }
                        }
                    });
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // =====================================================================
    // CLEANUP
    // =====================================================================

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
