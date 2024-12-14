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

import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_CLOSED_VIA_STYLUS_TAIL_BUTTON
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_CLOSED_VIA_STYLUS_TAIL_BUTTON_LOCKED
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_OPENED_VIA_SHORTCUT
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON_LOCKED
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

/** atest SystemUITests:MonitoringNoteTaskEventListenerTest */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskEventLoggerTest : SysuiTestCase() {

    @Mock lateinit var uiEventLogger: UiEventLogger

    private fun createNoteTaskEventLogger(): NoteTaskEventLogger =
        NoteTaskEventLogger(uiEventLogger)

    private fun createNoteTaskInfo(): NoteTaskInfo =
        NoteTaskInfo(packageName = NOTES_PACKAGE_NAME, uid = NOTES_UID, UserHandle.of(0))

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    // region logNoteTaskOpened
    @Test
    fun logNoteTaskOpened_entryPointWidgetPickerShortcut_noteOpenedViaShortcut() {
        val info = createNoteTaskInfo().copy(entryPoint = NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT)

        createNoteTaskEventLogger().logNoteTaskOpened(info)

        val expected = NOTE_OPENED_VIA_SHORTCUT
        verify(uiEventLogger).log(expected, info.uid, info.packageName)
    }

    @Test
    fun onNoteTaskBubbleExpanded_entryPointQuickAffordance_noteOpenedViaQuickAffordance() {
        val info = createNoteTaskInfo().copy(entryPoint = NoteTaskEntryPoint.QUICK_AFFORDANCE)

        createNoteTaskEventLogger().logNoteTaskOpened(info)

        val expected = NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE
        verify(uiEventLogger).log(expected, info.uid, info.packageName)
    }

    @Test
    fun onNoteTaskBubbleExpanded_entryPointTailButtonAndIsKeyguardUnlocked_noteOpenedViaTailButtonUnlocked() { // ktlint-disable max-line-length
        val info =
            createNoteTaskInfo()
                .copy(
                    entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                    isKeyguardLocked = false,
                )

        createNoteTaskEventLogger().logNoteTaskOpened(info)

        val expected = NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON
        verify(uiEventLogger).log(expected, info.uid, info.packageName)
    }

    @Test
    fun onNoteTaskBubbleExpanded_entryPointTailButtonAndIsKeyguardLocked_noteOpenedViaTailButtonLocked() { // ktlint-disable max-line-length
        val info =
            createNoteTaskInfo()
                .copy(
                    entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                    isKeyguardLocked = true,
                )

        createNoteTaskEventLogger().logNoteTaskOpened(info)

        val expected = NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON_LOCKED
        verify(uiEventLogger).log(expected, info.uid, info.packageName)
    }

    @Test
    fun onNoteTaskBubbleExpanded_noEntryPoint_noLog() {
        val info = createNoteTaskInfo().copy(entryPoint = null)

        createNoteTaskEventLogger().logNoteTaskOpened(info)

        verifyNoMoreInteractions(uiEventLogger)
    }
    // endregion

    // region logNoteTaskClosed
    @Test
    fun logNoteTaskClosed_entryPointTailButton_noteClosedViaTailButtonUnlocked() {
        val info =
            createNoteTaskInfo()
                .copy(
                    entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                    isKeyguardLocked = false,
                )

        createNoteTaskEventLogger().logNoteTaskClosed(info)

        val expected = NOTE_CLOSED_VIA_STYLUS_TAIL_BUTTON
        verify(uiEventLogger).log(expected, info.uid, info.packageName)
    }

    @Test
    fun logNoteTaskClosed_entryPointTailButtonAndKeyguardLocked_noteClosedViaTailButtonLocked() {
        val info =
            createNoteTaskInfo()
                .copy(
                    entryPoint = NoteTaskEntryPoint.TAIL_BUTTON,
                    isKeyguardLocked = true,
                )

        createNoteTaskEventLogger().logNoteTaskClosed(info)

        val expected = NOTE_CLOSED_VIA_STYLUS_TAIL_BUTTON_LOCKED
        verify(uiEventLogger).log(expected, info.uid, info.packageName)
    }

    @Test
    fun logNoteTaskClosed_noEntryPoint_noLog() {
        val info = createNoteTaskInfo().copy(entryPoint = null)

        createNoteTaskEventLogger().logNoteTaskOpened(info)

        verifyNoMoreInteractions(uiEventLogger)
    }
    // endregion

    private companion object {
        const val NOTES_PACKAGE_NAME = "com.android.note.app"
        const val NOTES_UID = 123456
    }
}
