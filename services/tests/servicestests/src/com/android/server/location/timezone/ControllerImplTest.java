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
package com.android.server.location.timezone;

import static android.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_SUCCESS;
import static android.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN;

import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DISABLED;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED;
import static com.android.server.location.timezone.TestSupport.USER1_CONFIG_GEO_DETECTION_DISABLED;
import static com.android.server.location.timezone.TestSupport.USER1_CONFIG_GEO_DETECTION_ENABLED;
import static com.android.server.location.timezone.TestSupport.USER1_ID;
import static com.android.server.location.timezone.TestSupport.USER2_CONFIG_GEO_DETECTION_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static java.util.Arrays.asList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.location.timezone.LocationTimeZoneEvent;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.IndentingPrintWriter;

import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.TestState;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Tests for {@link ControllerImpl}. */
@Presubmit
public class ControllerImplTest {

    private static final long ARBITRARY_TIME = 12345L;

    private static final LocationTimeZoneEvent USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1 =
            createLocationTimeZoneEvent(USER1_ID, EVENT_TYPE_SUCCESS, asList("Europe/London"));
    private static final LocationTimeZoneEvent USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2 =
            createLocationTimeZoneEvent(USER1_ID, EVENT_TYPE_SUCCESS, asList("Europe/Paris"));
    private static final LocationTimeZoneEvent USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT =
            createLocationTimeZoneEvent(USER1_ID, EVENT_TYPE_UNCERTAIN, null);

    private TestThreadingDomain mTestThreadingDomain;
    private TestCallback mTestCallback;
    private TestLocationTimeZoneProvider mTestLocationTimeZoneProvider;

    @Before
    public void setUp() {
        // For simplicity, the TestThreadingDomain uses the test's main thread. To execute posted
        // runnables, the test must call methods on mTestThreadingDomain otherwise those runnables
        // will never get a chance to execute.
        mTestThreadingDomain = new TestThreadingDomain();
        mTestCallback = new TestCallback(mTestThreadingDomain);
        mTestLocationTimeZoneProvider =
                new TestLocationTimeZoneProvider(mTestThreadingDomain, "primary");
    }

