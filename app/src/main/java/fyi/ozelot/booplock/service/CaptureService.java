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
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import fyi.ozelot.booplock.data.AttemptRecord;
import fyi.ozelot.booplock.data.AttemptStorage;
import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.debug.DebugLog;
import fyi.ozelot.booplock.notify.NotificationHelper;

/**
 * Foreground service that takes 3 photos from the front camera and 3 photos from the
 * rear camera (6 total), with a 2-second pause between shots on each camera.
 *
 * Capture sequence: FRONT phase (shots 0-2) → REAR phase (shots 0-2) → finalize.
 * If a camera is unavailable or errors out, that phase is skipped gracefully and
 * finalization proceeds with whatever photos were already saved.
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
 */
public class CaptureService extends Service {

    public static final String EXTRA_FAILED_COUNT = "failed_count";

    private static final String TAG = "CaptureService";
    private static final int SHOTS_PER_CAMERA = 3;
    private static final long SHOT_DELAY_MS = 2000;
    private static final long VIDEO_DURATION_MS = 10_000;
    private static final long TIMEOUT_MS = 60_000;

    private enum Phase { FRONT, REAR }

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private MediaRecorder mediaRecorder;
    private File pendingVideoFile;
    private CameraCharacteristics currentCameraCharacteristics;
    private boolean currentCameraFront;

    private Phase currentPhase;
    private int shotsTaken;
    private final List<String> photoPaths = new ArrayList<>();
    private String videoPath;

    private int failedCount;
    private long startTimestamp;
    private boolean finalized;
    private boolean stopped;
    private boolean videoRecording;
    private boolean stoppingVideo;

    private AttemptStorage storage;

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

