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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.icu.util.Calendar;
import android.icu.util.GregorianCalendar;
import android.icu.util.TimeZone;
import android.os.TimestampedValue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorStrategyImplTest {

    private static final TimestampedValue<Long> ARBITRARY_CLOCK_INITIALIZATION_INFO =
            new TimestampedValue<>(
                    123456789L /* realtimeClockMillis */,
                    createUtcTime(2008, 5, 23, 12, 0, 0));

    /**
     * An arbitrary time, very different from the {@link #ARBITRARY_CLOCK_INITIALIZATION_INFO}
     * time. Can be used as the basis for time suggestions.
     */
    private static final long ARBITRARY_TEST_TIME_MILLIS = createUtcTime(2018, 1, 1, 12, 0, 0);

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
        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;

        TelephonyTimeSuggestion timeSuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTimeMillis);
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
                    mScript.generateTelephonyTimeSuggestion(slotIndex, ARBITRARY_TEST_TIME_MILLIS);

            // Increment the the device clocks to simulate the passage of time.
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
        long slotIndex1TimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        long slotIndex2TimeMillis = ARBITRARY_TEST_TIME_MILLIS + Duration.ofDays(1).toMillis();

        // Make a suggestion with slotIndex2.
        {
            TelephonyTimeSuggestion slotIndex2TimeSuggestion =
                    mScript.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2TimeMillis);
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
                    mScript.generateTelephonyTimeSuggestion(slotIndex1, slotIndex1TimeMillis);
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
                    mScript.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2TimeMillis);
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
                    mScript.generateTelephonyTimeSuggestion(slotIndex2, slotIndex2TimeMillis);
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
                mScript.generateTelephonyTimeSuggestion(slotIndex, ARBITRARY_TEST_TIME_MILLIS);
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

        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        int slotIndex = ARBITRARY_SLOT_INDEX;

        TelephonyTimeSuggestion timeSuggestion1 =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTimeMillis);
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
    public void testSuggestTelephonyTime_timeDetectionToggled() {
        final int clockIncrementMillis = 100;
        final int systemClockUpdateThreshold = 2000;
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeThresholds(systemClockUpdateThreshold)
                .pokeAutoTimeDetectionEnabled(false);

        int slotIndex = ARBITRARY_SLOT_INDEX;
        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        TelephonyTimeSuggestion timeSuggestion1 =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTimeMillis);
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
        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        TelephonyTimeSuggestion telephonySuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTimeMillis);

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
                mScript.generateManualTimeSuggestion(ARBITRARY_TEST_TIME_MILLIS);

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
        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                mScript.generateTelephonyTimeSuggestion(slotIndex, testTimeMillis);

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
        long manualTimeMillis = testTimeMillis + Duration.ofDays(1).toMillis();
        ManualTimeSuggestion manualTimeSuggestion =
                mScript.generateManualTimeSuggestion(manualTimeMillis);
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

    /**
     * Manual suggestions should be ignored if auto time is enabled.
     */
    @Test
    public void testSuggestManualTime_autoTimeEnabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        ManualTimeSuggestion timeSuggestion =
                mScript.generateManualTimeSuggestion(ARBITRARY_TEST_TIME_MILLIS);

        mScript.simulateTimePassing()
                .simulateManualTimeSuggestion(timeSuggestion, false /* expectedResult */)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestNetworkTime_autoTimeEnabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        NetworkTimeSuggestion timeSuggestion =
                mScript.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME_MILLIS);

        mScript.simulateTimePassing();

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(timeSuggestion.getUtcTime());
        mScript.simulateNetworkTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(expectedSystemClockMillis);
    }

    @Test
    public void testSuggestNetworkTime_autoTimeDisabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(false);

        NetworkTimeSuggestion timeSuggestion =
                mScript.generateNetworkTimeSuggestion(ARBITRARY_TEST_TIME_MILLIS);

        mScript.simulateTimePassing()
                .simulateNetworkTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking();
    }

    @Test
    public void testSuggestNetworkTime_telephonySuggestionsBeatNetworkSuggestions() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        // Three obviously different times that could not be mistaken for each other.
        long networkTimeMillis1 = ARBITRARY_TEST_TIME_MILLIS;
        long networkTimeMillis2 = ARBITRARY_TEST_TIME_MILLIS + Duration.ofDays(30).toMillis();
        long telephonyTimeMillis = ARBITRARY_TEST_TIME_MILLIS + Duration.ofDays(60).toMillis();
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A network suggestion is made. It should be used because there is no telephony suggestion.
        NetworkTimeSuggestion networkTimeSuggestion1 =
                mScript.generateNetworkTimeSuggestion(networkTimeMillis1);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(networkTimeSuggestion1.getUtcTime()));

        // Check internal state.
        mScript.assertLatestTelephonySuggestion(ARBITRARY_SLOT_INDEX, null)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, mScript.peekLatestValidNetworkSuggestion());
        assertNull(mScript.peekBestTelephonySuggestion());

        // Simulate a little time passing.
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a telephony suggestion is made. Telephony suggestions are prioritized over network
        // suggestions so it should "win".
        TelephonyTimeSuggestion telephonyTimeSuggestion =
                mScript.generateTelephonyTimeSuggestion(ARBITRARY_SLOT_INDEX, telephonyTimeMillis);
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
                mScript.generateNetworkTimeSuggestion(networkTimeMillis2);
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
        assertNull(mScript.peekBestTelephonySuggestion());

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
        assertNull(mScript.peekBestTelephonySuggestion());
    }

    /**
     * A fake implementation of TimeDetectorStrategy.Callback. Besides tracking changes and behaving
     * like the real thing should, it also asserts preconditions.
     */
    private static class FakeCallback implements TimeDetectorStrategy.Callback {
        private boolean mAutoTimeDetectionEnabled;
        private boolean mWakeLockAcquired;
        private long mElapsedRealtimeMillis;
        private long mSystemClockMillis;
        private int mSystemClockUpdateThresholdMillis = 2000;

        // Tracking operations.
        private boolean mSystemClockWasSet;

        @Override
        public int systemClockUpdateThresholdMillis() {
            return mSystemClockUpdateThresholdMillis;
        }

        @Override
        public boolean isAutoTimeDetectionEnabled() {
            return mAutoTimeDetectionEnabled;
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
        }

        void verifySystemClockNotSet() {
            assertFalse(mSystemClockWasSet);
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

        private final FakeCallback mFakeCallback;
        private final TimeDetectorStrategyImpl mTimeDetectorStrategy;

        Script() {
            mFakeCallback = new FakeCallback();
            mTimeDetectorStrategy = new TimeDetectorStrategyImpl();
            mTimeDetectorStrategy.initialize(mFakeCallback);

        }

        Script pokeAutoTimeDetectionEnabled(boolean enabled) {
            mFakeCallback.pokeAutoTimeDetectionEnabled(enabled);
            return this;
        }

        Script pokeFakeClocks(TimestampedValue<Long> timeInfo) {
            mFakeCallback.pokeElapsedRealtimeMillis(timeInfo.getReferenceTimeMillis());
            mFakeCallback.pokeSystemClockMillis(timeInfo.getValue());
            return this;
        }

        Script pokeThresholds(int systemClockUpdateThreshold) {
            mFakeCallback.pokeSystemClockUpdateThreshold(systemClockUpdateThreshold);
            return this;
        }

        long peekElapsedRealtimeMillis() {
            return mFakeCallback.peekElapsedRealtimeMillis();
        }

        long peekSystemClockMillis() {
            return mFakeCallback.peekSystemClockMillis();
        }

        Script simulateTelephonyTimeSuggestion(TelephonyTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestTelephonyTime(timeSuggestion);
            return this;
        }

        Script simulateManualTimeSuggestion(
                ManualTimeSuggestion timeSuggestion, boolean expectedResult) {
            assertEquals(expectedResult, mTimeDetectorStrategy.suggestManualTime(timeSuggestion));
            return this;
        }

        Script simulateNetworkTimeSuggestion(NetworkTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestNetworkTime(timeSuggestion);
            return this;
        }

        Script simulateAutoTimeDetectionToggle() {
            mFakeCallback.simulateAutoTimeZoneDetectionToggle();
            mTimeDetectorStrategy.handleAutoTimeDetectionChanged();
            return this;
        }

        Script simulateTimePassing(long clockIncrementMillis) {
            mFakeCallback.simulateTimePassing(clockIncrementMillis);
            return this;
        }

        /**
         * Simulates time passing by an arbitrary (but relatively small) amount.
         */
        Script simulateTimePassing() {
            return simulateTimePassing(999);
        }

        Script verifySystemClockWasNotSetAndResetCallTracking() {
            mFakeCallback.verifySystemClockNotSet();
            mFakeCallback.resetCallTracking();
            return this;
        }

        Script verifySystemClockWasSetAndResetCallTracking(long expectedSystemClockMillis) {
            mFakeCallback.verifySystemClockWasSet(expectedSystemClockMillis);
            mFakeCallback.resetCallTracking();
            return this;
        }

        /**
         * White box test info: Asserts the latest suggestion for the slotIndex is as expected.
         */
        Script assertLatestTelephonySuggestion(int slotIndex, TelephonyTimeSuggestion expected) {
            assertEquals(expected, mTimeDetectorStrategy.getLatestTelephonySuggestion(slotIndex));
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
         * Generates a ManualTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        ManualTimeSuggestion generateManualTimeSuggestion(long timeMillis) {
            TimestampedValue<Long> utcTime =
                    new TimestampedValue<>(mFakeCallback.peekElapsedRealtimeMillis(), timeMillis);
            return new ManualTimeSuggestion(utcTime);
        }

        /**
         * Generates a {@link TelephonyTimeSuggestion} using the current elapsed realtime clock for
         * the reference time.
         */
        TelephonyTimeSuggestion generateTelephonyTimeSuggestion(int slotIndex, Long timeMillis) {
            TimestampedValue<Long> time = null;
            if (timeMillis != null) {
                time = new TimestampedValue<>(peekElapsedRealtimeMillis(), timeMillis);
            }
            return createTelephonyTimeSuggestion(slotIndex, time);
        }

        /**
         * Generates a NetworkTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        NetworkTimeSuggestion generateNetworkTimeSuggestion(long timeMillis) {
            TimestampedValue<Long> utcTime =
                    new TimestampedValue<>(mFakeCallback.peekElapsedRealtimeMillis(), timeMillis);
            return new NetworkTimeSuggestion(utcTime);
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

    private static long createUtcTime(int year, int monthInYear, int day, int hourOfDay, int minute,
            int second) {
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("Etc/UTC"));
        cal.clear();
        cal.set(year, monthInYear - 1, day, hourOfDay, minute, second);
        return cal.getTimeInMillis();
    }
}
