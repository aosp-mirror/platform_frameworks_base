/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.classifier.brightline;

import static com.android.systemui.classifier.FalsingManagerImpl.FALSING_REMAIN_LOCKED;
import static com.android.systemui.classifier.FalsingManagerImpl.FALSING_SUCCESS;

import android.hardware.biometrics.BiometricSourceType;
import android.net.Uri;
import android.util.Log;
import android.view.MotionEvent;

import com.android.internal.logging.MetricsLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.ProximitySensor;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * FalsingManager designed to make clear why a touch was rejected.
 */
public class BrightLineFalsingManager implements FalsingManager {

    static final boolean DEBUG = false;
    private static final String TAG = "FalsingManagerPlugin";

    private final FalsingDataProvider mDataProvider;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ProximitySensor mProximitySensor;
    private boolean mSessionStarted;
    private MetricsLogger mMetricsLogger;
    private int mIsFalseTouchCalls;
    private boolean mShowingAod;
    private boolean mScreenOn;
    private boolean mJustUnlockedWithFace;

    private final List<FalsingClassifier> mClassifiers;

    private ProximitySensor.ProximitySensorListener mSensorEventListener = this::onProximityEvent;

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onBiometricAuthenticated(int userId,
                        BiometricSourceType biometricSourceType) {
                    if (userId == KeyguardUpdateMonitor.getCurrentUser()
                            && biometricSourceType == BiometricSourceType.FACE) {
                        mJustUnlockedWithFace = true;
                    }
                }
            };

    public BrightLineFalsingManager(
            FalsingDataProvider falsingDataProvider,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ProximitySensor proximitySensor,
            DeviceConfigProxy deviceConfigProxy) {
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDataProvider = falsingDataProvider;
        mProximitySensor = proximitySensor;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateCallback);

        mMetricsLogger = new MetricsLogger();
        mClassifiers = new ArrayList<>();
        DistanceClassifier distanceClassifier =
                new DistanceClassifier(mDataProvider, deviceConfigProxy);
        ProximityClassifier proximityClassifier =
                new ProximityClassifier(distanceClassifier, mDataProvider, deviceConfigProxy);
        mClassifiers.add(new PointerCountClassifier(mDataProvider));
        mClassifiers.add(new TypeClassifier(mDataProvider));
        mClassifiers.add(new DiagonalClassifier(mDataProvider, deviceConfigProxy));
        mClassifiers.add(distanceClassifier);
        mClassifiers.add(proximityClassifier);
        mClassifiers.add(new ZigZagClassifier(mDataProvider, deviceConfigProxy));
    }

    private void registerSensors() {
        mProximitySensor.register(mSensorEventListener);
    }


    private void unregisterSensors() {
        mProximitySensor.unregister(mSensorEventListener);
    }

    private void sessionStart() {
        if (!mSessionStarted && !mShowingAod && mScreenOn) {
            logDebug("Starting Session");
            mSessionStarted = true;
            mJustUnlockedWithFace = false;
            registerSensors();
            mClassifiers.forEach(FalsingClassifier::onSessionStarted);
        }
    }

    private void sessionEnd() {
        if (mSessionStarted) {
            logDebug("Ending Session");
            mSessionStarted = false;
            unregisterSensors();
            mDataProvider.onSessionEnd();
            mClassifiers.forEach(FalsingClassifier::onSessionEnded);
            if (mIsFalseTouchCalls != 0) {
                mMetricsLogger.histogram(FALSING_REMAIN_LOCKED, mIsFalseTouchCalls);
                mIsFalseTouchCalls = 0;
            }
        }
    }

    private void updateInteractionType(@Classifier.InteractionType int type) {
        logDebug("InteractionType: " + type);
        mClassifiers.forEach((classifier) -> classifier.setInteractionType(type));
    }

    @Override
    public boolean isClassiferEnabled() {
        return true;
    }

    @Override
    public boolean isFalseTouch() {
        boolean r = !mJustUnlockedWithFace && mClassifiers.stream().anyMatch(falsingClassifier -> {
            boolean result = falsingClassifier.isFalseTouch();
            if (result) {
                logInfo(falsingClassifier.getClass().getName() + ": true");
            } else {
                logDebug(falsingClassifier.getClass().getName() + ": false");
            }
            return result;
        });

        logDebug("Is false touch? " + r);

        return r;
    }

    @Override
    public void onTouchEvent(MotionEvent motionEvent, int width, int height) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mDataProvider.onMotionEvent(motionEvent);
        mClassifiers.forEach((classifier) -> classifier.onTouchEvent(motionEvent));
    }

    private void onProximityEvent(ProximitySensor.ProximityEvent proximityEvent) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mClassifiers.forEach((classifier) -> classifier.onProximityEvent(proximityEvent));
    }

    @Override
    public void onSucccessfulUnlock() {
        if (mIsFalseTouchCalls != 0) {
            mMetricsLogger.histogram(FALSING_SUCCESS, mIsFalseTouchCalls);
            mIsFalseTouchCalls = 0;
        }
        sessionEnd();
    }

    @Override
    public void onNotificationActive() {
    }

    @Override
    public void setShowingAod(boolean showingAod) {
        mShowingAod = showingAod;
        if (showingAod) {
            sessionEnd();
        } else {
            sessionStart();
        }
    }

    @Override
    public void onNotificatonStartDraggingDown() {
        updateInteractionType(Classifier.NOTIFICATION_DRAG_DOWN);

    }

    @Override
    public boolean isUnlockingDisabled() {
        return false;
    }


    @Override
    public void onNotificatonStopDraggingDown() {
    }

    @Override
    public void setNotificationExpanded() {
    }

    @Override
    public void onQsDown() {
        updateInteractionType(Classifier.QUICK_SETTINGS);
    }

    @Override
    public void setQsExpanded(boolean b) {
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return false;
    }

    @Override
    public void onTrackingStarted(boolean secure) {
        updateInteractionType(secure ? Classifier.BOUNCER_UNLOCK : Classifier.UNLOCK);
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
        updateInteractionType(
                rightCorner ? Classifier.RIGHT_AFFORDANCE : Classifier.LEFT_AFFORDANCE);
    }

    @Override
    public void onAffordanceSwipingAborted() {
    }

    @Override
    public void onStartExpandingFromPulse() {
        updateInteractionType(Classifier.PULSE_EXPAND);
    }

    @Override
    public void onExpansionFromPulseStopped() {
    }

    @Override
    public Uri reportRejectedTouch() {
        return null;
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
        sessionStart();
    }

    @Override
    public void onScreenOff() {
        mScreenOn = false;
        sessionEnd();
    }


    @Override
    public void onNotificatonStopDismissing() {
    }

    @Override
    public void onNotificationDismissed() {
    }

    @Override
    public void onNotificatonStartDismissing() {
        updateInteractionType(Classifier.NOTIFICATION_DISMISS);
    }

    @Override
    public void onNotificationDoubleTap(boolean b, float v, float v1) {
    }

    @Override
    public void onBouncerShown() {
    }

    @Override
    public void onBouncerHidden() {
    }

    @Override
    public void dump(PrintWriter printWriter) {
    }

    @Override
    public void cleanup() {
        unregisterSensors();
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateCallback);
    }

    static void logDebug(String msg) {
        logDebug(msg, null);
    }

    static void logDebug(String msg, Throwable throwable) {
        if (DEBUG) {
            Log.d(TAG, msg, throwable);
        }
    }

    static void logInfo(String msg) {
        Log.i(TAG, msg);
    }

    static void logError(String msg) {
        Log.e(TAG, msg);
    }
}
