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
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.notetask.NoteTaskController.Companion.INTENT_EXTRA_USE_STYLUS_MODE
import com.android.systemui.notetask.NoteTaskController.ShowNoteTaskUiEvent
import com.android.systemui.notetask.NoteTaskInfoResolver.NoteTaskInfo
import com.android.systemui.notetask.shortcut.CreateNoteTaskShortcutActivity
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
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

/**
 * Tests for [NoteTaskController].
 *
 * Build/Install/Run:
 * - atest SystemUITests:NoteTaskControllerTest
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskControllerTest : SysuiTestCase() {

    @Mock lateinit var context: Context
    @Mock lateinit var packageManager: PackageManager
    @Mock lateinit var resolver: NoteTaskInfoResolver
    @Mock lateinit var bubbles: Bubbles
    @Mock lateinit var optionalBubbles: Optional<Bubbles>
    @Mock lateinit var keyguardManager: KeyguardManager
    @Mock lateinit var optionalKeyguardManager: Optional<KeyguardManager>
    @Mock lateinit var optionalUserManager: Optional<UserManager>
    @Mock lateinit var userManager: UserManager
    @Mock lateinit var uiEventLogger: UiEventLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(context.packageManager).thenReturn(packageManager)
        whenever(resolver.resolveInfo()).thenReturn(NoteTaskInfo(NOTES_PACKAGE_NAME, NOTES_UID))
        whenever(optionalBubbles.orElse(null)).thenReturn(bubbles)
        whenever(optionalKeyguardManager.orElse(null)).thenReturn(keyguardManager)
        whenever(optionalUserManager.orElse(null)).thenReturn(userManager)
        whenever(userManager.isUserUnlocked).thenReturn(true)
    }

    private fun createNoteTaskController(isEnabled: Boolean = true): NoteTaskController {
        return NoteTaskController(
            context = context,
            resolver = resolver,
            optionalBubbles = optionalBubbles,
            optionalKeyguardManager = optionalKeyguardManager,
            optionalUserManager = optionalUserManager,
            isEnabled = isEnabled,
            uiEventLogger = uiEventLogger,
        )
    }

    // region showNoteTask
    @Test
    fun showNoteTask_keyguardIsLocked_shouldStartActivityAndLogUiEvent() {
        whenever(keyguardManager.isKeyguardLocked).thenReturn(true)

        createNoteTaskController()
            .showNoteTask(
                isInMultiWindowMode = false,
                uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE,
            )

        val intentCaptor = argumentCaptor<Intent>()
        verify(context).startActivity(capture(intentCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(NoteTaskController.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTES_PACKAGE_NAME)
            assertThat(intent.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.getBooleanExtra(INTENT_EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        verifyZeroInteractions(bubbles)
        verify(uiEventLogger)
            .log(
                ShowNoteTaskUiEvent.NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE,
                NOTES_UID,
                NOTES_PACKAGE_NAME
            )
    }

    @Test
    fun showNoteTask_keyguardIsUnlocked_shouldStartBubblesAndLogUiEvent() {
        whenever(keyguardManager.isKeyguardLocked).thenReturn(false)

        createNoteTaskController()
            .showNoteTask(
                isInMultiWindowMode = false,
                uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON,
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
        verify(uiEventLogger)
            .log(
                ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON,
                NOTES_UID,
                NOTES_PACKAGE_NAME
            )
    }

    @Test
    fun showNoteTask_keyguardIsUnlocked_uiEventIsNull_shouldStartBubblesWithoutLoggingUiEvent() {
        whenever(keyguardManager.isKeyguardLocked).thenReturn(false)

        createNoteTaskController().showNoteTask(isInMultiWindowMode = false, uiEvent = null)

        verifyZeroInteractions(context)
        val intentCaptor = argumentCaptor<Intent>()
        verify(bubbles).showOrHideAppBubble(capture(intentCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(NoteTaskController.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTES_PACKAGE_NAME)
            assertThat(intent.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.getBooleanExtra(INTENT_EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        verifyZeroInteractions(uiEventLogger)
    }

    @Test
    fun showNoteTask_isInMultiWindowMode_shouldStartActivityAndLogUiEvent() {
        whenever(keyguardManager.isKeyguardLocked).thenReturn(false)

        createNoteTaskController()
            .showNoteTask(
                isInMultiWindowMode = true,
                uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_SHORTCUT,
            )

        val intentCaptor = argumentCaptor<Intent>()
        verify(context).startActivity(capture(intentCaptor))
        intentCaptor.value.let { intent ->
            assertThat(intent.action).isEqualTo(NoteTaskController.ACTION_CREATE_NOTE)
            assertThat(intent.`package`).isEqualTo(NOTES_PACKAGE_NAME)
            assertThat(intent.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.getBooleanExtra(INTENT_EXTRA_USE_STYLUS_MODE, false)).isTrue()
        }
        verifyZeroInteractions(bubbles)
        verify(uiEventLogger)
            .log(ShowNoteTaskUiEvent.NOTE_OPENED_VIA_SHORTCUT, NOTES_UID, NOTES_PACKAGE_NAME)
    }

    @Test
    fun showNoteTask_bubblesIsNull_shouldDoNothing() {
        whenever(optionalBubbles.orElse(null)).thenReturn(null)

        createNoteTaskController()
            .showNoteTask(
                isInMultiWindowMode = false,
                uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON
            )

        verifyZeroInteractions(context, bubbles, uiEventLogger)
    }

    @Test
    fun showNoteTask_keyguardManagerIsNull_shouldDoNothing() {
        whenever(optionalKeyguardManager.orElse(null)).thenReturn(null)

        createNoteTaskController()
            .showNoteTask(
                isInMultiWindowMode = false,
                uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON,
            )

        verifyZeroInteractions(context, bubbles, uiEventLogger)
    }

    @Test
    fun showNoteTask_userManagerIsNull_shouldDoNothing() {
        whenever(optionalUserManager.orElse(null)).thenReturn(null)

        createNoteTaskController()
            .showNoteTask(
                isInMultiWindowMode = false,
                uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON,
            )

        verifyZeroInteractions(context, bubbles, uiEventLogger)
    }

    @Test
    fun showNoteTask_intentResolverReturnsNull_shouldDoNothing() {
        whenever(resolver.resolveInfo()).thenReturn(null)

        createNoteTaskController()
            .showNoteTask(
                isInMultiWindowMode = false,
                uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON,
            )

        verifyZeroInteractions(context, bubbles, uiEventLogger)
    }

    @Test
    fun showNoteTask_flagDisabled_shouldDoNothing() {
        createNoteTaskController(isEnabled = false)
            .showNoteTask(uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON)

        verifyZeroInteractions(context, bubbles, uiEventLogger)
    }

    @Test
    fun showNoteTask_userIsLocked_shouldDoNothing() {
        whenever(userManager.isUserUnlocked).thenReturn(false)

        createNoteTaskController()
            .showNoteTask(
                isInMultiWindowMode = false,
                uiEvent = ShowNoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON,
            )

        verifyZeroInteractions(context, bubbles, uiEventLogger)
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
