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
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController.Callback
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.qr.domain.model.QRCodeScannerTileModel
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class QRCodeScannerTileDataInteractorTest : SysuiTestCase() {

    private val testUser = UserHandle.of(1)!!
    private val testDispatcher = StandardTestDispatcher()
    private val scope = TestScope(testDispatcher)
    private val testIntent = mock<Intent>()
    private val qrCodeScannerController =
        mock<QRCodeScannerController> {
            whenever(intent).thenReturn(testIntent)
            whenever(isAbleToLaunchScannerActivity).thenReturn(false)
        }
    private val testAvailableModel = QRCodeScannerTileModel.Available(testIntent)
    private val testUnavailableModel = QRCodeScannerTileModel.TemporarilyUnavailable

    private val underTest: QRCodeScannerTileDataInteractor =
        QRCodeScannerTileDataInteractor(
            testDispatcher,
            scope.backgroundScope,
            qrCodeScannerController,
        )

    @Test
    fun availability_matchesController_cameraNotAvailable() =
        scope.runTest {
            val expectedAvailability = false
            whenever(qrCodeScannerController.isCameraAvailable).thenReturn(false)

            val availability by collectLastValue(underTest.availability(testUser))

            assertThat(availability).isEqualTo(expectedAvailability)
        }

    @Test
    fun availability_matchesController_cameraIsAvailable() =
        scope.runTest {
            val expectedAvailability = true
            whenever(qrCodeScannerController.isCameraAvailable).thenReturn(true)

            val availability by collectLastValue(underTest.availability(testUser))

            assertThat(availability).isEqualTo(expectedAvailability)
        }

    @Test
    fun data_matchesController() =
        scope.runTest {
            val captor = argumentCaptor<Callback>()
            val lastData by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()

            verify(qrCodeScannerController).addCallback(captor.capture())
            val callback = captor.value

            assertThat(lastData!!).isEqualTo(testUnavailableModel)

            whenever(qrCodeScannerController.isAbleToLaunchScannerActivity).thenReturn(true)
            callback.onQRCodeScannerActivityChanged()
            runCurrent()
            assertThat(lastData!!).isEqualTo(testAvailableModel)

            whenever(qrCodeScannerController.isAbleToLaunchScannerActivity).thenReturn(false)
            callback.onQRCodeScannerActivityChanged()
            runCurrent()
            assertThat(lastData!!).isEqualTo(testUnavailableModel)
        }
}
