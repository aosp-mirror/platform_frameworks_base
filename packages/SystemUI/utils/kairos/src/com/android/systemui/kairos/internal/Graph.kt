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

import com.android.systemui.kairos.internal.util.Bag
import java.util.TreeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Tracks all upstream connections for Mux nodes.
 *
 * Connections come in two flavors:
 * 1. **DIRECT** :: The upstream node may emit events that would cause the owner of this depth
 *    tracker to also emit.
 * 2. **INDIRECT** :: The upstream node will not emit events, but may start doing so in a future
 *    transaction (at which point its depth will change to DIRECT).
 *
 * DIRECT connections are the standard, active connections that propagate events through the graph.
 * They are used to calculate the evaluation depth of a node, so that it is only visited once it is
 * certain that all DIRECT upstream connections have already been visited (or are not emitting in
 * the current transaction).
 *
 * It is *invalid* for a node to be directly upstream of itself. Doing so is an error.
 *
 * INDIRECT connections identify nodes that are still "alive" (should not be garbage-collected) but
 * are presently "dormant". This only occurs when a MuxDeferredNode has nothing switched-in, but is
 * still connected to its "patches" upstream node, implying that something *may* be switched-in at a
 * later time.
 *
 * It is *invalid* for a node to be indirectly upstream of itself. These connections are
 * automatically filtered out.
 *
 * When there are no connections, either DIRECT or INDIRECT, a node *dies* and all incoming/outgoing
 * connections are freed so that it can be garbage-collected.
 *
 * Note that there is an edge case where a MuxDeferredNode is connected to itself via its "patches"
 * upstream node. In this case:
 * 1. If the node has switched-in upstream nodes, then this is perfectly valid. Downstream nodes
 *    will see a direct connection to this MuxDeferredNode.
 * 2. Otherwise, the node would normally be considered "dormant" and downstream nodes would see an
 *    indirect connection. However, because a node cannot be indirectly upstream of itself, then the
 *    MuxDeferredNode sees no connection via its patches upstream node, and so is considered "dead".
 *    Conceptually, this makes some sense: The only way for this recursive MuxDeferredNode to become
 *    non-dormant is to switch some upstream nodes back in, but since the patches node is itself,
 *    this will never happen.
 *
 * This behavior underpins the recursive definition of `nextOnly`.
 */
internal class DepthTracker {

    @Volatile var snapshotIsDirect = true
    @Volatile private var snapshotIsIndirectRoot = false

    private inline val snapshotIsIndirect: Boolean
        get() = !snapshotIsDirect

    @Volatile var snapshotIndirectDepth: Int = 0
    @Volatile var snapshotDirectDepth: Int = 0

    private val _snapshotIndirectRoots = HashSet<MuxDeferredNode<*, *>>()
    val snapshotIndirectRoots
        get() = _snapshotIndirectRoots.toSet()

    private val indirectAdditions = HashSet<MuxDeferredNode<*, *>>()
    private val indirectRemovals = HashSet<MuxDeferredNode<*, *>>()
    private val dirty_directUpstreamDepths = TreeMap<Int, Int>()
    private val dirty_indirectUpstreamDepths = TreeMap<Int, Int>()
    private val dirty_indirectUpstreamRoots = Bag<MuxDeferredNode<*, *>>()
    @Volatile var dirty_directDepth = 0
    @Volatile private var dirty_indirectDepth = 0
    @Volatile private var dirty_depthIsDirect = true
    @Volatile private var dirty_isIndirectRoot = false

    fun schedule(scheduler: Scheduler, node: MuxNode<*, *, *>) {
        if (dirty_depthIsDirect) {
            scheduler.schedule(dirty_directDepth, node)
        } else {
            scheduler.scheduleIndirect(dirty_indirectDepth, node)
        }
    }

    // only used by MuxDeferred
    // and only when there is a direct connection to the patch node
    fun setIsIndirectRoot(isRoot: Boolean): Boolean {
        if (isRoot != dirty_isIndirectRoot) {
            dirty_isIndirectRoot = isRoot
            return !dirty_depthIsDirect
        }
        return false
    }

    // adds an upstream connection, and recalcs depth
    // returns true if depth has changed
    fun addDirectUpstream(oldDepth: Int?, newDepth: Int): Boolean {
        if (oldDepth != null) {
            dirty_directUpstreamDepths.compute(oldDepth) { _, count ->
                count?.minus(1)?.takeIf { it > 0 }
            }
        }
        dirty_directUpstreamDepths.compute(newDepth) { _, current -> current?.plus(1) ?: 1 }
        return recalcDepth()
    }

