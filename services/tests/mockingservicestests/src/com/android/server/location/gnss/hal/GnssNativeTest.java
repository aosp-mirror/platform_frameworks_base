/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.gnss.hal;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.location.gnss.GnssConfiguration;
import com.android.server.location.gnss.GnssPowerStats;
import com.android.server.location.injector.Injector;
import com.android.server.location.injector.TestInjector;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Objects;
import java.util.concurrent.Executor;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class GnssNativeTest {

    private @Mock Context mContext;
    private @Mock GnssConfiguration mMockConfiguration;
    private FakeGnssHal mFakeGnssHal;
    private GnssNative mGnssNative;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mFakeGnssHal = new FakeGnssHal();
        GnssNative.setGnssHalForTest(mFakeGnssHal);
        Injector injector = new TestInjector(mContext);
        mGnssNative = spy(Objects.requireNonNull(GnssNative.create(injector, mMockConfiguration)));
        mGnssNative.register();
    }

    @Test
    public void testRequestPowerStats_onNull_executesCallbackWithNull() {
        mFakeGnssHal.setPowerStats(null);
        Executor executor = spy(Runnable::run);
        GnssNative.PowerStatsCallback callback = spy(stats -> {});

        mGnssNative.requestPowerStats(executor, callback);

        verify(executor).execute(any());
        verify(callback).onReportPowerStats(null);
    }

    @Test
    public void testRequestPowerStats_onPowerStats_executesCallbackWithStats() {
        GnssPowerStats powerStats = new GnssPowerStats(1, 2, 3, 4, 5, 6, 7, 8, new double[]{9, 10});
        mFakeGnssHal.setPowerStats(powerStats);
        Executor executor = spy(Runnable::run);
        GnssNative.PowerStatsCallback callback = spy(stats -> {});

        mGnssNative.requestPowerStats(executor, callback);

        verify(executor).execute(any());
        verify(callback).onReportPowerStats(powerStats);
    }

    @Test
    public void testRequestPowerStatsBlocking_onNull_returnsNull() {
        mFakeGnssHal.setPowerStats(null);

        assertThat(mGnssNative.requestPowerStatsBlocking()).isNull();
    }

    @Test
    public void testRequestPowerStatsBlocking_onPowerStats_returnsStats() {
        GnssPowerStats powerStats = new GnssPowerStats(1, 2, 3, 4, 5, 6, 7, 8, new double[]{9, 10});
        mFakeGnssHal.setPowerStats(powerStats);

        assertThat(mGnssNative.requestPowerStatsBlocking()).isEqualTo(powerStats);
    }

    @Test
    public void testGetLastKnownPowerStats_onNull_preservesLastKnownPowerStats() {
        GnssPowerStats powerStats = new GnssPowerStats(1, 2, 3, 4, 5, 6, 7, 8, new double[]{9, 10});

        mGnssNative.reportGnssPowerStats(powerStats);
        assertThat(mGnssNative.getLastKnownPowerStats()).isEqualTo(powerStats);

        mGnssNative.reportGnssPowerStats(null);
        assertThat(mGnssNative.getLastKnownPowerStats()).isEqualTo(powerStats);
    }

    @Test
    public void testGetLastKnownPowerStats_onPowerStats_updatesLastKnownPowerStats() {
        GnssPowerStats powerStats1 = new GnssPowerStats(1, 2, 3, 4, 5, 6, 7, 8, new double[]{9, 0});
        GnssPowerStats powerStats2 = new GnssPowerStats(2, 3, 4, 5, 6, 7, 8, 9, new double[]{0, 9});

        mGnssNative.reportGnssPowerStats(powerStats1);
        assertThat(mGnssNative.getLastKnownPowerStats()).isEqualTo(powerStats1);

        mGnssNative.reportGnssPowerStats(powerStats2);
        assertThat(mGnssNative.getLastKnownPowerStats()).isEqualTo(powerStats2);
    }

}
