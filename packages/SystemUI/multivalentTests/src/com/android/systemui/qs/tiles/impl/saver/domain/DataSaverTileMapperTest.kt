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

package com.android.systemui.qs.tiles.impl.saver.domain

import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.saver.domain.model.DataSaverTileModel
import com.android.systemui.qs.tiles.impl.saver.qsDataSaverTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DataSaverTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val dataSaverTileConfig = kosmos.qsDataSaverTileConfig

    // Using lazy (versus =) to make sure we override the right context -- see b/311612168
    private val mapper by lazy {
        DataSaverTileMapper(
            context.orCreateTestableResources
                .apply {
                    addOverride(R.drawable.qs_data_saver_icon_off, TestStubDrawable())
                    addOverride(R.drawable.qs_data_saver_icon_on, TestStubDrawable())
                }
                .resources,
            context.theme
        )
    }

    @Test
    fun activeStateMatchesEnabledModel() {
        val inputModel = DataSaverTileModel(true)

        val outputState = mapper.map(dataSaverTileConfig, inputModel)

        val expectedState =
            createDataSaverTileState(
                QSTileState.ActivationState.ACTIVE,
                R.drawable.qs_data_saver_icon_on
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun inactiveStateMatchesDisabledModel() {
        val inputModel = DataSaverTileModel(false)

        val outputState = mapper.map(dataSaverTileConfig, inputModel)

        val expectedState =
            createDataSaverTileState(
                QSTileState.ActivationState.INACTIVE,
                R.drawable.qs_data_saver_icon_off
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createDataSaverTileState(
        activationState: QSTileState.ActivationState,
        iconRes: Int
    ): QSTileState {
        val label = context.getString(R.string.data_saver)
        val secondaryLabel =
            if (activationState == QSTileState.ActivationState.ACTIVE)
                context.resources.getStringArray(R.array.tile_states_saver)[2]
            else if (activationState == QSTileState.ActivationState.INACTIVE)
                context.resources.getStringArray(R.array.tile_states_saver)[1]
            else context.resources.getStringArray(R.array.tile_states_saver)[0]

        return QSTileState(
            { Icon.Loaded(context.getDrawable(iconRes)!!, null) },
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
