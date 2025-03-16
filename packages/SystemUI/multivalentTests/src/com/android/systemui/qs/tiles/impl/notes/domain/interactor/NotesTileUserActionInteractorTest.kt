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

package com.android.systemui.qs.tiles.impl.notes.domain.interactor

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandlerSubject
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.qs.tiles.impl.notes.domain.model.NotesTileModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.Mockito.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
class NotesTileUserActionInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val inputHandler = FakeQSTileIntentUserInputHandler()
    private val panelInteractor = mock<PanelInteractor>()
    private val noteTaskController = mock<NoteTaskController>()

    private lateinit var underTest: NotesTileUserActionInteractor

    @Before
    fun setUp() {
        underTest = NotesTileUserActionInteractor(inputHandler, panelInteractor, noteTaskController)
    }

    @Test
    fun handleClick_launchDefaultNotesApp() =
        testScope.runTest {
            underTest.handleInput(QSTileInputTestKtx.click(NotesTileModel))

            verify(noteTaskController).showNoteTask(NoteTaskEntryPoint.QS_NOTES_TILE)
        }

    @Test
    fun handleLongClick_launchSettings() =
        testScope.runTest {
            underTest.handleInput(QSTileInputTestKtx.longClick(NotesTileModel))

            QSTileIntentUserInputHandlerSubject.assertThat(inputHandler).handledOneIntentInput {
                assertThat(it.intent.action).isEqualTo(Intent.ACTION_MANAGE_DEFAULT_APP)
            }
        }
}
