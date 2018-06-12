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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.timedetector.TimeSignal;
import android.content.Context;
import android.support.test.runner.AndroidJUnit4;
import android.util.TimestampedValue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorServiceTest {

    private TimeDetectorService mTimeDetectorService;

    private Context mMockContext;
    private TimeDetectorStrategy mMockTimeDetectorStrategy;

    @Before
    public void setUp() {
        mMockContext = mock(Context.class);
        mMockTimeDetectorStrategy = mock(TimeDetectorStrategy.class);
        mTimeDetectorService = new TimeDetectorService(mMockContext, mMockTimeDetectorStrategy);
    }

    @After
    public void tearDown() {
        verifyNoMoreInteractions(mMockContext, mMockTimeDetectorStrategy);
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
        verify(mMockTimeDetectorStrategy).suggestTime(timeSignal);
    }

    private static TimeSignal createNitzTimeSignal() {
        TimestampedValue<Long> timeValue = new TimestampedValue<>(100L, 1_000_000L);
        return new TimeSignal(TimeSignal.SOURCE_ID_NITZ, timeValue);
    }
}
