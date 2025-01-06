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

package com.android.systemui.kairos.internal

import com.android.systemui.kairos.internal.util.LogIndent
import java.util.PriorityQueue

internal interface Scheduler {
    fun schedule(depth: Int, node: MuxNode<*, *, *>)

    fun scheduleIndirect(indirectDepth: Int, node: MuxNode<*, *, *>)
}

internal class SchedulerImpl(private val enqueue: (MuxNode<*, *, *>) -> Boolean) : Scheduler {
    private val scheduledQ = PriorityQueue<Pair<Int, MuxNode<*, *, *>>>(compareBy { it.first })

    override fun schedule(depth: Int, node: MuxNode<*, *, *>) {
        if (enqueue(node)) {
            scheduledQ.add(Pair(depth, node))
        }
    }

    override fun scheduleIndirect(indirectDepth: Int, node: MuxNode<*, *, *>) {
        schedule(Int.MIN_VALUE + indirectDepth, node)
    }

    internal fun drainEval(logIndent: Int, network: Network): Int =
        drain(logIndent) { runStep ->
            runStep { muxNode ->
                network.evalScope {
                    muxNode.markedForEvaluation = false
                    muxNode.visit(currentLogIndent, this)
                }
            }
            // If any visited MuxPromptNodes had their depths increased, eagerly propagate those
            // depth changes now before performing further network evaluation.
            val numNodes = network.compactor.drainCompact(currentLogIndent)
            logLn("promptly compacted $numNodes nodes")
        }

    internal fun drainCompact(logIndent: Int): Int =
        drain(logIndent) { runStep ->
            runStep { muxNode ->
                muxNode.markedForCompaction = false
                muxNode.visitCompact(scheduler = this@SchedulerImpl)
            }
        }

    private inline fun drain(
        logIndent: Int,
        crossinline onStep:
            LogIndent.(
                runStep: LogIndent.(visit: LogIndent.(MuxNode<*, *, *>) -> Unit) -> Unit
            ) -> Unit,
    ): Int {
        var total = 0
        while (scheduledQ.isNotEmpty()) {
            val maxDepth = scheduledQ.peek()?.first ?: error("Unexpected empty scheduler")
            LogIndent(logIndent).onStep { visit ->
                logDuration("step $maxDepth") {
                    val subtotal = runStep(maxDepth) { visit(it) }
                    logLn("visited $subtotal nodes")
                    total += subtotal
                }
            }
        }
        return total
    }

    private inline fun runStep(maxDepth: Int, crossinline visit: (MuxNode<*, *, *>) -> Unit): Int {
        var total = 0
        val toVisit = mutableListOf<MuxNode<*, *, *>>()
        while (scheduledQ.peek()?.first?.let { it <= maxDepth } == true) {
            val (d, node) = scheduledQ.remove()
            if (
                node.depthTracker.dirty_hasDirectUpstream() &&
                    d < node.depthTracker.dirty_directDepth
            ) {
                scheduledQ.add(node.depthTracker.dirty_directDepth to node)
            } else {
                total++
                toVisit.add(node)
            }
        }

        for (node in toVisit) {
            visit(node)
        }

        return total
    }
}
