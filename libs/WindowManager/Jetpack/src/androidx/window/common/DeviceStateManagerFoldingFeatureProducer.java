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

import static android.hardware.devicestate.DeviceStateManager.INVALID_DEVICE_STATE_IDENTIFIER;

import static androidx.window.common.CommonFoldingFeature.COMMON_STATE_UNKNOWN;
import static androidx.window.common.CommonFoldingFeature.COMMON_STATE_USE_BASE_STATE;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    /**
     * Emulated device state {@link DeviceStateManager.DeviceStateCallback#onStateChanged(int)} to
     * {@link CommonFoldingFeature.State} map.
     */
    private final SparseIntArray mDeviceStateToPostureMap = new SparseIntArray();

    /**
     * Emulated device state received via
     * {@link DeviceStateManager.DeviceStateCallback#onStateChanged(int)}.
     * "Emulated" states differ from "base" state in the sense that they may not correspond 1:1 with
     * physical device states. They represent the state of the device when various software
     * features and APIs are applied. The emulated states generally consist of all "base" states,
     * but may have additional states such as "concurrent" or "rear display". Concurrent mode for
     * example is activated via public API and can be active in both the "open" and "half folded"
     * device states.
     */
    private int mCurrentDeviceState = INVALID_DEVICE_STATE_IDENTIFIER;

    /**
     * Base device state received via
     * {@link DeviceStateManager.DeviceStateCallback#onBaseStateChanged(int)}.
     * "Base" in this context means the "physical" state of the device.
     */
    private int mCurrentBaseDeviceState = INVALID_DEVICE_STATE_IDENTIFIER;

    @NonNull
    private final RawFoldingFeatureProducer mRawFoldSupplier;

    private final boolean mIsHalfOpenedSupported;

    private final DeviceStateCallback mDeviceStateCallback = new DeviceStateCallback() {
        @Override
        public void onStateChanged(int state) {
            mCurrentDeviceState = state;
            mRawFoldSupplier.getData(DeviceStateManagerFoldingFeatureProducer
                    .this::notifyFoldingFeatureChange);
        }

        @Override
        public void onBaseStateChanged(int state) {
            mCurrentBaseDeviceState = state;

            if (mDeviceStateToPostureMap.get(mCurrentDeviceState)
                    == COMMON_STATE_USE_BASE_STATE) {
                mRawFoldSupplier.getData(DeviceStateManagerFoldingFeatureProducer
                        .this::notifyFoldingFeatureChange);
            }
        }
    };

    public DeviceStateManagerFoldingFeatureProducer(@NonNull Context context,
            @NonNull RawFoldingFeatureProducer rawFoldSupplier,
            @NonNull DeviceStateManager deviceStateManager) {
        mRawFoldSupplier = rawFoldSupplier;
        String[] deviceStatePosturePairs = context.getResources()
                .getStringArray(R.array.config_device_state_postures);
        boolean isHalfOpenedSupported = false;
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
            isHalfOpenedSupported = isHalfOpenedSupported
                    || posture == CommonFoldingFeature.COMMON_STATE_HALF_OPENED;
            mDeviceStateToPostureMap.put(deviceState, posture);
        }
        mIsHalfOpenedSupported = isHalfOpenedSupported;
        if (mDeviceStateToPostureMap.size() > 0) {
            Objects.requireNonNull(deviceStateManager)
                    .registerCallback(context.getMainExecutor(), mDeviceStateCallback);
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
    protected void onListenersChanged() {
        super.onListenersChanged();
        if (hasListeners()) {
            mRawFoldSupplier.addDataChangedCallback(this::notifyFoldingFeatureChange);
        } else {
            mCurrentDeviceState = INVALID_DEVICE_STATE_IDENTIFIER;
            mRawFoldSupplier.removeDataChangedCallback(this::notifyFoldingFeatureChange);
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
     * Returns a {@link List} of all the {@link CommonFoldingFeature} with the state set to
     * {@link CommonFoldingFeature#COMMON_STATE_UNKNOWN}. This method parses a {@link String} so a
     * caller should consider caching the value or the derived value.
     */
    @NonNull
    public List<CommonFoldingFeature> getFoldsWithUnknownState() {
        Optional<String> optionalFoldingFeatureString = mRawFoldSupplier.getCurrentData();

        if (optionalFoldingFeatureString.isPresent()) {
            return CommonFoldingFeature.parseListFromString(
                    optionalFoldingFeatureString.get(), CommonFoldingFeature.COMMON_STATE_UNKNOWN
            );
        }
        return Collections.emptyList();
    }


    /**
     * Returns {@code true} if the device supports half-opened mode, {@code false} otherwise.
     */
    public boolean isHalfOpenedSupported() {
        return mIsHalfOpenedSupported;
    }

    /**
     * Adds the data to the storeFeaturesConsumer when the data is ready.
     * @param storeFeaturesConsumer a consumer to collect the data when it is first available.
     */
    @Override
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
        return parseListFromString(displayFeaturesString, currentHingeState());
    }

    @CommonFoldingFeature.State
    private int currentHingeState() {
        @CommonFoldingFeature.State
        int posture = mDeviceStateToPostureMap.get(mCurrentDeviceState, COMMON_STATE_UNKNOWN);

        if (posture == CommonFoldingFeature.COMMON_STATE_USE_BASE_STATE) {
            posture = mDeviceStateToPostureMap.get(mCurrentBaseDeviceState, COMMON_STATE_UNKNOWN);
        }

        return posture;
    }
}
