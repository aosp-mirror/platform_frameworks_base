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

import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.flatMap
import com.android.systemui.kairos.util.getMaybe
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DemuxNode<K, A>(
    private val branchNodeByKey: ConcurrentHashMap<K, DemuxBranchNode<K, A>>,
    val lifecycle: DemuxLifecycle<K, A>,
    private val spec: DemuxActivator<K, A>,
) : SchedulableNode {

    val schedulable = Schedulable.N(this)

    inline val mutex
        get() = lifecycle.mutex

    lateinit var upstreamConnection: NodeConnection<Map<K, A>>

    fun getAndMaybeAddDownstream(key: K): DemuxBranchNode<K, A> =
        branchNodeByKey.getOrPut(key) { DemuxBranchNode(key, this) }

    override suspend fun schedule(evalScope: EvalScope) {
        val upstreamResult = upstreamConnection.getPushEvent(evalScope)
        if (upstreamResult is Just) {
            coroutineScope {
                val outerScope = this
                mutex.withLock {
                    coroutineScope {
                        for ((key, _) in upstreamResult.value) {
                            launch {
                                branchNodeByKey[key]?.let { branch ->
                                    outerScope.launch { branch.schedule(evalScope) }
                                }
                            }
                        }
                    }
                }
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
}

internal class DemuxBranchNode<K, A>(val key: K, private val demuxNode: DemuxNode<K, A>) :
    PushNode<A> {

    private val mutex = Mutex()

    val downstreamSet = DownstreamSet()

    override val depthTracker: DepthTracker
        get() = demuxNode.upstreamConnection.depthTracker

    override suspend fun hasCurrentValue(transactionStore: TransactionStore): Boolean =
        demuxNode.upstreamConnection.hasCurrentValue(transactionStore)

    override suspend fun getPushEvent(evalScope: EvalScope): Maybe<A> =
        demuxNode.upstreamConnection.getPushEvent(evalScope).flatMap { it.getMaybe(key) }

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
            demuxNode.removeDownstreamAndDeactivateIfNeeded(key)
        }
    }

    override suspend fun deactivateIfNeeded() {
        if (mutex.withLock { downstreamSet.isEmpty() }) {
            demuxNode.removeDownstreamAndDeactivateIfNeeded(key)
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

internal fun <K, A> DemuxImpl(
    upstream: suspend EvalScope.() -> TFlowImpl<Map<K, A>>,
    numKeys: Int?,
): DemuxImpl<K, A> =
    DemuxImpl(
        DemuxLifecycle(
            object : DemuxActivator<K, A> {
                override suspend fun activate(
                    evalScope: EvalScope,
                    lifecycle: DemuxLifecycle<K, A>,
                ): Pair<DemuxNode<K, A>, Boolean>? {
                    val dmux = DemuxNode(ConcurrentHashMap(numKeys ?: 16), lifecycle, this)
                    return upstream
                        .invoke(evalScope)
                        .activate(evalScope, downstream = dmux.schedulable)
                        ?.let { (conn, needsEval) ->
                            dmux.apply { upstreamConnection = conn } to needsEval
                        }
                }
            }
        )
    )

internal class DemuxImpl<in K, out A>(private val dmux: DemuxLifecycle<K, A>) {
    fun eventsForKey(key: K): TFlowImpl<A> = TFlowCheap { downstream ->
        dmux.activate(evalScope = this, key)?.let { (branchNode, needsEval) ->
            branchNode.addDownstream(downstream)
            val branchNeedsEval = needsEval && branchNode.getPushEvent(evalScope = this) is Just
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

    suspend fun activate(evalScope: EvalScope, key: K): Pair<DemuxBranchNode<K, A>, Boolean>? =
        coroutineScope {
            mutex
                .withLock {
                    when (val state = lifecycleState) {
                        is DemuxLifecycleState.Dead -> null
                        is DemuxLifecycleState.Active ->
                            state.node.getAndMaybeAddDownstream(key) to
                                async {
                                    state.node.upstreamConnection.hasCurrentValue(
                                        evalScope.transactionStore
                                    )
                                }
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
                                    node.getAndMaybeAddDownstream(key) to
                                        CompletableDeferred(needsEval)
                                }
                        }
                    }
                }
                ?.let { (branch, result) -> branch to result.await() }
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

internal interface DemuxActivator<K, A> {
    suspend fun activate(
        evalScope: EvalScope,
        lifecycle: DemuxLifecycle<K, A>,
    ): Pair<DemuxNode<K, A>, Boolean>?
}

internal inline fun <K, A> DemuxLifecycle(onSubscribe: DemuxActivator<K, A>) =
    DemuxLifecycle(DemuxLifecycleState.Inactive(onSubscribe))
