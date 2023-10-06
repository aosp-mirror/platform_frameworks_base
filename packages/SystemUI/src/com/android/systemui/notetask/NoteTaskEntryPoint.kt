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

import com.android.systemui.notetask.quickaffordance.NoteTaskQuickAffordanceConfig
import com.android.systemui.notetask.shortcut.LaunchNoteTaskActivity
import com.android.systemui.screenshot.appclips.AppClipsTrampolineActivity

/**
 * Supported entry points for [NoteTaskController.showNoteTask].
 *
 * An entry point represents where the note task has ben called from. In rare cases, it may
 * represent a "re-entry" (i.e., [APP_CLIPS]).
 */
enum class NoteTaskEntryPoint {

    /** @see [LaunchNoteTaskActivity] */
    WIDGET_PICKER_SHORTCUT,

    /** @see [LaunchNoteTaskActivity] */
    WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE,

    /** @see [NoteTaskQuickAffordanceConfig] */
    QUICK_AFFORDANCE,

    /** @see [NoteTaskInitializer.callbacks] */
    TAIL_BUTTON,

    /** @see [AppClipsTrampolineActivity] */
    APP_CLIPS,

    /** @see [NoteTaskInitializer.callbacks] */
    KEYBOARD_SHORTCUT,
}
