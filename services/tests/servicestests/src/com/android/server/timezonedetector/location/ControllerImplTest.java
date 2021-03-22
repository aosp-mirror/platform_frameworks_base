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

import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;
import static com.android.server.timezonedetector.location.TestSupport.USER1_CONFIG_GEO_DETECTION_DISABLED;
import static com.android.server.timezonedetector.location.TestSupport.USER1_CONFIG_GEO_DETECTION_ENABLED;
import static com.android.server.timezonedetector.location.TestSupport.USER2_CONFIG_GEO_DETECTION_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static java.util.Arrays.asList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.platform.test.annotations.Presubmit;
import android.service.timezone.TimeZoneProviderSuggestion;
import android.util.IndentingPrintWriter;

import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.TestState;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.ProviderStateEnum;

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

    private static final long ARBITRARY_TIME_MILLIS = 12345L;

    private static final TimeZoneProviderEvent USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1 =
            createSuggestionEvent(asList("Europe/London"));
    private static final TimeZoneProviderEvent USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2 =
            createSuggestionEvent(asList("Europe/Paris"));
    private static final TimeZoneProviderEvent USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT =
            TimeZoneProviderEvent.createUncertainEvent();
    private static final TimeZoneProviderEvent USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT =
            TimeZoneProviderEvent.createPermanentFailureEvent("Test");

    private TestThreadingDomain mTestThreadingDomain;
    private TestCallback mTestCallback;
    private TestLocationTimeZoneProvider mTestPrimaryLocationTimeZoneProvider;
    private TestLocationTimeZoneProvider mTestSecondaryLocationTimeZoneProvider;

    @Before
    public void setUp() {
        // For simplicity, the TestThreadingDomain uses the test's main thread. To execute posted
        // runnables, the test must call methods on mTestThreadingDomain otherwise those runnables
        // will never get a chance to execute.
        LocationTimeZoneProvider.ProviderMetricsLogger stubbedProviderMetricsLogger = stateEnum -> {
            // Stubbed.
        };
        mTestThreadingDomain = new TestThreadingDomain();
        mTestCallback = new TestCallback(mTestThreadingDomain);
        mTestPrimaryLocationTimeZoneProvider = new TestLocationTimeZoneProvider(
                stubbedProviderMetricsLogger, mTestThreadingDomain, "primary");
        mTestSecondaryLocationTimeZoneProvider = new TestLocationTimeZoneProvider(
                stubbedProviderMetricsLogger, mTestThreadingDomain, "secondary");
    }

    @Test
    public void initializationFailure_primary() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedInitTimeout = testEnvironment.getProviderInitializationTimeout()
                .plus(testEnvironment.getProviderInitializationTimeoutFuzz());

        mTestPrimaryLocationTimeZoneProvider.setFailDuringInitialization(true);

        // Initialize. After initialization the providers must be initialized and one should be
        // started.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertInitializationTimeoutSet(expectedInitTimeout);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void initializationFailure_secondary() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedInitTimeout = testEnvironment.getProviderInitializationTimeout()
                .plus(testEnvironment.getProviderInitializationTimeoutFuzz());

        mTestSecondaryLocationTimeZoneProvider.setFailDuringInitialization(true);

        // Initialize. After initialization the providers must be initialized and one should be
        // started.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestPrimaryLocationTimeZoneProvider.assertInitializationTimeoutSet(expectedInitTimeout);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void initializationFailure_both() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.setFailDuringInitialization(true);
        mTestSecondaryLocationTimeZoneProvider.setFailDuringInitialization(true);

        // Initialize. After initialization the providers must be initialized and one should be
        // started.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void initialState_started() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedInitTimeout = testEnvironment.getProviderInitializationTimeout()
                .plus(testEnvironment.getProviderInitializationTimeoutFuzz());

        // Initialize. After initialization the providers must be initialized and one should be
        // started.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestPrimaryLocationTimeZoneProvider.assertInitializationTimeoutSet(expectedInitTimeout);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void initialState_disabled() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_DISABLED);

        // Initialize. After initialization the providers must be initialized but neither should be
        // started.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_uncertaintySuggestionSentIfNoEventReceived() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        // The primary should have reported uncertainty, which should trigger the controller to
        // start the uncertainty timeout and start the secondary.
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate time passing with no provider event being received from either the primary or
        // secondary.
        mTestThreadingDomain.executeNext();

        // Now both initialization timeouts should have triggered. The uncertainty timeout should
        // still not be triggered.
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Finally, the uncertainty timeout should cause the controller to make an uncertain
        // suggestion.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_eventReceivedBeforeInitializationTimeout() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_eventReceivedFromPrimaryAfterInitializationTimeout() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made and the secondary to be shut down.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_eventReceivedFromSecondaryAfterInitializationTimeout() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_repeatedPrimaryCertainty() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // A second, identical event should not cause another suggestion.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // And a third, different event should cause another suggestion.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_repeatedSecondaryCertainty() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // A second, identical event should not cause another suggestion.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // And a third, different event should cause another suggestion.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_uncertaintyTriggersASuggestionAfterUncertaintyTimeout() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made and ensure the primary is considered initialized.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate an uncertain event being received from the primary provider. This should not
        // cause a suggestion to be made straight away, but the uncertainty timeout should be
        // started and the secondary should be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made, cancel the uncertainty timeout and ensure the secondary is
        // considered initialized.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate an uncertain event being received from the secondary provider. This should not
        // cause a suggestion to be made straight away, but the uncertainty timeout should be
        // started. Both providers are now started, with no initialization timeout set.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate time passing. This means the uncertainty timeout should fire and the uncertain
        // suggestion should be made.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_briefUncertaintyTriggersNoSuggestion() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Uncertainty should not cause a suggestion to be made straight away, but the uncertainty
        // timeout should be started and the secondary should be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // And a success event from the primary provider should cause the controller to make another
        // suggestion, the uncertainty timeout should be cancelled and the secondary should be
        // stopped again.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void configChanges_enableAndDisableWithNoPreviousSuggestion() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_DISABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void configChanges_enableAndDisableWithPreviousSuggestion() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_DISABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a success event being received from the primary provider.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        // Because there had been a previous suggestion, the controller should withdraw it
        // immediately to let the downstream components know that the provider can no longer be sure
        // of the time zone.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void configChanges_userSwitch_enabledToEnabled() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate the primary provider suggesting a time zone.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        // Receiving a "success" provider event should cause a suggestion to be made synchronously,
        // and also clear the scheduled uncertainty suggestion.
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate the user change (but geo detection still enabled).
        testEnvironment.simulateConfigChange(USER2_CONFIG_GEO_DETECTION_ENABLED);

        // We expect the provider to end up in PROVIDER_STATE_STARTED_INITIALIZING, but it should
        // have been stopped when the user changed.
        int[] expectedStateTransitions =
                { PROVIDER_STATE_STOPPED, PROVIDER_STATE_STARTED_INITIALIZING };
        mTestPrimaryLocationTimeZoneProvider.assertStateChangesAndCommit(expectedStateTransitions);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfig(
                PROVIDER_STATE_STARTED_INITIALIZING, USER2_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void primaryPermFailure_secondaryEventsReceived() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a failure location event being received from the primary provider. This should
        // cause the secondary to be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // And a success event from the secondary provider should cause the controller to make
        // another suggestion, the uncertainty timeout should be cancelled.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);
    }

    @Test
    public void primaryPermFailure_disableAndEnable() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a failure location event being received from the primary provider. This should
        // cause the secondary to be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void secondaryPermFailure_primaryEventsReceived() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate an uncertain event from the primary. This will start the secondary, which will
        // give this test the opportunity to simulate its failure. Then it will be possible to
        // demonstrate controller behavior with only the primary working.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate failure event from the secondary. This should just affect the secondary's state.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // And a success event from the primary provider should cause the controller to make
        // a suggestion, the uncertainty timeout should be cancelled.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getSuggestion().getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the primary. The secondary cannot be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);
    }

    @Test
    public void secondaryPermFailure_disableAndEnable() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate an uncertain event from the primary. This will start the secondary, which will
        // give this test the opportunity to simulate its failure. Then it will be possible to
        // demonstrate controller behavior with only the primary working.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate failure event from the secondary. This should just affect the secondary's state.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled. Only the primary can be
        // started.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void bothPermFailure_disableAndEnable() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a failure event from the primary. This will start the secondary.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate failure event from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    @Test
    public void stateRecording() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        {
            LocationTimeZoneManagerServiceState state = controllerImpl.getStateForTests();
            assertNull(state.getLastSuggestion());
            assertTrue(state.getPrimaryProviderStates().isEmpty());
            assertTrue(state.getSecondaryProviderStates().isEmpty());
        }

        // State recording and simulate some provider behavior that will show up in the state
        // recording.
        controllerImpl.setProviderStateRecordingEnabled(true);

        // Simulate an uncertain event from the primary. This will start the secondary.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        {
            LocationTimeZoneManagerServiceState state = controllerImpl.getStateForTests();
            assertNull(state.getLastSuggestion());
            List<LocationTimeZoneProvider.ProviderState> primaryProviderStates =
                    state.getPrimaryProviderStates();
            assertEquals(1, primaryProviderStates.size());
            assertEquals(PROVIDER_STATE_STARTED_UNCERTAIN,
                    primaryProviderStates.get(0).stateEnum);
            List<LocationTimeZoneProvider.ProviderState> secondaryProviderStates =
                    state.getSecondaryProviderStates();
            assertEquals(1, secondaryProviderStates.size());
            assertEquals(PROVIDER_STATE_STARTED_INITIALIZING,
                    secondaryProviderStates.get(0).stateEnum);
        }

        // Simulate an uncertain event from the primary. This will start the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        {
            LocationTimeZoneManagerServiceState state = controllerImpl.getStateForTests();
            assertEquals(USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds(),
                    state.getLastSuggestion().getZoneIds());
            List<LocationTimeZoneProvider.ProviderState> primaryProviderStates =
                    state.getPrimaryProviderStates();
            assertEquals(1, primaryProviderStates.size());
            assertEquals(PROVIDER_STATE_STARTED_UNCERTAIN, primaryProviderStates.get(0).stateEnum);
            List<LocationTimeZoneProvider.ProviderState> secondaryProviderStates =
                    state.getSecondaryProviderStates();
            assertEquals(2, secondaryProviderStates.size());
            assertEquals(PROVIDER_STATE_STARTED_CERTAIN, secondaryProviderStates.get(1).stateEnum);
        }

        controllerImpl.setProviderStateRecordingEnabled(false);
        {
            LocationTimeZoneManagerServiceState state = controllerImpl.getStateForTests();
            assertEquals(USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds(),
                    state.getLastSuggestion().getZoneIds());
            assertTrue(state.getPrimaryProviderStates().isEmpty());
            assertTrue(state.getSecondaryProviderStates().isEmpty());
        }
    }

    private static void assertUncertaintyTimeoutSet(
            LocationTimeZoneProviderController.Environment environment,
            LocationTimeZoneProviderController controller) {
        assertTrue(controller.isUncertaintyTimeoutSet());
        assertEquals(environment.getUncertaintyDelay().toMillis(),
                controller.getUncertaintyTimeoutDelayMillis());
    }

    private static TimeZoneProviderEvent createSuggestionEvent(@NonNull List<String> timeZoneIds) {
        return TimeZoneProviderEvent.createSuggestionEvent(
                new TimeZoneProviderSuggestion.Builder()
                        .setElapsedRealtimeMillis(ARBITRARY_TIME_MILLIS)
                        .setTimeZoneIds(timeZoneIds)
                        .build());
    }

    private static class TestEnvironment extends LocationTimeZoneProviderController.Environment {

        // These timeouts are set deliberately so that:
        // (initialization timeout * 2) < uncertainty delay
        //
        // That makes the order of initialization timeout Vs uncertainty delay deterministic.
        static final Duration PROVIDER_INITIALIZATION_TIMEOUT = Duration.ofMinutes(5);
        static final Duration PROVIDER_INITIALIZATION_TIMEOUT_FUZZ = Duration.ofMinutes(1);
        private static final Duration UNCERTAINTY_DELAY = Duration.ofMinutes(15);

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
        void destroy() {
            // No-op test impl.
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
        private boolean mFailDuringInitialization;
        private boolean mInitialized;
        private boolean mDestroyed;

        /**
         * Creates the instance.
         */
        TestLocationTimeZoneProvider(ProviderMetricsLogger providerMetricsLogger,
                ThreadingDomain threadingDomain, String providerName) {
            super(providerMetricsLogger, threadingDomain, providerName,
                    new FakeTimeZoneProviderEventPreProcessor());
        }

        public void setFailDuringInitialization(boolean failInitialization) {
            mFailDuringInitialization = failInitialization;
        }

        @Override
        void onInitialize() {
            mInitialized = true;
            if (mFailDuringInitialization) {
                throw new RuntimeException("Simulated initialization failure");
            }
        }

        @Override
        void onDestroy() {
            mDestroyed = true;
        }

        @Override
        void onSetCurrentState(ProviderState newState) {
            mTestProviderState.set(newState);
        }

        @Override
        void onStartUpdates(Duration initializationTimeout) {
            // Nothing needed for tests.
        }

        @Override
        void onStopUpdates() {
            // Nothing needed for tests.
        }

        @Override
        public void dump(IndentingPrintWriter pw, String[] args) {
            // Nothing needed for tests.
        }

        /** Asserts that {@link #initialize(ProviderListener)} has been called. */
        void assertInitialized() {
            assertTrue(mInitialized);
        }

        public void assertIsPermFailedAndCommit() {
            // A failed provider doesn't hold config.
            assertStateEnumAndConfig(PROVIDER_STATE_PERM_FAILED, null /* config */);
            mTestProviderState.commitLatest();
        }

        void assertIsStoppedAndCommit() {
            // A stopped provider doesn't hold config.
            assertStateEnumAndConfig(PROVIDER_STATE_STOPPED, null /* config */);
            mTestProviderState.commitLatest();
        }

        /**
         * Asserts the provider's state enum and config matches the expected.
         * Commits the latest changes to the state.
         */
        void assertStateEnumAndConfigAndCommit(
                @ProviderStateEnum int expectedStateEnum,
                @Nullable ConfigurationInternal expectedConfig) {
            assertStateEnumAndConfig(expectedStateEnum, expectedConfig);
            mTestProviderState.commitLatest();
        }

        /**
         * Asserts the provider's state enum and config matches the expected.
         * Does not commit any state changes.
         */
        void assertStateEnumAndConfig(
                @ProviderStateEnum int expectedStateEnum,
                @Nullable ConfigurationInternal expectedConfig) {
            ProviderState currentState = mCurrentState.get();
            assertEquals(expectedStateEnum, currentState.stateEnum);

            // If and only if the controller is initializing, the initialization timeout must be
            // set.
            assertEquals(expectedStateEnum == PROVIDER_STATE_STARTED_INITIALIZING,
                    isInitializationTimeoutSet());

            assertConfig(expectedConfig);
        }

        private void assertConfig(@Nullable ConfigurationInternal expectedConfig) {
            ProviderState currentState = mCurrentState.get();
            assertEquals(expectedConfig, currentState.currentUserConfiguration);
        }

        void assertInitializationTimeoutSet(Duration expectedTimeout) {
            assertTrue(isInitializationTimeoutSet());
            assertEquals(expectedTimeout, getInitializationTimeoutDelay());
        }

        void simulateTimeZoneProviderEvent(@NonNull TimeZoneProviderEvent event) {
            handleTimeZoneProviderEvent(event);
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
