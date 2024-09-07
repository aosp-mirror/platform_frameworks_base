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

package com.android.systemui.qs.tiles.impl.reducebrightness.ui

import android.graphics.drawable.TestStubDrawable
import android.service.quicksettings.Tile
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.reducebrightness.domain.model.ReduceBrightColorsTileModel
import com.android.systemui.qs.tiles.impl.reducebrightness.qsReduceBrightColorsTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ReduceBrightColorsTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val config = kosmos.qsReduceBrightColorsTileConfig

    private lateinit var mapper: ReduceBrightColorsTileMapper

    @Before
    fun setup() {
        mapper =
            ReduceBrightColorsTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(R.drawable.qs_extra_dim_icon_on, TestStubDrawable())
                        addOverride(R.drawable.qs_extra_dim_icon_off, TestStubDrawable())
                    }
                    .resources,
                context.theme
            )
    }

    @Test
    fun disabledModel() {
        val inputModel = ReduceBrightColorsTileModel(false)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createReduceBrightColorsTileState(
                QSTileState.ActivationState.INACTIVE,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel() {
        val inputModel = ReduceBrightColorsTileModel(true)

        val outputState = mapper.map(config, inputModel)

        val expectedState = createReduceBrightColorsTileState(QSTileState.ActivationState.ACTIVE)
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createReduceBrightColorsTileState(
        activationState: QSTileState.ActivationState,
    ): QSTileState {
        val label =
            context.getString(com.android.internal.R.string.reduce_bright_colors_feature_name)
        val iconRes =
            if (activationState == QSTileState.ActivationState.ACTIVE)
                R.drawable.qs_extra_dim_icon_on
            else R.drawable.qs_extra_dim_icon_off
        return QSTileState(
            { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
            iconRes,
            label,
            activationState,
            context.resources
                .getStringArray(R.array.tile_states_reduce_brightness)[
                    if (activationState == QSTileState.ActivationState.ACTIVE) Tile.STATE_ACTIVE
                    else Tile.STATE_INACTIVE],
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            label,
            null,
            QSTileState.SideViewIcon.None,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }
}
