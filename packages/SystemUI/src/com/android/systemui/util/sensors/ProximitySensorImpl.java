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
import android.os.Build;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.Execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
class ProximitySensorImpl implements ProximitySensor {
    private static final String TAG = "ProxSensor";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG) || Build.IS_DEBUGGABLE;
    private static final long SECONDARY_PING_INTERVAL_MS = 5000;

    ThresholdSensor mPrimaryThresholdSensor;
    ThresholdSensor mSecondaryThresholdSensor;
    private final DelayableExecutor mDelayableExecutor;
    private final Execution mExecution;
    private final List<ThresholdSensor.Listener> mListeners = new ArrayList<>();
    private String mTag = null;
    @VisibleForTesting protected boolean mPaused;
    private ThresholdSensorEvent mLastPrimaryEvent;
    @VisibleForTesting
    ThresholdSensorEvent mLastEvent;
    private boolean mRegistered;
    private final AtomicBoolean mAlerting = new AtomicBoolean();
    private Runnable mCancelSecondaryRunnable;
    boolean mInitializedListeners = false;
    private boolean mSecondarySafe = false; // safe to skip primary sensor check and use secondary
    protected @DevicePostureController.DevicePostureInt int mDevicePosture;

    final ThresholdSensor.Listener mPrimaryEventListener = this::onPrimarySensorEvent;

    final ThresholdSensor.Listener mSecondaryEventListener =
            new ThresholdSensor.Listener() {
        @Override
        public void onThresholdCrossed(ThresholdSensorEvent event) {
            // If we no longer have a "below" signal and the secondary sensor is not
            // considered "safe", then we need to turn it off.
            if (!mSecondarySafe
                    && (mLastPrimaryEvent == null
                    || !mLastPrimaryEvent.getBelow()
                    || !event.getBelow())) {
                chooseSensor();
                if (mLastPrimaryEvent == null || !mLastPrimaryEvent.getBelow()) {
                    // Only check the secondary as long as the primary thinks we're near.
                    if (mCancelSecondaryRunnable != null) {
                        mCancelSecondaryRunnable.run();
                        mCancelSecondaryRunnable = null;
                    }
                    return;
                } else {
                    // Check this sensor again in a moment.
                    mCancelSecondaryRunnable = mDelayableExecutor.executeDelayed(() -> {
                        // This is safe because we know that mSecondaryThresholdSensor
                        // is loaded, otherwise we wouldn't be here.
                        mPrimaryThresholdSensor.pause();
                        mSecondaryThresholdSensor.resume();
                    },
                        SECONDARY_PING_INTERVAL_MS);
                }
            }
            logDebug("Secondary sensor event: " + event.getBelow() + ".");

            if (!mPaused) {
                onSensorEvent(event);
            }
        }
    };

    @Inject
    ProximitySensorImpl(
            @PrimaryProxSensor ThresholdSensor primary,
            @SecondaryProxSensor ThresholdSensor  secondary,
            @Main DelayableExecutor delayableExecutor,
            Execution execution) {
        mPrimaryThresholdSensor = primary;
        mSecondaryThresholdSensor = secondary;
        mDelayableExecutor = delayableExecutor;
        mExecution = execution;
    }

    @Override
    public void setTag(String tag) {
        mTag = tag;
        mPrimaryThresholdSensor.setTag(tag + ":primary");
        mSecondaryThresholdSensor.setTag(tag + ":secondary");
    }

    @Override
    public void setDelay(int delay) {
        mExecution.assertIsMainThread();
        mPrimaryThresholdSensor.setDelay(delay);
        mSecondaryThresholdSensor.setDelay(delay);
    }

    /**
     * Unregister with the {@link SensorManager} without unsetting listeners on this object.
     */
    @Override
    public void pause() {
        mExecution.assertIsMainThread();
        mPaused = true;
        unregisterInternal();
    }

    /**
     * Register with the {@link SensorManager}. No-op if no listeners are registered on this object.
     */
    @Override
    public void resume() {
        mExecution.assertIsMainThread();
        mPaused = false;
        registerInternal();
    }

    @Override
    public void setSecondarySafe(boolean safe) {
        mSecondarySafe = mSecondaryThresholdSensor.isLoaded() && safe;
        chooseSensor();
    }

    /**
     * Returns true if we are registered with the SensorManager.
     */
    @Override
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
        mExecution.assertIsMainThread();
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
        mExecution.assertIsMainThread();
        if (mRegistered || mPaused || mListeners.isEmpty()) {
            return;
        }
        if (!mInitializedListeners) {
            mPrimaryThresholdSensor.pause();
            mSecondaryThresholdSensor.pause();
            mPrimaryThresholdSensor.register(mPrimaryEventListener);
            mSecondaryThresholdSensor.register(mSecondaryEventListener);
            mInitializedListeners = true;
        }

        mRegistered = true;
        chooseSensor();
    }

    private void chooseSensor() {
        mExecution.assertIsMainThread();
        if (!mRegistered || mPaused || mListeners.isEmpty()) {
            return;
        }
        if (mSecondarySafe) {
            mSecondaryThresholdSensor.resume();
            mPrimaryThresholdSensor.pause();
        } else {
            mPrimaryThresholdSensor.resume();
            mSecondaryThresholdSensor.pause();
        }
    }

    /**
     * Remove a listener.
     *
     * If all listeners are removed from an instance of this class,
     * it will unregister itself with the SensorManager.
     */
    @Override
    public void unregister(ThresholdSensor.Listener listener) {
        mExecution.assertIsMainThread();
        mListeners.remove(listener);
        if (mListeners.size() == 0) {
            unregisterInternal();
        }
    }

    @Override
    public void destroy() {
        pause();
    }

    @Override
    public String getName() {
        return mPrimaryThresholdSensor.getName();
    }

    @Override
    public String getType() {
        return mPrimaryThresholdSensor.getType();
    }

    protected void unregisterInternal() {
        mExecution.assertIsMainThread();
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

    @Override
    public Boolean isNear() {
        return isLoaded() && mLastEvent != null ? mLastEvent.getBelow() : null;
    }

    @Override
    public void alertListeners() {
        mExecution.assertIsMainThread();
        if (mAlerting.getAndSet(true)) {
            return;
        }
        if (mLastEvent != null) {
            ThresholdSensorEvent lastEvent = mLastEvent;  // Listeners can null out mLastEvent.
            List<ThresholdSensor.Listener> listeners = new ArrayList<>(mListeners);
            listeners.forEach(proximitySensorListener ->
                    proximitySensorListener.onThresholdCrossed(lastEvent));
        }

        mAlerting.set(false);
    }

    private void onPrimarySensorEvent(ThresholdSensorEvent event) {
        mExecution.assertIsMainThread();
        if (mLastPrimaryEvent != null && event.getBelow() == mLastPrimaryEvent.getBelow()) {
            return;
        }

        mLastPrimaryEvent = event;

        if (mSecondarySafe && mSecondaryThresholdSensor.isLoaded()) {
            logDebug("Primary sensor reported " + (event.getBelow() ? "near" : "far")
                    + ". Checking secondary.");
            if (mCancelSecondaryRunnable == null) {
                mSecondaryThresholdSensor.resume();
            }
            return;
        }


        if (!mSecondaryThresholdSensor.isLoaded()) {  // No secondary
            logDebug("Primary sensor event: " + event.getBelow() + ". No secondary.");
            onSensorEvent(event);
        } else if (event.getBelow()) {  // Covered? Check secondary.
            logDebug("Primary sensor event: " + event.getBelow() + ". Checking secondary.");
            if (mCancelSecondaryRunnable != null) {
                mCancelSecondaryRunnable.run();
            }
            mSecondaryThresholdSensor.resume();
        } else {  // Uncovered. Report immediately.
            onSensorEvent(event);
        }
    }

    private void onSensorEvent(ThresholdSensorEvent event) {
        mExecution.assertIsMainThread();
        if (mLastEvent != null && event.getBelow() == mLastEvent.getBelow()) {
            return;
        }

        if (!mSecondarySafe && !event.getBelow()) {
            chooseSensor();
        }

        mLastEvent = event;
        alertListeners();
    }

    @Override
    public String toString() {
        return String.format("{registered=%s, paused=%s, near=%s, posture=%s, primarySensor=%s, "
                + "secondarySensor=%s secondarySafe=%s}",
                isRegistered(), mPaused, isNear(), mDevicePosture, mPrimaryThresholdSensor,
                mSecondaryThresholdSensor, mSecondarySafe);
    }

    void logDebug(String msg) {
        if (DEBUG) {
            Log.d(TAG, (mTag != null ? "[" + mTag + "] " : "") + msg);
        }
    }
}
