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
import android.hardware.input.FakeInputManager
import android.view.KeyboardShortcutGroup
import android.view.WindowManager
import android.view.WindowManager.KeyboardShortcutsReceiver
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever

class ShortcutHelperTestHelper(
    repo: ShortcutHelperStateRepository,
    private val context: Context,
    private val fakeBroadcastDispatcher: FakeBroadcastDispatcher,
    private val fakeCommandQueue: FakeCommandQueue,
    private val fakeInputManager: FakeInputManager,
    windowManager: WindowManager
) {

    companion object {
        const val DEFAULT_DEVICE_ID = 123
    }

    private var imeShortcuts: List<KeyboardShortcutGroup> = emptyList()
    private var currentAppsShortcuts: List<KeyboardShortcutGroup> = emptyList()

    init {
        whenever(windowManager.requestImeKeyboardShortcuts(any(), any())).thenAnswer {
            val keyboardShortcutReceiver = it.getArgument<KeyboardShortcutsReceiver>(0)
            keyboardShortcutReceiver.onKeyboardShortcutsReceived(imeShortcuts)
            return@thenAnswer Unit
        }
        whenever(windowManager.requestAppKeyboardShortcuts(any(), any())).thenAnswer {
            val keyboardShortcutReceiver = it.getArgument<KeyboardShortcutsReceiver>(0)
            keyboardShortcutReceiver.onKeyboardShortcutsReceived(currentAppsShortcuts)
            return@thenAnswer Unit
        }
        repo.start()
    }

    /**
     * Use this method to set what ime shortcuts should be returned from windowManager in tests. By
     * default windowManager.requestImeKeyboardShortcuts will return emptyList. See init block.
     */
    fun setImeShortcuts(imeShortcuts: List<KeyboardShortcutGroup>) {
        this.imeShortcuts = imeShortcuts
    }

    /**
     * Use this method to set what current app shortcuts should be returned from windowManager in
     * tests. By default [WindowManager.requestAppKeyboardShortcuts] will return emptyList.
     */
    fun setCurrentAppsShortcuts(currentAppShortcuts: List<KeyboardShortcutGroup>) {
        this.currentAppsShortcuts = currentAppShortcuts
    }

    fun hideThroughCloseSystemDialogs() {
        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        )
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
        fakeInputManager.addPhysicalKeyboardIfNotPresent(deviceId)
        fakeCommandQueue.doForEachCallback { it.toggleKeyboardShortcutsMenu(deviceId) }
    }

    fun hideForSystem() {
        fakeCommandQueue.doForEachCallback { it.dismissKeyboardShortcutsMenu() }
    }
}
