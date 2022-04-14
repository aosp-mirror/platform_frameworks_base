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
import android.os.HandlerExecutor
import android.os.Looper
import android.os.Message
import android.os.UserHandle
import android.text.TextUtils
import android.util.IndentingPrintWriter
import android.util.SparseArray
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import java.io.PrintWriter
import java.util.concurrent.Executor

data class ReceiverData(
    val receiver: BroadcastReceiver,
    val filter: IntentFilter,
    val executor: Executor,
    val user: UserHandle,
    val permission: String? = null
)

private const val MSG_ADD_RECEIVER = 0
private const val MSG_REMOVE_RECEIVER = 1
private const val MSG_REMOVE_RECEIVER_FOR_USER = 2
private const val TAG = "BroadcastDispatcher"
private const val DEBUG = true

/**
 * SystemUI master Broadcast Dispatcher.
 *
 * This class allows [BroadcastReceiver] to register and centralizes registrations to [Context]
 * from SystemUI. That way the number of calls to [BroadcastReceiver.onReceive] can be reduced for
 * a given broadcast.
 *
 * Use only for IntentFilters with actions and optionally categories. It does not support,
 * permissions, schemes, data types, data authorities or priority different than 0.
 * Cannot be used for getting sticky broadcasts (either as return of registering or as re-delivery).
 * Broadcast handling may be asynchronous *without* calling goAsync(), as it's running within sysui
 * and doesn't need to worry about being killed.
 */
