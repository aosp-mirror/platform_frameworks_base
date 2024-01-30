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

package android.app.timezonedetector;

import static android.app.time.ParcelableTestSupport.assertRoundTripParcelable;
import static android.app.time.ParcelableTestSupport.roundTripParcelable;
import static android.app.timezonedetector.ShellCommandTestSupport.createShellCommandWithArgsAndOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.ShellCommand;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ManualTimeZoneSuggestionTest {

    private static final String ARBITRARY_ZONE_ID1 = "Europe/London";
    private static final String ARBITRARY_ZONE_ID2 = "Europe/Paris";

    @Test
    public void testEquals() {
        ManualTimeZoneSuggestion one = new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID1);
        assertEquals(one, one);

        ManualTimeZoneSuggestion two = new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID1);
        assertEquals(one, two);
        assertEquals(two, one);

        ManualTimeZoneSuggestion three = new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID2);
        assertNotEquals(one, three);
        assertNotEquals(three, one);

        // DebugInfo must not be considered in equals().
        one.addDebugInfo("Debug info 1");
        two.addDebugInfo("Debug info 2");
        assertEquals(one, two);
    }

    @Test
    public void testParcelable() {
        ManualTimeZoneSuggestion suggestion = new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID1);
        assertRoundTripParcelable(suggestion);

        // DebugInfo should also be stored (but is not checked by equals()
        suggestion.addDebugInfo("This is debug info");
        ManualTimeZoneSuggestion rtSuggestion = roundTripParcelable(suggestion);
        assertEquals(suggestion.getDebugInfo(), rtSuggestion.getDebugInfo());
    }

    @Test
    public void testPrintCommandLineOpts() throws Exception {
        try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
            ManualTimeZoneSuggestion.printCommandLineOpts(pw);
            assertTrue(sw.getBuffer().length() > 0);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noArgs() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions("");
        ManualTimeZoneSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_validSuggestion() {
        ShellCommand testShellCommand =
                createShellCommandWithArgsAndOptions("--zone_id Europe/London");
        ManualTimeZoneSuggestion expectedSuggestion =
                new ManualTimeZoneSuggestion("Europe/London");
        ManualTimeZoneSuggestion actualSuggestion =
                ManualTimeZoneSuggestion.parseCommandLineArg(testShellCommand);
        assertEquals(expectedSuggestion, actualSuggestion);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--zone_id Europe/London --bad_arg 0");
        ManualTimeZoneSuggestion.parseCommandLineArg(testShellCommand);
    }
}
