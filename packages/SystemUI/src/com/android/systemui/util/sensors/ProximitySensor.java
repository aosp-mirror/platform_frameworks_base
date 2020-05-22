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

package com.android.systemui.util.sensors;

import android.hardware.SensorManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.Assert;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Wrapper around SensorManager customized for the Proximity sensor.
 *
 * The ProximitySensor supports the concept of a primary and a
 * secondary hardware sensor. The primary sensor is used for a first
 * pass check if the phone covered. When triggered, it then checks
 * the secondary sensor for confirmation (if there is one). It does
 * not send a proximity event until the secondary sensor confirms (or
 * rejects) the reading. The secondary sensor is, in fact, the source
 * of truth.
 *
 * This is necessary as sometimes keeping the secondary sensor on for
 * extends periods is undesirable. It may, however, result in increased
 * latency for proximity readings.
 *
 * Phones should configure this via a config.xml overlay. If no
 * proximity sensor is set (primary or secondary) we fall back to the
 * default Sensor.TYPE_PROXIMITY. If proximity_sensor_type is set in
 * config.xml, that will be used as the primary sensor. If
 * proximity_sensor_secondary_type is set, that will function as the
 * secondary sensor. If no secondary is set, only the primary will be
 * used.
 */
public class ProximitySensor implements ThresholdSensor {
    private static final String TAG = "ProxSensor";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long SECONDARY_PING_INTERVAL_MS = 5000;

    private final ThresholdSensor mPrimaryThresholdSensor;
    private final ThresholdSensor mSecondaryThresholdSensor;
    private final DelayableExecutor mDelayableExecutor;
    private final List<ThresholdSensor.Listener> mListeners = new ArrayList<>();
    private String mTag = null;
    @VisibleForTesting protected boolean mPaused;
    private ThresholdSensorEvent mLastPrimaryEvent;
    @VisibleForTesting
    ThresholdSensorEvent mLastEvent;
    private boolean mRegistered;
    private final AtomicBoolean mAlerting = new AtomicBoolean();
    private Runnable mCancelSecondaryRunnable;
    private boolean mInitializedListeners = false;

    private ThresholdSensor.Listener mPrimaryEventListener = new ThresholdSensor.Listener() {
        @Override
        public void onThresholdCrossed(ThresholdSensorEvent event) {
            onPrimarySensorEvent(event);
        }
    };

    private ThresholdSensor.Listener mSecondaryEventListener = new ThresholdSensor.Listener() {
        @Override
        public void onThresholdCrossed(ThresholdSensorEvent event) {
            // This sensor should only be used briefly. Turn it off as soon as we get a reading.
            mSecondaryThresholdSensor.pause();

            // Only check the secondary as long as the primary thinks we're near.
            if (!mLastPrimaryEvent.getBelow()) {
                mCancelSecondaryRunnable = null;
                return;
            }
            logDebug("Secondary sensor event: " + event.getBelow() + ".");

            // Check this sensor again in a moment.
            mCancelSecondaryRunnable = mDelayableExecutor.executeDelayed(
                    mSecondaryThresholdSensor::resume, SECONDARY_PING_INTERVAL_MS);

            onSensorEvent(event);
        }
    };

    @Inject
    public ProximitySensor(@PrimaryProxSensor ThresholdSensor primary,
            @SecondaryProxSensor ThresholdSensor  secondary,
            @Main DelayableExecutor delayableExecutor) {
        mPrimaryThresholdSensor = primary;
        mSecondaryThresholdSensor = secondary;
        mDelayableExecutor = delayableExecutor;
    }

    @Override
    public void setTag(String tag) {
        mTag = tag;
        mPrimaryThresholdSensor.setTag(tag + ":primary");
        mSecondaryThresholdSensor.setTag(tag + ":secondary");
    }

    @Override
    public void setDelay(int delay) {
        Assert.isMainThread();
        mPrimaryThresholdSensor.setDelay(delay);
        mSecondaryThresholdSensor.setDelay(delay);
    }

    /**
     * Unregister with the {@link SensorManager} without unsetting listeners on this object.
     */
    @Override
    public void pause() {
        Assert.isMainThread();
        mPaused = true;
        unregisterInternal();
    }

    /**
     * Register with the {@link SensorManager}. No-op if no listeners are registered on this object.
     */
    @Override
    public void resume() {
        Assert.isMainThread();
        mPaused = false;
        registerInternal();
    }


    /**
     * Returns true if we are registered with the SensorManager.
     */
    public boolean isRegistered() {
        return mRegistered;
    }

    /**
     * Returns {@code false} if a Proximity sensor is not available.
     */
    @Override
    public boolean isLoaded() {
        return mPrimaryThresholdSensor.isLoaded();
    }

    /**
     * Add a listener.
     *
     * Registers itself with the {@link SensorManager} if this is the first listener
     * added. If the ProximitySensor is paused, it will be registered when resumed.
     */
    @Override
    public void register(ThresholdSensor.Listener listener) {
        Assert.isMainThread();
        if (!isLoaded()) {
            return;
        }

        if (mListeners.contains(listener)) {
            logDebug("ProxListener registered multiple times: " + listener);
        } else {
            mListeners.add(listener);
        }
        registerInternal();
    }

