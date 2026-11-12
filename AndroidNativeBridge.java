package com.myraa.voice.agent;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AndroidNativeBridge
 * Secure JavaScript Interface exposed to MYRAA WebView as `window.Android`.
 *
 * Implements real Android phone control:
 * 1. Dynamic app discovery & launch via PackageManager (YouTube, Instagram, WhatsApp, Settings, Camera, Calculator, etc.).
 * 2. Cross-app Accessibility control: Back, Home, Recent Apps, Scrolling, Swiping, Coordinate Tapping,
 *    Element Clicking, Long Pressing, Text Input (ACTION_SET_TEXT), Screen Content Reading.
 * 3. Notifications drawer, Quick Settings, Screen Locking, Screenshot.
 * 4. Safe Messaging workflow: Contact resolution via ContactsContract, message draft preparation,
 *    and strict confirmation check before automated actions.
 * 5. Hardware utilities: Camera Flashlight toggle, Phone Dialer, System Settings.
 * 6. Native capability & Accessibility status reporting.
 */
public class AndroidNativeBridge {

    private static final String TAG = "AndroidNativeBridge";

    private final Context context;
    private final WebView webView;
    private final CameraManager cameraManager;
    private final Handler mainHandler;
    private String cameraId;
    private boolean isTorchOn = false;

    // Strict whitelist of permitted native actions
    private static final Set<String> ALLOWED_ACTIONS = new HashSet<>(Arrays.asList(
        "open_app",
        "press_back",
        "press_home",
        "press_recents",
        "open_notifications",
        "open_quick_settings",
        "lock_screen",
        "take_screenshot",
        "scroll_up",
        "scroll_down",
        "scroll",
        "swipe",
        "tap_accessible_element",
        "click_node",
        "long_press_element",
        "long_press_coordinates",
        "tap_coordinates",
        "type_text",
        "click_and_type",
        "read_accessible_ui",
        "get_current_app",
        "resolve_contact",
        "send_message",
        "prepare_sms",
        "toggle_torch",
        "open_dialer",
        "open_settings",
        "open_accessibility_settings",
        "open_play_store",
        "stop_listening",
        "get_status"
    ));

    // Common app aliases for instantaneous lookups
    private static final Map<String, String> COMMON_ALIASES = new HashMap<>();
    static {
        COMMON_ALIASES.put("youtube", "com.google.android.youtube");
        COMMON_ALIASES.put("yt", "com.google.android.youtube");
        COMMON_ALIASES.put("instagram", "com.instagram.android");
        COMMON_ALIASES.put("insta", "com.instagram.android");
        COMMON_ALIASES.put("whatsapp", "com.whatsapp");
        COMMON_ALIASES.put("wa", "com.whatsapp");
        COMMON_ALIASES.put("chrome", "com.android.chrome");
        COMMON_ALIASES.put("browser", "com.android.chrome");
        COMMON_ALIASES.put("maps", "com.google.android.apps.maps");
        COMMON_ALIASES.put("google maps", "com.google.android.apps.maps");
        COMMON_ALIASES.put("camera", "com.android.camera");
        COMMON_ALIASES.put("settings", "com.android.settings");
        COMMON_ALIASES.put("calculator", "com.google.android.calculator");
        COMMON_ALIASES.put("spotify", "com.spotify.music");
        COMMON_ALIASES.put("telegram", "org.telegram.messenger");
        COMMON_ALIASES.put("gmail", "com.google.android.gm");
        COMMON_ALIASES.put("mail", "com.google.android.gm");
        COMMON_ALIASES.put("twitter", "com.twitter.android");
        COMMON_ALIASES.put("x", "com.twitter.android");
        COMMON_ALIASES.put("photos", "com.google.android.apps.photos");
        COMMON_ALIASES.put("gallery", "com.google.android.apps.photos");
    }

