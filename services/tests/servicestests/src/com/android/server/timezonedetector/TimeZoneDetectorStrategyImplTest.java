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

package com.android.server.timezonedetector;

import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_APPLICABLE;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_NOT_SUPPORTED;
import static android.app.timezonedetector.TimeZoneCapabilities.CAPABILITY_POSSESSED;

import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_HIGH;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_HIGHEST;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_LOW;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_MEDIUM;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_NONE;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_USAGE_THRESHOLD;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.annotation.UserIdInt;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.MatchType;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.Quality;
import android.app.timezonedetector.TimeZoneCapabilities;
import android.app.timezonedetector.TimeZoneConfiguration;
import android.util.IndentingPrintWriter;

import com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.QualifiedTelephonyTimeZoneSuggestion;

import org.junit.Before;
import org.junit.Test;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * White-box unit tests for {@link TimeZoneDetectorStrategyImpl}.
 */
public class TimeZoneDetectorStrategyImplTest {

    /** A time zone used for initialization that does not occur elsewhere in tests. */
    private static final @UserIdInt int USER_ID = 9876;
    private static final String ARBITRARY_TIME_ZONE_ID = "Etc/UTC";
    private static final int SLOT_INDEX1 = 10000;
    private static final int SLOT_INDEX2 = 20000;

