/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import com.android.systemui.SysuiTestableContext
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import java.lang.IllegalStateException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class FakeBroadcastDispatcher(
    context: SysuiTestableContext,
    mainExecutor: Executor,
    broadcastRunningLooper: Looper,
    broadcastRunningExecutor: Executor,
    dumpManager: DumpManager,
    logger: BroadcastDispatcherLogger,
    userTracker: UserTracker,
    private val shouldFailOnLeakedReceiver: Boolean
) :
    BroadcastDispatcher(
        context,
        mainExecutor,
        broadcastRunningLooper,
        broadcastRunningExecutor,
        dumpManager,
        logger,
        userTracker,
        PendingRemovalStore(logger)
    ) {

    val registeredReceivers: MutableSet<BroadcastReceiver> = ConcurrentHashMap.newKeySet()

    override fun registerReceiverWithHandler(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        handler: Handler,
        user: UserHandle,
        @Context.RegisterReceiverFlags flags: Int,
        permission: String?
    ) {
        registeredReceivers.add(receiver)
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        executor: Executor?,
        user: UserHandle?,
        @Context.RegisterReceiverFlags flags: Int,
        permission: String?
    ) {
        registeredReceivers.add(receiver)
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        registeredReceivers.remove(receiver)
    }

    override fun unregisterReceiverForUser(receiver: BroadcastReceiver, user: UserHandle) {
        registeredReceivers.remove(receiver)
    }

    fun cleanUpReceivers(testName: String) {
        registeredReceivers.forEach {
            Log.i(testName, "Receiver not unregistered from dispatcher: $it")
            if (shouldFailOnLeakedReceiver) {
                throw IllegalStateException("Receiver not unregistered from dispatcher: $it")
            }
        }
        registeredReceivers.clear()
    }
}
