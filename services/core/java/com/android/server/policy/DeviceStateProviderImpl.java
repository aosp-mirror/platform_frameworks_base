/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE_IDENTIFIER;

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
import android.os.Environment;
import android.os.PowerManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.input.InputManagerInternal;
import com.android.server.policy.devicestate.config.Conditions;
import com.android.server.policy.devicestate.config.DeviceStateConfig;
import com.android.server.policy.devicestate.config.LidSwitchCondition;
import com.android.server.policy.devicestate.config.NumericRange;
import com.android.server.policy.devicestate.config.Properties;
import com.android.server.policy.devicestate.config.SensorCondition;
import com.android.server.policy.devicestate.config.XmlParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Implementation of {@link DeviceStateProvider} that reads the set of supported device states
 * from a configuration file provided at either /vendor/etc/devicestate or
 * /data/system/devicestate/.
 * <p>
 * When a device state configuration file is present this provider will consider the provided
 * {@link Conditions} block for each declared state, halting and returning when the first set of
 * conditions for a device state match the current system state. If there are multiple states whose
 * conditions match the current system state the matching state with the smallest integer identifier
 * will be returned. When no declared state matches the current system state, the device state with
 * the smallest integer identifier will be returned.
 * <p>
 * By default, the provider reports {@link #DEFAULT_DEVICE_STATE} when no configuration file is
 * provided.
 */
public final class DeviceStateProviderImpl implements DeviceStateProvider,
        InputManagerInternal.LidSwitchCallback, SensorEventListener,
        PowerManager.OnThermalStatusChangedListener {
    private static final String TAG = "DeviceStateProviderImpl";
    private static final boolean DEBUG = false;

    private static final BooleanSupplier TRUE_BOOLEAN_SUPPLIER = () -> true;
    private static final BooleanSupplier FALSE_BOOLEAN_SUPPLIER = () -> false;

    @VisibleForTesting
    static final DeviceState DEFAULT_DEVICE_STATE =
            new DeviceState(new DeviceState.Configuration.Builder(MINIMUM_DEVICE_STATE_IDENTIFIER,
                    "DEFAULT").build());

    private static final String VENDOR_CONFIG_FILE_PATH = "etc/devicestate/";
    private static final String DATA_CONFIG_FILE_PATH = "system/devicestate/";
    private static final String CONFIG_FILE_NAME = "device_state_configuration.xml";
    private static final String PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED =
            "com.android.server.policy.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED";
    private static final String PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN =
            "com.android.server.policy.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN";
    private static final String PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN =
            "com.android.server.policy.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN";
    private static final String PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS =
            "com.android.server.policy.PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS";
    private static final String PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP =
            "com.android.server.policy.PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP";
    private static final String PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL =
            "com.android.server.policy.PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL";
    private static final String PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE =
            "com.android.server.policy.PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE";
    private static final String PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST =
            "com.android.server.policy.PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST";
    private static final String PROPERTY_APP_INACCESSIBLE =
            "com.android.server.policy.PROPERTY_APP_INACCESSIBLE";
    private static final String PROPERTY_EMULATED_ONLY =
            "com.android.server.policy.PROPERTY_EMULATED_ONLY";
    private static final String PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY =
            "com.android.server.policy.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY";
    private static final String PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY =
            "com.android.server.policy.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY";
    private static final String PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP =
            "com.android.server.policy.PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP";
    private static final String PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE =
            "com.android.server.policy.PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE";
    private static final String PROPERTY_EXTENDED_DEVICE_STATE_EXTERNAL_DISPLAY =
            "com.android.server.policy.PROPERTY_EXTENDED_DEVICE_STATE_EXTERNAL_DISPLAY";
    private static final String PROPERTY_FEATURE_REAR_DISPLAY =
            "com.android.server.policy.PROPERTY_FEATURE_REAR_DISPLAY";
    private static final String PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT =
            "com.android.server.policy.PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT";



    /** Interface that allows reading the device state configuration. */
    interface ReadableConfig {
        @NonNull
        InputStream openRead() throws IOException;
    }

    /**
     * Returns a new {@link DeviceStateProviderImpl} instance.
     *
     * @param context the {@link Context} that should be used to access system services.
     */
    public static DeviceStateProviderImpl create(@NonNull Context context) {
        File configFile = getConfigurationFile();
        if (configFile == null) {
            return createFromConfig(context, null);
        }
        return createFromConfig(context, new ReadableFileConfig(configFile));
    }

    /**
     * Returns a new {@link DeviceStateProviderImpl} instance.
     *
     * @param context the {@link Context} that should be used to access system services.
     * @param readableConfig the config the provider instance should read supported states from.
     */
    @VisibleForTesting
    static DeviceStateProviderImpl createFromConfig(@NonNull Context context,
            @Nullable ReadableConfig readableConfig) {
        List<DeviceState> deviceStateList = new ArrayList<>();
        List<Conditions> conditionsList = new ArrayList<>();

        if (readableConfig != null) {
            DeviceStateConfig config = parseConfig(readableConfig);
            if (config != null) {
                for (com.android.server.policy.devicestate.config.DeviceState stateConfig :
                        config.getDeviceState()) {
                    final int state = stateConfig.getIdentifier().intValue();
                    final String name = stateConfig.getName() == null ? "" : stateConfig.getName();

                    Set<@DeviceState.DeviceStateProperties Integer> systemProperties =
                            new HashSet<>();
                    Set<@DeviceState.DeviceStateProperties Integer> physicalProperties =
                            new HashSet<>();
                    final Properties configFlags = stateConfig.getProperties();
                    if (configFlags != null) {
                        List<String> configPropertyStrings = configFlags.getProperty();
                        for (int i = 0; i < configPropertyStrings.size(); i++) {
                            final String configPropertyString = configPropertyStrings.get(i);
                            addPropertyByString(configPropertyString, systemProperties,
                                    physicalProperties);
                        }
                    }
                    DeviceState.Configuration deviceStateConfiguration =
                            new DeviceState.Configuration.Builder(state, name)
                                    .setSystemProperties(systemProperties)
                                    .setPhysicalProperties(physicalProperties)
                                    .build();
                    deviceStateList.add(new DeviceState(deviceStateConfiguration));

                    final Conditions condition = stateConfig.getConditions();
                    conditionsList.add(condition);
                }
            }
        }

        if (deviceStateList.isEmpty()) {
            deviceStateList.add(DEFAULT_DEVICE_STATE);
            conditionsList.add(null);
        }
        return new DeviceStateProviderImpl(context, deviceStateList, conditionsList);
    }

    private static void addPropertyByString(String propertyString,
            Set<@DeviceState.SystemDeviceStateProperties Integer> systemProperties,
            Set<@DeviceState.PhysicalDeviceStateProperties Integer> physicalProperties) {
        switch (propertyString) {
            // Look for the physical hardware properties first
            case PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED:
                physicalProperties.add(
                        DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED);
                break;
            case PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN:
                physicalProperties.add(
                        DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN);
                break;
            case PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN:
                physicalProperties.add(
                        DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN);
                break;
            case PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS:
                systemProperties.add(
                        DeviceState.PROPERTY_POLICY_CANCEL_OVERRIDE_REQUESTS);
                break;
            case PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP:
                systemProperties.add(
                        DeviceState.PROPERTY_POLICY_CANCEL_WHEN_REQUESTER_NOT_ON_TOP);
                break;
            case PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL:
                systemProperties.add(
                        DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL);
                break;
            case PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE:
                systemProperties.add(
                        DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE);
                break;
            case PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST:
                systemProperties.add(
                        DeviceState.PROPERTY_POLICY_AVAILABLE_FOR_APP_REQUEST);
                break;
            case PROPERTY_APP_INACCESSIBLE:
                systemProperties.add(DeviceState.PROPERTY_APP_INACCESSIBLE);
                break;
            case PROPERTY_EMULATED_ONLY:
                systemProperties.add(DeviceState.PROPERTY_EMULATED_ONLY);
                break;
            case PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY:
                systemProperties.add(
                        DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY);
                break;
            case PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY:
                systemProperties.add(
                        DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY);
                break;
            case PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP:
                systemProperties.add(
                        DeviceState.PROPERTY_POWER_CONFIGURATION_TRIGGER_SLEEP);
                break;
            case PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE:
                systemProperties.add(
                        DeviceState.PROPERTY_POWER_CONFIGURATION_TRIGGER_WAKE);
                break;
            case PROPERTY_EXTENDED_DEVICE_STATE_EXTERNAL_DISPLAY:
                systemProperties.add(
                        DeviceState.PROPERTY_EXTENDED_DEVICE_STATE_EXTERNAL_DISPLAY);
                break;
            case PROPERTY_FEATURE_REAR_DISPLAY:
                systemProperties.add(DeviceState.PROPERTY_FEATURE_REAR_DISPLAY);
                break;
            case PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT:
                systemProperties.add(DeviceState.PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT);
                break;
            default:
                Slog.w(TAG, "Parsed unknown flag with name: " + propertyString);
                break;
        }
    }

    // Lock for internal state.
    private final Object mLock = new Object();
    private final Context mContext;
    // List of supported states in ascending order based on their identifier.
    private final DeviceState[] mOrderedStates;
    // Map of state identifier to a boolean supplier that returns true when all required conditions
    // are met for the device to be in the state.
    private final SparseArray<BooleanSupplier> mStateConditions = new SparseArray<>();

    @Nullable
    @GuardedBy("mLock")
    private Listener mListener = null;
    @GuardedBy("mLock")
    private int mLastReportedState = INVALID_DEVICE_STATE_IDENTIFIER;

    @GuardedBy("mLock")
    private Boolean mIsLidOpen;
    @GuardedBy("mLock")
    private final Map<Sensor, SensorEvent> mLatestSensorEvent = new ArrayMap<>();
    @GuardedBy("mLock")
    private @PowerManager.ThermalStatus int mThermalStatus = PowerManager.THERMAL_STATUS_NONE;

    @GuardedBy("mLock")
    private boolean mPowerSaveModeEnabled;

    private DeviceStateProviderImpl(@NonNull Context context,
            @NonNull List<DeviceState> deviceStates,
            @NonNull List<Conditions> stateConditions) {
        Preconditions.checkArgument(deviceStates.size() == stateConditions.size(),
                "Number of device states must be equal to the number of device state conditions.");

        mContext = context;

        DeviceState[] orderedStates = deviceStates.toArray(new DeviceState[deviceStates.size()]);
        Arrays.sort(orderedStates, Comparator.comparingInt(DeviceState::getIdentifier));
        mOrderedStates = orderedStates;

        setStateConditions(deviceStates, stateConditions);

        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager != null) {
            // If any of the device states are thermal sensitive, i.e. it should be disabled when
            // the device is overheating, then we will update the list of supported states when
            // thermal status changes.
            if (hasThermalSensitiveState(deviceStates)) {
                powerManager.addThermalStatusListener(this);
            }

            // If any of the device states are power sensitive, i.e. it should be disabled when
            // power save mode is enabled, then we will update the list of supported states when
            // power save mode is toggled.
            if (hasPowerSaveSensitiveState(deviceStates)) {
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
                mContext.registerReceiver(receiver, filter);
            }
        }
    }

    private void setStateConditions(@NonNull List<DeviceState> deviceStates,
            @NonNull List<Conditions> stateConditions) {
        // Whether or not this instance should register to receive lid switch notifications from
        // InputManagerInternal. If there are no device state conditions that are based on the lid
        // switch there is no need to register for a callback.
        boolean shouldListenToLidSwitch = false;

        // The set of Sensor(s) that this instance should register to receive SensorEvent(s) from.
        final ArraySet<Sensor> sensorsToListenTo = new ArraySet<>();

        for (int i = 0; i < stateConditions.size(); i++) {
            final int state = deviceStates.get(i).getIdentifier();
            if (DEBUG) {
                Slog.d(TAG, "Evaluating conditions for device state " + state
                        + " (" + deviceStates.get(i).getName() + ")");
            }
            final Conditions conditions = stateConditions.get(i);
            if (conditions == null) {
                // If this state has the FLAG_EMULATED_ONLY flag on it, it should never be triggered
                // by a physical hardware change, and should always return false for it's conditions
                if (deviceStates.get(i).hasProperty(DeviceState.PROPERTY_EMULATED_ONLY)) {
                    mStateConditions.put(state, FALSE_BOOLEAN_SUPPLIER);
                } else {
                    mStateConditions.put(state, TRUE_BOOLEAN_SUPPLIER);
                }
                continue;
            }

            // Whether or not all the required hardware components could be found that match the
            // requirements from the config.
            boolean allRequiredComponentsFound = true;
            // Whether or not this condition requires the lid switch.
            boolean lidSwitchRequired = false;
            // Set of sensors required for this condition.
            ArraySet<Sensor> sensorsRequired = new ArraySet<>();

            List<BooleanSupplier> suppliers = new ArrayList<>();

            LidSwitchCondition lidSwitchCondition = conditions.getLidSwitch();
            if (lidSwitchCondition != null) {
                suppliers.add(new LidSwitchBooleanSupplier(lidSwitchCondition.getOpen()));
                lidSwitchRequired = true;
                if (DEBUG) {
                    Slog.d(TAG, "Lid switch required");
                }
            }

            List<SensorCondition> sensorConditions = conditions.getSensor();
            for (int j = 0; j < sensorConditions.size(); j++) {
                SensorCondition sensorCondition = sensorConditions.get(j);
                final String expectedSensorType = sensorCondition.getType();
                final String expectedSensorName = sensorCondition.getName();

                final Sensor foundSensor = findSensor(expectedSensorType, expectedSensorName);
                if (foundSensor == null) {
                    Slog.e(TAG, "Failed to find Sensor with type: " + expectedSensorType
                            + " and name: " + expectedSensorName);
                    allRequiredComponentsFound = false;
                    break;
                }

                if (DEBUG) {
                    Slog.d(TAG, "Found sensor with type: " + expectedSensorType
                            + " (" + expectedSensorName + ")");
                }

                suppliers.add(new SensorBooleanSupplier(foundSensor, sensorCondition.getValue()));
                sensorsRequired.add(foundSensor);
            }

            if (allRequiredComponentsFound) {
                shouldListenToLidSwitch |= lidSwitchRequired;
                sensorsToListenTo.addAll(sensorsRequired);

                if (suppliers.size() > 1) {
                    mStateConditions.put(state, new AndBooleanSupplier(suppliers));
                } else if (suppliers.size() > 0) {
                    // No need to wrap with an AND supplier if there is only 1.
                    mStateConditions.put(state, suppliers.get(0));
                } else {
                    // There are no conditions for this state. Default to always true.
                    mStateConditions.put(state, TRUE_BOOLEAN_SUPPLIER);
                }
            } else {
                // Failed to setup this condition. This can happen if a sensor is missing. Default
                // this state to always false.
                mStateConditions.put(state, FALSE_BOOLEAN_SUPPLIER);
            }
        }

        if (shouldListenToLidSwitch) {
            InputManagerInternal inputManager = LocalServices.getService(
                    InputManagerInternal.class);
            inputManager.registerLidSwitchCallback(this);
        }

        final SensorManager sensorManager = mContext.getSystemService(SensorManager.class);
        for (int i = 0; i < sensorsToListenTo.size(); i++) {
            Sensor sensor = sensorsToListenTo.valueAt(i);
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    @Nullable
    private Sensor findSensor(String type, String name) {
        final SensorManager sensorManager = mContext.getSystemService(SensorManager.class);
        final List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
        for (int sensorIndex = 0; sensorIndex < sensors.size(); sensorIndex++) {
            final Sensor sensor = sensors.get(sensorIndex);
            final String sensorType = sensor.getStringType();
            final String sensorName = sensor.getName();

            if (sensorType == null || sensorName == null) {
                continue;
            }

            if (sensorType.equals(type) && sensorName.equals(name)) {
                return sensor;
            }
        }
        return null;
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
                if (isThermalStatusCriticalOrAbove(mThermalStatus) && deviceState.hasProperty(
                        DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL
                )) {
                    continue;
                }
                if (mPowerSaveModeEnabled && deviceState.hasProperty(
                        DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE)) {
                    continue;
                }
                supportedStates.add(deviceState);
            }
        }

        listener.onSupportedDeviceStatesChanged(
                supportedStates.toArray(new DeviceState[supportedStates.size()]),
                reason);
    }

    /** Computes the current device state and notifies the listener of a change, if needed. */
    void notifyDeviceStateChangedIfNeeded() {
        int stateToReport = INVALID_DEVICE_STATE_IDENTIFIER;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }

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
                    if (DEBUG) {
                        Slog.d(TAG, "Unable to check current state", e);
                    }
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
            mListener.onStateChanged(stateToReport);
        }
    }

    @Override
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        synchronized (mLock) {
            mIsLidOpen = lidOpen;
        }
        if (DEBUG) {
            Slog.d(TAG, "Lid switch state: " + (lidOpen ? "open" : "closed"));
        }
        notifyDeviceStateChangedIfNeeded();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        synchronized (mLock) {
            mLatestSensorEvent.put(event.sensor, event);
        }
        notifyDeviceStateChangedIfNeeded();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Do nothing.
    }

    @Override
    public void dump(@NonNull PrintWriter writer, @Nullable String[] args) {
        writer.println("DeviceStateProviderImpl");

        synchronized (mLock) {
            writer.println("  mLastReportedState = " + mLastReportedState);
            writer.println("  mPowerSaveModeEnabled = " + mPowerSaveModeEnabled);
            writer.println("  mThermalStatus = " + mThermalStatus);
            writer.println("  mIsLidOpen = " + mIsLidOpen);
            writer.println("  Sensor values:");

            for (Sensor sensor : mLatestSensorEvent.keySet()) {
                SensorEvent sensorEvent = mLatestSensorEvent.get(sensor);
                writer.println("   - " + toSensorValueString(sensor, sensorEvent));
            }
        }
    }

    /**
     * Implementation of {@link BooleanSupplier} that returns {@code true} if the expected lid
     * switch open state matches {@link #mIsLidOpen}.
     */
    private final class LidSwitchBooleanSupplier implements BooleanSupplier {
        private final boolean mExpectedOpen;

        LidSwitchBooleanSupplier(boolean expectedOpen) {
            mExpectedOpen = expectedOpen;
        }

        @Override
        public boolean getAsBoolean() {
            synchronized (mLock) {
                if (mIsLidOpen == null) {
                    throw new IllegalStateException("Have not received lid switch value.");
                }

                return mIsLidOpen == mExpectedOpen;
            }
        }
    }

    /**
     * Implementation of {@link BooleanSupplier} that returns {@code true} if the latest
     * {@link SensorEvent#values sensor event values} for the specified {@link Sensor} adhere to
     * the supplied {@link NumericRange ranges}.
     */
    private final class SensorBooleanSupplier implements BooleanSupplier {
        @NonNull
        private final Sensor mSensor;
        @NonNull
        private final List<NumericRange> mExpectedValues;

        SensorBooleanSupplier(@NonNull Sensor sensor, @NonNull List<NumericRange> expectedValues) {
            mSensor = sensor;
            mExpectedValues = expectedValues;
        }

        @Override
        public boolean getAsBoolean() {
            synchronized (mLock) {
                SensorEvent latestEvent = mLatestSensorEvent.get(mSensor);
                if (latestEvent == null) {
                    throw new IllegalStateException("Have not received sensor event.");
                }

                if (latestEvent.values.length < mExpectedValues.size()) {
                    throw new RuntimeException("Number of supplied numeric range(s) does not "
                            + "match the number of values in the latest sensor event for sensor: "
                            + mSensor);
                }

                for (int i = 0; i < mExpectedValues.size(); i++) {
                    if (!adheresToRange(latestEvent.values[i], mExpectedValues.get(i))) {
                        return false;
                    }
                }
                return true;
            }
        }

        /**
         * Returns {@code true} if the supplied {@code value} adheres to the constraints specified
         * in {@code range}.
         */
        private boolean adheresToRange(float value, @NonNull NumericRange range) {
            final BigDecimal min = range.getMin_optional();
            if (min != null) {
                if (DEBUG) {
                    Slog.d(TAG, "value: " + value + ", constraint min: " + min.floatValue());
                }
                if (value <= min.floatValue()) {
                    return false;
                }
            }

            final BigDecimal minInclusive = range.getMinInclusive_optional();
            if (minInclusive != null) {
                if (DEBUG) {
                    Slog.d(TAG, "value: " + value + ", constraint min-inclusive: "
                            + minInclusive.floatValue());
                }
                if (value < minInclusive.floatValue()) {
                    return false;
                }
            }

            final BigDecimal max = range.getMax_optional();
            if (max != null) {
                if (DEBUG) {
                    Slog.d(TAG, "value: " + value + ", constraint max: " + max.floatValue());
                }
                if (value >= max.floatValue()) {
                    return false;
                }
            }

            final BigDecimal maxInclusive = range.getMaxInclusive_optional();
            if (maxInclusive != null) {
                if (DEBUG) {
                    Slog.d(TAG, "value: " + value + ", constraint max-inclusive: "
                            + maxInclusive.floatValue());
                }
                if (value > maxInclusive.floatValue()) {
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * Implementation of {@link BooleanSupplier} whose result is the product of an AND operation
     * applied to the result of all child suppliers.
     */
    private static final class AndBooleanSupplier implements BooleanSupplier {
        @NonNull
        List<BooleanSupplier> mBooleanSuppliers;

        AndBooleanSupplier(@NonNull List<BooleanSupplier> booleanSuppliers) {
            mBooleanSuppliers = booleanSuppliers;
        }

        @Override
        public boolean getAsBoolean() {
            for (int i = 0; i < mBooleanSuppliers.size(); i++) {
                if (!mBooleanSuppliers.get(i).getAsBoolean()) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Returns the device state configuration file that should be used, or {@code null} if no file
     * is present on the device.
     * <p>
     * Defaults to returning a config file present in the data/ dir at
     * {@link #DATA_CONFIG_FILE_PATH}, and then falls back to the config file in the vendor/ dir
     * at {@link #VENDOR_CONFIG_FILE_PATH} if no config file is found in the data/ dir.
     */
    @Nullable
    private static File getConfigurationFile() {
        final File configFileFromDataDir = Environment.buildPath(Environment.getDataDirectory(),
                DATA_CONFIG_FILE_PATH, CONFIG_FILE_NAME);
        if (configFileFromDataDir.exists()) {
            return configFileFromDataDir;
        }

        final File configFileFromVendorDir = Environment.buildPath(Environment.getVendorDirectory(),
                VENDOR_CONFIG_FILE_PATH, CONFIG_FILE_NAME);
        if (configFileFromVendorDir.exists()) {
            return configFileFromVendorDir;
        }

        return null;
    }

    @GuardedBy("mLock")
    private void dumpSensorValues() {
        Slog.i(TAG, "Sensor values:");
        for (Sensor sensor : mLatestSensorEvent.keySet()) {
            SensorEvent sensorEvent = mLatestSensorEvent.get(sensor);
            Slog.i(TAG, toSensorValueString(sensor, sensorEvent));
        }
    }

    private String toSensorValueString(Sensor sensor, @Nullable SensorEvent event) {
        String sensorString = sensor == null ? "null" : sensor.getName();
        String eventValues = event == null ? "null" : Arrays.toString(event.values);
        return sensorString + " : " + eventValues;
    }

    /**
     * Tries to parse the provided file into a {@link DeviceStateConfig} object. Returns
     * {@code null} if the file could not be successfully parsed.
     */
    @Nullable
    private static DeviceStateConfig parseConfig(@NonNull ReadableConfig readableConfig) {
        try (InputStream in = readableConfig.openRead();
                InputStream bin = new BufferedInputStream(in)) {
            return XmlParser.read(bin);
        } catch (IOException | DatatypeConfigurationException | XmlPullParserException e) {
            Slog.e(TAG, "Encountered an error while reading device state config", e);
        }
        return null;
    }

    /** Implementation of {@link ReadableConfig} that reads config data from a file. */
    private static final class ReadableFileConfig implements ReadableConfig {
        @NonNull
        private final File mFile;

        private ReadableFileConfig(@NonNull File file) {
            mFile = file;
        }

        @Override
        public InputStream openRead() throws IOException {
            return new FileInputStream(mFile);
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

    private static boolean hasThermalSensitiveState(List<DeviceState> deviceStates) {
        for (int i = 0; i < deviceStates.size(); i++) {
            if (deviceStates.get(i).hasProperty(
                    DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_THERMAL_STATUS_CRITICAL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPowerSaveSensitiveState(List<DeviceState> deviceStates) {
        for (int i = 0; i < deviceStates.size(); i++) {
            if (deviceStates.get(i).hasProperty(
                    DeviceState.PROPERTY_POLICY_UNSUPPORTED_WHEN_POWER_SAVE_MODE)) {
                return true;
            }
        }
        return false;
    }
}
