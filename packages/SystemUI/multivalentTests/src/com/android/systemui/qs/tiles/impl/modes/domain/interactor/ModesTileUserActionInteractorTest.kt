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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.qs.tiles.impl.modes.domain.interactor

import android.graphics.drawable.TestStubDrawable
import android.platform.test.annotations.EnableFlags
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.asIcon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.actions.qsTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.modes.domain.model.ModesTileModel
import com.android.systemui.statusbar.policy.data.repository.zenModeRepository
import com.android.systemui.statusbar.policy.domain.interactor.zenModeInteractor
import com.android.systemui.statusbar.policy.ui.dialog.mockModesDialogDelegate
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(android.app.Flags.FLAG_MODES_UI)
class ModesTileUserActionInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val inputHandler = kosmos.qsTileIntentUserInputHandler
    private val mockDialogDelegate = kosmos.mockModesDialogDelegate
    private val zenModeRepository = kosmos.zenModeRepository
    private val zenModeInteractor = kosmos.zenModeInteractor

    private val underTest =
        ModesTileUserActionInteractor(inputHandler, mockDialogDelegate, zenModeInteractor)

    @Test
    fun handleClick_active_showsDialog() = runTest {
        val expandable = mock<Expandable>()
        underTest.handleInput(
            QSTileInputTestKtx.click(data = modelOf(true, listOf("DND")), expandable = expandable)
        )

        verify(mockDialogDelegate).showDialog(eq(expandable))
    }

    @Test
    fun handleClick_inactive_showsDialog() = runTest {
        val expandable = mock<Expandable>()
        underTest.handleInput(
            QSTileInputTestKtx.click(data = modelOf(false, emptyList()), expandable = expandable)
        )

        verify(mockDialogDelegate).showDialog(eq(expandable))
    }

    @Test
    @EnableFlags(Flags.FLAG_QS_UI_REFACTOR_COMPOSE_FRAGMENT)
    fun handleToggleClick_multipleModesActive_deactivatesAll() =
        testScope.runTest {
            val activeModes by collectLastValue(zenModeInteractor.activeModes)

            zenModeRepository.addModes(
                listOf(
                    TestModeBuilder.MANUAL_DND_ACTIVE,
                    TestModeBuilder().setName("Mode 1").setActive(true).build(),
                    TestModeBuilder().setName("Mode 2").setActive(true).build(),
                )
            )
            assertThat(activeModes?.modeNames?.count()).isEqualTo(3)

            underTest.handleInput(
                QSTileInputTestKtx.toggleClick(
                    data = modelOf(true, listOf("DND", "Mode 1", "Mode 2"))
                )
            )

            assertThat(activeModes?.isAnyActive()).isFalse()
        }

    @Test
    @EnableFlags(Flags.FLAG_QS_UI_REFACTOR_COMPOSE_FRAGMENT)
    fun handleToggleClick_dndActive_deactivatesDnd() =
        testScope.runTest {
            val dndMode by collectLastValue(zenModeInteractor.dndMode)

            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_ACTIVE)
            assertThat(dndMode?.isActive).isTrue()

            underTest.handleInput(
                QSTileInputTestKtx.toggleClick(data = modelOf(true, listOf("DND")))
            )

            assertThat(dndMode?.isActive).isFalse()
        }

    @Test
    @EnableFlags(Flags.FLAG_QS_UI_REFACTOR_COMPOSE_FRAGMENT)
    fun handleToggleClick_dndInactive_activatesDnd() =
        testScope.runTest {
            val dndMode by collectLastValue(zenModeInteractor.dndMode)

            zenModeRepository.addMode(TestModeBuilder.MANUAL_DND_INACTIVE)
            assertThat(dndMode?.isActive).isFalse()

            underTest.handleInput(
                QSTileInputTestKtx.toggleClick(data = modelOf(false, emptyList()))
            )

            assertThat(dndMode?.isActive).isTrue()
        }

    @Test
    fun handleLongClick_active_opensSettings() = runTest {
        underTest.handleInput(QSTileInputTestKtx.longClick(modelOf(true, listOf("DND"))))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_ZEN_MODE_SETTINGS)
        }
    }

    @Test
    fun handleLongClick_inactive_opensSettings() = runTest {
        underTest.handleInput(QSTileInputTestKtx.longClick(modelOf(false, emptyList())))

        QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
            assertThat(it.intent.action).isEqualTo(Settings.ACTION_ZEN_MODE_SETTINGS)
        }
    }

    private fun modelOf(isActivated: Boolean, activeModeNames: List<String>): ModesTileModel {
        return ModesTileModel(isActivated, activeModeNames, TestStubDrawable("icon").asIcon(), 123)
    }
}
