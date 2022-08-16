/*
 * Copyright 2022 The Android Open Source Project
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

import android.media.AudioDeviceInfo;
import android.media.MediaRoute2Info;

import com.android.settingslib.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class DeviceIconUtilTest {
    private final DeviceIconUtil mDeviceIconUtil = new DeviceIconUtil();

    @Test
    public void getIconResIdFromMediaRouteType_usbDevice_isHeadphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_DEVICE))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_usbHeadset_isHeadphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_HEADSET))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_usbAccessory_isHeadphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_ACCESSORY))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_dock_isHeadphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_DOCK))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_hdmi_isHeadphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_HDMI))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_wiredHeadset_isHeadphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_WIRED_HEADSET))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_wiredHeadphones_isHeadphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_WIRED_HEADPHONES))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_builtinSpeaker_isSmartphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_BUILTIN_SPEAKER))
            .isEqualTo(R.drawable.ic_smartphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_unsupportedType_isSmartphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_UNKNOWN))
            .isEqualTo(R.drawable.ic_smartphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_usbDevice_isHeadphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_DEVICE))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_usbHeadset_isHeadphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_HEADSET))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_usbAccessory_isHeadphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_ACCESSORY))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_dock_isHeadphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_DOCK))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_hdmi_isHeadphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_HDMI))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_wiredHeadset_isHeadphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_wiredHeadphones_isHeadphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
            .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_builtinSpeaker_isSmartphone() {
        assertThat(
            mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
            .isEqualTo(R.drawable.ic_smartphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_unsupportedType_isSmartphone() {
        assertThat(mDeviceIconUtil.getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_UNKNOWN))
            .isEqualTo(R.drawable.ic_smartphone);
    }
}
