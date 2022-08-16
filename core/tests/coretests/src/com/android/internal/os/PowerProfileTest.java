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

import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_AMBIENT;
import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_SCREEN_FULL;
import static com.android.internal.os.PowerProfile.POWER_GROUP_DISPLAY_SCREEN_ON;

import android.annotation.XmlRes;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;
import com.android.internal.power.ModemPowerProfile;
import com.android.internal.util.XmlUtils;

import junit.framework.TestCase;

import org.junit.Before;
import org.junit.Test;

/*
 * Keep this file in sync with frameworks/base/core/res/res/xml/power_profile_test.xml and
 * frameworks/base/core/tests/coretests/res/xml/power_profile_test_modem.xml
 *
 * Run with:
 *     atest com.android.internal.os.PowerProfileTest
 */
@SmallTest
public class PowerProfileTest extends TestCase {

    static final String TAG_TEST_MODEM = "test-modem";
    static final String ATTR_NAME = "name";

    private PowerProfile mProfile;
    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        mProfile = new PowerProfile(mContext);
    }

    @Test
    public void testPowerProfile() {
        mProfile.forceInitForTesting(mContext, R.xml.power_profile_test);

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
        assertEquals(0.5,
                mProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_AMBIENT, 0));
        assertEquals(100.0,
                mProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_ON, 0));
        assertEquals(800.0,
                mProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_FULL, 0));
        assertEquals(100.0, mProfile.getAveragePower(PowerProfile.POWER_AUDIO));
        assertEquals(150.0, mProfile.getAveragePower(PowerProfile.POWER_VIDEO));

        assertEquals(123.0, mProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_SLEEP));
        assertEquals(456.0, mProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_IDLE));
        assertEquals(789.0, mProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_RX));
        assertEquals(10.0, mProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, 0));
        assertEquals(20.0, mProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, 1));
        assertEquals(30.0, mProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, 2));
        assertEquals(40.0, mProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, 3));
        assertEquals(50.0, mProfile.getAveragePower(PowerProfile.POWER_MODEM_CONTROLLER_TX, 4));

        // Deprecated Modem constants should work with current format.
        assertEquals(123.0, mProfile.getAverageBatteryDrainMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_DRAIN_TYPE_SLEEP));
        assertEquals(456.0, mProfile.getAverageBatteryDrainMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_DRAIN_TYPE_IDLE));
        assertEquals(789.0, mProfile.getAverageBatteryDrainMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(10.0, mProfile.getAverageBatteryDrainMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(20.0, mProfile.getAverageBatteryDrainMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(30.0, mProfile.getAverageBatteryDrainMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(40.0, mProfile.getAverageBatteryDrainMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(50.0, mProfile.getAverageBatteryDrainMa(
                PowerProfile.SUBSYSTEM_MODEM | ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));
    }

    @Test
    public void testModemPowerProfile_defaultRat() throws Exception {
        final XmlResourceParser parser = getTestModemElement(R.xml.power_profile_test_modem,
                "testModemPowerProfile_defaultRat");
        ModemPowerProfile mpp = new ModemPowerProfile();
        mpp.parseFromXml(parser);
        assertEquals(10.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_DRAIN_TYPE_SLEEP));
        assertEquals(20.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_DRAIN_TYPE_IDLE));

        // Only default RAT was defined, all other RAT's should fallback to the default value.
        assertEquals(30.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(30.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(30.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));

        assertEquals(40.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(40.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(40.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));

        assertEquals(50.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(50.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(50.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));

        assertEquals(60.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(60.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(60.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));

        assertEquals(70.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(70.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(70.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));

        assertEquals(80.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));
        assertEquals(80.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));
        assertEquals(80.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));
    }

    @Test
    public void testModemPowerProfile_partiallyDefined() throws Exception {
        final XmlResourceParser parser = getTestModemElement(R.xml.power_profile_test_modem,
                "testModemPowerProfile_partiallyDefined");
        ModemPowerProfile mpp = new ModemPowerProfile();
        mpp.parseFromXml(parser);
        assertEquals(1.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_DRAIN_TYPE_SLEEP));
        assertEquals(2.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_DRAIN_TYPE_IDLE));

        assertEquals(3.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(4.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(5.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(6.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(7.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(8.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));

        // LTE RAT power constants were not defined, fallback to defaults
        assertEquals(3.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(4.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(5.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(6.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(7.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(8.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(13.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(14.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(15.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(16.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(17.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(18.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_4));

        // Non-mmwave NR frequency power constants were not defined, fallback to defaults
        assertEquals(13.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(14.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(15.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(16.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(17.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(18.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(13.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(14.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(15.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(16.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(17.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(18.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(13.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(14.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(15.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(16.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(17.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(18.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(53.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(54.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(55.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(56.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(57.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(58.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_4));
    }

    @Test
    public void testModemPowerProfile_fullyDefined() throws Exception {
        final XmlResourceParser parser = getTestModemElement(R.xml.power_profile_test_modem,
                "testModemPowerProfile_fullyDefined");
        ModemPowerProfile mpp = new ModemPowerProfile();
        mpp.parseFromXml(parser);
        assertEquals(1.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_DRAIN_TYPE_SLEEP));
        assertEquals(2.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_DRAIN_TYPE_IDLE));

        // Only default RAT was defined, all other RAT's should fallback to the default value.
        assertEquals(3.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(4.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(5.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(6.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(7.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(8.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_DEFAULT | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(10.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(20.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(30.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(40.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(50.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(60.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_LTE | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(13.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(14.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(15.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(16.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(17.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(18.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_DEFAULT
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(23.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(24.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(25.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(26.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(27.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(28.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_LOW
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(33.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(34.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(35.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(36.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(37.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(38.0, mpp.getAverageBatteryDrainMa(
                ModemPowerProfile.MODEM_RAT_TYPE_NR | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MID
                        | ModemPowerProfile.MODEM_DRAIN_TYPE_TX
                        | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(43.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(44.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(45.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(46.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(47.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(48.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_HIGH
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_4));

        assertEquals(53.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_RX));
        assertEquals(54.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_0));
        assertEquals(55.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_1));
        assertEquals(56.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_2));
        assertEquals(57.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_3));
        assertEquals(58.0, mpp.getAverageBatteryDrainMa(ModemPowerProfile.MODEM_RAT_TYPE_NR
                | ModemPowerProfile.MODEM_NR_FREQUENCY_RANGE_MMWAVE
                | ModemPowerProfile.MODEM_DRAIN_TYPE_TX | ModemPowerProfile.MODEM_TX_LEVEL_4));
    }

    private XmlResourceParser getTestModemElement(@XmlRes int xmlId, String elementName)
            throws Exception {
        final String element = TAG_TEST_MODEM;
        final Resources resources = mContext.getResources();
        XmlResourceParser parser = resources.getXml(xmlId);
        while (true) {
            XmlUtils.nextElement(parser);
            final String e = parser.getName();
            if (e == null) break;
            if (!e.equals(element)) continue;

            final String name = parser.getAttributeValue(null, ATTR_NAME);
            if (!name.equals(elementName)) continue;

            return parser;
        }
        fail("Unanable to find element " + element + " with name " + elementName);
        return null;
    }
}
