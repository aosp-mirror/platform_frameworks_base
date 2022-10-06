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
 * Tests for non-SDK methods on {@link UnixEpochTime}.
 */
@RunWith(AndroidJUnit4.class)
public class UnixEpochTimeTest {

    @Test
    public void testEqualsAndHashcode() {
        UnixEpochTime one1000one = new UnixEpochTime(1000, 1);
        assertEqualsAndHashCode(one1000one, one1000one);

        UnixEpochTime one1000two = new UnixEpochTime(1000, 1);
        assertEqualsAndHashCode(one1000one, one1000two);

        UnixEpochTime two1000 = new UnixEpochTime(1000, 2);
        assertNotEquals(one1000one, two1000);

        UnixEpochTime one2000 = new UnixEpochTime(2000, 1);
        assertNotEquals(one1000one, one2000);
    }

    private static void assertEqualsAndHashCode(Object one, Object two) {
        assertEquals(one, two);
        assertEquals(one.hashCode(), two.hashCode());
    }

    @Test
    public void testParceling() {
        UnixEpochTime value = new UnixEpochTime(1000, 1);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(value, 0);

            parcel.setDataPosition(0);

            UnixEpochTime stringValueCopy =
                    parcel.readParcelable(null /* classLoader */, UnixEpochTime.class);
            assertEquals(value, stringValueCopy);
        } finally {
            parcel.recycle();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noElapsedRealtime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--unix_epoch_time 12345");
        UnixEpochTime.parseCommandLineArgs(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noUnixEpochTime() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321");
        UnixEpochTime.parseCommandLineArgs(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_validSuggestion() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345");
        UnixEpochTime expectedValue = new UnixEpochTime(54321L, 12345L);
        UnixEpochTime actualValue = UnixEpochTime.parseCommandLineArgs(testShellCommand);
        assertEquals(expectedValue, actualValue);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--elapsed_realtime 54321 --unix_epoch_time 12345 --bad_arg 0");
        UnixEpochTime.parseCommandLineArgs(testShellCommand);
    }

    @Test
    public void testAt() {
        long timeMillis = 1000L;
        int elapsedRealtimeMillis = 100;
        UnixEpochTime unixEpochTime = new UnixEpochTime(elapsedRealtimeMillis, timeMillis);
        // Reference time is after the timestamp.
        UnixEpochTime at125 = unixEpochTime.at(125);
        assertEquals(timeMillis + (125 - elapsedRealtimeMillis), at125.getUnixEpochTimeMillis());
        assertEquals(125, at125.getElapsedRealtimeMillis());

        // Reference time is before the timestamp.
        UnixEpochTime at75 = unixEpochTime.at(75);
        assertEquals(timeMillis + (75 - elapsedRealtimeMillis), at75.getUnixEpochTimeMillis());
        assertEquals(75, at75.getElapsedRealtimeMillis());
    }

    @Test
    public void testElapsedRealtimeDifference() {
        UnixEpochTime value1 = new UnixEpochTime(1000, 123L);
        assertEquals(0, UnixEpochTime.elapsedRealtimeDifference(value1, value1));

        UnixEpochTime value2 = new UnixEpochTime(1, 321L);
        assertEquals(999, UnixEpochTime.elapsedRealtimeDifference(value1, value2));
        assertEquals(-999, UnixEpochTime.elapsedRealtimeDifference(value2, value1));
    }
}
