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

import static org.mockito.Mockito.clearInvocations;

import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDeviceAttributes;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

/**
 * Tests for Absolute Volume Control where the local device is a Playback device and the
 * System Audio device is a TV.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class PlaybackDeviceToTvAvcTest extends BaseAbsoluteVolumeControlTest {

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
        return Constants.ADDR_TV;
    }

    @Override
    protected int getSystemAudioDeviceType() {
        return HdmiDeviceInfo.DEVICE_TV;
    }

    /**
     * AVC is disabled when an Audio System with unknown support for <Set Audio Volume Level>
     * becomes the System Audio device. It is enabled once the Audio System reports that it
     * supports <Set Audio Volume Level> and sends <Report Audio Status>.
     */
    @Test
    public void switchToAudioSystem_absoluteVolumeControlDisabledUntilAllConditionsMet() {
        enableAbsoluteVolumeControl();

        // Audio System enables System Audio Mode. AVC should be disabled.
        receiveSetSystemAudioMode(true);
        verifyAbsoluteVolumeDisabled();

        clearInvocations(mAudioManager, mAudioDeviceVolumeManager);

        // Audio System reports support for <Set Audio Volume Level>
        mNativeWrapper.onCecMessage(ReportFeaturesMessage.build(
                Constants.ADDR_AUDIO_SYSTEM, HdmiControlManager.HDMI_CEC_VERSION_2_0,
                Arrays.asList(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM), Constants.RC_PROFILE_SOURCE,
                Collections.emptyList(),
                DeviceFeatures.NO_FEATURES_SUPPORTED.toBuilder()
                        .setSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_SUPPORTED)
                        .build()));
        mTestLooper.dispatchAll();

        // Audio system reports its initial audio status
        mNativeWrapper.onCecMessage(HdmiCecMessageBuilder.buildReportAudioStatus(
                Constants.ADDR_AUDIO_SYSTEM,
                getLogicalAddress(),
                30,
                false));
        mTestLooper.dispatchAll();

        verifyAbsoluteVolumeEnabled();
    }
}
