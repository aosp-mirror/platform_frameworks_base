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
import android.content.BroadcastReceiver.PendingResult
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import com.android.systemui.SysuiTestableContext
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * A fake instance of [BroadcastDispatcher] for tests.
 *
 * Important: The *real* broadcast dispatcher will only send intents to receivers if the intent
 * matches the [IntentFilter] that the [BroadcastReceiver] was registered with. This fake class
 * exposes [sendIntentToMatchingReceiversOnly] to get the same matching behavior as the real
 * broadcast dispatcher.
 */
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

    private val receivers: MutableSet<InternalReceiver> = ConcurrentHashMap.newKeySet()

    override fun registerReceiverWithHandler(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        handler: Handler,
        user: UserHandle,
        @Context.RegisterReceiverFlags flags: Int,
        permission: String?
    ) {
        receivers.add(InternalReceiver(receiver, filter))
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        executor: Executor?,
        user: UserHandle?,
        @Context.RegisterReceiverFlags flags: Int,
        permission: String?
    ) {
        receivers.add(InternalReceiver(receiver, filter))
    }

    override fun unregisterReceiver(receiver: BroadcastReceiver) {
        receivers.removeIf { it.receiver == receiver }
    }

    override fun unregisterReceiverForUser(receiver: BroadcastReceiver, user: UserHandle) {
        receivers.removeIf { it.receiver == receiver }
    }

    /**
     * Sends the given [intent] to *only* the receivers that were registered with an [IntentFilter]
     * that matches the intent.
     *
     * A non-null [pendingResult] can be used to pass the sending user.
     */
    fun sendIntentToMatchingReceiversOnly(
        context: Context,
        intent: Intent,
        pendingResult: PendingResult? = null
    ) {
        receivers.forEach {
            if (
                it.filter.match(
                    context.contentResolver,
                    intent,
                    /* resolve= */ false,
                    /* logTag= */ "FakeBroadcastDispatcher",
                ) > 0
            ) {
                if (pendingResult != null) {
                    it.receiver.pendingResult = pendingResult
                }
                it.receiver.onReceive(context, intent)
            }
        }
    }

    val numReceiversRegistered: Int
        get() = receivers.size

    fun cleanUpReceivers(testName: String) {
        receivers.forEach {
            val receiver = it.receiver
            Log.i(testName, "Receiver not unregistered from dispatcher: $receiver")
            if (shouldFailOnLeakedReceiver) {
                throw IllegalStateException("Receiver not unregistered from dispatcher: $receiver")
            }
        }
        receivers.clear()
    }

    private data class InternalReceiver(
        val receiver: BroadcastReceiver,
        val filter: IntentFilter,
    )

    companion object {
        fun fakePendingResultForUser(userId: Int) =
            PendingResult(
                /* resultCode = */ 0,
                /* resultData = */ "",
                /* resultExtras = */ null,
                /* type = */ PendingResult.TYPE_REGISTERED,
                /* ordered = */ false,
                /* sticky = */ false,
                /* token = */ null,
                userId,
                /* flags = */ 0,
            )
    }
}
