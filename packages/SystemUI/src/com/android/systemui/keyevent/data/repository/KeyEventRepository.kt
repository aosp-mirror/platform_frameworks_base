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

package com.android.systemui.keyevent.data.repository

import android.view.KeyEvent
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.CommandQueue
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Defines interface for classes that encapsulate application state for key event presses. */
interface KeyEventRepository {
    /** Observable for whether the power button key is pressed/down or not. */
    val isPowerButtonDown: Flow<Boolean>
}

@SysUISingleton
class KeyEventRepositoryImpl
@Inject
constructor(
    private val commandQueue: CommandQueue,
) : KeyEventRepository {
    override val isPowerButtonDown: Flow<Boolean> = conflatedCallbackFlow {
        val callback =
            object : CommandQueue.Callbacks {
                override fun handleSystemKey(event: KeyEvent) {
                    if (event.keyCode == KeyEvent.KEYCODE_POWER) {
                        trySendWithFailureLogging(event.isDown, TAG, "updated isPowerButtonDown")
                    }
                }
            }
        trySendWithFailureLogging(false, TAG, "init isPowerButtonDown")
        commandQueue.addCallback(callback)
        awaitClose { commandQueue.removeCallback(callback) }
    }

    companion object {
        private const val TAG = "KeyEventRepositoryImpl"
    }
}
