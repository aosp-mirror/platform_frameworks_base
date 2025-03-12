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

import com.android.systemui.kairos.internal.util.ConcurrentNullableHashMap
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.util.Just
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Base class for muxing nodes, which have a potentially dynamic collection of upstream nodes. */
internal sealed class MuxNode<K : Any, V, Output>(val lifecycle: MuxLifecycle<Output>) :
    PushNode<Output> {

    inline val mutex
        get() = lifecycle.mutex

    // TODO: preserve insertion order?
    val upstreamData = ConcurrentNullableHashMap<K, V>()
    val switchedIn = ConcurrentHashMap<K, MuxBranchNode<K, V>>()
    val downstreamSet: DownstreamSet = DownstreamSet()

    // TODO: inline DepthTracker? would need to be added to PushNode signature
    final override val depthTracker = DepthTracker()

    final override suspend fun addDownstream(downstream: Schedulable) {
        mutex.withLock { addDownstreamLocked(downstream) }
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

    final override suspend fun removeDownstream(downstream: Schedulable) {
        // TODO: return boolean?
        mutex.withLock { downstreamSet.remove(downstream) }
    }

    final override suspend fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable) {
        val deactivate =
            mutex.withLock {
                downstreamSet.remove(downstream)
                downstreamSet.isEmpty()
            }
        if (deactivate) {
            doDeactivate()
        }
    }

    final override suspend fun deactivateIfNeeded() {
        if (mutex.withLock { downstreamSet.isEmpty() }) {
            doDeactivate()
        }
    }

    /** visit this node from the scheduler (push eval) */
    abstract suspend fun visit(evalScope: EvalScope)

    /** perform deactivation logic, propagating to all upstream nodes. */
    protected abstract suspend fun doDeactivate()

    final override suspend fun scheduleDeactivationIfNeeded(evalScope: EvalScope) {
        if (mutex.withLock { downstreamSet.isEmpty() }) {
            evalScope.scheduleDeactivation(this)
        }
    }

    suspend fun adjustDirectUpstream(scheduler: Scheduler, oldDepth: Int, newDepth: Int) {
        mutex.withLock {
            if (depthTracker.addDirectUpstream(oldDepth, newDepth)) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun moveIndirectUpstreamToDirect(
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectRoots: Set<MuxDeferredNode<*, *>>,
        newDepth: Int,
    ) {
        mutex.withLock {
            if (
                depthTracker.addDirectUpstream(oldDepth = null, newDepth) or
                    depthTracker.removeIndirectUpstream(depth = oldIndirectDepth) or
                    depthTracker.updateIndirectRoots(removals = oldIndirectRoots)
            ) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun adjustIndirectUpstream(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *>>,
        additions: Set<MuxDeferredNode<*, *>>,
    ) {
        mutex.withLock {
            if (
                depthTracker.addIndirectUpstream(oldDepth, newDepth) or
                    depthTracker.updateIndirectRoots(
                        additions,
                        removals,
                        butNot = this as? MuxDeferredNode<*, *>,
                    )
            ) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun moveDirectUpstreamToIndirect(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        mutex.withLock {
            if (
                depthTracker.addIndirectUpstream(oldDepth = null, newDepth) or
                    depthTracker.removeDirectUpstream(oldDepth) or
                    depthTracker.updateIndirectRoots(
                        additions = newIndirectSet,
                        butNot = this as? MuxDeferredNode<*, *>,
                    )
            ) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun removeDirectUpstream(scheduler: Scheduler, depth: Int, key: K) {
        mutex.withLock {
            switchedIn.remove(key)
            if (depthTracker.removeDirectUpstream(depth)) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun removeIndirectUpstream(
        scheduler: Scheduler,
        oldDepth: Int,
        indirectSet: Set<MuxDeferredNode<*, *>>,
        key: K,
    ) {
        mutex.withLock {
            switchedIn.remove(key)
            if (
                depthTracker.removeIndirectUpstream(oldDepth) or
                    depthTracker.updateIndirectRoots(removals = indirectSet)
            ) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun visitCompact(scheduler: Scheduler) = coroutineScope {
        if (depthTracker.isDirty()) {
            depthTracker.applyChanges(coroutineScope = this, scheduler, downstreamSet, this@MuxNode)
        }
    }

    abstract fun hasCurrentValueLocked(transactionStore: TransactionStore): Boolean
}

/** An input branch of a mux node, associated with a key. */
internal class MuxBranchNode<K : Any, V>(private val muxNode: MuxNode<K, V, *>, val key: K) :
    SchedulableNode {

    val schedulable = Schedulable.N(this)

    @Volatile lateinit var upstream: NodeConnection<V>

    override suspend fun schedule(evalScope: EvalScope) {
        val upstreamResult = upstream.getPushEvent(evalScope)
        if (upstreamResult is Just) {
            muxNode.upstreamData[key] = upstreamResult.value
            evalScope.schedule(muxNode)
        }
    }

    override suspend fun adjustDirectUpstream(scheduler: Scheduler, oldDepth: Int, newDepth: Int) {
        muxNode.adjustDirectUpstream(scheduler, oldDepth, newDepth)
    }

    override suspend fun moveIndirectUpstreamToDirect(
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectSet: Set<MuxDeferredNode<*, *>>,
        newDirectDepth: Int,
    ) {
        muxNode.moveIndirectUpstreamToDirect(
            scheduler,
            oldIndirectDepth,
            oldIndirectSet,
            newDirectDepth,
        )
    }

    override suspend fun adjustIndirectUpstream(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *>>,
        additions: Set<MuxDeferredNode<*, *>>,
    ) {
        muxNode.adjustIndirectUpstream(scheduler, oldDepth, newDepth, removals, additions)
    }

    override suspend fun moveDirectUpstreamToIndirect(
        scheduler: Scheduler,
        oldDirectDepth: Int,
        newIndirectDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        muxNode.moveDirectUpstreamToIndirect(
            scheduler,
            oldDirectDepth,
            newIndirectDepth,
            newIndirectSet,
        )
    }

    override suspend fun removeDirectUpstream(scheduler: Scheduler, depth: Int) {
        muxNode.removeDirectUpstream(scheduler, depth, key)
    }

    override suspend fun removeIndirectUpstream(
        scheduler: Scheduler,
        depth: Int,
        indirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        muxNode.removeIndirectUpstream(scheduler, depth, indirectSet, key)
    }

    override fun toString(): String = "MuxBranchNode(key=$key, mux=$muxNode)"
}

/** Tracks lifecycle of MuxNode in the network. Essentially a mutable ref for MuxLifecycleState. */
internal class MuxLifecycle<A>(@Volatile var lifecycleState: MuxLifecycleState<A>) : TFlowImpl<A> {
    val mutex = Mutex()

    override fun toString(): String = "TFlowLifecycle[$hashString][$lifecycleState][$mutex]"

    override suspend fun activate(
        evalScope: EvalScope,
        downstream: Schedulable,
    ): ActivationResult<A>? =
        mutex.withLock {
            when (val state = lifecycleState) {
                is MuxLifecycleState.Dead -> null
                is MuxLifecycleState.Active -> {
                    state.node.addDownstreamLocked(downstream)
                    ActivationResult(
                        connection = NodeConnection(state.node, state.node),
                        needsEval = state.node.hasCurrentValueLocked(evalScope.transactionStore),
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
                                    MuxLifecycleState.Active(node)
                                }
                        }
                        ?.let { node ->
                            node.addDownstreamLocked(downstream)
                            ActivationResult(
                                connection = NodeConnection(node, node),
                                needsEval = false,
                            )
                        }
                }
            }
        }
}

internal sealed interface MuxLifecycleState<out A> {
    class Inactive<A>(val spec: MuxActivator<A>) : MuxLifecycleState<A> {
        override fun toString(): String = "Inactive"
    }

    class Active<A>(val node: MuxNode<*, *, A>) : MuxLifecycleState<A> {
        override fun toString(): String = "Active(node=$node)"
    }

    data object Dead : MuxLifecycleState<Nothing>
}

internal interface MuxActivator<A> {
    suspend fun activate(evalScope: EvalScope, lifecycle: MuxLifecycle<A>): MuxNode<*, *, A>?
}

internal inline fun <A> MuxLifecycle(onSubscribe: MuxActivator<A>): TFlowImpl<A> =
    MuxLifecycle(MuxLifecycleState.Inactive(onSubscribe))
