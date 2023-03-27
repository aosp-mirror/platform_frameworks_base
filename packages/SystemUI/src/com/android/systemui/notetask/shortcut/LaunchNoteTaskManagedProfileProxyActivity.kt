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

package com.android.systemui.notetask.shortcut

import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import androidx.activity.ComponentActivity
import com.android.systemui.notetask.NoteTaskController
import com.android.systemui.notetask.NoteTaskEntryPoint
import com.android.systemui.settings.UserTracker
import javax.inject.Inject

/**
 * An internal proxy activity that starts notes app in the work profile.
 *
 * If there is no work profile, this activity finishes gracefully.
 *
 * This activity MUST NOT be exported because that would expose the INTERACT_ACROSS_USER privilege
 * to any apps.
 */
class LaunchNoteTaskManagedProfileProxyActivity
@Inject
constructor(
    private val controller: NoteTaskController,
    private val userTracker: UserTracker,
    private val userManager: UserManager,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val managedProfileUser =
            userTracker.userProfiles.firstOrNull { userManager.isManagedProfile(it.id) }

        if (managedProfileUser == null) {
            logDebug { "Fail to find the work profile user." }
        } else {
            controller.showNoteTaskAsUser(
                entryPoint = NoteTaskEntryPoint.WIDGET_PICKER_SHORTCUT,
                user = managedProfileUser.userHandle
            )
        }
        finish()
    }
}

private inline fun logDebug(message: () -> String) {
    if (Build.IS_DEBUGGABLE) {
        Log.d(NoteTaskController.TAG, message())
    }
}
