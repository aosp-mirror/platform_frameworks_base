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

import android.app.role.RoleManager
import android.os.UserHandle
import android.view.KeyEvent
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.CommandQueue
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import java.util.concurrent.Executor
import javax.inject.Inject

/** Class responsible to "glue" all note task dependencies. */
internal class NoteTaskInitializer
@Inject
constructor(
    private val controller: NoteTaskController,
    private val roleManager: RoleManager,
    private val commandQueue: CommandQueue,
    private val optionalBubbles: Optional<Bubbles>,
    @Background private val backgroundExecutor: Executor,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
    private val userTracker: UserTracker,
) {

    @VisibleForTesting
    val callbacks =
        object : CommandQueue.Callbacks {
            override fun handleSystemKey(key: KeyEvent) {
                if (key.keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL) {
                    controller.showNoteTask(NoteTaskEntryPoint.TAIL_BUTTON)
                } else if (
                    key.keyCode == KeyEvent.KEYCODE_N && key.isMetaPressed && key.isCtrlPressed
                ) {
                    controller.showNoteTask(NoteTaskEntryPoint.KEYBOARD_SHORTCUT)
                }
            }
        }

    fun initialize() {
        // Guard against feature not being enabled or mandatory dependencies aren't available.
        if (!isEnabled || optionalBubbles.isEmpty) return

        controller.setNoteTaskShortcutEnabled(true, userTracker.userHandle)
        commandQueue.addCallback(callbacks)
        roleManager.addOnRoleHoldersChangedListenerAsUser(
            backgroundExecutor,
            controller::onRoleHoldersChanged,
            UserHandle.ALL,
        )
    }
}
