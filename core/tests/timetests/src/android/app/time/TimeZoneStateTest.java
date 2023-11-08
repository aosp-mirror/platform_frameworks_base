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

package android.app.time;

import static android.app.time.ParcelableTestSupport.assertEqualsAndHashCode;
import static android.app.time.ParcelableTestSupport.assertRoundTripParcelable;
import static android.app.timezonedetector.ShellCommandTestSupport.createShellCommandWithArgsAndOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.ShellCommand;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for non-SDK methods on {@link TimeZoneState}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TimeZoneStateTest {

    @Test
    public void testEqualsAndHashcode() {
        String zone1 = "Europe/London";
        TimeZoneState zone1False_1 = new TimeZoneState(zone1, false);
        assertEqualsAndHashCode(zone1False_1, zone1False_1);

        TimeZoneState zone1False_2 = new TimeZoneState(zone1, false);
        assertEqualsAndHashCode(zone1False_1, zone1False_2);

        TimeZoneState zone1True = new TimeZoneState(zone1, true);
        assertNotEquals(zone1False_1, zone1True);

        String zone2 = "Europe/Parise";
        TimeZoneState zone2False = new TimeZoneState(zone2, false);
        assertNotEquals(zone1False_1, zone2False);
    }

    @Test
    public void testParceling() {
        assertRoundTripParcelable(new TimeZoneState("Europe/London", true));
        assertRoundTripParcelable(new TimeZoneState("Europe/London", false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noZoneId() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--user_should_confirm_id true");
        TimeZoneState.parseCommandLineArgs(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noUserShouldConfirmId() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--zone_id Europe/London");
        TimeZoneState.parseCommandLineArgs(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_validSuggestion() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--zone_id Europe/London --user_should_confirm_id true");
        TimeZoneState expectedValue = new TimeZoneState("Europe/London", true);
        TimeZoneState actualValue = TimeZoneState.parseCommandLineArgs(testShellCommand);
        assertEquals(expectedValue, actualValue);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--zone_id Europe/London --user_should_confirm_id true --bad_arg 0");
        TimeZoneState.parseCommandLineArgs(testShellCommand);
    }
}
