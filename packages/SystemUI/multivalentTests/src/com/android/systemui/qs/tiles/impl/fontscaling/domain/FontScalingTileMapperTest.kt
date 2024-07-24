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

package com.android.systemui.qs.tiles.impl.fontscaling.domain

import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.fontscaling.domain.model.FontScalingTileModel
import com.android.systemui.qs.tiles.impl.fontscaling.qsFontScalingTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class FontScalingTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val fontScalingTileConfig = kosmos.qsFontScalingTileConfig

    private val mapper by lazy {
        FontScalingTileMapper(
            context.orCreateTestableResources
                .apply { addOverride(R.drawable.ic_qs_font_scaling, TestStubDrawable()) }
                .resources,
            context.theme
        )
    }

    @Test
    fun activeStateMatchesEnabledModel() {
        val inputModel = FontScalingTileModel

        val outputState = mapper.map(fontScalingTileConfig, inputModel)

        val expectedState = createFontScalingTileState()
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createFontScalingTileState(): QSTileState =
        QSTileState(
            {
                Icon.Loaded(
                    context.getDrawable(
                        R.drawable.ic_qs_font_scaling,
                    )!!,
                    null
                )
            },
            context.getString(R.string.quick_settings_font_scaling_label),
            QSTileState.ActivationState.ACTIVE,
            null,
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            context.getString(R.string.quick_settings_font_scaling_label),
            null,
            QSTileState.SideViewIcon.Chevron,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
}
