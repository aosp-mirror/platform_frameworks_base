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

import android.util.TimestampedValue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TimeDetectorStrategyTest {

    @Test
    public void testGetTimeAt() {
        long timeMillis = 1000L;
        int referenceTimeMillis = 100;
        TimestampedValue<Long> timestampedValue =
                new TimestampedValue<>(referenceTimeMillis, timeMillis);
        // Reference time is after the timestamp.
        assertEquals(
                timeMillis + (125 - referenceTimeMillis),
                TimeDetectorStrategy.getTimeAt(timestampedValue, 125));

        // Reference time is before the timestamp.
        assertEquals(
                timeMillis + (75 - referenceTimeMillis),
                TimeDetectorStrategy.getTimeAt(timestampedValue, 75));
    }
}
