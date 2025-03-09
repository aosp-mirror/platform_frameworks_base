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

import com.android.systemui.kairos.internal.util.Key
import com.android.systemui.kairos.internal.util.launchImmediate
import com.android.systemui.kairos.internal.util.mapParallel
import com.android.systemui.kairos.internal.util.mapValuesNotNullParallelTo
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Left
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.None
import com.android.systemui.kairos.util.Right
import com.android.systemui.kairos.util.filterJust
import com.android.systemui.kairos.util.map
import com.android.systemui.kairos.util.partitionEithers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal class MuxPromptMovingNode<K : Any, V>(
    lifecycle: MuxLifecycle<Pair<Map<K, V>, Map<K, PullNode<V>>?>>,
    private val spec: MuxActivator<Pair<Map<K, V>, Map<K, PullNode<V>>?>>,
) :
    MuxNode<K, V, Pair<Map<K, V>, Map<K, PullNode<V>>?>>(lifecycle),
    Key<Pair<Map<K, V>, Map<K, PullNode<V>>?>> {

    @Volatile var patchData: Map<K, Maybe<TFlowImpl<V>>>? = null
    @Volatile var patches: MuxPromptPatchNode<K, V>? = null

    @Volatile private var reEval: Pair<Map<K, V>, Map<K, PullNode<V>>?>? = null

    override fun hasCurrentValueLocked(transactionStore: TransactionStore): Boolean =
        transactionStore.contains(this)

    override suspend fun hasCurrentValue(transactionStore: TransactionStore): Boolean =
        mutex.withLock { hasCurrentValueLocked(transactionStore) }

    override suspend fun visit(evalScope: EvalScope) {
        val preSwitchResults: Map<K, V> = upstreamData.toMap()
        upstreamData.clear()

        val patch: Map<K, Maybe<TFlowImpl<V>>>? = patchData
        patchData = null

        val (reschedule, evalResult) =
            reEval?.let { false to it }
                ?: if (preSwitchResults.isNotEmpty() || patch?.isNotEmpty() == true) {
                    doEval(preSwitchResults, patch, evalScope)
                } else {
                    false to null
                }
        reEval = null

        if (reschedule || depthTracker.dirty_depthIncreased()) {
            reEval = evalResult
            // Can't schedule downstream yet, need to compact first
            if (depthTracker.dirty_depthIncreased()) {
                depthTracker.schedule(evalScope.compactor, node = this)
            }
            evalScope.schedule(this)
        } else {
            val compactDownstream = depthTracker.isDirty()
            if (evalResult != null || compactDownstream) {
                coroutineScope {
                    mutex.withLock {
                        if (compactDownstream) {
                            adjustDownstreamDepths(evalScope, coroutineScope = this)
                        }
                        if (evalResult != null) {
                            evalScope.setResult(this@MuxPromptMovingNode, evalResult)
                            if (!scheduleAll(downstreamSet, evalScope)) {
                                evalScope.scheduleDeactivation(this@MuxPromptMovingNode)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun doEval(
        preSwitchResults: Map<K, V>,
        patch: Map<K, Maybe<TFlowImpl<V>>>?,
        evalScope: EvalScope,
    ): Pair<Boolean, Pair<Map<K, V>, Map<K, PullNode<V>>?>?> {
        val newlySwitchedIn: Map<K, PullNode<V>>? =
            patch?.let {
                // We have a patch, process additions/updates and removals
                val (adds, removes) =
                    patch
                        .asSequence()
                        .map { (k, newUpstream: Maybe<TFlowImpl<V>>) ->
                            when (newUpstream) {
                                is Just -> Left(k to newUpstream.value)
                                None -> Right(k)
                            }
                        }
                        .partitionEithers()

                val additionsAndUpdates = mutableMapOf<K, PullNode<V>>()
                val severed = mutableListOf<NodeConnection<*>>()

                coroutineScope {
                    // remove and sever
                    removes.forEach { k ->
                        switchedIn.remove(k)?.let { branchNode: MuxBranchNode<K, V> ->
                            val conn: NodeConnection<V> = branchNode.upstream
                            severed.add(conn)
                            launchImmediate {
                                conn.removeDownstream(downstream = branchNode.schedulable)
                            }
                            depthTracker.removeDirectUpstream(conn.depthTracker.snapshotDirectDepth)
                        }
                    }

                    // add or replace
                    adds
                        .mapParallel { (k, newUpstream: TFlowImpl<V>) ->
                            val branchNode = MuxBranchNode(this@MuxPromptMovingNode, k)
                            k to
                                newUpstream.activate(evalScope, branchNode.schedulable)?.let {
                                    (conn, _) ->
                                    branchNode.apply { upstream = conn }
                                }
                        }
                        .forEach { (k, newBranch: MuxBranchNode<K, V>?) ->
                            // remove old and sever, if present
                            switchedIn.remove(k)?.let { oldBranch: MuxBranchNode<K, V> ->
                                val conn: NodeConnection<V> = oldBranch.upstream
                                severed.add(conn)
                                launchImmediate {
                                    conn.removeDownstream(downstream = oldBranch.schedulable)
                                }
                                depthTracker.removeDirectUpstream(
                                    conn.depthTracker.snapshotDirectDepth
                                )
                            }

                            // add new
                            newBranch?.let {
                                switchedIn[k] = newBranch
                                additionsAndUpdates[k] = newBranch.upstream.directUpstream
                                val branchDepthTracker = newBranch.upstream.depthTracker
                                if (branchDepthTracker.snapshotIsDirect) {
                                    depthTracker.addDirectUpstream(
                                        oldDepth = null,
                                        newDepth = branchDepthTracker.snapshotDirectDepth,
                                    )
                                } else {
                                    depthTracker.addIndirectUpstream(
                                        oldDepth = null,
                                        newDepth = branchDepthTracker.snapshotIndirectDepth,
                                    )
                                    depthTracker.updateIndirectRoots(
                                        additions = branchDepthTracker.snapshotIndirectRoots,
                                        butNot = null,
                                    )
                                }
                            }
                        }
                }

                coroutineScope {
                    for (severedNode in severed) {
                        launch { severedNode.scheduleDeactivationIfNeeded(evalScope) }
                    }
                }

                additionsAndUpdates.takeIf { it.isNotEmpty() }
            }

        return if (preSwitchResults.isNotEmpty() || newlySwitchedIn != null) {
            (newlySwitchedIn != null) to (preSwitchResults to newlySwitchedIn)
        } else {
            false to null
        }
    }

    private suspend fun adjustDownstreamDepths(
        evalScope: EvalScope,
        coroutineScope: CoroutineScope,
    ) {
        if (depthTracker.dirty_depthIncreased()) {
            // schedule downstream nodes on the compaction scheduler; this scheduler is drained at
            // the end of this eval depth, so that all depth increases are applied before we advance
            // the eval step
            depthTracker.schedule(evalScope.compactor, node = this@MuxPromptMovingNode)
        } else if (depthTracker.isDirty()) {
            // schedule downstream nodes on the eval scheduler; this is more efficient and is only
            // safe if the depth hasn't increased
            depthTracker.applyChanges(
                coroutineScope,
                evalScope.scheduler,
                downstreamSet,
                muxNode = this@MuxPromptMovingNode,
            )
        }
    }

    override suspend fun getPushEvent(
        evalScope: EvalScope
    ): Maybe<Pair<Map<K, V>, Map<K, PullNode<V>>?>> = evalScope.getCurrentValue(key = this)

    override suspend fun doDeactivate() {
        // Update lifecycle
        lifecycle.mutex.withLock {
            if (lifecycle.lifecycleState !is MuxLifecycleState.Active) return@doDeactivate
            lifecycle.lifecycleState = MuxLifecycleState.Inactive(spec)
        }
        // Process branch nodes
        switchedIn.values.forEach { branchNode ->
            branchNode.upstream.removeDownstreamAndDeactivateIfNeeded(
                downstream = branchNode.schedulable
            )
        }
        // Process patch node
        patches?.let { patches ->
            patches.upstream.removeDownstreamAndDeactivateIfNeeded(downstream = patches.schedulable)
        }
    }

    suspend fun removeIndirectPatchNode(
        scheduler: Scheduler,
        oldDepth: Int,
        indirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        mutex.withLock {
            patches = null
            if (
                depthTracker.removeIndirectUpstream(oldDepth) or
                    depthTracker.updateIndirectRoots(removals = indirectSet)
            ) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun removeDirectPatchNode(scheduler: Scheduler, depth: Int) {
        mutex.withLock {
            patches = null
            if (depthTracker.removeDirectUpstream(depth)) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }
}

internal class MuxPromptEvalNode<K, V>(
    private val movingNode: PullNode<Pair<Map<K, V>, Map<K, PullNode<V>>?>>
) : PullNode<Map<K, V>> {
    override suspend fun getPushEvent(evalScope: EvalScope): Maybe<Map<K, V>> =
        movingNode.getPushEvent(evalScope).map { (preSwitchResults, newlySwitchedIn) ->
            coroutineScope {
                newlySwitchedIn
                    ?.map { (k, v) -> async { v.getPushEvent(evalScope).map { k to it } } }
                    ?.awaitAll()
                    ?.asSequence()
                    ?.filterJust()
                    ?.toMap(preSwitchResults.toMutableMap()) ?: preSwitchResults
            }
        }
}

// TODO: inner class?
internal class MuxPromptPatchNode<K : Any, V>(private val muxNode: MuxPromptMovingNode<K, V>) :
    SchedulableNode {

    val schedulable = Schedulable.N(this)

    lateinit var upstream: NodeConnection<Map<K, Maybe<TFlowImpl<V>>>>

    override suspend fun schedule(evalScope: EvalScope) {
        val upstreamResult = upstream.getPushEvent(evalScope)
        if (upstreamResult is Just) {
            muxNode.patchData = upstreamResult.value
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
        muxNode.removeDirectPatchNode(scheduler, depth)
    }

    override suspend fun removeIndirectUpstream(
        scheduler: Scheduler,
        depth: Int,
        indirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        muxNode.removeIndirectPatchNode(scheduler, depth, indirectSet)
    }
}

internal fun <K : Any, A> switchPromptImpl(
    getStorage: suspend EvalScope.() -> Map<K, TFlowImpl<A>>,
    getPatches: suspend EvalScope.() -> TFlowImpl<Map<K, Maybe<TFlowImpl<A>>>>,
): TFlowImpl<Map<K, A>> {
    val moving =
        MuxLifecycle(
            object : MuxActivator<Pair<Map<K, A>, Map<K, PullNode<A>>?>> {
                override suspend fun activate(
                    evalScope: EvalScope,
                    lifecycle: MuxLifecycle<Pair<Map<K, A>, Map<K, PullNode<A>>?>>,
                ): MuxNode<*, *, Pair<Map<K, A>, Map<K, PullNode<A>>?>>? {
                    val storage: Map<K, TFlowImpl<A>> = getStorage(evalScope)
                    // Initialize mux node and switched-in connections.
                    val movingNode =
                        MuxPromptMovingNode(lifecycle, this).apply {
                            coroutineScope {
                                launch {
                                    storage.mapValuesNotNullParallelTo(switchedIn) { (key, flow) ->
                                        val branchNode = MuxBranchNode(this@apply, key)
                                        flow
                                            .activate(
                                                evalScope = evalScope,
                                                downstream = branchNode.schedulable,
                                            )
                                            ?.let { (conn, needsEval) ->
                                                branchNode
                                                    .apply { upstream = conn }
                                                    .also {
                                                        if (needsEval) {
                                                            val result =
                                                                conn.getPushEvent(evalScope)
                                                            if (result is Just) {
                                                                upstreamData[key] = result.value
                                                            }
                                                        }
                                                    }
                                            }
                                    }
                                }
                                // Setup patches connection
                                val patchNode = MuxPromptPatchNode(this@apply)
                                getPatches(evalScope)
                                    .activate(
                                        evalScope = evalScope,
                                        downstream = patchNode.schedulable,
                                    )
                                    ?.let { (conn, needsEval) ->
                                        patchNode.upstream = conn
                                        patches = patchNode

                                        if (needsEval) {
                                            val result = conn.getPushEvent(evalScope)
                                            if (result is Just) {
                                                patchData = result.value
                                            }
                                        }
                                    }
                            }
                        }
                    // Update depth based on all initial switched-in nodes.
                    movingNode.switchedIn.values.forEach { branch ->
                        val conn = branch.upstream
                        if (conn.depthTracker.snapshotIsDirect) {
                            movingNode.depthTracker.addDirectUpstream(
                                oldDepth = null,
                                newDepth = conn.depthTracker.snapshotDirectDepth,
                            )
                        } else {
                            movingNode.depthTracker.addIndirectUpstream(
                                oldDepth = null,
                                newDepth = conn.depthTracker.snapshotIndirectDepth,
                            )
                            movingNode.depthTracker.updateIndirectRoots(
                                additions = conn.depthTracker.snapshotIndirectRoots,
                                butNot = null,
                            )
                        }
                    }
                    // Update depth based on patches node.
                    movingNode.patches?.upstream?.let { conn ->
                        if (conn.depthTracker.snapshotIsDirect) {
                            movingNode.depthTracker.addDirectUpstream(
                                oldDepth = null,
                                newDepth = conn.depthTracker.snapshotDirectDepth,
                            )
                        } else {
                            movingNode.depthTracker.addIndirectUpstream(
                                oldDepth = null,
                                newDepth = conn.depthTracker.snapshotIndirectDepth,
                            )
                            movingNode.depthTracker.updateIndirectRoots(
                                additions = conn.depthTracker.snapshotIndirectRoots,
                                butNot = null,
                            )
                        }
                    }
                    movingNode.depthTracker.reset()

                    // Schedule for evaluation if any switched-in nodes or the patches node have
                    // already emitted within this transaction.
                    if (movingNode.patchData != null || movingNode.upstreamData.isNotEmpty()) {
                        evalScope.schedule(movingNode)
                    }

                    return movingNode.takeUnless { it.patches == null && it.switchedIn.isEmpty() }
                }
            }
        )

    val eval = TFlowCheap { downstream ->
        moving.activate(evalScope = this, downstream)?.let { (connection, needsEval) ->
            val evalNode = MuxPromptEvalNode(connection.directUpstream)
            ActivationResult(
                connection = NodeConnection(evalNode, connection.schedulerUpstream),
                needsEval = needsEval,
            )
        }
    }
    return eval.cached()
}
