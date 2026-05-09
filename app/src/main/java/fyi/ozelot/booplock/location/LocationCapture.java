package fyi.ozelot.booplock.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import fyi.ozelot.booplock.data.AttemptLocation;
import fyi.ozelot.booplock.data.Prefs;
import fyi.ozelot.booplock.debug.DebugLog;

public final class LocationCapture {

    private LocationCapture() {
    }

    public static boolean hasLocationPermission(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canReadLocation(Context ctx) {
        return Prefs.get(ctx).isLocationEnabled()
                && hasLocationPermission(ctx)
                && isLocationEnabled(ctx);
    }

    /**
     * Attempts to get the current location, blocking up to {@code timeoutMs} for a fresh fix.
     *
     * Strategy:
     *  1. If a cached last-known location exists and is < 5 minutes old, return it immediately.
     *  2. Otherwise, request a single active update from the network provider (fast, 1-3s).
     *  3. If no update arrives within the timeout, fall back to whatever is cached.
     *
     * Must be called from a background thread. Callbacks are delivered on the main looper.
     */
    @WorkerThread
    @Nullable
    @SuppressWarnings("MissingPermission")
    public static AttemptLocation captureWithTimeout(Context ctx, long timeoutMs) {
        boolean settingEnabled = Prefs.get(ctx).isLocationEnabled();
        boolean permGranted = hasLocationPermission(ctx);
        boolean systemEnabled = isLocationEnabled(ctx);
        DebugLog.i(ctx, "LOC canRead: setting=" + settingEnabled
                + " perm=" + permGranted
                + " systemOn=" + systemEnabled);

        if (!settingEnabled || !permGranted || !systemEnabled) return null;

        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            DebugLog.w(ctx, "LOC LocationManager is null");
            return null;
        }

        AttemptLocation lastKnown = getLastKnownLocation(ctx);
        if (lastKnown != null) {
            long ageMs = System.currentTimeMillis() - lastKnown.timestampMs;
            DebugLog.i(ctx, "LOC lastKnown: " + lastKnown.coordinates()
                    + " age=" + (ageMs / 1000) + "s provider=" + lastKnown.provider);
            if (ageMs < 5 * 60_000L) {
                DebugLog.i(ctx, "LOC using cached fix (recent)");
                return lastKnown;
            }
        } else {
            DebugLog.i(ctx, "LOC no lastKnown available");
        }

        List<String> providers = lm.getProviders(true);
        DebugLog.i(ctx, "LOC active providers: " + providers);
        if (providers.isEmpty()) {
            DebugLog.w(ctx, "LOC no active providers, returning lastKnown");
            return lastKnown;
        }

        String provider = providers.contains(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER
                : providers.get(0);
        DebugLog.i(ctx, "LOC requesting fresh fix from provider=" + provider
                + " timeout=" + timeoutMs + "ms API=" + Build.VERSION.SDK_INT);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Location> ref = new AtomicReference<>();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                CancellationSignal cancel = new CancellationSignal();
                lm.getCurrentLocation(provider, cancel,
                        ContextCompat.getMainExecutor(ctx),
                        location -> {
                            if (location != null) {
                                ref.set(location);
                                DebugLog.i(ctx, "LOC getCurrentLocation callback: "
                                        + location.getLatitude() + "," + location.getLongitude()
                                        + " acc=" + (location.hasAccuracy() ? location.getAccuracy() + "m" : "?"));
                            } else {
                                DebugLog.w(ctx, "LOC getCurrentLocation callback: null");
                            }
                            latch.countDown();
                        });
                boolean arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                DebugLog.i(ctx, "LOC latch done: arrived=" + arrived);
                try { cancel.cancel(); } catch (RuntimeException ignored) { }
            } else {
                LocationListener listener = location -> {
                    ref.set(location);
                    DebugLog.i(ctx, "LOC requestSingleUpdate callback: "
                            + location.getLatitude() + "," + location.getLongitude()
                            + " acc=" + (location.hasAccuracy() ? location.getAccuracy() + "m" : "?"));
                    latch.countDown();
                };
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                boolean arrived = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
                DebugLog.i(ctx, "LOC latch done: arrived=" + arrived);
                try { lm.removeUpdates(listener); } catch (RuntimeException ignored) { }
            }
        } catch (InterruptedException e) {
            DebugLog.w(ctx, "LOC interrupted while waiting for fix");
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            DebugLog.e(ctx, "LOC RuntimeException: " + e.getMessage());
        }

        Location fresh = ref.get();
        if (fresh != null) {
            DebugLog.i(ctx, "LOC returning fresh fix");
            return new AttemptLocation(
                    fresh.getLatitude(),
                    fresh.getLongitude(),
                    fresh.hasAccuracy() ? fresh.getAccuracy() : -1,
                    fresh.getTime(),
                    fresh.getProvider());
        }
        DebugLog.w(ctx, "LOC no fresh fix received, returning lastKnown=" + (lastKnown != null));
        return lastKnown;
    }

    @Nullable
    @SuppressWarnings("MissingPermission")
    public static AttemptLocation getLastKnownLocation(Context ctx) {
        if (!canReadLocation(ctx)) return null;

        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;

        Location best = null;
        try {
            List<String> providers = lm.getProviders(true);
            for (String provider : providers) {
                Location candidate = lm.getLastKnownLocation(provider);
                if (isBetter(candidate, best)) {
                    best = candidate;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }

        if (best == null) return null;
        return new AttemptLocation(
                best.getLatitude(),
                best.getLongitude(),
                best.hasAccuracy() ? best.getAccuracy() : -1,
                best.getTime(),
                best.getProvider());
    }

    private static boolean isBetter(@Nullable Location candidate, @Nullable Location current) {
        if (candidate == null) return false;
        if (current == null) return true;
        if (candidate.getTime() > current.getTime() + 120_000) return true;
        if (current.getTime() > candidate.getTime() + 120_000) return false;
        if (!candidate.hasAccuracy()) return false;
        if (!current.hasAccuracy()) return true;
        return candidate.getAccuracy() < current.getAccuracy();
    }

    private static boolean isLocationEnabled(Context ctx) {
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return lm.isLocationEnabled();
            }
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
