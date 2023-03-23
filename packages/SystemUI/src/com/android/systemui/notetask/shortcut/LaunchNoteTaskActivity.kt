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
import android.content.pm.UserInfo
import android.os.Bundle
import android.os.UserManager
import androidx.activity.ComponentActivity
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.settings.UserTracker
import javax.inject.Inject

/** Activity responsible for launching the note experience, and finish. */
class LaunchNoteTaskActivity
@Inject
constructor(
    private val controller: NoteTaskController,
    private val userManager: UserManager,
    private val userTracker: UserTracker,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Under the hood, notes app shortcuts are shown in a floating window, called Bubble.
        // Bubble API is only available in the main user but not work profile.
        //
        // On devices with work profile (WP), SystemUI provides both personal notes app shortcuts &
        // work profile notes app shortcuts. In order to make work profile notes app shortcuts to
        // show in Bubble, a few redirections across users are required:
        // 1. When `LaunchNoteTaskActivity` is started in the work profile user, we launch
        //    `LaunchNoteTaskManagedProfileProxyActivity` on the main user, which has access to the
        //    Bubble API.
        // 2. `LaunchNoteTaskManagedProfileProxyActivity` calls `Bubble#showOrHideAppBubble` with
        //     the work profile user ID.
        // 3. Bubble renders the work profile notes app activity in a floating window, which is
        //    hosted in the main user.
        //
        //            WP                                main user
        //  ------------------------          -------------------------------------------
        // | LaunchNoteTaskActivity |   ->   | LaunchNoteTaskManagedProfileProxyActivity |
        //  ------------------------          -------------------------------------------
        //                                                        |
        //                 main user                              |
        //         ----------------------------                   |
        //        | Bubble#showOrHideAppBubble |   <--------------
        //        |      (with WP user ID)     |
        //         ----------------------------
        val mainUser: UserInfo? = userTracker.userProfiles.firstOrNull { it.isMain }
        if (userManager.isManagedProfile && mainUser != null) {
            controller.startNoteTaskProxyActivityForUser(mainUser.userHandle)
        } else {
            controller.showNoteTask(entryPoint = NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT)
        }
        finish()
    }

    companion object {

        /** Creates a new [Intent] set to start [LaunchNoteTaskActivity]. */
        fun newIntent(context: Context): Intent {
            return Intent(context, LaunchNoteTaskActivity::class.java).apply {
                // Intent's action must be set in shortcuts, or an exception will be thrown.
                action = Intent.ACTION_CREATE_NOTE
            }
        }
    }
}
