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

package com.android.settingslib.fuelgague

import android.content.Intent
import android.os.BatteryManager
import android.os.BatteryManager.BATTERY_PLUGGED_AC
import android.os.BatteryManager.BATTERY_PLUGGED_DOCK
import android.os.BatteryManager.BATTERY_PLUGGED_USB
import android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS
import android.os.BatteryManager.BATTERY_STATUS_FULL
import android.os.BatteryManager.BATTERY_STATUS_UNKNOWN
import android.os.BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE
import android.os.BatteryManager.CHARGING_POLICY_DEFAULT
import android.os.BatteryManager.EXTRA_CHARGING_STATUS
import android.os.OsProtoEnums.BATTERY_PLUGGED_NONE
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.settingslib.fuelgauge.BatteryStatus.isBatteryDefender
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses

@RunWith(Suite::class)
@SuiteClasses(
    BatteryStatusTest.NonParameterizedTest::class,
    BatteryStatusTest.IsPluggedInTest::class,
    BatteryStatusTest.IsChargedTest::class,
)
class BatteryStatusTest {

    @RunWith(AndroidJUnit4::class)
    class NonParameterizedTest {
        @Test
        fun isLowBattery_20Percent_returnsTrue() {
            val level = 20
            val intent = createIntent(batteryLevel = level)

            assertWithMessage("failed by isLowBattery(Intent), level=$level")
                .that(BatteryStatus.isLowBattery(intent))
                .isTrue()
            assertWithMessage("failed by isLowBattery($level)")
                .that(BatteryStatus.isLowBattery(level))
                .isTrue()
        }

        @Test
        fun isLowBattery_21Percent_returnsFalse() {
            val level = 21
            val intent = createIntent(batteryLevel = level)

            assertWithMessage("failed by isLowBattery(intent), level=$level")
                .that(BatteryStatus.isLowBattery(intent))
                .isFalse()
            assertWithMessage("failed by isLowBattery($level)")
                .that(BatteryStatus.isLowBattery(intent))
                .isFalse()
        }

        @Test
        fun isSevereLowBattery_10Percent_returnsTrue() {
            val batteryChangedIntent = createIntent(batteryLevel = 10)

            assertThat(BatteryStatus.isSevereLowBattery(batteryChangedIntent)).isTrue()
        }

        @Test
        fun isSevereLowBattery_11Percent_returnFalse() {
            val batteryChangedIntent = createIntent(batteryLevel = 11)

            assertThat(BatteryStatus.isSevereLowBattery(batteryChangedIntent)).isFalse()
        }

        @Test
        fun isExtremeLowBattery_3Percent_returnsTrue() {
            val batteryChangedIntent = createIntent(batteryLevel = 3)

            assertThat(BatteryStatus.isExtremeLowBattery(batteryChangedIntent)).isTrue()
        }

        @Test
        fun isExtremeLowBattery_4Percent_returnsFalse() {
            val batteryChangedIntent = createIntent(batteryLevel = 4)

            assertThat(BatteryStatus.isExtremeLowBattery(batteryChangedIntent)).isFalse()
        }

        @Test
        fun isBatteryDefender_chargingLongLife_returnsTrue() {
            val chargingStatus = CHARGING_POLICY_ADAPTIVE_LONGLIFE
            val batteryChangedIntent = createIntent(chargingStatus = chargingStatus)

            assertIsBatteryDefender(chargingStatus, batteryChangedIntent).isTrue()
        }

        @Test
        fun isBatteryDefender_nonChargingLongLife_returnsFalse() {
            val chargingStatus = CHARGING_POLICY_DEFAULT
            val batteryChangedIntent = createIntent(chargingStatus = chargingStatus)

            assertIsBatteryDefender(chargingStatus, batteryChangedIntent).isFalse()
        }

        private fun assertIsBatteryDefender(chargingStatus: Int, batteryChangedIntent: Intent) =
            object {
                val assertions =
                    listOf(
                        "failed by isBatteryDefender(Intent), chargingStatus=$chargingStatus".let {
                            assertWithMessage(it).that(isBatteryDefender(batteryChangedIntent))
                        },
                        "failed by isBatteryDefender($chargingStatus)".let {
                            assertWithMessage(it).that(isBatteryDefender(chargingStatus))
                        },
                    )

                fun isTrue() = assertions.forEach { it.isTrue() }

                fun isFalse() = assertions.forEach { it.isFalse() }
            }

        private fun createIntent(
            batteryLevel: Int = 50,
            chargingStatus: Int = CHARGING_POLICY_DEFAULT
        ): Intent =
            Intent().apply {
                putExtra(BatteryManager.EXTRA_LEVEL, batteryLevel)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(EXTRA_CHARGING_STATUS, chargingStatus)
            }
    }

    @RunWith(Parameterized::class)
    class IsPluggedInTest(
        private val name: String,
        private val plugged: Int,
        val expected: Boolean
    ) {

        @Test
        fun isPluggedIn_() {
            val batteryChangedIntent =
                Intent().apply { putExtra(BatteryManager.EXTRA_PLUGGED, plugged) }

            assertWithMessage("failed by isPluggedIn(plugged=$plugged)")
                .that(BatteryStatus.isPluggedIn(plugged))
                .isEqualTo(expected)
            assertWithMessage("failed by isPlugged(Intent), which plugged=$plugged")
                .that(BatteryStatus.isPluggedIn(batteryChangedIntent))
                .isEqualTo(expected)
        }

        companion object {
            @Parameterized.Parameters(name = "{0}")
            @JvmStatic
            fun parameters() =
                arrayListOf(
                    arrayOf("withAC_returnsTrue", BATTERY_PLUGGED_AC, true),
                    arrayOf("withDock_returnsTrue", BATTERY_PLUGGED_DOCK, true),
                    arrayOf("withUSB_returnsTrue", BATTERY_PLUGGED_USB, true),
                    arrayOf("withWireless_returnsTrue", BATTERY_PLUGGED_WIRELESS, true),
                    arrayOf("pluggedNone_returnsTrue", BATTERY_PLUGGED_NONE, false),
                )
        }
    }

    @RunWith(Parameterized::class)
    class IsChargedTest(
        private val status: Int,
        private val batteryLevel: Int,
        private val expected: Boolean
    ) {

        @Test
        fun isCharged_() {
            val batteryChangedIntent =
                Intent().apply {
                    putExtra(BatteryManager.EXTRA_STATUS, status)
                    putExtra(BatteryManager.EXTRA_SCALE, 100)
                    putExtra(BatteryManager.EXTRA_LEVEL, batteryLevel)
                }

            assertWithMessage(
                    "failed by isCharged(Intent), status=$status, batteryLevel=$batteryLevel"
                )
                .that(BatteryStatus.isCharged(batteryChangedIntent))
                .isEqualTo(expected)
            assertWithMessage("failed by isCharged($status, $batteryLevel)")
                .that(BatteryStatus.isCharged(status, batteryLevel))
                .isEqualTo(expected)
        }

        companion object {
            @Parameterized.Parameters(name = "status{0}_level{1}_returns-{2}")
            @JvmStatic
            fun parameters() =
                arrayListOf(
                    arrayOf(BATTERY_STATUS_FULL, 99, true),
                    arrayOf(BATTERY_STATUS_UNKNOWN, 100, true),
                    arrayOf(BATTERY_STATUS_FULL, 100, true),
                    arrayOf(BATTERY_STATUS_UNKNOWN, 99, false),
                )
        }
    }
}