    // Telephony test cases are ordered so that each successive one is of the same or higher score
    // than the previous.
    private static final TelephonyTestCase[] TELEPHONY_TEST_CASES = new TelephonyTestCase[] {
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_MEDIUM),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_MEDIUM),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGH),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGH),
            newTelephonyTestCase(MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_HIGHEST),
            newTelephonyTestCase(MATCH_TYPE_EMULATOR_ZONE_ID, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGHEST),
    };

    private static final TimeZoneConfiguration CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED =
            new TimeZoneConfiguration.Builder()
                    .setAutoDetectionEnabled(true)
                    .build();

    private static final TimeZoneConfiguration CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED =
            new TimeZoneConfiguration.Builder()
                    .setAutoDetectionEnabled(false)
                    .build();

    private TimeZoneDetectorStrategyImpl mTimeZoneDetectorStrategy;
    private FakeCallback mFakeCallback;
    private MockStrategyListener mMockStrategyListener;

    @Before
    public void setUp() {
        mFakeCallback = new FakeCallback();
        mMockStrategyListener = new MockStrategyListener();
        mTimeZoneDetectorStrategy = new TimeZoneDetectorStrategyImpl(mFakeCallback);
        mFakeCallback.setStrategyForSettingsCallbacks(mTimeZoneDetectorStrategy);
        mTimeZoneDetectorStrategy.setStrategyListener(mMockStrategyListener);
    }

    @Test
    public void testGetCapabilities() {
        new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);
        TimeZoneCapabilities expectedCapabilities = mFakeCallback.getCapabilities(USER_ID);
        assertEquals(expectedCapabilities, mTimeZoneDetectorStrategy.getCapabilities(USER_ID));
    }

    @Test
    public void testGetConfiguration() {
        new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);
        TimeZoneConfiguration expectedConfiguration = mFakeCallback.getConfiguration(USER_ID);
        assertTrue(expectedConfiguration.isComplete());
        assertEquals(expectedConfiguration, mTimeZoneDetectorStrategy.getConfiguration(USER_ID));
    }

    @Test
    public void testCapabilitiesTestInfra_unrestricted() {
        Script script = new Script();

        script.initializeUser(USER_ID, UserCase.UNRESTRICTED,
                CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);
        {
            // Check the fake test infra is doing what is expected.
            TimeZoneCapabilities capabilities = mFakeCallback.getCapabilities(USER_ID);
            assertEquals(CAPABILITY_POSSESSED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_APPLICABLE, capabilities.getSuggestManualTimeZone());
        }

        script.initializeUser(USER_ID, UserCase.UNRESTRICTED,
                CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED);
        {
            // Check the fake test infra is doing what is expected.
            TimeZoneCapabilities capabilities = mFakeCallback.getCapabilities(USER_ID);
            assertEquals(CAPABILITY_POSSESSED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZone());
        }
    }

    @Test
    public void testCapabilitiesTestInfra_restricted() {
        Script script = new Script();

        script.initializeUser(USER_ID, UserCase.RESTRICTED,
                CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);
        {
            // Check the fake test infra is doing what is expected.
            TimeZoneCapabilities capabilities = mFakeCallback.getCapabilities(USER_ID);
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSuggestManualTimeZone());
        }

        script.initializeUser(USER_ID, UserCase.RESTRICTED,
                CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED);
        {
            // Check the fake test infra is doing what is expected.
            TimeZoneCapabilities capabilities = mFakeCallback.getCapabilities(USER_ID);
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_NOT_ALLOWED, capabilities.getSuggestManualTimeZone());
        }
    }

    @Test
    public void testCapabilitiesTestInfra_autoDetectNotSupported() {
        Script script = new Script();

        script.initializeUser(USER_ID, UserCase.AUTO_DETECT_NOT_SUPPORTED,
                CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);
        {
            // Check the fake test infra is doing what is expected.
            TimeZoneCapabilities capabilities = mFakeCallback.getCapabilities(USER_ID);
            assertEquals(CAPABILITY_NOT_SUPPORTED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZone());
        }

        script.initializeUser(USER_ID, UserCase.AUTO_DETECT_NOT_SUPPORTED,
                CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED);
        {
            // Check the fake test infra is doing what is expected.
            TimeZoneCapabilities capabilities = mFakeCallback.getCapabilities(USER_ID);
            assertEquals(CAPABILITY_NOT_SUPPORTED, capabilities.getConfigureAutoDetectionEnabled());
            assertEquals(CAPABILITY_POSSESSED, capabilities.getSuggestManualTimeZone());
        }
    }

    @Test
    public void testUpdateConfiguration_unrestricted() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        // Set the configuration with auto detection enabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        // Nothing should have happened: it was initialized in this state.
        script.verifyConfigurationNotChanged();

        // Update the configuration with auto detection disabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED);

        // The settings should have been changed and the StrategyListener onChange() called.
        script.verifyConfigurationChangedAndReset(
                USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED);

        // Update the configuration with auto detection enabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        // The settings should have been changed and the StrategyListener onChange() called.
        script.verifyConfigurationChangedAndReset(USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);
    }

    @Test
    public void testUpdateConfiguration_restricted() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.RESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        // Try to update the configuration with auto detection disabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();

        // Update the configuration with auto detection enabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();
    }

    @Test
    public void testUpdateConfiguration_autoDetectNotSupported() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.AUTO_DETECT_NOT_SUPPORTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        // Try to update the configuration with auto detection disabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();

        // Update the configuration with auto detection enabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();
    }

    @Test
    public void testEmptyTelephonySuggestions() {
        TelephonyTimeZoneSuggestion slotIndex1TimeZoneSuggestion =
                createEmptySlotIndex1Suggestion();
        TelephonyTimeZoneSuggestion slotIndex2TimeZoneSuggestion =
                createEmptySlotIndex2Suggestion();
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        script.simulateTelephonyTimeZoneSuggestion(slotIndex1TimeZoneSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        QualifiedTelephonyTimeZoneSuggestion expectedSlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(slotIndex1TimeZoneSuggestion,
                        TELEPHONY_SCORE_NONE);
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
        assertNull(mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

        script.simulateTelephonyTimeZoneSuggestion(slotIndex2TimeZoneSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        QualifiedTelephonyTimeZoneSuggestion expectedSlotIndex2ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(slotIndex2TimeZoneSuggestion,
                        TELEPHONY_SCORE_NONE);
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
        assertEquals(expectedSlotIndex2ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
        // SlotIndex1 should always beat slotIndex2, all other things being equal.
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
    }

    @Test
    public void testTelephonySuggestionsWhenTimeZoneUninitialized() {
        assertTrue(TELEPHONY_SCORE_LOW < TELEPHONY_SCORE_USAGE_THRESHOLD);
        assertTrue(TELEPHONY_SCORE_HIGH >= TELEPHONY_SCORE_USAGE_THRESHOLD);
        TelephonyTestCase testCase = newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW);
        TelephonyTestCase testCase2 = newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH);

        Script script = new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        // A low quality suggestions will not be taken: The device time zone setting is left
        // uninitialized.
        {
            TelephonyTimeZoneSuggestion lowQualitySuggestion =
                    testCase.createSuggestion(SLOT_INDEX1, "America/New_York");
            script.simulateTelephonyTimeZoneSuggestion(lowQualitySuggestion)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            lowQualitySuggestion, testCase.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }

        // A good quality suggestion will be used.
        {
            TelephonyTimeZoneSuggestion goodQualitySuggestion =
                    testCase2.createSuggestion(SLOT_INDEX1, "Europe/London");
            script.simulateTelephonyTimeZoneSuggestion(goodQualitySuggestion)
                    .verifyTimeZoneChangedAndReset(goodQualitySuggestion);

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            goodQualitySuggestion, testCase2.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }

        // A low quality suggestion will be accepted, but not used to set the device time zone.
        {
            TelephonyTimeZoneSuggestion lowQualitySuggestion2 =
                    testCase.createSuggestion(SLOT_INDEX1, "America/Los_Angeles");
            script.simulateTelephonyTimeZoneSuggestion(lowQualitySuggestion2)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            lowQualitySuggestion2, testCase.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }
    }

    /**
     * Confirms that toggling the auto time zone detection setting has the expected behavior when
     * the strategy is "opinionated".
     */
    @Test
    public void testTogglingAutoTimeZoneDetection() {
        Script script = new Script();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            // Start with the device in a known state.
            script.initializeUser(USER_ID, UserCase.UNRESTRICTED,
                    CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED)
                    .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

            TelephonyTimeZoneSuggestion suggestion =
                    testCase.createSuggestion(SLOT_INDEX1, "Europe/London");
            script.simulateTelephonyTimeZoneSuggestion(suggestion);

            // When time zone detection is not enabled, the time zone suggestion will not be set
            // regardless of the score.
            script.verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(suggestion, testCase.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Toggling the time zone setting on should cause the device setting to be set.
            script.simulateAutoTimeZoneDetectionEnabled(USER_ID, true);

            // When time zone detection is already enabled the suggestion (if it scores highly
            // enough) should be set immediately.
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Toggling the time zone setting should off should do nothing.
            script.simulateAutoTimeZoneDetectionEnabled(USER_ID, false)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }
    }

    @Test
    public void testTelephonySuggestionsSingleSlotId() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }

        /*
         * This is the same test as above but the test cases are in
         * reverse order of their expected score. New suggestions always replace previous ones:
         * there's effectively no history and so ordering shouldn't make any difference.
         */

        // Each test case will have the same or lower score than the last.
        ArrayList<TelephonyTestCase> descendingCasesByScore =
                new ArrayList<>(Arrays.asList(TELEPHONY_TEST_CASES));
        Collections.reverse(descendingCasesByScore);

        for (TelephonyTestCase testCase : descendingCasesByScore) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }
    }

    private void makeSlotIndex1SuggestionAndCheckState(Script script, TelephonyTestCase testCase) {
        // Give the next suggestion a different zone from the currently set device time zone;
        String currentZoneId = mFakeCallback.getDeviceTimeZone();
        String suggestionZoneId =
                "Europe/London".equals(currentZoneId) ? "Europe/Paris" : "Europe/London";
        TelephonyTimeZoneSuggestion zoneSlotIndex1Suggestion =
                testCase.createSuggestion(SLOT_INDEX1, suggestionZoneId);
        QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(
                        zoneSlotIndex1Suggestion, testCase.expectedScore);

        script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion);
        if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
            script.verifyTimeZoneChangedAndReset(zoneSlotIndex1Suggestion);
        } else {
            script.verifyTimeZoneNotChanged();
        }

        // Assert internal service state.
        assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
        assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
    }

    /**
     * Tries a set of test cases to see if the slotIndex with the lowest numeric value is given
     * preference. This test also confirms that the time zone setting would only be set if a
     * suggestion is of sufficient quality.
     */
    @Test
    public void testTelephonySuggestionMultipleSlotIndexSuggestionScoringAndSlotIndexBias() {
        String[] zoneIds = { "Europe/London", "Europe/Paris" };
        TelephonyTimeZoneSuggestion emptySlotIndex1Suggestion = createEmptySlotIndex1Suggestion();
        TelephonyTimeZoneSuggestion emptySlotIndex2Suggestion = createEmptySlotIndex2Suggestion();
        QualifiedTelephonyTimeZoneSuggestion expectedEmptySlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion,
                        TELEPHONY_SCORE_NONE);
        QualifiedTelephonyTimeZoneSuggestion expectedEmptySlotIndex2ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion,
                        TELEPHONY_SCORE_NONE);

        Script script = new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                // Initialize the latest suggestions as empty so we don't need to worry about nulls
                // below for the first loop.
                .simulateTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion)
                .simulateTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion)
                .resetConfigurationTracking();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            TelephonyTimeZoneSuggestion zoneSlotIndex1Suggestion =
                    testCase.createSuggestion(SLOT_INDEX1, zoneIds[0]);
            TelephonyTimeZoneSuggestion zoneSlotIndex2Suggestion =
                    testCase.createSuggestion(SLOT_INDEX2, zoneIds[1]);
            QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex1ScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion,
                            testCase.expectedScore);
            QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex2ScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(zoneSlotIndex2Suggestion,
                            testCase.expectedScore);

            // Start the test by making a suggestion for slotIndex1.
            script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion);
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(zoneSlotIndex1Suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedEmptySlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // SlotIndex2 then makes an alternative suggestion with an identical score. SlotIndex1's
            // suggestion should still "win" if it is above the required threshold.
            script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex2Suggestion);
            script.verifyTimeZoneNotChanged();

            // Assert internal service state.
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedZoneSlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
            // SlotIndex1 should always beat slotIndex2, all other things being equal.
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Withdrawing slotIndex1's suggestion should leave slotIndex2 as the new winner. Since
            // the zoneId is different, the time zone setting should be updated if the score is high
            // enough.
            script.simulateTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion);
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(zoneSlotIndex2Suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            assertEquals(expectedEmptySlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedZoneSlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
            assertEquals(expectedZoneSlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Reset the state for the next loop.
            script.simulateTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion)
                    .verifyTimeZoneNotChanged();
            assertEquals(expectedEmptySlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedEmptySlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
        }
    }

    /**
     * The {@link TimeZoneDetectorStrategyImpl.Callback} is left to detect whether changing
     * the time zone is actually necessary. This test proves that the service doesn't assume it
     * knows the current setting.
     */
    @Test
    public void testTelephonySuggestionTimeZoneDetectorStrategyDoesNotAssumeCurrentSetting() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED);

        TelephonyTestCase testCase = newTelephonyTestCase(
                MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH);
        TelephonyTimeZoneSuggestion losAngelesSuggestion =
                testCase.createSuggestion(SLOT_INDEX1, "America/Los_Angeles");
        TelephonyTimeZoneSuggestion newYorkSuggestion =
                testCase.createSuggestion(SLOT_INDEX1, "America/New_York");

        // Initialization.
        script.simulateTelephonyTimeZoneSuggestion(losAngelesSuggestion)
                .verifyTimeZoneChangedAndReset(losAngelesSuggestion);
        // Suggest it again - it should not be set because it is already set.
        script.simulateTelephonyTimeZoneSuggestion(losAngelesSuggestion)
                .verifyTimeZoneNotChanged();

        // Toggling time zone detection should set the device time zone only if the current setting
        // value is different from the most recent telephony suggestion.
        script.simulateAutoTimeZoneDetectionEnabled(USER_ID, false)
                .verifyTimeZoneNotChanged()
                .simulateAutoTimeZoneDetectionEnabled(USER_ID, true)
                .verifyTimeZoneNotChanged();

        // Simulate a user turning auto detection off, a new suggestion being made while auto
        // detection is off, and the user turning it on again.
        script.simulateAutoTimeZoneDetectionEnabled(USER_ID, false)
                .simulateTelephonyTimeZoneSuggestion(newYorkSuggestion)
                .verifyTimeZoneNotChanged();
        // Latest suggestion should be used.
        script.simulateAutoTimeZoneDetectionEnabled(USER_ID, true)
                .verifyTimeZoneChangedAndReset(newYorkSuggestion);
    }

    @Test
    public void testManualSuggestion_unrestricted_simulateAutoTimeZoneEnabled() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Auto time zone detection is enabled so the manual suggestion should be ignored.
        script.simulateManualTimeZoneSuggestion(
                USER_ID, createManualSuggestion("Europe/Paris"), false /* expectedResult */)
                .verifyTimeZoneNotChanged();
    }

    @Test
    public void testManualSuggestion_restricted_simulateAutoTimeZoneEnabled() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.RESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Auto time zone detection is enabled so the manual suggestion should be ignored.
        script.simulateManualTimeZoneSuggestion(
                USER_ID, createManualSuggestion("Europe/Paris"), false /* expectedResult */)
            .verifyTimeZoneNotChanged();
    }

    @Test
    public void testManualSuggestion_autoDetectNotSupported_simulateAutoTimeZoneEnabled() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.AUTO_DETECT_NOT_SUPPORTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Auto time zone detection is enabled so the manual suggestion should be ignored.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, true /* expectedResult */)
            .verifyTimeZoneChangedAndReset(manualSuggestion);
    }

    @Test
    public void testManualSuggestion_unrestricted_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Auto time zone detection is disabled so the manual suggestion should be used.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, true /* expectedResult */)
            .verifyTimeZoneChangedAndReset(manualSuggestion);
    }

    @Test
    public void testManualSuggestion_restricted_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.RESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Restricted users do not have the capability.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, false /* expectedResult */)
                .verifyTimeZoneNotChanged();
    }

    @Test
    public void testManualSuggestion_autoDetectNotSupported_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeUser(USER_ID, UserCase.AUTO_DETECT_NOT_SUPPORTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Unrestricted users have the capability.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, true /* expectedResult */)
                .verifyTimeZoneChangedAndReset(manualSuggestion);
    }

    @Test
    public void testAddDumpable() {
        new Script()
                .initializeUser(USER_ID, UserCase.UNRESTRICTED,
                        CONFIG_AUTO_TIME_ZONE_DETECTION_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        AtomicBoolean dumpCalled = new AtomicBoolean(false);
        class FakeDumpable implements Dumpable {
            @Override
            public void dump(IndentingPrintWriter pw, String[] args) {
                dumpCalled.set(true);
            }
        }

        mTimeZoneDetectorStrategy.addDumpable(new FakeDumpable());
        IndentingPrintWriter ipw = new IndentingPrintWriter(new StringWriter());
        String[] args = {"ArgOne", "ArgTwo"};
        mTimeZoneDetectorStrategy.dump(ipw, args);

        assertTrue(dumpCalled.get());
    }

    private static ManualTimeZoneSuggestion createManualSuggestion(String zoneId) {
        return new ManualTimeZoneSuggestion(zoneId);
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex1Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX1).build();
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex2Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX2).build();
    }

    static class FakeCallback implements TimeZoneDetectorStrategyImpl.Callback {

        private TimeZoneCapabilities mCapabilities;
        private final TestState<UserConfiguration> mConfiguration = new TestState<>();
        private final TestState<String> mTimeZoneId = new TestState<>();
        private TimeZoneDetectorStrategyImpl mStrategy;

        void setStrategyForSettingsCallbacks(TimeZoneDetectorStrategyImpl strategy) {
            assertNotNull(strategy);
            mStrategy = strategy;
        }

        void initializeUser(@UserIdInt int userId, TimeZoneCapabilities capabilities,
                TimeZoneConfiguration configuration) {
            assertEquals(userId, capabilities.getUserId());
            mCapabilities = capabilities;
            assertTrue(configuration.isComplete());
            mConfiguration.init(new UserConfiguration(userId, configuration));
        }

        void initializeTimeZoneSetting(String zoneId) {
            mTimeZoneId.init(zoneId);
        }

        @Override
        public TimeZoneCapabilities getCapabilities(@UserIdInt int userId) {
            assertEquals(userId, mCapabilities.getUserId());
            return mCapabilities;
        }

        @Override
        public TimeZoneConfiguration getConfiguration(@UserIdInt int userId) {
            UserConfiguration latest = mConfiguration.getLatest();
            assertEquals(userId, latest.userId);
            return latest.configuration;
        }

        @Override
        public void setConfiguration(@UserIdInt int userId, TimeZoneConfiguration newConfig) {
            assertNotNull(newConfig);
            assertTrue(newConfig.isComplete());

            UserConfiguration latestUserConfig = mConfiguration.getLatest();
            assertEquals(userId, latestUserConfig.userId);
            TimeZoneConfiguration oldConfig = latestUserConfig.configuration;

            mConfiguration.set(new UserConfiguration(userId, newConfig));

            if (!newConfig.equals(oldConfig)) {
                if (oldConfig.isAutoDetectionEnabled() != newConfig.isAutoDetectionEnabled()) {
                    // Simulate what happens when the auto detection enabled configuration is
                    // changed.
                    mStrategy.handleAutoTimeZoneConfigChanged();
                }
            }
        }

        @Override
        public boolean isAutoDetectionEnabled() {
            return mConfiguration.getLatest().configuration.isAutoDetectionEnabled();
        }

        @Override
        public boolean isDeviceTimeZoneInitialized() {
            return mTimeZoneId.getLatest() != null;
        }

        @Override
        public String getDeviceTimeZone() {
            return mTimeZoneId.getLatest();
        }

        @Override
        public void setDeviceTimeZone(String zoneId) {
            mTimeZoneId.set(zoneId);
        }

        void assertKnownUser(int userId) {
            assertEquals(userId, mCapabilities.getUserId());
            assertEquals(userId, mConfiguration.getLatest().userId);
        }

        void assertTimeZoneNotChanged() {
            mTimeZoneId.assertHasNotBeenSet();
        }

        void assertTimeZoneChangedTo(String timeZoneId) {
            mTimeZoneId.assertHasBeenSet();
            mTimeZoneId.assertChangeCount(1);
            mTimeZoneId.assertLatestEquals(timeZoneId);
        }

        void commitAllChanges() {
            mTimeZoneId.commitLatest();
            mConfiguration.commitLatest();
        }
    }

    private static final class UserConfiguration {
        public final @UserIdInt int userId;
        public final TimeZoneConfiguration configuration;

        UserConfiguration(int userId, TimeZoneConfiguration configuration) {
            this.userId = userId;
            this.configuration = configuration;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            UserConfiguration that = (UserConfiguration) o;
            return userId == that.userId
                    && Objects.equals(configuration, that.configuration);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, configuration);
        }

        @Override
        public String toString() {
            return "UserConfiguration{"
                    + "userId=" + userId
                    + ", configuration=" + configuration
                    + '}';
        }
    }

    /** Some piece of state that tests want to track. */
    private static class TestState<T> {
        private T mInitialValue;
        private LinkedList<T> mValues = new LinkedList<>();

        void init(T value) {
            mValues.clear();
            mInitialValue = value;
        }

        void set(T value) {
            mValues.addFirst(value);
        }

        boolean hasBeenSet() {
            return mValues.size() > 0;
        }

        void assertHasNotBeenSet() {
            assertFalse(hasBeenSet());
        }

        void assertHasBeenSet() {
            assertTrue(hasBeenSet());
        }

        void commitLatest() {
            if (hasBeenSet()) {
                mInitialValue = mValues.getLast();
                mValues.clear();
            }
        }

        void assertLatestEquals(T expected) {
            assertEquals(expected, getLatest());
        }

        void assertChangeCount(int expectedCount) {
            assertEquals(expectedCount, mValues.size());
        }

        public T getLatest() {
            if (hasBeenSet()) {
                return mValues.getFirst();
            }
            return mInitialValue;
        }
    }

    /** Simulated user test cases. */
    enum UserCase {
        /** A catch-all for users that can set auto time zone config. */
        UNRESTRICTED,
        /** A catch-all for users that can't set auto time zone config. */
        RESTRICTED,
        /**
         * Like {@link #UNRESTRICTED}, but auto tz detection is not
         * supported on the device.
         */
        AUTO_DETECT_NOT_SUPPORTED,
    }

    /**
     * Creates a {@link TimeZoneCapabilities} object for a user in the specific role with the
     * supplied configuration.
     */
    private static TimeZoneCapabilities createCapabilities(
            int userId, UserCase userRole, TimeZoneConfiguration configuration) {
        switch (userRole) {
            case UNRESTRICTED: {
                int suggestManualTimeZoneCapability = configuration.isAutoDetectionEnabled()
                        ? CAPABILITY_NOT_APPLICABLE : CAPABILITY_POSSESSED;
                return new TimeZoneCapabilities.Builder(userId)
                        .setConfigureAutoDetectionEnabled(CAPABILITY_POSSESSED)
                        .setSuggestManualTimeZone(suggestManualTimeZoneCapability)
                        .build();
            }
            case RESTRICTED: {
                return new TimeZoneCapabilities.Builder(userId)
                        .setConfigureAutoDetectionEnabled(CAPABILITY_NOT_ALLOWED)
                        .setSuggestManualTimeZone(CAPABILITY_NOT_ALLOWED)
                        .build();

            }
            case AUTO_DETECT_NOT_SUPPORTED: {
                return new TimeZoneCapabilities.Builder(userId)
                        .setConfigureAutoDetectionEnabled(CAPABILITY_NOT_SUPPORTED)
                        .setSuggestManualTimeZone(CAPABILITY_POSSESSED)
                        .build();

            }
            default:
                throw new AssertionError(userRole + " not recognized");
        }
    }

    /**
     * A "fluent" class allows reuse of code in tests: initialization, simulation and verification
     * logic.
     */
    private class Script {

        Script initializeUser(
                @UserIdInt int userId, UserCase userRole, TimeZoneConfiguration configuration) {
            TimeZoneCapabilities capabilities = createCapabilities(userId, userRole, configuration);
            mFakeCallback.initializeUser(userId, capabilities, configuration);
            return this;
        }

        Script initializeTimeZoneSetting(String zoneId) {
            mFakeCallback.initializeTimeZoneSetting(zoneId);
            return this;
        }

        Script simulateAutoTimeZoneDetectionEnabled(@UserIdInt int userId, boolean enabled) {
            TimeZoneConfiguration configuration = new TimeZoneConfiguration.Builder()
                    .setAutoDetectionEnabled(enabled)
                    .build();
            return simulateUpdateConfiguration(userId, configuration);
        }

        /**
         * Simulates the time zone detection strategy receiving an updated configuration.
         */
        Script simulateUpdateConfiguration(
                @UserIdInt int userId, TimeZoneConfiguration configuration) {
            mTimeZoneDetectorStrategy.updateConfiguration(userId, configuration);
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving a telephony-originated suggestion.
         */
        Script simulateTelephonyTimeZoneSuggestion(TelephonyTimeZoneSuggestion timeZoneSuggestion) {
            mTimeZoneDetectorStrategy.suggestTelephonyTimeZone(timeZoneSuggestion);
            return this;
        }

        /** Simulates the time zone detection strategy receiving a user-originated suggestion. */
        Script simulateManualTimeZoneSuggestion(
                @UserIdInt int userId, ManualTimeZoneSuggestion manualTimeZoneSuggestion,
                boolean expectedResult) {
            mFakeCallback.assertKnownUser(userId);
            boolean actualResult = mTimeZoneDetectorStrategy.suggestManualTimeZone(
                    userId, manualTimeZoneSuggestion);
            assertEquals(expectedResult, actualResult);
            return this;
        }

        Script verifyTimeZoneNotChanged() {
            mFakeCallback.assertTimeZoneNotChanged();
            return this;
        }

        Script verifyTimeZoneChangedAndReset(TelephonyTimeZoneSuggestion suggestion) {
            mFakeCallback.assertTimeZoneChangedTo(suggestion.getZoneId());
            mFakeCallback.commitAllChanges();
            return this;
        }

        Script verifyTimeZoneChangedAndReset(ManualTimeZoneSuggestion suggestion) {
            mFakeCallback.assertTimeZoneChangedTo(suggestion.getZoneId());
            mFakeCallback.commitAllChanges();
            return this;
        }

        /**
         * Verifies that the configuration has been changed to the expected value.
         */
        Script verifyConfigurationChangedAndReset(
                @UserIdInt int userId, TimeZoneConfiguration expected) {
            mFakeCallback.mConfiguration.assertHasBeenSet();
            UserConfiguration expectedUserConfig = new UserConfiguration(userId, expected);
            assertEquals(expectedUserConfig, mFakeCallback.mConfiguration.getLatest());
            mFakeCallback.commitAllChanges();

            // Also confirm the listener triggered.
            mMockStrategyListener.verifyOnConfigurationChangedCalled();
            mMockStrategyListener.reset();
            return this;
        }

        /**
         * Verifies that no underlying settings associated with the properties from the
         * {@link TimeZoneConfiguration} have been changed.
         */
        Script verifyConfigurationNotChanged() {
            mFakeCallback.mConfiguration.assertHasNotBeenSet();

            // Also confirm the listener did not trigger.
            mMockStrategyListener.verifyOnConfigurationChangedNotCalled();
            return this;
        }

        Script resetConfigurationTracking() {
            mFakeCallback.commitAllChanges();
            return this;
        }
    }

    private static class TelephonyTestCase {
        public final int matchType;
        public final int quality;
        public final int expectedScore;

        TelephonyTestCase(int matchType, int quality, int expectedScore) {
            this.matchType = matchType;
            this.quality = quality;
            this.expectedScore = expectedScore;
        }

        private TelephonyTimeZoneSuggestion createSuggestion(int slotIndex, String zoneId) {
            return new TelephonyTimeZoneSuggestion.Builder(slotIndex)
                    .setZoneId(zoneId)
                    .setMatchType(matchType)
                    .setQuality(quality)
                    .build();
        }
    }

    private static TelephonyTestCase newTelephonyTestCase(
            @MatchType int matchType, @Quality int quality, int expectedScore) {
        return new TelephonyTestCase(matchType, quality, expectedScore);
    }

    private static class MockStrategyListener implements TimeZoneDetectorStrategy.StrategyListener {
        private boolean mOnConfigurationChangedCalled;

        @Override
        public void onConfigurationChanged() {
            mOnConfigurationChangedCalled = true;
        }

        void verifyOnConfigurationChangedCalled() {
            assertTrue(mOnConfigurationChangedCalled);
        }

        void verifyOnConfigurationChangedNotCalled() {
            assertFalse(mOnConfigurationChangedCalled);
        }

        void reset() {
            mOnConfigurationChangedCalled = false;
        }
    }
}
