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

import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;
import static com.android.server.location.timezone.TestSupport.USER1_CONFIG_GEO_DETECTION_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.platform.test.annotations.Presubmit;
import android.util.IndentingPrintWriter;

import com.android.server.location.timezone.LocationTimeZoneProvider.ProviderState;
import com.android.server.timezonedetector.ConfigurationInternal;
import com.android.server.timezonedetector.TestState;

import org.junit.Before;
import org.junit.Test;

import java.time.Duration;

/**
 * Tests for {@link NullLocationTimeZoneProvider} and, indirectly, the class it extends
 * {@link LocationTimeZoneProvider}.
 */
@Presubmit
public class NullLocationTimeZoneProviderTest {

    private TestThreadingDomain mTestThreadingDomain;

    private TestController mTestController;

    @Before
    public void setUp() {
        mTestThreadingDomain = new TestThreadingDomain();
        mTestController = new TestController(mTestThreadingDomain);
    }

    @Test
    public void initialization() {
        String providerName = "primary";
        NullLocationTimeZoneProvider provider =
                new NullLocationTimeZoneProvider(mTestThreadingDomain, providerName);
        provider.initialize(providerState -> mTestController.onProviderStateChange(providerState));

        ProviderState currentState = provider.getCurrentState();
        assertEquals(PROVIDER_STATE_STOPPED, currentState.stateEnum);
        assertNull(currentState.currentUserConfiguration);
        assertSame(provider, currentState.provider);
        mTestThreadingDomain.assertQueueEmpty();
    }

    @Test
    public void startSchedulesPermFailure() {
        String providerName = "primary";
        NullLocationTimeZoneProvider provider =
                new NullLocationTimeZoneProvider(mTestThreadingDomain, providerName);
        provider.initialize(providerState -> mTestController.onProviderStateChange(providerState));

        ConfigurationInternal config = USER1_CONFIG_GEO_DETECTION_ENABLED;
        Duration arbitraryInitializationTimeout = Duration.ofMinutes(5);
        Duration arbitraryInitializationTimeoutFuzz = Duration.ofMinutes(2);
        provider.startUpdates(config, arbitraryInitializationTimeout,
                arbitraryInitializationTimeoutFuzz);

        // The NullProvider should enter the enabled state, but have schedule an immediate runnable
        // to switch to perm failure.
        ProviderState currentState = provider.getCurrentState();
        assertSame(provider, currentState.provider);
        assertEquals(PROVIDER_STATE_STARTED_INITIALIZING, currentState.stateEnum);
        assertEquals(config, currentState.currentUserConfiguration);
        mTestThreadingDomain.assertNextQueueItemIsImmediate();
        // Entering enabled() does not trigger an onProviderStateChanged() as it is requested by the
        // controller.
        mTestController.assertProviderChangeNotTriggered();

        // Check the queued runnable causes the provider to go into perm failed state.
        mTestThreadingDomain.executeNext();

        // Entering perm failed triggers an onProviderStateChanged() as it is asynchronously
        // triggered.
        mTestController.assertProviderChangeTriggered(PROVIDER_STATE_PERM_FAILED);
    }

    /** A test stand-in for the {@link LocationTimeZoneProviderController}. */
    private static class TestController extends LocationTimeZoneProviderController {

        private TestState<ProviderState> mProviderState = new TestState<>();

        TestController(ThreadingDomain threadingDomain) {
            super(threadingDomain);
        }

        @Override
        void initialize(Environment environment, Callback callback) {
            // Not needed for provider testing.
        }

        @Override
        void onConfigChanged() {
            // Not needed for provider testing.
        }

        @Override
        boolean isUncertaintyTimeoutSet() {
            // Not needed for provider testing.
            return false;
        }

        @Override
        long getUncertaintyTimeoutDelayMillis() {
            // Not needed for provider testing.
            return 0;
        }

        void onProviderStateChange(ProviderState providerState) {
            this.mProviderState.set(providerState);
        }

        @Override
        public void dump(IndentingPrintWriter pw, String[] args) {
            // Not needed for provider testing.
        }

        void assertProviderChangeTriggered(int expectedStateEnum) {
            assertEquals(expectedStateEnum, mProviderState.getLatest().stateEnum);
            mProviderState.commitLatest();
        }

        public void assertProviderChangeNotTriggered() {
            mProviderState.assertHasNotBeenSet();
        }
    }
}
