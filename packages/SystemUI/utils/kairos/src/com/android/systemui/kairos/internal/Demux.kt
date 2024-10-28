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

import com.android.systemui.kairos.internal.util.hashString
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DemuxNode<K, A>(
    private val branchNodeByKey: ConcurrentHashMap<K, DemuxNode<K, A>.BranchNode>,
    val lifecycle: DemuxLifecycle<K, A>,
    private val spec: DemuxActivator<K, A>,
) : SchedulableNode {

    val schedulable = Schedulable.N(this)

    inline val mutex
        get() = lifecycle.mutex

    lateinit var upstreamConnection: NodeConnection<Map<K, A>>

    @Volatile private var epoch: Long = Long.MIN_VALUE

    suspend fun hasCurrentValueLocked(evalScope: EvalScope, key: K): Boolean =
        evalScope.epoch == epoch && upstreamConnection.getPushEvent(evalScope).contains(key)

    suspend fun hasCurrentValue(evalScope: EvalScope, key: K): Boolean =
        mutex.withLock { hasCurrentValueLocked(evalScope, key) }

    fun getAndMaybeAddDownstream(key: K): BranchNode =
        branchNodeByKey.getOrPut(key) { BranchNode(key) }

    override suspend fun schedule(evalScope: EvalScope) = coroutineScope {
        val upstreamResult = upstreamConnection.getPushEvent(evalScope)
        mutex.withLock {
            updateEpoch(evalScope)
            for ((key, _) in upstreamResult) {
                branchNodeByKey[key]?.let { branch -> launch { branch.schedule(evalScope) } }
            }
        }
    }

    override suspend fun adjustDirectUpstream(scheduler: Scheduler, oldDepth: Int, newDepth: Int) {
        coroutineScope {
            mutex.withLock {
                for ((_, branchNode) in branchNodeByKey) {
                    branchNode.downstreamSet.adjustDirectUpstream(
                        coroutineScope = this,
                        scheduler,
                        oldDepth,
                        newDepth,
                    )
                }
            }
        }
    }

    override suspend fun moveIndirectUpstreamToDirect(
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectSet: Set<MuxDeferredNode<*, *>>,
        newDirectDepth: Int,
    ) {
        coroutineScope {
            mutex.withLock {
                for ((_, branchNode) in branchNodeByKey) {
                    branchNode.downstreamSet.moveIndirectUpstreamToDirect(
                        coroutineScope = this,
                        scheduler,
                        oldIndirectDepth,
                        oldIndirectSet,
                        newDirectDepth,
                    )
                }
            }
        }
    }

    override suspend fun adjustIndirectUpstream(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *>>,
        additions: Set<MuxDeferredNode<*, *>>,
    ) {
        coroutineScope {
            mutex.withLock {
                for ((_, branchNode) in branchNodeByKey) {
                    branchNode.downstreamSet.adjustIndirectUpstream(
                        coroutineScope = this,
                        scheduler,
                        oldDepth,
                        newDepth,
                        removals,
                        additions,
                    )
                }
            }
        }
    }

    override suspend fun moveDirectUpstreamToIndirect(
        scheduler: Scheduler,
        oldDirectDepth: Int,
        newIndirectDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        coroutineScope {
            mutex.withLock {
                for ((_, branchNode) in branchNodeByKey) {
                    branchNode.downstreamSet.moveDirectUpstreamToIndirect(
                        coroutineScope = this,
                        scheduler,
                        oldDirectDepth,
                        newIndirectDepth,
                        newIndirectSet,
                    )
                }
            }
        }
    }

    override suspend fun removeIndirectUpstream(
        scheduler: Scheduler,
        depth: Int,
        indirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        coroutineScope {
            mutex.withLock {
                lifecycle.lifecycleState = DemuxLifecycleState.Dead
                for ((_, branchNode) in branchNodeByKey) {
                    branchNode.downstreamSet.removeIndirectUpstream(
                        coroutineScope = this,
                        scheduler,
                        depth,
                        indirectSet,
                    )
                }
            }
        }
    }

    override suspend fun removeDirectUpstream(scheduler: Scheduler, depth: Int) {
        coroutineScope {
            mutex.withLock {
                lifecycle.lifecycleState = DemuxLifecycleState.Dead
                for ((_, branchNode) in branchNodeByKey) {
                    branchNode.downstreamSet.removeDirectUpstream(
                        coroutineScope = this,
                        scheduler,
                        depth,
                    )
                }
            }
        }
    }

    suspend fun removeDownstreamAndDeactivateIfNeeded(key: K) {
        val deactivate =
            mutex.withLock {
                branchNodeByKey.remove(key)
                branchNodeByKey.isEmpty()
            }
        if (deactivate) {
            // No need for mutex here; no more concurrent changes to can occur during this phase
            lifecycle.lifecycleState = DemuxLifecycleState.Inactive(spec)
            upstreamConnection.removeDownstreamAndDeactivateIfNeeded(downstream = schedulable)
        }
    }

    fun updateEpoch(evalScope: EvalScope) {
        epoch = evalScope.epoch
    }

    suspend fun getPushEvent(evalScope: EvalScope, key: K): A =
        upstreamConnection.getPushEvent(evalScope).getValue(key)

    inner class BranchNode(val key: K) : PushNode<A> {

        private val mutex = Mutex()

        val downstreamSet = DownstreamSet()

        override val depthTracker: DepthTracker
            get() = upstreamConnection.depthTracker

        override suspend fun hasCurrentValue(evalScope: EvalScope): Boolean =
            hasCurrentValue(evalScope, key)

        override suspend fun getPushEvent(evalScope: EvalScope): A = getPushEvent(evalScope, key)

        override suspend fun addDownstream(downstream: Schedulable) {
            mutex.withLock { downstreamSet.add(downstream) }
        }

        override suspend fun removeDownstream(downstream: Schedulable) {
            mutex.withLock { downstreamSet.remove(downstream) }
        }

        override suspend fun removeDownstreamAndDeactivateIfNeeded(downstream: Schedulable) {
            val canDeactivate =
                mutex.withLock {
                    downstreamSet.remove(downstream)
                    downstreamSet.isEmpty()
                }
            if (canDeactivate) {
                removeDownstreamAndDeactivateIfNeeded(key)
            }
        }

        override suspend fun deactivateIfNeeded() {
            if (mutex.withLock { downstreamSet.isEmpty() }) {
                removeDownstreamAndDeactivateIfNeeded(key)
            }
        }

        override suspend fun scheduleDeactivationIfNeeded(evalScope: EvalScope) {
            if (mutex.withLock { downstreamSet.isEmpty() }) {
                evalScope.scheduleDeactivation(this)
            }
        }

        suspend fun schedule(evalScope: EvalScope) {
            if (!coroutineScope { mutex.withLock { scheduleAll(downstreamSet, evalScope) } }) {
                evalScope.scheduleDeactivation(this)
            }
        }
    }
}

