package com.volumesteps;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

public class VolumeKeyService extends AccessibilityService {

    private static final String TAG = "VolumeKeyService";
    private VolumeStepController controller;
    private VolumeLockController lockController;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private VolumeOverlay overlay;
    private MediaSession mediaSession;
    private VolumeProvider volumeProvider;
    private PowerManager powerManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean initialized = false;
    private Integer holdingKey = null;
    private int repeatCount = 0;
    private VolumeStepController.StepListener stepListener;

    private final Runnable repeatRunnable = new Runnable() {
        @Override public void run() {
            try {
                if (holdingKey == null || controller == null) return;
                if (powerManager != null && !powerManager.isInteractive()) { holdingKey = null; return; }
                if (holdingKey == KeyEvent.KEYCODE_VOLUME_UP) controller.stepUp();
                else if (holdingKey == KeyEvent.KEYCODE_VOLUME_DOWN) controller.stepDown();
                Compat.vibrateTick(vibrator);
                repeatCount++;
                handler.postDelayed(this, repeatCount > 8 ? 40 : 80);
            } catch (Exception e) { Log.e(TAG, "repeat", e); holdingKey = null; }
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "onServiceConnected");
        initialize();
    }

    private synchronized void initialize() {
        cleanup();
        try {
            prefs = getSharedPreferences("volume_steps", MODE_PRIVATE);
            vibrator = Compat.getVibrator(this);
            powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        } catch (Exception e) { Log.e(TAG, "basic init", e); }

        try {
            controller = VolumeStepController.getInstance(this);
            controller.attachSession(0);
            controller.syncFromSystem();
            controller.startObserving();
            stepListener = new VolumeStepController.StepListener() {
                @Override public void onStepChanged(int step, int total, boolean showOverlay) {
                    try { if (volumeProvider != null) volumeProvider.setCurrentVolume(step); } catch (Exception e) {}
                    // Only show the app bar for user-initiated changes while enabled; passive/external
                    // changes (and the disabled state) leave the native bar to handle it on its own.
                    if (!showOverlay) return;
                    try { if (prefs == null || !prefs.getBoolean("enabled", false)) return; } catch (Exception e) { return; }
                    try { if (overlay != null) overlay.show(step, total); }
                    catch (Exception e) { Log.w(TAG, "overlay", e); overlay = null; }
                }
            };
            controller.addStepListener(stepListener);
        } catch (Exception e) { Log.e(TAG, "controller", e); }

        try {
            lockController = VolumeLockController.getInstance(this);
            lockController.enforceAll();
            lockController.startObserving();
        } catch (Exception e) { Log.e(TAG, "lock", e); }

        try {
            if (Settings.canDrawOverlays(this)) {
                overlay = new VolumeOverlay(this);
                overlay.setLockController(lockController);
                overlay.setOnStepSeekListener(new VolumeOverlay.OnStepSeekListener() {
                    @Override public void onStepSeek(int step) {
                        try { if (controller != null) controller.setStep(step); } catch (Exception e) {}
                    }
                });
            }
        } catch (Exception e) { Log.e(TAG, "overlay", e); overlay = null; }

        try { setupMediaSession(); } catch (Exception e) { Log.e(TAG, "media", e); }

        try { prefs.edit().putBoolean("enabled", true).apply(); } catch (Exception e) {}
        initialized = true;
    }

    private void setupMediaSession() {
        if (controller == null) return;
        mediaSession = new MediaSession(this, "VolumeSteps");
        volumeProvider = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE,
                controller.getTotalSteps(), controller.getCurrentStep()) {
            @Override public void onAdjustVolume(int dir) {
                try {
                    if (controller == null) return;
                    if (dir > 0) controller.stepUp(); else if (dir < 0) controller.stepDown();
                    setCurrentVolume(controller.getCurrentStep());
                } catch (Exception e) {}
            }
            @Override public void onSetVolumeTo(int vol) {
                try { if (controller == null) return; controller.setStep(vol); setCurrentVolume(vol); }
                catch (Exception e) {}
            }
        };
        mediaSession.setPlaybackToRemote(volumeProvider);
        mediaSession.setPlaybackState(new PlaybackState.Builder()
            .setState(PlaybackState.STATE_PAUSED, 0, 0f)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE).build());
        mediaSession.setActive(true);
    }

    public static void attachAudioSession(android.content.Context ctx, int sid) {
        try { VolumeStepController.getInstance(ctx).attachSession(sid); } catch (Exception e) {}
    }
    public static void detachAudioSession(android.content.Context ctx, int sid) {
        try { VolumeStepController.getInstance(ctx).detachSession(sid); } catch (Exception e) {}
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        try {
            if (!initialized || prefs == null || !prefs.getBoolean("enabled", false) || controller == null) return false;
            int kc = event.getKeyCode();
            if (kc != KeyEvent.KEYCODE_VOLUME_UP && kc != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && holdingKey == null) {
                holdingKey = kc; repeatCount = 0;
                if (kc == KeyEvent.KEYCODE_VOLUME_UP) controller.stepUp(); else controller.stepDown();
                Compat.vibrateTick(vibrator);
                handler.postDelayed(repeatRunnable, 400);
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                holdingKey = null; handler.removeCallbacks(repeatRunnable);
            }
            return true;
        } catch (Exception e) { Log.e(TAG, "key", e); return false; }
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        try { prefs.edit().putBoolean("enabled", false).apply(); } catch (Exception e) {}
        cleanup(); return false;
    }

    private synchronized void cleanup() {
        initialized = false; holdingKey = null;
        handler.removeCallbacksAndMessages(null);
        try { if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); } } catch (Exception e) {}
        mediaSession = null; volumeProvider = null;
        try { if (overlay != null) overlay.hide(); } catch (Exception e) {}
        overlay = null;
        try { if (controller != null && stepListener != null) controller.removeStepListener(stepListener); } catch (Exception e) {}
        try { if (controller != null) controller.release(); } catch (Exception e) {}
        try { if (lockController != null) lockController.release(); } catch (Exception e) {}
        controller = null; lockController = null; stepListener = null;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() { holdingKey = null; handler.removeCallbacks(repeatRunnable); }
}
