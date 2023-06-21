/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.notetask

import android.app.KeyguardManager
import androidx.annotation.VisibleForTesting
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.kotlin.getOrNull
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import javax.inject.Inject

/** Class responsible to "glue" all note task dependencies. */
internal class NoteTaskInitializer
@Inject
constructor(
    private val optionalBubbles: Optional<Bubbles>,
    private val noteTaskController: NoteTaskController,
    private val commandQueue: CommandQueue,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
    private val optionalKeyguardManager: Optional<KeyguardManager>,
) {

    @VisibleForTesting
    val callbacks =
        object : CommandQueue.Callbacks {
            override fun handleSystemKey(keyCode: Int) {
                if (keyCode == NoteTaskController.NOTE_TASK_KEY_EVENT) {
                    showNoteTask()
                }
            }
        }

    private fun showNoteTask() {
        val uiEvent =
            if (optionalKeyguardManager.isKeyguardLocked) {
                NoteTaskController.ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON_LOCKED
            } else {
                NoteTaskController.ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON
            }
        noteTaskController.showNoteTask(uiEvent = uiEvent)
    }

    fun initialize() {
        if (isEnabled && optionalBubbles.isPresent) {
            commandQueue.addCallback(callbacks)
        }
        noteTaskController.setNoteTaskShortcutEnabled(isEnabled)
    }
}

private val Optional<KeyguardManager>.isKeyguardLocked: Boolean
    // If there's no KeyguardManager, assume that the keyguard is not locked.
    get() = getOrNull()?.isKeyguardLocked ?: false
