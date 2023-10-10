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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.clearInvocations;

import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.Collections;

/**
 * Tests for absolute volume behavior where the local device is a Playback device and the
 * System Audio device is a TV.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class PlaybackDeviceToTvAvbTest extends BasePlaybackDeviceAvbTest {

    @Override
    protected int getSystemAudioDeviceLogicalAddress() {
        return Constants.ADDR_TV;
    }

    @Override
    protected int getSystemAudioDeviceType() {
        return HdmiDeviceInfo.DEVICE_TV;
    }

    /**
     * AVB is disabled when an Audio System with unknown support for <Set Audio Volume Level>
     * becomes the System Audio device. It is enabled once the Audio System reports that it
     * supports <Set Audio Volume Level> and sends <Report Audio Status>.
     */
    @Test
    public void switchToAudioSystem_absoluteVolumeControlDisabledUntilAllConditionsMet() {
        enableAbsoluteVolumeBehavior();

        // Audio System enables System Audio Mode. AVB should be disabled.
        receiveSetSystemAudioMode(true);
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);

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

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
    }
}
