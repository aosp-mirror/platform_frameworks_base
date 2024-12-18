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

import android.os.UserHandle
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.qr.domain.model.QRCodeScannerTileModel
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart

/** Observes one qr scanner state changes providing the [QRCodeScannerTileModel]. */
class QRCodeScannerTileDataInteractor
@Inject
constructor(
    @Background private val bgCoroutineContext: CoroutineContext,
    @Application private val scope: CoroutineScope,
    private val qrController: QRCodeScannerController,
) : QSTileDataInteractor<QRCodeScannerTileModel> {
    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<QRCodeScannerTileModel> =
        conflatedCallbackFlow {
                qrController.registerQRCodeScannerChangeObservers(DEFAULT_QR_CODE_SCANNER_CHANGE)
                val callback =
                    object : QRCodeScannerController.Callback {
                        override fun onQRCodeScannerActivityChanged() {
                            trySend(generateModel())
                        }
                    }
                qrController.addCallback(callback)
                awaitClose {
                    qrController.removeCallback(callback)
                    qrController.unregisterQRCodeScannerChangeObservers(
                        DEFAULT_QR_CODE_SCANNER_CHANGE
                    )
                }
            }
            .onStart { emit(generateModel()) }
            .flowOn(bgCoroutineContext)

    override fun availability(user: UserHandle): Flow<Boolean> =
        flowOf(qrController.isCameraAvailable)

    private fun generateModel(): QRCodeScannerTileModel {
        val intent = qrController.intent

        return if (qrController.isAbleToLaunchScannerActivity && intent != null)
            QRCodeScannerTileModel.Available(intent)
        else QRCodeScannerTileModel.TemporarilyUnavailable
    }
}
