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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.time.ITimeZoneDetectorListener;
import android.app.time.TimeZoneConfiguration;
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

    private static final int ARBITRARY_USER_ID = 9999;
    private static final List<String> ARBITRARY_TIME_ZONE_IDS = Arrays.asList("TestZoneId");
    private static final long ARBITRARY_ELAPSED_REALTIME_MILLIS = 1234L;

    private Context mMockContext;

    private TimeZoneDetectorService mTimeZoneDetectorService;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;
    private TestCallerIdentityInjector mTestCallerIdentityInjector;
    private FakeServiceConfigAccessor mFakeServiceConfigAccessor;
    private FakeTimeZoneDetectorStrategy mFakeTimeZoneDetectorStrategy;


    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeZoneDetectorServiceTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());

        mTestCallerIdentityInjector = new TestCallerIdentityInjector();
        mTestCallerIdentityInjector.initializeCallingUserId(ARBITRARY_USER_ID);

        mFakeTimeZoneDetectorStrategy = new FakeTimeZoneDetectorStrategy();
        mFakeServiceConfigAccessor = new FakeServiceConfigAccessor();

        mTimeZoneDetectorService = new TimeZoneDetectorService(
                mMockContext, mTestHandler, mTestCallerIdentityInjector,
                mFakeServiceConfigAccessor, mFakeTimeZoneDetectorStrategy);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
    }

    @Test(expected = SecurityException.class)
    public void testGetCapabilitiesAndConfig_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        try {
            mTimeZoneDetectorService.getCapabilitiesAndConfig();
            fail("Expected SecurityException");
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                    anyString());
        }
    }

    @Test
    public void testGetCapabilitiesAndConfig() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        ConfigurationInternal configuration =
                createConfigurationInternal(true /* autoDetectionEnabled*/);
        mFakeServiceConfigAccessor.initializeConfiguration(configuration);

        assertEquals(configuration.createCapabilitiesAndConfig(),
                mTimeZoneDetectorService.getCapabilitiesAndConfig());

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                anyString());
    }

    @Test(expected = SecurityException.class)
    public void testAddListener_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        ITimeZoneDetectorListener mockListener = mock(ITimeZoneDetectorListener.class);
        try {
            mTimeZoneDetectorService.addListener(mockListener);
            fail("Expected SecurityException");
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                    anyString());
        }
    }

    @Test(expected = SecurityException.class)
    public void testRemoveListener_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        ITimeZoneDetectorListener mockListener = mock(ITimeZoneDetectorListener.class);
        try {
            mTimeZoneDetectorService.removeListener(mockListener);
            fail("Expected a SecurityException");
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                    anyString());
        }
    }

    @Test
    public void testListenerRegistrationAndCallbacks() throws Exception {
        ConfigurationInternal initialConfiguration =
                createConfigurationInternal(false /* autoDetectionEnabled */);
        mFakeServiceConfigAccessor.initializeConfiguration(initialConfiguration);

        IBinder mockListenerBinder = mock(IBinder.class);
        ITimeZoneDetectorListener mockListener = mock(ITimeZoneDetectorListener.class);

        {
            doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());
            when(mockListener.asBinder()).thenReturn(mockListenerBinder);

            mTimeZoneDetectorService.addListener(mockListener);

            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                    anyString());
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
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                    anyString());
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
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                    anyString());
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
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                    anyString());
            verify(mockListener, never()).onChange();
            verifyNoMoreInteractions(mockListenerBinder, mockListener, mMockContext);
            reset(mockListenerBinder, mockListener, mMockContext);
        }
    }

    @Test(expected = SecurityException.class)
    public void testSuggestGeolocationTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());
        GeolocationTimeZoneSuggestion timeZoneSuggestion = createGeolocationTimeZoneSuggestion();

        try {
            mTimeZoneDetectorService.suggestGeolocationTimeZone(timeZoneSuggestion);
            fail("Expected SecurityException");
        } finally {
            verify(mMockContext).enforceCallingOrSelfPermission(
                    eq(android.Manifest.permission.SET_TIME_ZONE),
                    anyString());
        }
    }

    @Test
    public void testSuggestGeolocationTimeZone() throws Exception {
        doNothing().when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());

        GeolocationTimeZoneSuggestion timeZoneSuggestion = createGeolocationTimeZoneSuggestion();

        mTimeZoneDetectorService.suggestGeolocationTimeZone(timeZoneSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SET_TIME_ZONE),
                anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mFakeTimeZoneDetectorStrategy.verifySuggestGeolocationTimeZoneCalled(timeZoneSuggestion);
    }

    @Test(expected = SecurityException.class)
    public void testSuggestManualTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());
        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        try {
            mTimeZoneDetectorService.suggestManualTimeZone(timeZoneSuggestion);
            fail("Expected SecurityException");
        } finally {
            verify(mMockContext).enforceCallingOrSelfPermission(
                    eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE),
                    anyString());
        }
    }

    @Test
    public void testSuggestManualTimeZone() throws Exception {
        doNothing().when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());

        ManualTimeZoneSuggestion timeZoneSuggestion = createManualTimeZoneSuggestion();

        boolean expectedResult = true; // The test strategy always returns true.
        assertEquals(expectedResult,
                mTimeZoneDetectorService.suggestManualTimeZone(timeZoneSuggestion));

        mFakeTimeZoneDetectorStrategy.verifySuggestManualTimeZoneCalled(timeZoneSuggestion);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE),
                anyString());
    }

    @Test(expected = SecurityException.class)
    public void testSuggestTelephonyTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();

        try {
            mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion);
            fail("Expected SecurityException");
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE),
                    anyString());
        }
    }

    @Test(expected = SecurityException.class)
    public void testSuggestTelephonyTimeZone_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();

        try {
            mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion);
            fail("Expected SecurityException");
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE),
                    anyString());
        }
    }

    @Test
    public void testSuggestTelephonyTimeZone() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TelephonyTimeZoneSuggestion timeZoneSuggestion = createTelephonyTimeZoneSuggestion();
        mTimeZoneDetectorService.suggestTelephonyTimeZone(timeZoneSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE),
                anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mFakeTimeZoneDetectorStrategy.verifySuggestTelephonyTimeZoneCalled(timeZoneSuggestion);
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
        mFakeTimeZoneDetectorStrategy.verifyDumpCalled();
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
        return new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
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
