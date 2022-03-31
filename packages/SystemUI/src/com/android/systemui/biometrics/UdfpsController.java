/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics;

import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD;
import static android.hardware.biometrics.BiometricOverlayConstants.REASON_AUTH_KEYGUARD;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.systemui.classifier.Classifier.UDFPS_AUTHENTICATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.phone.panelstate.PanelExpansionStateManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;
import com.android.systemui.util.time.SystemClock;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

import kotlin.Unit;

/**
 * Shows and hides the under-display fingerprint sensor (UDFPS) overlay, handles UDFPS touch events,
 * and coordinates triggering of the high-brightness mode (HBM).
 *
 * Note that the current architecture is designed so that a single {@link UdfpsController}
 * controls/manages all UDFPS sensors. In other words, a single controller is registered with
 * {@link com.android.server.biometrics.sensors.fingerprint.FingerprintService}, and interfaces such
 * as {@link FingerprintManager#onPointerDown(long, int, int, int, float, float)} or
 * {@link IUdfpsOverlayController#showUdfpsOverlay} should all have
 * {@code sensorId} parameters.
 */
@SuppressWarnings("deprecation")
@SysUISingleton
public class UdfpsController implements DozeReceiver {
    private static final String TAG = "UdfpsController";
    private static final long AOD_INTERRUPT_TIMEOUT_MILLIS = 1000;
    private static final long DEFAULT_VIBRATION_DURATION = 1000; // milliseconds

    // Minimum required delay between consecutive touch logs in milliseconds.
    private static final long MIN_TOUCH_LOG_INTERVAL = 50;

    private final Context mContext;
    private final Execution mExecution;
    private final FingerprintManager mFingerprintManager;
    @NonNull private final LayoutInflater mInflater;
    private final WindowManager mWindowManager;
    private final DelayableExecutor mFgExecutor;
    @NonNull private final PanelExpansionStateManager mPanelExpansionStateManager;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;
    @NonNull private final DumpManager mDumpManager;
    @NonNull private final SystemUIDialogManager mDialogManager;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final VibratorHelper mVibrator;
    @NonNull private final FalsingManager mFalsingManager;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final AccessibilityManager mAccessibilityManager;
    @NonNull private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @Nullable private final UdfpsHbmProvider mHbmProvider;
    @NonNull private final KeyguardBypassController mKeyguardBypassController;
    @NonNull private final ConfigurationController mConfigurationController;
    @NonNull private final SystemClock mSystemClock;
    @NonNull private final UnlockedScreenOffAnimationController
            mUnlockedScreenOffAnimationController;
    @NonNull private final LatencyTracker mLatencyTracker;
    @VisibleForTesting @NonNull final BiometricDisplayListener mOrientationListener;
    // Currently the UdfpsController supports a single UDFPS sensor. If devices have multiple
    // sensors, this, in addition to a lot of the code here, will be updated.
    @VisibleForTesting final FingerprintSensorPropertiesInternal mSensorProps;
    @NonNull private final ActivityLaunchAnimator mActivityLaunchAnimator;
    @Nullable private final AlternateUdfpsTouchProvider mAlternateTouchProvider;

    // Tracks the velocity of a touch to help filter out the touches that move too fast.
    @Nullable private VelocityTracker mVelocityTracker;
    // The ID of the pointer for which ACTION_DOWN has occurred. -1 means no pointer is active.
    private int mActivePointerId = -1;
    // The timestamp of the most recent touch log.
    private long mTouchLogTime;
    // Sensor has a capture (good or bad) for this touch. Do not need to illuminate for this
    // particular touch event anymore. In other words, do not illuminate until user lifts and
    // touches the sensor area again.
    // TODO: We should probably try to make touch/illumination things more of a FSM
    private boolean mAcquiredReceived;

    // The current request from FingerprintService. Null if no current request.
    @Nullable UdfpsControllerOverlay mOverlay;

    // The fingerprint AOD trigger doesn't provide an ACTION_UP/ACTION_CANCEL event to tell us when
    // to turn off high brightness mode. To get around this limitation, the state of the AOD
    // interrupt is being tracked and a timeout is used as a last resort to turn off high brightness
    // mode.
    private boolean mIsAodInterruptActive;
    @Nullable private Runnable mCancelAodTimeoutAction;
    private boolean mScreenOn;
    private Runnable mAodInterruptRunnable;
    private boolean mOnFingerDown;
    private boolean mAttemptedToDismissKeyguard;
    private final Set<Callback> mCallbacks = new HashSet<>();

