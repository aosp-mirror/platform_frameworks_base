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

import com.android.systemui.kairos.internal.store.MutableMapK
import com.android.systemui.kairos.internal.store.SingletonMapK
import com.android.systemui.kairos.internal.store.singleOf
import com.android.systemui.kairos.internal.util.Key
import com.android.systemui.kairos.internal.util.launchImmediate
import com.android.systemui.kairos.internal.util.mapParallel
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.None
import com.android.systemui.kairos.util.just
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private typealias MuxPromptMovingResult<W, K, V> = Pair<MuxResult<W, K, V>, MuxResult<W, K, V>?>

internal class MuxPromptMovingNode<W, K, V>(
    lifecycle: MuxLifecycle<MuxPromptMovingResult<W, K, V>>,
    private val spec: MuxActivator<MuxPromptMovingResult<W, K, V>>,
    factory: MutableMapK.Factory<W, K>,
) :
    MuxNode<W, K, V, MuxPromptMovingResult<W, K, V>>(lifecycle, factory),
    Key<MuxPromptMovingResult<W, K, V>> {

    @Volatile var patchData: Iterable<Map.Entry<K, Maybe<TFlowImpl<V>>>>? = null
    @Volatile var patches: PatchNode? = null

    @Volatile private var reEval: MuxPromptMovingResult<W, K, V>? = null

    override suspend fun visit(evalScope: EvalScope) {
        val preSwitchNotEmpty = upstreamData.isNotEmpty()
        val preSwitchResults: MuxResult<W, K, V> = upstreamData.readOnlyCopy()
        upstreamData.clear()

        val patch: Iterable<Map.Entry<K, Maybe<TFlowImpl<V>>>>? = patchData
        patchData = null

        val (reschedule, evalResult) =
            reEval?.let { false to it }
                ?: if (preSwitchNotEmpty || patch != null) {
                    doEval(preSwitchNotEmpty, preSwitchResults, patch, evalScope)
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
            schedule(evalScope)
        } else {
            val compactDownstream = depthTracker.isDirty()
            if (evalResult != null || compactDownstream) {
                coroutineScope {
                    mutex.withLock {
                        if (compactDownstream) {
                            adjustDownstreamDepths(evalScope, coroutineScope = this)
                        }
                        if (evalResult != null) {
                            epoch = evalScope.epoch
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
        preSwitchNotEmpty: Boolean,
        preSwitchResults: MuxResult<W, K, V>,
        patch: Iterable<Map.Entry<K, Maybe<TFlowImpl<V>>>>?,
        evalScope: EvalScope,
    ): Pair<Boolean, MuxPromptMovingResult<W, K, V>?> {
        val newlySwitchedIn: MuxResult<W, K, V>? =
            patch?.let {
                // We have a patch, process additions/updates and removals
                val adds = mutableListOf<Pair<K, TFlowImpl<V>>>()
                val removes = mutableListOf<K>()
                patch.forEach { (k, newUpstream) ->
                    when (newUpstream) {
                        is Just -> adds.add(k to newUpstream.value)
                        None -> removes.add(k)
                    }
                }

                val additionsAndUpdates = mutableListOf<Pair<K, PullNode<V>>>()
                val severed = mutableListOf<NodeConnection<*>>()

                coroutineScope {
                    // remove and sever
                    removes.forEach { k ->
                        switchedIn.remove(k)?.let { branchNode: BranchNode ->
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
                            val branchNode = BranchNode(k)
                            k to
                                newUpstream.activate(evalScope, branchNode.schedulable)?.let {
                                    (conn, _) ->
                                    branchNode.apply { upstream = conn }
                                }
                        }
                        .forEach { (k, newBranch: BranchNode?) ->
                            // remove old and sever, if present
                            switchedIn.remove(k)?.let { oldBranch: BranchNode ->
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
                                additionsAndUpdates.add(k to newBranch.upstream.directUpstream)
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

                val resultStore = storeFactory.create<PullNode<V>>(additionsAndUpdates.size)
                for ((k, node) in additionsAndUpdates) {
                    resultStore[k] = node
                }
                resultStore.takeIf { it.isNotEmpty() }?.asReadOnly()
            }

        return if (preSwitchNotEmpty || newlySwitchedIn != null) {
            (newlySwitchedIn != null) to (preSwitchResults to newlySwitchedIn)
        } else {
            false to null
        }
    }

    private fun adjustDownstreamDepths(evalScope: EvalScope, coroutineScope: CoroutineScope) {
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

    override suspend fun getPushEvent(evalScope: EvalScope): MuxPromptMovingResult<W, K, V> =
        evalScope.getCurrentValue(key = this)

    override suspend fun doDeactivate() {
        // Update lifecycle
        lifecycle.mutex.withLock {
            if (lifecycle.lifecycleState !is MuxLifecycleState.Active) return@doDeactivate
            lifecycle.lifecycleState = MuxLifecycleState.Inactive(spec)
        }
        // Process branch nodes
        switchedIn.forEach { (_, branchNode) ->
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
        indirectSet: Set<MuxDeferredNode<*, *, *>>,
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

    inner class PatchNode : SchedulableNode {

        val schedulable = Schedulable.N(this)

        lateinit var upstream: NodeConnection<Iterable<Map.Entry<K, Maybe<TFlowImpl<V>>>>>

        override suspend fun schedule(evalScope: EvalScope) {
            patchData = upstream.getPushEvent(evalScope)
            this@MuxPromptMovingNode.schedule(evalScope)
        }

        override suspend fun adjustDirectUpstream(
            scheduler: Scheduler,
            oldDepth: Int,
            newDepth: Int,
        ) {
            this@MuxPromptMovingNode.adjustDirectUpstream(scheduler, oldDepth, newDepth)
        }

        override suspend fun moveIndirectUpstreamToDirect(
            scheduler: Scheduler,
            oldIndirectDepth: Int,
            oldIndirectSet: Set<MuxDeferredNode<*, *, *>>,
            newDirectDepth: Int,
        ) {
            this@MuxPromptMovingNode.moveIndirectUpstreamToDirect(
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
            removals: Set<MuxDeferredNode<*, *, *>>,
            additions: Set<MuxDeferredNode<*, *, *>>,
        ) {
            this@MuxPromptMovingNode.adjustIndirectUpstream(
                scheduler,
                oldDepth,
                newDepth,
                removals,
                additions,
            )
        }

        override suspend fun moveDirectUpstreamToIndirect(
            scheduler: Scheduler,
            oldDirectDepth: Int,
            newIndirectDepth: Int,
            newIndirectSet: Set<MuxDeferredNode<*, *, *>>,
        ) {
            this@MuxPromptMovingNode.moveDirectUpstreamToIndirect(
                scheduler,
                oldDirectDepth,
                newIndirectDepth,
                newIndirectSet,
            )
        }

        override suspend fun removeDirectUpstream(scheduler: Scheduler, depth: Int) {
            this@MuxPromptMovingNode.removeDirectPatchNode(scheduler, depth)
        }

        override suspend fun removeIndirectUpstream(
            scheduler: Scheduler,
            depth: Int,
            indirectSet: Set<MuxDeferredNode<*, *, *>>,
        ) {
            this@MuxPromptMovingNode.removeIndirectPatchNode(scheduler, depth, indirectSet)
        }
    }
}

internal class MuxPromptEvalNode<W, K, V>(
    private val movingNode: PullNode<MuxPromptMovingResult<W, K, V>>,
    private val factory: MutableMapK.Factory<W, K>,
) : PullNode<MuxResult<W, K, V>> {
    override suspend fun getPushEvent(evalScope: EvalScope): MuxResult<W, K, V> =
        movingNode.getPushEvent(evalScope).let { (preSwitchResults, newlySwitchedIn) ->
            newlySwitchedIn?.let {
                factory
                    .create(preSwitchResults)
                    .also { store ->
                        newlySwitchedIn.forEach { k, pullNode -> store[k] = pullNode }
                    }
                    .asReadOnly()
            } ?: preSwitchResults
        }
}

internal inline fun <A> switchPromptImplSingle(
    crossinline getStorage: suspend EvalScope.() -> TFlowImpl<A>,
    crossinline getPatches: suspend EvalScope.() -> TFlowImpl<TFlowImpl<A>>,
): TFlowImpl<A> =
    mapImpl({
        switchPromptImpl(
            getStorage = { singleOf(getStorage()).asIterable() },
            getPatches = {
                mapImpl(getPatches) { newFlow -> singleOf(just(newFlow)).asIterable() }
            },
            storeFactory = SingletonMapK.Factory(),
        )
    }) { map ->
        map.getValue(Unit).getPushEvent(this)
    }

internal fun <W, K, V> switchPromptImpl(
    getStorage: suspend EvalScope.() -> Iterable<Map.Entry<K, TFlowImpl<V>>>,
    getPatches: suspend EvalScope.() -> TFlowImpl<Iterable<Map.Entry<K, Maybe<TFlowImpl<V>>>>>,
    storeFactory: MutableMapK.Factory<W, K>,
): TFlowImpl<MuxResult<W, K, V>> {
    val moving = MuxLifecycle(MuxPromptActivator(getStorage, storeFactory, getPatches))
    val eval = TFlowCheap { downstream ->
        moving.activate(evalScope = this, downstream)?.let { (connection, needsEval) ->
            val evalNode = MuxPromptEvalNode(connection.directUpstream, storeFactory)
            ActivationResult(
                connection = NodeConnection(evalNode, connection.schedulerUpstream),
                needsEval = needsEval,
            )
        }
    }
    return eval.cached()
}

private class MuxPromptActivator<W, K, V>(
    private val getStorage: suspend EvalScope.() -> Iterable<Map.Entry<K, TFlowImpl<V>>>,
    private val storeFactory: MutableMapK.Factory<W, K>,
    private val getPatches:
        suspend EvalScope.() -> TFlowImpl<Iterable<Map.Entry<K, Maybe<TFlowImpl<V>>>>>,
) : MuxActivator<MuxPromptMovingResult<W, K, V>> {
    override suspend fun activate(
        evalScope: EvalScope,
        lifecycle: MuxLifecycle<MuxPromptMovingResult<W, K, V>>,
    ): MuxNode<W, *, *, MuxPromptMovingResult<W, K, V>>? {
        // Initialize mux node and switched-in connections.
        val movingNode =
            MuxPromptMovingNode(lifecycle, this, storeFactory).apply {
                coroutineScope {
                    launch { initializeUpstream(evalScope, getStorage, storeFactory) }
                    // Setup patches connection
                    val patchNode = PatchNode()
                    getPatches(evalScope)
                        .activate(evalScope = evalScope, downstream = patchNode.schedulable)
                        ?.let { (conn, needsEval) ->
                            patchNode.upstream = conn
                            patches = patchNode
                            if (needsEval) {
                                patchData = conn.getPushEvent(evalScope)
                            }
                        }
                }
                // Update depth based on all initial switched-in nodes.
                initializeDepth()
                // Update depth based on patches node.
                patches?.upstream?.let { conn ->
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
                // Reset all depth adjustments, since no downstream has been notified
                depthTracker.reset()
            }

        // Schedule for evaluation if any switched-in nodes or the patches node have
        // already emitted within this transaction.
        if (movingNode.patchData != null || movingNode.upstreamData.isNotEmpty()) {
            movingNode.schedule(evalScope)
        }

        return movingNode.takeUnless { it.patches == null && it.switchedIn.isEmpty() }
    }
}
