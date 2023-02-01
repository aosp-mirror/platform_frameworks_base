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

package com.android.server.companion.virtual;

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_SENSORS;
import static android.hardware.Sensor.TYPE_ACCELEROMETER;

import static com.google.common.truth.Truth.assertThat;

import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.sensor.VirtualSensorConfig;
import android.os.Parcel;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualDeviceParamsTest {

    private static final String SENSOR_NAME = "VirtualSensorName";
    private static final String SENSOR_VENDOR = "VirtualSensorVendor";
    private static final int PLAYBACK_SESSION_ID = 42;
    private static final int RECORDING_SESSION_ID = 77;

    @Test
    public void parcelable_shouldRecreateSuccessfully() {
        VirtualDeviceParams originalParams = new VirtualDeviceParams.Builder()
                .setLockState(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED)
                .setUsersWithMatchingAccounts(Set.of(UserHandle.of(123), UserHandle.of(456)))
                .setDevicePolicy(POLICY_TYPE_SENSORS, DEVICE_POLICY_CUSTOM)
                .setDevicePolicy(POLICY_TYPE_AUDIO, DEVICE_POLICY_CUSTOM)
                .setAudioPlaybackSessionId(PLAYBACK_SESSION_ID)
                .setAudioRecordingSessionId(RECORDING_SESSION_ID)
                .addVirtualSensorConfig(
                        new VirtualSensorConfig.Builder(TYPE_ACCELEROMETER, SENSOR_NAME)
                                .setVendor(SENSOR_VENDOR)
                                .build())
                .build();
        Parcel parcel = Parcel.obtain();
        originalParams.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualDeviceParams params = VirtualDeviceParams.CREATOR.createFromParcel(parcel);
        assertThat(params).isEqualTo(originalParams);
        assertThat(params.getLockState()).isEqualTo(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED);
        assertThat(params.getUsersWithMatchingAccounts())
                .containsExactly(UserHandle.of(123), UserHandle.of(456));
        assertThat(params.getDevicePolicy(POLICY_TYPE_SENSORS)).isEqualTo(DEVICE_POLICY_CUSTOM);
        assertThat(params.getDevicePolicy(POLICY_TYPE_AUDIO)).isEqualTo(DEVICE_POLICY_CUSTOM);
        assertThat(params.getAudioPlaybackSessionId()).isEqualTo(PLAYBACK_SESSION_ID);
        assertThat(params.getAudioRecordingSessionId()).isEqualTo(RECORDING_SESSION_ID);

        List<VirtualSensorConfig> sensorConfigs = params.getVirtualSensorConfigs();
        assertThat(sensorConfigs).hasSize(1);
        VirtualSensorConfig sensorConfig = sensorConfigs.get(0);
        assertThat(sensorConfig.getType()).isEqualTo(TYPE_ACCELEROMETER);
        assertThat(sensorConfig.getName()).isEqualTo(SENSOR_NAME);
        assertThat(sensorConfig.getVendor()).isEqualTo(SENSOR_VENDOR);
        assertThat(sensorConfig.getStateChangeCallback()).isNull();
    }
}
