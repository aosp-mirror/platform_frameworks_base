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

package com.android.server.location.gnss;

import static com.android.server.location.gnss.TimeDetectorNetworkTimeHelper.MAX_NETWORK_TIME_AGE_MILLIS;
import static com.android.server.location.gnss.TimeDetectorNetworkTimeHelper.NTP_REFRESH_INTERVAL_MILLIS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.time.UnixEpochTime;
import android.platform.test.annotations.Presubmit;

import com.android.server.location.gnss.NetworkTimeHelper.InjectTimeCallback;
import com.android.server.location.gnss.TimeDetectorNetworkTimeHelper.Environment;
import com.android.server.timedetector.NetworkTimeSuggestion;
import com.android.server.timezonedetector.StateChangeListener;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link TimeDetectorNetworkTimeHelper}.
 */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class TimeDetectorNetworkTimeHelperTest {

    private static final NetworkTimeSuggestion ARBITRARY_NETWORK_TIME =
            new NetworkTimeSuggestion(new UnixEpochTime(1234L, 7777L), 123);

    private FakeEnvironment mFakeEnvironment;
    @Mock private InjectTimeCallback mMockInjectTimeCallback;
    private TimeDetectorNetworkTimeHelper mTimeDetectorNetworkTimeHelper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mFakeEnvironment = new FakeEnvironment();
        mTimeDetectorNetworkTimeHelper = new TimeDetectorNetworkTimeHelper(
                mFakeEnvironment, mMockInjectTimeCallback);

        // TimeDetectorNetworkTimeHelper should register for network time updates during
        // construction.
        mFakeEnvironment.assertHasNetworkTimeChangeListener();
    }

    @Test
    public void setPeriodicTimeInjectionMode_true() {
        testSetPeriodicTimeInjectionMode(true);
    }

    @Test
    public void setPeriodicTimeInjectionMode_false() {
        testSetPeriodicTimeInjectionMode(false);
    }

    private void testSetPeriodicTimeInjectionMode(boolean periodicTimeInjectionMode) {
        NetworkTimeSuggestion networkTime = ARBITRARY_NETWORK_TIME;
        int millisElapsedSinceNetworkTimeReceived = 1000;
        mFakeEnvironment.pokeLatestNetworkTime(networkTime);

        long currentElapsedRealtimeMillis =
                networkTime.getUnixEpochTime().getElapsedRealtimeMillis()
                        + millisElapsedSinceNetworkTimeReceived;
        mFakeEnvironment.pokeElapsedRealtimeMillis(currentElapsedRealtimeMillis);

        mTimeDetectorNetworkTimeHelper.setPeriodicTimeInjectionMode(periodicTimeInjectionMode);

        // All injections are async, so we have to simulate the async work taking place.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        mFakeEnvironment.assertHasImmediateCallback();
        mFakeEnvironment.simulateTimeAdvancing(1);

        // Any call to setPeriodicTimeInjectionMode() should result in an (async) injected time
        verify(mMockInjectTimeCallback).injectTime(
                networkTime.getUnixEpochTime().getUnixEpochTimeMillis(),
                networkTime.getUnixEpochTime().getElapsedRealtimeMillis(),
                networkTime.getUncertaintyMillis());

        // Check whether the scheduled async is set up / not set up for the periodic request.
        if (periodicTimeInjectionMode) {
            mFakeEnvironment.assertHasScheduledAsyncCallback(
                    mFakeEnvironment.elapsedRealtimeMillis() + NTP_REFRESH_INTERVAL_MILLIS);
        } else {
            mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        }
    }

    @Test
    public void periodicInjectionBehavior() {
        // Set the elapsed realtime clock to an arbitrary start value.
        mFakeEnvironment.pokeElapsedRealtimeMillis(12345L);

        // Configure periodic time injections. Doing so should cause a time query, but no time is
        // available.
        mTimeDetectorNetworkTimeHelper.setPeriodicTimeInjectionMode(true);

        // All query/injections are async, so we have to simulate the async work taking place.
        mFakeEnvironment.assertHasImmediateCallback();
        mFakeEnvironment.simulateTimeAdvancing(1);

        // No time available, so no injection.
        verifyNoMoreInteractions(mMockInjectTimeCallback);

        // A periodic check should be scheduled.
        mFakeEnvironment.assertHasScheduledAsyncCallback(
                mFakeEnvironment.elapsedRealtimeMillis() + NTP_REFRESH_INTERVAL_MILLIS);

        // Time passes...
        mFakeEnvironment.simulateTimeAdvancing(NTP_REFRESH_INTERVAL_MILLIS / 2);

        // A network time becomes available: This should cause the registered listener to trigger.
        NetworkTimeSuggestion networkTime = ARBITRARY_NETWORK_TIME;
        mFakeEnvironment.simulateLatestNetworkTimeChange(networkTime);

        // All query/injections are async, so we have to simulate the async work taking place,
        // causing a query, time injection and a re-schedule.
        mFakeEnvironment.simulateTimeAdvancing(1);
        verify(mMockInjectTimeCallback).injectTime(
                networkTime.getUnixEpochTime().getUnixEpochTimeMillis(),
                networkTime.getUnixEpochTime().getElapsedRealtimeMillis(),
                networkTime.getUncertaintyMillis());

        // A new periodic check should be scheduled.
        mFakeEnvironment.assertHasNoImmediateCallback();

        mFakeEnvironment.assertHasScheduledAsyncCallback(
                mFakeEnvironment.elapsedRealtimeMillis() + NTP_REFRESH_INTERVAL_MILLIS);

        int arbitraryIterationCount = 3;
        for (int i = 0; i < arbitraryIterationCount; i++) {
            // Advance by the amount needed for the scheduled work to run. That work should query
            // and inject.
            mFakeEnvironment.simulateTimeAdvancing(NTP_REFRESH_INTERVAL_MILLIS);

            // All query/injections are async, so we have to simulate the async work taking place,
            // causing a query, time injection and a re-schedule.
            verify(mMockInjectTimeCallback).injectTime(
                    networkTime.getUnixEpochTime().getUnixEpochTimeMillis(),
                    networkTime.getUnixEpochTime().getElapsedRealtimeMillis(),
                    networkTime.getUncertaintyMillis());

            // A new periodic check should be scheduled.
            mFakeEnvironment.assertHasScheduledAsyncCallback(
                    mFakeEnvironment.elapsedRealtimeMillis() + NTP_REFRESH_INTERVAL_MILLIS);
            mFakeEnvironment.assertHasNoImmediateCallback();
        }
    }

    @Test
    public void networkTimeAvailableBehavior() {
        // Set the elapsed realtime clock to an arbitrary start value.
        mFakeEnvironment.pokeElapsedRealtimeMillis(12345L);

        // No periodic time injections. This call causes a time query, but no time is available yet.
        mTimeDetectorNetworkTimeHelper.setPeriodicTimeInjectionMode(false);

        // All query/injections are async, so we have to simulate the async work taking place.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        mFakeEnvironment.assertHasImmediateCallback();
        mFakeEnvironment.simulateTimeAdvancing(1);

        // No time available, so no injection.
        verifyNoMoreInteractions(mMockInjectTimeCallback);

        // No periodic check should be scheduled.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();

        // Time passes...
        mFakeEnvironment.simulateTimeAdvancing(NTP_REFRESH_INTERVAL_MILLIS / 2);

        // A network time becomes available: This should cause the registered listener to trigger
        // and cause time to be injected.
        NetworkTimeSuggestion networkTime = ARBITRARY_NETWORK_TIME;
        mFakeEnvironment.simulateLatestNetworkTimeChange(networkTime);

        // All query/injections are async, so we have to simulate the async work taking place,
        // causing a query, time injection and a re-schedule.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        mFakeEnvironment.assertHasImmediateCallback();
        mFakeEnvironment.simulateTimeAdvancing(1);
        verify(mMockInjectTimeCallback).injectTime(
                networkTime.getUnixEpochTime().getUnixEpochTimeMillis(),
                networkTime.getUnixEpochTime().getElapsedRealtimeMillis(),
                networkTime.getUncertaintyMillis());

        // No periodic check should be scheduled.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        mFakeEnvironment.assertHasNoImmediateCallback();
    }

    @Test
    public void networkConnectivityAvailableBehavior() {
        // Set the elapsed realtime clock to an arbitrary start value.
        mFakeEnvironment.pokeElapsedRealtimeMillis(12345L);

        // No periodic time injections. This call causes a time query, but no time is available yet.
        mTimeDetectorNetworkTimeHelper.setPeriodicTimeInjectionMode(false);

        // All query/injections are async, so we have to simulate the async work taking place.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        mFakeEnvironment.assertHasImmediateCallback();
        mFakeEnvironment.simulateTimeAdvancing(1);

        // No time available, so no injection.
        verifyNoMoreInteractions(mMockInjectTimeCallback);

        // No periodic check should be scheduled.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();

        // Time passes...
        mFakeEnvironment.simulateTimeAdvancing(NTP_REFRESH_INTERVAL_MILLIS / 2);

        NetworkTimeSuggestion networkTime = ARBITRARY_NETWORK_TIME;
        mFakeEnvironment.pokeLatestNetworkTime(networkTime);

        // Simulate location code noticing that connectivity has changed and notifying the helper.
        mTimeDetectorNetworkTimeHelper.onNetworkAvailable();

        // All query/injections are async, so we have to simulate the async work taking place,
        // causing a query, time injection and a re-schedule.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        mFakeEnvironment.assertHasImmediateCallback();
        mFakeEnvironment.simulateTimeAdvancing(1);
        verify(mMockInjectTimeCallback).injectTime(
                networkTime.getUnixEpochTime().getUnixEpochTimeMillis(),
                networkTime.getUnixEpochTime().getElapsedRealtimeMillis(),
                networkTime.getUncertaintyMillis());

        // No periodic check should be scheduled.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        mFakeEnvironment.assertHasNoImmediateCallback();
    }

    @Test
    public void oldTimesNotInjected() {
        NetworkTimeSuggestion networkTime = ARBITRARY_NETWORK_TIME;
        mFakeEnvironment.pokeLatestNetworkTime(networkTime);

        int millisElapsedSinceNetworkTimeReceived = MAX_NETWORK_TIME_AGE_MILLIS;
        long currentElapsedRealtimeMillis =
                networkTime.getUnixEpochTime().getElapsedRealtimeMillis()
                        + millisElapsedSinceNetworkTimeReceived;
        mFakeEnvironment.pokeElapsedRealtimeMillis(currentElapsedRealtimeMillis);

        mTimeDetectorNetworkTimeHelper.setPeriodicTimeInjectionMode(true);

        // All injections are async, so we have to simulate the async work taking place.
        mFakeEnvironment.assertHasNoScheduledAsyncCallback();
        mFakeEnvironment.assertHasImmediateCallback();

        // The age of the network time will now be MAX_NETWORK_TIME_AGE_MILLIS + 1, which is too
        // old to inject.
        mFakeEnvironment.simulateTimeAdvancing(1);

        // Old network times should not be injected.
        verify(mMockInjectTimeCallback, never()).injectTime(anyLong(), anyLong(), anyInt());

        // Check whether the scheduled async is set up / not set up for the periodic request.
        mFakeEnvironment.assertHasScheduledAsyncCallback(
                mFakeEnvironment.elapsedRealtimeMillis() + NTP_REFRESH_INTERVAL_MILLIS);
    }

    /** A fake implementation of {@link Environment} for use by this test. */
    private static class FakeEnvironment implements Environment {

        private StateChangeListener mNetworkTimeUpdateListener;

        private long mCurrentElapsedRealtimeMillis;
        private NetworkTimeSuggestion mLatestNetworkTime;

        private TimeDetectorNetworkTimeHelper mImmediateAsyncCallback;
        private String mImmediateAsyncCallbackReason;

        private TimeDetectorNetworkTimeHelper mScheduledAsyncCallback;
        private long mScheduledAsyncRunnableTimeMillis;

        @Override
        public long elapsedRealtimeMillis() {
            return mCurrentElapsedRealtimeMillis;
        }

        @Override
        public NetworkTimeSuggestion getLatestNetworkTime() {
            return mLatestNetworkTime;
        }

        @Override
        public void setNetworkTimeUpdateListener(StateChangeListener stateChangeListener) {
            mNetworkTimeUpdateListener = stateChangeListener;
        }

        @Override
        public void requestImmediateTimeQueryCallback(TimeDetectorNetworkTimeHelper helper,
                String reason) {
            if (mImmediateAsyncCallback != null) {
                fail("Only one immediate callback expected at a time, found reason: "
                        + mImmediateAsyncCallbackReason);
            }
            mImmediateAsyncCallback = helper;
            mImmediateAsyncCallbackReason = reason;
        }

        @Override
        public void requestDelayedTimeQueryCallback(
                TimeDetectorNetworkTimeHelper instance, long delayMillis) {
            mScheduledAsyncCallback = instance;
            mScheduledAsyncRunnableTimeMillis = mCurrentElapsedRealtimeMillis + delayMillis;
        }

        @Override
        public void clearDelayedTimeQueryCallback() {
            mScheduledAsyncCallback = null;
            mScheduledAsyncRunnableTimeMillis = -1;
        }

        void pokeLatestNetworkTime(NetworkTimeSuggestion networkTime) {
            mLatestNetworkTime = networkTime;
        }

        void pokeElapsedRealtimeMillis(long currentElapsedRealtimeMillis) {
            mCurrentElapsedRealtimeMillis = currentElapsedRealtimeMillis;
        }

        void simulateLatestNetworkTimeChange(NetworkTimeSuggestion networkTime) {
            mLatestNetworkTime = networkTime;
            mNetworkTimeUpdateListener.onChange();
        }

        void simulateTimeAdvancing(long durationMillis) {
            mCurrentElapsedRealtimeMillis += durationMillis;

            if (mImmediateAsyncCallback != null) {
                TimeDetectorNetworkTimeHelper helper = mImmediateAsyncCallback;
                String reason = mImmediateAsyncCallbackReason;
                mImmediateAsyncCallback = null;
                mImmediateAsyncCallbackReason = null;
                helper.queryAndInjectNetworkTime(reason);
            }

            if (mScheduledAsyncCallback != null
                    && mCurrentElapsedRealtimeMillis >= mScheduledAsyncRunnableTimeMillis) {
                TimeDetectorNetworkTimeHelper helper = mScheduledAsyncCallback;
                mScheduledAsyncCallback = null;
                mScheduledAsyncRunnableTimeMillis = -1;
                helper.delayedQueryAndInjectNetworkTime();
            }
        }

        void assertHasNetworkTimeChangeListener() {
            assertNotNull(mNetworkTimeUpdateListener);
        }

        void assertHasImmediateCallback() {
            assertNotNull(mImmediateAsyncCallback);
        }

        void assertHasNoImmediateCallback() {
            assertNull(mImmediateAsyncCallback);
        }

        void assertHasScheduledAsyncCallback(long expectedScheduledAsyncRunnableTimeMillis) {
            assertNotNull(mScheduledAsyncCallback);
            assertEquals(expectedScheduledAsyncRunnableTimeMillis,
                    mScheduledAsyncRunnableTimeMillis);
        }

        void assertHasNoScheduledAsyncCallback() {
            assertNull(mScheduledAsyncCallback);
        }
    }
}
