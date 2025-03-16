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

import static com.android.settingslib.media.InputRouteManager.INPUT_ATTRIBUTES;
import static com.android.settingslib.media.InputRouteManager.PRESETS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;

import com.android.settingslib.testutils.shadow.ShadowRouter2Manager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowRouter2Manager.class})
public class InputRouteManagerTest {
    private static final int BUILTIN_MIC_ID = 1;
    private static final int INPUT_WIRED_HEADSET_ID = 2;
    private static final int INPUT_USB_DEVICE_ID = 3;
    private static final int INPUT_USB_HEADSET_ID = 4;
    private static final int INPUT_USB_ACCESSORY_ID = 5;
    private static final int HDMI_ID = 6;
    private static final int MAX_VOLUME = 1;
    private static final int CURRENT_VOLUME = 0;
    private static final boolean VOLUME_FIXED_TRUE = true;
    private static final String PRODUCT_NAME_BUILTIN_MIC = "Built-in Mic";
    private static final String PRODUCT_NAME_WIRED_HEADSET = "My Wired Headset";
    private static final String PRODUCT_NAME_USB_HEADSET = "My USB Headset";
    private static final String PRODUCT_NAME_USB_DEVICE = "My USB Device";
    private static final String PRODUCT_NAME_USB_ACCESSORY = "My USB Accessory";
    private static final String PRODUCT_NAME_HDMI_DEVICE = "HDMI device";

    private final Context mContext = spy(RuntimeEnvironment.application);
    private InputRouteManager mInputRouteManager;

