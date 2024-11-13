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

package com.android.systemui.qs.tiles.impl.qr

import android.content.res.mainResources
import com.android.systemui.classifier.fakeFalsingManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.backgroundCoroutineContext
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.qrcodescanner.dagger.QRCodeScannerModule
import com.android.systemui.qrcodescanner.qrCodeScannerController
import com.android.systemui.qs.qsEventLogger
import com.android.systemui.qs.tiles.base.actions.qsTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.analytics.qsTileAnalytics
import com.android.systemui.qs.tiles.base.interactor.fakeDisabledByPolicyInteractor
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelImpl
import com.android.systemui.qs.tiles.impl.custom.qsTileLogger
import com.android.systemui.qs.tiles.impl.qr.domain.interactor.QRCodeScannerTileDataInteractor
import com.android.systemui.qs.tiles.impl.qr.domain.interactor.QRCodeScannerTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.qr.ui.QRCodeScannerTileMapper
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.time.systemClock

val Kosmos.qsQRCodeScannerTileConfig by
    Kosmos.Fixture { QRCodeScannerModule.provideQRCodeScannerTileConfig(qsEventLogger) }

val Kosmos.qrCodeScannerTileDataInteractor by
    Kosmos.Fixture {
        QRCodeScannerTileDataInteractor(
            backgroundCoroutineContext,
            applicationCoroutineScope,
            qrCodeScannerController
        )
    }

val Kosmos.qrCodeScannerTileUserActionInteractor by
    Kosmos.Fixture { QRCodeScannerTileUserActionInteractor(qsTileIntentUserInputHandler) }

val Kosmos.qrCodeScannerTileMapper by
    Kosmos.Fixture { QRCodeScannerTileMapper(mainResources, mainResources.newTheme()) }

val Kosmos.qsQRCodeScannerViewModel by
    Kosmos.Fixture {
        QSTileViewModelImpl(
            qsQRCodeScannerTileConfig,
            { qrCodeScannerTileUserActionInteractor },
            { qrCodeScannerTileDataInteractor },
            { qrCodeScannerTileMapper },
            fakeDisabledByPolicyInteractor,
            fakeUserRepository,
            fakeFalsingManager,
            qsTileAnalytics,
            qsTileLogger,
            systemClock,
            testDispatcher,
            testDispatcher,
            testScope.backgroundScope,
        )
    }
