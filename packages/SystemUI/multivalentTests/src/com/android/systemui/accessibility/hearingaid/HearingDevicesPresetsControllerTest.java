/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Collections.emptyList;

import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHapClient;
import android.bluetooth.BluetoothHapPresetInfo;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.HapClientProfile;
import com.android.settingslib.bluetooth.LocalBluetoothProfile;
import com.android.settingslib.bluetooth.LocalBluetoothProfileManager;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;
import java.util.concurrent.Executor;

/** Tests for {@link HearingDevicesPresetsController}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesPresetsControllerTest extends SysuiTestCase {

    private static final int TEST_PRESET_INDEX = 1;
    private static final String TEST_PRESET_NAME = "test_preset";
    private static final int TEST_HAP_GROUP_ID = 1;
    private static final int TEST_REASON = 1024;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private LocalBluetoothProfileManager mProfileManager;
    @Mock
    private HapClientProfile mHapClientProfile;
    @Mock
    private CachedBluetoothDevice mCachedBluetoothDevice;
    @Mock
    private CachedBluetoothDevice mSubCachedBluetoothDevice;
    @Mock
    private BluetoothDevice mBluetoothDevice;
    @Mock
    private BluetoothDevice mSubBluetoothDevice;

    @Mock
    private HearingDevicesPresetsController.PresetCallback mCallback;

    private HearingDevicesPresetsController mController;

    @Before
    public void setUp() {
        when(mProfileManager.getHapClientProfile()).thenReturn(mHapClientProfile);
        when(mHapClientProfile.isProfileReady()).thenReturn(true);
        when(mCachedBluetoothDevice.getDevice()).thenReturn(mBluetoothDevice);
        when(mCachedBluetoothDevice.getSubDevice()).thenReturn(mSubCachedBluetoothDevice);
        when(mSubCachedBluetoothDevice.getDevice()).thenReturn(mSubBluetoothDevice);

        mController = new HearingDevicesPresetsController(mProfileManager, mCallback);
    }

    @Test
    public void onServiceConnected_callExpectedCallback() {
        mController.onServiceConnected();

        verify(mHapClientProfile).registerCallback(any(Executor.class),
                any(BluetoothHapClient.Callback.class));
        verify(mCallback).onPresetInfoUpdated(anyList(), anyInt());
    }

    @Test
    public void getAllPresetInfo_setInvalidHearingDevice_getEmpty() {
        when(mCachedBluetoothDevice.getProfiles()).thenReturn(emptyList());
        mController.setHearingDeviceIfSupportHap(mCachedBluetoothDevice);
        BluetoothHapPresetInfo hapPresetInfo = getHapPresetInfo(true);
        when(mHapClientProfile.getAllPresetInfo(mBluetoothDevice)).thenReturn(
                List.of(hapPresetInfo));

        assertThat(mController.getAllPresetInfo()).isEmpty();
    }

    @Test
    public void getAllPresetInfo_containsNotAvailablePresetInfo_getEmpty() {
        setValidHearingDeviceSupportHap();
        BluetoothHapPresetInfo hapPresetInfo = getHapPresetInfo(false);
        when(mHapClientProfile.getAllPresetInfo(mBluetoothDevice)).thenReturn(
                List.of(hapPresetInfo));

        assertThat(mController.getAllPresetInfo()).isEmpty();
    }

    @Test
    public void getAllPresetInfo_containsOnePresetInfo_getOnePresetInfo() {
        setValidHearingDeviceSupportHap();
        BluetoothHapPresetInfo hapPresetInfo = getHapPresetInfo(true);
        when(mHapClientProfile.getAllPresetInfo(mBluetoothDevice)).thenReturn(
                List.of(hapPresetInfo));

        assertThat(mController.getAllPresetInfo()).contains(hapPresetInfo);
    }

    @Test
    public void getActivePresetIndex_getExpectedIndex() {
        setValidHearingDeviceSupportHap();
        when(mHapClientProfile.getActivePresetIndex(mBluetoothDevice)).thenReturn(
                TEST_PRESET_INDEX);

        assertThat(mController.getActivePresetIndex()).isEqualTo(TEST_PRESET_INDEX);
    }

    @Test
    public void onPresetSelected_presetIndex_callOnPresetInfoUpdatedWithExpectedPresetIndex() {
        setValidHearingDeviceSupportHap();
        BluetoothHapPresetInfo hapPresetInfo = getHapPresetInfo(true);
        when(mHapClientProfile.getAllPresetInfo(mBluetoothDevice)).thenReturn(
                List.of(hapPresetInfo));
        when(mHapClientProfile.getActivePresetIndex(mBluetoothDevice)).thenReturn(
                TEST_PRESET_INDEX);

        mController.onPresetSelected(mBluetoothDevice, TEST_PRESET_INDEX, TEST_REASON);

        verify(mCallback).onPresetInfoUpdated(eq(List.of(hapPresetInfo)), eq(TEST_PRESET_INDEX));
    }

    @Test
    public void onPresetInfoChanged_presetIndex_callOnPresetInfoUpdatedWithExpectedPresetIndex() {
        setValidHearingDeviceSupportHap();
        BluetoothHapPresetInfo hapPresetInfo = getHapPresetInfo(true);
        when(mHapClientProfile.getAllPresetInfo(mBluetoothDevice)).thenReturn(
                List.of(hapPresetInfo));
        when(mHapClientProfile.getActivePresetIndex(mBluetoothDevice)).thenReturn(
                TEST_PRESET_INDEX);

        mController.onPresetInfoChanged(mBluetoothDevice, List.of(hapPresetInfo), TEST_REASON);

        verify(mCallback).onPresetInfoUpdated(List.of(hapPresetInfo), TEST_PRESET_INDEX);
    }

    @Test
    public void onPresetSelectionFailed_callOnPresetCommandFailed() {
        setValidHearingDeviceSupportHap();

        mController.onPresetSelectionFailed(mBluetoothDevice, TEST_REASON);

        verify(mCallback).onPresetCommandFailed(TEST_REASON);
    }

    @Test
    public void onSetPresetNameFailed_callOnPresetCommandFailed() {
        setValidHearingDeviceSupportHap();

        mController.onSetPresetNameFailed(mBluetoothDevice, TEST_REASON);

        verify(mCallback).onPresetCommandFailed(TEST_REASON);
    }

    @Test
    public void onPresetSelectionForGroupFailed_callSelectPresetIndividual() {
        setValidHearingDeviceSupportHap();
        mController.selectPreset(TEST_PRESET_INDEX);
        Mockito.reset(mHapClientProfile);
        when(mHapClientProfile.getHapGroup(mBluetoothDevice)).thenReturn(TEST_HAP_GROUP_ID);

        mController.onPresetSelectionForGroupFailed(TEST_HAP_GROUP_ID, TEST_REASON);


        verify(mHapClientProfile).selectPreset(mBluetoothDevice, TEST_PRESET_INDEX);
        verify(mHapClientProfile).selectPreset(mSubBluetoothDevice, TEST_PRESET_INDEX);
    }

    @Test
    public void onSetPresetNameForGroupFailed_callOnPresetCommandFailed() {
        setValidHearingDeviceSupportHap();

        mController.onSetPresetNameForGroupFailed(TEST_HAP_GROUP_ID, TEST_REASON);

        verify(mCallback).onPresetCommandFailed(TEST_REASON);
    }

    @Test
    public void registerHapCallback_callHapRegisterCallback() {
        mController.registerHapCallback();

        verify(mHapClientProfile).registerCallback(any(Executor.class),
                any(BluetoothHapClient.Callback.class));
    }

    @Test
    public void unregisterHapCallback_callHapUnregisterCallback() {
        mController.unregisterHapCallback();

        verify(mHapClientProfile).unregisterCallback(any(BluetoothHapClient.Callback.class));
    }

    @Test
    public void selectPreset_supportSynchronized_validGroupId_callSelectPresetForGroup() {
        setValidHearingDeviceSupportHap();
        when(mHapClientProfile.supportsSynchronizedPresets(mBluetoothDevice)).thenReturn(true);
        when(mHapClientProfile.getHapGroup(mBluetoothDevice)).thenReturn(TEST_HAP_GROUP_ID);

        mController.selectPreset(TEST_PRESET_INDEX);

        verify(mHapClientProfile).selectPresetForGroup(TEST_HAP_GROUP_ID, TEST_PRESET_INDEX);
    }

    @Test
    public void selectPreset_supportSynchronized_invalidGroupId_callSelectPresetIndividual() {
        setValidHearingDeviceSupportHap();
        when(mHapClientProfile.supportsSynchronizedPresets(mBluetoothDevice)).thenReturn(true);
        when(mHapClientProfile.getHapGroup(mBluetoothDevice)).thenReturn(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID);

        mController.selectPreset(TEST_PRESET_INDEX);

        verify(mHapClientProfile).selectPreset(mBluetoothDevice, TEST_PRESET_INDEX);
        verify(mHapClientProfile).selectPreset(mSubBluetoothDevice, TEST_PRESET_INDEX);
    }

    @Test
    public void selectPreset_notSupportSynchronized_validGroupId_callSelectPresetIndividual() {
        setValidHearingDeviceSupportHap();
        when(mHapClientProfile.supportsSynchronizedPresets(mBluetoothDevice)).thenReturn(false);
        when(mHapClientProfile.getHapGroup(mBluetoothDevice)).thenReturn(TEST_HAP_GROUP_ID);

        mController.selectPreset(TEST_PRESET_INDEX);

        verify(mHapClientProfile).selectPreset(mBluetoothDevice, TEST_PRESET_INDEX);
        verify(mHapClientProfile).selectPreset(mSubBluetoothDevice, TEST_PRESET_INDEX);
    }

    private BluetoothHapPresetInfo getHapPresetInfo(boolean available) {
        BluetoothHapPresetInfo info = mock(BluetoothHapPresetInfo.class);
        when(info.getName()).thenReturn(TEST_PRESET_NAME);
        when(info.getIndex()).thenReturn(TEST_PRESET_INDEX);
        when(info.isAvailable()).thenReturn(available);
        return info;
    }

    private void setValidHearingDeviceSupportHap() {
        LocalBluetoothProfile hapClientProfile = mock(HapClientProfile.class);
        List<LocalBluetoothProfile> profiles = List.of(hapClientProfile);
        when(mCachedBluetoothDevice.getProfiles()).thenReturn(profiles);

        mController.setHearingDeviceIfSupportHap(mCachedBluetoothDevice);
    }
}
