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

import static com.google.common.truth.Truth.assertThat;

import android.hardware.hdmi.HdmiDeviceInfo;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
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

    /**
     * Build a CEC message from a hex byte string with bytes separated by {@code :}.
     *
     * <p>This format is used by both cec-client and www.cec-o-matic.com
     */
    private static HdmiCecMessage buildMessage(String message) {
        String[] parts = message.split(":");
        int src = Integer.parseInt(parts[0].substring(0, 1), 16);
        int dest = Integer.parseInt(parts[0].substring(1, 2), 16);
        int opcode = Integer.parseInt(parts[1], 16);
        byte[] params = new byte[parts.length - 2];
        for (int i = 0; i < params.length; i++) {
            params[i] = Byte.parseByte(parts[i + 2], 16);
        }
        return new HdmiCecMessage(src, dest, opcode, params);
    }
}
