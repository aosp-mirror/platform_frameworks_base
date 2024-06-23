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

package com.android.systemui.qs.tiles.impl.colorcorrection.domain

import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.colorcorrection.domain.model.ColorCorrectionTileModel
import com.android.systemui.qs.tiles.impl.colorcorrection.qsColorCorrectionTileConfig
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ColorCorrectionTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val colorCorrectionTileConfig = kosmos.qsColorCorrectionTileConfig
    private val subtitleArray by lazy {
        context.resources.getStringArray(R.array.tile_states_color_correction)
    }
    // Using lazy (versus =) to make sure we override the right context -- see b/311612168
    private val mapper by lazy {
        ColorCorrectionTileMapper(
            context.orCreateTestableResources
                .apply { addOverride(R.drawable.ic_qs_color_correction, TestStubDrawable()) }
                .resources,
            context.theme
        )
    }

    @Test
    fun disabledModel() {
        val inputModel = ColorCorrectionTileModel(false)

        val outputState = mapper.map(colorCorrectionTileConfig, inputModel)

        val expectedState =
            createColorCorrectionTileState(QSTileState.ActivationState.INACTIVE, subtitleArray[1])
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel() {
        val inputModel = ColorCorrectionTileModel(true)

        val outputState = mapper.map(colorCorrectionTileConfig, inputModel)

        val expectedState =
            createColorCorrectionTileState(QSTileState.ActivationState.ACTIVE, subtitleArray[2])
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createColorCorrectionTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_color_correction_label)
        return QSTileState(
            { Icon.Loaded(context.getDrawable(R.drawable.ic_qs_color_correction)!!, null) },
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