    private fun recalcDepth(): Boolean {
        val newDepth =
            dirty_directUpstreamDepths.lastEntry()?.let { (maxDepth, _) -> maxDepth + 1 } ?: 0

        val isDirect = dirty_directUpstreamDepths.isNotEmpty()
        val isDirectChanged = dirty_depthIsDirect != isDirect
        dirty_depthIsDirect = isDirect

        return (newDepth != dirty_directDepth).also { dirty_directDepth = newDepth } or
            isDirectChanged
    }

    private fun recalcIndirDepth(): Boolean {
        val newDepth =
            dirty_indirectUpstreamDepths.lastEntry()?.let { (maxDepth, _) -> maxDepth + 1 } ?: 0
        return (!dirty_depthIsDirect && !dirty_isIndirectRoot && newDepth != dirty_indirectDepth)
            .also { dirty_indirectDepth = newDepth }
    }

    fun removeDirectUpstream(depth: Int): Boolean {
        dirty_directUpstreamDepths.compute(depth) { _, count -> count?.minus(1)?.takeIf { it > 0 } }
        return recalcDepth()
    }

    fun addIndirectUpstream(oldDepth: Int?, newDepth: Int): Boolean =
        if (oldDepth == newDepth) {
            false
        } else {
            if (oldDepth != null) {
                dirty_indirectUpstreamDepths.compute(oldDepth) { _, current ->
                    current?.minus(1)?.takeIf { it > 0 }
                }
            }
            dirty_indirectUpstreamDepths.compute(newDepth) { _, current -> current?.plus(1) ?: 1 }
            recalcIndirDepth()
        }

    fun removeIndirectUpstream(depth: Int): Boolean {
        dirty_indirectUpstreamDepths.compute(depth) { _, current ->
            current?.minus(1)?.takeIf { it > 0 }
        }
        return recalcIndirDepth()
    }

    fun updateIndirectRoots(
        additions: Set<MuxDeferredNode<*, *>>? = null,
        removals: Set<MuxDeferredNode<*, *>>? = null,
        butNot: MuxDeferredNode<*, *>? = null,
    ): Boolean {
        val addsChanged =
            additions
                ?.let { dirty_indirectUpstreamRoots.addAll(additions, butNot) }
                ?.let {
                    indirectAdditions.addAll(indirectRemovals.applyRemovalDiff(it))
                    true
                } ?: false
        val removalsChanged =
            removals
                ?.let { dirty_indirectUpstreamRoots.removeAll(removals) }
                ?.let {
                    indirectRemovals.addAll(indirectAdditions.applyRemovalDiff(it))
                    true
                } ?: false
        return (!dirty_depthIsDirect && (addsChanged || removalsChanged))
    }

    private fun <T> HashSet<T>.applyRemovalDiff(changeSet: Set<T>): Set<T> {
        val remainder = HashSet<T>()
        for (element in changeSet) {
            if (!add(element)) {
                remainder.add(element)
            }
        }
        return remainder
    }

    suspend fun propagateChanges(scheduler: Scheduler, muxNode: MuxNode<*, *, *>) {
        if (isDirty()) {
            schedule(scheduler, muxNode)
        }
    }

    fun applyChanges(
        coroutineScope: CoroutineScope,
        scheduler: Scheduler,
        downstreamSet: DownstreamSet,
        muxNode: MuxNode<*, *, *>,
    ) {
        when {
            dirty_depthIsDirect -> {
                if (snapshotIsDirect) {
                    downstreamSet.adjustDirectUpstream(
                        coroutineScope,
                        scheduler,
                        oldDepth = snapshotDirectDepth,
                        newDepth = dirty_directDepth,
                    )
                } else {
                    downstreamSet.moveIndirectUpstreamToDirect(
                        coroutineScope,
                        scheduler,
                        oldIndirectDepth = snapshotIndirectDepth,
                        oldIndirectSet =
                            buildSet {
                                addAll(snapshotIndirectRoots)
                                if (snapshotIsIndirectRoot) {
                                    add(muxNode as MuxDeferredNode<*, *>)
                                }
                            },
                        newDirectDepth = dirty_directDepth,
                    )
                }
            }

            dirty_hasIndirectUpstream() || dirty_isIndirectRoot -> {
                if (snapshotIsDirect) {
                    downstreamSet.moveDirectUpstreamToIndirect(
                        coroutineScope,
                        scheduler,
                        oldDirectDepth = snapshotDirectDepth,
                        newIndirectDepth = dirty_indirectDepth,
                        newIndirectSet =
                            buildSet {
                                addAll(dirty_indirectUpstreamRoots)
                                if (dirty_isIndirectRoot) {
                                    add(muxNode as MuxDeferredNode<*, *>)
                                }
                            },
                    )
                } else {
                    downstreamSet.adjustIndirectUpstream(
                        coroutineScope,
                        scheduler,
                        oldDepth = snapshotIndirectDepth,
                        newDepth = dirty_indirectDepth,
                        removals =
                            buildSet {
                                addAll(indirectRemovals)
                                if (snapshotIsIndirectRoot && !dirty_isIndirectRoot) {
                                    add(muxNode as MuxDeferredNode<*, *>)
                                }
                            },
                        additions =
                            buildSet {
                                addAll(indirectAdditions)
                                if (!snapshotIsIndirectRoot && dirty_isIndirectRoot) {
                                    add(muxNode as MuxDeferredNode<*, *>)
                                }
                            },
                    )
                }
            }

            else -> {
                // die
                muxNode.lifecycle.lifecycleState = MuxLifecycleState.Dead

                if (snapshotIsDirect) {
                    downstreamSet.removeDirectUpstream(
                        coroutineScope,
                        scheduler,
                        depth = snapshotDirectDepth,
                    )
                } else {
                    downstreamSet.removeIndirectUpstream(
                        coroutineScope,
                        scheduler,
                        depth = snapshotIndirectDepth,
                        indirectSet =
                            buildSet {
                                addAll(snapshotIndirectRoots)
                                if (snapshotIsIndirectRoot) {
                                    add(muxNode as MuxDeferredNode<*, *>)
                                }
                            },
                    )
                }
                downstreamSet.clear()
            }
        }
        reset()
    }

