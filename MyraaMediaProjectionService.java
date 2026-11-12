package com.myraa.voice.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * MyraaMediaProjectionService
 * Android Foreground Service managing MediaProjection screen capture.
 * Fully compatible with Android 14+ (requires FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION).
 *
 * Provides:
 * - Secure capture token management
 * - VirtualDisplay & ImageReader configuration
 * - On-demand single frame capture with JPEG base64 encoding
 * - Complete resource disposal on termination or user revocation
 */
public class MyraaMediaProjectionService extends Service {

    private static final String TAG = "MyraaProjection";
    private static final String CHANNEL_ID = "myraa_projection_channel";
    private static final int NOTIFICATION_ID = 2002;

    private static MyraaMediaProjectionService instance = null;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth = 720;
    private int screenHeight = 1280;
    private int screenDensity = DisplayMetrics.DENSITY_MEDIUM;

    public static MyraaMediaProjectionService getInstance() {
        return instance;
    }

    public static boolean isSharingActive() {
        return instance != null && instance.mediaProjection != null;
    }

    public class LocalBinder extends Binder {
        public MyraaMediaProjectionService getService() {
            return MyraaMediaProjectionService.this;
        }
    }

    private final IBinder binder = new LocalBinder();

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification("MYRAA Screen Sharing Active", "Visual AI analysis enabled");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("resultData")) {
            int resultCode = intent.getIntExtra("resultCode", 0);
            Intent resultData = intent.getParcelableExtra("resultData");

            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager != null && resultData != null) {
                initMediaProjection(manager, resultCode, resultData);
            }
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void initMediaProjection(MediaProjectionManager manager, int resultCode, Intent resultData) {
        // Release any existing capture session first
        stopCapture();

        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to acquire MediaProjection token.");
            return;
        }

        // Register mandatory stop callback
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                super.onStop();
                Log.i(TAG, "MediaProjection stopped by system or user.");
                stopCapture();
            }
        }, null);

        // Fetch display metrics
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getMetrics(metrics);
            screenWidth = Math.min(1080, metrics.widthPixels);
            screenHeight = Math.min(1920, metrics.heightPixels);
            screenDensity = metrics.densityDpi;
        }

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "MyraaVisualCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            null
        );

        Log.i(TAG, "VirtualDisplay created: " + screenWidth + "x" + screenHeight);
    }

    /**
     * Captures the most recent screen frame, compresses it to JPEG, and returns a base64 string.
     */
    public String captureFrameAsBase64(int quality) {
        if (imageReader == null) {
            return null;
        }

        Image image = null;
        Bitmap bitmap = null;
        ByteArrayOutputStream baos = null;

        try {
            image = imageReader.acquireLatestImage();
            if (image == null) {
                return null;
            }

            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop padding if necessary
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight);

            // Compress to JPEG
            baos = new ByteArrayOutputStream();
            int compQuality = Math.max(30, Math.min(90, quality));
            cropped.compress(Bitmap.CompressFormat.JPEG, compQuality, baos);
            byte[] bytes = baos.toByteArray();

            cropped.recycle();

            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring frame from ImageReader: " + e.getMessage());
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (image != null) {
                image.close();
            }
            if (baos != null) {
                try { baos.close(); } catch (Exception ignored) {}
            }
        }
    }

    public void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        Log.i(TAG, "Screen capture resources cleanly disposed.");
    }

    @Override
    public void onDestroy() {
        stopCapture();
        instance = null;
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "MYRAA Screen Sharing Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Screen capture for visual AI understanding");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
}
