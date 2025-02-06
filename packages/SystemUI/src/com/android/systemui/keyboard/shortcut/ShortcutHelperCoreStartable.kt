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

package com.android.systemui.keyboard.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyboard.shortcut.data.repository.CustomInputGesturesRepository
import com.android.systemui.keyboard.shortcut.data.repository.ShortcutHelperStateRepository
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.CommandQueue
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class ShortcutHelperCoreStartable
@Inject
constructor(
    private val commandQueue: CommandQueue,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val stateRepository: ShortcutHelperStateRepository,
    private val activityStarter: ActivityStarter,
    @Background private val backgroundScope: CoroutineScope,
    private val customInputGesturesRepository: CustomInputGesturesRepository
) : CoreStartable {
    override fun start() {
        registerBroadcastReceiver(
            action = Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS,
            onReceive = { showShortcutHelper() },
        )
        registerBroadcastReceiver(
            action = Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS,
            onReceive = { stateRepository.hide() },
        )
        registerBroadcastReceiver(
            action = Intent.ACTION_CLOSE_SYSTEM_DIALOGS,
            onReceive = { stateRepository.hide() },
        )
        registerBroadcastReceiver(
            action = Intent.ACTION_USER_SWITCHED,
            onReceive = { customInputGesturesRepository.refreshCustomInputGestures() },
        )
        commandQueue.addCallback(
            object : CommandQueue.Callbacks {
                override fun dismissKeyboardShortcutsMenu() {
                    stateRepository.hide()
                }

                override fun toggleKeyboardShortcutsMenu(deviceId: Int) {
                    toggleShortcutHelper(deviceId)
                }
            }
        )
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

    private fun showShortcutHelper() {
        dismissKeyguardThenPerformShortcutHelperAction { stateRepository.show() }
    }

    private fun toggleShortcutHelper(deviceId: Int? = null) {
        dismissKeyguardThenPerformShortcutHelperAction { stateRepository.toggle(deviceId) }
    }

    private fun dismissKeyguardThenPerformShortcutHelperAction(action: suspend () -> Unit) {
        activityStarter.dismissKeyguardThenExecute(
            /* action= */ {
                backgroundScope.launch { action() }
                false
            },
            /* cancel= */ {},
            /* afterKeyguardGone= */ true,
        )
    }
}
