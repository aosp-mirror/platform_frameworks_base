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

package com.android.server.display;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.utils.SensorUtils;

import java.io.PrintWriter;

/**
 * Maintains the proximity state of the display.
 * Internally listens for proximity updates and schedules a power state update when the proximity
 * state changes.
 */
public final class DisplayPowerProximityStateController {
    @VisibleForTesting
    static final int MSG_PROXIMITY_SENSOR_DEBOUNCED = 1;
    @VisibleForTesting
    static final int PROXIMITY_UNKNOWN = -1;
    @VisibleForTesting
    static final int PROXIMITY_POSITIVE = 1;
    @VisibleForTesting
    static final int PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY = 0;

    private static final int MSG_IGNORE_PROXIMITY = 2;

    private static final int PROXIMITY_NEGATIVE = 0;

    private static final boolean DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT = false;
    // Proximity sensor debounce delay in milliseconds for positive transitions.

    // Proximity sensor debounce delay in milliseconds for negative transitions.
    private static final int PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY = 250;
    // Trigger proximity if distance is less than 5 cm.
    private static final float TYPICAL_PROXIMITY_THRESHOLD = 5.0f;

    private final String mTag;
    // A lock to handle the deadlock and race conditions.
    private final Object mLock = new Object();
    // The manager which lets us access the device's ProximitySensor
    private final SensorManager mSensorManager;
    // An entity which manages the wakelocks.
    private final WakelockController mWakelockController;
    // A handler to process all the events on this thread in a synchronous manner
    private final DisplayPowerProximityStateHandler mHandler;
    // A runnable to execute the utility to update the power state.
    private final Runnable mNudgeUpdatePowerState;
    private Clock mClock;
    // A listener which listen's to the events emitted by the proximity sensor.
    private final SensorEventListener mProximitySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mProximitySensorEnabled) {
                final long time = mClock.uptimeMillis();
                final float distance = event.values[0];
                boolean positive = distance >= 0.0f && distance < mProximityThreshold;
                handleProximitySensorEvent(time, positive);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    // The proximity sensor, or null if not available or needed.
    private Sensor mProximitySensor;

    // The configurations for the associated display
    private DisplayDeviceConfig mDisplayDeviceConfig;

    // True if a request has been made to wait for the proximity sensor to go negative.
    @GuardedBy("mLock")
    private boolean mPendingWaitForNegativeProximityLocked;

    // True if the device should wait for negative proximity sensor before
    // waking up the screen.  This is set to false as soon as a negative
    // proximity sensor measurement is observed or when the device is forced to
    // go to sleep by the user.  While true, the screen remains off.
    private boolean mWaitingForNegativeProximity;

    // True if the device should not take into account the proximity sensor
    // until either the proximity sensor state changes, or there is no longer a
    // request to listen to proximity sensor.
    private boolean mIgnoreProximityUntilChanged;

    // Set to true if the proximity sensor listener has been registered
    // with the sensor manager.
    private boolean mProximitySensorEnabled;

    // The raw non-debounced proximity sensor state.
    private int mPendingProximity = PROXIMITY_UNKNOWN;

    // -1 if fully debounced. Else, represents the time in ms when the debounce suspend blocker will
    // be removed. Applies for both positive and negative proximity flips.
    private long mPendingProximityDebounceTime = -1;

    // True if the screen was turned off because of the proximity sensor.
    // When the screen turns on again, we report user activity to the power manager.
    private boolean mScreenOffBecauseOfProximity;

    // The debounced proximity sensor state.
    private int mProximity = PROXIMITY_UNKNOWN;

    // The actual proximity sensor threshold value.
    private float mProximityThreshold;

    // A flag representing if the ramp is to be skipped when the proximity changes from positive
    // to negative
    private boolean mSkipRampBecauseOfProximityChangeToNegative = false;

    // The DisplayId of the associated Logical Display.
    private int mDisplayId;

    /**
     * Create a new instance of DisplayPowerProximityStateController.
     *
     * @param wakeLockController    WakelockController used to acquire/release wakelocks
     * @param displayDeviceConfig   DisplayDeviceConfig instance from which the configs(Proximity
     *                              Sensor) are to be loaded
     * @param looper                A looper onto which the handler is to be associated.
     * @param nudgeUpdatePowerState A runnable to execute the utility to update the power state
     * @param displayId             The DisplayId of the associated Logical Display.
     * @param sensorManager         The manager which lets us access the display's ProximitySensor
     */
    public DisplayPowerProximityStateController(
            WakelockController wakeLockController, DisplayDeviceConfig displayDeviceConfig,
            Looper looper,
            Runnable nudgeUpdatePowerState, int displayId, SensorManager sensorManager,
            Injector injector) {
        if (injector == null) {
            injector = new Injector();
        }
        mClock = injector.createClock();
        mWakelockController = wakeLockController;
        mHandler = new DisplayPowerProximityStateHandler(looper);
        mNudgeUpdatePowerState = nudgeUpdatePowerState;
        mDisplayDeviceConfig = displayDeviceConfig;
        mDisplayId = displayId;
        mTag = "DisplayPowerProximityStateController[" + mDisplayId + "]";
        mSensorManager = sensorManager;
        loadProximitySensor();
    }

    /**
     * Manages the pending state of the proximity.
     */
    public void updatePendingProximityRequestsLocked() {
        synchronized (mLock) {
            mWaitingForNegativeProximity |= mPendingWaitForNegativeProximityLocked;
            mPendingWaitForNegativeProximityLocked = false;

            if (mIgnoreProximityUntilChanged) {
                // Also, lets stop waiting for negative proximity if we're ignoring it.
                mWaitingForNegativeProximity = false;
            }
        }
    }

    /**
     * Clean up all resources that are accessed via the {@link #mHandler} thread.
     */
    public void cleanup() {
        setProximitySensorEnabled(false);
    }

    /**
     * Returns true if the proximity sensor screen-off function is available.
     */
    public boolean isProximitySensorAvailable() {
        return mProximitySensor != null;
    }

    /**
     * Sets the flag to indicate that the system is waiting for the negative proximity event
     */
    public boolean setPendingWaitForNegativeProximityLocked(
            boolean requestWaitForNegativeProximity) {
        synchronized (mLock) {
            if (requestWaitForNegativeProximity
                    && !mPendingWaitForNegativeProximityLocked) {
                mPendingWaitForNegativeProximityLocked = true;
                return true;
            }
            return false;
        }
    }

    /**
     * Updates the proximity state of the display, based on the newly received DisplayPowerRequest
     * and the target display state
     */
    public void updateProximityState(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int displayState) {
        mSkipRampBecauseOfProximityChangeToNegative = false;
        if (mProximitySensor != null) {
            if (displayPowerRequest.useProximitySensor && displayState != Display.STATE_OFF) {
                // At this point the policy says that the screen should be on, but we've been
                // asked to listen to the prox sensor to adjust the display state, so lets make
                // sure the sensor is on.
                setProximitySensorEnabled(true);
                if (!mScreenOffBecauseOfProximity
                        && mProximity == PROXIMITY_POSITIVE
                        && !mIgnoreProximityUntilChanged) {
                    // Prox sensor already reporting "near" so we should turn off the screen.
                    // Also checked that we aren't currently set to ignore the proximity sensor
                    // temporarily.
                    mScreenOffBecauseOfProximity = true;
                    sendOnProximityPositiveWithWakelock();
                }
            } else if (mWaitingForNegativeProximity
                    && mScreenOffBecauseOfProximity
                    && mProximity == PROXIMITY_POSITIVE
                    && displayState != Display.STATE_OFF) {
                // The policy says that we should have the screen on, but it's off due to the prox
                // and we've been asked to wait until the screen is far from the user to turn it
                // back on. Let keep the prox sensor on so we can tell when it's far again.
                setProximitySensorEnabled(true);
            } else {
                // We haven't been asked to use the prox sensor and we're not waiting on the screen
                // to turn back on...so let's shut down the prox sensor.
                setProximitySensorEnabled(false);
                mWaitingForNegativeProximity = false;
            }
            if (mScreenOffBecauseOfProximity
                    && (mProximity != PROXIMITY_POSITIVE || mIgnoreProximityUntilChanged)) {
                // The screen *was* off due to prox being near, but now it's "far" so lets turn
                // the screen back on.  Also turn it back on if we've been asked to ignore the
                // prox sensor temporarily.
                mScreenOffBecauseOfProximity = false;
                mSkipRampBecauseOfProximityChangeToNegative = true;
                sendOnProximityNegativeWithWakelock();
            }
        } else {
            setProximitySensorEnabled(false);
            mWaitingForNegativeProximity = false;
            mIgnoreProximityUntilChanged = false;

            if (mScreenOffBecauseOfProximity) {
                // The screen *was* off due to prox being near, but now there's no prox sensor, so
                // let's turn the screen back on.
                mScreenOffBecauseOfProximity = false;
                mSkipRampBecauseOfProximityChangeToNegative = true;
                sendOnProximityNegativeWithWakelock();
            }
        }
    }

    /**
     * A utility to check if the brightness change ramp is to be skipped because the proximity was
     * changed from positive to negative.
     */
    public boolean shouldSkipRampBecauseOfProximityChangeToNegative() {
        return mSkipRampBecauseOfProximityChangeToNegative;
    }

    /**
     * Represents of the screen is currently turned off because of the proximity state.
     */
    public boolean isScreenOffBecauseOfProximity() {
        return mScreenOffBecauseOfProximity;
    }

    /**
     * Ignores the proximity sensor until the sensor state changes, but only if the sensor is
     * currently enabled and forcing the screen to be dark.
     */
    public void ignoreProximitySensorUntilChanged() {
        mHandler.sendEmptyMessage(MSG_IGNORE_PROXIMITY);
    }

    /**
     * This adjusts the state of this class when a change in the DisplayDevice is detected.
     */
    public void notifyDisplayDeviceChanged(DisplayDeviceConfig displayDeviceConfig) {
        this.mDisplayDeviceConfig = displayDeviceConfig;
        loadProximitySensor();
    }

    /**
     * Used to dump the state.
     *
     * @param pw The PrintWriter used to dump the state.
     */
    public void dumpLocal(PrintWriter pw) {
        pw.println();
        pw.println("DisplayPowerProximityStateController:");
        synchronized (mLock) {
            pw.println("  mPendingWaitForNegativeProximityLocked="
                    + mPendingWaitForNegativeProximityLocked);
        }
        pw.println("  mDisplayId=" + mDisplayId);
        pw.println("  mWaitingForNegativeProximity=" + mWaitingForNegativeProximity);
        pw.println("  mIgnoreProximityUntilChanged=" + mIgnoreProximityUntilChanged);
        pw.println("  mProximitySensor=" + mProximitySensor);
        pw.println("  mProximitySensorEnabled=" + mProximitySensorEnabled);
        pw.println("  mProximityThreshold=" + mProximityThreshold);
        pw.println("  mProximity=" + proximityToString(mProximity));
        pw.println("  mPendingProximity=" + proximityToString(mPendingProximity));
        pw.println("  mPendingProximityDebounceTime="
                + TimeUtils.formatUptime(mPendingProximityDebounceTime));
        pw.println("  mScreenOffBecauseOfProximity=" + mScreenOffBecauseOfProximity);
        pw.println("  mSkipRampBecauseOfProximityChangeToNegative="
                + mSkipRampBecauseOfProximityChangeToNegative);
    }

    void ignoreProximitySensorUntilChangedInternal() {
        if (!mIgnoreProximityUntilChanged
                && mProximity == PROXIMITY_POSITIVE) {
            // Only ignore if it is still reporting positive (near)
            mIgnoreProximityUntilChanged = true;
            Slog.i(mTag, "Ignoring proximity");
            mNudgeUpdatePowerState.run();
        }
    }

    private void sendOnProximityPositiveWithWakelock() {
        mWakelockController.acquireWakelock(WakelockController.WAKE_LOCK_PROXIMITY_POSITIVE);
        mHandler.post(mWakelockController.getOnProximityPositiveRunnable());
    }

    private void sendOnProximityNegativeWithWakelock() {
        mWakelockController.acquireWakelock(WakelockController.WAKE_LOCK_PROXIMITY_NEGATIVE);
        mHandler.post(mWakelockController.getOnProximityNegativeRunnable());
    }

    private void loadProximitySensor() {
        if (DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT || mDisplayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        final DisplayDeviceConfig.SensorData proxSensor =
                mDisplayDeviceConfig.getProximitySensor();
        mProximitySensor = SensorUtils.findSensor(mSensorManager, proxSensor.type, proxSensor.name,
                Sensor.TYPE_PROXIMITY);
        if (mProximitySensor != null) {
            mProximityThreshold = Math.min(mProximitySensor.getMaximumRange(),
                    TYPICAL_PROXIMITY_THRESHOLD);
        }
    }

    private void setProximitySensorEnabled(boolean enable) {
        if (enable) {
            if (!mProximitySensorEnabled) {
                // Register the listener.
                // Proximity sensor state already cleared initially.
                mProximitySensorEnabled = true;
                mIgnoreProximityUntilChanged = false;
                mSensorManager.registerListener(mProximitySensorListener, mProximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            }
        } else {
            if (mProximitySensorEnabled) {
                // Unregister the listener.
                // Clear the proximity sensor state for next time.
                mProximitySensorEnabled = false;
                mProximity = PROXIMITY_UNKNOWN;
                mIgnoreProximityUntilChanged = false;
                mPendingProximity = PROXIMITY_UNKNOWN;
                mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                mSensorManager.unregisterListener(mProximitySensorListener);
                // release wake lock(must be last)
                boolean proxDebounceSuspendBlockerReleased =
                        mWakelockController.releaseWakelock(
                                WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE);
                if (proxDebounceSuspendBlockerReleased) {
                    mPendingProximityDebounceTime = -1;
                }
            }
        }
    }

    private void handleProximitySensorEvent(long time, boolean positive) {
        if (mProximitySensorEnabled) {
            if (mPendingProximity == PROXIMITY_NEGATIVE && !positive) {
                return; // no change
            }
            if (mPendingProximity == PROXIMITY_POSITIVE && positive) {
                return; // no change
            }

            // Only accept a proximity sensor reading if it remains
            // stable for the entire debounce delay.  We hold a wake lock while
            // debouncing the sensor.
            mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
            if (positive) {
                mPendingProximity = PROXIMITY_POSITIVE;
                mPendingProximityDebounceTime = time + PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY;
                mWakelockController.acquireWakelock(
                        WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE); // acquire wake lock
            } else {
                mPendingProximity = PROXIMITY_NEGATIVE;
                mPendingProximityDebounceTime = time + PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY;
                mWakelockController.acquireWakelock(
                        WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE); // acquire wake lock
            }

            // Debounce the new sensor reading.
            debounceProximitySensor();
        }
    }

    private void debounceProximitySensor() {
        if (mProximitySensorEnabled
                && mPendingProximity != PROXIMITY_UNKNOWN
                && mPendingProximityDebounceTime >= 0) {
            final long now = mClock.uptimeMillis();
            if (mPendingProximityDebounceTime <= now) {
                if (mProximity != mPendingProximity) {
                    // if the status of the sensor changed, stop ignoring.
                    mIgnoreProximityUntilChanged = false;
                    Slog.i(mTag, "No longer ignoring proximity [" + mPendingProximity + "]");
                }
                // Sensor reading accepted.  Apply the change then release the wake lock.
                mProximity = mPendingProximity;
                mNudgeUpdatePowerState.run();
                // (must be last)
                boolean proxDebounceSuspendBlockerReleased =
                        mWakelockController.releaseWakelock(
                                WakelockController.WAKE_LOCK_PROXIMITY_DEBOUNCE);
                if (proxDebounceSuspendBlockerReleased) {
                    mPendingProximityDebounceTime = -1;
                }

            } else {
                // Need to wait a little longer.
                // Debounce again later.  We continue holding a wake lock while waiting.
                Message msg = mHandler.obtainMessage(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                mHandler.sendMessageAtTime(msg, mPendingProximityDebounceTime);
            }
        }
    }

    private class DisplayPowerProximityStateHandler extends Handler {
        DisplayPowerProximityStateHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PROXIMITY_SENSOR_DEBOUNCED:
                    debounceProximitySensor();
                    break;

                case MSG_IGNORE_PROXIMITY:
                    ignoreProximitySensorUntilChangedInternal();
                    break;
            }
        }
    }

    private String proximityToString(int state) {
        switch (state) {
            case PROXIMITY_UNKNOWN:
                return "Unknown";
            case PROXIMITY_NEGATIVE:
                return "Negative";
            case PROXIMITY_POSITIVE:
                return "Positive";
            default:
                return Integer.toString(state);
        }
    }

    @VisibleForTesting
    boolean getPendingWaitForNegativeProximityLocked() {
        synchronized (mLock) {
            return mPendingWaitForNegativeProximityLocked;
        }
    }

    @VisibleForTesting
    boolean getWaitingForNegativeProximity() {
        return mWaitingForNegativeProximity;
    }

    @VisibleForTesting
    boolean shouldIgnoreProximityUntilChanged() {
        return mIgnoreProximityUntilChanged;
    }

    boolean isProximitySensorEnabled() {
        return mProximitySensorEnabled;
    }

    @VisibleForTesting
    Handler getHandler() {
        return mHandler;
    }

    @VisibleForTesting
    int getPendingProximity() {
        return mPendingProximity;
    }

    @VisibleForTesting
    int getProximity() {
        return mProximity;
    }


    @VisibleForTesting
    long getPendingProximityDebounceTime() {
        return mPendingProximityDebounceTime;
    }

    @VisibleForTesting
    SensorEventListener getProximitySensorListener() {
        return mProximitySensorListener;
    }

    /** Functional interface for providing time. */
    @VisibleForTesting
    interface Clock {
        /**
         * Returns current time in milliseconds since boot, not counting time spent in deep sleep.
         */
        long uptimeMillis();
    }

    @VisibleForTesting
    static class Injector {
        Clock createClock() {
            return () -> SystemClock.uptimeMillis();
        }
    }
}
