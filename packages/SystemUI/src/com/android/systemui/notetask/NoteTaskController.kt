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

@file:OptIn(InternalNoteTaskApi::class)

package com.android.systemui.notetask

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.app.role.RoleManager.ROLE_NOTES
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.devicepolicy.areKeyguardShortcutsDisabled
import com.android.systemui.log.DebugLogger.debugLog
import com.android.systemui.notetask.NoteTaskRoleManagerExt.createNoteShortcutInfoAsUser
import com.android.systemui.notetask.NoteTaskRoleManagerExt.getDefaultRoleHolderAsUser
import com.android.systemui.notetask.shortcut.LaunchNoteTaskManagedProfileProxyActivity
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.system.ActivityManagerKt.isInForeground
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.settings.SecureSettings
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
    private val roleManager: RoleManager,
    private val shortcutManager: ShortcutManager,
    private val resolver: NoteTaskInfoResolver,
    private val eventLogger: NoteTaskEventLogger,
    private val optionalBubbles: Optional<Bubbles>,
    private val userManager: UserManager,
    private val keyguardManager: KeyguardManager,
    private val activityManager: ActivityManager,
    @NoteTaskEnabledKey private val isEnabled: Boolean,
    private val devicePolicyManager: DevicePolicyManager,
    private val userTracker: UserTracker,
    private val secureSettings: SecureSettings,
) {

    @VisibleForTesting val infoReference = AtomicReference<NoteTaskInfo?>()

    /** @see BubbleExpandListener */
    fun onBubbleExpandChanged(isExpanding: Boolean, key: String?) {
        if (!isEnabled) return

        val info = infoReference.getAndSet(null) ?: return

        if (key != Bubble.getAppBubbleKeyForApp(info.packageName, info.user)) return

        // Safe guard mechanism, this callback should only be called for app bubbles.
        if (info.launchMode != NoteTaskLaunchMode.AppBubble) return

        if (isExpanding) {
            debugLog { "onBubbleExpandChanged - expanding: $info" }
            eventLogger.logNoteTaskOpened(info)
        } else {
            debugLog { "onBubbleExpandChanged - collapsing: $info" }
            eventLogger.logNoteTaskClosed(info)
        }
    }

    /** Starts [LaunchNoteTaskProxyActivity] on the given [user]. */
    fun startNoteTaskProxyActivityForUser(user: UserHandle) {
        context.startActivityAsUser(
            Intent().apply {
                component =
                    ComponentName(context, LaunchNoteTaskManagedProfileProxyActivity::class.java)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            user
        )
    }

    /** Starts the notes role setting. */
    fun startNotesRoleSetting(activityContext: Context, entryPoint: NoteTaskEntryPoint?) {
        val user =
            if (entryPoint == null) {
                userTracker.userHandle
            } else {
                getUserForHandlingNotesTaking(entryPoint)
            }
        activityContext.startActivityAsUser(
            Intent(Intent.ACTION_MANAGE_DEFAULT_APP).apply {
                putExtra(Intent.EXTRA_ROLE_NAME, ROLE_NOTES)
            },
            user
        )
    }

    /**
     * Returns the [UserHandle] of an android user that should handle the notes taking [entryPoint].
     *
     * On company owned personally enabled (COPE) devices, if the given [entryPoint] is in the
     * [FORCE_WORK_NOTE_APPS_ENTRY_POINTS_ON_COPE_DEVICES] list, the default notes app in the work
     * profile user will always be launched.
     *
     * On non managed devices or devices with other management modes, the current [UserHandle] is
     * returned.
     */
    fun getUserForHandlingNotesTaking(entryPoint: NoteTaskEntryPoint): UserHandle =
        if (
            entryPoint in FORCE_WORK_NOTE_APPS_ENTRY_POINTS_ON_COPE_DEVICES &&
                devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile
        ) {
            userTracker.userProfiles.firstOrNull { userManager.isManagedProfile(it.id) }?.userHandle
                ?: userTracker.userHandle
        } else {
            secureSettings.preferredUser
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
        if (!isEnabled) return

        showNoteTaskAsUser(entryPoint, getUserForHandlingNotesTaking(entryPoint))
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
            debugLog { "Enterprise policy disallows launching note app when the screen is locked." }
            return
        }

        val info = resolver.resolveInfo(entryPoint, isKeyguardLocked, user)

        if (info == null) {
            debugLog { "Default notes app isn't set" }
            showNoDefaultNotesAppToast()
            return
        }

        infoReference.set(info)

        try {
            // TODO(b/266686199): We should handle when app not available. For now, we log.
            debugLog { "onShowNoteTask - start: $info on user#${user.identifier}" }
            when (info.launchMode) {
                is NoteTaskLaunchMode.AppBubble -> {
                    val intent = createNoteTaskIntent(info)
                    val icon =
                        Icon.createWithResource(context, R.drawable.ic_note_task_shortcut_widget)
                    bubbles.showOrHideAppBubble(intent, user, icon)
                    // App bubble logging happens on `onBubbleExpandChanged`.
                    debugLog { "onShowNoteTask - opened as app bubble: $info" }
                }
                is NoteTaskLaunchMode.Activity -> {
                    if (activityManager.isInForeground(info.packageName)) {
                        // Force note task into background by calling home.
                        val intent = createHomeIntent()
                        context.startActivityAsUser(intent, user)
                        eventLogger.logNoteTaskClosed(info)
                        debugLog { "onShowNoteTask - closed as activity: $info" }
                    } else {
                        val intent = createNoteTaskIntent(info)
                        context.startActivityAsUser(intent, user)
                        eventLogger.logNoteTaskOpened(info)
                        debugLog { "onShowNoteTask - opened as activity: $info" }
                    }
                }
            }
            debugLog { "onShowNoteTask - success: $info" }
        } catch (e: ActivityNotFoundException) {
            debugLog { "onShowNoteTask - failed: $info" }
        }
        debugLog { "onShowNoteTask - completed: $info" }
    }

    @VisibleForTesting
    fun showNoDefaultNotesAppToast() {
        Toast.makeText(context, R.string.set_default_notes_app_toast_content, Toast.LENGTH_SHORT)
            .show()
    }

    /**
     * Set `android:enabled` property in the `AndroidManifest` associated with the Shortcut
     * component to [value].
     *
     * If the shortcut entry `android:enabled` is set to `true`, the shortcut will be visible in the
     * Widget Picker to all users.
     */
    fun setNoteTaskShortcutEnabled(value: Boolean, user: UserHandle) {
        val enabledState =
            if (value) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }

        // If the required user matches the tracking user, the injected context is already a context
        // of the required user. Avoid calling #createContextAsUser because creating a context for
        // a user takes time.
        val userContext =
            if (user == userTracker.userHandle) {
                context
            } else {
                context.createContextAsUser(user, /* flags= */ 0)
            }

        userContext.packageManager.setComponentEnabledSetting(
            SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT,
            enabledState,
            PackageManager.DONT_KILL_APP,
        )

        debugLog { "setNoteTaskShortcutEnabled - completed: $isEnabled" }
    }

    /**
     * Updates all [NoteTaskController] related information, including but not exclusively the
     * widget shortcut created by the [user] - by default it will use the current user.
     *
     * Keep in mind the shortcut API has a
     * [rate limiting](https://developer.android.com/develop/ui/views/launch/shortcuts/managing-shortcuts#rate-limiting)
     * and may not be updated in real-time. To reduce the chance of stale shortcuts, we run the
     * function during System UI initialization.
     */
    fun updateNoteTaskAsUser(user: UserHandle) {
        val packageName = roleManager.getDefaultRoleHolderAsUser(ROLE_NOTES, user)
        val hasNotesRoleHolder = isEnabled && !packageName.isNullOrEmpty()

        setNoteTaskShortcutEnabled(hasNotesRoleHolder, user)

        if (hasNotesRoleHolder) {
            shortcutManager.enableShortcuts(listOf(SHORTCUT_ID))
            val updatedShortcut = roleManager.createNoteShortcutInfoAsUser(context, user)
            shortcutManager.updateShortcuts(listOf(updatedShortcut))
        } else {
            shortcutManager.disableShortcuts(listOf(SHORTCUT_ID))
        }
    }

    /** @see OnRoleHoldersChangedListener */
    fun onRoleHoldersChanged(roleName: String, user: UserHandle) {
        if (roleName != ROLE_NOTES) return

        if (user == userTracker.userHandle) {
            updateNoteTaskAsUser(user)
        } else {
            // TODO(b/278729185): Replace fire and forget service with a bounded service.
            val intent = NoteTaskControllerUpdateService.createIntent(context)
            context.startServiceAsUser(intent, user)
        }
    }

    private val SecureSettings.preferredUser: UserHandle
        get() {
            val userId =
                secureSettings.getInt(
                    Settings.Secure.DEFAULT_NOTE_TASK_PROFILE,
                    userTracker.userHandle.identifier,
                )
            return UserHandle.of(userId)
        }

    companion object {
        val TAG = NoteTaskController::class.simpleName.orEmpty()

        /**
         * IMPORTANT! The shortcut package name and class should be synchronized with Settings:
         * [com.android.settings.notetask.shortcut.CreateNoteTaskShortcutActivity].
         *
         * Changing the package name or class is a breaking change.
         */
        @VisibleForTesting
        val SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT =
            ComponentName(
                "com.android.settings",
                "com.android.settings.notetask.shortcut.CreateNoteTaskShortcutActivity",
            )

        const val SHORTCUT_ID = "note_task_shortcut_id"

        /**
         * Shortcut extra which can point to a package name and can be used to indicate an alternate
         * badge info. Launcher only reads this if the shortcut comes from a system app.
         *
         * Duplicated from [com.android.launcher3.icons.IconCache].
         *
         * @see com.android.launcher3.icons.IconCache.EXTRA_SHORTCUT_BADGE_OVERRIDE_PACKAGE
         */
        const val EXTRA_SHORTCUT_BADGE_OVERRIDE_PACKAGE = "extra_shortcut_badge_override_package"

        /**
         * A list of entry points which should be redirected to the work profile default notes app
         * on company owned personally enabled (COPE) devices.
         *
         * Entry points in this list don't let users / admin to select the work or personal default
         * notes app to be launched.
         */
        val FORCE_WORK_NOTE_APPS_ENTRY_POINTS_ON_COPE_DEVICES =
            listOf(NoteTaskEntryPoint.TAIL_BUTTON, NoteTaskEntryPoint.QUICK_AFFORDANCE)
    }
}

/** Creates an [Intent] for [ROLE_NOTES]. */
private fun createNoteTaskIntent(info: NoteTaskInfo): Intent =
    Intent(Intent.ACTION_CREATE_NOTE).apply {
        setPackage(info.packageName)

        // EXTRA_USE_STYLUS_MODE does not mean a stylus is in-use, but a stylus entrypoint
        // was used to start the note task.
        val useStylusMode = info.entryPoint != NoteTaskEntryPoint.KEYBOARD_SHORTCUT
        putExtra(Intent.EXTRA_USE_STYLUS_MODE, useStylusMode)

        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // We should ensure the note experience can be opened both as a full screen (lockscreen)
        // and inside the app bubble (contextual). These additional flags will do that.
        if (info.launchMode == NoteTaskLaunchMode.Activity) {
            addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        }
    }

/** Creates an [Intent] which forces the current app to background by calling home. */
private fun createHomeIntent(): Intent =
    Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
