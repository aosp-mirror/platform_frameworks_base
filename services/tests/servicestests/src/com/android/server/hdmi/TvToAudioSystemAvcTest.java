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

import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioDeviceAttributes;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for Absolute Volume Control where the local device is a TV and the System Audio device
 * is an Audio System. Assumes that the TV uses ARC (rather than eARC).
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class TvToAudioSystemAvcTest extends BaseAbsoluteVolumeControlTest {

    @Override
    protected HdmiCecLocalDevice createLocalDevice(HdmiControlService hdmiControlService) {
        return new HdmiCecLocalDeviceTv(hdmiControlService);
    }

    @Override
    protected int getPhysicalAddress() {
        return 0x0000;
    }

    @Override
    protected int getDeviceType() {
        return HdmiDeviceInfo.DEVICE_TV;
    }

    @Override
    protected AudioDeviceAttributes getAudioOutputDevice() {
        return HdmiControlService.AUDIO_OUTPUT_DEVICE_HDMI_ARC;
    }

    @Override
    protected int getSystemAudioDeviceLogicalAddress() {
        return Constants.ADDR_AUDIO_SYSTEM;
    }

    @Override
    protected int getSystemAudioDeviceType() {
        return HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM;
    }
}
