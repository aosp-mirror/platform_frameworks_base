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

import static android.app.StatusBarManager.SESSION_BIOMETRIC_PROMPT;
import static android.app.StatusBarManager.SESSION_KEYGUARD;
import static android.hardware.biometrics.BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_GOOD;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_BP;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_AUTH_KEYGUARD;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_ENROLLING;
import static android.hardware.biometrics.BiometricRequestConstants.REASON_ENROLL_FIND_SENSOR;

import static com.android.internal.util.LatencyTracker.ACTION_UDFPS_ILLUMINATE;
import static com.android.internal.util.Preconditions.checkNotNull;
import static com.android.systemui.classifier.Classifier.UDFPS_AUTHENTICATION;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.hardware.biometrics.BiometricFingerprintConstants;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.SensorProperties;
import android.hardware.display.DisplayManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.hardware.fingerprint.IUdfpsOverlayControllerCallback;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Trace;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.InstanceId;
import com.android.internal.util.LatencyTracker;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.biometrics.dagger.BiometricsBackground;
import com.android.systemui.biometrics.domain.interactor.UdfpsOverlayInteractor;
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams;
import com.android.systemui.biometrics.udfps.InteractionEvent;
import com.android.systemui.biometrics.udfps.NormalizedTouchData;
import com.android.systemui.biometrics.udfps.SinglePointerTouchProcessor;
import com.android.systemui.biometrics.udfps.TouchProcessor;
import com.android.systemui.biometrics.udfps.TouchProcessorResult;
import com.android.systemui.biometrics.ui.viewmodel.DefaultUdfpsTouchOverlayViewModel;
import com.android.systemui.biometrics.ui.viewmodel.DeviceEntryUdfpsTouchOverlayViewModel;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor;
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor;
import com.android.systemui.doze.DozeReceiver;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.log.SessionTracker;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.LockscreenShadeTransitionController;
import com.android.systemui.statusbar.VibratorHelper;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.SystemUIDialogManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;
import com.android.systemui.util.time.SystemClock;

import dagger.Lazy;

import kotlin.Unit;

