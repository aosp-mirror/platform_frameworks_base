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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.TimestampedValue;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.timezonedetector.TestHandler;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorServiceTest {

    private Context mMockContext;
    private StubbedTimeDetectorStrategy mStubbedTimeDetectorStrategy;

    private TimeDetectorService mTimeDetectorService;
    private HandlerThread mHandlerThread;
    private TestHandler mTestHandler;


    @Before
    public void setUp() {
        mMockContext = mock(Context.class);

        // Create a thread + handler for processing the work that the service posts.
        mHandlerThread = new HandlerThread("TimeDetectorServiceTest");
        mHandlerThread.start();
        mTestHandler = new TestHandler(mHandlerThread.getLooper());

        mStubbedTimeDetectorStrategy = new StubbedTimeDetectorStrategy();

        mTimeDetectorService = new TimeDetectorService(
                mMockContext, mTestHandler, mStubbedTimeDetectorStrategy);
    }

    @After
    public void tearDown() throws Exception {
        mHandlerThread.quit();
        mHandlerThread.join();
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
        mStubbedTimeDetectorStrategy.verifySuggestTelephonyTimeCalled(timeSuggestion);
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
        mTimeDetectorService.suggestManualTime(manualTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE),
                anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mStubbedTimeDetectorStrategy.verifySuggestManualTimeCalled(manualTimeSuggestion);
    }

    @Test(expected = SecurityException.class)
    public void testSuggestNetworkTime_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());
        NetworkTimeSuggestion NetworkTimeSuggestion = createNetworkTimeSuggestion();

        try {
            mTimeDetectorService.suggestNetworkTime(NetworkTimeSuggestion);
            fail();
        } finally {
            verify(mMockContext).enforceCallingOrSelfPermission(
                    eq(android.Manifest.permission.SET_TIME), anyString());
        }
    }

    @Test
    public void testSuggestNetworkTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());

        NetworkTimeSuggestion NetworkTimeSuggestion = createNetworkTimeSuggestion();
        mTimeDetectorService.suggestNetworkTime(NetworkTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SET_TIME), anyString());

        mTestHandler.waitForMessagesToBeProcessed();
        mStubbedTimeDetectorStrategy.verifySuggestNetworkTimeCalled(NetworkTimeSuggestion);
    }

    @Test
    public void testDump() {
        when(mMockContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        mTimeDetectorService.dump(null, null, null);

        verify(mMockContext).checkCallingOrSelfPermission(eq(android.Manifest.permission.DUMP));
        mStubbedTimeDetectorStrategy.verifyDumpCalled();
    }

    @Test
    public void testAutoTimeDetectionToggle() throws Exception {
        mTimeDetectorService.handleAutoTimeDetectionChanged();
        mTestHandler.assertTotalMessagesEnqueued(1);
        mTestHandler.waitForMessagesToBeProcessed();
        mStubbedTimeDetectorStrategy.verifyHandleAutoTimeDetectionChangedCalled();

        mStubbedTimeDetectorStrategy.resetCallTracking();

        mTimeDetectorService.handleAutoTimeDetectionChanged();
        mTestHandler.assertTotalMessagesEnqueued(2);
        mTestHandler.waitForMessagesToBeProcessed();
        mStubbedTimeDetectorStrategy.verifyHandleAutoTimeDetectionChangedCalled();
    }

    private static TelephonyTimeSuggestion createTelephonyTimeSuggestion() {
        int slotIndex = 1234;
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUtcTime(timeValue)
                .build();
    }

    private static ManualTimeSuggestion createManualTimeSuggestion() {
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new ManualTimeSuggestion(timeValue);
    }

    private static NetworkTimeSuggestion createNetworkTimeSuggestion() {
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new NetworkTimeSuggestion(timeValue);
    }

    private static class StubbedTimeDetectorStrategy implements TimeDetectorStrategy {

        // Call tracking.
        private TelephonyTimeSuggestion mLastTelephonySuggestion;
        private ManualTimeSuggestion mLastManualSuggestion;
        private NetworkTimeSuggestion mLastNetworkSuggestion;
        private boolean mHandleAutoTimeDetectionChangedCalled;
        private boolean mDumpCalled;

        @Override
        public void initialize(Callback ignored) {
        }

        @Override
        public void suggestTelephonyTime(TelephonyTimeSuggestion timeSuggestion) {
            mLastTelephonySuggestion = timeSuggestion;
        }

        @Override
        public void suggestManualTime(ManualTimeSuggestion timeSuggestion) {
            mLastManualSuggestion = timeSuggestion;
        }

        @Override
        public void suggestNetworkTime(NetworkTimeSuggestion timeSuggestion) {
            mLastNetworkSuggestion = timeSuggestion;
        }

        @Override
        public void handleAutoTimeDetectionChanged() {
            mHandleAutoTimeDetectionChangedCalled = true;
        }

        @Override
        public void dump(PrintWriter pw, String[] args) {
            mDumpCalled = true;
        }

        void resetCallTracking() {
            mLastTelephonySuggestion = null;
            mLastManualSuggestion = null;
            mLastNetworkSuggestion = null;
            mHandleAutoTimeDetectionChangedCalled = false;
            mDumpCalled = false;
        }

        void verifySuggestTelephonyTimeCalled(TelephonyTimeSuggestion expectedSuggestion) {
            assertEquals(expectedSuggestion, mLastTelephonySuggestion);
        }

        public void verifySuggestManualTimeCalled(ManualTimeSuggestion expectedSuggestion) {
            assertEquals(expectedSuggestion, mLastManualSuggestion);
        }

        public void verifySuggestNetworkTimeCalled(NetworkTimeSuggestion expectedSuggestion) {
            assertEquals(expectedSuggestion, mLastNetworkSuggestion);
        }

        void verifyHandleAutoTimeDetectionChangedCalled() {
            assertTrue(mHandleAutoTimeDetectionChangedCalled);
        }

        void verifyDumpCalled() {
            assertTrue(mDumpCalled);
        }
    }
}
