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

package com.android.systemui.qs.tiles.impl

import android.graphics.drawable.TestStubDrawable
import android.service.quicksettings.Tile
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.airplane.domain.AirplaneModeMapper
import com.android.systemui.qs.tiles.impl.airplane.domain.model.AirplaneModeTileModel
import com.android.systemui.qs.tiles.impl.airplane.qsAirplaneModeTileConfig
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class AirplaneModeMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val airplaneModeConfig = kosmos.qsAirplaneModeTileConfig

    private lateinit var mapper: AirplaneModeMapper

    @Before
    fun setup() {
        mapper =
            AirplaneModeMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(R.drawable.qs_airplane_icon_off, TestStubDrawable())
                        addOverride(R.drawable.qs_airplane_icon_on, TestStubDrawable())
                    }
                    .resources,
                context.theme,
            )
    }

    @Test
    fun enabledModel_mapsCorrectly() {
        val inputModel = AirplaneModeTileModel(true)

        val outputState = mapper.map(airplaneModeConfig, inputModel)

        val expectedState =
            createAirplaneModeState(
                QSTileState.ActivationState.ACTIVE,
                context.resources.getStringArray(R.array.tile_states_airplane)[Tile.STATE_ACTIVE],
                R.drawable.qs_airplane_icon_on
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun disabledModel_mapsCorrectly() {
        val inputModel = AirplaneModeTileModel(false)

        val outputState = mapper.map(airplaneModeConfig, inputModel)

        val expectedState =
            createAirplaneModeState(
                QSTileState.ActivationState.INACTIVE,
                context.resources.getStringArray(R.array.tile_states_airplane)[Tile.STATE_INACTIVE],
                R.drawable.qs_airplane_icon_off
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createAirplaneModeState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
        iconRes: Int
    ): QSTileState {
        val label = context.getString(R.string.airplane_mode)
        return QSTileState(
            { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
            iconRes,
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            label,
            null,
            QSTileState.SideViewIcon.None,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }
}
