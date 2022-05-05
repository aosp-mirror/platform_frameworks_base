package com.android.systemui.broadcast

import android.content.BroadcastReceiver
import android.os.UserHandle
import android.util.SparseSetArray
import androidx.annotation.GuardedBy
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.logging.BroadcastDispatcherLogger
import com.android.systemui.util.indentIfPossible
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Store information about requests for unregistering receivers from [BroadcastDispatcher], before
 * they have been completely removed from the system.
 *
 * This helps make unregistering a receiver a *sync* operation.
 */
class PendingRemovalStore @Inject constructor(
    private val logger: BroadcastDispatcherLogger
) : Dumpable {
    @GuardedBy("pendingRemoval")
    private val pendingRemoval: SparseSetArray<BroadcastReceiver> = SparseSetArray()

    fun tagForRemoval(broadcastReceiver: BroadcastReceiver, userId: Int) {
        logger.logTagForRemoval(userId, broadcastReceiver)
        synchronized(pendingRemoval) {
            pendingRemoval.add(userId, broadcastReceiver)
        }
    }

    fun isPendingRemoval(broadcastReceiver: BroadcastReceiver, userId: Int): Boolean {
        return synchronized(pendingRemoval) {
            pendingRemoval.contains(userId, broadcastReceiver) ||
                pendingRemoval.contains(UserHandle.USER_ALL, broadcastReceiver)
        }
    }

    fun clearPendingRemoval(broadcastReceiver: BroadcastReceiver, userId: Int) {
        synchronized(pendingRemoval) {
            pendingRemoval.remove(userId, broadcastReceiver)
        }
        logger.logClearedAfterRemoval(userId, broadcastReceiver)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        synchronized(pendingRemoval) {
            pw.indentIfPossible {
                val size = pendingRemoval.size()
                for (i in 0 until size) {
                    val user = pendingRemoval.keyAt(i)
                    print(user)
                    print("->")
                    println(pendingRemoval.get(user))
                }
            }
        }
    }
}