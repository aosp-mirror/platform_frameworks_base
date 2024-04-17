/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.power;

import static android.provider.DeviceConfig.NAMESPACE_ATTENTION_MANAGER_SERVICE;

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Class used to detect when the phone is placed face down. This is used for Flip to Screen Off. A
 * client can use this detector to trigger state changes like screen off when the phone is face
 * down.
 */
public class FaceDownDetector implements SensorEventListener {

    private static final String TAG = "FaceDownDetector";
    private static final boolean DEBUG = false;

    private static final int SCREEN_OFF_RESULT =
            FrameworkStatsLog.FACE_DOWN_REPORTED__FACE_DOWN_RESPONSE__SCREEN_OFF;
    private static final int USER_INTERACTION =
            FrameworkStatsLog.FACE_DOWN_REPORTED__FACE_DOWN_RESPONSE__USER_INTERACTION;
    private static final int UNFLIP =
            FrameworkStatsLog.FACE_DOWN_REPORTED__FACE_DOWN_RESPONSE__UNFLIP;
    private static final int UNKNOWN =
            FrameworkStatsLog.FACE_DOWN_REPORTED__FACE_DOWN_RESPONSE__UNKNOWN;

    /**
     * Used by the ExponentialMovingAverage accelerations, this determines how quickly the
     * average can change. A number closer to 1 will mean it will take longer to change.
     */
    private static final float MOVING_AVERAGE_WEIGHT = 0.5f;

    /** DeviceConfig flag name, if {@code true}, enables Face Down features. */
    static final String KEY_FEATURE_ENABLED = "enable_flip_to_screen_off";

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_FEATURE_ENABLED = true;

    private boolean mIsEnabled;
    // Defaults to true, we only want to disable if this is specifically requested.
    private boolean mEnabledOverride = true;

    private int mSensorMaxLatencyMicros;

    /**
     * DeviceConfig flag name, determines how long to disable sensor when user interacts while
     * device is flipped.
     */
    private static final String KEY_INTERACTION_BACKOFF = "face_down_interaction_backoff_millis";

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final long DEFAULT_INTERACTION_BACKOFF = 60_000;

    private long mUserInteractionBackoffMillis;

    /**
     * DeviceConfig flag name, defines the max change in acceleration which will prevent face down
     * due to movement.
     */
    static final String KEY_ACCELERATION_THRESHOLD = "acceleration_threshold";

    /** Default value in absence of {@link DeviceConfig} override. */
    static final float DEFAULT_ACCELERATION_THRESHOLD = 0.2f;

    private float mAccelerationThreshold;

    /**
     * DeviceConfig flag name, defines the maximum z-axis acceleration that will indicate the phone
     * is face down.
     */
    static final String KEY_Z_ACCELERATION_THRESHOLD = "z_acceleration_threshold";

    /** Default value in absence of {@link DeviceConfig} override. */
    static final float DEFAULT_Z_ACCELERATION_THRESHOLD = -9.5f;

    private float mZAccelerationThreshold;

    /**
     * After going face down, we relax the threshold to make it more difficult to exit face down
     * than to enter it.
     */
    private float mZAccelerationThresholdLenient;

    /**
     * DeviceConfig flag name, defines the minimum amount of time that has to pass while the phone
     * is face down and not moving in order to trigger face down behavior, in milliseconds.
     */
    static final String KEY_TIME_THRESHOLD_MILLIS = "time_threshold_millis";

    /** Default value in absence of {@link DeviceConfig} override. */
    static final long DEFAULT_TIME_THRESHOLD_MILLIS = 1_000L;

    private Duration mTimeThreshold;

    private Sensor mAccelerometer;
    private SensorManager mSensorManager;
    private final Consumer<Boolean> mOnFlip;

    /** Values we store for logging purposes. */
    private long mLastFlipTime = 0L;
    public int mPreviousResultType = UNKNOWN;
    public long mPreviousResultTime = 0L;
    private long mMillisSaved = 0L;

    private final ExponentialMovingAverage mCurrentXYAcceleration =
            new ExponentialMovingAverage(MOVING_AVERAGE_WEIGHT);
    private final ExponentialMovingAverage mCurrentZAcceleration =
            new ExponentialMovingAverage(MOVING_AVERAGE_WEIGHT);

