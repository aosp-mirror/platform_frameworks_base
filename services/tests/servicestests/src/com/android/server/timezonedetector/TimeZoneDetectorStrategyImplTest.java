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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.MatchType;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.Quality;

import com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.QualifiedTelephonyTimeZoneSuggestion;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;

/**
 * White-box unit tests for {@link TimeZoneDetectorStrategyImpl}.
 */
public class TimeZoneDetectorStrategyImplTest {

    /** A time zone used for initialization that does not occur elsewhere in tests. */
    private static final String ARBITRARY_TIME_ZONE_ID = "Etc/UTC";
    private static final int SLOT_INDEX1 = 10000;
    private static final int SLOT_INDEX2 = 20000;

    // Suggestion test cases are ordered so that each successive one is of the same or higher score
    // than the previous.
    private static final SuggestionTestCase[] TEST_CASES = new SuggestionTestCase[] {
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW),
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY, QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET,
                    TELEPHONY_SCORE_MEDIUM),
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_MEDIUM),
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY, QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH),
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGH),
            newTestCase(MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_HIGHEST),
            newTestCase(MATCH_TYPE_EMULATOR_ZONE_ID, QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGHEST),
    };

    private TimeZoneDetectorStrategyImpl mTimeZoneDetectorStrategy;
    private FakeTimeZoneDetectorStrategyCallback mFakeTimeZoneDetectorStrategyCallback;

    @Before
    public void setUp() {
        mFakeTimeZoneDetectorStrategyCallback = new FakeTimeZoneDetectorStrategyCallback();
        mTimeZoneDetectorStrategy =
                new TimeZoneDetectorStrategyImpl(mFakeTimeZoneDetectorStrategyCallback);
    }

    @Test
    public void testEmptyTelephonySuggestions() {
        TelephonyTimeZoneSuggestion slotIndex1TimeZoneSuggestion =
                createEmptySlotIndex1Suggestion();
        TelephonyTimeZoneSuggestion slotIndex2TimeZoneSuggestion =
                createEmptySlotIndex2Suggestion();
        Script script = new Script()
                .initializeAutoTimeZoneDetection(true)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        script.simulateTelephonyTimeZoneSuggestion(slotIndex1TimeZoneSuggestion)
                .verifyTimeZoneNotSet();

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
                .verifyTimeZoneNotSet();

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
        SuggestionTestCase testCase = newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW);
        SuggestionTestCase testCase2 = newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH);

        Script script = new Script()
                .initializeAutoTimeZoneDetection(true);

        // A low quality suggestions will not be taken: The device time zone setting is left
        // uninitialized.
        {
            TelephonyTimeZoneSuggestion lowQualitySuggestion =
                    testCase.createSuggestion(SLOT_INDEX1, "America/New_York");
            script.simulateTelephonyTimeZoneSuggestion(lowQualitySuggestion)
                    .verifyTimeZoneNotSet();

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
                    .verifyTimeZoneSetAndReset(goodQualitySuggestion);

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
                    .verifyTimeZoneNotSet();

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

        for (SuggestionTestCase testCase : TEST_CASES) {
            // Start with the device in a known state.
            script.initializeAutoTimeZoneDetection(false)
                    .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

            TelephonyTimeZoneSuggestion suggestion =
                    testCase.createSuggestion(SLOT_INDEX1, "Europe/London");
            script.simulateTelephonyTimeZoneSuggestion(suggestion);

            // When time zone detection is not enabled, the time zone suggestion will not be set
            // regardless of the score.
            script.verifyTimeZoneNotSet();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(suggestion, testCase.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Toggling the time zone setting on should cause the device setting to be set.
            script.autoTimeZoneDetectionEnabled(true);

            // When time zone detection is already enabled the suggestion (if it scores highly
            // enough) should be set immediately.
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneSetAndReset(suggestion);
            } else {
                script.verifyTimeZoneNotSet();
            }

            // Assert internal service state.
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Toggling the time zone setting should off should do nothing.
            script.autoTimeZoneDetectionEnabled(false)
                    .verifyTimeZoneNotSet();

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
                .initializeAutoTimeZoneDetection(true)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        for (SuggestionTestCase testCase : TEST_CASES) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }

        /*
         * This is the same test as above but the test cases are in
         * reverse order of their expected score. New suggestions always replace previous ones:
         * there's effectively no history and so ordering shouldn't make any difference.
         */

        // Each test case will have the same or lower score than the last.
        ArrayList<SuggestionTestCase> descendingCasesByScore =
                new ArrayList<>(Arrays.asList(TEST_CASES));
        Collections.reverse(descendingCasesByScore);

        for (SuggestionTestCase testCase : descendingCasesByScore) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }
    }

    private void makeSlotIndex1SuggestionAndCheckState(Script script, SuggestionTestCase testCase) {
        // Give the next suggestion a different zone from the currently set device time zone;
        String currentZoneId = mFakeTimeZoneDetectorStrategyCallback.getDeviceTimeZone();
        String suggestionZoneId =
                "Europe/London".equals(currentZoneId) ? "Europe/Paris" : "Europe/London";
        TelephonyTimeZoneSuggestion zoneSlotIndex1Suggestion =
                testCase.createSuggestion(SLOT_INDEX1, suggestionZoneId);
        QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(
                        zoneSlotIndex1Suggestion, testCase.expectedScore);

        script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion);
        if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
            script.verifyTimeZoneSetAndReset(zoneSlotIndex1Suggestion);
        } else {
            script.verifyTimeZoneNotSet();
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
    public void testMultipleSlotIndexSuggestionScoringAndSlotIndexBias() {
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
                .initializeAutoTimeZoneDetection(true)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                // Initialize the latest suggestions as empty so we don't need to worry about nulls
                // below for the first loop.
                .simulateTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion)
                .simulateTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion)
                .resetState();

        for (SuggestionTestCase testCase : TEST_CASES) {
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
                script.verifyTimeZoneSetAndReset(zoneSlotIndex1Suggestion);
            } else {
                script.verifyTimeZoneNotSet();
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
            script.verifyTimeZoneNotSet();

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
                script.verifyTimeZoneSetAndReset(zoneSlotIndex2Suggestion);
            } else {
                script.verifyTimeZoneNotSet();
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
                    .verifyTimeZoneNotSet();
            assertEquals(expectedEmptySlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedEmptySlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
        }
    }

    /**
     * The {@link TimeZoneDetectorStrategyImpl.Callback} is left to detect whether changing the time
     * zone is actually necessary. This test proves that the service doesn't assume it knows the
     * current setting.
     */
    @Test
    public void testTimeZoneDetectorStrategyDoesNotAssumeCurrentSetting() {
        Script script = new Script()
                .initializeAutoTimeZoneDetection(true);

        SuggestionTestCase testCase =
                newTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                        TELEPHONY_SCORE_HIGH);
        TelephonyTimeZoneSuggestion losAngelesSuggestion =
                testCase.createSuggestion(SLOT_INDEX1, "America/Los_Angeles");
        TelephonyTimeZoneSuggestion newYorkSuggestion =
                testCase.createSuggestion(SLOT_INDEX1, "America/New_York");

        // Initialization.
        script.simulateTelephonyTimeZoneSuggestion(losAngelesSuggestion)
                .verifyTimeZoneSetAndReset(losAngelesSuggestion);
        // Suggest it again - it should not be set because it is already set.
        script.simulateTelephonyTimeZoneSuggestion(losAngelesSuggestion)
                .verifyTimeZoneNotSet();

        // Toggling time zone detection should set the device time zone only if the current setting
        // value is different from the most recent telephony suggestion.
        script.autoTimeZoneDetectionEnabled(false)
                .verifyTimeZoneNotSet()
                .autoTimeZoneDetectionEnabled(true)
                .verifyTimeZoneNotSet();

        // Simulate a user turning auto detection off, a new suggestion being made while auto
        // detection is off, and the user turning it on again.
        script.autoTimeZoneDetectionEnabled(false)
                .simulateTelephonyTimeZoneSuggestion(newYorkSuggestion)
                .verifyTimeZoneNotSet();
        // Latest suggestion should be used.
        script.autoTimeZoneDetectionEnabled(true)
                .verifyTimeZoneSetAndReset(newYorkSuggestion);
    }

    @Test
    public void testManualSuggestion_autoTimeZoneDetectionEnabled() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .initializeAutoTimeZoneDetection(true);

        // Auto time zone detection is enabled so the manual suggestion should be ignored.
        script.simulateManualTimeZoneSuggestion(
                createManualSuggestion("Europe/Paris"), false /* expectedResult */)
            .verifyTimeZoneNotSet();
    }


    @Test
    public void testManualSuggestion_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .initializeAutoTimeZoneDetection(false);

        // Auto time zone detection is disabled so the manual suggestion should be used.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(manualSuggestion, true /* expectedResult */)
            .verifyTimeZoneSetAndReset(manualSuggestion);
    }

    private ManualTimeZoneSuggestion createManualSuggestion(String zoneId) {
        return new ManualTimeZoneSuggestion(zoneId);
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex1Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX1).build();
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex2Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX2).build();
    }

    static class FakeTimeZoneDetectorStrategyCallback
            implements TimeZoneDetectorStrategyImpl.Callback {

        private boolean mAutoTimeZoneDetectionEnabled;
        private TestState<String> mTimeZoneId = new TestState<>();

        @Override
        public boolean isAutoTimeZoneDetectionEnabled() {
            return mAutoTimeZoneDetectionEnabled;
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

        void initializeAutoTimeZoneDetection(boolean enabled) {
            mAutoTimeZoneDetectionEnabled = enabled;
        }

        void initializeTimeZone(String zoneId) {
            mTimeZoneId.init(zoneId);
        }

        void setAutoTimeZoneDetectionEnabled(boolean enabled) {
            mAutoTimeZoneDetectionEnabled = enabled;
        }

        void assertTimeZoneNotSet() {
            mTimeZoneId.assertHasNotBeenSet();
        }

        void assertTimeZoneSet(String timeZoneId) {
            mTimeZoneId.assertHasBeenSet();
            mTimeZoneId.assertChangeCount(1);
            mTimeZoneId.assertLatestEquals(timeZoneId);
        }

        void commitAllChanges() {
            mTimeZoneId.commitLatest();
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

    /**
     * A "fluent" class allows reuse of code in tests: initialization, simulation and verification
     * logic.
     */
    private class Script {

        Script initializeAutoTimeZoneDetection(boolean enabled) {
            mFakeTimeZoneDetectorStrategyCallback.initializeAutoTimeZoneDetection(enabled);
            return this;
        }

        Script initializeTimeZoneSetting(String zoneId) {
            mFakeTimeZoneDetectorStrategyCallback.initializeTimeZone(zoneId);
            return this;
        }

        Script autoTimeZoneDetectionEnabled(boolean enabled) {
            mFakeTimeZoneDetectorStrategyCallback.setAutoTimeZoneDetectionEnabled(enabled);
            mTimeZoneDetectorStrategy.handleAutoTimeZoneDetectionChanged();
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
                ManualTimeZoneSuggestion manualTimeZoneSuggestion, boolean expectedResult) {
            assertEquals(expectedResult,
                    mTimeZoneDetectorStrategy.suggestManualTimeZone(manualTimeZoneSuggestion));
            return this;
        }

        Script verifyTimeZoneNotSet() {
            mFakeTimeZoneDetectorStrategyCallback.assertTimeZoneNotSet();
            return this;
        }

        Script verifyTimeZoneSetAndReset(TelephonyTimeZoneSuggestion suggestion) {
            mFakeTimeZoneDetectorStrategyCallback.assertTimeZoneSet(suggestion.getZoneId());
            mFakeTimeZoneDetectorStrategyCallback.commitAllChanges();
            return this;
        }

        Script verifyTimeZoneSetAndReset(ManualTimeZoneSuggestion suggestion) {
            mFakeTimeZoneDetectorStrategyCallback.assertTimeZoneSet(suggestion.getZoneId());
            mFakeTimeZoneDetectorStrategyCallback.commitAllChanges();
            return this;
        }

        Script resetState() {
            mFakeTimeZoneDetectorStrategyCallback.commitAllChanges();
            return this;
        }
    }

    private static class SuggestionTestCase {
        public final int matchType;
        public final int quality;
        public final int expectedScore;

        SuggestionTestCase(int matchType, int quality, int expectedScore) {
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

    private static SuggestionTestCase newTestCase(
            @MatchType int matchType, @Quality int quality, int expectedScore) {
        return new SuggestionTestCase(matchType, quality, expectedScore);
    }
}
