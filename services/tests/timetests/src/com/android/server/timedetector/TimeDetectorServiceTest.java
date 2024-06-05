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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
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

import android.app.time.ExternalTimeSuggestion;
import android.app.time.ITimeDetectorListener;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.time.TimeState;
import android.app.time.UnixEpochTime;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelableException;
import android.util.NtpTrustedTime;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.location.gnss.TimeDetectorNetworkTimeHelper;
import com.android.server.timezonedetector.TestCallerIdentityInjector;
import com.android.server.timezonedetector.TestHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.time.Instant;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorServiceTest {

    private static final int ARBITRARY_USER_ID = 9999;
    private static final int ARBITRARY_SYSTEM_CLOCK_UPDATE_THRESHOLD_MILLIS = 1234;
    private static final Instant ARBITRARY_SUGGESTION_LOWER_BOUND = Instant.ofEpochMilli(0);
    private static final Instant ARBITRARY_SUGGESTION_UPPER_BOUND =
            Instant.ofEpochMilli(Long.MAX_VALUE);
    private static final int[] ARBITRARY_ORIGIN_PRIORITIES = { ORIGIN_NETWORK };

    private Context mMockContext;

    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;
    private TestCallerIdentityInjector mTestCallerIdentityInjector;
    private FakeTimeDetectorStrategy mFakeTimeDetectorStrategySpy;

    private NtpTrustedTime mMockNtpTrustedTime;
    private TimeDetectorService mTimeDetectorService;

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeDetectorServiceTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());

        mTestCallerIdentityInjector = new TestCallerIdentityInjector();
        mTestCallerIdentityInjector.initializeCallingUserId(ARBITRARY_USER_ID);

        mFakeTimeDetectorStrategySpy = spy(new FakeTimeDetectorStrategy());
        mMockNtpTrustedTime = mock(NtpTrustedTime.class);

        mTimeDetectorService = new TimeDetectorService(
                mMockContext, mTestHandler, mTestCallerIdentityInjector,
                mFakeTimeDetectorStrategySpy, mMockNtpTrustedTime);
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

        assertThrows(SecurityException.class, mTimeDetectorService::getCapabilitiesAndConfig);
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testGetCapabilitiesAndConfig() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        ConfigurationInternal configuration =
                createConfigurationInternal(true /* autoDetectionEnabled*/);
        mFakeTimeDetectorStrategySpy.initializeConfiguration(configuration);

        TimeCapabilitiesAndConfig actualCapabilitiesAndConfig =
                mTimeDetectorService.getCapabilitiesAndConfig();
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
        int expectedUserId = mTestCallerIdentityInjector.getCallingUserId();
        verify(mFakeTimeDetectorStrategySpy).getCapabilitiesAndConfig(expectedUserId, false);

        boolean bypassUserPolicyChecks = false;
        TimeCapabilitiesAndConfig expectedCapabilitiesAndConfig =
                configuration.createCapabilitiesAndConfig(bypassUserPolicyChecks);
        assertEquals(expectedCapabilitiesAndConfig, actualCapabilitiesAndConfig);
    }

    @Test
    public void testAddListener_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        ITimeDetectorListener mockListener = mock(ITimeDetectorListener.class);
        assertThrows(SecurityException.class, () -> mTimeDetectorService.addListener(mockListener));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testRemoveListener_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        ITimeDetectorListener mockListener = mock(ITimeDetectorListener.class);
        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.removeListener(mockListener));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testListenerRegistrationAndCallbacks() throws Exception {
        ConfigurationInternal initialConfiguration =
                createConfigurationInternal(false /* autoDetectionEnabled */);
        mFakeTimeDetectorStrategySpy.initializeConfiguration(initialConfiguration);

        IBinder mockListenerBinder = mock(IBinder.class);
        ITimeDetectorListener mockListener = mock(ITimeDetectorListener.class);

        {
            doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());
            when(mockListener.asBinder()).thenReturn(mockListenerBinder);

            mTimeDetectorService.addListener(mockListener);

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
            TimeConfiguration autoDetectEnabledConfiguration =
                    createTimeConfiguration(true /* autoDetectionEnabled */);
            mTimeDetectorService.updateConfiguration(autoDetectEnabledConfiguration);

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
            mTimeDetectorService.removeListener(mockListener);

            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
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
                    eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
            verify(mockListener, never()).onChange();
            verifyNoMoreInteractions(mockListenerBinder, mockListener, mMockContext);
            reset(mockListenerBinder, mockListener, mMockContext);
        }
    }

    @Test
    public void testSuggestTelephonyTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TelephonyTimeSuggestion timeSuggestion = createTelephonyTimeSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.suggestTelephonyTime(timeSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE), anyString());
    }

    @Test
    public void testSuggestTelephonyTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TelephonyTimeSuggestion timeSuggestion = createTelephonyTimeSuggestion();
        mTimeDetectorService.suggestTelephonyTime(timeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        verify(mFakeTimeDetectorStrategySpy).suggestTelephonyTime(timeSuggestion);
    }

    @Test
    public void testSuggestManualTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        ManualTimeSuggestion manualTimeSuggestion = createManualTimeSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.suggestManualTime(manualTimeSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE), anyString());
    }

    @Test
    public void testSuggestManualTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        ManualTimeSuggestion manualTimeSuggestion = createManualTimeSuggestion();

        assertTrue(mTimeDetectorService.suggestManualTime(manualTimeSuggestion));
        int expectedUserId = mTestCallerIdentityInjector.getCallingUserId();
        boolean expectedBypassUserPolicyChecks = false;
        verify(mFakeTimeDetectorStrategySpy).suggestManualTime(
                expectedUserId, manualTimeSuggestion, expectedBypassUserPolicyChecks);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE), anyString());

    }

    @Test
    public void testSuggestNetworkTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        NetworkTimeSuggestion networkTimeSuggestion = createNetworkTimeSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.suggestNetworkTime(networkTimeSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());
    }

    @Test
    public void testSuggestNetworkTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        NetworkTimeSuggestion networkTimeSuggestion = createNetworkTimeSuggestion();
        mTimeDetectorService.suggestNetworkTime(networkTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        verify(mFakeTimeDetectorStrategySpy).suggestNetworkTime(networkTimeSuggestion);
    }

    @Test
    public void testSuggestGnssTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        GnssTimeSuggestion gnssTimeSuggestion = createGnssTimeSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.suggestGnssTime(gnssTimeSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());
    }

    @Test
    public void testSuggestGnssTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        GnssTimeSuggestion gnssTimeSuggestion = createGnssTimeSuggestion();
        mTimeDetectorService.suggestGnssTime(gnssTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        verify(mFakeTimeDetectorStrategySpy).suggestGnssTime(gnssTimeSuggestion);
    }

    @Test
    public void testSuggestExternalTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        ExternalTimeSuggestion externalTimeSuggestion = createExternalTimeSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.suggestExternalTime(externalTimeSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SUGGEST_EXTERNAL_TIME), anyString());
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
        verify(mFakeTimeDetectorStrategySpy).suggestExternalTime(externalTimeSuggestion);
    }

    @Test
    public void testClearNetworkTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.clearLatestNetworkTime());
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());
    }

    @Test
    public void testClearLatestNetworkSuggestion() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        mTimeDetectorService.clearLatestNetworkTime();

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());
        verify(mFakeTimeDetectorStrategySpy).clearLatestNetworkSuggestion();
    }

    @Test
    public void testGetLatestNetworkSuggestion() {
        NetworkTimeSuggestion latestNetworkSuggestion = createNetworkTimeSuggestion();
        mFakeTimeDetectorStrategySpy.setLatestNetworkTime(latestNetworkSuggestion);

        assertEquals(latestNetworkSuggestion, mTimeDetectorService.getLatestNetworkSuggestion());
    }

    @Test
    public void testGetLatestNetworkSuggestion_noTimeAvailable() {
        mFakeTimeDetectorStrategySpy.setLatestNetworkTime(null);

        assertNull(mTimeDetectorService.getLatestNetworkSuggestion());
    }

    @Test
    public void testLatestNetworkTime() {
        if (TimeDetectorNetworkTimeHelper.isInUse()) {
            NetworkTimeSuggestion latestNetworkSuggestion = createNetworkTimeSuggestion();
            mFakeTimeDetectorStrategySpy.setLatestNetworkTime(latestNetworkSuggestion);

            assertEquals(latestNetworkSuggestion.getUnixEpochTime(),
                    mTimeDetectorService.latestNetworkTime());
        } else {
            NtpTrustedTime.TimeResult latestNetworkTime = new NtpTrustedTime.TimeResult(
                    1234L, 54321L, 999, InetSocketAddress.createUnresolved("test.timeserver", 123));
            when(mMockNtpTrustedTime.getCachedTimeResult())
                    .thenReturn(latestNetworkTime);
            UnixEpochTime expected = new UnixEpochTime(
                    latestNetworkTime.getElapsedRealtimeMillis(),
                    latestNetworkTime.getTimeMillis());
            assertEquals(expected, mTimeDetectorService.latestNetworkTime());
        }
    }

    @Test
    public void testLatestNetworkTime_noTimeAvailable() {
        if (TimeDetectorNetworkTimeHelper.isInUse()) {
            mFakeTimeDetectorStrategySpy.setLatestNetworkTime(null);
        } else {
            when(mMockNtpTrustedTime.getCachedTimeResult()).thenReturn(null);
        }
        assertThrows(ParcelableException.class, () -> mTimeDetectorService.latestNetworkTime());
    }

    @Test
    public void testGetTimeState() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());
        TimeState fakeState = new TimeState(new UnixEpochTime(12345L, 98765L), true);
        mFakeTimeDetectorStrategySpy.setTimeState(fakeState);

        TimeState actualState = mTimeDetectorService.getTimeState();

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
        assertEquals(actualState, fakeState);
    }

    @Test
    public void testGetTimeState_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        assertThrows(SecurityException.class, mTimeDetectorService::getTimeState);
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testSetTimeState() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TimeState state = new TimeState(new UnixEpochTime(12345L, 98765L), true);
        mTimeDetectorService.setTimeState(state);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
        assertEquals(mFakeTimeDetectorStrategySpy.getTimeState(), state);
    }

    @Test
    public void testSetTimeState_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        TimeState state = new TimeState(new UnixEpochTime(12345L, 98765L), true);
        assertThrows(SecurityException.class, () -> mTimeDetectorService.setTimeState(state));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testConfirmTime() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        UnixEpochTime confirmationTime = new UnixEpochTime(12345L, 98765L);
        // The fake strategy always returns false.
        assertFalse(mTimeDetectorService.confirmTime(confirmationTime));

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
        verify(mFakeTimeDetectorStrategySpy).confirmTime(confirmationTime);
    }

    @Test
    public void testConfirmTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());

        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.confirmTime(new UnixEpochTime(12345L, 98765L)));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testSetManualTime() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        ManualTimeSuggestion timeSuggestion = createManualTimeSuggestion();

        boolean expectedResult = true; // The test strategy always returns true.
        assertEquals(expectedResult,
                mTimeDetectorService.setManualTime(timeSuggestion));

        // The service calls "suggestManualTime()" because the logic is the same.
        int expectedUserId = mTestCallerIdentityInjector.getCallingUserId();
        boolean expectedBypassUserPolicyChecks = false;
        verify(mFakeTimeDetectorStrategySpy).suggestManualTime(
                expectedUserId, timeSuggestion, expectedBypassUserPolicyChecks);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testSetManualTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        ManualTimeSuggestion timeSuggestion = createManualTimeSuggestion();

        assertThrows(SecurityException.class,
                () -> mTimeDetectorService.setManualTime(timeSuggestion));
        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION), anyString());
    }

    @Test
    public void testDump() {
        when(mMockContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        PrintWriter pw = new PrintWriter(new StringWriter());
        mTimeDetectorService.dump(null, pw, null);

        verify(mMockContext).checkCallingOrSelfPermission(eq(android.Manifest.permission.DUMP));
        verify(mFakeTimeDetectorStrategySpy).dump(any(), any());
    }

    private static TimeConfiguration createTimeConfiguration(boolean autoDetectionEnabled) {
        return new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(autoDetectionEnabled)
                .build();
    }

    static ConfigurationInternal createConfigurationInternal(boolean autoDetectionEnabled) {
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

    private static TelephonyTimeSuggestion createTelephonyTimeSuggestion() {
        int slotIndex = 1234;
        UnixEpochTime timeValue = new UnixEpochTime(100L, 1_000_000L);
        return new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUnixEpochTime(timeValue)
                .build();
    }

    private static ManualTimeSuggestion createManualTimeSuggestion() {
        UnixEpochTime timeValue = new UnixEpochTime(100L, 1_000_000L);
        return new ManualTimeSuggestion(timeValue);
    }

    private static NetworkTimeSuggestion createNetworkTimeSuggestion() {
        UnixEpochTime timeValue = new UnixEpochTime(100L, 1_000_000L);
        return new NetworkTimeSuggestion(timeValue, 123);
    }

    private static GnssTimeSuggestion createGnssTimeSuggestion() {
        UnixEpochTime timeValue = new UnixEpochTime(100L, 1_000_000L);
        return new GnssTimeSuggestion(timeValue);
    }

    private static ExternalTimeSuggestion createExternalTimeSuggestion() {
        return new ExternalTimeSuggestion(100L, 1_000_000L);
    }
}
