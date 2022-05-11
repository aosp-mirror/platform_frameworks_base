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
 */

package com.android.systemui.statusbar.notification.collection

import android.view.Choreographer
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.util.ListenerSet
import com.android.systemui.util.concurrency.DelayableExecutor
import dagger.Binds
import dagger.Module
import javax.inject.Inject

/**
 * Choreographs evaluation resulting from multiple asynchronous sources. Specifically, it exposes
 * [schedule], and [addOnEvalListener]; the former will "schedule" an asynchronous invocation of the
 * latter. Multiple invocations of [schedule] before any added listeners are invoked have no effect.
 */
interface NotifPipelineChoreographer {
    /**
     * Schedules all listeners registered with [addOnEvalListener] to be asynchronously executed at
     * some point in the future. The exact timing is up to the implementation.
     */
    fun schedule()

    /** Cancels a pending evaluation triggered by any recent calls to [schedule]. */
    fun cancel()

    /** Adds a listener [Runnable] that will be invoked when the scheduled evaluation occurs. */
    fun addOnEvalListener(onEvalListener: Runnable)

    /** Removes a listener previously registered with [addOnEvalListener]. */
    fun removeOnEvalListener(onEvalListener: Runnable)
}

@Module(includes = [PrivateModule::class])
object NotifPipelineChoreographerModule

@Module
private interface PrivateModule {
    @Binds
    fun bindChoreographer(impl: NotifPipelineChoreographerImpl): NotifPipelineChoreographer
}

private const val TIMEOUT_MS: Long = 100

@SysUISingleton
private class NotifPipelineChoreographerImpl @Inject constructor(
    private val viewChoreographer: Choreographer,
    @Main private val executor: DelayableExecutor
) : NotifPipelineChoreographer {

    private val listeners = ListenerSet<Runnable>()
    private var timeoutSubscription: Runnable? = null
    private var isScheduled = false

    private val frameCallback = Choreographer.FrameCallback {
        if (isScheduled) {
            isScheduled = false
            timeoutSubscription?.run()
            listeners.forEach { it.run() }
        }
    }

    override fun schedule() {
        if (isScheduled) return
        isScheduled = true
        viewChoreographer.postFrameCallback(frameCallback)
        if (!isScheduled) {
            // Guard against synchronous evaluation of the frame callback.
            return
        }
        timeoutSubscription = executor.executeDelayed(::onTimeout, TIMEOUT_MS)
    }

    override fun cancel() {
        if (!isScheduled) return
        timeoutSubscription?.run()
        viewChoreographer.removeFrameCallback(frameCallback)
    }

    override fun addOnEvalListener(onEvalListener: Runnable) {
        listeners.addIfAbsent(onEvalListener)
    }

    override fun removeOnEvalListener(onEvalListener: Runnable) {
        listeners.remove(onEvalListener)
    }

    private fun onTimeout() {
        if (isScheduled) {
            isScheduled = false
            viewChoreographer.removeFrameCallback(frameCallback)
            listeners.forEach { it.run() }
        }
    }
}

class FakeNotifPipelineChoreographer : NotifPipelineChoreographer {

    var isScheduled = false
    val listeners = ListenerSet<Runnable>()

    fun runIfScheduled() {
        if (isScheduled) {
            isScheduled = false
            listeners.forEach { it.run() }
        }
    }

    override fun schedule() {
        isScheduled = true
    }

    override fun cancel() {
        isScheduled = false
    }

    override fun addOnEvalListener(onEvalListener: Runnable) {
        listeners.addIfAbsent(onEvalListener)
    }

    override fun removeOnEvalListener(onEvalListener: Runnable) {
        listeners.remove(onEvalListener)
    }
}