    fun dirty_hasDirectUpstream(): Boolean = dirty_directUpstreamDepths.isNotEmpty()

    private fun dirty_hasIndirectUpstream(): Boolean = dirty_indirectUpstreamRoots.isNotEmpty()

    override fun toString(): String =
        "DepthTracker(" +
            "sIsDirect=$snapshotIsDirect, " +
            "sDirectDepth=$snapshotDirectDepth, " +
            "sIndirectDepth=$snapshotIndirectDepth, " +
            "sIndirectRoots=$snapshotIndirectRoots, " +
            "dIsIndirectRoot=$dirty_isIndirectRoot, " +
            "dDirectDepths=$dirty_directUpstreamDepths, " +
            "dIndirectDepths=$dirty_indirectUpstreamDepths, " +
            "dIndirectRoots=$dirty_indirectUpstreamRoots" +
            ")"

    fun reset() {
        snapshotIsDirect = dirty_hasDirectUpstream()
        snapshotDirectDepth = dirty_directDepth
        snapshotIndirectDepth = dirty_indirectDepth
        snapshotIsIndirectRoot = dirty_isIndirectRoot
        if (indirectAdditions.isNotEmpty() || indirectRemovals.isNotEmpty()) {
            _snapshotIndirectRoots.clear()
            _snapshotIndirectRoots.addAll(dirty_indirectUpstreamRoots)
        }
        indirectAdditions.clear()
        indirectRemovals.clear()
        //        check(!isDirty()) { "should not be dirty after a reset" }
    }

    fun isDirty(): Boolean =
        when {
            snapshotIsDirect -> !dirty_depthIsDirect || snapshotDirectDepth != dirty_directDepth
            snapshotIsIndirectRoot -> dirty_depthIsDirect || !dirty_isIndirectRoot
            else ->
                dirty_depthIsDirect ||
                    dirty_isIndirectRoot ||
                    snapshotIndirectDepth != dirty_indirectDepth ||
                    indirectAdditions.isNotEmpty() ||
                    indirectRemovals.isNotEmpty()
        }

    fun dirty_depthIncreased(): Boolean =
        snapshotDirectDepth < dirty_directDepth || snapshotIsIndirect && dirty_hasDirectUpstream()
}

/**
 * Tracks downstream nodes to be scheduled when the owner of this DownstreamSet produces a value in
 * a transaction.
 */
internal class DownstreamSet {

    val outputs = HashSet<Output<*>>()
    val stateWriters = mutableListOf<TStateSource<*>>()
    val muxMovers = HashSet<MuxDeferredNode<*, *>>()
    val nodes = HashSet<SchedulableNode>()

    fun add(schedulable: Schedulable) {
        when (schedulable) {
            is Schedulable.S -> stateWriters.add(schedulable.state)
            is Schedulable.M -> muxMovers.add(schedulable.muxMover)
            is Schedulable.N -> nodes.add(schedulable.node)
            is Schedulable.O -> outputs.add(schedulable.output)
        }
    }

