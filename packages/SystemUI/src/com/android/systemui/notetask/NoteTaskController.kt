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
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
import android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.devicepolicy.areKeyguardShortcutsDisabled
import com.android.systemui.notetask.shortcut.CreateNoteTaskShortcutActivity
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.kotlin.getOrNull
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.Bubbles
import com.android.wm.shell.bubbles.Bubbles.BubbleExpandListener
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Entry point for creating and managing note.
 *
 * The controller decides how a note is launched based in the device state: locked or unlocked.
 *
 * Currently, we only support a single task per time.
 */
@SysUISingleton
class NoteTaskController
@Inject
constructor(
    private val context: Context,
    private val resolver: NoteTaskInfoResolver,
    private val eventLogger: NoteTaskEventLogger,
    private val optionalBubbles: Optional<Bubbles>,
    private val userManager: UserManager,
    private val keyguardManager: KeyguardManager,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
    private val devicePolicyManager: DevicePolicyManager,
    private val userTracker: UserTracker,
) {

    @VisibleForTesting val infoReference = AtomicReference<NoteTaskInfo?>()

    /** @see BubbleExpandListener */
    fun onBubbleExpandChanged(isExpanding: Boolean, key: String?) {
        if (!isEnabled) return

        if (key != Bubble.KEY_APP_BUBBLE) return

        val info = infoReference.getAndSet(null)

        // Safe guard mechanism, this callback should only be called for app bubbles.
        if (info?.launchMode != NoteTaskLaunchMode.AppBubble) return

        if (isExpanding) {
            logDebug { "onBubbleExpandChanged - expanding: $info" }
            eventLogger.logNoteTaskOpened(info)
        } else {
            logDebug { "onBubbleExpandChanged - collapsing: $info" }
            eventLogger.logNoteTaskClosed(info)
        }
    }

    /**
     * Shows a note task. How the task is shown will depend on when the method is invoked.
     *
     * If the keyguard is locked, notes will open as a full screen experience. A locked device has
     * no contextual information which let us use the whole screen space available.
     *
     * If the keyguard is unlocked, notes will open as a bubble OR it will be collapsed if the notes
     * bubble is already opened.
     *
     * That will let users open other apps in full screen, and take contextual notes.
     */
    fun showNoteTask(
        entryPoint: NoteTaskEntryPoint,
    ) {
        showNoteTaskAsUser(entryPoint, userTracker.userHandle)
    }

    /** A variant of [showNoteTask] which launches note task in the given [user]. */
    fun showNoteTaskAsUser(
        entryPoint: NoteTaskEntryPoint,
        user: UserHandle,
    ) {
        if (!isEnabled) return

        val bubbles = optionalBubbles.getOrNull() ?: return

        // TODO(b/249954038): We should handle direct boot (isUserUnlocked). For now, we do nothing.
        if (!userManager.isUserUnlocked) return

        val isKeyguardLocked = keyguardManager.isKeyguardLocked
        // KeyguardQuickAffordanceInteractor blocks the quick affordance from showing in the
        // keyguard if it is not allowed by the admin policy. Here we block any other way to show
        // note task when the screen is locked.
        if (
            isKeyguardLocked &&
                devicePolicyManager.areKeyguardShortcutsDisabled(userId = user.identifier)
        ) {
            logDebug { "Enterprise policy disallows launching note app when the screen is locked." }
            return
        }

        val info = resolver.resolveInfo(entryPoint, isKeyguardLocked) ?: return

        infoReference.set(info)

        // TODO(b/266686199): We should handle when app not available. For now, we log.
        val intent = createNoteIntent(info)
        try {
            logDebug { "onShowNoteTask - start: $info on user#${user.identifier}" }
            when (info.launchMode) {
                is NoteTaskLaunchMode.AppBubble -> {
                    bubbles.showOrHideAppBubble(intent, userTracker.userHandle)
                    // App bubble logging happens on `onBubbleExpandChanged`.
                    logDebug { "onShowNoteTask - opened as app bubble: $info" }
                }
                is NoteTaskLaunchMode.Activity -> {
                    context.startActivityAsUser(intent, user)
                    eventLogger.logNoteTaskOpened(info)
                    logDebug { "onShowNoteTask - opened as activity: $info" }
                }
            }
            logDebug { "onShowNoteTask - success: $info" }
        } catch (e: ActivityNotFoundException) {
            logDebug { "onShowNoteTask - failed: $info" }
        }
        logDebug { "onShowNoteTask - compoleted: $info" }
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

        logDebug { "setNoteTaskShortcutEnabled - completed: $isEnabled" }
    }

    companion object {
        val TAG = NoteTaskController::class.simpleName.orEmpty()
    }
}

private fun createNoteIntent(info: NoteTaskInfo): Intent =
    Intent(Intent.ACTION_CREATE_NOTE).apply {
        setPackage(info.packageName)

        // EXTRA_USE_STYLUS_MODE does not mean a stylus is in-use, but a stylus entrypoint
        // was used to start it.
        putExtra(Intent.EXTRA_USE_STYLUS_MODE, true)

        addFlags(FLAG_ACTIVITY_NEW_TASK)
        // We should ensure the note experience can be open both as a full screen (lock screen)
        // and inside the app bubble (contextual). These additional flags will do that.
        if (info.launchMode == NoteTaskLaunchMode.Activity) {
            addFlags(FLAG_ACTIVITY_MULTIPLE_TASK)
            addFlags(FLAG_ACTIVITY_NEW_DOCUMENT)
        }
    }

private inline fun logDebug(message: () -> String) {
    if (Build.IS_DEBUGGABLE) {
        Log.d(NoteTaskController.TAG, message())
    }
}
