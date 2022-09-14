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

import static android.app.timezonedetector.ShellCommandTestSupport.createShellCommandWithArgsAndOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.os.Parcel;
import android.os.ShellCommand;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for non-SDK methods on {@link TimeState}.
 */
@RunWith(AndroidJUnit4.class)
public class TimeStateTest {

    @Test
    public void testEqualsAndHashcode() {
        UnixEpochTime time1 = new UnixEpochTime(1, 1);
        TimeState time1False_1 = new TimeState(time1, false);
        assertEqualsAndHashCode(time1False_1, time1False_1);

        TimeState time1False_2 = new TimeState(time1, false);
        assertEqualsAndHashCode(time1False_1, time1False_2);

        TimeState time1True = new TimeState(time1, true);
        assertNotEquals(time1False_1, time1True);

        UnixEpochTime time2 = new UnixEpochTime(2, 2);
        TimeState time2False = new TimeState(time2, false);
        assertNotEquals(time1False_1, time2False);
    }

    private static void assertEqualsAndHashCode(Object one, Object two) {
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
    }

    @Test
    public void testParceling() {
        UnixEpochTime time = new UnixEpochTime(1, 2);
        TimeState value = new TimeState(time, true);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(value, 0);

            parcel.setDataPosition(0);

            TimeState stringValueCopy =
                    parcel.readParcelable(null /* classLoader */, TimeState.class);
            assertEquals(value, stringValueCopy);
        } finally {
            parcel.recycle();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noElapsedRealtime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--unix_epoch_time 12345 --user_should_confirm_time true");
        TimeState.parseCommandLineArgs(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noUnixEpochTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --user_should_confirm_time true");
        TimeState.parseCommandLineArgs(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noUserShouldConfirmTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--unix_epoch_time 12345 --elapsed_realtime 54321");
        TimeState.parseCommandLineArgs(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_validSuggestion() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345 --user_should_confirm_time true");
        TimeState expectedValue = new TimeState(new UnixEpochTime(54321L, 12345L), true);
        TimeState actualValue = TimeState.parseCommandLineArgs(testShellCommand);
        assertEquals(expectedValue, actualValue);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345 --user_should_confirm_time true"
                        + " --bad_arg 0");
        TimeState.parseCommandLineArgs(testShellCommand);
    }
}
