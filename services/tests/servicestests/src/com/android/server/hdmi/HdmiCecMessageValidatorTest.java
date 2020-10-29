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
import static com.android.server.hdmi.HdmiCecMessageValidator.ERROR_PARAMETER;
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

    @Test
    public void isValid_setMenuLanguage() {
        assertMessageValidity("4F:32:53:50:41").isEqualTo(OK);
        assertMessageValidity("0F:32:45:4E:47:8C:49:D3:48").isEqualTo(OK);

        assertMessageValidity("40:32:53:50:41").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:32").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("4F:32:45:55").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("4F:32:19:7F:83").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_setOsdString() {
        assertMessageValidity("40:64:80:41").isEqualTo(OK);
        // Even though the parameter string in this message is longer than 14 bytes, it is accepted
        // as this parameter might be extended in future versions.
        assertMessageValidity("04:64:00:4C:69:76:69:6E:67:52:6F:6F:6D:20:54:56:C4").isEqualTo(OK);

        assertMessageValidity("4F:64:40:41").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:64:C0:41").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:64:00").isEqualTo(ERROR_PARAMETER_SHORT);
        // Invalid Display Control
        assertMessageValidity("40:64:20:4C:69:76").isEqualTo(ERROR_PARAMETER);
        // Invalid ASCII characters
        assertMessageValidity("40:64:40:4C:69:7F").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_setOsdName() {
        assertMessageValidity("40:47:4C:69:76:69:6E:67:52:6F:6F:6D:54:56").isEqualTo(OK);
        assertMessageValidity("40:47:54:56").isEqualTo(OK);

        assertMessageValidity("4F:47:54:56").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:47:54:56").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:47").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:47:4C:69:7F").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_recordStatus() {
        assertMessageValidity("40:0A:01").isEqualTo(OK);
        assertMessageValidity("40:0A:13").isEqualTo(OK);
        assertMessageValidity("40:0A:1F:04:01").isEqualTo(OK);

        assertMessageValidity("0F:0A:01").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:0A:01").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:0A").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:0A:00").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:0A:0F").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:0A:1D").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:0A:30").isEqualTo(ERROR_PARAMETER);
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
