/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.timezonedetector.location;

import static android.service.timezone.TimeZoneProviderStatus.DEPENDENCY_STATUS_OK;
import static android.service.timezone.TimeZoneProviderStatus.OPERATION_STATUS_OK;

import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;
import static com.android.server.timezonedetector.location.TestSupport.USER1_CONFIG_GEO_DETECTION_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import static java.util.Arrays.asList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.platform.test.annotations.Presubmit;
import android.service.timezone.TimeZoneProviderEvent;
import android.service.timezone.TimeZoneProviderStatus;
import android.service.timezone.TimeZoneProviderSuggestion;
import android.util.IndentingPrintWriter;

import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.TestState;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderListener;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests for {@link LocationTimeZoneProvider}.
 */
@Presubmit
public class LocationTimeZoneProviderTest {

    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 123456789L;
    private static final TimeZoneProviderStatus ARBITRARY_PROVIDER_STATUS =
            new TimeZoneProviderStatus.Builder()
                    .setConnectivityDependencyStatus(DEPENDENCY_STATUS_OK)
                    .setLocationDetectionDependencyStatus(DEPENDENCY_STATUS_OK)
                    .setTimeZoneResolutionOperationStatus(OPERATION_STATUS_OK)
                    .build();

    private TestThreadingDomain mTestThreadingDomain;
    private TestProviderListener mProviderListener;
    private FakeTimeZoneProviderEventPreProcessor mTimeZoneProviderEventPreProcessor;

    @Before
    public void setUp() {
        mTestThreadingDomain = new TestThreadingDomain();
        mProviderListener = new TestProviderListener();
        mTimeZoneProviderEventPreProcessor = new FakeTimeZoneProviderEventPreProcessor();
    }

