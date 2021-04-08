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

import android.hardware.SensorManager;
import android.hardware.biometrics.BiometricSourceType;
import android.util.Log;
import android.view.MotionEvent;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.sensors.ThresholdSensor;
import com.android.systemui.util.time.SystemClock;

import java.util.Collections;

import javax.inject.Inject;

@SysUISingleton
class FalsingCollectorImpl implements FalsingCollector {

    private static final boolean DEBUG = false;
    private static final String TAG = "FalsingManager";
    private static final String PROXIMITY_SENSOR_TAG = "FalsingManager";

    private final FalsingDataProvider mFalsingDataProvider;
    private final FalsingManager mFalsingManager;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final HistoryTracker mHistoryTracker;
    private final ProximitySensor mProximitySensor;
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardStateController mKeyguardStateController;
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
                    logDebug("StatusBarState=" + StatusBarState.toShortString(newState));
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

    @Inject
    FalsingCollectorImpl(FalsingDataProvider falsingDataProvider, FalsingManager falsingManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor, HistoryTracker historyTracker,
            ProximitySensor proximitySensor, StatusBarStateController statusBarStateController,
            KeyguardStateController keyguardStateController, SystemClock systemClock) {
        mFalsingDataProvider = falsingDataProvider;
        mFalsingManager = falsingManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mHistoryTracker = historyTracker;
        mProximitySensor = proximitySensor;
        mStatusBarStateController = statusBarStateController;
        mKeyguardStateController = keyguardStateController;
        mSystemClock = systemClock;


        mProximitySensor.setTag(PROXIMITY_SENSOR_TAG);
        mProximitySensor.setDelay(SensorManager.SENSOR_DELAY_GAME);

        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mState = mStatusBarStateController.getState();

        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateCallback);
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
        mFalsingDataProvider.onMotionEventComplete();
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
        if (!mFalsingDataProvider.isWirelessCharging()) {
            mProximitySensor.register(mSensorEventListener);
        }
    }

    private void unregisterSensors() {
        mProximitySensor.unregister(mSensorEventListener);
    }

    private void onProximityEvent(ThresholdSensor.ThresholdSensorEvent proximityEvent) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mFalsingManager.onProximityEvent(proximityEvent);
    }


    static void logDebug(String msg) {
        logDebug(msg, null);
    }

    static void logDebug(String msg, Throwable throwable) {
        if (DEBUG) {
            Log.d(TAG, msg, throwable);
        }
    }
}
