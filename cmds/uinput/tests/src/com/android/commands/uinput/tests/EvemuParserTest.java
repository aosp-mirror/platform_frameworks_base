/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.commands.uinput.tests;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;

import android.platform.test.annotations.Postsubmit;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.commands.uinput.EvemuParser;
import com.android.commands.uinput.Event;
import com.android.commands.uinput.Event.UinputControlCode;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.io.StringReader;

import src.com.android.commands.uinput.InputAbsInfo;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Postsubmit
public class EvemuParserTest {

    private Event getRegistrationEvent(String fileContents) throws IOException {
        StringReader reader = new StringReader(fileContents);
        EvemuParser parser = new EvemuParser(reader);
        Event event = parser.getNextEvent();
        assertThat(event.getCommand()).isEqualTo(Event.Command.REGISTER);
        return event;
    }

    @Test
    public void testNameParsing() throws IOException {
        Event event = getRegistrationEvent("""
                N: ACME Pointing Widget #4
                I: 0001 1234 5678 9abc
                """);
        assertThat(event.getName()).isEqualTo("ACME Pointing Widget #4");
    }

    @Test
    public void testIdParsing() throws IOException {
        Event event = getRegistrationEvent("""
                N: ACME Pointing Widget #4
                I: 0001 1234 5678 9abc
                """);
        assertThat(event.getBus()).isEqualTo(0x0001);
        assertThat(event.getVendorId()).isEqualTo(0x1234);
        assertThat(event.getProductId()).isEqualTo(0x5678);
        assertThat(event.getVersionId()).isEqualTo(0x9abc);
    }

    @Test
    public void testPropertyBitmapParsing() throws IOException {
        Event event = getRegistrationEvent("""
                N: ACME Pointing Widget #4
                I: 0001 1234 5678 9abc
                P: 05 00 00 00 00 00 00 00
                P: 01
                """);
        assertThat(event.getConfiguration().get(UinputControlCode.UI_SET_PROPBIT.getValue()))
                .asList().containsExactly(0, 2, 64);
    }

    @Test
    public void testEventBitmapParsing() throws IOException {
        Event event = getRegistrationEvent("""
                N: ACME Pointing Widget #4
                I: 0001 1234 5678 9abc
                B: 00 0b 00 00 00 00 00 00 00  # SYN
                B: 01 00 00 03 00 00 00 00 00  # KEY
                B: 01 00 01 00 00 00 00 00 00
                B: 02 03 00 00 00 00 00 00 00  # REL
                B: 03 00 00                    # ABS
                """);
        assertThat(event.getConfiguration().get(UinputControlCode.UI_SET_EVBIT.getValue()))
                .asList().containsExactly(Event.EV_KEY, Event.EV_REL);
        assertThat(event.getConfiguration().get(UinputControlCode.UI_SET_KEYBIT.getValue()))
                .asList().containsExactly(16, 17, 72);
        assertThat(event.getConfiguration().get(UinputControlCode.UI_SET_RELBIT.getValue()))
                .asList().containsExactly(0, 1);
        assertThat(event.getConfiguration().contains(UinputControlCode.UI_SET_ABSBIT.getValue()))
                .isFalse();
    }

    @Test
    public void testEventBitmapParsing_WithForceFeedback() throws IOException {
        Event event = getRegistrationEvent("""
                N: ACME Pointing Widget #4
                I: 0001 1234 5678 9abc
                B: 15 05  # FF
                """);
        assertThat(event.getConfiguration().get(UinputControlCode.UI_SET_EVBIT.getValue()))
                .asList().containsExactly(Event.EV_FF);
        assertThat(event.getConfiguration().get(UinputControlCode.UI_SET_FFBIT.getValue()))
                .asList().containsExactly(0, 2);
        assertThat(event.getFfEffectsMax()).isEqualTo(2);
    }

    private void assertAbsInfo(InputAbsInfo info, int minimum, int maximum, int fuzz, int flat,
                               int resolution) {
        assertThat(info).isNotNull();
        assertWithMessage("Incorrect minimum").that(info.minimum).isEqualTo(minimum);
        assertWithMessage("Incorrect maximum").that(info.maximum).isEqualTo(maximum);
        assertWithMessage("Incorrect fuzz").that(info.fuzz).isEqualTo(fuzz);
        assertWithMessage("Incorrect flat").that(info.flat).isEqualTo(flat);
        assertWithMessage("Incorrect resolution").that(info.resolution).isEqualTo(resolution);
    }

