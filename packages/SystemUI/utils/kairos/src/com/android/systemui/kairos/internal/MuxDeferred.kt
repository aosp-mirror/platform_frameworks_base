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
import com.android.systemui.kairos.internal.util.associateByIndexTo
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.internal.util.mapParallel
import com.android.systemui.kairos.internal.util.mapValuesNotNullParallelTo
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Left
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.None
import com.android.systemui.kairos.util.Right
import com.android.systemui.kairos.util.These
import com.android.systemui.kairos.util.flatMap
import com.android.systemui.kairos.util.getMaybe
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.maybeThat
import com.android.systemui.kairos.util.maybeThis
import com.android.systemui.kairos.util.merge
import com.android.systemui.kairos.util.orElseGet
import com.android.systemui.kairos.util.partitionEithers
import com.android.systemui.kairos.util.these
import java.util.TreeMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

internal class MuxDeferredNode<K : Any, V>(
    lifecycle: MuxLifecycle<Map<K, V>>,
    val spec: MuxActivator<Map<K, V>>,
) : MuxNode<K, V, Map<K, V>>(lifecycle), Key<Map<K, V>> {

    val schedulable = Schedulable.M(this)

    @Volatile var patches: NodeConnection<Map<K, Maybe<TFlowImpl<V>>>>? = null
    @Volatile var patchData: Map<K, Maybe<TFlowImpl<V>>>? = null

    override fun hasCurrentValueLocked(transactionStore: TransactionStore): Boolean =
        transactionStore.contains(this)

    override suspend fun hasCurrentValue(transactionStore: TransactionStore): Boolean =
        mutex.withLock { hasCurrentValueLocked(transactionStore) }

    override suspend fun visit(evalScope: EvalScope) {
        val result = upstreamData.toMap()
        upstreamData.clear()
        val scheduleDownstream = result.isNotEmpty()
        val compactDownstream = depthTracker.isDirty()
        if (scheduleDownstream || compactDownstream) {
            coroutineScope {
                mutex.withLock {
                    if (compactDownstream) {
                        depthTracker.applyChanges(
                            coroutineScope = this,
                            evalScope.scheduler,
                            downstreamSet,
                            muxNode = this@MuxDeferredNode,
                        )
                    }
                    if (scheduleDownstream) {
                        evalScope.setResult(this@MuxDeferredNode, result)
                        if (!scheduleAll(downstreamSet, evalScope)) {
                            evalScope.scheduleDeactivation(this@MuxDeferredNode)
                        }
                    }
                }
            }
        }
    }

    override suspend fun getPushEvent(evalScope: EvalScope): Maybe<Map<K, V>> =
        evalScope.getCurrentValue(key = this)

    private suspend fun compactIfNeeded(evalScope: EvalScope) {
        depthTracker.propagateChanges(evalScope.compactor, this)
    }

    override suspend fun doDeactivate() {
        // Update lifecycle
        lifecycle.mutex.withLock {
            if (lifecycle.lifecycleState !is MuxLifecycleState.Active) return@doDeactivate
            lifecycle.lifecycleState = MuxLifecycleState.Inactive(spec)
        }
        // Process branch nodes
        coroutineScope {
            switchedIn.values.forEach { branchNode ->
                branchNode.upstream.let {
                    launch { it.removeDownstreamAndDeactivateIfNeeded(branchNode.schedulable) }
                }
            }
        }
        // Process patch node
        patches?.removeDownstreamAndDeactivateIfNeeded(schedulable)
    }

    // MOVE phase
    //  - concurrent moves may be occurring, but no more evals. all depth recalculations are
    //    deferred to the end of this phase.
    suspend fun performMove(evalScope: EvalScope) {
        val patch = patchData ?: return
        patchData = null

        // TODO: this logic is very similar to what's in MuxPromptMoving, maybe turn into an inline
        //  fun?

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

        val severed = mutableListOf<NodeConnection<*>>()

        coroutineScope {
            // remove and sever
            removes.forEach { k ->
                switchedIn.remove(k)?.let { branchNode: MuxBranchNode<K, V> ->
                    val conn = branchNode.upstream
                    severed.add(conn)
                    launch { conn.removeDownstream(downstream = branchNode.schedulable) }
                    depthTracker.removeDirectUpstream(conn.depthTracker.snapshotDirectDepth)
                }
            }

            // add or replace
            adds
                .mapParallel { (k, newUpstream: TFlowImpl<V>) ->
                    val branchNode = MuxBranchNode(this@MuxDeferredNode, k)
                    k to
                        newUpstream.activate(evalScope, branchNode.schedulable)?.let { (conn, _) ->
                            branchNode.apply { upstream = conn }
                        }
                }
                .forEach { (k, newBranch: MuxBranchNode<K, V>?) ->
                    // remove old and sever, if present
                    switchedIn.remove(k)?.let { branchNode ->
                        val conn = branchNode.upstream
                        severed.add(conn)
                        launch { conn.removeDownstream(downstream = branchNode.schedulable) }
                        depthTracker.removeDirectUpstream(conn.depthTracker.snapshotDirectDepth)
                    }

                    // add new
                    newBranch?.let {
                        switchedIn[k] = newBranch
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
                                butNot = this@MuxDeferredNode,
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

        compactIfNeeded(evalScope)
    }

    suspend fun removeDirectPatchNode(scheduler: Scheduler) {
        mutex.withLock {
            if (
                depthTracker.removeIndirectUpstream(depth = 0) or
                    depthTracker.setIsIndirectRoot(false)
            ) {
                depthTracker.schedule(scheduler, this)
            }
            patches = null
        }
    }

    suspend fun removeIndirectPatchNode(
        scheduler: Scheduler,
        depth: Int,
        indirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        // indirectly connected patches forward the indirectSet
        mutex.withLock {
            if (
                depthTracker.updateIndirectRoots(removals = indirectSet) or
                    depthTracker.removeIndirectUpstream(depth)
            ) {
                depthTracker.schedule(scheduler, this)
            }
            patches = null
        }
    }

    suspend fun moveIndirectPatchNodeToDirect(
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        // directly connected patches are stored as an indirect singleton set of the patchNode
        mutex.withLock {
            if (
                depthTracker.updateIndirectRoots(removals = oldIndirectSet) or
                    depthTracker.removeIndirectUpstream(oldIndirectDepth) or
                    depthTracker.setIsIndirectRoot(true)
            ) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun moveDirectPatchNodeToIndirect(
        scheduler: Scheduler,
        newIndirectDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *>>,
    ) {
        // indirectly connected patches forward the indirectSet
        mutex.withLock {
            if (
                depthTracker.setIsIndirectRoot(false) or
                    depthTracker.updateIndirectRoots(additions = newIndirectSet, butNot = this) or
                    depthTracker.addIndirectUpstream(oldDepth = null, newDepth = newIndirectDepth)
            ) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun adjustIndirectPatchNode(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *>>,
        additions: Set<MuxDeferredNode<*, *>>,
    ) {
        // indirectly connected patches forward the indirectSet
        mutex.withLock {
            if (
                depthTracker.updateIndirectRoots(
                    additions = additions,
                    removals = removals,
                    butNot = this,
                ) or depthTracker.addIndirectUpstream(oldDepth = oldDepth, newDepth = newDepth)
            ) {
                depthTracker.schedule(scheduler, this)
            }
        }
    }

    suspend fun scheduleMover(evalScope: EvalScope) {
        patchData =
            checkNotNull(patches) { "mux mover scheduled with unset patches upstream node" }
                .getPushEvent(evalScope)
                .orElseGet { null }
        evalScope.scheduleMuxMover(this)
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal inline fun <A> switchDeferredImplSingle(
    crossinline getStorage: suspend EvalScope.() -> TFlowImpl<A>,
    crossinline getPatches: suspend EvalScope.() -> TFlowImpl<TFlowImpl<A>>,
): TFlowImpl<A> =
    mapImpl({
        switchDeferredImpl(
            getStorage = { mapOf(Unit to getStorage()) },
            getPatches = { mapImpl(getPatches) { newFlow -> mapOf(Unit to just(newFlow)) } },
        )
    }) { map ->
        map.getValue(Unit)
    }

internal fun <K : Any, A> switchDeferredImpl(
    getStorage: suspend EvalScope.() -> Map<K, TFlowImpl<A>>,
    getPatches: suspend EvalScope.() -> TFlowImpl<Map<K, Maybe<TFlowImpl<A>>>>,
): TFlowImpl<Map<K, A>> =
    MuxLifecycle(
        object : MuxActivator<Map<K, A>> {
            override suspend fun activate(
                evalScope: EvalScope,
                lifecycle: MuxLifecycle<Map<K, A>>,
            ): MuxNode<*, *, Map<K, A>>? {
                val storage: Map<K, TFlowImpl<A>> = getStorage(evalScope)
                // Initialize mux node and switched-in connections.
                val muxNode =
                    MuxDeferredNode(lifecycle, this).apply {
                        storage.mapValuesNotNullParallelTo(switchedIn) { (key, flow) ->
                            val branchNode = MuxBranchNode(this@apply, key)
                            flow.activate(evalScope, branchNode.schedulable)?.let {
                                (conn, needsEval) ->
                                branchNode
                                    .apply { upstream = conn }
                                    .also {
                                        if (needsEval) {
                                            val result = conn.getPushEvent(evalScope)
                                            if (result is Just) {
                                                upstreamData[key] = result.value
                                            }
                                        }
                                    }
                            }
                        }
                    }
                // Update depth based on all initial switched-in nodes.
                muxNode.switchedIn.values.forEach { branch ->
                    val conn = branch.upstream
                    if (conn.depthTracker.snapshotIsDirect) {
                        muxNode.depthTracker.addDirectUpstream(
                            oldDepth = null,
                            newDepth = conn.depthTracker.snapshotDirectDepth,
                        )
                    } else {
                        muxNode.depthTracker.addIndirectUpstream(
                            oldDepth = null,
                            newDepth = conn.depthTracker.snapshotIndirectDepth,
                        )
                        muxNode.depthTracker.updateIndirectRoots(
                            additions = conn.depthTracker.snapshotIndirectRoots,
                            butNot = muxNode,
                        )
                    }
                }
                // We don't have our patches connection established yet, so for now pretend we have
                // a direct connection to patches. We will update downstream nodes later if this
                // turns out to be a lie.
                muxNode.depthTracker.setIsIndirectRoot(true)
                muxNode.depthTracker.reset()

                // Setup patches connection; deferring allows for a recursive connection, where
                // muxNode is downstream of itself via patches.
                var isIndirect = true
                evalScope.deferAction {
                    val (patchesConn, needsEval) =
                        getPatches(evalScope).activate(evalScope, downstream = muxNode.schedulable)
                            ?: run {
                                isIndirect = false
                                // Turns out we can't connect to patches, so update our depth and
                                // propagate
                                muxNode.mutex.withLock {
                                    if (muxNode.depthTracker.setIsIndirectRoot(false)) {
                                        muxNode.depthTracker.schedule(evalScope.scheduler, muxNode)
                                    }
                                }
                                return@deferAction
                            }
                    muxNode.patches = patchesConn

                    if (!patchesConn.schedulerUpstream.depthTracker.snapshotIsDirect) {
                        // Turns out patches is indirect, so we are not a root. Update depth and
                        // propagate.
                        muxNode.mutex.withLock {
                            if (
                                muxNode.depthTracker.setIsIndirectRoot(false) or
                                    muxNode.depthTracker.addIndirectUpstream(
                                        oldDepth = null,
                                        newDepth = patchesConn.depthTracker.snapshotIndirectDepth,
                                    ) or
                                    muxNode.depthTracker.updateIndirectRoots(
                                        additions = patchesConn.depthTracker.snapshotIndirectRoots
                                    )
                            ) {
                                muxNode.depthTracker.schedule(evalScope.scheduler, muxNode)
                            }
                        }
                    }
                    // Schedule mover to process patch emission at the end of this transaction, if
                    // needed.
                    if (needsEval) {
                        val result = patchesConn.getPushEvent(evalScope)
                        if (result is Just) {
                            muxNode.patchData = result.value
                            evalScope.scheduleMuxMover(muxNode)
                        }
                    }
                }

                // Schedule for evaluation if any switched-in nodes have already emitted within
                // this transaction.
                if (muxNode.upstreamData.isNotEmpty()) {
                    evalScope.schedule(muxNode)
                }
                return muxNode.takeUnless { muxNode.switchedIn.isEmpty() && !isIndirect }
            }
        }
    )

internal inline fun <A> mergeNodes(
    crossinline getPulse: suspend EvalScope.() -> TFlowImpl<A>,
    crossinline getOther: suspend EvalScope.() -> TFlowImpl<A>,
    crossinline f: suspend EvalScope.(A, A) -> A,
): TFlowImpl<A> {
    val merged =
        mapImpl({ mergeNodes(getPulse, getOther) }) { these ->
            these.merge { thiz, that -> f(thiz, that) }
        }
    return merged.cached()
}

internal inline fun <A, B> mergeNodes(
    crossinline getPulse: suspend EvalScope.() -> TFlowImpl<A>,
    crossinline getOther: suspend EvalScope.() -> TFlowImpl<B>,
): TFlowImpl<These<A, B>> {
    val storage =
        mapOf(
            0 to mapImpl(getPulse) { These.thiz<A, B>(it) },
            1 to mapImpl(getOther) { These.that(it) },
        )
    val switchNode = switchDeferredImpl(getStorage = { storage }, getPatches = { neverImpl })
    val merged =
        mapImpl({ switchNode }) { mergeResults ->
            val first = mergeResults.getMaybe(0).flatMap { it.maybeThis() }
            val second = mergeResults.getMaybe(1).flatMap { it.maybeThat() }
            these(first, second).orElseGet { error("unexpected missing merge result") }
        }
    return merged.cached()
}

internal inline fun <A> mergeNodes(
    crossinline getPulses: suspend EvalScope.() -> Iterable<TFlowImpl<A>>
): TFlowImpl<List<A>> {
    val switchNode =
        switchDeferredImpl(
            getStorage = { getPulses().associateByIndexTo(TreeMap()) },
            getPatches = { neverImpl },
        )
    val merged = mapImpl({ switchNode }) { mergeResults -> mergeResults.values.toList() }
    return merged.cached()
}

internal inline fun <A> mergeNodesLeft(
    crossinline getPulses: suspend EvalScope.() -> Iterable<TFlowImpl<A>>
): TFlowImpl<A> {
    val switchNode =
        switchDeferredImpl(
            getStorage = { getPulses().associateByIndexTo(TreeMap()) },
            getPatches = { neverImpl },
        )
    val merged =
        mapImpl({ switchNode }) { mergeResults: Map<Int, A> -> mergeResults.values.first() }
    return merged.cached()
}
