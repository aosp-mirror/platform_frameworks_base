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

package com.android.systemui.keyboard.shortcut.data.repository

import android.content.Context
import android.content.Intent
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.keyguard.data.repository.FakeCommandQueue

class ShortcutHelperTestHelper(
    repo: ShortcutHelperRepository,
    private val context: Context,
    private val fakeBroadcastDispatcher: FakeBroadcastDispatcher,
    private val fakeCommandQueue: FakeCommandQueue,
) {

    init {
        repo.start()
    }

    fun hideFromActivity() {
        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS)
        )
    }

    fun showFromActivity() {
        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS)
        )
    }

    fun toggle(deviceId: Int) {
        fakeCommandQueue.doForEachCallback { it.toggleKeyboardShortcutsMenu(deviceId) }
    }

    fun hideForSystem() {
        fakeCommandQueue.doForEachCallback { it.dismissKeyboardShortcutsMenu() }
    }
}