    public AndroidNativeBridge(Context context, WebView webView) {
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.cameraManager = (CameraManager) this.context.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cameraManager != null && cameraManager.getCameraIdList().length > 0) {
                this.cameraId = cameraManager.getCameraIdList()[0];
            }
        } catch (CameraAccessException e) {
            this.cameraId = null;
        }
    }

    /**
     * Primary entry point callable from JavaScript: `window.Android.executeCommand(jsonStr)`.
     */
    @JavascriptInterface
    public String executeCommand(String requestJson) {
        JSONObject response = new JSONObject();
        try {
            JSONObject request = new JSONObject(requestJson);
            String action = request.optString("action", "").toLowerCase(Locale.ROOT).trim();

            if (!ALLOWED_ACTIONS.contains(action)) {
                response.put("status", "ACTION_NOT_SUPPORTED");
                response.put("action", action);
                response.put("message", "Action '" + action + "' is not permitted by MYRAA Security Policy.");
                return response.toString();
            }

            switch (action) {
                case "open_app":
                    return handleOpenApp(request.optString("target", request.optString("appName", "")));

                case "press_back":
                    return handlePressBack();

                case "press_home":
                    return handlePressHome();

                case "press_recents":
                    return handlePressRecents();

                case "open_notifications":
                    return handleOpenNotifications();

                case "open_quick_settings":
                    return handleOpenQuickSettings();

                case "lock_screen":
                    return handleLockScreen();

                case "take_screenshot":
                    return handleTakeScreenshot();

                case "scroll_up":
                    return handleScroll(false);

                case "scroll_down":
                    return handleScroll(true);

                case "scroll":
                    boolean isDown = !"up".equalsIgnoreCase(request.optString("direction", "down"));
                    return handleScroll(isDown);

                case "swipe":
                    String dir = request.optString("direction", "");
                    if (!dir.isEmpty()) {
                        long dur = request.optLong("duration", 300);
                        return handleSwipeDirection(dir, dur);
                    } else {
                        float sX = (float) request.optDouble("startX", 0);
                        float sY = (float) request.optDouble("startY", 0);
                        float eX = (float) request.optDouble("endX", 0);
                        float eY = (float) request.optDouble("endY", 0);
                        long dur = request.optLong("duration", 300);
                        return handleSwipeCoordinates(sX, sY, eX, eY, dur);
                    }

                case "tap_accessible_element":
                case "click_node":
                    return handleTapElement(request.optString("target", request.optString("text", "")));

                case "long_press_element":
                    return handleLongPressElement(request.optString("target", request.optString("text", "")));

                case "long_press_coordinates":
                    float lpX = (float) request.optDouble("x", -1);
                    float lpY = (float) request.optDouble("y", -1);
                    return handleLongPressCoordinates(lpX, lpY);

                case "tap_coordinates":
                    float tapX = (float) request.optDouble("x", -1);
                    float tapY = (float) request.optDouble("y", -1);
                    return handleTapCoordinates(tapX, tapY);

                case "type_text":
                    return handleTypeText(request.optString("text", ""));

                case "click_and_type":
                    return handleClickAndType(request.optString("target", ""), request.optString("text", ""));

                case "read_accessible_ui":
                    return handleReadAccessibleUi();

                case "get_current_app":
                    return handleGetCurrentApp();

                case "resolve_contact":
                    return handleResolveContact(request.optString("name", ""));

                case "send_message":
                case "prepare_sms":
                    return handleMessageWorkflow(
                        request.optString("recipient", request.optString("number", "")),
                        request.optString("message", request.optString("text", "")),
                        request.optString("app", "whatsapp"),
                        request.optBoolean("confirmed", false)
                    );

                case "toggle_torch":
                    return handleToggleTorch();

                case "open_dialer":
                    return handleOpenDialer(request.optString("number", ""));

                case "open_settings":
                    return handleOpenSettings();

                case "open_accessibility_settings":
                    return handleOpenAccessibilitySettings();

                case "open_play_store":
                    return handleOpenPlayStore(request.optString("target", ""));

                case "stop_listening":
                    return handleStopListening();

                case "get_status":
                    return handleGetStatus();

                default:
                    response.put("status", "ACTION_NOT_SUPPORTED");
                    response.put("action", action);
                    response.put("message", "Action not implemented in native bridge.");
                    return response.toString();
            }

        } catch (JSONException e) {
            try {
                response.put("status", "ACTION_FAILED");
                response.put("message", "Invalid JSON request: " + e.getMessage());
            } catch (JSONException ignored) {}
            return response.toString();
        }
    }

    @JavascriptInterface
    public String getNativePlatformInfo() {
        JSONObject info = new JSONObject();
        try {
            info.put("platform", "Android");
            info.put("apiLevel", Build.VERSION.SDK_INT);
            info.put("release", Build.VERSION.RELEASE);
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("accessibilityActive", MyraaAccessibilityService.isServiceActive());
            info.put("torchAvailable", cameraId != null);
        } catch (Exception ignored) {}
        return info.toString();
    }

    @JavascriptInterface
    public boolean isAccessibilityEnabled() {
        return MyraaAccessibilityService.isServiceActive();
    }

    @JavascriptInterface
    public void openAccessibilitySettings() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } catch (Exception e) {
                    Log.w(TAG, "Cannot open accessibility settings", e);
                }
            }
        });
    }

    // ==========================================
    // ACTION HANDLERS
    // ==========================================

    private String handleOpenApp(String targetApp) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "open_app");
        res.put("target", targetApp);

        if (targetApp == null || targetApp.trim().isEmpty()) {
            res.put("status", "APP_NOT_FOUND");
            res.put("message", "No application name specified.");
            return res.toString();
        }

        String query = targetApp.toLowerCase(Locale.ROOT).trim();
        PackageManager pm = context.getPackageManager();

        // 1. Check known aliases
        if (COMMON_ALIASES.containsKey(query)) {
            String pkg = COMMON_ALIASES.get(query);
            Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                res.put("status", "APP_OPENED");
                res.put("packageName", pkg);
                res.put("appName", targetApp);
                res.put("message", "Opened " + targetApp + " on Android.");
                return res.toString();
            }
        }

        // 2. Dynamic query of all installed launcher applications
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> availableActivities = pm.queryIntentActivities(launcherIntent, 0);

        List<JSONObject> exactMatches = new ArrayList<>();
        List<JSONObject> partialMatches = new ArrayList<>();

        for (ResolveInfo ri : availableActivities) {
            String appName = ri.loadLabel(pm).toString();
            String packageName = ri.activityInfo.packageName;
            String lowerName = appName.toLowerCase(Locale.ROOT);

            if (lowerName.equals(query) || packageName.equalsIgnoreCase(query)) {
                JSONObject match = new JSONObject();
                match.put("name", appName);
                match.put("packageName", packageName);
                exactMatches.add(match);
            } else if (lowerName.contains(query) || query.contains(lowerName)) {
                JSONObject match = new JSONObject();
                match.put("name", appName);
                match.put("packageName", packageName);
                partialMatches.add(match);
            }
        }

        if (!exactMatches.isEmpty()) {
            JSONObject chosen = exactMatches.get(0);
            String pkg = chosen.getString("packageName");
            Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                res.put("status", "APP_OPENED");
                res.put("packageName", pkg);
                res.put("appName", chosen.getString("name"));
                res.put("message", "Opened " + chosen.getString("name") + ".");
                return res.toString();
            }
        }

        if (partialMatches.size() == 1) {
            JSONObject chosen = partialMatches.get(0);
            String pkg = chosen.getString("packageName");
            Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                res.put("status", "APP_OPENED");
                res.put("packageName", pkg);
                res.put("appName", chosen.getString("name"));
                res.put("message", "Opened " + chosen.getString("name") + ".");
                return res.toString();
            }
        }

        if (partialMatches.size() > 1) {
            JSONArray candidates = new JSONArray();
            for (int i = 0; i < Math.min(4, partialMatches.size()); i++) {
                candidates.put(partialMatches.get(i));
            }
            res.put("status", "AMBIGUOUS_MATCH");
            res.put("candidates", candidates);
            res.put("message", "Multiple matching apps found. Which one did you mean?");
            return res.toString();
        }

        // App not found: Provide clean Play Store recommendation
        res.put("status", "APP_NOT_FOUND");
        res.put("message", "Application '" + targetApp + "' is not installed on this device. Would you like to view it on Google Play Store?");
        res.put("playStoreQuery", targetApp);
        return res.toString();
    }

    private String handleOpenPlayStore(String targetApp) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "open_play_store");
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(targetApp)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            res.put("status", "SUCCESS");
            res.put("message", "Opened Google Play Store for '" + targetApp + "'.");
        } catch (Exception e) {
            Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(targetApp)));
            webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(webIntent);
            res.put("status", "SUCCESS");
            res.put("message", "Opened Play Store web page.");
        }
        return res.toString();
    }

    private String handlePressBack() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "press_back");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.performBack();
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Navigated back." : "Back navigation action failed.");
        return res.toString();
    }

    private String handlePressHome() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "press_home");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.performHome();
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Returned to Home Screen." : "Home action failed.");
        return res.toString();
    }

    private String handlePressRecents() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "press_recents");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.performRecents();
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Opened Recent Apps." : "Recents action failed.");
        return res.toString();
    }

    private String handleOpenNotifications() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "open_notifications");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.performNotifications();
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Opened Notification Drawer." : "Open notifications failed.");
        return res.toString();
    }

    private String handleOpenQuickSettings() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "open_quick_settings");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.performQuickSettings();
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Opened Quick Settings." : "Open quick settings failed.");
        return res.toString();
    }

    private String handleLockScreen() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "lock_screen");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.performLockScreen();
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Locked screen." : "Lock screen action requires Android 9+ (Pie) or higher.");
        return res.toString();
    }

    private String handleTakeScreenshot() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "take_screenshot");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.takeScreenshot();
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Screenshot captured." : "Screenshot action requires Android 9+ (Pie) or higher.");
        return res.toString();
    }

    private String handleScroll(boolean scrollDown) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", scrollDown ? "scroll_down" : "scroll_up");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.performScroll(scrollDown);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? (scrollDown ? "Scrolled down." : "Scrolled up.") : "Scroll action failed on current screen.");
        return res.toString();
    }

    private String handleSwipeDirection(String direction, long durationMs) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "swipe");
        res.put("direction", direction);

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.swipeDirection(direction, durationMs);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Swiped " + direction + "." : "Swipe gesture requires Android 7.0+ (Nougat) or higher.");
        return res.toString();
    }

    private String handleSwipeCoordinates(float startX, float startY, float endX, float endY, long durationMs) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "swipe");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.swipeGesture(startX, startY, endX, endY, durationMs);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Swipe gesture completed." : "Swipe gesture failed.");
        return res.toString();
    }

    private String handleTapElement(String query) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "tap_accessible_element");
        res.put("target", query);

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.clickElementByTextOrDescription(query);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Tapped '" + query + "'." : "Could not find clickable element matching '" + query + "'.");
        return res.toString();
    }

    private String handleLongPressElement(String query) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "long_press_element");
        res.put("target", query);

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.longPressElementByTextOrDescription(query);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Long-pressed '" + query + "'." : "Could not long-press element matching '" + query + "'.");
        return res.toString();
    }

    private String handleLongPressCoordinates(float x, float y) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "long_press_coordinates");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.longPressCoordinates(x, y);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Long-pressed coordinates." : "Long-press gesture requires Android 7.0+ (Nougat) or higher.");
        return res.toString();
    }

    private String handleTapCoordinates(float x, float y) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "tap_coordinates");
        res.put("x", x);
        res.put("y", y);

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.tapCoordinates(x, y);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Tapped coordinates (" + x + ", " + y + ")." : "Coordinate tap requires Android 7.0+ (Nougat) or higher.");
        return res.toString();
    }

    private String handleTypeText(String text) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "type_text");
        res.put("text", text);

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.inputText(text);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Typed text into field." : "No editable text field currently active on screen.");
        return res.toString();
    }

    private String handleClickAndType(String target, String text) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "click_and_type");
        res.put("target", target);
        res.put("text", text);

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        boolean ok = service.clickAndType(target, text);
        res.put("status", ok ? "SUCCESS" : "ACTION_FAILED");
        res.put("message", ok ? "Selected '" + target + "' and entered text." : "Could not select target input or type text.");
        return res.toString();
    }

    private String handleReadAccessibleUi() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "read_accessible_ui");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        JSONObject summary = service.getActiveScreenSummary();
        res.put("status", "SUCCESS");
        res.put("message", "Read screen details successfully.");
        res.put("data", summary);
        return res.toString();
    }

    private String handleGetCurrentApp() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "get_current_app");

        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();
        if (service == null) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "MYRAA Accessibility Service is disabled. Please enable it in Android Settings.");
            return res.toString();
        }

        String pkg = service.getCurrentPackageName();
        String act = service.getCurrentActivityName();

        res.put("status", "SUCCESS");
        res.put("packageName", pkg);
        res.put("activityName", act);
        res.put("message", pkg.isEmpty() ? "MYRAA Assistant active." : "Active app: " + pkg);
        return res.toString();
    }

    private String handleResolveContact(String query) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "resolve_contact");
        res.put("query", query);

        if (query == null || query.trim().isEmpty()) {
            res.put("status", "ACTION_FAILED");
            res.put("message", "Contact query name cannot be empty.");
            return res.toString();
        }

        List<JSONObject> matches = new ArrayList<>();
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query));
            String[] projection = new String[]{
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            };

            Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null) {
                while (cursor.moveToNext() && matches.size() < 5) {
                    String name = cursor.getString(0);
                    String number = cursor.getString(1);
                    JSONObject contact = new JSONObject();
                    contact.put("name", name);
                    contact.put("number", number);
                    matches.add(contact);
                }
                cursor.close();
            }
        } catch (SecurityException e) {
            res.put("status", "PERMISSION_REQUIRED");
            res.put("message", "Read Contacts permission is not granted. Please grant contacts permission in Settings.");
            return res.toString();
        }

        if (matches.isEmpty()) {
            res.put("status", "APP_NOT_FOUND");
            res.put("message", "No contacts found matching '" + query + "'.");
            return res.toString();
        }

        if (matches.size() == 1) {
            res.put("status", "SUCCESS");
            res.put("contact", matches.get(0));
            res.put("message", "Found contact: " + matches.get(0).getString("name") + " (" + matches.get(0).getString("number") + ")");
            return res.toString();
        }

        JSONArray arr = new JSONArray();
        for (JSONObject m : matches) arr.put(m);
        res.put("status", "AMBIGUOUS_MATCH");
        res.put("candidates", arr);
        res.put("message", "Found " + matches.size() + " contacts matching '" + query + "'. Please specify which one you'd like to contact.");
        return res.toString();
    }

    /**
     * Safe user-authorized messaging workflow for WhatsApp / SMS.
     * Enforces confirmation before automated sending.
     */
    private String handleMessageWorkflow(String recipient, String message, String app, boolean confirmed) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "send_message");
        res.put("recipient", recipient);
        res.put("messageText", message);
        res.put("app", app);

        if (recipient == null || recipient.trim().isEmpty()) {
            res.put("status", "ACTION_FAILED");
            res.put("message", "Recipient contact name or phone number is required.");
            return res.toString();
        }

        if (message == null || message.trim().isEmpty()) {
            res.put("status", "ACTION_FAILED");
            res.put("message", "Message text cannot be empty.");
            return res.toString();
        }

        // If not confirmed by the user yet, prepare draft and ask for confirmation
        if (!confirmed) {
            res.put("status", "CONFIRMATION_REQUIRED");
            res.put("message", "Please confirm: Send \"" + message + "\" to " + recipient + " via " + app + "?");
            return res.toString();
        }

        // Execution after explicit user confirmation
        if ("whatsapp".equalsIgnoreCase(app)) {
            try {
                // Open WhatsApp chat directly with prepopulated message
                String cleanNum = recipient.replaceAll("[^0-9]", "");
                Intent waIntent;
                if (!cleanNum.isEmpty()) {
                    waIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + cleanNum + "&text=" + Uri.encode(message)));
                } else {
                    waIntent = new Intent(Intent.ACTION_SEND);
                    waIntent.setType("text/plain");
                    waIntent.setPackage("com.whatsapp");
                    waIntent.putExtra(Intent.EXTRA_TEXT, message);
                }
                waIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(waIntent);

                res.put("status", "SUCCESS");
                res.put("message", "Opened WhatsApp conversation for " + recipient + " with your message.");
                return res.toString();
            } catch (Exception e) {
                res.put("status", "ACTION_FAILED");
                res.put("message", "WhatsApp is not installed or could not be opened.");
                return res.toString();
            }
        } else {
            // SMS Draft via Intent.ACTION_SENDTO
            try {
                Intent smsIntent = new Intent(Intent.ACTION_SENDTO);
                smsIntent.setData(Uri.parse("smsto:" + Uri.encode(recipient)));
                smsIntent.putExtra("sms_body", message);
                smsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(smsIntent);

                res.put("status", "SUCCESS");
                res.put("message", "Prepared SMS draft to " + recipient + ".");
                return res.toString();
            } catch (Exception e) {
                res.put("status", "ACTION_FAILED");
                res.put("message", "Could not prepare SMS: " + e.getMessage());
                return res.toString();
            }
        }
    }

    private String handleToggleTorch() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "toggle_torch");

        if (cameraManager == null || cameraId == null) {
            res.put("status", "ACTION_NOT_SUPPORTED");
            res.put("message", "Flashlight hardware is unavailable on this device.");
            return res.toString();
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isTorchOn = !isTorchOn;
                cameraManager.setTorchMode(cameraId, isTorchOn);
                res.put("status", "SUCCESS");
                res.put("torchActive", isTorchOn);
                res.put("message", isTorchOn ? "Flashlight turned on." : "Flashlight turned off.");
                return res.toString();
            } else {
                res.put("status", "ACTION_NOT_SUPPORTED");
                res.put("message", "Torch mode requires Android 6.0+.");
                return res.toString();
            }
        } catch (CameraAccessException e) {
            res.put("status", "ACTION_FAILED");
            res.put("message", "Camera access error: " + e.getMessage());
            return res.toString();
        }
    }

    private String handleOpenDialer(String number) throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "open_dialer");
        res.put("number", number);

        try {
            Intent intent;
            if (number != null && !number.trim().isEmpty()) {
                intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number.trim())));
            } else {
                intent = new Intent(Intent.ACTION_DIAL);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);

            res.put("status", "SUCCESS");
            res.put("message", number != null && !number.isEmpty() ? "Opened phone dialer with " + number + "." : "Opened phone dialer.");
        } catch (Exception e) {
            res.put("status", "ACTION_FAILED");
            res.put("message", "Could not open dialer: " + e.getMessage());
        }
        return res.toString();
    }

    private String handleOpenSettings() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "open_settings");
        try {
            Intent intent = new Intent(Settings.ACTION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            res.put("status", "SUCCESS");
            res.put("message", "Opened Android System Settings.");
        } catch (Exception e) {
            res.put("status", "ACTION_FAILED");
            res.put("message", "Could not open settings: " + e.getMessage());
        }
        return res.toString();
    }

    private String handleOpenAccessibilitySettings() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "open_accessibility_settings");
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            res.put("status", "SUCCESS");
            res.put("message", "Opened Accessibility Settings. Please enable 'MYRAA' to activate phone control.");
        } catch (Exception e) {
            res.put("status", "ACTION_FAILED");
            res.put("message", "Could not open accessibility settings: " + e.getMessage());
        }
        return res.toString();
    }

    private String handleStopListening() throws JSONException {
        JSONObject res = new JSONObject();
        res.put("action", "stop_listening");
        // Notify any foreground services to stop listening
        try {
            Intent stopIntent = new Intent(context, MyraaForegroundVoiceService.class);
            context.stopService(stopIntent);
        } catch (Exception ignored) {}

        res.put("status", "SUCCESS");
        res.put("message", "Myraa voice session stopped and background processing paused.");
        return res.toString();
    }

    private String handleGetStatus() throws JSONException {
        JSONObject res = new JSONObject();
        boolean active = MyraaAccessibilityService.isServiceActive();
        MyraaAccessibilityService service = MyraaAccessibilityService.getInstance();

        res.put("status", "SUCCESS");
        res.put("action", "get_status");
        res.put("native", true);
        res.put("accessibilityActive", active);
        res.put("currentPackage", service != null ? service.getCurrentPackageName() : "");
        res.put("torchAvailable", cameraId != null);
        res.put("apiLevel", Build.VERSION.SDK_INT);
        res.put("model", Build.MODEL);
        res.put("message", active
            ? "Native Android Bridge & Accessibility active. Full phone control ready."
            : "Native bridge active; MYRAA Accessibility Service needs activation in Android Settings.");
        return res.toString();
    }
}