    @Test
    public void testAbsInfoParsing_WithResolution() throws IOException {
        Event event = getRegistrationEvent("""
                N: ACME Weird Gamepad
                I: 0001 1234 5678 9abc
                A: 03 -128 128 4 4 0    # ABS_MT_RX
                A: 2f 0 9 0 0 0         # ABS_MT_SLOT
                A: 34 -4096 4096 0 0 0  # ABS_MT_ORIENTATION
                A: 35 0 1599 0 0 11     # ABS_MT_POSITION_X
                """);
        SparseArray<InputAbsInfo> absInfos = event.getAbsInfo();
        assertThat(absInfos.size()).isEqualTo(4);
        assertAbsInfo(absInfos.get(0x03), -128, 128, 4, 4, 0);
        assertAbsInfo(absInfos.get(0x2f), 0, 9, 0, 0, 0);
        assertAbsInfo(absInfos.get(0x34), -4096, 4096, 0, 0, 0);
        assertAbsInfo(absInfos.get(0x35), 0, 1599, 0, 0, 11);
    }

    @Test
    public void testAbsInfoParsing_WithoutResolution() throws IOException {
        Event event = getRegistrationEvent("""
                N: ACME Terrible Touchscreen
                I: 0001 1234 5678 9abc
                A: 2f 0 9 0 0         # ABS_MT_SLOT
                A: 35 0 1599 0 0      # ABS_MT_POSITION_X
                A: 36 0 2559 0 0      # ABS_MT_POSITION_X
                """);
        SparseArray<InputAbsInfo> absInfos = event.getAbsInfo();
        assertThat(absInfos.size()).isEqualTo(3);
        assertAbsInfo(absInfos.get(0x2f), 0, 9, 0, 0, 0);
        assertAbsInfo(absInfos.get(0x35), 0, 1599, 0, 0, 0);
        assertAbsInfo(absInfos.get(0x36), 0, 2559, 0, 0, 0);
    }

    @Test
    public void testLedAndSwitchStatesIgnored() throws IOException {
        // We don't support L: and S: lines yet, so all we need to check here is that they don't
        // prevent the other events from being parsed.
        StringReader reader = new StringReader("""
                N: ACME Widget
                I: 0001 1234 5678 9abc
                L: 00 0
                L: 09 1
                S: 0a 1
                E: 0.000001 0 0 0  # SYN_REPORT
                """);
        EvemuParser parser = new EvemuParser(reader);
        assertThat(parser.getNextEvent().getCommand()).isEqualTo(Event.Command.REGISTER);
        assertThat(parser.getNextEvent().getCommand()).isEqualTo(Event.Command.DELAY);
        assertThat(parser.getNextEvent().getCommand()).isEqualTo(Event.Command.INJECT);
    }

    private void assertInjectEvent(Event event, int eventType, int eventCode, int value) {
        assertInjectEvent(event, eventType, eventCode, value, 0);
    }

    private void assertInjectEvent(Event event, int eventType, int eventCode, int value,
                                   long timestampOffsetMicros) {
        assertThat(event).isNotNull();
        assertThat(event.getCommand()).isEqualTo(Event.Command.INJECT);
        assertThat(event.getInjections()).asList()
                .containsExactly(eventType, eventCode, value).inOrder();
        assertThat(event.getTimestampOffsetMicros()).isEqualTo(timestampOffsetMicros);
    }

    private void assertDelayEvent(Event event, int durationNanos) {
        assertThat(event).isNotNull();
        assertThat(event.getCommand()).isEqualTo(Event.Command.DELAY);
        assertThat(event.getDurationNanos()).isEqualTo(durationNanos);
    }

    @Test
    public void testEventParsing_OneFrame() throws IOException {
        StringReader reader = new StringReader("""
                N: ACME Widget
                I: 0001 1234 5678 9abc
                E: 0.000001 0002 0000 0001   # REL_X +1
                E: 0.000001 0002 0001 -0002  # REL_Y -2
                E: 0.000001 0000 0000 0000   # SYN_REPORT
                """);
        EvemuParser parser = new EvemuParser(reader);
        assertThat(parser.getNextEvent().getCommand()).isEqualTo(Event.Command.REGISTER);
        assertThat(parser.getNextEvent().getCommand()).isEqualTo(Event.Command.DELAY);
        assertInjectEvent(parser.getNextEvent(), 0x2, 0x0, 1, -1);
        assertInjectEvent(parser.getNextEvent(), 0x2, 0x1, -2);
        assertInjectEvent(parser.getNextEvent(), 0x0, 0x0, 0);
    }