import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.ExperimentalCoroutinesApi;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

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

    private static final long MIN_UNCHANGED_INTERACTION_LOG_INTERVAL = 50;

    private final Context mContext;
    private final Execution mExecution;
    private final FingerprintManager mFingerprintManager;
    @NonNull private final LayoutInflater mInflater;
    private final WindowManager mWindowManager;
    private final DelayableExecutor mFgExecutor;
    @NonNull private final Executor mBiometricExecutor;
    @NonNull private final StatusBarStateController mStatusBarStateController;
    @NonNull private final KeyguardStateController mKeyguardStateController;
    @NonNull private final StatusBarKeyguardViewManager mKeyguardViewManager;
    @NonNull private final DumpManager mDumpManager;
    @NonNull private final SystemUIDialogManager mDialogManager;
    @NonNull private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @NonNull private final DeviceEntryFaceAuthInteractor mDeviceEntryFaceAuthInteractor;
    @NonNull private final VibratorHelper mVibrator;
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
    @NonNull private final ActivityTransitionAnimator mActivityTransitionAnimator;
    @NonNull private final PrimaryBouncerInteractor mPrimaryBouncerInteractor;
    @NonNull private final ShadeInteractor mShadeInteractor;
    @Nullable private final TouchProcessor mTouchProcessor;
    @NonNull private final SessionTracker mSessionTracker;
    @NonNull private final Lazy<DeviceEntryUdfpsTouchOverlayViewModel>
            mDeviceEntryUdfpsTouchOverlayViewModel;
    @NonNull private final Lazy<DefaultUdfpsTouchOverlayViewModel>
            mDefaultUdfpsTouchOverlayViewModel;
    @NonNull private final AlternateBouncerInteractor mAlternateBouncerInteractor;
    @NonNull private final UdfpsOverlayInteractor mUdfpsOverlayInteractor;
    @NonNull private final PowerInteractor mPowerInteractor;
    @NonNull private final CoroutineScope mScope;
    @NonNull private final InputManager mInputManager;
    @NonNull private final UdfpsKeyguardAccessibilityDelegate mUdfpsKeyguardAccessibilityDelegate;
    @NonNull private final SelectedUserInteractor mSelectedUserInteractor;
    @NonNull private final FpsUnlockTracker mFpsUnlockTracker;
    private final boolean mIgnoreRefreshRate;
    private final KeyguardTransitionInteractor mKeyguardTransitionInteractor;

    // Currently the UdfpsController supports a single UDFPS sensor. If devices have multiple
    // sensors, this, in addition to a lot of the code here, will be updated.
    @VisibleForTesting @NonNull FingerprintSensorPropertiesInternal mSensorProps;
    @VisibleForTesting @NonNull UdfpsOverlayParams mOverlayParams = new UdfpsOverlayParams();
    // TODO(b/229290039): UDFPS controller should manage its dimensions on its own. Remove this.
    @Nullable private Runnable mAuthControllerUpdateUdfpsLocation;
    @Nullable private UdfpsDisplayModeProvider mUdfpsDisplayMode;

    // The ID of the pointer for which ACTION_DOWN has occurred. -1 means no pointer is active.
    private int mActivePointerId = MotionEvent.INVALID_POINTER_ID;
    // Whether a pointer has been pilfered for current gesture
    private boolean mPointerPilfered = false;
    // The timestamp of the most recent touch log.
    private long mTouchLogTime;
    // The timestamp of the most recent log of a touch InteractionEvent.
    private long mLastTouchInteractionTime;
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

    public static final int LONG_PRESS = HapticFeedbackConstants.LONG_PRESS;

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
        final int touchConfigId = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_selected_udfps_touch_detection);
        pw.println("mSensorProps=(" + mSensorProps + ")");
        pw.println("touchConfigId: " + touchConfigId);
    }

    public class UdfpsOverlayController extends IUdfpsOverlayController.Stub {
        @OptIn(markerClass = ExperimentalCoroutinesApi.class)
        @Override
        public void showUdfpsOverlay(long requestId, int sensorId, int reason,
                @NonNull IUdfpsOverlayControllerCallback callback) {
            mFgExecutor.execute(() -> UdfpsController.this.showUdfpsOverlay(
                    new UdfpsControllerOverlay(
                        mContext,
                        mInflater,
                        mWindowManager,
                        mAccessibilityManager,
                        mStatusBarStateController,
                        mKeyguardViewManager,
                        mKeyguardUpdateMonitor,
                        mDialogManager,
                        mDumpManager,
                        mLockscreenShadeTransitionController,
                        mConfigurationController,
                        mKeyguardStateController,
                        mUnlockedScreenOffAnimationController,
                        mUdfpsDisplayMode,
                        requestId,
                        reason,
                        callback,
                        (view, event, fromUdfpsView) -> onTouch(
                            requestId,
                            event,
                            fromUdfpsView
                        ),
                            mActivityTransitionAnimator,
                        mPrimaryBouncerInteractor,
                        mAlternateBouncerInteractor,
                        mUdfpsKeyguardAccessibilityDelegate,
                        mKeyguardTransitionInteractor,
                        mSelectedUserInteractor,
                        mDeviceEntryUdfpsTouchOverlayViewModel,
                        mDefaultUdfpsTouchOverlayViewModel,
                        mShadeInteractor,
                        mUdfpsOverlayInteractor,
                        mPowerInteractor,
                        mScope
                    )));
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
                    final View view = mOverlay.getTouchOverlay();
                    unconfigureDisplay(view);
                    tryAodSendFingerUp();
                });
            }
        }

        @Override
        public void onEnrollmentProgress(int sensorId, int remaining) { }

        @Override
        public void onEnrollmentHelp(int sensorId) { }

        @Override
        public void setDebugMessage(int sensorId, String message) {
            mFgExecutor.execute(() -> {
                if (mOverlay == null || mOverlay.isHiding()) {
                    return;
                }
                if (!DeviceEntryUdfpsRefactor.isEnabled()) {
                    ((UdfpsView) mOverlay.getTouchOverlay()).setDebugMessage(message);
                }
            });
        }

        public Rect getSensorBounds() {
            return mOverlayParams.getSensorBounds();
        }

        /**
         * Passes a mocked MotionEvent to OnTouch.
         *
         * @param event MotionEvent to simulate in onTouch
         */
        public void debugOnTouch(MotionEvent event) {
            final long requestId = (mOverlay != null) ? mOverlay.getRequestId() : 0L;
            UdfpsController.this.onTouch(requestId, event, true);
        }

        /**
         * Debug to run onUiReady
         */
        public void debugOnUiReady(int sensorId) {
            final long requestId = (mOverlay != null) ? mOverlay.getRequestId() : 0L;
            UdfpsController.this.mFingerprintManager.onUdfpsUiEvent(
                    FingerprintManager.UDFPS_UI_READY, requestId, sensorId);
        }

        /**
         * Debug to show biometric prompt
         */
        public void debugBiometricPrompt() {
            final BiometricPrompt.AuthenticationCallback authenticationCallback =
                    new BiometricPrompt.AuthenticationCallback() {
                    };

            final BiometricPrompt biometricPrompt = new BiometricPrompt.Builder(mContext)
                    .setTitle("Test")
                    .setDeviceCredentialAllowed(true)
                    .setAllowBackgroundAuthentication(true)
                    .build();
            final Handler handler = new Handler(Looper.getMainLooper());
            biometricPrompt.authenticate(
                    new CancellationSignal(),
                    handler::post,
                    authenticationCallback);
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

            if (DeviceEntryUdfpsRefactor.isEnabled()) {
                if (mOverlay != null && mOverlay.getRequestReason() == REASON_AUTH_KEYGUARD) {
                    mOverlay.updateOverlayParams(mOverlayParams);
                } else {
                    redrawOverlay();
                }
            } else {
                final boolean wasShowingAlternateBouncer =
                        mAlternateBouncerInteractor.isVisibleState();
                // When the bounds change it's always to re-create the overlay's window with new
                // LayoutParams. If the overlay needs to be shown, this will re-create and show the
                // overlay with the updated LayoutParams. Otherwise, the overlay will remain hidden.
                redrawOverlay();
                if (wasShowingAlternateBouncer) {
                    mKeyguardViewManager.showBouncer(true);
                }
            }
        }
    }

    // TODO(b/229290039): UDFPS controller should manage its dimensions on its own. Remove this.
    public void setAuthControllerUpdateUdfpsLocation(@Nullable Runnable r) {
        mAuthControllerUpdateUdfpsLocation = r;
    }

    public void setUdfpsDisplayMode(@NonNull UdfpsDisplayModeProvider udfpsDisplayMode) {
        mUdfpsDisplayMode = udfpsDisplayMode;
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

    private void tryDismissingKeyguard() {
        if (!mOnFingerDown) {
            playStartHaptic();
        }
        mKeyguardViewManager.notifyKeyguardAuthenticated(false /* primaryAuth */);
        mAttemptedToDismissKeyguard = true;
    }

    private int getBiometricSessionType() {
        if (mOverlay == null) {
            return -1;
        }
        switch (mOverlay.getRequestReason()) {
            case REASON_AUTH_KEYGUARD:
                return SESSION_KEYGUARD;
            case REASON_AUTH_BP:
                return SESSION_BIOMETRIC_PROMPT;
            case REASON_ENROLL_FIND_SENSOR:
            case REASON_ENROLL_ENROLLING:
                // TODO(b/255634916): create a reason for enrollment (or an "unknown" reason).
                return SESSION_BIOMETRIC_PROMPT << 1;
            default:
                return -1;
        }
    }

    private static int toBiometricTouchReportedTouchType(InteractionEvent event) {
        switch (event) {
            case DOWN:
                return SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED__TOUCH_TYPE__TOUCH_TYPE_DOWN;
            case UP:
                return SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED__TOUCH_TYPE__TOUCH_TYPE_UP;
            case CANCEL:
                return SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED__TOUCH_TYPE__TOUCH_TYPE_CANCEL;
            default:
                return SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED__TOUCH_TYPE__TOUCH_TYPE_UNCHANGED;
        }
    }

    private void logBiometricTouch(InteractionEvent event, NormalizedTouchData data) {
        if (event == InteractionEvent.UNCHANGED) {
            long sinceLastLog = mSystemClock.elapsedRealtime() - mLastTouchInteractionTime;
            if (sinceLastLog < MIN_UNCHANGED_INTERACTION_LOG_INTERVAL) {
                return;
            }
        }
        mLastTouchInteractionTime = mSystemClock.elapsedRealtime();

        final int biometricTouchReportedTouchType = toBiometricTouchReportedTouchType(event);
        final InstanceId sessionIdProvider = mSessionTracker.getSessionId(
                getBiometricSessionType());
        final int sessionId = (sessionIdProvider != null) ? sessionIdProvider.getId() : -1;
        final int touchConfigId = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_selected_udfps_touch_detection);

        SysUiStatsLog.write(SysUiStatsLog.BIOMETRIC_TOUCH_REPORTED, biometricTouchReportedTouchType,
                touchConfigId, sessionId, data.getX(), data.getY(), data.getMinor(),
                data.getMajor(), data.getOrientation(), data.getTime(), data.getGestureStart(),
                mStatusBarStateController.isDozing());

        if (Build.isDebuggable()) {
            Log.d(TAG, data.toPrettyString(event.toString()));
            Log.d(TAG, "sessionId: " + sessionId
                    + ", isAod: " + mStatusBarStateController.isDozing()
                    + ", touchConfigId: " + touchConfigId);
        }
    }

    private boolean onTouch(long requestId, @NonNull MotionEvent event, boolean fromUdfpsView) {
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
        if (event.getAction() == MotionEvent.ACTION_DOWN
                || event.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
            // Reset on ACTION_DOWN, start of new gesture
            mPointerPilfered = false;
            if (mActivePointerId != MotionEvent.INVALID_POINTER_ID) {
                Log.w(TAG, "onTouch down received without a preceding up");
            }
            mActivePointerId = MotionEvent.INVALID_POINTER_ID;
            mOnFingerDown = false;
        } else if (!DeviceEntryUdfpsRefactor.isEnabled()) {
            if ((mLockscreenShadeTransitionController.getQSDragProgress() != 0f
                    && !mAlternateBouncerInteractor.isVisibleState())
                    || mPrimaryBouncerInteractor.isInTransit()) {
                Log.w(TAG, "ignoring touch due to qsDragProcess or primaryBouncerInteractor");
                return false;
            }
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

        boolean shouldPilfer = false;
        mActivePointerId = processedTouch.getPointerOnSensorId();
        switch (processedTouch.getEvent()) {
            case DOWN:
                if (shouldTryToDismissKeyguard()) {
                    tryDismissingKeyguard();
                }
                if (!mOnFingerDown) {
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
                }

                // Pilfer if valid overlap, don't allow following events to reach keyguard
                shouldPilfer = true;

                // Touch is a valid UDFPS touch. Inform the falsing manager so that the touch
                // isn't counted against the falsing algorithm as an accidental touch.
                // We do this on the DOWN event instead of CANCEL/UP because the CANCEL/UP events
                // get sent too late to this receiver (after the actual cancel/up motions occur),
                // and therefore wouldn't end up being used as part of the falsing algo.
                mFalsingManager.isFalseTouch(UDFPS_AUTHENTICATION);
                break;

            case UP:
            case CANCEL:
                if (InteractionEvent.CANCEL.equals(processedTouch.getEvent())) {
                    Log.w(TAG, "This is a CANCEL event that's reported as an UP event!");
                }
                mAttemptedToDismissKeyguard = false;
                onFingerUp(requestId,
                        mOverlay.getTouchOverlay(),
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

            case UNCHANGED:
                if (mActivePointerId == MotionEvent.INVALID_POINTER_ID
                        && mAlternateBouncerInteractor.isVisibleState()) {
                    // No pointer on sensor, forward to keyguard if alternateBouncer is visible
                    mKeyguardViewManager.onTouch(event);
                }

            default:
                break;
        }
        logBiometricTouch(processedTouch.getEvent(), data);

        // Always pilfer pointers that are within sensor area or when alternate bouncer is showing
        if (mActivePointerId != MotionEvent.INVALID_POINTER_ID
                || (mAlternateBouncerInteractor.isVisibleState()
                && !DeviceEntryUdfpsRefactor.isEnabled())) {
            shouldPilfer = true;
        }

        // Pilfer only once per gesture, don't pilfer for BP
        if (shouldPilfer && !mPointerPilfered
                && getBiometricSessionType() != SESSION_BIOMETRIC_PROMPT) {
            mInputManager.pilferPointers(
                    mOverlay.getTouchOverlay().getViewRootImpl().getInputToken());
            mPointerPilfered = true;
        }

        return mActivePointerId != MotionEvent.INVALID_POINTER_ID;
    }

    private boolean shouldTryToDismissKeyguard() {
        boolean onKeyguard = false;
        if (DeviceEntryUdfpsRefactor.isEnabled()) {
            onKeyguard = mKeyguardStateController.isShowing();
        } else {
            onKeyguard = mOverlay != null
                    && mOverlay.getAnimationViewController()
                        instanceof UdfpsKeyguardViewControllerLegacy;
        }
        return onKeyguard
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
            @NonNull UdfpsShell udfpsShell,
            @NonNull KeyguardStateController keyguardStateController,
            @NonNull DisplayManager displayManager,
            @Main Handler mainHandler,
            @NonNull ConfigurationController configurationController,
            @NonNull SystemClock systemClock,
            @NonNull UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            @NonNull SystemUIDialogManager dialogManager,
            @NonNull LatencyTracker latencyTracker,
            @NonNull ActivityTransitionAnimator activityTransitionAnimator,
            @NonNull @BiometricsBackground Executor biometricsExecutor,
            @NonNull PrimaryBouncerInteractor primaryBouncerInteractor,
            @NonNull ShadeInteractor shadeInteractor,
            @NonNull SinglePointerTouchProcessor singlePointerTouchProcessor,
            @NonNull SessionTracker sessionTracker,
            @NonNull AlternateBouncerInteractor alternateBouncerInteractor,
            @NonNull InputManager inputManager,
            @NonNull DeviceEntryFaceAuthInteractor deviceEntryFaceAuthInteractor,
            @NonNull UdfpsKeyguardAccessibilityDelegate udfpsKeyguardAccessibilityDelegate,
            @NonNull SelectedUserInteractor selectedUserInteractor,
            @NonNull FpsUnlockTracker fpsUnlockTracker,
            @NonNull KeyguardTransitionInteractor keyguardTransitionInteractor,
            Lazy<DeviceEntryUdfpsTouchOverlayViewModel> deviceEntryUdfpsTouchOverlayViewModel,
            Lazy<DefaultUdfpsTouchOverlayViewModel> defaultUdfpsTouchOverlayViewModel,
            @NonNull UdfpsOverlayInteractor udfpsOverlayInteractor,
            @NonNull PowerInteractor powerInteractor,
            @Application CoroutineScope scope) {
        mContext = context;
        mExecution = execution;
        mVibrator = vibrator;
        mInflater = inflater;
        mIgnoreRefreshRate = mContext.getResources()
                    .getBoolean(R.bool.config_ignoreUdfpsVote);
        // The fingerprint manager is queried for UDFPS before this class is constructed, so the
        // fingerprint manager should never be null.
        mFingerprintManager = checkNotNull(fingerprintManager);
        mWindowManager = windowManager;
        mFgExecutor = fgExecutor;
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
        screenLifecycle.addObserver(mScreenObserver);
        mScreenOn = screenLifecycle.getScreenState() == ScreenLifecycle.SCREEN_ON;
        mConfigurationController = configurationController;
        mSystemClock = systemClock;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;
        mLatencyTracker = latencyTracker;
        mActivityTransitionAnimator = activityTransitionAnimator;
        mSensorProps = new FingerprintSensorPropertiesInternal(
                -1 /* sensorId */,
                SensorProperties.STRENGTH_CONVENIENCE,
                0 /* maxEnrollmentsPerUser */,
                new ArrayList<>() /* componentInfo */,
                FingerprintSensorProperties.TYPE_UNKNOWN,
                false /* resetLockoutRequiresHardwareAuthToken */);

        mBiometricExecutor = biometricsExecutor;
        mPrimaryBouncerInteractor = primaryBouncerInteractor;
        mShadeInteractor = shadeInteractor;
        mAlternateBouncerInteractor = alternateBouncerInteractor;
        mUdfpsOverlayInteractor = udfpsOverlayInteractor;
        mPowerInteractor = powerInteractor;
        mScope = scope;
        mInputManager = inputManager;
        mUdfpsKeyguardAccessibilityDelegate = udfpsKeyguardAccessibilityDelegate;
        mSelectedUserInteractor = selectedUserInteractor;
        mFpsUnlockTracker = fpsUnlockTracker;
        mFpsUnlockTracker.startTracking();
        mKeyguardTransitionInteractor = keyguardTransitionInteractor;

        mTouchProcessor = singlePointerTouchProcessor;
        mSessionTracker = sessionTracker;
        mDeviceEntryUdfpsTouchOverlayViewModel = deviceEntryUdfpsTouchOverlayViewModel;
        mDefaultUdfpsTouchOverlayViewModel = defaultUdfpsTouchOverlayViewModel;

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
        mDeviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor;

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
            if (mOverlay != null && mOverlay.getTouchOverlay() != null) {
                mVibrator.performHapticFeedback(
                        mOverlay.getTouchOverlay(),
                        HapticFeedbackConstants.CONTEXT_CLICK
                );
            } else {
                Log.e(TAG, "No haptics played. Could not obtain overlay view to perform"
                        + "vibration. Either the controller overlay is null or has no view");
            }
        }
    }

    @Override
    public void dozeTimeTick() {
        if (mOverlay != null && mOverlay.getTouchOverlay() instanceof UdfpsView) {
            DeviceEntryUdfpsRefactor.assertInLegacyMode();
            final View view = mOverlay.getTouchOverlay();
            if (view != null) {
                ((UdfpsView) view).dozeTimeTick();
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
            if (mFingerprintManager != null) {
                mFingerprintManager.onUdfpsUiEvent(FingerprintManager.UDFPS_UI_OVERLAY_SHOWN,
                        overlay.getRequestId(), mSensorProps.sensorId);
            }
        } else {
            Log.v(TAG, "showUdfpsOverlay | the overlay is already showing");
        }
    }

    private void hideUdfpsOverlay() {
        mExecution.assertIsMainThread();

        if (mOverlay != null) {
            // Reset the controller back to its starting state.
            final View oldView = mOverlay.getTouchOverlay();
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

    private void unconfigureDisplay(View view) {
        if (!isOptical()) {
            return;
        }
        if (DeviceEntryUdfpsRefactor.isEnabled()) {
            if (mUdfpsDisplayMode != null) {
                mUdfpsDisplayMode.disable(null);
            }
        } else {
            if (view != null) {
                UdfpsView udfpsView = (UdfpsView) view;
                if (udfpsView.isDisplayConfigured()) {
                    udfpsView.unconfigureDisplay();
                }
            }
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
            if (mFalsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)) {
                Log.v(TAG, "aod lock icon long-press rejected by the falsing manager.");
                return;
            }
            mKeyguardViewManager.showPrimaryBouncer(true);

            // play the same haptic as the DeviceEntryIcon longpress
            if (mOverlay != null && mOverlay.getTouchOverlay() != null) {
                mVibrator.performHapticFeedback(
                        mOverlay.getTouchOverlay(),
                        UdfpsController.LONG_PRESS
                );
            } else {
                Log.e(TAG, "No haptics played. Could not obtain overlay view to perform"
                        + "vibration. Either the controller overlay is null or has no view");
            }
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
            // using a hard-coded value for orientation, time and gestureStart until they are
            // available from the sensor.
            onFingerDown(
                    requestId,
                    MotionEvent.INVALID_POINTER_ID /* pointerId */,
                    screenX,
                    screenY,
                    minor,
                    major,
                    0f /* orientation */,
                    0L /* time */,
                    0L /* gestureStart */,
                    true /* isAod */);
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
        if (mOverlay != null && mOverlay.getTouchOverlay() != null) {
            onFingerUp(mOverlay.getRequestId(), mOverlay.getTouchOverlay());
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

    private void dispatchOnUiReady(long requestId) {
        mFingerprintManager.onUdfpsUiEvent(FingerprintManager.UDFPS_UI_READY, requestId,
                mSensorProps.sensorId);
        mLatencyTracker.onActionEnd(LatencyTracker.ACTION_UDFPS_ILLUMINATE);
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
            mLatencyTracker.onActionStart(ACTION_UDFPS_ILLUMINATE);
        }
        if (getBiometricSessionType() == SESSION_KEYGUARD) {
            mFpsUnlockTracker.onUiReadyStage();
        }
        // Refresh screen timeout and boost process priority if possible.
        mPowerManager.userActivity(mSystemClock.uptimeMillis(),
                PowerManager.USER_ACTIVITY_EVENT_TOUCH, 0);

        if (!mOnFingerDown) {
            playStartHaptic();

            mDeviceEntryFaceAuthInteractor.onUdfpsSensorTouched();
        }
        mOnFingerDown = true;
        mFingerprintManager.onPointerDown(requestId, mSensorProps.sensorId, pointerId, x, y,
                minor, major, orientation, time, gestureStart, isAod);
        Trace.endAsyncSection("UdfpsController.e2e.onPointerDown", 0);

        final View view = mOverlay.getTouchOverlay();
        if (view != null && isOptical()) {
            if (mIgnoreRefreshRate) {
                dispatchOnUiReady(requestId);
            } else {
                if (DeviceEntryUdfpsRefactor.isEnabled()) {
                    mUdfpsDisplayMode.enable(() -> dispatchOnUiReady(requestId));
                } else {
                    ((UdfpsView) view).configureDisplay(() -> dispatchOnUiReady(requestId));
                }
            }
        }

        for (Callback cb : mCallbacks) {
            cb.onFingerDown();
        }
    }

    private void onFingerUp(long requestId, @NonNull View view) {
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
            View view,
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
        mActivePointerId = MotionEvent.INVALID_POINTER_ID;
        mAcquiredReceived = false;
        if (mOnFingerDown) {
            mFingerprintManager.onPointerUp(requestId, mSensorProps.sensorId, pointerId, x,
                    y, minor, major, orientation, time, gestureStart, isAod);
            for (Callback cb : mCallbacks) {
                cb.onFingerUp();
            }
        }
        mOnFingerDown = false;
        unconfigureDisplay(view);
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
