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

package com.android.systemui.notetask

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.UserHandle
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Fake for [NoteTaskBubblesController] as mocking suspending functions is not supported in the
 * Android tree's version of mockito. Ideally the [NoteTaskBubblesController] should be implemented
 * using an interface for effectively providing multiple implementations but as this fake primarily
 * for dealing with old version of mockito there isn't any benefit in adding complexity.
 */
class FakeNoteTaskBubbleController(
    unUsed1: Context,
    unsUsed2: CoroutineDispatcher,
    private val optionalBubbles: Optional<Bubbles>
) : NoteTaskBubblesController(unUsed1, unsUsed2) {
    override suspend fun areBubblesAvailable() = optionalBubbles.isPresent

    override suspend fun showOrHideAppBubble(intent: Intent, userHandle: UserHandle, icon: Icon) {
        optionalBubbles.ifPresentOrElse(
            { bubbles -> bubbles.showOrHideAppBubble(intent, userHandle, icon) },
            { throw IllegalAccessException() }
        )
    }
}
