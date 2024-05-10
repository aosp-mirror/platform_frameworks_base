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

package com.android.systemui.qs.tiles.impl.night.ui

import android.graphics.drawable.TestStubDrawable
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.night.domain.model.NightDisplayTileModel
import com.android.systemui.qs.tiles.impl.night.qsNightDisplayTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.util.mockito.mock
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class NightDisplayTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val config = kosmos.qsNightDisplayTileConfig

    private val testStartTime = LocalTime.MIDNIGHT
    private val testEndTime = LocalTime.NOON

    private lateinit var mapper: NightDisplayTileMapper

    @Before
    fun setup() {
        mapper =
            NightDisplayTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(R.drawable.qs_nightlight_icon_on, TestStubDrawable())
                        addOverride(R.drawable.qs_nightlight_icon_off, TestStubDrawable())
                    }
                    .resources,
                context.theme,
                mock<QSTileLogger>(),
            )
    }

    @Test
    fun disabledModel_whenAutoModeOff() {
        val inputModel = NightDisplayTileModel.AutoModeOff(false, false)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.INACTIVE,
                context.resources.getStringArray(R.array.tile_states_night)[Tile.STATE_INACTIVE]
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    /** Force enable does not change the mode by itself. */
    @Test
    fun disabledModel_whenAutoModeOff_whenForceEnable() {
        val inputModel = NightDisplayTileModel.AutoModeOff(false, true)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.INACTIVE,
                context.resources.getStringArray(R.array.tile_states_night)[Tile.STATE_INACTIVE]
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel_whenAutoModeOff() {
        val inputModel = NightDisplayTileModel.AutoModeOff(true, false)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.ACTIVE,
                context.resources.getStringArray(R.array.tile_states_night)[Tile.STATE_ACTIVE]
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel_forceAutoMode_whenAutoModeOff() {
        val inputModel = NightDisplayTileModel.AutoModeOff(true, true)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.ACTIVE,
                context.resources.getStringArray(R.array.tile_states_night)[Tile.STATE_ACTIVE]
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel_autoModeTwilight_locationOff() {
        val inputModel = NightDisplayTileModel.AutoModeTwilight(true, false, false)

        val outputState = mapper.map(config, inputModel)

        val expectedState = createNightDisplayTileState(QSTileState.ActivationState.ACTIVE, null)
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel_autoModeTwilight_locationOn() {
        val inputModel = NightDisplayTileModel.AutoModeTwilight(true, false, true)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.quick_settings_night_secondary_label_until_sunrise)
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun disabledModel_autoModeTwilight_locationOn() {
        val inputModel = NightDisplayTileModel.AutoModeTwilight(false, false, true)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(R.string.quick_settings_night_secondary_label_on_at_sunset)
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun disabledModel_autoModeTwilight_locationOff() {
        val inputModel = NightDisplayTileModel.AutoModeTwilight(false, false, false)

        val outputState = mapper.map(config, inputModel)

        val expectedState = createNightDisplayTileState(QSTileState.ActivationState.INACTIVE, null)
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun disabledModel_autoModeCustom_24Hour() {
        val inputModel =
            NightDisplayTileModel.AutoModeCustom(false, false, testStartTime, null, true)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(
                    R.string.quick_settings_night_secondary_label_on_at,
                    formatter24Hour.format(testStartTime)
                )
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun disabledModel_autoModeCustom_12Hour() {
        val inputModel =
            NightDisplayTileModel.AutoModeCustom(false, false, testStartTime, null, false)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(
                    R.string.quick_settings_night_secondary_label_on_at,
                    formatter12Hour.format(testStartTime)
                )
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    /** Should have the same outcome as [disabledModel_autoModeCustom_12Hour] */
    @Test
    fun disabledModel_autoModeCustom_12Hour_isEnrolledForcedAutoMode() {
        val inputModel =
            NightDisplayTileModel.AutoModeCustom(false, true, testStartTime, null, false)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.INACTIVE,
                context.getString(
                    R.string.quick_settings_night_secondary_label_on_at,
                    formatter12Hour.format(testStartTime)
                )
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel_autoModeCustom_24Hour() {
        val inputModel = NightDisplayTileModel.AutoModeCustom(true, false, null, testEndTime, true)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(
                    R.string.quick_settings_secondary_label_until,
                    formatter24Hour.format(testEndTime)
                )
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel_autoModeCustom_12Hour() {
        val inputModel = NightDisplayTileModel.AutoModeCustom(true, false, null, testEndTime, false)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(
                    R.string.quick_settings_secondary_label_until,
                    formatter12Hour.format(testEndTime)
                )
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    /** Should have the same state as [enabledModel_autoModeCustom_24Hour] */
    @Test
    fun enabledModel_autoModeCustom_24Hour_forceEnabled() {
        val inputModel = NightDisplayTileModel.AutoModeCustom(true, true, null, testEndTime, true)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createNightDisplayTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(
                    R.string.quick_settings_secondary_label_until,
                    formatter24Hour.format(testEndTime)
                )
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createNightDisplayTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String?
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_night_display_label)

        val contentDescription =
            if (TextUtils.isEmpty(secondaryLabel)) label
            else TextUtils.concat(label, ", ", secondaryLabel)
        return QSTileState(
            {
                Icon.Loaded(
                    context.getDrawable(
                        if (activationState == QSTileState.ActivationState.ACTIVE)
                            R.drawable.qs_nightlight_icon_on
                        else R.drawable.qs_nightlight_icon_off
                    )!!,
                    null
                )
            },
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            contentDescription,
            null,
            QSTileState.SideViewIcon.None,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }

    private companion object {
        val formatter12Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("hh:mm a")
        val formatter24Hour: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
