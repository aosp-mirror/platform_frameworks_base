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
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManager.DeviceStateCallback;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.window.util.BaseDataProducer;

import com.android.internal.R;

import java.util.List;
import java.util.Optional;

/**
 * An implementation of {@link androidx.window.util.DataProducer} that returns the device's posture
 * by mapping the state returned from {@link DeviceStateManager} to values provided in the resources
 * config at {@link R.array#config_device_state_postures}.
 */
public final class DeviceStateManagerFoldingFeatureProducer extends
        BaseDataProducer<List<CommonFoldingFeature>> {
    private static final String TAG =
            DeviceStateManagerFoldingFeatureProducer.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final SparseIntArray mDeviceStateToPostureMap = new SparseIntArray();

    private int mCurrentDeviceState = INVALID_DEVICE_STATE;

    private final DeviceStateCallback mDeviceStateCallback = (state) -> {
        mCurrentDeviceState = state;
        notifyDataChanged();
    };

    public DeviceStateManagerFoldingFeatureProducer(@NonNull Context context) {
        mContext = context;
        String[] deviceStatePosturePairs = context.getResources()
                .getStringArray(R.array.config_device_state_postures);
        for (String deviceStatePosturePair : deviceStatePosturePairs) {
            String[] deviceStatePostureMapping = deviceStatePosturePair.split(":");
            if (deviceStatePostureMapping.length != 2) {
                if (DEBUG) {
                    Log.e(TAG, "Malformed device state posture pair: " + deviceStatePosturePair);
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
                    Log.e(TAG, "Failed to parse device state or posture: " + deviceStatePosturePair,
                            e);
                }
                continue;
            }

            mDeviceStateToPostureMap.put(deviceState, posture);
        }

        if (mDeviceStateToPostureMap.size() > 0) {
            context.getSystemService(DeviceStateManager.class)
                    .registerCallback(context.getMainExecutor(), mDeviceStateCallback);
        }
    }

    @Override
    @Nullable
    public Optional<List<CommonFoldingFeature>> getData() {
        final int globalHingeState = globalHingeState();
        String displayFeaturesString = mContext.getResources().getString(
                R.string.config_display_features);
        if (TextUtils.isEmpty(displayFeaturesString)) {
            return Optional.empty();
        }
        return Optional.of(parseListFromString(displayFeaturesString, globalHingeState));
    }

    private int globalHingeState() {
        return mDeviceStateToPostureMap.get(mCurrentDeviceState, COMMON_STATE_UNKNOWN);
    }
}
