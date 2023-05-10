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

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import javax.inject.Inject

/**
 * An internal proxy activity that starts the notes role setting.
 *
 * This activity is introduced mainly for the error handling of the notes app lock screen shortcut
 * picker, which only supports package + action but not extras. See
 * [KeyguardQuickAffordanceConfig.PickerScreenState.Disabled.actionComponentName].
 */
class LaunchNotesRoleSettingsTrampolineActivity
@Inject
constructor(
    private val controller: NoteTaskController,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryPoint =
            if (intent?.action == ACTION_MANAGE_NOTES_ROLE_FROM_QUICK_AFFORDANCE) {
                NoteTaskEntryPoint.QUICK_AFFORDANCE
            } else {
                null
            }
        controller.startNotesRoleSetting(this, entryPoint)
        finish()
    }

    companion object {
        const val ACTION_MANAGE_NOTES_ROLE_FROM_QUICK_AFFORDANCE =
            "com.android.systemui.action.MANAGE_NOTES_ROLE_FROM_QUICK_AFFORDANCE"
    }
}
