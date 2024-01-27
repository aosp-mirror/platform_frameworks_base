/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.uimodenight.domain

import android.app.UiModeManager
import android.platform.test.annotations.EnabledOnRavenwood
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.intentInputs
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.uimodenight.UiModeNightTileModelHelper.createModel
import com.android.systemui.qs.tiles.impl.uimodenight.domain.interactor.UiModeNightTileUserActionInteractor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class UiModeNightTileUserActionInteractorTest : SysuiTestCase() {

    private val qsTileIntentUserActionHandler = FakeQSTileIntentUserInputHandler()

    private lateinit var underTest: UiModeNightTileUserActionInteractor

    @Mock private lateinit var uiModeManager: UiModeManager

    @Before
    fun setup() {
        uiModeManager = mock<UiModeManager>()
        underTest =
            UiModeNightTileUserActionInteractor(
                EmptyCoroutineContext,
                uiModeManager,
                qsTileIntentUserActionHandler
            )
    }

    @Test
    fun handleClickToEnable() = runTest {
        val stateBeforeClick = false

        underTest.handleInput(QSTileInputTestKtx.click(createModel(stateBeforeClick)))

        verify(uiModeManager).setNightModeActivated(!stateBeforeClick)
    }

    @Test
    fun handleClickToDisable() = runTest {
        val stateBeforeClick = true

        underTest.handleInput(QSTileInputTestKtx.click(createModel(stateBeforeClick)))

        verify(uiModeManager).setNightModeActivated(!stateBeforeClick)
    }

    @Test
    fun clickToEnableDoesNothingWhenInPowerSaveInNightMode() = runTest {
        val isNightMode = true
        val isPowerSave = true

        underTest.handleInput(QSTileInputTestKtx.click(createModel(isNightMode, isPowerSave)))

        verify(uiModeManager, never()).setNightModeActivated(any())
    }

    @Test
    fun clickToEnableDoesNothingWhenInPowerSaveNotInNightMode() = runTest {
        val isNightMode = false
        val isPowerSave = true

        underTest.handleInput(QSTileInputTestKtx.click(createModel(isNightMode, isPowerSave)))

        verify(uiModeManager, never()).setNightModeActivated(any())
    }

    @Test
    fun handleLongClickNightModeEnabled() = runTest {
        val isNightMode = true

        underTest.handleInput(QSTileInputTestKtx.longClick(createModel(isNightMode)))

        Truth.assertThat(qsTileIntentUserActionHandler.handledInputs).hasSize(1)
        val intentInput = qsTileIntentUserActionHandler.intentInputs.last()
        val actualIntentAction = intentInput.intent.action
        val expectedIntentAction = Settings.ACTION_DARK_THEME_SETTINGS
        Truth.assertThat(actualIntentAction).isEqualTo(expectedIntentAction)
    }

    @Test
    fun handleLongClickNightModeDisabled() = runTest {
        val isNightMode = false

        underTest.handleInput(QSTileInputTestKtx.longClick(createModel(isNightMode)))

        Truth.assertThat(qsTileIntentUserActionHandler.handledInputs).hasSize(1)
        val intentInput = qsTileIntentUserActionHandler.intentInputs.last()
        val actualIntentAction = intentInput.intent.action
        val expectedIntentAction = Settings.ACTION_DARK_THEME_SETTINGS
        Truth.assertThat(actualIntentAction).isEqualTo(expectedIntentAction)
    }
}
