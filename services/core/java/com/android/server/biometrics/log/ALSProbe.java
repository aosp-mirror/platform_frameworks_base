/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.sensors.BaseClientMonitor;

import java.util.concurrent.TimeUnit;

/** Probe for ambient light. */
final class ALSProbe implements Probe {
    private static final String TAG = "ALSProbe";

    @Nullable
    private final SensorManager mSensorManager;
    @Nullable
    private final Sensor mLightSensor;
    @NonNull
    private final Handler mTimer;
    @DurationMillisLong
    private long mMaxSubscriptionTime = -1;

    private boolean mEnabled = false;
    private boolean mDestroyed = false;
    private volatile float mLastAmbientLux = -1;

    private final SensorEventListener mLightSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            mLastAmbientLux = event.values[0];
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    /**
     * Create a probe with a 1-minute max sampling time.
     *
     * @param sensorManager Sensor manager
     */
    ALSProbe(@NonNull SensorManager sensorManager) {
        this(sensorManager, new Handler(Looper.getMainLooper()),
                TimeUnit.MINUTES.toMillis(1));
    }

    /**
     * Create a probe with a given max sampling time.
     *
     * Note: The max time is a workaround for potential scheduler bugs where
     * {@link BaseClientMonitor#destroy()} is not called due to an abnormal lifecycle. Clients
     * should ensure that {@link #disable()} and {@link #destroy()} are called appropriately and
     * avoid relying on this timeout to unsubscribe from the sensor when it is not needed.
     *
     * @param sensorManager Sensor manager
     * @param handler Timeout handler
     * @param maxTime The max amount of time to subscribe to events. If this time is exceeded
     *                {@link #disable()} will be called and no sampling will occur until {@link
     *                #enable()} is called again.
     */
    @VisibleForTesting
    ALSProbe(@Nullable SensorManager sensorManager, @NonNull Handler handler,
            @DurationMillisLong long maxTime) {
        mSensorManager = sensorManager;
        mLightSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) : null;
        mTimer = handler;
        mMaxSubscriptionTime = maxTime;

        if (mSensorManager == null || mLightSensor == null) {
            Slog.w(TAG, "No sensor - probe disabled");
            mDestroyed = true;
        }
    }

    @Override
    public synchronized void enable() {
        if (!mDestroyed) {
            enableLightSensorLoggingLocked();
        }
    }

    @Override
    public synchronized void disable() {
        if (!mDestroyed) {
            disableLightSensorLoggingLocked();
        }
    }

    @Override
    public synchronized void destroy() {
        disable();
        mDestroyed = true;
    }

    /** The most recent lux reading. */
    public float getCurrentLux() {
        return mLastAmbientLux;
    }

    private void enableLightSensorLoggingLocked() {
        if (!mEnabled) {
            mEnabled = true;
            mLastAmbientLux = -1;
            mSensorManager.registerListener(mLightSensorListener, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
            Slog.v(TAG, "Enable ALS: " + mLightSensorListener.hashCode());
        }

        resetTimerLocked(true /* start */);
    }

    private void disableLightSensorLoggingLocked() {
        resetTimerLocked(false /* start */);

        if (mEnabled) {
            mEnabled = false;
            mLastAmbientLux = -1;
            mSensorManager.unregisterListener(mLightSensorListener);
            Slog.v(TAG, "Disable ALS: " + mLightSensorListener.hashCode());
        }
    }

    private void resetTimerLocked(boolean start) {
        mTimer.removeCallbacksAndMessages(this /* token */);
        if (start && mMaxSubscriptionTime > 0) {
            mTimer.postDelayed(this::onTimeout, this /* token */, mMaxSubscriptionTime);
        }
    }

    private void onTimeout() {
        Slog.e(TAG, "Max time exceeded for ALS logger - disabling: "
                + mLightSensorListener.hashCode());
        disable();
    }
}
