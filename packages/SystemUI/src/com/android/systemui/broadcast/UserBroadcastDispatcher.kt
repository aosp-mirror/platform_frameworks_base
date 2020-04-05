/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.UserHandle
import android.util.ArrayMap
import android.util.ArraySet
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.internal.util.Preconditions
import com.android.systemui.Dumpable
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val MSG_REGISTER_RECEIVER = 0
private const val MSG_UNREGISTER_RECEIVER = 1
private const val TAG = "UserBroadcastDispatcher"
private const val DEBUG = false

/**
 * Broadcast dispatcher for a given user registration [userId].
 *
 * Created by [BroadcastDispatcher] as needed by users. The value of [userId] can be
 * [UserHandle.USER_ALL].
 *
 * Each instance of this class will register itself exactly once with [Context]. Updates to the
 * [IntentFilter] will be done in the background thread.
 */
class UserBroadcastDispatcher(
    private val context: Context,
    private val userId: Int,
    private val bgLooper: Looper
) : BroadcastReceiver(), Dumpable {

    companion object {
        // Used only for debugging. If not debugging, this variable will not be accessed and all
        // received broadcasts will be tagged with 0. However, as DEBUG is false, nothing will be
        // logged
        val index = AtomicInteger(0)
    }

    private val bgHandler = object : Handler(bgLooper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_REGISTER_RECEIVER -> handleRegisterReceiver(msg.obj as ReceiverData)
                MSG_UNREGISTER_RECEIVER -> handleUnregisterReceiver(msg.obj as BroadcastReceiver)
                else -> Unit
            }
        }
    }

    private val registered = AtomicBoolean(false)

    internal fun isRegistered() = registered.get()

    // Only modify in BG thread
    private val actionsToReceivers = ArrayMap<String, MutableSet<ReceiverData>>()
    private val receiverToReceiverData = ArrayMap<BroadcastReceiver, MutableSet<ReceiverData>>()

    @VisibleForTesting
    internal fun isReceiverReferenceHeld(receiver: BroadcastReceiver): Boolean {
        return receiverToReceiverData.contains(receiver) ||
                actionsToReceivers.any {
            it.value.any { it.receiver == receiver }
        }
    }

    // Only call on BG thread as it reads from the maps
    private fun createFilter(): IntentFilter {
        Preconditions.checkState(bgHandler.looper.isCurrentThread,
                "This method should only be called from BG thread")
        val categories = mutableSetOf<String>()
        receiverToReceiverData.values.flatten().forEach {
            it.filter.categoriesIterator()?.asSequence()?.let {
                categories.addAll(it)
            }
        }
        val intentFilter = IntentFilter().apply {
            // The keys of the arrayMap are of type String! so null check is needed
            actionsToReceivers.keys.forEach { if (it != null) addAction(it) else Unit }
            categories.forEach { addCategory(it) }
        }
        return intentFilter
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = if (DEBUG) index.getAndIncrement() else 0
        if (DEBUG) Log.w(TAG, "[$id] Received $intent")
        bgHandler.post(
                HandleBroadcastRunnable(actionsToReceivers, context, intent, pendingResult, id))
    }

    /**
     * Register a [ReceiverData] for this user.
     */
    fun registerReceiver(receiverData: ReceiverData) {
        bgHandler.obtainMessage(MSG_REGISTER_RECEIVER, receiverData).sendToTarget()
    }

    /**
     * Unregister a given [BroadcastReceiver] for this user.
     */
    fun unregisterReceiver(receiver: BroadcastReceiver) {
        bgHandler.obtainMessage(MSG_UNREGISTER_RECEIVER, receiver).sendToTarget()
    }

    private fun handleRegisterReceiver(receiverData: ReceiverData) {
        Preconditions.checkState(bgHandler.looper.isCurrentThread,
                "This method should only be called from BG thread")
        if (DEBUG) Log.w(TAG, "Register receiver: ${receiverData.receiver}")
        receiverToReceiverData.getOrPut(receiverData.receiver, { ArraySet() }).add(receiverData)
        var changed = false
        // Index the BroadcastReceiver by all its actions, that way it's easier to dispatch given
        // a received intent.
        receiverData.filter.actionsIterator().forEach {
            actionsToReceivers.getOrPut(it) {
                changed = true
                ArraySet()
            }.add(receiverData)
        }
        if (changed) {
            createFilterAndRegisterReceiverBG()
        }
    }

    private fun handleUnregisterReceiver(receiver: BroadcastReceiver) {
        Preconditions.checkState(bgHandler.looper.isCurrentThread,
                "This method should only be called from BG thread")
        if (DEBUG) Log.w(TAG, "Unregister receiver: $receiver")
        val actions = receiverToReceiverData.getOrElse(receiver) { return }
                .flatMap { it.filter.actionsIterator().asSequence().asIterable() }.toSet()
        receiverToReceiverData.remove(receiver)?.clear()
        var changed = false
        actions.forEach { action ->
            actionsToReceivers.get(action)?.removeIf { it.receiver == receiver }
            if (actionsToReceivers.get(action)?.isEmpty() ?: false) {
                changed = true
                actionsToReceivers.remove(action)
            }
        }
        if (changed) {
            createFilterAndRegisterReceiverBG()
        }
    }

    // Only call this from a BG thread
    private fun createFilterAndRegisterReceiverBG() {
        val intentFilter = createFilter()
        bgHandler.post(RegisterReceiverRunnable(intentFilter))
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("  Registered=${registered.get()}")
        actionsToReceivers.forEach { (action, list) ->
            pw.println("    $action:")
            list.forEach { pw.println("      ${it.receiver}") }
        }
    }

    private class HandleBroadcastRunnable(
        val actionsToReceivers: Map<String, Set<ReceiverData>>,
        val context: Context,
        val intent: Intent,
        val pendingResult: PendingResult,
        val index: Int
    ) : Runnable {
        override fun run() {
            if (DEBUG) Log.w(TAG, "[$index] Dispatching $intent")
            actionsToReceivers.get(intent.action)
                    ?.filter {
                        it.filter.hasAction(intent.action) &&
                            it.filter.matchCategories(intent.categories) == null }
                    ?.forEach {
                        it.executor.execute {
                            if (DEBUG) Log.w(TAG,
                                    "[$index] Dispatching ${intent.action} to ${it.receiver}")
                            it.receiver.pendingResult = pendingResult
                            it.receiver.onReceive(context, intent)
                        }
                    }
        }
    }

    private inner class RegisterReceiverRunnable(val intentFilter: IntentFilter) : Runnable {

        /*
         * Registers and unregisters the BroadcastReceiver
         */
        override fun run() {
            if (registered.get()) {
                context.unregisterReceiver(this@UserBroadcastDispatcher)
                registered.set(false)
            }
            // Short interval without receiver, this can be problematic
            if (intentFilter.countActions() > 0 && !registered.get()) {
                context.registerReceiverAsUser(
                        this@UserBroadcastDispatcher,
                        UserHandle.of(userId),
                        intentFilter,
                        null,
                        bgHandler)
                registered.set(true)
            }
        }
    }
}
