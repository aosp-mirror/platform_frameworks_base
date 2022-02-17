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

package com.android.server.hdmi;

import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.android.server.hdmi.Constants.ADDR_TV;
import static com.android.server.hdmi.HdmiCecMessageValidator.ERROR_DESTINATION;
import static com.android.server.hdmi.HdmiCecMessageValidator.ERROR_PARAMETER_SHORT;
import static com.android.server.hdmi.HdmiCecMessageValidator.ERROR_SOURCE;
import static com.android.server.hdmi.HdmiCecMessageValidator.OK;
import static com.android.server.hdmi.HdmiUtils.buildMessage;

import static com.google.common.truth.Truth.assertThat;

import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.google.android.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class ReportFeaturesMessageTest {
    @Test
    public void build_invalidMessages() {
        assertThat(HdmiUtils.buildMessage("FF:A6:05:80:00:00")
                .getValidationResult()).isEqualTo(ERROR_SOURCE);
        assertThat(HdmiUtils.buildMessage("04:A6:05:80:00:00")
                .getValidationResult()).isEqualTo(ERROR_DESTINATION);
        assertThat(HdmiUtils.buildMessage("0F:A6")
                .getValidationResult()).isEqualTo(ERROR_PARAMETER_SHORT);
        assertThat(HdmiUtils.buildMessage("4F:A6:06:00:80:80:00")
                .getValidationResult()).isEqualTo(ERROR_PARAMETER_SHORT);
    }

    @Test
    public void build_longMessage() {
        HdmiCecMessage longMessage = HdmiUtils.buildMessage("4F:A6:05:00:80:80:00:81:80:00");
        assertThat(longMessage).isInstanceOf(ReportFeaturesMessage.class);
        ReportFeaturesMessage longReportFeaturesMessage = (ReportFeaturesMessage) longMessage;

        HdmiCecMessage shortMessage = HdmiUtils.buildMessage("4F:A6:05:00:00:01");
        assertThat(shortMessage).isInstanceOf(ReportFeaturesMessage.class);
        ReportFeaturesMessage shortReportFeaturesMessage = (ReportFeaturesMessage) shortMessage;

        assertThat(longReportFeaturesMessage.getDeviceFeatures()).isEqualTo(
                shortReportFeaturesMessage.getDeviceFeatures());
        assertThat(longReportFeaturesMessage.getCecVersion()).isEqualTo(
                shortReportFeaturesMessage.getCecVersion());
    }

    @Test
    public void build_basicTv_1_4() {
        HdmiCecMessage message = ReportFeaturesMessage.build(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE),
                DeviceFeatures.NO_FEATURES_SUPPORTED);

        assertThat(message.getValidationResult()).isEqualTo(OK);
        assertThat(message).isEqualTo(buildMessage("0F:A6:05:80:00:00"));
    }

    @Test
    public void build_basicPlayback_1_4() {
        HdmiCecMessage message = ReportFeaturesMessage.build(ADDR_PLAYBACK_1,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE),
                DeviceFeatures.NO_FEATURES_SUPPORTED);

        assertThat(message.getValidationResult()).isEqualTo(OK);
        assertThat(message).isEqualTo(buildMessage("4F:A6:05:10:00:00"));
    }

    @Test
    public void build_basicPlaybackAudioSystem_1_4() {
        HdmiCecMessage message = ReportFeaturesMessage.build(ADDR_PLAYBACK_1,
                HdmiControlManager.HDMI_CEC_VERSION_1_4_B,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK,
                        HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE),
                DeviceFeatures.NO_FEATURES_SUPPORTED);

        assertThat(message.getValidationResult()).isEqualTo(OK);
        assertThat(message).isEqualTo(buildMessage("4F:A6:05:18:00:00"));
    }

    @Test
    public void build_basicTv_2_0() {
        HdmiCecMessage message = ReportFeaturesMessage.build(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE),
                DeviceFeatures.NO_FEATURES_SUPPORTED);

        assertThat(message.getValidationResult()).isEqualTo(OK);
        assertThat(message).isEqualTo(buildMessage("0F:A6:06:80:00:00"));
    }

    @Test
    public void build_remoteControlTv_2_0() {
        HdmiCecMessage message = ReportFeaturesMessage.build(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_ONE),
                DeviceFeatures.NO_FEATURES_SUPPORTED);

        assertThat(message.getValidationResult()).isEqualTo(OK);
        assertThat(message).isEqualTo(buildMessage("0F:A6:06:80:02:00"));
    }

    @Test
    public void build_remoteControlPlayback_2_0() {
        HdmiCecMessage message = ReportFeaturesMessage.build(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK), Constants.RC_PROFILE_SOURCE,
                Lists.newArrayList(Constants.RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                        Constants.RC_PROFILE_SOURCE_HANDLES_SETUP_MENU),
                DeviceFeatures.NO_FEATURES_SUPPORTED);

        assertThat(message.getValidationResult()).isEqualTo(OK);
        assertThat(message).isEqualTo(buildMessage("0F:A6:06:10:4A:00"));
    }

    @Test
    public void build_deviceFeaturesTv_2_0() {
        HdmiCecMessage message = ReportFeaturesMessage.build(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE),
                DeviceFeatures.NO_FEATURES_SUPPORTED.toBuilder()
                        .setRecordTvScreenSupport(DeviceFeatures.FEATURE_SUPPORTED)
                        .build());

        assertThat(message.getValidationResult()).isEqualTo(OK);
        assertThat(message).isEqualTo(buildMessage("0F:A6:06:80:00:40"));
    }

    @Test
    public void build_deviceFeaturesPlayback_2_0() {
        HdmiCecMessage message = ReportFeaturesMessage.build(ADDR_TV,
                HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Lists.newArrayList(HdmiDeviceInfo.DEVICE_PLAYBACK), Constants.RC_PROFILE_SOURCE,
                Lists.newArrayList(Constants.RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
                        Constants.RC_PROFILE_SOURCE_HANDLES_SETUP_MENU),
                DeviceFeatures.NO_FEATURES_SUPPORTED.toBuilder()
                        .setDeckControlSupport(DeviceFeatures.FEATURE_SUPPORTED)
                        .build());

        assertThat(message.getValidationResult()).isEqualTo(OK);
        assertThat(message).isEqualTo(buildMessage("0F:A6:06:10:4A:10"));
    }
}
