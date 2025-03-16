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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.kotlin.VerificationKt.never;

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
import java.util.Set;
import java.util.concurrent.Executor;

/** Tests for {@link HearingDevicesPresetsController}. */
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesPresetsControllerTest extends SysuiTestCase {

    private static final int TEST_PRESET_INDEX = 1;
    private static final int TEST_UPDATED_PRESET_INDEX = 2;
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
    private CachedBluetoothDevice mCachedDevice;
    @Mock
    private CachedBluetoothDevice mCachedMemberDevice;
    @Mock
    private BluetoothDevice mDevice;
    @Mock
    private BluetoothDevice mMemberDevice;
    @Mock
    private HearingDevicesPresetsController.PresetCallback mCallback;

    private HearingDevicesPresetsController mController;

    @Before
    public void setUp() {
        when(mProfileManager.getHapClientProfile()).thenReturn(mHapClientProfile);
        when(mHapClientProfile.isProfileReady()).thenReturn(true);
        when(mCachedDevice.getDevice()).thenReturn(mDevice);
        when(mCachedDevice.getProfiles()).thenReturn(List.of(mHapClientProfile));
        when(mCachedDevice.getMemberDevice()).thenReturn(Set.of(mCachedMemberDevice));
        when(mCachedMemberDevice.getDevice()).thenReturn(mMemberDevice);

        mController = new HearingDevicesPresetsController(mProfileManager, mCallback);
        mController.setDevice(mCachedDevice);
    }

    @Test
    public void onServiceConnected_callExpectedCallback() {
        preparePresetInfo(/* isValid= */ true);

        mController.onServiceConnected();

        verify(mHapClientProfile).registerCallback(any(Executor.class),
                any(BluetoothHapClient.Callback.class));
        verify(mCallback).onPresetInfoUpdated(anyList(), anyInt());
    }

    @Test
    public void setDevice_nonHapDevice_getEmptyListAndInvalidActiveIndex() {
        when(mCachedDevice.getProfiles()).thenReturn(emptyList());
        preparePresetInfo(/* isValid= */ true);

        mController.setDevice(mCachedDevice);

        assertThat(mController.getAllPresetInfo()).isEmpty();
        assertThat(mController.getActivePresetIndex()).isEqualTo(
                BluetoothHapClient.PRESET_INDEX_UNAVAILABLE);
    }

    @Test
    public void refreshPresetInfo_containsOnlyNotAvailablePresetInfo_getEmptyList() {
        preparePresetInfo(/* isValid= */ false);

        mController.refreshPresetInfo();

        assertThat(mController.getAllPresetInfo()).isEmpty();
    }

    @Test
    public void refreshPresetInfo_containsOnePresetInfo_getOnePresetInfo() {
        List<BluetoothHapPresetInfo> infos = preparePresetInfo(/* isValid= */ true);

        mController.refreshPresetInfo();

        List<BluetoothHapPresetInfo> presetInfos = mController.getAllPresetInfo();
        assertThat(presetInfos.size()).isEqualTo(1);
        assertThat(presetInfos).contains(infos.getFirst());
    }

    @Test
    public void refreshPresetInfo_getExpectedIndex() {
        preparePresetInfo(/* isValid= */ true);

        mController.refreshPresetInfo();

        assertThat(mController.getActivePresetIndex()).isEqualTo(TEST_PRESET_INDEX);
    }

    @Test
    public void refreshPresetInfo_callbackIsCalledWhenNeeded() {
        List<BluetoothHapPresetInfo> infos = preparePresetInfo(/* isValid= */ true);

        mController.refreshPresetInfo();

        verify(mCallback).onPresetInfoUpdated(infos, TEST_PRESET_INDEX);

        Mockito.reset(mCallback);
        mController.refreshPresetInfo();

        verify(mCallback, never()).onPresetInfoUpdated(anyList(), anyInt());

        Mockito.reset(mCallback);
        when(mHapClientProfile.getActivePresetIndex(mDevice)).thenReturn(TEST_UPDATED_PRESET_INDEX);
        mController.refreshPresetInfo();

        verify(mCallback).onPresetInfoUpdated(infos, TEST_UPDATED_PRESET_INDEX);
    }

    @Test
    public void onPresetSelected_callOnPresetInfoUpdatedWithExpectedPresetIndex() {
        List<BluetoothHapPresetInfo> infos = preparePresetInfo(/* isValid= */ true);

        mController.onPresetSelected(mDevice, TEST_PRESET_INDEX, TEST_REASON);

        verify(mCallback).onPresetInfoUpdated(infos, TEST_PRESET_INDEX);
    }

    @Test
    public void onPresetInfoChanged_callOnPresetInfoUpdatedWithExpectedPresetIndex() {
        List<BluetoothHapPresetInfo> infos = preparePresetInfo(/* isValid= */ true);

        mController.onPresetInfoChanged(mDevice, infos, TEST_REASON);

        verify(mCallback).onPresetInfoUpdated(infos, TEST_PRESET_INDEX);
    }

    @Test
    public void onPresetSelectionFailed_callOnPresetCommandFailed() {
        mController.onPresetSelectionFailed(mDevice, TEST_REASON);

        verify(mCallback).onPresetCommandFailed(TEST_REASON);
    }

    @Test
    public void onSetPresetNameFailed_callOnPresetCommandFailed() {
        mController.onSetPresetNameFailed(mDevice, TEST_REASON);

        verify(mCallback).onPresetCommandFailed(TEST_REASON);
    }

    @Test
    public void onPresetSelectionForGroupFailed_callSelectPresetIndependently() {
        mController.selectPreset(TEST_PRESET_INDEX);
        Mockito.reset(mHapClientProfile);
        when(mHapClientProfile.getHapGroup(mDevice)).thenReturn(TEST_HAP_GROUP_ID);

        mController.onPresetSelectionForGroupFailed(TEST_HAP_GROUP_ID, TEST_REASON);

        verify(mHapClientProfile).selectPreset(mDevice, TEST_PRESET_INDEX);
        verify(mHapClientProfile).selectPreset(mMemberDevice, TEST_PRESET_INDEX);
    }

    @Test
    public void onSetPresetNameForGroupFailed_callOnPresetCommandFailed() {
        mController.onSetPresetNameForGroupFailed(TEST_HAP_GROUP_ID, TEST_REASON);

        verify(mCallback).onPresetCommandFailed(TEST_REASON);
    }

    @Test
    public void registerHapCallback_profileNotReady_addServiceListener() {
        when(mHapClientProfile.isProfileReady()).thenReturn(false);

        mController.registerHapCallback();

        verify(mProfileManager).addServiceListener(mController);
        verify(mHapClientProfile, never()).registerCallback(any(Executor.class),
                any(BluetoothHapClient.Callback.class));
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
        when(mHapClientProfile.supportsSynchronizedPresets(mDevice)).thenReturn(true);
        when(mHapClientProfile.getHapGroup(mDevice)).thenReturn(TEST_HAP_GROUP_ID);

        mController.selectPreset(TEST_PRESET_INDEX);

        verify(mHapClientProfile).selectPresetForGroup(TEST_HAP_GROUP_ID, TEST_PRESET_INDEX);
    }

    @Test
    public void selectPreset_supportSynchronized_invalidGroupId_callSelectPresetIndependently() {
        when(mHapClientProfile.supportsSynchronizedPresets(mDevice)).thenReturn(true);
        when(mHapClientProfile.getHapGroup(mDevice)).thenReturn(
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID);

        mController.selectPreset(TEST_PRESET_INDEX);

        verify(mHapClientProfile).selectPreset(mDevice, TEST_PRESET_INDEX);
        verify(mHapClientProfile).selectPreset(mMemberDevice, TEST_PRESET_INDEX);
    }

    @Test
    public void selectPreset_notSupportSynchronized_validGroupId_callSelectPresetIndependently() {
        when(mHapClientProfile.supportsSynchronizedPresets(mDevice)).thenReturn(false);
        when(mHapClientProfile.getHapGroup(mDevice)).thenReturn(TEST_HAP_GROUP_ID);

        mController.selectPreset(TEST_PRESET_INDEX);

        verify(mHapClientProfile).selectPreset(mDevice, TEST_PRESET_INDEX);
        verify(mHapClientProfile).selectPreset(mMemberDevice, TEST_PRESET_INDEX);
    }

    private List<BluetoothHapPresetInfo> preparePresetInfo(boolean isValid) {
        BluetoothHapPresetInfo info = getHapPresetInfo(isValid);
        List<BluetoothHapPresetInfo> infos = List.of(info);
        when(mHapClientProfile.getAllPresetInfo(mDevice)).thenReturn(infos);
        when(mHapClientProfile.getActivePresetIndex(mDevice)).thenReturn(TEST_PRESET_INDEX);
        return infos;
    }

    private BluetoothHapPresetInfo getHapPresetInfo(boolean available) {
        BluetoothHapPresetInfo info = mock(BluetoothHapPresetInfo.class);
        when(info.getName()).thenReturn(TEST_PRESET_NAME);
        when(info.getIndex()).thenReturn(TEST_PRESET_INDEX);
        when(info.isAvailable()).thenReturn(available);
        return info;
    }
}
