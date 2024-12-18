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

package com.android.systemui.qs.tiles.impl.battery.doman.interactor

import android.platform.test.annotations.EnabledOnRavenwood
import android.provider.Settings
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.battery.domain.interactor.BatterySaverTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.battery.domain.model.BatterySaverTileModel
import com.android.systemui.utils.leaks.FakeBatteryController
import com.google.common.truth.Truth
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class BatterySaverTileUserActionInteractorTest : SysuiTestCase() {
    private val inputHandler = FakeQSTileIntentUserInputHandler()
    private val controller = FakeBatteryController(LeakCheck())
    private val underTest = BatterySaverTileUserActionInteractor(inputHandler, controller)

    @Test
    fun handleClickWhenNotPluggedIn_flipsPowerSaverMode() = runTest {
        val originalPowerSaveMode = controller.isPowerSave
        controller.isPluggedIn = false

        underTest.handleInput(
            QSTileInputTestKtx.click(BatterySaverTileModel.Standard(false, originalPowerSaveMode))
        )

        Truth.assertThat(controller.isPowerSave).isNotEqualTo(originalPowerSaveMode)
    }

    @Test
    fun handleClickWhenPluggedIn_doesNotTurnOnPowerSaverMode() = runTest {
        controller.setPowerSaveMode(false)
        val originalPowerSaveMode = controller.isPowerSave
        controller.isPluggedIn = true

        underTest.handleInput(
            QSTileInputTestKtx.click(
                BatterySaverTileModel.Standard(controller.isPluggedIn, originalPowerSaveMode)
            )
        )

        Truth.assertThat(controller.isPowerSave).isEqualTo(originalPowerSaveMode)
    }

    @Test
    fun handleLongClick() = runTest {
        underTest.handleInput(
            QSTileInputTestKtx.longClick(BatterySaverTileModel.Standard(false, false))
        )

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            Truth.assertThat(it.intent.action).isEqualTo(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        }
    }
}
