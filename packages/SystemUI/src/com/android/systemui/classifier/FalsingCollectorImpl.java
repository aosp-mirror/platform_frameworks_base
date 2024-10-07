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

package com.android.systemui.classifier;

import static com.android.systemui.dock.DockManager.DockEventListener;

import android.hardware.SensorManager;
import android.hardware.biometrics.BiometricSourceType;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.VisibleForTesting;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Flags;
import com.android.systemui.communal.domain.interactor.CommunalInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.kotlin.BooleanFlowOperators;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.sensors.ThresholdSensor;
import com.android.systemui.util.sensors.ThresholdSensorEvent;
import com.android.systemui.util.time.SystemClock;

import dagger.Lazy;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

@SysUISingleton
class FalsingCollectorImpl implements FalsingCollector {

    private static final String TAG = "FalsingCollector";
    private static final String PROXIMITY_SENSOR_TAG = "FalsingCollector";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long GESTURE_PROCESSING_DELAY_MS = 100;

    private final Set<Integer> mAcceptedKeycodes = new HashSet<>(Arrays.asList(
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_SHIFT_LEFT,
        KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_SPACE
    ));

    private final FalsingDataProvider mFalsingDataProvider;
    private final FalsingManager mFalsingManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final HistoryTracker mHistoryTracker;
    private final ProximitySensor mProximitySensor;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
    private final Lazy<ShadeInteractor> mShadeInteractorLazy;
    private final Lazy<CommunalInteractor> mCommunalInteractorLazy;
    private final BatteryController mBatteryController;
    private final DockManager mDockManager;
    private final DelayableExecutor mMainExecutor;
    private final JavaAdapter mJavaAdapter;
    private final SystemClock mSystemClock;
    private final Lazy<SelectedUserInteractor> mUserInteractor;
    private final Lazy<DeviceEntryInteractor> mDeviceEntryInteractor;
    private final Lazy<SceneContainerOcclusionInteractor> mSceneContainerOcclusionInteractor;

    private int mState;
    private boolean mShowingAod;
    private boolean mShowingCommunalHub;
    private boolean mScreenOn;
    private boolean mSessionStarted;
    private MotionEvent mPendingDownEvent;
    private boolean mAvoidGesture;

