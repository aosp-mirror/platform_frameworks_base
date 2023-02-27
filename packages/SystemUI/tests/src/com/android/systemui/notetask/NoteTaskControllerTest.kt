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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.test.suitebuilder.annotation.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.notetask.NoteTaskController.Companion.INTENT_EXTRA_USE_STYLUS_MODE
import com.android.systemui.notetask.shortcut.CreateNoteTaskShortcutActivity
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.wm.shell.bubbles.Bubble
import com.android.wm.shell.bubbles.Bubbles
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

/** atest SystemUITests:NoteTaskControllerTest */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskControllerTest : SysuiTestCase() {

    @Mock lateinit var context: Context
    @Mock lateinit var packageManager: PackageManager
    @Mock lateinit var resolver: NoteTaskInfoResolver
    @Mock lateinit var bubbles: Bubbles
    @Mock lateinit var keyguardManager: KeyguardManager
    @Mock lateinit var userManager: UserManager
    @Mock lateinit var eventLogger: NoteTaskEventLogger

    private val noteTaskInfo = NoteTaskInfo(packageName = NOTES_PACKAGE_NAME, uid = NOTES_UID)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(context.packageManager).thenReturn(packageManager)
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(noteTaskInfo)
        whenever(userManager.isUserUnlocked).thenReturn(true)
    }

    private fun createNoteTaskController(
        isEnabled: Boolean = true,
        bubbles: Bubbles? = this.bubbles,
        keyguardManager: KeyguardManager? = this.keyguardManager,
        userManager: UserManager? = this.userManager,
    ): NoteTaskController =
        NoteTaskController(
            context = context,
            resolver = resolver,
            eventLogger = eventLogger,
            optionalBubbles = Optional.ofNullable(bubbles),
            optionalUserManager = Optional.ofNullable(userManager),
            optionalKeyguardManager = Optional.ofNullable(keyguardManager),
            isEnabled = isEnabled,
        )

    // region onBubbleExpandChanged
    @Test
    fun onBubbleExpandChanged_expanding_logNoteTaskOpened() {
        val expectedInfo = noteTaskInfo.copy(isKeyguardLocked = false, isInMultiWindowMode = false)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = true,
                key = Bubble.KEY_APP_BUBBLE,
            )

        verify(eventLogger).logNoteTaskOpened(expectedInfo)
        verifyZeroInteractions(context, bubbles, keyguardManager, userManager)
    }

    @Test
    fun onBubbleExpandChanged_collapsing_logNoteTaskClosed() {
        val expectedInfo = noteTaskInfo.copy(isKeyguardLocked = false, isInMultiWindowMode = false)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = false,
                key = Bubble.KEY_APP_BUBBLE,
            )

        verify(eventLogger).logNoteTaskClosed(expectedInfo)
        verifyZeroInteractions(context, bubbles, keyguardManager, userManager)
    }

    @Test
    fun onBubbleExpandChanged_expandingAndKeyguardLocked_doNothing() {
        val expectedInfo = noteTaskInfo.copy(isKeyguardLocked = true, isInMultiWindowMode = false)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = true,
                key = Bubble.KEY_APP_BUBBLE,
            )

        verifyZeroInteractions(context, bubbles, keyguardManager, userManager, eventLogger)
    }

    @Test
    fun onBubbleExpandChanged_notExpandingAndKeyguardLocked_doNothing() {
        val expectedInfo = noteTaskInfo.copy(isKeyguardLocked = true, isInMultiWindowMode = false)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = false,
                key = Bubble.KEY_APP_BUBBLE,
            )

        verifyZeroInteractions(context, bubbles, keyguardManager, userManager, eventLogger)
    }

    @Test
    fun onBubbleExpandChanged_expandingAndInMultiWindowMode_doNothing() {
        val expectedInfo = noteTaskInfo.copy(isKeyguardLocked = false, isInMultiWindowMode = true)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = true,
                key = Bubble.KEY_APP_BUBBLE,
            )

        verifyZeroInteractions(context, bubbles, keyguardManager, userManager)
    }

    @Test
    fun onBubbleExpandChanged_notExpandingAndInMultiWindowMode_doNothing() {
        val expectedInfo = noteTaskInfo.copy(isKeyguardLocked = false, isInMultiWindowMode = true)

        createNoteTaskController()
            .apply { infoReference.set(expectedInfo) }
            .onBubbleExpandChanged(
                isExpanding = false,
                key = Bubble.KEY_APP_BUBBLE,
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
                key = Bubble.KEY_APP_BUBBLE,
            )

        verifyZeroInteractions(context, bubbles, keyguardManager, userManager, eventLogger)
    }
    // endregion

    // region showNoteTask
    @Test
    fun showNoteTask_keyguardIsLocked_shouldStartActivityAndLogUiEvent() {
        val expectedInfo =
            noteTaskInfo.copy(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isInMultiWindowMode = false,
                isKeyguardLocked = true,
            )
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = expectedInfo.entryPoint!!,
                isInMultiWindowMode = expectedInfo.isInMultiWindowMode,
            )

        val intentCaptor = argumentCaptor<Intent>()
        verify(context).startActivity(capture(intentCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(NoteTaskController.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTES_PACKAGE_NAME)
            assertThat(intent.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.getBooleanExtra(INTENT_EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        verify(eventLogger).logNoteTaskOpened(expectedInfo)
        verifyZeroInteractions(bubbles)
    }

    @Test
    fun showNoteTask_keyguardIsUnlocked_shouldStartBubblesWithoutLoggingUiEvent() {
        val expectedInfo =
            noteTaskInfo.copy(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isInMultiWindowMode = false,
                isKeyguardLocked = false,
            )
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = expectedInfo.entryPoint!!,
                isInMultiWindowMode = expectedInfo.isInMultiWindowMode,
            )

        verifyZeroInteractions(context)
        val intentCaptor = argumentCaptor<Intent>()
        verify(bubbles).showOrHideAppBubble(capture(intentCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(NoteTaskController.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTES_PACKAGE_NAME)
            assertThat(intent.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.getBooleanExtra(INTENT_EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        verifyZeroInteractions(eventLogger)
    }

    @Test
    fun showNoteTask_isInMultiWindowMode_shouldStartActivityAndLogUiEvent() {
        val expectedInfo =
            noteTaskInfo.copy(
                entryPoint = NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT,
                isInMultiWindowMode = true,
                isKeyguardLocked = false,
            )
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(expectedInfo)
        whenever(keyguardManager.isKeyguardLocked).thenReturn(expectedInfo.isKeyguardLocked)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = expectedInfo.entryPoint!!,
                isInMultiWindowMode = expectedInfo.isInMultiWindowMode,
            )

        val intentCaptor = argumentCaptor<Intent>()
        verify(context).startActivity(capture(intentCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(NoteTaskController.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTES_PACKAGE_NAME)
            assertThat(intent.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.getBooleanExtra(INTENT_EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        verify(eventLogger).logNoteTaskOpened(expectedInfo)
        verifyZeroInteractions(bubbles)
    }

    @Test
    fun showNoteTask_bubblesIsNull_shouldDoNothing() {
        createNoteTaskController(bubbles = null)
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isInMultiWindowMode = false,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_keyguardManagerIsNull_shouldDoNothing() {
        createNoteTaskController(keyguardManager = null)
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isInMultiWindowMode = false,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_userManagerIsNull_shouldDoNothing() {
        createNoteTaskController(userManager = null)
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isInMultiWindowMode = false,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_intentResolverReturnsNull_shouldDoNothing() {
        whenever(resolver.resolveInfo(any(), any(), any())).thenReturn(null)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isInMultiWindowMode = false,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_flagDisabled_shouldDoNothing() {
        createNoteTaskController(isEnabled = false)
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isInMultiWindowMode = false,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }

    @Test
    fun showNoteTask_userIsLocked_shouldDoNothing() {
        whenever(userManager.isUserUnlocked).thenReturn(false)

        createNoteTaskController()
            .showNoteTask(
                entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                isInMultiWindowMode = false,
            )

        verifyZeroInteractions(context, bubbles, eventLogger)
    }
    // endregion

    // region setNoteTaskShortcutEnabled
    @Test
    fun setNoteTaskShortcutEnabled_setTrue() {
        createNoteTaskController().setNoteTaskShortcutEnabled(value = true)

        val argument = argumentCaptor<ComponentName>()
        verify(context.packageManager)
            .setComponentEnabledSetting(
                argument.capture(),
                eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        val expected = ComponentName(context, CreateNoteTaskShortcutActivity::class.java)
        assertThat(argument.value.flattenToString()).isEqualTo(expected.flattenToString())
    }

    @Test
    fun setNoteTaskShortcutEnabled_setFalse() {
        createNoteTaskController().setNoteTaskShortcutEnabled(value = false)

        val argument = argumentCaptor<ComponentName>()
        verify(context.packageManager)
            .setComponentEnabledSetting(
                argument.capture(),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                eq(PackageManager.DONT_KILL_APP),
            )
        val expected = ComponentName(context, CreateNoteTaskShortcutActivity::class.java)
        assertThat(argument.value.flattenToString()).isEqualTo(expected.flattenToString())
    }
    // endregion

    private companion object {
        const val NOTES_PACKAGE_NAME = "com.android.note.app"
        const val NOTES_UID = 123456
    }
}
