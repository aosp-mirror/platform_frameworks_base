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

package com.android.systemui.qs.tiles.impl.qr.ui

import android.content.Intent
import android.graphics.drawable.TestStubDrawable
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject
import com.android.systemui.qs.tiles.impl.qr.domain.model.QRCodeScannerTileModel
import com.android.systemui.qs.tiles.impl.qr.qsQRCodeScannerTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QRCodeScannerTileMapperTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val config = kosmos.qsQRCodeScannerTileConfig

    private lateinit var mapper: QRCodeScannerTileMapper

    @Before
    fun setup() {
        mapper =
            QRCodeScannerTileMapper(
                context.orCreateTestableResources
                    .apply {
                        addOverride(
                            com.android.systemui.res.R.drawable.ic_qr_code_scanner,
                            TestStubDrawable()
                        )
                    }
                    .resources,
                context.theme
            )
    }

    @Test
    fun availableModel() {
        val mockIntent = mock<Intent>()
        val inputModel = QRCodeScannerTileModel.Available(mockIntent)

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createQRCodeScannerTileState(
                QSTileState.ActivationState.INACTIVE,
                null,
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    @Test
    fun temporarilyUnavailableModel() {
        val inputModel = QRCodeScannerTileModel.TemporarilyUnavailable

        val outputState = mapper.map(config, inputModel)

        val expectedState =
            createQRCodeScannerTileState(
                QSTileState.ActivationState.UNAVAILABLE,
                context.getString(
                    com.android.systemui.res.R.string.qr_code_scanner_updating_secondary_label
                )
            )
        QSTileStateSubject.assertThat(outputState).isEqualTo(expectedState)
    }

    private fun createQRCodeScannerTileState(
        activationState: QSTileState.ActivationState,
        secondaryLabel: String?,
    ): QSTileState {
        val label = context.getString(com.android.systemui.res.R.string.qr_code_scanner_title)
        return QSTileState(
            {
                Icon.Loaded(
                    context.getDrawable(com.android.systemui.res.R.drawable.ic_qr_code_scanner)!!,
                    null
                )
            },
            com.android.systemui.res.R.drawable.ic_qr_code_scanner,
            label,
            activationState,
            secondaryLabel,
            setOf(QSTileState.UserAction.CLICK),
            label,
            null,
            QSTileState.SideViewIcon.Chevron,
            QSTileState.EnabledState.ENABLED,
            Switch::class.qualifiedName
        )
    }
}
