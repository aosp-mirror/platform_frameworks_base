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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;

import com.android.systemui.Dependency;
import com.android.systemui.UiOffloadThread;
import com.android.systemui.analytics.DataCollector;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.util.AsyncSensorManager;

import java.io.PrintWriter;

/**
 * When the phone is locked, listens to touch, sensor and phone events and sends them to
 * DataCollector and HumanInteractionClassifier.
 *
 * It does not collect touch events when the bouncer shows up.
 */
public class FalsingManager implements SensorEventListener, StateListener {
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

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mContext;

    private final SensorManager mSensorManager;
    private final DataCollector mDataCollector;
    private final HumanInteractionClassifier mHumanInteractionClassifier;
    private final AccessibilityManager mAccessibilityManager;
    private final UiOffloadThread mUiOffloadThread;

    private static FalsingManager sInstance = null;

    private boolean mEnforceBouncer = false;
    private boolean mBouncerOn = false;
    private boolean mBouncerOffOnDown = false;
    private boolean mSessionActive = false;
    private boolean mIsTouchScreen = true;
    private int mState = StatusBarState.SHADE;
    private boolean mScreenOn;
    private boolean mShowingAod;
    private Runnable mPendingWtf;

    protected final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            updateConfiguration();
        }
    };

    private FalsingManager(Context context) {
        mContext = context;
        mSensorManager = Dependency.get(AsyncSensorManager.class);
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        mDataCollector = DataCollector.getInstance(mContext);
        mHumanInteractionClassifier = HumanInteractionClassifier.getInstance(mContext);
        mUiOffloadThread = Dependency.get(UiOffloadThread.class);
        mScreenOn = context.getSystemService(PowerManager.class).isInteractive();

        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(ENFORCE_BOUNCER), false,
                mSettingsObserver,
                UserHandle.USER_ALL);

        updateConfiguration();
        Dependency.get(StatusBarStateController.class).addCallback(this);
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

    private boolean shouldSessionBeActive() {
        if (FalsingLog.ENABLED && FalsingLog.VERBOSE)
            FalsingLog.v("shouldBeActive", new StringBuilder()
                    .append("enabled=").append(isEnabled() ? 1 : 0)
                    .append(" mScreenOn=").append(mScreenOn ? 1 : 0)
                    .append(" mState=").append(StatusBarState.toShortString(mState))
                    .toString()
            );
        return isEnabled() && mScreenOn && (mState == StatusBarState.KEYGUARD) && !mShowingAod;
    }

    private boolean sessionEntrypoint() {
        if (!mSessionActive && shouldSessionBeActive()) {
            onSessionStart();
            return true;
        }
        return false;
    }

    private void sessionExitpoint(boolean force) {
        if (mSessionActive && (force || !shouldSessionBeActive())) {
            mSessionActive = false;

            // This can be expensive, and doesn't need to happen on the main thread.
            mUiOffloadThread.submit(() -> {
                mSensorManager.unregisterListener(this);
            });
        }
    }

    public void updateSessionActive() {
        if (shouldSessionBeActive()) {
            sessionEntrypoint();
        } else {
            sessionExitpoint(false /* force */);
        }
    }

    private void onSessionStart() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onSessionStart", "classifierEnabled=" + isClassiferEnabled());
            clearPendingWtf();
        }
        mBouncerOn = false;
        mSessionActive = true;

        if (mHumanInteractionClassifier.isEnabled()) {
            registerSensors(CLASSIFIER_SENSORS);
        }
        if (mDataCollector.isEnabledFull()) {
            registerSensors(COLLECTOR_SENSORS);
        }
        if (mDataCollector.isEnabled()) {
            mDataCollector.onFalsingSessionStarted();
        }
    }

    private void registerSensors(int [] sensors) {
        for (int sensorType : sensors) {
            Sensor s = mSensorManager.getDefaultSensor(sensorType);
            if (s != null) {

                // This can be expensive, and doesn't need to happen on the main thread.
                mUiOffloadThread.submit(() -> {
                    mSensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME);
                });
            }
        }
    }

    public boolean isClassiferEnabled() {
        return mHumanInteractionClassifier.isEnabled();
    }

    private boolean isEnabled() {
        return mHumanInteractionClassifier.isEnabled() || mDataCollector.isEnabled();
    }

    public boolean isUnlockingDisabled() {
        return mDataCollector.isUnlockingDisabled();
    }
    /**
     * @return true if the classifier determined that this is not a human interacting with the phone
     */
    public boolean isFalseTouch() {
        if (FalsingLog.ENABLED) {
            // We're getting some false wtfs from touches that happen after the device went
            // to sleep. Only report missing sessions that happen when the device is interactive.
            if (!mSessionActive && mContext.getSystemService(PowerManager.class).isInteractive()
                    && mPendingWtf == null) {
                int enabled = isEnabled() ? 1 : 0;
                int screenOn = mScreenOn ? 1 : 0;
                String state = StatusBarState.toShortString(mState);
                Throwable here = new Throwable("here");
                FalsingLog.wLogcat("isFalseTouch", new StringBuilder()
                        .append("Session is not active, yet there's a query for a false touch.")
                        .append(" enabled=").append(enabled)
                        .append(" mScreenOn=").append(screenOn)
                        .append(" mState=").append(state)
                        .append(". Escalating to WTF if screen does not turn on soon.")
                        .toString());

                // Unfortunately we're also getting false positives for touches that happen right
                // after the screen turns on, but before that notification has made it to us.
                // Unfortunately there's no good way to catch that, except to wait and see if we get
                // the screen on notification soon.
                mPendingWtf = () -> FalsingLog.wtf("isFalseTouch", new StringBuilder()
                        .append("Session did not become active after query for a false touch.")
                        .append(" enabled=").append(enabled)
                        .append('/').append(isEnabled() ? 1 : 0)
                        .append(" mScreenOn=").append(screenOn)
                        .append('/').append(mScreenOn ? 1 : 0)
                        .append(" mState=").append(state)
                        .append('/').append(StatusBarState.toShortString(mState))
                        .append(". Look for warnings ~1000ms earlier to see root cause.")
                        .toString(), here);
                mHandler.postDelayed(mPendingWtf, 1000);
            }
        }
        if (mAccessibilityManager.isTouchExplorationEnabled()) {
            // Touch exploration triggers false positives in the classifier and
            // already sufficiently prevents false unlocks.
            return false;
        }
        if (!mIsTouchScreen) {
            // Unlocking with input devices besides the touchscreen should already be sufficiently
            // anti-falsed.
            return false;
        }
        return mHumanInteractionClassifier.isFalseTouch();
    }

    private void clearPendingWtf() {
        if (mPendingWtf != null) {
            mHandler.removeCallbacks(mPendingWtf);
            mPendingWtf = null;
        }
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

    public void setShowingAod(boolean showingAod) {
        mShowingAod = showingAod;
        updateSessionActive();
    }

    @Override
    public void onStateChanged(int newState) {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("setStatusBarState", new StringBuilder()
                    .append("from=").append(StatusBarState.toShortString(mState))
                    .append(" to=").append(StatusBarState.toShortString(newState))
                    .toString());
        }
        mState = newState;
        updateSessionActive();
    }

    public void onScreenTurningOn() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onScreenTurningOn", new StringBuilder()
                    .append("from=").append(mScreenOn ? 1 : 0)
                    .toString());
            clearPendingWtf();
        }
        mScreenOn = true;
        if (sessionEntrypoint()) {
            mDataCollector.onScreenTurningOn();
        }
    }

    public void onScreenOnFromTouch() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onScreenOnFromTouch", new StringBuilder()
                    .append("from=").append(mScreenOn ? 1 : 0)
                    .toString());
        }
        mScreenOn = true;
        if (sessionEntrypoint()) {
            mDataCollector.onScreenOnFromTouch();
        }
    }

    public void onScreenOff() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onScreenOff", new StringBuilder()
                    .append("from=").append(mScreenOn ? 1 : 0)
                    .toString());
        }
        mDataCollector.onScreenOff();
        mScreenOn = false;
        sessionExitpoint(false /* force */);
    }

    public void onSucccessfulUnlock() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onSucccessfulUnlock", "");
        }
        mDataCollector.onSucccessfulUnlock();
    }

    public void onBouncerShown() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onBouncerShown", new StringBuilder()
                    .append("from=").append(mBouncerOn ? 1 : 0)
                    .toString());
        }
        if (!mBouncerOn) {
            mBouncerOn = true;
            mDataCollector.onBouncerShown();
        }
    }

    public void onBouncerHidden() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onBouncerHidden", new StringBuilder()
                    .append("from=").append(mBouncerOn ? 1 : 0)
                    .toString());
        }
        if (mBouncerOn) {
            mBouncerOn = false;
            mDataCollector.onBouncerHidden();
        }
    }

    public void onQsDown() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onQsDown", "");
        }
        mHumanInteractionClassifier.setType(Classifier.QUICK_SETTINGS);
        mDataCollector.onQsDown();
    }

    public void setQsExpanded(boolean expanded) {
        mDataCollector.setQsExpanded(expanded);
    }

    public void onTrackingStarted(boolean secure) {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onTrackingStarted", "");
        }
        mHumanInteractionClassifier.setType(secure ?
                Classifier.BOUNCER_UNLOCK : Classifier.UNLOCK);
        mDataCollector.onTrackingStarted();
    }

    public void onTrackingStopped() {
        mDataCollector.onTrackingStopped();
    }

    public void onNotificationActive() {
        mDataCollector.onNotificationActive();
    }

    public void onNotificationDoubleTap(boolean accepted, float dx, float dy) {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onNotificationDoubleTap", "accepted=" + accepted
                    + " dx=" + dx + " dy=" + dy + " (px)");
        }
        mDataCollector.onNotificationDoubleTap();
    }

    public void setNotificationExpanded() {
        mDataCollector.setNotificationExpanded();
    }

    public void onNotificatonStartDraggingDown() {
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onNotificatonStartDraggingDown", "");
        }
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
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onNotificatonStartDismissing", "");
        }
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
        if (FalsingLog.ENABLED) {
            FalsingLog.i("onAffordanceSwipingStarted", "");
        }
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
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mIsTouchScreen = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
            // If the bouncer was not shown during the down event,
            // we want the entire gesture going to HumanInteractionClassifier
            mBouncerOffOnDown = !mBouncerOn;
        }
        if (mSessionActive) {
            if (!mBouncerOn) {
                // In case bouncer is "visible", but onFullyShown has not yet been called,
                // avoid adding the event to DataCollector
                mDataCollector.onTouchEvent(event, width, height);
            }
            if (mBouncerOffOnDown) {
                mHumanInteractionClassifier.onTouchEvent(event);
            }
        }
    }

    public void dump(PrintWriter pw) {
        pw.println("FALSING MANAGER");
        pw.print("classifierEnabled="); pw.println(isClassiferEnabled() ? 1 : 0);
        pw.print("mSessionActive="); pw.println(mSessionActive ? 1 : 0);
        pw.print("mBouncerOn="); pw.println(mSessionActive ? 1 : 0);
        pw.print("mState="); pw.println(StatusBarState.toShortString(mState));
        pw.print("mScreenOn="); pw.println(mScreenOn ? 1 : 0);
        pw.println();
    }

    public Uri reportRejectedTouch() {
        if (mDataCollector.isEnabled()) {
            return mDataCollector.reportRejectedTouch();
        }
        return null;
    }

    public boolean isReportingEnabled() {
        return mDataCollector.isReportingEnabled();
    }
}
