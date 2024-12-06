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

@file:Suppress("NOTHING_TO_INLINE")

package com.android.systemui.kairos.internal

import com.android.systemui.kairos.internal.store.MapHolder
import com.android.systemui.kairos.internal.store.MapK
import com.android.systemui.kairos.internal.store.MutableMapK
import com.android.systemui.kairos.internal.store.asMapHolder
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.internal.util.logDuration

internal typealias MuxResult<W, K, V> = MapK<W, K, PullNode<V>>

/** Base class for muxing nodes, which have a (potentially dynamic) collection of upstream nodes. */
internal sealed class MuxNode<W, K, V>(
    val lifecycle: MuxLifecycle<W, K, V>,
    protected val storeFactory: MutableMapK.Factory<W, K>,
) : PushNode<MuxResult<W, K, V>> {

    lateinit var upstreamData: MutableMapK<W, K, PullNode<V>>
    lateinit var switchedIn: MutableMapK<W, K, BranchNode>

    @Volatile var markedForCompaction = false
    @Volatile var markedForEvaluation = false

    val downstreamSet: DownstreamSet = DownstreamSet()

    // TODO: inline DepthTracker? would need to be added to PushNode signature
    final override val depthTracker = DepthTracker()

    val transactionCache = TransactionCache<MuxResult<W, K, V>>()
    val epoch
        get() = transactionCache.epoch

    inline fun hasCurrentValueLocked(evalScope: EvalScope): Boolean = epoch == evalScope.epoch

    override fun hasCurrentValue(logIndent: Int, evalScope: EvalScope): Boolean =
        hasCurrentValueLocked(evalScope)

    final override fun addDownstream(downstream: Schedulable) {
        addDownstreamLocked(downstream)
    }

    /**
     * Adds a downstream schedulable to this mux node, such that when this mux node emits a value,
     * it will be scheduled for evaluation within this same transaction.
     *
     * Must only be called when [mutex] is acquired.
     */
    fun addDownstreamLocked(downstream: Schedulable) {
        downstreamSet.add(downstream)
    }

    final override fun removeDownstream(downstream: Schedulable) {
        // TODO: return boolean?
        downstreamSet.remove(downstream)
    }

    final override fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable) {
        downstreamSet.remove(downstream)
        val deactivate = downstreamSet.isEmpty()
        if (deactivate) {
            doDeactivate()
        }
    }

    final override fun deactivateIfNeeded() {
        if (downstreamSet.isEmpty()) {
            doDeactivate()
        }
    }

    /** visit this node from the scheduler (push eval) */
    abstract fun visit(logIndent: Int, evalScope: EvalScope)

    /** perform deactivation logic, propagating to all upstream nodes. */
    protected abstract fun doDeactivate()

    final override fun scheduleDeactivationIfNeeded(evalScope: EvalScope) {
        if (downstreamSet.isEmpty()) {
            evalScope.scheduleDeactivation(this)
        }
    }

    fun adjustDirectUpstream(scheduler: Scheduler, oldDepth: Int, newDepth: Int) {

        if (depthTracker.addDirectUpstream(oldDepth, newDepth)) {
            depthTracker.schedule(scheduler, this)
        }
    }

    fun moveIndirectUpstreamToDirect(
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectRoots: Set<MuxDeferredNode<*, *, *>>,
        newDepth: Int,
    ) {
        if (
            depthTracker.addDirectUpstream(oldDepth = null, newDepth) or
                depthTracker.removeIndirectUpstream(depth = oldIndirectDepth) or
                depthTracker.updateIndirectRoots(removals = oldIndirectRoots)
        ) {
            depthTracker.schedule(scheduler, this)
        }
    }

    fun adjustIndirectUpstream(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *, *>>,
        additions: Set<MuxDeferredNode<*, *, *>>,
    ) {
        if (
            depthTracker.addIndirectUpstream(oldDepth, newDepth) or
                depthTracker.updateIndirectRoots(
                    additions,
                    removals,
                    butNot = this as? MuxDeferredNode<*, *, *>,
                )
        ) {
            depthTracker.schedule(scheduler, this)
        }
    }

    fun moveDirectUpstreamToIndirect(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *, *>>,
    ) {
        if (
            depthTracker.addIndirectUpstream(oldDepth = null, newDepth) or
                depthTracker.removeDirectUpstream(oldDepth) or
                depthTracker.updateIndirectRoots(
                    additions = newIndirectSet,
                    butNot = this as? MuxDeferredNode<*, *, *>,
                )
        ) {
            depthTracker.schedule(scheduler, this)
        }
    }

    fun removeDirectUpstream(scheduler: Scheduler, depth: Int, key: K) {
        switchedIn.remove(key)
        if (depthTracker.removeDirectUpstream(depth)) {
            depthTracker.schedule(scheduler, this)
        }
    }

    fun removeIndirectUpstream(
        scheduler: Scheduler,
        oldDepth: Int,
        indirectSet: Set<MuxDeferredNode<*, *, *>>,
        key: K,
    ) {
        switchedIn.remove(key)
        if (
            depthTracker.removeIndirectUpstream(oldDepth) or
                depthTracker.updateIndirectRoots(removals = indirectSet)
        ) {
            depthTracker.schedule(scheduler, this)
        }
    }

    fun visitCompact(scheduler: Scheduler) {
        if (depthTracker.isDirty()) {
            depthTracker.applyChanges(scheduler, downstreamSet, this@MuxNode)
        }
    }

    fun schedule(evalScope: EvalScope) {
        // TODO: Potential optimization
        //  Detect if this node is guaranteed to have a single upstream within this transaction,
        //  then bypass scheduling it. Instead immediately schedule its downstream and treat this
        //  MuxNode as a Pull (effectively making it a mapCheap).
        depthTracker.schedule(evalScope.scheduler, this)
    }

    /** An input branch of a mux node, associated with a key. */
    inner class BranchNode(val key: K) : SchedulableNode {

        val schedulable = Schedulable.N(this)

        lateinit var upstream: NodeConnection<V>

        override fun schedule(logIndent: Int, evalScope: EvalScope) {
            logDuration(logIndent, "MuxBranchNode.schedule") {
                if (this@MuxNode is MuxPromptNode && this@MuxNode.name != null) {
                    logLn("[${this@MuxNode}] scheduling $key")
                }
                upstreamData[key] = upstream.directUpstream
                this@MuxNode.schedule(evalScope)
            }
        }

        override fun adjustDirectUpstream(scheduler: Scheduler, oldDepth: Int, newDepth: Int) {
            this@MuxNode.adjustDirectUpstream(scheduler, oldDepth, newDepth)
        }

        override fun moveIndirectUpstreamToDirect(
            scheduler: Scheduler,
            oldIndirectDepth: Int,
            oldIndirectSet: Set<MuxDeferredNode<*, *, *>>,
            newDirectDepth: Int,
        ) {
            this@MuxNode.moveIndirectUpstreamToDirect(
                scheduler,
                oldIndirectDepth,
                oldIndirectSet,
                newDirectDepth,
            )
        }

        override fun adjustIndirectUpstream(
            scheduler: Scheduler,
            oldDepth: Int,
            newDepth: Int,
            removals: Set<MuxDeferredNode<*, *, *>>,
            additions: Set<MuxDeferredNode<*, *, *>>,
        ) {
            this@MuxNode.adjustIndirectUpstream(scheduler, oldDepth, newDepth, removals, additions)
        }

        override fun moveDirectUpstreamToIndirect(
            scheduler: Scheduler,
            oldDirectDepth: Int,
            newIndirectDepth: Int,
            newIndirectSet: Set<MuxDeferredNode<*, *, *>>,
        ) {
            this@MuxNode.moveDirectUpstreamToIndirect(
                scheduler,
                oldDirectDepth,
                newIndirectDepth,
                newIndirectSet,
            )
        }

        override fun removeDirectUpstream(scheduler: Scheduler, depth: Int) {
            removeDirectUpstream(scheduler, depth, key)
        }

        override fun removeIndirectUpstream(
            scheduler: Scheduler,
            depth: Int,
            indirectSet: Set<MuxDeferredNode<*, *, *>>,
        ) {
            removeIndirectUpstream(scheduler, depth, indirectSet, key)
        }

        override fun toString(): String = "MuxBranchNode(key=$key, mux=${this@MuxNode})"
    }
}

