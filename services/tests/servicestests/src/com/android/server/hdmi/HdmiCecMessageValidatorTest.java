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
    public void isValid_unregisteredSource() {
        // Message invokes a broadcast response
        //   <Get Menu Language>
        assertMessageValidity("F4:91").isEqualTo(OK);
        //   <Request Active Source>
        assertMessageValidity("FF:85").isEqualTo(OK);

        // Message by CEC Switch
        //   <Routing Change>
        assertMessageValidity("FF:80:00:00:10:00").isEqualTo(OK);

        //   <Routing Information>
        assertMessageValidity("FF:81:10:00").isEqualTo(OK);

        // Standby
        assertMessageValidity("F4:36").isEqualTo(OK);
        assertMessageValidity("FF:36").isEqualTo(OK);

        // <Report Physical Address> / <Active Source>
        assertMessageValidity("FF:84:10:00:04").isEqualTo(OK);
        assertMessageValidity("FF:82:10:00").isEqualTo(OK);
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
        assertMessageValidity("04:90:03:05").isEqualTo(OK);

        assertMessageValidity("0F:90:00").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:90").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("04:90").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("04:90:04").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_menuRequest() {
        assertMessageValidity("40:8D:00").isEqualTo(OK);
        assertMessageValidity("40:8D:02:04").isEqualTo(OK);

        assertMessageValidity("0F:8D:00").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:8D").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:8D").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:8D:03").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_menuStatus() {
        assertMessageValidity("40:8E:00").isEqualTo(OK);
        assertMessageValidity("40:8E:01:00").isEqualTo(OK);

        assertMessageValidity("0F:8E:00").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:8E").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:8E").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:8E:02").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_systemAudioModeRequest() {
        assertMessageValidity("40:70:00:00").isEqualTo(OK);
        assertMessageValidity("40:70").isEqualTo(OK);

        assertMessageValidity("F0:70").isEqualTo(ERROR_SOURCE);
        // Invalid physical address
        assertMessageValidity("40:70:10:10").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_setSystemAudioMode() {
        assertMessageValidity("40:72:00").isEqualTo(OK);
        assertMessageValidity("4F:72:01:03").isEqualTo(OK);

        assertMessageValidity("F0:72").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:72").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:72:02").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_systemAudioModeStatus() {
        assertMessageValidity("40:7E:00").isEqualTo(OK);
        assertMessageValidity("40:7E:01:01").isEqualTo(OK);

        assertMessageValidity("0F:7E:00").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:7E").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:7E").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:7E:02").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_setAudioRate() {
        assertMessageValidity("40:9A:00").isEqualTo(OK);
        assertMessageValidity("40:9A:03").isEqualTo(OK);
        assertMessageValidity("40:9A:06:02").isEqualTo(OK);

        assertMessageValidity("0F:9A:00").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:9A").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:9A").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:9A:07").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_setTimerProgramTitle() {
        assertMessageValidity("40:67:47:61:6D:65:20:6F:66:20:54:68:72:6F:6E:65:73").isEqualTo(OK);
        assertMessageValidity("40:67:4A").isEqualTo(OK);

        assertMessageValidity("4F:67:47:4F:54").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F4:67:47:4F:54").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:67").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:67:47:9A:54").isEqualTo(ERROR_PARAMETER);
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

    @Test
    public void isValid_setAnalogueTimer_clearAnalogueTimer() {
        assertMessageValidity("04:33:0C:08:10:1E:04:30:08:00:13:AD:06").isEqualTo(OK);
        assertMessageValidity("04:34:04:0C:16:0F:08:37:00:02:EA:60:03:34").isEqualTo(OK);

        assertMessageValidity("0F:33:0C:08:10:1E:04:30:08:00:13:AD:06")
                .isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:34:04:0C:16:0F:08:37:00:02:EA:60:03").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("04:33:0C:08:10:1E:04:30:08:13:AD:06")
                .isEqualTo(ERROR_PARAMETER_SHORT);
        // Out of range Day of Month
        assertMessageValidity("04:34:20:0C:16:0F:08:37:00:02:EA:60:03").isEqualTo(ERROR_PARAMETER);
        // Out of range Month of Year
        assertMessageValidity("04:33:0C:00:10:1E:04:30:08:00:13:AD:06").isEqualTo(ERROR_PARAMETER);
        // Out of range Start Time - Hour
        assertMessageValidity("04:34:04:0C:18:0F:08:37:00:02:EA:60:03").isEqualTo(ERROR_PARAMETER);
        // Out of range Start Time - Minute
        assertMessageValidity("04:33:0C:08:10:50:04:30:08:00:13:AD:06").isEqualTo(ERROR_PARAMETER);
        // Out of range Duration - Duration Hours
        assertMessageValidity("04:34:04:0C:16:0F:64:37:00:02:EA:60:03").isEqualTo(ERROR_PARAMETER);
        // Out of range Duration - Minute
        assertMessageValidity("04:33:0C:08:10:1E:04:64:08:00:13:AD:06").isEqualTo(ERROR_PARAMETER);
        // Invalid Recording Sequence
        assertMessageValidity("04:34:04:0C:16:0F:08:37:88:02:EA:60:03").isEqualTo(ERROR_PARAMETER);
        // Invalid Recording Sequence
        assertMessageValidity("04:33:0C:08:10:1E:04:30:A2:00:13:AD:06").isEqualTo(ERROR_PARAMETER);
        // Out of range Analogue Broadcast Type
        assertMessageValidity("04:34:04:0C:16:0F:08:37:00:03:EA:60:03").isEqualTo(ERROR_PARAMETER);
        // Out of range Analogue Frequency
        assertMessageValidity("04:33:0C:08:10:1E:04:30:08:00:FF:FF:06").isEqualTo(ERROR_PARAMETER);
        // Out of range Broadcast System
        assertMessageValidity("04:34:04:0C:16:0F:08:37:00:02:EA:60:20").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_setDigitalTimer_clearDigitalTimer() {
        // Services identified by Digital IDs - ARIB Broadcast System
        assertMessageValidity("04:99:0C:08:15:05:04:1E:00:00:C4:C2:11:D8:75:30").isEqualTo(OK);
        // Service identified by Digital IDs - ATSC Broadcast System
        assertMessageValidity("04:97:1E:07:12:20:50:28:01:01:8B:5E:39:5A").isEqualTo(OK);
        // Service identified by Digital IDs - DVB Broadcast System
        assertMessageValidity("04:99:05:0C:06:0A:19:3B:40:19:8B:44:03:11:04:FC").isEqualTo(OK);
        // Service identified by Channel - 1 part channel number
        assertMessageValidity("04:97:12:06:0C:2D:5A:19:08:91:04:00:B1").isEqualTo(OK);
        // Service identified by Channel - 2 part channel number
        assertMessageValidity("04:99:15:09:00:0F:00:2D:04:82:09:C8:72:C8").isEqualTo(OK);

        assertMessageValidity("4F:97:0C:08:15:05:04:1E:00:00:C4:C2:11:D8:75:30")
                .isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:99:15:09:00:0F:00:2D:04:82:09:C8:72:C8").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("04:97:1E:12:20:58:01:01:8B:5E:39:5A")
                .isEqualTo(ERROR_PARAMETER_SHORT);
        // Out of range Day of Month
        assertMessageValidity("04:99:24:0C:06:0A:19:3B:40:19:8B:44:03:11:04:FC")
                .isEqualTo(ERROR_PARAMETER);
        // Out of range Month of Year
        assertMessageValidity("04:97:12:10:0C:2D:5A:19:08:91:04:00:B1").isEqualTo(ERROR_PARAMETER);
        // Out of range Start Time - Hour
        assertMessageValidity("04:99:0C:08:20:05:04:1E:00:00:C4:C2:11:D8:75:30")
                .isEqualTo(ERROR_PARAMETER);
        // Out of range Start Time - Minute
        assertMessageValidity("04:97:15:09:00:4B:00:2D:04:82:09:C8:72:C8")
                .isEqualTo(ERROR_PARAMETER);
        // Out of range Duration - Duration Hours
        assertMessageValidity("04:99:1E:07:12:20:78:28:01:01:8B:5E:39:5A")
                .isEqualTo(ERROR_PARAMETER);
        // Out of range Duration - Minute
        assertMessageValidity("04:97:05:0C:06:0A:19:48:40:19:8B:44:03:11:04:FC")
                .isEqualTo(ERROR_PARAMETER);
        // Invalid Recording Sequence
        assertMessageValidity("04:99:12:06:0C:2D:5A:19:90:91:04:00:B1").isEqualTo(ERROR_PARAMETER);
        // Invalid Recording Sequence
        assertMessageValidity("04:97:0C:08:15:05:04:1E:21:00:C4:C2:11:D8:75:30")
                .isEqualTo(ERROR_PARAMETER);

        // Invalid Digital Broadcast System
        assertMessageValidity("04:99:1E:07:12:20:50:28:01:04:8B:5E:39:5A")
                .isEqualTo(ERROR_PARAMETER);
        // Invalid Digital Broadcast System
        assertMessageValidity("04:97:05:0C:06:0A:19:3B:40:93:8B:44:03:11:04:FC")
                .isEqualTo(ERROR_PARAMETER);
        // Insufficient data for ARIB Broadcast system
        assertMessageValidity("04:99:0C:08:15:05:04:1E:00:00:C4:C2:11:D8:75")
                .isEqualTo(ERROR_PARAMETER);
        // Insufficient data for ATSC Broadcast system
        assertMessageValidity("04:97:1E:07:12:20:50:28:01:01:8B:5E:39").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for DVB Broadcast system
        assertMessageValidity("04:99:05:0C:06:0A:19:3B:40:19:8B:44:03:11:04")
                .isEqualTo(ERROR_PARAMETER);
        // Insufficient data for 2 part channel number
        assertMessageValidity("04:97:15:09:00:0F:00:2D:04:82:09:C8:72").isEqualTo(ERROR_PARAMETER);
        // Invalid Channel Number format
        assertMessageValidity("04:99:12:06:0C:2D:5A:19:08:91:0D:00:B1").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_setExternalTimer_clearExternalTimer() {
        assertMessageValidity("40:A1:0C:08:15:05:04:1E:02:04:20").isEqualTo(OK);
        assertMessageValidity("40:A2:14:09:12:28:4B:19:10:05:10:00").isEqualTo(OK);

        assertMessageValidity("4F:A1:0C:08:15:05:04:1E:02:04:20").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F4:A2:14:09:12:28:4B:19:10:05:10:00").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:A1:0C:08:15:05:04:1E:02:04").isEqualTo(ERROR_PARAMETER_SHORT);
        // Out of range Day of Month
        assertMessageValidity("40:A2:28:09:12:28:4B:19:10:05:10:00").isEqualTo(ERROR_PARAMETER);
        // Out of range Month of Year
        assertMessageValidity("40:A1:0C:0F:15:05:04:1E:02:04:20").isEqualTo(ERROR_PARAMETER);
        // Out of range Start Time - Hour
        assertMessageValidity("40:A2:14:09:1A:28:4B:19:10:05:10:00").isEqualTo(ERROR_PARAMETER);
        // Out of range Start Time - Minute
        assertMessageValidity("40:A1:0C:08:15:48:04:1E:02:04:20").isEqualTo(ERROR_PARAMETER);
        // Out of range Duration - Duration Hours
        assertMessageValidity("40:A2:14:09:12:28:66:19:10:05:10:00").isEqualTo(ERROR_PARAMETER);
        // Out of range Duration - Minute
        assertMessageValidity("40:A1:0C:08:15:05:04:3F:02:04:20").isEqualTo(ERROR_PARAMETER);
        // Invalid Recording Sequence
        assertMessageValidity("40:A2:14:09:12:28:4B:19:84:05:10:00").isEqualTo(ERROR_PARAMETER);
        // Invalid Recording Sequence
        assertMessageValidity("40:A1:0C:08:15:05:04:1E:14:04:20").isEqualTo(ERROR_PARAMETER);
        // Invalid external source specifier
        assertMessageValidity("40:A2:14:09:12:28:4B:19:10:08:10:00").isEqualTo(ERROR_PARAMETER);
        // Invalid External PLug
        assertMessageValidity("04:A1:0C:08:15:05:04:1E:02:04:00").isEqualTo(ERROR_PARAMETER);
        // Invalid Physical Address
        assertMessageValidity("40:A2:14:09:12:28:4B:19:10:05:10:10").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_timerClearedStatus() {
        assertMessageValidity("40:43:01:7E").isEqualTo(OK);
        assertMessageValidity("40:43:80").isEqualTo(OK);

        assertMessageValidity("4F:43:01").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:43:80").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:43").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:43:03").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_timerStatus() {
        // Programmed - Space available
        assertMessageValidity("40:35:58").isEqualTo(OK);
        // Programmed - Not enough space available
        assertMessageValidity("40:35:B9:32:1C:4F").isEqualTo(OK);
        // Not programmed - Date out of range
        assertMessageValidity("40:35:82:3B").isEqualTo(OK);
        // Not programmed - Duplicate
        assertMessageValidity("40:35:EE:52:0C").isEqualTo(OK);

        assertMessageValidity("4F:35:58").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:35:82").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:35").isEqualTo(ERROR_PARAMETER_SHORT);
        // Programmed - Invalid programmed info
        assertMessageValidity("40:35:BD").isEqualTo(ERROR_PARAMETER);
        // Non programmed - Invalid not programmed error info
        assertMessageValidity("40:35:DE").isEqualTo(ERROR_PARAMETER);
        // Programmed - Might not be enough space available - Invalid duration hours
        assertMessageValidity("40:35:BB:96:1C").isEqualTo(ERROR_PARAMETER);
        // Not programmed - Duplicate - Invalid duration minutes
        assertMessageValidity("40:35:EE:52:4A").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_deckControl() {
        assertMessageValidity("40:42:01:6E").isEqualTo(OK);
        assertMessageValidity("40:42:04").isEqualTo(OK);

        assertMessageValidity("4F:42:01").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:42:04").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:42").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:42:05").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_deckStatus() {
        assertMessageValidity("40:1B:11:58").isEqualTo(OK);
        assertMessageValidity("40:1B:1F").isEqualTo(OK);

        assertMessageValidity("4F:1B:11").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:1B:1F").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:1B").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:1B:10").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:1B:20").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_statusRequest() {
        assertMessageValidity("40:08:01").isEqualTo(OK);
        assertMessageValidity("40:08:02:5C").isEqualTo(OK);
        assertMessageValidity("40:1A:01:F8").isEqualTo(OK);
        assertMessageValidity("40:1A:03").isEqualTo(OK);

        assertMessageValidity("4F:08:01").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:08:03").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("4F:1A:01").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:1A:03").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:08").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:1A").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:08:00").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:08:05").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:1A:00").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:1A:04").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_play() {
        assertMessageValidity("40:41:16:E3").isEqualTo(OK);
        assertMessageValidity("40:41:20").isEqualTo(OK);

        assertMessageValidity("4F:41:16").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:41:20").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:41").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:41:04").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:41:18").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:41:23").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:41:26").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_selectAnalogueService() {
        assertMessageValidity("40:92:00:13:0F:00:96").isEqualTo(OK);
        assertMessageValidity("40:92:02:EA:60:1F").isEqualTo(OK);

        assertMessageValidity("4F:92:00:13:0F:00").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:92:02:EA:60:1F").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:92:00:13:0F").isEqualTo(ERROR_PARAMETER_SHORT);
        // Invalid Analogue Broadcast type
        assertMessageValidity("40:92:03:EA:60:1F").isEqualTo(ERROR_PARAMETER);
        // Invalid Analogue Frequency
        assertMessageValidity("40:92:00:FF:FF:00").isEqualTo(ERROR_PARAMETER);
        // Invalid Broadcast system
        assertMessageValidity("40:92:02:EA:60:20").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_selectDigitalService() {
        assertMessageValidity("40:93:00:11:CE:90:0F:00:78").isEqualTo(OK);
        assertMessageValidity("40:93:10:13:0B:34:38").isEqualTo(OK);
        assertMessageValidity("40:93:9A:06:F9:D3:E6").isEqualTo(OK);
        assertMessageValidity("40:93:91:09:F4:40:C8").isEqualTo(OK);

        assertMessageValidity("4F:93:00:11:CE:90:0F:00:78").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:93:10:13:0B:34:38").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:93:9A:06:F9").isEqualTo(ERROR_PARAMETER_SHORT);
        // Invalid Digital Broadcast System
        assertMessageValidity("40:93:14:11:CE:90:0F:00:78").isEqualTo(ERROR_PARAMETER);
        // Invalid Digital Broadcast System
        assertMessageValidity("40:93:A0:07:95:F1").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for ARIB Broadcast system
        assertMessageValidity("40:93:00:11:CE:90:0F:00").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for ATSC Broadcast system
        assertMessageValidity("40:93:10:13:0B:34").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for DVB Broadcast system
        assertMessageValidity("40:93:18:BE:77:00:7D:01").isEqualTo(ERROR_PARAMETER);
        // Invalid channel number format
        assertMessageValidity("40:93:9A:10:F9:D3").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for 2 part channel number
        assertMessageValidity("40:93:91:09:F4:40").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_tunerDeviceStatus() {
        // Displaying digital tuner
        assertMessageValidity("40:07:00:00:11:CE:90:0F:00:78").isEqualTo(OK);
        assertMessageValidity("40:07:80:10:13:0B:34:38").isEqualTo(OK);
        assertMessageValidity("40:07:00:9A:06:F9:D3:E6").isEqualTo(OK);
        assertMessageValidity("40:07:00:91:09:F4:40:C8").isEqualTo(OK);
        // Not displaying tuner
        assertMessageValidity("40:07:01").isEqualTo(OK);
        assertMessageValidity("40:07:81:07:64:B9:02").isEqualTo(OK);
        // Displaying analogue tuner
        assertMessageValidity("40:07:02:00:13:0F:00:96").isEqualTo(OK);
        assertMessageValidity("40:07:82:02:EA:60:1F").isEqualTo(OK);

        assertMessageValidity("4F:07:00:00:11:CE:90:0F:00:78").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:07:82:02:EA:60:1F").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:07").isEqualTo(ERROR_PARAMETER_SHORT);

        // Invalid display info
        assertMessageValidity("40:07:09:A1:8C:17:51").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:07:A7:0C:29").isEqualTo(ERROR_PARAMETER);
        // Invalid Digital Broadcast System
        assertMessageValidity("40:07:00:14:11:CE:90:0F:00:78").isEqualTo(ERROR_PARAMETER);
        // Invalid Digital Broadcast System
        assertMessageValidity("40:07:80:A0:07:95:F1").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for ARIB Broadcast system
        assertMessageValidity("40:07:00:00:11:CE:90:0F:00").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for ATSC Broadcast system
        assertMessageValidity("40:07:80:10:13:0B:34").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for DVB Broadcast system
        assertMessageValidity("40:07:00:18:BE:77:00:7D:01").isEqualTo(ERROR_PARAMETER);
        // Invalid channel number format
        assertMessageValidity("40:07:80:9A:10:F9:D3").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for 1 part channel number
        assertMessageValidity("40:07:00:90:04:F7").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for 2 part channel number
        assertMessageValidity("40:07:80:91:09:F4:40").isEqualTo(ERROR_PARAMETER);
        // Invalid Analogue Broadcast type
        assertMessageValidity("40:07:02:03:EA:60:1F").isEqualTo(ERROR_PARAMETER);
        // Invalid Analogue Frequency
        assertMessageValidity("40:07:82:00:FF:FF:00").isEqualTo(ERROR_PARAMETER);
        // Invalid Broadcast system
        assertMessageValidity("40:07:02:02:EA:60:20").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_UserControlPressed() {
        assertMessageValidity("40:44:07").isEqualTo(OK);
        assertMessageValidity("40:44:52:A7").isEqualTo(OK);

        assertMessageValidity("40:44:60").isEqualTo(OK);
        assertMessageValidity("40:44:60:1A").isEqualTo(OK);

        assertMessageValidity("40:44:67").isEqualTo(OK);
        assertMessageValidity("40:44:67:04:00:B1").isEqualTo(OK);
        assertMessageValidity("40:44:67:09:C8:72:C8").isEqualTo(OK);

        assertMessageValidity("40:44:68").isEqualTo(OK);
        assertMessageValidity("40:44:68:93").isEqualTo(OK);
        assertMessageValidity("40:44:69").isEqualTo(OK);
        assertMessageValidity("40:44:69:7C").isEqualTo(OK);
        assertMessageValidity("40:44:6A").isEqualTo(OK);
        assertMessageValidity("40:44:6A:B4").isEqualTo(OK);

        assertMessageValidity("40:44:56").isEqualTo(OK);
        assertMessageValidity("40:44:56:60").isEqualTo(OK);

        assertMessageValidity("40:44:57").isEqualTo(OK);
        assertMessageValidity("40:44:57:A0").isEqualTo(OK);

        assertMessageValidity("4F:44:07").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("F0:44:52:A7").isEqualTo(ERROR_SOURCE);
        assertMessageValidity("40:44").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:44:67:04:B1").isEqualTo(ERROR_PARAMETER_SHORT);
        // Invalid Play mode
        assertMessageValidity("40:44:60:04").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:44:60:08").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:44:60:26").isEqualTo(ERROR_PARAMETER);
        // Invalid Channel Identifier - Channel number format
        assertMessageValidity("40:44:67:11:8A:42").isEqualTo(ERROR_PARAMETER);
        // Insufficient data for 2 - part channel number
        assertMessageValidity("40:44:67:09:C8:72").isEqualTo(ERROR_PARAMETER);
        // Invalid UI Broadcast type
        assertMessageValidity("40:44:56:11").isEqualTo(ERROR_PARAMETER);
        // Invalid UI Sound Presentation Control
        assertMessageValidity("40:44:57:40").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_physicalAddress() {
        assertMessageValidity("4F:82:10:00").isEqualTo(OK);
        assertMessageValidity("4F:82:12:34").isEqualTo(OK);
        assertMessageValidity("0F:82:00:00").isEqualTo(OK);
        assertMessageValidity("40:9D:14:00").isEqualTo(OK);
        assertMessageValidity("40:9D:10:00").isEqualTo(OK);
        assertMessageValidity("0F:81:44:20").isEqualTo(OK);
        assertMessageValidity("4F:81:13:10").isEqualTo(OK);
        assertMessageValidity("4F:86:14:14").isEqualTo(OK);
        assertMessageValidity("0F:86:15:24").isEqualTo(OK);

        assertMessageValidity("4F:82:10").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:9D:14").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("0F:81:44").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("0F:86:15").isEqualTo(ERROR_PARAMETER_SHORT);

        assertMessageValidity("4F:82:10:10").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("4F:82:10:06").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:9D:14:04").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("40:9D:10:01").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("0F:81:44:02").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("4F:81:13:05").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("4F:86:10:14").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("0F:86:10:24").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_reportPhysicalAddress() {
        assertMessageValidity("4F:84:10:00:04").isEqualTo(OK);
        assertMessageValidity("0F:84:00:00:00").isEqualTo(OK);

        assertMessageValidity("4F:84:10:00").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("0F:84:00").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:84:10:00:04").isEqualTo(ERROR_DESTINATION);
        // Invalid Physical Address
        assertMessageValidity("4F:84:10:10:04").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("0F:84:00:30:00").isEqualTo(ERROR_PARAMETER);
        // Invalid Device Type
        assertMessageValidity("4F:84:12:34:08").isEqualTo(ERROR_PARAMETER);
    }

    @Test
    public void isValid_routingChange() {
        assertMessageValidity("0F:80:10:00:40:00").isEqualTo(OK);
        assertMessageValidity("4F:80:12:00:50:00").isEqualTo(OK);

        assertMessageValidity("0F:80:10:00:40").isEqualTo(ERROR_PARAMETER_SHORT);
        assertMessageValidity("40:80:12:00:50:00").isEqualTo(ERROR_DESTINATION);
        assertMessageValidity("0F:80:10:01:40:00").isEqualTo(ERROR_PARAMETER);
        assertMessageValidity("4F:80:12:00:50:50").isEqualTo(ERROR_PARAMETER);
    }

    private IntegerSubject assertMessageValidity(String message) {
        return assertThat(mHdmiCecMessageValidator.isValid(buildMessage(message), false));
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
