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

import com.android.systemui.kairos.internal.store.MapK
import com.android.systemui.kairos.internal.store.MutableArrayMapK
import com.android.systemui.kairos.internal.store.MutableMapK
import com.android.systemui.kairos.internal.store.SingletonMapK
import com.android.systemui.kairos.internal.store.StoreEntry
import com.android.systemui.kairos.internal.store.asArrayHolder
import com.android.systemui.kairos.internal.store.asSingle
import com.android.systemui.kairos.internal.store.singleOf
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.internal.util.logDuration
import com.android.systemui.kairos.internal.util.logLn
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.Maybe.Just
import com.android.systemui.kairos.util.Maybe.None
import com.android.systemui.kairos.util.These
import com.android.systemui.kairos.util.flatMap
import com.android.systemui.kairos.util.getMaybe
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.maybeThat
import com.android.systemui.kairos.util.maybeThis
import com.android.systemui.kairos.util.merge
import com.android.systemui.kairos.util.orError
import com.android.systemui.kairos.util.these

internal class MuxDeferredNode<W, K, V>(
    val name: String?,
    lifecycle: MuxLifecycle<W, K, V>,
    val spec: MuxActivator<W, K, V>,
    factory: MutableMapK.Factory<W, K>,
) : MuxNode<W, K, V>(lifecycle, factory) {

    val schedulable = Schedulable.M(this)
    var patches: NodeConnection<Iterable<Map.Entry<K, Maybe<EventsImpl<V>>>>>? = null
    var patchData: Iterable<Map.Entry<K, Maybe<EventsImpl<V>>>>? = null

    override fun visit(logIndent: Int, evalScope: EvalScope) {
        check(epoch < evalScope.epoch) { "node unexpectedly visited multiple times in transaction" }
        logDuration(logIndent, "MuxDeferred[$name].visit") {
            val scheduleDownstream: Boolean
            val result: MapK<W, K, PullNode<V>>
            logDuration("copying upstream data", false) {
                scheduleDownstream = upstreamData.isNotEmpty()
                result = upstreamData.readOnlyCopy()
                upstreamData.clear()
            }
            if (name != null) {
                logLn("[${this@MuxDeferredNode}] result = $result")
            }
            val compactDownstream = depthTracker.isDirty()
            if (scheduleDownstream || compactDownstream) {
                if (compactDownstream) {
                    logDuration("compactDownstream", false) {
                        depthTracker.applyChanges(
                            evalScope.scheduler,
                            downstreamSet,
                            muxNode = this@MuxDeferredNode,
                        )
                    }
                }
                if (scheduleDownstream) {
                    logDuration("scheduleDownstream") {
                        if (name != null) {
                            logLn("[${this@MuxDeferredNode}] scheduling")
                        }
                        transactionCache.put(evalScope, result)
                        if (!scheduleAll(currentLogIndent, downstreamSet, evalScope)) {
                            evalScope.scheduleDeactivation(this@MuxDeferredNode)
                        }
                    }
                }
            }
        }
    }

    override fun getPushEvent(logIndent: Int, evalScope: EvalScope): MuxResult<W, K, V> =
        logDuration(logIndent, "MuxDeferred.getPushEvent") {
            transactionCache.getCurrentValue(evalScope).also {
                if (name != null) {
                    logLn("[${this@MuxDeferredNode}] getPushEvent = $it")
                }
            }
        }

    private fun compactIfNeeded(evalScope: EvalScope) {
        depthTracker.propagateChanges(evalScope.compactor, this)
    }

    override fun doDeactivate() {
        // Update lifecycle
        if (lifecycle.lifecycleState !is MuxLifecycleState.Active) return@doDeactivate
        lifecycle.lifecycleState = MuxLifecycleState.Inactive(spec)
        // Process branch nodes
        switchedIn.forEach { (_, branchNode) ->
            branchNode.upstream.removeDownstreamAndDeactivateIfNeeded(branchNode.schedulable)
        }
        // Process patch node
        patches?.removeDownstreamAndDeactivateIfNeeded(schedulable)
    }

    // MOVE phase
    //  - concurrent moves may be occurring, but no more evals. all depth recalculations are
    //    deferred to the end of this phase.
    fun performMove(logIndent: Int, evalScope: EvalScope) {
        if (name != null) {
            logLn(logIndent, "[${this@MuxDeferredNode}] performMove (patchData = $patchData)")
        }

        val patch = patchData ?: return
        patchData = null

        // TODO: this logic is very similar to what's in MuxPrompt, maybe turn into an inline fun?

        // We have a patch, process additions/updates and removals
        val adds = mutableListOf<Pair<K, EventsImpl<V>>>()
        val removes = mutableListOf<K>()
        patch.forEach { (k, newUpstream) ->
            when (newUpstream) {
                is Just -> adds.add(k to newUpstream.value)
                None -> removes.add(k)
            }
        }

        val severed = mutableListOf<NodeConnection<*>>()

        // remove and sever
        removes.forEach { k ->
            switchedIn.remove(k)?.let { branchNode: BranchNode ->
                val conn = branchNode.upstream
                severed.add(conn)
                conn.removeDownstream(downstream = branchNode.schedulable)
                depthTracker.removeDirectUpstream(conn.depthTracker.snapshotDirectDepth)
            }
        }

        // add or replace
        adds.forEach { (k, newUpstream: EventsImpl<V>) ->
            // remove old and sever, if present
            switchedIn.remove(k)?.let { branchNode ->
                val conn = branchNode.upstream
                severed.add(conn)
                conn.removeDownstream(downstream = branchNode.schedulable)
                depthTracker.removeDirectUpstream(conn.depthTracker.snapshotDirectDepth)
            }

            // add new
            val newBranch = BranchNode(k)
            newUpstream.activate(evalScope, newBranch.schedulable)?.let { (conn, _) ->
                newBranch.upstream = conn
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

        for (severedNode in severed) {
            severedNode.scheduleDeactivationIfNeeded(evalScope)
        }

        compactIfNeeded(evalScope)
    }

    fun removeDirectPatchNode(scheduler: Scheduler) {
        if (
            depthTracker.removeIndirectUpstream(depth = 0) or depthTracker.setIsIndirectRoot(false)
        ) {
            depthTracker.schedule(scheduler, this)
        }
        patches = null
    }

    fun removeIndirectPatchNode(
        scheduler: Scheduler,
        depth: Int,
        indirectSet: Set<MuxDeferredNode<*, *, *>>,
    ) {
        // indirectly connected patches forward the indirectSet
        if (
            depthTracker.updateIndirectRoots(removals = indirectSet) or
                depthTracker.removeIndirectUpstream(depth)
        ) {
            depthTracker.schedule(scheduler, this)
        }
        patches = null
    }

    fun moveIndirectPatchNodeToDirect(
        scheduler: Scheduler,
        oldIndirectDepth: Int,
        oldIndirectSet: Set<MuxDeferredNode<*, *, *>>,
    ) {
        // directly connected patches are stored as an indirect singleton set of the patchNode
        if (
            depthTracker.updateIndirectRoots(removals = oldIndirectSet) or
                depthTracker.removeIndirectUpstream(oldIndirectDepth) or
                depthTracker.setIsIndirectRoot(true)
        ) {
            depthTracker.schedule(scheduler, this)
        }
    }

    fun moveDirectPatchNodeToIndirect(
        scheduler: Scheduler,
        newIndirectDepth: Int,
        newIndirectSet: Set<MuxDeferredNode<*, *, *>>,
    ) {
        // indirectly connected patches forward the indirectSet
        if (
            depthTracker.setIsIndirectRoot(false) or
                depthTracker.updateIndirectRoots(additions = newIndirectSet, butNot = this) or
                depthTracker.addIndirectUpstream(oldDepth = null, newDepth = newIndirectDepth)
        ) {
            depthTracker.schedule(scheduler, this)
        }
    }

    fun adjustIndirectPatchNode(
        scheduler: Scheduler,
        oldDepth: Int,
        newDepth: Int,
        removals: Set<MuxDeferredNode<*, *, *>>,
        additions: Set<MuxDeferredNode<*, *, *>>,
    ) {
        // indirectly connected patches forward the indirectSet
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

    fun scheduleMover(logIndent: Int, evalScope: EvalScope) {
        logDuration(logIndent, "MuxDeferred.scheduleMover") {
            patchData =
                checkNotNull(patches) { "mux mover scheduled with unset patches upstream node" }
                    .getPushEvent(currentLogIndent, evalScope)
            evalScope.scheduleMuxMover(this@MuxDeferredNode)
        }
    }

    override fun toString(): String =
        "${this::class.simpleName}@$hashString${name?.let { "[$it]" }.orEmpty()}"
}

internal inline fun <A> switchDeferredImplSingle(
    name: String? = null,
    crossinline getStorage: EvalScope.() -> EventsImpl<A>,
    crossinline getPatches: EvalScope.() -> EventsImpl<EventsImpl<A>>,
): EventsImpl<A> {
    val patches = mapImpl(getPatches) { newEvents, _ -> singleOf(just(newEvents)).asIterable() }
    val switchDeferredImpl =
        switchDeferredImpl(
            name = name,
            getStorage = { singleOf(getStorage()).asIterable() },
            getPatches = { patches },
            storeFactory = SingletonMapK.Factory(),
        )
    return mapImpl({ switchDeferredImpl }) { map, logIndent ->
        map.asSingle().getValue(Unit).getPushEvent(logIndent, this).also {
            if (name != null) {
                logLn(logIndent, "[$name] extracting single mux: $it")
            }
        }
    }
}

internal fun <W, K, V> switchDeferredImpl(
    name: String? = null,
    getStorage: EvalScope.() -> Iterable<Map.Entry<K, EventsImpl<V>>>,
    getPatches: EvalScope.() -> EventsImpl<Iterable<Map.Entry<K, Maybe<EventsImpl<V>>>>>,
    storeFactory: MutableMapK.Factory<W, K>,
): EventsImpl<MuxResult<W, K, V>> =
    MuxLifecycle(MuxDeferredActivator(name, getStorage, storeFactory, getPatches))

private class MuxDeferredActivator<W, K, V>(
    private val name: String?,
    private val getStorage: EvalScope.() -> Iterable<Map.Entry<K, EventsImpl<V>>>,
    private val storeFactory: MutableMapK.Factory<W, K>,
    private val getPatches: EvalScope.() -> EventsImpl<Iterable<Map.Entry<K, Maybe<EventsImpl<V>>>>>,
) : MuxActivator<W, K, V> {
    override fun activate(
        evalScope: EvalScope,
        lifecycle: MuxLifecycle<W, K, V>,
    ): Pair<MuxNode<W, K, V>, (() -> Unit)?>? {
        // Initialize mux node and switched-in connections.
        val muxNode =
            MuxDeferredNode(name, lifecycle, this, storeFactory).apply {
                initializeUpstream(evalScope, getStorage, storeFactory)
                // Update depth based on all initial switched-in nodes.
                initializeDepth()
                // We don't have our patches connection established yet, so for now pretend we have
                // a direct connection to patches. We will update downstream nodes later if this
                // turns out to be a lie.
                depthTracker.setIsIndirectRoot(true)
                depthTracker.reset()
            }

        // Schedule for evaluation if any switched-in nodes have already emitted within
        // this transaction.
        if (muxNode.upstreamData.isNotEmpty()) {
            muxNode.schedule(evalScope)
        }

        return muxNode to
            fun() {
                // Setup patches connection; deferring allows for a recursive connection, where
                // muxNode is downstream of itself via patches.
                val (patchesConn, needsEval) =
                    getPatches(evalScope).activate(evalScope, downstream = muxNode.schedulable)
                        ?: run {
                            // Turns out we can't connect to patches, so update our depth and
                            // propagate
                            if (muxNode.depthTracker.setIsIndirectRoot(false)) {
                                // TODO: schedules might not be necessary now that we're not
                                // parallel?
                                muxNode.depthTracker.schedule(evalScope.scheduler, muxNode)
                            }
                            return
                        }
                muxNode.patches = patchesConn

                if (!patchesConn.schedulerUpstream.depthTracker.snapshotIsDirect) {
                    // Turns out patches is indirect, so we are not a root. Update depth and
                    // propagate.
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
                // Schedule mover to process patch emission at the end of this transaction, if
                // needed.
                if (needsEval) {
                    muxNode.patchData = patchesConn.getPushEvent(0, evalScope)
                    evalScope.scheduleMuxMover(muxNode)
                }
            }
    }
}

internal inline fun <A> mergeNodes(
    crossinline getPulse: EvalScope.() -> EventsImpl<A>,
    crossinline getOther: EvalScope.() -> EventsImpl<A>,
    name: String? = null,
    crossinline f: EvalScope.(A, A) -> A,
): EventsImpl<A> {
    val mergedThese = mergeNodes(name, getPulse, getOther)
    val merged =
        mapImpl({ mergedThese }) { these, _ -> these.merge { thiz, that -> f(thiz, that) } }
    return merged.cached()
}

internal fun <T> Iterable<T>.asIterableWithIndex(): Iterable<Map.Entry<Int, T>> =
    asSequence().mapIndexed { i, t -> StoreEntry(i, t) }.asIterable()

internal inline fun <A, B> mergeNodes(
    name: String? = null,
    crossinline getPulse: EvalScope.() -> EventsImpl<A>,
    crossinline getOther: EvalScope.() -> EventsImpl<B>,
): EventsImpl<These<A, B>> {
    val storage =
        listOf(
                mapImpl(getPulse) { it, _ -> These.thiz(it) },
                mapImpl(getOther) { it, _ -> These.that(it) },
            )
            .asIterableWithIndex()
    val switchNode =
        switchDeferredImpl(
            name = name,
            getStorage = { storage },
            getPatches = { neverImpl },
            storeFactory = MutableArrayMapK.Factory(),
        )
    val merged =
        mapImpl({ switchNode }) { it, logIndent ->
            val mergeResults = it.asArrayHolder()
            val first =
                mergeResults.getMaybe(0).flatMap { it.getPushEvent(logIndent, this).maybeThis() }
            val second =
                mergeResults.getMaybe(1).flatMap { it.getPushEvent(logIndent, this).maybeThat() }
            these(first, second).orError { "unexpected missing merge result" }
        }
    return merged.cached()
}

internal inline fun <A> mergeNodes(
    crossinline getPulses: EvalScope.() -> Iterable<EventsImpl<A>>
): EventsImpl<List<A>> {
    val switchNode =
        switchDeferredImpl(
            getStorage = { getPulses().asIterableWithIndex() },
            getPatches = { neverImpl },
            storeFactory = MutableArrayMapK.Factory(),
        )
    val merged =
        mapImpl({ switchNode }) { it, logIndent ->
            val mergeResults = it.asArrayHolder()
            mergeResults.map { (_, node) -> node.getPushEvent(logIndent, this) }
        }
    return merged.cached()
}

internal inline fun <A> mergeNodesLeft(
    crossinline getPulses: EvalScope.() -> Iterable<EventsImpl<A>>
): EventsImpl<A> {
    val switchNode =
        switchDeferredImpl(
            getStorage = { getPulses().asIterableWithIndex() },
            getPatches = { neverImpl },
            storeFactory = MutableArrayMapK.Factory(),
        )
    val merged =
        mapImpl({ switchNode }) { it, logIndent ->
            val mergeResults = it.asArrayHolder()
            mergeResults.values.first().getPushEvent(logIndent, this)
        }
    return merged.cached()
}