    @Test
    public void testEventParsing_MultipleFrames() throws IOException {
        StringReader reader = new StringReader("""
                N: ACME YesBird Typing Aid
                I: 0001 1234 5678 9abc
                E: 0.000001 0001 0015 0001   # KEY_Y press
                E: 0.000001 0000 0000 0000   # SYN_REPORT
                E: 0.010001 0001 0015 0000   # KEY_Y release
                E: 0.010001 0000 0000 0000   # SYN_REPORT
                E: 1.010001 0001 0015 0001   # KEY_Y press
                E: 1.010001 0000 0000 0000   # SYN_REPORT
                """);
        EvemuParser parser = new EvemuParser(reader);
        assertThat(parser.getNextEvent().getCommand()).isEqualTo(Event.Command.REGISTER);
        assertThat(parser.getNextEvent().getCommand()).isEqualTo(Event.Command.DELAY);

        assertInjectEvent(parser.getNextEvent(), 0x1, 0x15, 1, -1);
        assertInjectEvent(parser.getNextEvent(), 0x0, 0x0, 0);

        assertDelayEvent(parser.getNextEvent(), 10_000_000);

        assertInjectEvent(parser.getNextEvent(), 0x1, 0x15, 0, 10_000);
        assertInjectEvent(parser.getNextEvent(), 0x0, 0x0, 0);

        assertDelayEvent(parser.getNextEvent(), 1_000_000_000);

        assertInjectEvent(parser.getNextEvent(), 0x1, 0x15, 1, 1_000_000);
        assertInjectEvent(parser.getNextEvent(), 0x0, 0x0, 0);
    }

    @Test
    public void testErrorLineNumberReporting() throws IOException {
        StringReader reader = new StringReader("""
                # EVEMU 1.3
                N: ACME Widget
                # Comment to make sure they're taken into account when numbering lines
                I: 0001 1234 5678 9abc
                00 00 00 00 00 00 00 00  # Missing a type
                E: 0.000001 0001 0015 0001   # KEY_Y press
                E: 0.000001 0000 0000 0000   # SYN_REPORT
                """);
        try {
            new EvemuParser(reader);
            fail("Parser should have thrown an error about the line with the missing type.");
        } catch (EvemuParser.ParsingException ex) {
            assertThat(ex.makeErrorMessage()).startsWith("Parsing error on line 5:");
        }
    }

