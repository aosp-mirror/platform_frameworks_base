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

package com.android.systemui.qs.tiles.impl.onehanded.ui

import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tileimpl.SubtitleArrayMapping
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.onehanded.domain.model.OneHandedModeTileModel
import com.android.systemui.qs.tiles.impl.onehanded.qsOneHandedModeTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class OneHandedModeTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val config = kosmos.qsOneHandedModeTileConfig
    private val subtitleArrayId = SubtitleArrayMapping.getSubtitleId(config.tileSpec.spec)
    private val subtitleArray by lazy { context.resources.getStringArray(subtitleArrayId) }

    private lateinit var mapper: OneHandedModeTileMapper

    @Before
    fun setup() {
        mapper =
            OneHandedModeTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(
                            com.android.internal.R.drawable.ic_qs_one_handed_mode,
                            TestStubDrawable()
                        )
                    }
                    .resources,
                context.theme
            )
    }

    @Test
    fun disabledModel() {
        val inputModel = OneHandedModeTileModel(false)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createOneHandedModeTileState(
                QSTileState.ActivationState.INACTIVE,
                subtitleArray[1],
                com.android.internal.R.drawable.ic_qs_one_handed_mode
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun enabledModel() {
        val inputModel = OneHandedModeTileModel(true)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createOneHandedModeTileState(
                QSTileState.ActivationState.ACTIVE,
                subtitleArray[2],
                com.android.internal.R.drawable.ic_qs_one_handed_mode
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createOneHandedModeTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String,
        iconRes: Int,
    ): QSTileState {
        val label = context.getString(R.string.quick_settings_onehanded_label)
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
