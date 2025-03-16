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
package com.android.systemui.qs.tiles.impl.hearingdevices.domain

import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.hearingdevices.domain.model.HearingDevicesTileModel
import com.android.systemui.qs.tiles.impl.hearingdevices.qsHearingDevicesTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HearingDevicesTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val qsTileConfig = kosmos.qsHearingDevicesTileConfig
    private val mapper by lazy {
        HearingDevicesTileMapper(
            context.orCreateTestableResources
                .apply { addOverride(R.drawable.qs_hearing_devices_icon, TestStubDrawable()) }
                .resources,
            context.theme,
        )
    }

    @Test
    fun map_anyActiveHearingDevice_anyPairedHearingDevice_activeState() {
        val tileState: QSTileState =
            mapper.map(
                qsTileConfig,
                HearingDevicesTileModel(
                    isAnyActiveHearingDevice = true,
                    isAnyPairedHearingDevice = true,
                ),
            )
        val expectedState =
            createHearingDevicesTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.quick_settings_hearing_devices_connected),
            )
        QSTileStateSubject.assertThat(tileState).isEqualTo(expectedState)
    }

    @Test
    fun map_noActiveHearingDevice_anyPairedHearingDevice_inactiveState() {
        val tileState: QSTileState =
            mapper.map(
                qsTileConfig,
                HearingDevicesTileModel(
                    isAnyActiveHearingDevice = false,
                    isAnyPairedHearingDevice = true,
                ),
            )
        val expectedState =
            createHearingDevicesTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(R.string.quick_settings_hearing_devices_disconnected),
            )
        QSTileStateSubject.assertThat(tileState).isEqualTo(expectedState)
    }

    @Test
    fun map_noActiveHearingDevice_noPairedHearingDevice_inactiveState() {
        val tileState: QSTileState =
            mapper.map(
                qsTileConfig,
                HearingDevicesTileModel(
                    isAnyActiveHearingDevice = false,
                    isAnyPairedHearingDevice = false,
                ),
            )
        val expectedState =
            createHearingDevicesTileState(QSTileState.ActivationState.INACTIVE, secondaryLabel = "")
        QSTileStateSubject.assertThat(tileState).isEqualTo(expectedState)
    }

    private fun createHearingDevicesTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_hearing_devices_label)
        val iconRes = R.drawable.qs_hearing_devices_icon
        return QSTileState(
            Icon.Loaded(context.getDrawable(iconRes)!!, null),
            iconRes,
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            label,
            null,
            QSTileState.SideViewIcon.Chevron,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName,
        )
    }
}
