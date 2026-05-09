package fyi.ozelot.booplock.service;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

import fyi.ozelot.booplock.data.AttemptRecord;
import fyi.ozelot.booplock.data.AttemptStorage;
import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.debug.DebugLog;
import fyi.ozelot.booplock.notify.NotificationHelper;

/**
 * Foreground service that takes a single headless (no preview) photo using
 * the front camera via Camera2 + ImageReader.
 *
 * Why Camera2 instead of CameraX/Intent:
 *   - CameraX requires a LifecycleOwner (activity/fragment) — no UI here.
 *   - ACTION_IMAGE_CAPTURE would open the camera for the user — the opposite of what we want.
 *   - Camera2 + ImageReader allows capturing without an on-screen Surface,
 *     everything runs on a background thread.
 *
 * Android constraints:
 *   - Since Android 14, a "camera" foreground service requires the
 *     FOREGROUND_SERVICE_CAMERA permission and the appropriate manifest declaration.
 *   - Since Android 12, "background-to-foreground service start" restrictions apply.
 *     Triggering from DeviceAdminReceiver is usually considered privileged,
 *     but under extreme conditions (e.g. battery saver) the system may refuse to start.
 *   - On some devices with an active keyguard, the camera won't be granted until
 *     the screen turns on — the first failed attempt is already the right moment
 *     since the screen is active at that point.
 */
public class CaptureService extends Service {

    public static final String EXTRA_FAILED_COUNT = "failed_count";

    private static final String TAG = "CaptureService";

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;

    private int failedCount;
    private long startTimestamp;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            failedCount = intent.getIntExtra(EXTRA_FAILED_COUNT, 1);
        }
        startTimestamp = System.currentTimeMillis();
        DebugLog.i(this, "CaptureService: START failedCount=" + failedCount);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NotificationHelper.NOTIF_SERVICE_ID,
                    NotificationHelper.buildServiceNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        } else {
            startForeground(
                    NotificationHelper.NOTIF_SERVICE_ID,
                    NotificationHelper.buildServiceNotification(this));
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            DebugLog.e(this, "CaptureService: CAMERA permission missing - stopping");
            stopAndCleanup();
            return START_NOT_STICKY;
        }

        cameraThread = new HandlerThread("BoopLockCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        cameraHandler.post(this::openFrontCamera);

        cameraHandler.postDelayed(() -> {
            DebugLog.e(this, "CaptureService: TIMEOUT 8s - camera did not respond");
            stopAndCleanup();
        }, 8000);

        return START_NOT_STICKY;
    }

    @SuppressWarnings("MissingPermission")
    private void openFrontCamera() {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            stopAndCleanup();
            return;
        }
        try {
            String frontCameraId = findFrontCameraId(cm);
            if (frontCameraId == null) {
                Log.w(TAG, "No front camera available");
                stopAndCleanup();
                return;
            }

            CameraCharacteristics chars = cm.getCameraCharacteristics(frontCameraId);
            StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size jpegSize = pickJpegSize(map);

            imageReader = ImageReader.newInstance(
                    jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 1);
            imageReader.setOnImageAvailableListener(this::onJpegAvailable, cameraHandler);

            cm.openCamera(frontCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Camera disconnected");
                    camera.close();
                    cameraDevice = null;
                    stopAndCleanup();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    stopAndCleanup();
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Cannot open camera", e);
            stopAndCleanup();
        }
    }

    private void createCaptureSession() {
        try {
            cameraDevice.createCaptureSession(
                    Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            captureStill();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configure failed");
                            stopAndCleanup();
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession failed", e);
            stopAndCleanup();
        }
    }

    private void captureStill() {
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(imageReader.getSurface());
            // Basic auto modes - perfect quality is not needed.
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
            captureSession.capture(b.build(), null, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "captureStill failed", e);
            stopAndCleanup();
        }
    }

    private void onJpegAvailable(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) {
                DebugLog.e(this, "CaptureService: acquireLatestImage returned null");
                return;
            }
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            DebugLog.i(this, "CaptureService: JPEG ready, size=" + bytes.length + "B");

            AttemptStorage storage = new AttemptStorage(this);
            java.io.File outFile = storage.newPhotoFile(startTimestamp);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(bytes);
            }
            DebugLog.i(this, "CaptureService: photo saved -> " + outFile.getName());

            AttemptRecord rec = storage.append(
                    startTimestamp, failedCount, outFile.getAbsolutePath());

            Prefs prefs = Prefs.get(this);
            prefs.setLastCyclePhotoPath(outFile.getAbsolutePath());
            prefs.setLastCycleRecordId(rec.id);
            prefs.setCaptureCompleteInCycle(true);

            boolean notifEnabled = prefs.areNotificationsEnabled();
            DebugLog.i(this, "CaptureService: captureComplete=true notifEnabled=" + notifEnabled + " recordId=" + rec.id);

            // Send notification IMMEDIATELY after saving the photo.
            //
            // The previous approach waited for USER_PRESENT or onPasswordSucceeded,
            // but Samsung One UI 7 blocks both signals for sleeping processes.
            // The notification appears in the notification shade on the lock screen
            // (correct behavior for a security app) and is visible right after unlock.
            //
            // onPasswordSucceeded and USER_PRESENT remain as backup for cycle reset.
            if (notifEnabled) {
                DebugLog.i(this, "CaptureService: sending notification IMMEDIATELY");
                NotificationHelper.postAlert(this, rec.id);
            } else {
                DebugLog.w(this, "CaptureService: notifications disabled in settings");
            }
            prefs.resetCurrentCycle();
        } catch (IOException e) {
            DebugLog.e(this, "CaptureService: photo save error: " + e.getMessage());
            Log.e(TAG, "Failed to save photo", e);
        } finally {
            if (img != null) img.close();
            stopAndCleanup();
        }
    }

    @Nullable
    private static String findFrontCameraId(CameraManager cm) throws CameraAccessException {
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id;
            }
        }
        return null;
    }

    private static Size pickJpegSize(@Nullable StreamConfigurationMap map) {
        if (map == null) return new Size(640, 480);
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        // Pick the smallest size >= 640x480 to save quickly
        // and minimize the risk of "camera didn't finish in time".
        Size[] copy = sizes.clone();
        Arrays.sort(copy, (a, b) -> Long.compare(
                (long) a.getWidth() * a.getHeight(),
                (long) b.getWidth() * b.getHeight()));
        for (Size s : copy) {
            if (s.getWidth() >= 640 && s.getHeight() >= 480) return s;
        }
        return copy[copy.length - 1];
    }

    private boolean stopped;

    private void stopAndCleanup() {
        if (stopped) return;
        stopped = true;

        // Close all camera resources first.
        Runnable cleanup = () -> {
            try {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
            } catch (Throwable t) {
                Log.w(TAG, "cleanup", t);
            }
            // Give the system a moment to close before stopping the service.
            new Handler(getMainLooper()).post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        };

        if (cameraHandler != null) {
            cameraHandler.removeCallbacksAndMessages(null);
            cameraHandler.post(cleanup);
        } else {
            cleanup.run();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
    }

    /** Suppresses unused-variable warning. */
    @SuppressWarnings("unused")
    private static long sinceBoot() {
        return SystemClock.elapsedRealtime();
    }
}
