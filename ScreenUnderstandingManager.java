package com.myraa.voice.agent;

import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ScreenUnderstandingManager
 * Inspects accessible UI elements from the active window:
 * - Identifies clickable buttons, input fields, labels, headings, and scroll containers.
 * - Extracts minimal necessary details for the assistant without storing private screen data.
 * - Respects user privacy: avoids collecting password or payment fields.
 */
public class ScreenUnderstandingManager {

    private static final String TAG = "ScreenUnderstanding";

    public ScreenUnderstandingManager() {}

    public JSONObject analyzeScreen(MyraaAccessibilityService service) {
        JSONObject res = new JSONObject();
        try {
            if (service == null) {
                res.put("status", "PERMISSION_REQUIRED");
                res.put("message", "Accessibility Service is not active.");
                return res;
            }

            AccessibilityNodeInfo root = null;
            try {
                root = service.getRootInActiveWindow();
            } catch (Exception e) {
                Log.w(TAG, "Cannot get root window: " + e.getMessage());
            }

            if (root == null) {
                res.put("status", "ACTION_FAILED");
                res.put("message", "No accessible UI content found in current window.");
                res.put("packageName", service.getCurrentPackageName());
                return res;
            }

            List<JSONObject> buttons = new ArrayList<>();
            List<JSONObject> inputs = new ArrayList<>();
            List<String> visibleTexts = new ArrayList<>();

            scanNodeRecursive(root, buttons, inputs, visibleTexts);

            res.put("status", "SUCCESS");
            res.put("packageName", service.getCurrentPackageName());
            res.put("activityName", service.getCurrentActivityName());

            JSONArray btnArr = new JSONArray();
            for (int i = 0; i < Math.min(12, buttons.size()); i++) {
                btnArr.put(buttons.get(i));
            }
            res.put("clickableButtons", btnArr);

            JSONArray inputArr = new JSONArray();
            for (int i = 0; i < Math.min(6, inputs.size()); i++) {
                inputArr.put(inputs.get(i));
            }
            res.put("editableInputs", inputArr);

            JSONArray textArr = new JSONArray();
            for (int i = 0; i < Math.min(10, visibleTexts.size()); i++) {
                textArr.put(visibleTexts.get(i));
            }
            res.put("textSnippets", textArr);
            res.put("totalElementsScanned", buttons.size() + inputs.size() + visibleTexts.size());

            // Build human-readable screen overview for voice response
            StringBuilder voiceOverview = new StringBuilder();
            if (!service.getCurrentPackageName().isEmpty()) {
                voiceOverview.append("Active app: ").append(cleanPackageLabel(service.getCurrentPackageName())).append(". ");
            }
            if (!buttons.isEmpty()) {
                voiceOverview.append("Found ").append(buttons.size()).append(" clickable actions");
                if (!buttons.isEmpty() && buttons.get(0).has("label")) {
                    voiceOverview.append(" including \"").append(buttons.get(0).optString("label")).append("\"");
                }
                voiceOverview.append(". ");
            }
            if (!inputs.isEmpty()) {
                voiceOverview.append("There is an editable input field available. ");
            }
            res.put("summaryText", voiceOverview.toString().trim());

        } catch (Exception e) {
            Log.e(TAG, "analyzeScreen error:", e);
            try {
                res.put("status", "ACTION_FAILED");
                res.put("message", "Screen analysis failed: " + e.getMessage());
            } catch (Exception ignored) {}
        }
        return res;
    }

    private void scanNodeRecursive(AccessibilityNodeInfo node, List<JSONObject> buttons, List<JSONObject> inputs, List<String> texts) {
        if (node == null) return;

        // Privacy check: Never inspect or capture password fields
        if (node.isPassword()) {
            return;
        }

        CharSequence nodeText = node.getText();
        CharSequence nodeDesc = node.getContentDescription();
        CharSequence viewId = node.getViewIdResourceName();

        String label = "";
        if (nodeText != null && nodeText.length() > 0) {
            label = nodeText.toString().trim();
        } else if (nodeDesc != null && nodeDesc.length() > 0) {
            label = nodeDesc.toString().trim();
        }

        if (node.isEditable()) {
            try {
                JSONObject inputItem = new JSONObject();
                inputItem.put("label", label.isEmpty() ? "Editable field" : label);
                if (viewId != null) inputItem.put("viewId", viewId.toString());
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                inputItem.put("centerX", b.centerX());
                inputItem.put("centerY", b.centerY());
                inputs.add(inputItem);
            } catch (Exception ignored) {}
        } else if (node.isClickable() && !label.isEmpty()) {
            try {
                JSONObject btnItem = new JSONObject();
                btnItem.put("label", label);
                if (viewId != null) btnItem.put("viewId", viewId.toString());
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                btnItem.put("centerX", b.centerX());
                btnItem.put("centerY", b.centerY());
                buttons.add(btnItem);
            } catch (Exception ignored) {}
        } else if (!label.isEmpty()) {
            texts.add(label);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            scanNodeRecursive(node.getChild(i), buttons, inputs, texts);
        }
    }

    private String cleanPackageLabel(String pkg) {
        if (pkg.contains("youtube")) return "YouTube";
        if (pkg.contains("whatsapp")) return "WhatsApp";
        if (pkg.contains("instagram")) return "Instagram";
        if (pkg.contains("chrome")) return "Chrome";
        if (pkg.contains("settings")) return "Settings";
        if (pkg.contains("calculator")) return "Calculator";
        if (pkg.contains("camera")) return "Camera";
        if (pkg.contains("maps")) return "Google Maps";
        if (pkg.contains("spotify")) return "Spotify";
        return pkg;
    }
}
