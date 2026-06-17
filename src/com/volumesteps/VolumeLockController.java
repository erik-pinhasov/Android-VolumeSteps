package com.volumesteps;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;

/**
 * Per-stream volume locking, VolumeLockr-style. There is no real OS-level lock, so we
 * watch volume changes via a ContentObserver and snap any locked stream back to its
 * stored level whenever it drifts. Convergence is by value comparison: we only revert
 * when the current level differs from the locked level, so a self-triggered observer
 * callback naturally no-ops (same pattern VolumeStepController uses with lastSystemVol).
 *
 * Locks are enforced whenever the accessibility service is connected, independent of the
 * volume-steps "enabled" toggle.
 */
public class VolumeLockController {

    private static final String TAG = "VolumeLockCtrl";

    // Managed streams, in display order: Media, Ring, Notification, Alarm, Call.
    public static final int[] MANAGED = {
        AudioManager.STREAM_MUSIC,        // 3
        AudioManager.STREAM_RING,         // 2
        AudioManager.STREAM_NOTIFICATION, // 5
        AudioManager.STREAM_ALARM,        // 4
        AudioManager.STREAM_VOICE_CALL    // 0
    };

    public static String labelFor(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC:        return "Media";
            case AudioManager.STREAM_RING:         return "Ring";
            case AudioManager.STREAM_NOTIFICATION: return "Notification";
            case AudioManager.STREAM_ALARM:        return "Alarm";
            case AudioManager.STREAM_VOICE_CALL:   return "Call";
            default:                               return "Stream " + stream;
        }
    }

    /** Crisp vector icon (res/drawable) for a stream, used in the app and the overlay. */
    public static int iconResFor(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC:        return R.drawable.ic_vol_media;
            case AudioManager.STREAM_RING:         return R.drawable.ic_vol_ring;
            case AudioManager.STREAM_NOTIFICATION: return R.drawable.ic_vol_notification;
            case AudioManager.STREAM_ALARM:        return R.drawable.ic_vol_alarm;
            case AudioManager.STREAM_VOICE_CALL:   return R.drawable.ic_vol_call;
            default:                               return R.drawable.ic_vol_media;
        }
    }

    private final Context context;
    private final AudioManager audioManager;
    private final SharedPreferences prefs;
    // Enforcement (setStreamVolume on every settings change) runs off the main thread so a burst of
    // volume changes can't ANR the accessibility service. See VolumeStepController for the same fix.
    private final HandlerThread workerThread;
    private final Handler handler;
    private volatile boolean selfChanging = false;

    private final Runnable enforceRunnable = new Runnable() {
        @Override public void run() { enforceAll(); }
    };

    private final ContentObserver volumeObserver;

    private VolumeLockController(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.prefs = context.getSharedPreferences("volume_steps", Context.MODE_PRIVATE);
        this.workerThread = new HandlerThread("VolumeLockWorker");
        this.workerThread.start();
        this.handler = new Handler(workerThread.getLooper());
        this.volumeObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                if (selfChanging) return;
                // Coalesce bursts: re-enforce once after things settle, not per change.
                handler.removeCallbacks(enforceRunnable);
                handler.postDelayed(enforceRunnable, 60);
            }
        };
    }

    private static volatile VolumeLockController instance;
    public static VolumeLockController getInstance(Context ctx) {
        if (instance == null) {
            synchronized (VolumeLockController.class) {
                if (instance == null) instance = new VolumeLockController(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    public boolean isLocked(int stream) { return prefs.getBoolean("lock_" + stream, false); }
    public int getLockedLevel(int stream) { return prefs.getInt("lock_level_" + stream, getVolume(stream)); }

    public int getVolume(int stream) {
        try { return audioManager.getStreamVolume(stream); } catch (Exception e) { return 0; }
    }
    public int getMaxVolume(int stream) {
        try { return audioManager.getStreamMaxVolume(stream); } catch (Exception e) { return 0; }
    }

    /** Lock/unlock a stream. On lock, snapshots the current level as the locked level. */
    public void setLock(int stream, boolean locked) {
        SharedPreferences.Editor e = prefs.edit().putBoolean("lock_" + stream, locked);
        if (locked) e.putInt("lock_level_" + stream, getVolume(stream));
        e.apply();
        if (locked) enforce(stream);
    }

    /** Update the level a stream is pinned to. Applied immediately when locked. */
    public void setLockedLevel(int stream, int level) {
        int max = getMaxVolume(stream);
        int clamped = Math.max(0, Math.min(level, max));
        prefs.edit().putInt("lock_level_" + stream, clamped).apply();
        if (isLocked(stream)) enforce(stream);
    }

    public void startObserving() {
        try { context.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver); }
        catch (Exception e) { Log.w(TAG, "observe failed", e); }
    }
    public void stopObserving() {
        try { context.getContentResolver().unregisterContentObserver(volumeObserver); }
        catch (Exception e) {}
    }

    public void enforceAll() {
        for (int stream : MANAGED) if (isLocked(stream)) enforce(stream);
    }

    private void enforce(int stream) {
        try {
            int target = getLockedLevel(stream);
            if (getVolume(stream) == target) return;
            selfChanging = true;
            try { audioManager.setStreamVolume(stream, target, 0); }
            catch (Exception e) { /* e.g. SecurityException under DND; best-effort */ }
            selfChanging = false;
        } catch (Exception e) { Log.w(TAG, "enforce error", e); }
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        try { stopObserving(); } catch (Exception e) {}
        try { workerThread.quitSafely(); } catch (Exception e) {}
        synchronized (VolumeLockController.class) { instance = null; }
    }
}