    protected void registerInternal() {
        Assert.isMainThread();
        if (mRegistered || mPaused || mListeners.isEmpty()) {
            return;
        }
        if (!mInitializedListeners) {
            mPrimaryThresholdSensor.register(mPrimaryEventListener);
            mSecondaryThresholdSensor.pause();
            mSecondaryThresholdSensor.register(mSecondaryEventListener);
            mInitializedListeners = true;
        }
        logDebug("Registering sensor listener");
        mPrimaryThresholdSensor.resume();
        mRegistered = true;
    }

    /**
     * Remove a listener.
     *
     * If all listeners are removed from an instance of this class,
     * it will unregister itself with the SensorManager.
     */
    @Override
    public void unregister(ThresholdSensor.Listener listener) {
        Assert.isMainThread();
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            unregisterInternal();
        }
    }

    protected void unregisterInternal() {
        Assert.isMainThread();
        if (!mRegistered) {
            return;
        }
        logDebug("unregistering sensor listener");
        mPrimaryThresholdSensor.pause();
        mSecondaryThresholdSensor.pause();
        if (mCancelSecondaryRunnable != null) {
            mCancelSecondaryRunnable.run();
            mCancelSecondaryRunnable = null;
        }
        mLastPrimaryEvent = null;  // Forget what we know.
        mLastEvent = null;
        mRegistered = false;
    }

    public Boolean isNear() {
        return isLoaded() && mLastEvent != null ? mLastEvent.getBelow() : null;
    }

    /** Update all listeners with the last value this class received from the sensor. */
    public void alertListeners() {
        Assert.isMainThread();
        if (mAlerting.getAndSet(true)) {
            return;
        }
        if (mLastEvent != null) {
            List<ThresholdSensor.Listener> listeners = new ArrayList<>(mListeners);
            listeners.forEach(proximitySensorListener ->
                    proximitySensorListener.onThresholdCrossed(mLastEvent));
        }

        mAlerting.set(false);
    }

    private void onPrimarySensorEvent(ThresholdSensorEvent event) {
        Assert.isMainThread();
        if (mLastPrimaryEvent != null && event.getBelow() == mLastPrimaryEvent.getBelow()) {
            return;
        }

        mLastPrimaryEvent = event;

        if (event.getBelow() && mSecondaryThresholdSensor.isLoaded()) {
            logDebug("Primary sensor is near. Checking secondary.");
            if (mCancelSecondaryRunnable == null) {
                mSecondaryThresholdSensor.resume();
            }
        } else {
            if (!mSecondaryThresholdSensor.isLoaded()) {
                logDebug("Primary sensor event: " + event.getBelow() + ". No secondary.");
            } else {
                logDebug("Primary sensor event: " + event.getBelow() + ".");
            }
            onSensorEvent(event);
        }
    }

    private void onSensorEvent(ThresholdSensorEvent event) {
        Assert.isMainThread();
        if (mLastEvent != null && event.getBelow() == mLastEvent.getBelow()) {
            return;
        }

        mLastEvent = event;
        alertListeners();
    }

    @Override
    public String toString() {
        return String.format("{registered=%s, paused=%s, near=%s, primarySensor=%s, "
                + "secondarySensor=%s}",
                isRegistered(), mPaused, isNear(), mPrimaryThresholdSensor,
                mSecondaryThresholdSensor);
    }

    /**
     * Convenience class allowing for briefly checking the proximity sensor.
     */
    public static class ProximityCheck implements Runnable {

        private final ProximitySensor mSensor;
        private final DelayableExecutor mDelayableExecutor;
        private List<Consumer<Boolean>> mCallbacks = new ArrayList<>();
        private final ThresholdSensor.Listener mListener;
        private final AtomicBoolean mRegistered = new AtomicBoolean();

        @Inject
        public ProximityCheck(ProximitySensor sensor, DelayableExecutor delayableExecutor) {
            mSensor = sensor;
            mSensor.setTag("prox_check");
            mDelayableExecutor = delayableExecutor;
            mListener = this::onProximityEvent;
        }

        /** Set a descriptive tag for the sensors registration. */
        public void setTag(String tag) {
            mSensor.setTag(tag);
        }

        @Override
        public void run() {
            unregister();
            mSensor.alertListeners();
        }

        /**
         * Query the proximity sensor, timing out if no result.
         */
        public void check(long timeoutMs, Consumer<Boolean> callback) {
            if (!mSensor.isLoaded()) {
                callback.accept(null);
            }
            mCallbacks.add(callback);
            if (!mRegistered.getAndSet(true)) {
                mSensor.register(mListener);
                mDelayableExecutor.executeDelayed(this, timeoutMs);
            }
        }

        private void unregister() {
            mSensor.unregister(mListener);
            mRegistered.set(false);
        }

        private void onProximityEvent(ThresholdSensorEvent proximityEvent) {
            mCallbacks.forEach(
                    booleanConsumer ->
                            booleanConsumer.accept(
                                    proximityEvent == null ? null : proximityEvent.getBelow()));
            mCallbacks.clear();
            unregister();
            mRegistered.set(false);
        }
    }

    private void logDebug(String msg) {
        if (DEBUG) {
            Log.d(TAG, (mTag != null ? "[" + mTag + "] " : "") + msg);
        }
    }
}
