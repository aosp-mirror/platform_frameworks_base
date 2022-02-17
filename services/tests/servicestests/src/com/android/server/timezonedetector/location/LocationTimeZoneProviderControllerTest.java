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

import static com.android.server.timezonedetector.ConfigurationInternal.DETECTION_MODE_MANUAL;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_PROVIDERS_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_STOPPED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_UNKNOWN;
import static com.android.server.timezonedetector.location.TestSupport.USER1_CONFIG_GEO_DETECTION_DISABLED;
import static com.android.server.timezonedetector.location.TestSupport.USER1_CONFIG_GEO_DETECTION_ENABLED;
import static com.android.server.timezonedetector.location.TestSupport.USER2_CONFIG_GEO_DETECTION_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static java.util.Arrays.asList;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.service.timezone.TimeZoneProviderEvent;
import android.service.timezone.TimeZoneProviderSuggestion;
import android.util.IndentingPrintWriter;

import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.GeolocationTimeZoneSuggestion;
import com.android.server.timezonedetector.TestState;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderMetricsLogger;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.ProviderStateEnum;
import com.android.server.timezonedetector.location.LocationTimeZoneProviderController.State;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Tests for {@link LocationTimeZoneProviderController}. */
@Presubmit
public class LocationTimeZoneProviderControllerTest {

    private static final long ARBITRARY_TIME_MILLIS = 12345L;

    private static final TimeZoneProviderEvent USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1 =
            createSuggestionEvent(asList("Europe/London"));
    private static final TimeZoneProviderEvent USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2 =
            createSuggestionEvent(asList("Europe/Paris"));
    private static final TimeZoneProviderEvent USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT =
            TimeZoneProviderEvent.createUncertainEvent(ARBITRARY_TIME_MILLIS);
    private static final TimeZoneProviderEvent USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT =
            TimeZoneProviderEvent.createPermanentFailureEvent(ARBITRARY_TIME_MILLIS, "Test");

    private TestThreadingDomain mTestThreadingDomain;
    private TestMetricsLogger mTestMetricsLogger;
    private TestCallback mTestCallback;
    private TestLocationTimeZoneProvider mTestPrimaryLocationTimeZoneProvider;
    private TestLocationTimeZoneProvider mTestSecondaryLocationTimeZoneProvider;

    @Before
    public void setUp() {
        // For simplicity, the TestThreadingDomain uses the test's main thread. To execute posted
        // runnables, the test must call methods on mTestThreadingDomain otherwise those runnables
        // will never get a chance to execute.
        mTestThreadingDomain = new TestThreadingDomain();
        mTestMetricsLogger = new TestMetricsLogger();

        mTestCallback = new TestCallback(mTestThreadingDomain);

        ProviderMetricsLogger stubbedProviderMetricsLogger = stateEnum -> {};
        mTestPrimaryLocationTimeZoneProvider = new TestLocationTimeZoneProvider(
                stubbedProviderMetricsLogger, mTestThreadingDomain, "primary");
        mTestSecondaryLocationTimeZoneProvider = new TestLocationTimeZoneProvider(
                stubbedProviderMetricsLogger, mTestThreadingDomain, "secondary");
    }

