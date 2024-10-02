/*
 * Copyright (C) 2024 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.experimental.frp.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal interface Scheduler {
    suspend fun schedule(depth: Int, node: MuxNode<*, *, *>)

    suspend fun scheduleIndirect(indirectDepth: Int, node: MuxNode<*, *, *>)
}

internal class SchedulerImpl : Scheduler {
    val enqueued = ConcurrentHashMap<MuxNode<*, *, *>, Any>()
    val scheduledQ = PriorityBlockingQueue<Pair<Int, MuxNode<*, *, *>>>(16, compareBy { it.first })
    val chan = Channel<Pair<Int, MuxNode<*, *, *>>>(Channel.UNLIMITED)

    override suspend fun schedule(depth: Int, node: MuxNode<*, *, *>) {
        if (enqueued.putIfAbsent(node, node) == null) {
            chan.send(Pair(depth, node))
        }
    }

    override suspend fun scheduleIndirect(indirectDepth: Int, node: MuxNode<*, *, *>) {
        schedule(Int.MIN_VALUE + indirectDepth, node)
    }

    suspend fun activate() {
        for (nodeSchedule in chan) {
            scheduledQ.add(nodeSchedule)
            drainChan()
        }
    }

    internal suspend fun drainEval(network: Network) {
        drain { runStep ->
            runStep { muxNode -> network.evalScope { muxNode.visit(this) } }
            // If any visited MuxPromptNodes had their depths increased, eagerly propagate those
            // depth
            // changes now before performing further network evaluation.
            network.compactor.drainCompact()
        }
    }

    internal suspend fun drainCompact() {
        drain { runStep -> runStep { muxNode -> muxNode.visitCompact(scheduler = this) } }
    }

    private suspend inline fun drain(
        crossinline onStep:
            suspend (runStep: suspend (visit: suspend (MuxNode<*, *, *>) -> Unit) -> Unit) -> Unit
    ): Unit = coroutineScope {
        while (!chan.isEmpty || scheduledQ.isNotEmpty()) {
            drainChan()
            val maxDepth = scheduledQ.peek()?.first ?: error("Unexpected empty scheduler")
            onStep { visit -> runStep(maxDepth, visit) }
        }
    }

    private suspend fun drainChan() {
        while (!chan.isEmpty) {
            scheduledQ.add(chan.receive())
        }
    }

    private suspend inline fun runStep(
        maxDepth: Int,
        crossinline visit: suspend (MuxNode<*, *, *>) -> Unit,
    ) = coroutineScope {
        while (scheduledQ.peek()?.first?.let { it <= maxDepth } == true) {
            val (d, node) = scheduledQ.remove()
            if (
                node.depthTracker.dirty_hasDirectUpstream() &&
                    d < node.depthTracker.dirty_directDepth
            ) {
                scheduledQ.add(node.depthTracker.dirty_directDepth to node)
            } else {
                launch {
                    enqueued.remove(node)
                    visit(node)
                }
            }
        }
    }
}
