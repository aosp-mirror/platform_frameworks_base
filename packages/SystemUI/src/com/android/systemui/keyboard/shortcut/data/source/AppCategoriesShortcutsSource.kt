/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyboard.shortcut.data.source

import android.view.KeyboardShortcutGroup
import android.view.WindowManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.extensions.copy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class AppCategoriesShortcutsSource
@Inject
constructor(
    private val windowManager: WindowManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : KeyboardShortcutGroupsSource {

    override suspend fun shortcutGroups(deviceId: Int): List<KeyboardShortcutGroup> =
        withContext(backgroundDispatcher) {
            val group = windowManager.getApplicationLaunchKeyboardShortcuts(deviceId)
            return@withContext if (group == null) {
                emptyList()
            } else {
                val sortedShortcutItems = group.items.sortedBy { it.label!!.toString().lowercase() }
                listOf(group.copy(items = sortedShortcutItems))
            }
        }
}
