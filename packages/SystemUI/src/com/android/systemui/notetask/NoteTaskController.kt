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
import android.content.Context
import android.os.UserManager
import android.view.KeyEvent
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.kotlin.getOrNull
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import javax.inject.Inject

/**
 * Entry point for creating and managing note.
 *
 * The controller decides how a note is launched based in the device state: locked or unlocked.
 *
 * Currently, we only support a single task per time.
 */
@SysUISingleton
internal class NoteTaskController
@Inject
constructor(
    private val context: Context,
    private val intentResolver: NoteTaskIntentResolver,
    private val optionalBubbles: Optional<Bubbles>,
    private val optionalKeyguardManager: Optional<KeyguardManager>,
    private val optionalUserManager: Optional<UserManager>,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
) {

    fun handleSystemKey(keyCode: Int) {
        if (!isEnabled) return

        if (keyCode == KeyEvent.KEYCODE_VIDEO_APP_1) {
            showNoteTask()
        }
    }

    private fun showNoteTask() {
        val bubbles = optionalBubbles.getOrNull() ?: return
        val keyguardManager = optionalKeyguardManager.getOrNull() ?: return
        val userManager = optionalUserManager.getOrNull() ?: return
        val intent = intentResolver.resolveIntent() ?: return

        // TODO(b/249954038): We should handle direct boot (isUserUnlocked). For now, we do nothing.
        if (!userManager.isUserUnlocked) return

        if (keyguardManager.isKeyguardLocked) {
            context.startActivity(intent)
        } else {
            // TODO(b/254606432): Should include Intent.EXTRA_FLOATING_WINDOW_MODE parameter.
            bubbles.showAppBubble(intent)
        }
    }
}