    fun remove(schedulable: Schedulable) {
        when (schedulable) {
            is Schedulable.S -> error("WTF: latches are never removed")
            is Schedulable.M -> muxMovers.remove(schedulable.muxMover)
            is Schedulable.N -> nodes.remove(schedulable.node)
            is Schedulable.O -> outputs.remove(schedulable.output)
        }
    }

    fun adjustDirectUpstream(
        coroutineScope: CoroutineScope,
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
    ) =
        coroutineScope.run {
            for (node in nodes) {
                launch { node.adjustDirectUpstream(scheduler, oldDepth, newDepth) }
            }
        }

    fun moveIndirectUpstreamToDirect(
        coroutineScope: CoroutineScope,
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectSet: Set<MuxDeferredNode<*, *>>,
        newDirectDepth: Int,
    ) =
        coroutineScope.run {
            for (node in nodes) {
                launch {
                    node.moveIndirectUpstreamToDirect(
                        scheduler,
                        oldIndirectDepth,
                        oldIndirectSet,
                        newDirectDepth,
                    )
                }
            }
            for (mover in muxMovers) {
                launch {
                    mover.moveIndirectPatchNodeToDirect(scheduler, oldIndirectDepth, oldIndirectSet)
                }
            }
        }

    fun adjustIndirectUpstream(
        coroutineScope: CoroutineScope,
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *>>,
        additions: Set<MuxDeferredNode<*, *>>,
    ) =
        coroutineScope.run {
            for (node in nodes) {
                launch {
                    node.adjustIndirectUpstream(scheduler, oldDepth, newDepth, removals, additions)
                }
            }
            for (mover in muxMovers) {
                launch {
                    mover.adjustIndirectPatchNode(
                        scheduler,
                        oldDepth,
                        newDepth,
                        removals,
                        additions,
                    )
                }
            }
        }

    fun moveDirectUpstreamToIndirect(
        coroutineScope: CoroutineScope,
        scheduler: Scheduler,
        oldDirectDepth: Int,
        newIndirectDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *>>,
    ) =
        coroutineScope.run {
            for (node in nodes) {
                launch {
                    node.moveDirectUpstreamToIndirect(
                        scheduler,
                        oldDirectDepth,
                        newIndirectDepth,
                        newIndirectSet,
                    )
                }
            }
            for (mover in muxMovers) {
                launch {
                    mover.moveDirectPatchNodeToIndirect(scheduler, newIndirectDepth, newIndirectSet)
                }
            }
        }

    fun removeIndirectUpstream(
        coroutineScope: CoroutineScope,
        scheduler: Scheduler,
        depth: Int,
        indirectSet: Set<MuxDeferredNode<*, *>>,
    ) =
        coroutineScope.run {
            for (node in nodes) {
                launch { node.removeIndirectUpstream(scheduler, depth, indirectSet) }
            }
            for (mover in muxMovers) {
                launch { mover.removeIndirectPatchNode(scheduler, depth, indirectSet) }
            }
            for (output in outputs) {
                launch { output.kill() }
            }
        }

    fun removeDirectUpstream(coroutineScope: CoroutineScope, scheduler: Scheduler, depth: Int) =
        coroutineScope.run {
            for (node in nodes) {
                launch { node.removeDirectUpstream(scheduler, depth) }
            }
            for (mover in muxMovers) {
                launch { mover.removeDirectPatchNode(scheduler) }
            }
            for (output in outputs) {
                launch { output.kill() }
            }
        }

    fun clear() {
        outputs.clear()
        stateWriters.clear()
        muxMovers.clear()
        nodes.clear()
    }
}

// TODO: remove this indirection
internal sealed interface Schedulable {
    data class S constructor(val state: TStateSource<*>) : Schedulable

    data class M constructor(val muxMover: MuxDeferredNode<*, *>) : Schedulable

    data class N constructor(val node: SchedulableNode) : Schedulable

    data class O constructor(val output: Output<*>) : Schedulable
}

internal fun DownstreamSet.isEmpty() =
    nodes.isEmpty() && outputs.isEmpty() && muxMovers.isEmpty() && stateWriters.isEmpty()

@Suppress("NOTHING_TO_INLINE") internal inline fun DownstreamSet.isNotEmpty() = !isEmpty()

internal fun CoroutineScope.scheduleAll(
    downstreamSet: DownstreamSet,
    evalScope: EvalScope,
): Boolean {
    downstreamSet.nodes.forEach { launch { it.schedule(evalScope) } }
    downstreamSet.muxMovers.forEach { launch { it.scheduleMover(evalScope) } }
    downstreamSet.outputs.forEach { launch { it.schedule(evalScope) } }
    downstreamSet.stateWriters.forEach { evalScope.schedule(it) }
    return downstreamSet.isNotEmpty()
}
