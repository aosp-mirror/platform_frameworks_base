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

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.systemui.classifier.Classifier.LOCK_ICON;
import static com.android.systemui.classifier.Classifier.UDFPS_AUTHENTICATION;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Point;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
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
import android.util.RotationUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.FaceAuthApiRequestReason;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.biometrics.dagger.BiometricsBackground;
import com.android.systemui.biometrics.udfps.InteractionEvent;
import com.android.systemui.biometrics.udfps.NormalizedTouchData;
import com.android.systemui.biometrics.udfps.SinglePointerTouchProcessor;
import com.android.systemui.biometrics.udfps.TouchProcessor;
import com.android.systemui.biometrics.udfps.TouchProcessorResult;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.keyguard.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;
import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Provider;

import kotlin.Unit;

/**
 * Shows and hides the under-display fingerprint sensor (UDFPS) overlay, handles UDFPS touch events,
 * and toggles the UDFPS display mode.
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
public class UdfpsController implements DozeReceiver, Dumpable {
    private static final String TAG = "UdfpsController";
    private static final long AOD_SEND_FINGER_UP_DELAY_MILLIS = 1000;

    // Minimum required delay between consecutive touch logs in milliseconds.
    private static final long MIN_TOUCH_LOG_INTERVAL = 50;

    private final Context mContext;
    private final Execution mExecution;
    private final FingerprintManager mFingerprintManager;
    @NonNull private final LayoutInflater mInflater;
    private final WindowManager mWindowManager;
    private final DelayableExecutor mFgExecutor;
    @NonNull private final Executor mBiometricExecutor;
    @NonNull private final ShadeExpansionStateManager mShadeExpansionStateManager;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;
    @NonNull private final DumpManager mDumpManager;
    @NonNull private final SystemUIDialogManager mDialogManager;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final VibratorHelper mVibrator;
    @NonNull private final FeatureFlags mFeatureFlags;
    @NonNull private final FalsingManager mFalsingManager;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final AccessibilityManager mAccessibilityManager;
    @NonNull private final LockscreenShadeTransitionController mLockscreenShadeTransitionController;
    @NonNull private final ConfigurationController mConfigurationController;
    @NonNull private final SystemClock mSystemClock;
    @NonNull private final UnlockedScreenOffAnimationController
            mUnlockedScreenOffAnimationController;
    @NonNull private final LatencyTracker mLatencyTracker;
    @VisibleForTesting @NonNull final BiometricDisplayListener mOrientationListener;
    @NonNull private final ActivityLaunchAnimator mActivityLaunchAnimator;
    @NonNull private final PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @Nullable private final TouchProcessor mTouchProcessor;
    @NonNull private final AlternateBouncerInteractor mAlternateBouncerInteractor;

    // Currently the UdfpsController supports a single UDFPS sensor. If devices have multiple
    // sensors, this, in addition to a lot of the code here, will be updated.
    @VisibleForTesting @NonNull FingerprintSensorPropertiesInternal mSensorProps;
    @VisibleForTesting @NonNull UdfpsOverlayParams mOverlayParams = new UdfpsOverlayParams();
    // TODO(b/229290039): UDFPS controller should manage its dimensions on its own. Remove this.
    @Nullable private Runnable mAuthControllerUpdateUdfpsLocation;
    @Nullable private final AlternateUdfpsTouchProvider mAlternateTouchProvider;
    @Nullable private UdfpsDisplayModeProvider mUdfpsDisplayMode;

    // Tracks the velocity of a touch to help filter out the touches that move too fast.
    @Nullable private VelocityTracker mVelocityTracker;
    // The ID of the pointer for which ACTION_DOWN has occurred. -1 means no pointer is active.
    private int mActivePointerId = -1;
    // The timestamp of the most recent touch log.
    private long mTouchLogTime;
    // Sensor has a capture (good or bad) for this touch. No need to enable the UDFPS display mode
    // anymore for this particular touch event. In other words, do not enable the UDFPS mode until
    // the user touches the sensor area again.
    private boolean mAcquiredReceived;

    // The current request from FingerprintService. Null if no current request.
    @Nullable UdfpsControllerOverlay mOverlay;

    // The fingerprint AOD trigger doesn't provide an ACTION_UP/ACTION_CANCEL event to tell us when
    // to turn off high brightness mode. To get around this limitation, the state of the AOD
    // interrupt is being tracked and a timeout is used as a last resort to turn off high brightness
    // mode.
    private boolean mIsAodInterruptActive;
    @Nullable private Runnable mCancelAodFingerUpAction;
    private boolean mScreenOn;
    private Runnable mAodInterruptRunnable;
    private boolean mOnFingerDown;
    private boolean mAttemptedToDismissKeyguard;
    private final Set<Callback> mCallbacks = new HashSet<>();

    @VisibleForTesting
    public static final VibrationAttributes UDFPS_VIBRATION_ATTRIBUTES =
            new VibrationAttributes.Builder()
                    // vibration will bypass battery saver mode:
                    .setUsage(VibrationAttributes.USAGE_COMMUNICATION_REQUEST)
                    .build();
    @VisibleForTesting
    public static final VibrationAttributes LOCK_ICON_VIBRATION_ATTRIBUTES =
            new VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
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

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.println("mSensorProps=(" + mSensorProps + ")");
    }

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @Override
        public void showUdfpsOverlay(long requestId, int sensorId, int reason,
                @NonNull IUdfpsOverlayControllerCallback callback) {
            mFgExecutor.execute(() -> UdfpsController.this.showUdfpsOverlay(
                    new UdfpsControllerOverlay(mContext, mFingerprintManager, mInflater,
                            mWindowManager, mAccessibilityManager, mStatusBarStateController,
                            mShadeExpansionStateManager, mKeyguardViewManager,
                            mKeyguardUpdateMonitor, mDialogManager, mDumpManager,
                            mLockscreenShadeTransitionController, mConfigurationController,
                            mKeyguardStateController,
                            mUnlockedScreenOffAnimationController,
                            mUdfpsDisplayMode, requestId, reason, callback,
                            (view, event, fromUdfpsView) -> onTouch(requestId, event,
                                    fromUdfpsView), mActivityLaunchAnimator, mFeatureFlags,
                            mPrimaryBouncerInteractor, mAlternateBouncerInteractor)));
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
            if (BiometricFingerprintConstants.shouldDisableUdfpsDisplayMode(acquiredInfo)) {
                boolean acquiredGood = acquiredInfo == FINGERPRINT_ACQUIRED_GOOD;
                mFgExecutor.execute(() -> {
                    if (mOverlay == null) {
                        Log.e(TAG, "Null request when onAcquired for sensorId: " + sensorId
                                + " acquiredInfo=" + acquiredInfo);
                        return;
                    }
                    mAcquiredReceived = true;
                    final UdfpsView view = mOverlay.getOverlayView();
                    if (view != null && isOptical()) {
                        unconfigureDisplay(view);
                    }
                    tryAodSendFingerUp();
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
     * Updates the overlay parameters and reconstructs or redraws the overlay, if necessary.
     *
     * @param sensorProps   sensor for which the overlay is getting updated.
     * @param overlayParams See {@link UdfpsOverlayParams}.
     */
    public void updateOverlayParams(@NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull UdfpsOverlayParams overlayParams) {
        if (mSensorProps.sensorId != sensorProps.sensorId) {
            mSensorProps = sensorProps;
            Log.w(TAG, "updateUdfpsParams | sensorId has changed");
        }

        if (!mOverlayParams.equals(overlayParams)) {
            mOverlayParams = overlayParams;

            final boolean wasShowingAlternateBouncer = mAlternateBouncerInteractor.isVisibleState();

            // When the bounds change it's always necessary to re-create the overlay's window with
            // new LayoutParams. If the overlay needs to be shown, this will re-create and show the
            // overlay with the updated LayoutParams. Otherwise, the overlay will remain hidden.
            redrawOverlay();
            if (wasShowingAlternateBouncer) {
                mKeyguardViewManager.showBouncer(true);
            }
        }
    }

    // TODO(b/229290039): UDFPS controller should manage its dimensions on its own. Remove this.
    public void setAuthControllerUpdateUdfpsLocation(@Nullable Runnable r) {
        mAuthControllerUpdateUdfpsLocation = r;
    }

    public void setUdfpsDisplayMode(@Nullable UdfpsDisplayMode udfpsDisplayMode) {
        mUdfpsDisplayMode = udfpsDisplayMode;
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
                String reason = intent.getStringExtra("reason");
                reason = (reason != null) ? reason : "unknown";
                Log.d(TAG, "ACTION_CLOSE_SYSTEM_DIALOGS received, reason: " + reason
                        + ", mRequestReason: " + mOverlay.getRequestReason());

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
     * @param x                   coordinate
     * @param y                   coordinate
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
                && mOverlayParams.getSensorBounds().contains((int) x, (int) y);
    }

    private Point getTouchInNativeCoordinates(@NonNull MotionEvent event, int idx) {
        Point portraitTouch = new Point(
                (int) event.getRawX(idx),
                (int) event.getRawY(idx)
        );
        final int rot = mOverlayParams.getRotation();
        if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
            RotationUtils.rotatePoint(portraitTouch,
                    RotationUtils.deltaRotation(rot, Surface.ROTATION_0),
                    mOverlayParams.getLogicalDisplayWidth(),
                    mOverlayParams.getLogicalDisplayHeight()
            );
        }

        // Scale the coordinates to native resolution.
        final float scale = mOverlayParams.getScaleFactor();
        portraitTouch.x = (int) (portraitTouch.x / scale);
        portraitTouch.y = (int) (portraitTouch.y / scale);
        return portraitTouch;
    }

    private void tryDismissingKeyguard() {
        if (!mOnFingerDown) {
            playStartHaptic();
        }
        mKeyguardViewManager.notifyKeyguardAuthenticated(false /* strongAuth */);
        mAttemptedToDismissKeyguard = true;
    }

    @VisibleForTesting
    boolean onTouch(long requestId, @NonNull MotionEvent event, boolean fromUdfpsView) {
        if (mFeatureFlags.isEnabled(Flags.UDFPS_NEW_TOUCH_DETECTION)) {
            return newOnTouch(requestId, event, fromUdfpsView);
        } else {
            return oldOnTouch(requestId, event, fromUdfpsView);
        }
    }

    private boolean newOnTouch(long requestId, @NonNull MotionEvent event, boolean fromUdfpsView) {
        if (!fromUdfpsView) {
            Log.e(TAG, "ignoring the touch injected from outside of UdfpsView");
            return false;
        }
        if (mOverlay == null) {
            Log.w(TAG, "ignoring onTouch with null overlay");
            return false;
        }
        if (!mOverlay.matchesRequestId(requestId)) {
            Log.w(TAG, "ignoring stale touch event: " + requestId + " current: "
                    + mOverlay.getRequestId());
            return false;
        }

        final TouchProcessorResult result = mTouchProcessor.processTouch(event, mActivePointerId,
                mOverlayParams);
        if (result instanceof TouchProcessorResult.Failure) {
            Log.w(TAG, ((TouchProcessorResult.Failure) result).getReason());
            return false;
        }

        final TouchProcessorResult.ProcessedTouch processedTouch =
                (TouchProcessorResult.ProcessedTouch) result;
        final NormalizedTouchData data = processedTouch.getTouchData();

        mActivePointerId = processedTouch.getPointerOnSensorId();
        switch (processedTouch.getEvent()) {
            case DOWN:
                if (shouldTryToDismissKeyguard()) {
                    tryDismissingKeyguard();
                }
                onFingerDown(requestId,
                        data.getPointerId(),
                        data.getX(),
                        data.getY(),
                        data.getMinor(),
                        data.getMajor(),
                        data.getOrientation(),
                        data.getTime(),
                        data.getGestureStart(),
                        mStatusBarStateController.isDozing());
                break;

            case UP:
            case CANCEL:
                if (InteractionEvent.CANCEL.equals(processedTouch.getEvent())) {
                    Log.w(TAG, "This is a CANCEL event that's reported as an UP event!");
                }
                mAttemptedToDismissKeyguard = false;
                onFingerUp(requestId,
                        mOverlay.getOverlayView(),
                        data.getPointerId(),
                        data.getX(),
                        data.getY(),
                        data.getMinor(),
                        data.getMajor(),
                        data.getOrientation(),
                        data.getTime(),
                        data.getGestureStart(),
                        mStatusBarStateController.isDozing());
                mFalsingManager.isFalseTouch(UDFPS_AUTHENTICATION);
                break;


            default:
                break;
        }

        // We should only consume touches that are within the sensor. By returning "false" for
        // touches outside of the sensor, we let other UI components consume these events and act on
        // them appropriately.
        return processedTouch.getTouchData().isWithinSensor(mOverlayParams.getNativeSensorBounds());
    }

    private boolean oldOnTouch(long requestId, @NonNull MotionEvent event, boolean fromUdfpsView) {
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
        boolean handled = false;
        switch (event.getActionMasked()) {
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

                final boolean withinSensorArea =
                        isWithinSensorArea(udfpsView, event.getX(), event.getY(), fromUdfpsView);
                if (withinSensorArea) {
                    Trace.beginAsyncSection("UdfpsController.e2e.onPointerDown", 0);
                    Log.v(TAG, "onTouch | action down");
                    // The pointer that causes ACTION_DOWN is always at index 0.
                    // We need to persist its ID to track it during ACTION_MOVE that could include
                    // data for many other pointers because of multi-touch support.
                    mActivePointerId = event.getPointerId(0);
                    mVelocityTracker.addMovement(event);
                    handled = true;
                    mAcquiredReceived = false;
                }
                if ((withinSensorArea || fromUdfpsView) && shouldTryToDismissKeyguard()) {
                    Log.v(TAG, "onTouch | dismiss keyguard ACTION_DOWN");
                    tryDismissingKeyguard();
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
                    final boolean actionMoveWithinSensorArea =
                            isWithinSensorArea(udfpsView, event.getX(idx), event.getY(idx),
                                    fromUdfpsView);
                    if ((fromUdfpsView || actionMoveWithinSensorArea)
                            && shouldTryToDismissKeyguard()) {
                        Log.v(TAG, "onTouch | dismiss keyguard ACTION_MOVE");
                        tryDismissingKeyguard();
                        break;
                    }
                    // Map the touch to portrait mode if the device is in landscape mode.
                    final Point scaledTouch = getTouchInNativeCoordinates(event, idx);
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

                        if (!mOnFingerDown && !mAcquiredReceived && !exceedsVelocityThreshold) {
                            final float scale = mOverlayParams.getScaleFactor();
                            float scaledMinor = minor / scale;
                            float scaledMajor = major / scale;
                            onFingerDown(requestId, scaledTouch.x, scaledTouch.y, scaledMinor,
                                    scaledMajor);

                            Log.v(TAG, "onTouch | finger down: " + touchInfo);
                            mTouchLogTime = mSystemClock.elapsedRealtime();
                            handled = true;
                        } else if (sinceLastLog >= MIN_TOUCH_LOG_INTERVAL) {
                            Log.v(TAG, "onTouch | finger move: " + touchInfo);
                            mTouchLogTime = mSystemClock.elapsedRealtime();
                        }
                    } else {
                        Log.v(TAG, "onTouch | finger outside");
                        onFingerUp(requestId, udfpsView);
                        // Maybe announce for accessibility.
                        mFgExecutor.execute(() -> {
                            if (mOverlay == null) {
                                Log.e(TAG, "touch outside sensor area received"
                                        + "but serverRequest is null");
                                return;
                            }
                            // Scale the coordinates to native resolution.
                            final float scale = mOverlayParams.getScaleFactor();
                            final float scaledSensorX =
                                    mOverlayParams.getSensorBounds().centerX() / scale;
                            final float scaledSensorY =
                                    mOverlayParams.getSensorBounds().centerY() / scale;

                            mOverlay.onTouchOutsideOfSensorArea(
                                    scaledTouch.x, scaledTouch.y, scaledSensorX, scaledSensorY,
                                    mOverlayParams.getRotation());
                        });
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
            @NonNull ShadeExpansionStateManager shadeExpansionStateManager,
            @NonNull StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @NonNull DumpManager dumpManager,
            @NonNull KeyguardUpdateMonitor keyguardUpdateMonitor,
            @NonNull FeatureFlags featureFlags,
            @NonNull FalsingManager falsingManager,
            @NonNull PowerManager powerManager,
            @NonNull AccessibilityManager accessibilityManager,
            @NonNull LockscreenShadeTransitionController lockscreenShadeTransitionController,
            @NonNull ScreenLifecycle screenLifecycle,
            @NonNull VibratorHelper vibrator,
            @NonNull UdfpsHapticsSimulator udfpsHapticsSimulator,
            @NonNull UdfpsShell udfpsShell,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull DisplayManager displayManager,
            @Main Handler mainHandler,
            @NonNull ConfigurationController configurationController,
            @NonNull SystemClock systemClock,
            @NonNull UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            @NonNull SystemUIDialogManager dialogManager,
            @NonNull LatencyTracker latencyTracker,
            @NonNull ActivityLaunchAnimator activityLaunchAnimator,
            @NonNull Optional<Provider<AlternateUdfpsTouchProvider>> alternateTouchProvider,
            @NonNull @BiometricsBackground Executor biometricsExecutor,
            @NonNull PrimaryBouncerInteractor primaryBouncerInteractor,
            @NonNull SinglePointerTouchProcessor singlePointerTouchProcessor,
            @NonNull AlternateBouncerInteractor alternateBouncerInteractor) {
        mContext = context;
        mExecution = execution;
        mVibrator = vibrator;
        mInflater = inflater;
        // The fingerprint manager is queried for UDFPS before this class is constructed, so the
        // fingerprint manager should never be null.
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mFgExecutor = fgExecutor;
        mShadeExpansionStateManager = shadeExpansionStateManager;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mKeyguardViewManager = statusBarKeyguardViewManager;
        mDumpManager = dumpManager;
        mDialogManager = dialogManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mFeatureFlags = featureFlags;
        mFalsingManager = falsingManager;
        mPowerManager = powerManager;
        mAccessibilityManager = accessibilityManager;
        mLockscreenShadeTransitionController = lockscreenShadeTransitionController;
        screenLifecycle.addObserver(mScreenObserver);
        mScreenOn = screenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
        mConfigurationController = configurationController;
        mSystemClock = systemClock;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mLatencyTracker = latencyTracker;
        mActivityLaunchAnimator = activityLaunchAnimator;
        mAlternateTouchProvider = alternateTouchProvider.map(Provider::get).orElse(null);
        mSensorProps = new FingerprintSensorPropertiesInternal(
                -1 /* sensorId */,
                SensorProperties.STRENGTH_CONVENIENCE,
                0 /* maxEnrollmentsPerUser */,
                new ArrayList<>() /* componentInfo */,
                FingerprintSensorProperties.TYPE_UNKNOWN,
                false /* resetLockoutRequiresHardwareAuthToken */);

        mBiometricExecutor = biometricsExecutor;
        mPrimaryBouncerInteractor = primaryBouncerInteractor;
        mAlternateBouncerInteractor = alternateBouncerInteractor;

        mTouchProcessor = mFeatureFlags.isEnabled(Flags.UDFPS_NEW_TOUCH_DETECTION)
                ? singlePointerTouchProcessor : null;

        mDumpManager.registerDumpable(TAG, this);

        mOrientationListener = new BiometricDisplayListener(
                context,
                displayManager,
                mainHandler,
                BiometricDisplayListener.SensorType.UnderDisplayFingerprint.INSTANCE,
                () -> {
                    if (mAuthControllerUpdateUdfpsLocation != null) {
                        mAuthControllerUpdateUdfpsLocation.run();
                    }
                    return Unit.INSTANCE;
                });

        final UdfpsOverlayController mUdfpsOverlayController = new UdfpsOverlayController();
        mFingerprintManager.setUdfpsOverlayController(mUdfpsOverlayController);

        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mBroadcastReceiver, filter,
                Context.RECEIVER_EXPORTED_UNAUDITED);

        udfpsHapticsSimulator.setUdfpsController(this);
        udfpsShell.setUdfpsOverlayController(mUdfpsOverlayController);
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
                    UDFPS_VIBRATION_ATTRIBUTES);
        }
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

    private void redrawOverlay() {
        UdfpsControllerOverlay overlay = mOverlay;
        if (overlay != null) {
            hideUdfpsOverlay();
            showUdfpsOverlay(overlay);
        }
    }

    private void showUdfpsOverlay(@NonNull UdfpsControllerOverlay overlay) {
        mExecution.assertIsMainThread();

        mOverlay = overlay;
        final int requestReason = overlay.getRequestReason();
        if (requestReason == REASON_AUTH_KEYGUARD
                && !mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
            Log.d(TAG, "Attempting to showUdfpsOverlay when fingerprint detection"
                    + " isn't running on keyguard. Skip show.");
            return;
        }
        if (overlay.show(this, mOverlayParams)) {
            Log.v(TAG, "showUdfpsOverlay | adding window reason=" + requestReason);
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
            mKeyguardViewManager.hideAlternateBouncer(true);
            Log.v(TAG, "hideUdfpsOverlay | removing window: " + removed);
        } else {
            Log.v(TAG, "hideUdfpsOverlay | the overlay is already hidden");
        }

        mOverlay = null;
        mOrientationListener.disable();

    }

    private void unconfigureDisplay(@NonNull UdfpsView view) {
        if (view.isDisplayConfigured()) {
            view.unconfigureDisplay();
        }
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
            if (mFalsingManager.isFalseTouch(LOCK_ICON)) {
                Log.v(TAG, "aod lock icon long-press rejected by the falsing manager.");
                return;
            }
            mKeyguardViewManager.showPrimaryBouncer(true);

            // play the same haptic as the LockIconViewController longpress
            mVibrator.vibrate(
                    Process.myUid(),
                    mContext.getOpPackageName(),
                    UdfpsController.EFFECT_CLICK,
                    "aod-lock-icon-longpress",
                    LOCK_ICON_VIBRATION_ATTRIBUTES);
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
            mCancelAodFingerUpAction = mFgExecutor.executeDelayed(this::tryAodSendFingerUp,
                    AOD_SEND_FINGER_UP_DELAY_MILLIS);
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
     * The sensor that triggers {@link #onAodInterrupt} doesn't emit ACTION_UP or ACTION_CANCEL
     * events, which means the fingerprint gesture created by the AOD interrupt needs to be
     * cancelled manually.
     * This should be called when authentication either succeeds or fails. Failing to cancel the
     * scan will leave the display in the UDFPS mode until the user lifts their finger. On optical
     * sensors, this can result in illumination persisting for longer than necessary.
     */
    @VisibleForTesting
    void tryAodSendFingerUp() {
        if (!mIsAodInterruptActive) {
            return;
        }
        cancelAodSendFingerUpAction();
        if (mOverlay != null && mOverlay.getOverlayView() != null) {
            onFingerUp(mOverlay.getRequestId(), mOverlay.getOverlayView());
        }
    }

    /**
     * Cancels any scheduled AoD finger-up actions without triggered the finger-up action. Only
     * call this method if the finger-up event has been guaranteed to have already occurred.
     */
    @VisibleForTesting
    void cancelAodSendFingerUpAction() {
        mIsAodInterruptActive = false;
        if (mCancelAodFingerUpAction != null) {
            mCancelAodFingerUpAction.run();
            mCancelAodFingerUpAction = null;
        }
    }

    private boolean isOptical() {
        return mSensorProps.sensorType == FingerprintSensorProperties.TYPE_UDFPS_OPTICAL;
    }

    public boolean isFingerDown() {
        return mOnFingerDown;
    }

    private void onFingerDown(
            long requestId,
            int x,
            int y,
            float minor,
            float major) {
        onFingerDown(
                requestId,
                MotionEvent.INVALID_POINTER_ID /* pointerId */,
                x,
                y,
                minor,
                major,
                0f /* orientation */,
                0L /* time */,
                0L /* gestureStart */,
                false /* isAod */);
    }

    private void onFingerDown(
            long requestId,
            int pointerId,
            float x,
            float y,
            float minor,
            float major,
            float orientation,
            long time,
            long gestureStart,
            boolean isAod) {
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
        if (isOptical()) {
            mLatencyTracker.onActionStart(LatencyTracker.ACTION_UDFPS_ILLUMINATE);
        }
        // Refresh screen timeout and boost process priority if possible.
        mPowerManager.userActivity(mSystemClock.uptimeMillis(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);

        if (!mOnFingerDown) {
            playStartHaptic();

            if (!mKeyguardUpdateMonitor.isFaceDetectionRunning()) {
                mKeyguardUpdateMonitor.requestFaceAuth(FaceAuthApiRequestReason.UDFPS_POINTER_DOWN);
            }
        }
        mOnFingerDown = true;
        if (mAlternateTouchProvider != null) {
            mBiometricExecutor.execute(() -> {
                mAlternateTouchProvider.onPointerDown(requestId, (int) x, (int) y, minor, major);
            });
            mFgExecutor.execute(() -> {
                if (mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
                    mKeyguardUpdateMonitor.onUdfpsPointerDown((int) requestId);
                }
            });
        } else {
            if (mFeatureFlags.isEnabled(Flags.UDFPS_NEW_TOUCH_DETECTION)) {
                mFingerprintManager.onPointerDown(requestId, mSensorProps.sensorId, pointerId, x, y,
                        minor, major, orientation, time, gestureStart, isAod);
            } else {
                mFingerprintManager.onPointerDown(requestId, mSensorProps.sensorId, (int) x,
                        (int) y, minor, major);
            }
        }
        Trace.endAsyncSection("UdfpsController.e2e.onPointerDown", 0);
        final UdfpsView view = mOverlay.getOverlayView();
        if (view != null && isOptical()) {
            view.configureDisplay(() -> {
                if (mAlternateTouchProvider != null) {
                    mBiometricExecutor.execute(() -> {
                        mAlternateTouchProvider.onUiReady();
                        mLatencyTracker.onActionEnd(LatencyTracker.ACTION_UDFPS_ILLUMINATE);
                    });
                } else {
                    mFingerprintManager.onUiReady(requestId, mSensorProps.sensorId);
                    mLatencyTracker.onActionEnd(LatencyTracker.ACTION_UDFPS_ILLUMINATE);
                }
            });
        }

        for (Callback cb : mCallbacks) {
            cb.onFingerDown();
        }
    }

    private void onFingerUp(long requestId, @NonNull UdfpsView view) {
        onFingerUp(
                requestId,
                view,
                MotionEvent.INVALID_POINTER_ID /* pointerId */,
                0f /* x */,
                0f /* y */,
                0f /* minor */,
                0f /* major */,
                0f /* orientation */,
                0L /* time */,
                0L /* gestureStart */,
                false /* isAod */);
    }

    private void onFingerUp(
            long requestId,
            @NonNull UdfpsView view,
            int pointerId,
            float x,
            float y,
            float minor,
            float major,
            float orientation,
            long time,
            long gestureStart,
            boolean isAod) {
        mExecution.assertIsMainThread();
        mActivePointerId = -1;
        mAcquiredReceived = false;
        if (mOnFingerDown) {
            if (mAlternateTouchProvider != null) {
                mBiometricExecutor.execute(() -> {
                    mAlternateTouchProvider.onPointerUp(requestId);
                });
                mFgExecutor.execute(() -> {
                    if (mKeyguardUpdateMonitor.isFingerprintDetectionRunning()) {
                        mKeyguardUpdateMonitor.onUdfpsPointerUp((int) requestId);
                    }
                });
            } else {
                if (mFeatureFlags.isEnabled(Flags.UDFPS_NEW_TOUCH_DETECTION)) {
                    mFingerprintManager.onPointerUp(requestId, mSensorProps.sensorId, pointerId, x,
                            y, minor, major, orientation, time, gestureStart, isAod);
                } else {
                    mFingerprintManager.onPointerUp(requestId, mSensorProps.sensorId);
                }
            }
            for (Callback cb : mCallbacks) {
                cb.onFingerUp();
            }
        }
        mOnFingerDown = false;
        if (isOptical()) {
            unconfigureDisplay(view);
        }
        cancelAodSendFingerUpAction();
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
