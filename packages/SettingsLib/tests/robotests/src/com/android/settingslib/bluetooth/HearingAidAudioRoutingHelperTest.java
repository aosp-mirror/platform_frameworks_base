/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.bluetooth;

import static com.android.settingslib.bluetooth.HearingAidAudioRoutingConstants.BUILTIN_MIC;
import static com.android.settingslib.bluetooth.HearingAidAudioRoutingConstants.BUILTIN_SPEAKER;
import static com.android.settingslib.bluetooth.HearingAidAudioRoutingConstants.MICROPHONE_SOURCE_VOICE_COMMUNICATION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;

import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.bluetooth.HearingAidAudioRoutingConstants.RoutingValue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Tests for {@link HearingAidAudioRoutingHelper}. */
@RunWith(RobolectricTestRunner.class)
public class HearingAidAudioRoutingHelperTest {

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Spy
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private static final String TEST_DEVICE_ADDRESS = "00:A1:A1:A1:A1:A1";
    private static final String NOT_EXPECT_DEVICE_ADDRESS = "11:B2:B2:B2:B2:B2";

    @Mock
    private AudioProductStrategy mAudioStrategy;
    @Spy
    private AudioManager mAudioManager = mContext.getSystemService(AudioManager.class);
    @Mock
    private AudioDeviceInfo mHearingDeviceInfoOutput;
    @Mock
    private AudioDeviceInfo mLeHearingDeviceInfoInput;
    @Mock
    private CachedBluetoothDevice mCachedBluetoothDevice;
    @Mock
    private CachedBluetoothDevice mSubCachedBluetoothDevice;
    private AudioDeviceAttributes mHearingDeviceAttributeOutput;
    private HearingAidAudioRoutingHelper mHelper;

