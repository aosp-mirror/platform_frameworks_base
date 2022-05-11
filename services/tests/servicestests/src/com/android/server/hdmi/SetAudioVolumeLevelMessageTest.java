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

import static com.android.server.hdmi.HdmiCecMessageValidator.ERROR_DESTINATION;
import static com.android.server.hdmi.HdmiCecMessageValidator.ERROR_PARAMETER_SHORT;
import static com.android.server.hdmi.HdmiCecMessageValidator.ERROR_SOURCE;
import static com.android.server.hdmi.HdmiUtils.buildMessage;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class SetAudioVolumeLevelMessageTest {
    @Test
    public void build_maxVolume() {
        HdmiCecMessage message = SetAudioVolumeLevelMessage.build(
                Constants.ADDR_TV, Constants.ADDR_PLAYBACK_1, 100);
        assertThat(message.getValidationResult()).isEqualTo(HdmiCecMessageValidator.OK);
        assertThat(message).isEqualTo(buildMessage("04:73:64"));
    }

    @Test
    public void build_noVolumeChange() {
        HdmiCecMessage message = SetAudioVolumeLevelMessage.build(
                Constants.ADDR_TV, Constants.ADDR_AUDIO_SYSTEM, 0x7F);
        assertThat(message.getValidationResult()).isEqualTo(HdmiCecMessageValidator.OK);
        assertThat(message).isEqualTo(buildMessage("05:73:7F"));
    }

    @Test
    public void build_invalid() {
        assertThat(SetAudioVolumeLevelMessage
                .build(Constants.ADDR_UNREGISTERED, Constants.ADDR_AUDIO_SYSTEM, 50)
                .getValidationResult())
                .isEqualTo(ERROR_SOURCE);
        assertThat(SetAudioVolumeLevelMessage
                .build(Constants.ADDR_TV, Constants.ADDR_BROADCAST, 50)
                .getValidationResult())
                .isEqualTo(ERROR_DESTINATION);
        assertThat(HdmiUtils.buildMessage("04:73")
                .getValidationResult())
                .isEqualTo(ERROR_PARAMETER_SHORT);
    }
}
