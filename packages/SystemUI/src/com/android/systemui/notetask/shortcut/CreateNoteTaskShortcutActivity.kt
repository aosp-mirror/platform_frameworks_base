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

@file:OptIn(InternalNoteTaskApi::class)

package com.android.systemui.notetask.shortcut

import android.app.Activity
import android.app.role.RoleManager
import android.content.pm.ShortcutManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.android.systemui.notetask.InternalNoteTaskApi
import com.android.systemui.notetask.NoteTaskRoleManagerExt.createNoteShortcutInfoAsUser
import javax.inject.Inject

/**
 * Activity responsible for creating a shortcut for notes action. If the shortcut is enabled, a new
 * shortcut will appear in the widget picker. If the shortcut is selected, the Activity here will be
 * launched, creating a new shortcut for [CreateNoteTaskShortcutActivity], and will finish.
 *
 * @see <a
 *   href="https://developer.android.com/develop/ui/views/launch/shortcuts/creating-shortcuts#custom-pinned">Creating
 *   a custom shortcut activity</a>
 */
class CreateNoteTaskShortcutActivity
@Inject
constructor(
    private val roleManager: RoleManager,
    private val shortcutManager: ShortcutManager,
) : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shortcutInfo = roleManager.createNoteShortcutInfoAsUser(context = this, user)
        val shortcutIntent = shortcutManager.createShortcutResultIntent(shortcutInfo)
        setResult(Activity.RESULT_OK, shortcutIntent)

        finish()
    }
}