open class BroadcastDispatcher @JvmOverloads constructor (
    private val context: Context,
    private val bgLooper: Looper,
    private val bgExecutor: Executor,
    private val dumpManager: DumpManager,
    private val logger: BroadcastDispatcherLogger,
    private val userTracker: UserTracker,
    private val removalPendingStore: PendingRemovalStore = PendingRemovalStore(logger)
) : Dumpable {

    // Only modify in BG thread
    private val receiversByUser = SparseArray<UserBroadcastDispatcher>(20)

    fun initialize() {
        dumpManager.registerDumpable(javaClass.name, this)
    }

    /**
     * Register a receiver for broadcast with the dispatcher
     *
     * @param receiver A receiver to dispatch the [Intent]
     * @param filter A filter to determine what broadcasts should be dispatched to this receiver.
     *               It will only take into account actions and categories for filtering. It must
     *               have at least one action.
     * @param handler A handler to dispatch [BroadcastReceiver.onReceive].
     * @param user A user handle to determine which broadcast should be dispatched to this receiver.
     *             By default, it is the user of the context (system user in SystemUI).
     * @param flags Flags to use when registering the receiver. [Context.RECEIVER_EXPORTED] by
     *              default.
     * @throws IllegalArgumentException if the filter has other constraints that are not actions or
     *                                  categories or the filter has no actions.
     *
     */
    @Deprecated(message = "Replacing Handler for Executor in SystemUI",
        replaceWith = ReplaceWith("registerReceiver(receiver, filter, executor, user, permission)")
    )
    @JvmOverloads
    open fun registerReceiverWithHandler(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        handler: Handler,
        user: UserHandle = context.user,
        @Context.RegisterReceiverFlags flags: Int = Context.RECEIVER_EXPORTED,
        permission: String? = null
    ) {
        registerReceiver(receiver, filter, HandlerExecutor(handler), user, flags, permission)
    }

    /**
     * Register a receiver for broadcast with the dispatcher
     *
     * @param receiver A receiver to dispatch the [Intent]
     * @param filter A filter to determine what broadcasts should be dispatched to this receiver.
     *               It will only take into account actions and categories for filtering. It must
     *               have at least one action.
     * @param executor An executor to dispatch [BroadcastReceiver.onReceive]. Pass null to use an
     *                 executor in the main thread (default).
     * @param user A user handle to determine which broadcast should be dispatched to this receiver.
     *             Pass `null` to use the user of the context (system user in SystemUI).
     * @param flags Flags to use when registering the receiver. [Context.RECEIVER_EXPORTED] by
     *              default.
     * @throws IllegalArgumentException if the filter has other constraints that are not actions or
     *                                  categories or the filter has no actions.
     */
    @JvmOverloads
    open fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
        executor: Executor? = null,
        user: UserHandle? = null,
        @Context.RegisterReceiverFlags flags: Int = Context.RECEIVER_EXPORTED,
        permission: String? = null
    ) {
        checkFilter(filter)
        val data = ReceiverData(
                receiver,
                filter,
                executor ?: context.mainExecutor,
                user ?: context.user,
                permission
            )
        this.handler
                .obtainMessage(MSG_ADD_RECEIVER, flags, 0, data)
                .sendToTarget()
    }

    private fun checkFilter(filter: IntentFilter) {
        val sb = StringBuilder()
        if (filter.countActions() == 0) sb.append("Filter must contain at least one action. ")
        if (filter.countDataAuthorities() != 0) sb.append("Filter cannot contain DataAuthorities. ")
        if (filter.countDataPaths() != 0) sb.append("Filter cannot contain DataPaths. ")
        if (filter.countDataSchemes() != 0) sb.append("Filter cannot contain DataSchemes. ")
        if (filter.countDataTypes() != 0) sb.append("Filter cannot contain DataTypes. ")
        if (filter.priority != 0) sb.append("Filter cannot modify priority. ")
        if (!TextUtils.isEmpty(sb)) throw IllegalArgumentException(sb.toString())
    }

    /**
     * Unregister receiver for all users.
     * <br>
     * This will remove every registration of [receiver], not those done just with [UserHandle.ALL].
     *
     * @param receiver The receiver to unregister. It will be unregistered for all users.
     */
    open fun unregisterReceiver(receiver: BroadcastReceiver) {
        removalPendingStore.tagForRemoval(receiver, UserHandle.USER_ALL)
        handler.obtainMessage(MSG_REMOVE_RECEIVER, receiver).sendToTarget()
    }

    /**
     * Unregister receiver for a particular user.
     *
     * @param receiver The receiver to unregister.
     * @param user The user associated to the registered [receiver]. It can be [UserHandle.ALL].
     */
    open fun unregisterReceiverForUser(receiver: BroadcastReceiver, user: UserHandle) {
        removalPendingStore.tagForRemoval(receiver, user.identifier)
        handler.obtainMessage(MSG_REMOVE_RECEIVER_FOR_USER, user.identifier, 0, receiver)
                .sendToTarget()
    }

    @VisibleForTesting
    protected open fun createUBRForUser(userId: Int) =
            UserBroadcastDispatcher(
                context,
                userId,
                bgLooper,
                bgExecutor,
                logger,
                removalPendingStore
            )

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Broadcast dispatcher:")
        val ipw = IndentingPrintWriter(pw, "  ")
        ipw.increaseIndent()
        for (index in 0 until receiversByUser.size()) {
            ipw.println("User ${receiversByUser.keyAt(index)}")
            receiversByUser.valueAt(index).dump(ipw, args)
        }
        ipw.println("Pending removal:")
        removalPendingStore.dump(ipw, args)
        ipw.decreaseIndent()
    }

    private val handler = object : Handler(bgLooper) {

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ADD_RECEIVER -> {
                    val data = msg.obj as ReceiverData
                    val flags = msg.arg1
                    // If the receiver asked to be registered under the current user, we register
                    // under the actual current user.
                    val userId = if (data.user.identifier == UserHandle.USER_CURRENT) {
                        userTracker.userId
                    } else {
                        data.user.identifier
                    }
                    if (userId < UserHandle.USER_ALL) {
                        throw IllegalStateException(
                                "Attempting to register receiver for invalid user {$userId}")
                    }
                    val uBR = receiversByUser.get(userId, createUBRForUser(userId))
                    receiversByUser.put(userId, uBR)
                    uBR.registerReceiver(data, flags)
                }

                MSG_REMOVE_RECEIVER -> {
                    for (it in 0 until receiversByUser.size()) {
                        receiversByUser.valueAt(it).unregisterReceiver(msg.obj as BroadcastReceiver)
                    }
                    removalPendingStore.clearPendingRemoval(
                        msg.obj as BroadcastReceiver,
                        UserHandle.USER_ALL
                    )
                }

                MSG_REMOVE_RECEIVER_FOR_USER -> {
                    val userId = if (msg.arg1 == UserHandle.USER_CURRENT) {
                        userTracker.userId
                    } else {
                        msg.arg1
                    }
                    receiversByUser.get(userId)?.unregisterReceiver(msg.obj as BroadcastReceiver)
                    removalPendingStore.clearPendingRemoval(msg.obj as BroadcastReceiver, userId)
                }
                else -> super.handleMessage(msg)
            }
        }
    }
}
