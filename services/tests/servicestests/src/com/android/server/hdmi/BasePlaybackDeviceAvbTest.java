/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.media.AudioManager;

import org.junit.Test;

/**
 * Base class for tests for absolute volume behavior on Playback devices. Contains tests that are
 * relevant to Playback devices but not to TVs.
 *
 * Subclasses contain tests for the following pairs of (local device, System Audio device):
 * (Playback, TV): {@link PlaybackDeviceToTvAvbTest}
 * (Playback, Audio System): {@link PlaybackDeviceToAudioSystemAvbTest}
 */
public abstract class BasePlaybackDeviceAvbTest extends BaseAbsoluteVolumeBehaviorTest {

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

    /**
     * Unlike TVs, Playback devices don't start the process for adopting adjust-only AVB
     * if the System Audio device doesn't support <Set Audio Volume Level>
     */
    @Test
    public void savlNotSupported_allOtherConditionsMet_giveAudioStatusNotSent() {
        mAudioManager.setDeviceVolumeBehavior(getAudioOutputDevice(),
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL);
        setCecVolumeControlSetting(HdmiControlManager.VOLUME_CONTROL_ENABLED);
        enableSystemAudioModeIfNeeded();

        receiveSetAudioVolumeLevelSupport(DeviceFeatures.FEATURE_NOT_SUPPORTED);
        verifyGiveAudioStatusNeverSent();
    }
}
