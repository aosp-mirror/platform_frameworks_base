/*
 * Copyright 2019 The Android Open Source Project
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

package android.app.timedetector;

import static android.app.time.ParcelableTestSupport.assertRoundTripParcelable;
import static android.app.time.ParcelableTestSupport.roundTripParcelable;
import static android.app.timezonedetector.ShellCommandTestSupport.createShellCommandWithArgsAndOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.app.time.UnixEpochTime;
import android.os.ShellCommand;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ManualTimeSuggestionTest {

    private static final UnixEpochTime ARBITRARY_TIME = new UnixEpochTime(1111L, 2222L);

    @Test
    public void testEquals() {
        ManualTimeSuggestion one = new ManualTimeSuggestion(ARBITRARY_TIME);
        assertEquals(one, one);

        ManualTimeSuggestion two = new ManualTimeSuggestion(ARBITRARY_TIME);
        assertEquals(one, two);
        assertEquals(two, one);

        UnixEpochTime differentTime = new UnixEpochTime(
                ARBITRARY_TIME.getElapsedRealtimeMillis() + 1,
                ARBITRARY_TIME.getUnixEpochTimeMillis());
        ManualTimeSuggestion three = new ManualTimeSuggestion(differentTime);
        assertNotEquals(one, three);
        assertNotEquals(three, one);

        // DebugInfo must not be considered in equals().
        one.addDebugInfo("Debug info 1");
        two.addDebugInfo("Debug info 2");
        assertEquals(one, two);
    }

    @Test
    public void testParcelable() {
        ManualTimeSuggestion suggestion = new ManualTimeSuggestion(ARBITRARY_TIME);
        assertRoundTripParcelable(suggestion);

        // DebugInfo should also be stored (but is not checked by equals()
        suggestion.addDebugInfo("This is debug info");
        ManualTimeSuggestion rtSuggestion = roundTripParcelable(suggestion);
        assertEquals(suggestion.getDebugInfo(), rtSuggestion.getDebugInfo());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noReferenceTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--unix_epoch_time 12345");
        ManualTimeSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noUnixEpochTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321");
        ManualTimeSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_validSuggestion() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345");
        UnixEpochTime timeSignal = new UnixEpochTime(54321L, 12345L);
        ManualTimeSuggestion expectedSuggestion = new ManualTimeSuggestion(timeSignal);
        ManualTimeSuggestion actualSuggestion =
                ManualTimeSuggestion.parseCommandLineArg(testShellCommand);
        assertEquals(expectedSuggestion, actualSuggestion);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345 --bad_arg 0");
        ManualTimeSuggestion.parseCommandLineArg(testShellCommand);
    }
}
