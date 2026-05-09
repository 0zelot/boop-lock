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

import fyi.ozelot.booplock.data.AttemptLocation;
import fyi.ozelot.booplock.data.AttemptRecord;
import fyi.ozelot.booplock.data.AttemptStorage;
import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.debug.DebugLog;
import fyi.ozelot.booplock.email.EmailAttemptMailer;
import fyi.ozelot.booplock.location.LocationCapture;
import fyi.ozelot.booplock.notify.NotificationHelper;

/**
 * Foreground service that captures photos and (optionally) video after a failed unlock attempt.
 *
 * Capture sequence when video is enabled:
 *   1. Front photos  (camera 1, ~6 s)                           ← photoThread
 *   2. After front photos: Rear photos (camera 0) starts        ← photoThread
 *      simultaneously with Video (camera 1, 10 s)              ← videoThread
 *   Finalization waits for both rear photos and video to complete.
 *
 * When video is disabled:
 *   Front photos → Rear photos → finalize (sequential, single thread).
 *
 * Why Camera2 instead of CameraX/Intent:
 *   - CameraX requires a LifecycleOwner (activity/fragment) — no UI here.
 *   - ACTION_IMAGE_CAPTURE would open the camera for the user — the opposite of what we want.
 *   - Camera2 + ImageReader allows capturing without an on-screen Surface,
 *     everything runs on a background thread.
 */
public class CaptureService extends Service {

    public static final String EXTRA_FAILED_COUNT = "failed_count";

    private static final String TAG = "CaptureService";
    private static final int SHOTS_PER_CAMERA = 3;
    private static final long SHOT_DELAY_MS = 2000;
    private static final long VIDEO_DURATION_MS = 10_000;
    private static final long TIMEOUT_MS = 60_000;

    private enum Phase { FRONT, REAR }

    // --- Photo camera (front stills, and rear stills when video is disabled) ---
    private HandlerThread photoThread;
    private Handler photoHandler;
    private CameraDevice photoCameraDevice;
    private CameraCaptureSession photoSession;
    private ImageReader imageReader;
    private CameraCharacteristics currentCameraCharacteristics;
    private boolean currentCameraFront;
    private Phase currentPhase;
    private int shotsTaken;

    // --- Video camera (rear, runs in parallel with front photos when video is enabled) ---
    private HandlerThread videoThread;
    private Handler videoHandler;
    private CameraDevice videoCameraDevice;
    private CameraCaptureSession videoSession;
    private MediaRecorder mediaRecorder;
    private File pendingVideoFile;

    // --- Shared state ---
    private final List<String> photoPaths = new ArrayList<>();
    private String videoPath;
    private int failedCount;
    private long startTimestamp;
    private AttemptStorage storage;

    // volatile so cross-thread visibility is guaranteed when the other thread reads them.
    private volatile boolean photoDone = false;
    private volatile boolean videoDone  = false;

    private boolean finalized    = false; // guarded by synchronized(this)
    private boolean stopped      = false;
    private boolean videoRecording = false;
    private boolean stoppingVideo  = false;
    private boolean runVideoParallel = false;

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

        boolean videoEnabled = Prefs.get(this).isVideoEnabled();
        boolean audioOk = hasAudioPermission();
        runVideoParallel = videoEnabled && audioOk;

        // Video is marked done until the FRONT→REAR transition actually starts it.
        // This prevents finalizeCapture() from waiting for video that hasn't started yet.
        videoDone = true;

        // Photo thread — always present.
        photoThread = new HandlerThread("BoopLockPhotoThread");
        photoThread.start();
        photoHandler = new Handler(photoThread.getLooper());
        photoHandler.post(this::openCurrentCamera);

        if (runVideoParallel) {
            // Prepare a dedicated video thread. Video will be posted to it once front
            // photos are done (in advancePhaseOrFinalize), so it runs in parallel with
            // rear photos on the now-free front camera.
            videoThread = new HandlerThread("BoopLockVideoThread");
            videoThread.start();
            videoHandler = new Handler(videoThread.getLooper());
        }

        // Safety timeout — force finalization after 60 s regardless.
        photoHandler.postDelayed(() -> {
            DebugLog.e(this, "CaptureService: TIMEOUT 60s - finalizing with "
                    + photoPaths.size() + " photos");
            photoDone = true;
            videoDone = true;
            finalizeCapture();
        }, TIMEOUT_MS);

