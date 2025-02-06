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

import com.android.systemui.kairos.BuildScope
import com.android.systemui.kairos.BuildSpec
import com.android.systemui.kairos.CoalescingEventProducerScope
import com.android.systemui.kairos.CoalescingMutableEvents
import com.android.systemui.kairos.DeferredValue
import com.android.systemui.kairos.EffectScope
import com.android.systemui.kairos.EventProducerScope
import com.android.systemui.kairos.Events
import com.android.systemui.kairos.EventsInit
import com.android.systemui.kairos.GroupedEvents
import com.android.systemui.kairos.KairosCoroutineScope
import com.android.systemui.kairos.KairosNetwork
import com.android.systemui.kairos.LocalNetwork
import com.android.systemui.kairos.MutableEvents
import com.android.systemui.kairos.TransactionScope
import com.android.systemui.kairos.groupByKey
import com.android.systemui.kairos.init
import com.android.systemui.kairos.internal.util.childScope
import com.android.systemui.kairos.internal.util.invokeOnCancel
import com.android.systemui.kairos.internal.util.launchImmediate
import com.android.systemui.kairos.launchEffect
import com.android.systemui.kairos.mergeLeft
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.Maybe.Absent
import com.android.systemui.kairos.util.Maybe.Present
import com.android.systemui.kairos.util.map
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job