    @VisibleForTesting
    public static final VibrationAttributes VIBRATION_ATTRIBUTES =
            new VibrationAttributes.Builder()
                    // vibration will bypass battery saver mode:
                    .setUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                    .build();

    // haptic to use for successful device entry
    public static final VibrationEffect EFFECT_CLICK =
            VibrationEffect.get(VibrationEffect.EFFECT_CLICK);

    private final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
        @Override
        public void onScreenTurnedOn() {
            mScreenOn = true;
            if (mAodInterruptRunnable != null) {
                mAodInterruptRunnable.run();
                mAodInterruptRunnable = null;
            }
        }

        @Override
        public void onScreenTurnedOff() {
            mScreenOn = false;
        }
    };

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @Override
        public void showUdfpsOverlay(long requestId, int sensorId, int reason,
                @NonNull IUdfpsOverlayControllerCallback callback) {
            mFgExecutor.execute(
                    () -> UdfpsController.this.showUdfpsOverlay(new UdfpsControllerOverlay(
                            mContext, mFingerprintManager, mInflater, mWindowManager,
                            mAccessibilityManager, mStatusBarStateController,
                            mPanelExpansionStateManager, mKeyguardViewManager,
                            mKeyguardUpdateMonitor, mDialogManager, mDumpManager,
                            mLockscreenShadeTransitionController, mConfigurationController,
                            mSystemClock, mKeyguardStateController,
                            mUnlockedScreenOffAnimationController, mSensorProps,
                            mHbmProvider, requestId, reason, callback,
                            (view, event, fromUdfpsView) ->
                                   onTouch(requestId, event, fromUdfpsView),
                            mActivityLaunchAnimator)));
        }

        @Override
        public void hideUdfpsOverlay(int sensorId) {
            mFgExecutor.execute(() -> {
                if (mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
                    // if we get here, we expect keyguardUpdateMonitor's fingerprintRunningState
                    // to be updated shortly afterwards
                    Log.d(TAG, "hiding udfps overlay when "
                            + "mKeyguardUpdateMonitor.isFingerprintDetectionRunning()=true");
                }

                UdfpsController.this.hideUdfpsOverlay();
            });
        }

        @Override
        public void onAcquired(
                int sensorId,
                @BiometricFingerprintConstants.FingerprintAcquired int acquiredInfo
        ) {
            if (BiometricFingerprintConstants.shouldTurnOffHbm(acquiredInfo)) {
                boolean acquiredGood = acquiredInfo == FINGERPRINT_ACQUIRED_GOOD;
                mFgExecutor.execute(() -> {
                    if (mOverlay == null) {
                        Log.e(TAG, "Null request when onAcquired for sensorId: " + sensorId
                                + " acquiredInfo=" + acquiredInfo);
                        return;
                    }
                    mAcquiredReceived = true;
                    final UdfpsView view = mOverlay.getOverlayView();
                    if (view != null) {
                        view.stopIllumination(); // turn off HBM
                    }
                    if (acquiredGood) {
                        mOverlay.onAcquiredGood();
                    }
                });
            }
        }

        @Override
        public void onEnrollmentProgress(int sensorId, int remaining) {
            mFgExecutor.execute(() -> {
                if (mOverlay == null) {
                    Log.e(TAG, "onEnrollProgress received but serverRequest is null");
                    return;
                }
                mOverlay.onEnrollmentProgress(remaining);
            });
        }

        @Override
        public void onEnrollmentHelp(int sensorId) {
            mFgExecutor.execute(() -> {
                if (mOverlay == null) {
                    Log.e(TAG, "onEnrollmentHelp received but serverRequest is null");
                    return;
                }
                mOverlay.onEnrollmentHelp();
            });
        }

        @Override
        public void setDebugMessage(int sensorId, String message) {
            mFgExecutor.execute(() -> {
                if (mOverlay == null || mOverlay.isHiding()) {
                    return;
                }
                mOverlay.getOverlayView().setDebugMessage(message);
            });
        }
    }

    /**
     * Calculate the pointer speed given a velocity tracker and the pointer id.
     * This assumes that the velocity tracker has already been passed all relevant motion events.
     */
    public static float computePointerSpeed(@NonNull VelocityTracker tracker, int pointerId) {
        final float vx = tracker.getXVelocity(pointerId);
        final float vy = tracker.getYVelocity(pointerId);
        return (float) Math.sqrt(Math.pow(vx, 2.0) + Math.pow(vy, 2.0));
    }

    /**
     * Whether the velocity exceeds the acceptable UDFPS debouncing threshold.
     */
    public static boolean exceedsVelocityThreshold(float velocity) {
        return velocity > 750f;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mOverlay != null
                    && mOverlay.getRequestReason() != REASON_AUTH_KEYGUARD
                    && Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                Log.d(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS received, mRequestReason: "
                        + mOverlay.getRequestReason());
                mOverlay.cancel();
                hideUdfpsOverlay();
            }
        }
    };

    /**
     * Forwards touches to the udfps controller / view
     */
    public boolean onTouch(MotionEvent event) {
        if (mOverlay == null || mOverlay.isHiding()) {
            return false;
        }
        // TODO(b/225068271): may not be correct but no way to get the id yet
        return onTouch(mOverlay.getRequestId(), event, false);
    }

    /**
     * @param x coordinate
     * @param y coordinate
     * @param relativeToUdfpsView true if the coordinates are relative to the udfps view; else,
     *                            calculate from the display dimensions in portrait orientation
     */
    private boolean isWithinSensorArea(UdfpsView udfpsView, float x, float y,
            boolean relativeToUdfpsView) {
        if (relativeToUdfpsView) {
            // TODO: move isWithinSensorArea to UdfpsController.
            return udfpsView.isWithinSensorArea(x, y);
        }

        if (mOverlay == null || mOverlay.getAnimationViewController() == null) {
            return false;
        }

        return !mOverlay.getAnimationViewController().shouldPauseAuth()
                && getSensorLocation().contains(x, y);
    }

    private boolean onTouch(long requestId, @NonNull MotionEvent event, boolean fromUdfpsView) {
        if (mOverlay == null) {
            Log.w(TAG, "ignoring onTouch with null overlay");
            return false;
        }
        if (!mOverlay.matchesRequestId(requestId)) {
            Log.w(TAG, "ignoring stale touch event: " + requestId + " current: "
                    + mOverlay.getRequestId());
            return false;
        }

        final UdfpsView udfpsView = mOverlay.getOverlayView();
        final boolean isIlluminationRequested = udfpsView.isIlluminationRequested();
        boolean handled = false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_OUTSIDE:
                udfpsView.onTouchOutsideView();
                return true;
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_HOVER_ENTER:
                Trace.beginSection("UdfpsController.onTouch.ACTION_DOWN");
                // To simplify the lifecycle of the velocity tracker, make sure it's never null
                // after ACTION_DOWN, and always null after ACTION_CANCEL or ACTION_UP.
                if (mVelocityTracker == null) {
                    mVelocityTracker = VelocityTracker.obtain();
                } else {
                    // ACTION_UP or ACTION_CANCEL is not guaranteed to be called before a new
                    // ACTION_DOWN, in that case we should just reuse the old instance.
                    mVelocityTracker.clear();
                }

                boolean withinSensorArea =
                        isWithinSensorArea(udfpsView, event.getX(), event.getY(), fromUdfpsView);
                if (withinSensorArea) {
                    mLatencyTracker.onActionStart(LatencyTracker.ACTION_UDFPS_ILLUMINATE);
                    Trace.beginAsyncSection("UdfpsController.e2e.onPointerDown", 0);
                    Log.v(TAG, "onTouch | action down");
                    // The pointer that causes ACTION_DOWN is always at index 0.
                    // We need to persist its ID to track it during ACTION_MOVE that could include
                    // data for many other pointers because of multi-touch support.
                    mActivePointerId = event.getPointerId(0);
                    mVelocityTracker.addMovement(event);
                    handled = true;
                }
                if ((withinSensorArea || fromUdfpsView) && shouldTryToDismissKeyguard()) {
                    Log.v(TAG, "onTouch | dismiss keyguard ACTION_DOWN");
                    if (!mOnFingerDown) {
                        playStartHaptic();
                    }
                    mKeyguardViewManager.notifyKeyguardAuthenticated(false /* strongAuth */);
                    mAttemptedToDismissKeyguard = true;
                }
                Trace.endSection();
                break;

            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_HOVER_MOVE:
                Trace.beginSection("UdfpsController.onTouch.ACTION_MOVE");
                final int idx = mActivePointerId == -1
                        ? event.getPointerId(0)
                        : event.findPointerIndex(mActivePointerId);
                if (idx == event.getActionIndex()) {
                    boolean actionMoveWithinSensorArea =
                            isWithinSensorArea(udfpsView, event.getX(idx), event.getY(idx),
                                    fromUdfpsView);
                    if ((fromUdfpsView || actionMoveWithinSensorArea)
                            && shouldTryToDismissKeyguard()) {
                        Log.v(TAG, "onTouch | dismiss keyguard ACTION_MOVE");
                        if (!mOnFingerDown) {
                            playStartHaptic();
                        }
                        mKeyguardViewManager.notifyKeyguardAuthenticated(false /* strongAuth */);
                        mAttemptedToDismissKeyguard = true;
                        break;
                    }
                    if (actionMoveWithinSensorArea) {
                        if (mVelocityTracker == null) {
                            // touches could be injected, so the velocity tracker may not have
                            // been initialized (via ACTION_DOWN).
                            mVelocityTracker = VelocityTracker.obtain();
                        }
                        mVelocityTracker.addMovement(event);
                        // Compute pointer velocity in pixels per second.
                        mVelocityTracker.computeCurrentVelocity(1000);
                        // Compute pointer speed from X and Y velocities.
                        final float v = computePointerSpeed(mVelocityTracker, mActivePointerId);
                        final float minor = event.getTouchMinor(idx);
                        final float major = event.getTouchMajor(idx);
                        final boolean exceedsVelocityThreshold = exceedsVelocityThreshold(v);
                        final String touchInfo = String.format(
                                "minor: %.1f, major: %.1f, v: %.1f, exceedsVelocityThreshold: %b",
                                minor, major, v, exceedsVelocityThreshold);
                        final long sinceLastLog = mSystemClock.elapsedRealtime() - mTouchLogTime;
                        if (!isIlluminationRequested && !mAcquiredReceived
                                && !exceedsVelocityThreshold) {
                            final int rawX = (int) event.getRawX();
                            final int rawY = (int) event.getRawY();
                            // Default coordinates assume portrait mode.
                            int x = rawX;
                            int y = rawY;

                            // Gets the size based on the current rotation of the display.
                            Point p = new Point();
                            mContext.getDisplay().getRealSize(p);

                            // Transform x, y to portrait mode if the device is in landscape mode.
                            switch (mContext.getDisplay().getRotation()) {
                                case Surface.ROTATION_90:
                                    x = p.y - rawY;
                                    y = rawX;
                                    break;

                                case Surface.ROTATION_270:
                                    x = rawY;
                                    y = p.x - rawX;
                                    break;

                                default:
                                    // Do nothing to stay in portrait mode.
                            }

                            onFingerDown(requestId, x, y, minor, major);
                            Log.v(TAG, "onTouch | finger down: " + touchInfo);
                            mTouchLogTime = mSystemClock.elapsedRealtime();
                            mPowerManager.userActivity(mSystemClock.uptimeMillis(),
                                    PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);
                            handled = true;
                        } else if (sinceLastLog >= MIN_TOUCH_LOG_INTERVAL) {
                            Log.v(TAG, "onTouch | finger move: " + touchInfo);
                            mTouchLogTime = mSystemClock.elapsedRealtime();
                        }
                    } else {
                        Log.v(TAG, "onTouch | finger outside");
                        onFingerUp(requestId, udfpsView);
                    }
                }
                Trace.endSection();
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_HOVER_EXIT:
                Trace.beginSection("UdfpsController.onTouch.ACTION_UP");
                mActivePointerId = -1;
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                Log.v(TAG, "onTouch | finger up");
                mAttemptedToDismissKeyguard = false;
                onFingerUp(requestId, udfpsView);
                mFalsingManager.isFalseTouch(UDFPS_AUTHENTICATION);
                Trace.endSection();
                break;

            default:
                // Do nothing.
        }
        return handled;
    }

    private boolean shouldTryToDismissKeyguard() {
        return mOverlay != null
                && mOverlay.getAnimationViewController() instanceof UdfpsKeyguardViewController
                && mKeyguardStateController.canDismissLockScreen()
                && !mAttemptedToDismissKeyguard;
    }

    @Inject
    public UdfpsController(@NonNull Context context,
            @NonNull Execution execution,
            @NonNull LayoutInflater inflater,
            @Nullable FingerprintManager fingerprintManager,
            @NonNull WindowManager windowManager,
            @NonNull StatusBarStateController statusBarStateController,
            @Main DelayableExecutor fgExecutor,
            @NonNull PanelExpansionStateManager panelExpansionStateManager,
            @NonNull StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull DumpManager dumpManager,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull FalsingManager falsingManager,
            @NonNull PowerManager powerManager,
            @NonNull AccessibilityManager accessibilityManager,
            @NonNull LockscreenShadeTransitionController lockscreenShadeTransitionController,
            @NonNull ScreenLifecycle screenLifecycle,
            @NonNull VibratorHelper vibrator,
            @NonNull UdfpsHapticsSimulator udfpsHapticsSimulator,
            @NonNull Optional<UdfpsHbmProvider> hbmProvider,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull KeyguardBypassController keyguardBypassController,
            @NonNull DisplayManager displayManager,
            @Main Handler mainHandler,
            @NonNull ConfigurationController configurationController,
            @NonNull SystemClock systemClock,
            @NonNull UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            @NonNull SystemUIDialogManager dialogManager,
            @NonNull LatencyTracker latencyTracker,
            @NonNull ActivityLaunchAnimator activityLaunchAnimator,
            @NonNull Optional<AlternateUdfpsTouchProvider> aternateTouchProvider) {
        mContext = context;
        mExecution = execution;
        mVibrator = vibrator;
        mInflater = inflater;
        // The fingerprint manager is queried for UDFPS before this class is constructed, so the
        // fingerprint manager should never be null.
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mFgExecutor = fgExecutor;
        mPanelExpansionStateManager = panelExpansionStateManager;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mKeyguardViewManager = statusBarKeyguardViewManager;
        mDumpManager = dumpManager;
        mDialogManager = dialogManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mFalsingManager = falsingManager;
        mPowerManager = powerManager;
        mAccessibilityManager = accessibilityManager;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        mHbmProvider = hbmProvider.orElse(null);
        screenLifecycle.addObserver(mScreenObserver);
        mScreenOn = screenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
        mKeyguardBypassController = keyguardBypassController;
        mConfigurationController = configurationController;
        mSystemClock = systemClock;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mLatencyTracker = latencyTracker;
        mActivityLaunchAnimator = activityLaunchAnimator;
        mAlternateTouchProvider = aternateTouchProvider.orElse(null);

        mSensorProps = findFirstUdfps();
        // At least one UDFPS sensor exists
        checkArgument(mSensorProps != null);
        mOrientationListener = new BiometricDisplayListener(
                context,
                displayManager,
                mainHandler,
                new BiometricDisplayListener.SensorType.UnderDisplayFingerprint(mSensorProps),
                () -> {
                    onOrientationChanged();
                    return Unit.INSTANCE;
                });

        mFingerprintManager.setUdfpsOverlayController(new UdfpsOverlayController());

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mBroadcastReceiver, filter,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        udfpsHapticsSimulator.setUdfpsController(this);
    }

    /**
     * If a11y touchExplorationEnabled, play haptic to signal UDFPS scanning started.
     */
    @VisibleForTesting
    public void playStartHaptic() {
        if (mAccessibilityManager.isTouchExplorationEnabled()) {
            mVibrator.vibrate(
                    Process.myUid(),
                    mContext.getOpPackageName(),
                    EFFECT_CLICK,
                    "udfps-onStart-click",
                    VIBRATION_ATTRIBUTES);
        }
    }

    @Nullable
    private FingerprintSensorPropertiesInternal findFirstUdfps() {
        for (FingerprintSensorPropertiesInternal props :
                mFingerprintManager.getSensorPropertiesInternal()) {
            if (props.isAnyUdfpsType()) {
                return props;
            }
        }
        return null;
    }

    @Override
    public void dozeTimeTick() {
        if (mOverlay != null) {
            final UdfpsView view = mOverlay.getOverlayView();
            if (view != null) {
                view.dozeTimeTick();
            }
        }
    }

    /**
     * @return where the UDFPS exists on the screen in pixels.
     */
    public RectF getSensorLocation() {
        // This is currently used to calculate the amount of space available for notifications
        // on lockscreen and for the udfps light reveal animation on keyguard.
        // Keyguard is only shown in portrait mode for now, so this will need to
        // be updated if that ever changes.
        final SensorLocationInternal location = mSensorProps.getLocation();
        return new RectF(location.sensorLocationX - location.sensorRadius,
                location.sensorLocationY - location.sensorRadius,
                location.sensorLocationX + location.sensorRadius,
                location.sensorLocationY + location.sensorRadius);
    }

    private void redrawOverlay() {
        UdfpsControllerOverlay overlay = mOverlay;
        if (overlay != null) {
            hideUdfpsOverlay();
            showUdfpsOverlay(overlay);
        }
    }

    private void onOrientationChanged() {
        // When the configuration changes it's almost always necessary to destroy and re-create
        // the overlay's window to pass it the new LayoutParams.
        // Hiding the overlay will destroy its window. It's safe to hide the overlay regardless
        // of whether it is already hidden.
        final boolean wasShowingAltAuth = mKeyguardViewManager.isShowingAlternateAuth();

        // If the overlay needs to be shown, this will re-create and show the overlay with the
        // updated LayoutParams. Otherwise, the overlay will remain hidden.
        redrawOverlay();
        if (wasShowingAltAuth) {
            mKeyguardViewManager.showGenericBouncer(true);
        }
    }

    private void showUdfpsOverlay(@NonNull UdfpsControllerOverlay overlay) {
        mExecution.assertIsMainThread();

        mOverlay = overlay;
        if (overlay.show(this)) {
            Log.v(TAG, "showUdfpsOverlay | adding window reason="
                    + overlay.getRequestReason());
            mOnFingerDown = false;
            mAttemptedToDismissKeyguard = false;
            mOrientationListener.enable();
        } else {
            Log.v(TAG, "showUdfpsOverlay | the overlay is already showing");
        }
    }

    private void hideUdfpsOverlay() {
        mExecution.assertIsMainThread();

        if (mOverlay != null) {
            // Reset the controller back to its starting state.
            final UdfpsView oldView = mOverlay.getOverlayView();
            if (oldView != null) {
                onFingerUp(mOverlay.getRequestId(), oldView);
            }
            final boolean removed = mOverlay.hide();
            if (mKeyguardViewManager.isShowingAlternateAuth()) {
                mKeyguardViewManager.resetAlternateAuth(true);
            }
            Log.v(TAG, "hideUdfpsOverlay | removing window: " + removed);
        } else {
            Log.v(TAG, "hideUdfpsOverlay | the overlay is already hidden");
        }

        mOverlay = null;
        mOrientationListener.disable();
    }

    /**
     * Request fingerprint scan.
     *
     * This is intended to be called in response to a sensor that triggers an AOD interrupt for the
     * fingerprint sensor.
     */
    void onAodInterrupt(int screenX, int screenY, float major, float minor) {
        if (mIsAodInterruptActive) {
            return;
        }

        if (!mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
            mKeyguardViewManager.showBouncer(true);
            return;
        }

        // TODO(b/225068271): this may not be correct but there isn't a way to track it
        final long requestId = mOverlay != null ? mOverlay.getRequestId() : -1;
        mAodInterruptRunnable = () -> {
            mIsAodInterruptActive = true;
            // Since the sensor that triggers the AOD interrupt doesn't provide
            // ACTION_UP/ACTION_CANCEL,  we need to be careful about not letting the screen
            // accidentally remain in high brightness mode. As a mitigation, queue a call to
            // cancel the fingerprint scan.
            mCancelAodTimeoutAction = mFgExecutor.executeDelayed(this::onCancelUdfps,
                    AOD_INTERRUPT_TIMEOUT_MILLIS);
            // using a hard-coded value for major and minor until it is available from the sensor
            onFingerDown(requestId, screenX, screenY, minor, major);
        };

        if (mScreenOn) {
            mAodInterruptRunnable.run();
            mAodInterruptRunnable = null;
        }
    }

    /**
     * Add a callback for fingerUp and fingerDown events
     */
    public void addCallback(Callback cb) {
        mCallbacks.add(cb);
    }

    /**
     * Remove callback
     */
    public void removeCallback(Callback cb) {
        mCallbacks.remove(cb);
    }

    /**
     * Cancel updfs scan affordances - ability to hide the HbmSurfaceView (white circle) before
     * user explicitly lifts their finger. Generally, this should be called whenever udfps fails
     * or errors.
     *
     * The sensor that triggers an AOD fingerprint interrupt (see onAodInterrupt) doesn't give
     * ACTION_UP/ACTION_CANCEL events, so and AOD interrupt scan needs to be cancelled manually.
     * This should be called when authentication either succeeds or fails. Failing to cancel the
     * scan will leave the screen in high brightness mode and will show the HbmSurfaceView until
     * the user lifts their finger.
     */
    void onCancelUdfps() {
        if (mOverlay != null && mOverlay.getOverlayView() != null) {
            onFingerUp(mOverlay.getRequestId(), mOverlay.getOverlayView());
        }
        if (!mIsAodInterruptActive) {
            return;
        }
        if (mCancelAodTimeoutAction != null) {
            mCancelAodTimeoutAction.run();
            mCancelAodTimeoutAction = null;
        }
        mIsAodInterruptActive = false;
    }

    public boolean isFingerDown() {
        return mOnFingerDown;
    }

    private void onFingerDown(long requestId, int x, int y, float minor, float major) {
        mExecution.assertIsMainThread();

        if (mOverlay == null) {
            Log.w(TAG, "Null request in onFingerDown");
            return;
        }
        if (!mOverlay.matchesRequestId(requestId)) {
            Log.w(TAG, "Mismatched fingerDown: " + requestId
                    + " current: " + mOverlay.getRequestId());
            return;
        }

        if (mOverlay.getAnimationViewController() instanceof UdfpsKeyguardViewController
                && !mStatusBarStateController.isDozing()) {
            mKeyguardBypassController.setUserHasDeviceEntryIntent(true);
        }

        if (!mOnFingerDown) {
            playStartHaptic();

            if (!mKeyguardUpdateMonitor.isFaceDetectionRunning()) {
                mKeyguardUpdateMonitor.requestFaceAuth(/* userInitiatedRequest */ false);
            }
        }
        mOnFingerDown = true;
        if (mAlternateTouchProvider != null) {
            mAlternateTouchProvider.onPointerDown(requestId, x, y, minor, major);
        } else {
            mFingerprintManager.onPointerDown(requestId, mSensorProps.sensorId, x, y, minor, major);
        }
        Trace.endAsyncSection("UdfpsController.e2e.onPointerDown", 0);

        final UdfpsView view = mOverlay.getOverlayView();
        if (view != null) {
            Trace.beginAsyncSection("UdfpsController.e2e.startIllumination", 0);
            view.startIllumination(() -> {
                mFingerprintManager.onUiReady(requestId, mSensorProps.sensorId);
                mLatencyTracker.onActionEnd(LatencyTracker.ACTION_UDFPS_ILLUMINATE);
                Trace.endAsyncSection("UdfpsController.e2e.startIllumination", 0);
            });
        }

        for (Callback cb : mCallbacks) {
            cb.onFingerDown();
        }
    }

    private void onFingerUp(long requestId, @NonNull UdfpsView view) {
        mExecution.assertIsMainThread();
        mActivePointerId = -1;
        mAcquiredReceived = false;
        if (mOnFingerDown) {
            if (mAlternateTouchProvider != null) {
                mAlternateTouchProvider.onPointerUp(requestId);
            } else {
                mFingerprintManager.onPointerUp(requestId, mSensorProps.sensorId);
            }
            for (Callback cb : mCallbacks) {
                cb.onFingerUp();
            }
        }
        mOnFingerDown = false;
        if (view.isIlluminationRequested()) {
            view.stopIllumination();
        }
    }

    /**
     * Callback for fingerUp and fingerDown events.
     */
    public interface Callback {
        /**
         * Called onFingerUp events. Will only be called if the finger was previously down.
         */
        void onFingerUp();

        /**
         * Called onFingerDown events.
         */
        void onFingerDown();
    }
}
