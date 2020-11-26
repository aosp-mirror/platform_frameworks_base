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

import static com.android.internal.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_PERMANENT_FAILURE;
import static com.android.internal.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_SUCCESS;
import static com.android.internal.location.timezone.LocationTimeZoneEvent.EVENT_TYPE_UNCERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DISABLED;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_CERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_INITIALIZING;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_ENABLED_UNCERTAIN;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.location.timezone.TestSupport.USER1_CONFIG_GEO_DETECTION_DISABLED;
import static com.android.server.location.timezone.TestSupport.USER1_CONFIG_GEO_DETECTION_ENABLED;
import static com.android.server.location.timezone.TestSupport.USER2_CONFIG_GEO_DETECTION_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static java.util.Arrays.asList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.platform.test.annotations.Presubmit;
import android.util.IndentingPrintWriter;

import com.android.internal.location.timezone.LocationTimeZoneEvent;
import com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.ProviderStateEnum;
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

    private static final long ARBITRARY_TIME_MILLIS = 12345L;

    private static final LocationTimeZoneEvent USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1 =
            createLocationTimeZoneEvent(EVENT_TYPE_SUCCESS, asList("Europe/London"));
    private static final LocationTimeZoneEvent USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2 =
            createLocationTimeZoneEvent(EVENT_TYPE_SUCCESS, asList("Europe/Paris"));
    private static final LocationTimeZoneEvent USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT =
            createLocationTimeZoneEvent(EVENT_TYPE_UNCERTAIN, null);
    private static final LocationTimeZoneEvent USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT =
            createLocationTimeZoneEvent(EVENT_TYPE_PERMANENT_FAILURE, null);

    private TestThreadingDomain mTestThreadingDomain;
    private TestCallback mTestCallback;
    private TestLocationTimeZoneProvider mTestPrimaryLocationTimeZoneProvider;
    private TestLocationTimeZoneProvider mTestSecondaryLocationTimeZoneProvider;

    @Before
    public void setUp() {
        // For simplicity, the TestThreadingDomain uses the test's main thread. To execute posted
        // runnables, the test must call methods on mTestThreadingDomain otherwise those runnables
        // will never get a chance to execute.
        mTestThreadingDomain = new TestThreadingDomain();
        mTestCallback = new TestCallback(mTestThreadingDomain);
        mTestPrimaryLocationTimeZoneProvider =
                new TestLocationTimeZoneProvider(mTestThreadingDomain, "primary");
        mTestSecondaryLocationTimeZoneProvider =
                new TestLocationTimeZoneProvider(mTestThreadingDomain, "secondary");
    }

    @Test
    public void initialState_enabled() {
        ControllerImpl controllerImpl = new ControllerImpl(mTestThreadingDomain,
                mTestPrimaryLocationTimeZoneProvider, mTestSecondaryLocationTimeZoneProvider);
        TestEnvironment testEnvironment = new TestEnvironment(
                mTestThreadingDomain, controllerImpl, USER1_CONFIG_GEO_DETECTION_ENABLED);
        Duration expectedInitTimeout = testEnvironment.getProviderInitializationTimeout()
                .plus(testEnvironment.getProviderInitializationTimeoutFuzz());

        // Initialize. After initialization the providers must be initialized and one should be
        // enabled.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestPrimaryLocationTimeZoneProvider.assertInitializationTimeoutSet(expectedInitTimeout);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
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
        // enabled.
        controllerImpl.initialize(testEnvironment, mTestCallback);

        mTestPrimaryLocationTimeZoneProvider.assertInitialized();
        mTestSecondaryLocationTimeZoneProvider.assertInitialized();

        mTestPrimaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        // The primary should have reported uncertainty, which should trigger the controller to
        // start the uncertainty timeout and enable the secondary.
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate time passing with no provider event being received from either the primary or
        // secondary.
        mTestThreadingDomain.executeNext();

        // Now both initialization timeouts should have triggered. The uncertainty timeout should
        // still not be triggered.
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Finally, the uncertainty timeout should cause the controller to make an uncertain
        // suggestion.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made and the secondary to be shut down.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // A second, identical event should not cause another suggestion.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // And a third, different event should cause another suggestion.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getTimeZoneIds());
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate time passing with no provider event being received from the primary.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // A second, identical event should not cause another suggestion.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // And a third, different event should cause another suggestion.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getTimeZoneIds());
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made and ensure the primary is considered initialized.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate an uncertain event being received from the primary provider. This should not
        // cause a suggestion to be made straight away, but the uncertainty timeout should be
        // started and the secondary should be enabled.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate a location event being received from the secondary provider. This should cause a
        // suggestion to be made, cancel the uncertainty timeout and ensure the secondary is
        // considered initialized.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate an uncertain event being received from the secondary provider. This should not
        // cause a suggestion to be made straight away, but the uncertainty timeout should be
        // started. Both providers are now enabled, with no initialization timeout set.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate time passing. This means the uncertainty timeout should fire and the uncertain
        // suggestion should be made.
        mTestThreadingDomain.executeNext();

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a location event being received from the primary provider. This should cause a
        // suggestion to be made.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Uncertainty should not cause a suggestion to be made straight away, but the uncertainty
        // timeout should be started and the secondary should be enabled.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // And a success event from the primary provider should cause the controller to make another
        // suggestion, the uncertainty timeout should be cancelled and the secondary should be
        // disabled again.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getTimeZoneIds());
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

        mTestPrimaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
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

        mTestPrimaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a success event being received from the primary provider.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        // Because there had been a previous suggestion, the controller should withdraw it
        // immediately to let the downstream components know that the provider can no longer be sure
        // of the time zone.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate the primary provider suggesting a time zone.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1);

        // Receiving a "success" provider event should cause a suggestion to be made synchronously,
        // and also clear the scheduled uncertainty suggestion.
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT1.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate the user change (but geo detection still enabled).
        testEnvironment.simulateConfigChange(USER2_CONFIG_GEO_DETECTION_ENABLED);

        // We expect the provider to end up in PROVIDER_STATE_ENABLED, but it should have been
        // disabled when the user changed.
        int[] expectedStateTransitions =
                { PROVIDER_STATE_DISABLED, PROVIDER_STATE_ENABLED_INITIALIZING };
        mTestPrimaryLocationTimeZoneProvider.assertStateChangesAndCommit(expectedStateTransitions);
        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfig(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER2_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a failure location event being received from the primary provider. This should
        // cause the secondary to be enabled.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // And a success event from the secondary provider should cause the controller to make
        // another suggestion, the uncertainty timeout should be cancelled.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a failure location event being received from the primary provider. This should
        // cause the secondary to be enabled.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate an uncertain event from the primary. This will enable the secondary, which will
        // give this test the opportunity to simulate its failure. Then it will be possible to
        // demonstrate controller behavior with only the primary working.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate failure event from the secondary. This should just affect the secondary's state.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // And a success event from the primary provider should cause the controller to make
        // a suggestion, the uncertainty timeout should be cancelled.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_CERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertSuggestionMadeAndCommit(
                USER1_SUCCESS_LOCATION_TIME_ZONE_EVENT2.getTimeZoneIds());
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate uncertainty from the primary. The secondary cannot be enabled.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate an uncertain event from the primary. This will enable the secondary, which will
        // give this test the opportunity to simulate its failure. Then it will be possible to
        // demonstrate controller behavior with only the primary working.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_UNCERTAIN_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Simulate failure event from the secondary. This should just affect the secondary's state.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_UNCERTAIN, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertUncertaintyTimeoutSet(testEnvironment, controllerImpl);

        // Now signal a config change so that geo detection is disabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_DISABLED);

        mTestPrimaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Now signal a config change so that geo detection is enabled. Only the primary can be
        // enabled.
        testEnvironment.simulateConfigChange(USER1_CONFIG_GEO_DETECTION_ENABLED);

        mTestPrimaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
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
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestSecondaryLocationTimeZoneProvider.assertIsDisabledAndCommit();
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate a failure event from the primary. This will enable the secondary.
        mTestPrimaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertStateEnumAndConfigAndCommit(
                PROVIDER_STATE_ENABLED_INITIALIZING, USER1_CONFIG_GEO_DETECTION_ENABLED);
        mTestCallback.assertNoSuggestionMade();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());

        // Simulate failure event from the secondary.
        mTestSecondaryLocationTimeZoneProvider.simulateLocationTimeZoneEvent(
                USER1_PERM_FAILURE_LOCATION_TIME_ZONE_EVENT);

        mTestPrimaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestSecondaryLocationTimeZoneProvider.assertIsPermFailedAndCommit();
        mTestCallback.assertUncertainSuggestionMadeAndCommit();
        assertFalse(controllerImpl.isUncertaintyTimeoutSet());
    }

    private static void assertUncertaintyTimeoutSet(
            LocationTimeZoneProviderController.Environment environment,
            LocationTimeZoneProviderController controller) {
        assertTrue(controller.isUncertaintyTimeoutSet());
        assertEquals(environment.getUncertaintyDelay().toMillis(),
                controller.getUncertaintyTimeoutDelayMillis());
    }

    private static LocationTimeZoneEvent createLocationTimeZoneEvent(
            int eventType, @Nullable List<String> timeZoneIds) {
        LocationTimeZoneEvent.Builder builder = new LocationTimeZoneEvent.Builder()
                .setElapsedRealtimeMillis(ARBITRARY_TIME_MILLIS)
                .setEventType(eventType);
        if (timeZoneIds != null) {
            builder.setTimeZoneIds(timeZoneIds);
        }
        return builder.build();
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

        public void assertIsPermFailedAndCommit() {
            // A failed provider doesn't hold config.
            assertStateEnumAndConfig(PROVIDER_STATE_PERM_FAILED, null /* config */);
            mTestProviderState.commitLatest();
        }

        void assertIsDisabledAndCommit() {
            // A disabled provider doesn't hold config.
            assertStateEnumAndConfig(PROVIDER_STATE_DISABLED, null /* config */);
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
            assertEquals(expectedStateEnum == PROVIDER_STATE_ENABLED_INITIALIZING,
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
