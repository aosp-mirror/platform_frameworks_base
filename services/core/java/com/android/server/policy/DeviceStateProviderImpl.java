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

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.input.InputManagerInternal;
import android.os.Environment;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.devicestate.DeviceStateProvider;
import com.android.server.policy.devicestate.config.Conditions;
import com.android.server.policy.devicestate.config.DeviceState;
import com.android.server.policy.devicestate.config.DeviceStateConfig;
import com.android.server.policy.devicestate.config.LidSwitchCondition;
import com.android.server.policy.devicestate.config.XmlParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.BooleanSupplier;

import javax.xml.datatype.DatatypeConfigurationException;

/**
 * Implementation of {@link DeviceStateProvider} that reads the set of supported device states
 * from a configuration file provided at either /vendor/etc/devicestate or
 * /data/system/devicestate/. By default, the provider supports {@link #DEFAULT_DEVICE_STATE} when
 * no configuration is provided.
 */
public final class DeviceStateProviderImpl implements DeviceStateProvider,
        InputManagerInternal.LidSwitchCallback {
    private static final String TAG = "DeviceStateProviderImpl";

    private static final BooleanSupplier TRUE_BOOLEAN_SUPPLIER = () -> true;

    @VisibleForTesting
    static final int DEFAULT_DEVICE_STATE = 0;

    private static final String VENDOR_CONFIG_FILE_PATH = "etc/devicestate/";
    private static final String DATA_CONFIG_FILE_PATH = "system/devicestate/";
    private static final String CONFIG_FILE_NAME = "device_state_configuration.xml";

    /** Interface that allows reading the device state configuration. */
    interface ReadableConfig {
        @NonNull
        InputStream openRead() throws IOException;
    }

    /** Returns a new {@link DeviceStateProviderImpl} instance. */
    public static DeviceStateProviderImpl create() {
        File configFile = getConfigurationFile();
        if (configFile == null) {
            return createFromConfig(null);
        }
        return createFromConfig(new ReadableFileConfig(configFile));
    }

    /**
     * Returns a new {@link DeviceStateProviderImpl} instance.
     *
     * @param readableConfig the config the provider instance should read supported states from.
     */
    @VisibleForTesting
    static DeviceStateProviderImpl createFromConfig(@Nullable ReadableConfig readableConfig) {
        SparseArray<Conditions> conditionsForState = new SparseArray<>();
        if (readableConfig != null) {
            DeviceStateConfig config = parseConfig(readableConfig);
            if (config != null) {
                for (DeviceState stateConfig : config.getDeviceState()) {
                    int state = stateConfig.getIdentifier().intValue();
                    Conditions conditions = stateConfig.getConditions();
                    conditionsForState.put(state, conditions);
                }
            }
        }

        if (conditionsForState.size() == 0) {
            conditionsForState.put(DEFAULT_DEVICE_STATE, null);
        }
        return new DeviceStateProviderImpl(conditionsForState);
    }

    // Lock for internal state.
    private final Object mLock = new Object();
    // List of supported states in ascending order.
    private final int[] mOrderedStates;
    // Map of state to a boolean supplier that returns true when all required conditions are met for
    // the device to be in the state.
    private final SparseArray<BooleanSupplier> mStateConditions;

    @Nullable
    @GuardedBy("mLock")
    private Listener mListener = null;
    @GuardedBy("mLock")
    private int mLastReportedState = INVALID_DEVICE_STATE;

    @GuardedBy("mLock")
    private boolean mIsLidOpen;

    private DeviceStateProviderImpl(SparseArray<Conditions> conditionsForState) {
        mOrderedStates = new int[conditionsForState.size()];
        for (int i = 0; i < conditionsForState.size(); i++) {
            mOrderedStates[i] = conditionsForState.keyAt(i);
        }

        // Whether or not this instance should register to receive lid switch notifications from
        // InputManagerInternal. If there are no device state conditions that are based on the lid
        // switch there is no need to register for a callback.
        boolean shouldListenToLidSwitch = false;

        mStateConditions = new SparseArray<>();
        for (int i = 0; i < mOrderedStates.length; i++) {
            int state = mOrderedStates[i];
            Conditions conditions = conditionsForState.get(state);
            if (conditions == null) {
                mStateConditions.put(state, TRUE_BOOLEAN_SUPPLIER);
                continue;
            }

            LidSwitchCondition lidSwitchCondition = conditions.getLidSwitch();
            if (lidSwitchCondition == null) {
                // We currently only support the lid switch so if it doesn't exist the condition
                // is always true.
                mStateConditions.put(state, TRUE_BOOLEAN_SUPPLIER);
                continue;
            }

            mStateConditions.put(state, new LidSwitchBooleanSupplier(lidSwitchCondition.getOpen()));
            shouldListenToLidSwitch = true;
        }

        if (shouldListenToLidSwitch) {
            InputManagerInternal inputManager = LocalServices.getService(
                    InputManagerInternal.class);
            inputManager.registerLidSwitchCallback(this);
        }
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
        int[] supportedStates;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }

            supportedStates = Arrays.copyOf(mOrderedStates, mOrderedStates.length);
        }

        mListener.onSupportedDeviceStatesChanged(supportedStates);
    }

    /** Computes the current device state and notifies the listener of a change, if needed. */
    void notifyDeviceStateChangedIfNeeded() {
        int stateToReport = INVALID_DEVICE_STATE;
        synchronized (mLock) {
            if (mListener == null) {
                return;
            }

            int newState = mOrderedStates[0];
            for (int i = 1; i < mOrderedStates.length; i++) {
                int state = mOrderedStates[i];
                if (mStateConditions.get(state).getAsBoolean()) {
                    newState = state;
                    break;
                }
            }

            if (newState != mLastReportedState) {
                mLastReportedState = newState;
                stateToReport = newState;
            }
        }

        if (stateToReport != INVALID_DEVICE_STATE) {
            mListener.onStateChanged(stateToReport);
        }
    }

    @Override
    public void notifyLidSwitchChanged(long whenNanos, boolean lidOpen) {
        synchronized (mLock) {
            mIsLidOpen = lidOpen;
        }
        notifyDeviceStateChangedIfNeeded();
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
                return mIsLidOpen == mExpectedOpen;
            }
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
}
