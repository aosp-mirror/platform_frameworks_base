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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Xml;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.power.ModemPowerProfile;
import com.android.internal.util.XmlUtils;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;

/*
 * Keep this file in sync with frameworks/base/core/res/res/xml/power_profile_test.xml and
 * frameworks/base/core/tests/coretests/res/xml/power_profile_test_modem.xml
 *
 * Run with:
 *     atest com.android.internal.os.PowerProfileTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class PowerProfileTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    static final String TAG_TEST_MODEM = "test-modem";
    static final String ATTR_NAME = "name";

    private PowerProfile mProfile;

    @Before
    public void setUp() {
        mProfile = new PowerProfile();
    }

    @Test
    public void testPowerProfile() {
        mProfile.initForTesting(resolveParser("power_profile_test"));

        assertEquals(5.0, mProfile.getAveragePower(PowerProfile.POWER_CPU_SUSPEND));
        assertEquals(1.11, mProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE));
        assertEquals(2.55, mProfile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE));
        assertEquals(2.11, mProfile.getAveragePowerForCpuScalingPolicy(0));
        assertEquals(2.22, mProfile.getAveragePowerForCpuScalingPolicy(3));
        assertEquals(30.0, mProfile.getAveragePowerForCpuScalingStep(0, 2));
        assertEquals(60.0, mProfile.getAveragePowerForCpuScalingStep(3, 3));
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

        assertEquals(0.02, mProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE));
        assertEquals(3, mProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX));
        assertEquals(5, mProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_TX));
        assertEquals(3300, mProfile.getAveragePower(
                PowerProfile.POWER_BLUETOOTH_CONTROLLER_OPERATING_VOLTAGE));
    }

    @DisabledOnRavenwood
    @Test
    public void configDefaults() throws XmlPullParserException {
        Resources mockResources = mock(Resources.class);
        when(mockResources.getInteger(com.android.internal.R.integer.config_bluetooth_rx_cur_ma))
                .thenReturn(123);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(
                "<device name='Android'>"
                + "<item name='bluetooth.controller.idle'>10</item>"
                + "</device>"));
        mProfile.initForTesting(parser, mockResources);
        assertThat(mProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_IDLE))
                .isEqualTo(10);
        assertThat(mProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_CONTROLLER_RX))
                .isEqualTo(123);
    }

    @Test
    public void testPowerProfile_legacyCpuConfig() {
        // This power profile has per-cluster data, rather than per-policy
        mProfile.initForTesting(resolveParser("power_profile_test_cpu_legacy"));

        assertEquals(2.11, mProfile.getAveragePowerForCpuScalingPolicy(0));
        assertEquals(2.22, mProfile.getAveragePowerForCpuScalingPolicy(4));
        assertEquals(30.0, mProfile.getAveragePowerForCpuScalingStep(0, 2));
        assertEquals(60.0, mProfile.getAveragePowerForCpuScalingStep(4, 3));
        assertEquals(3000.0, mProfile.getBatteryCapacity());
        assertEquals(0.5,
                mProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_AMBIENT, 0));
        assertEquals(100.0,
                mProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_ON, 0));
        assertEquals(800.0,
                mProfile.getAveragePowerForOrdinal(POWER_GROUP_DISPLAY_SCREEN_FULL, 0));
    }

    @Test
    public void testModemPowerProfile_defaultRat() throws Exception {
        final XmlPullParser parser = getTestModemElement("power_profile_test_modem",
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
        final XmlPullParser parser = getTestModemElement("power_profile_test_modem",
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
        final XmlPullParser parser = getTestModemElement("power_profile_test_modem",
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

    private XmlPullParser getTestModemElement(String resourceName, String elementName)
            throws Exception {
        XmlPullParser parser = resolveParser(resourceName);
        final String element = TAG_TEST_MODEM;
        while (true) {
            XmlUtils.nextElement(parser);
            final String e = parser.getName();
            if (e == null) break;
            if (!e.equals(element)) continue;

            final String name = parser.getAttributeValue(null, ATTR_NAME);
            if (!name.equals(elementName)) continue;

            return parser;
        }
        fail("Unable to find element " + element + " with name " + elementName);
        return null;
    }

    private XmlPullParser resolveParser(String resourceName) {
        if (RavenwoodRule.isOnRavenwood()) {
            try {
                return Xml.resolvePullParser(getClass().getClassLoader()
                        .getResourceAsStream("res/xml/" + resourceName + ".xml"));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            Context context = androidx.test.InstrumentationRegistry.getContext();
            Resources resources = context.getResources();
            int resId = resources.getIdentifier(resourceName, "xml", context.getPackageName());
            return resources.getXml(resId);
        }
    }

    private void assertEquals(double expected, double actual) {
        Assert.assertEquals(expected, actual, 0.1);
    }
}
