/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.shared.model

import android.net.wifi.WifiManager.UNKNOWN_SSID
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableRowLogger
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel.Active.Companion.MAX_VALID_LEVEL
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel.Companion.MIN_VALID_LEVEL
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class WifiNetworkModelTest : SysuiTestCase() {
    @Test
    fun active_levelsInValidRange_noException() {
        (MIN_VALID_LEVEL..MAX_VALID_LEVEL).forEach { level ->
            WifiNetworkModel.Active(NETWORK_ID, level = level)
            // No assert, just need no crash
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun active_levelNegative_exceptionThrown() {
        WifiNetworkModel.Active(NETWORK_ID, level = MIN_VALID_LEVEL - 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun active_levelTooHigh_exceptionThrown() {
        WifiNetworkModel.Active(NETWORK_ID, level = MAX_VALID_LEVEL + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun carrierMerged_invalidSubId_exceptionThrown() {
        WifiNetworkModel.CarrierMerged(NETWORK_ID, INVALID_SUBSCRIPTION_ID, 1)
    }

    @Test
    fun active_hasValidSsid_nullSsid_false() {
        val network =
            WifiNetworkModel.Active(
                NETWORK_ID,
                level = MAX_VALID_LEVEL,
                ssid = null,
            )

        assertThat(network.hasValidSsid()).isFalse()
    }

    @Test
    fun active_hasValidSsid_unknownSsid_false() {
        val network =
            WifiNetworkModel.Active(
                NETWORK_ID,
                level = MAX_VALID_LEVEL,
                ssid = UNKNOWN_SSID,
            )

        assertThat(network.hasValidSsid()).isFalse()
    }

    @Test
    fun active_hasValidSsid_validSsid_true() {
        val network =
            WifiNetworkModel.Active(
                NETWORK_ID,
                level = MAX_VALID_LEVEL,
                ssid = "FakeSsid",
            )

        assertThat(network.hasValidSsid()).isTrue()
    }

    // Non-exhaustive logDiffs test -- just want to make sure the logging logic isn't totally broken

    @Test
    fun logDiffs_carrierMergedToInactive_resetsAllFields() {
        val logger = TestLogger()
        val prevVal =
            WifiNetworkModel.CarrierMerged(
                networkId = 5,
                subscriptionId = 3,
                level = 1,
            )

        WifiNetworkModel.Inactive.logDiffs(prevVal, logger)

        assertThat(logger.changes).contains(Pair(COL_NETWORK_TYPE, TYPE_INACTIVE))
        assertThat(logger.changes).contains(Pair(COL_NETWORK_ID, NETWORK_ID_DEFAULT.toString()))
        assertThat(logger.changes).contains(Pair(COL_VALIDATED, "false"))
        assertThat(logger.changes).contains(Pair(COL_LEVEL, LEVEL_DEFAULT.toString()))
        assertThat(logger.changes).contains(Pair(COL_SSID, "null"))
    }

    @Test
    fun logDiffs_inactiveToCarrierMerged_logsAllFields() {
        val logger = TestLogger()
        val carrierMerged =
            WifiNetworkModel.CarrierMerged(
                networkId = 6,
                subscriptionId = 3,
                level = 2,
            )

        carrierMerged.logDiffs(prevVal = WifiNetworkModel.Inactive, logger)

        assertThat(logger.changes).contains(Pair(COL_NETWORK_TYPE, TYPE_CARRIER_MERGED))
        assertThat(logger.changes).contains(Pair(COL_NETWORK_ID, "6"))
        assertThat(logger.changes).contains(Pair(COL_SUB_ID, "3"))
        assertThat(logger.changes).contains(Pair(COL_VALIDATED, "true"))
        assertThat(logger.changes).contains(Pair(COL_LEVEL, "2"))
        assertThat(logger.changes).contains(Pair(COL_SSID, "null"))
    }

    @Test
    fun logDiffs_inactiveToActive_logsAllActiveFields() {
        val logger = TestLogger()
        val activeNetwork =
            WifiNetworkModel.Active(
                networkId = 5,
                isValidated = true,
                level = 3,
                ssid = "Test SSID",
                hotspotDeviceType = WifiNetworkModel.HotspotDeviceType.LAPTOP,
            )

        activeNetwork.logDiffs(prevVal = WifiNetworkModel.Inactive, logger)

        assertThat(logger.changes).contains(Pair(COL_NETWORK_TYPE, TYPE_ACTIVE))
        assertThat(logger.changes).contains(Pair(COL_NETWORK_ID, "5"))
        assertThat(logger.changes).contains(Pair(COL_VALIDATED, "true"))
        assertThat(logger.changes).contains(Pair(COL_LEVEL, "3"))
        assertThat(logger.changes).contains(Pair(COL_SSID, "Test SSID"))
        assertThat(logger.changes).contains(Pair(COL_HOTSPOT, "LAPTOP"))
    }
    @Test
    fun logDiffs_activeToInactive_resetsAllActiveFields() {
        val logger = TestLogger()
        val activeNetwork =
            WifiNetworkModel.Active(
                networkId = 5,
                isValidated = true,
                level = 3,
                ssid = "Test SSID"
            )

        WifiNetworkModel.Inactive.logDiffs(prevVal = activeNetwork, logger)

        assertThat(logger.changes).contains(Pair(COL_NETWORK_TYPE, TYPE_INACTIVE))
        assertThat(logger.changes).contains(Pair(COL_NETWORK_ID, NETWORK_ID_DEFAULT.toString()))
        assertThat(logger.changes).contains(Pair(COL_VALIDATED, "false"))
        assertThat(logger.changes).contains(Pair(COL_LEVEL, LEVEL_DEFAULT.toString()))
        assertThat(logger.changes).contains(Pair(COL_SSID, "null"))
        assertThat(logger.changes).contains(Pair(COL_HOTSPOT, "null"))
    }

    @Test
    fun logDiffs_carrierMergedToActive_logsAllActiveFields() {
        val logger = TestLogger()
        val activeNetwork =
            WifiNetworkModel.Active(
                networkId = 5,
                isValidated = true,
                level = 3,
                ssid = "Test SSID",
                hotspotDeviceType = WifiNetworkModel.HotspotDeviceType.AUTO,
            )
        val prevVal =
            WifiNetworkModel.CarrierMerged(
                networkId = 5,
                subscriptionId = 3,
                level = 1,
            )

        activeNetwork.logDiffs(prevVal, logger)

        assertThat(logger.changes).contains(Pair(COL_NETWORK_TYPE, TYPE_ACTIVE))
        assertThat(logger.changes).contains(Pair(COL_NETWORK_ID, "5"))
        assertThat(logger.changes).contains(Pair(COL_VALIDATED, "true"))
        assertThat(logger.changes).contains(Pair(COL_LEVEL, "3"))
        assertThat(logger.changes).contains(Pair(COL_SSID, "Test SSID"))
        assertThat(logger.changes).contains(Pair(COL_HOTSPOT, "AUTO"))
    }
    @Test
    fun logDiffs_activeToCarrierMerged_logsAllFields() {
        val logger = TestLogger()
        val activeNetwork =
            WifiNetworkModel.Active(
                networkId = 5,
                isValidated = true,
                level = 3,
                ssid = "Test SSID"
            )
        val carrierMerged =
            WifiNetworkModel.CarrierMerged(
                networkId = 6,
                subscriptionId = 3,
                level = 2,
            )

        carrierMerged.logDiffs(prevVal = activeNetwork, logger)

        assertThat(logger.changes).contains(Pair(COL_NETWORK_TYPE, TYPE_CARRIER_MERGED))
        assertThat(logger.changes).contains(Pair(COL_NETWORK_ID, "6"))
        assertThat(logger.changes).contains(Pair(COL_SUB_ID, "3"))
        assertThat(logger.changes).contains(Pair(COL_VALIDATED, "true"))
        assertThat(logger.changes).contains(Pair(COL_LEVEL, "2"))
        assertThat(logger.changes).contains(Pair(COL_SSID, "null"))
    }

    @Test
    fun logDiffs_activeChangesLevel_onlyLevelLogged() {
        val logger = TestLogger()
        val prevActiveNetwork =
            WifiNetworkModel.Active(
                networkId = 5,
                isValidated = true,
                level = 3,
                ssid = "Test SSID"
            )
        val newActiveNetwork =
            WifiNetworkModel.Active(
                networkId = 5,
                isValidated = true,
                level = 2,
                ssid = "Test SSID"
            )

        newActiveNetwork.logDiffs(prevActiveNetwork, logger)

        assertThat(logger.changes).isEqualTo(listOf(Pair(COL_LEVEL, "2")))
    }

    private class TestLogger : TableRowLogger {
        val changes = mutableListOf<Pair<String, String>>()

        override fun logChange(columnName: String, value: String?) {
            changes.add(Pair(columnName, value.toString()))
        }

        override fun logChange(columnName: String, value: Int) {
            changes.add(Pair(columnName, value.toString()))
        }

        override fun logChange(columnName: String, value: Boolean) {
            changes.add(Pair(columnName, value.toString()))
        }
    }

    companion object {
        private const val NETWORK_ID = 2
    }
}
