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

import com.android.systemui.kairos.State
import com.android.systemui.kairos.internal.util.HeteroMap
import com.android.systemui.kairos.internal.util.logDuration
import com.android.systemui.kairos.internal.util.logLn
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.Maybe.Present
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
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

    override val compactor = SchedulerImpl {
        if (it.markedForCompaction) false
        else {
            it.markedForCompaction = true
            true
        }
    }
    override val scheduler = SchedulerImpl {
        if (it.markedForEvaluation) false
        else {
            it.markedForEvaluation = true
            true
        }
    }
    override val transactionStore = TransactionStore()

    private val stateWrites = ArrayDeque<StateSource<*>>()
    private val outputsByDispatcher = HashMap<ContinuationInterceptor, ArrayDeque<Output<*>>>()
    private val muxMovers = ArrayDeque<MuxDeferredNode<*, *, *>>()
    private val deactivations = ArrayDeque<PushNode<*>>()
    private val outputDeactivations = ArrayDeque<Output<*>>()
    private val transactionMutex = Mutex()
    private val inputScheduleChan = Channel<ScheduledAction<*>>()

    override fun scheduleOutput(output: Output<*>) {
        val continuationInterceptor =
            output.context[ContinuationInterceptor] ?: Dispatchers.Unconfined
        outputsByDispatcher.computeIfAbsent(continuationInterceptor) { ArrayDeque() }.add(output)
    }

    override fun scheduleMuxMover(muxMover: MuxDeferredNode<*, *, *>) {
        muxMovers.add(muxMover)
    }

    override fun schedule(state: StateSource<*>) {
        stateWrites.add(state)
    }

    override fun scheduleDeactivation(node: PushNode<*>) {
        deactivations.add(node)
    }

    override fun scheduleDeactivation(output: Output<*>) {
        outputDeactivations.add(output)
    }

    /** Listens for external events and starts Kairos transactions. Runs forever. */
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
                val e = epoch
                val duration = measureTime {
                    logLn(0, "===starting transaction $e===")
                    try {
                        logDuration(1, "init actions") {
                            // Run all actions
                            evalScope {
                                for (action in actions) {
                                    action.started(evalScope = this@evalScope)
                                }
                            }
                        }
                        // Step through the network
                        doTransaction(1)
                    } catch (e: Exception) {
                        // Signal failure
                        while (actions.isNotEmpty()) {
                            actions.removeLast().fail(e)
                        }
                        // re-throw, cancelling this coroutine
                        throw e
                    } finally {
                        logDuration(1, "signal completions") {
                            // Signal completion
                            while (actions.isNotEmpty()) {
                                actions.removeLast().completed()
                            }
                        }
                    }
                }
                logLn(0, "===transaction $e took $duration===")
            }
        }
    }

    /** Evaluates [block] inside of a new transaction when the network is ready. */
    fun <R> transaction(reason: String, block: suspend EvalScope.() -> R): Deferred<R> =
        CompletableDeferred<R>(parent = coroutineScope.coroutineContext.job).also { onResult ->
            if (!coroutineScope.isActive) {
                onResult.cancel()
                return@also
            }
            val job =
                coroutineScope.launch {
                    inputScheduleChan.send(
                        ScheduledAction(reason, onStartTransaction = block, onResult = onResult)
                    )
                }
            onResult.invokeOnCompletion { job.cancel() }
        }

    inline fun <R> evalScope(block: EvalScope.() -> R): R = deferScope {
        block(EvalScopeImpl(this@Network, this))
    }

    /** Performs a transactional update of the Kairos network. */
    private suspend fun doTransaction(logIndent: Int) {
        // Traverse network, then run outputs
        logDuration(logIndent, "traverse network") {
            do {
                val numNodes =
                    logDuration("drainEval") { scheduler.drainEval(currentLogIndent, this@Network) }
                logLn("drained $numNodes nodes")
            } while (logDuration("evalOutputs") { evalScope { evalOutputs(this) } })
        }
        // Update states
        logDuration(logIndent, "update states") {
            evalScope { evalStateWriters(currentLogIndent, this) }
        }
        // Invalidate caches
        // Note: this needs to occur before deferred switches
        logDuration(logIndent, "clear store") { transactionStore.clear() }
        epoch++
        // Perform deferred switches
        logDuration(logIndent, "evalMuxMovers") {
            evalScope { evalMuxMovers(currentLogIndent, this) }
        }
        // Compact depths
        logDuration(logIndent, "compact") {
            scheduler.drainCompact(currentLogIndent)
            compactor.drainCompact(currentLogIndent)
        }
        // Deactivate nodes with no downstream
        logDuration(logIndent, "deactivations") { evalDeactivations() }
    }

    /** Invokes all [Output]s that have received data within this transaction. */
    private suspend fun evalOutputs(evalScope: EvalScope): Boolean {
        if (outputsByDispatcher.isEmpty()) {
            return false
        }
        // Outputs can enqueue other outputs, so we need two loops
        while (outputsByDispatcher.isNotEmpty()) {
            var launchedAny = false
            coroutineScope {
                for ((key, outputs) in outputsByDispatcher) {
                    if (outputs.isNotEmpty()) {
                        launchedAny = true
                        launch(key) {
                            while (outputs.isNotEmpty()) {
                                val output = outputs.removeFirst()
                                launch { output.visit(evalScope) }
                            }
                        }
                    }
                }
            }
            if (!launchedAny) {
                outputsByDispatcher.clear()
            }
        }
        return true
    }

    private fun evalMuxMovers(logIndent: Int, evalScope: EvalScope) {
        while (muxMovers.isNotEmpty()) {
            val toMove = muxMovers.removeFirst()
            toMove.performMove(logIndent, evalScope)
        }
    }

    /** Updates all [State]es that have changed within this transaction. */
    private fun evalStateWriters(logIndent: Int, evalScope: EvalScope) {
        while (stateWrites.isNotEmpty()) {
            val latch = stateWrites.removeFirst()
            latch.updateState(logIndent, evalScope)
        }
    }

    private fun evalDeactivations() {
        while (deactivations.isNotEmpty()) {
            // traverse in reverse order
            //   - deactivations are added in depth-order during the node traversal phase
            //   - perform deactivations in reverse order, in case later ones propagate to
            //     earlier ones
            val toDeactivate = deactivations.removeLast()
            toDeactivate.deactivateIfNeeded()
        }

        while (outputDeactivations.isNotEmpty()) {
            val toDeactivate = outputDeactivations.removeFirst()
            toDeactivate.upstream?.removeDownstreamAndDeactivateIfNeeded(
                downstream = toDeactivate.schedulable
            )
        }
        check(deactivations.isEmpty()) { "unexpected lingering deactivations" }
        check(outputDeactivations.isEmpty()) { "unexpected lingering output deactivations" }
    }
}

