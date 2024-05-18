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

import android.content.Intent
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.actions.qsTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.qr.domain.model.QRCodeScannerTileModel
import com.android.systemui.qs.tiles.impl.qr.qrCodeScannerTileUserActionInteractor
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class QRCodeScannerTileUserActionInteractorTest : SysuiTestCase() {
    val kosmos = Kosmos()
    private val inputHandler = kosmos.qsTileIntentUserInputHandler
    private val underTest = kosmos.qrCodeScannerTileUserActionInteractor
    private val intent = mock<Intent>()

    @Test
    fun handleClick_available() = runTest {
        val inputModel = QRCodeScannerTileModel.Available(intent)

        underTest.handleInput(QSTileInputTestKtx.click(inputModel))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            intent
        }
    }

    @Test
    fun handleClick_temporarilyUnavailable() = runTest {
        val inputModel = QRCodeScannerTileModel.TemporarilyUnavailable

        underTest.handleInput(QSTileInputTestKtx.click(inputModel))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledNoInputs()
    }

    @Test
    fun handleLongClick_available() = runTest {
        val inputModel = QRCodeScannerTileModel.Available(intent)

        underTest.handleInput(QSTileInputTestKtx.longClick(inputModel))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledNoInputs()
    }

    @Test
    fun handleLongClick_temporarilyUnavailable() = runTest {
        val inputModel = QRCodeScannerTileModel.TemporarilyUnavailable

        underTest.handleInput(QSTileInputTestKtx.longClick(inputModel))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledNoInputs()
    }
}
