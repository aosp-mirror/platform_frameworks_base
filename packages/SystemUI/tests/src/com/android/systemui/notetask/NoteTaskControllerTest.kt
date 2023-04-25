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
import android.app.role.RoleManager
import android.app.role.RoleManager.ROLE_NOTES
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_MAIN
import android.content.Intent.CATEGORY_HOME
import android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK
import android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.pm.UserInfo
import android.graphics.drawable.Icon
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.test.ext.truth.content.IntentSubject.assertThat
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.notetask.NoteTaskController.Companion.EXTRA_SHORTCUT_BADGE_OVERRIDE_PACKAGE
import com.android.systemui.notetask.NoteTaskController.Companion.SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT
import com.android.systemui.notetask.NoteTaskController.Companion.SHORTCUT_ID
import com.android.systemui.notetask.NoteTaskEntryPoint.APP_CLIPS
import com.android.systemui.notetask.NoteTaskEntryPoint.QUICK_AFFORDANCE
import com.android.systemui.notetask.NoteTaskEntryPoint.TAIL_BUTTON
import com.android.systemui.notetask.shortcut.LaunchNoteTaskActivity
import com.android.systemui.notetask.shortcut.LaunchNoteTaskManagedProfileProxyActivity
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.SecureSettings
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.Bubbles
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

