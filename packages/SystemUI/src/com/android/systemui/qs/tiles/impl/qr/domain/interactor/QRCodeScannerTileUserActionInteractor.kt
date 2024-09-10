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

package com.android.systemui.qs.tiles.impl.qr.domain.interactor

import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.qr.domain.model.QRCodeScannerTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject

/** Handles qr tile clicks. */
class QRCodeScannerTileUserActionInteractor
@Inject
constructor(
    private val qsTileIntentUserActionHandler: QSTileIntentUserInputHandler,
) : QSTileUserActionInteractor<QRCodeScannerTileModel> {

    override suspend fun handleInput(input: QSTileInput<QRCodeScannerTileModel>): Unit =
        with(input) {
            when (action) {
                is QSTileUserAction.Click -> {
                    when (data) {
                        is QRCodeScannerTileModel.Available ->
                            qsTileIntentUserActionHandler.handle(
                                action.expandable,
                                data.intent,
                                true
                            )
                        is QRCodeScannerTileModel.TemporarilyUnavailable -> {} // no-op
                    }
                }
                is QSTileUserAction.LongClick -> {} // no-op
                is QSTileUserAction.ToggleClick -> {}
            }
        }
}
