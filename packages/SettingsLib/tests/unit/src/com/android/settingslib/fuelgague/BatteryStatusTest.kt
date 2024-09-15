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

import android.content.Context
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
import android.os.BatteryManager.EXTRA_MAX_CHARGING_CURRENT
import android.os.BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE
import android.os.OsProtoEnums.BATTERY_PLUGGED_NONE
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.fuelgauge.BatteryStatus
import com.android.settingslib.fuelgauge.BatteryStatus.CHARGING_FAST
import com.android.settingslib.fuelgauge.BatteryStatus.CHARGING_REGULAR
import com.android.settingslib.fuelgauge.BatteryStatus.CHARGING_SLOWLY
import com.android.settingslib.fuelgauge.BatteryStatus.CHARGING_UNKNOWN
import com.android.settingslib.fuelgauge.BatteryStatus.isBatteryDefender
import com.android.settingslib.fuelgauge.BatteryUtils
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Optional
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
    BatteryStatusTest.GetChargingSpeedTest::class,
    BatteryStatusTest.IsPluggedInDockTest::class,
)
open class BatteryStatusTest {

    @RunWith(AndroidJUnit4::class)
    class NonParameterizedTest : BatteryStatusTest() {
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
    }

    @RunWith(Parameterized::class)
    class IsPluggedInTest(
        private val name: String,
        private val plugged: Int,
        val expected: Boolean
    ) : BatteryStatusTest() {

        @Test
        fun isPluggedIn_() {
            val batteryChangedIntent = createIntent(plugged = plugged)

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
    class IsPluggedInDockTest(
        private val name: String,
        private val plugged: Int,
        val expected: Boolean
    ) : BatteryStatusTest() {

        @Test
        fun isPluggedDockIn_() {
            val batteryChangedIntent = createIntent(plugged = plugged)

            assertWithMessage("failed by isPluggedInDock(plugged=$plugged)")
                .that(BatteryStatus.isPluggedInDock(plugged))
                .isEqualTo(expected)
            assertWithMessage("failed by isPluggedInDock(Intent), which plugged=$plugged")
                .that(BatteryStatus.isPluggedInDock(batteryChangedIntent))
                .isEqualTo(expected)
        }

        companion object {
            @Parameterized.Parameters(name = "{0}")
            @JvmStatic
            fun parameters() =
                arrayListOf(
                    arrayOf("withAC_returnsTrue", BATTERY_PLUGGED_AC, false),
                    arrayOf("withDock_returnsTrue", BATTERY_PLUGGED_DOCK, true),
                    arrayOf("withUSB_returnsTrue", BATTERY_PLUGGED_USB, false),
                    arrayOf("withWireless_returnsTrue", BATTERY_PLUGGED_WIRELESS, false),
                    arrayOf("pluggedNone_returnsTrue", BATTERY_PLUGGED_NONE, false),
                )
        }
    }

    @RunWith(Parameterized::class)
    class IsChargedTest(
        private val status: Int,
        private val batteryLevel: Int,
        private val expected: Boolean
    ) : BatteryStatusTest() {

        @Test
        fun isCharged_() {
            val batteryChangedIntent = createIntent(batteryLevel = batteryLevel, status = status)

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

    @RunWith(Parameterized::class)
    class GetChargingSpeedTest(
        private val name: String,
        private val maxChargingCurrent: Optional<Int>,
        private val maxChargingVoltage: Optional<Int>,
        private val expectedChargingSpeed: Int,
        private val chargingStringV2Enabled: Boolean,
    ) {

        val context: Context = ApplicationProvider.getApplicationContext()

        @Test
        fun getChargingSpeed_() {
            BatteryUtils.setChargingStringV2Enabled(
                chargingStringV2Enabled,
                false /* updateProperty */
            )
            val batteryChangedIntent =
                Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                    maxChargingCurrent.ifPresent { putExtra(EXTRA_MAX_CHARGING_CURRENT, it) }
                    maxChargingVoltage.ifPresent { putExtra(EXTRA_MAX_CHARGING_VOLTAGE, it) }
                }

            assertThat(BatteryStatus.getChargingSpeed(context, batteryChangedIntent))
                .isEqualTo(expectedChargingSpeed)
        }

        companion object {
            @Parameterized.Parameters(name = "{0}")
            @JvmStatic
            fun parameters() =
                arrayListOf(
                    arrayOf(
                        "maxCurrent=n/a, maxVoltage=n/a -> UNKNOWN",
                        Optional.empty<Int>(),
                        Optional.empty<Int>(),
                        CHARGING_UNKNOWN,
                        false /* chargingStringV2Enabled */
                    ),
                    arrayOf(
                        "maxCurrent=0, maxVoltage=9000000 -> UNKNOWN",
                        Optional.of(0),
                        Optional.of(0),
                        CHARGING_UNKNOWN,
                        false /* chargingStringV2Enabled */
                    ),
                    arrayOf(
                        "maxCurrent=1500000, maxVoltage=5000000 -> CHARGING_REGULAR",
                        Optional.of(1500000),
                        Optional.of(5000000),
                        CHARGING_REGULAR,
                        false /* chargingStringV2Enabled */
                    ),
                    arrayOf(
                        "maxCurrent=1000000, maxVoltage=5000000 -> CHARGING_REGULAR",
                        Optional.of(1000000),
                        Optional.of(5000000),
                        CHARGING_REGULAR,
                        false /* chargingStringV2Enabled */
                    ),
                    arrayOf(
                        "maxCurrent=1500001, maxVoltage=5000000 -> CHARGING_FAST",
                        Optional.of(1501000),
                        Optional.of(5000000),
                        CHARGING_FAST,
                        false /* chargingStringV2Enabled */
                    ),
                    arrayOf(
                        "maxCurrent=999999, maxVoltage=5000000 -> CHARGING_SLOWLY",
                        Optional.of(999999),
                        Optional.of(5000000),
                        CHARGING_SLOWLY,
                        false /* chargingStringV2Enabled */
                    ),
                    arrayOf(
                        "maxCurrent=3000000, maxVoltage=9000000 -> CHARGING_FAST",
                        Optional.of(3000000),
                        Optional.of(9000000),
                        CHARGING_FAST,
                        true /* chargingStringV2Enabled */
                    ),
                    arrayOf(
                        "maxCurrent=2200000, maxVoltage=9000000 -> CHARGING_REGULAR",
                        Optional.of(2200000),
                        Optional.of(9000000),
                        CHARGING_REGULAR,
                        true /* chargingStringV2Enabled */
                    ),
                )
        }
    }

    protected fun createIntent(
        batteryLevel: Int = 50,
        chargingStatus: Int = CHARGING_POLICY_DEFAULT,
        plugged: Int = BATTERY_PLUGGED_NONE,
        status: Int = BatteryManager.BATTERY_STATUS_CHARGING,
    ): Intent =
        Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_STATUS, status)
            putExtra(BatteryManager.EXTRA_LEVEL, batteryLevel)
            putExtra(BatteryManager.EXTRA_SCALE, 100)
            putExtra(BatteryManager.EXTRA_CHARGING_STATUS, chargingStatus)
            putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
        }
}
