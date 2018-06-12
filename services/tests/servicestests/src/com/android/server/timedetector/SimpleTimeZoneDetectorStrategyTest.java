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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.timedetector.TimeSignal;
import android.support.test.runner.AndroidJUnit4;
import android.util.TimestampedValue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SimpleTimeZoneDetectorStrategyTest {

    private TimeDetectorStrategy.Callback mMockCallback;

    private SimpleTimeDetectorStrategy mSimpleTimeZoneDetectorStrategy;

    @Before
    public void setUp() {
        mMockCallback = mock(TimeDetectorStrategy.Callback.class);
        mSimpleTimeZoneDetectorStrategy = new SimpleTimeDetectorStrategy();
        mSimpleTimeZoneDetectorStrategy.initialize(mMockCallback);
    }

    @Test
    public void testSuggestTime_nitz() {
        TimestampedValue<Long> utcTime = createUtcTime();
        TimeSignal timeSignal = new TimeSignal(TimeSignal.SOURCE_ID_NITZ, utcTime);

        mSimpleTimeZoneDetectorStrategy.suggestTime(timeSignal);

        verify(mMockCallback).setTime(utcTime);
    }

    @Test
    public void testSuggestTime_unknownSource() {
        TimestampedValue<Long> utcTime = createUtcTime();
        TimeSignal timeSignal = new TimeSignal("unknown", utcTime);
        mSimpleTimeZoneDetectorStrategy.suggestTime(timeSignal);

        verify(mMockCallback, never()).setTime(any());
    }

    private static TimestampedValue<Long> createUtcTime() {
        return new TimestampedValue<>(321L, 123456L);
    }
}
