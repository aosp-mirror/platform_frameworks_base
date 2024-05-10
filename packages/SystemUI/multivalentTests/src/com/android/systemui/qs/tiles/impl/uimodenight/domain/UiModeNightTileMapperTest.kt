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

package com.android.systemui.qs.tiles.impl.uimodenight.domain

import android.app.UiModeManager
import android.graphics.drawable.TestStubDrawable
import android.text.TextUtils
import android.view.View
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.uimodenight.UiModeNightTileModelHelper.createModel
import com.android.systemui.qs.tiles.impl.uimodenight.qsUiModeNightTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import kotlin.reflect.KClass
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class UiModeNightTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val qsTileConfig = kosmos.qsUiModeNightTileConfig

    private val mapper by lazy {
        UiModeNightTileMapper(
            context.orCreateTestableResources
                .apply {
                    addOverride(R.drawable.qs_light_dark_theme_icon_off, TestStubDrawable())
                    addOverride(R.drawable.qs_light_dark_theme_icon_on, TestStubDrawable())
                }
                .resources,
            context.theme
        )
    }

    private fun createUiNightModeTileState(
        iconRes: Int = R.drawable.qs_light_dark_theme_icon_off,
        label: CharSequence = context.getString(R.string.quick_settings_ui_mode_night_label),
        activationState: QSTileState.ActivationState = QSTileState.ActivationState.INACTIVE,
        secondaryLabel: CharSequence? = null,
        supportedActions: Set<QSTileState.UserAction> =
            if (activationState == QSTileState.ActivationState.UNAVAILABLE)
                setOf(QSTileState.UserAction.LONG_CLICK)
            else setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
        contentDescription: CharSequence? = null,
        stateDescription: CharSequence? = null,
        sideViewIcon: QSTileState.SideViewIcon = QSTileState.SideViewIcon.None,
        enabledState: QSTileState.EnabledState = QSTileState.EnabledState.ENABLED,
        expandedAccessibilityClass: KClass<out View>? = Switch::class,
    ): QSTileState {
        return QSTileState(
            { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
            label,
            activationState,
            secondaryLabel,
            supportedActions,
            contentDescription,
            stateDescription,
            sideViewIcon,
            enabledState,
            expandedAccessibilityClass?.qualifiedName
        )
    }

    @Test
    fun mapsEnabledDataToUnavailableStateWhenOnPowerSave() {
        val inputModel = createModel(nightMode = true, powerSave = true)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedSecondaryLabel =
            context.getString(R.string.quick_settings_dark_mode_secondary_label_battery_saver)
        val expectedContentDescription =
            TextUtils.concat(expectedLabel, ", ", expectedSecondaryLabel)
        val expectedState =
            createUiNightModeTileState(
                activationState = QSTileState.ActivationState.UNAVAILABLE,
                secondaryLabel = expectedSecondaryLabel,
                contentDescription = expectedContentDescription
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun mapsDisabledDataToUnavailableStateWhenOnPowerSave() {
        val inputModel = createModel(nightMode = false, powerSave = true)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedSecondaryLabel =
            context.getString(R.string.quick_settings_dark_mode_secondary_label_battery_saver)
        val expectedContentDescription =
            TextUtils.concat(expectedLabel, ", ", expectedSecondaryLabel)
        val expectedState =
            createUiNightModeTileState(
                activationState = QSTileState.ActivationState.UNAVAILABLE,
                secondaryLabel = expectedSecondaryLabel,
                contentDescription = expectedContentDescription
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun mapsDisabledDataToInactiveState() {
        val inputModel = createModel(nightMode = false, powerSave = false)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[1]
        val expectedState =
            createUiNightModeTileState(
                activationState = QSTileState.ActivationState.INACTIVE,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                contentDescription = expectedLabel
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun mapsEnabledDataToActiveState() {
        val inputModel = createModel(true, false)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[2]
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_on,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.ACTIVE,
                contentDescription = expectedLabel
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun mapsEnabledDataToOnIconState() {
        val inputModel = createModel(nightMode = true, powerSave = false)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[2]
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_on,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.ACTIVE,
                contentDescription = expectedLabel
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun mapsDisabledDataToOffIconState() {
        val inputModel = createModel(nightMode = false, powerSave = false)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[1]
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.INACTIVE,
                contentDescription = expectedLabel
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun supportsClickAndLongClickActionsWhenNotInPowerSaveInNightMode() {
        val inputModel = createModel(nightMode = true, powerSave = false)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[2]
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_on,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.ACTIVE,
                contentDescription = expectedLabel,
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun supportsOnlyLongClickActionWhenUnavailableInPowerSaveInNightMode() {
        val inputModel = createModel(nightMode = true, powerSave = true)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel =
            context.getString(R.string.quick_settings_dark_mode_secondary_label_battery_saver)
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedContentDescription =
            TextUtils.concat(expectedLabel, ", ", expectedSecondaryLabel)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.UNAVAILABLE,
                contentDescription = expectedContentDescription,
                supportedActions = setOf(QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun supportsClickAndLongClickActionsWhenNotInPowerSaveNotInNightMode() {
        val inputModel = createModel(nightMode = false, powerSave = false)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[1]
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.INACTIVE,
                contentDescription = expectedLabel,
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun supportsOnlyClickActionWhenUnavailableInPowerSaveNotInNightMode() {
        val inputModel = createModel(nightMode = false, powerSave = true)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel =
            context.getString(R.string.quick_settings_dark_mode_secondary_label_battery_saver)
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.UNAVAILABLE,
                contentDescription = TextUtils.concat(expectedLabel, ", ", expectedSecondaryLabel),
                supportedActions = setOf(QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun secondaryLabelCorrectWhenInPowerSaveMode() {
        val inputModel = createModel(powerSave = true)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel =
            context.getString(R.string.quick_settings_dark_mode_secondary_label_battery_saver)
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.UNAVAILABLE,
                contentDescription = TextUtils.concat(expectedLabel, ", ", expectedSecondaryLabel),
                supportedActions = setOf(QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun secondaryLabelCorrectWhenInNightModeNotInPowerSaveModeLocationEnabledUiModeIsNightAuto() {
        val inputModel =
            createModel(
                nightMode = true,
                powerSave = false,
                isLocationEnabled = true,
                uiMode = UiModeManager.MODE_NIGHT_AUTO
            )

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel =
            context.getString(R.string.quick_settings_dark_mode_secondary_label_until_sunrise)
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_on,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.ACTIVE,
                contentDescription = TextUtils.concat(expectedLabel, ", ", expectedSecondaryLabel),
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun secondaryLabelCorrectWhenNotInNightModeNotInPowerSaveModeLocationEnableUiModeIsNightAuto() {
        val inputModel =
            createModel(
                nightMode = false,
                powerSave = false,
                isLocationEnabled = true,
                uiMode = UiModeManager.MODE_NIGHT_AUTO
            )

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel =
            context.getString(R.string.quick_settings_dark_mode_secondary_label_on_at_sunset)
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.INACTIVE,
                contentDescription = TextUtils.concat(expectedLabel, ", ", expectedSecondaryLabel),
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun secondaryLabelCorrectWhenNotInPowerSaveAndUiModeIsNightYesInNightMode() {
        val inputModel =
            createModel(nightMode = true, powerSave = false, uiMode = UiModeManager.MODE_NIGHT_YES)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[2]

        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_on,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.ACTIVE,
                contentDescription = expectedLabel,
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun secondaryLabelCorrectWhenNotInPowerSaveAndUiModeIsNightNoNotInNightMode() {
        val inputModel =
            createModel(nightMode = false, powerSave = false, uiMode = UiModeManager.MODE_NIGHT_NO)

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[1]
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.INACTIVE,
                contentDescription = expectedLabel,
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun secondaryLabelCorrectWhenNotInPowerSaveAndUiModeIsUnknownCustomNotInNightMode() {
        val inputModel =
            createModel(
                nightMode = false,
                powerSave = false,
                uiMode = UiModeManager.MODE_NIGHT_CUSTOM,
                nighModeCustomType = UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN
            )

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[1]
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.INACTIVE,
                contentDescription = expectedLabel,
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun secondaryLabelCorrectWhenNotInPowerSaveAndUiModeIsUnknownCustomInNightMode() {
        val inputModel =
            createModel(
                nightMode = true,
                powerSave = false,
                uiMode = UiModeManager.MODE_NIGHT_CUSTOM,
                nighModeCustomType = UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN
            )

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel = context.resources.getStringArray(R.array.tile_states_dark)[2]
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_on,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.ACTIVE,
                contentDescription = expectedLabel,
                supportedActions =
                    setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }

    @Test
    fun secondaryLabelCorrectWhenInPowerSaveAndUiModeIsUnknownCustomNotInNightMode() {
        val inputModel =
            createModel(
                nightMode = false,
                powerSave = true,
                uiMode = UiModeManager.MODE_NIGHT_CUSTOM,
                nighModeCustomType = UiModeManager.MODE_NIGHT_CUSTOM_TYPE_UNKNOWN
            )

        val actualState: QSTileState = mapper.map(qsTileConfig, inputModel)

        val expectedSecondaryLabel =
            context.getString(R.string.quick_settings_dark_mode_secondary_label_battery_saver)
        val expectedLabel = context.getString(R.string.quick_settings_ui_mode_night_label)
        val expectedContentDescription =
            TextUtils.concat(expectedLabel, ", ", expectedSecondaryLabel)
        val expectedState =
            createUiNightModeTileState(
                iconRes = R.drawable.qs_light_dark_theme_icon_off,
                label = expectedLabel,
                secondaryLabel = expectedSecondaryLabel,
                activationState = QSTileState.ActivationState.UNAVAILABLE,
                contentDescription = expectedContentDescription,
                supportedActions = setOf(QSTileState.UserAction.LONG_CLICK)
            )
        QSTileStateSubject.assertThat(actualState).isEqualTo(expectedState)
    }
}
