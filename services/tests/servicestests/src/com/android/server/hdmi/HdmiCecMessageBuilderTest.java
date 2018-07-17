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

import static com.android.server.hdmi.Constants.ADDR_BROADCAST;
import static com.android.server.hdmi.Constants.ADDR_PLAYBACK_1;
import static com.google.common.truth.Truth.assertThat;

import android.hardware.hdmi.HdmiDeviceInfo;
import android.support.test.filters.SmallTest;
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
                        ADDR_PLAYBACK_1, 01234, HdmiDeviceInfo.DEVICE_PLAYBACK);
        assertThat(message)
                .isEqualTo(
                        new HdmiCecMessage(
                                ADDR_PLAYBACK_1,
                                ADDR_BROADCAST,
                                Constants.MESSAGE_REPORT_PHYSICAL_ADDRESS,
                                new byte[] {012, 034}));
    }
}
