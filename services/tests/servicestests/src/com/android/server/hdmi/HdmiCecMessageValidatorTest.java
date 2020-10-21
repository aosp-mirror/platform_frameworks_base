/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static com.android.server.hdmi.HdmiCecMessageValidator.OK;

import static com.google.common.truth.Truth.assertThat;

import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.google.common.truth.IntegerSubject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link com.android.server.hdmi.HdmiCecMessageValidator} class. */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiCecMessageValidatorTest {

    private HdmiCecMessageValidator mHdmiCecMessageValidator;
    private TestLooper mTestLooper = new TestLooper();

    @Before
    public void setUp() throws Exception {
        HdmiControlService mHdmiControlService = new HdmiControlService(
                InstrumentationRegistry.getTargetContext());

        mHdmiControlService.setIoLooper(mTestLooper.getLooper());
        mHdmiCecMessageValidator = new HdmiCecMessageValidator(mHdmiControlService);
    }

    @Test
    public void isValid_giveDevicePowerStatus() {
        assertMessageValidity("04:8F").isEqualTo(OK);

        assertMessageValidity("0F:8F").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F4:8F").isEqualTo(ERROR_SOURCE);
    }

    @Test
    public void isValid_reportPowerStatus() {
        assertMessageValidity("04:90:00").isEqualTo(OK);

        assertMessageValidity("0F:90:00").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:90").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("04:90").isEqualTo(ERROR_PARAMETER_SHORT);
    }

    private IntegerSubject assertMessageValidity(String message) {
        return assertThat(mHdmiCecMessageValidator.isValid(buildMessage(message)));
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
            params[i] = (byte) Integer.parseInt(parts[i + 2], 16);
        }
        return new HdmiCecMessage(src, dest, opcode, params);
    }
}
