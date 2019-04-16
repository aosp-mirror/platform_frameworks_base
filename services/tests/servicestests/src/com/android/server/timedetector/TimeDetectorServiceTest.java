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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.timedetector.TimeSignal;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.TimestampedValue;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.timedetector.TimeDetectorStrategy.Callback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorServiceTest {

    private Context mMockContext;
    private StubbedTimeDetectorStrategy mStubbedTimeDetectorStrategy;
    private Callback mMockCallback;

    private TimeDetectorService mTimeDetectorService;

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);
        mMockCallback = mock(Callback.class);
        mStubbedTimeDetectorStrategy = new StubbedTimeDetectorStrategy();

        mTimeDetectorService = new TimeDetectorService(
                mMockContext, mMockCallback,
                mStubbedTimeDetectorStrategy);
    }

    @Test(expected=SecurityException.class)
    public void testStubbedCall_withoutPermission() {
        doThrow(new SecurityException("Mock"))
                .when(mMockContext).enforceCallingPermission(anyString(), any());
        TimeSignal timeSignal = createNitzTimeSignal();

        try {
            mTimeDetectorService.suggestTime(timeSignal);
        } finally {
            verify(mMockContext).enforceCallingPermission(
                    eq(android.Manifest.permission.SET_TIME), anyString());
        }
    }

    @Test
    public void testSuggestTime() {
        doNothing().when(mMockContext).enforceCallingPermission(anyString(), any());

        TimeSignal timeSignal = createNitzTimeSignal();
        mTimeDetectorService.suggestTime(timeSignal);

        verify(mMockContext)
                .enforceCallingPermission(eq(android.Manifest.permission.SET_TIME), anyString());
        mStubbedTimeDetectorStrategy.verifySuggestTimeCalled(timeSignal);
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
    public void testAutoTimeDetectionToggle() {
        when(mMockCallback.isTimeDetectionEnabled()).thenReturn(true);

        mTimeDetectorService.handleAutoTimeDetectionToggle();

        mStubbedTimeDetectorStrategy.verifyHandleAutoTimeDetectionToggleCalled(true);

        when(mMockCallback.isTimeDetectionEnabled()).thenReturn(false);

        mTimeDetectorService.handleAutoTimeDetectionToggle();

        mStubbedTimeDetectorStrategy.verifyHandleAutoTimeDetectionToggleCalled(false);
    }

    private static TimeSignal createNitzTimeSignal() {
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new TimeSignal(TimeSignal.SOURCE_ID_NITZ, timeValue);
    }

    private static class StubbedTimeDetectorStrategy implements TimeDetectorStrategy {

        // Call tracking.
        private TimeSignal mLastSuggestedTime;
        private Boolean mLastAutoTimeDetectionToggle;
        private boolean mDumpCalled;

        @Override
        public void initialize(Callback ignored) {
        }

        @Override
        public void suggestTime(TimeSignal timeSignal) {
            resetCallTracking();
            mLastSuggestedTime = timeSignal;
        }

        @Override
        public void handleAutoTimeDetectionToggle(boolean enabled) {
            resetCallTracking();
            mLastAutoTimeDetectionToggle = enabled;
        }

        @Override
        public void dump(PrintWriter pw, String[] args) {
            resetCallTracking();
            mDumpCalled = true;
        }

        void resetCallTracking() {
            mLastSuggestedTime = null;
            mLastAutoTimeDetectionToggle = null;
            mDumpCalled = false;
        }

        void verifySuggestTimeCalled(TimeSignal expectedSignal) {
            assertEquals(expectedSignal, mLastSuggestedTime);
        }

        void verifyHandleAutoTimeDetectionToggleCalled(boolean expectedEnable) {
            assertNotNull(mLastAutoTimeDetectionToggle);
            assertEquals(expectedEnable, mLastAutoTimeDetectionToggle);
        }

        void verifyDumpCalled() {
            assertTrue(mDumpCalled);
        }
    }
}