    @Test
    public void lifecycle() {
        String providerName = "arbitrary";
        RecordingProviderMetricsLogger providerMetricsLogger = new RecordingProviderMetricsLogger();
        TestLocationTimeZoneProvider provider = new TestLocationTimeZoneProvider(
                providerMetricsLogger,
                mTestThreadingDomain,
                providerName,
                mTimeZoneProviderEventPreProcessor);

        // initialize()
        {
            provider.initialize(mProviderListener);
            provider.assertOnInitializeCalled();

            ProviderState currentState = assertAndReturnProviderState(
                    provider, providerMetricsLogger, PROVIDER_STATE_STOPPED,
                    /*expectedReportedStatus=*/null);
            assertNull(currentState.currentUserConfiguration);
            assertSame(provider, currentState.provider);
            mTestThreadingDomain.assertQueueEmpty();
        }

        ConfigurationInternal config = USER1_CONFIG_GEO_DETECTION_ENABLED;

        // startUpdates()
        {
            Duration arbitraryInitializationTimeout = Duration.ofMinutes(5);
            Duration arbitraryInitializationTimeoutFuzz = Duration.ofMinutes(2);
            Duration arbitraryEventFilteringAgeThreshold = Duration.ofMinutes(3);
            provider.startUpdates(config, arbitraryInitializationTimeout,
                    arbitraryInitializationTimeoutFuzz, arbitraryEventFilteringAgeThreshold);

            provider.assertOnStartCalled(
                    arbitraryInitializationTimeout, arbitraryEventFilteringAgeThreshold);

            ProviderState currentState = assertAndReturnProviderState(
                    provider, providerMetricsLogger, PROVIDER_STATE_STARTED_INITIALIZING,
                    /*expectedReportedStatus=*/null);
            assertSame(provider, currentState.provider);
            assertEquals(config, currentState.currentUserConfiguration);
            assertNull(currentState.event);
            // The initialization timeout should be queued.
            Duration expectedInitializationTimeout =
                    arbitraryInitializationTimeout.plus(arbitraryInitializationTimeoutFuzz);
            mTestThreadingDomain.assertSingleDelayedQueueItem(expectedInitializationTimeout);
            // We don't intend to trigger the timeout, so clear it.
            mTestThreadingDomain.removeAllQueuedRunnables();

            // Entering started does not trigger an onProviderStateChanged() as it is requested by
            // the controller.
            mProviderListener.assertProviderChangeNotReported();
        }

        // Simulate a suggestion event being received.
        {
            TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                    .setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                    .setTimeZoneIds(Arrays.asList("Europe/London"))
                    .build();
            TimeZoneProviderEvent event = TimeZoneProviderEvent.createSuggestionEvent(
                    ARBITRARY_ELAPSED_REALTIME_MILLIS, suggestion, ARBITRARY_PROVIDER_STATUS);
            provider.simulateProviderEventReceived(event);

            ProviderState currentState = assertAndReturnProviderState(
                    provider, providerMetricsLogger, PROVIDER_STATE_STARTED_CERTAIN,
                    ARBITRARY_PROVIDER_STATUS);
            assertSame(provider, currentState.provider);
            assertEquals(event, currentState.event);
            assertEquals(config, currentState.currentUserConfiguration);
            mTestThreadingDomain.assertQueueEmpty();
            mProviderListener.assertProviderChangeReported(PROVIDER_STATE_STARTED_CERTAIN);
        }

        // Simulate an uncertain event being received.
        {
            TimeZoneProviderEvent event = TimeZoneProviderEvent.createUncertainEvent(
                    ARBITRARY_ELAPSED_REALTIME_MILLIS, ARBITRARY_PROVIDER_STATUS);
            provider.simulateProviderEventReceived(event);

            ProviderState currentState = assertAndReturnProviderState(
                    provider, providerMetricsLogger, PROVIDER_STATE_STARTED_UNCERTAIN,
                    ARBITRARY_PROVIDER_STATUS);
            assertSame(provider, currentState.provider);
            assertEquals(event, currentState.event);
            assertEquals(config, currentState.currentUserConfiguration);
            mTestThreadingDomain.assertQueueEmpty();
            mProviderListener.assertProviderChangeReported(PROVIDER_STATE_STARTED_UNCERTAIN);
        }

        // stopUpdates()
        {
            provider.stopUpdates();
            provider.assertOnStopUpdatesCalled();

            ProviderState currentState = assertAndReturnProviderState(
                    provider, providerMetricsLogger, PROVIDER_STATE_STOPPED,
                    /*expectedReportedStatus=*/null);
            assertSame(provider, currentState.provider);
            assertEquals(PROVIDER_STATE_STOPPED, currentState.stateEnum);
            assertNull(currentState.event);
            assertNull(currentState.currentUserConfiguration);
            mTestThreadingDomain.assertQueueEmpty();
            // Entering stopped does not trigger an onProviderStateChanged() as it is requested by
            // the controller.
            mProviderListener.assertProviderChangeNotReported();
        }

        // destroy()
        {
            provider.destroy();
            provider.assertOnDestroyCalled();
        }
    }

    @Test
    public void stateRecording() {
        String providerName = "primary";
        StubbedProviderMetricsLogger providerMetricsLogger = new StubbedProviderMetricsLogger();
        TestLocationTimeZoneProvider provider = new TestLocationTimeZoneProvider(
                providerMetricsLogger,
                mTestThreadingDomain,
                providerName,
                mTimeZoneProviderEventPreProcessor);

        // initialize()
        provider.initialize(mProviderListener);
        provider.assertLatestRecordedState(PROVIDER_STATE_STOPPED);

        // startUpdates()
        ConfigurationInternal config = USER1_CONFIG_GEO_DETECTION_ENABLED;
        Duration arbitraryInitializationTimeout = Duration.ofMinutes(5);
        Duration arbitraryInitializationTimeoutFuzz = Duration.ofMinutes(2);
        Duration eventFilteringAgeThreshold = Duration.ofMinutes(3);
        provider.startUpdates(config, arbitraryInitializationTimeout,
                arbitraryInitializationTimeoutFuzz, eventFilteringAgeThreshold);
        provider.assertLatestRecordedState(PROVIDER_STATE_STARTED_INITIALIZING);

        // Simulate a suggestion event being received.
        TimeZoneProviderSuggestion suggestion = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .setTimeZoneIds(Arrays.asList("Europe/London"))
                .build();
        TimeZoneProviderEvent event = TimeZoneProviderEvent.createSuggestionEvent(
                ARBITRARY_ELAPSED_REALTIME_MILLIS, suggestion, null);
        provider.simulateProviderEventReceived(event);
        provider.assertLatestRecordedState(PROVIDER_STATE_STARTED_CERTAIN);

        // Simulate an uncertain event being received.
        event = TimeZoneProviderEvent.createUncertainEvent(ARBITRARY_ELAPSED_REALTIME_MILLIS, null);
        provider.simulateProviderEventReceived(event);
        provider.assertLatestRecordedState(PROVIDER_STATE_STARTED_UNCERTAIN);

        // stopUpdates()
        provider.stopUpdates();
        provider.assertLatestRecordedState(PROVIDER_STATE_STOPPED);

        // destroy()
        provider.destroy();
        provider.assertLatestRecordedState(PROVIDER_STATE_DESTROYED);
    }

