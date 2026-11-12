package com.myraa.voice.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MyraaAccessibilityService
 * Comprehensive native Android AccessibilityService for hands-free phone control:
 * - Real app UI inspection: reads screen text, finds buttons, identifies editable fields.
 * - Element interaction: click, long press, coordinate tap fallback, parent hierarchy traversal.
 * - System navigation: Back, Home, Recent Apps, Notifications Drawer, Quick Settings, Lock Screen, Screenshot.
 * - Gestures: scroll up, scroll down, swipe in any direction (left, right, up, down).
 * - Text entry: ACTION_SET_TEXT into editable fields, click-and-type.
 * - Active app tracking: records current window package and activity names.
 *
 * Compatible with Android 10 (API 29) through Android 14+ (API 34).
 */
public class MyraaAccessibilityService extends AccessibilityService {

    private static final String TAG = "MyraaAccessibility";
    private static volatile MyraaAccessibilityService instance = null;

    private String currentPackageName = "";
    private String currentActivityName = "";

    public static MyraaAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isServiceActive() {
        return instance != null;
    }

    public String getCurrentPackageName() {
        return currentPackageName != null ? currentPackageName : "";
    }

    public String getCurrentActivityName() {
        return currentActivityName != null ? currentActivityName : "";
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "MyraaAccessibilityService connected and ready.");

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg != null && pkg.length() > 0) {
            currentPackageName = pkg.toString();
        }

        CharSequence cls = event.getClassName();
        if (cls != null && cls.length() > 0) {
            currentActivityName = cls.toString();
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "MyraaAccessibilityService interrupted by system.");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        Log.i(TAG, "MyraaAccessibilityService unbinding.");
        instance = null;
        return super.onUnbind(intent);
    }

    // ==========================================
    // 1. SYSTEM NAVIGATION ACTIONS
    // ==========================================

    public boolean performBack() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    public boolean performHome() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    public boolean performRecents() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
    }

    public boolean performNotifications() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
    }

    public boolean performQuickSettings() {
        return performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
    }

    public boolean performLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    public boolean takeScreenshot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT);
        }
        return false;
    }

    // ==========================================
    // 2. TOUCH GESTURES (API 24+)
    // ==========================================

    public boolean tapCoordinates(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path clickPath = new Path();
            clickPath.moveTo(x, y);
            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
            gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 50));
            return dispatchGesture(gestureBuilder.build(), null, null);
        }
        return false;
    }

    public boolean longPressCoordinates(float x, float y) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path clickPath = new Path();
            clickPath.moveTo(x, y);
            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
            // Long press requires 800ms touch duration
            gestureBuilder.addStroke(new GestureDescription.StrokeDescription(clickPath, 0, 800));
            return dispatchGesture(gestureBuilder.build(), null, null);
        }
        return false;
    }

    public boolean swipeGesture(float startX, float startY, float endX, float endY, long durationMs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Path swipePath = new Path();
            swipePath.moveTo(startX, startY);
            swipePath.lineTo(endX, endY);
            GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
            long dur = Math.max(100, Math.min(durationMs, 2000));
            gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, dur));
            return dispatchGesture(gestureBuilder.build(), null, null);
        }
        return false;
    }

    public boolean swipeDirection(String direction, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float width = dm.widthPixels;
        float height = dm.heightPixels;
        float midX = width / 2.0f;
        float midY = height / 2.0f;

        String dir = direction != null ? direction.toLowerCase(Locale.ROOT).trim() : "down";
        long dur = durationMs > 0 ? durationMs : 300;

        switch (dir) {
            case "left":
                return swipeGesture(width * 0.85f, midY, width * 0.15f, midY, dur);
            case "right":
                return swipeGesture(width * 0.15f, midY, width * 0.85f, midY, dur);
            case "up":
                return swipeGesture(midX, height * 0.75f, midX, height * 0.25f, dur);
            case "down":
            default:
                return swipeGesture(midX, height * 0.25f, midX, height * 0.75f, dur);
        }
    }

    public boolean performScroll(boolean scrollDown) {
        AccessibilityNodeInfo root = safeGetRoot();
        if (root != null) {
            AccessibilityNodeInfo scrollable = findScrollableNode(root);
            if (scrollable != null) {
                int action = scrollDown
                        ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                boolean res = scrollable.performAction(action);
                if (res) return true;
            }
        }

        // Gesture Fallback: Swipe vertically across center of screen
        return swipeDirection(scrollDown ? "up" : "down", 350);
    }

    // ==========================================
    // 3. ELEMENT SELECTION & INTERACTION
    // ==========================================

    public boolean clickElementByTextOrDescription(String query) {
        AccessibilityNodeInfo root = safeGetRoot();
        if (root == null || query == null || query.trim().isEmpty()) return false;

        String cleanQuery = query.trim();
        String lowerQuery = cleanQuery.toLowerCase(Locale.ROOT);

        // 1. Search by view ID if formatted as resource id
        if (cleanQuery.contains(":id/") || cleanQuery.contains("/")) {
            List<AccessibilityNodeInfo> idNodes = root.findAccessibilityNodeInfosByViewId(cleanQuery);
            if (idNodes != null && !idNodes.isEmpty()) {
                for (AccessibilityNodeInfo node : idNodes) {
                    if (clickNodeResilient(node)) return true;
                }
            }
        }

        // 2. Search by exact or substring text
        List<AccessibilityNodeInfo> textNodes = root.findAccessibilityNodeInfosByText(cleanQuery);
        if (textNodes != null && !textNodes.isEmpty()) {
            for (AccessibilityNodeInfo node : textNodes) {
                if (clickNodeResilient(node)) return true;
            }
        }

        // 3. Deep hierarchy scan for text or contentDescription
        AccessibilityNodeInfo matched = findMatchingNode(root, lowerQuery);
        if (matched != null) {
            return clickNodeResilient(matched);
        }

        return false;
    }

    public boolean longPressElementByTextOrDescription(String query) {
        AccessibilityNodeInfo root = safeGetRoot();
        if (root == null || query == null || query.trim().isEmpty()) return false;

        String cleanQuery = query.trim();
        String lowerQuery = cleanQuery.toLowerCase(Locale.ROOT);

        AccessibilityNodeInfo matched = findMatchingNode(root, lowerQuery);
        if (matched != null) {
            if (matched.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return true;
            }
            Rect bounds = new Rect();
            matched.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) {
                return longPressCoordinates(bounds.centerX(), bounds.centerY());
            }
        }
        return false;
    }

    private boolean clickNodeResilient(AccessibilityNodeInfo node) {
        if (node == null) return false;

        // 1. Direct click
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }

        // 2. Traverse up parent hierarchy (up to 4 levels)
        AccessibilityNodeInfo current = node.getParent();
        int depth = 0;
        while (current != null && depth < 4) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
            depth++;
        }

        // 3. Fallback: Dispatch physical coordinate tap onto center of view bounds
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty() && bounds.width() > 0 && bounds.height() > 0) {
            return tapCoordinates(bounds.centerX(), bounds.centerY());
        }

        return false;
    }

    private AccessibilityNodeInfo findMatchingNode(AccessibilityNodeInfo node, String lowerQuery) {
        if (node == null) return null;

        CharSequence text = node.getText();
        if (text != null && text.toString().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return node;
        }

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
            return node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = findMatchingNode(node.getChild(i), lowerQuery);
            if (match != null) return match;
        }

        return null;
    }

    // ==========================================
    // 4. TEXT ENTRY (ACTION_SET_TEXT)
    // ==========================================

    public boolean inputText(String text) {
        AccessibilityNodeInfo root = safeGetRoot();
        if (root == null || text == null) return false;

        // 1. Check currently focused input
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null && focused.isEditable()) {
            return setTextOnNode(focused, text);
        }

        // 2. Find first editable node on screen
        AccessibilityNodeInfo editable = findFirstEditableNode(root);
        if (editable != null) {
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return setTextOnNode(editable, text);
        }

        return false;
    }

    public boolean clickAndType(String targetQuery, String text) {
        if (clickElementByTextOrDescription(targetQuery)) {
            try {
                Thread.sleep(350);
            } catch (InterruptedException ignored) {}
            return inputText(text);
        }
        return false;
    }

    private boolean setTextOnNode(AccessibilityNodeInfo node, String text) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
    }

    private AccessibilityNodeInfo findFirstEditableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFirstEditableNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    // ==========================================
    // 5. ACCESSIBLE SCREEN READING & AWARENESS
    // ==========================================

    public String readScreenContent() {
        AccessibilityNodeInfo root = safeGetRoot();
        if (root == null) return "[]";

        JSONArray contentArray = new JSONArray();
        collectNodeDetails(root, contentArray);
        return contentArray.toString();
    }

    public JSONObject getActiveScreenSummary() {
        JSONObject summary = new JSONObject();
        try {
            summary.put("activePackage", getCurrentPackageName());
            summary.put("activeActivity", getCurrentActivityName());

            AccessibilityNodeInfo root = safeGetRoot();
            if (root == null) {
                summary.put("elementsFound", 0);
                summary.put("titles", new JSONArray());
                return summary;
            }

            List<String> textSnippets = new ArrayList<>();
            List<String> clickableTitles = new ArrayList<>();
            scanScreenTitles(root, textSnippets, clickableTitles);

            JSONArray textsArr = new JSONArray();
            for (int i = 0; i < Math.min(10, textSnippets.size()); i++) {
                textsArr.put(textSnippets.get(i));
            }

            JSONArray clickArr = new JSONArray();
            for (int i = 0; i < Math.min(10, clickableTitles.size()); i++) {
                clickArr.put(clickableTitles.get(i));
            }

            summary.put("visibleTexts", textsArr);
            summary.put("clickableButtons", clickArr);
            summary.put("elementsFound", textSnippets.size() + clickableTitles.size());

        } catch (Exception e) {
            Log.w(TAG, "Screen summary scan error:", e);
        }
        return summary;
    }

    private void scanScreenTitles(AccessibilityNodeInfo node, List<String> texts, List<String> buttons) {
        if (node == null) return;

        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();

        if (txt != null && txt.length() > 0) {
            String clean = txt.toString().trim();
            if (node.isClickable()) {
                buttons.add(clean);
            } else {
                texts.add(clean);
            }
        } else if (desc != null && desc.length() > 0) {
            String clean = desc.toString().trim();
            if (node.isClickable()) {
                buttons.add(clean);
            } else {
                texts.add(clean);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            scanScreenTitles(node.getChild(i), texts, buttons);
        }
    }

    private void collectNodeDetails(AccessibilityNodeInfo node, JSONArray array) {
        if (node == null) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence viewId = node.getViewIdResourceName();
        CharSequence className = node.getClassName();

        boolean hasText = (text != null && text.length() > 0);
        boolean hasDesc = (desc != null && desc.length() > 0);

        if (hasText || hasDesc || node.isClickable() || node.isEditable()) {
            try {
                JSONObject item = new JSONObject();
                if (hasText) item.put("text", text.toString());
                if (hasDesc) item.put("description", desc.toString());
                if (viewId != null) item.put("viewId", viewId.toString());
                if (className != null) item.put("className", className.toString());
                item.put("clickable", node.isClickable());
                item.put("editable", node.isEditable());
                item.put("scrollable", node.isScrollable());

                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    JSONObject rectObj = new JSONObject();
                    rectObj.put("left", bounds.left);
                    rectObj.put("top", bounds.top);
                    rectObj.put("right", bounds.right);
                    rectObj.put("bottom", bounds.bottom);
                    rectObj.put("centerX", bounds.centerX());
                    rectObj.put("centerY", bounds.centerY());
                    item.put("bounds", rectObj);
                }

                array.put(item);
            } catch (Exception ignored) {}
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectNodeDetails(node.getChild(i), array);
        }
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findScrollableNode(child);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo safeGetRoot() {
        try {
            return getRootInActiveWindow();
        } catch (Exception e) {
            Log.w(TAG, "safeGetRoot exception: " + e.getMessage());
            return null;
        }
    }
}
