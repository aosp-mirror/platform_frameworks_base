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

    private val WAKE_LOCK_TAG = "SysUI:BroadcastSender"
    private val WAKE_LOCK_SEND_REASON = "sendInBackground"

    /**
     * Sends broadcast via [Context.sendBroadcast] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcast(intent: Intent) {
        sendInBackground {
            context.sendBroadcast(intent)
        }
    }

    /**
     * Sends broadcast via [Context.sendBroadcast] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcast(intent: Intent, receiverPermission: String?) {
        sendInBackground {
            context.sendBroadcast(intent, receiverPermission)
        }
    }

    /**
     * Sends broadcast via [Context.sendBroadcastAsUser] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcastAsUser(intent: Intent, userHandle: UserHandle) {
        sendInBackground {
            context.sendBroadcastAsUser(intent, userHandle)
        }
    }

    /**
     * Sends broadcast via [Context.sendBroadcastAsUser] on background thread to avoid blocking
     * synchronous binder call.
     */
    @AnyThread
    fun sendBroadcastAsUser(intent: Intent, userHandle: UserHandle, receiverPermission: String?) {
        sendInBackground {
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
        sendInBackground {
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
        sendInBackground {
            context.sendBroadcastAsUser(intent, userHandle, receiverPermission, appOp)
        }
    }

    /**
     * Sends [Intent.ACTION_CLOSE_SYSTEM_DIALOGS] broadcast to the system.
     */
    @AnyThread
    fun closeSystemDialogs() {
        sendInBackground {
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    /**
     * Dispatches parameter on background executor while holding a wakelock.
     */
    private fun sendInBackground(callable: () -> Unit) {
        val broadcastWakelock = wakeLockBuilder.setTag(WAKE_LOCK_TAG)
                                .setMaxTimeout(5000)
                                .build()
        broadcastWakelock.acquire(WAKE_LOCK_SEND_REASON)
        bgExecutor.execute {
            try {
                callable.invoke()
            } finally {
                broadcastWakelock.release(WAKE_LOCK_SEND_REASON)
            }
        }
    }
}