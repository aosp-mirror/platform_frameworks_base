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

import android.content.res.Resources
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.impl.qr.domain.model.QRCodeScannerTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.res.R
import javax.inject.Inject

/** Maps [QRCodeScannerTileModel] to [QSTileState]. */
class QRCodeScannerTileMapper
@Inject
constructor(
    @Main private val resources: Resources,
    private val theme: Resources.Theme,
) : QSTileDataToStateMapper<QRCodeScannerTileModel> {

    override fun map(config: QSTileConfig, data: QRCodeScannerTileModel): QSTileState =
        QSTileState.build(resources, theme, config.uiConfig) {
            label = resources.getString(R.string.qr_code_scanner_title)
            contentDescription = label
            icon = {
                Icon.Loaded(resources.getDrawable(R.drawable.ic_qr_code_scanner, theme), null)
            }
            sideViewIcon = QSTileState.SideViewIcon.Chevron
            supportedActions = setOf(QSTileState.UserAction.CLICK)

            when (data) {
                is QRCodeScannerTileModel.Available -> {
                    activationState = QSTileState.ActivationState.INACTIVE
                    secondaryLabel = null
                }
                is QRCodeScannerTileModel.TemporarilyUnavailable -> {
                    activationState = QSTileState.ActivationState.UNAVAILABLE
                    secondaryLabel =
                        resources.getString(R.string.qr_code_scanner_updating_secondary_label)
                }
            }
        }
}
