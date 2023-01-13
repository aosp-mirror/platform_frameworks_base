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
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.notetask.shortcut.CreateNoteTaskShortcutActivity
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

    /**
     * Shows a note task. How the task is shown will depend on when the method is invoked.
     *
     * If in multi-window mode, notes will open as a full screen experience. That is particularly
     * important for Large screen devices. These devices may support a taskbar that let users to
     * drag and drop a shortcut into multi-window mode, and notes should comply with this behaviour.
     *
     * If the keyguard is locked, notes will open as a full screen experience. A locked device has
     * no contextual information which let us use the whole screen space available.
     *
     * If no in multi-window or the keyguard is unlocked, notes will open as a bubble OR it will be
     * collapsed if the notes bubble is already opened.
     *
     * That will let users open other apps in full screen, and take contextual notes.
     */
    fun showNoteTask(isInMultiWindowMode: Boolean = false) {
        if (!isEnabled) return

        val bubbles = optionalBubbles.getOrNull() ?: return
        val keyguardManager = optionalKeyguardManager.getOrNull() ?: return
        val userManager = optionalUserManager.getOrNull() ?: return
        val intent = intentResolver.resolveIntent() ?: return

        // TODO(b/249954038): We should handle direct boot (isUserUnlocked). For now, we do nothing.
        if (!userManager.isUserUnlocked) return

        if (isInMultiWindowMode || keyguardManager.isKeyguardLocked) {
            context.startActivity(intent)
        } else {
            // TODO(b/254606432): Should include Intent.EXTRA_FLOATING_WINDOW_MODE parameter.
            bubbles.showOrHideAppBubble(intent)
        }
    }

    /**
     * Set `android:enabled` property in the `AndroidManifest` associated with the Shortcut
     * component to [value].
     *
     * If the shortcut entry `android:enabled` is set to `true`, the shortcut will be visible in the
     * Widget Picker to all users.
     */
    fun setNoteTaskShortcutEnabled(value: Boolean) {
        val componentName = ComponentName(context, CreateNoteTaskShortcutActivity::class.java)

        val enabledState =
            if (value) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

        context.packageManager.setComponentEnabledSetting(
            componentName,
            enabledState,
            PackageManager.DONT_KILL_APP,
        )
    }

    companion object {
        // TODO(b/254604589): Use final KeyEvent.KEYCODE_* instead.
        const val NOTE_TASK_KEY_EVENT = 311
    }
}