    private final ThresholdSensor.Listener mSensorEventListener = this::onProximityEvent;

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    logDebug("StatusBarState=" + StatusBarState.toString(newState));
                    mState = newState;
                    updateSessionActive();
                }
            };

    private final KeyguardStateController.Callback mKeyguardStateControllerCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    updateSensorRegistration();
                }
            };


    private final KeyguardUpdateMonitorCallback mKeyguardUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onBiometricAuthenticated(int userId,
                        BiometricSourceType biometricSourceType,
                        boolean isStrongBiometric) {
                    if (userId == mUserInteractor.get().getSelectedUserId()
                            && biometricSourceType == BiometricSourceType.FACE) {
                        mFalsingDataProvider.setJustUnlockedWithFace(true);
                    }
                }
            };


    private final BatteryStateChangeCallback mBatteryListener = new BatteryStateChangeCallback() {
        @Override
        public void onWirelessChargingChanged(boolean isWirelessCharging) {
            if (isWirelessCharging || mDockManager.isDocked()) {
                mProximitySensor.pause();
            } else {
                mProximitySensor.resume();
            }
        }
    };

    private final DockEventListener mDockEventListener = new DockEventListener() {
        @Override
        public void onEvent(int event) {
            if (event == DockManager.STATE_NONE && !mBatteryController.isWirelessCharging()) {
                mProximitySensor.resume();
            } else {
                mProximitySensor.pause();
            }
        }
    };

    @Inject
    FalsingCollectorImpl(
            FalsingDataProvider falsingDataProvider,
            FalsingManager falsingManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            HistoryTracker historyTracker,
            ProximitySensor proximitySensor,
            StatusBarStateController statusBarStateController,
            KeyguardStateController keyguardStateController,
            Lazy<ShadeInteractor> shadeInteractorLazy,
            BatteryController batteryController,
            DockManager dockManager,
            @Main DelayableExecutor mainExecutor,
            JavaAdapter javaAdapter,
            SystemClock systemClock,
            Lazy<SelectedUserInteractor> userInteractor,
            Lazy<CommunalInteractor> communalInteractorLazy,
            Lazy<DeviceEntryInteractor> deviceEntryInteractor,
            Lazy<SceneContainerOcclusionInteractor> sceneContainerOcclusionInteractor) {
        mFalsingDataProvider = falsingDataProvider;
        mFalsingManager = falsingManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mHistoryTracker = historyTracker;
        mProximitySensor = proximitySensor;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mShadeInteractorLazy = shadeInteractorLazy;
        mBatteryController = batteryController;
        mDockManager = dockManager;
        mMainExecutor = mainExecutor;
        mJavaAdapter = javaAdapter;
        mSystemClock = systemClock;
        mUserInteractor = userInteractor;
        mCommunalInteractorLazy = communalInteractorLazy;
        mDeviceEntryInteractor = deviceEntryInteractor;
        mSceneContainerOcclusionInteractor = sceneContainerOcclusionInteractor;
    }

    @Override
    public void init() {
        mProximitySensor.setTag(PROXIMITY_SENSOR_TAG);
        mProximitySensor.setDelay(SensorManager.SENSOR_DELAY_GAME);

        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mState = mStatusBarStateController.getState();

        if (SceneContainerFlag.isEnabled()) {
            mJavaAdapter.alwaysCollectFlow(
                    mDeviceEntryInteractor.get().isDeviceEntered(),
                    this::isDeviceEnteredChanged
            );
            mJavaAdapter.alwaysCollectFlow(
                    mSceneContainerOcclusionInteractor.get().getInvisibleDueToOcclusion(),
                    this::isInvisibleDueToOcclusionChanged
            );
        } else {
            mKeyguardStateController.addCallback(mKeyguardStateControllerCallback);
        }

        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateCallback);

        mJavaAdapter.alwaysCollectFlow(
                mShadeInteractorLazy.get().isQsExpanded(),
                this::onQsExpansionChanged
        );
        final CommunalInteractor communalInteractor = mCommunalInteractorLazy.get();
        mJavaAdapter.alwaysCollectFlow(
                BooleanFlowOperators.INSTANCE.allOf(
                        communalInteractor.isCommunalEnabled(),
                        communalInteractor.isCommunalShowing()),
                this::onShowingCommunalHubChanged
        );

        mBatteryController.addCallback(mBatteryListener);
        mDockManager.addListener(mDockEventListener);
    }

    public void isDeviceEnteredChanged(boolean unused) {
        updateSensorRegistration();
    }

    public void isInvisibleDueToOcclusionChanged(boolean unused) {
        updateSensorRegistration();
    }

    @Override
    public void onSuccessfulUnlock() {
        logDebug("REAL: onSuccessfulUnlock");
        mFalsingManager.onSuccessfulUnlock();
        sessionEnd();
    }

    @Override
    public void setShowingAod(boolean showingAod) {
        logDebug("REAL: setShowingAod(" + showingAod + ")");
        mShowingAod = showingAod;
        updateSessionActive();
    }

    @VisibleForTesting
    void onQsExpansionChanged(Boolean expanded) {
        logDebug("REAL: onQsExpansionChanged(" + expanded + ")");
        if (expanded) {
            unregisterSensors();
        } else if (mSessionStarted) {
            registerSensors();
        }
    }

    private void onShowingCommunalHubChanged(boolean isShowing) {
        logDebug("REAL: onShowingCommunalHubChanged(" + isShowing + ")");
        mShowingCommunalHub = isShowing;
        updateSessionActive();
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return false;
    }

    @Override
    public void onScreenOnFromTouch() {
        logDebug("REAL: onScreenOnFromTouch");
        onScreenTurningOn();
    }

    @Override
    public boolean isReportingEnabled() {
        return false;
    }

    @Override
    public void onScreenTurningOn() {
        logDebug("REAL: onScreenTurningOn");
        mScreenOn = true;
        updateSessionActive();
    }

    @Override
    public void onScreenOff() {
        logDebug("REAL: onScreenOff");
        mScreenOn = false;
        updateSessionActive();
    }

    @Override
    public void onBouncerShown() {
        logDebug("REAL: onBouncerShown");
        unregisterSensors();
    }

    @Override
    public void onBouncerHidden() {
        logDebug("REAL: onBouncerHidden");
        if (mSessionStarted) {
            registerSensors();
        }
    }

    @Override
    public void onKeyEvent(KeyEvent ev) {
        logDebug("REAL: onKeyEvent(" + KeyEvent.actionToString(ev.getAction()) + ")");
        // Only collect if it is an ACTION_UP action and is allow-listed
        if (ev.getAction() == KeyEvent.ACTION_UP && mAcceptedKeycodes.contains(ev.getKeyCode())) {
            mFalsingDataProvider.onKeyEvent(ev);
        }
    }

    @Override
    public void onTouchEvent(MotionEvent ev) {
        logDebug("REAL: onTouchEvent(" + MotionEvent.actionToString(ev.getActionMasked()) + ")");
        if (!isKeyguardShowing()) {
            avoidGesture();
            return;
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
            return;
        }

        // We delay processing down events to see if another component wants to process them.
        // If #avoidGesture is called after a MotionEvent.ACTION_DOWN, all following motion events
        // will be ignored by the collector until another MotionEvent.ACTION_DOWN is passed in.
        // avoidGesture must be called immediately following the MotionEvent.ACTION_DOWN, before
        // any other events are processed, otherwise the whole gesture will be recorded.
        //
        // We should only delay processing of these events for touchscreen sources
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && isTouchscreenSource(ev)) {
            // Make a copy of ev, since it will be recycled after we exit this method.
            mPendingDownEvent = MotionEvent.obtain(ev);
            mAvoidGesture = false;
        } else if (!mAvoidGesture) {
            if (mPendingDownEvent != null) {
                mFalsingDataProvider.onMotionEvent(mPendingDownEvent);
                mPendingDownEvent.recycle();
                mPendingDownEvent = null;
            }
            mFalsingDataProvider.onMotionEvent(ev);
        }
    }

    @Override
    public void onMotionEventComplete() {
        logDebug("REAL: onMotionEventComplete");
        // We must delay processing the completion because of the way Android handles click events.
        // It generally delays executing them immediately, instead choosing to give the UI a chance
        // to respond to touch events before acknowledging the click. As such, we must also delay,
        // giving click handlers a chance to analyze it.
        // You might think we could do something clever to remove this delay - adding non-committed
        // results that can later be changed - but this won't help. Calling the code
        // below can eventually end up in a "Falsing Event" being fired. If we remove the delay
        // here, we would still have to add the delay to the event, but we'd also have to make all
        // the intervening code more complicated in the process. This is the simplest insertion
        // point for the delay.
        mMainExecutor.executeDelayed(
                mFalsingDataProvider::onMotionEventComplete, GESTURE_PROCESSING_DELAY_MS);
    }

    @Override
    public void avoidGesture() {
        logDebug("REAL: avoidGesture");
        mAvoidGesture = true;
        if (mPendingDownEvent != null) {
            mPendingDownEvent.recycle();
            mPendingDownEvent = null;
        }
    }

    @Override
    public void cleanup() {
        logDebug("REAL: cleanup");
        unregisterSensors();
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mBatteryController.removeCallback(mBatteryListener);
        mDockManager.removeListener(mDockEventListener);
    }

    @Override
    public void updateFalseConfidence(FalsingClassifier.Result result) {
        logDebug("REAL: updateFalseConfidence(" + result.isFalse() + ")");
        mHistoryTracker.addResults(Collections.singleton(result), mSystemClock.uptimeMillis());
    }

    @Override
    public void onA11yAction() {
        logDebug("REAL: onA11yAction");
        if (mPendingDownEvent != null) {
            mPendingDownEvent.recycle();
            mPendingDownEvent = null;
        }
        mFalsingDataProvider.onA11yAction();
    }

    /**
     * returns {@code true} if the device supports Touchscreen, {@code false} otherwise. Defaults to
     * {@code true} if the device is {@code null}
     */
    private boolean isTouchscreenSource(MotionEvent ev) {
        if (!Flags.nonTouchscreenDevicesBypassFalsing()) {
            return true;
        }
        InputDevice device = ev.getDevice();
        if (device != null) {
            return device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN);
        } else {
            return true;
        }
    }

    private boolean shouldSessionBeActive() {
        return mScreenOn
                && (mState == StatusBarState.KEYGUARD)
                && !mShowingAod
                && !mShowingCommunalHub;
    }

    private void updateSessionActive() {
        if (shouldSessionBeActive()) {
            sessionStart();
        } else {
            sessionEnd();
        }
        updateSensorRegistration();
    }

    private boolean shouldBeRegisteredToSensors() {
        final boolean isKeyguard = mState == StatusBarState.KEYGUARD;

        final boolean isShadeOverOccludedKeyguard = mState == StatusBarState.SHADE
                && isKeyguardShowing()
                && isKeyguardOccluded();

        return mScreenOn && !mShowingAod && (isKeyguard || isShadeOverOccludedKeyguard);
    }

    private void updateSensorRegistration() {
        if (shouldBeRegisteredToSensors()) {
            registerSensors();
        } else {
            unregisterSensors();
        }
    }

    private void sessionStart() {
        if (!mSessionStarted && shouldSessionBeActive()) {
            logDebug("Starting Session");
            mSessionStarted = true;
            mFalsingDataProvider.setJustUnlockedWithFace(false);
            mFalsingDataProvider.onSessionStarted();
        }
    }

    private void sessionEnd() {
        if (mSessionStarted) {
            logDebug("Ending Session");
            mSessionStarted = false;
            mFalsingDataProvider.onSessionEnd();
        }
    }

    private void registerSensors() {
        mProximitySensor.register(mSensorEventListener);
    }

    private void unregisterSensors() {
        mProximitySensor.unregister(mSensorEventListener);
    }

    private void onProximityEvent(ThresholdSensorEvent proximityEvent) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mFalsingManager.onProximityEvent(new ProximityEventImpl(proximityEvent));
    }

    /**
     * Returns {@code true} if the keyguard is showing (whether or not the screen is on, whether or
     * not an activity is occluding the keyguard, and whether or not the shade is open on top of the
     * keyguard), or {@code false} if the user has dismissed the keyguard by authenticating or
     * swiping up.
     */
    private boolean isKeyguardShowing() {
        if (SceneContainerFlag.isEnabled()) {
            return !mDeviceEntryInteractor.get().isDeviceEntered().getValue();
        } else {
            return mKeyguardStateController.isShowing();
        }
    }

    /**
     * Returns {@code true} if there is an activity display on top of ("occluding") the keyguard, or
     * {@code false} if an activity is not occluding the keyguard (including if the keyguard is not
     * showing at all).
     */
    private boolean isKeyguardOccluded() {
        if (SceneContainerFlag.isEnabled()) {
            return mSceneContainerOcclusionInteractor.get().getInvisibleDueToOcclusion().getValue();
        } else {
            return mKeyguardStateController.isOccluded();
        }
    }

    static void logDebug(String msg) {
        if (DEBUG) {
            Log.d(TAG, msg);
        }
    }

    private static class ProximityEventImpl implements FalsingManager.ProximityEvent {
        private final ThresholdSensorEvent mThresholdSensorEvent;

        ProximityEventImpl(ThresholdSensorEvent thresholdSensorEvent) {
            mThresholdSensorEvent = thresholdSensorEvent;
        }
        @Override
        public boolean getCovered() {
            return mThresholdSensorEvent.getBelow();
        }

        @Override
        public long getTimestampNs() {
            return mThresholdSensorEvent.getTimestampNs();
        }
    }
}
