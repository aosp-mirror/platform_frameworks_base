/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.timedetector;

import static com.android.server.timezonedetector.ShellCommandTestSupport.createShellCommandWithArgsAndOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.app.time.UnixEpochTime;
import android.os.ShellCommand;

import org.junit.Test;

public class GnssTimeSuggestionTest {

    private static final UnixEpochTime ARBITRARY_TIME =
            new UnixEpochTime(1111L, 2222L);

    @Test
    public void testEquals() {
        GnssTimeSuggestion one = new GnssTimeSuggestion(ARBITRARY_TIME);
        assertEquals(one, one);

        GnssTimeSuggestion two = new GnssTimeSuggestion(ARBITRARY_TIME);
        assertEquals(one, two);
        assertEquals(two, one);

        UnixEpochTime differentTime = new UnixEpochTime(
                ARBITRARY_TIME.getElapsedRealtimeMillis() + 1,
                ARBITRARY_TIME.getUnixEpochTimeMillis());
        GnssTimeSuggestion three = new GnssTimeSuggestion(differentTime);
        assertNotEquals(one, three);
        assertNotEquals(three, one);

        // DebugInfo must not be considered in equals().
        one.addDebugInfo("Debug info 1");
        two.addDebugInfo("Debug info 2");
        assertEquals(one, two);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noReferenceTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--unix_epoch_time 12345");
        GnssTimeSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noUnixEpochTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321");
        GnssTimeSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_validSuggestion() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345");
        UnixEpochTime timeSignal = new UnixEpochTime(54321L, 12345L);
        GnssTimeSuggestion expectedSuggestion = new GnssTimeSuggestion(timeSignal);
        GnssTimeSuggestion actualSuggestion =
                GnssTimeSuggestion.parseCommandLineArg(testShellCommand);
        assertEquals(expectedSuggestion, actualSuggestion);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345 --bad_arg 0");
        GnssTimeSuggestion.parseCommandLineArg(testShellCommand);
    }
}
