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

package com.android.systemui.qs.tiles.impl.modes.domain.interactor

import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.ui.dialog.ModesDialogDelegate
import com.google.common.truth.Truth
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(android.app.Flags.FLAG_MODES_UI)
class ModesTileUserActionInteractorTest : SysuiTestCase() {
    private val inputHandler = FakeQSTileIntentUserInputHandler()

    @Mock private lateinit var dialogTransitionAnimator: DialogTransitionAnimator
    @Mock private lateinit var dialogDelegate: ModesDialogDelegate
    @Mock private lateinit var mockDialog: SystemUIDialog

    private lateinit var underTest: ModesTileUserActionInteractor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        whenever(dialogDelegate.createDialog()).thenReturn(mockDialog)

        underTest =
            ModesTileUserActionInteractor(
                EmptyCoroutineContext,
                inputHandler,
                dialogTransitionAnimator,
                dialogDelegate,
            )
    }

    @Test
    fun handleClick() = runTest {
        underTest.handleInput(QSTileInputTestKtx.click(ModesTileModel(false)))

        verify(mockDialog).show()
    }

    @Test
    fun handleLongClick() = runTest {
        underTest.handleInput(QSTileInputTestKtx.longClick(ModesTileModel(false)))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            Truth.assertThat(it.intent.action).isEqualTo(Settings.ACTION_ZEN_MODE_SETTINGS)
        }
    }
}
