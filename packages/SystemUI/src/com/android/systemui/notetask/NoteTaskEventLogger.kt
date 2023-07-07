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

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.systemui.notetask.NoteTaskEntryPoint.APP_CLIPS
import com.android.systemui.notetask.NoteTaskEntryPoint.KEYBOARD_SHORTCUT
import com.android.systemui.notetask.NoteTaskEntryPoint.QUICK_AFFORDANCE
import com.android.systemui.notetask.NoteTaskEntryPoint.TAIL_BUTTON
import com.android.systemui.notetask.NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT
import com.android.systemui.notetask.NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_OPENED_VIA_SHORTCUT
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON
import com.android.systemui.notetask.NoteTaskEventLogger.NoteTaskUiEvent.NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON_LOCKED
import javax.inject.Inject

/**
 * A wrapper around [UiEventLogger] specialized in the note taking UI events.
 *
 * if the accepted [NoteTaskInfo] contains a [NoteTaskInfo.entryPoint], it will be logged as the
 * correct [NoteTaskUiEvent]. If null, it will be ignored.
 *
 * @see NoteTaskController for usage examples.
 */
class NoteTaskEventLogger @Inject constructor(private val uiEventLogger: UiEventLogger) {

    /** Logs a [NoteTaskInfo] as an **open** [NoteTaskUiEvent], including package name and uid. */
    fun logNoteTaskOpened(info: NoteTaskInfo) {
        val event =
                when (info.entryPoint) {
                    TAIL_BUTTON -> {
                        if (info.isKeyguardLocked) {
                            NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON_LOCKED
                        } else {
                            NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON
                        }
                    }

                    WIDGET_PICKER_SHORTCUT,
                    WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE -> NOTE_OPENED_VIA_SHORTCUT

                    QUICK_AFFORDANCE -> NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE
                    APP_CLIPS,
                    KEYBOARD_SHORTCUT,
                    null -> return
                }
        uiEventLogger.log(event, info.uid, info.packageName)
    }

    /** Logs a [NoteTaskInfo] as a **closed** [NoteTaskUiEvent], including package name and uid. */
    fun logNoteTaskClosed(info: NoteTaskInfo) {
        val event =
                when (info.entryPoint) {
                    TAIL_BUTTON -> {
                        if (info.isKeyguardLocked) {
                            NoteTaskUiEvent.NOTE_CLOSED_VIA_STYLUS_TAIL_BUTTON_LOCKED
                        } else {
                            NoteTaskUiEvent.NOTE_CLOSED_VIA_STYLUS_TAIL_BUTTON
                        }
                    }

                    WIDGET_PICKER_SHORTCUT,
                    WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE,
                    QUICK_AFFORDANCE,
                    APP_CLIPS,
                    KEYBOARD_SHORTCUT,
                    null -> return
                }
        uiEventLogger.log(event, info.uid, info.packageName)
    }

    /** IDs of UI events accepted by [NoteTaskController]. */
    enum class NoteTaskUiEvent(private val _id: Int) : UiEventLogger.UiEventEnum {

        @UiEvent(doc = "User opened a note by tapping on the lockscreen shortcut.")
        NOTE_OPENED_VIA_KEYGUARD_QUICK_AFFORDANCE(1294),

        @UiEvent(doc = "User opened a note by pressing the stylus tail button while the screen was unlocked.") // ktlint-disable max-line-length
        NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON(1295),

        @UiEvent(doc = "User opened a note by pressing the stylus tail button while the screen was locked.") // ktlint-disable max-line-length
        NOTE_OPENED_VIA_STYLUS_TAIL_BUTTON_LOCKED(1296),

        @UiEvent(doc = "User opened a note by tapping on an app shortcut.")
        NOTE_OPENED_VIA_SHORTCUT(1297),

        @UiEvent(doc = "Note closed via a tail button while device is unlocked")
        NOTE_CLOSED_VIA_STYLUS_TAIL_BUTTON(1311),

        @UiEvent(doc = "Note closed via a tail button while device is locked")
        NOTE_CLOSED_VIA_STYLUS_TAIL_BUTTON_LOCKED(1312);

        override fun getId() = _id
    }
}