internal class ScheduledAction<T>(
    val reason: String,
    private val onResult: CompletableDeferred<T>? = null,
    private val onStartTransaction: suspend EvalScope.() -> T,
) {
    private var result: Maybe<T> = Maybe.absent

    suspend fun started(evalScope: EvalScope) {
        result = Maybe.present(onStartTransaction(evalScope))
    }

    fun fail(ex: Exception) {
        result = Maybe.absent
        onResult?.completeExceptionally(ex)
    }

    fun completed() {
        if (onResult != null) {
            when (val result = result) {
                is Present -> onResult.complete(result.value)
                else -> {}
            }
        }
        result = Maybe.absent
    }
}

internal class TransactionStore private constructor(private val storage: HeteroMap) {
    constructor(capacity: Int) : this(HeteroMap(capacity))

    constructor() : this(HeteroMap())

    operator fun <A> get(key: HeteroMap.Key<A>): A =
        storage.getOrError(key) { "no value for $key in this transaction" }

    operator fun <A> set(key: HeteroMap.Key<A>, value: A) {
        storage[key] = value
    }

    fun clear() = storage.clear()
}

internal class TransactionCache<A> {
    private val key = object : HeteroMap.Key<A> {}
    @Volatile
    var epoch: Long = Long.MIN_VALUE
        private set

    fun getOrPut(evalScope: EvalScope, block: () -> A): A =
        if (epoch < evalScope.epoch) {
            epoch = evalScope.epoch
            block().also { evalScope.transactionStore[key] = it }
        } else {
            evalScope.transactionStore[key]
        }

    fun put(evalScope: EvalScope, value: A) {
        epoch = evalScope.epoch
        evalScope.transactionStore[key] = value
    }

    fun getCurrentValue(evalScope: EvalScope): A = evalScope.transactionStore[key]
}
