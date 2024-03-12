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

package com.android.systemui.dump

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Trace
import android.os.UserHandle
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.concurrency.DelayableExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class LogBufferFreezer constructor(
    private val dumpManager: DumpManager,
    @Main private val executor: DelayableExecutor,
    private val freezeDuration: Long
) {
    @Inject constructor(
        dumpManager: DumpManager,
        @Main executor: DelayableExecutor
    ) : this(dumpManager, executor, TimeUnit.MINUTES.toMillis(5))

    private var pendingToken: Runnable? = null

    fun attach(broadcastDispatcher: BroadcastDispatcher) {
        broadcastDispatcher.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        onBugreportStarted()
                    }
                },
                IntentFilter("com.android.internal.intent.action.BUGREPORT_STARTED"),
                executor,
                UserHandle.ALL)
    }

    private fun onBugreportStarted() {
        Trace.instantForTrack(Trace.TRACE_TAG_APP, "bugreport",
                "BUGREPORT_STARTED broadcast received")
        pendingToken?.run()

        Log.i(TAG, "Freezing log buffers")
        dumpManager.freezeBuffers()

        pendingToken = executor.executeDelayed({
            Log.i(TAG, "Unfreezing log buffers")
            pendingToken = null
            dumpManager.unfreezeBuffers()
        }, freezeDuration)
    }
}

private const val TAG = "LogBufferFreezer"