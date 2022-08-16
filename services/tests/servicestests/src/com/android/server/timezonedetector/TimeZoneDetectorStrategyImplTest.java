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

import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_HIGH;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_HIGHEST;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_LOW;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_MEDIUM;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_NONE;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_USAGE_THRESHOLD;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.MatchType;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.Quality;

import com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.QualifiedTelephonyTimeZoneSuggestion;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * White-box unit tests for {@link TimeZoneDetectorStrategyImpl}.
 */
public class TimeZoneDetectorStrategyImplTest {

    private static final @UserIdInt int USER_ID = 9876;
    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 1234;
    /** A time zone used for initialization that does not occur elsewhere in tests. */
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

    private static final ConfigurationInternal CONFIG_USER_RESTRICTED_AUTO_DISABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(false)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_USER_RESTRICTED_AUTO_ENABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(false)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(true)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_DETECT_NOT_SUPPORTED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setTelephonyDetectionFeatureSupported(false)
                    .setGeoDetectionFeatureSupported(false)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_DISABLED_GEO_DISABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(false)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_ENABLED_GEO_DISABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_ENABLED_GEO_ENABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setTelephonyDetectionFeatureSupported(true)
                    .setGeoDetectionFeatureSupported(true)
                    .setTelephonyFallbackSupported(false)
                    .setGeoDetectionRunInBackgroundEnabled(false)
                    .setEnhancedMetricsCollectionEnabled(false)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabledSetting(true)
                    .setLocationEnabledSetting(true)
                    .setGeoDetectionEnabledSetting(true)
                    .build();

    private TimeZoneDetectorStrategyImpl mTimeZoneDetectorStrategy;
    private FakeEnvironment mFakeEnvironment;

    @Before
    public void setUp() {
        mFakeEnvironment = new FakeEnvironment();
        mFakeEnvironment.initializeConfig(CONFIG_AUTO_DISABLED_GEO_DISABLED);
        mTimeZoneDetectorStrategy = new TimeZoneDetectorStrategyImpl(mFakeEnvironment);
    }

    @Test
    public void testEmptyTelephonySuggestions() {
        TelephonyTimeZoneSuggestion slotIndex1TimeZoneSuggestion =
                createEmptySlotIndex1Suggestion();
        TelephonyTimeZoneSuggestion slotIndex2TimeZoneSuggestion =
                createEmptySlotIndex2Suggestion();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking();

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

    /**
     * Telephony suggestions have quality metadata. Ordinarily, low scoring suggestions are not
     * used, but this is not true if the device's time zone setting is uninitialized.
     */
    @Test
    public void testTelephonySuggestionsWhenTimeZoneUninitialized() {
        assertTrue(TELEPHONY_SCORE_LOW < TELEPHONY_SCORE_USAGE_THRESHOLD);
        assertTrue(TELEPHONY_SCORE_HIGH >= TELEPHONY_SCORE_USAGE_THRESHOLD);
        TelephonyTestCase testCase = newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW);
        TelephonyTestCase testCase2 = newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH);

        Script script = new Script()
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking();

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
     * the strategy is "opinionated" when using telephony auto detection.
     */
    @Test
    public void testTogglingAutoDetection_autoTelephony() {
        Script script = new Script();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            // Start with the device in a known state.
            script.initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                    .simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                    .resetConfigurationTracking();

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
            script.simulateSetAutoMode(true);

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
            script.simulateSetAutoMode(false)
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
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }

        /*
         * This is the same test as above but the test cases are in
         * reverse order of their expected score. New suggestions always replace previous ones:
         * there's effectively no history and so ordering shouldn't make any difference.
         */

        // Each test case will have the same or lower score than the last.
        List<TelephonyTestCase> descendingCasesByScore = Arrays.asList(TELEPHONY_TEST_CASES);
        Collections.reverse(descendingCasesByScore);

