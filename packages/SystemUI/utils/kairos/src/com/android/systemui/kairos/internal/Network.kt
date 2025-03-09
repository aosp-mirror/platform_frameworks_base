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

import com.android.systemui.kairos.TState
import com.android.systemui.kairos.internal.util.HeteroMap
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.none
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

private val nextNetworkId = AtomicLong()

internal class Network(val coroutineScope: CoroutineScope) : NetworkScope {

    override val networkId: Any = nextNetworkId.getAndIncrement()

    @Volatile
    override var epoch: Long = 0L
        private set

    override val network
        get() = this

    override val compactor = SchedulerImpl()
    override val scheduler = SchedulerImpl()
    override val transactionStore = HeteroMap()

    private val stateWrites = ConcurrentLinkedQueue<TStateSource<*>>()
    private val outputsByDispatcher =
        ConcurrentHashMap<ContinuationInterceptor, ConcurrentLinkedQueue<Output<*>>>()
    private val muxMovers = ConcurrentLinkedQueue<MuxDeferredNode<*, *>>()
    private val deactivations = ConcurrentLinkedDeque<PushNode<*>>()
    private val outputDeactivations = ConcurrentLinkedQueue<Output<*>>()
    private val transactionMutex = Mutex()
    private val inputScheduleChan = Channel<ScheduledAction<*>>()

    override fun scheduleOutput(output: Output<*>) {
        val continuationInterceptor =
            output.context[ContinuationInterceptor] ?: Dispatchers.Unconfined
        outputsByDispatcher
            .computeIfAbsent(continuationInterceptor) { ConcurrentLinkedQueue() }
            .add(output)
    }

    override fun scheduleMuxMover(muxMover: MuxDeferredNode<*, *>) {
        muxMovers.add(muxMover)
    }

    override fun schedule(state: TStateSource<*>) {
        stateWrites.add(state)
    }

    // TODO: weird that we have this *and* scheduler exposed
    override suspend fun schedule(node: MuxNode<*, *, *>) {
        scheduler.schedule(node.depthTracker.dirty_directDepth, node)
    }

    override fun scheduleDeactivation(node: PushNode<*>) {
        deactivations.add(node)
    }

    override fun scheduleDeactivation(output: Output<*>) {
        outputDeactivations.add(output)
    }

    /** Listens for external events and starts FRP transactions. Runs forever. */
    suspend fun runInputScheduler() {
        val actions = mutableListOf<ScheduledAction<*>>()
        for (first in inputScheduleChan) {
            // Drain and conflate all transaction requests into a single transaction
            actions.add(first)
            while (true) {
                yield()
                val func = inputScheduleChan.tryReceive().getOrNull() ?: break
                actions.add(func)
            }
            transactionMutex.withLock {
                // Run all actions
                evalScope {
                    for (action in actions) {
                        launch { action.started(evalScope = this@evalScope) }
                    }
                }
                // Step through the network
                doTransaction()
                // Signal completion
                while (actions.isNotEmpty()) {
                    actions.removeLast().completed()
                }
            }
        }
    }

    /** Evaluates [block] inside of a new transaction when the network is ready. */
    fun <R> transaction(block: suspend EvalScope.() -> R): Deferred<R> =
        CompletableDeferred<R>(parent = coroutineScope.coroutineContext.job).also { onResult ->
            val job =
                coroutineScope.launch {
                    inputScheduleChan.send(
                        ScheduledAction(onStartTransaction = block, onResult = onResult)
                    )
                }
            onResult.invokeOnCompletion { job.cancel() }
        }

    suspend fun <R> evalScope(block: suspend EvalScope.() -> R): R = deferScope {
        block(EvalScopeImpl(this@Network, this))
    }

    /** Performs a transactional update of the FRP network. */
    private suspend fun doTransaction() {
        // Traverse network, then run outputs
        do {
            scheduler.drainEval(this)
        } while (evalScope { evalOutputs(this) })
        // Update states
        evalScope { evalStateWriters(this) }
        transactionStore.clear()
        // Perform deferred switches
        evalScope { evalMuxMovers(this) }
        // Compact depths
        scheduler.drainCompact()
        compactor.drainCompact()
        // Deactivate nodes with no downstream
        evalDeactivations()
        epoch++
    }

    /** Invokes all [Output]s that have received data within this transaction. */
    private suspend fun evalOutputs(evalScope: EvalScope): Boolean {
        // Outputs can enqueue other outputs, so we need two loops
        if (outputsByDispatcher.isEmpty()) return false
        while (outputsByDispatcher.isNotEmpty()) {
            var launchedAny = false
            coroutineScope {
                for ((key, outputs) in outputsByDispatcher) {
                    if (outputs.isNotEmpty()) {
                        launchedAny = true
                        launch(key) {
                            while (outputs.isNotEmpty()) {
                                val output = outputs.remove()
                                launch { output.visit(evalScope) }
                            }
                        }
                    }
                }
            }
            if (!launchedAny) outputsByDispatcher.clear()
        }
        return true
    }

    private suspend fun evalMuxMovers(evalScope: EvalScope) {
        while (muxMovers.isNotEmpty()) {
            coroutineScope {
                val toMove = muxMovers.remove()
                launch { toMove.performMove(evalScope) }
            }
        }
    }

    /** Updates all [TState]es that have changed within this transaction. */
    private suspend fun evalStateWriters(evalScope: EvalScope) {
        coroutineScope {
            while (stateWrites.isNotEmpty()) {
                val latch = stateWrites.remove()
                launch { latch.updateState(evalScope) }
            }
        }
    }

    private suspend fun evalDeactivations() {
        coroutineScope {
            launch {
                while (deactivations.isNotEmpty()) {
                    // traverse in reverse order
                    //   - deactivations are added in depth-order during the node traversal phase
                    //   - perform deactivations in reverse order, in case later ones propagate to
                    //     earlier ones
                    val toDeactivate = deactivations.removeLast()
                    launch { toDeactivate.deactivateIfNeeded() }
                }
            }
            while (outputDeactivations.isNotEmpty()) {
                val toDeactivate = outputDeactivations.remove()
                launch {
                    toDeactivate.upstream?.removeDownstreamAndDeactivateIfNeeded(
                        downstream = toDeactivate.schedulable
                    )
                }
            }
        }
        check(deactivations.isEmpty()) { "unexpected lingering deactivations" }
        check(outputDeactivations.isEmpty()) { "unexpected lingering output deactivations" }
    }
}

internal class ScheduledAction<T>(
    private val onResult: CompletableDeferred<T>? = null,
    private val onStartTransaction: suspend EvalScope.() -> T,
) {
    private var result: Maybe<T> = none

    suspend fun started(evalScope: EvalScope) {
        result = just(onStartTransaction(evalScope))
    }

    fun completed() {
        if (onResult != null) {
            when (val result = result) {
                is Just -> onResult.complete(result.value)
                else -> {}
            }
        }
        result = none
    }
}

internal typealias TransactionStore = HeteroMap
