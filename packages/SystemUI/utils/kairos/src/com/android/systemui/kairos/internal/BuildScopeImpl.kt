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

import com.android.systemui.kairos.CoalescingMutableTFlow
import com.android.systemui.kairos.FrpBuildScope
import com.android.systemui.kairos.FrpCoalescingProducerScope
import com.android.systemui.kairos.FrpDeferredValue
import com.android.systemui.kairos.FrpEffectScope
import com.android.systemui.kairos.FrpNetwork
import com.android.systemui.kairos.FrpProducerScope
import com.android.systemui.kairos.FrpSpec
import com.android.systemui.kairos.FrpStateScope
import com.android.systemui.kairos.FrpTransactionScope
import com.android.systemui.kairos.GroupedTFlow
import com.android.systemui.kairos.LocalFrpNetwork
import com.android.systemui.kairos.MutableTFlow
import com.android.systemui.kairos.TFlow
import com.android.systemui.kairos.TFlowInit
import com.android.systemui.kairos.groupByKey
import com.android.systemui.kairos.init
import com.android.systemui.kairos.internal.util.childScope
import com.android.systemui.kairos.internal.util.launchOnCancel
import com.android.systemui.kairos.internal.util.mapValuesParallel
import com.android.systemui.kairos.launchEffect
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.None
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.map
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.job

