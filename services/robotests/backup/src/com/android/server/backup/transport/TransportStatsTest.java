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
 * limitations under the License
 */

package com.android.server.backup.transport;

import static com.android.server.backup.testing.TransportData.backupTransport;
import static com.android.server.backup.testing.TransportData.d2dTransport;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.platform.test.annotations.Presubmit;

import com.android.server.backup.transport.TransportStats.Stats;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class TransportStatsTest {
    private static final double TOLERANCE = 0.0001;

    private TransportStats mTransportStats;
    private ComponentName mTransportComponent1;
    private ComponentName mTransportComponent2;

    @Before
    public void setUp() throws Exception {
        mTransportStats = new TransportStats();
        mTransportComponent1 = backupTransport().getTransportComponent();
        mTransportComponent2 = d2dTransport().getTransportComponent();
    }

    @Test
    public void testRegisterConnectionTime() {
        mTransportStats.registerConnectionTime(mTransportComponent1, 50L);

        Stats stats = mTransportStats.getStatsForTransport(mTransportComponent1);
        assertThat(stats.average).isWithin(TOLERANCE).of(50);
        assertThat(stats.max).isEqualTo(50L);
        assertThat(stats.min).isEqualTo(50L);
        assertThat(stats.n).isEqualTo(1);
    }

    @Test
    public void testRegisterConnectionTime_whenHasAlreadyOneSample() {
        mTransportStats.registerConnectionTime(mTransportComponent1, 50L);

        mTransportStats.registerConnectionTime(mTransportComponent1, 100L);

        Stats stats = mTransportStats.getStatsForTransport(mTransportComponent1);
        assertThat(stats.average).isWithin(TOLERANCE).of(75);
        assertThat(stats.max).isEqualTo(100L);
        assertThat(stats.min).isEqualTo(50L);
        assertThat(stats.n).isEqualTo(2);
    }

    @Test
    public void testGetStatsForTransport() {
        mTransportStats.registerConnectionTime(mTransportComponent1, 10L);
        mTransportStats.registerConnectionTime(mTransportComponent2, 20L);

        Stats stats = mTransportStats.getStatsForTransport(mTransportComponent1);

        assertThat(stats.average).isWithin(TOLERANCE).of(10);
        assertThat(stats.max).isEqualTo(10L);
        assertThat(stats.min).isEqualTo(10L);
        assertThat(stats.n).isEqualTo(1);
    }

    @Test
    public void testMerge() {
        mTransportStats.registerConnectionTime(mTransportComponent1, 10L);
        mTransportStats.registerConnectionTime(mTransportComponent2, 20L);
        Stats stats1 = mTransportStats.getStatsForTransport(mTransportComponent1);
        Stats stats2 = mTransportStats.getStatsForTransport(mTransportComponent2);

        Stats stats = Stats.merge(stats1, stats2);

        assertThat(stats.average).isWithin(TOLERANCE).of(15);
        assertThat(stats.max).isEqualTo(20L);
        assertThat(stats.min).isEqualTo(10L);
        assertThat(stats.n).isEqualTo(2);
    }
}
