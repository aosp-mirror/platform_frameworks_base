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

package com.android.systemui.qs.tiles.impl.modes.ui

import android.graphics.drawable.TestStubDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigTestBuilder
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ModesTileMapperTest : SysuiTestCase() {
    val config =
        QSTileConfigTestBuilder.build {
            uiConfig =
                QSTileUIConfig.Resource(
                    iconRes = R.drawable.qs_dnd_icon_off,
                    labelRes = R.string.quick_settings_modes_label,
                )
        }

    val underTest =
        ModesTileMapper(
            context.orCreateTestableResources
                .apply {
                    addOverride(R.drawable.qs_dnd_icon_on, TestStubDrawable())
                    addOverride(R.drawable.qs_dnd_icon_off, TestStubDrawable())
                }
                .resources,
            context.theme,
        )

    @Test
    fun inactiveState() {
        val model = ModesTileModel(isActivated = false, activeModes = emptyList())

        val state = underTest.map(config, model)

        assertThat(state.activationState).isEqualTo(QSTileState.ActivationState.INACTIVE)
        assertThat(state.iconRes).isEqualTo(R.drawable.qs_dnd_icon_off)
        assertThat(state.secondaryLabel).isEqualTo("No active modes")
    }

    @Test
    fun activeState_oneMode() {
        val model = ModesTileModel(isActivated = true, activeModes = listOf("DND"))

        val state = underTest.map(config, model)

        assertThat(state.activationState).isEqualTo(QSTileState.ActivationState.ACTIVE)
        assertThat(state.iconRes).isEqualTo(R.drawable.qs_dnd_icon_on)
        assertThat(state.secondaryLabel).isEqualTo("DND is active")
    }

    @Test
    fun activeState_multipleModes() {
        val model =
            ModesTileModel(isActivated = true, activeModes = listOf("Mode 1", "Mode 2", "Mode 3"))

        val state = underTest.map(config, model)

        assertThat(state.activationState).isEqualTo(QSTileState.ActivationState.ACTIVE)
        assertThat(state.iconRes).isEqualTo(R.drawable.qs_dnd_icon_on)
        assertThat(state.secondaryLabel).isEqualTo("3 modes are active")
    }
}
