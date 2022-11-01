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

package com.android.systemui.util.kotlin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * A utility for handling incoming IPCs from a Binder interface in the order that they are received.
 *
 * This class serves as a replacement for the common [android.os.Handler] message-queue pattern,
 * where IPCs can arrive on arbitrary threads and are all enqueued onto a queue and processed by the
 * Handler in-order.
 *
 *     class MyService : Service() {
 *
 *       private val serializer = IpcSerializer()
 *
 *       // Need to invoke process() in order to actually process IPCs sent over the serializer.
 *       override fun onStart(...) = lifecycleScope.launch {
 *         serializer.process()
 *       }
 *
 *       // In your binder implementation, use runSerializedBlocking to enqueue a function onto
 *       // the serializer.
 *       override fun onBind(intent: Intent?) = object : IAidlService.Stub() {
 *         override fun ipcMethodFoo() = serializer.runSerializedBlocking {
 *           ...
 *         }
 *
 *         override fun ipcMethodBar() = serializer.runSerializedBlocking {
 *           ...
 *         }
 *       }
 *     }
 */
class IpcSerializer {

    private val channel = Channel<Pair<CompletableDeferred<Unit>, Job>>()

    /**
     * Runs functions enqueued via usage of [runSerialized] and [runSerializedBlocking] serially.
     * This method will never complete normally, so it must be launched in its own coroutine; if
     * this is not actively running, no enqueued functions will be evaluated.
     */
    suspend fun process(): Nothing {
        for ((start, finish) in channel) {
            // Signal to the sender that serializer has reached this message
            start.complete(Unit)
            // Wait to hear from the sender that it has finished running it's work, before handling
            // the next message
            finish.join()
        }
        error("Unexpected end of serialization channel")
    }

    /**
     * Enqueues [block] for evaluation by the serializer, suspending the caller until it has
     * completed. It is up to the caller to define what thread this is evaluated in, determined
     * by the [kotlin.coroutines.CoroutineContext] used.
     */
    suspend fun <R> runSerialized(block: suspend () -> R): R {
        val start = CompletableDeferred(Unit)
        val finish = CompletableDeferred(Unit)
        // Enqueue our message on the channel.
        channel.send(start to finish)
        // Wait for the serializer to reach our message
        start.await()
        // Now evaluate the block
        val result = block()
        // Notify the serializer that we've completed evaluation
        finish.complete(Unit)
        return result
    }

    /**
     * Enqueues [block] for evaluation by the serializer, blocking the binder thread until it has
     * completed. Evaluation occurs on the binder thread, so methods like
     * [android.os.Binder.getCallingUid] that depend on the current thread will work as expected.
     */
    fun <R> runSerializedBlocking(block: suspend () -> R): R = runBlocking { runSerialized(block) }
}
