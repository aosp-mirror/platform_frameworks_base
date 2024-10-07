/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.power.stats;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PowerStatsSchedulerTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule.Builder()
            .setProvideMainThread(true)
            .build();

    @Test
    public void alignToWallClock() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        // Expect the aligned value to be adjusted by 1 min 30 sec - rounded to the next 15 min
        assertThat(PowerStatsScheduler.alignToWallClock(123, TimeUnit.MINUTES.toMillis(15),
                123 + TimeUnit.HOURS.toMillis(2),
                Instant.parse("2007-12-03T10:13:30.00Z").toEpochMilli())).isEqualTo(
                123 + Duration.parse("PT1M30S").toMillis());

        // Expect the aligned value to be adjusted by 2 min 45 sec - rounded to the next 15 min
        assertThat(PowerStatsScheduler.alignToWallClock(123, TimeUnit.MINUTES.toMillis(15),
                123 + TimeUnit.HOURS.toMillis(2),
                Instant.parse("2007-12-03T10:57:15.00Z").toEpochMilli())).isEqualTo(
                123 + Duration.parse("PT2M45S").toMillis());

        // Expect the aligned value to be adjusted by 15 sec - rounded to the next 1 min
        assertThat(PowerStatsScheduler.alignToWallClock(123, TimeUnit.MINUTES.toMillis(1),
                123 + TimeUnit.HOURS.toMillis(2),
                Instant.parse("2007-12-03T10:14:45.00Z").toEpochMilli())).isEqualTo(
                123 + Duration.parse("PT15S").toMillis());

        // Expect the aligned value to be adjusted by 1 hour 46 min 30 sec -
        // rounded to the next 3 hours
        assertThat(PowerStatsScheduler.alignToWallClock(123, TimeUnit.HOURS.toMillis(3),
                123 + TimeUnit.HOURS.toMillis(9),
                Instant.parse("2007-12-03T10:13:30.00Z").toEpochMilli())).isEqualTo(
                123 + Duration.parse("PT1H46M30S").toMillis());
    }
}
