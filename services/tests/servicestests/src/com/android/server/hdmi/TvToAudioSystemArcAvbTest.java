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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.media.AudioDeviceAttributes;
import android.media.AudioManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Collections;

/**
 * Tests for absolute volume behavior where the local device is a TV panel,
 * System Audio device is an Audio System, and they are connected via ARC.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class TvToAudioSystemArcAvbTest extends BaseTvToAudioSystemAvbTest {

    @Override
    protected AudioDeviceAttributes getAudioOutputDevice() {
        return HdmiControlService.AUDIO_OUTPUT_DEVICE_HDMI_ARC;
    }

    @Test
    public void switchAudioOutputDeviceFromArcToEarc_volumeAdjusted_updatesAudioService() {
        enableAbsoluteVolumeBehavior();
        mNativeWrapper.clearResultMessages();

        mAudioFramework.setDevicesForAttributes(HdmiControlService.STREAM_MUSIC_ATTRIBUTES,
                Collections.singletonList(HdmiControlService.AUDIO_OUTPUT_DEVICE_HDMI_EARC));

        receiveReportAudioStatus(20, true);
        verify(mAudioManager).setStreamVolume(eq(AudioManager.STREAM_MUSIC), eq(5),
                anyInt());
        verify(mAudioManager).adjustStreamVolume(eq(AudioManager.STREAM_MUSIC),
                eq(AudioManager.ADJUST_MUTE), anyInt());
    }
}
