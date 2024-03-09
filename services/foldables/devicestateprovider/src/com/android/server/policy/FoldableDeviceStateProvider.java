/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE;
import static android.hardware.devicestate.DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL;
import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.TYPE_EXTERNAL;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.devicestate.DeviceState;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Trace;
import android.util.Dumpable;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.policy.feature.flags.FeatureFlags;
import com.android.server.policy.feature.flags.FeatureFlagsImpl;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Device state provider for foldable devices.
 * <p>
 * It is an implementation of {@link DeviceStateProvider} tailored specifically for
 * foldable devices and allows simple callback-based configuration with hall sensor
 * and hinge angle sensor values.
 */
public final class FoldableDeviceStateProvider implements DeviceStateProvider,
        SensorEventListener, PowerManager.OnThermalStatusChangedListener,
        DisplayManager.DisplayListener {

    private static final String TAG = "FoldableDeviceStateProvider";
    private static final boolean DEBUG = false;

    // Lock for internal state.
    private final Object mLock = new Object();

    // List of supported states in ascending order based on their identifier.
    private final DeviceState[] mOrderedStates;

    // Map of state identifier to a boolean supplier that returns true when all required conditions
    // are met for the device to be in the state.
    private final SparseArray<BooleanSupplier> mStateConditions = new SparseArray<>();

    // Map of state identifier to a boolean supplier that returns true when the device state has all
    // the conditions needed for availability.
    private final SparseArray<BooleanSupplier> mStateAvailabilityConditions = new SparseArray<>();

    private final DeviceStatePredicateWrapper[] mConfigurations;

    @GuardedBy("mLock")
    private final SparseBooleanArray mExternalDisplaysConnected = new SparseBooleanArray();

    private final Sensor mHingeAngleSensor;
    private final DisplayManager mDisplayManager;
    private final Sensor mHallSensor;
    private static final Predicate<FoldableDeviceStateProvider> ALLOWED = p -> true;

    @Nullable
    @GuardedBy("mLock")
    private Listener mListener = null;
    @GuardedBy("mLock")
    private int mLastReportedState = INVALID_DEVICE_STATE_IDENTIFIER;
    @GuardedBy("mLock")
    private SensorEvent mLastHingeAngleSensorEvent = null;
    @GuardedBy("mLock")
    private SensorEvent mLastHallSensorEvent = null;
    @GuardedBy("mLock")
    private @PowerManager.ThermalStatus
    int mThermalStatus = PowerManager.THERMAL_STATUS_NONE;
    @GuardedBy("mLock")
    private boolean mIsScreenOn = false;

    @GuardedBy("mLock")
    private boolean mPowerSaveModeEnabled;

    private final boolean mIsDualDisplayBlockingEnabled;

    public FoldableDeviceStateProvider(
            @NonNull Context context,
            @NonNull SensorManager sensorManager,
            @NonNull Sensor hingeAngleSensor,
            @NonNull Sensor hallSensor,
            @NonNull DisplayManager displayManager,
            @NonNull DeviceStatePredicateWrapper[] deviceStatePredicateWrappers) {
        this(new FeatureFlagsImpl(), context, sensorManager, hingeAngleSensor, hallSensor,
                displayManager, deviceStatePredicateWrappers);
    }

    @VisibleForTesting
    public FoldableDeviceStateProvider(
            @NonNull FeatureFlags featureFlags,
            @NonNull Context context,
            @NonNull SensorManager sensorManager,
            @NonNull Sensor hingeAngleSensor,
            @NonNull Sensor hallSensor,
            @NonNull DisplayManager displayManager,
            @NonNull DeviceStatePredicateWrapper[] deviceStatePredicateWrappers) {

        Preconditions.checkArgument(deviceStatePredicateWrappers.length > 0,
                "Device state configurations array must not be empty");

        mHingeAngleSensor = hingeAngleSensor;
        mHallSensor = hallSensor;
        mDisplayManager = displayManager;
        mConfigurations = deviceStatePredicateWrappers;
        mIsDualDisplayBlockingEnabled = featureFlags.enableDualDisplayBlocking();

        sensorManager.registerListener(this, mHingeAngleSensor, SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, mHallSensor, SENSOR_DELAY_FASTEST);

        mOrderedStates = new DeviceState[deviceStatePredicateWrappers.length];
        for (int i = 0; i < deviceStatePredicateWrappers.length; i++) {
            final DeviceStatePredicateWrapper configuration = deviceStatePredicateWrappers[i];
            mOrderedStates[i] = configuration.mDeviceState;

            assertUniqueDeviceStateIdentifier(configuration);
            initialiseStateConditions(configuration);
            initialiseStateAvailabilityConditions(configuration);
        }

        Handler handler = new Handler(Looper.getMainLooper());
        mDisplayManager.registerDisplayListener(
                /* listener = */ this,
                /* handler= */ handler);

        Arrays.sort(mOrderedStates, Comparator.comparingInt(DeviceState::getIdentifier));

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager != null) {
            // If any of the device states are thermal sensitive, i.e. it should be disabled when
            // the device is overheating, then we will update the list of supported states when
            // thermal status changes.
            if (hasThermalSensitiveState(deviceStatePredicateWrappers)) {
                powerManager.addThermalStatusListener(this);
            }

            // If any of the device states are power sensitive, i.e. it should be disabled when
            // power save mode is enabled, then we will update the list of supported states when
            // power save mode is toggled.
            if (hasPowerSaveSensitiveState(deviceStatePredicateWrappers)) {
                IntentFilter filter = new IntentFilter(
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED_INTERNAL);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED_INTERNAL.equals(
                                intent.getAction())) {
                            onPowerSaveModeChanged(powerManager.isPowerSaveMode());
                        }
                    }
                };
                context.registerReceiver(receiver, filter);
            }
        }
    }

    private void assertUniqueDeviceStateIdentifier(DeviceStatePredicateWrapper configuration) {
        if (mStateConditions.get(configuration.mDeviceState.getIdentifier()) != null) {
            throw new IllegalArgumentException("Device state configurations must have unique"
                    + " device state identifiers, found duplicated identifier: "
                    + configuration.mDeviceState.getIdentifier());
        }
    }

    private void initialiseStateConditions(DeviceStatePredicateWrapper configuration) {
        mStateConditions.put(configuration.mDeviceState.getIdentifier(), () ->
                configuration.mActiveStatePredicate.test(this));
    }

    private void initialiseStateAvailabilityConditions(DeviceStatePredicateWrapper configuration) {
            mStateAvailabilityConditions.put(configuration.mDeviceState.getIdentifier(), () ->
                    configuration.mAvailabilityPredicate.test(this));
    }

    @Override
    public void setListener(Listener listener) {
        synchronized (mLock) {
            if (mListener != null) {
                throw new RuntimeException("Provider already has a listener set.");
            }
            mListener = listener;
        }
        notifySupportedStatesChanged(SUPPORTED_DEVICE_STATES_CHANGED_INITIALIZED);
        notifyDeviceStateChangedIfNeeded();
    }

    /** Notifies the listener that the set of supported device states has changed. */
    private void notifySupportedStatesChanged(@SupportedStatesUpdatedReason int reason) {
        List<DeviceState> supportedStates = new ArrayList<>();
        Listener listener;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }
            listener = mListener;
            for (DeviceState deviceState : mOrderedStates) {
                if (isStateSupported(deviceState)) {
                    supportedStates.add(deviceState);
                }
            }
        }

        listener.onSupportedDeviceStatesChanged(
                supportedStates.toArray(new DeviceState[supportedStates.size()]), reason);
    }

    @GuardedBy("mLock")
    private boolean isStateSupported(DeviceState deviceState) {
        if (isThermalStatusCriticalOrAbove(mThermalStatus) && deviceState.hasProperty(
                PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL)) {
            return false;
        }
        if (mPowerSaveModeEnabled && deviceState.hasProperty(
                PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE)) {
            return false;
        }
        if (mIsDualDisplayBlockingEnabled
                && mStateAvailabilityConditions.contains(deviceState.getIdentifier())) {
            return mStateAvailabilityConditions
                    .get(deviceState.getIdentifier())
                    .getAsBoolean();
        }
        return true;
    }

    /** Computes the current device state and notifies the listener of a change, if needed. */
    void notifyDeviceStateChangedIfNeeded() {
        int stateToReport = INVALID_DEVICE_STATE_IDENTIFIER;
        Listener listener;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }

            listener = mListener;

            int newState = INVALID_DEVICE_STATE_IDENTIFIER;
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
            if (newState == INVALID_DEVICE_STATE_IDENTIFIER) {
                Slog.e(TAG, "No declared device states match any of the required conditions.");
                dumpSensorValues();
            }

            if (newState != INVALID_DEVICE_STATE_IDENTIFIER && newState != mLastReportedState) {
                mLastReportedState = newState;
                stateToReport = newState;
            }
        }

        if (stateToReport != INVALID_DEVICE_STATE_IDENTIFIER) {
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
        Slog.i(TAG, "isScreenOn: " + isScreenOn());
    }

    @GuardedBy("mLock")
    private void dumpSensorValues(Sensor sensor, @Nullable SensorEvent event) {
        Slog.i(TAG, toSensorValueString(sensor, event));
    }

    private String toSensorValueString(Sensor sensor, @Nullable SensorEvent event) {
        String sensorString = sensor == null ? "null" : sensor.getName();
        String eventValues = event == null ? "null" : Arrays.toString(event.values);
        return sensorString + " : " + eventValues;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        // TODO(b/312397262): consider virtual displays cases
        synchronized (mLock) {
            if (mIsDualDisplayBlockingEnabled
                    && !mExternalDisplaysConnected.get(displayId, false)) {
                var display = mDisplayManager.getDisplay(displayId);
                if (display == null || display.getType() != TYPE_EXTERNAL) {
                    return;
                }
                mExternalDisplaysConnected.put(displayId, true);

                // Only update the supported state when going from 0 external display to 1
                if (mExternalDisplaysConnected.size() == 1) {
                    notifySupportedStatesChanged(
                            SUPPORTED_DEVICE_STATES_CHANGED_EXTERNAL_DISPLAY_ADDED);
                }
            }
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        synchronized (mLock) {
            if (mIsDualDisplayBlockingEnabled && mExternalDisplaysConnected.get(displayId, false)) {
                mExternalDisplaysConnected.delete(displayId);

                // Only update the supported states when going from 1 external display to 0
                if (mExternalDisplaysConnected.size() == 0) {
                    notifySupportedStatesChanged(
                            SUPPORTED_DEVICE_STATES_CHANGED_EXTERNAL_DISPLAY_REMOVED);
                }
            }
        }
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId == DEFAULT_DISPLAY) {
            // Could potentially be moved to the background if needed.
            try {
                Trace.beginSection("FoldableDeviceStateProvider#onDisplayChanged()");
                int displayState = mDisplayManager.getDisplay(displayId).getState();
                synchronized (mLock) {
                    mIsScreenOn = displayState == Display.STATE_ON;
                }
            } finally {
                Trace.endSection();
            }
        }
    }

    @Override
    public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
        writer.println("FoldableDeviceStateProvider");

        synchronized (mLock) {
            writer.println("  mLastReportedState = " + mLastReportedState);
            writer.println("  mPowerSaveModeEnabled = " + mPowerSaveModeEnabled);
            writer.println("  mThermalStatus = " + mThermalStatus);
            writer.println("  mLastHingeAngleSensorEvent = " +
                    toSensorValueString(mHingeAngleSensor, mLastHingeAngleSensorEvent));
            writer.println("  mLastHallSensorEvent = " +
                    toSensorValueString(mHallSensor, mLastHallSensorEvent));
        }

        writer.println();
        writer.println("  Predicates:");

        for (int i = 0; i < mConfigurations.length; i++) {
            final DeviceStatePredicateWrapper configuration = mConfigurations[i];
            final Predicate<FoldableDeviceStateProvider> predicate =
                    configuration.mActiveStatePredicate;

            if (predicate instanceof Dumpable dumpable) {
                dumpable.dump(writer, /* args= */ null);
            }
        }
    }

    /**
     * Configuration wrapper for a single device state, contains information about the state like
     * identifier, name, flags and a predicate that should return true if the state should
     * be selected.
     */
    public static class DeviceStatePredicateWrapper {
        private final DeviceState mDeviceState;
        private final Predicate<FoldableDeviceStateProvider> mActiveStatePredicate;
        private final Predicate<FoldableDeviceStateProvider> mAvailabilityPredicate;

        private DeviceStatePredicateWrapper(
                @NonNull DeviceState deviceState,
                @NonNull Predicate<FoldableDeviceStateProvider> predicate) {
            this(deviceState, predicate, ALLOWED);
        }

        /** Create a configuration with availability and availability predicate **/
        private DeviceStatePredicateWrapper(
                @NonNull DeviceState deviceState,
                @NonNull Predicate<FoldableDeviceStateProvider> activeStatePredicate,
                @NonNull Predicate<FoldableDeviceStateProvider> availabilityPredicate) {

            mDeviceState = deviceState;
            mActiveStatePredicate = activeStatePredicate;
            mAvailabilityPredicate = availabilityPredicate;
        }

        /** Create a configuration with an active state predicate **/
        public static DeviceStatePredicateWrapper createConfig(
                @NonNull DeviceState deviceState,
                @NonNull Predicate<FoldableDeviceStateProvider> activeStatePredicate
        ) {
            return new DeviceStatePredicateWrapper(deviceState, activeStatePredicate);
        }

        /** Create a configuration with availability and active state predicate **/
        public static DeviceStatePredicateWrapper createConfig(
                @NonNull DeviceState deviceState,
                @NonNull Predicate<FoldableDeviceStateProvider> activeStatePredicate,
                @NonNull Predicate<FoldableDeviceStateProvider> availabilityPredicate
        ) {
            return new DeviceStatePredicateWrapper(deviceState, activeStatePredicate,
                    availabilityPredicate);
        }

        /**
         * Creates a device state configuration for a closed tent-mode aware state.
         * <p>
         * During tent mode:
         * - The inner display is OFF
         * - The outer display is ON
         * - The device is partially unfolded (left and right edges could be on the table)
         * In this mode the device the device so it could be used in a posture where both left
         * and right edges of the unfolded device are on the table.
         * <p>
         * The predicate returns false after the hinge angle reaches
         * {@code tentModeSwitchAngleDegrees}. Then it switches back only when the hinge angle
         * becomes less than {@code maxClosedAngleDegrees}. Hinge angle is 0 degrees when the device
         * is fully closed and 180 degrees when it is fully unfolded.
         * <p>
         * For example, when tentModeSwitchAngleDegrees = 90 and maxClosedAngleDegrees = 5 degrees:
         *  - when unfolding the device from fully closed posture (last state == closed or it is
         *    undefined yet) this state will become not matching after reaching the angle
         *    of 90 degrees, it allows the device to switch the outer display to the inner display
         *    only when reaching this threshold
         *  - when folding (last state != 'closed') this state will become matching after reaching
         *    the angle less than 5 degrees and when hall sensor detected that the device is closed,
         *    so the switch from the inner display to the outer will become only when the device
         *    is fully closed.
         *
         * @param deviceState {@link DeviceState} that corresponds to this state.
         * @param minClosedAngleDegrees minimum (inclusive) hinge angle value for the closed state
         * @param maxClosedAngleDegrees maximum (non-inclusive) hinge angle value for the closed
         *                              state
         * @param tentModeSwitchAngleDegrees the angle when this state should switch when unfolding
         * @return device state configuration
         */
        public static DeviceStatePredicateWrapper createTentModeClosedState(
                @NonNull DeviceState deviceState,
                int minClosedAngleDegrees,
                int maxClosedAngleDegrees,
                int tentModeSwitchAngleDegrees
        ) {
            return new DeviceStatePredicateWrapper(deviceState,
                    (stateContext) -> {
                        final boolean hallSensorClosed = stateContext.isHallSensorClosed();
                        final float hingeAngle = stateContext.getHingeAngle();
                        final int lastState = stateContext.getLastReportedDeviceState();
                        final boolean isScreenOn = stateContext.isScreenOn();

                        final int switchingDegrees =
                                isScreenOn ? tentModeSwitchAngleDegrees : maxClosedAngleDegrees;

                        final int closedDeviceState = deviceState.getIdentifier();
                        final boolean isLastStateClosed = lastState == closedDeviceState
                                || lastState == INVALID_DEVICE_STATE_IDENTIFIER;

                        final boolean shouldBeClosedBecauseTentMode = isLastStateClosed
                                && hingeAngle >= minClosedAngleDegrees
                                && hingeAngle < switchingDegrees;

                        final boolean shouldBeClosedBecauseFullyShut = hallSensorClosed
                                && hingeAngle >= minClosedAngleDegrees
                                && hingeAngle < maxClosedAngleDegrees;

                        return shouldBeClosedBecauseFullyShut || shouldBeClosedBecauseTentMode;
                    });
        }
    }

    /**
     * @return Whether there is an external connected display.
     */
    public boolean hasNoConnectedExternalDisplay() {
        synchronized (mLock) {
            return mExternalDisplaysConnected.size() == 0;
        }
    }

    /**
     * @return Whether the screen is on.
     */
    public boolean isScreenOn() {
        synchronized (mLock) {
            return mIsScreenOn;
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

    @VisibleForTesting
    void onPowerSaveModeChanged(boolean isPowerSaveModeEnabled) {
        synchronized (mLock) {
            if (mPowerSaveModeEnabled != isPowerSaveModeEnabled) {
                mPowerSaveModeEnabled = isPowerSaveModeEnabled;
                notifySupportedStatesChanged(
                        isPowerSaveModeEnabled ? SUPPORTED_DEVICE_STATES_CHANGED_POWER_SAVE_ENABLED
                                : SUPPORTED_DEVICE_STATES_CHANGED_POWER_SAVE_DISABLED);
            }
        }
    }

    @Override
    public void onThermalStatusChanged(@PowerManager.ThermalStatus int thermalStatus) {
        int previousThermalStatus;
        synchronized (mLock) {
            previousThermalStatus = mThermalStatus;
            mThermalStatus = thermalStatus;
        }

        boolean isThermalStatusCriticalOrAbove = isThermalStatusCriticalOrAbove(thermalStatus);
        boolean isPreviousThermalStatusCriticalOrAbove =
                isThermalStatusCriticalOrAbove(previousThermalStatus);
        if (isThermalStatusCriticalOrAbove != isPreviousThermalStatusCriticalOrAbove) {
            Slog.i(TAG, "Updating supported device states due to thermal status change."
                    + " isThermalStatusCriticalOrAbove: " + isThermalStatusCriticalOrAbove);
            notifySupportedStatesChanged(
                    isThermalStatusCriticalOrAbove
                            ? SUPPORTED_DEVICE_STATES_CHANGED_THERMAL_CRITICAL
                            : SUPPORTED_DEVICE_STATES_CHANGED_THERMAL_NORMAL);
        }
    }

    private static boolean isThermalStatusCriticalOrAbove(
            @PowerManager.ThermalStatus int thermalStatus) {
        switch (thermalStatus) {
            case PowerManager.THERMAL_STATUS_CRITICAL:
            case PowerManager.THERMAL_STATUS_EMERGENCY:
            case PowerManager.THERMAL_STATUS_SHUTDOWN:
                return true;
            default:
                return false;
        }
    }

    private static boolean hasThermalSensitiveState(DeviceStatePredicateWrapper[] deviceStates) {
        for (int i = 0; i < deviceStates.length; i++) {
            DeviceStatePredicateWrapper state = deviceStates[i];
            if (state.mDeviceState.hasProperty(
                    PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPowerSaveSensitiveState(DeviceStatePredicateWrapper[] deviceStates) {
        for (int i = 0; i < deviceStates.length; i++) {
            if (deviceStates[i].mDeviceState.hasProperty(
                    PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE)) {
                return true;
            }
        }
        return false;
    }
}
