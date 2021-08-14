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

import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_EXTERNAL;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_GNSS;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;
import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_TELEPHONY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.time.ExternalTimeSuggestion;
import android.app.timedetector.GnssTimeSuggestion;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.os.TimestampedValue;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.timedetector.TimeDetectorStrategy.Origin;
import com.android.server.timezonedetector.ConfigurationChangeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorStrategyImplTest {

    private static final Instant TIME_LOWER_BOUND = createUtcTime(2009, 1, 1, 12, 0, 0);

    private static final TimestampedValue<Instant> ARBITRARY_CLOCK_INITIALIZATION_INFO =
            new TimestampedValue<>(
                    123456789L /* realtimeClockMillis */,
                    createUtcTime(2010, 5, 23, 12, 0, 0));

    // This is the traditional ordering for time detection on Android.
    private static final @Origin int [] PROVIDERS_PRIORITY = { ORIGIN_TELEPHONY, ORIGIN_NETWORK };

    /**
     * An arbitrary time, very different from the {@link #ARBITRARY_CLOCK_INITIALIZATION_INFO}
     * time. Can be used as the basis for time suggestions.
     */
    private static final Instant ARBITRARY_TEST_TIME = createUtcTime(2018, 1, 1, 12, 0, 0);

    private static final int ARBITRARY_SLOT_INDEX = 123456;

    private Script mScript;

    @Before
    public void setUp() {
        mScript = new Script();
    }

    @Test
    public void testSuggestTelephonyTime_autoTimeEnabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;

        TelephonyTimeSuggestion timeSuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTime);
        mScript.simulateTimePassing()
                .simulateTelephonyTimeSuggestion(timeSuggestion);

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(timeSuggestion.getUtcTime());
        mScript.verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion);
    }

    @Test
    public void testSuggestTelephonyTime_emptySuggestionIgnored() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        TelephonyTimeSuggestion timeSuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, null);
        mScript.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, null);
    }

    @Test
    public void testSuggestTelephonyTime_systemClockThreshold() {
        final int systemClockUpdateThresholdMillis = 1000;
        final int clockIncrementMillis = 100;
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeThresholds(systemClockUpdateThresholdMillis)
                .pokeAutoTimeDetectionEnabled(true);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        // Send the first time signal. It should be used.
        {
            TelephonyTimeSuggestion timeSuggestion1 =
                    mScript.generateTelephonyTimeSuggestion(slotIndex, ARBITRARY_TEST_TIME);

            // Increment the device clocks to simulate the passage of time.
            mScript.simulateTimePassing(clockIncrementMillis);

            long expectedSystemClockMillis1 =
                    mScript.calculateTimeInMillisForNow(timeSuggestion1.getUtcTime());

            mScript.simulateTelephonyTimeSuggestion(timeSuggestion1)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);
        }

        // Now send another time signal, but one that is too similar to the last one and should be
        // stored, but not used to set the system clock.
        {
            int underThresholdMillis = systemClockUpdateThresholdMillis - 1;
            TelephonyTimeSuggestion timeSuggestion2 = mScript.generateTelephonyTimeSuggestion(
                    slotIndex, mScript.peekSystemClockMillis() + underThresholdMillis);
            mScript.simulateTimePassing(clockIncrementMillis)
                    .simulateTelephonyTimeSuggestion(timeSuggestion2)
                    .verifySystemClockWasNotSetAndResetCallTracking()
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);
        }

        // Now send another time signal, but one that is on the threshold and so should be used.
        {
            TelephonyTimeSuggestion timeSuggestion3 = mScript.generateTelephonyTimeSuggestion(
                    slotIndex,
                    mScript.peekSystemClockMillis() + systemClockUpdateThresholdMillis);
            mScript.simulateTimePassing(clockIncrementMillis);

            long expectedSystemClockMillis3 =
                    mScript.calculateTimeInMillisForNow(timeSuggestion3.getUtcTime());

            mScript.simulateTelephonyTimeSuggestion(timeSuggestion3)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis3)
                    .assertLatestTelephonySuggestion(slotIndex, timeSuggestion3);
        }
    }

    @Test
    public void testSuggestTelephonyTime_multipleSlotIndexsAndBucketing() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

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
                    mScript.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            mScript.simulateTimePassing();

            long expectedSystemClockMillis =
                    mScript.calculateTimeInMillisForNow(slotIndex2TimeSuggestion.getUtcTime());

            mScript.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex1, null)
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }

        mScript.simulateTimePassing();

        // Now make a different suggestion with slotIndex1.
        {
            TelephonyTimeSuggestion slotIndex1TimeSuggestion =
                    mScript.generateTelephonyTimeSuggestion(slotIndex1, slotIndex1Time);
            mScript.simulateTimePassing();

            long expectedSystemClockMillis =
                    mScript.calculateTimeInMillisForNow(slotIndex1TimeSuggestion.getUtcTime());

            mScript.simulateTelephonyTimeSuggestion(slotIndex1TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex1, slotIndex1TimeSuggestion);

        }

        mScript.simulateTimePassing();

        // Make another suggestion with slotIndex2. It should be stored but not used because the
        // slotIndex1 suggestion will still "win".
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    mScript.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            mScript.simulateTimePassing();

            mScript.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasNotSetAndResetCallTracking()
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }

        // Let enough time pass that slotIndex1's suggestion should now be too old.
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.TELEPHONY_BUCKET_SIZE_MILLIS);

        // Make another suggestion with slotIndex2. It should be used because the slotIndex1
        // is in an older "bucket".
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    mScript.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2Time);
            mScript.simulateTimePassing();

            long expectedSystemClockMillis =
                    mScript.calculateTimeInMillisForNow(slotIndex2TimeSuggestion.getUtcTime());

            mScript.simulateTelephonyTimeSuggestion(slotIndex2TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis)
                    .assertLatestTelephonySuggestion(slotIndex2, slotIndex2TimeSuggestion);
        }
    }

    @Test
    public void testSuggestTelephonyTime_autoTimeDisabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(false);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        TelephonyTimeSuggestion timeSuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, ARBITRARY_TEST_TIME);
        mScript.simulateTimePassing()
                .simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion);
    }

    @Test
    public void testSuggestTelephonyTime_invalidNitzReferenceTimesIgnored() {
        final int systemClockUpdateThreshold = 2000;
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeThresholds(systemClockUpdateThreshold)
                .pokeAutoTimeDetectionEnabled(true);

        Instant testTime = ARBITRARY_TEST_TIME;
        int slotIndex = ARBITRARY_SLOT_INDEX;

        TelephonyTimeSuggestion timeSuggestion1 =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTime);
        TimestampedValue<Long> utcTime1 = timeSuggestion1.getUtcTime();

        // Initialize the strategy / device with a time set from a telephony suggestion.
        mScript.simulateTimePassing();
        long expectedSystemClockMillis1 = mScript.calculateTimeInMillisForNow(utcTime1);
        mScript.simulateTelephonyTimeSuggestion(timeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // The UTC time increment should be larger than the system clock update threshold so we
        // know it shouldn't be ignored for other reasons.
        long validUtcTimeMillis = utcTime1.getValue() + (2 * systemClockUpdateThreshold);

        // Now supply a new signal that has an obviously bogus reference time : older than the last
        // one.
        long referenceTimeBeforeLastSignalMillis = utcTime1.getReferenceTimeMillis() - 1;
        TimestampedValue<Long> utcTime2 = new TimestampedValue<>(
                referenceTimeBeforeLastSignalMillis, validUtcTimeMillis);
        TelephonyTimeSuggestion timeSuggestion2 =
                createTelephonyTimeSuggestion(slotIndex, utcTime2);
        mScript.simulateTelephonyTimeSuggestion(timeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Now supply a new signal that has an obviously bogus reference time : substantially in the
        // future.
        long referenceTimeInFutureMillis =
                utcTime1.getReferenceTimeMillis() + Integer.MAX_VALUE + 1;
        TimestampedValue<Long> utcTime3 = new TimestampedValue<>(
                referenceTimeInFutureMillis, validUtcTimeMillis);
        TelephonyTimeSuggestion timeSuggestion3 =
                createTelephonyTimeSuggestion(slotIndex, utcTime3);
        mScript.simulateTelephonyTimeSuggestion(timeSuggestion3)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Just to prove validUtcTimeMillis is valid.
        long validReferenceTimeMillis = utcTime1.getReferenceTimeMillis() + 100;
        TimestampedValue<Long> utcTime4 = new TimestampedValue<>(
                validReferenceTimeMillis, validUtcTimeMillis);
        long expectedSystemClockMillis4 = mScript.calculateTimeInMillisForNow(utcTime4);
        TelephonyTimeSuggestion timeSuggestion4 =
                createTelephonyTimeSuggestion(slotIndex, utcTime4);
        mScript.simulateTelephonyTimeSuggestion(timeSuggestion4)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis4)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion4);
    }

    @Test
    public void telephonyTimeSuggestion_ignoredWhenReferencedTimeIsInThePast() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant suggestedTime = TIME_LOWER_BOUND.minus(Duration.ofDays(1));

        TelephonyTimeSuggestion timeSuggestion =
                mScript.generateTelephonyTimeSuggestion(
                        slotIndex, suggestedTime);

        mScript.simulateTelephonyTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, null);
    }

    @Test
    public void testSuggestTelephonyTime_timeDetectionToggled() {
        final int clockIncrementMillis = 100;
        final int systemClockUpdateThreshold = 2000;
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeThresholds(systemClockUpdateThreshold)
                .pokeAutoTimeDetectionEnabled(false);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion timeSuggestion1 =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTime);
        TimestampedValue<Long> utcTime1 = timeSuggestion1.getUtcTime();

        // Simulate time passing.
        mScript.simulateTimePassing(clockIncrementMillis);

        // Simulate the time signal being received. It should not be used because auto time
        // detection is off but it should be recorded.
        mScript.simulateTelephonyTimeSuggestion(timeSuggestion1)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Simulate more time passing.
        mScript.simulateTimePassing(clockIncrementMillis);

        long expectedSystemClockMillis1 = mScript.calculateTimeInMillisForNow(utcTime1);

        // Turn on auto time detection.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis1)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Turn off auto time detection.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion1);

        // Receive another valid time signal.
        // It should be on the threshold and accounting for the clock increments.
        TelephonyTimeSuggestion timeSuggestion2 = mScript.generateTelephonyTimeSuggestion(
                slotIndex, mScript.peekSystemClockMillis() + systemClockUpdateThreshold);

        // Simulate more time passing.
        mScript.simulateTimePassing(clockIncrementMillis);

        long expectedSystemClockMillis2 =
                mScript.calculateTimeInMillisForNow(timeSuggestion2.getUtcTime());

        // The new time, though valid, should not be set in the system clock because auto time is
        // disabled.
        mScript.simulateTelephonyTimeSuggestion(timeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);

        // Turn on auto time detection.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis2)
                .assertLatestTelephonySuggestion(slotIndex, timeSuggestion2);
    }

    @Test
    public void testSuggestTelephonyTime_maxSuggestionAge() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion telephonySuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTime);

        mScript.simulateTimePassing();

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(telephonySuggestion.getUtcTime());
        mScript.simulateTelephonyTimeSuggestion(telephonySuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis  /* expectedNetworkBroadcast */)
                .assertLatestTelephonySuggestion(slotIndex, telephonySuggestion);

        // Look inside and check what the strategy considers the current best telephony suggestion.
        assertEquals(telephonySuggestion, mScript.peekBestTelephonySuggestion());

        // Simulate time passing, long enough that telephonySuggestion is now too old.
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS);

        // Look inside and check what the strategy considers the current best telephony suggestion.
        // It should still be the, it's just no longer used.
        assertNull(mScript.peekBestTelephonySuggestion());
        mScript.assertLatestTelephonySuggestion(slotIndex, telephonySuggestion);
    }

    @Test
    public void testSuggestManualTime_autoTimeDisabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(false);

        ManualTimeSuggestion timeSuggestion =
                mScript.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateTimePassing();

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(timeSuggestion.getUtcTime());
        mScript.simulateManualTimeSuggestion(timeSuggestion, true /* expectedResult */)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestManualTime_retainsAutoSignal() {
        // Configure the start state.
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int slotIndex = ARBITRARY_SLOT_INDEX;

        // Simulate a telephony suggestion.
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTime);

        // Simulate the passage of time.
        mScript.simulateTimePassing();

        long expectedAutoClockMillis =
                mScript.calculateTimeInMillisForNow(telephonyTimeSuggestion.getUtcTime());
        mScript.simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedAutoClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        mScript.simulateTimePassing();

        // Switch to manual.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        mScript.simulateTimePassing();

        // Simulate a manual suggestion 1 day different from the auto suggestion.
        Instant manualTime = testTime.plus(Duration.ofDays(1));
        ManualTimeSuggestion manualTimeSuggestion =
                mScript.generateManualTimeSuggestion(manualTime);
        mScript.simulateTimePassing();

        long expectedManualClockMillis =
                mScript.calculateTimeInMillisForNow(manualTimeSuggestion.getUtcTime());
        mScript.simulateManualTimeSuggestion(manualTimeSuggestion, true /* expectedResult */)
                .verifySystemClockWasSetAndResetCallTracking(expectedManualClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Simulate the passage of time.
        mScript.simulateTimePassing();

        // Switch back to auto.
        mScript.simulateAutoTimeDetectionToggle();

        expectedAutoClockMillis =
                mScript.calculateTimeInMillisForNow(telephonyTimeSuggestion.getUtcTime());
        mScript.verifySystemClockWasSetAndResetCallTracking(expectedAutoClockMillis)
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);

        // Switch back to manual - nothing should happen to the clock.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestTelephonySuggestion(slotIndex, telephonyTimeSuggestion);
    }

    @Test
    public void manualTimeSuggestion_isIgnored_whenAutoTimeEnabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        ManualTimeSuggestion timeSuggestion =
                mScript.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateTimePassing()
                .simulateManualTimeSuggestion(timeSuggestion, false /* expectedResult */)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void manualTimeSuggestion_ignoresTimeLowerBound() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(false);
        Instant suggestedTime = TIME_LOWER_BOUND.minus(Duration.ofDays(1));

        ManualTimeSuggestion timeSuggestion =
                mScript.generateManualTimeSuggestion(suggestedTime);

        mScript.simulateManualTimeSuggestion(timeSuggestion, true /* expectedResult */)
                .verifySystemClockWasSetAndResetCallTracking(suggestedTime.toEpochMilli());
    }

    @Test
    public void testSuggestNetworkTime_autoTimeEnabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoOriginPriorities(ORIGIN_NETWORK)
                .pokeAutoTimeDetectionEnabled(true);

        NetworkTimeSuggestion timeSuggestion =
                mScript.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateTimePassing();

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(timeSuggestion.getUtcTime());
        mScript.simulateNetworkTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestNetworkTime_autoTimeDisabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoOriginPriorities(ORIGIN_NETWORK)
                .pokeAutoTimeDetectionEnabled(false);

        NetworkTimeSuggestion timeSuggestion =
                mScript.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateTimePassing()
                .simulateNetworkTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void networkTimeSuggestion_ignoredWhenReferencedTimeIsInThePast() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoOriginPriorities(ORIGIN_NETWORK)
                .pokeAutoTimeDetectionEnabled(true);

        Instant suggestedTime = TIME_LOWER_BOUND.minus(Duration.ofDays(1));
        NetworkTimeSuggestion timeSuggestion = mScript
                .generateNetworkTimeSuggestion(suggestedTime);

        mScript.simulateNetworkTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestNetworkSuggestion(null);
    }

    @Test
    public void testSuggestGnssTime_autoTimeEnabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoOriginPriorities(ORIGIN_GNSS)
                .pokeAutoTimeDetectionEnabled(true);

        GnssTimeSuggestion timeSuggestion =
                mScript.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateTimePassing();

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(timeSuggestion.getUtcTime());
        mScript.simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestGnssTime_autoTimeDisabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoOriginPriorities(ORIGIN_GNSS)
                .pokeAutoTimeDetectionEnabled(false);

        GnssTimeSuggestion timeSuggestion =
                mScript.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateTimePassing()
                .simulateGnssTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestExternalTime_autoTimeEnabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoOriginPriorities(ORIGIN_EXTERNAL)
                .pokeAutoTimeDetectionEnabled(true);

        ExternalTimeSuggestion timeSuggestion =
                mScript.generateExternalTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateTimePassing();

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(timeSuggestion.getUtcTime());
        mScript.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestExternalTime_autoTimeDisabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoOriginPriorities(ORIGIN_EXTERNAL)
                .pokeAutoTimeDetectionEnabled(false);

        ExternalTimeSuggestion timeSuggestion =
                mScript.generateExternalTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateTimePassing()
                .simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void externalTimeSuggestion_ignoredWhenReferencedTimeIsInThePast() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoOriginPriorities(ORIGIN_EXTERNAL)
                .pokeAutoTimeDetectionEnabled(true);

        Instant suggestedTime = TIME_LOWER_BOUND.minus(Duration.ofDays(1));
        ExternalTimeSuggestion timeSuggestion = mScript
                .generateExternalTimeSuggestion(suggestedTime);

        mScript.simulateExternalTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestExternalSuggestion(null);
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_telephonyNetworkOrigins() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK);

        // Three obviously different times that could not be mistaken for each other.
        Instant networkTime1 = ARBITRARY_TEST_TIME;
        Instant networkTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A network suggestion is made. It should be used because there is no telephony suggestion.
        NetworkTimeSuggestion networkTimeSuggestion1 =
                mScript.generateNetworkTimeSuggestion(networkTime1);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(networkTimeSuggestion1.getUtcTime()));

        // Check internal state.
        mScript.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, null)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, mScript.peekLatestValidNetworkSuggestion());
        assertNull("No telephony suggestions were made:", mScript.peekBestTelephonySuggestion());

        // Simulate a little time passing.
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a telephony suggestion is made. Telephony suggestions are prioritized over network
        // suggestions so it should "win".
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                mScript.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(telephonyTimeSuggestion.getUtcTime()));

        // Check internal state.
        mScript.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, mScript.peekLatestValidNetworkSuggestion());
        assertEquals(telephonyTimeSuggestion, mScript.peekBestTelephonySuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another network suggestion is made. Telephony suggestions are prioritized over
        // network suggestions so the latest telephony suggestion should still "win".
        NetworkTimeSuggestion networkTimeSuggestion2 =
                mScript.generateNetworkTimeSuggestion(networkTime2);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        mScript.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, mScript.peekLatestValidNetworkSuggestion());
        assertEquals(telephonyTimeSuggestion, mScript.peekBestTelephonySuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that telephonyTimeSuggestion is now too old to
        // be used but networkTimeSuggestion2 is not.
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        mScript.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        mScript.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, mScript.peekLatestValidNetworkSuggestion());
        assertNull(
                "Telephony suggestion should be expired:",
                mScript.peekBestTelephonySuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        mScript.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest network time now wins.
        mScript.verifySystemClockWasSetAndResetCallTracking(
                mScript.calculateTimeInMillisForNow(networkTimeSuggestion2.getUtcTime()));

        // Check internal state.
        mScript.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, mScript.peekLatestValidNetworkSuggestion());
        assertNull(
                "Telephony suggestion should still be expired:",
                mScript.peekBestTelephonySuggestion());
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_networkGnssOrigins() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_NETWORK, ORIGIN_GNSS);

        // Three obviously different times that could not be mistaken for each other.
        Instant gnssTime1 = ARBITRARY_TEST_TIME;
        Instant gnssTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant networkTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A gnss suggestion is made. It should be used because there is no network suggestion.
        GnssTimeSuggestion gnssTimeSuggestion1 =
                mScript.generateGnssTimeSuggestion(gnssTime1);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(gnssTimeSuggestion1.getUtcTime()));

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(null)
                .assertLatestGnssSuggestion(gnssTimeSuggestion1);
        assertEquals(gnssTimeSuggestion1, mScript.peekLatestValidGnssSuggestion());
        assertNull("No network suggestions were made:", mScript.peekLatestValidNetworkSuggestion());

        // Simulate a little time passing.
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a network suggestion is made. Network suggestions are prioritized over gnss
        // suggestions so it should "win".
        NetworkTimeSuggestion networkTimeSuggestion =
                mScript.generateNetworkTimeSuggestion(networkTime);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(networkTimeSuggestion.getUtcTime()));

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion1);
        assertEquals(gnssTimeSuggestion1, mScript.peekLatestValidGnssSuggestion());
        assertEquals(networkTimeSuggestion, mScript.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another gnss suggestion is made. Network suggestions are prioritized over
        // gnss suggestions so the latest network suggestion should still "win".
        GnssTimeSuggestion gnssTimeSuggestion2 =
                mScript.generateGnssTimeSuggestion(gnssTime2);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateGnssTimeSuggestion(gnssTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, mScript.peekLatestValidGnssSuggestion());
        assertEquals(networkTimeSuggestion, mScript.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that telephonyTimeSuggestion is now too old to
        // be used but networkTimeSuggestion2 is not.
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        mScript.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, mScript.peekLatestValidGnssSuggestion());
        assertNull(
                "Network suggestion should be expired:",
                mScript.peekLatestValidNetworkSuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        mScript.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest gnss time now wins.
        mScript.verifySystemClockWasSetAndResetCallTracking(
                mScript.calculateTimeInMillisForNow(gnssTimeSuggestion2.getUtcTime()));

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestGnssSuggestion(gnssTimeSuggestion2);
        assertEquals(gnssTimeSuggestion2, mScript.peekLatestValidGnssSuggestion());
        assertNull(
                "Network suggestion should still be expired:",
                mScript.peekLatestValidNetworkSuggestion());
    }

    @Test
    public void highPrioritySuggestionsBeatLowerPrioritySuggestions_networkExternalOrigins() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_NETWORK, ORIGIN_EXTERNAL);

        // Three obviously different times that could not be mistaken for each other.
        Instant externalTime1 = ARBITRARY_TEST_TIME;
        Instant externalTime2 = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant networkTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A external suggestion is made. It should be used because there is no network suggestion.
        ExternalTimeSuggestion externalTimeSuggestion1 =
                mScript.generateExternalTimeSuggestion(externalTime1);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateExternalTimeSuggestion(externalTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(externalTimeSuggestion1.getUtcTime()));

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(null)
                .assertLatestExternalSuggestion(externalTimeSuggestion1);
        assertEquals(externalTimeSuggestion1, mScript.peekLatestValidExternalSuggestion());
        assertNull("No network suggestions were made:", mScript.peekLatestValidNetworkSuggestion());

        // Simulate a little time passing.
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a network suggestion is made. Network suggestions are prioritized over external
        // suggestions so it should "win".
        NetworkTimeSuggestion networkTimeSuggestion =
                mScript.generateNetworkTimeSuggestion(networkTime);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(networkTimeSuggestion.getUtcTime()));

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion1);
        assertEquals(externalTimeSuggestion1, mScript.peekLatestValidExternalSuggestion());
        assertEquals(networkTimeSuggestion, mScript.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another external suggestion is made. Network suggestions are prioritized over
        // external suggestions so the latest network suggestion should still "win".
        ExternalTimeSuggestion externalTimeSuggestion2 =
                mScript.generateExternalTimeSuggestion(externalTime2);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateExternalTimeSuggestion(externalTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, mScript.peekLatestValidExternalSuggestion());
        assertEquals(networkTimeSuggestion, mScript.peekLatestValidNetworkSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that networkTimeSuggestion is now too old to
        // be used but externalTimeSuggestion2 is not.
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        mScript.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, mScript.peekLatestValidExternalSuggestion());
        assertNull(
                "Network suggestion should be expired:",
                mScript.peekLatestValidNetworkSuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        mScript.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest external time now wins.
        mScript.verifySystemClockWasSetAndResetCallTracking(
                mScript.calculateTimeInMillisForNow(externalTimeSuggestion2.getUtcTime()));

        // Check internal state.
        mScript.assertLatestNetworkSuggestion(networkTimeSuggestion)
                .assertLatestExternalSuggestion(externalTimeSuggestion2);
        assertEquals(externalTimeSuggestion2, mScript.peekLatestValidExternalSuggestion());
        assertNull(
                "Network suggestion should still be expired:",
                mScript.peekLatestValidNetworkSuggestion());
    }

    @Test
    public void whenAllTimeSuggestionsAreAvailable_higherPriorityWins_lowerPriorityComesFirst() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK, ORIGIN_EXTERNAL,
                          ORIGIN_GNSS);

        Instant networkTime = ARBITRARY_TEST_TIME;
        Instant externalTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(15));
        Instant gnssTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));

        NetworkTimeSuggestion networkTimeSuggestion =
                mScript.generateNetworkTimeSuggestion(networkTime);
        ExternalTimeSuggestion externalTimeSuggestion =
                mScript.generateExternalTimeSuggestion(externalTime);
        GnssTimeSuggestion gnssTimeSuggestion =
                mScript.generateGnssTimeSuggestion(gnssTime);
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                mScript.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);

        mScript.simulateNetworkTimeSuggestion(networkTimeSuggestion)
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
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK,
                        ORIGIN_EXTERNAL, ORIGIN_GNSS);

        Instant networkTime = ARBITRARY_TEST_TIME;
        Instant telephonyTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(30));
        Instant externalTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(50));
        Instant gnssTime = ARBITRARY_TEST_TIME.plus(Duration.ofDays(60));

        NetworkTimeSuggestion networkTimeSuggestion =
                mScript.generateNetworkTimeSuggestion(networkTime);
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                mScript.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTime);
        GnssTimeSuggestion gnssTimeSuggestion =
                mScript.generateGnssTimeSuggestion(gnssTime);
        ExternalTimeSuggestion externalTimeSuggestion =
                mScript.generateExternalTimeSuggestion(externalTime);

        mScript.simulateTelephonyTimeSuggestion(telephonyTimeSuggestion)
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
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK);

        NetworkTimeSuggestion timeSuggestion =
                mScript.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    @Test
    public void whenHigherPrioritySuggestionsAreNotAvailable_fallbacksToNext() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY, ORIGIN_NETWORK,
                                ORIGIN_EXTERNAL, ORIGIN_GNSS);

        GnssTimeSuggestion timeSuggestion =
                mScript.generateGnssTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateGnssTimeSuggestion(timeSuggestion)
                .assertLatestGnssSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    @Test
    public void suggestionsFromTelephonyOriginNotInPriorityList_areIgnored() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_NETWORK);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        Instant testTime = ARBITRARY_TEST_TIME;
        TelephonyTimeSuggestion timeSuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTime);

        mScript.simulateTelephonyTimeSuggestion(timeSuggestion)
                .assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromNetworkOriginNotInPriorityList_areIgnored() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY);

        NetworkTimeSuggestion timeSuggestion = mScript.generateNetworkTimeSuggestion(
                ARBITRARY_TEST_TIME);

        mScript.simulateNetworkTimeSuggestion(timeSuggestion)
                .assertLatestNetworkSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromGnssOriginNotInPriorityList_areIgnored() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY);

        GnssTimeSuggestion timeSuggestion = mScript.generateGnssTimeSuggestion(
                ARBITRARY_TEST_TIME);

        mScript.simulateGnssTimeSuggestion(timeSuggestion)
                .assertLatestGnssSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void suggestionsFromExternalOriginNotInPriorityList_areIgnored() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY);

        ExternalTimeSuggestion timeSuggestion = mScript.generateExternalTimeSuggestion(
                ARBITRARY_TEST_TIME);

        mScript.simulateExternalTimeSuggestion(timeSuggestion)
                .assertLatestExternalSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void autoOriginPrioritiesList_doesNotAffectManualSuggestion() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(false)
                .pokeAutoOriginPriorities(ORIGIN_TELEPHONY);

        ManualTimeSuggestion timeSuggestion =
                mScript.generateManualTimeSuggestion(ARBITRARY_TEST_TIME);

        mScript.simulateManualTimeSuggestion(timeSuggestion, true /* expectedResult */)
                .verifySystemClockWasSetAndResetCallTracking(ARBITRARY_TEST_TIME.toEpochMilli());
    }

    /**
     * A fake implementation of {@link TimeDetectorStrategyImpl.Environment}. Besides tracking
     * changes and behaving like the real thing should, it also asserts preconditions.
     */
    private static class FakeEnvironment implements TimeDetectorStrategyImpl.Environment {
        private boolean mAutoTimeDetectionEnabled;
        private boolean mWakeLockAcquired;
        private long mElapsedRealtimeMillis;
        private long mSystemClockMillis;
        private int mSystemClockUpdateThresholdMillis = 2000;
        private int[] mAutoOriginPriorities = PROVIDERS_PRIORITY;
        private ConfigurationChangeListener mConfigChangeListener;

        // Tracking operations.
        private boolean mSystemClockWasSet;

        @Override
        public void setConfigChangeListener(ConfigurationChangeListener listener) {
            mConfigChangeListener = Objects.requireNonNull(listener);
        }

        @Override
        public int systemClockUpdateThresholdMillis() {
            return mSystemClockUpdateThresholdMillis;
        }

        @Override
        public boolean isAutoTimeDetectionEnabled() {
            return mAutoTimeDetectionEnabled;
        }

        @Override
        public Instant autoTimeLowerBound() {
            return TIME_LOWER_BOUND;
        }

        @Override
        public int[] autoOriginPriorities() {
            return mAutoOriginPriorities;
        }

        @Override
        public ConfigurationInternal configurationInternal(int userId) {
            throw new UnsupportedOperationException();
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
        public void setSystemClock(long newTimeMillis) {
            assertWakeLockAcquired();
            mSystemClockWasSet = true;
            mSystemClockMillis = newTimeMillis;
        }

        @Override
        public void releaseWakeLock() {
            assertWakeLockAcquired();
            mWakeLockAcquired = false;
        }

        // Methods below are for managing the fake's behavior.

        void pokeSystemClockUpdateThreshold(int thresholdMillis) {
            mSystemClockUpdateThresholdMillis = thresholdMillis;
        }

        void pokeElapsedRealtimeMillis(long elapsedRealtimeMillis) {
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
        }

        void pokeSystemClockMillis(long systemClockMillis) {
            mSystemClockMillis = systemClockMillis;
        }

        void pokeAutoTimeDetectionEnabled(boolean enabled) {
            mAutoTimeDetectionEnabled = enabled;
        }

        void pokeAutoOriginPriorities(@Origin int[] autoOriginPriorities) {
            mAutoOriginPriorities = autoOriginPriorities;
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

        void simulateAutoTimeZoneDetectionToggle() {
            mAutoTimeDetectionEnabled = !mAutoTimeDetectionEnabled;
            mConfigChangeListener.onChange();
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

        private final FakeEnvironment mFakeEnvironment;
        private final TimeDetectorStrategyImpl mTimeDetectorStrategy;

        Script() {
            mFakeEnvironment = new FakeEnvironment();
            mTimeDetectorStrategy = new TimeDetectorStrategyImpl(mFakeEnvironment);
        }

        Script pokeAutoTimeDetectionEnabled(boolean enabled) {
            mFakeEnvironment.pokeAutoTimeDetectionEnabled(enabled);
            return this;
        }

        Script pokeFakeClocks(TimestampedValue<Instant> timeInfo) {
            mFakeEnvironment.pokeElapsedRealtimeMillis(timeInfo.getReferenceTimeMillis());
            mFakeEnvironment.pokeSystemClockMillis(timeInfo.getValue().toEpochMilli());
            return this;
        }

        Script pokeThresholds(int systemClockUpdateThreshold) {
            mFakeEnvironment.pokeSystemClockUpdateThreshold(systemClockUpdateThreshold);
            return this;
        }

        Script pokeAutoOriginPriorities(@Origin int... autoOriginPriorities) {
            mFakeEnvironment.pokeAutoOriginPriorities(autoOriginPriorities);
            return this;
        }

        long peekElapsedRealtimeMillis() {
            return mFakeEnvironment.peekElapsedRealtimeMillis();
        }

        long peekSystemClockMillis() {
            return mFakeEnvironment.peekSystemClockMillis();
        }

        Script simulateTelephonyTimeSuggestion(TelephonyTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestTelephonyTime(timeSuggestion);
            return this;
        }

        Script simulateManualTimeSuggestion(
                ManualTimeSuggestion timeSuggestion, boolean expectedResult) {
            String errorMessage = expectedResult
                    ? "Manual time suggestion was ignored, but expected to be accepted."
                    : "Manual time suggestion was accepted, but expected to be ignored.";
            assertEquals(
                    errorMessage,
                    expectedResult,
                    mTimeDetectorStrategy.suggestManualTime(timeSuggestion));
            return this;
        }

        Script simulateNetworkTimeSuggestion(NetworkTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestNetworkTime(timeSuggestion);
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
            mFakeEnvironment.simulateAutoTimeZoneDetectionToggle();
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
         * reference time.
         */
        ManualTimeSuggestion generateManualTimeSuggestion(Instant suggestedTime) {
            TimestampedValue<Long> utcTime =
                    new TimestampedValue<>(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new ManualTimeSuggestion(utcTime);
        }

        /**
         * Generates a {@link TelephonyTimeSuggestion} using the current elapsed realtime clock for
         * the reference time.
         */
        TelephonyTimeSuggestion generateTelephonyTimeSuggestion(int slotIndex, long timeMillis) {
            TimestampedValue<Long> time =
                    new TimestampedValue<>(peekElapsedRealtimeMillis(), timeMillis);
            return createTelephonyTimeSuggestion(slotIndex, time);
        }

        /**
         * Generates a {@link TelephonyTimeSuggestion} using the current elapsed realtime clock for
         * the reference time.
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
         * reference time.
         */
        NetworkTimeSuggestion generateNetworkTimeSuggestion(Instant suggestedTime) {
            TimestampedValue<Long> utcTime =
                    new TimestampedValue<>(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new NetworkTimeSuggestion(utcTime);
        }

        /**
         * Generates a GnssTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        GnssTimeSuggestion generateGnssTimeSuggestion(Instant suggestedTime) {
            TimestampedValue<Long> utcTime =
                    new TimestampedValue<>(
                            mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
            return new GnssTimeSuggestion(utcTime);
        }

        /**
         * Generates a ExternalTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        ExternalTimeSuggestion generateExternalTimeSuggestion(Instant suggestedTime) {
            return new ExternalTimeSuggestion(mFakeEnvironment.peekElapsedRealtimeMillis(),
                            suggestedTime.toEpochMilli());
        }

        /**
         * Calculates what the supplied time would be when adjusted for the movement of the fake
         * elapsed realtime clock.
         */
        long calculateTimeInMillisForNow(TimestampedValue<Long> utcTime) {
            return TimeDetectorStrategy.getTimeAt(utcTime, peekElapsedRealtimeMillis());
        }
    }

    private static TelephonyTimeSuggestion createTelephonyTimeSuggestion(int slotIndex,
            TimestampedValue<Long> utcTime) {
        return new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUtcTime(utcTime)
                .build();
    }

    private static Instant createUtcTime(int year, int monthInYear, int day, int hourOfDay,
            int minute, int second) {
        return LocalDateTime.of(year, monthInYear, day, hourOfDay, minute, second)
                .toInstant(ZoneOffset.UTC);
    }
}
