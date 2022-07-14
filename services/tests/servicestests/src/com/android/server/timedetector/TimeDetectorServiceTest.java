/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
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

import android.app.time.ExternalTimeSuggestion;
import android.app.time.ITimeDetectorListener;
import android.app.time.TimeConfiguration;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.app.timedetector.TimePoint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.TimestampedValue;
import android.util.NtpTrustedTime;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.timezonedetector.TestCallerIdentityInjector;
import com.android.server.timezonedetector.TestHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorServiceTest {

    private static final int ARBITRARY_USER_ID = 9999;
    private static final int ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS = 1234;
    private static final Instant ARBITRARY_AUTO_TIME_LOWER_BOUND = Instant.ofEpochMilli(0);
    private static final int[] ARBITRARY_ORIGIN_PRIORITIES = { ORIGIN_NETWORK };

    private Context mMockContext;

    private TimeDetectorService mTimeDetectorService;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;
    private TestCallerIdentityInjector mTestCallerIdentityInjector;
    private FakeServiceConfigAccessor mFakeServiceConfigAccessor;
    private NtpTrustedTime mMockNtpTrustedTime;
    private FakeTimeDetectorStrategy mFakeTimeDetectorStrategy;


    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeDetectorServiceTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());

        mTestCallerIdentityInjector = new TestCallerIdentityInjector();
        mTestCallerIdentityInjector.initializeCallingUserId(ARBITRARY_USER_ID);

        mFakeTimeDetectorStrategy = new FakeTimeDetectorStrategy();
        mFakeServiceConfigAccessor = new FakeServiceConfigAccessor();
        mMockNtpTrustedTime = mock(NtpTrustedTime.class);

        mTimeDetectorService = new TimeDetectorService(
                mMockContext, mTestHandler, mFakeServiceConfigAccessor,
                mFakeTimeDetectorStrategy, mTestCallerIdentityInjector, mMockNtpTrustedTime);
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
            mTimeDetectorService.getCapabilitiesAndConfig();
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

        assertEquals(configuration.capabilitiesAndConfig(),
                mTimeDetectorService.getCapabilitiesAndConfig());

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                anyString());
    }

    @Test(expected = SecurityException.class)
    public void testAddListener_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        ITimeDetectorListener mockListener = mock(ITimeDetectorListener.class);
        try {
            mTimeDetectorService.addListener(mockListener);
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

        ITimeDetectorListener mockListener = mock(ITimeDetectorListener.class);
        try {
            mTimeDetectorService.removeListener(mockListener);
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
        ITimeDetectorListener mockListener = mock(ITimeDetectorListener.class);

        {
            doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());
            when(mockListener.asBinder()).thenReturn(mockListenerBinder);

            mTimeDetectorService.addListener(mockListener);

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
            TimeConfiguration autoDetectEnabledConfiguration =
                    createTimeConfiguration(true /* autoDetectionEnabled */);
            mTimeDetectorService.updateConfiguration(autoDetectEnabledConfiguration);

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
            mTimeDetectorService.removeListener(mockListener);

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

            TimeConfiguration autoDetectDisabledConfiguration =
                    createTimeConfiguration(false /* autoDetectionEnabled */);
            mTimeDetectorService.updateConfiguration(autoDetectDisabledConfiguration);

            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION),
                    anyString());
            verify(mockListener, never()).onChange();
            verifyNoMoreInteractions(mockListenerBinder, mockListener, mMockContext);
            reset(mockListenerBinder, mockListener, mMockContext);
        }
    }

    @Test(expected = SecurityException.class)
    public void testSuggestTelephonyTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TelephonyTimeSuggestion timeSuggestion = createTelephonyTimeSuggestion();

        try {
            mTimeDetectorService.suggestTelephonyTime(timeSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE),
                    anyString());
        }
    }

    @Test
    public void testSuggestTelephonyTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TelephonyTimeSuggestion timeSuggestion = createTelephonyTimeSuggestion();
        mTimeDetectorService.suggestTelephonyTime(timeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE),
                anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mFakeTimeDetectorStrategy.verifySuggestTelephonyTimeCalled(timeSuggestion);
    }

    @Test(expected = SecurityException.class)
    public void testSuggestManualTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());
        ManualTimeSuggestion manualTimeSuggestion = createManualTimeSuggestion();

        try {
            mTimeDetectorService.suggestManualTime(manualTimeSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingOrSelfPermission(
                    eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE),
                    anyString());
        }
    }

    @Test
    public void testSuggestManualTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());

        ManualTimeSuggestion manualTimeSuggestion = createManualTimeSuggestion();

        assertTrue(mTimeDetectorService.suggestManualTime(manualTimeSuggestion));
        mFakeTimeDetectorStrategy.verifySuggestManualTimeCalled(
                mTestCallerIdentityInjector.getCallingUserId(), manualTimeSuggestion);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE),
                anyString());

    }

    @Test(expected = SecurityException.class)
    public void testSuggestNetworkTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());
        NetworkTimeSuggestion networkTimeSuggestion = createNetworkTimeSuggestion();

        try {
            mTimeDetectorService.suggestNetworkTime(networkTimeSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingOrSelfPermission(
                    eq(android.Manifest.permission.SET_TIME), anyString());
        }
    }

    @Test
    public void testSuggestNetworkTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());

        NetworkTimeSuggestion networkTimeSuggestion = createNetworkTimeSuggestion();
        mTimeDetectorService.suggestNetworkTime(networkTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mFakeTimeDetectorStrategy.verifySuggestNetworkTimeCalled(networkTimeSuggestion);
    }

    @Test(expected = SecurityException.class)
    public void testSuggestGnssTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());
        GnssTimeSuggestion gnssTimeSuggestion = createGnssTimeSuggestion();

        try {
            mTimeDetectorService.suggestGnssTime(gnssTimeSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingOrSelfPermission(
                    eq(android.Manifest.permission.SET_TIME), anyString());
        }
    }

    @Test
    public void testSuggestGnssTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());

        GnssTimeSuggestion gnssTimeSuggestion = createGnssTimeSuggestion();
        mTimeDetectorService.suggestGnssTime(gnssTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mFakeTimeDetectorStrategy.verifySuggestGnssTimeCalled(gnssTimeSuggestion);
    }

    @Test(expected = SecurityException.class)
    public void testSuggestExternalTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        ExternalTimeSuggestion externalTimeSuggestion = createExternalTimeSuggestion();

        try {
            mTimeDetectorService.suggestExternalTime(externalTimeSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.SUGGEST_EXTERNAL_TIME), anyString());
        }
    }

    @Test
    public void testSuggestExternalTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        ExternalTimeSuggestion externalTimeSuggestion = createExternalTimeSuggestion();
        mTimeDetectorService.suggestExternalTime(externalTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_EXTERNAL_TIME), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mFakeTimeDetectorStrategy.verifySuggestExternalTimeCalled(externalTimeSuggestion);
    }

    @Test
    public void testLatestNetworkTime() {
        NtpTrustedTime.TimeResult latestNetworkTime =
                new NtpTrustedTime.TimeResult(1234L, 54321L, 999);
        when(mMockNtpTrustedTime.getCachedTimeResult())
                .thenReturn(latestNetworkTime);
        TimePoint expected = new TimePoint(latestNetworkTime.getTimeMillis(),
                latestNetworkTime.getElapsedRealtimeMillis());
        assertEquals(expected, mTimeDetectorService.latestNetworkTime());
    }

    @Test
    public void testLatestNetworkTime_noTimeAvailable() {
        when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null);
        assertThrows(ParcelableException.class, () -> mTimeDetectorService.latestNetworkTime());
    }

    @Test
    public void testDump() {
        when(mMockContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        PrintWriter pw = new PrintWriter(new StringWriter());
        mTimeDetectorService.dump(null, pw, null);

        verify(mMockContext).checkCallingOrSelfPermission(eq(android.Manifest.permission.DUMP));
        mFakeTimeDetectorStrategy.verifyDumpCalled();
    }

    private static TimeConfiguration createTimeConfiguration(boolean autoDetectionEnabled) {
        return new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(autoDetectionEnabled)
                .build();
    }

    private static ConfigurationInternal createConfigurationInternal(boolean autoDetectionEnabled) {
        return new ConfigurationInternal.Builder(ARBITRARY_USER_ID)
                .setUserConfigAllowed(true)
                .setAutoDetectionSupported(true)
                .setSystemClockUpdateThresholdMillis(ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS)
                .setAutoTimeLowerBound(ARBITRARY_AUTO_TIME_LOWER_BOUND)
                .setOriginPriorities(ARBITRARY_ORIGIN_PRIORITIES)
                .setDeviceHasY2038Issue(true)
                .setAutoDetectionEnabledSetting(autoDetectionEnabled)
                .build();
    }

    private static TelephonyTimeSuggestion createTelephonyTimeSuggestion() {
        int slotIndex = 1234;
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUnixEpochTime(timeValue)
                .build();
    }

    private static ManualTimeSuggestion createManualTimeSuggestion() {
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new ManualTimeSuggestion(timeValue);
    }

    private static NetworkTimeSuggestion createNetworkTimeSuggestion() {
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new NetworkTimeSuggestion(timeValue, 123);
    }

    private static GnssTimeSuggestion createGnssTimeSuggestion() {
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new GnssTimeSuggestion(timeValue);
    }

    private static ExternalTimeSuggestion createExternalTimeSuggestion() {
        return new ExternalTimeSuggestion(100L, 1_000_000L);
    }
}