    @Test
    public void initialState_enabled() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestLocationTimeZoneProvider.assertInitialized();

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);
        mTestCallback.assertNoSuggestionMade();
    }

    @Test
    public void initialState_disabled() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_DISABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestLocationTimeZoneProvider.assertInitialized();

        mTestLocationTimeZoneProvider.assertIsDisabled();
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertNoSuggestionMade();
    }

    @Test
    public void enabled_uncertaintySuggestionSentIfNoEventReceived() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        Duration expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);

        // Simulate time passing with no event being received.
        mTestThreadingDomain.executeNext();

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        mTestThreadingDomain.assertQueueEmpty();
    }

    @Test
    public void enabled_uncertaintySuggestionCancelledIfEventReceived() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);
        mTestCallback.assertNoSuggestionMade();

        // Simulate a location event being received by the provider. This should cause a suggestion
        // to be made, and the timeout to be cleared.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
    }

    @Test
    public void enabled_repeatedCertainty() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);
        mTestCallback.assertNoSuggestionMade();

        // Simulate a location event being received by the provider. This should cause a suggestion
        // to be made, and the timeout to be cleared.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());

        // A second, identical event should not cause another suggestion.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertNoSuggestionMade();

        // And a third, different event should cause another suggestion.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getTimeZoneIds());
    }

    @Test
    public void enabled_briefUncertaintyTriggersNoSuggestion() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);
        mTestCallback.assertNoSuggestionMade();

        // Simulate a location event being received by the provider. This should cause a suggestion
        // to be made, and the timeout to be cleared.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());

        // Uncertainty should cause a suggestion to (only) be queued.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertSingleDelayedQueueItem(testEnvironment.getUncertaintyDelay());
        mTestCallback.assertNoSuggestionMade();

        // And a third event should cause yet another suggestion and for the queued item to be
        // removed.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getTimeZoneIds());
    }

    @Test
    public void configChanges_enableAndDisable() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_DISABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestLocationTimeZoneProvider.assertIsDisabled();
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertNoSuggestionMade();

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);
        mTestCallback.assertNoSuggestionMade();

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestLocationTimeZoneProvider.assertIsDisabled();
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertNoSuggestionMade();
    }

    @Test
    public void configChanges_disableWithPreviousSuggestion() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);
        mTestCallback.assertNoSuggestionMade();

        // Simulate a location event being received by the provider. This should cause a suggestion
        // to be made, and the timeout to be cleared.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());

        // Simulate the user disabling the provider.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        // Because there had been a previous suggestion, the controller should withdraw it
        // immediately to let the downstream components know that the provider can no longer be sure
        // of the time zone.
        mTestLocationTimeZoneProvider.assertIsDisabled();
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertSuggestionMadeAndCommit(null);
    }

    @Test
    public void configChanges_userSwitch_enabledToEnabled() {
        ControllerImpl controllerImpl =
                new ControllerImpl(mTestThreadingDomain, mTestLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        controllerImpl.initialize(testEnvironment, mTestCallback);

        // There should be a runnable scheduled to suggest uncertainty if no event is received.
        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);
        mTestCallback.assertNoSuggestionMade();

        // Have the provider suggest a time zone.
        mTestLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        // Receiving a "success" provider event should cause a suggestion to be made synchronously,
        // and also clear the scheduled uncertainty suggestion.
        mTestLocationTimeZoneProvider.assertIsEnabled(USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());

        // Simulate the user change (but geo detection still enabled).
        testEnvironment.simulateConfigChange(USER2_CONFIG_GEO_DETECTION_ENABLED);

        // We expect the provider to end up in PROVIDER_STATE_ENABLED, but it should have been
        // disabled when the user changed.
        // The controller should schedule a runnable to make a suggestion if the provider doesn't
        // send a success event.
        int[] expectedStateTransitions = { PROVIDER_STATE_DISABLED, PROVIDER_STATE_ENABLED };
        mTestLocationTimeZoneProvider.assertStateChangesAndCommit(expectedStateTransitions);
        mTestLocationTimeZoneProvider.assertConfig(USER2_CONFIG_GEO_DETECTION_ENABLED);
        expectedTimeout = expectedProviderInitializationTimeout();
        mTestThreadingDomain.assertSingleDelayedQueueItem(expectedTimeout);
        mTestCallback.assertNoSuggestionMade();

        // Simulate no event being received, and time passing.
        mTestThreadingDomain.executeNext();

        mTestLocationTimeZoneProvider.assertIsEnabled(USER2_CONFIG_GEO_DETECTION_ENABLED);
        mTestThreadingDomain.assertQueueEmpty();
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
    }

    private static LocationTimeZoneEvent createLocationTimeZoneEvent(@UserIdInt int userId,
            int eventType, @Nullable List<String> timeZoneIds) {
        LocationTimeZoneEvent.Builder builder = new LocationTimeZoneEvent.Builder()
                .setElapsedRealtimeNanos(ARBITRARY_TIME)
                .setUserHandle(UserHandle.of(userId))
                .setEventType(eventType);
        if (timeZoneIds != null) {
            builder.setTimeZoneIds(timeZoneIds);
        }
        return builder.build();
    }


    private Duration expectedProviderInitializationTimeout() {
        return TestEnvironment.PROVIDER_INITIALIZATION_TIMEOUT
                .plus(TestEnvironment.PROVIDER_INITIALIZATION_TIMEOUT_FUZZ);
    }

    private static class TestEnvironment extends LocationTimeZoneProviderController.Environment {

        static final Duration PROVIDER_INITIALIZATION_TIMEOUT = Duration.ofMinutes(5);
        static final Duration PROVIDER_INITIALIZATION_TIMEOUT_FUZZ = Duration.ofMinutes(1);
        private static final Duration UNCERTAINTY_DELAY = Duration.ofMinutes(3);

        private final LocationTimeZoneProviderController mController;
        private ConfigurationInternal mConfigurationInternal;

        TestEnvironment(ThreadingDomain threadingDomain,
                LocationTimeZoneProviderController controller,
                ConfigurationInternal configurationInternal) {
            super(threadingDomain);
            mController = Objects.requireNonNull(controller);
            mConfigurationInternal = Objects.requireNonNull(configurationInternal);
        }

        @Override
        ConfigurationInternal getCurrentUserConfigurationInternal() {
            return mConfigurationInternal;
        }

        @Override
        Duration getProviderInitializationTimeout() {
            return PROVIDER_INITIALIZATION_TIMEOUT;
        }

        @Override
        Duration getProviderInitializationTimeoutFuzz() {
            return PROVIDER_INITIALIZATION_TIMEOUT_FUZZ;
        }

        @Override
        Duration getUncertaintyDelay() {
            return UNCERTAINTY_DELAY;
        }

        void simulateConfigChange(ConfigurationInternal newConfig) {
            ConfigurationInternal oldConfig = mConfigurationInternal;
            mConfigurationInternal = Objects.requireNonNull(newConfig);
            if (Objects.equals(oldConfig, newConfig)) {
                fail("Bad test? No config change when one was expected");
            }
            mController.onConfigChanged();
        }
    }

    private static class TestCallback extends LocationTimeZoneProviderController.Callback {

        private TestState<GeolocationTimeZoneSuggestion> mLatestSuggestion = new TestState<>();

        TestCallback(ThreadingDomain threadingDomain) {
            super(threadingDomain);
        }

        @Override
        void suggest(GeolocationTimeZoneSuggestion suggestion) {
            mLatestSuggestion.set(suggestion);
        }

        void assertSuggestionMadeAndCommit(@Nullable List<String> expectedZoneIds) {
            mLatestSuggestion.assertHasBeenSet();
            assertEquals(expectedZoneIds, mLatestSuggestion.getLatest().getZoneIds());
            mLatestSuggestion.commitLatest();
        }

        void assertNoSuggestionMade() {
            mLatestSuggestion.assertHasNotBeenSet();
        }

        void assertUncertainSuggestionMadeAndCommit() {
            // An "uncertain" suggestion has null time zone IDs.
            assertSuggestionMadeAndCommit(null);
        }
    }

    private static class TestLocationTimeZoneProvider extends LocationTimeZoneProvider {

        /** Used to track historic provider states for tests. */
        private final TestState<ProviderState> mTestProviderState = new TestState<>();
        private boolean mInitialized;

        /**
         * Creates the instance.
         */
        TestLocationTimeZoneProvider(ThreadingDomain threadingDomain, String providerName) {
            super(threadingDomain, providerName);
        }

        @Override
        void onInitialize() {
            mInitialized = true;
        }

        @Override
        void onSetCurrentState(ProviderState newState) {
            mTestProviderState.set(newState);
        }

        @Override
        void onEnable(Duration initializationTimeout) {
            // Nothing needed for tests.
        }

        @Override
        void onDisable() {
            // Nothing needed for tests.
        }

        @Override
        void logWarn(String msg) {
            System.out.println(msg);
        }

        @Override
        public void dump(IndentingPrintWriter pw, String[] args) {
            // Nothing needed for tests.
        }

        /** Asserts that {@link #initialize(ProviderListener)} has been called. */
        void assertInitialized() {
            assertTrue(mInitialized);
        }

        void assertIsDisabled() {
            // Disabled providers don't hold config.
            assertConfig(null);
            assertIsEnabledAndCommit(false);
        }

        /**
         * Asserts the provider's config matches the expected, and the current state is set
         * accordingly. Commits the latest changes to the state.
         */
        void assertIsEnabled(@NonNull ConfigurationInternal expectedConfig) {
            assertConfig(expectedConfig);

            boolean expectIsEnabled = expectedConfig.getAutoDetectionEnabledBehavior();
            assertIsEnabledAndCommit(expectIsEnabled);
        }

        private void assertIsEnabledAndCommit(boolean enabled) {
            ProviderState currentState = mCurrentState.get();
            if (enabled) {
                assertEquals(PROVIDER_STATE_ENABLED, currentState.stateEnum);
            } else {
                assertEquals(PROVIDER_STATE_DISABLED, currentState.stateEnum);
            }
            mTestProviderState.commitLatest();
        }

        void assertConfig(@NonNull ConfigurationInternal expectedConfig) {
            ProviderState currentState = mCurrentState.get();
            assertEquals(expectedConfig, currentState.currentUserConfiguration);
        }

        void simulateLocationTimeZoneEvent(@NonNull LocationTimeZoneEvent event) {
            handleLocationTimeZoneEvent(event);
        }

        /**
         * Asserts the most recent state changes. The ordering is such that the last element in the
         * provided array is expected to be the current state.
         */
        void assertStateChangesAndCommit(int... expectedProviderStates) {
            if (expectedProviderStates.length == 0) {
                mTestProviderState.assertHasNotBeenSet();
            } else {
                mTestProviderState.assertChangeCount(expectedProviderStates.length);

                List<ProviderState> previousProviderStates = new ArrayList<>();
                for (int i = 0; i < expectedProviderStates.length; i++) {
                    previousProviderStates.add(mTestProviderState.getPrevious(i));
                }
                // The loop above will produce a list with the most recent state in element 0. So,
                // reverse the list as the arguments to this method are expected to be in order
                // oldest...latest.
                Collections.reverse(previousProviderStates);

                boolean allMatch = true;
                for (int i = 0; i < expectedProviderStates.length; i++) {
                    allMatch = allMatch && expectedProviderStates[i]
                            == previousProviderStates.get(i).stateEnum;
                }
                if (!allMatch) {
                    fail("Provider state enums expected=" + Arrays.toString(expectedProviderStates)
                            + " but states were"
                            + " actually=" + previousProviderStates);
                }
            }
            mTestProviderState.commitLatest();
        }
    }
}
