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

package com.android.systemui.qs.tiles.impl.saver.domain.interactor

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.testing.LeakCheck
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.intentInputs
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.saver.domain.model.DataSaverTileModel
import com.android.systemui.settings.UserFileManager
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.utils.leaks.FakeDataSaverController
import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class DataSaverTileUserActionInteractorTest : SysuiTestCase() {
    private val qsTileIntentUserActionHandler = FakeQSTileIntentUserInputHandler()
    private val dataSaverController = FakeDataSaverController(LeakCheck())

    private lateinit var userFileManager: UserFileManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var dialogFactory: SystemUIDialog.Factory
    private lateinit var underTest: DataSaverTileUserActionInteractor

    @Before
    fun setup() {
        userFileManager = mock<UserFileManager>()
        sharedPreferences = mock<SharedPreferences>()
        dialogFactory = mock<SystemUIDialog.Factory>()
        whenever(
                userFileManager.getSharedPreferences(
                    eq(DataSaverTileUserActionInteractor.PREFS),
                    eq(Context.MODE_PRIVATE),
                    eq(context.userId)
                )
            )
            .thenReturn(sharedPreferences)

        underTest =
            DataSaverTileUserActionInteractor(
                context,
                EmptyCoroutineContext,
                EmptyCoroutineContext,
                dataSaverController,
                qsTileIntentUserActionHandler,
                mock<DialogTransitionAnimator>(),
                dialogFactory,
                userFileManager,
            )
    }

    /** Since the dialog was shown before, we expect the click to enable the controller. */
    @Test
    fun handleClickToEnableDialogShownBefore() = runTest {
        whenever(
                sharedPreferences.getBoolean(
                    eq(DataSaverTileUserActionInteractor.DIALOG_SHOWN),
                    any()
                )
            )
            .thenReturn(true)
        val stateBeforeClick = false

        underTest.handleInput(QSTileInputTestKtx.click(DataSaverTileModel(stateBeforeClick)))

        assertThat(dataSaverController.isDataSaverEnabled).isEqualTo(!stateBeforeClick)
    }

    /**
     * The first time the tile is clicked to turn on we expect (1) the enabled state to not change
     * and (2) the dialog to be shown instead.
     */
    @Test
    fun handleClickToEnableDialogNotShownBefore() = runTest {
        whenever(
                sharedPreferences.getBoolean(
                    eq(DataSaverTileUserActionInteractor.DIALOG_SHOWN),
                    any()
                )
            )
            .thenReturn(false)
        val mockDialog = mock<SystemUIDialog>()
        whenever(dialogFactory.create(any(), any())).thenReturn(mockDialog)
        val stateBeforeClick = false

        val input = QSTileInputTestKtx.click(DataSaverTileModel(stateBeforeClick))
        underTest.handleInput(input)

        assertThat(dataSaverController.isDataSaverEnabled).isEqualTo(stateBeforeClick)
        verify(mockDialog).show()
    }

    /** Disabling should flip the state, even if the dialog was not shown before. */
    @Test
    fun handleClickToDisableDialogNotShownBefore() = runTest {
        whenever(
                sharedPreferences.getBoolean(
                    eq(DataSaverTileUserActionInteractor.DIALOG_SHOWN),
                    any()
                )
            )
            .thenReturn(false)
        val enabledBeforeClick = true

        underTest.handleInput(QSTileInputTestKtx.click(DataSaverTileModel(enabledBeforeClick)))

        assertThat(dataSaverController.isDataSaverEnabled).isEqualTo(!enabledBeforeClick)
    }

    @Test
    fun handleClickToDisableDialogShownBefore() = runTest {
        whenever(
                sharedPreferences.getBoolean(
                    eq(DataSaverTileUserActionInteractor.DIALOG_SHOWN),
                    any()
                )
            )
            .thenReturn(true)
        val enabledBeforeClick = true

        underTest.handleInput(QSTileInputTestKtx.click(DataSaverTileModel(enabledBeforeClick)))

        assertThat(dataSaverController.isDataSaverEnabled).isEqualTo(!enabledBeforeClick)
    }

    @Test
    fun handleLongClickWhenEnabled() = runTest {
        val enabledState = true

        underTest.handleInput(QSTileInputTestKtx.longClick(DataSaverTileModel(enabledState)))

        assertThat(qsTileIntentUserActionHandler.handledInputs).hasSize(1)
        val intentInput = qsTileIntentUserActionHandler.intentInputs.last()
        val actualIntentAction = intentInput.intent.action
        val expectedIntentAction = Settings.ACTION_DATA_SAVER_SETTINGS
        assertThat(actualIntentAction).isEqualTo(expectedIntentAction)
    }

    @Test
    fun handleLongClickWhenDisabled() = runTest {
        val enabledState = false

        underTest.handleInput(QSTileInputTestKtx.longClick(DataSaverTileModel(enabledState)))

        assertThat(qsTileIntentUserActionHandler.handledInputs).hasSize(1)
        val intentInput = qsTileIntentUserActionHandler.intentInputs.last()
        val actualIntentAction = intentInput.intent.action
        val expectedIntentAction = Settings.ACTION_DATA_SAVER_SETTINGS
        assertThat(actualIntentAction).isEqualTo(expectedIntentAction)
    }
}