internal typealias BranchNode<W, K, V> = MuxNode<W, K, V>.BranchNode

/** Tracks lifecycle of MuxNode in the network. Essentially a mutable ref for MuxLifecycleState. */
internal class MuxLifecycle<W, K, V>(var lifecycleState: MuxLifecycleState<W, K, V>) :
    EventsImpl<MuxResult<W, K, V>> {

    override fun toString(): String = "MuxLifecycle[$hashString][$lifecycleState]"

    override fun activate(
        evalScope: EvalScope,
        downstream: Schedulable,
    ): ActivationResult<MuxResult<W, K, V>>? =
        when (val state = lifecycleState) {
            is MuxLifecycleState.Dead -> {
                null
            }
            is MuxLifecycleState.Active -> {
                state.node.addDownstreamLocked(downstream)
                ActivationResult(
                    connection = NodeConnection(state.node, state.node),
                    needsEval = state.node.hasCurrentValueLocked(evalScope),
                )
            }
            is MuxLifecycleState.Inactive -> {
                state.spec
                    .activate(evalScope, this@MuxLifecycle)
                    .also { node ->
                        lifecycleState =
                            if (node == null) {
                                MuxLifecycleState.Dead
                            } else {
                                MuxLifecycleState.Active(node.first)
                            }
                    }
                    ?.let { (node, postActivate) ->
                        postActivate?.invoke()
                        node.addDownstreamLocked(downstream)
                        ActivationResult(connection = NodeConnection(node, node), needsEval = false)
                    }
            }
        }
}

