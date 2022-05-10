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

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link HdmiCecMessage} class. */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiCecMessageTest {

    @Test
    public void testEqualsHdmiCecMessage() {
        int source = 0;
        int destination = 1;
        int opcode = 0x7f;
        byte[] params1 = {0x00, 0x1a, 0x2b, 0x3c};
        byte[] params2 = {0x00, 0x1a, 0x2b, 0x3c, 0x4d};

        new EqualsTester()
                .addEqualityGroup(
                        HdmiCecMessage.build(source, destination, opcode, params1),
                        HdmiCecMessage.build(source, destination, opcode, params1))
                .addEqualityGroup(HdmiCecMessage.build(source, destination, opcode, params2))
                .addEqualityGroup(HdmiCecMessage.build(source + 1, destination, opcode, params1))
                .addEqualityGroup(HdmiCecMessage.build(source, destination + 1, opcode, params1))
                .addEqualityGroup(HdmiCecMessage.build(source, destination, opcode + 1, params1))
                .testEquals();
    }
}
