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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
    private boolean mDestroyRequested = false;
    private boolean mDisableRequested = false;
    private NextConsumer mNextConsumer = null;
    private volatile float mLastAmbientLux = -1;

    private final SensorEventListener mLightSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            onNext(event.values[0]);
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
        if (!mDestroyed && !mDestroyRequested) {
            mDisableRequested = false;
            enableLightSensorLoggingLocked();
        }
    }

    @Override
    public synchronized void disable() {
        mDisableRequested = true;

        // if a final consumer is set it will call destroy/disable on the next value if requested
        if (!mDestroyed && mNextConsumer == null) {
            disableLightSensorLoggingLocked(false /* destroying */);
        }
    }

    @Override
    public synchronized void destroy() {
        mDestroyRequested = true;

        // if a final consumer is set it will call destroy/disable on the next value if requested
        if (!mDestroyed && mNextConsumer == null) {
            disableLightSensorLoggingLocked(true /* destroying */);
            mDestroyed = true;
        }
    }

    private synchronized void onNext(float value) {
        mLastAmbientLux = value;

        final NextConsumer consumer = mNextConsumer;
        mNextConsumer = null;
        if (consumer != null) {
            Slog.v(TAG, "Finishing next consumer");

            if (mDestroyRequested) {
                destroy();
            } else if (mDisableRequested) {
                disable();
            }

            consumer.consume(value);
        }
    }

    /** The most recent lux reading. */
    public float getMostRecentLux() {
        return mLastAmbientLux;
    }

    /**
     * Register a listener for the next available ALS reading, which will be reported to the given
     * consumer even if this probe is {@link #disable()}'ed or {@link #destroy()}'ed before a value
     * is available.
     *
     * This method is intended to be used for event logs that occur when the screen may be
     * off and sampling may have been {@link #disable()}'ed. In these cases, this method will turn
     * on the sensor (if needed), fetch & report the first value, and then destroy or disable this
     * probe (if needed).
     *
     * @param consumer consumer to notify when the data is available
     * @param handler handler for notifying the consumer, or null
     */
    public synchronized void awaitNextLux(@NonNull Consumer<Float> consumer,
            @Nullable Handler handler) {
        final NextConsumer nextConsumer = new NextConsumer(consumer, handler);
        final float current = mLastAmbientLux;
        if (current > -1f) {
            nextConsumer.consume(current);
        } else if (mNextConsumer != null) {
            mNextConsumer.add(nextConsumer);
        } else {
            mDestroyed = false;
            mNextConsumer = nextConsumer;
            enableLightSensorLoggingLocked();
        }
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

    private void disableLightSensorLoggingLocked(boolean destroying) {
        resetTimerLocked(false /* start */);

        if (mEnabled) {
            mEnabled = false;
            if (!destroying) {
                mLastAmbientLux = -1;
            }
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

    private synchronized void onTimeout() {
        Slog.e(TAG, "Max time exceeded for ALS logger - disabling: "
                + mLightSensorListener.hashCode());

        // if consumers are waiting but there was no sensor change, complete them with the latest
        // value before disabling
        onNext(mLastAmbientLux);
        disable();
    }

    private static class NextConsumer {
        @NonNull private final Consumer<Float> mConsumer;
        @Nullable private final Handler mHandler;
        @NonNull private final List<NextConsumer> mOthers = new ArrayList<>();

        private NextConsumer(@NonNull Consumer<Float> consumer, @Nullable Handler handler) {
            mConsumer = consumer;
            mHandler = handler;
        }

        public void consume(float value) {
            if (mHandler != null) {
                mHandler.post(() -> mConsumer.accept(value));
            } else {
                mConsumer.accept(value);
            }
            for (NextConsumer c : mOthers) {
                c.consume(value);
            }
        }

        public void add(NextConsumer consumer) {
            mOthers.add(consumer);
        }
    }
}
