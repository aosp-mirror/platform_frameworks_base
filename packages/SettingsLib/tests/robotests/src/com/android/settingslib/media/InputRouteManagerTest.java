/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.settingslib.media;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import com.android.settingslib.testutils.shadow.ShadowRouter2Manager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowRouter2Manager.class})
public class InputRouteManagerTest {
    private static final int BUILTIN_MIC_ID = 1;
    private static final int INPUT_WIRED_HEADSET_ID = 2;
    private static final int INPUT_USB_DEVICE_ID = 3;
    private static final int INPUT_USB_HEADSET_ID = 4;
    private static final int INPUT_USB_ACCESSORY_ID = 5;

    private final Context mContext = spy(RuntimeEnvironment.application);
    private InputRouteManager mInputRouteManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final AudioManager audioManager = mock(AudioManager.class);
        mInputRouteManager = new InputRouteManager(mContext, audioManager);
    }

    @Test
    public void onAudioDevicesAdded_shouldUpdateInputMediaDevice() {
        final AudioDeviceInfo info1 = mock(AudioDeviceInfo.class);
        when(info1.getType()).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_MIC);
        when(info1.getId()).thenReturn(BUILTIN_MIC_ID);

        final AudioDeviceInfo info2 = mock(AudioDeviceInfo.class);
        when(info2.getType()).thenReturn(AudioDeviceInfo.TYPE_WIRED_HEADSET);
        when(info2.getId()).thenReturn(INPUT_WIRED_HEADSET_ID);

        final AudioDeviceInfo info3 = mock(AudioDeviceInfo.class);
        when(info3.getType()).thenReturn(AudioDeviceInfo.TYPE_USB_DEVICE);
        when(info3.getId()).thenReturn(INPUT_USB_DEVICE_ID);

        final AudioDeviceInfo info4 = mock(AudioDeviceInfo.class);
        when(info4.getType()).thenReturn(AudioDeviceInfo.TYPE_USB_HEADSET);
        when(info4.getId()).thenReturn(INPUT_USB_HEADSET_ID);

        final AudioDeviceInfo info5 = mock(AudioDeviceInfo.class);
        when(info5.getType()).thenReturn(AudioDeviceInfo.TYPE_USB_ACCESSORY);
        when(info5.getId()).thenReturn(INPUT_USB_ACCESSORY_ID);

        final AudioDeviceInfo unsupportedInfo = mock(AudioDeviceInfo.class);
        when(unsupportedInfo.getType()).thenReturn(AudioDeviceInfo.TYPE_HDMI);

        final AudioManager audioManager = mock(AudioManager.class);
        AudioDeviceInfo[] devices = {info1, info2, info3, info4, info5, unsupportedInfo};
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(devices);

        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);

        assertThat(inputRouteManager.mInputMediaDevices).isEmpty();

        inputRouteManager.mAudioDeviceCallback.onAudioDevicesAdded(devices);

        // The unsupported info should be filtered out.
        assertThat(inputRouteManager.mInputMediaDevices).hasSize(devices.length - 1);
        assertThat(inputRouteManager.mInputMediaDevices.get(0).getId())
                .isEqualTo(String.valueOf(BUILTIN_MIC_ID));
        assertThat(inputRouteManager.mInputMediaDevices.get(1).getId())
                .isEqualTo(String.valueOf(INPUT_WIRED_HEADSET_ID));
        assertThat(inputRouteManager.mInputMediaDevices.get(2).getId())
                .isEqualTo(String.valueOf(INPUT_USB_DEVICE_ID));
        assertThat(inputRouteManager.mInputMediaDevices.get(3).getId())
                .isEqualTo(String.valueOf(INPUT_USB_HEADSET_ID));
        assertThat(inputRouteManager.mInputMediaDevices.get(4).getId())
                .isEqualTo(String.valueOf(INPUT_USB_ACCESSORY_ID));
    }

    @Test
    public void onAudioDevicesRemoved_shouldUpdateInputMediaDevice() {
        final AudioManager audioManager = mock(AudioManager.class);
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))
                .thenReturn(new AudioDeviceInfo[] {});

        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);

        final MediaDevice device = mock(MediaDevice.class);
        inputRouteManager.mInputMediaDevices.add(device);

        final AudioDeviceInfo info = mock(AudioDeviceInfo.class);
        when(info.getType()).thenReturn(AudioDeviceInfo.TYPE_WIRED_HEADSET);
        inputRouteManager.mAudioDeviceCallback.onAudioDevicesRemoved(new AudioDeviceInfo[] {info});

        assertThat(inputRouteManager.mInputMediaDevices).isEmpty();
    }

    @Test
    public void getMaxInputGain_returnMaxInputGain() {
        assertThat(mInputRouteManager.getMaxInputGain()).isEqualTo(15);
    }

    @Test
    public void getCurrentInputGain_returnCurrentInputGain() {
        assertThat(mInputRouteManager.getCurrentInputGain()).isEqualTo(8);
    }

    @Test
    public void isInputGainFixed() {
        assertThat(mInputRouteManager.isInputGainFixed()).isTrue();
    }
}