internal fun <K, A> DemuxImpl(
    upstream: suspend EvalScope.() -> TFlowImpl<Map<K, A>>,
    numKeys: Int?,
): DemuxImpl<K, A> =
    DemuxImpl(DemuxLifecycle(DemuxLifecycleState.Inactive(DemuxActivator(numKeys, upstream))))

internal class DemuxActivator<K, A>(
    private val numKeys: Int?,
    private val upstream: suspend EvalScope.() -> TFlowImpl<Map<K, A>>,
) {
    suspend fun activate(
        evalScope: EvalScope,
        lifecycle: DemuxLifecycle<K, A>,
    ): Pair<DemuxNode<K, A>, Set<K>>? {
        val demux = DemuxNode(ConcurrentHashMap(numKeys ?: 16), lifecycle, this)
        return upstream
            .invoke(evalScope)
            .activate(evalScope, downstream = demux.schedulable)
            ?.let { (conn, needsEval) ->
                Pair(
                    demux.apply { upstreamConnection = conn },
                    if (needsEval) {
                        demux.updateEpoch(evalScope)
                        conn.getPushEvent(evalScope).keys
                    } else {
                        emptySet()
                    },
                )
            }
    }
}

internal class DemuxImpl<in K, out A>(private val dmux: DemuxLifecycle<K, A>) {
    fun eventsForKey(key: K): TFlowImpl<A> = TFlowCheap { downstream ->
        dmux.activate(evalScope = this, key)?.let { (branchNode, needsEval) ->
            branchNode.addDownstream(downstream)
            val branchNeedsEval = needsEval && branchNode.hasCurrentValue(evalScope = this)
            ActivationResult(
                connection = NodeConnection(branchNode, branchNode),
                needsEval = branchNeedsEval,
            )
        }
    }
}

internal class DemuxLifecycle<K, A>(@Volatile var lifecycleState: DemuxLifecycleState<K, A>) {
    val mutex = Mutex()

    override fun toString(): String = "TFlowDmuxState[$hashString][$lifecycleState][$mutex]"

    suspend fun activate(evalScope: EvalScope, key: K): Pair<DemuxNode<K, A>.BranchNode, Boolean>? =
        mutex.withLock {
            when (val state = lifecycleState) {
                is DemuxLifecycleState.Dead -> null
                is DemuxLifecycleState.Active ->
                    state.node.getAndMaybeAddDownstream(key) to
                        state.node.hasCurrentValueLocked(evalScope, key)
                is DemuxLifecycleState.Inactive -> {
                    state.spec
                        .activate(evalScope, this@DemuxLifecycle)
                        .also { result ->
                            lifecycleState =
                                if (result == null) {
                                    DemuxLifecycleState.Dead
                                } else {
                                    DemuxLifecycleState.Active(result.first)
                                }
                        }
                        ?.let { (node, needsEval) ->
                            node.getAndMaybeAddDownstream(key) to (key in needsEval)
                        }
                }
            }
        }
}

internal sealed interface DemuxLifecycleState<out K, out A> {
    class Inactive<K, A>(val spec: DemuxActivator<K, A>) : DemuxLifecycleState<K, A> {
        override fun toString(): String = "Inactive"
    }

    class Active<K, A>(val node: DemuxNode<K, A>) : DemuxLifecycleState<K, A> {
        override fun toString(): String = "Active(node=$node)"
    }

    data object Dead : DemuxLifecycleState<Nothing, Nothing>
}
