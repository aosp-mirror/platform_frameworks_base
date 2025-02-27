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

import static androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_UNKNOWN;
import static androidx.window.common.layout.CommonFoldingFeature.parseListFromString;

import android.content.Context;
import android.hardware.devicestate.DeviceState;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback;
import android.hardware.devicestate.DeviceStateUtil;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.annotation.BinderThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.window.common.layout.CommonFoldingFeature;
import androidx.window.common.layout.DisplayFoldFeatureCommon;

import com.android.internal.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * An implementation of {@link BaseDataProducer} that returns
 * the device's posture by mapping the state returned from {@link DeviceStateManager} to
 * values provided in the resources' config at {@link R.array#config_device_state_postures}.
 */
public final class DeviceStateManagerFoldingFeatureProducer
        extends BaseDataProducer<List<CommonFoldingFeature>> {
    private static final String TAG =
            DeviceStateManagerFoldingFeatureProducer.class.getSimpleName();
    private static final boolean DEBUG = false;

    /**
     * Device state received via
     * {@link DeviceStateManager.DeviceStateCallback#onDeviceStateChanged(DeviceState)}.
     * The identifier returned through {@link DeviceState#getIdentifier()} may not correspond 1:1
     * with the physical state of the device. This could correspond to the system state of the
     * device when various software features or overrides are applied. The emulated states generally
     * consist of all "base" states, but may have additional states such as "concurrent" or
     * "rear display". Concurrent mode for example is activated via public API and can be active in
     * both the "open" and "half folded" device states.
     */
    private DeviceState mCurrentDeviceState = INVALID_DEVICE_STATE;

    @NonNull
    private final Context mContext;

    @NonNull
    private final RawFoldingFeatureProducer mRawFoldSupplier;

    @NonNull
    private final DeviceStateMapper mDeviceStateMapper;

    private final DeviceStateCallback mDeviceStateCallback = new DeviceStateCallback() {
        @BinderThread // Subsequent callback after registered.
        @MainThread // Initial callback if registration is on the main thread.
        @Override
        public void onDeviceStateChanged(@NonNull DeviceState state) {
            final boolean isMainThread = Looper.myLooper() == Looper.getMainLooper();
            final Executor executor = isMainThread ? Runnable::run : mContext.getMainExecutor();
            executor.execute(() -> {
                DeviceStateManagerFoldingFeatureProducer.this.onDeviceStateChanged(state);
            });
        }
    };

    public DeviceStateManagerFoldingFeatureProducer(@NonNull Context context,
            @NonNull RawFoldingFeatureProducer rawFoldSupplier,
            @NonNull DeviceStateManager deviceStateManager) {
        mContext = context;
        mRawFoldSupplier = rawFoldSupplier;
        mDeviceStateMapper =
                new DeviceStateMapper(context, deviceStateManager.getSupportedDeviceStates());

        if (!mDeviceStateMapper.isDeviceStateToPostureMapEmpty()) {
            Objects.requireNonNull(deviceStateManager)
                    .registerCallback(Runnable::run, mDeviceStateCallback);
        }
    }

    @MainThread
    @VisibleForTesting
    void onDeviceStateChanged(@NonNull DeviceState state) {
        mCurrentDeviceState = state;
        mRawFoldSupplier.getData(this::notifyFoldingFeatureChangeLocked);
    }

    /**
     * Add a callback to mCallbacks if there is no device state. This callback will be run
     * once a device state is set. Otherwise,run the callback immediately.
     */
    private void runCallbackWhenValidState(@NonNull DeviceState state,
            @NonNull Consumer<List<CommonFoldingFeature>> callback,
            @NonNull String displayFeaturesString) {
        if (mDeviceStateMapper.isDeviceStateValid(state)) {
            callback.accept(calculateFoldingFeature(state, displayFeaturesString));
        } else {
            // This callback will be added to mCallbacks and removed once it runs once.
            final AcceptOnceConsumer<List<CommonFoldingFeature>> singleRunCallback =
                    new AcceptOnceConsumer<>(this, callback);
            addDataChangedCallback(singleRunCallback);
        }
    }

    @Override
    protected void onListenersChanged() {
        super.onListenersChanged();
        if (hasListeners()) {
            mRawFoldSupplier.addDataChangedCallback(this::notifyFoldingFeatureChangeLocked);
        } else {
            mCurrentDeviceState = INVALID_DEVICE_STATE;
            mRawFoldSupplier.removeDataChangedCallback(this::notifyFoldingFeatureChangeLocked);
        }
    }

    @NonNull
    private DeviceState getCurrentDeviceState() {
        return mCurrentDeviceState;
    }

    @NonNull
    @Override
    public Optional<List<CommonFoldingFeature>> getCurrentData() {
        final Optional<String> displayFeaturesString = mRawFoldSupplier.getCurrentData();
        final DeviceState state = getCurrentDeviceState();
        if (!mDeviceStateMapper.isDeviceStateValid(state) || displayFeaturesString.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(calculateFoldingFeature(state, displayFeaturesString.get()));
        }
    }

    /**
     * Returns a {@link List} of all the {@link CommonFoldingFeature} with the state set to
     * {@link CommonFoldingFeature#COMMON_STATE_UNKNOWN}. This method parses a {@link String} so a
     * caller should consider caching the value or the derived value.
     */
    @NonNull
    public List<CommonFoldingFeature> getFoldsWithUnknownState() {
        final Optional<String> optionalFoldingFeatureString = mRawFoldSupplier.getCurrentData();

        if (optionalFoldingFeatureString.isPresent()) {
            return CommonFoldingFeature.parseListFromString(
                    optionalFoldingFeatureString.get(), CommonFoldingFeature.COMMON_STATE_UNKNOWN
            );
        }
        return Collections.emptyList();
    }

    /**
     * Returns the list of supported {@link DisplayFoldFeatureCommon} calculated from the
     * {@link DeviceStateManagerFoldingFeatureProducer}.
     */
    @NonNull
    public List<DisplayFoldFeatureCommon> getDisplayFeatures() {
        final List<DisplayFoldFeatureCommon> foldFeatures = new ArrayList<>();
        final List<CommonFoldingFeature> folds = getFoldsWithUnknownState();

        final boolean isHalfOpenedSupported = isHalfOpenedSupported();
        for (CommonFoldingFeature fold : folds) {
            foldFeatures.add(DisplayFoldFeatureCommon.create(fold, isHalfOpenedSupported));
        }
        return foldFeatures;
    }

    /**
     * Returns {@code true} if the device supports half-opened mode, {@code false} otherwise.
     */
    public boolean isHalfOpenedSupported() {
        return mDeviceStateMapper.mIsHalfOpenedSupported;
    }

    /**
     * Adds the data to the storeFeaturesConsumer when the data is ready.
     *
     * @param storeFeaturesConsumer a consumer to collect the data when it is first available.
     */
    @Override
    public void getData(@NonNull Consumer<List<CommonFoldingFeature>> storeFeaturesConsumer) {
        mRawFoldSupplier.getData((String displayFeaturesString) -> {
            if (TextUtils.isEmpty(displayFeaturesString)) {
                storeFeaturesConsumer.accept(new ArrayList<>());
            } else {
                final DeviceState state = getCurrentDeviceState();
                runCallbackWhenValidState(state, storeFeaturesConsumer, displayFeaturesString);
            }
        });
    }

    private void notifyFoldingFeatureChangeLocked(String displayFeaturesString) {
        final DeviceState state = mCurrentDeviceState;
        if (!mDeviceStateMapper.isDeviceStateValid(state)) {
            return;
        }
        if (TextUtils.isEmpty(displayFeaturesString)) {
            notifyDataChanged(new ArrayList<>());
        } else {
            notifyDataChanged(calculateFoldingFeature(state, displayFeaturesString));
        }
    }

    @NonNull
    private List<CommonFoldingFeature> calculateFoldingFeature(@NonNull DeviceState deviceState,
            @NonNull String displayFeaturesString) {
        @CommonFoldingFeature.State
        final int hingeState = mDeviceStateMapper.getHingeState(deviceState);
        return parseListFromString(displayFeaturesString, hingeState);
    }

    /** Internal class to map device states to corresponding postures. */
    private static class DeviceStateMapper {
        /**
         * Emulated device state
         * {@link DeviceStateManager.DeviceStateCallback#onDeviceStateChanged(DeviceState)} to
         * {@link CommonFoldingFeature.State} map.
         */
        private final SparseIntArray mDeviceStateToPostureMap = new SparseIntArray();

        /** The list of device states that are supported. */
        @NonNull
        private final List<DeviceState> mSupportedStates;

        final boolean mIsHalfOpenedSupported;

        DeviceStateMapper(@NonNull Context context, @NonNull List<DeviceState> supportedStates) {
            mSupportedStates = supportedStates;

            final String[] deviceStatePosturePairs = context.getResources()
                    .getStringArray(R.array.config_device_state_postures);
            boolean isHalfOpenedSupported = false;
            for (String deviceStatePosturePair : deviceStatePosturePairs) {
                final String[] deviceStatePostureMapping = deviceStatePosturePair.split(":");
                if (deviceStatePostureMapping.length != 2) {
                    if (DEBUG) {
                        Log.e(TAG, "Malformed device state posture pair: "
                                + deviceStatePosturePair);
                    }
                    continue;
                }

                final int deviceState;
                final int posture;
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
        }

        boolean isDeviceStateToPostureMapEmpty() {
            return mDeviceStateToPostureMap.size() == 0;
        }

        /**
         * Validates if the provided deviceState exists in the {@link #mDeviceStateToPostureMap}
         * which was initialized in the constructor of {@link DeviceStateMapper}.
         * Returns a boolean value of whether the device state is valid.
         */
        boolean isDeviceStateValid(@NonNull DeviceState deviceState) {
            // If the device state is not found in the map, indexOfKey returns a negative number.
            return mDeviceStateToPostureMap.indexOfKey(deviceState.getIdentifier()) >= 0;
        }

        @CommonFoldingFeature.State
        int getHingeState(@NonNull DeviceState deviceState) {
            @CommonFoldingFeature.State
            final int posture =
                    mDeviceStateToPostureMap.get(deviceState.getIdentifier(), COMMON_STATE_UNKNOWN);
            if (posture != CommonFoldingFeature.COMMON_STATE_USE_BASE_STATE) {
                return posture;
            }

            final int baseStateIdentifier =
                    DeviceStateUtil.calculateBaseStateIdentifier(deviceState, mSupportedStates);
            return mDeviceStateToPostureMap.get(baseStateIdentifier, COMMON_STATE_UNKNOWN);
        }
    }
}
