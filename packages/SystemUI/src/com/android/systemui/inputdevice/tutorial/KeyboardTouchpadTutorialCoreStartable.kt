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

package com.android.systemui.inputdevice.tutorial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.inputdevice.tutorial.ui.TutorialNotificationCoordinator
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity
import com.android.systemui.shared.Flags.newTouchpadGesturesTutorial
import dagger.Lazy
import javax.inject.Inject

/** A [CoreStartable] to launch a scheduler for keyboard and touchpad tutorial notification */
@SysUISingleton
class KeyboardTouchpadTutorialCoreStartable
@Inject
constructor(
    private val tutorialNotificationCoordinator: Lazy<TutorialNotificationCoordinator>,
    private val broadcastDispatcher: BroadcastDispatcher,
    @Application private val applicationContext: Context,
) : CoreStartable {
    override fun start() {
        if (newTouchpadGesturesTutorial()) {
            tutorialNotificationCoordinator.get().start()
            registerTutorialBroadcastReceiver()
        }
    }

    private fun registerTutorialBroadcastReceiver() {
        broadcastDispatcher.registerReceiver(
            receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        applicationContext.startActivityAsUser(
                            Intent(
                                applicationContext,
                                KeyboardTouchpadTutorialActivity::class.java
                            ),
                            UserHandle.SYSTEM
                        )
                    }
                },
            filter = IntentFilter("com.android.systemui.action.KEYBOARD_TOUCHPAD_TUTORIAL"),
            flags = Context.RECEIVER_EXPORTED,
            user = UserHandle.ALL,
        )
    }
}
