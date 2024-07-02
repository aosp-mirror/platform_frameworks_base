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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Active
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState.Inactive
import com.android.systemui.statusbar.CommandQueue
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@SysUISingleton
class ShortcutHelperRepository
@Inject
constructor(
    private val commandQueue: CommandQueue,
    private val broadcastDispatcher: BroadcastDispatcher,
) : CoreStartable {

    val state = MutableStateFlow<ShortcutHelperState>(Inactive)

    override fun start() {
        registerBroadcastReceiver(
            action = Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS,
            onReceive = { state.value = Active() }
        )
        registerBroadcastReceiver(
            action = Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS,
            onReceive = { state.value = Inactive }
        )
        registerBroadcastReceiver(
            action = Intent.ACTION_CLOSE_SYSTEM_DIALOGS,
            onReceive = { state.value = Inactive }
        )
        commandQueue.addCallback(
            object : CommandQueue.Callbacks {
                override fun dismissKeyboardShortcutsMenu() {
                    state.value = Inactive
                }

                override fun toggleKeyboardShortcutsMenu(deviceId: Int) {
                    state.value =
                        if (state.value is Inactive) {
                            Active(deviceId)
                        } else {
                            Inactive
                        }
                }
            }
        )
    }

    fun hide() {
        state.value = Inactive
    }

    private fun registerBroadcastReceiver(action: String, onReceive: () -> Unit) {
        broadcastDispatcher.registerReceiver(
            receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        onReceive()
                    }
                },
            filter = IntentFilter(action),
            flags = Context.RECEIVER_EXPORTED or Context.RECEIVER_VISIBLE_TO_INSTANT_APPS,
            user = UserHandle.ALL,
        )
    }
}
