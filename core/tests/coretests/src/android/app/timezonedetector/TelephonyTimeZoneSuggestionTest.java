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

import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;
import static android.app.timezonedetector.ParcelableTestSupport.roundTripParcelable;
import static android.app.timezonedetector.ShellCommandTestSupport.createShellCommandWithArgsAndOptions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.os.ShellCommand;

import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

public class TelephonyTimeZoneSuggestionTest {
    private static final int SLOT_INDEX = 99999;

    @Test
    public void testEquals() {
        TelephonyTimeZoneSuggestion.Builder builder1 =
                new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            assertEquals(one, one);
        }

        TelephonyTimeZoneSuggestion.Builder builder2 =
                new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            assertEquals(one, two);
            assertEquals(two, one);
        }

        TelephonyTimeZoneSuggestion.Builder builder3 =
                new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX + 1);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion three = builder3.build();
            assertNotEquals(one, three);
            assertNotEquals(three, one);
        }

        builder1.setZoneId("Europe/London");
        builder1.setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY);
        builder1.setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            assertNotEquals(one, two);
        }

        builder2.setZoneId("Europe/Paris");
        builder2.setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY);
        builder2.setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setZoneId("Europe/Paris");
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            assertEquals(one, two);
        }

        builder1.setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID);
        builder2.setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            assertEquals(one, two);
        }

        builder1.setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        builder2.setQuality(
                TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setQuality(
                TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS);
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            assertEquals(one, two);
        }

        // DebugInfo must not be considered in equals().
        {
            TelephonyTimeZoneSuggestion one = builder1.build();
            TelephonyTimeZoneSuggestion two = builder2.build();
            one.addDebugInfo("Debug info 1");
            two.addDebugInfo("Debug info 2");
            assertEquals(one, two);
        }
    }

    @Test(expected = RuntimeException.class)
    public void testBuilderValidates_emptyZone_badMatchType() {
        TelephonyTimeZoneSuggestion.Builder builder =
                new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX);
        // No zone ID, so match type should be left unset.
        builder.setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET);
        builder.build();
    }

    @Test(expected = RuntimeException.class)
    public void testBuilderValidates_zoneSet_badMatchType() {
        TelephonyTimeZoneSuggestion.Builder builder =
                new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX);
        builder.setZoneId("Europe/London");
        builder.setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        builder.build();
    }

    @Test
    public void testParcelable() {
        TelephonyTimeZoneSuggestion.Builder builder =
                new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX);
        assertRoundTripParcelable(builder.build());

        builder.setZoneId("Europe/London");
        builder.setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID);
        builder.setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE);
        TelephonyTimeZoneSuggestion suggestion1 = builder.build();
        assertRoundTripParcelable(suggestion1);

        // DebugInfo should also be stored (but is not checked by equals()
        String debugString = "This is debug info";
        suggestion1.addDebugInfo(debugString);
        TelephonyTimeZoneSuggestion suggestion1_2 = roundTripParcelable(suggestion1);
        assertEquals(suggestion1, suggestion1_2);
        assertTrue(suggestion1_2.getDebugInfo().contains(debugString));
    }

    @Test
    public void testPrintCommandLineOpts() throws Exception {
        try (StringWriter sw = new StringWriter(); PrintWriter pw = new PrintWriter(sw)) {
            TelephonyTimeZoneSuggestion.printCommandLineOpts(pw);
            assertTrue(sw.getBuffer().length() > 0);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noArgs() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions("");
        TelephonyTimeZoneSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_noSlotIndex() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions("--zone_id _");
        TelephonyTimeZoneSuggestion.parseCommandLineArg(testShellCommand);
    }

    @Test
    public void testParseCommandLineArg_validEmptyZoneIdSuggestion() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--slot_index 0 --zone_id _");
        TelephonyTimeZoneSuggestion expectedSuggestion =
                new TelephonyTimeZoneSuggestion.Builder(0).build();
        TelephonyTimeZoneSuggestion actualSuggestion =
                TelephonyTimeZoneSuggestion.parseCommandLineArg(testShellCommand);
        assertEquals(expectedSuggestion, actualSuggestion);
    }

    @Test
    public void testParseCommandLineArg_validNonEmptySuggestion() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--slot_index 0 --zone_id Europe/London --quality single --match_type country");
        TelephonyTimeZoneSuggestion expectedSuggestion =
                new TelephonyTimeZoneSuggestion.Builder(0)
                        .setZoneId("Europe/London")
                        .setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE)
                        .setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY)
                        .build();
        TelephonyTimeZoneSuggestion actualSuggestion =
                TelephonyTimeZoneSuggestion.parseCommandLineArg(testShellCommand);
        assertEquals(expectedSuggestion, actualSuggestion);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseCommandLineArg_unknownArgument() {
        ShellCommand testShellCommand = createShellCommandWithArgsAndOptions(
                "--slot_index 0 --zone_id _ --bad_arg 0");
        TelephonyTimeZoneSuggestion.parseCommandLineArg(testShellCommand);
    }
}
