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

import com.android.systemui.statusbar.CommandQueue
import com.android.wm.shell.bubbles.Bubbles
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject

/** Class responsible to "glue" all note task dependencies. */
internal class NoteTaskInitializer
@Inject
constructor(
    private val optionalBubbles: Optional<Bubbles>,
    private val lazyNoteTaskController: Lazy<NoteTaskController>,
    private val commandQueue: CommandQueue,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
) {

    private val callbacks =
        object : CommandQueue.Callbacks {
            override fun handleSystemKey(keyCode: Int) {
                lazyNoteTaskController.get().handleSystemKey(keyCode)
            }
        }

    fun initialize() {
        if (isEnabled && optionalBubbles.isPresent) {
            commandQueue.addCallback(callbacks)
        }
    }
}
