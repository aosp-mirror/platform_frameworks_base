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

package com.android.server.timezonedetector;

import static android.app.time.DetectorStatusTypes.DETECTION_ALGORITHM_STATUS_RUNNING;
import static android.app.time.DetectorStatusTypes.DETECTOR_STATUS_RUNNING;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_IS_CERTAIN;
import static android.app.time.LocationTimeZoneAlgorithmStatus.PROVIDER_STATUS_NOT_PRESENT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.app.time.TelephonyTimeZoneAlgorithmStatus;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneDetectorStatus;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.content.Context;
import android.os.HandlerThread;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TimeZoneDetectorInternalImplTest {

    private static final TelephonyTimeZoneAlgorithmStatus ARBITRARY_TELEPHONY_STATUS =
            new TelephonyTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING);
    private static final LocationTimeZoneAlgorithmStatus ARBITRARY_LOCATION_CERTAIN_STATUS =
            new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                    PROVIDER_STATUS_IS_CERTAIN, null, PROVIDER_STATUS_NOT_PRESENT, null);
    private static final TimeZoneDetectorStatus ARBITRARY_DETECTOR_STATUS =
            new TimeZoneDetectorStatus(DETECTOR_STATUS_RUNNING, ARBITRARY_TELEPHONY_STATUS,
                    ARBITRARY_LOCATION_CERTAIN_STATUS);

    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 1234L;
    private static final String ARBITRARY_ZONE_ID = "TestZoneId";
    private static final List<String> ARBITRARY_ZONE_IDS = Arrays.asList(ARBITRARY_ZONE_ID);
    private static final int ARBITRARY_USER_ID = 9999;

    private Context mMockContext;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;
    private TestCurrentUserIdentityInjector mTestCurrentUserIdentityInjector;
    private FakeTimeZoneDetectorStrategy mFakeTimeZoneDetectorStrategySpy;

    private TimeZoneDetectorInternalImpl mTimeZoneDetectorInternal;

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeZoneDetectorInternalTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());
        mTestCurrentUserIdentityInjector = new TestCurrentUserIdentityInjector();
        mTestCurrentUserIdentityInjector.initializeCurrentUserId(ARBITRARY_USER_ID);
        mFakeTimeZoneDetectorStrategySpy = spy(new FakeTimeZoneDetectorStrategy());

        mTimeZoneDetectorInternal = new TimeZoneDetectorInternalImpl(
                mMockContext, mTestHandler, mTestCurrentUserIdentityInjector,
                mFakeTimeZoneDetectorStrategySpy);
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
        TimeZoneDetectorStatus testStatus = ARBITRARY_DETECTOR_STATUS;
        mFakeTimeZoneDetectorStrategySpy.initializeConfigurationAndStatus(testConfig, testStatus);

        TimeZoneCapabilitiesAndConfig actualCapabilitiesAndConfig =
                mTimeZoneDetectorInternal.getCapabilitiesAndConfigForDpm();

        int expectedUserId = mTestCurrentUserIdentityInjector.getCurrentUserId();
        final boolean expectedBypassUserPolicyChecks = true;
        verify(mFakeTimeZoneDetectorStrategySpy).getCapabilitiesAndConfig(
                expectedUserId, expectedBypassUserPolicyChecks);

        TimeZoneCapabilitiesAndConfig expectedCapabilitiesAndConfig =
                new TimeZoneCapabilitiesAndConfig(
                        testStatus,
                        testConfig.asCapabilities(expectedBypassUserPolicyChecks),
                        testConfig.asConfiguration());
        assertEquals(expectedCapabilitiesAndConfig, actualCapabilitiesAndConfig);
    }

    @Test
    public void testUpdateConfigurationForDpm() throws Exception {
        final boolean autoDetectionEnabled = false;
        ConfigurationInternal initialConfigurationInternal =
                createConfigurationInternal(autoDetectionEnabled);
        TimeZoneDetectorStatus testStatus = ARBITRARY_DETECTOR_STATUS;
        mFakeTimeZoneDetectorStrategySpy.initializeConfigurationAndStatus(
                initialConfigurationInternal, testStatus);

        TimeZoneConfiguration timeConfiguration = new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(true)
                .build();
        assertTrue(mTimeZoneDetectorInternal.updateConfigurationForDpm(timeConfiguration));

        final boolean expectedBypassUserPolicyChecks = true;
        verify(mFakeTimeZoneDetectorStrategySpy).updateConfiguration(
                mTestCurrentUserIdentityInjector.getCurrentUserId(),
                timeConfiguration,
                expectedBypassUserPolicyChecks);
    }

    @Test
    public void testSetManualTimeZoneForDpm() throws Exception {
        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        // The fake strategy always returns true.
        assertTrue(mTimeZoneDetectorInternal.setManualTimeZoneForDpm(timeZoneSuggestion));

        int expectedUserId = mTestCurrentUserIdentityInjector.getCurrentUserId();
        boolean expectedBypassUserPolicyChecks = true;
        verify(mFakeTimeZoneDetectorStrategySpy).suggestManualTimeZone(
                expectedUserId, timeZoneSuggestion, expectedBypassUserPolicyChecks);
    }

    @Test
    public void testHandleLocationAlgorithmEvent() throws Exception {
        GeolocationTimeZoneSuggestion timeZoneSuggestion = createGeolocationTimeZoneSuggestion();
        LocationAlgorithmEvent suggestionEvent = new LocationAlgorithmEvent(
                ARBITRARY_LOCATION_CERTAIN_STATUS, timeZoneSuggestion);
        mTimeZoneDetectorInternal.handleLocationAlgorithmEvent(suggestionEvent);
        mTestHandler.assertTotalMessagesEnqueued(1);

        mTestHandler.waitForMessagesToBeProcessed();
        verify(mFakeTimeZoneDetectorStrategySpy).handleLocationAlgorithmEvent(suggestionEvent);
    }
    private static ManualTimeZoneSuggestion createManualTimeZoneSuggestion() {
        return new ManualTimeZoneSuggestion(ARBITRARY_ZONE_ID);
    }

    private static GeolocationTimeZoneSuggestion createGeolocationTimeZoneSuggestion() {
        return GeolocationTimeZoneSuggestion.createCertainSuggestion(
                ARBITRARY_ELAPSED_REALTIME_MILLIS, ARBITRARY_ZONE_IDS);
    }

    private static ConfigurationInternal createConfigurationInternal(boolean autoDetectionEnabled) {
        return new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
                .setTelephonyFallbackSupported(false)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setEnhancedMetricsCollectionEnabled(false)
                .setUserConfigAllowed(true)
                .setAutoDetectionEnabledSetting(autoDetectionEnabled)
                .setLocationEnabledSetting(false)
                .setGeoDetectionEnabledSetting(false)
                .build();
    }
}
