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

package androidx.window.common;

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE;

import static androidx.window.common.CommonFoldingFeature.COMMON_STATE_UNKNOWN;
import static androidx.window.common.CommonFoldingFeature.parseListFromString;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.window.util.AcceptOnceConsumer;
import androidx.window.util.BaseDataProducer;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * An implementation of {@link androidx.window.util.BaseDataProducer} that returns
 * the device's posture by mapping the state returned from {@link DeviceStateManager} to
 * values provided in the resources' config at {@link R.array#config_device_state_postures}.
 */
public final class DeviceStateManagerFoldingFeatureProducer
        extends BaseDataProducer<List<CommonFoldingFeature>> {
    private static final String TAG =
            DeviceStateManagerFoldingFeatureProducer.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final SparseIntArray mDeviceStateToPostureMap = new SparseIntArray();

    private int mCurrentDeviceState = INVALID_DEVICE_STATE;

    @NonNull
    private final BaseDataProducer<String> mRawFoldSupplier;

    public DeviceStateManagerFoldingFeatureProducer(@NonNull Context context,
            @NonNull BaseDataProducer<String> rawFoldSupplier) {
        mRawFoldSupplier = rawFoldSupplier;
        String[] deviceStatePosturePairs = context.getResources()
                .getStringArray(R.array.config_device_state_postures);
        for (String deviceStatePosturePair : deviceStatePosturePairs) {
            String[] deviceStatePostureMapping = deviceStatePosturePair.split(":");
            if (deviceStatePostureMapping.length != 2) {
                if (DEBUG) {
                    Log.e(TAG, "Malformed device state posture pair: "
                            + deviceStatePosturePair);
                }
                continue;
            }

            int deviceState;
            int posture;
            try {
                deviceState = Integer.parseInt(deviceStatePostureMapping[0]);
                posture = Integer.parseInt(deviceStatePostureMapping[1]);
            } catch (NumberFormatException e) {
                if (DEBUG) {
                    Log.e(TAG, "Failed to parse device state or posture: "
                                    + deviceStatePosturePair,
                            e);
                }
                continue;
            }

            mDeviceStateToPostureMap.put(deviceState, posture);
        }

        if (mDeviceStateToPostureMap.size() > 0) {
            DeviceStateCallback deviceStateCallback = (state) -> {
                mCurrentDeviceState = state;
                mRawFoldSupplier.getData(this::notifyFoldingFeatureChange);
            };
            Objects.requireNonNull(context.getSystemService(DeviceStateManager.class))
                    .registerCallback(context.getMainExecutor(), deviceStateCallback);
        }
    }

    /**
     * Add a callback to mCallbacks if there is no device state. This callback will be run
     * once a device state is set. Otherwise,run the callback immediately.
     */
    private void runCallbackWhenValidState(@NonNull Consumer<List<CommonFoldingFeature>> callback,
            String displayFeaturesString) {
        if (isCurrentStateValid()) {
            callback.accept(calculateFoldingFeature(displayFeaturesString));
        } else {
            // This callback will be added to mCallbacks and removed once it runs once.
            AcceptOnceConsumer<List<CommonFoldingFeature>> singleRunCallback =
                    new AcceptOnceConsumer<>(this, callback);
            addDataChangedCallback(singleRunCallback);
        }
    }

    /**
     * Checks to find {@link DeviceStateManagerFoldingFeatureProducer#mCurrentDeviceState} in the
     * {@link DeviceStateManagerFoldingFeatureProducer#mDeviceStateToPostureMap} which was
     * initialized in the constructor of {@link DeviceStateManagerFoldingFeatureProducer}.
     * Returns a boolean value of whether the device state is valid.
     */
    private boolean isCurrentStateValid() {
        // If the device state is not found in the map, indexOfKey returns a negative number.
        return mDeviceStateToPostureMap.indexOfKey(mCurrentDeviceState) >= 0;
    }

    @Override
    protected void onListenersChanged(
            @NonNull Set<Consumer<List<CommonFoldingFeature>>> callbacks) {
        super.onListenersChanged(callbacks);
        if (callbacks.isEmpty()) {
            mCurrentDeviceState = INVALID_DEVICE_STATE;
            mRawFoldSupplier.removeDataChangedCallback(this::notifyFoldingFeatureChange);
        } else {
            mRawFoldSupplier.addDataChangedCallback(this::notifyFoldingFeatureChange);
        }
    }

    @NonNull
    @Override
    public Optional<List<CommonFoldingFeature>> getCurrentData() {
        Optional<String> displayFeaturesString = mRawFoldSupplier.getCurrentData();
        if (!isCurrentStateValid()) {
            return Optional.empty();
        } else {
            return displayFeaturesString.map(this::calculateFoldingFeature);
        }
    }

    /**
     * Adds the data to the storeFeaturesConsumer when the data is ready.
     * @param storeFeaturesConsumer a consumer to collect the data when it is first available.
     */
    public void getData(Consumer<List<CommonFoldingFeature>> storeFeaturesConsumer) {
        mRawFoldSupplier.getData((String displayFeaturesString) -> {
            if (TextUtils.isEmpty(displayFeaturesString)) {
                storeFeaturesConsumer.accept(new ArrayList<>());
            } else {
                runCallbackWhenValidState(storeFeaturesConsumer, displayFeaturesString);
            }
        });
    }

    private void notifyFoldingFeatureChange(String displayFeaturesString) {
        if (!isCurrentStateValid()) {
            return;
        }
        if (TextUtils.isEmpty(displayFeaturesString)) {
            notifyDataChanged(new ArrayList<>());
        } else {
            notifyDataChanged(calculateFoldingFeature(displayFeaturesString));
        }
    }

    private List<CommonFoldingFeature> calculateFoldingFeature(String displayFeaturesString) {
        final int globalHingeState = globalHingeState();
        return parseListFromString(displayFeaturesString, globalHingeState);
    }

    private int globalHingeState() {
        return mDeviceStateToPostureMap.get(mCurrentDeviceState, COMMON_STATE_UNKNOWN);
    }
}
