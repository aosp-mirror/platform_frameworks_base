/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util.concurrency

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Allows to wait for multiple callbacks and notify when the last one is executed
 */
class PendingTasksContainer {

    private var pendingTasksCount: AtomicInteger = AtomicInteger(0)
    private var completionCallback: AtomicReference<Runnable> = AtomicReference()

    /**
     * Registers a task that we should wait for
     * @return a runnable that should be invoked when the task is finished
     */
    fun registerTask(name: String): Runnable {
        pendingTasksCount.incrementAndGet()

        if (ENABLE_TRACE) {
            Trace.beginAsyncSection("PendingTasksContainer#$name", 0)
        }

        return Runnable {
            if (pendingTasksCount.decrementAndGet() == 0) {
                val onComplete = completionCallback.getAndSet(null)
                onComplete?.run()

                if (ENABLE_TRACE) {
                    Trace.endAsyncSection("PendingTasksContainer#$name", 0)
                }
            }
        }
    }

    /**
     * Clears state and initializes the container
     */
    fun reset() {
        // Create new objects in case if there are pending callbacks from the previous invocations
        completionCallback = AtomicReference()
        pendingTasksCount = AtomicInteger(0)
    }

    /**
     * Starts waiting for all tasks to be completed
     * When all registered tasks complete it will invoke the [onComplete] callback
     */
    fun onTasksComplete(onComplete: Runnable) {
        completionCallback.set(onComplete)

        if (pendingTasksCount.get() == 0) {
            val currentOnComplete = completionCallback.getAndSet(null)
            currentOnComplete?.run()
        }
    }

    /**
     * Returns current pending tasks count
     */
    fun getPendingCount(): Int = pendingTasksCount.get()
}

private const val ENABLE_TRACE = false