/** atest SystemUITests:NoteTaskControllerTest */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskControllerTest : SysuiTestCase() {

    @Mock private lateinit var context: Context
    @Mock private lateinit var workProfileContext: Context
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var workProfilePackageManager: PackageManager
    @Mock private lateinit var resolver: NoteTaskInfoResolver
    @Mock private lateinit var bubbles: Bubbles
    @Mock private lateinit var keyguardManager: KeyguardManager
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var eventLogger: NoteTaskEventLogger
    @Mock private lateinit var roleManager: RoleManager
    @Mock private lateinit var shortcutManager: ShortcutManager
    @Mock private lateinit var activityManager: ActivityManager
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var secureSettings: SecureSettings
    private val userTracker = FakeUserTracker()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(context.getString(R.string.note_task_button_label))
            .thenReturn(NOTE_TASK_SHORT_LABEL)
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(NOTE_TASK_INFO)
        whenever(userManager.isUserUnlocked).thenReturn(true)
        whenever(
                devicePolicyManager.getKeyguardDisabledFeatures(
                    /* admin= */ eq(null),
                    /* userHandle= */ anyInt()
                )
            )
            .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
        whenever(roleManager.getRoleHoldersAsUser(ROLE_NOTES, userTracker.userHandle))
            .thenReturn(listOf(NOTE_TASK_PACKAGE_NAME))
        whenever(activityManager.getRunningTasks(anyInt())).thenReturn(emptyList())
        whenever(userManager.isManagedProfile(workUserInfo.id)).thenReturn(true)
        whenever(context.resources).thenReturn(getContext().resources)
        whenever(secureSettings.userTracker).thenReturn(userTracker)
    }

    private fun createNoteTaskController(
        isEnabled: Boolean = true,
        bubbles: Bubbles? = this.bubbles,
    ): NoteTaskController =
        NoteTaskController(
            context = context,
            resolver = resolver,
            eventLogger = eventLogger,
            optionalBubbles = Optional.ofNullable(bubbles),
            userManager = userManager,
            keyguardManager = keyguardManager,
            isEnabled = isEnabled,
            devicePolicyManager = devicePolicyManager,
            userTracker = userTracker,
            roleManager = roleManager,
            shortcutManager = shortcutManager,
            activityManager = activityManager,
            secureSettings = secureSettings,
        )

    // region onBubbleExpandChanged
    @Test
    fun onBubbleExpandChanged_expanding_logNoteTaskOpened() {
        val expectedInfo = NOTE_TASK_INFO.copy(isKeyguardLocked = false)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = true,
                key = Bubble.getAppBubbleKeyForApp(expectedInfo.packageName, expectedInfo.user),
            )

        verify(eventLogger).logNoteTaskOpened(expectedInfo)
        verifyZeroInteractions(context, bubbles, keyguardManager, userManager)
    }

    @Test
    fun onBubbleExpandChanged_collapsing_logNoteTaskClosed() {
        val expectedInfo = NOTE_TASK_INFO.copy(isKeyguardLocked = false)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = false,
                key = Bubble.getAppBubbleKeyForApp(expectedInfo.packageName, expectedInfo.user),
            )

        verify(eventLogger).logNoteTaskClosed(expectedInfo)
        verifyZeroInteractions(context, bubbles, keyguardManager, userManager)
    }

    @Test
    fun onBubbleExpandChanged_expandingAndKeyguardLocked_shouldDoNothing() {
        val expectedInfo = NOTE_TASK_INFO.copy(isKeyguardLocked = true)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = true,
                key = Bubble.getAppBubbleKeyForApp(expectedInfo.packageName, expectedInfo.user),
            )

        verifyZeroInteractions(context, bubbles, keyguardManager, userManager, eventLogger)
    }

    @Test
    fun onBubbleExpandChanged_notExpandingAndKeyguardLocked_shouldDoNothing() {
        val expectedInfo = NOTE_TASK_INFO.copy(isKeyguardLocked = true)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = false,
                key = Bubble.getAppBubbleKeyForApp(expectedInfo.packageName, expectedInfo.user),
            )

        verifyZeroInteractions(context, bubbles, keyguardManager, userManager, eventLogger)
    }

    @Test
    fun onBubbleExpandChanged_notKeyAppBubble_shouldDoNothing() {
        createNoteTaskController()
            .onBubbleExpandChanged(
                isExpanding = true,
                key = "any other key",
            )

        verifyZeroInteractions(context, bubbles, keyguardManager, userManager, eventLogger)
    }

    @Test
    fun onBubbleExpandChanged_flagDisabled_shouldDoNothing() {
        createNoteTaskController(isEnabled = false)
            .onBubbleExpandChanged(
                isExpanding = true,
                key = Bubble.getAppBubbleKeyForApp(NOTE_TASK_INFO.packageName, NOTE_TASK_INFO.user),
            )

        verifyZeroInteractions(context, bubbles, keyguardManager, userManager, eventLogger)
    }
    // endregion

    // region showNoteTask
    @Test
    fun showNoteTask_keyguardIsLocked_shouldStartActivityAndLogUiEvent() {
        val expectedInfo =
            NOTE_TASK_INFO.copy(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isKeyguardLocked = true,
            )
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = expectedInfo.entryPoint!!,
            )

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTE_TASK_PACKAGE_NAME)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_TASK).isEqualTo(FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.flags and FLAG_ACTIVITY_MULTIPLE_TASK)
                .isEqualTo(FLAG_ACTIVITY_MULTIPLE_TASK)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_DOCUMENT)
                .isEqualTo(FLAG_ACTIVITY_NEW_DOCUMENT)
            assertThat(intent.getBooleanExtra(Intent.EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        assertThat(userCaptor.value).isEqualTo(userTracker.userHandle)
        verify(eventLogger).logNoteTaskOpened(expectedInfo)
        verifyZeroInteractions(bubbles)
    }

    @Test
    fun showNoteTask_defaultUserSet_shouldStartActivityWithExpectedUserAndLogUiEvent() {
        whenever(secureSettings.getInt(eq(Settings.Secure.DEFAULT_NOTE_TASK_PROFILE), any()))
            .thenReturn(10)
        val user10 = UserHandle.of(/* userId= */ 10)

        val expectedInfo =
            NOTE_TASK_INFO.copy(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isKeyguardLocked = true,
                user = user10,
            )
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = expectedInfo.entryPoint!!,
            )

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTE_TASK_PACKAGE_NAME)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_TASK).isEqualTo(FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.flags and FLAG_ACTIVITY_MULTIPLE_TASK)
                .isEqualTo(FLAG_ACTIVITY_MULTIPLE_TASK)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_DOCUMENT)
                .isEqualTo(FLAG_ACTIVITY_NEW_DOCUMENT)
            assertThat(intent.getBooleanExtra(Intent.EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        assertThat(userCaptor.value).isEqualTo(user10)
        verify(eventLogger).logNoteTaskOpened(expectedInfo)
        verifyZeroInteractions(bubbles)
    }

    @Test
    fun showNoteTaskWithUser_keyguardIsLocked_shouldStartActivityWithExpectedUserAndLogUiEvent() {
        val user10 = UserHandle.of(/* userId= */ 10)
        val expectedInfo =
            NOTE_TASK_INFO.copy(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isKeyguardLocked = true,
                user = user10,
            )
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)

        createNoteTaskController()
            .showNoteTaskAsUser(
                entryPoint = expectedInfo.entryPoint!!,
                user = user10,
            )

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTE_TASK_PACKAGE_NAME)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_TASK).isEqualTo(FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.flags and FLAG_ACTIVITY_MULTIPLE_TASK)
                .isEqualTo(FLAG_ACTIVITY_MULTIPLE_TASK)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_DOCUMENT)
                .isEqualTo(FLAG_ACTIVITY_NEW_DOCUMENT)
            assertThat(intent.getBooleanExtra(Intent.EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        assertThat(userCaptor.value).isEqualTo(user10)
        verify(eventLogger).logNoteTaskOpened(expectedInfo)
        verifyZeroInteractions(bubbles)
    }

    @Test
    fun showNoteTask_keyguardIsLocked_noteIsOpen_shouldCloseActivityAndLogUiEvent() {
        val expectedInfo =
            NOTE_TASK_INFO.copy(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isKeyguardLocked = true,
            )
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)
        whenever(activityManager.getRunningTasks(anyInt()))
            .thenReturn(listOf(NOTE_RUNNING_TASK_INFO))

        createNoteTaskController().showNoteTask(entryPoint = expectedInfo.entryPoint!!)

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(ACTION_MAIN)
            assertThat(intent.categories).contains(CATEGORY_HOME)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_TASK).isEqualTo(FLAG_ACTIVITY_NEW_TASK)
        }
        assertThat(userCaptor.value).isEqualTo(userTracker.userHandle)
        verify(eventLogger).logNoteTaskClosed(expectedInfo)
        verifyZeroInteractions(bubbles)
    }

    @Test
    fun showNoteTask_keyguardIsUnlocked_shouldStartBubblesWithoutLoggingUiEvent() {
        val expectedInfo =
            NOTE_TASK_INFO.copy(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isKeyguardLocked = false,
            )
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = expectedInfo.entryPoint!!,
            )

        // Context package name used to create bubble icon from drawable resource id
        verify(context).packageName
        verifyNoteTaskOpenInBubbleInUser(userTracker.userHandle)
        verifyZeroInteractions(eventLogger)
    }

    @Test
    fun showNoteTask_bubblesIsNull_shouldDoNothing() {
        createNoteTaskController(bubbles = null)
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_intentResolverReturnsNull_shouldShowToast() {
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(null)
        val noteTaskController = spy(createNoteTaskController())
        doNothing().whenever(noteTaskController).showNoDefaultNotesAppToast()

        noteTaskController.showNoteTask(entryPoint = NoteTaskEntryPoint.TAIL_BUTTON)

        verify(noteTaskController).showNoDefaultNotesAppToast()
        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_flagDisabled_shouldDoNothing() {
        createNoteTaskController(isEnabled = false)
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_userIsLocked_shouldDoNothing() {
        whenever(userManager.isUserUnlocked).thenReturn(false)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_keyboardShortcut_shouldStartActivity() {
        val expectedInfo =
            NOTE_TASK_INFO.copy(
                entryPoint = NoteTaskEntryPoint.KEYBOARD_SHORTCUT,
                isKeyguardLocked = true,
            )
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = expectedInfo.entryPoint!!,
            )

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTE_TASK_PACKAGE_NAME)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_TASK).isEqualTo(FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.flags and FLAG_ACTIVITY_MULTIPLE_TASK)
                .isEqualTo(FLAG_ACTIVITY_MULTIPLE_TASK)
            assertThat(intent.flags and FLAG_ACTIVITY_NEW_DOCUMENT)
                .isEqualTo(FLAG_ACTIVITY_NEW_DOCUMENT)
            assertThat(intent.getBooleanExtra(Intent.EXTRA_USE_STYLUS_MODE, true)).isFalse()
        }
        assertThat(userCaptor.value).isEqualTo(userTracker.userHandle)
        verify(eventLogger).logNoteTaskOpened(expectedInfo)
        verifyZeroInteractions(bubbles)
    }
    // endregion

    // region setNoteTaskShortcutEnabled
    @Test
    fun setNoteTaskShortcutEnabled_setTrue() {
        createNoteTaskController().setNoteTaskShortcutEnabled(value = true, userTracker.userHandle)

        val argument = argumentCaptor<ComponentName>()
        verify(context.packageManager)
            .setComponentEnabledSetting(
                argument.capture(),
                eq(COMPONENT_ENABLED_STATE_ENABLED),
                eq(PackageManager.DONT_KILL_APP),
            )

        assertThat(argument.value).isEqualTo(SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT)
    }

    @Test
    fun setNoteTaskShortcutEnabled_setFalse() {
        createNoteTaskController().setNoteTaskShortcutEnabled(value = false, userTracker.userHandle)

        val argument = argumentCaptor<ComponentName>()
        verify(context.packageManager)
            .setComponentEnabledSetting(
                argument.capture(),
                eq(COMPONENT_ENABLED_STATE_DISABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        assertThat(argument.value).isEqualTo(SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT)
    }

    @Test
    fun setNoteTaskShortcutEnabled_workProfileUser_setTrue() {
        whenever(context.createContextAsUser(eq(workUserInfo.userHandle), any()))
            .thenReturn(workProfileContext)
        whenever(workProfileContext.packageManager).thenReturn(workProfilePackageManager)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().setNoteTaskShortcutEnabled(value = true, workUserInfo.userHandle)

        val argument = argumentCaptor<ComponentName>()
        verify(workProfilePackageManager)
            .setComponentEnabledSetting(
                argument.capture(),
                eq(COMPONENT_ENABLED_STATE_ENABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        assertThat(argument.value).isEqualTo(SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT)
    }

    @Test
    fun setNoteTaskShortcutEnabled_workProfileUser_setFalse() {
        whenever(context.createContextAsUser(eq(workUserInfo.userHandle), any()))
            .thenReturn(workProfileContext)
        whenever(workProfileContext.packageManager).thenReturn(workProfilePackageManager)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController()
            .setNoteTaskShortcutEnabled(value = false, workUserInfo.userHandle)

        val argument = argumentCaptor<ComponentName>()
        verify(workProfilePackageManager)
            .setComponentEnabledSetting(
                argument.capture(),
                eq(COMPONENT_ENABLED_STATE_DISABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        assertThat(argument.value).isEqualTo(SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT)
    }
    // endregion

    // region keyguard policy
    @Test
    fun showNoteTask_keyguardLocked_keyguardDisableShortcutsAll_shouldDoNothing() {
        whenever(keyguardManager.isKeyguardLocked).thenReturn(true)
        whenever(
                devicePolicyManager.getKeyguardDisabledFeatures(
                    /* admin= */ eq(null),
                    /* userHandle= */ anyInt()
                )
            )
            .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL)

        createNoteTaskController().showNoteTask(entryPoint = QUICK_AFFORDANCE)

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_keyguardLocked_keyguardDisableFeaturesAll_shouldDoNothing() {
        whenever(keyguardManager.isKeyguardLocked).thenReturn(true)
        whenever(
                devicePolicyManager.getKeyguardDisabledFeatures(
                    /* admin= */ eq(null),
                    /* userHandle= */ anyInt()
                )
            )
            .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL)

        createNoteTaskController().showNoteTask(entryPoint = QUICK_AFFORDANCE)

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_keyguardUnlocked_keyguardDisableShortcutsAll_shouldStartBubble() {
        whenever(keyguardManager.isKeyguardLocked).thenReturn(false)
        whenever(
                devicePolicyManager.getKeyguardDisabledFeatures(
                    /* admin= */ eq(null),
                    /* userHandle= */ anyInt()
                )
            )
            .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_SHORTCUTS_ALL)

        createNoteTaskController().showNoteTask(entryPoint = QUICK_AFFORDANCE)

        verifyNoteTaskOpenInBubbleInUser(userTracker.userHandle)
    }

    @Test
    fun showNoteTask_keyguardUnlocked_keyguardDisableFeaturesAll_shouldStartBubble() {
        whenever(keyguardManager.isKeyguardLocked).thenReturn(false)
        whenever(
                devicePolicyManager.getKeyguardDisabledFeatures(
                    /* admin= */ eq(null),
                    /* userHandle= */ anyInt()
                )
            )
            .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_ALL)

        createNoteTaskController().showNoteTask(entryPoint = QUICK_AFFORDANCE)

        verifyNoteTaskOpenInBubbleInUser(userTracker.userHandle)
    }
    // endregion

    // region showNoteTask, COPE devices
    @Test
    fun showNoteTask_copeDevices_quickAffordanceEntryPoint_managedProfileNotFound_shouldStartBubbleInTheMainProfile() { // ktlint-disable max-line-length
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(listOf(mainUserInfo), mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().showNoteTask(entryPoint = QUICK_AFFORDANCE)

        verifyNoteTaskOpenInBubbleInUser(mainUserInfo.userHandle)
    }

    @Test
    fun showNoteTask_copeDevices_quickAffordanceEntryPoint_shouldStartBubbleInWorkProfile() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().showNoteTask(entryPoint = QUICK_AFFORDANCE)

        verifyNoteTaskOpenInBubbleInUser(workUserInfo.userHandle)
    }

    @Test
    fun showNoteTask_copeDevices_tailButtonEntryPoint_shouldStartBubbleInWorkProfile() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().showNoteTask(entryPoint = NoteTaskEntryPoint.TAIL_BUTTON)

        verifyNoteTaskOpenInBubbleInUser(workUserInfo.userHandle)
    }

    @Test
    fun showNoteTask_copeDevices_shortcutsEntryPoint_shouldStartBubbleInTheSelectedUser() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController()
            .showNoteTask(entryPoint = NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT)

        verifyNoteTaskOpenInBubbleInUser(mainUserInfo.userHandle)
    }

    @Test
    fun showNoteTask_copeDevices_appClipsEntryPoint_shouldStartBubbleInTheSelectedUser() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().showNoteTask(entryPoint = NoteTaskEntryPoint.APP_CLIPS)

        verifyNoteTaskOpenInBubbleInUser(mainUserInfo.userHandle)
    }
    // endregion

    private fun verifyNoteTaskOpenInBubbleInUser(userHandle: UserHandle) {
        val intentCaptor = argumentCaptor<Intent>()
        val iconCaptor = argumentCaptor<Icon>()
        verify(bubbles)
            .showOrHideAppBubble(capture(intentCaptor), eq(userHandle), capture(iconCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(Intent.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTE_TASK_PACKAGE_NAME)
            assertThat(intent.flags).isEqualTo(FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.getBooleanExtra(Intent.EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        iconCaptor.value.let { icon ->
            assertThat(icon).isNotNull()
            assertThat(icon.resId).isEqualTo(R.drawable.ic_note_task_shortcut_widget)
        }
    }

    // region onRoleHoldersChanged
    @Test
    fun onRoleHoldersChanged_notNotesRole_doNothing() {
        val user = UserHandle.of(0)

        createNoteTaskController(isEnabled = true).onRoleHoldersChanged("NOT_NOTES", user)

        verifyZeroInteractions(context)
    }

    @Test
    fun onRoleHoldersChanged_notesRole_sameUser_shouldUpdateShortcuts() {
        val user = userTracker.userHandle
        val controller = spy(createNoteTaskController())
        doNothing().whenever(controller).updateNoteTaskAsUser(any())

        controller.onRoleHoldersChanged(ROLE_NOTES, user)

        verify(controller).updateNoteTaskAsUser(user)
    }

    @Test
    fun onRoleHoldersChanged_notesRole_differentUser_shouldUpdateShortcutsInUserProcess() {
        // FakeUserTracker will default to UserHandle.SYSTEM.
        val user = UserHandle.CURRENT

        createNoteTaskController(isEnabled = true).onRoleHoldersChanged(ROLE_NOTES, user)

        verify(context).startServiceAsUser(any(), eq(user))
    }
    // endregion

    // region updateNoteTaskAsUser
    @Test
    fun updateNoteTaskAsUser_withNotesRole_withShortcuts_shouldUpdateShortcuts() {
        createNoteTaskController(isEnabled = true).updateNoteTaskAsUser(userTracker.userHandle)

        val actualComponent = argumentCaptor<ComponentName>()
        verify(context.packageManager)
            .setComponentEnabledSetting(
                actualComponent.capture(),
                eq(COMPONENT_ENABLED_STATE_ENABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        assertThat(actualComponent.value).isEqualTo(SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT)
        verify(shortcutManager, never()).disableShortcuts(any())
        verify(shortcutManager).enableShortcuts(listOf(SHORTCUT_ID))
        val actualShortcuts = argumentCaptor<List<ShortcutInfo>>()
        verify(shortcutManager).updateShortcuts(actualShortcuts.capture())
        val actualShortcut = actualShortcuts.value.first()
        assertThat(actualShortcut.id).isEqualTo(SHORTCUT_ID)
        assertThat(actualShortcut.intent?.component?.className)
            .isEqualTo(LaunchNoteTaskActivity::class.java.name)
        assertThat(actualShortcut.intent?.action).isEqualTo(Intent.ACTION_CREATE_NOTE)
        assertThat(actualShortcut.shortLabel).isEqualTo(NOTE_TASK_SHORT_LABEL)
        assertThat(actualShortcut.isLongLived).isEqualTo(true)
        assertThat(actualShortcut.icon.resId).isEqualTo(R.drawable.ic_note_task_shortcut_widget)
        assertThat(actualShortcut.extras?.getString(EXTRA_SHORTCUT_BADGE_OVERRIDE_PACKAGE))
            .isEqualTo(NOTE_TASK_PACKAGE_NAME)
    }

    @Test
    fun updateNoteTaskAsUser_noNotesRole_shouldDisableShortcuts() {
        whenever(roleManager.getRoleHoldersAsUser(ROLE_NOTES, userTracker.userHandle))
            .thenReturn(emptyList())

        createNoteTaskController(isEnabled = true).updateNoteTaskAsUser(userTracker.userHandle)

        val argument = argumentCaptor<ComponentName>()
        verify(context.packageManager)
            .setComponentEnabledSetting(
                argument.capture(),
                eq(COMPONENT_ENABLED_STATE_DISABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        assertThat(argument.value).isEqualTo(SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT)
        verify(shortcutManager).disableShortcuts(listOf(SHORTCUT_ID))
        verify(shortcutManager, never()).enableShortcuts(any())
        verify(shortcutManager, never()).updateShortcuts(any())
    }

    @Test
    fun updateNoteTaskAsUser_flagDisabled_shouldDisableShortcuts() {
        createNoteTaskController(isEnabled = false).updateNoteTaskAsUser(userTracker.userHandle)

        val argument = argumentCaptor<ComponentName>()
        verify(context.packageManager)
            .setComponentEnabledSetting(
                argument.capture(),
                eq(COMPONENT_ENABLED_STATE_DISABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        assertThat(argument.value).isEqualTo(SETTINGS_CREATE_NOTE_TASK_SHORTCUT_COMPONENT)
        verify(shortcutManager).disableShortcuts(listOf(SHORTCUT_ID))
        verify(shortcutManager, never()).enableShortcuts(any())
        verify(shortcutManager, never()).updateShortcuts(any())
    }
    // endregion

    // startregion startNoteTaskProxyActivityForUser
    @Test
    fun startNoteTaskProxyActivityForUser_shouldStartLaunchNoteTaskProxyActivityWithExpectedUser() {
        val user0 = UserHandle.of(0)
        createNoteTaskController().startNoteTaskProxyActivityForUser(user0)

        val intentCaptor = argumentCaptor<Intent>()
        verify(context).startActivityAsUser(intentCaptor.capture(), eq(user0))
        intentCaptor.value.let { intent ->
            assertThat(intent)
                .hasComponent(
                    ComponentName(context, LaunchNoteTaskManagedProfileProxyActivity::class.java)
                )
            assertThat(intent).hasFlags(FLAG_ACTIVITY_NEW_TASK)
        }
    }
    // endregion

    // region getUserForHandlingNotesTaking
    @Test
    fun getUserForHandlingNotesTaking_cope_quickAffordance_shouldReturnWorkProfileUser() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        val user = createNoteTaskController().getUserForHandlingNotesTaking(QUICK_AFFORDANCE)

        assertThat(user).isEqualTo(UserHandle.of(workUserInfo.id))
    }

    @Test
    fun getUserForHandlingNotesTaking_cope_tailButton_shouldReturnWorkProfileUser() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        val user = createNoteTaskController().getUserForHandlingNotesTaking(TAIL_BUTTON)

        assertThat(user).isEqualTo(UserHandle.of(workUserInfo.id))
    }

    @Test
    fun getUserForHandlingNotesTaking_cope_appClip_shouldReturnCurrentUser() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        val user = createNoteTaskController().getUserForHandlingNotesTaking(APP_CLIPS)

        assertThat(user).isEqualTo(UserHandle.of(mainUserInfo.id))
    }

    @Test
    fun getUserForHandlingNotesTaking_noManagement_quickAffordance_shouldReturnCurrentUser() {
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        val user = createNoteTaskController().getUserForHandlingNotesTaking(QUICK_AFFORDANCE)

        assertThat(user).isEqualTo(UserHandle.of(mainUserInfo.id))
    }

    @Test
    fun getUserForHandlingNotesTaking_noManagement_tailButton_shouldReturnCurrentUser() {
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        val user = createNoteTaskController().getUserForHandlingNotesTaking(TAIL_BUTTON)

        assertThat(user).isEqualTo(UserHandle.of(mainUserInfo.id))
    }

    @Test
    fun getUserForHandlingNotesTaking_noManagement_appClip_shouldReturnCurrentUser() {
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        val user = createNoteTaskController().getUserForHandlingNotesTaking(APP_CLIPS)

        assertThat(user).isEqualTo(UserHandle.of(mainUserInfo.id))
    }
    // endregion

    // startregion startNotesRoleSetting
    @Test
    fun startNotesRoleSetting_cope_quickAffordance_shouldStartNoteRoleIntentWithWorkProfileUser() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().startNotesRoleSetting(context, QUICK_AFFORDANCE)

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent).hasAction(Intent.ACTION_MANAGE_DEFAULT_APP)
        }
        assertThat(userCaptor.value).isEqualTo(UserHandle.of(workUserInfo.id))
    }

    @Test
    fun startNotesRoleSetting_cope_nullEntryPoint_shouldStartNoteRoleIntentWithCurrentUser() {
        whenever(devicePolicyManager.isOrganizationOwnedDeviceWithManagedProfile).thenReturn(true)
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().startNotesRoleSetting(context, entryPoint = null)

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent).hasAction(Intent.ACTION_MANAGE_DEFAULT_APP)
        }
        assertThat(userCaptor.value).isEqualTo(UserHandle.of(mainUserInfo.id))
    }

    @Test
    fun startNotesRoleSetting_noManagement_quickAffordance_shouldStartNoteRoleIntentWithCurrentUser() { // ktlint-disable max-line-length
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().startNotesRoleSetting(context, QUICK_AFFORDANCE)

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent).hasAction(Intent.ACTION_MANAGE_DEFAULT_APP)
        }
        assertThat(userCaptor.value).isEqualTo(UserHandle.of(mainUserInfo.id))
    }

    @Test
    fun startNotesRoleSetting_noManagement_nullEntryPoint_shouldStartNoteRoleIntentWithCurrentUser() { // ktlint-disable max-line-length
        userTracker.set(mainAndWorkProfileUsers, mainAndWorkProfileUsers.indexOf(mainUserInfo))

        createNoteTaskController().startNotesRoleSetting(context, entryPoint = null)

        val intentCaptor = argumentCaptor<Intent>()
        val userCaptor = argumentCaptor<UserHandle>()
        verify(context).startActivityAsUser(capture(intentCaptor), capture(userCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent).hasAction(Intent.ACTION_MANAGE_DEFAULT_APP)
        }
        assertThat(userCaptor.value).isEqualTo(UserHandle.of(mainUserInfo.id))
    }
    // endregion

    private companion object {
        const val NOTE_TASK_SHORT_LABEL = "Notetaking"
        const val NOTE_TASK_ACTIVITY_NAME = "NoteTaskActivity"
        const val NOTE_TASK_PACKAGE_NAME = "com.android.note.app"
        const val NOTE_TASK_UID = 123456

        private val NOTE_TASK_INFO =
            NoteTaskInfo(
                packageName = NOTE_TASK_PACKAGE_NAME,
                uid = NOTE_TASK_UID,
                user = UserHandle.of(0),
            )
        private val NOTE_RUNNING_TASK_INFO =
            ActivityManager.RunningTaskInfo().apply {
                topActivity = ComponentName(NOTE_TASK_PACKAGE_NAME, NOTE_TASK_ACTIVITY_NAME)
            }

        val mainUserInfo =
            UserInfo(/* id= */ 0, /* name= */ "primary", /* flags= */ UserInfo.FLAG_MAIN)
        val workUserInfo =
            UserInfo(/* id= */ 10, /* name= */ "work", /* flags= */ UserInfo.FLAG_PROFILE)
        val mainAndWorkProfileUsers = listOf(mainUserInfo, workUserInfo)
    }
}
