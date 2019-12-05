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

import static android.app.timezonedetector.PhoneTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET;
import static android.app.timezonedetector.PhoneTimeZoneSuggestion.QUALITY_SINGLE_ZONE;

import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.PHONE_SCORE_HIGH;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.PHONE_SCORE_HIGHEST;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.PHONE_SCORE_LOW;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.PHONE_SCORE_MEDIUM;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.PHONE_SCORE_NONE;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategy.PHONE_SCORE_USAGE_THRESHOLD;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.PhoneTimeZoneSuggestion;
import android.app.timezonedetector.PhoneTimeZoneSuggestion.MatchType;
import android.app.timezonedetector.PhoneTimeZoneSuggestion.Quality;

import com.android.server.timezonedetector.TimeZoneDetectorStrategy.QualifiedPhoneTimeZoneSuggestion;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;

/**
 * White-box unit tests for {@link TimeZoneDetectorStrategy}.
 */
public class TimeZoneDetectorStrategyTest {

    /** A time zone used for initialization that does not occur elsewhere in tests. */
    private static final String ARBITRARY_TIME_ZONE_ID = "Etc/UTC";
    private static final int PHONE1_ID = 10000;
    private static final int PHONE2_ID = 20000;

    // Suggestion test cases are ordered so that each successive one is of the same or higher score
    // than the previous.
    private static final SuggestionTestCase[] TEST_CASES = new SuggestionTestCase[] {
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, PHONE_SCORE_LOW),
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY, QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET,
                    PHONE_SCORE_MEDIUM),
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, PHONE_SCORE_MEDIUM),
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY, QUALITY_SINGLE_ZONE, PHONE_SCORE_HIGH),
            newTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    PHONE_SCORE_HIGH),
            newTestCase(MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, PHONE_SCORE_HIGHEST),
            newTestCase(MATCH_TYPE_EMULATOR_ZONE_ID, QUALITY_SINGLE_ZONE, PHONE_SCORE_HIGHEST),
    };

    private TimeZoneDetectorStrategy mTimeZoneDetectorStrategy;
    private FakeTimeZoneDetectorStrategyCallback mFakeTimeZoneDetectorStrategyCallback;

    @Before
    public void setUp() {
        mFakeTimeZoneDetectorStrategyCallback = new FakeTimeZoneDetectorStrategyCallback();
        mTimeZoneDetectorStrategy =
                new TimeZoneDetectorStrategy(mFakeTimeZoneDetectorStrategyCallback);
    }

    @Test
    public void testEmptyPhoneSuggestions() {
        PhoneTimeZoneSuggestion phone1TimeZoneSuggestion = createEmptyPhone1Suggestion();
        PhoneTimeZoneSuggestion phone2TimeZoneSuggestion = createEmptyPhone2Suggestion();
        Script script = new Script()
                .initializeAutoTimeZoneDetection(true)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        script.suggestPhoneTimeZone(phone1TimeZoneSuggestion)
                .verifyTimeZoneNotSet();

        // Assert internal service state.
        QualifiedPhoneTimeZoneSuggestion expectedPhone1ScoredSuggestion =
                new QualifiedPhoneTimeZoneSuggestion(phone1TimeZoneSuggestion, PHONE_SCORE_NONE);
        assertEquals(expectedPhone1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
        assertNull(mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE2_ID));
        assertEquals(expectedPhone1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());

        script.suggestPhoneTimeZone(phone2TimeZoneSuggestion)
                .verifyTimeZoneNotSet();

        // Assert internal service state.
        QualifiedPhoneTimeZoneSuggestion expectedPhone2ScoredSuggestion =
                new QualifiedPhoneTimeZoneSuggestion(phone2TimeZoneSuggestion, PHONE_SCORE_NONE);
        assertEquals(expectedPhone1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
        assertEquals(expectedPhone2ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE2_ID));
        // Phone 1 should always beat phone 2, all other things being equal.
        assertEquals(expectedPhone1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());
    }

    @Test
    public void testFirstPlausiblePhoneSuggestionAcceptedWhenTimeZoneUninitialized() {
        SuggestionTestCase testCase = newTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, PHONE_SCORE_LOW);
        PhoneTimeZoneSuggestion lowQualitySuggestion =
                testCase.createSuggestion(PHONE1_ID, "America/New_York");

        // The device time zone setting is left uninitialized.
        Script script = new Script()
                .initializeAutoTimeZoneDetection(true);

        // The very first suggestion will be taken.
        script.suggestPhoneTimeZone(lowQualitySuggestion)
                .verifyTimeZoneSetAndReset(lowQualitySuggestion);

        // Assert internal service state.
        QualifiedPhoneTimeZoneSuggestion expectedScoredSuggestion =
                new QualifiedPhoneTimeZoneSuggestion(lowQualitySuggestion, testCase.expectedScore);
        assertEquals(expectedScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
        assertEquals(expectedScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());

        // Another low quality suggestion will be ignored now that the setting is initialized.
        PhoneTimeZoneSuggestion lowQualitySuggestion2 =
                testCase.createSuggestion(PHONE1_ID, "America/Los_Angeles");
        script.suggestPhoneTimeZone(lowQualitySuggestion2)
                .verifyTimeZoneNotSet();

        // Assert internal service state.
        QualifiedPhoneTimeZoneSuggestion expectedScoredSuggestion2 =
                new QualifiedPhoneTimeZoneSuggestion(lowQualitySuggestion2, testCase.expectedScore);
        assertEquals(expectedScoredSuggestion2,
                mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
        assertEquals(expectedScoredSuggestion2,
                mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());
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

            PhoneTimeZoneSuggestion suggestion =
                    testCase.createSuggestion(PHONE1_ID, "Europe/London");
            script.suggestPhoneTimeZone(suggestion);

            // When time zone detection is not enabled, the time zone suggestion will not be set
            // regardless of the score.
            script.verifyTimeZoneNotSet();

            // Assert internal service state.
            QualifiedPhoneTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedPhoneTimeZoneSuggestion(suggestion, testCase.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());

            // Toggling the time zone setting on should cause the device setting to be set.
            script.autoTimeZoneDetectionEnabled(true);

            // When time zone detection is already enabled the suggestion (if it scores highly
            // enough) should be set immediately.
            if (testCase.expectedScore >= PHONE_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneSetAndReset(suggestion);
            } else {
                script.verifyTimeZoneNotSet();
            }

            // Assert internal service state.
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());

            // Toggling the time zone setting should off should do nothing.
            script.autoTimeZoneDetectionEnabled(false)
                    .verifyTimeZoneNotSet();

            // Assert internal service state.
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());
        }
    }

    @Test
    public void testPhoneSuggestionsSinglePhone() {
        Script script = new Script()
                .initializeAutoTimeZoneDetection(true)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        for (SuggestionTestCase testCase : TEST_CASES) {
            makePhone1SuggestionAndCheckState(script, testCase);
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
            makePhone1SuggestionAndCheckState(script, testCase);
        }
    }

    private void makePhone1SuggestionAndCheckState(Script script, SuggestionTestCase testCase) {
        // Give the next suggestion a different zone from the currently set device time zone;
        String currentZoneId = mFakeTimeZoneDetectorStrategyCallback.getDeviceTimeZone();
        String suggestionZoneId =
                "Europe/London".equals(currentZoneId) ? "Europe/Paris" : "Europe/London";
        PhoneTimeZoneSuggestion zonePhone1Suggestion =
                testCase.createSuggestion(PHONE1_ID, suggestionZoneId);
        QualifiedPhoneTimeZoneSuggestion expectedZonePhone1ScoredSuggestion =
                new QualifiedPhoneTimeZoneSuggestion(zonePhone1Suggestion, testCase.expectedScore);

        script.suggestPhoneTimeZone(zonePhone1Suggestion);
        if (testCase.expectedScore >= PHONE_SCORE_USAGE_THRESHOLD) {
            script.verifyTimeZoneSetAndReset(zonePhone1Suggestion);
        } else {
            script.verifyTimeZoneNotSet();
        }

        // Assert internal service state.
        assertEquals(expectedZonePhone1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
        assertEquals(expectedZonePhone1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());
    }

    /**
     * Tries a set of test cases to see if the phone with the lowest ID is given preference. This
     * test also confirms that the time zone setting would only be set if a suggestion is of
     * sufficient quality.
     */
    @Test
    public void testMultiplePhoneSuggestionScoringAndPhoneIdBias() {
        String[] zoneIds = { "Europe/London", "Europe/Paris" };
        PhoneTimeZoneSuggestion emptyPhone1Suggestion = createEmptyPhone1Suggestion();
        PhoneTimeZoneSuggestion emptyPhone2Suggestion = createEmptyPhone2Suggestion();
        QualifiedPhoneTimeZoneSuggestion expectedEmptyPhone1ScoredSuggestion =
                new QualifiedPhoneTimeZoneSuggestion(emptyPhone1Suggestion, PHONE_SCORE_NONE);
        QualifiedPhoneTimeZoneSuggestion expectedEmptyPhone2ScoredSuggestion =
                new QualifiedPhoneTimeZoneSuggestion(emptyPhone2Suggestion, PHONE_SCORE_NONE);

        Script script = new Script()
                .initializeAutoTimeZoneDetection(true)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                // Initialize the latest suggestions as empty so we don't need to worry about nulls
                // below for the first loop.
                .suggestPhoneTimeZone(emptyPhone1Suggestion)
                .suggestPhoneTimeZone(emptyPhone2Suggestion)
                .resetState();

        for (SuggestionTestCase testCase : TEST_CASES) {
            PhoneTimeZoneSuggestion zonePhone1Suggestion =
                    testCase.createSuggestion(PHONE1_ID, zoneIds[0]);
            PhoneTimeZoneSuggestion zonePhone2Suggestion =
                    testCase.createSuggestion(PHONE2_ID, zoneIds[1]);
            QualifiedPhoneTimeZoneSuggestion expectedZonePhone1ScoredSuggestion =
                    new QualifiedPhoneTimeZoneSuggestion(zonePhone1Suggestion,
                            testCase.expectedScore);
            QualifiedPhoneTimeZoneSuggestion expectedZonePhone2ScoredSuggestion =
                    new QualifiedPhoneTimeZoneSuggestion(zonePhone2Suggestion,
                            testCase.expectedScore);

            // Start the test by making a suggestion for phone 1.
            script.suggestPhoneTimeZone(zonePhone1Suggestion);
            if (testCase.expectedScore >= PHONE_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneSetAndReset(zonePhone1Suggestion);
            } else {
                script.verifyTimeZoneNotSet();
            }

            // Assert internal service state.
            assertEquals(expectedZonePhone1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
            assertEquals(expectedEmptyPhone2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE2_ID));
            assertEquals(expectedZonePhone1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());

            // Phone 2 then makes an alternative suggestion with an identical score. Phone 1's
            // suggestion should still "win" if it is above the required threshold.
            script.suggestPhoneTimeZone(zonePhone2Suggestion);
            script.verifyTimeZoneNotSet();

            // Assert internal service state.
            assertEquals(expectedZonePhone1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
            assertEquals(expectedZonePhone2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE2_ID));
            // Phone 1 should always beat phone 2, all other things being equal.
            assertEquals(expectedZonePhone1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());

            // Withdrawing phone 1's suggestion should leave phone 2 as the new winner. Since the
            // zoneId is different, the time zone setting should be updated if the score is high
            // enough.
            script.suggestPhoneTimeZone(emptyPhone1Suggestion);
            if (testCase.expectedScore >= PHONE_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneSetAndReset(zonePhone2Suggestion);
            } else {
                script.verifyTimeZoneNotSet();
            }

            // Assert internal service state.
            assertEquals(expectedEmptyPhone1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
            assertEquals(expectedZonePhone2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE2_ID));
            assertEquals(expectedZonePhone2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestPhoneSuggestionForTests());

            // Reset the state for the next loop.
            script.suggestPhoneTimeZone(emptyPhone2Suggestion)
                    .verifyTimeZoneNotSet();
            assertEquals(expectedEmptyPhone1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE1_ID));
            assertEquals(expectedEmptyPhone2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestPhoneSuggestion(PHONE2_ID));
        }
    }

    /**
     * The {@link TimeZoneDetectorStrategy.Callback} is left to detect whether changing the time
     * zone is actually necessary. This test proves that the service doesn't assume it knows the
     * current setting.
     */
    @Test
    public void testTimeZoneDetectorStrategyDoesNotAssumeCurrentSetting() {
        Script script = new Script()
                .initializeAutoTimeZoneDetection(true);

        SuggestionTestCase testCase =
                newTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                        PHONE_SCORE_HIGH);
        PhoneTimeZoneSuggestion losAngelesSuggestion =
                testCase.createSuggestion(PHONE1_ID, "America/Los_Angeles");
        PhoneTimeZoneSuggestion newYorkSuggestion =
                testCase.createSuggestion(PHONE1_ID, "America/New_York");

        // Initialization.
        script.suggestPhoneTimeZone(losAngelesSuggestion)
                .verifyTimeZoneSetAndReset(losAngelesSuggestion);
        // Suggest it again - it should not be set because it is already set.
        script.suggestPhoneTimeZone(losAngelesSuggestion)
                .verifyTimeZoneNotSet();

        // Toggling time zone detection should set the device time zone only if the current setting
        // value is different from the most recent phone suggestion.
        script.autoTimeZoneDetectionEnabled(false)
                .verifyTimeZoneNotSet()
                .autoTimeZoneDetectionEnabled(true)
                .verifyTimeZoneNotSet();

        // Simulate a user turning auto detection off, a new suggestion being made while auto
        // detection is off, and the user turning it on again.
        script.autoTimeZoneDetectionEnabled(false)
                .suggestPhoneTimeZone(newYorkSuggestion)
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
        script.suggestManualTimeZone(createManualSuggestion("Europe/Paris"))
            .verifyTimeZoneNotSet();
    }


    @Test
    public void testManualSuggestion_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                .initializeAutoTimeZoneDetection(false);

        // Auto time zone detection is disabled so the manual suggestion should be used.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.suggestManualTimeZone(manualSuggestion)
            .verifyTimeZoneSetAndReset(manualSuggestion);
    }

    private ManualTimeZoneSuggestion createManualSuggestion(String zoneId) {
        return new ManualTimeZoneSuggestion(zoneId);
    }

    private static PhoneTimeZoneSuggestion createEmptyPhone1Suggestion() {
        return new PhoneTimeZoneSuggestion.Builder(PHONE1_ID).build();
    }

    private static PhoneTimeZoneSuggestion createEmptyPhone2Suggestion() {
        return new PhoneTimeZoneSuggestion.Builder(PHONE2_ID).build();
    }

    static class FakeTimeZoneDetectorStrategyCallback implements TimeZoneDetectorStrategy.Callback {

        private boolean mAutoTimeZoneDetectionEnabled;
        private TestState<String> mTimeZoneId = new TestState<>();

        @Override
        public boolean isAutoTimeZoneDetectionEnabled() {
            return mAutoTimeZoneDetectionEnabled;
        }

        @Override
        public boolean isDeviceTimeZoneInitialized() {
            return mTimeZoneId != null;
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
            mTimeZoneDetectorStrategy.handleAutoTimeZoneDetectionChange();
            return this;
        }

        /** Simulates the time zone detection strategy receiving a phone-originated suggestion. */
        Script suggestPhoneTimeZone(PhoneTimeZoneSuggestion phoneTimeZoneSuggestion) {
            mTimeZoneDetectorStrategy.suggestPhoneTimeZone(phoneTimeZoneSuggestion);
            return this;
        }

        /** Simulates the time zone detection strategy receiving a user-originated suggestion. */
        Script suggestManualTimeZone(ManualTimeZoneSuggestion manualTimeZoneSuggestion) {
            mTimeZoneDetectorStrategy.suggestManualTimeZone(manualTimeZoneSuggestion);
            return this;
        }

        Script verifyTimeZoneNotSet() {
            mFakeTimeZoneDetectorStrategyCallback.assertTimeZoneNotSet();
            return this;
        }

        Script verifyTimeZoneSetAndReset(PhoneTimeZoneSuggestion suggestion) {
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

        private PhoneTimeZoneSuggestion createSuggestion(int phoneId, String zoneId) {
            return new PhoneTimeZoneSuggestion.Builder(phoneId)
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
