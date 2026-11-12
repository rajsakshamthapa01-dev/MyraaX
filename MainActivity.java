package com.myraa.voice.agent;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity
 * Native Android host for MYRAA Voice AI Assistant.
 * - Injects secure AndroidNativeBridge as `window.Android`.
 * - Handles runtime permissions for Audio, Camera, Contacts, and Notifications.
 * - Directs users to Accessibility Settings to activate real phone automation.
 * - Supports hands-free WebView WebRTC media stream capture.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 2001;

    private WebView webView;
    private AndroidNativeBridge nativeBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        initWebView();
        checkAndRequestPermissions();
    }

    private void initWebView() {
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setMediaPlaybackRequiresUserGesture(false);
        webSettings.setAllowFileAccess(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setDatabaseEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Security: Prevent local file URL traversal
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);

        // Attach secure JavaScript Bridge
        nativeBridge = new AndroidNativeBridge(this, webView);
        webView.addJavascriptInterface(nativeBridge, "Android");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Automatically grant web microphone and camera permissions when app has runtime Android permissions
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        String[] resources = request.getResources();
                        List<String> granted = new ArrayList<>();
                        for (String r : resources) {
                            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)
                                        == PackageManager.PERMISSION_GRANTED) {
                                    granted.add(r);
                                }
                            } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                                        == PackageManager.PERMISSION_GRANTED) {
                                    granted.add(r);
                                }
                            }
                        }
                        if (!granted.isEmpty()) {
                            request.grant(granted.toArray(new String[0]));
                        } else {
                            request.deny();
                        }
                    }
                });
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                notifyWebBridgeReady();
            }
        });

        // Load MYRAA Application (Local assets or hosted preview URL)
        webView.loadUrl(getString(R.string.app_url));
    }

    private void checkAndRequestPermissions() {
        List<String> neededPermissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.READ_CONTACTS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            notifyWebBridgeReady();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyWebBridgeReady();
    }

    public void notifyWebBridgeReady() {
        if (webView != null) {
            boolean isAccessibilityActive = MyraaAccessibilityService.isServiceActive();
            String js = "if (window.__onAndroidNativeReady) { window.__onAndroidNativeReady({ native: true, accessibility: "
                    + isAccessibilityActive + ", apiLevel: " + Build.VERSION.SDK_INT + ", model: '" + Build.MODEL + "' }); }";
            webView.evaluateJavascript(js, null);
        }
    }

    public void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "Please tap 'MYRAA' and turn on Accessibility", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "Cannot launch accessibility settings", e);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