    @Test
    public void testFreeDesktopEvemuRecording() throws IOException {
        // This is a real recording from FreeDesktop's evemu-record tool, as a basic compatibility
        // check with the FreeDesktop tools.
        // (CheckStyle objects to the long line here. It can be split up with escaped newlines once
        // the fix for b/306423115 reaches Android.)
        StringReader reader = new StringReader("""
                # EVEMU 1.3
                # Kernel: 6.5.6-1rodete4-amd64
                # DMI: dmi:bvnLENOVO:bvrXXXXXXXX(X.XX):bdXX/XX/XXXX:brX.XX:efrX.XX:svnLENOVO:pnXXXXXXXXXX:pvrThinkPadX1Carbon:rvnLENOVO:rnXXXXXXXXX:rvrXXXXX:cvnLENOVO:ctXX:cvrNone:skuLENOVO_MT_20KG_BU_Think_FM_ThinkPadX1Carbon:
                # Input device name: "Synaptics TM3289-021"
                # Input device ID: bus 0x1d vendor 0x6cb product 0000 version 0000
                # Size in mm: 96x52
                # Supported events:
                #   Event type 0 (EV_SYN)
                #     Event code 0 (SYN_REPORT)
                #     Event code 1 (SYN_CONFIG)
                #     Event code 2 (SYN_MT_REPORT)
                #     Event code 3 (SYN_DROPPED)
                #     Event code 4 ((null))
                #     Event code 5 ((null))
                #     Event code 6 ((null))
                #     Event code 7 ((null))
                #     Event code 8 ((null))
                #     Event code 9 ((null))
                #     Event code 10 ((null))
                #     Event code 11 ((null))
                #     Event code 12 ((null))
                #     Event code 13 ((null))
                #     Event code 14 ((null))
                #     Event code 15 (SYN_MAX)
                #   Event type 1 (EV_KEY)
                #     Event code 272 (BTN_LEFT)
                #     Event code 325 (BTN_TOOL_FINGER)
                #     Event code 328 (BTN_TOOL_QUINTTAP)
                #     Event code 330 (BTN_TOUCH)
                #     Event code 333 (BTN_TOOL_DOUBLETAP)
                #     Event code 334 (BTN_TOOL_TRIPLETAP)
                #     Event code 335 (BTN_TOOL_QUADTAP)
                #   Event type 3 (EV_ABS)
                #     Event code 0 (ABS_X)
                #       Value        0
                #       Min          0
                #       Max       1936
                #       Fuzz         0
                #       Flat         0
                #       Resolution  20
                #     Event code 1 (ABS_Y)
                #       Value        0
                #       Min          0
                #       Max       1057
                #       Fuzz         0
                #       Flat         0
                #       Resolution  20
                #     Event code 24 (ABS_PRESSURE)
                #       Value        0
                #       Min          0
                #       Max        255
                #       Fuzz         0
                #       Flat         0
                #       Resolution   0
                #     Event code 47 (ABS_MT_SLOT)
                #       Value        0
                #       Min          0
                #       Max          4
                #       Fuzz         0
                #       Flat         0
                #       Resolution   0
                #     Event code 48 (ABS_MT_TOUCH_MAJOR)
                #       Value        0
                #       Min          0
                #       Max         15
                #       Fuzz         0
                #       Flat         0
                #       Resolution   0
                #     Event code 49 (ABS_MT_TOUCH_MINOR)
                #       Value        0
                #       Min          0
                #       Max         15
                #       Fuzz         0
                #       Flat         0
                #       Resolution   0
                #     Event code 52 (ABS_MT_ORIENTATION)
                #       Value        0
                #       Min          0
                #       Max          1
                #       Fuzz         0
                #       Flat         0
                #       Resolution   0
                #     Event code 53 (ABS_MT_POSITION_X)
                #       Value        0
                #       Min          0
                #       Max       1936
                #       Fuzz         0
                #       Flat         0
                #       Resolution  20
                #     Event code 54 (ABS_MT_POSITION_Y)
                #       Value        0
                #       Min          0
                #       Max       1057
                #       Fuzz         0
                #       Flat         0
                #       Resolution  20
                #     Event code 55 (ABS_MT_TOOL_TYPE)
                #       Value        0
                #       Min          0
                #       Max         15
                #       Fuzz         0
                #       Flat         0
                #       Resolution   0
                #     Event code 57 (ABS_MT_TRACKING_ID)
                #       Value        0
                #       Min          0
                #       Max      65535
                #       Fuzz         0
                #       Flat         0
                #       Resolution   0
                #     Event code 58 (ABS_MT_PRESSURE)
                #       Value        0
                #       Min          0
                #       Max        255
                #       Fuzz         0
                #       Flat         0
                #       Resolution   0
                # Properties:
                #   Property  type 0 (INPUT_PROP_POINTER)
                #   Property  type 2 (INPUT_PROP_BUTTONPAD)
                N: Synaptics TM3289-021
                I: 001d 06cb 0000 0000
                P: 05 00 00 00 00 00 00 00
                B: 00 0b 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 01 00 00 00 00 00
                B: 01 20 e5 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 01 00 00 00 00 00 00 00 00
                B: 02 00 00 00 00 00 00 00 00
                B: 03 03 00 00 01 00 80 f3 06
                B: 04 00 00 00 00 00 00 00 00
                B: 05 00 00 00 00 00 00 00 00
                B: 11 00 00 00 00 00 00 00 00
                B: 12 00 00 00 00 00 00 00 00
                B: 14 00 00 00 00 00 00 00 00
                B: 15 00 00 00 00 00 00 00 00
                B: 15 00 00 00 00 00 00 00 00
                A: 00 0 1936 0 0 20
                A: 01 0 1057 0 0 20
                A: 18 0 255 0 0 0
                A: 2f 0 4 0 0 0
                A: 30 0 15 0 0 0
                A: 31 0 15 0 0 0
                A: 34 0 1 0 0 0
                A: 35 0 1936 0 0 20
                A: 36 0 1057 0 0 20
                A: 37 0 15 0 0 0
                A: 39 0 65535 0 0 0
                A: 3a 0 255 0 0 0
                ################################
                #      Waiting for events      #
                ################################
                E: 0.000001 0003 0039 0000\t# EV_ABS / ABS_MT_TRACKING_ID   0
                E: 0.000001 0003 0035 0891\t# EV_ABS / ABS_MT_POSITION_X    891
                E: 0.000001 0003 0036 0333\t# EV_ABS / ABS_MT_POSITION_Y    333
                E: 0.000001 0003 003a 0056\t# EV_ABS / ABS_MT_PRESSURE      56
                E: 0.000001 0003 0030 0001\t# EV_ABS / ABS_MT_TOUCH_MAJOR   1
                E: 0.000001 0003 0031 0001\t# EV_ABS / ABS_MT_TOUCH_MINOR   1
                E: 0.000001 0001 014a 0001\t# EV_KEY / BTN_TOUCH            1
                E: 0.000001 0001 0145 0001\t# EV_KEY / BTN_TOOL_FINGER      1
                E: 0.000001 0003 0000 0891\t# EV_ABS / ABS_X                891
                E: 0.000001 0003 0001 0333\t# EV_ABS / ABS_Y                333
                E: 0.000001 0003 0018 0056\t# EV_ABS / ABS_PRESSURE         56
                E: 0.000001 0000 0000 0000\t# ------------ SYN_REPORT (0) ---------- +0ms
                E: 0.006081 0003 0035 0888\t# EV_ABS / ABS_MT_POSITION_X    888
                """);
        EvemuParser parser = new EvemuParser(reader);
        Event regEvent = parser.getNextEvent();
        assertThat(regEvent.getName()).isEqualTo("Synaptics TM3289-021");

        assertThat(regEvent.getBus()).isEqualTo(0x001d);
        assertThat(regEvent.getVendorId()).isEqualTo(0x6cb);
        assertThat(regEvent.getProductId()).isEqualTo(0x0000);
        // TODO(b/302297266): check version ID once it's supported

        assertThat(regEvent.getConfiguration().get(UinputControlCode.UI_SET_PROPBIT.getValue()))
                .asList().containsExactly(0, 2);

        assertThat(regEvent.getConfiguration().get(UinputControlCode.UI_SET_EVBIT.getValue()))
                .asList().containsExactly(Event.EV_KEY, Event.EV_ABS);
        assertThat(regEvent.getConfiguration().get(UinputControlCode.UI_SET_KEYBIT.getValue()))
                .asList().containsExactly(272, 325, 328, 330, 333, 334, 335);
        assertThat(regEvent.getConfiguration().get(UinputControlCode.UI_SET_ABSBIT.getValue()))
                .asList().containsExactly(0, 1, 24, 47, 48, 49, 52, 53, 54, 55, 57, 58);

        SparseArray<InputAbsInfo> absInfos = regEvent.getAbsInfo();
        assertAbsInfo(absInfos.get(0), 0, 1936, 0, 0, 20);
        assertAbsInfo(absInfos.get(1), 0, 1057, 0, 0, 20);
        assertAbsInfo(absInfos.get(24), 0, 255, 0, 0, 0);
        assertAbsInfo(absInfos.get(47), 0, 4, 0, 0, 0);
        assertAbsInfo(absInfos.get(48), 0, 15, 0, 0, 0);
        assertAbsInfo(absInfos.get(49), 0, 15, 0, 0, 0);
        assertAbsInfo(absInfos.get(52), 0, 1, 0, 0, 0);
        assertAbsInfo(absInfos.get(53), 0, 1936, 0, 0, 20);
        assertAbsInfo(absInfos.get(54), 0, 1057, 0, 0, 20);
        assertAbsInfo(absInfos.get(55), 0, 15, 0, 0, 0);
        assertAbsInfo(absInfos.get(57), 0, 65535, 0, 0, 0);
        assertAbsInfo(absInfos.get(58), 0, 255, 0, 0, 0);

        assertThat(parser.getNextEvent().getCommand()).isEqualTo(Event.Command.DELAY);

        assertInjectEvent(parser.getNextEvent(), 0x3, 0x39, 0, -1);
        assertInjectEvent(parser.getNextEvent(), 0x3, 0x35, 891);
        assertInjectEvent(parser.getNextEvent(), 0x3, 0x36, 333);
        assertInjectEvent(parser.getNextEvent(), 0x3, 0x3a, 56);
        assertInjectEvent(parser.getNextEvent(), 0x3, 0x30, 1);
        assertInjectEvent(parser.getNextEvent(), 0x3, 0x31, 1);
        assertInjectEvent(parser.getNextEvent(), 0x1, 0x14a, 1);
        assertInjectEvent(parser.getNextEvent(), 0x1, 0x145, 1);
        assertInjectEvent(parser.getNextEvent(), 0x3, 0x0, 891);
        assertInjectEvent(parser.getNextEvent(), 0x3, 0x1, 333);
        assertInjectEvent(parser.getNextEvent(), 0x3, 0x18, 56);
        assertInjectEvent(parser.getNextEvent(), 0x0, 0x0, 0);

        assertDelayEvent(parser.getNextEvent(), 6_080_000);

        assertInjectEvent(parser.getNextEvent(), 0x3, 0x0035, 888, 6_080);
    }
}
