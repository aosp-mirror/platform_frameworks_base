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
 * limitations under the License.
 */

package com.android.server;

import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MutableBoolean;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.LocalServices;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.wm.WindowManagerInternal;

/**
 * The service that listens for gestures detected in sensor firmware and starts the intent
 * accordingly.
 * <p>For now, only camera launch gesture is supported, and in the future, more gestures can be
 * added.</p>
 * @hide
 */
public class GestureLauncherService extends SystemService {
    private static final boolean DBG = false;
    private static final boolean DBG_CAMERA_LIFT = false;
    private static final String TAG = "GestureLauncherService";

    /**
     * Time in milliseconds in which the power button must be pressed twice so it will be considered
     * as a camera launch.
     */
    @VisibleForTesting static final long CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS = 300;

    /**
     * Interval in milliseconds in which the power button must be depressed in succession to be
     * considered part of an extended sequence of taps. Note that this is a looser threshold than
     * the camera launch gesture, because the purpose of this threshold is to measure the
     * frequency of consecutive taps, for evaluation for future gestures.
     */
    @VisibleForTesting static final long POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS = 500;

    /** The listener that receives the gesture event. */
    private final GestureEventListener mGestureListener = new GestureEventListener();
    private final CameraLiftTriggerEventListener mCameraLiftTriggerListener =
            new CameraLiftTriggerEventListener();

    private Sensor mCameraLaunchSensor;
    private Sensor mCameraLiftTriggerSensor;
    private Context mContext;
    private final MetricsLogger mMetricsLogger;
    private PowerManager mPowerManager;
    private WindowManagerInternal mWindowManagerInternal;

    /** The wake lock held when a gesture is detected. */
    private WakeLock mWakeLock;
    private boolean mCameraLaunchRegistered;
    private boolean mCameraLiftRegistered;
    private int mUserId;

    // Below are fields used for event logging only.
    /** Elapsed real time when the camera gesture is turned on. */
    private long mCameraGestureOnTimeMs = 0L;

    /** Elapsed real time when the last camera gesture was detected. */
    private long mCameraGestureLastEventTime = 0L;

    /**
     * How long the sensor 1 has been turned on since camera launch sensor was
     * subscribed to and when the last camera launch gesture was detected.
     * <p>Sensor 1 is the main sensor used to detect camera launch gesture.</p>
     */
    private long mCameraGestureSensor1LastOnTimeMs = 0L;

    /**
     * If applicable, how long the sensor 2 has been turned on since camera
     * launch sensor was subscribed to and when the last camera launch
     * gesture was detected.
     * <p>Sensor 2 is the secondary sensor used to detect camera launch gesture.
     * This is optional and if only sensor 1 is used for detect camera launch
     * gesture, this value would always be 0.</p>
     */
    private long mCameraGestureSensor2LastOnTimeMs = 0L;

    /**
     * Extra information about the event when the last camera launch gesture
     * was detected.
     */
    private int mCameraLaunchLastEventExtra = 0;

    /**
     * Whether camera double tap power button gesture is currently enabled;
     */
    private boolean mCameraDoubleTapPowerEnabled;
    private long mLastPowerDown;
    private int mPowerButtonConsecutiveTaps;
    private final UiEventLogger mUiEventLogger;

    @VisibleForTesting
    public enum GestureLauncherEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The user lifted the device just the right way to launch the camera.")
        GESTURE_CAMERA_LIFT(658),

        @UiEvent(doc = "The user wiggled the device just the right way to launch the camera.")
        GESTURE_CAMERA_WIGGLE(659),

        @UiEvent(doc = "The user double-tapped power quickly enough to launch the camera.")
        GESTURE_CAMERA_DOUBLE_TAP_POWER(660);

        private final int mId;

        GestureLauncherEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
    public GestureLauncherService(Context context) {
        this(context, new MetricsLogger(), new UiEventLoggerImpl());
    }

    @VisibleForTesting
    GestureLauncherService(Context context, MetricsLogger metricsLogger,
            UiEventLogger uiEventLogger) {
        super(context);
        mContext = context;
        mMetricsLogger = metricsLogger;
        mUiEventLogger = uiEventLogger;
    }

