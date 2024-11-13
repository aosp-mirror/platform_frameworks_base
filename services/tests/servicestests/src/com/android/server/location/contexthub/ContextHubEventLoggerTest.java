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

package com.android.server.location.contexthub;

import static com.google.common.truth.Truth.assertThat;

import android.chre.flags.Flags;
import android.hardware.location.NanoAppMessage;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@Presubmit
public class ContextHubEventLoggerTest {
    private static final ContextHubEventLogger sInstance = ContextHubEventLogger.getInstance();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void testLogNanoappLoad() {
        ContextHubEventLogger.NanoappLoadEvent[] events =
                new ContextHubEventLogger.NanoappLoadEvent[] {
                    new ContextHubEventLogger.NanoappLoadEvent(0, -1, 42, -34, 100, false),
                    new ContextHubEventLogger.NanoappLoadEvent(0, 0, 123, 321, 001, true)
                };
        String[] eventStrings = generateEventDumpStrings(events);

        // log events and test sInstance.toString() contains event details
        sInstance.clear();
        sInstance.logNanoappLoad(-1, 42, -34, 100, false);
        sInstance.logNanoappLoad(0, 123, 321, 001, true);
        String instanceDump = sInstance.toString();
        for (String eventString: eventStrings) {
            assertThat(eventString.length() > 0).isTrue();
            assertThat(instanceDump.contains(eventString)).isTrue();
        }
    }

    @Test
    public void testLogNanoappUnload() {
        ContextHubEventLogger.NanoappUnloadEvent[] events =
                new ContextHubEventLogger.NanoappUnloadEvent[] {
                    new ContextHubEventLogger.NanoappUnloadEvent(0, -1, 47, false),
                    new ContextHubEventLogger.NanoappUnloadEvent(0, 1, 0xFFFFFFFF, true)
                };
        String[] eventStrings = generateEventDumpStrings(events);

        // log events and test sInstance.toString() contains event details
        sInstance.clear();
        sInstance.logNanoappUnload(-1, 47, false);
        sInstance.logNanoappUnload(1, 0xFFFFFFFF, true);
        String instanceDump = sInstance.toString();
        for (String eventString: eventStrings) {
            assertThat(eventString.length() > 0).isTrue();
            assertThat(instanceDump.contains(eventString)).isTrue();
        }
    }

    @Test
    public void testLogMessageFromNanoapp() {
        NanoAppMessage message1 = NanoAppMessage.createMessageFromNanoApp(1, 0,
                new byte[] {0x00, 0x11, 0x22, 0x33}, false);
        NanoAppMessage message2 = NanoAppMessage.createMessageFromNanoApp(0, 1,
                new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}, true);
        ContextHubEventLogger.NanoappMessageEvent[] events =
                new ContextHubEventLogger.NanoappMessageEvent[] {
                    new ContextHubEventLogger.NanoappMessageEvent(8, -123, message1, false),
                    new ContextHubEventLogger.NanoappMessageEvent(9, 321, message2, true)
                };
        String[] eventStrings = generateEventDumpStrings(events);

