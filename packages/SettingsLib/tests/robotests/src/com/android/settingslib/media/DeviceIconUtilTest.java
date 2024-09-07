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

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.MediaRoute2Info;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.settingslib.R;
import com.android.settingslib.media.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowSystemProperties;

@RunWith(RobolectricTestRunner.class)
public class DeviceIconUtilTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;

    @Before
    public void setup() {
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_TV_MEDIA_OUTPUT_DIALOG);
        mContext = RuntimeEnvironment.getApplication();
    }

    @Test
    public void getIconResIdFromMediaRouteType_usbDevice_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_DEVICE))
                .isEqualTo(R.drawable.ic_headphone);
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_DEVICE))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_usbHeadset_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_HEADSET))
                .isEqualTo(R.drawable.ic_headphone);
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_HEADSET))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_usbAccessory_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_ACCESSORY))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_tv_usbAccessory_isUsb() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_USB_ACCESSORY))
                .isEqualTo(R.drawable.ic_usb);
    }

    @Test
    public void getIconResIdFromMediaRouteType_dock_isDock() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_DOCK))
                .isEqualTo(R.drawable.ic_dock_device);
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_DOCK))
                .isEqualTo(R.drawable.ic_dock_device);
    }

    @Test
    public void getIconResIdFromMediaRouteType_hdmi() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_HDMI))
                .isEqualTo(R.drawable.ic_external_display);
    }

    @Test
    public void getIconResIdFromMediaRouteType_tv_hdmi_isTv() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_HDMI))
                .isEqualTo(R.drawable.ic_tv);
    }

    @Test
    public void getIconResIdFromMediaRouteType_hdmiArc_isExternalDisplay() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_HDMI_ARC))
                .isEqualTo(R.drawable.ic_external_display);
    }

    @Test
    public void getIconResIdFromMediaRouteType_tv_hdmiArc_isHdmi() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_HDMI_ARC))
                .isEqualTo(R.drawable.ic_hdmi);
    }

    @Test
    public void getIconResIdFromMediaRouteType_hdmiEarc_isExternalDisplay() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_HDMI_EARC))
                .isEqualTo(R.drawable.ic_external_display);
    }

    @Test
    public void getIconResIdFromMediaRouteType_tv_hdmiEarc_isHdmi() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_HDMI_EARC))
                .isEqualTo(R.drawable.ic_hdmi);
    }

    @Test
    public void getIconResIdFromMediaRouteType_wiredHeadset_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_WIRED_HEADSET))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_tv_wiredHeadset_isWiredDevice() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_WIRED_HEADSET))
                .isEqualTo(R.drawable.ic_wired_device);
    }

    @Test
    public void getIconResIdFromMediaRouteType_wiredHeadphones_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_WIRED_HEADPHONES))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_tv_wiredHeadphones_isWiredDevice() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_WIRED_HEADPHONES))
                .isEqualTo(R.drawable.ic_wired_device);
    }

    @Test
    public void getIconResIdFromMediaRouteType_builtinSpeaker_isSmartphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_BUILTIN_SPEAKER))
                .isEqualTo(R.drawable.ic_smartphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_tv_builtinSpeaker_isTv() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_BUILTIN_SPEAKER))
                .isEqualTo(R.drawable.ic_tv);
    }

    @Test
    public void getIconResIdFromMediaRouteType_onTablet_builtinSpeaker_isTablet() {
        ShadowSystemProperties.override("ro.build.characteristics", "tablet");
        assertThat(new DeviceIconUtil(mContext)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_BUILTIN_SPEAKER))
                .isEqualTo(R.drawable.ic_media_tablet);
    }

    @Test
    public void getIconResIdFromMediaRouteType_unsupportedType_isSmartphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_UNKNOWN))
                .isEqualTo(R.drawable.ic_smartphone);
    }

    @Test
    public void getIconResIdFromMediaRouteType_onTablet_unsupportedType_isTablet() {
        ShadowSystemProperties.override("ro.build.characteristics", "tablet");
        assertThat(new DeviceIconUtil(mContext)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_UNKNOWN))
                .isEqualTo(R.drawable.ic_media_tablet);
    }

    @Test
    public void getIconResIdFromMediaRouteType_tv_unsupportedType_isSpeaker() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromMediaRouteType(MediaRoute2Info.TYPE_UNKNOWN))
                .isEqualTo(R.drawable.ic_media_speaker_device);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_usbDevice_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_DEVICE))
                .isEqualTo(R.drawable.ic_headphone);
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_DEVICE))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_usbHeadset_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_HEADSET))
                .isEqualTo(R.drawable.ic_headphone);
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_HEADSET))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_usbAccessory_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_ACCESSORY))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_tv_usbAccessory_isUsb() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_USB_ACCESSORY))
                .isEqualTo(R.drawable.ic_usb);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_dock_isDock() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_DOCK))
                .isEqualTo(R.drawable.ic_dock_device);
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_DOCK))
                .isEqualTo(R.drawable.ic_dock_device);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_hdmi_isExternalDisplay() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_HDMI))
                .isEqualTo(R.drawable.ic_external_display);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_tv_hdmi_isTv() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_HDMI))
                .isEqualTo(R.drawable.ic_tv);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_hdmiArc_isExternalDisplay() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_HDMI_ARC))
                .isEqualTo(R.drawable.ic_external_display);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_hdmiArc_isHdmi() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_HDMI_ARC))
                .isEqualTo(R.drawable.ic_hdmi);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_hdmiEarc_isExternalDisplay() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_HDMI_EARC))
                .isEqualTo(R.drawable.ic_external_display);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_tv_hdmiEarc() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_HDMI_EARC))
                .isEqualTo(R.drawable.ic_hdmi);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_wiredHeadset_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_tv_wiredHeadset_isWiredDevice() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
                .isEqualTo(R.drawable.ic_wired_device);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_wiredHeadphones_isHeadphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
                .isEqualTo(R.drawable.ic_headphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_tv_wiredHeadphones_isWiredDevice() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
                .isEqualTo(R.drawable.ic_wired_device);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_builtinSpeaker_isSmartphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
                .isEqualTo(R.drawable.ic_smartphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_tv_builtinSpeaker_isTv() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
                .isEqualTo(R.drawable.ic_tv);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_unsupportedType_isSmartphone() {
        assertThat(new DeviceIconUtil(/* isTv */ false)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_UNKNOWN))
                .isEqualTo(R.drawable.ic_smartphone);
    }

    @Test
    public void getIconResIdFromAudioDeviceType_tv_unsupportedType_isSpeaker() {
        assertThat(new DeviceIconUtil(/* isTv */ true)
                .getIconResIdFromAudioDeviceType(AudioDeviceInfo.TYPE_UNKNOWN))
                .isEqualTo(R.drawable.ic_media_speaker_device);
    }
}
