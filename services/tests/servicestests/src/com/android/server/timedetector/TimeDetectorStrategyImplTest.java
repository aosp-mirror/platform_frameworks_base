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

package com.android.server.timedetector;

import static com.android.server.SystemClockTime.TIME_CONFIDENCE_HIGH;
import static com.android.server.SystemClockTime.TIME_CONFIDENCE_LOW;
import static com.android.server.SystemTimeZone.TIME_ZONE_CONFIDENCE_HIGH;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_EXTERNAL;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_GNSS;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_TELEPHONY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.UserIdInt;
import android.app.time.ExternalTimeSuggestion;
import android.app.time.TimeState;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.os.TimestampedValue;

import com.android.server.SystemClockTime.TimeConfidence;
import com.android.server.timedetector.TimeDetectorStrategy.Origin;
import com.android.server.timezonedetector.StateChangeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import junitparams.JUnitParamsRunner;
import junitparams.Parameters;

@RunWith(JUnitParamsRunner.class)
public class TimeDetectorStrategyImplTest {

    private static final @UserIdInt int ARBITRARY_USER_ID = 9876;
    private static final int ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS = 1234;
    private static final Instant DEFAULT_SUGGESTION_LOWER_BOUND =
            createUnixEpochTime(2005, 1, 1, 1, 0, 0);
    /** A value after {@link #DEFAULT_SUGGESTION_LOWER_BOUND} */
    private static final Instant TEST_SUGGESTION_LOWER_BOUND =
            createUnixEpochTime(2006, 1, 1, 1, 0, 0);
    private static final Instant DEFAULT_SUGGESTION_UPPER_BOUND =
            createUnixEpochTime(2099, 12, 1, 1, 0, 0);
    /** A value before {@link #DEFAULT_SUGGESTION_UPPER_BOUND} */
    private static final Instant TEST_SUGGESTION_UPPER_BOUND =
            createUnixEpochTime(2037, 12, 1, 1, 0, 0);

    private static final TimestampedValue<Instant> ARBITRARY_CLOCK_INITIALIZATION_INFO =
            new TimestampedValue<>(
                    123456789L /* realtimeClockMillis */,
                    createUnixEpochTime(2010, 5, 23, 12, 0, 0));

    // This is the traditional ordering for time detection on Android.
    private static final @Origin int [] ORIGIN_PRIORITIES = { ORIGIN_TELEPHONY, ORIGIN_NETWORK };

    /**
     * An arbitrary time, very different from the {@link #ARBITRARY_CLOCK_INITIALIZATION_INFO}
     * time. Can be used as the basis for time suggestions.
     */
    private static final Instant ARBITRARY_TEST_TIME = createUnixEpochTime(2018, 1, 1, 12, 0, 0);

    private static final int ARBITRARY_SLOT_INDEX = 123456;

    private static final ConfigurationInternal CONFIG_AUTO_DISABLED =
            new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionSupported(true)
                    .setSystemClockUpdateThresholdMillis(
                            ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS)
                    .setSystemClockUpdateThresholdMillis(TIME_CONFIDENCE_HIGH)
                    .setAutoSuggestionLowerBound(DEFAULT_SUGGESTION_LOWER_BOUND)
                    .setManualSuggestionLowerBound(DEFAULT_SUGGESTION_LOWER_BOUND)
                    .setSuggestionUpperBound(DEFAULT_SUGGESTION_UPPER_BOUND)
                    .setOriginPriorities(ORIGIN_PRIORITIES)
                    .setAutoDetectionEnabledSetting(false)
                    .build();

    private static final ConfigurationInternal CONFIG_AUTO_ENABLED =
            new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionSupported(true)
                    .setSystemClockUpdateThresholdMillis(
                            ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS)
                    .setSystemClockUpdateThresholdMillis(TIME_CONFIDENCE_HIGH)
                    .setAutoSuggestionLowerBound(DEFAULT_SUGGESTION_LOWER_BOUND)
                    .setManualSuggestionLowerBound(DEFAULT_SUGGESTION_LOWER_BOUND)
                    .setSuggestionUpperBound(DEFAULT_SUGGESTION_UPPER_BOUND)
                    .setOriginPriorities(ORIGIN_PRIORITIES)
                    .setAutoDetectionEnabledSetting(true)
                    .build();

    private FakeEnvironment mFakeEnvironment;

    @Before
    public void setUp() {
        mFakeEnvironment = new FakeEnvironment();
        mFakeEnvironment.initializeConfig(CONFIG_AUTO_DISABLED);
        mFakeEnvironment.initializeFakeClocks(
                ARBITRARY_CLOCK_INITIALIZATION_INFO, TIME_CONFIDENCE_LOW);
    }