    private AudioDeviceInfo mockBuiltinMicInfo() {
        final AudioDeviceInfo info = mock(AudioDeviceInfo.class);
        when(info.getType()).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_MIC);
        when(info.getId()).thenReturn(BUILTIN_MIC_ID);
        when(info.getAddress()).thenReturn("");
        when(info.getProductName()).thenReturn(PRODUCT_NAME_BUILTIN_MIC);
        return info;
    }

    private AudioDeviceInfo mockWiredHeadsetInfo() {
        final AudioDeviceInfo info = mock(AudioDeviceInfo.class);
        when(info.getType()).thenReturn(AudioDeviceInfo.TYPE_WIRED_HEADSET);
        when(info.getId()).thenReturn(INPUT_WIRED_HEADSET_ID);
        when(info.getAddress()).thenReturn("");
        when(info.getProductName()).thenReturn(PRODUCT_NAME_WIRED_HEADSET);
        return info;
    }

    private AudioDeviceInfo mockUsbDeviceInfo() {
        final AudioDeviceInfo info = mock(AudioDeviceInfo.class);
        when(info.getType()).thenReturn(AudioDeviceInfo.TYPE_USB_DEVICE);
        when(info.getId()).thenReturn(INPUT_USB_DEVICE_ID);
        when(info.getAddress()).thenReturn("");
        when(info.getProductName()).thenReturn(PRODUCT_NAME_USB_DEVICE);
        return info;
    }

    private AudioDeviceInfo mockUsbHeadsetInfo() {
        final AudioDeviceInfo info = mock(AudioDeviceInfo.class);
        when(info.getType()).thenReturn(AudioDeviceInfo.TYPE_USB_HEADSET);
        when(info.getId()).thenReturn(INPUT_USB_HEADSET_ID);
        when(info.getAddress()).thenReturn("");
        when(info.getProductName()).thenReturn(PRODUCT_NAME_USB_HEADSET);
        return info;
    }

    private AudioDeviceInfo mockUsbAccessoryInfo() {
        final AudioDeviceInfo info = mock(AudioDeviceInfo.class);
        when(info.getType()).thenReturn(AudioDeviceInfo.TYPE_USB_ACCESSORY);
        when(info.getId()).thenReturn(INPUT_USB_ACCESSORY_ID);
        when(info.getAddress()).thenReturn("");
        when(info.getProductName()).thenReturn(PRODUCT_NAME_USB_ACCESSORY);
        return info;
    }

    private AudioDeviceInfo mockHdmiInfo() {
        final AudioDeviceInfo info = mock(AudioDeviceInfo.class);
        when(info.getType()).thenReturn(AudioDeviceInfo.TYPE_HDMI);
        when(info.getId()).thenReturn(HDMI_ID);
        when(info.getAddress()).thenReturn("");
        when(info.getProductName()).thenReturn(PRODUCT_NAME_HDMI_DEVICE);
        return info;
    }

    private AudioDeviceAttributes getBuiltinMicDeviceAttributes() {
        return new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_INPUT,
                AudioDeviceInfo.TYPE_BUILTIN_MIC,
                /* address= */ "");
    }

    private AudioDeviceAttributes getWiredHeadsetDeviceAttributes() {
        return new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_INPUT,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                /* address= */ "");
    }

    private AudioDeviceAttributes getUsbHeadsetDeviceAttributes() {
        return new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_INPUT,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                /* address= */ "");
    }

    private AudioDeviceAttributes getHdmiDeviceAttributes() {
        return new AudioDeviceAttributes(
                AudioDeviceAttributes.ROLE_INPUT, AudioDeviceInfo.TYPE_HDMI, /* address= */ "");
    }

    private void onPreferredDevicesForCapturePresetChanged(InputRouteManager inputRouteManager) {
        final List<AudioDeviceAttributes> audioDeviceAttributesList =
                new ArrayList<AudioDeviceAttributes>();
        inputRouteManager.onPreferredDevicesForCapturePresetChangedListener(
                MediaRecorder.AudioSource.MIC, audioDeviceAttributesList);
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final AudioManager audioManager = mock(AudioManager.class);
        mInputRouteManager = new InputRouteManager(mContext, audioManager);
    }

    @Test
    public void onAudioDevicesAdded_shouldUpdateInputMediaDevice() {
        final AudioManager audioManager = mock(AudioManager.class);
        AudioDeviceInfo[] devices = {
            mockBuiltinMicInfo(),
            mockWiredHeadsetInfo(),
            mockUsbDeviceInfo(),
            mockUsbHeadsetInfo(),
            mockUsbAccessoryInfo(),
            mockHdmiInfo()
        };
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(devices);

        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);

        assertThat(inputRouteManager.mInputMediaDevices).isEmpty();

        inputRouteManager.mAudioDeviceCallback.onAudioDevicesAdded(devices);
        onPreferredDevicesForCapturePresetChanged(inputRouteManager);

        // The unsupported (hdmi) info should be filtered out.
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

        inputRouteManager.mAudioDeviceCallback.onAudioDevicesRemoved(
                new AudioDeviceInfo[] {mockWiredHeadsetInfo()});
        onPreferredDevicesForCapturePresetChanged(inputRouteManager);

        assertThat(inputRouteManager.mInputMediaDevices).isEmpty();
    }

    @Test
    public void getSelectedInputDevice_returnOneFromAudioManager() {
        final AudioManager audioManager = mock(AudioManager.class);
        AudioDeviceInfo[] devices = {mockWiredHeadsetInfo(), mockBuiltinMicInfo()};
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(devices);

        // Mock audioManager.getDevicesForAttributes returns exactly one audioDeviceAttributes.
        when(audioManager.getDevicesForAttributes(INPUT_ATTRIBUTES))
                .thenReturn(Collections.singletonList(getWiredHeadsetDeviceAttributes()));

        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);
        onPreferredDevicesForCapturePresetChanged(inputRouteManager);

        // The selected input device has the same type as the one returned from AudioManager.
        InputMediaDevice selectedInputDevice =
                (InputMediaDevice) inputRouteManager.getSelectedInputDevice();
        assertThat(selectedInputDevice.getAudioDeviceInfoType())
                .isEqualTo(AudioDeviceInfo.TYPE_WIRED_HEADSET);
    }

    @Test
    public void getSelectedInputDevice_returnMoreThanOneFromAudioManager() {
        final AudioManager audioManager = mock(AudioManager.class);
        AudioDeviceInfo[] devices = {mockWiredHeadsetInfo(), mockBuiltinMicInfo()};
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(devices);

        // Mock audioManager.getDevicesForAttributes returns more than one audioDeviceAttributes.
        List<AudioDeviceAttributes> attributesOfSelectedInputDevices = new ArrayList<>();
        attributesOfSelectedInputDevices.add(getWiredHeadsetDeviceAttributes());
        attributesOfSelectedInputDevices.add(getBuiltinMicDeviceAttributes());
        when(audioManager.getDevicesForAttributes(INPUT_ATTRIBUTES))
                .thenReturn(attributesOfSelectedInputDevices);

        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);
        onPreferredDevicesForCapturePresetChanged(inputRouteManager);

        // The selected input device has the same type as the first one returned from AudioManager.
        InputMediaDevice selectedInputDevice =
                (InputMediaDevice) inputRouteManager.getSelectedInputDevice();
        assertThat(selectedInputDevice.getAudioDeviceInfoType())
                .isEqualTo(AudioDeviceInfo.TYPE_WIRED_HEADSET);
    }

    @Test
    public void getSelectedInputDevice_returnEmptyFromAudioManager() {
        final AudioManager audioManager = mock(AudioManager.class);
        AudioDeviceInfo[] devices = {mockWiredHeadsetInfo(), mockBuiltinMicInfo()};
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(devices);

        // Mock audioManager.getDevicesForAttributes returns empty list of audioDeviceAttributes.
        List<AudioDeviceAttributes> emptyAttributesOfSelectedInputDevices = new ArrayList<>();
        when(audioManager.getDevicesForAttributes(INPUT_ATTRIBUTES))
                .thenReturn(emptyAttributesOfSelectedInputDevices);

        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);
        onPreferredDevicesForCapturePresetChanged(inputRouteManager);

        // The selected input device has default type AudioDeviceInfo.TYPE_BUILTIN_MIC.
        InputMediaDevice selectedInputDevice =
                (InputMediaDevice) inputRouteManager.getSelectedInputDevice();
        assertThat(selectedInputDevice.getAudioDeviceInfoType())
                .isEqualTo(AudioDeviceInfo.TYPE_BUILTIN_MIC);
    }

    @Test
    public void selectDevice() {
        final AudioManager audioManager = mock(AudioManager.class);
        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);
        final MediaDevice builtinMicDevice =
                InputMediaDevice.create(
                        mContext,
                        String.valueOf(BUILTIN_MIC_ID),
                        AudioDeviceInfo.TYPE_BUILTIN_MIC,
                        MAX_VOLUME,
                        CURRENT_VOLUME,
                        VOLUME_FIXED_TRUE,
                        PRODUCT_NAME_BUILTIN_MIC);
        inputRouteManager.selectDevice(builtinMicDevice);

        for (@MediaRecorder.Source int preset : PRESETS) {
            verify(audioManager, atLeastOnce())
                    .setPreferredDeviceForCapturePreset(preset, getBuiltinMicDeviceAttributes());
        }
    }

    @Test
    public void onInitiation_shouldApplyDefaultSelectedDeviceToAllPresets() {
        final AudioManager audioManager = mock(AudioManager.class);
        new InputRouteManager(mContext, audioManager);

        verify(audioManager, atLeastOnce()).getDevicesForAttributes(INPUT_ATTRIBUTES);
        for (@MediaRecorder.Source int preset : PRESETS) {
            verify(audioManager, atLeastOnce())
                    .setPreferredDeviceForCapturePreset(preset, getBuiltinMicDeviceAttributes());
        }
    }

    @Test
    public void onAudioDevicesAdded_shouldActivateAddedDevice() {
        final AudioManager audioManager = mock(AudioManager.class);
        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);
        AudioDeviceInfo[] devices = {mockWiredHeadsetInfo()};
        inputRouteManager.mAudioDeviceCallback.onAudioDevicesAdded(devices);

        // The only added wired headset will be activated.
        for (@MediaRecorder.Source int preset : PRESETS) {
            verify(audioManager, atLeast(1))
                    .setPreferredDeviceForCapturePreset(preset, getWiredHeadsetDeviceAttributes());
        }
    }

    @Test
    public void onAudioDevicesAdded_shouldActivateLastAddedDevice() {
        final AudioManager audioManager = mock(AudioManager.class);
        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);
        AudioDeviceInfo[] devices = {mockWiredHeadsetInfo(), mockUsbHeadsetInfo()};
        inputRouteManager.mAudioDeviceCallback.onAudioDevicesAdded(devices);

        // When adding multiple valid input devices, the last added device (usb headset in this
        // case) will be activated.
        for (@MediaRecorder.Source int preset : PRESETS) {
            verify(audioManager, never())
                    .setPreferredDeviceForCapturePreset(preset, getWiredHeadsetDeviceAttributes());
            verify(audioManager, atLeast(1))
                    .setPreferredDeviceForCapturePreset(preset, getUsbHeadsetDeviceAttributes());
        }
    }

    @Test
    public void onAudioDevicesAdded_doNotActivateInvalidAddedDevice() {
        final AudioManager audioManager = mock(AudioManager.class);
        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);
        AudioDeviceInfo[] devices = {mockHdmiInfo()};
        inputRouteManager.mAudioDeviceCallback.onAudioDevicesAdded(devices);

        // Do not activate since HDMI is not a valid input device.
        for (@MediaRecorder.Source int preset : PRESETS) {
            verify(audioManager, never())
                    .setPreferredDeviceForCapturePreset(preset, getHdmiDeviceAttributes());
        }
    }

    @Test
    public void onAudioDevicesAdded_doNotActivatePreexistingDevice() {
        final AudioManager audioManager = mock(AudioManager.class);
        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);

        final AudioDeviceInfo info = mockWiredHeadsetInfo();
        InputMediaDevice device = createInputMediaDeviceFromDeviceInfo(info);
        inputRouteManager.mInputMediaDevices.add(device);

        // Trigger onAudioDevicesAdded with a device that already exists in the device list.
        AudioDeviceInfo[] devices = {info};
        inputRouteManager.mAudioDeviceCallback.onAudioDevicesAdded(devices);

        // The device should not be activated.
        for (@MediaRecorder.Source int preset : PRESETS) {
            verify(audioManager, never())
                    .setPreferredDeviceForCapturePreset(preset, getWiredHeadsetDeviceAttributes());
        }
    }

    @Test
    public void onAudioDevicesRemoved_shouldApplyDefaultSelectedDeviceToAllPresets() {
        final AudioManager audioManager = mock(AudioManager.class);
        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);
        AudioDeviceInfo[] devices = {mockWiredHeadsetInfo()};
        inputRouteManager.mAudioDeviceCallback.onAudioDevicesRemoved(devices);

        // Called twice, one after initiation, the other after onAudioDevicesRemoved call.
        verify(audioManager, atLeast(2)).getDevicesForAttributes(INPUT_ATTRIBUTES);
        for (@MediaRecorder.Source int preset : PRESETS) {
            verify(audioManager, atLeast(2))
                    .setPreferredDeviceForCapturePreset(preset, getBuiltinMicDeviceAttributes());
        }
    }

    @Test
    public void getMaxInputGain_returnMaxInputGain() {
        assertThat(mInputRouteManager.getMaxInputGain()).isEqualTo(100);
    }

    @Test
    public void getCurrentInputGain_returnCurrentInputGain() {
        assertThat(mInputRouteManager.getCurrentInputGain()).isEqualTo(100);
    }

    @Test
    public void isInputGainFixed() {
        assertThat(mInputRouteManager.isInputGainFixed()).isTrue();
    }

    @Test
    public void onAudioDevicesAdded_shouldSetProductNameCorrectly() {
        final AudioDeviceInfo info1 = mockWiredHeadsetInfo();
        String firstProductName = "My first headset";
        when(info1.getProductName()).thenReturn(firstProductName);
        InputMediaDevice inputMediaDevice1 = createInputMediaDeviceFromDeviceInfo(info1);

        final AudioDeviceInfo info2 = mockWiredHeadsetInfo();
        String secondProductName = "My second headset";
        when(info2.getProductName()).thenReturn(secondProductName);
        InputMediaDevice inputMediaDevice2 = createInputMediaDeviceFromDeviceInfo(info2);

        final AudioDeviceInfo infoWithNullProductName = mockWiredHeadsetInfo();
        when(infoWithNullProductName.getProductName()).thenReturn(null);
        InputMediaDevice inputMediaDevice3 =
                createInputMediaDeviceFromDeviceInfo(infoWithNullProductName);

        final AudioDeviceInfo infoWithBlankProductName = mockWiredHeadsetInfo();
        when(infoWithBlankProductName.getProductName()).thenReturn("");
        InputMediaDevice inputMediaDevice4 =
                createInputMediaDeviceFromDeviceInfo(infoWithBlankProductName);

        final AudioManager audioManager = mock(AudioManager.class);
        AudioDeviceInfo[] devices = {
            info1, info2, infoWithNullProductName, infoWithBlankProductName
        };
        when(audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)).thenReturn(devices);

        InputRouteManager inputRouteManager = new InputRouteManager(mContext, audioManager);

        assertThat(inputRouteManager.mInputMediaDevices).isEmpty();

        inputRouteManager.mAudioDeviceCallback.onAudioDevicesAdded(devices);
        onPreferredDevicesForCapturePresetChanged(inputRouteManager);

        assertThat(inputRouteManager.mInputMediaDevices)
                .containsExactly(
                        inputMediaDevice1, inputMediaDevice2, inputMediaDevice3, inputMediaDevice4)
                .inOrder();
    }

    private InputMediaDevice createInputMediaDeviceFromDeviceInfo(AudioDeviceInfo info) {
        return InputMediaDevice.create(
                mContext,
                String.valueOf(info.getId()),
                info.getType(),
                MAX_VOLUME,
                CURRENT_VOLUME,
                VOLUME_FIXED_TRUE,
                info.getProductName() == null ? null : info.getProductName().toString());
    }
}