internal class BuildScopeImpl(val stateScope: StateScopeImpl, val coroutineScope: CoroutineScope) :
    InternalBuildScope, InternalStateScope by stateScope {

    private val job: Job
        get() = coroutineScope.coroutineContext.job

    override val kairosNetwork: KairosNetwork by lazy {
        LocalNetwork(network, coroutineScope, endSignal)
    }

    override fun <T> events(builder: suspend EventProducerScope<T>.() -> Unit): Events<T> =
        buildEvents(
            constructEvents = { inputNode ->
                val events = MutableEvents(network, inputNode)
                events to
                    object : EventProducerScope<T> {
                        override suspend fun emit(value: T) {
                            events.emit(value)
                        }
                    }
            },
            builder = builder,
        )

    override fun <In, Out> coalescingEvents(
        getInitialValue: () -> Out,
        coalesce: (old: Out, new: In) -> Out,
        builder: suspend CoalescingEventProducerScope<In>.() -> Unit,
    ): Events<Out> =
        buildEvents(
            constructEvents = { inputNode ->
                val events =
                    CoalescingMutableEvents(
                        null,
                        coalesce = { old, new: In -> coalesce(old.value, new) },
                        network,
                        getInitialValue,
                        inputNode,
                    )
                events to
                    object : CoalescingEventProducerScope<In> {
                        override fun emit(value: In) {
                            events.emit(value)
                        }
                    }
            },
            builder = builder,
        )

    override fun <A> asyncScope(block: BuildSpec<A>): Pair<DeferredValue<A>, Job> {
        val childScope = mutableChildBuildScope()
        return DeferredValue(deferAsync { block(childScope) }) to childScope.job
    }

    override fun <R> deferredBuildScope(block: BuildScope.() -> R): DeferredValue<R> =
        DeferredValue(deferAsync { block() })

    override fun deferredBuildScopeAction(block: BuildScope.() -> Unit) {
        deferAction { block() }
    }

    override fun <A> Events<A>.observe(
        coroutineContext: CoroutineContext,
        block: EffectScope.(A) -> Unit,
    ): DisposableHandle {
        val subRef = AtomicReference<Maybe<Output<A>>>(null)
        val childScope = coroutineScope.childScope()
        lateinit var cancelHandle: DisposableHandle
        val handle = DisposableHandle {
            cancelHandle.dispose()
            subRef.getAndSet(Absent)?.let { output ->
                if (output is Present) {
                    @Suppress("DeferredResultUnused")
                    network.transaction("observeEffect cancelled") {
                        scheduleDeactivation(output.value)
                    }
                }
            }
        }
        // When our scope is cancelled, deactivate this observer.
        cancelHandle = childScope.coroutineContext.job.invokeOnCompletion { handle.dispose() }
        val localNetwork = LocalNetwork(network, childScope, endSignal)
        val outputNode =
            Output<A>(
                context = coroutineContext,
                onDeath = { subRef.set(Absent) },
                onEmit = { output ->
                    if (subRef.get() is Present) {
                        // Not cancelled, safe to emit
                        val scope =
                            object : EffectScope, TransactionScope by this {
                                override fun <R> async(
                                    context: CoroutineContext,
                                    start: CoroutineStart,
                                    block: suspend KairosCoroutineScope.() -> R,
                                ): Deferred<R> =
                                    childScope.async(context, start) {
                                        object : KairosCoroutineScope, CoroutineScope by this {
                                                override val kairosNetwork: KairosNetwork
                                                    get() = localNetwork
                                            }
                                            .block()
                                    }

                                override val kairosNetwork: KairosNetwork
                                    get() = localNetwork
                            }
                        scope.block(output)
                    }
                },
            )
        // Defer, in case any EventsLoops / StateLoops still need to be set
        deferAction {
            // Check for immediate cancellation
            if (subRef.get() != null) return@deferAction
            this@observe.takeUntil(endSignal)
                .init
                .connect(evalScope = stateScope.evalScope)
                .activate(evalScope = stateScope.evalScope, outputNode.schedulable)
                ?.let { (conn, needsEval) ->
                    outputNode.upstream = conn
                    if (!subRef.compareAndSet(null, Maybe.present(outputNode))) {
                        // Job's already been cancelled, schedule deactivation
                        scheduleDeactivation(outputNode)
                    } else if (needsEval) {
                        outputNode.schedule(0, evalScope = stateScope.evalScope)
                    }
                } ?: run { childScope.cancel() }
        }
        return handle
    }

    override fun <A, B> Events<A>.mapBuild(transform: BuildScope.(A) -> B): Events<B> {
        val childScope = coroutineScope.childScope()
        return EventsInit(
            constInit(
                "mapBuild",
                mapImpl({ init.connect(evalScope = this) }) { spec, _ ->
                        reenterBuildScope(outerScope = this@BuildScopeImpl, childScope)
                            .transform(spec)
                    }
                    .cached(),
            )
        )
    }

    override fun <K, A, B> Events<Map<K, Maybe<BuildSpec<A>>>>.applyLatestSpecForKey(
        initialSpecs: DeferredValue<Map<K, BuildSpec<B>>>,
        numKeys: Int?,
    ): Pair<Events<Map<K, Maybe<A>>>, DeferredValue<Map<K, B>>> {
        val eventsByKey: GroupedEvents<K, Maybe<BuildSpec<A>>> = groupByKey(numKeys)
        val initOut: Lazy<Map<K, B>> = deferAsync {
            initialSpecs.unwrapped.value.mapValues { (k, spec) ->
                val newEnd = eventsByKey[k]
                val newScope = childBuildScope(newEnd)
                newScope.spec()
            }
        }
        val childScope = coroutineScope.childScope()
        val changesNode: EventsImpl<Map<K, Maybe<A>>> =
            mapImpl(upstream = { this@applyLatestSpecForKey.init.connect(evalScope = this) }) {
                upstreamMap,
                _ ->
                reenterBuildScope(this@BuildScopeImpl, childScope).run {
                    upstreamMap.mapValues { (k: K, ma: Maybe<BuildSpec<A>>) ->
                        ma.map { spec ->
                            val newEnd = eventsByKey[k].skipNext()
                            val newScope = childBuildScope(newEnd)
                            newScope.spec()
                        }
                    }
                }
            }
        val changes: Events<Map<K, Maybe<A>>> =
            EventsInit(constInit("applyLatestForKey", changesNode.cached()))
        // Ensure effects are observed; otherwise init will stay alive longer than expected
        changes.observe()
        return changes to DeferredValue(initOut)
    }

    private fun <A, T : Events<A>, S> buildEvents(
        name: String? = null,
        constructEvents: (InputNode<A>) -> Pair<T, S>,
        builder: suspend S.() -> Unit,
    ): Events<A> {
        var job: Job? = null
        val stopEmitter = newStopEmitter("buildEvents[$name]")
        // Create a child scope that will be kept alive beyond the end of this transaction.
        val childScope = coroutineScope.childScope()
        lateinit var emitter: Pair<T, S>
        val inputNode =
            InputNode<A>(
                activate = {
                    // It's possible that activation occurs after all effects have been run, due
                    // to a MuxDeferred switch-in. For this reason, we need to activate in a new
                    // transaction.
                    check(job == null) { "[$name] already activated" }
                    job =
                        childScope.launchImmediate {
                            network
                                .transaction("buildEvents") {
                                    reenterBuildScope(this@BuildScopeImpl, childScope)
                                        .launchEffect {
                                            builder(emitter.second)
                                            stopEmitter.emit(Unit)
                                        }
                                }
                                .await()
                                .join()
                        }
                },
                deactivate = {
                    checkNotNull(job) { "[$name] already deactivated" }.cancel()
                    job = null
                },
            )
        emitter = constructEvents(inputNode)
        return emitter.first.takeUntil(mergeLeft(stopEmitter, endSignal))
    }

    private fun newStopEmitter(name: String): CoalescingMutableEvents<Unit, Unit> =
        CoalescingMutableEvents(
            name = name,
            coalesce = { _, _: Unit -> },
            network = network,
            getInitialValue = {},
        )

    private fun childBuildScope(newEnd: Events<Any>): BuildScopeImpl {
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
                endSignalOnce.observe { newCoroutineScope.cancel() }
            }
    }

    private fun mutableChildBuildScope(): BuildScopeImpl {
        val childScope = coroutineScope.childScope()
        val stopEmitter = lazy {
            newStopEmitter("mutableChildBuildScope").apply {
                childScope.invokeOnCancel { emit(Unit) }
            }
        }
        return BuildScopeImpl(
            stateScope =
                StateScopeImpl(evalScope = stateScope.evalScope, endSignalLazy = stopEmitter),
            coroutineScope = childScope,
        )
    }
}

private fun EvalScope.reenterBuildScope(
    outerScope: BuildScopeImpl,
    coroutineScope: CoroutineScope,
) =
    BuildScopeImpl(
        stateScope =
            StateScopeImpl(evalScope = this, endSignalLazy = outerScope.stateScope.endSignalLazy),
        coroutineScope,
    )
