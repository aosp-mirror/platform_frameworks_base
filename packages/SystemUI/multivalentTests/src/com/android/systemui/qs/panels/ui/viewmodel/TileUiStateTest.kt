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

package com.android.systemui.qs.panels.ui.viewmodel

import android.content.res.Resources
import android.content.res.mainResources
import android.service.quicksettings.Tile
import android.widget.Button
import android.widget.Switch
import androidx.compose.ui.semantics.Role
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TileUiStateTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val resources: Resources
        get() = kosmos.mainResources

    @Test
    fun stateUnavailable_secondaryLabelNotmodified() {
        val testString = "TEST STRING"
        val state =
            QSTile.State().apply {
                state = Tile.STATE_UNAVAILABLE
                secondaryLabel = testString
            }

        val uiState = state.toUiState()

        assertThat(uiState.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun accessibilityRole_switch() {
        val stateSwitch =
            QSTile.State().apply { expandedAccessibilityClassName = Switch::class.java.name }
        val uiState = stateSwitch.toUiState()
        assertThat(uiState.accessibilityRole).isEqualTo(Role.Switch)
    }

    @Test
    fun accessibilityRole_button() {
        val stateButton =
            QSTile.State().apply { expandedAccessibilityClassName = Button::class.java.name }
        val uiState = stateButton.toUiState()
        assertThat(uiState.accessibilityRole).isEqualTo(Role.Button)
    }

    @Test
    fun accessibilityRole_switchWithSecondaryClick() {
        val stateSwitchWithSecondaryClick =
            QSTile.State().apply {
                expandedAccessibilityClassName = Switch::class.java.name
                handlesSecondaryClick = true
            }
        val uiState = stateSwitchWithSecondaryClick.toUiState()
        assertThat(uiState.accessibilityRole).isEqualTo(Role.Button)
    }

    @Test
    fun switchInactive_secondaryLabelNotModified() {
        val testString = "TEST STRING"
        val state =
            QSTile.State().apply {
                expandedAccessibilityClassName = Switch::class.java.name
                state = Tile.STATE_INACTIVE
                secondaryLabel = testString
            }

        val uiState = state.toUiState()

        assertThat(uiState.secondaryLabel).isEqualTo(testString)
    }

    @Test
    fun switchActive_secondaryLabelNotModified() {
        val testString = "TEST STRING"
        val state =
            QSTile.State().apply {
                expandedAccessibilityClassName = Switch::class.java.name
                state = Tile.STATE_ACTIVE
                secondaryLabel = testString
            }

        val uiState = state.toUiState()

        assertThat(uiState.secondaryLabel).isEqualTo(testString)
    }

    @Test
    fun buttonInactive_secondaryLabelNotModifiedWhenEmpty() {
        val state =
            QSTile.State().apply {
                expandedAccessibilityClassName = Button::class.java.name
                state = Tile.STATE_INACTIVE
                secondaryLabel = ""
            }

        val uiState = state.toUiState()

        assertThat(uiState.secondaryLabel).isEmpty()
    }

    @Test
    fun buttonActive_secondaryLabelNotModifiedWhenEmpty() {
        val state =
            QSTile.State().apply {
                expandedAccessibilityClassName = Button::class.java.name
                state = Tile.STATE_ACTIVE
                secondaryLabel = ""
            }

        val uiState = state.toUiState()

        assertThat(uiState.secondaryLabel).isEmpty()
    }

    @Test
    fun buttonUnavailable_emptySecondaryLabel_default() {
        val state =
            QSTile.State().apply {
                expandedAccessibilityClassName = Button::class.java.name
                state = Tile.STATE_UNAVAILABLE
                secondaryLabel = ""
            }

        val uiState = state.toUiState()

        assertThat(uiState.secondaryLabel).isEqualTo(resources.getString(R.string.tile_unavailable))
    }

    @Test
    fun switchUnavailable_emptySecondaryLabel_defaultUnavailable() {
        val state =
            QSTile.State().apply {
                expandedAccessibilityClassName = Switch::class.java.name
                state = Tile.STATE_UNAVAILABLE
                secondaryLabel = ""
            }

        val uiState = state.toUiState()

        assertThat(uiState.secondaryLabel).isEqualTo(resources.getString(R.string.tile_unavailable))
    }

    @Test
    fun switchInactive_emptySecondaryLabel_defaultOff() {
        val state =
            QSTile.State().apply {
                expandedAccessibilityClassName = Switch::class.java.name
                state = Tile.STATE_INACTIVE
                secondaryLabel = ""
            }

        val uiState = state.toUiState()

        assertThat(uiState.secondaryLabel).isEqualTo(resources.getString(R.string.switch_bar_off))
    }

    @Test
    fun switchActive_emptySecondaryLabel_defaultOn() {
        val state =
            QSTile.State().apply {
                expandedAccessibilityClassName = Switch::class.java.name
                state = Tile.STATE_ACTIVE
                secondaryLabel = ""
            }

        val uiState = state.toUiState()

        assertThat(uiState.secondaryLabel).isEqualTo(resources.getString(R.string.switch_bar_on))
    }

    @Test
    fun disabledByPolicy_inactive_appearsAsUnavailable() {
        val stateDisabledByPolicy =
            QSTile.State().apply {
                state = Tile.STATE_INACTIVE
                disabledByPolicy = true
            }

        val uiState = stateDisabledByPolicy.toUiState()

        assertThat(uiState.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun disabledByPolicy_active_appearsAsUnavailable() {
        val stateDisabledByPolicy =
            QSTile.State().apply {
                state = Tile.STATE_ACTIVE
                disabledByPolicy = true
            }

        val uiState = stateDisabledByPolicy.toUiState()

        assertThat(uiState.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun disabledByPolicy_clickLabel() {
        val stateDisabledByPolicy =
            QSTile.State().apply {
                state = Tile.STATE_INACTIVE
                disabledByPolicy = true
            }

        val uiState = stateDisabledByPolicy.toUiState()
        assertThat(uiState.accessibilityUiState.clickLabel)
            .isEqualTo(
                resources.getString(
                    R.string.accessibility_tile_disabled_by_policy_action_description
                )
            )
    }

    @Test
    fun notDisabledByPolicy_clickLabel_null() {
        val stateDisabledByPolicy =
            QSTile.State().apply {
                state = Tile.STATE_INACTIVE
                disabledByPolicy = false
            }

        val uiState = stateDisabledByPolicy.toUiState()
        assertThat(uiState.accessibilityUiState.clickLabel).isNull()
    }

    @Test
    fun disabledByPolicy_unavailableInStateDescription() {
        val state =
            QSTile.State().apply {
                disabledByPolicy = true
                state = Tile.STATE_INACTIVE
            }

        val uiState = state.toUiState()
        assertThat(uiState.accessibilityUiState.stateDescription)
            .contains(resources.getString(R.string.tile_unavailable))
    }

    private fun QSTile.State.toUiState() = toUiState(resources)
}

private val TileUiState.accessibilityRole: Role
    get() = accessibilityUiState.accessibilityRole
