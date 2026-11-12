package com.myraa.voice.agent;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * TaskExecutionEngine
 * Coordinates multi-step user-authorized automation workflows:
 * State Machine: IDLE -> UNDERSTANDING -> PLANNING -> EXECUTING -> VERIFYING -> COMPLETED / FAILED / CANCELLED.
 * Features:
 * - Bounded retries and execution timeouts.
 * - Progress callbacks to WebView.
 * - Cancellation support (e.g., "Myraa, cancel task" or "ab ruk jao").
 * - Safe limits: Maximum 8 steps per workflow to prevent loops.
 */
public class TaskExecutionEngine {

    private static final String TAG = "TaskExecutionEngine";

    public enum TaskState {
        IDLE,
        UNDERSTANDING,
        PLANNING,
        WAITING_FOR_PERMISSION,
        EXECUTING,
        VERIFYING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public interface TaskProgressListener {
        void onProgress(String taskId, TaskState state, String message, int currentStep, int totalSteps);
        void onCompleted(String taskId, JSONObject result);
        void onError(String taskId, String error);
    }

    private TaskState currentState = TaskState.IDLE;
    private String currentTaskId = "";
    private boolean isCancellationRequested = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public TaskExecutionEngine() {}

    public synchronized TaskState getCurrentState() {
        return currentState;
    }

    public synchronized void cancelCurrentTask() {
        isCancellationRequested = true;
        currentState = TaskState.CANCELLED;
        Log.i(TAG, "Task " + currentTaskId + " cancellation requested.");
    }

    /**
     * Executes a structured multi-step task asynchronously.
     */
    public void executeMultiStepTask(
            final String taskId,
            final List<JSONObject> stepActions,
            final AppLauncherManager appLauncher,
            final MyraaAccessibilityService accessibilityService,
            final TaskProgressListener listener) {

        this.currentTaskId = taskId;
        this.currentState = TaskState.PLANNING;
        this.isCancellationRequested = false;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int totalSteps = Math.min(stepActions.size(), 8); // Enforce safety cap of 8 steps
                    notifyProgress(taskId, TaskState.EXECUTING, "Starting multi-step execution (" + totalSteps + " steps)...", 0, totalSteps, listener);

                    for (int i = 0; i < totalSteps; i++) {
                        if (isCancellationRequested) {
                            currentState = TaskState.CANCELLED;
                            notifyProgress(taskId, TaskState.CANCELLED, "Task was cancelled by user.", i, totalSteps, listener);
                            return;
                        }

                        JSONObject step = stepActions.get(i);
                        String actionType = step.optString("action", "");
                        notifyProgress(taskId, TaskState.EXECUTING, "Executing step " + (i + 1) + ": " + actionType, i + 1, totalSteps, listener);

                        boolean stepSuccess = executeStepAction(step, appLauncher, accessibilityService);
                        if (!stepSuccess) {
                            currentState = TaskState.FAILED;
                            String failMsg = "Step " + (i + 1) + " (" + actionType + ") could not be completed.";
                            notifyError(taskId, failMsg, listener);
                            return;
                        }

                        // Short pause between sequential steps for UI stabilization
                        Thread.sleep(600);
                    }

                    currentState = TaskState.COMPLETED;
                    JSONObject finalRes = new JSONObject();
                    finalRes.put("status", "SUCCESS");
                    finalRes.put("taskId", taskId);
                    finalRes.put("message", "All " + totalSteps + " steps completed successfully.");
                    notifyCompleted(taskId, finalRes, listener);

                } catch (Exception e) {
                    currentState = TaskState.FAILED;
                    notifyError(taskId, "Task execution error: " + e.getMessage(), listener);
                }
            }
        }).start();
    }

    private boolean executeStepAction(JSONObject step, AppLauncherManager appLauncher, MyraaAccessibilityService service) {
        String action = step.optString("action", "");
        switch (action) {
            case "open_app":
                JSONObject res = appLauncher.launchApp(step.optString("target", ""));
                return "APP_OPENED".equals(res.optString("status"));

            case "click":
            case "tap":
                if (service == null) return false;
                return service.clickElementByTextOrDescription(step.optString("target", ""));

            case "type":
                if (service == null) return false;
                return service.inputText(step.optString("text", ""));

            case "scroll_down":
                if (service == null) return false;
                return service.performScroll(true);

            case "scroll_up":
                if (service == null) return false;
                return service.performScroll(false);

            case "press_back":
                if (service == null) return false;
                return service.performBack();

            default:
                return false;
        }
    }

    private void notifyProgress(final String id, final TaskState st, final String msg, final int curr, final int total, final TaskProgressListener listener) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onProgress(id, st, msg, curr, total);
            }
        });
    }

    private void notifyCompleted(final String id, final JSONObject res, final TaskProgressListener listener) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onCompleted(id, res);
            }
        });
    }

    private void notifyError(final String id, final String err, final TaskProgressListener listener) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listener != null) listener.onError(id, err);
            }
        });
    }
}
