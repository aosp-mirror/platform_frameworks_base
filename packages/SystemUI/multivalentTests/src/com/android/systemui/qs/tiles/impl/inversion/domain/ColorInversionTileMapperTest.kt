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

package com.android.systemui.qs.tiles.impl.inversion.domain

import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tileimpl.SubtitleArrayMapping
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.inversion.domain.model.ColorInversionTileModel
import com.android.systemui.qs.tiles.impl.inversion.qsColorInversionTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ColorInversionTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val colorInversionTileConfig = kosmos.qsColorInversionTileConfig
    private val subtitleArrayId =
        SubtitleArrayMapping.getSubtitleId(colorInversionTileConfig.tileSpec.spec)
    private val subtitleArray by lazy { context.resources.getStringArray(subtitleArrayId) }
    // Using lazy (versus =) to make sure we override the right context -- see b/311612168
    private val mapper by lazy {
        ColorInversionTileMapper(
            context.orCreateTestableResources
                .apply {
                    addOverride(R.drawable.qs_invert_colors_icon_off, TestStubDrawable())
                    addOverride(R.drawable.qs_invert_colors_icon_on, TestStubDrawable())
                }
                .resources,
            context.theme
        )
    }

    @Test
    fun disabledModel() {
        val inputModel = ColorInversionTileModel(false)

        val outputState = mapper.map(colorInversionTileConfig, inputModel)

        val expectedState =
            createColorInversionTileState(
                QSTileState.ActivationState.INACTIVE,
                subtitleArray[1],
                R.drawable.qs_invert_colors_icon_off
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel() {
        val inputModel = ColorInversionTileModel(true)

        val outputState = mapper.map(colorInversionTileConfig, inputModel)

        val expectedState =
            createColorInversionTileState(
                QSTileState.ActivationState.ACTIVE,
                subtitleArray[2],
                R.drawable.qs_invert_colors_icon_on
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createColorInversionTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
        iconRes: Int,
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_inversion_label)
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
