/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qrcodescanner.dagger

import com.android.systemui.Flags
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.QRCodeScannerTile
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.qr.domain.interactor.QRCodeScannerTileDataInteractor
import com.android.systemui.qs.tiles.impl.qr.domain.interactor.QRCodeScannerTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.qr.domain.model.QRCodeScannerTileModel
import com.android.systemui.qs.tiles.impl.qr.ui.QRCodeScannerTileMapper
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.viewmodel.StubQSTileViewModel
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
interface QRCodeScannerModule {

    /**  */
    @Binds
    @IntoMap
    @StringKey(QRCodeScannerTile.TILE_SPEC)
    fun bindQRCodeScannerTile(qrCodeScannerTile: QRCodeScannerTile): QSTileImpl<*>

    companion object {
        const val QR_CODE_SCANNER_TILE_SPEC = "qr_code_scanner"

        @Provides
        @IntoMap
        @StringKey(QR_CODE_SCANNER_TILE_SPEC)
        fun provideQRCodeScannerTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(QR_CODE_SCANNER_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qr_code_scanner,
                        labelRes = R.string.qr_code_scanner_title,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
            )

        /** Inject QR Code Scanner Tile into tileViewModelMap in QSModule. */
        @Provides
        @IntoMap
        @StringKey(QR_CODE_SCANNER_TILE_SPEC)
        fun provideQRCodeScannerTileViewModel(
            factory: QSTileViewModelFactory.Static<QRCodeScannerTileModel>,
            mapper: QRCodeScannerTileMapper,
            stateInteractor: QRCodeScannerTileDataInteractor,
            userActionInteractor: QRCodeScannerTileUserActionInteractor
        ): QSTileViewModel =
            if (Flags.qsNewTilesFuture())
                factory.create(
                    TileSpec.create(QR_CODE_SCANNER_TILE_SPEC),
                    userActionInteractor,
                    stateInteractor,
                    mapper,
                )
            else StubQSTileViewModel
    }
}