internal class BuildScopeImpl(val stateScope: StateScopeImpl, val coroutineScope: CoroutineScope) :
    BuildScope, StateScope by stateScope {

    private val job: Job
        get() = coroutineScope.coroutineContext.job

    override val frpScope: FrpBuildScope = FrpBuildScopeImpl()

    override suspend fun <R> runInBuildScope(block: suspend FrpBuildScope.() -> R): R {
        val complete = CompletableDeferred<R>(parent = coroutineContext.job)
        block.startCoroutine(
            frpScope,
            object : Continuation<R> {
                override val context: CoroutineContext
                    get() = EmptyCoroutineContext

                override fun resumeWith(result: Result<R>) {
                    complete.completeWith(result)
                }
            },
        )
        return complete.await()
    }

    private fun <A, T : TFlow<A>, S> buildTFlow(
        constructFlow: (InputNode<A>) -> Pair<T, S>,
        builder: suspend S.() -> Unit,
    ): TFlow<A> {
        var job: Job? = null
        val stopEmitter = newStopEmitter()
        val handle = this.job.invokeOnCompletion { stopEmitter.emit(Unit) }
        // Create a child scope that will be kept alive beyond the end of this transaction.
        val childScope = coroutineScope.childScope()
        lateinit var emitter: Pair<T, S>
        val inputNode =
            InputNode<A>(
                activate = {
                    check(job == null) { "already activated" }
                    job =
                        reenterBuildScope(this@BuildScopeImpl, childScope).runInBuildScope {
                            launchEffect {
                                builder(emitter.second)
                                handle.dispose()
                                stopEmitter.emit(Unit)
                            }
                        }
                },
                deactivate = {
                    checkNotNull(job) { "already deactivated" }.cancel()
                    job = null
                },
            )
        emitter = constructFlow(inputNode)
        return with(frpScope) { emitter.first.takeUntil(stopEmitter) }
    }

    private fun <T> tFlowInternal(builder: suspend FrpProducerScope<T>.() -> Unit): TFlow<T> =
        buildTFlow(
            constructFlow = { inputNode ->
                val flow = MutableTFlow(network, inputNode)
                flow to
                    object : FrpProducerScope<T> {
                        override suspend fun emit(value: T) {
                            flow.emit(value)
                        }
                    }
            },
            builder = builder,
        )

    private fun <In, Out> coalescingTFlowInternal(
        getInitialValue: () -> Out,
        coalesce: (old: Out, new: In) -> Out,
        builder: suspend FrpCoalescingProducerScope<In>.() -> Unit,
    ): TFlow<Out> =
        buildTFlow(
            constructFlow = { inputNode ->
                val flow = CoalescingMutableTFlow(coalesce, network, getInitialValue, inputNode)
                flow to
                    object : FrpCoalescingProducerScope<In> {
                        override fun emit(value: In) {
                            flow.emit(value)
                        }
                    }
            },
            builder = builder,
        )

    private fun <A> asyncScopeInternal(block: FrpSpec<A>): Pair<FrpDeferredValue<A>, Job> {
        val childScope = mutableChildBuildScope()
        return FrpDeferredValue(deferAsync { childScope.runInBuildScope(block) }) to childScope.job
    }

    private fun <R> deferredInternal(block: suspend FrpBuildScope.() -> R): FrpDeferredValue<R> =
        FrpDeferredValue(deferAsync { runInBuildScope(block) })

    private fun deferredActionInternal(block: suspend FrpBuildScope.() -> Unit) {
        deferAction { runInBuildScope(block) }
    }

    private fun <A> TFlow<A>.observeEffectInternal(
        context: CoroutineContext,
        block: suspend FrpEffectScope.(A) -> Unit,
    ): Job {
        val subRef = AtomicReference<Maybe<Output<A>>>(null)
        val childScope = coroutineScope.childScope()
        // When our scope is cancelled, deactivate this observer.
        childScope.launchOnCancel(CoroutineName("TFlow.observeEffect")) {
            subRef.getAndSet(None)?.let { output ->
                if (output is Just) {
                    @Suppress("DeferredResultUnused")
                    network.transaction { scheduleDeactivation(output.value) }
                }
            }
        }
        // Defer so that we don't suspend the caller
        deferAction {
            val outputNode =
                Output<A>(
                    context = context,
                    onDeath = { subRef.getAndSet(None)?.let { childScope.cancel() } },
                    onEmit = { output ->
                        if (subRef.get() is Just) {
                            // Not cancelled, safe to emit
                            val coroutine: suspend FrpEffectScope.() -> Unit = { block(output) }
                            val complete = CompletableDeferred<Unit>(parent = coroutineContext.job)
                            coroutine.startCoroutine(
                                object : FrpEffectScope, FrpTransactionScope by frpScope {
                                    override val frpCoroutineScope: CoroutineScope = childScope
                                    override val frpNetwork: FrpNetwork =
                                        LocalFrpNetwork(network, childScope, endSignal)
                                },
                                completion =
                                    object : Continuation<Unit> {
                                        override val context: CoroutineContext
                                            get() = EmptyCoroutineContext

                                        override fun resumeWith(result: Result<Unit>) {
                                            complete.completeWith(result)
                                        }
                                    },
                            )
                            complete.await()
                        }
                    },
                )
            with(frpScope) { this@observeEffectInternal.takeUntil(endSignal) }
                .init
                .connect(evalScope = stateScope.evalScope)
                .activate(evalScope = stateScope.evalScope, outputNode.schedulable)
                ?.let { (conn, needsEval) ->
                    outputNode.upstream = conn
                    if (!subRef.compareAndSet(null, just(outputNode))) {
                        // Job's already been cancelled, schedule deactivation
                        scheduleDeactivation(outputNode)
                    } else if (needsEval) {
                        outputNode.schedule(evalScope = stateScope.evalScope)
                    }
                } ?: childScope.cancel()
        }
        return childScope.coroutineContext.job
    }

    private fun <A, B> TFlow<A>.mapBuildInternal(
        transform: suspend FrpBuildScope.(A) -> B
    ): TFlow<B> {
        val childScope = coroutineScope.childScope()
        return TFlowInit(
            constInit(
                "mapBuild",
                mapImpl({ init.connect(evalScope = this) }) { spec ->
                        reenterBuildScope(outerScope = this@BuildScopeImpl, childScope)
                            .runInBuildScope {
                                val (result, _) = asyncScope { transform(spec) }
                                result.get()
                            }
                    }
                    .cached(),
            )
        )
    }

    private fun <K, A, B> TFlow<Map<K, Maybe<FrpSpec<A>>>>.applyLatestForKeyInternal(
        init: FrpDeferredValue<Map<K, FrpSpec<B>>>,
        numKeys: Int?,
    ): Pair<TFlow<Map<K, Maybe<A>>>, FrpDeferredValue<Map<K, B>>> {
        val eventsByKey: GroupedTFlow<K, Maybe<FrpSpec<A>>> = groupByKey(numKeys)
        val initOut: Deferred<Map<K, B>> = deferAsync {
            init.unwrapped.await().mapValuesParallel { (k, spec) ->
                val newEnd = with(frpScope) { eventsByKey[k].skipNext() }
                val newScope = childBuildScope(newEnd)
                newScope.runInBuildScope(spec)
            }
        }
        val childScope = coroutineScope.childScope()
        val changesNode: TFlowImpl<Map<K, Maybe<A>>> =
            mapImpl(upstream = { this@applyLatestForKeyInternal.init.connect(evalScope = this) }) {
                upstreamMap ->
                reenterBuildScope(this@BuildScopeImpl, childScope).run {
                    upstreamMap.mapValuesParallel { (k: K, ma: Maybe<FrpSpec<A>>) ->
                        ma.map { spec ->
                            val newEnd = with(frpScope) { eventsByKey[k].skipNext() }
                            val newScope = childBuildScope(newEnd)
                            newScope.runInBuildScope(spec)
                        }
                    }
                }
            }
        val changes: TFlow<Map<K, Maybe<A>>> =
            TFlowInit(constInit("applyLatestForKey", changesNode.cached()))
        // Ensure effects are observed; otherwise init will stay alive longer than expected
        changes.observeEffectInternal(EmptyCoroutineContext) {}
        return changes to FrpDeferredValue(initOut)
    }

    private fun newStopEmitter(): CoalescingMutableTFlow<Unit, Unit> =
        CoalescingMutableTFlow(
            coalesce = { _, _: Unit -> },
            network = network,
            getInitialValue = {},
        )

    private suspend fun childBuildScope(newEnd: TFlow<Any>): BuildScopeImpl {
        val newCoroutineScope: CoroutineScope = coroutineScope.childScope()
        return BuildScopeImpl(
                stateScope = stateScope.childStateScope(newEnd),
                coroutineScope = newCoroutineScope,
            )
            .apply {
                // Ensure that once this transaction is done, the new child scope enters the
                // completing state (kept alive so long as there are child jobs).
                scheduleOutput(
                    OneShot {
                        // TODO: don't like this cast
                        (newCoroutineScope.coroutineContext.job as CompletableJob).complete()
                    }
                )
                runInBuildScope { endSignal.nextOnly().observe { newCoroutineScope.cancel() } }
            }
    }

    private fun mutableChildBuildScope(): BuildScopeImpl {
        val stopEmitter = newStopEmitter()
        val childScope = coroutineScope.childScope()
        childScope.coroutineContext.job.invokeOnCompletion { stopEmitter.emit(Unit) }
        // Ensure that once this transaction is done, the new child scope enters the completing
        // state (kept alive so long as there are child jobs).
        scheduleOutput(
            OneShot {
                // TODO: don't like this cast
                (childScope.coroutineContext.job as CompletableJob).complete()
            }
        )
        return BuildScopeImpl(
            stateScope = StateScopeImpl(evalScope = stateScope.evalScope, endSignal = stopEmitter),
            coroutineScope = childScope,
        )
    }

    private inner class FrpBuildScopeImpl : FrpBuildScope, FrpStateScope by stateScope.frpScope {

        override fun <T> tFlow(builder: suspend FrpProducerScope<T>.() -> Unit): TFlow<T> =
            tFlowInternal(builder)

        override fun <In, Out> coalescingTFlow(
            getInitialValue: () -> Out,
            coalesce: (old: Out, new: In) -> Out,
            builder: suspend FrpCoalescingProducerScope<In>.() -> Unit,
        ): TFlow<Out> = coalescingTFlowInternal(getInitialValue, coalesce, builder)

        override fun <A> asyncScope(block: FrpSpec<A>): Pair<FrpDeferredValue<A>, Job> =
            asyncScopeInternal(block)

        override fun <R> deferredBuildScope(
            block: suspend FrpBuildScope.() -> R
        ): FrpDeferredValue<R> = deferredInternal(block)

        override fun deferredBuildScopeAction(block: suspend FrpBuildScope.() -> Unit) =
            deferredActionInternal(block)

        override fun <A> TFlow<A>.observe(
            coroutineContext: CoroutineContext,
            block: suspend FrpEffectScope.(A) -> Unit,
        ): Job = observeEffectInternal(coroutineContext, block)

        override fun <A, B> TFlow<A>.mapBuild(transform: suspend FrpBuildScope.(A) -> B): TFlow<B> =
            mapBuildInternal(transform)

        override fun <K, A, B> TFlow<Map<K, Maybe<FrpSpec<A>>>>.applyLatestSpecForKey(
            initialSpecs: FrpDeferredValue<Map<K, FrpSpec<B>>>,
            numKeys: Int?,
        ): Pair<TFlow<Map<K, Maybe<A>>>, FrpDeferredValue<Map<K, B>>> =
            applyLatestForKeyInternal(initialSpecs, numKeys)
    }
}

private fun EvalScope.reenterBuildScope(
    outerScope: BuildScopeImpl,
    coroutineScope: CoroutineScope,
) =
    BuildScopeImpl(
        stateScope = StateScopeImpl(evalScope = this, endSignal = outerScope.endSignal),
        coroutineScope,
    )
