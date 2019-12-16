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
import android.app.timedetector.PhoneTimeSuggestion;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.TimestampedValue;

import androidx.test.runner.AndroidJUnit4;

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

    @Test
    public void testSuggestPhoneTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        PhoneTimeSuggestion phoneTimeSuggestion = createPhoneTimeSuggestion();
        mTimeDetectorService.suggestPhoneTime(phoneTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingPermission(
                eq(android.Manifest.permission.SET_TIME),
                anyString());

        mTestHandler.waitForEmptyQueue();
        mStubbedTimeDetectorStrategy.verifySuggestPhoneTimeCalled(phoneTimeSuggestion);
    }

    @Test
    public void testSuggestManualTime() throws Exception {
        doNothing().when(mMockContext).enforceCallingOrSelfPermission(anyString(), any());

        ManualTimeSuggestion manualTimeSuggestion = createManualTimeSuggestion();
        mTimeDetectorService.suggestManualTime(manualTimeSuggestion);
        mTestHandler.assertTotalMessagesEnqueued(1);

        verify(mMockContext).enforceCallingOrSelfPermission(
                eq(android.Manifest.permission.SET_TIME),
                anyString());

        mTestHandler.waitForEmptyQueue();
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

        mTestHandler.waitForEmptyQueue();
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
        mTimeDetectorService.handleAutoTimeDetectionToggle();
        mTestHandler.assertTotalMessagesEnqueued(1);
        mTestHandler.waitForEmptyQueue();
        mStubbedTimeDetectorStrategy.verifyHandleAutoTimeDetectionToggleCalled();

        mTimeDetectorService.handleAutoTimeDetectionToggle();
        mTestHandler.assertTotalMessagesEnqueued(2);
        mTestHandler.waitForEmptyQueue();
        mStubbedTimeDetectorStrategy.verifyHandleAutoTimeDetectionToggleCalled();
    }

    private static PhoneTimeSuggestion createPhoneTimeSuggestion() {
        int phoneId = 1234;
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new PhoneTimeSuggestion.Builder(phoneId)
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
        private PhoneTimeSuggestion mLastPhoneSuggestion;
        private ManualTimeSuggestion mLastManualSuggestion;
        private NetworkTimeSuggestion mLastNetworkSuggestion;
        private boolean mLastAutoTimeDetectionToggleCalled;
        private boolean mDumpCalled;

        @Override
        public void initialize(Callback ignored) {
        }

        @Override
        public void suggestPhoneTime(PhoneTimeSuggestion timeSuggestion) {
            resetCallTracking();
            mLastPhoneSuggestion = timeSuggestion;
        }

        @Override
        public void suggestManualTime(ManualTimeSuggestion timeSuggestion) {
            resetCallTracking();
            mLastManualSuggestion = timeSuggestion;
        }

        @Override
        public void suggestNetworkTime(NetworkTimeSuggestion timeSuggestion) {
            resetCallTracking();
            mLastNetworkSuggestion = timeSuggestion;
        }

        @Override
        public void handleAutoTimeDetectionChanged() {
            resetCallTracking();
            mLastAutoTimeDetectionToggleCalled = true;
        }

        @Override
        public void dump(PrintWriter pw, String[] args) {
            resetCallTracking();
            mDumpCalled = true;
        }

        void resetCallTracking() {
            mLastPhoneSuggestion = null;
            mLastManualSuggestion = null;
            mLastNetworkSuggestion = null;
            mLastAutoTimeDetectionToggleCalled = false;
            mDumpCalled = false;
        }

        void verifySuggestPhoneTimeCalled(PhoneTimeSuggestion expectedSuggestion) {
            assertEquals(expectedSuggestion, mLastPhoneSuggestion);
        }

        public void verifySuggestManualTimeCalled(ManualTimeSuggestion expectedSuggestion) {
            assertEquals(expectedSuggestion, mLastManualSuggestion);
        }

        public void verifySuggestNetworkTimeCalled(NetworkTimeSuggestion expectedSuggestion) {
            assertEquals(expectedSuggestion, mLastNetworkSuggestion);
        }

        void verifyHandleAutoTimeDetectionToggleCalled() {
            assertTrue(mLastAutoTimeDetectionToggleCalled);
        }

        void verifyDumpCalled() {
            assertTrue(mDumpCalled);
        }
    }

    /**
     * A Handler that can track posts/sends and wait for work to be completed.
     */
    private static class TestHandler extends Handler {

        private int mMessagesSent;

        TestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            mMessagesSent++;
            return super.sendMessageAtTime(msg, uptimeMillis);
        }

        /** Asserts the number of messages posted or sent is as expected. */
        void assertTotalMessagesEnqueued(int expected) {
            assertEquals(expected, mMessagesSent);
        }

        /**
         * Waits for all currently enqueued work due to be processed to be completed before
         * returning.
         */
        void waitForEmptyQueue() throws InterruptedException {
            while (!getLooper().getQueue().isIdle()) {
                Thread.sleep(100);
            }
        }
    }
}
