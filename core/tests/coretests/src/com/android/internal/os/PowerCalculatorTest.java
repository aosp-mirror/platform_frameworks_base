/*
 * Copyright (C) 2018 The Android Open Source Project
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
 *
 *
 */

package com.android.internal.os;


import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.BatteryStats;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PowerCalculatorTest extends TestCase {
    private static final long US_IN_HR = 1000L * 1000L * 60L * 60L;

    @Mock
    private PowerProfile mPowerProfile;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    /** Test {@link MediaPowerCalculator#calculateApp} */
    @Test
    public void testMediaPowerCalculator() {
        when(mPowerProfile.getAveragePower(PowerProfile.POWER_AUDIO)).thenReturn(12.0);
        when(mPowerProfile.getAveragePower(PowerProfile.POWER_VIDEO)).thenReturn(25.0);

        BatteryStats.Uid u = mock(BatteryStats.Uid.class);
        BatteryStats.Timer audioTimer = mock(BatteryStats.Timer.class);
        when(u.getAudioTurnedOnTimer()).thenReturn(audioTimer);
        when(audioTimer.getTotalTimeLocked(2L * US_IN_HR, 0)).thenReturn(2L * US_IN_HR);
        BatteryStats.Timer videoTimer = mock(BatteryStats.Timer.class);
        when(u.getVideoTurnedOnTimer()).thenReturn(videoTimer);
        when(videoTimer.getTotalTimeLocked(2L * US_IN_HR, 0)).thenReturn(1L * US_IN_HR);

        MediaPowerCalculator mediaPowerCalculator = new MediaPowerCalculator(mPowerProfile);
        BatterySipper app = new BatterySipper(BatterySipper.DrainType.APP, null, 0);

        mediaPowerCalculator.calculateApp(app, u, 2L * US_IN_HR, 2L * US_IN_HR, 0);
        assertEquals(49.0, app.sumPower());
    }


}
