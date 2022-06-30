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

package com.android.server.hdmi;

import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDeviceAttributes;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.google.android.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;

/**
 * Tests for Absolute Volume Control where the local device is a Playback device and the
 * System Audio device is an Audio System.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class PlaybackDeviceToAudioSystemAvcTest extends BaseAbsoluteVolumeControlTest {

    @Override
    protected HdmiCecLocalDevice createLocalDevice(HdmiControlService hdmiControlService) {
        return new HdmiCecLocalDevicePlayback(hdmiControlService);
    }

    @Override
    protected int getPhysicalAddress() {
        return 0x1100;
    }

    @Override
    protected int getDeviceType() {
        return HdmiDeviceInfo.DEVICE_PLAYBACK;
    }

    @Override
    protected AudioDeviceAttributes getAudioOutputDevice() {
        return HdmiControlService.AUDIO_OUTPUT_DEVICE_HDMI;
    }

    @Override
    protected int getSystemAudioDeviceLogicalAddress() {
        return Constants.ADDR_AUDIO_SYSTEM;
    }

    @Override
    protected int getSystemAudioDeviceType() {
        return HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
    }

    /**
     * AVC is disabled if the Audio System disables System Audio mode, and the TV has unknown
     * support for <Set Audio Volume Level>. It is enabled once the TV confirms support for
     * <Set Audio Volume Level> and sends <Report Audio Status>.
     */
    @Test
    public void switchToTv_absoluteVolumeControlDisabledUntilAllConditionsMet() {
        enableAbsoluteVolumeControl();

        // Audio System disables System Audio Mode. AVC should be disabled.
        receiveSetSystemAudioMode(false);
        verifyAbsoluteVolumeDisabled();

        // TV reports support for <Set Audio Volume Level>
        mNativeWrapper.onCecMessage(ReportFeaturesMessage.build(
                Constants.ADDR_TV, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(HdmiDeviceInfo.DEVICE_TV), Constants.RC_PROFILE_TV,
                Lists.newArrayList(Constants.RC_PROFILE_TV_NONE),
                DeviceFeatures.NO_FEATURES_SUPPORTED.toBuilder()
                        .setSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED)
                        .build()));
        mTestLooper.dispatchAll();

        // TV reports its initial audio status
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildReportAudioStatus(
                Constants.ADDR_TV,
                getLogicalAddress(),
                30,
                false));
        mTestLooper.dispatchAll();

        verifyAbsoluteVolumeEnabled();
    }
}
