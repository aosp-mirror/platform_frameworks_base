/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.time.UnixEpochTime;
import android.net.Network;
import android.util.NtpTrustedTime;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.timedetector.NetworkTimeUpdateService.Engine.RefreshCallbacks;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

@RunWith(AndroidJUnit4.class)
public class NetworkTimeUpdateServiceTest {

    private static final InetSocketAddress FAKE_SERVER_ADDRESS =
            InetSocketAddress.createUnresolved("test", 123);
    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 100000000L;
    private static final long ARBITRARY_UNIX_EPOCH_TIME_MILLIS = 5555555555L;
    private static final int ARBITRARY_UNCERTAINTY_MILLIS = 999;

    private FakeElapsedRealtimeClock mFakeElapsedRealtimeClock;
    private NtpTrustedTime mMockNtpTrustedTime;
    private Network mDummyNetwork;

    @Before
    public void setUp() {
        mFakeElapsedRealtimeClock = new FakeElapsedRealtimeClock();
        mMockNtpTrustedTime = mock(NtpTrustedTime.class);
        mDummyNetwork = mock(Network.class);
    }

    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_nullNetwork() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 5;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
        // Trigger the engine's logic.
        engine.refreshAndRescheduleIfRequired(null, "Test", mockCallback);

        // Expect nothing to have happened.
        verifyNoMoreInteractions(mMockNtpTrustedTime);
        verifyNoMoreInteractions(mockCallback);
    }

    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_success() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 5;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        // Simulated NTP client behavior: No cached time value available initially, then a
        // successful refresh.
        NtpTrustedTime.TimeResult timeResult = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
        when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null, timeResult);
        when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(true);

        // Simulate the passage of time for realism.
        mFakeElapsedRealtimeClock.incrementMillis(5000);

        RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
        // Trigger the engine's logic.
        engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

        // Expect the refresh attempt to have been made.
        verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

        // Check everything happened that was supposed to.
        NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult);
        verify(mockCallback).submitSuggestion(expectedSuggestion);
        long expectedDelayMillis = normalPollingIntervalMillis;
        verify(mockCallback).scheduleNextRefresh(
                timeResult.getElapsedRealtimeMillis() + expectedDelayMillis);
    }

    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_failThenFailRepeatedly() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 5;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        for (int i = 0; i < tryAgainTimesMax + 1; i++) {
            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);

            // Simulated NTP client behavior: No cached time value available and failure to refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(false);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect a refresh attempt each time: there's no currently cached result.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            // Check everything happened that was supposed to.
            verify(mockCallback, never()).submitSuggestion(any());
            long expectedDelayMillis;
            if (i < tryAgainTimesMax) {
                expectedDelayMillis = shortPollingIntervalMillis;
            } else {
                expectedDelayMillis = normalPollingIntervalMillis;
            }
            verify(mockCallback).scheduleNextRefresh(
                    mFakeElapsedRealtimeClock.getElapsedRealtimeMillis() + expectedDelayMillis);

            reset(mMockNtpTrustedTime);
        }
    }

    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_successThenFailRepeatedly() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 5;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        NtpTrustedTime.TimeResult timeResult = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
        NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult);

        {
            // Simulated NTP client behavior: No cached time value available initially, with a
            // successful refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null, timeResult);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(true);

            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect the refresh attempt to have been made: there is no cached network time
            // initially.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            verify(mockCallback, times(1)).submitSuggestion(expectedSuggestion);
            long expectedDelayMillis = normalPollingIntervalMillis;
            verify(mockCallback).scheduleNextRefresh(
                    timeResult.getElapsedRealtimeMillis() + expectedDelayMillis);
            reset(mMockNtpTrustedTime);
        }

        // Increment the current time by enough so that an attempt to refresh the time should be
        // made every time refreshAndRescheduleIfRequired() is called.
        mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis);

        // Test multiple follow-up calls.
        for (int i = 0; i < tryAgainTimesMax + 1; i++) {
            // Simulated NTP client behavior: (Too old) cached time value available, unsuccessful
            // refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(false);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect a refresh attempt each time as the cached network time is too old.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            // No valid time, no suggestion.
            verify(mockCallback, never()).submitSuggestion(any());

            // Check the scheduling.
            long expectedDelayMillis;
            if (i < tryAgainTimesMax) {
                expectedDelayMillis = shortPollingIntervalMillis;
            } else {
                expectedDelayMillis = normalPollingIntervalMillis;
            }
            verify(mockCallback).scheduleNextRefresh(
                    mFakeElapsedRealtimeClock.getElapsedRealtimeMillis() + expectedDelayMillis);

            reset(mMockNtpTrustedTime);

            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);
        }
    }

    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_successThenFail_tryAgainTimesZero() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 0;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        NtpTrustedTime.TimeResult timeResult = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
        NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult);

        {
            // Simulated NTP client behavior: No cached time value available initially, with a
            // successful refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null, timeResult);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(true);

            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect the refresh attempt to have been made: there is no cached network time
            // initially.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            verify(mockCallback, times(1)).submitSuggestion(expectedSuggestion);
            long expectedDelayMillis = normalPollingIntervalMillis;
            verify(mockCallback).scheduleNextRefresh(
                    timeResult.getElapsedRealtimeMillis() + expectedDelayMillis);
            reset(mMockNtpTrustedTime);
        }

        // Increment the current time by enough so that an attempt to refresh the time should be
        // made every time refreshAndRescheduleIfRequired() is called.
        mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis);

        // Test multiple follow-up calls.
        for (int i = 0; i < 3; i++) {
            // Simulated NTP client behavior: (Too old) cached time value available, unsuccessful
            // refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(false);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect a refresh attempt each time as the cached network time is too old.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            // No valid time, no suggestion.
            verify(mockCallback, never()).submitSuggestion(any());

            // Check the scheduling. tryAgainTimesMax == 0, so the algorithm should start with
            long expectedDelayMillis = normalPollingIntervalMillis;
            verify(mockCallback).scheduleNextRefresh(
                    mFakeElapsedRealtimeClock.getElapsedRealtimeMillis() + expectedDelayMillis);

            reset(mMockNtpTrustedTime);

            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);
        }
    }

    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_successThenFail_tryAgainTimesNegative() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = -1;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        NtpTrustedTime.TimeResult timeResult = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
        NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult);

        {
            // Simulated NTP client behavior: No cached time value available initially, with a
            // successful refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null, timeResult);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(true);

            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect the refresh attempt to have been made: there is no cached network time
            // initially.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            verify(mockCallback, times(1)).submitSuggestion(expectedSuggestion);
            long expectedDelayMillis = normalPollingIntervalMillis;
            verify(mockCallback).scheduleNextRefresh(
                    timeResult.getElapsedRealtimeMillis() + expectedDelayMillis);
            reset(mMockNtpTrustedTime);
        }

        // Increment the current time by enough so that an attempt to refresh the time should be
        // made every time refreshAndRescheduleIfRequired() is called.
        mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis);

        // Test multiple follow-up calls.
        for (int i = 0; i < 3; i++) {
            // Simulated NTP client behavior: (Too old) cached time value available, unsuccessful
            // refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(false);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect a refresh attempt each time as the cached network time is too old.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            // No valid time, no suggestion.
            verify(mockCallback, never()).submitSuggestion(any());

            // Check the scheduling. tryAgainTimesMax == -1, so it should always be
            // shortPollingIntervalMillis.
            long expectedDelayMillis = shortPollingIntervalMillis;
            verify(mockCallback).scheduleNextRefresh(
                    mFakeElapsedRealtimeClock.getElapsedRealtimeMillis() + expectedDelayMillis);

            reset(mMockNtpTrustedTime);

            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);
        }
    }

    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_successFailSuccess() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 5;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        NtpTrustedTime.TimeResult timeResult1 = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
        {
            // Simulated NTP client behavior: No cached time value available initially, with a
            // successful refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null, timeResult1);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(true);

            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect the refresh attempt to have been made: there is no cached network time
            // initially.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult1);
            verify(mockCallback, times(1)).submitSuggestion(expectedSuggestion);
            long expectedDelayMillis = normalPollingIntervalMillis;
            verify(mockCallback).scheduleNextRefresh(
                    timeResult1.getElapsedRealtimeMillis() + expectedDelayMillis);
            reset(mMockNtpTrustedTime);
        }

        // Increment the current time by enough so that the cached time result is too old and an
        // attempt to refresh the time should be made every time refreshAndRescheduleIfRequired() is
        // called.
        mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis);

        {
            // Simulated NTP client behavior: (Old) cached time value available initially, with an
            // unsuccessful refresh.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult1);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(false);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect the refresh attempt to have been made: the timeResult is too old.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            // No valid time, no suggestion.
            verify(mockCallback, never()).submitSuggestion(any());

            long expectedDelayMillis = shortPollingIntervalMillis;
            verify(mockCallback).scheduleNextRefresh(
                    mFakeElapsedRealtimeClock.getElapsedRealtimeMillis() + expectedDelayMillis);

            reset(mMockNtpTrustedTime);
        }

        // Increment time enough to avoid the minimum refresh interval protection.
        mFakeElapsedRealtimeClock.incrementMillis(shortPollingIntervalMillis);

        NtpTrustedTime.TimeResult timeResult2 = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());

        {
            // Simulated NTP client behavior: (Old) cached time value available initially, with a
            // successful refresh and a new cached time value.
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult1, timeResult2);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(true);

            // Simulate the passage of time for realism.
            mFakeElapsedRealtimeClock.incrementMillis(5000);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);

            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect the refresh attempt to have been made: the timeResult is too old.
            verify(mMockNtpTrustedTime).forceRefresh(mDummyNetwork);

            NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult2);
            verify(mockCallback, times(1)).submitSuggestion(expectedSuggestion);
            long expectedDelayMillis = normalPollingIntervalMillis;
            verify(mockCallback).scheduleNextRefresh(
                    timeResult2.getElapsedRealtimeMillis() + expectedDelayMillis);
            reset(mMockNtpTrustedTime);
        }
    }

    /**
     * Confirms that if a refreshAndRescheduleIfRequired() call is made, e.g. for reasons besides
     * scheduled alerts, and the latest time is not too old, then an NTP refresh won't be attempted.
     * A suggestion will still be made.
     */
    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_noRefreshIfLatestIsFresh() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 5;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        // Simulated NTP client behavior: A cached time value is available.
        NtpTrustedTime.TimeResult timeResult = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
        when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult);
        // Increment the clock, but not enough to consider the cached value too old.
        mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis - 1);

        RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
        // Trigger the engine's logic.
        engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

        // Expect no refresh attempt to have been made.
        verify(mMockNtpTrustedTime, never()).forceRefresh(any());

        // Suggestions must be made every time if the cached time value is not too old in case it
        // was refreshed by a different component.
        NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult);
        verify(mockCallback, times(1)).submitSuggestion(expectedSuggestion);

        // The next wake-up should be rescheduled for when the cached time value will become too
        // old.
        long expectedDelayMillis = normalPollingIntervalMillis;
        verify(mockCallback).scheduleNextRefresh(
                timeResult.getElapsedRealtimeMillis() + expectedDelayMillis);
    }

    /**
     * Confirms that if a refreshAndRescheduleIfRequired() call is made, e.g. for reasons besides
     * scheduled alerts, and the latest time is too old, then an NTP refresh will be attempted.
     */
    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_failureHandlingAfterLatestIsTooOld() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 5;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        // Simulated NTP client behavior: A cached time value is available, increment the clock,
        // enough to consider the cached value too old. The refresh attempt will fail.
        NtpTrustedTime.TimeResult timeResult = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
        when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult);
        mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis);
        when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(false);

        RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
        // Trigger the engine's logic.
        engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

        // Expect a refresh attempt to have been made.
        verify(mMockNtpTrustedTime, times(1)).forceRefresh(mDummyNetwork);

        // Suggestions should not be made if the cached time value is too old.
        verify(mockCallback, never()).submitSuggestion(any());

        // The next wake-up should be rescheduled using the short polling interval.
        long expectedDelayMillis = shortPollingIntervalMillis;
        verify(mockCallback).scheduleNextRefresh(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis() + expectedDelayMillis);
    }

    /**
     * Confirms that if a refreshAndRescheduleIfRequired() call is made and there was a recently
     * failed refresh, then another won't be scheduled too soon.
     */
    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_minimumRefreshTimeEnforced() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 0;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        NtpTrustedTime.TimeResult timeResult = createNtpTimeResult(
                mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());

        // Simulate an initial call to refreshAndRescheduleIfRequired() prime the "last refresh
        // attempt" time. A cached time value is available, but it's too old but the refresh
        // attempt will fail.
        long lastRefreshAttemptElapsedMillis;
        {
            // Increment the clock, enough to consider the cached value too old.
            mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis);
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(false);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect a refresh attempt to have been made.
            verify(mMockNtpTrustedTime, times(1)).forceRefresh(mDummyNetwork);
            lastRefreshAttemptElapsedMillis = mFakeElapsedRealtimeClock.getElapsedRealtimeMillis();

            // Suggestions should not be made if the cached time value is too old.
            verify(mockCallback, never()).submitSuggestion(any());

            // The next wake-up should be rescheduled using the normalPollingIntervalMillis.
            // Because the time signal age > normalPollingIntervalMillis, the last refresh attempt
            // time will be used.
            long expectedDelayMillis = normalPollingIntervalMillis;
            long expectedNextRefreshElapsedMillis =
                    lastRefreshAttemptElapsedMillis + expectedDelayMillis;
            verify(mockCallback).scheduleNextRefresh(expectedNextRefreshElapsedMillis);

            reset(mMockNtpTrustedTime);
        }

        // Simulate a second call to refreshAndRescheduleIfRequired() very soon after the first, as
        // might happen if there were a network state change.
        // The cached time value is available, but it's still too old. Because the last call was so
        // recent, no refresh should take place and the next scheduled refresh time should be
        // set appropriately based on the last attempt.
        {
            // Increment the clock by a relatively small amount so that it's considered "too soon".
            mFakeElapsedRealtimeClock.incrementMillis(shortPollingIntervalMillis / 2);

            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect no refresh attempt to have been made: time elapsed isn't enough.
            verify(mMockNtpTrustedTime, never()).forceRefresh(any());

            // Suggestions should not be made if the cached time value is too old.
            verify(mockCallback, never()).submitSuggestion(any());

            // The next wake-up should be rescheduled using the normal polling interval and the last
            // refresh attempt time.
            long expectedDelayMillis = normalPollingIntervalMillis;
            long expectedNextRefreshElapsedMillis =
                    lastRefreshAttemptElapsedMillis + expectedDelayMillis;
            verify(mockCallback).scheduleNextRefresh(expectedNextRefreshElapsedMillis);

            reset(mMockNtpTrustedTime);
        }
    }

    /**
     * Confirms that if a refreshAndRescheduleIfRequired() call is made and there was a recently
     * failed refresh, then another won't be scheduled too soon.
     */
    @Test
    public void engineImpl_refreshAndRescheduleIfRequired_minimumRefreshTimeEnforced_b269425914() {
        mFakeElapsedRealtimeClock.setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS);

        int normalPollingIntervalMillis = 7777777;
        int shortPollingIntervalMillis = 3333;
        int tryAgainTimesMax = 3;
        NetworkTimeUpdateService.Engine engine = new NetworkTimeUpdateService.EngineImpl(
                mFakeElapsedRealtimeClock,
                normalPollingIntervalMillis, shortPollingIntervalMillis, tryAgainTimesMax,
                mMockNtpTrustedTime);

        NtpTrustedTime.TimeResult timeResult1;

        // Start out with a successful refresh.
        long lastRefreshAttemptElapsedMillis;
        {
            mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis);
            timeResult1 = createNtpTimeResult(mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null, timeResult1);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(true);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            // Expect a refresh attempt to have been made.
            verify(mMockNtpTrustedTime, times(1)).forceRefresh(mDummyNetwork);
            lastRefreshAttemptElapsedMillis = mFakeElapsedRealtimeClock.getElapsedRealtimeMillis();

            NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult1);
            verify(mockCallback, times(1)).submitSuggestion(expectedSuggestion);

            // The next wake-up should be rescheduled using the normalPollingIntervalMillis.
            // Because the time signal age > normalPollingIntervalMillis, the last refresh attempt
            // time will be used.
            long expectedDelayMillis = normalPollingIntervalMillis;
            long expectedNextRefreshElapsedMillis =
                    lastRefreshAttemptElapsedMillis + expectedDelayMillis;
            verify(mockCallback).scheduleNextRefresh(expectedNextRefreshElapsedMillis);

            reset(mMockNtpTrustedTime);
        }

        // Now fail: This should result in the next refresh using a short polling interval.
        {
            mFakeElapsedRealtimeClock.incrementMillis(normalPollingIntervalMillis);
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult1);
            when(mMockNtpTrustedTime.forceRefresh(mDummyNetwork)).thenReturn(false);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            verify(mMockNtpTrustedTime, times(1)).forceRefresh(mDummyNetwork);
            lastRefreshAttemptElapsedMillis = mFakeElapsedRealtimeClock.getElapsedRealtimeMillis();

            // Suggestions should not be made if the cached time value is too old.
            verify(mockCallback, never()).submitSuggestion(any());

            // The next wake-up should be rescheduled using the last refresh attempt time (because
            // the latest time result is too old) and the short polling interval.
            long expectedDelayMillis = shortPollingIntervalMillis;
            long expectedNextRefreshElapsedMillis =
                    lastRefreshAttemptElapsedMillis + expectedDelayMillis;
            verify(mockCallback).scheduleNextRefresh(expectedNextRefreshElapsedMillis);

            reset(mMockNtpTrustedTime);
        }

        // Simulate some other thread successfully refreshing the value held by NtpTrustedTime and
        // confirm it is handled correctly.
        {
            mFakeElapsedRealtimeClock.incrementMillis(shortPollingIntervalMillis / 2);
            NtpTrustedTime.TimeResult timeResult2 = createNtpTimeResult(
                    mFakeElapsedRealtimeClock.getElapsedRealtimeMillis());
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(timeResult2);

            mFakeElapsedRealtimeClock.incrementMillis(shortPollingIntervalMillis / 2);

            RefreshCallbacks mockCallback = mock(RefreshCallbacks.class);
            // Trigger the engine's logic.
            engine.refreshAndRescheduleIfRequired(mDummyNetwork, "Test", mockCallback);

            verify(mMockNtpTrustedTime, never()).forceRefresh(any());

            NetworkTimeSuggestion expectedSuggestion = createExpectedSuggestion(timeResult2);
            verify(mockCallback, times(1)).submitSuggestion(expectedSuggestion);

            // The next wake-up should be rescheduled using the normal polling interval and the
            // latest time result.
            long expectedDelayMillis = normalPollingIntervalMillis;
            long expectedNextRefreshElapsedMillis =
                    timeResult2.getElapsedRealtimeMillis() + expectedDelayMillis;
            verify(mockCallback).scheduleNextRefresh(expectedNextRefreshElapsedMillis);
        }
    }

    private static NetworkTimeSuggestion createExpectedSuggestion(
            NtpTrustedTime.TimeResult timeResult) {
        UnixEpochTime unixEpochTime = new UnixEpochTime(
                timeResult.getElapsedRealtimeMillis(), timeResult.getTimeMillis());
        return new NetworkTimeSuggestion(unixEpochTime, timeResult.getUncertaintyMillis());
    }

    private static NtpTrustedTime.TimeResult createNtpTimeResult(long elapsedRealtimeMillis) {
        return new NtpTrustedTime.TimeResult(
                ARBITRARY_UNIX_EPOCH_TIME_MILLIS,
                elapsedRealtimeMillis,
                ARBITRARY_UNCERTAINTY_MILLIS,
                FAKE_SERVER_ADDRESS);
    }

    private static class FakeElapsedRealtimeClock implements Supplier<Long> {

        private long mElapsedRealtimeMillis;

        public void setElapsedRealtimeMillis(long elapsedRealtimeMillis) {
            mElapsedRealtimeMillis = elapsedRealtimeMillis;
        }

        public long getElapsedRealtimeMillis() {
            return mElapsedRealtimeMillis;
        }

        public long incrementMillis(int millis) {
            mElapsedRealtimeMillis += millis;
            return mElapsedRealtimeMillis;
        }

        @Override
        public Long get() {
            return getElapsedRealtimeMillis();
        }
    }
}
