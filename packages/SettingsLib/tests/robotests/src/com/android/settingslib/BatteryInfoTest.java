/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.SystemClock;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = TestConfig.MANIFEST_PATH, sdk = TestConfig.SDK_VERSION)
public class BatteryInfoTest {
    private static final String STATUS_FULL = "Full";
    private Intent mBatteryBroadcast;
    @Mock
    private BatteryStats mBatteryStats;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Context mContext;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mBatteryBroadcast = new Intent();
        mBatteryBroadcast.putExtra(BatteryManager.EXTRA_PLUGGED, 0);
        mBatteryBroadcast.putExtra(BatteryManager.EXTRA_LEVEL, 0);
        mBatteryBroadcast.putExtra(BatteryManager.EXTRA_SCALE, 100);
        mBatteryBroadcast.putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL);

        when(mContext.getResources().getString(R.string.battery_info_status_full))
                .thenReturn(STATUS_FULL);
    }

    @Test
    public void testGetBatteryInfo_HasStatusLabel() {
        BatteryInfo info = BatteryInfo.getBatteryInfo(mContext, mBatteryBroadcast, mBatteryStats,
                SystemClock.elapsedRealtime() * 1000, true);

        assertThat(info.statusLabel).isEqualTo(STATUS_FULL);
    }
}
