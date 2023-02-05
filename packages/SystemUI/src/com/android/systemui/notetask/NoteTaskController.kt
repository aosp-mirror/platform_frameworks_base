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
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.util.Log
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
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
    private val resolver: NoteTaskInfoResolver,
    private val optionalBubbles: Optional<Bubbles>,
    private val optionalKeyguardManager: Optional<KeyguardManager>,
    private val optionalUserManager: Optional<UserManager>,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
    private val uiEventLogger: UiEventLogger,
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
     * If not in multi-window or the keyguard is unlocked, notes will open as a bubble OR it will be
     * collapsed if the notes bubble is already opened.
     *
     * That will let users open other apps in full screen, and take contextual notes.
     */
    @JvmOverloads
    fun showNoteTask(isInMultiWindowMode: Boolean = false, uiEvent: ShowNoteTaskUiEvent? = null) {

        if (!isEnabled) return

        val bubbles = optionalBubbles.getOrNull() ?: return
        val keyguardManager = optionalKeyguardManager.getOrNull() ?: return
        val userManager = optionalUserManager.getOrNull() ?: return

        // TODO(b/249954038): We should handle direct boot (isUserUnlocked). For now, we do nothing.
        if (!userManager.isUserUnlocked) return

        val noteTaskInfo = resolver.resolveInfo() ?: return

        uiEvent?.let { uiEventLogger.log(it, noteTaskInfo.uid, noteTaskInfo.packageName) }

        // TODO(b/266686199): We should handle when app not available. For now, we log.
        val intent = noteTaskInfo.toCreateNoteIntent()
        try {
            if (isInMultiWindowMode || keyguardManager.isKeyguardLocked) {
                context.startActivity(intent)
            } else {
                bubbles.showOrHideAppBubble(intent)
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Activity not found for action: $ACTION_CREATE_NOTE.", e)
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

    /** IDs of UI events accepted by [showNoteTask]. */
    enum class ShowNoteTaskUiEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "User opened a note by tapping on the lockscreen shortcut.")
        NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE(1294),

        /* ktlint-disable max-line-length */
        @UiEvent(
            doc =
                "User opened a note by pressing the stylus tail button while the screen was unlocked."
        )
        NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON(1295),
        @UiEvent(
            doc =
                "User opened a note by pressing the stylus tail button while the screen was locked."
        )
        NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON_LOCKED(1296),
        @UiEvent(doc = "User opened a note by tapping on an app shortcut.")
        NOTE_OPENED_VIA_SHORTCUT(1297);

        override fun getId() = _id
    }

    companion object {
        private val TAG = NoteTaskController::class.simpleName.orEmpty()

        private fun NoteTaskInfoResolver.NoteTaskInfo.toCreateNoteIntent(): Intent {
            return Intent(ACTION_CREATE_NOTE)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // EXTRA_USE_STYLUS_MODE does not mean a stylus is in-use, but a stylus entrypoint
                // was used to start it.
                .putExtra(INTENT_EXTRA_USE_STYLUS_MODE, true)
        }

        // TODO(b/254604589): Use final KeyEvent.KEYCODE_* instead.
        const val NOTE_TASK_KEY_EVENT = 311

        // TODO(b/265912743): Use Intent.ACTION_CREATE_NOTE instead.
        const val ACTION_CREATE_NOTE = "android.intent.action.CREATE_NOTE"

        // TODO(b/265912743): Use Intent.INTENT_EXTRA_USE_STYLUS_MODE instead.
        const val INTENT_EXTRA_USE_STYLUS_MODE = "android.intent.extra.USE_STYLUS_MODE"
    }
}