    @Test
    public void entersUncertainState_whenEventHasUnsupportedZones() {
        String providerName = "primary";
        StubbedProviderMetricsLogger providerMetricsLogger = new StubbedProviderMetricsLogger();
        TestLocationTimeZoneProvider provider = new TestLocationTimeZoneProvider(
                providerMetricsLogger,
                mTestThreadingDomain,
                providerName,
                mTimeZoneProviderEventPreProcessor);
        provider.initialize(mProviderListener);
        mTimeZoneProviderEventPreProcessor.enterUncertainMode();

        ConfigurationInternal config = USER1_CONFIG_GEO_DETECTION_ENABLED;
        Duration arbitraryInitializationTimeout = Duration.ofMinutes(5);
        Duration arbitraryInitializationTimeoutFuzz = Duration.ofMinutes(2);
        Duration eventFilteringAgeThreshold = Duration.ofMinutes(3);
        provider.startUpdates(config, arbitraryInitializationTimeout,
                arbitraryInitializationTimeoutFuzz, eventFilteringAgeThreshold);

        List<String> invalidTimeZoneIds = asList("Atlantic/Atlantis");
        TimeZoneProviderSuggestion invalidIdSuggestion = new TimeZoneProviderSuggestion.Builder()
                .setElapsedRealtimeMillis(ARBITRARY_ELAPSED_REALTIME_MILLIS)
                .setTimeZoneIds(invalidTimeZoneIds)
                .build();
        TimeZoneProviderStatus providerStatus = null;
        TimeZoneProviderEvent event = TimeZoneProviderEvent.createSuggestionEvent(
                ARBITRARY_ELAPSED_REALTIME_MILLIS, invalidIdSuggestion, providerStatus);
        provider.simulateProviderEventReceived(event);
        provider.assertLatestRecordedState(PROVIDER_STATE_STARTED_UNCERTAIN);
    }

    /** A test stand-in for the real {@link LocationTimeZoneProviderController}'s listener. */
    private static class TestProviderListener implements ProviderListener {

        private final TestState<ProviderState> mReportedProviderStateChanges = new TestState<>();

        @Override
        public void onProviderStateChange(ProviderState providerState) {
            mReportedProviderStateChanges.set(providerState);
        }

        void assertProviderChangeReported(int expectedStateEnum) {
            mReportedProviderStateChanges.assertChangeCount(1);

            ProviderState latest = mReportedProviderStateChanges.getLatest();
            assertEquals(expectedStateEnum, latest.stateEnum);
            mReportedProviderStateChanges.commitLatest();
        }

        public void assertProviderChangeNotReported() {
            mReportedProviderStateChanges.assertHasNotBeenSet();
        }
    }

