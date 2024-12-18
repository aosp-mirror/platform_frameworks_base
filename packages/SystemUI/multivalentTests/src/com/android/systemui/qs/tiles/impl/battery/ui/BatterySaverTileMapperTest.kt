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

package com.android.systemui.qs.tiles.impl.battery.ui

import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.battery.domain.model.BatterySaverTileModel
import com.android.systemui.qs.tiles.impl.battery.qsBatterySaverTileConfig
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BatterySaverTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val batterySaverTileConfig = kosmos.qsBatterySaverTileConfig
    private lateinit var mapper: BatterySaverTileMapper

    @Before
    fun setup() {
        mapper =
            BatterySaverTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(R.drawable.qs_battery_saver_icon_off, TestStubDrawable())
                        addOverride(R.drawable.qs_battery_saver_icon_on, TestStubDrawable())
                    }
                    .resources,
                context.theme,
            )
    }

    @Test
    fun map_standard_notPluggedInNotPowerSaving() {
        val inputModel = BatterySaverTileModel.Standard(false, false)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.INACTIVE,
                "",
                R.drawable.qs_battery_saver_icon_off,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_standard_notPluggedInPowerSaving() {
        val inputModel = BatterySaverTileModel.Standard(false, true)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.ACTIVE,
                "",
                R.drawable.qs_battery_saver_icon_on,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_standard_pluggedInPowerSaving() {
        val inputModel = BatterySaverTileModel.Standard(true, true)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.UNAVAILABLE,
                "",
                R.drawable.qs_battery_saver_icon_on,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_standard_pluggedInNotPowerSaving() {
        val inputModel = BatterySaverTileModel.Standard(true, false)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.UNAVAILABLE,
                "",
                R.drawable.qs_battery_saver_icon_off,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_extremeSaverDisabledNotPluggedInNotPowerSaving() {
        val inputModel = BatterySaverTileModel.Extreme(false, false, false)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.INACTIVE,
                "",
                R.drawable.qs_battery_saver_icon_off,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_extremeSaverDisabledNotPluggedInPowerSaving() {
        val inputModel = BatterySaverTileModel.Extreme(false, true, false)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.standard_battery_saver_text),
                R.drawable.qs_battery_saver_icon_on,
                context.getString(R.string.standard_battery_saver_text),
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_extremeSaverDisabledPluggedInPowerSaving() {
        val inputModel = BatterySaverTileModel.Extreme(true, true, false)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.UNAVAILABLE,
                "",
                R.drawable.qs_battery_saver_icon_on,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_extremeSaverDisabledPluggedInNotPowerSaving() {
        val inputModel = BatterySaverTileModel.Extreme(true, false, false)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.UNAVAILABLE,
                "",
                R.drawable.qs_battery_saver_icon_off,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_extremeSaverEnabledNotPluggedInNotPowerSaving() {
        val inputModel = BatterySaverTileModel.Extreme(false, false, true)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.INACTIVE,
                "",
                R.drawable.qs_battery_saver_icon_off,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_extremeSaverEnabledNotPluggedInPowerSaving() {
        val inputModel = BatterySaverTileModel.Extreme(false, true, true)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.ACTIVE,
                context.getString(R.string.extreme_battery_saver_text),
                R.drawable.qs_battery_saver_icon_on,
                context.getString(R.string.extreme_battery_saver_text),
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_extremeSaverEnabledPluggedInPowerSaving() {
        val inputModel = BatterySaverTileModel.Extreme(true, true, true)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.UNAVAILABLE,
                "",
                R.drawable.qs_battery_saver_icon_on,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun map_extremeSaverEnabledPluggedInNotPowerSaving() {
        val inputModel = BatterySaverTileModel.Extreme(true, false, true)

        val outputState = mapper.map(batterySaverTileConfig, inputModel)

        val expectedState =
            createBatterySaverTileState(
                QSTileState.ActivationState.UNAVAILABLE,
                "",
                R.drawable.qs_battery_saver_icon_off,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createBatterySaverTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
        iconRes: Int,
        stateDescription: CharSequence?,
    ): QSTileState {
        val label = context.getString(R.string.battery_detail_switch_title)
        return QSTileState(
            { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
            iconRes,
            label,
            activationState,
            secondaryLabel,
            if (activationState == QSTileState.ActivationState.UNAVAILABLE)
                setOf(QSTileState.UserAction.LONG_CLICK)
            else setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
            label,
            stateDescription,
            QSTileState.SideViewIcon.None,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }
}
