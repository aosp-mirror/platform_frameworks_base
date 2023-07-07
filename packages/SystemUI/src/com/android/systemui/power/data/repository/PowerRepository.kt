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
 *
 */

package com.android.systemui.power.data.repository

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Defines interface for classes that act as source of truth for power-related data. */
interface PowerRepository {
    /** Whether the device is interactive. Starts with the current state. */
    val isInteractive: Flow<Boolean>
}

@SysUISingleton
class PowerRepositoryImpl
@Inject
constructor(
    manager: PowerManager,
    dispatcher: BroadcastDispatcher,
) : PowerRepository {

    override val isInteractive: Flow<Boolean> = conflatedCallbackFlow {
        fun send() {
            trySendWithFailureLogging(manager.isInteractive, TAG)
        }

        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    send()
                }
            }

        dispatcher.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        send()

        awaitClose { dispatcher.unregisterReceiver(receiver) }
    }

    companion object {
        private const val TAG = "PowerRepository"
    }
}