    @Test
    public void testSuggestTelephonyTime_autoTimeEnabled() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;

        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);
        script.simulateTimePassing()
                .simulateTelephonyTimeSuggestion(timeSuggestion);

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion);
    }

    @Test
    public void testSuggestTelephonyTime_emptySuggestionIgnored() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, null);
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, null);
    }

    @Test
    public void testSuggestTelephonyTime_systemClockThreshold() {
        final int systemClockUpdateThresholdMillis = 1000;
        final int clockIncrementMillis = 100;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setSystemClockUpdateThresholdMillis(systemClockUpdateThresholdMillis)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        // Send the first time signal. It should be used.
        {
            TelephonyTimeSuggestion timeSuggestion1 =
                    script.generateTelephonyTimeSuggestion(slotIndex, ARBITRARY_TEST_TIME);

            // Increment the device clocks to simulate the passage of time.
            script.simulateTimePassing(clockIncrementMillis);

            long expectedSystemClockMillis1 =
                    script.calculateTimeInMillisForNow(timeSuggestion1.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(timeSuggestion1)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);
        }

        // Now send another time signal, but one that is too similar to the last one and should be
        // stored, but not used to set the system clock.
        {
            int underThresholdMillis = systemClockUpdateThresholdMillis - 1;
            TelephonyTimeSuggestion timeSuggestion2 = script.generateTelephonyTimeSuggestion(
                    slotIndex, script.peekSystemClockMillis() + underThresholdMillis);
            script.simulateTimePassing(clockIncrementMillis)
                    .simulateTelephonyTimeSuggestion(timeSuggestion2)
                    .verifySystemClockWasNotSetAndResetCallTracking()
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);
        }

        // Now send another time signal, but one that is on the threshold and so should be used.
        {
            TelephonyTimeSuggestion timeSuggestion3 = script.generateTelephonyTimeSuggestion(
                    slotIndex,
                    script.peekSystemClockMillis() + systemClockUpdateThresholdMillis);
            script.simulateTimePassing(clockIncrementMillis);

            long expectedSystemClockMillis3 =
                    script.calculateTimeInMillisForNow(timeSuggestion3.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(timeSuggestion3)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis3)
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion3);
        }
    }

    @Test
    public void testSuggestTelephonyTime_multipleSlotIndexsAndBucketing() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED);

        // There are 2 slotIndexes in this test. slotIndex1 and slotIndex2 have different opinions
        // about the current time. slotIndex1 < slotIndex2 (which is important because the strategy
        // uses the lowest slotIndex when multiple telephony suggestions are available.
        int slotIndex1 = ARBITRARY_SLOT_INDEX;
        int slotIndex2 = ARBITRARY_SLOT_INDEX + 1;
        Instant slotIndex1Time = ARBITRARY_TEST_TIME;
        Instant slotIndex2Time = ARBITRARY_TEST_TIME.plus(Duration.ofDays(1));

        // Make a suggestion with slotIndex2.
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    script.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            script.simulateTimePassing();

            long expectedSystemClockMillis = script.calculateTimeInMillisForNow(
                    slotIndex2TimeSuggestion.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex1, null)
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }

        script.simulateTimePassing();

        // Now make a different suggestion with slotIndex1.
        {
            TelephonyTimeSuggestion slotIndex1TimeSuggestion =
                    script.generateTelephonyTimeSuggestion(slotIndex1, slotIndex1Time);
            script.simulateTimePassing();

            long expectedSystemClockMillis = script.calculateTimeInMillisForNow(
                    slotIndex1TimeSuggestion.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(slotIndex1TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex1, slotIndex1TimeSuggestion);

        }

        script.simulateTimePassing();

        // Make another suggestion with slotIndex2. It should be stored but not used because the
        // slotIndex1 suggestion will still "win".
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    script.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            script.simulateTimePassing();

            script.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasNotSetAndResetCallTracking()
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }

        // Let enough time pass that slotIndex1's suggestion should now be too old.
        script.simulateTimePassing(TimeDetectorStrategyImpl.TELEPHONY_BUCKET_SIZE_MILLIS);

        // Make another suggestion with slotIndex2. It should be used because the slotIndex1
        // is in an older "bucket".
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    script.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            script.simulateTimePassing();

            long expectedSystemClockMillis = script.calculateTimeInMillisForNow(
                    slotIndex2TimeSuggestion.getUnixEpochTime());

            script.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }
    }

    /**
     * If an auto suggested time matches the current system clock, the confidence in the current
     * system clock is raised even when auto time is disabled. The system clock itself must not be
     * changed.
     */
    @Test
    public void testSuggestTelephonyTime_autoTimeDisabled_suggestionMatchesSystemClock() {
        TimestampedValue<Instant> initialClockTime = ARBITRARY_CLOCK_INITIALIZATION_INFO;
        final int confidenceUpgradeThresholdMillis = 1000;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setSystemClockConfidenceThresholdMillis(
                                confidenceUpgradeThresholdMillis)
                        .build();
        Script script = new Script()
                .pokeFakeClocks(initialClockTime, TIME_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(configInternal);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        script.simulateTimePassing();
        long timeElapsedMillis =
                script.peekElapsedRealtimeMillis() - initialClockTime.getReferenceTimeMillis();

        // Create a suggestion time that approximately matches the current system clock.
        Instant suggestionInstant = initialClockTime.getValue()
                .plusMillis(timeElapsedMillis)
                .plusMillis(confidenceUpgradeThresholdMillis);
        UnixEpochTime matchingClockTime = new UnixEpochTime(
                script.peekElapsedRealtimeMillis(),
                suggestionInstant.toEpochMilli());
        TelephonyTimeSuggestion timeSuggestion = new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUnixEpochTime(matchingClockTime)
                .build();
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    /**
     * If an auto suggested time doesn't match the current system clock, the confidence in the
     * current system clock will stay where it is. The system clock itself must not be changed.
     */
    @Test
    public void testSuggestTelephonyTime_autoTimeDisabled_suggestionMismatchesSystemClock() {
        TimestampedValue<Instant> initialClockTime = ARBITRARY_CLOCK_INITIALIZATION_INFO;
        final int confidenceUpgradeThresholdMillis = 1000;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setSystemClockConfidenceThresholdMillis(
                                confidenceUpgradeThresholdMillis)
                        .build();
        Script script = new Script().pokeFakeClocks(initialClockTime, TIME_CONFIDENCE_LOW)
                .simulateConfigurationInternalChange(configInternal);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        script.simulateTimePassing();
        long timeElapsedMillis =
                script.peekElapsedRealtimeMillis() - initialClockTime.getReferenceTimeMillis();

        // Create a suggestion time that doesn't match the current system clock closely enough.
        Instant suggestionInstant = initialClockTime.getValue()
                .plusMillis(timeElapsedMillis)
                .plusMillis(confidenceUpgradeThresholdMillis + 1);
        UnixEpochTime mismatchingClockTime = new UnixEpochTime(
                script.peekElapsedRealtimeMillis(),
                suggestionInstant.toEpochMilli());
        TelephonyTimeSuggestion timeSuggestion = new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUnixEpochTime(mismatchingClockTime)
                .build();
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    /**
     * If a suggested time doesn't match the current system clock, the confidence in the current
     * system clock will not drop.
     */
    @Test
    public void testSuggestTelephonyTime_autoTimeDisabled_suggestionMismatchesSystemClock2() {
        TimestampedValue<Instant> initialClockTime = ARBITRARY_CLOCK_INITIALIZATION_INFO;
        final int confidenceUpgradeThresholdMillis = 1000;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setSystemClockConfidenceThresholdMillis(
                                confidenceUpgradeThresholdMillis)
                        .build();
        Script script = new Script().pokeFakeClocks(initialClockTime, TIME_CONFIDENCE_HIGH)
                .simulateConfigurationInternalChange(configInternal);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        script.simulateTimePassing();
        long timeElapsedMillis =
                script.peekElapsedRealtimeMillis() - initialClockTime.getReferenceTimeMillis();

        // Create a suggestion time that doesn't closely match the current system clock.
        Instant initialClockInstant = initialClockTime.getValue();
        UnixEpochTime mismatchingClockTime = new UnixEpochTime(
                script.peekElapsedRealtimeMillis(),
                initialClockInstant.plusMillis(timeElapsedMillis + 1_000_000).toEpochMilli());
        TelephonyTimeSuggestion timeSuggestion = new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUnixEpochTime(mismatchingClockTime)
                .build();
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestTelephonyTime_invalidNitzReferenceTimesIgnored() {
        final int systemClockUpdateThresholdMillis = 2000;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setSystemClockUpdateThresholdMillis(systemClockUpdateThresholdMillis)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant testTime = ARBITRARY_TEST_TIME;
        int slotIndex = ARBITRARY_SLOT_INDEX;

        TelephonyTimeSuggestion timeSuggestion1 =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);
        UnixEpochTime unixEpochTime1 = timeSuggestion1.getUnixEpochTime();

        // Initialize the strategy / device with a time set from a telephony suggestion.
        script.simulateTimePassing();
        long expectedSystemClockMillis1 = script.calculateTimeInMillisForNow(unixEpochTime1);
        script.simulateTelephonyTimeSuggestion(timeSuggestion1)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // The Unix epoch time increment should be larger than the system clock update threshold so
        // we know it shouldn't be ignored for other reasons.
        long validUnixEpochTimeMillis = unixEpochTime1.getUnixEpochTimeMillis()
                + (2 * systemClockUpdateThresholdMillis);

        // Now supply a new signal that has an obviously bogus elapsed realtime : older than the
        // last one.
        long referenceTimeBeforeLastSignalMillis = unixEpochTime1.getElapsedRealtimeMillis() - 1;
        UnixEpochTime unixEpochTime2 = new UnixEpochTime(
                referenceTimeBeforeLastSignalMillis, validUnixEpochTimeMillis);
        TelephonyTimeSuggestion timeSuggestion2 =
                createTelephonyTimeSuggestion(slotIndex, unixEpochTime2);
        script.simulateTelephonyTimeSuggestion(timeSuggestion2)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Now supply a new signal that has an obviously bogus elapsed realtime; one substantially
        // in the future.
        long referenceTimeInFutureMillis =
                unixEpochTime1.getElapsedRealtimeMillis() + Integer.MAX_VALUE + 1;
        UnixEpochTime unixEpochTime3 = new UnixEpochTime(
                referenceTimeInFutureMillis, validUnixEpochTimeMillis);
        TelephonyTimeSuggestion timeSuggestion3 =
                createTelephonyTimeSuggestion(slotIndex, unixEpochTime3);
        script.simulateTelephonyTimeSuggestion(timeSuggestion3)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Just to prove validUnixEpochTimeMillis is valid.
        long validReferenceTimeMillis = unixEpochTime1.getElapsedRealtimeMillis() + 100;
        UnixEpochTime unixEpochTime4 = new UnixEpochTime(
                validReferenceTimeMillis, validUnixEpochTimeMillis);
        long expectedSystemClockMillis4 = script.calculateTimeInMillisForNow(unixEpochTime4);
        TelephonyTimeSuggestion timeSuggestion4 =
                createTelephonyTimeSuggestion(slotIndex, unixEpochTime4);
        script.simulateTelephonyTimeSuggestion(timeSuggestion4)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis4)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion4);
    }

    @Test
    public void testSuggestTelephonyTime_timeDetectionToggled() {
        final int clockIncrementMillis = 100;
        final int systemClockUpdateThresholdMillis = 2000;
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setSystemClockUpdateThresholdMillis(systemClockUpdateThresholdMillis)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion timeSuggestion1 =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);
        UnixEpochTime unixEpochTime1 = timeSuggestion1.getUnixEpochTime();

        // Simulate time passing.
        script.simulateTimePassing(clockIncrementMillis);

        // Simulate the time signal being received. It should not be used because auto time
        // detection is off but it should be recorded.
        script.simulateTelephonyTimeSuggestion(timeSuggestion1)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Simulate more time passing.
        script.simulateTimePassing(clockIncrementMillis);

        long expectedSystemClockMillis1 = script.calculateTimeInMillisForNow(unixEpochTime1);

        // Turn on auto time detection.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Turn off auto time detection.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Receive another valid time signal.
        // It should be on the threshold and accounting for the clock increments.
        TelephonyTimeSuggestion timeSuggestion2 = script.generateTelephonyTimeSuggestion(
                slotIndex, script.peekSystemClockMillis() + systemClockUpdateThresholdMillis);

        // Simulate more time passing.
        script.simulateTimePassing(clockIncrementMillis);

        long expectedSystemClockMillis2 =
                script.calculateTimeInMillisForNow(timeSuggestion2.getUnixEpochTime());

        // The new time, though valid, should not be set in the system clock because auto time is
        // disabled.
        script.simulateTelephonyTimeSuggestion(timeSuggestion2)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);

        // Turn on auto time detection.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis2)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);
    }

    @Test
    public void testSuggestTelephonyTime_maxSuggestionAge() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion telephonySuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(telephonySuggestion.getUnixEpochTime());
        script.simulateTelephonyTimeSuggestion(telephonySuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis  /* expectedNetworkBroadcast */)
                .assertLatestTelephonySuggestion(slotIndex, telephonySuggestion);

        // Look inside and check what the strategy considers the current best telephony suggestion.
        assertEquals(telephonySuggestion, script.peekBestTelephonySuggestion());

        // Simulate time passing, long enough that telephonySuggestion is now too old.
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS);

        // Look inside and check what the strategy considers the current best telephony suggestion.
        // It should still be there, it's just no longer used.
        assertNull(script.peekBestTelephonySuggestion());
        script.assertLatestTelephonySuggestion(slotIndex, telephonySuggestion);
    }

    @Test
    public void testSuggestTelephonyTime_rejectedBelowLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .setAutoSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowLowerBound = TEST_SUGGESTION_LOWER_BOUND.minusSeconds(1);
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, belowLowerBound);
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestTelephonyTime_notRejectedAboveLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .setAutoSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveLowerBound = TEST_SUGGESTION_LOWER_BOUND.plusSeconds(1);
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, aboveLowerBound);
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(aboveLowerBound.toEpochMilli());
    }

    @Test
    public void testSuggestTelephonyTime_rejectedAboveUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveUpperBound = TEST_SUGGESTION_UPPER_BOUND.plusSeconds(1);
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, aboveUpperBound);
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestTelephonyTime_notRejectedBelowUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowUpperBound = TEST_SUGGESTION_UPPER_BOUND.minusSeconds(1);
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, belowUpperBound);
        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(belowUpperBound.toEpochMilli());
    }

    @Test
    public void testSuggestManualTime_autoTimeDisabled() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_DISABLED)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        ManualTimeSuggestion timeSuggestion =
                script.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = true;
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    /** Confirms the effect of user policy restrictions on being able to set the time. */
    @Test
    @Parameters({ "true,true", "true,false", "false,true", "false,false" })
    public void testSuggestManualTime_autoTimeDisabled_userRestrictions(
            boolean userConfigAllowed, boolean bypassUserPolicyChecks) {
        ConfigurationInternal configAutoDisabled =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setUserConfigAllowed(userConfigAllowed)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configAutoDisabled)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        ManualTimeSuggestion timeSuggestion =
                script.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        boolean expectedResult = userConfigAllowed || bypassUserPolicyChecks;
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, bypassUserPolicyChecks, expectedResult);
        if (expectedResult) {
            script.verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
        } else {
            script.verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                    .verifySystemClockWasNotSetAndResetCallTracking();
        }
    }

    @Test
    public void testSuggestManualTime_retainsAutoSignal() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        // Simulate a telephony suggestion.
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);

        // Simulate the passage of time.
        script.simulateTimePassing();

        long expectedAutoClockMillis =
                script.calculateTimeInMillisForNow(telephonyTimeSuggestion.getUnixEpochTime());
        script.simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedAutoClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        script.simulateTimePassing();

        // Switch to manual.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        script.simulateTimePassing();

        // Simulate a manual suggestion 1 day different from the auto suggestion.
        Instant manualTime = testTime.plus(Duration.ofDays(1));
        ManualTimeSuggestion manualTimeSuggestion =
                script.generateManualTimeSuggestion(manualTime);
        script.simulateTimePassing();

        long expectedManualClockMillis =
                script.calculateTimeInMillisForNow(manualTimeSuggestion.getUnixEpochTime());
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = true;
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, manualTimeSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedManualClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        script.simulateTimePassing();

        // Switch back to auto.
        script.simulateAutoTimeDetectionToggle();

        expectedAutoClockMillis =
                script.calculateTimeInMillisForNow(telephonyTimeSuggestion.getUnixEpochTime());
        script.verifySystemClockWasSetAndResetCallTracking(expectedAutoClockMillis)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Switch back to manual - nothing should happen to the clock.
        script.simulateAutoTimeDetectionToggle()
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);
    }

    @Test
    public void testSuggestManualTime_isIgnored_whenAutoTimeEnabled() {
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED)
                        .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        ManualTimeSuggestion timeSuggestion =
                script.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = false;
        script.simulateTimePassing().simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestManualTime_rejectedAboveUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveUpperBound = TEST_SUGGESTION_UPPER_BOUND.plusSeconds(1);
        ManualTimeSuggestion timeSuggestion = script.generateManualTimeSuggestion(aboveUpperBound);
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = false;
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestManualTime_notRejectedBelowUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowUpperBound = TEST_SUGGESTION_UPPER_BOUND.minusSeconds(1);
        ManualTimeSuggestion timeSuggestion = script.generateManualTimeSuggestion(belowUpperBound);
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = true;
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(belowUpperBound.toEpochMilli());
    }

    @Test
    public void testSuggestManualTime_rejectedBelowLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setManualSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowLowerBound = TEST_SUGGESTION_LOWER_BOUND.minusSeconds(1);
        ManualTimeSuggestion timeSuggestion = script.generateManualTimeSuggestion(belowLowerBound);
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = false;
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestManualTimes_notRejectedAboveLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setManualSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveLowerBound = TEST_SUGGESTION_LOWER_BOUND.plusSeconds(1);
        ManualTimeSuggestion timeSuggestion = script.generateManualTimeSuggestion(aboveLowerBound);
        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = true;
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(aboveLowerBound.toEpochMilli());
    }

    @Test
    public void testSuggestNetworkTime_autoTimeEnabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestNetworkTime_autoTimeDisabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing()
                .simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testClearLatestNetworkSuggestion() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK, ORIGIN_EXTERNAL)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        // Create two different time suggestions for the current elapsedRealtimeMillis.
        ExternalTimeSuggestion externalTimeSuggestion =
                script.generateExternalTimeSuggestion(ARBITRARY_TEST_TIME);
        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME.plus(Duration.ofHours(5)));
        script.simulateTimePassing();

        // Suggest an external time: This should cause the device to change time.
        {
            long expectedSystemClockMillis =
                    script.calculateTimeInMillisForNow(externalTimeSuggestion.getUnixEpochTime());
            script.simulateExternalTimeSuggestion(externalTimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
        }

        // Suggest a network time: This should cause the device to change time because
        // network > external.
        {
            long expectedSystemClockMillis =
                    script.calculateTimeInMillisForNow(networkTimeSuggestion.getUnixEpochTime());
            script.simulateNetworkTimeSuggestion(networkTimeSuggestion)
                    .assertLatestNetworkSuggestion(networkTimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
        }

        // Clear the network time. This should cause the device to change back to the external time,
        // which is now the best time available.
        {
            long expectedSystemClockMillis =
                    script.calculateTimeInMillisForNow(externalTimeSuggestion.getUnixEpochTime());
            script.simulateClearLatestNetworkSuggestion()
                    .assertLatestNetworkSuggestion(null)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
        }
    }

    @Test
    public void testSuggestNetworkTime_rejectedBelowLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .setAutoSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowLowerBound = TEST_SUGGESTION_LOWER_BOUND.minusSeconds(1);
        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(belowLowerBound);
        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(null)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestNetworkTime_notRejectedAboveLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .setAutoSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveLowerBound = TEST_SUGGESTION_LOWER_BOUND.plusSeconds(1);
        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(aboveLowerBound);
        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(aboveLowerBound.toEpochMilli());
    }

    @Test
    public void testSuggestNetworkTime_rejectedAboveUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveUpperBound = TEST_SUGGESTION_UPPER_BOUND.plusSeconds(1);
        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(aboveUpperBound);
        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(null)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestNetworkTime_notRejectedBelowUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowUpperBound = TEST_SUGGESTION_UPPER_BOUND.minusSeconds(1);
        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(belowUpperBound);
        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(belowUpperBound.toEpochMilli());
    }

    @Test
    public void testSuggestGnssTime_autoTimeEnabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestGnssTime_autoTimeDisabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing()
                .simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestGnssTime_rejectedBelowLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_GNSS)
                        .setAutoSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowLowerBound = TEST_SUGGESTION_LOWER_BOUND.minusSeconds(1);
        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(belowLowerBound);
        script.simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestGnssTime_notRejectedAboveLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_GNSS)
                        .setAutoSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveLowerBound = TEST_SUGGESTION_LOWER_BOUND.plusSeconds(1);
        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(aboveLowerBound);
        script.simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(aboveLowerBound.toEpochMilli());
    }

    @Test
    public void testSuggestGnssTime_rejectedAboveUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_GNSS)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveUpperBound = TEST_SUGGESTION_UPPER_BOUND.plusSeconds(1);
        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(aboveUpperBound);
        script.simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestGnssTime_notRejectedBelowUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_GNSS)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowUpperBound = TEST_SUGGESTION_UPPER_BOUND.minusSeconds(1);
        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(belowUpperBound);
        script.simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(belowUpperBound.toEpochMilli());
    }

    @Test
    public void testSuggestExternalTime_autoTimeEnabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing();

        long expectedSystemClockMillis =
                script.calculateTimeInMillisForNow(timeSuggestion.getUnixEpochTime());
        script.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestExternalTime_autoTimeDisabled() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateTimePassing()
                .simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestExternalTime_rejectedBelowLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .setAutoSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowLowerBound = TEST_SUGGESTION_LOWER_BOUND.minusSeconds(1);
        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(belowLowerBound);
        script.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestExternalTime_notRejectedAboveLowerBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .setAutoSuggestionLowerBound(TEST_SUGGESTION_LOWER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveLowerBound = TEST_SUGGESTION_LOWER_BOUND.plusSeconds(1);
        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(aboveLowerBound);
        script.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(aboveLowerBound.toEpochMilli());
    }

    @Test
    public void testSuggestExternalTime_rejectedAboveUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant aboveUpperBound = TEST_SUGGESTION_UPPER_BOUND.plusSeconds(1);
        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(aboveUpperBound);
        script.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestExternalTime_notRejectedBelowUpperBound() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_EXTERNAL)
                        .setSuggestionUpperBound(TEST_SUGGESTION_UPPER_BOUND)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        Instant belowUpperBound = TEST_SUGGESTION_UPPER_BOUND.minusSeconds(1);
        ExternalTimeSuggestion timeSuggestion =
                script.generateExternalTimeSuggestion(belowUpperBound);
        script.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(belowUpperBound.toEpochMilli());
    }

    @Test
    public void testGetTimeState() {
        TimestampedValue<Instant> deviceTime = ARBITRARY_CLOCK_INITIALIZATION_INFO;
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED)
                .pokeFakeClocks(deviceTime, TIME_CONFIDENCE_LOW)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        UnixEpochTime systemClockTime = new UnixEpochTime(deviceTime.getReferenceTimeMillis(),
                deviceTime.getValue().toEpochMilli());

        // When confidence is low, the user should confirm.
        script.assertGetTimeStateReturns(new TimeState(systemClockTime, true));

        // When confidence is high, no need for the user to confirm.
        script.pokeFakeClocks(deviceTime, TIME_ZONE_CONFIDENCE_HIGH)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH);

        script.assertGetTimeStateReturns(new TimeState(systemClockTime, false));
    }

    @Test
    public void testSetTimeState() {
        TimestampedValue<Instant> deviceTime = ARBITRARY_CLOCK_INITIALIZATION_INFO;
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED)
                .pokeFakeClocks(deviceTime, TIME_CONFIDENCE_LOW)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);


        UnixEpochTime systemClockTime = new UnixEpochTime(11111L, 222222L);
        boolean userShouldConfirmTime = false;
        TimeState state = new TimeState(systemClockTime, userShouldConfirmTime);
        script.simulateSetTimeState(state);

        UnixEpochTime expectedTime = systemClockTime.at(script.peekElapsedRealtimeMillis());
        long expectedTimeMillis = expectedTime.getUnixEpochTimeMillis();
        // userShouldConfirmTime == high confidence
        script.verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasSetAndResetCallTracking(expectedTimeMillis);

        TimeState expectedTimeState = new TimeState(expectedTime, userShouldConfirmTime);
        script.assertGetTimeStateReturns(expectedTimeState);
    }

    @Test
    public void testConfirmTime_autoDisabled() {
        testConfirmTime(CONFIG_AUTO_ENABLED);
    }

    @Test
    public void testConfirmTime_autoEnabled() {
        testConfirmTime(CONFIG_AUTO_ENABLED);
    }

    private void testConfirmTime(ConfigurationInternal config) {
        TimestampedValue<Instant> deviceTime = ARBITRARY_CLOCK_INITIALIZATION_INFO;
        Script script = new Script().simulateConfigurationInternalChange(CONFIG_AUTO_ENABLED)
                .pokeFakeClocks(deviceTime, TIME_CONFIDENCE_LOW)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW);

        long maxConfidenceThreshold = config.getSystemClockConfidenceThresholdMillis();
        UnixEpochTime incorrectTime1 =
                new UnixEpochTime(
                        deviceTime.getReferenceTimeMillis() + maxConfidenceThreshold + 1,
                        deviceTime.getValue().toEpochMilli());
        script.simulateConfirmTime(incorrectTime1, false)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();

        UnixEpochTime incorrectTime2 =
                new UnixEpochTime(
                        deviceTime.getReferenceTimeMillis(),
                        deviceTime.getValue().toEpochMilli() + maxConfidenceThreshold + 1);
        script.simulateConfirmTime(incorrectTime2, false)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Confirm using a time that is at the threshold.
        UnixEpochTime correctTime1 =
                new UnixEpochTime(
                        deviceTime.getReferenceTimeMillis(),
                        deviceTime.getValue().toEpochMilli() + maxConfidenceThreshold);
        script.simulateConfirmTime(correctTime1, true)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Reset back to low confidence.
        script.pokeFakeClocks(deviceTime, TIME_CONFIDENCE_LOW)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Confirm using a time that is at the threshold.
        UnixEpochTime correctTime2 =
                new UnixEpochTime(
                        deviceTime.getReferenceTimeMillis() + maxConfidenceThreshold,
                        deviceTime.getValue().toEpochMilli());
        script.simulateConfirmTime(correctTime2, true)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Reset back to low confidence.
        script.pokeFakeClocks(deviceTime, TIME_CONFIDENCE_LOW)
                .verifySystemClockConfidence(TIME_CONFIDENCE_LOW)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Confirm using a time that exactly matches.
        UnixEpochTime correctTime3 =
                new UnixEpochTime(
                        deviceTime.getReferenceTimeMillis(),
                        deviceTime.getValue().toEpochMilli());
        script.simulateConfirmTime(correctTime3, true)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now try to confirm using another incorrect time: Confidence should remain high as the
        // confirmation is ignored / returns false.
        script.simulateConfirmTime(incorrectTime1, false)
                .verifySystemClockConfidence(TIME_CONFIDENCE_HIGH)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_telephonyNetworkOrigins() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        // Three obviously different times that could not be mistaken for each other.
        Instant networkTime1 = ARBITRARY_TEST_TIME;
        Instant networkTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A network suggestion is made. It should be used because there is no telephony suggestion.
        NetworkTimeSuggestion networkTimeSuggestion1 =
                script.generateNetworkTimeSuggestion(networkTime1);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                networkTimeSuggestion1.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, null)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, script.peekLatestValidNetworkSuggestion());
        assertNull("No telephony suggestions were made:", script.peekBestTelephonySuggestion());

        // Simulate a little time passing.
        script.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a telephony suggestion is made. Telephony suggestions are prioritized over network
        // suggestions so it should "win".
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                telephonyTimeSuggestion.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, script.peekLatestValidNetworkSuggestion());
        assertEquals(telephonyTimeSuggestion, script.peekBestTelephonySuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another network suggestion is made. Telephony suggestions are prioritized over
        // network suggestions so the latest telephony suggestion should still "win".
        NetworkTimeSuggestion networkTimeSuggestion2 =
                script.generateNetworkTimeSuggestion(networkTime2);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, script.peekLatestValidNetworkSuggestion());
        assertEquals(telephonyTimeSuggestion, script.peekBestTelephonySuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that telephonyTimeSuggestion is now too old to
        // be used but networkTimeSuggestion2 is not.
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        script.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, script.peekLatestValidNetworkSuggestion());
        assertNull(
                "Telephony suggestion should be expired:",
                script.peekBestTelephonySuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        script.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest network time now wins.
        script.verifySystemClockWasSetAndResetCallTracking(
                script.calculateTimeInMillisForNow(networkTimeSuggestion2.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, script.peekLatestValidNetworkSuggestion());
        assertNull(
                "Telephony suggestion should still be expired:",
                script.peekBestTelephonySuggestion());
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_networkGnssOrigins() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK, ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        // Three obviously different times that could not be mistaken for each other.
        Instant gnssTime1 = ARBITRARY_TEST_TIME;
        Instant gnssTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant networkTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A gnss suggestion is made. It should be used because there is no network suggestion.
        GnssTimeSuggestion gnssTimeSuggestion1 =
                script.generateGnssTimeSuggestion(gnssTime1);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                gnssTimeSuggestion1.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(null)
                .assertLatestGnssSuggestion(gnssTimeSuggestion1);
        assertEquals(gnssTimeSuggestion1, script.peekLatestValidGnssSuggestion());
        assertNull("No network suggestions were made:", script.peekLatestValidNetworkSuggestion());

        // Simulate a little time passing.
        script.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a network suggestion is made. Network suggestions are prioritized over gnss
        // suggestions so it should "win".
        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(networkTime);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                networkTimeSuggestion.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion1);
        assertEquals(gnssTimeSuggestion1, script.peekLatestValidGnssSuggestion());
        assertEquals(networkTimeSuggestion, script.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another gnss suggestion is made. Network suggestions are prioritized over
        // gnss suggestions so the latest network suggestion should still "win".
        GnssTimeSuggestion gnssTimeSuggestion2 =
                script.generateGnssTimeSuggestion(gnssTime2);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, script.peekLatestValidGnssSuggestion());
        assertEquals(networkTimeSuggestion, script.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that telephonyTimeSuggestion is now too old to
        // be used but networkTimeSuggestion2 is not.
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        script.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, script.peekLatestValidGnssSuggestion());
        assertNull(
                "Network suggestion should be expired:",
                script.peekLatestValidNetworkSuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        script.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest gnss time now wins.
        script.verifySystemClockWasSetAndResetCallTracking(
                script.calculateTimeInMillisForNow(gnssTimeSuggestion2.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, script.peekLatestValidGnssSuggestion());
        assertNull(
                "Network suggestion should still be expired:",
                script.peekLatestValidNetworkSuggestion());
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_networkExternalOrigins() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK, ORIGIN_EXTERNAL)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        // Three obviously different times that could not be mistaken for each other.
        Instant externalTime1 = ARBITRARY_TEST_TIME;
        Instant externalTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant networkTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A external suggestion is made. It should be used because there is no network suggestion.
        ExternalTimeSuggestion externalTimeSuggestion1 =
                script.generateExternalTimeSuggestion(externalTime1);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateExternalTimeSuggestion(externalTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                externalTimeSuggestion1.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(null)
                .assertLatestExternalSuggestion(externalTimeSuggestion1);
        assertEquals(externalTimeSuggestion1, script.peekLatestValidExternalSuggestion());
        assertNull("No network suggestions were made:", script.peekLatestValidNetworkSuggestion());

        // Simulate a little time passing.
        script.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a network suggestion is made. Network suggestions are prioritized over external
        // suggestions so it should "win".
        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(networkTime);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        script.calculateTimeInMillisForNow(
                                networkTimeSuggestion.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion1);
        assertEquals(externalTimeSuggestion1, script.peekLatestValidExternalSuggestion());
        assertEquals(networkTimeSuggestion, script.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another external suggestion is made. Network suggestions are prioritized over
        // external suggestions so the latest network suggestion should still "win".
        ExternalTimeSuggestion externalTimeSuggestion2 =
                script.generateExternalTimeSuggestion(externalTime2);
        script.simulateTimePassing(smallTimeIncrementMillis)
                .simulateExternalTimeSuggestion(externalTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, script.peekLatestValidExternalSuggestion());
        assertEquals(networkTimeSuggestion, script.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that networkTimeSuggestion is now too old to
        // be used but externalTimeSuggestion2 is not.
        script.simulateTimePassing(TimeDetectorStrategyImpl.MAX_SUGGESTION_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        script.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, script.peekLatestValidExternalSuggestion());
        assertNull(
                "Network suggestion should be expired:",
                script.peekLatestValidNetworkSuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        script.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest external time now wins.
        script.verifySystemClockWasSetAndResetCallTracking(
                script.calculateTimeInMillisForNow(externalTimeSuggestion2.getUnixEpochTime()));

        // Check internal state.
        script.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, script.peekLatestValidExternalSuggestion());
        assertNull(
                "Network suggestion should still be expired:",
                script.peekLatestValidNetworkSuggestion());
    }

    @Test
    public void whenAllTimeSuggestionsAreAvailable_higherPriorityWins_lowerPriorityComesFirst() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK, ORIGIN_EXTERNAL,
                                ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        Instant networkTime = ARBITRARY_TEST_TIME;
        Instant externalTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(15));
        Instant gnssTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));

        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(networkTime);
        ExternalTimeSuggestion externalTimeSuggestion =
                script.generateExternalTimeSuggestion(externalTime);
        GnssTimeSuggestion gnssTimeSuggestion =
                script.generateGnssTimeSuggestion(gnssTime);
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);

        script.simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .simulateExternalTimeSuggestion(externalTimeSuggestion)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion)
                .simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion)
                .assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(telephonyTime.toEpochMilli());
    }

    @Test
    public void whenAllTimeSuggestionsAreAvailable_higherPriorityWins_higherPriorityComesFirst() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK, ORIGIN_EXTERNAL,
                                ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        Instant networkTime = ARBITRARY_TEST_TIME;
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant externalTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(50));
        Instant gnssTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));

        NetworkTimeSuggestion networkTimeSuggestion =
                script.generateNetworkTimeSuggestion(networkTime);
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                script.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);
        GnssTimeSuggestion gnssTimeSuggestion =
                script.generateGnssTimeSuggestion(gnssTime);
        ExternalTimeSuggestion externalTimeSuggestion =
                script.generateExternalTimeSuggestion(externalTime);

        script.simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion)
                .simulateExternalTimeSuggestion(externalTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(telephonyTime.toEpochMilli());
    }

    @Test
    public void whenHighestPrioritySuggestionIsNotAvailable_fallbacksToNext() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        NetworkTimeSuggestion timeSuggestion =
                script.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    @Test
    public void whenHigherPrioritySuggestionsAreNotAvailable_fallbacksToNext() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK, ORIGIN_EXTERNAL,
                                ORIGIN_GNSS)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        GnssTimeSuggestion timeSuggestion =
                script.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        script.simulateGnssTimeSuggestion(timeSuggestion)
                .assertLatestGnssSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    @Test
    public void suggestionsFromTelephonyOriginNotInPriorityList_areIgnored() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_NETWORK)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion timeSuggestion =
                script.generateTelephonyTimeSuggestion(slotIndex, testTime);

        script.simulateTelephonyTimeSuggestion(timeSuggestion)
                .assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromNetworkOriginNotInPriorityList_areNotUsed() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        NetworkTimeSuggestion timeSuggestion = script.generateNetworkTimeSuggestion(
                ARBITRARY_TEST_TIME);

        script.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromGnssOriginNotInPriorityList_areNotUsed() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        GnssTimeSuggestion timeSuggestion = script.generateGnssTimeSuggestion(
                ARBITRARY_TEST_TIME);

        script.simulateGnssTimeSuggestion(timeSuggestion)
                .assertLatestGnssSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromExternalOriginNotInPriorityList_areNotUsed() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_ENABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        ExternalTimeSuggestion timeSuggestion = script.generateExternalTimeSuggestion(
                ARBITRARY_TEST_TIME);

        script.simulateExternalTimeSuggestion(timeSuggestion)
                .assertLatestExternalSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void autoOriginPrioritiesList_doesNotAffectManualSuggestion() {
        ConfigurationInternal configInternal =
                new ConfigurationInternal.Builder(CONFIG_AUTO_DISABLED)
                        .setOriginPriorities(ORIGIN_TELEPHONY)
                        .build();
        Script script = new Script().simulateConfigurationInternalChange(configInternal);

        ManualTimeSuggestion timeSuggestion =
                script.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        boolean bypassUserPolicyChecks = false;
        boolean expectedResult = true;
        script.simulateManualTimeSuggestion(
                ARBITRARY_USER_ID, timeSuggestion, bypassUserPolicyChecks, expectedResult)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    /**
     * A fake implementation of {@link TimeDetectorStrategyImpl.Environment}. Besides tracking
     * changes and behaving like the real thing should, it also asserts preconditions.
     */
    private static class FakeEnvironment implements TimeDetectorStrategyImpl.Environment {

        private ConfigurationInternal mConfigurationInternal;
        private boolean mWakeLockAcquired;
        private long mElapsedRealtimeMillis;
        private long mSystemClockMillis;
        private int mSystemClockConfidence = TIME_CONFIDENCE_LOW;
        private StateChangeListener mConfigurationInternalChangeListener;

        // Tracking operations.
        private boolean mSystemClockWasSet;

        void initializeConfig(ConfigurationInternal configurationInternal) {
            mConfigurationInternal = configurationInternal;
        }

        public void initializeFakeClocks(
                TimestampedValue<Instant> timeInfo, @TimeConfidence int timeConfidence) {
            pokeElapsedRealtimeMillis(timeInfo.getReferenceTimeMillis());
            pokeSystemClockMillis(timeInfo.getValue().toEpochMilli(), timeConfidence);
        }

        @Override
        public void setConfigurationInternalChangeListener(StateChangeListener listener) {
            mConfigurationInternalChangeListener = Objects.requireNonNull(listener);
        }

        @Override
        public ConfigurationInternal getCurrentUserConfigurationInternal() {
            return mConfigurationInternal;
        }

        @Override
        public void acquireWakeLock() {
            if (mWakeLockAcquired) {
                fail("Wake lock already acquired");
            }
            mWakeLockAcquired = true;
        }

        @Override
        public long elapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        @Override
        public long systemClockMillis() {
            return mSystemClockMillis;
        }

        @Override
        public @TimeConfidence int systemClockConfidence() {
            return mSystemClockConfidence;
        }

        @Override
        public void setSystemClock(
                long newTimeMillis, @TimeConfidence int confidence, String logMsg) {
            assertWakeLockAcquired();
            mSystemClockWasSet = true;
            mSystemClockMillis = newTimeMillis;
            mSystemClockConfidence = confidence;
        }

        @Override
        public void setSystemClockConfidence(@TimeConfidence int confidence, String logMsg) {
            assertWakeLockAcquired();
            mSystemClockConfidence = confidence;
        }

        @Override
        public void releaseWakeLock() {
            assertWakeLockAcquired();
            mWakeLockAcquired = false;
        }

        @Override
        public void addDebugLogEntry(String logMsg) {
            // No-op for tests
        }

        @Override
        public void dumpDebugLog(PrintWriter printWriter) {
            // No-op for tests
        }

        // Methods below are for managing the fake's behavior.

        void simulateConfigurationInternalChange(ConfigurationInternal configurationInternal) {
            mConfigurationInternal = configurationInternal;
            mConfigurationInternalChangeListener.onChange();
        }

        void pokeElapsedRealtimeMillis(long elapsedRealtimeMillis) {
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
        }

        void pokeSystemClockMillis(long systemClockMillis, @TimeConfidence int timeConfidence) {
            mSystemClockMillis = systemClockMillis;
            mSystemClockConfidence = timeConfidence;
        }

        long peekElapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        long peekSystemClockMillis() {
            return mSystemClockMillis;
        }

        void simulateTimePassing(long incrementMillis) {
            mElapsedRealtimeMillis += incrementMillis;
            mSystemClockMillis += incrementMillis;
        }

        void verifySystemClockNotSet() {
            assertFalse(
                    String.format("System clock was manipulated and set to %s(=%s)",
                            Instant.ofEpochMilli(mSystemClockMillis), mSystemClockMillis),
                    mSystemClockWasSet);
        }

        void verifySystemClockWasSet(long expectedSystemClockMillis) {
            assertTrue(mSystemClockWasSet);
            assertEquals(expectedSystemClockMillis, mSystemClockMillis);
        }

        public void verifySystemClockConfidenceLatest(@TimeConfidence int expectedConfidence) {
            assertEquals(expectedConfidence, mSystemClockConfidence);
        }

        void resetCallTracking() {
            mSystemClockWasSet = false;
        }

        private void assertWakeLockAcquired() {
            assertTrue("The operation must be performed only after acquiring the wakelock",
                    mWakeLockAcquired);
        }
    }

    /**
     * A fluent helper class for tests.
     */
    private class Script {

        private final TimeDetectorStrategyImpl mTimeDetectorStrategy;

        Script() {
            mFakeEnvironment = new FakeEnvironment();
            mTimeDetectorStrategy = new TimeDetectorStrategyImpl(mFakeEnvironment);
        }

        Script pokeFakeClocks(TimestampedValue<Instant> initialClockTime,
                @TimeConfidence int timeConfidence) {
            mFakeEnvironment.pokeElapsedRealtimeMillis(initialClockTime.getReferenceTimeMillis());
            mFakeEnvironment.pokeSystemClockMillis(
                    initialClockTime.getValue().toEpochMilli(), timeConfidence);
            return this;
        }

        long peekElapsedRealtimeMillis() {
            return mFakeEnvironment.peekElapsedRealtimeMillis();
        }

        long peekSystemClockMillis() {
            return mFakeEnvironment.peekSystemClockMillis();
        }

        /**
         * Simulates the user / user's configuration changing.
         */
        Script simulateConfigurationInternalChange(ConfigurationInternal configurationInternal) {
            mFakeEnvironment.simulateConfigurationInternalChange(configurationInternal);
            return this;
        }

        Script simulateTelephonyTimeSuggestion(TelephonyTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestTelephonyTime(timeSuggestion);
            return this;
        }

        Script simulateManualTimeSuggestion(
                @UserIdInt int userId, ManualTimeSuggestion timeSuggestion,
                boolean bypassUserPolicyChecks, boolean expectedResult) {
            String errorMessage = expectedResult
                    ? "Manual time suggestion was ignored, but expected to be accepted."
                    : "Manual time suggestion was accepted, but expected to be ignored.";
            assertEquals(
                    errorMessage,
                    expectedResult,
                    mTimeDetectorStrategy.suggestManualTime(
                            userId, timeSuggestion, bypassUserPolicyChecks));
            return this;
        }

        Script simulateNetworkTimeSuggestion(NetworkTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestNetworkTime(timeSuggestion);
            return this;
        }

        Script simulateClearLatestNetworkSuggestion() {
            mTimeDetectorStrategy.clearLatestNetworkSuggestion();
            return this;
        }

        Script simulateGnssTimeSuggestion(GnssTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestGnssTime(timeSuggestion);
            return this;
        }

        Script simulateExternalTimeSuggestion(ExternalTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestExternalTime(timeSuggestion);
            return this;
        }

        Script simulateAutoTimeDetectionToggle() {
            ConfigurationInternal configurationInternal =
                    mFakeEnvironment.getCurrentUserConfigurationInternal();
            boolean autoDetectionEnabledSetting =
                    !configurationInternal.getAutoDetectionEnabledSetting();
            ConfigurationInternal newConfigurationInternal =
                    new ConfigurationInternal.Builder(configurationInternal)
                            .setAutoDetectionEnabledSetting(autoDetectionEnabledSetting)
                            .build();
            mFakeEnvironment.simulateConfigurationInternalChange(newConfigurationInternal);
            return this;
        }

        Script simulateTimePassing(long clockIncrementMillis) {
            mFakeEnvironment.simulateTimePassing(clockIncrementMillis);
            return this;
        }

        /**
         * Simulates time passing by an arbitrary (but relatively small) amount.
         */
        Script simulateTimePassing() {
            return simulateTimePassing(999);
        }

        /** Calls {@link TimeDetectorStrategy#setTimeState(TimeState)}. */
        Script simulateSetTimeState(TimeState timeState) {
            mTimeDetectorStrategy.setTimeState(timeState);
            return this;
        }

        /** Calls {@link TimeDetectorStrategy#confirmTime(UnixEpochTime)}. */
        Script simulateConfirmTime(UnixEpochTime confirmationTime, boolean expectedReturnValue) {
            assertEquals(expectedReturnValue, mTimeDetectorStrategy.confirmTime(confirmationTime));
            return this;
        }

        Script verifySystemClockWasNotSetAndResetCallTracking() {
            mFakeEnvironment.verifySystemClockNotSet();
            mFakeEnvironment.resetCallTracking();
            return this;
        }

        Script verifySystemClockWasSetAndResetCallTracking(long expectedSystemClockMillis) {
            mFakeEnvironment.verifySystemClockWasSet(expectedSystemClockMillis);
            mFakeEnvironment.resetCallTracking();
            return this;
        }

        Script verifySystemClockConfidence(@TimeConfidence int expectedConfidence) {
            mFakeEnvironment.verifySystemClockConfidenceLatest(expectedConfidence);
            return this;
        }

        /**
         * White box test info: Asserts the latest suggestion for the slotIndex is as expected.
         */
        Script assertLatestTelephonySuggestion(int slotIndex, TelephonyTimeSuggestion expected) {
            assertEquals(
                    "Expected to see " + expected + " at slotIndex=" + slotIndex + ", but got "
                            + mTimeDetectorStrategy.getLatestTelephonySuggestion(slotIndex),
                    expected, mTimeDetectorStrategy.getLatestTelephonySuggestion(slotIndex));
            return this;
        }

        /**
         * White box test info: Asserts the latest network suggestion is as expected.
         */
        Script assertLatestNetworkSuggestion(NetworkTimeSuggestion expected) {
            assertEquals(expected, mTimeDetectorStrategy.getLatestNetworkSuggestion());
            return this;
        }

        /**
         * White box test info: Asserts the latest gnss suggestion is as expected.
         */
        Script assertLatestGnssSuggestion(GnssTimeSuggestion expected) {
            assertEquals(expected, mTimeDetectorStrategy.getLatestGnssSuggestion());
            return this;
        }

        /**
         * White box test info: Asserts the latest external suggestion is as expected.
         */
        Script assertLatestExternalSuggestion(ExternalTimeSuggestion expected) {
            assertEquals(expected, mTimeDetectorStrategy.getLatestExternalSuggestion());
            return this;
        }

        Script assertGetTimeStateReturns(TimeState expected) {
            assertEquals(expected, mTimeDetectorStrategy.getTimeState());
            return this;
        }

        /**
         * White box test info: Returns the telephony suggestion that would be used, if any, given
         * the current elapsed real time clock and regardless of origin prioritization.
         */
        TelephonyTimeSuggestion peekBestTelephonySuggestion() {
            return mTimeDetectorStrategy.findBestTelephonySuggestionForTests();
        }

        /**
         * White box test info: Returns the network suggestion that would be used, if any, given the
         * current elapsed real time clock and regardless of origin prioritization.
         */
        NetworkTimeSuggestion peekLatestValidNetworkSuggestion() {
            return mTimeDetectorStrategy.findLatestValidNetworkSuggestionForTests();
        }

        /**
         * White box test info: Returns the gnss suggestion that would be used, if any, given the
         * current elapsed real time clock and regardless of origin prioritization.
         */
        GnssTimeSuggestion peekLatestValidGnssSuggestion() {
            return mTimeDetectorStrategy.findLatestValidGnssSuggestionForTests();
        }

        /**
         * White box test info: Returns the external suggestion that would be used, if any, given
         * the current elapsed real time clock and regardless of origin prioritization.
         */
        ExternalTimeSuggestion peekLatestValidExternalSuggestion() {
            return mTimeDetectorStrategy.findLatestValidExternalSuggestionForTests();
        }

        /**
         * Generates a ManualTimeSuggestion using the current elapsed realtime clock for the
         * elapsed realtime.
         */
        ManualTimeSuggestion generateManualTimeSuggestion(Instant suggestedTime) {
            UnixEpochTime unixEpochTime =
                    new UnixEpochTime(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new ManualTimeSuggestion(unixEpochTime);
        }

        /**
         * Generates a {@link TelephonyTimeSuggestion} using the current elapsed realtime clock for
         * the elapsed realtime.
         */
        TelephonyTimeSuggestion generateTelephonyTimeSuggestion(int slotIndex, long timeMillis) {
            UnixEpochTime time = new UnixEpochTime(peekElapsedRealtimeMillis(), timeMillis);
            return createTelephonyTimeSuggestion(slotIndex, time);
        }

        /**
         * Generates a {@link TelephonyTimeSuggestion} using the current elapsed realtime clock for
         * the elapsed realtime.
         */
        TelephonyTimeSuggestion generateTelephonyTimeSuggestion(
                int slotIndex, Instant suggestedTime) {
            if (suggestedTime == null) {
                return createTelephonyTimeSuggestion(slotIndex, null);
            }
            return generateTelephonyTimeSuggestion(slotIndex, suggestedTime.toEpochMilli());
        }

        /**
         * Generates a NetworkTimeSuggestion using the current elapsed realtime clock for the
         * elapsed realtime.
         */
        NetworkTimeSuggestion generateNetworkTimeSuggestion(Instant suggestedTime) {
            UnixEpochTime unixEpochTime =
                    new UnixEpochTime(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new NetworkTimeSuggestion(unixEpochTime, 123);
        }

        /**
         * Generates a GnssTimeSuggestion using the current elapsed realtime clock for the
         * elapsed realtime.
         */
        GnssTimeSuggestion generateGnssTimeSuggestion(Instant suggestedTime) {
            UnixEpochTime unixEpochTime =
                    new UnixEpochTime(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new GnssTimeSuggestion(unixEpochTime);
        }

        /**
         * Generates a ExternalTimeSuggestion using the current elapsed realtime clock for the
         * elapsed realtime.
         */
        ExternalTimeSuggestion generateExternalTimeSuggestion(Instant suggestedTime) {
            return new ExternalTimeSuggestion(mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
        }

        /**
         * Calculates what the supplied time would be when adjusted for the movement of the fake
         * elapsed realtime clock.
         */
        long calculateTimeInMillisForNow(UnixEpochTime unixEpochTime) {
            return unixEpochTime.at(peekElapsedRealtimeMillis()).getUnixEpochTimeMillis();
        }
    }

    private static TelephonyTimeSuggestion createTelephonyTimeSuggestion(int slotIndex,
            UnixEpochTime unixEpochTime) {
        return new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUnixEpochTime(unixEpochTime)
                .build();
    }

    private static Instant createUnixEpochTime(int year, int monthInYear, int day, int hourOfDay,
            int minute, int second) {
        return LocalDateTime.of(year, monthInYear, day, hourOfDay, minute, second)
                .toInstant(ZoneOffset.UTC);
    }
}
