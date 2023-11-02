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

import static com.android.server.timedetector.TimeDetectorStrategy.ORIGIN_NETWORK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ManualTimeSuggestion;
import android.content.Context;
import android.os.HandlerThread;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.timezonedetector.TestCurrentUserIdentityInjector;
import com.android.server.timezonedetector.TestHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorInternalImplTest {
    private static final int ARBITRARY_USER_ID = 9999;
    private static final int ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS = 1234;
    private static final Instant ARBITRARY_SUGGESTION_LOWER_BOUND = Instant.ofEpochMilli(0);
    private static final Instant ARBITRARY_SUGGESTION_UPPER_BOUND =
            Instant.ofEpochMilli(Long.MAX_VALUE);
    private static final int[] ARBITRARY_ORIGIN_PRIORITIES = { ORIGIN_NETWORK };

    private Context mMockContext;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;
    private TestCurrentUserIdentityInjector mTestCurrentUserIdentityInjector;
    private FakeServiceConfigAccessor mFakeServiceConfigAccessorSpy;
    private FakeTimeDetectorStrategy mFakeTimeDetectorStrategySpy;

    private TimeDetectorInternalImpl mTimeDetectorInternal;

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeDetectorInternalTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());
        mTestCurrentUserIdentityInjector = new TestCurrentUserIdentityInjector();
        mTestCurrentUserIdentityInjector.initializeCurrentUserId(ARBITRARY_USER_ID);
        mFakeServiceConfigAccessorSpy = spy(new FakeServiceConfigAccessor());
        mFakeTimeDetectorStrategySpy = spy(new FakeTimeDetectorStrategy());

        mTimeDetectorInternal = new TimeDetectorInternalImpl(
                mMockContext, mTestHandler, mTestCurrentUserIdentityInjector,
                mFakeServiceConfigAccessorSpy, mFakeTimeDetectorStrategySpy);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }

    @Test
    public void testGetCapabilitiesAndConfigForDpm() throws Exception {
        final boolean autoDetectionEnabled = true;
        ConfigurationInternal testConfig = createConfigurationInternal(autoDetectionEnabled);
        mFakeServiceConfigAccessorSpy.initializeCurrentUserConfiguration(testConfig);

        TimeCapabilitiesAndConfig actualCapabilitiesAndConfig =
                mTimeDetectorInternal.getCapabilitiesAndConfigForDpm();

        int expectedUserId = mTestCurrentUserIdentityInjector.getCurrentUserId();
        verify(mFakeServiceConfigAccessorSpy).getConfigurationInternal(expectedUserId);

        final boolean bypassUserPolicyChecks = true;
        TimeCapabilitiesAndConfig expectedCapabilitiesAndConfig =
                testConfig.createCapabilitiesAndConfig(bypassUserPolicyChecks);
        assertEquals(expectedCapabilitiesAndConfig, actualCapabilitiesAndConfig);
    }

    @Test
    public void testUpdateConfigurationForDpm() throws Exception {
        final boolean autoDetectionEnabled = false;
        ConfigurationInternal initialConfigurationInternal =
                createConfigurationInternal(autoDetectionEnabled);
        mFakeServiceConfigAccessorSpy.initializeCurrentUserConfiguration(
                initialConfigurationInternal);

        TimeConfiguration timeConfiguration = new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(true)
                .build();
        assertTrue(mTimeDetectorInternal.updateConfigurationForDpm(timeConfiguration));

        final boolean expectedBypassUserPolicyChecks = true;
        verify(mFakeServiceConfigAccessorSpy).updateConfiguration(
                mTestCurrentUserIdentityInjector.getCurrentUserId(),
                timeConfiguration,
                expectedBypassUserPolicyChecks);
    }

    @Test
    public void testSetManualTimeZoneForDpm() throws Exception {
        ManualTimeSuggestion timeSuggestion = createManualTimeSuggestion();

        // The fake strategy always returns true.
        assertTrue(mTimeDetectorInternal.setManualTimeForDpm(timeSuggestion));

        int expectedUserId = mTestCurrentUserIdentityInjector.getCurrentUserId();
        boolean expectedBypassUserPolicyChecks = false;
        verify(mFakeTimeDetectorStrategySpy).suggestManualTime(
                expectedUserId, timeSuggestion, expectedBypassUserPolicyChecks);
    }

    @Test
    public void testSuggestNetworkTime() throws Exception {
        NetworkTimeSuggestion networkTimeSuggestion = createNetworkTimeSuggestion();

        mTimeDetectorInternal.suggestNetworkTime(networkTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        mTestHandler.waitForMessagesToBeProcessed();
        verify(mFakeTimeDetectorStrategySpy).suggestNetworkTime(networkTimeSuggestion);
    }

    private static NetworkTimeSuggestion createNetworkTimeSuggestion() {
        UnixEpochTime timeValue = new UnixEpochTime(100L, 1_000_000L);
        return new NetworkTimeSuggestion(timeValue, 123);
    }

    @Test
    public void testSuggestGnssTime() throws Exception {
        GnssTimeSuggestion gnssTimeSuggestion = createGnssTimeSuggestion();

        mTimeDetectorInternal.suggestGnssTime(gnssTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        mTestHandler.waitForMessagesToBeProcessed();
        verify(mFakeTimeDetectorStrategySpy).suggestGnssTime(gnssTimeSuggestion);
    }

    private static ManualTimeSuggestion createManualTimeSuggestion() {
        UnixEpochTime timeValue = new UnixEpochTime(100L, 1_000_000L);
        return new ManualTimeSuggestion(timeValue);
    }

    private static GnssTimeSuggestion createGnssTimeSuggestion() {
        UnixEpochTime timeValue = new UnixEpochTime(100L, 1_000_000L);
        return new GnssTimeSuggestion(timeValue);
    }

    private static ConfigurationInternal createConfigurationInternal(boolean autoDetectionEnabled) {
        return new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setAutoDetectionSupported(true)
                .setSystemClockUpdateThresholdMillis(ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS)
                .setAutoSuggestionLowerBound(ARBITRARY_SUGGESTION_LOWER_BOUND)
                .setManualSuggestionLowerBound(ARBITRARY_SUGGESTION_LOWER_BOUND)
                .setSuggestionUpperBound(ARBITRARY_SUGGESTION_UPPER_BOUND)
                .setOriginPriorities(ARBITRARY_ORIGIN_PRIORITIES)
                .setAutoDetectionEnabledSetting(autoDetectionEnabled)
                .build();
    }
}
