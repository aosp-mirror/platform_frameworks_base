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

import android.hardware.hdmi.DeviceFeatures;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.google.android.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;

/**
 * Tests for absolute volume behavior where the local device is a Playback device and the
 * System Audio device is an Audio System.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class PlaybackDeviceToAudioSystemAvbTest extends BasePlaybackDeviceAvbTest {

    @Override
    protected int getSystemAudioDeviceLogicalAddress() {
        return Constants.ADDR_AUDIO_SYSTEM;
    }

    @Override
    protected int getSystemAudioDeviceType() {
        return HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
    }

    /**
     * AVB is disabled if the Audio System disables System Audio mode, and the TV has unknown
     * support for <Set Audio Volume Level>. It is enabled once the TV confirms support for
     * <Set Audio Volume Level> and sends <Report Audio Status>.
     */
    @Test
    public void switchToTv_absoluteVolumeControlDisabledUntilAllConditionsMet() {
        enableAbsoluteVolumeBehavior();

        // Audio System disables System Audio Mode. AVB should be disabled.
        receiveSetSystemAudioMode(false);
        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);

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

        assertThat(mAudioManager.getDeviceVolumeBehavior(getAudioOutputDevice())).isEqualTo(
                AudioManager.DEVICE_VOLUME_BEHAVIOR_ABSOLUTE);
    }
}
