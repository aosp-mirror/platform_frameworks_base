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

package com.android.systemui.statusbar.pipeline.mobile.data.model

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableRowLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_ACTIVITY_DIRECTION
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_CARRIER_NETWORK_CHANGE
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_CDMA_LEVEL
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_CONNECTION_STATE
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_EMERGENCY
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_IS_GSM
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_OPERATOR
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_PRIMARY_LEVEL
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_RESOLVED_NETWORK_TYPE
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_ROAMING
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class MobileConnectionModelTest : SysuiTestCase() {

    @Test
    fun `log diff - initial log contains all columns`() {
        val logger = TestLogger()
        val connection = MobileConnectionModel()

        connection.logFull(logger)

        assertThat(logger.changes)
            .contains(Pair(COL_EMERGENCY, connection.isEmergencyOnly.toString()))
        assertThat(logger.changes).contains(Pair(COL_ROAMING, connection.isRoaming.toString()))
        assertThat(logger.changes)
            .contains(Pair(COL_OPERATOR, connection.operatorAlphaShort.toString()))
        assertThat(logger.changes).contains(Pair(COL_IS_GSM, connection.isGsm.toString()))
        assertThat(logger.changes).contains(Pair(COL_CDMA_LEVEL, connection.cdmaLevel.toString()))
        assertThat(logger.changes)
            .contains(Pair(COL_PRIMARY_LEVEL, connection.primaryLevel.toString()))
        assertThat(logger.changes)
            .contains(Pair(COL_CONNECTION_STATE, connection.dataConnectionState.toString()))
        assertThat(logger.changes)
            .contains(Pair(COL_ACTIVITY_DIRECTION, connection.dataActivityDirection.toString()))
        assertThat(logger.changes)
            .contains(
                Pair(COL_CARRIER_NETWORK_CHANGE, connection.carrierNetworkChangeActive.toString())
            )
        assertThat(logger.changes)
            .contains(Pair(COL_RESOLVED_NETWORK_TYPE, connection.resolvedNetworkType.toString()))
    }

    @Test
    fun `log diff - primary level changes - only level is logged`() {
        val logger = TestLogger()
        val connectionOld = MobileConnectionModel(primaryLevel = 1)

        val connectionNew = MobileConnectionModel(primaryLevel = 2)

        connectionNew.logDiffs(connectionOld, logger)

        assertThat(logger.changes).isEqualTo(listOf(Pair(COL_PRIMARY_LEVEL, "2")))
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
}
