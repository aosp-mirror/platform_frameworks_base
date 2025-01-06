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

import com.android.systemui.animation.Expandable
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tiles.base.actions.QSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.notes.domain.model.NotesTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileUserAction
import javax.inject.Inject

class NotesTileUserActionInteractor
@Inject constructor(
    private val qsTileIntentUserInputHandler: QSTileIntentUserInputHandler,
    private val panelInteractor: PanelInteractor,
    private val noteTaskController: NoteTaskController,
) : QSTileUserActionInteractor<NotesTileModel> {
    val longClickIntent = NoteTaskController.createNotesRoleHolderSettingsIntent()

    override suspend fun handleInput(input: QSTileInput<NotesTileModel>) {
        when (input.action) {
            is QSTileUserAction.Click -> handleClick()
            is QSTileUserAction.LongClick -> handleLongClick(input.action.expandable)
            is QSTileUserAction.ToggleClick -> {}
        }
    }

    fun handleClick() {
        noteTaskController.showNoteTask(NoteTaskEntryPoint.QS_NOTES_TILE)
        panelInteractor.collapsePanels()
    }

    fun handleLongClick(expandable: Expandable?) {
        qsTileIntentUserInputHandler.handle(expandable, longClickIntent)
    }
}
