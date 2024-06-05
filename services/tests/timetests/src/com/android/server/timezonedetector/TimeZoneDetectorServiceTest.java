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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.time.ITimeZoneDetectorListener;
import android.app.time.LocationTimeZoneAlgorithmStatus;
import android.app.time.TelephonyTimeZoneAlgorithmStatus;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneDetectorStatus;
import android.app.time.TimeZoneState;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class TimeZoneDetectorServiceTest {

    private static final LocationTimeZoneAlgorithmStatus ARBITRARY_LOCATION_CERTAIN_STATUS =
            new LocationTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING,
                    PROVIDER_STATUS_IS_CERTAIN, null, PROVIDER_STATUS_NOT_PRESENT, null);
    private static final TimeZoneDetectorStatus ARBITRARY_DETECTOR_STATUS =
            new TimeZoneDetectorStatus(DETECTOR_STATUS_RUNNING,
                    new TelephonyTimeZoneAlgorithmStatus(DETECTION_ALGORITHM_STATUS_RUNNING),
                    ARBITRARY_LOCATION_CERTAIN_STATUS);
    private static final int ARBITRARY_USER_ID = 9999;
    private static final List<String> ARBITRARY_TIME_ZONE_IDS = Arrays.asList("TestZoneId");
    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 1234L;

    private Context mMockContext;

    private TimeZoneDetectorService mTimeZoneDetectorService;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;
    private TestCallerIdentityInjector mTestCallerIdentityInjector;
    private FakeTimeZoneDetectorStrategy mFakeTimeZoneDetectorStrategySpy;


    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeZoneDetectorServiceTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());

        mTestCallerIdentityInjector = new TestCallerIdentityInjector();
        mTestCallerIdentityInjector.initializeCallingUserId(ARBITRARY_USER_ID);

        mFakeTimeZoneDetectorStrategySpy = spy(new FakeTimeZoneDetectorStrategy());

        mTimeZoneDetectorService = new TimeZoneDetectorService(
                mMockContext, mTestHandler, mTestCallerIdentityInjector,
                mFakeTimeZoneDetectorStrategySpy);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }

    @Test
    public void testGetCapabilitiesAndConfig_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        assertThrows(SecurityException.class, mTimeZoneDetectorService::getCapabilitiesAndConfig);
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testGetCapabilitiesAndConfig() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        ConfigurationInternal configuration =
                createConfigurationInternal(true /* autoDetectionEnabled*/);
        mFakeTimeZoneDetectorStrategySpy.initializeConfigurationAndStatus(configuration,
                ARBITRARY_DETECTOR_STATUS);

        TimeZoneCapabilitiesAndConfig actualCapabilitiesAndConfig =
                mTimeZoneDetectorService.getCapabilitiesAndConfig();

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());

        int expectedUserId = mTestCallerIdentityInjector.getCallingUserId();
        boolean expectedBypassUserPolicyChecks = false;
        verify(mFakeTimeZoneDetectorStrategySpy)
                .getCapabilitiesAndConfig(expectedUserId, expectedBypassUserPolicyChecks);

        TimeZoneCapabilitiesAndConfig expectedCapabilitiesAndConfig =
                new TimeZoneCapabilitiesAndConfig(
                        ARBITRARY_DETECTOR_STATUS,
                        configuration.asCapabilities(expectedBypassUserPolicyChecks),
                        configuration.asConfiguration());
        assertEquals(expectedCapabilitiesAndConfig, actualCapabilitiesAndConfig);
    }

    @Test
    public void testAddListener_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        ITimeZoneDetectorListener mockListener = mock(ITimeZoneDetectorListener.class);
        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.addListener(mockListener));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testRemoveListener_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        ITimeZoneDetectorListener mockListener = mock(ITimeZoneDetectorListener.class);
        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.removeListener(mockListener));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testListenerRegistrationAndCallbacks() throws Exception {
        ConfigurationInternal initialConfiguration =
                createConfigurationInternal(false /* autoDetectionEnabled */);

        mFakeTimeZoneDetectorStrategySpy.initializeConfigurationAndStatus(
                initialConfiguration, ARBITRARY_DETECTOR_STATUS);

        IBinder mockListenerBinder = mock(IBinder.class);
        ITimeZoneDetectorListener mockListener = mock(ITimeZoneDetectorListener.class);

        {
            doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());
            when(mockListener.asBinder()).thenReturn(mockListenerBinder);

            mTimeZoneDetectorService.addListener(mockListener);

            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
            verify(mockListener).asBinder();
            verify(mockListenerBinder).linkToDeath(any(), anyInt());
            verifyNoMoreInteractions(mockListenerBinder, mockListener, mMockContext);
            reset(mockListenerBinder, mockListener, mMockContext);
        }

        {
            doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

            // Simulate the configuration being changed and verify the mockListener was notified.
            TimeZoneConfiguration autoDetectEnabledConfiguration =
                    createTimeZoneConfiguration(true /* autoDetectionEnabled */);
            mTimeZoneDetectorService.updateConfiguration(autoDetectEnabledConfiguration);

            // The configuration update notification is asynchronous.
            mTestHandler.waitForMessagesToBeProcessed();

            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
            verify(mockListener).onChange();
            verifyNoMoreInteractions(mockListenerBinder, mockListener, mMockContext);
            reset(mockListenerBinder, mockListener, mMockContext);
        }

        {
            doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());
            when(mockListener.asBinder()).thenReturn(mockListenerBinder);
            when(mockListenerBinder.unlinkToDeath(any(), anyInt())).thenReturn(true);

            // Now remove the listener, change the config again, and verify the listener is not
            // called.
            mTimeZoneDetectorService.removeListener(mockListener);

            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
            verify(mockListener).asBinder();
            verify(mockListenerBinder).unlinkToDeath(any(), eq(0));
            verifyNoMoreInteractions(mockListenerBinder, mockListener, mMockContext);
            reset(mockListenerBinder, mockListener, mMockContext);
        }

        {
            doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

            TimeZoneConfiguration autoDetectDisabledConfiguration =
                    createTimeZoneConfiguration(false /* autoDetectionEnabled */);
            mTimeZoneDetectorService.updateConfiguration(autoDetectDisabledConfiguration);

            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
            verify(mockListener, never()).onChange();
            verifyNoMoreInteractions(mockListenerBinder, mockListener, mMockContext);
            reset(mockListenerBinder, mockListener, mMockContext);
        }
    }

    @Test
    public void testHandleLocationAlgorithmEvent_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        GeolocationTimeZoneSuggestion timeZoneSuggestion = createGeolocationTimeZoneSuggestion();
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(
                ARBITRARY_LOCATION_CERTAIN_STATUS, timeZoneSuggestion);

        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.handleLocationAlgorithmEvent(event));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME_ZONE), anyString());
    }

    @Test
    public void testHandleLocationAlgorithmEvent() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        GeolocationTimeZoneSuggestion timeZoneSuggestion = createGeolocationTimeZoneSuggestion();
        LocationAlgorithmEvent event = new LocationAlgorithmEvent(
                ARBITRARY_LOCATION_CERTAIN_STATUS, timeZoneSuggestion);

        mTimeZoneDetectorService.handleLocationAlgorithmEvent(event);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME_ZONE), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        verify(mFakeTimeZoneDetectorStrategySpy).handleLocationAlgorithmEvent(event);
    }

    @Test
    public void testSuggestManualTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.suggestManualTimeZone(timeZoneSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE), anyString());
    }

    @Test
    public void testSuggestManualTimeZone() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        boolean expectedResult = true; // The test strategy always returns true.
        assertEquals(expectedResult,
                mTimeZoneDetectorService.suggestManualTimeZone(timeZoneSuggestion));

        int expectedUserId = mTestCallerIdentityInjector.getCallingUserId();
        boolean expectedBypassUserPolicyChecks = false;
        verify(mFakeTimeZoneDetectorStrategySpy).suggestManualTimeZone(
                expectedUserId, timeZoneSuggestion, expectedBypassUserPolicyChecks);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE), anyString());
    }

    @Test
    public void testSuggestTelephonyTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE), anyString());
    }

    @Test
    public void testSuggestTelephonyTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE), anyString());
    }

    @Test
    public void testSuggestTelephonyTimeZone() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();
        mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        verify(mFakeTimeZoneDetectorStrategySpy).suggestTelephonyTimeZone(timeZoneSuggestion);
    }

    @Test
    public void testGetTimeZoneState() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());
        TimeZoneState fakeState = new TimeZoneState("Europe/Narnia", true);
        mFakeTimeZoneDetectorStrategySpy.setTimeZoneState(fakeState);

        TimeZoneState actualState = mTimeZoneDetectorService.getTimeZoneState();

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
        assertEquals(fakeState, actualState);
    }

    @Test
    public void testGetTimeZoneState_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        assertThrows(SecurityException.class, mTimeZoneDetectorService::getTimeZoneState);
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testSetTimeZoneState() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TimeZoneState state = new TimeZoneState("Europe/Narnia", true);
        mTimeZoneDetectorService.setTimeZoneState(state);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
        assertEquals(state, mFakeTimeZoneDetectorStrategySpy.getTimeZoneState());
    }

    @Test
    public void testSetTimeZoneState_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        TimeZoneState state = new TimeZoneState("Europe/Narnia", true);
        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.setTimeZoneState(state));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testConfirmTimeZone() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        // The fake strategy always returns false.
        assertFalse(mTimeZoneDetectorService.confirmTimeZone("Europe/Narnia"));

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
        verify(mFakeTimeZoneDetectorStrategySpy).confirmTimeZone("Europe/Narnia");
    }

    @Test
    public void testConfirmTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.confirmTimeZone("Europe/Narnia"));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testSetManualTimeZone() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        boolean expectedResult = true; // The test strategy always returns true.
        assertEquals(expectedResult,
                mTimeZoneDetectorService.setManualTimeZone(timeZoneSuggestion));

        // The service calls "suggestManualTimeZone()" because the logic is the same.
        int expectedUserId = mTestCallerIdentityInjector.getCallingUserId();
        boolean expectedBypassUserPolicyChecks = false;
        verify(mFakeTimeZoneDetectorStrategySpy).suggestManualTimeZone(
                expectedUserId, timeZoneSuggestion, expectedBypassUserPolicyChecks);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testSetManualTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeZoneDetectorService.setManualTimeZone(timeZoneSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testDump() {
        when(mMockContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        Dumpable dumpable = mock(Dumpable.class);
        mTimeZoneDetectorService.addDumpable(dumpable);

        PrintWriter pw = new PrintWriter(new StringWriter());
        mTimeZoneDetectorService.dump(null, pw, null);

        verify(mMockContext).checkCallingOrSelfPermission(eq(android.Manifest.permission.DUMP));
        verify(mFakeTimeZoneDetectorStrategySpy).dump(any(), any());
        verify(dumpable).dump(any(), any());
    }

    private static TimeZoneConfiguration createTimeZoneConfiguration(boolean autoDetectionEnabled) {
        return new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(autoDetectionEnabled)
                .build();
    }

    private static ConfigurationInternal createConfigurationInternal(boolean autoDetectionEnabled) {
        // Default geo detection settings from auto detection settings - they are not important to
        // the tests.
        final boolean geoDetectionEnabled = autoDetectionEnabled;
        return new ConfigurationInternal.Builder()
                .setUserId(ARBITRARY_USER_ID)
                .setTelephonyDetectionFeatureSupported(true)
                .setGeoDetectionFeatureSupported(true)
                .setTelephonyFallbackSupported(false)
                .setGeoDetectionRunInBackgroundEnabled(false)
                .setEnhancedMetricsCollectionEnabled(false)
                .setUserConfigAllowed(true)
                .setAutoDetectionEnabledSetting(autoDetectionEnabled)
                .setLocationEnabledSetting(geoDetectionEnabled)
                .setGeoDetectionEnabledSetting(geoDetectionEnabled)
                .build();
    }

    private static GeolocationTimeZoneSuggestion createGeolocationTimeZoneSuggestion() {
        return GeolocationTimeZoneSuggestion.createCertainSuggestion(
                ARBITRARY_ELAPSED_REALTIME_MILLIS, ARBITRARY_TIME_ZONE_IDS);
    }

    private static ManualTimeZoneSuggestion createManualTimeZoneSuggestion() {
        return new ManualTimeZoneSuggestion("TestZoneId");
    }

    private static TelephonyTimeZoneSuggestion createTelephonyTimeZoneSuggestion() {
        int slotIndex = 1234;
        return new TelephonyTimeZoneSuggestion.Builder(slotIndex)
                .setZoneId("TestZoneId")
                .setMatchType(TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET)
                .setQuality(TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE)
                .build();
    }
}
