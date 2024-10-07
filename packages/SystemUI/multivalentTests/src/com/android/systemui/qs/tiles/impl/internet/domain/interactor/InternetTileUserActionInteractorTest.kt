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

package com.android.systemui.qs.tiles.impl.internet.domain.interactor

import android.platform.test.annotations.EnabledOnRavenwood
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.qs.tiles.dialog.WifiStateWorker
import com.android.systemui.qs.tiles.impl.internet.domain.model.InternetTileModel
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.google.common.truth.Truth
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class InternetTileUserActionInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val inputHandler = FakeQSTileIntentUserInputHandler()

    private lateinit var underTest: InternetTileUserActionInteractor

    @Mock private lateinit var internetDialogManager: InternetDialogManager
    @Mock private lateinit var wifiStateWorker: WifiStateWorker
    @Mock private lateinit var controller: AccessPointController

    @Before
    fun setup() {
        internetDialogManager = mock<InternetDialogManager>()
        wifiStateWorker = mock<WifiStateWorker>()
        controller = mock<AccessPointController>()

        underTest =
            InternetTileUserActionInteractor(
                kosmos.testScope.coroutineContext,
                internetDialogManager,
                wifiStateWorker,
                controller,
                inputHandler,
            )
    }

    @Test
    fun handleClickWhenActive() =
        kosmos.testScope.runTest {
            val input = InternetTileModel.Active()

            underTest.handleInput(QSTileInputTestKtx.click(input))

            verify(internetDialogManager).create(eq(true), anyBoolean(), anyBoolean(), nullable())
        }

    @Test
    fun handleClickWhenInactive() =
        kosmos.testScope.runTest {
            val input = InternetTileModel.Inactive()

            underTest.handleInput(QSTileInputTestKtx.click(input))

            verify(internetDialogManager).create(eq(true), anyBoolean(), anyBoolean(), nullable())
        }

    @Test
    fun handleLongClickWhenActive() =
        kosmos.testScope.runTest {
            val input = InternetTileModel.Active()

            underTest.handleInput(QSTileInputTestKtx.longClick(input))

            QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
                Truth.assertThat(it.intent.action).isEqualTo(Settings.ACTION_WIFI_SETTINGS)
            }
        }

    @Test
    fun handleLongClickWhenInactive() =
        kosmos.testScope.runTest {
            val input = InternetTileModel.Inactive()

            underTest.handleInput(QSTileInputTestKtx.longClick(input))

            QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
                Truth.assertThat(it.intent.action).isEqualTo(Settings.ACTION_WIFI_SETTINGS)
            }
        }

    @Test
    fun handleSecondaryClickWhenWifiOn() =
        kosmos.testScope.runTest {
            whenever(wifiStateWorker.isWifiEnabled).thenReturn(true)

            underTest.handleInput(QSTileInputTestKtx.toggleClick(InternetTileModel.Active()))

            verify(wifiStateWorker, times(1)).isWifiEnabled = eq(false)
        }

    @Test
    fun handleSecondaryClickWhenWifiOff() =
        kosmos.testScope.runTest {
            whenever(wifiStateWorker.isWifiEnabled).thenReturn(false)

            underTest.handleInput(QSTileInputTestKtx.toggleClick(InternetTileModel.Inactive()))

            verify(wifiStateWorker, times(1)).isWifiEnabled = eq(true)
        }
}
