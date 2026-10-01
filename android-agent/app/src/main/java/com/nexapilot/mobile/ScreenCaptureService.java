package com.nexapilot.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    public static final String ACTION_START = "com.nexapilot.mobile.SCREEN_START";
    public static final String ACTION_STOP = "com.nexapilot.mobile.SCREEN_STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "nexapilot_screen_vision";
    private static final int NOTIFICATION_ID = 92;
    private static volatile boolean active = false;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private SharedPreferences prefs;
    private long lastFrameAt = 0L;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("nexapilot", MODE_PRIVATE);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Screen vision active"));
        beginProjection(resultCode, resultData);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static boolean isActive() { return active; }

    private void beginProjection(int resultCode, Intent resultData) {
        try {
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                stopCapture();
                return;
            }

            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopCapture(); }
            }, new android.os.Handler(getMainLooper()));

            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
                android.graphics.Rect bounds = metrics.getBounds();
                dm.widthPixels = bounds.width();
                dm.heightPixels = bounds.height();
                dm.densityDpi = getResources().getDisplayMetrics().densityDpi;
            } else {
                wm.getDefaultDisplay().getRealMetrics(dm);
            }

            int sourceW = Math.max(1, dm.widthPixels);
            int sourceH = Math.max(1, dm.heightPixels);
            int longSide = Math.max(sourceW, sourceH);
            float scale = longSide > 960 ? 960f / longSide : 1f;
            int width = Math.max(2, ((int) (sourceW * scale)) & ~1);
            int height = Math.max(2, ((int) (sourceH * scale)) & ~1);
            int density = Math.max(160, dm.densityDpi);

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, new android.os.Handler(getMainLooper()));
            virtualDisplay = projection.createVirtualDisplay(
                    "NexaPilotScreenVision",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null,
                    null
            );

            active = true;
            prefs.edit().putBoolean("screen_vision_enabled", true).apply();
            LiveAssistantService.notifyScreenVisionChanged(true);
        } catch (Exception e) {
            stopCapture();
        }
    }

    private void onImageAvailable(ImageReader reader) {
        long now = System.currentTimeMillis();
        if (now - lastFrameAt < 850) {
            Image stale = null;
            try { stale = reader.acquireLatestImage(); } catch (Exception ignored) { }
            if (stale != null) stale.close();
            return;
        }
        lastFrameAt = now;

        Image image = null;
        Bitmap bitmap = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            int paddedWidth = image.getWidth() + rowPadding / pixelStride;

            bitmap = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            cropped.compress(Bitmap.CompressFormat.JPEG, 48, out);
            cropped.recycle();
            LiveAssistantService.pushVideoFrame(out.toByteArray());
        } catch (Exception ignored) {
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            if (image != null) image.close();
        }
    }

    private void stopCapture() {
        active = false;
        if (prefs != null) prefs.edit().putBoolean("screen_vision_enabled", false).apply();
        LiveAssistantService.notifyScreenVisionChanged(false);
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) { }
        try { if (imageReader != null) imageReader.close(); } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        virtualDisplay = null;
        imageReader = null;
        projection = null;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (active || projection != null) stopCapture();
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "NexaPilot Screen Vision", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Shares your screen with the active NexaPilot Live session");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, NexaPilotActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("NexaPilot Screen Vision")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
