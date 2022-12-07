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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskIntentResolver
import javax.inject.Inject

/** Activity responsible for launching the note experience, and finish. */
internal class LaunchNoteTaskActivity
@Inject
constructor(
    private val noteTaskController: NoteTaskController,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        noteTaskController.showNoteTask(isInMultiWindowMode)

        finish()
    }

    companion object {

        /** Creates a new [Intent] set to start [LaunchNoteTaskActivity]. */
        fun newIntent(context: Context): Intent {
            return Intent(context, LaunchNoteTaskActivity::class.java).apply {
                // Intent's action must be set in shortcuts, or an exception will be thrown.
                // TODO(b/254606432): Use Intent.ACTION_NOTES instead.
                action = NoteTaskIntentResolver.NOTES_ACTION
            }
        }
    }
}