        return START_NOT_STICKY;
    }

    // =========================================================================
    // Foreground service helpers
    // =========================================================================

    private void startCaptureForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForegroundWithTypes(foregroundServiceTypes(false));
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
            startForegroundWithTypes(foregroundServiceTypes(true));
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            DebugLog.e(this, "CaptureService: cannot enable microphone FGS: " + e.getMessage());
            return false;
        }
    }

    private int foregroundServiceTypes(boolean includeMicrophone) {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        if (includeMicrophone) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (LocationCapture.canReadLocation(this)) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        return type;
    }

    private void startForegroundWithTypes(int types) {
        try {
            startForeground(
                    NotificationHelper.NOTIF_SERVICE_ID,
                    NotificationHelper.buildServiceNotification(this),
                    types);
        } catch (SecurityException | IllegalArgumentException e) {
            DebugLog.w(this, "CaptureService: FGS type fallback: " + e.getMessage());
            startForeground(
                    NotificationHelper.NOTIF_SERVICE_ID,
                    NotificationHelper.buildServiceNotification(this),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        }
    }

    // =========================================================================
    // Photo capture — runs on photoThread
    // =========================================================================

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
            imageReader.setOnImageAvailableListener(this::onJpegAvailable, photoHandler);

            DebugLog.i(this, "CaptureService: opening " + currentPhase + " camera id=" + cameraId
                    + " size=" + jpegSize.getWidth() + "x" + jpegSize.getHeight());

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    photoCameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Camera disconnected");
                    camera.close();
                    photoCameraDevice = null;
                    handleCameraPhaseError("disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Camera error: " + error);
                    camera.close();
                    photoCameraDevice = null;
                    handleCameraPhaseError("error " + error);
                }
            }, photoHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Cannot open camera", e);
            handleCameraPhaseError(e.getMessage());
        }
    }

    private void createCaptureSession() {
        try {
            photoCameraDevice.createCaptureSession(
                    Collections.singletonList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            photoSession = session;
                            captureStill();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configure failed");
                            handleCameraPhaseError("configure failed");
                        }
                    }, photoHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession failed", e);
            handleCameraPhaseError(e.getMessage());
        }
    }

    private void captureStill() {
        try {
            CaptureRequest.Builder b = photoCameraDevice.createCaptureRequest(
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
            photoSession.capture(b.build(), null, photoHandler);
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
            photoHandler.postDelayed(this::captureStill, SHOT_DELAY_MS);
        } else {
            DebugLog.i(this, "CaptureService: all " + SHOTS_PER_CAMERA
                    + " shots done for " + currentPhase);
            closePhotoCameraThen(this::advancePhaseOrFinalize);
        }
    }

    private void closePhotoCameraThen(Runnable next) {
        try {
            if (photoSession != null) { photoSession.close(); photoSession = null; }
            if (photoCameraDevice != null) { photoCameraDevice.close(); photoCameraDevice = null; }
            if (imageReader != null) { imageReader.close(); imageReader = null; }
        } catch (Throwable t) {
            Log.w(TAG, "closePhotoCameraResources", t);
        }
        photoHandler.postDelayed(next, 500);
    }

    private void advancePhaseOrFinalize() {
        if (finalized || photoDone) return;
        if (currentPhase == Phase.FRONT) {
            currentPhase = Phase.REAR;
            shotsTaken = 0;
            DebugLog.i(this, "CaptureService: switching to REAR camera");

            if (runVideoParallel) {
                // Front camera is now free. Start video on it in parallel with rear photos.
                videoDone = false;
                videoHandler.post(this::startVideoCapture);
                DebugLog.i(this, "CaptureService: video started in parallel with rear photos");
            }

            openCurrentCamera();
        } else {
            DebugLog.i(this, "CaptureService: all " + (SHOTS_PER_CAMERA * 2) + " photos done");
            photoDone = true;
            finalizeCapture();
        }
    }

    private void handleCameraPhaseError(String reason) {
        DebugLog.w(this, "CaptureService: " + currentPhase + " camera error: " + reason
                + ", photos so far: " + photoPaths.size());
        closePhotoCameraThen(this::advancePhaseOrFinalize);
    }

    // =========================================================================
    // Video capture — runs on videoThread (parallel) or photoThread (sequential)
    // =========================================================================

    @SuppressWarnings("MissingPermission")
    private void startVideoCapture() {
        if (finalized) {
            videoDone = true;
            return;
        }
        if (!Prefs.get(this).isVideoEnabled()) {
            DebugLog.i(this, "CaptureService: video disabled in settings - skipping");
            videoDone = true;
            finalizeCapture();
            return;
        }
        if (!hasAudioPermission()) {
            DebugLog.w(this, "CaptureService: RECORD_AUDIO permission missing - skipping video");
            videoDone = true;
            finalizeCapture();
            return;
        }
        if (!tryStartMicrophoneForeground()) {
            DebugLog.w(this, "CaptureService: microphone foreground type unavailable - skipping video");
            videoDone = true;
            finalizeCapture();
            return;
        }

        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            handleVideoError("no CameraManager");
            return;
        }

        try {
            // Front camera is free (front photos already done) — use it for video.
            // Rear camera is busy taking rear still photos in parallel.
            String cameraId = findFrontCameraId(cm);
            boolean frontCamera = true;
            if (cameraId == null) {
                cameraId = findRearCameraId(cm);
                frontCamera = false;
            }
            if (cameraId == null) {
                DebugLog.w(this, "CaptureService: no camera available for video - skipping");
                videoDone = true;
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

            Handler callbackHandler = videoHandler != null ? videoHandler : photoHandler;
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    videoCameraDevice = camera;
                    createVideoCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.w(TAG, "Video camera disconnected");
                    camera.close();
                    videoCameraDevice = null;
                    handleVideoError("disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "Video camera error: " + error);
                    camera.close();
                    videoCameraDevice = null;
                    handleVideoError("error " + error);
                }
            }, callbackHandler);
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
            Handler callbackHandler = videoHandler != null ? videoHandler : photoHandler;
            videoCameraDevice.createCaptureSession(
                    Collections.singletonList(recorderSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            videoSession = session;
                            startVideoRecording(recorderSurface);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Video capture session configure failed");
                            handleVideoError("configure failed");
                        }
                    }, callbackHandler);
        } catch (CameraAccessException | RuntimeException e) {
            Log.e(TAG, "createVideoCaptureSession failed", e);
            handleVideoError(e.getMessage());
        }
    }

    private void startVideoRecording(Surface recorderSurface) {
        try {
            CaptureRequest.Builder b = videoCameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_RECORD);
            b.addTarget(recorderSurface);
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            videoSession.setRepeatingRequest(b.build(), null,
                    videoHandler != null ? videoHandler : photoHandler);

            mediaRecorder.start();
            videoRecording = true;
            DebugLog.i(this, "CaptureService: recording video for " + VIDEO_DURATION_MS + "ms");
            Handler h = videoHandler != null ? videoHandler : photoHandler;
            h.postDelayed(this::stopVideoCaptureAndFinalize, VIDEO_DURATION_MS);
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
            if (videoSession != null) {
                try {
                    videoSession.stopRepeating();
                    videoSession.abortCaptures();
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
            DebugLog.i(this, "CaptureService: saved video size=" + completedFile.length() + "B");
        } else {
            DebugLog.w(this, "CaptureService: video not saved");
        }

        closeVideoCameraThen(() -> {
            stoppingVideo = false;
            videoDone = true;
            finalizeCapture();
        });
    }

    private void closeVideoCameraThen(Runnable next) {
        try {
            if (videoSession != null) { videoSession.close(); videoSession = null; }
            if (videoCameraDevice != null) { videoCameraDevice.close(); videoCameraDevice = null; }
        } catch (Throwable t) {
            Log.w(TAG, "closeVideoCameraResources", t);
        }
        Handler h = videoHandler != null ? videoHandler : photoHandler;
        h.postDelayed(next, 500);
    }

    private void handleVideoError(String reason) {
        DebugLog.w(this, "CaptureService: video error: " + reason
                + ", finalizing with photos only");
        videoRecording = false;
        releaseMediaRecorder(true);
        closeVideoCameraThen(() -> {
            videoDone = true;
            finalizeCapture();
        });
    }

    // =========================================================================
    // Finalization — waits for both photo and video to complete
    // =========================================================================

    private void finalizeCapture() {
        // Guard: proceed only when both photo and video work is done.
        // The synchronized block is narrow — just the flag check/set.
        synchronized (this) {
            if (!photoDone || !videoDone) return;
            if (finalized) return;
            finalized = true;
        }

        DebugLog.i(this, "CaptureService: finalizing, total photos=" + photoPaths.size()
                + " video=" + (videoPath != null));

        AttemptLocation location = LocationCapture.captureWithTimeout(this, 5000);
        if (location != null) {
            DebugLog.i(this, "CaptureService: location=" + location.coordinates()
                    + " provider=" + location.provider);
        } else if (Prefs.get(this).isLocationEnabled()) {
            DebugLog.w(this, "CaptureService: location enabled but unavailable after timeout");
        }

        AttemptRecord rec = storage.append(startTimestamp, failedCount, photoPaths,
                videoPath, location);

        Prefs prefs = Prefs.get(this);
        prefs.setLastCyclePhotoPath(photoPaths.isEmpty() ? null : photoPaths.get(0));
        prefs.setLastCycleRecordId(rec.id);
        prefs.setCaptureCompleteInCycle(true);

        boolean notifEnabled = prefs.areNotificationsEnabled();
        DebugLog.i(this, "CaptureService: captureComplete=true notifEnabled=" + notifEnabled
                + " recordId=" + rec.id);

        if (notifEnabled) {
            DebugLog.i(this, "CaptureService: sending notification IMMEDIATELY");
            NotificationHelper.postAlert(this, rec.id);
        } else {
            DebugLog.w(this, "CaptureService: notifications disabled in settings");
        }
        prefs.resetCurrentCycle();

        if (prefs.isEmailEnabled()) {
            DebugLog.i(this, "CaptureService: sending attempt email");
            Context appContext = getApplicationContext();
            EmailAttemptMailer.sendAttemptAsync(appContext, rec, result -> {
                if (result.success) {
                    DebugLog.i(appContext, "CaptureService: email sent: " + result.message);
                } else {
                    DebugLog.w(appContext, "CaptureService: email failed: " + result.message);
                }
            });
        }
        stopAndCleanup();
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    private void stopAndCleanup() {
        if (stopped) return;
        stopped = true;

        Runnable cleanup = () -> {
            try {
                if (photoSession != null) { photoSession.close(); photoSession = null; }
                if (photoCameraDevice != null) { photoCameraDevice.close(); photoCameraDevice = null; }
                if (imageReader != null) { imageReader.close(); imageReader = null; }
            } catch (Throwable t) {
                Log.w(TAG, "cleanup photo camera", t);
            }
            try {
                if (videoSession != null) { videoSession.close(); videoSession = null; }
                if (videoCameraDevice != null) { videoCameraDevice.close(); videoCameraDevice = null; }
            } catch (Throwable t) {
                Log.w(TAG, "cleanup video camera", t);
            }
            if (videoRecording && mediaRecorder != null) {
                try { mediaRecorder.stop(); } catch (RuntimeException ignored) { }
            }
            videoRecording = false;
            stoppingVideo = false;
            releaseMediaRecorder(true);

            new Handler(getMainLooper()).post(() -> {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        };

        if (photoHandler != null) {
            photoHandler.removeCallbacksAndMessages(null);
            if (videoHandler != null) videoHandler.removeCallbacksAndMessages(null);
            photoHandler.post(cleanup);
        } else {
            cleanup.run();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (photoThread != null) { photoThread.quitSafely(); photoThread = null; }
        if (videoThread != null) { videoThread.quitSafely(); videoThread = null; }
    }

    // =========================================================================
    // Static helpers
    // =========================================================================

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
            if (s.getWidth() <= 1280 && s.getHeight() <= 720) return s;
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
            case Surface.ROTATION_90:  return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default:                   return 0;
        }
    }

    private void releaseMediaRecorder(boolean deletePendingFile) {
        MediaRecorder recorder = mediaRecorder;
        mediaRecorder = null;
        if (recorder != null) {
            try { recorder.reset(); } catch (RuntimeException ignored) { }
            try { recorder.release(); } catch (RuntimeException ignored) { }
        }
        if (deletePendingFile && pendingVideoFile != null && pendingVideoFile.exists()
                && !pendingVideoFile.delete()) {
            Log.w(TAG, "Cannot delete failed video file: " + pendingVideoFile);
        }
        pendingVideoFile = null;
    }
}