internal sealed interface MuxLifecycleState<out W, out K, out V> {
    class Inactive<W, K, V>(val spec: MuxActivator<W, K, V>) : MuxLifecycleState<W, K, V> {
        override fun toString(): String = "Inactive"
    }

    class Active<W, K, V>(val node: MuxNode<W, K, V>) : MuxLifecycleState<W, K, V> {
        override fun toString(): String = "Active(node=$node)"
    }

    data object Dead : MuxLifecycleState<Nothing, Nothing, Nothing>
}

internal interface MuxActivator<W, K, V> {
    fun activate(
        evalScope: EvalScope,
        lifecycle: MuxLifecycle<W, K, V>,
    ): Pair<MuxNode<W, K, V>, (() -> Unit)?>?
}

internal inline fun <W, K, V> MuxLifecycle(
    onSubscribe: MuxActivator<W, K, V>
): EventsImpl<MuxResult<W, K, V>> = MuxLifecycle(MuxLifecycleState.Inactive(onSubscribe))

internal fun <K, V> EventsImpl<MuxResult<MapHolder.W, K, V>>.awaitValues(): EventsImpl<Map<K, V>> =
    mapImpl({ this@awaitValues }) { results, logIndent ->
        results.asMapHolder().unwrapped.mapValues { it.value.getPushEvent(logIndent, this) }
    }

// activation logic

internal fun <W, K, V> MuxNode<W, K, V>.initializeUpstream(
    evalScope: EvalScope,
    getStorage: EvalScope.() -> Iterable<Map.Entry<K, EventsImpl<V>>>,
    storeFactory: MutableMapK.Factory<W, K>,
) {
    val storage = getStorage(evalScope)
    val initUpstream = buildList {
        storage.forEach { (key, events) ->
            val branchNode = BranchNode(key)
            add(
                events.activate(evalScope, branchNode.schedulable)?.let { (conn, needsEval) ->
                    Triple(
                        key,
                        branchNode.apply { upstream = conn },
                        if (needsEval) conn.directUpstream else null,
                    )
                }
            )
        }
    }
    switchedIn = storeFactory.create(initUpstream.size)
    upstreamData = storeFactory.create(initUpstream.size)
    for (triple in initUpstream) {
        triple?.let { (key, branch, upstream) ->
            switchedIn[key] = branch
            upstream?.let { upstreamData[key] = upstream }
        }
    }
}

internal fun <W, K, V> MuxNode<W, K, V>.initializeDepth() {
    switchedIn.forEach { (_, branch) ->
        val conn = branch.upstream
        if (conn.depthTracker.snapshotIsDirect) {
            depthTracker.addDirectUpstream(
                oldDepth = null,
                newDepth = conn.depthTracker.snapshotDirectDepth,
            )
        } else {
            depthTracker.addIndirectUpstream(
                oldDepth = null,
                newDepth = conn.depthTracker.snapshotIndirectDepth,
            )
            depthTracker.updateIndirectRoots(
                additions = conn.depthTracker.snapshotIndirectRoots,
                butNot = null,
            )
        }
    }
}
