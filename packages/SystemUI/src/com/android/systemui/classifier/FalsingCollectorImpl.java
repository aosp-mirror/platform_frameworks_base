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
import android.view.MotionEvent;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BatteryController.BatteryStateChangeCallback;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.sensors.ThresholdSensor;
import com.android.systemui.util.sensors.ThresholdSensorEvent;
import com.android.systemui.util.time.SystemClock;

import java.util.Collections;

import javax.inject.Inject;

@SysUISingleton
class FalsingCollectorImpl implements FalsingCollector {

    private static final String TAG = "FalsingCollector";
    private static final String PROXIMITY_SENSOR_TAG = "FalsingCollector";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long GESTURE_PROCESSING_DELAY_MS = 100;

    private final FalsingDataProvider mFalsingDataProvider;
    private final FalsingManager mFalsingManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final HistoryTracker mHistoryTracker;
    private final ProximitySensor mProximitySensor;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
    private final BatteryController mBatteryController;
    private final DockManager mDockManager;
    private final DelayableExecutor mMainExecutor;
    private final SystemClock mSystemClock;

    private int mState;
    private boolean mShowingAod;
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


    private final KeyguardUpdateMonitorCallback mKeyguardUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onBiometricAuthenticated(int userId,
                        BiometricSourceType biometricSourceType,
                        boolean isStrongBiometric) {
                    if (userId == KeyguardUpdateMonitor.getCurrentUser()
                            && biometricSourceType == BiometricSourceType.FACE) {
                        mFalsingDataProvider.setJustUnlockedWithFace(true);
                    }
                }
            };


    private final BatteryStateChangeCallback mBatteryListener = new BatteryStateChangeCallback() {
        @Override
        public void onBatteryLevelChanged(int level, boolean pluggedIn, boolean charging) {
        }

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
            BatteryController batteryController,
            DockManager dockManager,
            @Main DelayableExecutor mainExecutor,
            SystemClock systemClock) {
        mFalsingDataProvider = falsingDataProvider;
        mFalsingManager = falsingManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mHistoryTracker = historyTracker;
        mProximitySensor = proximitySensor;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mBatteryController = batteryController;
        mDockManager = dockManager;
        mMainExecutor = mainExecutor;
        mSystemClock = systemClock;

        mProximitySensor.setTag(PROXIMITY_SENSOR_TAG);
        mProximitySensor.setDelay(SensorManager.SENSOR_DELAY_GAME);

        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mState = mStatusBarStateController.getState();

        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateCallback);

        mBatteryController.addCallback(mBatteryListener);
        mDockManager.addListener(mDockEventListener);
    }

    @Override
    public void onSuccessfulUnlock() {
        mFalsingManager.onSuccessfulUnlock();
        sessionEnd();
    }

    @Override
    public void onNotificationActive() {
    }

    @Override
    public void setShowingAod(boolean showingAod) {
        mShowingAod = showingAod;
        updateSessionActive();
    }

    @Override
    public void onNotificationStartDraggingDown() {
    }

    @Override
    public void onNotificationStopDraggingDown() {
    }

    @Override
    public void setNotificationExpanded() {
    }

    @Override
    public void onQsDown() {
    }

    @Override
    public void setQsExpanded(boolean expanded) {
        if (expanded) {
            unregisterSensors();
        } else if (mSessionStarted) {
            registerSensors();
        }
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return false;
    }

    @Override
    public void onTrackingStarted(boolean secure) {
    }

    @Override
    public void onTrackingStopped() {
    }

    @Override
    public void onLeftAffordanceOn() {
    }

    @Override
    public void onCameraOn() {
    }

    @Override
    public void onAffordanceSwipingStarted(boolean rightCorner) {
    }

    @Override
    public void onAffordanceSwipingAborted() {
    }

    @Override
    public void onStartExpandingFromPulse() {
    }

    @Override
    public void onExpansionFromPulseStopped() {
    }

    @Override
    public void onScreenOnFromTouch() {
        onScreenTurningOn();
    }

    @Override
    public boolean isReportingEnabled() {
        return false;
    }

    @Override
    public void onUnlockHintStarted() {
    }

    @Override
    public void onCameraHintStarted() {
    }

    @Override
    public void onLeftAffordanceHintStarted() {
    }

    @Override
    public void onScreenTurningOn() {
        mScreenOn = true;
        updateSessionActive();
    }

    @Override
    public void onScreenOff() {
        mScreenOn = false;
        updateSessionActive();
    }

    @Override
    public void onNotificationStopDismissing() {
    }

    @Override
    public void onNotificationDismissed() {
    }

    @Override
    public void onNotificationStartDismissing() {
    }

    @Override
    public void onNotificationDoubleTap(boolean accepted, float dx, float dy) {
    }

    @Override
    public void onBouncerShown() {
        unregisterSensors();
    }

    @Override
    public void onBouncerHidden() {
        if (mSessionStarted) {
            registerSensors();
        }
    }

    @Override
    public void onTouchEvent(MotionEvent ev) {
        if (!mKeyguardStateController.isShowing()) {
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
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
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
        mAvoidGesture = true;
        if (mPendingDownEvent != null) {
            mPendingDownEvent.recycle();
            mPendingDownEvent = null;
        }
    }

    @Override
    public void cleanup() {
        unregisterSensors();
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mBatteryController.removeCallback(mBatteryListener);
        mDockManager.removeListener(mDockEventListener);
    }

    @Override
    public void updateFalseConfidence(FalsingClassifier.Result result) {
        mHistoryTracker.addResults(Collections.singleton(result), mSystemClock.uptimeMillis());
    }

    private boolean shouldSessionBeActive() {
        return mScreenOn && (mState == StatusBarState.KEYGUARD) && !mShowingAod;
    }

    private void updateSessionActive() {
        if (shouldSessionBeActive()) {
            sessionStart();
        } else {
            sessionEnd();
        }
    }

    private void sessionStart() {
        if (!mSessionStarted && shouldSessionBeActive()) {
            logDebug("Starting Session");
            mSessionStarted = true;
            mFalsingDataProvider.setJustUnlockedWithFace(false);
            registerSensors();
            mFalsingDataProvider.onSessionStarted();
        }
    }

    private void sessionEnd() {
        if (mSessionStarted) {
            logDebug("Ending Session");
            mSessionStarted = false;
            unregisterSensors();
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


    static void logDebug(String msg) {
        logDebug(msg, null);
    }

    static void logDebug(String msg, Throwable throwable) {
        if (DEBUG) {
            Log.d(TAG, msg, throwable);
        }
    }

    private static class ProximityEventImpl implements FalsingManager.ProximityEvent {
        private ThresholdSensorEvent mThresholdSensorEvent;

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
