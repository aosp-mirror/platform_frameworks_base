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

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.util.Log;
import android.view.MotionEvent;

import com.android.systemui.classifier.Classifier;
import com.android.systemui.plugins.FalsingManager;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FalsingManager designed to make clear why a touch was rejected.
 */
public class BrightLineFalsingManager implements FalsingManager {

    static final boolean DEBUG = false;
    private static final String TAG = "FalsingManagerPlugin";

    private final SensorManager mSensorManager;
    private final FalsingDataProvider mDataProvider;
    private boolean mSessionStarted;
    private boolean mShowingAod;
    private boolean mScreenOn;

    private final ExecutorService mBackgroundExecutor = Executors.newSingleThreadExecutor();

    private final List<FalsingClassifier> mClassifiers;

    private SensorEventListener mSensorEventListener = new SensorEventListener() {
        @Override
        public synchronized void onSensorChanged(SensorEvent event) {
            onSensorEvent(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };

    public BrightLineFalsingManager(FalsingDataProvider falsingDataProvider,
            SensorManager sensorManager) {
        mDataProvider = falsingDataProvider;
        mSensorManager = sensorManager;
        mClassifiers = new ArrayList<>();
        DistanceClassifier distanceClassifier = new DistanceClassifier(mDataProvider);
        ProximityClassifier proximityClassifier = new ProximityClassifier(distanceClassifier,
                mDataProvider);
        mClassifiers.add(new PointerCountClassifier(mDataProvider));
        mClassifiers.add(new TypeClassifier(mDataProvider));
        mClassifiers.add(new DiagonalClassifier(mDataProvider));
        mClassifiers.add(distanceClassifier);
        mClassifiers.add(proximityClassifier);
        mClassifiers.add(new ZigZagClassifier(mDataProvider));
    }

    private void registerSensors() {
        Sensor s = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (s != null) {
            // This can be expensive, and doesn't need to happen on the main thread.
            mBackgroundExecutor.submit(() -> {
                logDebug("registering sensor listener");
                mSensorManager.registerListener(
                        mSensorEventListener, s, SensorManager.SENSOR_DELAY_GAME);
            });
        }
    }


    private void unregisterSensors() {
        // This can be expensive, and doesn't need to happen on the main thread.
        mBackgroundExecutor.submit(() -> {
            logDebug("unregistering sensor listener");
            mSensorManager.unregisterListener(mSensorEventListener);
        });
    }

    private void sessionStart() {
        if (!mSessionStarted && !mShowingAod && mScreenOn) {
            logDebug("Starting Session");
            mSessionStarted = true;
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
        boolean r = mClassifiers.stream().anyMatch(falsingClassifier -> {
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

    private void onSensorEvent(SensorEvent sensorEvent) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mClassifiers.forEach((classifier) -> classifier.onSensorEvent(sensorEvent));
    }

    @Override
    public void onSucccessfulUnlock() {
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