        startCaptureForeground();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            DebugLog.e(this, "CaptureService: CAMERA permission missing - stopping");
            stopAndCleanup();
            return START_NOT_STICKY;
        }

        storage = new AttemptStorage(this);
        currentPhase = Phase.FRONT;
        shotsTaken = 0;

        cameraThread = new HandlerThread("BoopLockCameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        cameraHandler.post(this::openCurrentCamera);

        cameraHandler.postDelayed(() -> {
            DebugLog.e(this, "CaptureService: TIMEOUT 60s - finalizing with "
                    + photoPaths.size() + " photos");
            finalizeCapture();
        }, TIMEOUT_MS);

        return START_NOT_STICKY;
    }

    private void startCaptureForeground() {
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
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean tryStartMicrophoneForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true;
        try {
            startForeground(
                    NotificationHelper.NOTIF_SERVICE_ID,
                    NotificationHelper.buildServiceNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            DebugLog.e(this, "CaptureService: cannot enable microphone FGS: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("MissingPermission")
    private void openCurrentCamera() {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            handleCameraPhaseError("no CameraManager");
            return;
        }
        try {
            String cameraId = currentPhase == Phase.FRONT
                    ? findFrontCameraId(cm)
                    : findRearCameraId(cm);

            if (cameraId == null) {
                DebugLog.w(this, "CaptureService: no " + currentPhase + " camera, skipping phase");
                advancePhaseOrFinalize();
                return;
            }

            CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
            currentCameraCharacteristics = chars;
            currentCameraFront = currentPhase == Phase.FRONT;
            StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size jpegSize = pickJpegSize(map);

            imageReader = ImageReader.newInstance(
                    jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::onJpegAvailable, cameraHandler);

            DebugLog.i(this, "CaptureService: opening " + currentPhase + " camera id=" + cameraId
                    + " size=" + jpegSize.getWidth() + "x" + jpegSize.getHeight());

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
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
                    handleCameraPhaseError("disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    handleCameraPhaseError("error " + error);
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Cannot open camera", e);
            handleCameraPhaseError(e.getMessage());
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
                            handleCameraPhaseError("configure failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession failed", e);
            handleCameraPhaseError(e.getMessage());
        }
    }

    private void captureStill() {
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(imageReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 85);
            if (currentCameraCharacteristics != null) {
                b.set(CaptureRequest.JPEG_ORIENTATION,
                        outputOrientation(currentCameraCharacteristics, currentCameraFront));
            }
            captureSession.capture(b.build(), null, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "captureStill failed", e);
            handleCameraPhaseError(e.getMessage());
        }
    }

    private void onJpegAvailable(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) {
                DebugLog.e(this, "CaptureService: acquireLatestImage returned null");
            } else {
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);

                String label = currentPhase == Phase.FRONT ? "front" : "rear";
                File outFile = storage.newPhotoFile(startTimestamp, label, shotsTaken);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(bytes);
                    photoPaths.add(outFile.getAbsolutePath());
                    DebugLog.i(this, "CaptureService: saved " + label + "_" + shotsTaken
                            + " size=" + bytes.length + "B");
                } catch (IOException e) {
                    DebugLog.e(this, "CaptureService: save error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            DebugLog.e(this, "CaptureService: onJpegAvailable error: " + e.getMessage());
        } finally {
            if (img != null) img.close();
        }

        shotsTaken++;
        if (shotsTaken < SHOTS_PER_CAMERA) {
            DebugLog.i(this, "CaptureService: shot " + shotsTaken + "/" + SHOTS_PER_CAMERA
                    + " done for " + currentPhase + ", waiting " + SHOT_DELAY_MS + "ms");
            cameraHandler.postDelayed(this::captureStill, SHOT_DELAY_MS);
        } else {
            DebugLog.i(this, "CaptureService: all " + SHOTS_PER_CAMERA
                    + " shots done for " + currentPhase);
            closeCameraResourcesThen(this::advancePhaseOrFinalize);
        }
    }

    /** Closes current camera resources synchronously, then posts {@code next} after a settle delay. */
    private void closeCameraResourcesThen(Runnable next) {
        try {
            if (captureSession != null) { captureSession.close(); captureSession = null; }
            if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
        } catch (Throwable t) {
            Log.w(TAG, "closeCameraResources", t);
        }
        cameraHandler.postDelayed(next, 500);
    }

    private void advancePhaseOrFinalize() {
        if (finalized) return;
        if (currentPhase == Phase.FRONT) {
            currentPhase = Phase.REAR;
            shotsTaken = 0;
            DebugLog.i(this, "CaptureService: switching to REAR camera");
            openCurrentCamera();
        } else {
            startVideoCapture();
        }
    }

    private void handleCameraPhaseError(String reason) {
        DebugLog.w(this, "CaptureService: " + currentPhase + " camera error: " + reason
                + ", photos so far: " + photoPaths.size());
        closeCameraResourcesThen(this::advancePhaseOrFinalize);
    }

    @SuppressWarnings("MissingPermission")
    private void startVideoCapture() {
        if (finalized) return;

        if (!Prefs.get(this).isVideoEnabled()) {
            DebugLog.i(this, "CaptureService: video disabled in settings - skipping");
            finalizeCapture();
            return;
        }
        if (!hasAudioPermission()) {
            DebugLog.w(this, "CaptureService: RECORD_AUDIO permission missing - skipping video");
            finalizeCapture();
            return;
        }
        if (!tryStartMicrophoneForeground()) {
            DebugLog.w(this, "CaptureService: microphone foreground type unavailable - skipping video");
            finalizeCapture();
            return;
        }

        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            handleVideoError("no CameraManager");
            return;
        }

        try {
            String cameraId = findFrontCameraId(cm);
            boolean frontCamera = true;
            if (cameraId == null) {
                cameraId = findRearCameraId(cm);
                frontCamera = false;
            }
            if (cameraId == null) {
                DebugLog.w(this, "CaptureService: no camera available for video - skipping");
                finalizeCapture();
                return;
            }

            CameraCharacteristics chars = cm.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = chars.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size videoSize = pickVideoSize(map);

            pendingVideoFile = storage.newVideoFile(startTimestamp);
            prepareMediaRecorder(pendingVideoFile, videoSize, chars, frontCamera);

            DebugLog.i(this, "CaptureService: opening video camera id=" + cameraId
                    + " size=" + videoSize.getWidth() + "x" + videoSize.getHeight());

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createVideoCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Video camera disconnected");
                    camera.close();
                    cameraDevice = null;
                    handleVideoError("disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Video camera error: " + error);
                    camera.close();
                    cameraDevice = null;
                    handleVideoError("error " + error);
                }
            }, cameraHandler);
        } catch (CameraAccessException | IOException | RuntimeException e) {
            Log.e(TAG, "Cannot start video capture", e);
            handleVideoError(e.getMessage());
        }
    }

    private void prepareMediaRecorder(File outFile, Size videoSize, CameraCharacteristics chars,
                                      boolean frontCamera) throws IOException {
        releaseMediaRecorder(true);
        pendingVideoFile = outFile;
        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(outFile.getAbsolutePath());
        mediaRecorder.setVideoEncodingBitRate(videoBitRate(videoSize));
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioEncodingBitRate(128_000);
        mediaRecorder.setAudioSamplingRate(44_100);
        mediaRecorder.setOrientationHint(videoOrientationHint(chars, frontCamera));
        mediaRecorder.prepare();
    }

    private void createVideoCaptureSession() {
        try {
            Surface recorderSurface = mediaRecorder.getSurface();
            cameraDevice.createCaptureSession(
                    Collections.singletonList(recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            startVideoRecording(recorderSurface);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Video capture session configure failed");
                            handleVideoError("configure failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "createVideoCaptureSession failed", e);
            handleVideoError(e.getMessage());
        }
    }

    private void startVideoRecording(Surface recorderSurface) {
        try {
            CaptureRequest.Builder b = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_RECORD);
            b.addTarget(recorderSurface);
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            captureSession.setRepeatingRequest(b.build(), null, cameraHandler);

            mediaRecorder.start();
            videoRecording = true;
            DebugLog.i(this, "CaptureService: recording video for "
                    + VIDEO_DURATION_MS + "ms");
            cameraHandler.postDelayed(this::stopVideoCaptureAndFinalize, VIDEO_DURATION_MS);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "startVideoRecording failed", e);
            handleVideoError(e.getMessage());
        }
    }

    private void stopVideoCaptureAndFinalize() {
        if (finalized || stoppingVideo) return;
        stoppingVideo = true;

        boolean saved = false;
        File completedFile = pendingVideoFile;

        try {
            if (captureSession != null) {
                try {
                    captureSession.stopRepeating();
                    captureSession.abortCaptures();
                } catch (CameraAccessException | IllegalStateException e) {
                    Log.w(TAG, "stop video repeating", e);
                }
            }

            if (videoRecording && mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    saved = completedFile != null && completedFile.exists()
                            && completedFile.length() > 0;
                } catch (RuntimeException e) {
                    Log.e(TAG, "mediaRecorder.stop failed", e);
                }
            }
        } finally {
            videoRecording = false;
            releaseMediaRecorder(!saved);
        }

        if (saved) {
            videoPath = completedFile.getAbsolutePath();
            DebugLog.i(this, "CaptureService: saved video size="
                    + completedFile.length() + "B");
        } else {
            DebugLog.w(this, "CaptureService: video not saved");
        }

        closeCameraResourcesThen(() -> {
            stoppingVideo = false;
            finalizeCapture();
        });
    }

    private void handleVideoError(String reason) {
        DebugLog.w(this, "CaptureService: video error: " + reason
                + ", finalizing with photos only");
        videoRecording = false;
        releaseMediaRecorder(true);
        closeCameraResourcesThen(this::finalizeCapture);
    }

    private void finalizeCapture() {
        if (finalized) return;
        if (videoRecording || stoppingVideo) {
            stopVideoCaptureAndFinalize();
            return;
        }
        finalized = true;

        DebugLog.i(this, "CaptureService: finalizing, total photos=" + photoPaths.size()
                + " video=" + (videoPath != null));

        AttemptRecord rec = storage.append(startTimestamp, failedCount, photoPaths, videoPath);

        Prefs prefs = Prefs.get(this);
        prefs.setLastCyclePhotoPath(photoPaths.isEmpty() ? null : photoPaths.get(0));
        prefs.setLastCycleRecordId(rec.id);
        prefs.setCaptureCompleteInCycle(true);

        boolean notifEnabled = prefs.areNotificationsEnabled();
        DebugLog.i(this, "CaptureService: captureComplete=true notifEnabled=" + notifEnabled
                + " recordId=" + rec.id);

        // Send notification IMMEDIATELY after saving the photos.
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

        stopAndCleanup();
    }

    @Nullable
    private static String findFrontCameraId(CameraManager cm) throws CameraAccessException {
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) return id;
        }
        return null;
    }

    @Nullable
    private static String findRearCameraId(CameraManager cm) throws CameraAccessException {
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return null;
    }

    private static Size pickJpegSize(@Nullable StreamConfigurationMap map) {
        if (map == null) return new Size(640, 480);
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        Size[] copy = sizes.clone();
        Arrays.sort(copy, (a, b) -> Long.compare(
                (long) a.getWidth() * a.getHeight(),
                (long) b.getWidth() * b.getHeight()));
        for (Size s : copy) {
            if (s.getWidth() >= 640 && s.getHeight() >= 480) return s;
        }
        return copy[copy.length - 1];
    }

    private static Size pickVideoSize(@Nullable StreamConfigurationMap map) {
        if (map == null) return new Size(640, 480);
        Size[] sizes = map.getOutputSizes(MediaRecorder.class);
        if (sizes == null || sizes.length == 0) return new Size(640, 480);
        Size[] copy = sizes.clone();
        Arrays.sort(copy, (a, b) -> Long.compare(
                (long) a.getWidth() * a.getHeight(),
                (long) b.getWidth() * b.getHeight()));
        for (Size s : copy) {
            if (s.getWidth() >= 640 && s.getHeight() >= 480
                    && s.getWidth() <= 1280 && s.getHeight() <= 720) {
                return s;
            }
        }
        for (Size s : copy) {
            if (s.getWidth() <= 1280 && s.getHeight() <= 720) {
                return s;
            }
        }
        return copy[0];
    }

    private static int videoBitRate(Size size) {
        long pixels = (long) size.getWidth() * size.getHeight();
        if (pixels >= 1280L * 720L) return 5_000_000;
        if (pixels >= 640L * 480L) return 2_000_000;
        return 1_000_000;
    }

    private int videoOrientationHint(CameraCharacteristics chars, boolean frontCamera) {
        int base = outputOrientation(chars, frontCamera);
        if (frontCamera) {
            base = (360 - base) % 360;
        }
        return (base + 180) % 360;
    }

    private int outputOrientation(CameraCharacteristics chars, boolean frontCamera) {
        Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = sensorOrientation != null ? sensorOrientation : 90;
        int device = deviceRotationDegrees();
        if (frontCamera) {
            return (sensor + device) % 360;
        }
        return (sensor - device + 360) % 360;
    }

    @SuppressWarnings("deprecation")
    private int deviceRotationDegrees() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        int rotation = Surface.ROTATION_0;
        if (wm != null && wm.getDefaultDisplay() != null) {
            rotation = wm.getDefaultDisplay().getRotation();
        }
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_0:
            default:
                return 0;
        }
    }

    private void releaseMediaRecorder(boolean deletePendingFile) {
        MediaRecorder recorder = mediaRecorder;
        mediaRecorder = null;
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (RuntimeException ignored) {
                // Recorder may already be stopped or failed; release below is still safe.
            }
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
                // Nothing useful to recover here.
            }
        }
        if (deletePendingFile && pendingVideoFile != null && pendingVideoFile.exists()
                && !pendingVideoFile.delete()) {
            Log.w(TAG, "Cannot delete failed video file: " + pendingVideoFile);
        }
        pendingVideoFile = null;
    }

    private void stopAndCleanup() {
        if (stopped) return;
        stopped = true;

        Runnable cleanup = () -> {
            try {
                if (captureSession != null) { captureSession.close(); captureSession = null; }
                if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; }
                if (imageReader != null) { imageReader.close(); imageReader = null; }
                if (videoRecording && mediaRecorder != null) {
                    try {
                        mediaRecorder.stop();
                    } catch (RuntimeException ignored) {
                        // A partial recording is not kept during service shutdown.
                    }
                }
                videoRecording = false;
                stoppingVideo = false;
                releaseMediaRecorder(true);
            } catch (Throwable t) {
                Log.w(TAG, "cleanup", t);
            }
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
}