    public void onStart() {
        LocalServices.addService(GestureLauncherService.class, this);
    }

    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            Resources resources = mContext.getResources();
            if (!isGestureLauncherEnabled(resources)) {
                if (DBG) Slog.d(TAG, "Gesture launcher is disabled in system properties.");
                return;
            }

            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mPowerManager = (PowerManager) mContext.getSystemService(
                    Context.POWER_SERVICE);
            mWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "GestureLauncherService");
            updateCameraRegistered();
            updateCameraDoubleTapPowerEnabled();

            mUserId = ActivityManager.getCurrentUser();
            mContext.registerReceiver(mUserReceiver, new IntentFilter(Intent.ACTION_USER_SWITCHED));
            registerContentObservers();
        }
    }

    private void registerContentObservers() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.CAMERA_GESTURE_DISABLED),
                false, mSettingObserver, mUserId);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED),
                false, mSettingObserver, mUserId);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED),
                false, mSettingObserver, mUserId);
    }

    private void updateCameraRegistered() {
        Resources resources = mContext.getResources();
        if (isCameraLaunchSettingEnabled(mContext, mUserId)) {
            registerCameraLaunchGesture(resources);
        } else {
            unregisterCameraLaunchGesture();
        }

        if (isCameraLiftTriggerSettingEnabled(mContext, mUserId)) {
            registerCameraLiftTrigger(resources);
        } else {
            unregisterCameraLiftTrigger();
        }
    }

    @VisibleForTesting
    void updateCameraDoubleTapPowerEnabled() {
        boolean enabled = isCameraDoubleTapPowerSettingEnabled(mContext, mUserId);
        synchronized (this) {
            mCameraDoubleTapPowerEnabled = enabled;
        }
    }

    private void unregisterCameraLaunchGesture() {
        if (mCameraLaunchRegistered) {
            mCameraLaunchRegistered = false;
            mCameraGestureOnTimeMs = 0L;
            mCameraGestureLastEventTime = 0L;
            mCameraGestureSensor1LastOnTimeMs = 0;
            mCameraGestureSensor2LastOnTimeMs = 0;
            mCameraLaunchLastEventExtra = 0;

            SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                    Context.SENSOR_SERVICE);
            sensorManager.unregisterListener(mGestureListener);
        }
    }

    /**
     * Registers for the camera launch gesture.
     */
    private void registerCameraLaunchGesture(Resources resources) {
        if (mCameraLaunchRegistered) {
            return;
        }
        mCameraGestureOnTimeMs = SystemClock.elapsedRealtime();
        mCameraGestureLastEventTime = mCameraGestureOnTimeMs;
        SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                Context.SENSOR_SERVICE);
        int cameraLaunchGestureId = resources.getInteger(
                com.android.internal.R.integer.config_cameraLaunchGestureSensorType);
        if (cameraLaunchGestureId != -1) {
            mCameraLaunchRegistered = false;
            String sensorName = resources.getString(
                com.android.internal.R.string.config_cameraLaunchGestureSensorStringType);
            mCameraLaunchSensor = sensorManager.getDefaultSensor(
                    cameraLaunchGestureId,
                    true /*wakeUp*/);

            // Compare the camera gesture string type to that in the resource file to make
            // sure we are registering the correct sensor. This is redundant check, it
            // makes the code more robust.
            if (mCameraLaunchSensor != null) {
                if (sensorName.equals(mCameraLaunchSensor.getStringType())) {
                    mCameraLaunchRegistered = sensorManager.registerListener(mGestureListener,
                            mCameraLaunchSensor, 0);
                } else {
                    String message = String.format("Wrong configuration. Sensor type and sensor "
                            + "string type don't match: %s in resources, %s in the sensor.",
                            sensorName, mCameraLaunchSensor.getStringType());
                    throw new RuntimeException(message);
                }
            }
            if (DBG) Slog.d(TAG, "Camera launch sensor registered: " + mCameraLaunchRegistered);
        } else {
            if (DBG) Slog.d(TAG, "Camera launch sensor is not specified.");
        }
    }

    private void unregisterCameraLiftTrigger() {
        if (mCameraLiftRegistered) {
            mCameraLiftRegistered = false;

            SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                    Context.SENSOR_SERVICE);
            sensorManager.cancelTriggerSensor(mCameraLiftTriggerListener, mCameraLiftTriggerSensor);
        }
    }

    /**
     * Registers for the camera lift trigger.
     */
    private void registerCameraLiftTrigger(Resources resources) {
        if (mCameraLiftRegistered) {
            return;
        }
        SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                Context.SENSOR_SERVICE);
        int cameraLiftTriggerId = resources.getInteger(
                com.android.internal.R.integer.config_cameraLiftTriggerSensorType);
        if (cameraLiftTriggerId != -1) {
            mCameraLiftRegistered = false;
            String sensorName = resources.getString(
                com.android.internal.R.string.config_cameraLiftTriggerSensorStringType);
            mCameraLiftTriggerSensor = sensorManager.getDefaultSensor(
                    cameraLiftTriggerId,
                    true /*wakeUp*/);

            // Compare the camera lift trigger string type to that in the resource file to make
            // sure we are registering the correct sensor. This is redundant check, it
            // makes the code more robust.
            if (mCameraLiftTriggerSensor != null) {
                if (sensorName.equals(mCameraLiftTriggerSensor.getStringType())) {
                    mCameraLiftRegistered = sensorManager.requestTriggerSensor(mCameraLiftTriggerListener,
                            mCameraLiftTriggerSensor);
                } else {
                    String message = String.format("Wrong configuration. Sensor type and sensor "
                            + "string type don't match: %s in resources, %s in the sensor.",
                            sensorName, mCameraLiftTriggerSensor.getStringType());
                    throw new RuntimeException(message);
                }
            }
            if (DBG) Slog.d(TAG, "Camera lift trigger sensor registered: " + mCameraLiftRegistered);
        } else {
            if (DBG) Slog.d(TAG, "Camera lift trigger sensor is not specified.");
        }
    }

    public static boolean isCameraLaunchSettingEnabled(Context context, int userId) {
        return isCameraLaunchEnabled(context.getResources())
                && (Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.CAMERA_GESTURE_DISABLED, 0, userId) == 0);
    }

    public static boolean isCameraDoubleTapPowerSettingEnabled(Context context, int userId) {
        return isCameraDoubleTapPowerEnabled(context.getResources())
                && (Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED, 0, userId) == 0);
    }

    public static boolean isCameraLiftTriggerSettingEnabled(Context context, int userId) {
        return isCameraLiftTriggerEnabled(context.getResources())
                && (Settings.Secure.getIntForUser(context.getContentResolver(),
                        Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED,
                        Settings.Secure.CAMERA_LIFT_TRIGGER_ENABLED_DEFAULT, userId) != 0);
    }

    /**
     * Whether to enable the camera launch gesture.
     */
    public static boolean isCameraLaunchEnabled(Resources resources) {
        boolean configSet = resources.getInteger(
                com.android.internal.R.integer.config_cameraLaunchGestureSensorType) != -1;
        return configSet &&
                !SystemProperties.getBoolean("gesture.disable_camera_launch", false);
    }

    public static boolean isCameraDoubleTapPowerEnabled(Resources resources) {
        return resources.getBoolean(
                com.android.internal.R.bool.config_cameraDoubleTapPowerGestureEnabled);
    }

    public static boolean isCameraLiftTriggerEnabled(Resources resources) {
        boolean configSet = resources.getInteger(
                com.android.internal.R.integer.config_cameraLiftTriggerSensorType) != -1;
        return configSet;
    }

    /**
     * Whether GestureLauncherService should be enabled according to system properties.
     */
    public static boolean isGestureLauncherEnabled(Resources resources) {
        return isCameraLaunchEnabled(resources) || isCameraDoubleTapPowerEnabled(resources) ||
                isCameraLiftTriggerEnabled(resources);
    }

    public boolean interceptPowerKeyDown(KeyEvent event, boolean interactive,
            MutableBoolean outLaunched) {
        if (event.isLongPress()) {
            // Long presses are sent as a second key down. If the long press threshold is set lower
            // than the double tap of sequence interval thresholds, this could cause false double
            // taps or consecutive taps, so we want to ignore the long press event.
            return false;
        }
        boolean launched = false;
        boolean intercept = false;
        long powerTapInterval;
        synchronized (this) {
            powerTapInterval = event.getEventTime() - mLastPowerDown;
            if (mCameraDoubleTapPowerEnabled
                    && powerTapInterval < CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS) {
                launched = true;
                intercept = interactive;
                mPowerButtonConsecutiveTaps++;
            } else if (powerTapInterval < POWER_SHORT_TAP_SEQUENCE_MAX_INTERVAL_MS) {
                mPowerButtonConsecutiveTaps++;
            } else {
                mPowerButtonConsecutiveTaps = 1;
            }
            mLastPowerDown = event.getEventTime();
        }
        if (DBG && mPowerButtonConsecutiveTaps > 1) {
            Slog.i(TAG, Long.valueOf(mPowerButtonConsecutiveTaps) +
                    " consecutive power button taps detected");
        }
        if (launched) {
            Slog.i(TAG, "Power button double tap gesture detected, launching camera. Interval="
                    + powerTapInterval + "ms");
            launched = handleCameraGesture(false /* useWakelock */,
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP);
            if (launched) {
                mMetricsLogger.action(MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE,
                        (int) powerTapInterval);
                mUiEventLogger.log(GestureLauncherEvent.GESTURE_CAMERA_DOUBLE_TAP_POWER);
            }
        }
        mMetricsLogger.histogram("power_consecutive_short_tap_count", mPowerButtonConsecutiveTaps);
        mMetricsLogger.histogram("power_double_tap_interval", (int) powerTapInterval);
        outLaunched.value = launched;
        return intercept && launched;
    }

    /**
     * @return true if camera was launched, false otherwise.
     */
    @VisibleForTesting
    boolean handleCameraGesture(boolean useWakelock, int source) {
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "GestureLauncher:handleCameraGesture");
        try {
            boolean userSetupComplete = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0;
            if (!userSetupComplete) {
                if (DBG) {
                    Slog.d(TAG, String.format(
                            "userSetupComplete = %s, ignoring camera gesture.",
                            userSetupComplete));
                }
                return false;
            }
            if (DBG) {
                Slog.d(TAG, String.format(
                        "userSetupComplete = %s, performing camera gesture.",
                        userSetupComplete));
            }

            if (useWakelock) {
                // Make sure we don't sleep too early
                mWakeLock.acquire(500L);
            }
            StatusBarManagerInternal service = LocalServices.getService(
                    StatusBarManagerInternal.class);
            service.onCameraLaunchGestureDetected(source);
            return true;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                mUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
                registerContentObservers();
                updateCameraRegistered();
                updateCameraDoubleTapPowerEnabled();
            }
        }
    };

    private final ContentObserver mSettingObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange, android.net.Uri uri, int userId) {
            if (userId == mUserId) {
                updateCameraRegistered();
                updateCameraDoubleTapPowerEnabled();
            }
        }
    };

    private final class GestureEventListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!mCameraLaunchRegistered) {
              if (DBG) Slog.d(TAG, "Ignoring gesture event because it's unregistered.");
              return;
            }
            if (event.sensor == mCameraLaunchSensor) {
                if (DBG) {
                    float[] values = event.values;
                    Slog.d(TAG, String.format("Received a camera launch event: " +
                            "values=[%.4f, %.4f, %.4f].", values[0], values[1], values[2]));
                }
                if (handleCameraGesture(true /* useWakelock */,
                        StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE)) {
                    mMetricsLogger.action(MetricsEvent.ACTION_WIGGLE_CAMERA_GESTURE);
                    mUiEventLogger.log(GestureLauncherEvent.GESTURE_CAMERA_WIGGLE);
                    trackCameraLaunchEvent(event);
                }
                return;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Ignored.
        }

        private void trackCameraLaunchEvent(SensorEvent event) {
            long now = SystemClock.elapsedRealtime();
            long totalDuration = now - mCameraGestureOnTimeMs;
            // values[0]: ratio between total time duration when accel is turned on and time
            //            duration since camera launch gesture is subscribed.
            // values[1]: ratio between total time duration when gyro is turned on and time duration
            //            since camera launch gesture is subscribed.
            // values[2]: extra information
            float[] values = event.values;

            long sensor1OnTime = (long) (totalDuration * (double) values[0]);
            long sensor2OnTime = (long) (totalDuration * (double) values[1]);
            int extra = (int) values[2];

            // We only log the difference in the event log to make aggregation easier.
            long gestureOnTimeDiff = now - mCameraGestureLastEventTime;
            long sensor1OnTimeDiff = sensor1OnTime - mCameraGestureSensor1LastOnTimeMs;
            long sensor2OnTimeDiff = sensor2OnTime - mCameraGestureSensor2LastOnTimeMs;
            int extraDiff = extra - mCameraLaunchLastEventExtra;

            // Gating against negative time difference. This doesn't usually happen, but it may
            // happen because of numeric errors.
            if (gestureOnTimeDiff < 0 || sensor1OnTimeDiff < 0 || sensor2OnTimeDiff < 0) {
                if (DBG) Slog.d(TAG, "Skipped event logging because negative numbers.");
                return;
            }

            if (DBG) Slog.d(TAG, String.format("totalDuration: %d, sensor1OnTime: %s, " +
                    "sensor2OnTime: %d, extra: %d",
                    gestureOnTimeDiff,
                    sensor1OnTimeDiff,
                    sensor2OnTimeDiff,
                    extraDiff));
            EventLogTags.writeCameraGestureTriggered(
                    gestureOnTimeDiff,
                    sensor1OnTimeDiff,
                    sensor2OnTimeDiff,
                    extraDiff);

            mCameraGestureLastEventTime = now;
            mCameraGestureSensor1LastOnTimeMs = sensor1OnTime;
            mCameraGestureSensor2LastOnTimeMs = sensor2OnTime;
            mCameraLaunchLastEventExtra = extra;
        }
    }

    private final class CameraLiftTriggerEventListener extends TriggerEventListener {
        @Override
        public void onTrigger(TriggerEvent event) {
            if (DBG_CAMERA_LIFT) Slog.d(TAG, String.format("onTrigger event - time: %d, name: %s",
                    event.timestamp, event.sensor.getName()));
            if (!mCameraLiftRegistered) {
              if (DBG_CAMERA_LIFT) Slog.d(TAG, "Ignoring camera lift event because it's " +
                      "unregistered.");
              return;
            }
            if (event.sensor == mCameraLiftTriggerSensor) {
                Resources resources = mContext.getResources();
                SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                        Context.SENSOR_SERVICE);
                boolean keyguardShowingAndNotOccluded =
                        mWindowManagerInternal.isKeyguardShowingAndNotOccluded();
                boolean interactive = mPowerManager.isInteractive();
                if (DBG_CAMERA_LIFT) {
                    float[] values = event.values;
                    Slog.d(TAG, String.format("Received a camera lift trigger " +
                            "event: values=[%.4f], keyguard showing: %b, interactive: %b", values[0],
                            keyguardShowingAndNotOccluded, interactive));
                }
                if (keyguardShowingAndNotOccluded || !interactive) {
                    if (handleCameraGesture(true /* useWakelock */,
                            StatusBarManager.CAMERA_LAUNCH_SOURCE_LIFT_TRIGGER)) {
                        MetricsLogger.action(mContext, MetricsEvent.ACTION_CAMERA_LIFT_TRIGGER);
                        mUiEventLogger.log(GestureLauncherEvent.GESTURE_CAMERA_LIFT);
                    }
                } else {
                    if (DBG_CAMERA_LIFT) Slog.d(TAG, "Ignoring lift event");
                }

                mCameraLiftRegistered = sensorManager.requestTriggerSensor(
                        mCameraLiftTriggerListener, mCameraLiftTriggerSensor);

                if (DBG_CAMERA_LIFT) Slog.d(TAG, "Camera lift trigger sensor re-registered: " +
                        mCameraLiftRegistered);
                return;
            }
        }
    }
}