        // log events and test sInstance.toString() contains event details
        sInstance.clear();
        sInstance.logMessageFromNanoapp(-123, message1, false);
        sInstance.logMessageFromNanoapp(321, message2, true);
        String instanceDump = sInstance.toString();
        for (String eventString: eventStrings) {
            assertThat(eventString.length() > 0).isTrue();
            assertThat(instanceDump.contains(eventString)).isTrue();
        }
    }

    @Test
    public void testLogMessageToNanoapp() {
        NanoAppMessage message1 = NanoAppMessage.createMessageToNanoApp(1, 0,
                new byte[] {0x00, 0x11, 0x22, 0x33});
        NanoAppMessage message2 = NanoAppMessage.createMessageToNanoApp(0, 1,
                new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
        ContextHubEventLogger.NanoappMessageEvent[] events =
                new ContextHubEventLogger.NanoappMessageEvent[] {
                    new ContextHubEventLogger.NanoappMessageEvent(23, 888, message1, true),
                    new ContextHubEventLogger.NanoappMessageEvent(34, 999, message2, false)
                };
        String[] eventStrings = generateEventDumpStrings(events);

        // log events and test sInstance.toString() contains event details
        sInstance.clear();
        sInstance.logMessageToNanoapp(888, message1, true);
        sInstance.logMessageToNanoapp(999, message2, false);
        String instanceDump = sInstance.toString();
        for (String eventString: eventStrings) {
            assertThat(eventString.length() > 0).isTrue();
            assertThat(instanceDump.contains(eventString)).isTrue();
        }
    }

    @Test
    @EnableFlags({Flags.FLAG_RELIABLE_MESSAGE,
                  Flags.FLAG_RELIABLE_MESSAGE_IMPLEMENTATION})
    public void testLogReliableMessageToNanoappStatus() {
        NanoAppMessage message1 = NanoAppMessage.createMessageToNanoApp(1, 0,
                new byte[] {0x00, 0x11, 0x22, 0x33});
        NanoAppMessage message2 = NanoAppMessage.createMessageToNanoApp(0, 1,
                new byte[] {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
        message1.setIsReliable(true);
        message2.setIsReliable(true);
        message1.setMessageSequenceNumber(0);
        message2.setMessageSequenceNumber(1);

        ContextHubEventLogger.NanoappMessageEvent[] events =
                new ContextHubEventLogger.NanoappMessageEvent[] {
                    new ContextHubEventLogger.NanoappMessageEvent(23, 888, message1, true),
                    new ContextHubEventLogger.NanoappMessageEvent(34, 999, message2, false)
                };
        String[] eventStrings = generateEventDumpStrings(events);

        // log events and test sInstance.toString() contains event details
        sInstance.clear();
        sInstance.logMessageToNanoapp(888, message1, true);
        sInstance.logMessageToNanoapp(999, message2, false);
        String instanceDump = sInstance.toString();
        for (String eventString: eventStrings) {
            assertThat(eventString.length() > 0).isTrue();
            assertThat(instanceDump.contains(eventString)).isTrue();
        }

        // set the error codes for the events and verify
        sInstance.logReliableMessageToNanoappStatus(0, (byte) 0x02);
        sInstance.logReliableMessageToNanoappStatus(1, (byte) 0x03);
        events[0].setErrorCode((byte) 0x02);
        events[1].setErrorCode((byte) 0x03);
        eventStrings = generateEventDumpStrings(events);

        instanceDump = sInstance.toString();
        for (String eventString: eventStrings) {
            assertThat(eventString.length() > 0).isTrue();
            assertThat(instanceDump.contains(eventString)).isTrue();
        }
    }

    @Test
    public void testLogContextHubRestart() {
        ContextHubEventLogger.ContextHubRestartEvent[] events =
                new ContextHubEventLogger.ContextHubRestartEvent[] {
                    new ContextHubEventLogger.ContextHubRestartEvent(0, 1),
                    new ContextHubEventLogger.ContextHubRestartEvent(1, 2)
                };
        String[] eventStrings = generateEventDumpStrings(events);

        // log events and test sInstance.toString() contains event details
        sInstance.clear();
        sInstance.logContextHubRestart(1);
        sInstance.logContextHubRestart(2);
        String instanceDump = sInstance.toString();
        for (String eventString: eventStrings) {
            assertThat(eventString.length() > 0).isTrue();
            assertThat(instanceDump.contains(eventString)).isTrue();
        }
    }

    /**
     * Generates the part of the event's toString() method that should be contained in the dump
     * output (everything without the timestamp).
     *
     * @param events        the events
     * @return              the string representation of the events
     */
    private String[] generateEventDumpStrings(ContextHubEventLogger.ContextHubEventBase[] events) {
        return (String[]) Arrays.stream(events)
                .map(event -> event.toString().split(event.getClass().getSimpleName(), 2)[1])
                .toArray(String[]::new);
    }
}