        for (TelephonyTestCase testCase : descendingCasesByScore) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }
    }

    private void makeSlotIndex1SuggestionAndCheckState(Script script, TelephonyTestCase testCase) {
        // Give the next suggestion a different zone from the currently set device time zone;
        String currentZoneId = mFakeEnvironment.getDeviceTimeZone();
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
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking()

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
     * The {@link TimeZoneDetectorStrategyImpl.Environment} is left to detect whether changing the
     * time zone is actually necessary. This test proves that the strategy doesn't assume it knows
     * the current settings.
     */
    @Test
    public void testTelephonySuggestionStrategyDoesNotAssumeCurrentSetting_autoTelephony() {
        Script script = new Script()
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                .resetConfigurationTracking();

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
        script.simulateSetAutoMode(false)
                .verifyTimeZoneNotChanged()
                .simulateSetAutoMode(true)
                .verifyTimeZoneNotChanged();

        // Simulate a user turning auto detection off, a new suggestion being made while auto
        // detection is off, and the user turning it on again.
        script.simulateSetAutoMode(false)
                .simulateTelephonyTimeZoneSuggestion(newYorkSuggestion)
                .verifyTimeZoneNotChanged();
        // Latest suggestion should be used.
        script.simulateSetAutoMode(true)
                .verifyTimeZoneChangedAndReset(newYorkSuggestion);
    }

    @Test
    public void testManualSuggestion_unrestricted_autoDetectionEnabled_autoTelephony() {
        checkManualSuggestion_unrestricted_autoDetectionEnabled(false /* geoDetectionEnabled */);
    }

    @Test
    public void testManualSuggestion_unrestricted_autoDetectionEnabled_autoGeo() {
        checkManualSuggestion_unrestricted_autoDetectionEnabled(true /* geoDetectionEnabled */);
    }

    private void checkManualSuggestion_unrestricted_autoDetectionEnabled(
            boolean geoDetectionEnabled) {
        ConfigurationInternal geoTzEnabledConfig =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED_GEO_DISABLED)
                        .setGeoDetectionEnabledSetting(geoDetectionEnabled)
                        .build();
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(geoTzEnabledConfig)
                .resetConfigurationTracking();

        // Auto time zone detection is enabled so the manual suggestion should be ignored.
        script.simulateManualTimeZoneSuggestion(
                USER_ID, createManualSuggestion("Europe/Paris"), false /* expectedResult */)
                .verifyTimeZoneNotChanged();

        assertNull(mTimeZoneDetectorStrategy.getLatestManualSuggestion());
    }

    @Test
    public void testManualSuggestion_restricted_simulateAutoTimeZoneEnabled() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_USER_RESTRICTED_AUTO_ENABLED)
                .resetConfigurationTracking();

        // User is restricted so the manual suggestion should be ignored.
        script.simulateManualTimeZoneSuggestion(
                USER_ID, createManualSuggestion("Europe/Paris"), false /* expectedResult */)
                .verifyTimeZoneNotChanged();

        assertNull(mTimeZoneDetectorStrategy.getLatestManualSuggestion());
    }

    @Test
    public void testManualSuggestion_unrestricted_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        // Auto time zone detection is disabled so the manual suggestion should be used.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, true /* expectedResult */)
            .verifyTimeZoneChangedAndReset(manualSuggestion);

        assertEquals(manualSuggestion, mTimeZoneDetectorStrategy.getLatestManualSuggestion());
    }

    @Test
    public void testManualSuggestion_restricted_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_USER_RESTRICTED_AUTO_DISABLED)
                .resetConfigurationTracking();

        // Restricted users do not have the capability.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, false /* expectedResult */)
                .verifyTimeZoneNotChanged();

        assertNull(mTimeZoneDetectorStrategy.getLatestManualSuggestion());
    }

    @Test
    public void testManualSuggestion_autoDetectNotSupported() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_DETECT_NOT_SUPPORTED)
                .resetConfigurationTracking();

        // Unrestricted users have the capability.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, true /* expectedResult */)
                .verifyTimeZoneChangedAndReset(manualSuggestion);

        assertEquals(manualSuggestion, mTimeZoneDetectorStrategy.getLatestManualSuggestion());
    }

    @Test
    public void testGeoSuggestion_uncertain() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking();

        GeolocationTimeZoneSuggestion uncertainSuggestion = createUncertainGeolocationSuggestion();

        script.simulateGeolocationTimeZoneSuggestion(uncertainSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        assertEquals(uncertainSuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    @Test
    public void testGeoSuggestion_noZones() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking();

        GeolocationTimeZoneSuggestion noZonesSuggestion = createCertainGeolocationSuggestion();

        script.simulateGeolocationTimeZoneSuggestion(noZonesSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        assertEquals(noZonesSuggestion, mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    @Test
    public void testGeoSuggestion_oneZone() {
        GeolocationTimeZoneSuggestion suggestion =
                createCertainGeolocationSuggestion("Europe/London");

        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking();

        script.simulateGeolocationTimeZoneSuggestion(suggestion)
                .verifyTimeZoneChangedAndReset(suggestion);

        // Assert internal service state.
        assertEquals(suggestion, mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    /**
     * In the current implementation, the first zone ID is always used unless the device is set to
     * one of the other options. This is "stickiness" - the device favors the zone it is currently
     * set to until that unambiguously can't be correct.
     */
    @Test
    public void testGeoSuggestion_multiZone() {
        GeolocationTimeZoneSuggestion londonOnlySuggestion =
                createCertainGeolocationSuggestion("Europe/London");
        GeolocationTimeZoneSuggestion londonOrParisSuggestion =
                createCertainGeolocationSuggestion("Europe/Paris", "Europe/London");
        GeolocationTimeZoneSuggestion parisOnlySuggestion =
                createCertainGeolocationSuggestion("Europe/Paris");

        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .resetConfigurationTracking();

        script.simulateGeolocationTimeZoneSuggestion(londonOnlySuggestion)
                .verifyTimeZoneChangedAndReset(londonOnlySuggestion);
        assertEquals(londonOnlySuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        // Confirm bias towards the current device zone when there's multiple zones to choose from.
        script.simulateGeolocationTimeZoneSuggestion(londonOrParisSuggestion)
                .verifyTimeZoneNotChanged();
        assertEquals(londonOrParisSuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        script.simulateGeolocationTimeZoneSuggestion(parisOnlySuggestion)
                .verifyTimeZoneChangedAndReset(parisOnlySuggestion);
        assertEquals(parisOnlySuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        // Now the suggestion that previously left the device on Europe/London will leave the device
        // on Europe/Paris.
        script.simulateGeolocationTimeZoneSuggestion(londonOrParisSuggestion)
                .verifyTimeZoneNotChanged();
        assertEquals(londonOrParisSuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    /**
     * Confirms that changing the geolocation time zone detection enabled setting has the expected
     * behavior, i.e. immediately recompute the detected time zone using different signals.
     */
    @Test
    public void testChangingGeoDetectionEnabled() {
        GeolocationTimeZoneSuggestion geolocationSuggestion =
                createCertainGeolocationSuggestion("Europe/London");
        TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                "Europe/Paris");

        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                .resetConfigurationTracking();

        // Add suggestions. Nothing should happen as time zone detection is disabled.
        script.simulateGeolocationTimeZoneSuggestion(geolocationSuggestion)
                .verifyTimeZoneNotChanged();

        assertEquals(geolocationSuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        script.simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                .verifyTimeZoneNotChanged();

        assertEquals(telephonySuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1).suggestion);

        // Toggling the time zone detection enabled setting on should cause the device setting to be
        // set from the telephony signal, as we've started with geolocation time zone detection
        // disabled.
        script.simulateSetAutoMode(true)
                .verifyTimeZoneChangedAndReset(telephonySuggestion);

        // Changing the detection to enable geo detection will cause the device tz setting to
        // change to use the latest geolocation suggestion.
        script.simulateSetGeoDetectionEnabled(true)
                .verifyTimeZoneChangedAndReset(geolocationSuggestion);

        // Changing the detection to disable geo detection should cause the device tz setting to
        // change to the telephony suggestion.
        script.simulateSetGeoDetectionEnabled(false)
                .verifyTimeZoneChangedAndReset(telephonySuggestion);

        assertEquals(geolocationSuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    @Test
    public void testTelephonyFallback() {
        ConfigurationInternal config = new ConfigurationInternal.Builder(
                CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .setTelephonyFallbackSupported(true)
                .build();

        Script script = new Script()
                .initializeClock(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(config)
                .resetConfigurationTracking();

        // Confirm initial state is as expected.
        script.verifyTelephonyFallbackIsEnabled(true)
                .verifyTimeZoneNotChanged();

        // Although geolocation detection is enabled, telephony fallback should be used initially
        // and until a suitable "certain" geolocation suggestion is received.
        {
            TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                    SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    "Europe/Paris");
            script.simulateIncrementClock()
                    .simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                    .verifyTimeZoneChangedAndReset(telephonySuggestion)
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Receiving an "uncertain" geolocation suggestion should have no effect.
        {
            GeolocationTimeZoneSuggestion uncertainGeolocationSuggestion =
                    createUncertainGeolocationSuggestion();
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(uncertainGeolocationSuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Receiving a "certain" geolocation suggestion should disable telephony fallback mode.
        {
            GeolocationTimeZoneSuggestion geolocationSuggestion =
                    createCertainGeolocationSuggestion("Europe/London");
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(geolocationSuggestion)
                    .verifyTimeZoneChangedAndReset(geolocationSuggestion)
                    .verifyTelephonyFallbackIsEnabled(false);
        }

        // Used to record the last telephony suggestion received, which will be used when fallback
        // takes place.
        TelephonyTimeZoneSuggestion lastTelephonySuggestion;

        // Telephony suggestions should now be ignored and geolocation detection is "in control".
        {
            TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                    SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    "Europe/Berlin");
            script.simulateIncrementClock()
                    .simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);
            lastTelephonySuggestion = telephonySuggestion;
        }

        // Geolocation suggestions should continue to be used as normal (previous telephony
        // suggestions are not used, even when the geolocation suggestion is uncertain).
        {
            GeolocationTimeZoneSuggestion geolocationSuggestion =
                    createCertainGeolocationSuggestion("Europe/Rome");
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(geolocationSuggestion)
                    .verifyTimeZoneChangedAndReset(geolocationSuggestion)
                    .verifyTelephonyFallbackIsEnabled(false);

            GeolocationTimeZoneSuggestion uncertainGeolocationSuggestion =
                    createUncertainGeolocationSuggestion();
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(uncertainGeolocationSuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);

            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(geolocationSuggestion)
                    // No change needed, device will already be set to Europe/Rome.
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);
        }

        // Enable telephony fallback. Nothing will change, because the geolocation is still certain,
        // but fallback will remain enabled.
        {
            script.simulateIncrementClock()
                    .simulateEnableTelephonyFallback()
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Make the geolocation algorithm uncertain.
        {
            GeolocationTimeZoneSuggestion uncertainGeolocationSuggestion =
                    createUncertainGeolocationSuggestion();
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(uncertainGeolocationSuggestion)
                    .verifyTimeZoneChangedAndReset(lastTelephonySuggestion)
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Make the geolocation algorithm certain, disabling telephony fallback.
        {
            GeolocationTimeZoneSuggestion geolocationSuggestion =
                    createCertainGeolocationSuggestion("Europe/Lisbon");
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(geolocationSuggestion)
                    .verifyTimeZoneChangedAndReset(geolocationSuggestion)
                    .verifyTelephonyFallbackIsEnabled(false);

        }

        // Demonstrate what happens when geolocation is uncertain when telephony fallback is
        // enabled.
        {
            GeolocationTimeZoneSuggestion uncertainGeolocationSuggestion =
                    createUncertainGeolocationSuggestion();
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(uncertainGeolocationSuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false)
                    .simulateEnableTelephonyFallback()
                    .verifyTimeZoneChangedAndReset(lastTelephonySuggestion)
                    .verifyTelephonyFallbackIsEnabled(true);
        }
    }

    @Test
    public void testTelephonyFallback_noTelephonySuggestionToFallBackTo() {
        ConfigurationInternal config = new ConfigurationInternal.Builder(
                CONFIG_AUTO_ENABLED_GEO_ENABLED)
                .setTelephonyFallbackSupported(true)
                .build();

        Script script = new Script()
                .initializeClock(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .simulateConfigurationInternalChange(config)
                .resetConfigurationTracking();

        // Confirm initial state is as expected.
        script.verifyTelephonyFallbackIsEnabled(true)
                .verifyTimeZoneNotChanged();

        // Receiving an "uncertain" geolocation suggestion should have no effect.
        {
            GeolocationTimeZoneSuggestion uncertainGeolocationSuggestion =
                    createUncertainGeolocationSuggestion();
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(uncertainGeolocationSuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Make an uncertain geolocation suggestion, there is no telephony suggestion to fall back
        // to
        {
            GeolocationTimeZoneSuggestion uncertainGeolocationSuggestion =
                    createUncertainGeolocationSuggestion();
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(uncertainGeolocationSuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }

        // Similar to the case above, but force a fallback attempt after making a "certain"
        // geolocation suggestion.
        // Geolocation suggestions should continue to be used as normal (previous telephony
        // suggestions are not used, even when the geolocation suggestion is uncertain).
        {
            GeolocationTimeZoneSuggestion geolocationSuggestion =
                    createCertainGeolocationSuggestion("Europe/Rome");
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(geolocationSuggestion)
                    .verifyTimeZoneChangedAndReset(geolocationSuggestion)
                    .verifyTelephonyFallbackIsEnabled(false);

            GeolocationTimeZoneSuggestion uncertainGeolocationSuggestion =
                    createUncertainGeolocationSuggestion();
            script.simulateIncrementClock()
                    .simulateGeolocationTimeZoneSuggestion(uncertainGeolocationSuggestion)
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(false);

            script.simulateIncrementClock()
                    .simulateEnableTelephonyFallback()
                    .verifyTimeZoneNotChanged()
                    .verifyTelephonyFallbackIsEnabled(true);
        }
    }

    @Test
    public void testGenerateMetricsState_enhancedMetricsCollection() {
        testGenerateMetricsState(true);
    }

    @Test
    public void testGenerateMetricsState_notEnhancedMetricsCollection() {
        testGenerateMetricsState(false);
    }

    private void testGenerateMetricsState(boolean enhancedMetricsCollection) {
        ConfigurationInternal expectedInternalConfig =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED_GEO_DISABLED)
                        .setEnhancedMetricsCollectionEnabled(enhancedMetricsCollection)
                        .build();
        String expectedDeviceTimeZoneId = "InitialZoneId";

        Script script = new Script()
                .initializeTimeZoneSetting(expectedDeviceTimeZoneId)
                .simulateConfigurationInternalChange(expectedInternalConfig)
                .resetConfigurationTracking();

        assertMetricsState(expectedInternalConfig, expectedDeviceTimeZoneId, null, null,
                null, MetricsTimeZoneDetectorState.DETECTION_MODE_MANUAL);

        // Make sure the manual suggestion is recorded.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Zone1");
        script.simulateManualTimeZoneSuggestion(USER_ID, manualSuggestion,
                true /* expectedResult */)
                .verifyTimeZoneChangedAndReset(manualSuggestion);
        expectedDeviceTimeZoneId = manualSuggestion.getZoneId();
        assertMetricsState(expectedInternalConfig, expectedDeviceTimeZoneId,
                manualSuggestion, null, null,
                MetricsTimeZoneDetectorState.DETECTION_MODE_MANUAL);

        // With time zone auto detection off, telephony and geo suggestions will be recorded.
        TelephonyTimeZoneSuggestion telephonySuggestion =
                createTelephonySuggestion(0 /* slotIndex */, MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                        QUALITY_SINGLE_ZONE, "Zone2");
        GeolocationTimeZoneSuggestion geolocationTimeZoneSuggestion =
                createCertainGeolocationSuggestion("Zone3", "Zone2");
        script.simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                .verifyTimeZoneNotChanged()
                .simulateGeolocationTimeZoneSuggestion(geolocationTimeZoneSuggestion)
                .verifyTimeZoneNotChanged();

        assertMetricsState(expectedInternalConfig, expectedDeviceTimeZoneId,
                manualSuggestion, telephonySuggestion, geolocationTimeZoneSuggestion,
                MetricsTimeZoneDetectorState.DETECTION_MODE_MANUAL);

        // Update the config and confirm that the config metrics state updates also.
        expectedInternalConfig = new ConfigurationInternal.Builder(expectedInternalConfig)
                .setAutoDetectionEnabledSetting(true)
                .setGeoDetectionEnabledSetting(true)
                .build();

        expectedDeviceTimeZoneId = geolocationTimeZoneSuggestion.getZoneIds().get(0);
        script.simulateConfigurationInternalChange(expectedInternalConfig)
                .verifyTimeZoneChangedAndReset(expectedDeviceTimeZoneId);
        assertMetricsState(expectedInternalConfig, expectedDeviceTimeZoneId,
                manualSuggestion, telephonySuggestion, geolocationTimeZoneSuggestion,
                MetricsTimeZoneDetectorState.DETECTION_MODE_GEO);
    }

    /**
     * Asserts that the information returned by {@link
     * TimeZoneDetectorStrategy#generateMetricsState()} matches expectations.
     */
    private void assertMetricsState(
            ConfigurationInternal expectedInternalConfig,
            String expectedDeviceTimeZoneId, ManualTimeZoneSuggestion expectedManualSuggestion,
            TelephonyTimeZoneSuggestion expectedTelephonySuggestion,
            GeolocationTimeZoneSuggestion expectedGeolocationTimeZoneSuggestion,
            int expectedDetectionMode) {

        MetricsTimeZoneDetectorState actualState = mTimeZoneDetectorStrategy.generateMetricsState();

        // Check the various feature state values are what we expect.
        assertFeatureStateMatchesConfig(expectedInternalConfig, actualState, expectedDetectionMode);

        OrdinalGenerator<String> tzIdOrdinalGenerator = new OrdinalGenerator<>(Function.identity());
        MetricsTimeZoneDetectorState expectedState =
                MetricsTimeZoneDetectorState.create(
                        tzIdOrdinalGenerator, expectedInternalConfig, expectedDeviceTimeZoneId,
                        expectedManualSuggestion, expectedTelephonySuggestion,
                        expectedGeolocationTimeZoneSuggestion);
        // Rely on MetricsTimeZoneDetectorState.equals() for time zone ID / ID ordinal comparisons.
        assertEquals(expectedState, actualState);
    }

    private static void assertFeatureStateMatchesConfig(ConfigurationInternal config,
            MetricsTimeZoneDetectorState actualState, int expectedDetectionMode) {
        assertEquals(config.isTelephonyDetectionSupported(),
                actualState.isTelephonyDetectionSupported());
        assertEquals(config.isGeoDetectionSupported(), actualState.isGeoDetectionSupported());
        assertEquals(config.isTelephonyFallbackSupported(),
                actualState.isTelephonyTimeZoneFallbackSupported());
        assertEquals(config.getAutoDetectionEnabledSetting(),
                actualState.getAutoDetectionEnabledSetting());
        assertEquals(config.getGeoDetectionEnabledSetting(),
                actualState.getGeoDetectionEnabledSetting());
        assertEquals(expectedDetectionMode, actualState.getDetectionMode());
    }

    private static ManualTimeZoneSuggestion createManualSuggestion(String zoneId) {
        return new ManualTimeZoneSuggestion(zoneId);
    }

    private static TelephonyTimeZoneSuggestion createTelephonySuggestion(
            int slotIndex, @MatchType int matchType, @Quality int quality, String zoneId) {
        return new TelephonyTimeZoneSuggestion.Builder(slotIndex)
                .setMatchType(matchType)
                .setQuality(quality)
                .setZoneId(zoneId)
                .build();
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex1Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX1).build();
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex2Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX2).build();
    }

    private GeolocationTimeZoneSuggestion createUncertainGeolocationSuggestion() {
        return GeolocationTimeZoneSuggestion.createCertainSuggestion(
                mFakeEnvironment.elapsedRealtimeMillis(), null);
    }

    private GeolocationTimeZoneSuggestion createCertainGeolocationSuggestion(
            @NonNull String... zoneIds) {
        assertNotNull(zoneIds);

        GeolocationTimeZoneSuggestion suggestion =
                GeolocationTimeZoneSuggestion.createCertainSuggestion(
                        mFakeEnvironment.elapsedRealtimeMillis(), Arrays.asList(zoneIds));
        suggestion.addDebugInfo("Test suggestion");
        return suggestion;
    }

    static class FakeEnvironment implements TimeZoneDetectorStrategyImpl.Environment {

        private final TestState<String> mTimeZoneId = new TestState<>();
        private ConfigurationInternal mConfigurationInternal;
        private @ElapsedRealtimeLong long mElapsedRealtimeMillis;
        private ConfigurationChangeListener mConfigurationInternalChangeListener;

        void initializeConfig(ConfigurationInternal configurationInternal) {
            mConfigurationInternal = configurationInternal;
        }

        void initializeClock(@ElapsedRealtimeLong long elapsedRealtimeMillis) {
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
        }

        void initializeTimeZoneSetting(String zoneId) {
            mTimeZoneId.init(zoneId);
        }

        void incrementClock() {
            mElapsedRealtimeMillis++;
        }

        @Override
        public void setConfigurationInternalChangeListener(ConfigurationChangeListener listener) {
            mConfigurationInternalChangeListener = listener;
        }

        @Override
        public ConfigurationInternal getCurrentUserConfigurationInternal() {
            return mConfigurationInternal;
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

        void simulateConfigurationInternalChange(ConfigurationInternal configurationInternal) {
            mConfigurationInternal = configurationInternal;
            mConfigurationInternalChangeListener.onChange();
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
        }

        @Override
        @ElapsedRealtimeLong
        public long elapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }
    }

    /**
     * A "fluent" class allows reuse of code in tests: initialization, simulation and verification
     * logic.
     */
    private class Script {

        Script initializeTimeZoneSetting(String zoneId) {
            mFakeEnvironment.initializeTimeZoneSetting(zoneId);
            return this;
        }

        Script initializeClock(long elapsedRealtimeMillis) {
            mFakeEnvironment.initializeClock(elapsedRealtimeMillis);
            return this;
        }

        Script simulateIncrementClock() {
            mFakeEnvironment.incrementClock();
            return this;
        }

        /**
         * Simulates the user / user's configuration changing.
         */
        Script simulateConfigurationInternalChange(ConfigurationInternal configurationInternal) {
            mFakeEnvironment.simulateConfigurationInternalChange(configurationInternal);
            return this;
        }

        /**
         * Simulates automatic time zone detection being set to the specified value.
         */
        Script simulateSetAutoMode(boolean autoDetectionEnabled) {
            ConfigurationInternal newConfig = new ConfigurationInternal.Builder(
                    mFakeEnvironment.getCurrentUserConfigurationInternal())
                    .setAutoDetectionEnabledSetting(autoDetectionEnabled)
                    .build();
            simulateConfigurationInternalChange(newConfig);
            return this;
        }

        /**
         * Simulates automatic geolocation time zone detection being set to the specified value.
         */
        Script simulateSetGeoDetectionEnabled(boolean geoDetectionEnabled) {
            ConfigurationInternal newConfig = new ConfigurationInternal.Builder(
                    mFakeEnvironment.getCurrentUserConfigurationInternal())
                    .setGeoDetectionEnabledSetting(geoDetectionEnabled)
                    .build();
            simulateConfigurationInternalChange(newConfig);
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving a geolocation-originated
         * suggestion.
         */
        Script simulateGeolocationTimeZoneSuggestion(GeolocationTimeZoneSuggestion suggestion) {
            mTimeZoneDetectorStrategy.suggestGeolocationTimeZone(suggestion);
            return this;
        }

        /** Simulates the time zone detection strategy receiving a user-originated suggestion. */
        Script simulateManualTimeZoneSuggestion(
                @UserIdInt int userId, ManualTimeZoneSuggestion manualTimeZoneSuggestion,
                boolean expectedResult) {
            boolean actualResult = mTimeZoneDetectorStrategy.suggestManualTimeZone(
                    userId, manualTimeZoneSuggestion);
            assertEquals(expectedResult, actualResult);
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving a telephony-originated suggestion.
         */
        Script simulateTelephonyTimeZoneSuggestion(TelephonyTimeZoneSuggestion timeZoneSuggestion) {
            mTimeZoneDetectorStrategy.suggestTelephonyTimeZone(timeZoneSuggestion);
            return this;
        }

        /**
         * Simulates the time zone detection strategty receiving a signal that allows it to do
         * telephony fallback.
         */
        Script simulateEnableTelephonyFallback() {
            mTimeZoneDetectorStrategy.enableTelephonyTimeZoneFallback();
            return this;
        }

        /**
         * Confirms that the device's time zone has not been set by previous actions since the test
         * state was last reset.
         */
        Script verifyTimeZoneNotChanged() {
            mFakeEnvironment.assertTimeZoneNotChanged();
            return this;
        }

        /** Verifies the device's time zone has been set and clears change tracking history. */
        Script verifyTimeZoneChangedAndReset(String zoneId) {
            mFakeEnvironment.assertTimeZoneChangedTo(zoneId);
            mFakeEnvironment.commitAllChanges();
            return this;
        }

        Script verifyTimeZoneChangedAndReset(ManualTimeZoneSuggestion suggestion) {
            mFakeEnvironment.assertTimeZoneChangedTo(suggestion.getZoneId());
            mFakeEnvironment.commitAllChanges();
            return this;
        }

        Script verifyTimeZoneChangedAndReset(TelephonyTimeZoneSuggestion suggestion) {
            mFakeEnvironment.assertTimeZoneChangedTo(suggestion.getZoneId());
            mFakeEnvironment.commitAllChanges();
            return this;
        }

        Script verifyTimeZoneChangedAndReset(GeolocationTimeZoneSuggestion suggestion) {
            assertEquals("Only use this method with unambiguous geo suggestions",
                    1, suggestion.getZoneIds().size());
            mFakeEnvironment.assertTimeZoneChangedTo(suggestion.getZoneIds().get(0));
            mFakeEnvironment.commitAllChanges();
            return this;
        }

        /** Verifies the state for telephony fallback. */
        Script verifyTelephonyFallbackIsEnabled(boolean expectedEnabled) {
            assertEquals(expectedEnabled,
                    mTimeZoneDetectorStrategy.isTelephonyFallbackEnabledForTests());
            return this;
        }

        Script resetConfigurationTracking() {
            mFakeEnvironment.commitAllChanges();
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
}
