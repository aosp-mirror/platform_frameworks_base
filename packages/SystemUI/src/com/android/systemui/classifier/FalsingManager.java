/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.MotionEvent;

import com.android.systemui.analytics.DataCollector;
import com.android.systemui.statusbar.StatusBarState;

/**
 * When the phone is locked, listens to touch, sensor and phone events and sends them to
 * DataCollector and HumanInteractionClassifier.
 *
 * It does not collect touch events when the bouncer shows up.
 */
public class FalsingManager implements SensorEventListener {
    private static final String ENFORCE_BOUNCER = "falsing_manager_enforce_bouncer";

    private static final int[] CLASSIFIER_SENSORS = new int[] {
            Sensor.TYPE_PROXIMITY,
    };

    private static final int[] COLLECTOR_SENSORS = new int[] {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_PROXIMITY,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_ROTATION_VECTOR,
    };

    private final Handler mHandler = new Handler();
    private final Context mContext;

    private final SensorManager mSensorManager;
    private final DataCollector mDataCollector;
    private final HumanInteractionClassifier mHumanInteractionClassifier;

    private static FalsingManager sInstance = null;

    private boolean mEnforceBouncer = false;
    private boolean mBouncerOn = false;
    private boolean mSessionActive = false;
    private int mState = StatusBarState.SHADE;

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateConfiguration();
        }
    };

    private FalsingManager(Context context) {
        mContext = context;
        mSensorManager = (SensorManager) mContext.getSystemService(Context.SENSOR_SERVICE);
        mDataCollector = DataCollector.getInstance(mContext);
        mHumanInteractionClassifier = HumanInteractionClassifier.getInstance(mContext);

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(ENFORCE_BOUNCER), false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        updateConfiguration();
    }

    public static FalsingManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new FalsingManager(context);
        }
        return sInstance;
    }

    private void updateConfiguration() {
        mEnforceBouncer = 0 != Settings.Secure.getInt(mContext.getContentResolver(),
                ENFORCE_BOUNCER, 0);
    }

    private boolean sessionEntrypoint() {
        if (!mSessionActive && isEnabled() &&
                (mState == StatusBarState.KEYGUARD || mState == StatusBarState.SHADE_LOCKED)) {
            onSessionStart();
            return true;
        }
        return false;
    }

    private void sessionExitpoint() {
        if (mSessionActive) {
            mSessionActive = false;
            mSensorManager.unregisterListener(this);
        }
    }

    private void onSessionStart() {
        mBouncerOn = false;
        mSessionActive = true;

        if (mHumanInteractionClassifier.isEnabled()) {
            registerSensors(CLASSIFIER_SENSORS);
        }
        if (mDataCollector.isEnabled()) {
            registerSensors(COLLECTOR_SENSORS);
        }
    }

    private void registerSensors(int [] sensors) {
        for (int sensorType : sensors) {
            Sensor s = mSensorManager.getDefaultSensor(sensorType);
            if (s != null) {
                mSensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
            }
        }
    }

    public boolean isClassiferEnabled() {
        return mHumanInteractionClassifier.isEnabled();
    }

    private boolean isEnabled() {
        return mHumanInteractionClassifier.isEnabled() || mDataCollector.isEnabled();
    }

    /**
     * @return true if the classifier determined that this is not a human interacting with the phone
     */
    public boolean isFalseTouch() {
        return mHumanInteractionClassifier.isFalseTouch();
    }

    @Override
    public synchronized void onSensorChanged(SensorEvent event) {
        mDataCollector.onSensorChanged(event);
        mHumanInteractionClassifier.onSensorChanged(event);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        mDataCollector.onAccuracyChanged(sensor, accuracy);
    }

    public boolean shouldEnforceBouncer() {
        return mEnforceBouncer;
    }

    public void setStatusBarState(int state) {
        mState = state;
    }

    public void onScreenTurningOn() {
        if (sessionEntrypoint()) {
            mDataCollector.onScreenTurningOn();
        }
    }

    public void onScreenOnFromTouch() {
        if (sessionEntrypoint()) {
            mDataCollector.onScreenOnFromTouch();
        }
    }

    public void onScreenOff() {
        mDataCollector.onScreenOff();
        sessionExitpoint();
    }

    public void onSucccessfulUnlock() {
        mDataCollector.onSucccessfulUnlock();
        sessionExitpoint();
    }

    public void onBouncerShown() {
        if (!mBouncerOn) {
            mBouncerOn = true;
            mDataCollector.onBouncerShown();
        }
    }

    public void onBouncerHidden() {
        if (mBouncerOn) {
            mBouncerOn = false;
            mDataCollector.onBouncerHidden();
        }
    }

    public void onQsDown() {
        mHumanInteractionClassifier.setType(Classifier.QUICK_SETTINGS);
        mDataCollector.onQsDown();
    }

    public void setQsExpanded(boolean expanded) {
        mDataCollector.setQsExpanded(expanded);
    }

    public void onTrackingStarted() {
        mHumanInteractionClassifier.setType(Classifier.UNLOCK);
        mDataCollector.onTrackingStarted();
    }

    public void onTrackingStopped() {
        mDataCollector.onTrackingStopped();
    }

    public void onNotificationActive() {
        mDataCollector.onNotificationActive();
    }

    public void onNotificationDoubleTap() {
        mDataCollector.onNotificationDoubleTap();
    }

    public void setNotificationExpanded() {
        mDataCollector.setNotificationExpanded();
    }

    public void onNotificatonStartDraggingDown() {
        mHumanInteractionClassifier.setType(Classifier.NOTIFICATION_DRAG_DOWN);
        mDataCollector.onNotificatonStartDraggingDown();
    }

    public void onNotificatonStopDraggingDown() {
        mDataCollector.onNotificatonStopDraggingDown();
    }

    public void onNotificationDismissed() {
        mDataCollector.onNotificationDismissed();
    }

    public void onNotificatonStartDismissing() {
        mHumanInteractionClassifier.setType(Classifier.NOTIFICATION_DISMISS);
        mDataCollector.onNotificatonStartDismissing();
    }

    public void onNotificatonStopDismissing() {
        mDataCollector.onNotificatonStopDismissing();
    }

    public void onCameraOn() {
        mDataCollector.onCameraOn();
    }

    public void onLeftAffordanceOn() {
        mDataCollector.onLeftAffordanceOn();
    }

    public void onAffordanceSwipingStarted(boolean rightCorner) {
        if (rightCorner) {
            mHumanInteractionClassifier.setType(Classifier.RIGHT_AFFORDANCE);
        } else {
            mHumanInteractionClassifier.setType(Classifier.LEFT_AFFORDANCE);
        }
        mDataCollector.onAffordanceSwipingStarted(rightCorner);
    }

    public void onAffordanceSwipingAborted() {
        mDataCollector.onAffordanceSwipingAborted();
    }

    public void onUnlockHintStarted() {
        mDataCollector.onUnlockHintStarted();
    }

    public void onCameraHintStarted() {
        mDataCollector.onCameraHintStarted();
    }

    public void onLeftAffordanceHintStarted() {
        mDataCollector.onLeftAffordanceHintStarted();
    }

    public void onTouchEvent(MotionEvent event, int width, int height) {
        if (mSessionActive && !mBouncerOn) {
            mDataCollector.onTouchEvent(event, width, height);
            mHumanInteractionClassifier.onTouchEvent(event);
        }
    }
}
