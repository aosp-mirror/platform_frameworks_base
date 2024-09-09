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

import android.app.Flags
import android.graphics.drawable.TestStubDrawable
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.asIcon
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
@EnableFlags(Flags.FLAG_MODES_UI)
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
        val icon = TestStubDrawable("res123").asIcon()
        val model =
            ModesTileModel(
                isActivated = false,
                activeModes = emptyList(),
                icon = icon,
            )

        val state = underTest.map(config, model)

        assertThat(state.activationState).isEqualTo(QSTileState.ActivationState.INACTIVE)
        assertThat(state.icon()).isEqualTo(icon)
        assertThat(state.secondaryLabel).isEqualTo("No active modes")
    }

    @Test
    fun activeState_oneMode() {
        val icon = TestStubDrawable("res123").asIcon()
        val model =
            ModesTileModel(
                isActivated = true,
                activeModes = listOf("DND"),
                icon = icon,
            )

        val state = underTest.map(config, model)

        assertThat(state.activationState).isEqualTo(QSTileState.ActivationState.ACTIVE)
        assertThat(state.icon()).isEqualTo(icon)
        assertThat(state.secondaryLabel).isEqualTo("DND is active")
    }

    @Test
    fun activeState_multipleModes() {
        val icon = TestStubDrawable("res123").asIcon()
        val model =
            ModesTileModel(
                isActivated = true,
                activeModes = listOf("Mode 1", "Mode 2", "Mode 3"),
                icon = icon,
            )

        val state = underTest.map(config, model)

        assertThat(state.activationState).isEqualTo(QSTileState.ActivationState.ACTIVE)
        assertThat(state.icon()).isEqualTo(icon)
        assertThat(state.secondaryLabel).isEqualTo("3 modes are active")
    }

    @Test
    fun state_modelHasIconResId_includesIconResId() {
        val icon = TestStubDrawable("res123").asIcon()
        val model =
            ModesTileModel(
                isActivated = false,
                activeModes = emptyList(),
                icon = icon,
                iconResId = 123
            )

        val state = underTest.map(config, model)

        assertThat(state.icon()).isEqualTo(icon)
        assertThat(state.iconRes).isEqualTo(123)
    }
}
