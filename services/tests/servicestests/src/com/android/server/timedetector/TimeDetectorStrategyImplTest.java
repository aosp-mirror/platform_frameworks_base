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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.PhoneTimeSuggestion;
import android.content.Intent;
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

    private static final int ARBITRARY_PHONE_ID = 123456;

    private Script mScript;

    @Before
    public void setUp() {
        mScript = new Script();
    }

    @Test
    public void testSuggestPhoneTime_autoTimeEnabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int phoneId = ARBITRARY_PHONE_ID;
        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;

        PhoneTimeSuggestion timeSuggestion =
                mScript.generatePhoneTimeSuggestion(phoneId, testTimeMillis);
        mScript.simulateTimePassing()
                .simulatePhoneTimeSuggestion(timeSuggestion);

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(timeSuggestion.getUtcTime());
        mScript.verifySystemClockWasSetAndResetCallTracking(
                expectedSystemClockMillis, true /* expectNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion);
    }

    @Test
    public void testSuggestPhoneTime_emptySuggestionIgnored() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int phoneId = ARBITRARY_PHONE_ID;
        PhoneTimeSuggestion timeSuggestion =
                mScript.generatePhoneTimeSuggestion(phoneId, null);
        mScript.simulatePhoneTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, null);
    }

    @Test
    public void testSuggestPhoneTime_systemClockThreshold() {
        final int systemClockUpdateThresholdMillis = 1000;
        final int clockIncrementMillis = 100;
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeThresholds(systemClockUpdateThresholdMillis)
                .pokeAutoTimeDetectionEnabled(true);

        int phoneId = ARBITRARY_PHONE_ID;

        // Send the first time signal. It should be used.
        {
            PhoneTimeSuggestion timeSuggestion1 =
                    mScript.generatePhoneTimeSuggestion(phoneId, ARBITRARY_TEST_TIME_MILLIS);

            // Increment the the device clocks to simulate the passage of time.
            mScript.simulateTimePassing(clockIncrementMillis);

            long expectedSystemClockMillis1 =
                    mScript.calculateTimeInMillisForNow(timeSuggestion1.getUtcTime());

            mScript.simulatePhoneTimeSuggestion(timeSuggestion1)
                    .verifySystemClockWasSetAndResetCallTracking(
                            expectedSystemClockMillis1, true /* expectNetworkBroadcast */)
                    .assertLatestPhoneSuggestion(phoneId, timeSuggestion1);
        }

        // Now send another time signal, but one that is too similar to the last one and should be
        // stored, but not used to set the system clock.
        {
            int underThresholdMillis = systemClockUpdateThresholdMillis - 1;
            PhoneTimeSuggestion timeSuggestion2 = mScript.generatePhoneTimeSuggestion(
                    phoneId, mScript.peekSystemClockMillis() + underThresholdMillis);
            mScript.simulateTimePassing(clockIncrementMillis)
                    .simulatePhoneTimeSuggestion(timeSuggestion2)
                    .verifySystemClockWasNotSetAndResetCallTracking()
                    .assertLatestPhoneSuggestion(phoneId, timeSuggestion2);
        }

        // Now send another time signal, but one that is on the threshold and so should be used.
        {
            PhoneTimeSuggestion timeSuggestion3 = mScript.generatePhoneTimeSuggestion(
                    phoneId,
                    mScript.peekSystemClockMillis() + systemClockUpdateThresholdMillis);
            mScript.simulateTimePassing(clockIncrementMillis);

            long expectedSystemClockMillis3 =
                    mScript.calculateTimeInMillisForNow(timeSuggestion3.getUtcTime());

            mScript.simulatePhoneTimeSuggestion(timeSuggestion3)
                    .verifySystemClockWasSetAndResetCallTracking(
                            expectedSystemClockMillis3, true /* expectNetworkBroadcast */)
                    .assertLatestPhoneSuggestion(phoneId, timeSuggestion3);
        }
    }

    @Test
    public void testSuggestPhoneTime_multiplePhoneIdsAndBucketing() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        // There are 2 phones in this test. Phone 2 has a different idea of the current time.
        // phone1Id < phone2Id (which is important because the strategy uses the lowest ID when
        // multiple phone suggestions are available.
        int phone1Id = ARBITRARY_PHONE_ID;
        int phone2Id = ARBITRARY_PHONE_ID + 1;
        long phone1TimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        long phone2TimeMillis = ARBITRARY_TEST_TIME_MILLIS + Duration.ofDays(1).toMillis();

        // Make a suggestion with phone2Id.
        {
            PhoneTimeSuggestion phone2TimeSuggestion =
                    mScript.generatePhoneTimeSuggestion(phone2Id, phone2TimeMillis);
            mScript.simulateTimePassing();

            long expectedSystemClockMillis =
                    mScript.calculateTimeInMillisForNow(phone2TimeSuggestion.getUtcTime());

            mScript.simulatePhoneTimeSuggestion(phone2TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(
                            expectedSystemClockMillis, true /* expectNetworkBroadcast */)
                    .assertLatestPhoneSuggestion(phone1Id, null)
                    .assertLatestPhoneSuggestion(phone2Id, phone2TimeSuggestion);
        }

        mScript.simulateTimePassing();

        // Now make a different suggestion with phone1Id.
        {
            PhoneTimeSuggestion phone1TimeSuggestion =
                    mScript.generatePhoneTimeSuggestion(phone1Id, phone1TimeMillis);
            mScript.simulateTimePassing();

            long expectedSystemClockMillis =
                    mScript.calculateTimeInMillisForNow(phone1TimeSuggestion.getUtcTime());

            mScript.simulatePhoneTimeSuggestion(phone1TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(
                            expectedSystemClockMillis, true /* expectNetworkBroadcast */)
                    .assertLatestPhoneSuggestion(phone1Id, phone1TimeSuggestion);

        }

        mScript.simulateTimePassing();

        // Make another suggestion with phone2Id. It should be stored but not used because the
        // phone1Id suggestion will still "win".
        {
            PhoneTimeSuggestion phone2TimeSuggestion =
                    mScript.generatePhoneTimeSuggestion(phone2Id, phone2TimeMillis);
            mScript.simulateTimePassing();

            mScript.simulatePhoneTimeSuggestion(phone2TimeSuggestion)
                    .verifySystemClockWasNotSetAndResetCallTracking()
                    .assertLatestPhoneSuggestion(phone2Id, phone2TimeSuggestion);
        }

        // Let enough time pass that phone1Id's suggestion should now be too old.
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.PHONE_BUCKET_SIZE_MILLIS);

        // Make another suggestion with phone2Id. It should be used because the phoneId1
        // is in an older "bucket".
        {
            PhoneTimeSuggestion phone2TimeSuggestion =
                    mScript.generatePhoneTimeSuggestion(phone2Id, phone2TimeMillis);
            mScript.simulateTimePassing();

            long expectedSystemClockMillis =
                    mScript.calculateTimeInMillisForNow(phone2TimeSuggestion.getUtcTime());

            mScript.simulatePhoneTimeSuggestion(phone2TimeSuggestion)
                    .verifySystemClockWasSetAndResetCallTracking(
                            expectedSystemClockMillis, true /* expectNetworkBroadcast */)
                    .assertLatestPhoneSuggestion(phone2Id, phone2TimeSuggestion);
        }
    }

    @Test
    public void testSuggestPhoneTime_autoTimeDisabled() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(false);

        int phoneId = ARBITRARY_PHONE_ID;
        PhoneTimeSuggestion timeSuggestion =
                mScript.generatePhoneTimeSuggestion(phoneId, ARBITRARY_TEST_TIME_MILLIS);
        mScript.simulateTimePassing()
                .simulatePhoneTimeSuggestion(timeSuggestion)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion);
    }

    @Test
    public void testSuggestPhoneTime_invalidNitzReferenceTimesIgnored() {
        final int systemClockUpdateThreshold = 2000;
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeThresholds(systemClockUpdateThreshold)
                .pokeAutoTimeDetectionEnabled(true);

        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        int phoneId = ARBITRARY_PHONE_ID;

        PhoneTimeSuggestion timeSuggestion1 =
                mScript.generatePhoneTimeSuggestion(phoneId, testTimeMillis);
        TimestampedValue<Long> utcTime1 = timeSuggestion1.getUtcTime();

        // Initialize the strategy / device with a time set from a phone suggestion.
        mScript.simulateTimePassing();
        long expectedSystemClockMillis1 = mScript.calculateTimeInMillisForNow(utcTime1);
        mScript.simulatePhoneTimeSuggestion(timeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis1, true /* expectNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion1);

        // The UTC time increment should be larger than the system clock update threshold so we
        // know it shouldn't be ignored for other reasons.
        long validUtcTimeMillis = utcTime1.getValue() + (2 * systemClockUpdateThreshold);

        // Now supply a new signal that has an obviously bogus reference time : older than the last
        // one.
        long referenceTimeBeforeLastSignalMillis = utcTime1.getReferenceTimeMillis() - 1;
        TimestampedValue<Long> utcTime2 = new TimestampedValue<>(
                referenceTimeBeforeLastSignalMillis, validUtcTimeMillis);
        PhoneTimeSuggestion timeSuggestion2 =
                createPhoneTimeSuggestion(phoneId, utcTime2);
        mScript.simulatePhoneTimeSuggestion(timeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion1);

        // Now supply a new signal that has an obviously bogus reference time : substantially in the
        // future.
        long referenceTimeInFutureMillis =
                utcTime1.getReferenceTimeMillis() + Integer.MAX_VALUE + 1;
        TimestampedValue<Long> utcTime3 = new TimestampedValue<>(
                referenceTimeInFutureMillis, validUtcTimeMillis);
        PhoneTimeSuggestion timeSuggestion3 =
                createPhoneTimeSuggestion(phoneId, utcTime3);
        mScript.simulatePhoneTimeSuggestion(timeSuggestion3)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion1);

        // Just to prove validUtcTimeMillis is valid.
        long validReferenceTimeMillis = utcTime1.getReferenceTimeMillis() + 100;
        TimestampedValue<Long> utcTime4 = new TimestampedValue<>(
                validReferenceTimeMillis, validUtcTimeMillis);
        long expectedSystemClockMillis4 = mScript.calculateTimeInMillisForNow(utcTime4);
        PhoneTimeSuggestion timeSuggestion4 =
                createPhoneTimeSuggestion(phoneId, utcTime4);
        mScript.simulatePhoneTimeSuggestion(timeSuggestion4)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis4, true /* expectNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion4);
    }

    @Test
    public void testSuggestPhoneTime_timeDetectionToggled() {
        final int clockIncrementMillis = 100;
        final int systemClockUpdateThreshold = 2000;
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeThresholds(systemClockUpdateThreshold)
                .pokeAutoTimeDetectionEnabled(false);

        int phoneId = ARBITRARY_PHONE_ID;
        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        PhoneTimeSuggestion timeSuggestion1 =
                mScript.generatePhoneTimeSuggestion(phoneId, testTimeMillis);
        TimestampedValue<Long> utcTime1 = timeSuggestion1.getUtcTime();

        // Simulate time passing.
        mScript.simulateTimePassing(clockIncrementMillis);

        // Simulate the time signal being received. It should not be used because auto time
        // detection is off but it should be recorded.
        mScript.simulatePhoneTimeSuggestion(timeSuggestion1)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion1);

        // Simulate more time passing.
        mScript.simulateTimePassing(clockIncrementMillis);

        long expectedSystemClockMillis1 = mScript.calculateTimeInMillisForNow(utcTime1);

        // Turn on auto time detection.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis1, true /* expectNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion1);

        // Turn off auto time detection.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion1);

        // Receive another valid time signal.
        // It should be on the threshold and accounting for the clock increments.
        PhoneTimeSuggestion timeSuggestion2 = mScript.generatePhoneTimeSuggestion(
                phoneId, mScript.peekSystemClockMillis() + systemClockUpdateThreshold);

        // Simulate more time passing.
        mScript.simulateTimePassing(clockIncrementMillis);

        long expectedSystemClockMillis2 =
                mScript.calculateTimeInMillisForNow(timeSuggestion2.getUtcTime());

        // The new time, though valid, should not be set in the system clock because auto time is
        // disabled.
        mScript.simulatePhoneTimeSuggestion(timeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion2);

        // Turn on auto time detection.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis2, true /* expectNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, timeSuggestion2);
    }

    @Test
    public void testSuggestPhoneTime_maxSuggestionAge() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int phoneId = ARBITRARY_PHONE_ID;
        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        PhoneTimeSuggestion phoneSuggestion =
                mScript.generatePhoneTimeSuggestion(phoneId, testTimeMillis);

        mScript.simulateTimePassing();

        long expectedSystemClockMillis =
                mScript.calculateTimeInMillisForNow(phoneSuggestion.getUtcTime());
        mScript.simulatePhoneTimeSuggestion(phoneSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis, true /* expectedNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, phoneSuggestion);

        // Look inside and check what the strategy considers the current best phone suggestion.
        assertEquals(phoneSuggestion, mScript.peekBestPhoneSuggestion());

        // Simulate time passing, long enough that phoneSuggestion is now too old.
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS);

        // Look inside and check what the strategy considers the current best phone suggestion. It
        // should still be the, it's just no longer used.
        assertNull(mScript.peekBestPhoneSuggestion());
        mScript.assertLatestPhoneSuggestion(phoneId, phoneSuggestion);
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
        mScript.simulateManualTimeSuggestion(timeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis, false /* expectNetworkBroadcast */);
    }

    @Test
    public void testSuggestManualTime_retainsAutoSignal() {
        // Configure the start state.
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        int phoneId = ARBITRARY_PHONE_ID;

        // Simulate a phone suggestion.
        long testTimeMillis = ARBITRARY_TEST_TIME_MILLIS;
        PhoneTimeSuggestion phoneTimeSuggestion =
                mScript.generatePhoneTimeSuggestion(phoneId, testTimeMillis);

        // Simulate the passage of time.
        mScript.simulateTimePassing();

        long expectedAutoClockMillis =
                mScript.calculateTimeInMillisForNow(phoneTimeSuggestion.getUtcTime());
        mScript.simulatePhoneTimeSuggestion(phoneTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedAutoClockMillis, true /* expectNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, phoneTimeSuggestion);

        // Simulate the passage of time.
        mScript.simulateTimePassing();

        // Switch to manual.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, phoneTimeSuggestion);

        // Simulate the passage of time.
        mScript.simulateTimePassing();

        // Simulate a manual suggestion 1 day different from the auto suggestion.
        long manualTimeMillis = testTimeMillis + Duration.ofDays(1).toMillis();
        ManualTimeSuggestion manualTimeSuggestion =
                mScript.generateManualTimeSuggestion(manualTimeMillis);
        mScript.simulateTimePassing();

        long expectedManualClockMillis =
                mScript.calculateTimeInMillisForNow(manualTimeSuggestion.getUtcTime());
        mScript.simulateManualTimeSuggestion(manualTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedManualClockMillis, false /* expectNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, phoneTimeSuggestion);

        // Simulate the passage of time.
        mScript.simulateTimePassing();

        // Switch back to auto.
        mScript.simulateAutoTimeDetectionToggle();

        expectedAutoClockMillis =
                mScript.calculateTimeInMillisForNow(phoneTimeSuggestion.getUtcTime());
        mScript.verifySystemClockWasSetAndResetCallTracking(
                        expectedAutoClockMillis, true /* expectNetworkBroadcast */)
                .assertLatestPhoneSuggestion(phoneId, phoneTimeSuggestion);

        // Switch back to manual - nothing should happen to the clock.
        mScript.simulateAutoTimeDetectionToggle()
                .verifySystemClockWasNotSetAndResetCallTracking()
                .assertLatestPhoneSuggestion(phoneId, phoneTimeSuggestion);
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
                .simulateManualTimeSuggestion(timeSuggestion)
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
                .verifySystemClockWasSetAndResetCallTracking(
                        expectedSystemClockMillis, false /* expectNetworkBroadcast */);
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
    public void testSuggestNetworkTime_phoneSuggestionsBeatNetworkSuggestions() {
        mScript.pokeFakeClocks(ARBITRARY_CLOCK_INITIALIZATION_INFO)
                .pokeAutoTimeDetectionEnabled(true);

        // Three obviously different times that could not be mistaken for each other.
        long networkTimeMillis1 = ARBITRARY_TEST_TIME_MILLIS;
        long networkTimeMillis2 = ARBITRARY_TEST_TIME_MILLIS + Duration.ofDays(30).toMillis();
        long phoneTimeMillis = ARBITRARY_TEST_TIME_MILLIS + Duration.ofDays(60).toMillis();
        // A small increment used to simulate the passage of time, but not enough to interfere with
        // macro-level time changes associated with suggestion age.
        final long smallTimeIncrementMillis = 101;

        // A network suggestion is made. It should be used because there is no phone suggestion.
        NetworkTimeSuggestion networkTimeSuggestion1 =
                mScript.generateNetworkTimeSuggestion(networkTimeMillis1);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion1)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(networkTimeSuggestion1.getUtcTime()),
                        false /* expectNetworkBroadcast */);

        // Check internal state.
        mScript.assertLatestPhoneSuggestion(ARBITRARY_PHONE_ID, null)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, mScript.peekLatestValidNetworkSuggestion());
        assertNull(mScript.peekBestPhoneSuggestion());

        // Simulate a little time passing.
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now a phone suggestion is made. Phone suggestions are prioritized over network
        // suggestions so it should "win".
        PhoneTimeSuggestion phoneTimeSuggestion =
                mScript.generatePhoneTimeSuggestion(ARBITRARY_PHONE_ID, phoneTimeMillis);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulatePhoneTimeSuggestion(phoneTimeSuggestion)
                .verifySystemClockWasSetAndResetCallTracking(
                        mScript.calculateTimeInMillisForNow(phoneTimeSuggestion.getUtcTime()),
                        true /* expectNetworkBroadcast */);

        // Check internal state.
        mScript.assertLatestPhoneSuggestion(ARBITRARY_PHONE_ID, phoneTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion1);
        assertEquals(networkTimeSuggestion1, mScript.peekLatestValidNetworkSuggestion());
        assertEquals(phoneTimeSuggestion, mScript.peekBestPhoneSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use".
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS / 2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Now another network suggestion is made. Phone suggestions are prioritized over network
        // suggestions so the latest phone suggestion should still "win".
        NetworkTimeSuggestion networkTimeSuggestion2 =
                mScript.generateNetworkTimeSuggestion(networkTimeMillis2);
        mScript.simulateTimePassing(smallTimeIncrementMillis)
                .simulateNetworkTimeSuggestion(networkTimeSuggestion2)
                .verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        mScript.assertLatestPhoneSuggestion(ARBITRARY_PHONE_ID, phoneTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, mScript.peekLatestValidNetworkSuggestion());
        assertEquals(phoneTimeSuggestion, mScript.peekBestPhoneSuggestion());

        // Simulate some significant time passing: half the time allowed before a time signal
        // becomes "too old to use". This should mean that phoneTimeSuggestion is now too old to be
        // used but networkTimeSuggestion2 is not.
        mScript.simulateTimePassing(TimeDetectorStrategyImpl.MAX_UTC_TIME_AGE_MILLIS / 2);

        // NOTE: The TimeDetectorStrategyImpl doesn't set an alarm for the point when the last
        // suggestion it used becomes too old: it requires a new suggestion or an auto-time toggle
        // to re-run the detection logic. This may change in future but until then we rely on a
        // steady stream of suggestions to re-evaluate.
        mScript.verifySystemClockWasNotSetAndResetCallTracking();

        // Check internal state.
        mScript.assertLatestPhoneSuggestion(ARBITRARY_PHONE_ID, phoneTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, mScript.peekLatestValidNetworkSuggestion());
        assertNull(mScript.peekBestPhoneSuggestion());

        // Toggle auto-time off and on to force the detection logic to run.
        mScript.simulateAutoTimeDetectionToggle()
                .simulateTimePassing(smallTimeIncrementMillis)
                .simulateAutoTimeDetectionToggle();

        // Verify the latest network time now wins.
        mScript.verifySystemClockWasSetAndResetCallTracking(
                mScript.calculateTimeInMillisForNow(networkTimeSuggestion2.getUtcTime()),
                false /* expectNetworkTimeBroadcast */);

        // Check internal state.
        mScript.assertLatestPhoneSuggestion(ARBITRARY_PHONE_ID, phoneTimeSuggestion)
                .assertLatestNetworkSuggestion(networkTimeSuggestion2);
        assertEquals(networkTimeSuggestion2, mScript.peekLatestValidNetworkSuggestion());
        assertNull(mScript.peekBestPhoneSuggestion());
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
        private Intent mBroadcastSent;

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
            assertWakeLockAcquired();
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

        @Override
        public void sendStickyBroadcast(Intent intent) {
            assertNotNull(intent);
            mBroadcastSent = intent;
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

        void verifyIntentWasBroadcast() {
            assertTrue(mBroadcastSent != null);
        }

        void verifyIntentWasNotBroadcast() {
            assertNull(mBroadcastSent);
        }

        void resetCallTracking() {
            mSystemClockWasSet = false;
            mBroadcastSent = null;
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

        Script simulatePhoneTimeSuggestion(PhoneTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestPhoneTime(timeSuggestion);
            return this;
        }

        Script simulateManualTimeSuggestion(ManualTimeSuggestion timeSuggestion) {
            mTimeDetectorStrategy.suggestManualTime(timeSuggestion);
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
            mFakeCallback.verifyIntentWasNotBroadcast();
            mFakeCallback.resetCallTracking();
            return this;
        }

        Script verifySystemClockWasSetAndResetCallTracking(
                long expectedSystemClockMillis, boolean expectNetworkBroadcast) {
            mFakeCallback.verifySystemClockWasSet(expectedSystemClockMillis);
            if (expectNetworkBroadcast) {
                mFakeCallback.verifyIntentWasBroadcast();
            }
            mFakeCallback.resetCallTracking();
            return this;
        }

        /**
         * White box test info: Asserts the latest suggestion for the phone ID is as expected.
         */
        Script assertLatestPhoneSuggestion(int phoneId, PhoneTimeSuggestion expected) {
            assertEquals(expected, mTimeDetectorStrategy.getLatestPhoneSuggestion(phoneId));
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
         * White box test info: Returns the phone suggestion that would be used, if any, given the
         * current elapsed real time clock and regardless of origin prioritization.
         */
        PhoneTimeSuggestion peekBestPhoneSuggestion() {
            return mTimeDetectorStrategy.findBestPhoneSuggestionForTests();
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
         * Generates a PhoneTimeSuggestion using the current elapsed realtime clock for the
         * reference time.
         */
        PhoneTimeSuggestion generatePhoneTimeSuggestion(int phoneId, Long timeMillis) {
            TimestampedValue<Long> time = null;
            if (timeMillis != null) {
                time = new TimestampedValue<>(peekElapsedRealtimeMillis(), timeMillis);
            }
            return createPhoneTimeSuggestion(phoneId, time);
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

    private static PhoneTimeSuggestion createPhoneTimeSuggestion(int phoneId,
            TimestampedValue<Long> utcTime) {
        return new PhoneTimeSuggestion.Builder(phoneId)
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
