/*
 * Copyright (C) 2017 The Android Open Source Project
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

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;

/*
 * Keep this file in sync with frameworks/base/core/res/res/xml/power_profile_test.xml
 */
@SmallTest
public class PowerProfileTest extends TestCase {

    private PowerProfile mProfile;

    @Before
    public void setUp() {
        mProfile = new PowerProfile(InstrumentationRegistry.getContext(), true);
    }

    @Test
    public void testPowerProfile() {
        assertEquals(2, mProfile.getNumCpuClusters());
        assertEquals(4, mProfile.getNumCoresInCpuCluster(0));
        assertEquals(4, mProfile.getNumCoresInCpuCluster(1));
        assertEquals(5.0, mProfile.getAveragePower(PowerProfile.POWER_CPU_SUSPEND));
        assertEquals(1.11, mProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE));
        assertEquals(2.55, mProfile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE));
        assertEquals(2.11, mProfile.getAveragePowerForCpuCluster(0));
        assertEquals(2.22, mProfile.getAveragePowerForCpuCluster(1));
        assertEquals(3, mProfile.getNumSpeedStepsInCpuCluster(0));
        assertEquals(30.0, mProfile.getAveragePowerForCpuCore(0, 2));
        assertEquals(4, mProfile.getNumSpeedStepsInCpuCluster(1));
        assertEquals(60.0, mProfile.getAveragePowerForCpuCore(1, 3));
        assertEquals(3000.0, mProfile.getBatteryCapacity());
        assertEquals(0.5, mProfile.getAveragePower(PowerProfile.POWER_AMBIENT_DISPLAY));
        assertEquals(100.0, mProfile.getAveragePower(PowerProfile.POWER_AUDIO));
        assertEquals(150.0, mProfile.getAveragePower(PowerProfile.POWER_VIDEO));
    }

}
