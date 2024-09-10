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
import android.view.WindowManager.KeyboardShortcutsReceiver
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine

class CurrentAppShortcutsSource @Inject constructor(private val windowManager: WindowManager) :
    KeyboardShortcutGroupsSource {
    override suspend fun shortcutGroups(deviceId: Int): List<KeyboardShortcutGroup> =
        suspendCancellableCoroutine { continuation ->
            val shortcutsReceiver = KeyboardShortcutsReceiver {
                continuation.resumeWith(Result.success(it ?: emptyList()))
            }
            windowManager.requestAppKeyboardShortcuts(shortcutsReceiver, deviceId)
        }
}
