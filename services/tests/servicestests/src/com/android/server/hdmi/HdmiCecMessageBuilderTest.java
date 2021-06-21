/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.hdmi;

import static com.android.server.hdmi.Constants.ADDR_AUDIO_SYSTEM;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiUtils.buildMessage;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.google.android.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
/** Tests for {@link HdmiCecMessageBuilder}.. */
public class HdmiCecMessageBuilderTest {

    @Test
    public void buildReportPhysicalAddressCommand() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildReportPhysicalAddressCommand(
                        ADDR_PLAYBACK_1, 0x1234, HdmiDeviceInfo.DEVICE_PLAYBACK);
        assertThat(message).isEqualTo(buildMessage("4f:84:12:34:04"));
    }

    @Test
    public void buildRequestShortAudioDescriptor() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRequestShortAudioDescriptor(
                        ADDR_TV,
                        ADDR_AUDIO_SYSTEM,
                        new int[] {Constants.AUDIO_CODEC_AAC, Constants.AUDIO_CODEC_LPCM});
        assertThat(message).isEqualTo(buildMessage("05:A4:06:01"));
    }

    @Test
    public void buildRoutingInformation() {
        HdmiCecMessage message =
                HdmiCecMessageBuilder.buildRoutingInformation(
                        ADDR_AUDIO_SYSTEM, 0x2100);
        assertThat(message).isEqualTo(buildMessage("5F:81:21:00"));
    }

    @Test
    public void buildSetOsdName_short() {
        String deviceName = "abc";
        HdmiCecMessage message = HdmiCecMessageBuilder.buildSetOsdNameCommand(ADDR_PLAYBACK_1,
                ADDR_TV, deviceName);
        assertThat(message).isEqualTo(buildMessage("40:47:61:62:63"));
    }

    @Test
    public void buildSetOsdName_maximumLength() {
        String deviceName = "abcdefghijklmn";
        HdmiCecMessage message = HdmiCecMessageBuilder.buildSetOsdNameCommand(ADDR_PLAYBACK_1,
                ADDR_TV, deviceName);
        assertThat(message).isEqualTo(
                buildMessage("40:47:61:62:63:64:65:66:67:68:69:6A:6B:6C:6D:6E"));
    }

    @Test
    public void buildSetOsdName_tooLong() {
        String deviceName = "abcdefghijklmnop";
        HdmiCecMessage message = HdmiCecMessageBuilder.buildSetOsdNameCommand(ADDR_PLAYBACK_1,
                ADDR_TV, deviceName);
        assertThat(message).isEqualTo(
                buildMessage("40:47:61:62:63:64:65:66:67:68:69:6A:6B:6C:6D:6E"));
    }

    @Test
    public void buildGiveFeatures() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildGiveFeatures(ADDR_PLAYBACK_1, ADDR_TV);

        assertThat(message).isEqualTo(buildMessage("40:A5"));
    }

    @Test
    public void buildReportFeatures_basicTv_1_4() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportFeatures(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE), Collections.emptyList());

        assertThat(message).isEqualTo(buildMessage("0F:A6:05:80:00:00"));
    }

    @Test
    public void buildReportFeatures_basicPlayback_1_4() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportFeatures(ADDR_PLAYBACK_1,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE), Collections.emptyList());

        assertThat(message).isEqualTo(buildMessage("4F:A6:05:10:00:00"));
    }

    @Test
    public void buildReportFeatures_basicPlaybackAudioSystem_1_4() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportFeatures(ADDR_PLAYBACK_1,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK,
                        HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE), Collections.emptyList());

        assertThat(message).isEqualTo(buildMessage("4F:A6:05:18:00:00"));
    }

    @Test
    public void buildReportFeatures_basicTv_2_0() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportFeatures(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE), Collections.emptyList());

        assertThat(message).isEqualTo(buildMessage("0F:A6:06:80:00:00"));
    }

    @Test
    public void buildReportFeatures_remoteControlTv_2_0() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportFeatures(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_ONE), Collections.emptyList());

        assertThat(message).isEqualTo(buildMessage("0F:A6:06:80:02:00"));
    }

    @Test
    public void buildReportFeatures_remoteControlPlayback_2_0() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportFeatures(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK), Constants.RC_PROFILE_SOURCE,
                Lists.newArrayList(Constants.RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                        Constants.RC_PROFILE_SOURCE_HANDLES_SETUP_MENU), Collections.emptyList());

        assertThat(message).isEqualTo(buildMessage("0F:A6:06:10:4A:00"));
    }

    @Test
    public void buildReportFeatures_deviceFeaturesTv_2_0() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportFeatures(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE),
                Lists.newArrayList(Constants.DEVICE_FEATURE_TV_SUPPORTS_RECORD_TV_SCREEN));

        assertThat(message).isEqualTo(buildMessage("0F:A6:06:80:00:40"));
    }

    @Test
    public void buildReportFeatures_deviceFeaturesPlayback_2_0() {
        HdmiCecMessage message = HdmiCecMessageBuilder.buildReportFeatures(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK), Constants.RC_PROFILE_SOURCE,
                Lists.newArrayList(Constants.RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                        Constants.RC_PROFILE_SOURCE_HANDLES_SETUP_MENU),
                Lists.newArrayList(Constants.DEVICE_FEATURE_SUPPORTS_DECK_CONTROL));

        assertThat(message).isEqualTo(buildMessage("0F:A6:06:10:4A:10"));
    }
}