    /**
     * Returns the provider's state after asserting that the current state matches what is expected.
     * This also asserts that the metrics logger was informed of the state change.
     */
    private static ProviderState assertAndReturnProviderState(
            TestLocationTimeZoneProvider provider,
            RecordingProviderMetricsLogger providerMetricsLogger, int expectedStateEnum,
            TimeZoneProviderStatus expectedReportedStatus) {
        ProviderState currentState = provider.getCurrentState();
        assertEquals(expectedStateEnum, currentState.stateEnum);
        assertEquals(expectedReportedStatus, currentState.getReportedStatus());
        providerMetricsLogger.assertChangeLoggedAndRemove(expectedStateEnum);
        providerMetricsLogger.assertNoMoreLogEntries();
        return currentState;
    }

    private static class TestLocationTimeZoneProvider extends LocationTimeZoneProvider {

        private boolean mOnInitializeCalled;
        private boolean mOnDestroyCalled;
        private boolean mOnStartUpdatesCalled;
        private Duration mInitializationTimeout;
        private Duration mEventFilteringAgeThreshold;
        private boolean mOnStopUpdatesCalled;

        /** Creates the instance. */
        TestLocationTimeZoneProvider(@NonNull ProviderMetricsLogger providerMetricsLogger,
                @NonNull ThreadingDomain threadingDomain,
                @NonNull String providerName,
                @NonNull TimeZoneProviderEventPreProcessor timeZoneProviderEventPreProcessor) {
            super(providerMetricsLogger, threadingDomain, providerName,
                    timeZoneProviderEventPreProcessor,
                    true /* recordStateChanges */);
        }

        @Override
        boolean onInitialize() {
            mOnInitializeCalled = true;
            return true;
        }

        @Override
        void onDestroy() {
            mOnDestroyCalled = true;
        }

        @Override
        void onStartUpdates(@NonNull Duration initializationTimeout,
                @NonNull Duration eventFilteringAgeThreshold) {
            mOnStartUpdatesCalled = true;
            mInitializationTimeout = initializationTimeout;
            mEventFilteringAgeThreshold = eventFilteringAgeThreshold;
        }

        @Override
        void onStopUpdates() {
            mOnStopUpdatesCalled = true;
        }

        @Override
        public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
            // No-op for tests
        }

        void assertOnInitializeCalled() {
            assertTrue(mOnInitializeCalled);
        }

        void assertOnStartCalled(Duration expectedInitializationTimeout,
                Duration eventFilteringAgeThreshold) {
            assertTrue(mOnStartUpdatesCalled);
            assertEquals(expectedInitializationTimeout, mInitializationTimeout);
            assertEquals(eventFilteringAgeThreshold, mEventFilteringAgeThreshold);
        }

        void simulateProviderEventReceived(TimeZoneProviderEvent event) {
            handleTimeZoneProviderEvent(event);
        }

        void assertOnStopUpdatesCalled() {
            assertTrue(mOnStopUpdatesCalled);
        }

        void assertOnDestroyCalled() {
            assertTrue(mOnDestroyCalled);
        }

        void assertLatestRecordedState(@ProviderState.ProviderStateEnum int expectedStateEnum) {
            List<ProviderState> recordedStates = getRecordedStates();
            assertEquals(expectedStateEnum,
                    recordedStates.get(recordedStates.size() - 1).stateEnum);
        }
    }

    private static class StubbedProviderMetricsLogger implements
            LocationTimeZoneProvider.ProviderMetricsLogger {

        @Override
        public void onProviderStateChanged(int stateEnum) {
            // Stubbed
        }
    }

    private static class RecordingProviderMetricsLogger implements
            LocationTimeZoneProvider.ProviderMetricsLogger {

        private LinkedList<Integer> mStates = new LinkedList<>();

        @Override
        public void onProviderStateChanged(int stateEnum) {
            mStates.add(stateEnum);
        }

        public void assertChangeLoggedAndRemove(int expectedLoggedState) {
            assertEquals("expected loggedState=" + expectedLoggedState
                    + " but states logged were=" + mStates,
                    (Integer) expectedLoggedState, mStates.peekFirst());
            mStates.removeFirst();
        }

        public void assertNoMoreLogEntries() {
            assertTrue(mStates.isEmpty());
        }
    }
}
