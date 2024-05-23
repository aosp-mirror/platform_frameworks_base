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

package com.android.systemui.qs.tiles.impl.screenrecord.ui

import android.graphics.drawable.TestStubDrawable
import android.text.TextUtils
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.screenrecord.domain.ui.ScreenRecordTileMapper
import com.android.systemui.qs.tiles.impl.screenrecord.qsScreenRecordTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ScreenRecordTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val config = kosmos.qsScreenRecordTileConfig

    private lateinit var mapper: ScreenRecordTileMapper

    @Before
    fun setup() {
        mapper =
            ScreenRecordTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(R.drawable.qs_screen_record_icon_on, TestStubDrawable())
                        addOverride(R.drawable.qs_screen_record_icon_off, TestStubDrawable())
                    }
                    .resources,
                context.theme
            )
    }

    @Test
    fun activeStateMatchesRecordingDataModel() {
        val inputModel = ScreenRecordModel.Recording

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createScreenRecordTileState(
                QSTileState.ActivationState.ACTIVE,
                R.drawable.qs_screen_record_icon_on,
                context.getString(R.string.quick_settings_screen_record_stop),
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun activeStateMatchesStartingDataModel() {
        val timeLeft = 0L
        val inputModel = ScreenRecordModel.Starting(timeLeft)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createScreenRecordTileState(
                QSTileState.ActivationState.ACTIVE,
                R.drawable.qs_screen_record_icon_on,
                String.format("%d...", timeLeft)
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun inactiveStateMatchesDisabledDataModel() {
        val inputModel = ScreenRecordModel.DoingNothing

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createScreenRecordTileState(
                QSTileState.ActivationState.INACTIVE,
                R.drawable.qs_screen_record_icon_off,
                context.getString(R.string.quick_settings_screen_record_start),
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createScreenRecordTileState(
        activationState: QSTileState.ActivationState,
        iconRes: Int,
        secondaryLabel: String,
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_screen_record_label)

        return QSTileState(
            { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK),
            if (TextUtils.isEmpty(secondaryLabel)) label
            else TextUtils.concat(label, ", ", secondaryLabel),
            null,
            if (activationState == QSTileState.ActivationState.INACTIVE)
                QSTileState.SideViewIcon.Chevron
            else QSTileState.SideViewIcon.None,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }
}
