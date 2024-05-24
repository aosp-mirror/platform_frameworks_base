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

package com.android.server.companion.virtual;

import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_CUSTOM;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_AUDIO;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_CAMERA;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_SENSORS;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.Context.DEVICE_ID_INVALID;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import android.companion.virtual.IVirtualDevice;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.flags.Flags;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class VirtualDeviceTest {

    private static final int VIRTUAL_DEVICE_ID = 42;
    private static final String PERSISTENT_ID = "persistentId";
    private static final String DEVICE_NAME = "VirtualDeviceName";
    private static final String DISPLAY_NAME = "DisplayName";

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Mock
    private IVirtualDevice mVirtualDevice;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void build_invalidId_shouldThrowIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualDevice(
                        mVirtualDevice, DEVICE_ID_INVALID, PERSISTENT_ID, DEVICE_NAME));
    }

    @Test
    public void build_defaultId_shouldThrowIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualDevice(
                        mVirtualDevice, DEVICE_ID_DEFAULT, PERSISTENT_ID, DEVICE_NAME));
    }

    @Test
    public void build_onlyRequiredFields() {
        VirtualDevice virtualDevice =
                new VirtualDevice(
                        mVirtualDevice, VIRTUAL_DEVICE_ID, /*persistentId=*/null, /*name=*/null);
        assertThat(virtualDevice.getDeviceId()).isEqualTo(VIRTUAL_DEVICE_ID);
        assertThat(virtualDevice.getPersistentDeviceId()).isNull();
        assertThat(virtualDevice.getName()).isNull();
    }

    @Test
    public void parcelable_shouldRecreateSuccessfully() {
        VirtualDevice originalDevice =
                new VirtualDevice(mVirtualDevice, VIRTUAL_DEVICE_ID, PERSISTENT_ID, DEVICE_NAME,
                        DISPLAY_NAME);
        Parcel parcel = Parcel.obtain();
        originalDevice.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VirtualDevice device = VirtualDevice.CREATOR.createFromParcel(parcel);
        assertThat(device.getDeviceId()).isEqualTo(VIRTUAL_DEVICE_ID);
        assertThat(device.getPersistentDeviceId()).isEqualTo(PERSISTENT_ID);
        assertThat(device.getName()).isEqualTo(DEVICE_NAME);
        assertThat(device.getDisplayName().toString()).isEqualTo(DISPLAY_NAME);
    }

    @Test
    public void virtualDevice_getDisplayIds() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_VDM_PUBLIC_APIS);

        VirtualDevice virtualDevice =
                new VirtualDevice(
                        mVirtualDevice, VIRTUAL_DEVICE_ID, /*persistentId=*/null, /*name=*/null);

        when(mVirtualDevice.getDisplayIds()).thenReturn(new int[0]);
        assertThat(virtualDevice.getDisplayIds()).hasLength(0);

        final int[] displayIds = new int[]{7, 18};
        when(mVirtualDevice.getDisplayIds()).thenReturn(displayIds);
        assertThat(virtualDevice.getDisplayIds()).isEqualTo(displayIds);
    }

    @Test
    public void virtualDevice_hasCustomSensorSupport() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_VDM_PUBLIC_APIS);

        VirtualDevice virtualDevice =
                new VirtualDevice(
                        mVirtualDevice, VIRTUAL_DEVICE_ID, /*persistentId=*/null, /*name=*/null);

        when(mVirtualDevice.getDevicePolicy(POLICY_TYPE_SENSORS)).thenReturn(DEVICE_POLICY_DEFAULT);
        assertThat(virtualDevice.hasCustomSensorSupport()).isFalse();

        when(mVirtualDevice.getDevicePolicy(POLICY_TYPE_SENSORS)).thenReturn(DEVICE_POLICY_CUSTOM);
        assertThat(virtualDevice.hasCustomSensorSupport()).isTrue();
    }

    @Test
    public void virtualDevice_hasCustomAudioInputSupport() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_VDM_PUBLIC_APIS);

        VirtualDevice virtualDevice =
                new VirtualDevice(
                        mVirtualDevice, VIRTUAL_DEVICE_ID, /*persistentId=*/null, /*name=*/null);

        when(mVirtualDevice.getDevicePolicy(POLICY_TYPE_AUDIO)).thenReturn(DEVICE_POLICY_DEFAULT);
        assertThat(virtualDevice.hasCustomAudioInputSupport()).isFalse();

        when(mVirtualDevice.getDevicePolicy(POLICY_TYPE_AUDIO)).thenReturn(DEVICE_POLICY_CUSTOM);
        assertThat(virtualDevice.hasCustomAudioInputSupport()).isTrue();
    }

    @Test
    public void virtualDevice_hasCustomCameraSupport() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_VDM_PUBLIC_APIS);

        VirtualDevice virtualDevice =
                new VirtualDevice(
                        mVirtualDevice, VIRTUAL_DEVICE_ID, /*persistentId=*/null, /*name=*/null);

        when(mVirtualDevice.getDevicePolicy(POLICY_TYPE_CAMERA)).thenReturn(DEVICE_POLICY_DEFAULT);
        assertThat(virtualDevice.hasCustomCameraSupport()).isFalse();

        when(mVirtualDevice.getDevicePolicy(POLICY_TYPE_CAMERA)).thenReturn(DEVICE_POLICY_CUSTOM);
        assertThat(virtualDevice.hasCustomCameraSupport()).isTrue();
    }
}
