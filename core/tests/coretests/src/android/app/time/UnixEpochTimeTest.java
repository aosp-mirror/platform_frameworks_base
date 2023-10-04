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

import android.os.ShellCommand;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for non-SDK methods on {@link UnixEpochTime}.
 *
 * <p>See also {@link android.app.time.cts.UnixEpochTimeTest} for SDK methods.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class UnixEpochTimeTest {

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
    public void testElapsedRealtimeDifference() {
        UnixEpochTime value1 = new UnixEpochTime(1000, 123L);
        assertEquals(0, UnixEpochTime.elapsedRealtimeDifference(value1, value1));

        UnixEpochTime value2 = new UnixEpochTime(1, 321L);
        assertEquals(999, UnixEpochTime.elapsedRealtimeDifference(value1, value2));
        assertEquals(-999, UnixEpochTime.elapsedRealtimeDifference(value2, value1));
    }
}
