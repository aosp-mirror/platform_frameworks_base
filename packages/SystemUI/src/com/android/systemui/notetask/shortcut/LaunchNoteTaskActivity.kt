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

package com.android.systemui.notetask.shortcut

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import javax.inject.Inject

/** Activity responsible for launching the note experience, and finish. */
class LaunchNoteTaskActivity @Inject constructor(private val controller: NoteTaskController) :
    ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryPoint =
            if (isInMultiWindowMode) {
                NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT_IN_MULTI_WINDOW_MODE
            } else {
                NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT
            }
        controller.showNoteTaskAsUser(entryPoint, user)
        finish()
    }

    companion object {

        /** Creates a new [Intent] set to start [LaunchNoteTaskActivity]. */
        fun createIntent(context: Context): Intent =
            Intent(context, LaunchNoteTaskActivity::class.java).apply {
                // Intent's action must be set in shortcuts, or an exception will be thrown.
                action = Intent.ACTION_CREATE_NOTE
            }

        /** Creates a new [ComponentName] for [LaunchNoteTaskActivity]. */
        fun createComponent(context: Context): ComponentName =
            ComponentName(context, LaunchNoteTaskActivity::class.java)
    }
}
