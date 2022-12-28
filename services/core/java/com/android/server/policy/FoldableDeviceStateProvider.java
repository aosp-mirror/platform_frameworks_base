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

package com.android.server.policy;

import static android.hardware.SensorManager.SENSOR_DELAY_FASTEST;
import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MAXIMUM_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.devicestate.DeviceState;
import com.android.server.devicestate.DeviceStateProvider;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Device state provider for foldable devices.
 *
 * It is an implementation of {@link DeviceStateProvider} tailored specifically for
 * foldable devices and allows simple callback-based configuration with hall sensor
 * and hinge angle sensor values.
 */
public final class FoldableDeviceStateProvider implements DeviceStateProvider,
        SensorEventListener {

    private static final String TAG = "FoldableDeviceStateProvider";
    private static final boolean DEBUG = false;

    // Lock for internal state.
    private final Object mLock = new Object();

    // List of supported states in ascending order based on their identifier.
    private final DeviceState[] mOrderedStates;

    // Map of state identifier to a boolean supplier that returns true when all required conditions
    // are met for the device to be in the state.
    private final SparseArray<BooleanSupplier> mStateConditions = new SparseArray<>();

    private final Sensor mHingeAngleSensor;
    private final Sensor mHallSensor;

    @Nullable
    @GuardedBy("mLock")
    private Listener mListener = null;
    @GuardedBy("mLock")
    private int mLastReportedState = INVALID_DEVICE_STATE;
    @GuardedBy("mLock")
    private SensorEvent mLastHingeAngleSensorEvent = null;
    @GuardedBy("mLock")
    private SensorEvent mLastHallSensorEvent = null;

    public FoldableDeviceStateProvider(@NonNull SensorManager sensorManager,
            @NonNull Sensor hingeAngleSensor,
            @NonNull Sensor hallSensor,
            @NonNull DeviceStateConfiguration[] deviceStateConfigurations) {

        Preconditions.checkArgument(deviceStateConfigurations.length > 0,
                "Device state configurations array must not be empty");

        mHingeAngleSensor = hingeAngleSensor;
        mHallSensor = hallSensor;

        sensorManager.registerListener(this, mHingeAngleSensor, SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, mHallSensor, SENSOR_DELAY_FASTEST);

        mOrderedStates = new DeviceState[deviceStateConfigurations.length];
        for (int i = 0; i < deviceStateConfigurations.length; i++) {
            final DeviceStateConfiguration configuration = deviceStateConfigurations[i];
            mOrderedStates[i] = configuration.mDeviceState;

            if (mStateConditions.get(configuration.mDeviceState.getIdentifier()) != null) {
                throw new IllegalArgumentException("Device state configurations must have unique"
                        + " device state identifiers, found duplicated identifier: " +
                        configuration.mDeviceState.getIdentifier());
            }

            mStateConditions.put(configuration.mDeviceState.getIdentifier(), () ->
                    configuration.mPredicate.apply(this));
        }

        Arrays.sort(mOrderedStates, Comparator.comparingInt(DeviceState::getIdentifier));
    }

    @Override
    public void setListener(Listener listener) {
        synchronized (mLock) {
            if (mListener != null) {
                throw new RuntimeException("Provider already has a listener set.");
            }
            mListener = listener;
        }
        notifySupportedStatesChanged();
        notifyDeviceStateChangedIfNeeded();
    }

    /** Notifies the listener that the set of supported device states has changed. */
    private void notifySupportedStatesChanged() {
        DeviceState[] supportedStates;
        Listener listener;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }

            listener = mListener;
            supportedStates = Arrays.copyOf(mOrderedStates, mOrderedStates.length);
        }

        listener.onSupportedDeviceStatesChanged(supportedStates);
    }

    /** Computes the current device state and notifies the listener of a change, if needed. */
    void notifyDeviceStateChangedIfNeeded() {
        int stateToReport = INVALID_DEVICE_STATE;
        Listener listener;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }

            listener = mListener;

            int newState = INVALID_DEVICE_STATE;
            for (int i = 0; i < mOrderedStates.length; i++) {
                int state = mOrderedStates[i].getIdentifier();
                if (DEBUG) {
                    Slog.d(TAG, "Checking conditions for " + mOrderedStates[i].getName() + "("
                            + i + ")");
                }
                boolean conditionSatisfied;
                try {
                    conditionSatisfied = mStateConditions.get(state).getAsBoolean();
                } catch (IllegalStateException e) {
                    // Failed to compute the current state based on current available data. Continue
                    // with the expectation that notifyDeviceStateChangedIfNeeded() will be called
                    // when a callback with the missing data is triggered. May trigger another state
                    // change if another state is satisfied currently.
                    Slog.w(TAG, "Unable to check current state = " + state, e);
                    dumpSensorValues();
                    continue;
                }

                if (conditionSatisfied) {
                    if (DEBUG) {
                        Slog.d(TAG, "Device State conditions satisfied, transition to " + state);
                    }
                    newState = state;
                    break;
                }
            }
            if (newState == INVALID_DEVICE_STATE) {
                Slog.e(TAG, "No declared device states match any of the required conditions.");
                dumpSensorValues();
            }

            if (newState != INVALID_DEVICE_STATE && newState != mLastReportedState) {
                mLastReportedState = newState;
                stateToReport = newState;
            }
        }

        if (stateToReport != INVALID_DEVICE_STATE) {
            listener.onStateChanged(stateToReport);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        synchronized (mLock) {
            if (event.sensor == mHallSensor) {
                mLastHallSensorEvent = event;
            } else if (event.sensor == mHingeAngleSensor) {
                mLastHingeAngleSensorEvent = event;
            }
        }
        notifyDeviceStateChangedIfNeeded();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do nothing.
    }

    private float getSensorValue(@Nullable SensorEvent sensorEvent) {
        if (sensorEvent == null) {
            throw new IllegalStateException("Have not received sensor event.");
        }

        if (sensorEvent.values.length < 1) {
            throw new IllegalStateException("Values in the sensor event are empty");
        }

        return sensorEvent.values[0];
    }

    @GuardedBy("mLock")
    private void dumpSensorValues() {
        Slog.i(TAG, "Sensor values:");
        dumpSensorValues(mHallSensor, mLastHallSensorEvent);
        dumpSensorValues(mHingeAngleSensor, mLastHingeAngleSensorEvent);
    }

    @GuardedBy("mLock")
    private void dumpSensorValues(@NonNull Sensor sensor, @Nullable SensorEvent event) {
        if (event != null) {
            Slog.i(TAG, sensor.getName() + ": " + Arrays.toString(event.values));
        } else {
            Slog.i(TAG, sensor.getName() + ": null");
        }
    }

    /**
     * Configuration for a single device state, contains information about the state like
     * identifier, name, flags and a predicate that should return true if the state should
     * be selected.
     */
    public static class DeviceStateConfiguration {
        private final DeviceState mDeviceState;
        private final Function<FoldableDeviceStateProvider, Boolean> mPredicate;

        private DeviceStateConfiguration(DeviceState deviceState,
                Function<FoldableDeviceStateProvider, Boolean> predicate) {
            mDeviceState = deviceState;
            mPredicate = predicate;
        }

        public static DeviceStateConfiguration createConfig(
                @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE) int identifier,
                @NonNull String name,
                @DeviceState.DeviceStateFlags int flags,
                Function<FoldableDeviceStateProvider, Boolean> predicate
        ) {
            return new DeviceStateConfiguration(new DeviceState(identifier, name, flags),
                    predicate);
        }

        public static DeviceStateConfiguration createConfig(
                @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE) int identifier,
                @NonNull String name,
                Function<FoldableDeviceStateProvider, Boolean> predicate
        ) {
            return new DeviceStateConfiguration(new DeviceState(identifier, name, /* flags= */ 0),
                    predicate);
        }

        /**
         * Creates a device state configuration for a closed tent-mode aware state.
         * This is useful to create a behavior when the device could be used in a tent mode: a mode
         * on a foldable device where we keep the outer display on after partially unfolding
         * the device so it could be used in a posture where both left and right edges of
         * the unfolded device are on the table.
         *
         * The predicate returns false when the hinge angle reaches
         * {@code tentModeSwitchAngleDegrees}. Then it switches back only when the hinge angle
         * becomes less than {@code maxClosedAngleDegrees}. Hinge angle is 0 degrees when the device
         * is fully closed and 180 degrees when it is fully unfolded.
         *
         * For example, when tentModeSwitchAngleDegrees = 90 and maxClosedAngleDegrees = 5 degrees:
         *  - when unfolding the device from fully closed posture (last state == closed or it is
         *    undefined yet) this state will become not matching when reaching the angle
         *    of 90 degrees, it allows the device to switch the outer display to the inner display
         *    only when reaching this threshold
         *  - when folding (last state != 'closed') this state will become matching when reaching
         *    the angle less than 5 degrees and when hall sensor detected that the device is closed,
         *    so the switch from the inner display to the outer will become only when the device
         *    is fully closed.
         *
         * @param identifier state identifier
         * @param name state name
         * @param flags state flags
         * @param minClosedAngleDegrees minimum (inclusive) hinge angle value for the closed state
         * @param maxClosedAngleDegrees maximum (non-inclusive) hinge angle value for the closed
         *                              state
         * @param tentModeSwitchAngleDegrees the angle when this state should switch when unfolding
         * @return device state configuration
         */
        public static DeviceStateConfiguration createTentModeClosedState(
                @IntRange(from = MINIMUM_DEVICE_STATE, to = MAXIMUM_DEVICE_STATE) int identifier,
                @NonNull String name,
                @DeviceState.DeviceStateFlags int flags,
                int minClosedAngleDegrees,
                int maxClosedAngleDegrees,
                int tentModeSwitchAngleDegrees
        ) {
            return new DeviceStateConfiguration(new DeviceState(identifier, name, flags),
                    (stateContext) -> {
                        final boolean hallSensorClosed = stateContext.isHallSensorClosed();
                        final float hingeAngle = stateContext.getHingeAngle();
                        final int lastState = stateContext.getLastReportedDeviceState();

                        final int closedDeviceState = identifier;
                        final boolean isLastStateClosed = lastState == closedDeviceState
                                || lastState == INVALID_DEVICE_STATE;

                        final boolean shouldBeClosedBecauseTentMode = isLastStateClosed
                                && hingeAngle >= minClosedAngleDegrees
                                && hingeAngle < tentModeSwitchAngleDegrees;

                        final boolean shouldBeClosedBecauseFullyShut = hallSensorClosed
                                && hingeAngle >= minClosedAngleDegrees
                                && hingeAngle < maxClosedAngleDegrees;

                        return shouldBeClosedBecauseFullyShut || shouldBeClosedBecauseTentMode;
                    });
        }
    }

    /**
     * @return current hinge angle value of a foldable device
     */
    public float getHingeAngle() {
        synchronized (mLock) {
            return getSensorValue(mLastHingeAngleSensorEvent);
        }
    }

    /**
     * @return true if hall sensor detected that the device is closed (fully shut)
     */
    public boolean isHallSensorClosed() {
        synchronized (mLock) {
            return getSensorValue(mLastHallSensorEvent) > 0f;
        }
    }

    /**
     * @return last reported device state
     */
    public int getLastReportedDeviceState() {
        synchronized (mLock) {
            return mLastReportedState;
        }
    }
}