    @Before
    public void setUp() {
        doReturn(mAudioManager).when(mContext).getSystemService(AudioManager.class);
        when(mHearingDeviceInfoOutput.getType()).thenReturn(AudioDeviceInfo.TYPE_HEARING_AID);
        when(mHearingDeviceInfoOutput.getAddress()).thenReturn(TEST_DEVICE_ADDRESS);
        when(mLeHearingDeviceInfoInput.getType()).thenReturn(AudioDeviceInfo.TYPE_BLE_HEADSET);
        when(mLeHearingDeviceInfoInput.getAddress()).thenReturn(TEST_DEVICE_ADDRESS);
        when(mCachedBluetoothDevice.getAddress()).thenReturn(TEST_DEVICE_ADDRESS);
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)).thenReturn(
                new AudioDeviceInfo[]{mHearingDeviceInfoOutput});
        when(mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(
                new AudioDeviceInfo[]{mLeHearingDeviceInfoInput});
        doReturn(Collections.emptyList()).when(mAudioManager).getPreferredDevicesForStrategy(
                any(AudioProductStrategy.class));
        when(mAudioStrategy.getAudioAttributesForLegacyStreamType(
                AudioManager.STREAM_MUSIC))
                .thenReturn((new AudioAttributes.Builder()).build());

        mHearingDeviceAttributeOutput = new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_OUTPUT,
                AudioDeviceInfo.TYPE_HEARING_AID,
                TEST_DEVICE_ADDRESS);
        mHelper = spy(new HearingAidAudioRoutingHelper(mContext));
        doReturn(List.of(mAudioStrategy)).when(mHelper).getAudioProductStrategies();
    }

    @Test
    public void setPreferredDeviceRoutingStrategies_hadValueThenValueAuto_callRemoveStrategy() {
        when(mAudioManager.getPreferredDeviceForStrategy(mAudioStrategy)).thenReturn(
                mHearingDeviceAttributeOutput);

        mHelper.setPreferredDeviceRoutingStrategies(List.of(mAudioStrategy),
                mHearingDeviceAttributeOutput, RoutingValue.AUTO);

        verify(mAudioManager, atLeastOnce()).removePreferredDeviceForStrategy(mAudioStrategy);
    }

    @Test
    public void setPreferredDeviceRoutingStrategies_NoValueThenValueAuto_notCallRemoveStrategy() {
        when(mAudioManager.getPreferredDeviceForStrategy(mAudioStrategy)).thenReturn(null);

        mHelper.setPreferredDeviceRoutingStrategies(List.of(mAudioStrategy),
                mHearingDeviceAttributeOutput, RoutingValue.AUTO);

        verify(mAudioManager, never()).removePreferredDeviceForStrategy(mAudioStrategy);
    }

    @Test
    public void setPreferredDeviceRoutingStrategies_valueHearingDevice_callSetStrategy() {
        mHelper.setPreferredDeviceRoutingStrategies(List.of(mAudioStrategy),
                mHearingDeviceAttributeOutput, RoutingValue.HEARING_DEVICE);

        verify(mAudioManager, atLeastOnce()).setPreferredDeviceForStrategy(mAudioStrategy,
                mHearingDeviceAttributeOutput);
    }

    @Test
    public void setPreferredDeviceRoutingStrategies_valueBuiltinDevice_callSetStrategy() {
        mHelper.setPreferredDeviceRoutingStrategies(List.of(mAudioStrategy),
                mHearingDeviceAttributeOutput, RoutingValue.BUILTIN_DEVICE);

        verify(mAudioManager, atLeastOnce()).setPreferredDeviceForStrategy(mAudioStrategy,
                BUILTIN_SPEAKER);
    }

    @Test
    public void getMatchedHearingDeviceAttributesForOutput_mainHearingDevice_equalAddress() {
        when(mCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getAddress()).thenReturn(TEST_DEVICE_ADDRESS);

        final String targetAddress = mHelper.getMatchedHearingDeviceAttributesForOutput(
                mCachedBluetoothDevice).getAddress();

        assertThat(targetAddress).isEqualTo(mHearingDeviceAttributeOutput.getAddress());
    }

    @Test
    public void getMatchedHearingDeviceAttributesForOutput_subHearingDevice_equalAddress() {
        when(mCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getAddress()).thenReturn(NOT_EXPECT_DEVICE_ADDRESS);
        when(mCachedBluetoothDevice.getSubDevice()).thenReturn(mSubCachedBluetoothDevice);
        when(mSubCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);
        when(mSubCachedBluetoothDevice.getAddress()).thenReturn(TEST_DEVICE_ADDRESS);

        final String targetAddress = mHelper.getMatchedHearingDeviceAttributesForOutput(
                mCachedBluetoothDevice).getAddress();

        assertThat(targetAddress).isEqualTo(mHearingDeviceAttributeOutput.getAddress());
    }

    @Test
    public void getMatchedHearingDeviceAttributesForOutput_memberHearingDevice_equalAddress() {
        when(mSubCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);
        when(mSubCachedBluetoothDevice.getAddress()).thenReturn(TEST_DEVICE_ADDRESS);
        final Set<CachedBluetoothDevice> memberDevices = new HashSet<>();
        memberDevices.add(mSubCachedBluetoothDevice);
        when(mCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);
        when(mCachedBluetoothDevice.getAddress()).thenReturn(NOT_EXPECT_DEVICE_ADDRESS);
        when(mCachedBluetoothDevice.getMemberDevice()).thenReturn(memberDevices);

        final String targetAddress = mHelper.getMatchedHearingDeviceAttributesForOutput(
                mCachedBluetoothDevice).getAddress();

        assertThat(targetAddress).isEqualTo(mHearingDeviceAttributeOutput.getAddress());
    }

    @Test
    public void setPreferredInputDeviceForCalls_valueAuto_callClearPreset() {
        when(mCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);

        mHelper.setPreferredInputDeviceForCalls(mCachedBluetoothDevice, RoutingValue.AUTO);

        verify(mAudioManager).clearPreferredDevicesForCapturePreset(
                MICROPHONE_SOURCE_VOICE_COMMUNICATION);
    }

    @Test
    public void setPreferredInputDeviceForCalls_valueHearingDevice_callSetPresetToHearingDevice() {
        final ArgumentCaptor<AudioDeviceAttributes> audioDeviceAttributesCaptor =
                ArgumentCaptor.forClass(AudioDeviceAttributes.class);
        when(mCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);

        mHelper.setPreferredInputDeviceForCalls(mCachedBluetoothDevice,
                RoutingValue.HEARING_DEVICE);

        verify(mAudioManager).setPreferredDeviceForCapturePreset(
                eq(MICROPHONE_SOURCE_VOICE_COMMUNICATION), audioDeviceAttributesCaptor.capture());
        assertThat(audioDeviceAttributesCaptor.getValue().getAddress()).isEqualTo(
                TEST_DEVICE_ADDRESS);
    }

    @Test
    public void setPreferredInputDeviceForCalls_valueBuiltinDevice_callClearPresetToBuiltinMic() {
        when(mCachedBluetoothDevice.isHearingAidDevice()).thenReturn(true);

        mHelper.setPreferredInputDeviceForCalls(mCachedBluetoothDevice,
                RoutingValue.BUILTIN_DEVICE);

        verify(mAudioManager).setPreferredDeviceForCapturePreset(
                MICROPHONE_SOURCE_VOICE_COMMUNICATION, BUILTIN_MIC);
    }
}