    @Test
    public void controllerStartsInUnknownState() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        assertControllerState(controller, STATE_UNKNOWN);
    }

    @Test
    public void initializationFailure_primary() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedInitTimeout = testEnvironment.getProviderInitializationTimeout()
                .plus(testEnvironment.getProviderInitializationTimeoutFuzz());

        mTestPrimaryLocationTimeZoneProvider.setFailDuringInitialization(true);

        // Initialize. After initialization the providers must be initialized and one should be
        // started.
        controller.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertInitializationTimeoutSet(expectedInitTimeout);
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void initializationFailure_secondary() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedInitTimeout = testEnvironment.getProviderInitializationTimeout()
                .plus(testEnvironment.getProviderInitializationTimeoutFuzz());

        mTestSecondaryLocationTimeZoneProvider.setFailDuringInitialization(true);

        // Initialize. After initialization the providers must be initialized and one should be
        // started.
        controller.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestPrimaryLocationTimeZoneProvider.assertInitializationTimeoutSet(expectedInitTimeout);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void initializationFailure_both() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.setFailDuringInitialization(true);
        mTestSecondaryLocationTimeZoneProvider.setFailDuringInitialization(true);

        // Initialize. After initialization the providers must be initialized and one should be
        // started.
        controller.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        assertControllerState(controller, STATE_FAILED);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING, STATE_FAILED);
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void initialState_started() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedInitTimeout = testEnvironment.getProviderInitializationTimeout()
                .plus(testEnvironment.getProviderInitializationTimeoutFuzz());

        // Initialize. After initialization the providers must be initialized and one should be
        // started.
        controller.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestPrimaryLocationTimeZoneProvider.assertInitializationTimeoutSet(expectedInitTimeout);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void initialState_disabled() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_DISABLED);

        // Initialize. After initialization the providers must be initialized but neither should be
        // started.
        controller.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        assertControllerState(controller, STATE_STOPPED);
        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_PROVIDERS_INITIALIZING, STATE_STOPPED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_uncertaintySuggestionSentIfNoEventReceived() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        assertControllerState(controller, STATE_INITIALIZING);
        // The primary should have reported uncertainty, which should trigger the controller to
        // start the uncertainty timeout and start the secondary.
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Simulate time passing with no provider event being received from either the primary or
        // secondary.
        mTestThreadingDomain.executeNext();

        assertControllerState(controller, STATE_INITIALIZING);
        // Now both initialization timeouts should have triggered. The uncertainty timeout should
        // still not be triggered.
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Finally, the uncertainty timeout should cause the controller to make an uncertain
        // suggestion.
        mTestThreadingDomain.executeNext();

        assertControllerState(controller, STATE_UNCERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_UNCERTAIN);
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_eventReceivedBeforeInitializationTimeout() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_eventReceivedFromPrimaryAfterInitializationTimeout() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made and the secondary to be shut down.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_eventReceivedFromSecondaryAfterInitializationTimeout() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_repeatedPrimaryCertainty() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // A second, identical event should not cause another suggestion.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // And a third, different event should cause another suggestion.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_repeatedSecondaryCertainty() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // A second, identical event should not cause another suggestion.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // And a third, different event should cause another suggestion.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_uncertaintyTriggersASuggestionAfterUncertaintyTimeout() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made and ensure the primary is considered initialized.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate an uncertain event being received from the primary provider. This should not
        // cause a suggestion to be made straight away, but the uncertainty timeout should be
        // started and the secondary should be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made, cancel the uncertainty timeout and ensure the secondary is
        // considered initialized.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate an uncertain event being received from the secondary provider. This should not
        // cause a suggestion to be made straight away, but the uncertainty timeout should be
        // started. Both providers are now started, with no initialization timeout set.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Simulate time passing. This means the uncertainty timeout should fire and the uncertain
        // suggestion should be made.
        mTestThreadingDomain.executeNext();

        assertControllerState(controller, STATE_UNCERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_UNCERTAIN);
        mTestCallback.assertUncertainSuggestionMadeFromEventAndCommit(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void enabled_briefUncertaintyTriggersNoSuggestion() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Uncertainty should not cause a suggestion to be made straight away, but the uncertainty
        // timeout should be started and the secondary should be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // And a success event from the primary provider should cause the controller to make another
        // suggestion, the uncertainty timeout should be cancelled and the secondary should be
        // stopped again.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void configChanges_enableAndDisableWithNoPreviousSuggestion() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_DISABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_STOPPED);
        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_PROVIDERS_INITIALIZING, STATE_STOPPED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        assertControllerState(controller, STATE_STOPPED);
        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_STOPPED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void configChanges_enableAndDisableWithPreviousSuggestion() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_DISABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_STOPPED);
        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_PROVIDERS_INITIALIZING, STATE_STOPPED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate a success event being received from the primary provider.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        // Because there had been a previous suggestion, the controller should withdraw it
        // immediately to let the downstream components know that the provider can no longer be sure
        // of the time zone.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        assertControllerState(controller, STATE_STOPPED);
        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_UNCERTAIN, STATE_STOPPED);
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void configChanges_userSwitch_enabledToEnabled() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate the primary provider suggesting a time zone.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        // Receiving a "success" provider event should cause a suggestion to be made synchronously,
        // and also clear the scheduled uncertainty suggestion.
        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate the user change (but geo detection still enabled).
        testEnvironment.simulateConfigChange(USER2_CONFIG_GEO_DETECTION_ENABLED);

        // Confirm that the previous suggestion was overridden.
        assertControllerState(controller, STATE_INITIALIZING);

        // We expect the provider to end up in PROVIDER_STATE_STARTED_INITIALIZING, but it should
        // have been stopped when the user changed.
        int[] expectedStateTransitions =
                { PROVIDER_STATE_STOPPED, PROVIDER_STATE_STARTED_INITIALIZING };
        mTestPrimaryLocationTimeZoneProvider.assertStateChangesAndCommit(expectedStateTransitions);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfig(
                PROVIDER_STATE_STARTED_INITIALIZING, USER2_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_UNCERTAIN, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void primaryPermFailure_secondaryEventsReceived() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate a failure location event being received from the primary provider. This should
        // cause the secondary to be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // And a success event from the secondary provider should cause the controller to make
        // another suggestion, the uncertainty timeout should be cancelled.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);
    }

    @Test
    public void primaryPermFailure_disableAndEnable() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate a failure location event being received from the primary provider. This should
        // cause the secondary to be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        assertControllerState(controller, STATE_STOPPED);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_STOPPED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void secondaryPermFailure_primaryEventsReceived() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate an uncertain event from the primary. This will start the secondary, which will
        // give this test the opportunity to simulate its failure. Then it will be possible to
        // demonstrate controller behavior with only the primary working.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Simulate failure event from the secondary. This should just affect the secondary's state.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // And a success event from the primary provider should cause the controller to make
        // a suggestion, the uncertainty timeout should be cancelled.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the primary. The secondary cannot be started.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);
    }

    @Test
    public void secondaryPermFailure_disableAndEnable() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate an uncertain event from the primary. This will start the secondary, which will
        // give this test the opportunity to simulate its failure. Then it will be possible to
        // demonstrate controller behavior with only the primary working.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Simulate failure event from the secondary. This should just affect the secondary's state.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controller);

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        assertControllerState(controller, STATE_STOPPED);
        mTestPrimaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_STOPPED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled. Only the primary can be
        // started.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void bothPermFailure_disableAndEnable() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate a failure event from the primary. This will start the secondary.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestMetricsLogger.assertStateChangesAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate failure event from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        assertControllerState(controller, STATE_FAILED);
        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_FAILED);
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    @Test
    public void stateRecording() {
        // The test provider enables state recording by default.
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, true /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial states.
        controller.initialize(testEnvironment, mTestCallback);

        {
            LocationTimeZoneManagerServiceState state = controller.getStateForTests();
            assertEquals(STATE_INITIALIZING, state.getControllerState());
            assertNull(state.getLastSuggestion());
            assertControllerRecordedStates(state,
                    STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
            assertProviderStates(state.getPrimaryProviderStates(),
                    PROVIDER_STATE_STOPPED, PROVIDER_STATE_STARTED_INITIALIZING);
            assertProviderStates(state.getSecondaryProviderStates(), PROVIDER_STATE_STOPPED);
        }
        controller.clearRecordedStates();

        // Simulate some provider behavior that will show up in the state recording.

        // Simulate an uncertain event from the primary. This will start the secondary.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        {
            LocationTimeZoneManagerServiceState state = controller.getStateForTests();
            assertEquals(STATE_INITIALIZING, state.getControllerState());
            assertNull(state.getLastSuggestion());
            assertControllerRecordedStates(state);
            assertProviderStates(
                    state.getPrimaryProviderStates(), PROVIDER_STATE_STARTED_UNCERTAIN);
            assertProviderStates(
                    state.getSecondaryProviderStates(), PROVIDER_STATE_STARTED_INITIALIZING);
        }
        controller.clearRecordedStates();

        // Simulate a certain event from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        {
            LocationTimeZoneManagerServiceState state = controller.getStateForTests();
            assertEquals(STATE_CERTAIN, state.getControllerState());
            assertEquals(USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds(),
                    state.getLastSuggestion().getZoneIds());
            assertControllerRecordedStates(state, STATE_CERTAIN);
            assertProviderStates(state.getPrimaryProviderStates());
            assertProviderStates(
                    state.getSecondaryProviderStates(), PROVIDER_STATE_STARTED_CERTAIN);
        }

        controller.clearRecordedStates();
        {
            LocationTimeZoneManagerServiceState state = controller.getStateForTests();
            assertEquals(STATE_CERTAIN, state.getControllerState());
            assertEquals(USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getSuggestion().getTimeZoneIds(),
                    state.getLastSuggestion().getZoneIds());
            assertControllerRecordedStates(state);
            assertProviderStates(state.getPrimaryProviderStates());
            assertProviderStates(state.getSecondaryProviderStates());
        }
    }

    private static void assertProviderStates(
            List<LocationTimeZoneProvider.ProviderState> providerStates,
            int... expectedStates) {
        assertEquals(expectedStates.length, providerStates.size());
        for (int i = 0; i < expectedStates.length; i++) {
            assertEquals(expectedStates[i], providerStates.get(i).stateEnum);
        }
    }

    @Test
    public void destroy() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, USER1_CONFIG_GEO_DETECTION_ENABLED);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_PROVIDERS_INITIALIZING, STATE_STOPPED, STATE_INITIALIZING);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Simulate the primary provider suggesting a time zone.
        mTestPrimaryLocationTimeZoneProvider.simulateTimeZoneProviderEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        // Receiving a "success" provider event should cause a suggestion to be made synchronously,
        // and also clear the scheduled uncertainty suggestion.
        assertControllerState(controller, STATE_CERTAIN);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_STARTED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsStoppedAndCommit();
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_CERTAIN);
        mTestCallback.assertCertainSuggestionMadeFromEventAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);
        assertFalse(controller.isUncertaintyTimeoutSet());

        // Trigger destroy().
        controller.destroy();

        assertControllerState(controller, STATE_DESTROYED);
        mTestMetricsLogger.assertStateChangesAndCommit(
                STATE_UNCERTAIN, STATE_STOPPED, STATE_DESTROYED);

        // Confirm that the previous suggestion was overridden.
        mTestCallback.assertUncertainSuggestionMadeAndCommit();

        mTestPrimaryLocationTimeZoneProvider.assertStateChangesAndCommit(
                PROVIDER_STATE_STOPPED, PROVIDER_STATE_DESTROYED);
        mTestSecondaryLocationTimeZoneProvider.assertStateChangesAndCommit(
                PROVIDER_STATE_DESTROYED);
        assertFalse(controller.isUncertaintyTimeoutSet());
    }

    /**
     * A controller-state-only test to prove that "run in background" configuration behaves as
     * intended. Provider states are well covered by other "enabled" tests.
     */
    @Test
    public void geoDetectionRunInBackground() {
        LocationTimeZoneProviderController controller = new LocationTimeZoneProviderController(
                mTestThreadingDomain, mTestMetricsLogger, mTestPrimaryLocationTimeZoneProvider,
                mTestSecondaryLocationTimeZoneProvider, false /* recordStateChanges */);

        // A configuration where the user has geo-detection disabled.
        ConfigurationInternal runInBackgroundDisabledConfig =
                new ConfigurationInternal.Builder(USER1_CONFIG_GEO_DETECTION_DISABLED)
                        .setLocationEnabledSetting(true)
                        .setAutoDetectionEnabledSetting(false)
                        .setGeoDetectionEnabledSetting(false)
                        .setGeoDetectionRunInBackgroundEnabled(false)
                        .build();
        // A configuration where geo-detection is disabled by the user but can run in the
        // background.
        ConfigurationInternal runInBackgroundEnabledConfig =
                new ConfigurationInternal.Builder(runInBackgroundDisabledConfig)
                        .setGeoDetectionRunInBackgroundEnabled(true)
                        .build();
        assertEquals(DETECTION_MODE_MANUAL, runInBackgroundEnabledConfig.getDetectionMode());
        assertTrue(runInBackgroundEnabledConfig.isGeoDetectionExecutionEnabled());

        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controller, runInBackgroundDisabledConfig);

        // Initialize and check initial state.
        controller.initialize(testEnvironment, mTestCallback);

        assertControllerState(controller, STATE_STOPPED);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_PROVIDERS_INITIALIZING, STATE_STOPPED);

        testEnvironment.simulateConfigChange(runInBackgroundEnabledConfig);

        assertControllerState(controller, STATE_INITIALIZING);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_INITIALIZING);

        testEnvironment.simulateConfigChange(runInBackgroundDisabledConfig);

        assertControllerState(controller, STATE_STOPPED);
        mTestMetricsLogger.assertStateChangesAndCommit(STATE_STOPPED);
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
                ARBITRARY_TIME_MILLIS,
                new TimeZoneProviderSuggestion.Builder()
                        .setElapsedRealtimeMillis(ARBITRARY_TIME_MILLIS)
                        .setTimeZoneIds(timeZoneIds)
                        .build());
    }

    private static void assertControllerState(LocationTimeZoneProviderController controller,
            @State String expectedState) {
        assertEquals(expectedState, controller.getStateForTests().getControllerState());
    }

    private static void assertControllerRecordedStates(
            LocationTimeZoneManagerServiceState state,
            @State String... expectedStates) {
        assertEquals(Arrays.asList(expectedStates), state.getControllerStates());
    }

    private static class TestEnvironment extends LocationTimeZoneProviderController.Environment {

        // These timeouts are set deliberately so that:
        // (initialization timeout * 2) < uncertainty delay
        //
        // That makes the order of initialization timeout Vs uncertainty delay deterministic.
        private static final Duration PROVIDER_INITIALIZATION_TIMEOUT = Duration.ofMinutes(5);
        private static final Duration PROVIDER_INITIALIZATION_TIMEOUT_FUZZ = Duration.ofMinutes(1);
        private static final Duration UNCERTAINTY_DELAY = Duration.ofMinutes(15);

        private static final Duration PROVIDER_EVENT_FILTERING_AGE_THRESHOLD =
                Duration.ofMinutes(3);

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
        Duration getProviderEventFilteringAgeThreshold() {
            return PROVIDER_EVENT_FILTERING_AGE_THRESHOLD;
        }

        @Override
        Duration getUncertaintyDelay() {
            return UNCERTAINTY_DELAY;
        }

        @Override
        long elapsedRealtimeMillis() {
            // The properties of the real clock will also work for tests, i.e. it doesn't go
            // backwards.
            return SystemClock.elapsedRealtime();
        }

        void simulateConfigChange(ConfigurationInternal newConfig) {
            ConfigurationInternal oldConfig = mConfigurationInternal;
            mConfigurationInternal = Objects.requireNonNull(newConfig);
            if (Objects.equals(oldConfig, newConfig)) {
                fail("Bad test? No config change when one was expected");
            }
            mController.onConfigurationInternalChanged();
        }
    }

    private static class TestMetricsLogger
            implements LocationTimeZoneProviderController.MetricsLogger {

        private final TestState<@State String> mLatestStateEnum = new TestState<>();

        @Override
        public void onStateChange(@State String stateEnum) {
            mLatestStateEnum.set(stateEnum);
        }

        public void assertStateChangesAndCommit(@State String... expectedStateEnums) {
            mLatestStateEnum.assertChanges(expectedStateEnums);
            mLatestStateEnum.commitLatest();
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

        void assertCertainSuggestionMadeFromEventAndCommit(TimeZoneProviderEvent event) {
            // Test coding error if this fails.
            assertEquals(TimeZoneProviderEvent.EVENT_TYPE_SUGGESTION, event.getType());

            TimeZoneProviderSuggestion suggestion = event.getSuggestion();
            assertSuggestionMadeAndCommit(
                    suggestion.getElapsedRealtimeMillis(),
                    suggestion.getTimeZoneIds());
        }

        void assertNoSuggestionMade() {
            mLatestSuggestion.assertHasNotBeenSet();
        }

        /** Asserts that an uncertain suggestion has been made from the supplied event. */
        void assertUncertainSuggestionMadeFromEventAndCommit(TimeZoneProviderEvent event) {
            // Test coding error if this fails.
            assertEquals(TimeZoneProviderEvent.EVENT_TYPE_UNCERTAIN, event.getType());

            assertSuggestionMadeAndCommit(event.getCreationElapsedMillis(), null);
        }

        /**
         * Asserts that an uncertain suggestion has been made.
         * Ignores the suggestion's effectiveFromElapsedMillis.
         */
        void assertUncertainSuggestionMadeAndCommit() {
            // An "uncertain" suggestion has null time zone IDs.
            assertSuggestionMadeAndCommit(null, null);
        }

        /**
         * Asserts that a suggestion has been made and some properties of that suggestion.
         * When expectedEffectiveFromElapsedMillis is null then its value isn't checked.
         */
        private void assertSuggestionMadeAndCommit(
                @Nullable @ElapsedRealtimeLong Long expectedEffectiveFromElapsedMillis,
                @Nullable List<String> expectedZoneIds) {
            mLatestSuggestion.assertHasBeenSet();
            if (expectedEffectiveFromElapsedMillis != null) {
                assertEquals(
                        expectedEffectiveFromElapsedMillis.longValue(),
                        mLatestSuggestion.getLatest().getEffectiveFromElapsedMillis());
            }
            assertEquals(expectedZoneIds, mLatestSuggestion.getLatest().getZoneIds());
            mLatestSuggestion.commitLatest();
        }
    }

    private static class TestLocationTimeZoneProvider extends LocationTimeZoneProvider {

        /** Used to track historic provider states for tests. */
        private final TestState<ProviderState> mTestProviderState = new TestState<>();
        private boolean mFailDuringInitialization;
        private boolean mInitialized;

        /**
         * Creates the instance.
         */
        TestLocationTimeZoneProvider(ProviderMetricsLogger providerMetricsLogger,
                ThreadingDomain threadingDomain, String providerName) {
            super(providerMetricsLogger, threadingDomain, providerName,
                    new FakeTimeZoneProviderEventPreProcessor(), true /* recordStateChanges */);
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
            // No behavior needed.
        }

        @Override
        void onSetCurrentState(ProviderState newState) {
            mTestProviderState.set(newState);
        }

        @Override
        void onStartUpdates(Duration initializationTimeout, Duration eventFilteringAgeThreshold) {
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
