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
public class TelephonyTimeSuggestionTest {
    private static final int SLOT_INDEX = 99999;

    @Test
    public void testEquals() {
        TelephonyTimeSuggestion.Builder builder1 = new TelephonyTimeSuggestion.Builder(SLOT_INDEX);
        {
            TelephonyTimeSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        TelephonyTimeSuggestion.Builder builder2 = new TelephonyTimeSuggestion.Builder(SLOT_INDEX);
        {
            TelephonyTimeSuggestion one = builder1.build();
            TelephonyTimeSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        builder1.setUnixEpochTime(new UnixEpochTime(1111L, 2222L));
        {
            TelephonyTimeSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        builder2.setUnixEpochTime(new UnixEpochTime(1111L, 2222L));
        {
            TelephonyTimeSuggestion one = builder1.build();
            TelephonyTimeSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        TelephonyTimeSuggestion.Builder builder3 =
                new TelephonyTimeSuggestion.Builder(SLOT_INDEX + 1);
        builder3.setUnixEpochTime(new UnixEpochTime(1111L, 2222L));
        {
            TelephonyTimeSuggestion one = builder1.build();
            TelephonyTimeSuggestion three = builder3.build();
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }

        // DebugInfo must not be considered in equals().
        builder1.addDebugInfo("Debug info 1");
        builder2.addDebugInfo("Debug info 2");
        {
            TelephonyTimeSuggestion one = builder1.build();
            TelephonyTimeSuggestion two = builder2.build();
            assertEquals(one, two);
        }
    }

    @Test
    public void testParcelable() {
        TelephonyTimeSuggestion.Builder builder = new TelephonyTimeSuggestion.Builder(SLOT_INDEX);
        assertRoundTripParcelable(builder.build());

        builder.setUnixEpochTime(new UnixEpochTime(1111L, 2222L));
        assertRoundTripParcelable(builder.build());

        // DebugInfo should also be stored (but is not checked by equals()
        {
            TelephonyTimeSuggestion suggestion1 = builder.build();
            builder.addDebugInfo("This is debug info");
            TelephonyTimeSuggestion rtSuggestion1 = roundTripParcelable(suggestion1);
            assertEquals(suggestion1.getDebugInfo(), rtSuggestion1.getDebugInfo());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noSlotIndex() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345");
        TelephonyTimeSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noReferenceTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--slot_index 0 --unix_epoch_time 12345");
        TelephonyTimeSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noUnixEpochTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--slot_index 0 --elapsed_realtime 54321");
        TelephonyTimeSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_validSuggestion() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--slot_index 0 --elapsed_realtime 54321 --unix_epoch_time 12345");
        TelephonyTimeSuggestion expectedSuggestion =
                new TelephonyTimeSuggestion.Builder(0)
                        .setUnixEpochTime(new UnixEpochTime(54321L, 12345L))
                        .build();
        TelephonyTimeSuggestion actualSuggestion =
                TelephonyTimeSuggestion.parseCommandLineArg(testShellCommand);
        assertEquals(expectedSuggestion, actualSuggestion);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--slot_index 0 --elapsed_realtime 54321 --unix_epoch_time 12345 --bad_arg 0");
        TelephonyTimeSuggestion.parseCommandLineArg(testShellCommand);
    }
}
