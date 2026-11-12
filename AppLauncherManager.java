package com.myraa.voice.agent;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AppLauncherManager
 * Handles dynamic discovery and launching of installed Android applications.
 * - Resolves installed apps dynamically via PackageManager.
 * - Handles aliases for common applications.
 * - Detects ambiguous app names and returns selectable candidate matches.
 * - Provides Google Play Store fallback when an app is not installed.
 */
public class AppLauncherManager {

    private static final String TAG = "AppLauncherManager";
    private final Context context;

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
        COMMON_ALIASES.put("calc", "com.google.android.calculator");
        COMMON_ALIASES.put("spotify", "com.spotify.music");
        COMMON_ALIASES.put("telegram", "org.telegram.messenger");
        COMMON_ALIASES.put("gmail", "com.google.android.gm");
        COMMON_ALIASES.put("mail", "com.google.android.gm");
        COMMON_ALIASES.put("twitter", "com.twitter.android");
        COMMON_ALIASES.put("x", "com.twitter.android");
        COMMON_ALIASES.put("photos", "com.google.android.apps.photos");
        COMMON_ALIASES.put("gallery", "com.google.android.apps.photos");
    }

    public AppLauncherManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject launchApp(String targetApp) {
        JSONObject res = new JSONObject();
        try {
            res.put("action", "open_app");
            res.put("target", targetApp);

            if (targetApp == null || targetApp.trim().isEmpty()) {
                res.put("status", "APP_NOT_FOUND");
                res.put("message", "No application name specified.");
                return res;
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
                    return res;
                }
            }

            // 2. Dynamic resolution of all installed launcher apps
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> available = pm.queryIntentActivities(launcherIntent, 0);

            List<JSONObject> exactMatches = new ArrayList<>();
            List<JSONObject> partialMatches = new ArrayList<>();

            for (ResolveInfo ri : available) {
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

            // Exact match
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
                    return res;
                }
            }

            // Single partial match
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
                    return res;
                }
            }

            // Ambiguous match: Return candidates
            if (partialMatches.size() > 1) {
                JSONArray candidates = new JSONArray();
                for (int i = 0; i < Math.min(5, partialMatches.size()); i++) {
                    candidates.put(partialMatches.get(i));
                }
                res.put("status", "AMBIGUOUS_MATCH");
                res.put("candidates", candidates);
                res.put("message", "Multiple matching apps found on your phone. Which one would you like to open?");
                return res;
            }

            // Not installed: Suggest Play Store
            res.put("status", "APP_NOT_FOUND");
            res.put("message", "Application '" + targetApp + "' is not installed on this device. Would you like to view it on Google Play Store?");
            res.put("playStoreQuery", targetApp);
            return res;

        } catch (Exception e) {
            Log.e(TAG, "launchApp error: ", e);
            try {
                res.put("status", "ACTION_FAILED");
                res.put("message", "App launch error: " + e.getMessage());
            } catch (JSONException ignored) {}
            return res;
        }
    }

    public JSONObject openPlayStore(String targetApp) {
        JSONObject res = new JSONObject();
        try {
            res.put("action", "open_play_store");
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(targetApp)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            res.put("status", "SUCCESS");
            res.put("message", "Opened Google Play Store for '" + targetApp + "'.");
        } catch (Exception e) {
            try {
                Intent webIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(targetApp)));
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(webIntent);
                res.put("status", "SUCCESS");
                res.put("message", "Opened Play Store web page.");
            } catch (Exception ex) {
                try {
                    res.put("status", "ACTION_FAILED");
                    res.put("message", "Could not open Play Store: " + ex.getMessage());
                } catch (JSONException ignored) {}
            }
        }
        return res;
    }

    public JSONArray getInstalledAppsSummary() {
        JSONArray arr = new JSONArray();
        try {
            PackageManager pm = context.getPackageManager();
            Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
            launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> available = pm.queryIntentActivities(launcherIntent, 0);

            for (ResolveInfo ri : available) {
                JSONObject app = new JSONObject();
                app.put("name", ri.loadLabel(pm).toString());
                app.put("packageName", ri.activityInfo.packageName);
                arr.put(app);
            }
        } catch (Exception e) {
            Log.w(TAG, "getInstalledAppsSummary error:", e);
        }
        return arr;
    }
}