    private boolean mFaceDown = false;
    private boolean mInteractive = false;
    private boolean mActive = false;

    private float mPrevAcceleration = 0;
    private long mPrevAccelerationTime = 0;

    private boolean mZAccelerationIsFaceDown = false;
    private long mZAccelerationFaceDownTime = 0L;

    private final Handler mHandler;
    private final Runnable mUserActivityRunnable;
    @VisibleForTesting
    final BroadcastReceiver mScreenReceiver;

    private Context mContext;

    public FaceDownDetector(@NonNull Consumer<Boolean> onFlip) {
        mOnFlip = Objects.requireNonNull(onFlip);
        mHandler = new Handler(Looper.getMainLooper());
        mScreenReceiver = new ScreenStateReceiver();
        mUserActivityRunnable = () -> {
            if (mFaceDown) {
                exitFaceDown(USER_INTERACTION, SystemClock.uptimeMillis() - mLastFlipTime);
                updateActiveState();
            }
        };
    }

    /** Initializes the FaceDownDetector and all necessary listeners. */
    public void systemReady(Context context) {
        mContext = context;
        mSensorManager = context.getSystemService(SensorManager.class);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        readValuesFromDeviceConfig();
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                ActivityThread.currentApplication().getMainExecutor(),
                (properties) -> onDeviceConfigChange(properties.getKeyset()));
        updateActiveState();
    }

    private void registerScreenReceiver(Context context) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(mScreenReceiver, intentFilter);
    }

    /**
     * Sets the active state of the detector. If false, we will not process accelerometer changes.
     */
    private void updateActiveState() {
        final long currentTime = SystemClock.uptimeMillis();
        final boolean sawRecentInteraction = mPreviousResultType == USER_INTERACTION
                && currentTime - mPreviousResultTime  < mUserInteractionBackoffMillis;
        final boolean shouldBeActive = mInteractive && mIsEnabled && !sawRecentInteraction;
        if (mActive != shouldBeActive) {
            if (shouldBeActive) {
                mSensorManager.registerListener(
                        this,
                        mAccelerometer,
                        SensorManager.SENSOR_DELAY_NORMAL,
                        mSensorMaxLatencyMicros
                );
                if (mPreviousResultType == SCREEN_OFF_RESULT) {
                    logScreenOff();
                }
            } else {
                if (mFaceDown && !mInteractive) {
                    mPreviousResultType = SCREEN_OFF_RESULT;
                    mPreviousResultTime = currentTime;
                }
                mSensorManager.unregisterListener(this);
                mFaceDown = false;
                mOnFlip.accept(false);
            }
            mActive = shouldBeActive;
            if (DEBUG) Slog.d(TAG, "Update active - " + shouldBeActive);
        }
    }

    /** Prints state information about FaceDownDetector */
    public void dump(PrintWriter pw) {
        pw.println("FaceDownDetector:");
        pw.println("  mFaceDown=" + mFaceDown);
        pw.println("  mActive=" + mActive);
        pw.println("  mLastFlipTime=" + mLastFlipTime);
        pw.println("  mSensorMaxLatencyMicros=" + mSensorMaxLatencyMicros);
        pw.println("  mUserInteractionBackoffMillis=" + mUserInteractionBackoffMillis);
        pw.println("  mPreviousResultTime=" + mPreviousResultTime);
        pw.println("  mPreviousResultType=" + mPreviousResultType);
        pw.println("  mMillisSaved=" + mMillisSaved);
        pw.println("  mZAccelerationThreshold=" + mZAccelerationThreshold);
        pw.println("  mAccelerationThreshold=" + mAccelerationThreshold);
        pw.println("  mTimeThreshold=" + mTimeThreshold);
        pw.println("  mEnabledOverride=" + mEnabledOverride);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        if (!mActive || !mIsEnabled) return;

        final float x = event.values[0];
        final float y = event.values[1];
        mCurrentXYAcceleration.updateMovingAverage(x * x + y * y);
        mCurrentZAcceleration.updateMovingAverage(event.values[2]);

        // Detect movement
        // If the x, y acceleration is within the acc threshold for at least a length of time longer
        // than the time threshold, we set moving to true.
        final long curTime = event.timestamp;
        if (Math.abs(mCurrentXYAcceleration.mMovingAverage - mPrevAcceleration)
                > mAccelerationThreshold) {
            mPrevAcceleration = mCurrentXYAcceleration.mMovingAverage;
            mPrevAccelerationTime = curTime;
        }
        final boolean moving = curTime - mPrevAccelerationTime <= mTimeThreshold.toNanos();

        // If the z acceleration is beyond the gravity/z-acceleration threshold for at least a
        // length of time longer than the time threshold, we set isFaceDownForPeriod to true.
        final float zAccelerationThreshold =
                mFaceDown ? mZAccelerationThresholdLenient : mZAccelerationThreshold;
        final boolean isCurrentlyFaceDown =
                mCurrentZAcceleration.mMovingAverage < zAccelerationThreshold;
        final boolean isFaceDownForPeriod = isCurrentlyFaceDown
                && mZAccelerationIsFaceDown
                && curTime - mZAccelerationFaceDownTime > mTimeThreshold.toNanos();
        if (isCurrentlyFaceDown && !mZAccelerationIsFaceDown) {
            mZAccelerationFaceDownTime = curTime;
            mZAccelerationIsFaceDown = true;
        } else if (!isCurrentlyFaceDown) {
            mZAccelerationIsFaceDown = false;
        }


        if (!moving && isFaceDownForPeriod && !mFaceDown) {
            faceDownDetected();
        } else if (!isFaceDownForPeriod && mFaceDown) {
            unFlipDetected();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void faceDownDetected() {
        if (DEBUG) Slog.d(TAG, "Triggered faceDownDetected.");
        mLastFlipTime = SystemClock.uptimeMillis();
        mFaceDown = true;
        mOnFlip.accept(true);
    }

    private void unFlipDetected() {
        if (DEBUG) Slog.d(TAG, "Triggered exitFaceDown");
        exitFaceDown(UNFLIP, SystemClock.uptimeMillis() - mLastFlipTime);
    }

    /**
     * The user interacted with the screen while face down, indicated the phone is in use.
     * We log this event and temporarily make this detector inactive.
     */
    public void userActivity(int event) {
        if (event != PowerManager.USER_ACTIVITY_EVENT_FACE_DOWN) {
            mHandler.post(mUserActivityRunnable);
        }
    }

    private void exitFaceDown(int resultType, long millisSinceFlip) {
        FrameworkStatsLog.write(FrameworkStatsLog.FACE_DOWN_REPORTED,
                resultType,
                millisSinceFlip,
                /* millis_until_normal_timeout= */ 0L,
                /* millis_until_next_screen_on= */ 0L);
        mFaceDown = false;
        mLastFlipTime = 0L;
        mPreviousResultType = resultType;
        mPreviousResultTime = SystemClock.uptimeMillis();
        mOnFlip.accept(false);
    }

    private void logScreenOff() {
        final long currentTime = SystemClock.uptimeMillis();
        FrameworkStatsLog.write(FrameworkStatsLog.FACE_DOWN_REPORTED,
                SCREEN_OFF_RESULT,
                /* millis_since_flip= */ mPreviousResultTime  - mLastFlipTime,
                mMillisSaved,
                /* millis_until_next_screen_on= */ currentTime - mPreviousResultTime);
        mPreviousResultType = UNKNOWN;
    }

    private boolean isEnabled() {
        return mEnabledOverride && DeviceConfig.getBoolean(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_FEATURE_ENABLED, DEFAULT_FEATURE_ENABLED) && mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_flipToScreenOffEnabled);
    }

    private float getAccelerationThreshold() {
        return getFloatFlagValue(KEY_ACCELERATION_THRESHOLD,
                DEFAULT_ACCELERATION_THRESHOLD,
                -2.0f,
                2.0f);
    }

    private float getZAccelerationThreshold() {
        return getFloatFlagValue(KEY_Z_ACCELERATION_THRESHOLD,
                DEFAULT_Z_ACCELERATION_THRESHOLD,
                -15.0f,
                0.0f);
    }

    private long getUserInteractionBackoffMillis() {
        return getLongFlagValue(KEY_INTERACTION_BACKOFF,
                DEFAULT_INTERACTION_BACKOFF,
                0,
                3600_000);
    }

    private int getSensorMaxLatencyMicros() {
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_flipToScreenOffMaxLatencyMicros);
    }

    private float getFloatFlagValue(String key, float defaultValue, float min, float max) {
        final float value = DeviceConfig.getFloat(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                key,
                defaultValue);

        if (value < min || value > max) {
            Slog.w(TAG, "Bad flag value supplied for: " + key);
            return defaultValue;
        }

        return value;
    }

    private long getLongFlagValue(String key, long defaultValue, long min, long max) {
        final long value = DeviceConfig.getLong(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                key,
                defaultValue);

        if (value < min || value > max) {
            Slog.w(TAG, "Bad flag value supplied for: " + key);
            return defaultValue;
        }

        return value;
    }

    private Duration getTimeThreshold() {
        final long millis = DeviceConfig.getLong(NAMESPACE_ATTENTION_MANAGER_SERVICE,
                KEY_TIME_THRESHOLD_MILLIS,
                DEFAULT_TIME_THRESHOLD_MILLIS);

        if (millis < 0 || millis > 15_000) {
            Slog.w(TAG, "Bad flag value supplied for: " + KEY_TIME_THRESHOLD_MILLIS);
            return Duration.ofMillis(DEFAULT_TIME_THRESHOLD_MILLIS);
        }

        return Duration.ofMillis(millis);
    }

    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        for (String key : keys) {
            switch (key) {
                case KEY_ACCELERATION_THRESHOLD:
                case KEY_Z_ACCELERATION_THRESHOLD:
                case KEY_TIME_THRESHOLD_MILLIS:
                case KEY_FEATURE_ENABLED:
                    readValuesFromDeviceConfig();
                    updateActiveState();
                    return;
                default:
                    Slog.i(TAG, "Ignoring change on " + key);
            }
        }
    }

    private void readValuesFromDeviceConfig() {
        mAccelerationThreshold = getAccelerationThreshold();
        mZAccelerationThreshold = getZAccelerationThreshold();
        mZAccelerationThresholdLenient = mZAccelerationThreshold + 1.0f;
        mTimeThreshold = getTimeThreshold();
        mSensorMaxLatencyMicros = getSensorMaxLatencyMicros();
        mUserInteractionBackoffMillis = getUserInteractionBackoffMillis();
        final boolean oldEnabled = mIsEnabled;
        mIsEnabled = isEnabled();
        if (oldEnabled != mIsEnabled) {
            if (!mIsEnabled) {
                mContext.unregisterReceiver(mScreenReceiver);
                mInteractive = false;
            } else {
                registerScreenReceiver(mContext);
                mInteractive = mContext.getSystemService(PowerManager.class).isInteractive();
            }
        }

        Slog.i(TAG, "readValuesFromDeviceConfig():"
                + "\nmAccelerationThreshold=" + mAccelerationThreshold
                + "\nmZAccelerationThreshold=" + mZAccelerationThreshold
                + "\nmTimeThreshold=" + mTimeThreshold
                + "\nmIsEnabled=" + mIsEnabled);
    }

    /**
     * Allows detector to be enabled & disabled.
     * @param enabled whether to enable detector.
     */
    public void setEnabledOverride(boolean enabled) {
        mEnabledOverride = enabled;
        mIsEnabled = isEnabled();
    }

    /**
     * Sets how much screen on time might be saved as a result of this detector. Currently used for
     * logging purposes.
     */
    public void setMillisSaved(long millisSaved) {
        mMillisSaved = millisSaved;
    }

    private final class ScreenStateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mInteractive = false;
                updateActiveState();
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                mInteractive = true;
                updateActiveState();
            }
        }
    }

    private final class ExponentialMovingAverage {
        private final float mAlpha;
        private final float mInitialAverage;
        private float mMovingAverage;

        ExponentialMovingAverage(float alpha) {
            this(alpha, 0.0f);
        }

        ExponentialMovingAverage(float alpha, float initialAverage) {
            this.mAlpha = alpha;
            this.mInitialAverage = initialAverage;
            this.mMovingAverage = initialAverage;
        }

        void updateMovingAverage(float newValue) {
            mMovingAverage = newValue + mAlpha * (mMovingAverage - newValue);
        }

        void reset() {
            mMovingAverage = this.mInitialAverage;
        }
    }
}
