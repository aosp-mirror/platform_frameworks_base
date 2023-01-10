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
import android.content.Intent
import android.content.IntentFilter
import android.util.ArraySet
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.util.indentIfPossible
import java.io.PrintWriter
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receiver for a given action-userId pair to be used by [UserBroadcastDispatcher].
 *
 * Each object of this class will take care of a single Action. It will register if it has at least
 * one [BroadcastReceiver] added to it, and unregister when none are left.
 *
 * It will also re-register if filters with new categories are added. But this should not happen
 * often.
 *
 * This class has no sync controls, so make sure to only make modifications from the background
 * thread.
 *
 * This class takes the following actions:
 * * [registerAction]: action to register this receiver (with the proper filter) with [Context].
 * * [unregisterAction]: action to unregister this receiver with [Context].
 * * [testPendingRemovalAction]: action to check if a particular [BroadcastReceiver] registered
 *   with [BroadcastDispatcher] has been unregistered and is pending removal. See
 *   [PendingRemovalStore].
 */
class ActionReceiver(
    private val action: String,
    private val userId: Int,
    private val registerAction: BroadcastReceiver.(IntentFilter) -> Unit,
    private val unregisterAction: BroadcastReceiver.() -> Unit,
    private val workerExecutor: Executor,
    private val logger: BroadcastDispatcherLogger,
    private val testPendingRemovalAction: (BroadcastReceiver, Int) -> Boolean
) : BroadcastReceiver(), Dumpable {

    companion object {
        val index = AtomicInteger(0)
    }

    var registered = false
        private set
    private val receiverDatas = ArraySet<ReceiverData>()
    private val activeCategories = ArraySet<String>()

    @Throws(IllegalArgumentException::class)
    fun addReceiverData(receiverData: ReceiverData) {
        if (!receiverData.filter.hasAction(action)) {
            throw(IllegalArgumentException("Trying to attach to $action without correct action," +
                "receiver: ${receiverData.receiver}"))
        }
        val addedCategories = activeCategories
                .addAll(receiverData.filter.categoriesIterator()?.asSequence() ?: emptySequence())

        if (receiverDatas.add(receiverData) && receiverDatas.size == 1) {
            registerAction(createFilter())
            registered = true
        } else if (addedCategories) {
            unregisterAction()
            registerAction(createFilter())
        }
    }

    fun hasReceiver(receiver: BroadcastReceiver): Boolean {
        return receiverDatas.any { it.receiver == receiver }
    }

    private fun createFilter(): IntentFilter {
        val filter = IntentFilter(action)
        activeCategories.forEach(filter::addCategory)
        return filter
    }

    fun removeReceiver(receiver: BroadcastReceiver) {
        if (receiverDatas.removeAll { it.receiver == receiver } &&
                receiverDatas.isEmpty() && registered) {
            unregisterAction()
            registered = false
            activeCategories.clear()
        }
    }

    @Throws(IllegalStateException::class)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != action) {
            throw(IllegalStateException("Received intent for ${intent.action} " +
                "in receiver for $action}"))
        }
        val id = index.getAndIncrement()
        logger.logBroadcastReceived(id, userId, intent)
        // Immediately return control to ActivityManager
        workerExecutor.execute {
            receiverDatas.forEach {
                if (it.filter.matchCategories(intent.categories) == null &&
                    !testPendingRemovalAction(it.receiver, userId)) {
                    it.executor.execute {
                        it.receiver.pendingResult = pendingResult
                        it.receiver.onReceive(context, intent)
                        logger.logBroadcastDispatched(id, action, it.receiver)
                    }
                }
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.indentIfPossible {
            println("Registered: $registered")
            println("Receivers:")
            pw.indentIfPossible {
                receiverDatas.forEach {
                    println(it.receiver)
                }
            }
            println("Categories: ${activeCategories.joinToString(", ")}")
        }
    }
}
