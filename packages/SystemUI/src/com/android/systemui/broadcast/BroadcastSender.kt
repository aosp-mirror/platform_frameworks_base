package com.android.systemui.broadcast

import android.annotation.AnyThread
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.wakelock.WakeLock
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * SystemUI master Broadcast sender
 *
 * This class dispatches broadcasts on background thread to avoid synchronous call to binder. Use
 * this class instead of calling [Context.sendBroadcast] directly.
 */
@SysUISingleton
class BroadcastSender @Inject constructor(
    private val context: Context,
    private val wakeLockBuilder: WakeLock.Builder,
    @Background private val bgExecutor: Executor
) {

    /**
     * Sends broadcast via [Context.sendBroadcast] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcast(intent: Intent) {
        sendInBackground("$intent") {
            context.sendBroadcast(intent)
        }
    }

    /**
     * Sends broadcast via [Context.sendBroadcast] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcast(intent: Intent, receiverPermission: String?) {
        sendInBackground("$intent") {
            context.sendBroadcast(intent, receiverPermission)
        }
    }

    /**
     * Sends broadcast via [Context.sendBroadcastAsUser] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcastAsUser(intent: Intent, userHandle: UserHandle) {
        sendInBackground("$intent") {
            context.sendBroadcastAsUser(intent, userHandle)
        }
    }

    /**
     * Sends broadcast via [Context.sendBroadcastAsUser] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcastAsUser(intent: Intent, userHandle: UserHandle, receiverPermission: String?) {
        sendInBackground("$intent") {
            context.sendBroadcastAsUser(intent, userHandle, receiverPermission)
        }
    }

    /**
     * Sends broadcast via [Context.sendBroadcastAsUser] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcastAsUser(
        intent: Intent,
        userHandle: UserHandle,
        receiverPermission: String?,
        options: Bundle?
    ) {
        sendInBackground("$intent") {
            context.sendBroadcastAsUser(intent, userHandle, receiverPermission, options)
        }
    }

    /**
     * Sends broadcast via [Context.sendBroadcastAsUser] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcastAsUser(
        intent: Intent,
        userHandle: UserHandle,
        receiverPermission: String?,
        appOp: Int
    ) {
        sendInBackground("$intent") {
            context.sendBroadcastAsUser(intent, userHandle, receiverPermission, appOp)
        }
    }

    /**
     * Sends [Intent.ACTION_CLOSE_SYSTEM_DIALOGS] broadcast to the system.
     */
    @AnyThread
    fun closeSystemDialogs() {
        sendInBackground("closeSystemDialogs") {
            context.closeSystemDialogs()
        }
    }

    /**
     * Dispatches parameter on background executor while holding a wakelock.
     */
    private fun sendInBackground(reason: String, callable: () -> Unit) {
        val broadcastWakelock = wakeLockBuilder.setTag(WAKE_LOCK_TAG)
                                .setMaxTimeout(5000)
                                .build()
        broadcastWakelock.acquire(reason)
        bgExecutor.execute {
            try {
                callable.invoke()
            } finally {
                broadcastWakelock.release(reason)
            }
        }
    }

    companion object {
        private const val WAKE_LOCK_TAG = "SysUI:BroadcastSender"
    }
}