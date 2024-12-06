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

import com.android.systemui.kairos.FrpDeferredValue
import com.android.systemui.kairos.FrpStateScope
import com.android.systemui.kairos.FrpStateful
import com.android.systemui.kairos.FrpTransactionScope
import com.android.systemui.kairos.GroupedTFlow
import com.android.systemui.kairos.TFlow
import com.android.systemui.kairos.TFlowInit
import com.android.systemui.kairos.TFlowLoop
import com.android.systemui.kairos.TState
import com.android.systemui.kairos.TStateInit
import com.android.systemui.kairos.emptyTFlow
import com.android.systemui.kairos.groupByKey
import com.android.systemui.kairos.init
import com.android.systemui.kairos.internal.store.ConcurrentHashMapK
import com.android.systemui.kairos.mapCheap
import com.android.systemui.kairos.merge
import com.android.systemui.kairos.switch
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.map

internal class StateScopeImpl(val evalScope: EvalScope, override val endSignal: TFlow<Any>) :
    StateScope, EvalScope by evalScope {

    override val endSignalOnce: TFlow<Any> = endSignal.nextOnlyInternal("StateScope.endSignal")

    private fun <A> TFlow<A>.truncateToScope(operatorName: String): TFlow<A> =
        if (endSignalOnce === emptyTFlow) {
            this
        } else {
            endSignalOnce.mapCheap { emptyTFlow }.toTStateInternal(operatorName, this).switch()
        }

    private fun <A> TFlow<A>.nextOnlyInternal(operatorName: String): TFlow<A> =
        if (this === emptyTFlow) {
            this
        } else {
            TFlowLoop<A>().apply {
                loopback =
                    mapCheap { emptyTFlow }
                        .toTStateInternal(operatorName, this@nextOnlyInternal)
                        .switch()
            }
        }

    private fun <A> TFlow<A>.toTStateInternal(operatorName: String, init: A): TState<A> =
        toTStateInternalDeferred(operatorName, CompletableLazy(init))

    private fun <A> TFlow<A>.toTStateInternalDeferred(
        operatorName: String,
        init: Lazy<A>,
    ): TState<A> {
        val changes = this@toTStateInternalDeferred
        val name = operatorName
        val impl =
            activatedTStateSource(
                name,
                operatorName,
                evalScope,
                { changes.init.connect(evalScope = this) },
                init,
            )
        return TStateInit(constInit(name, impl))
    }

    private fun <R> deferredInternal(block: FrpStateScope.() -> R): FrpDeferredValue<R> =
        FrpDeferredValue(deferAsync { runInStateScope(block) })

    private fun <A> TFlow<A>.toTStateDeferredInternal(
        initialValue: FrpDeferredValue<A>
    ): TState<A> {
        val operatorName = "toTStateDeferred"
        // Ensure state is only collected until the end of this scope
        return truncateToScope(operatorName)
            .toTStateInternalDeferred(operatorName, initialValue.unwrapped)
    }

    private fun <K, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementallyInternal(
        name: String?,
        storage: TState<Map<K, TFlow<V>>>,
    ): TFlow<Map<K, V>> {
        val patches =
            mapImpl({ init.connect(this) }) { patch, _ ->
                patch.mapValues { (_, m) -> m.map { flow -> flow.init.connect(this) } }.asIterable()
            }
        return TFlowInit(
            constInit(
                name,
                switchDeferredImpl(
                        name = name,
                        getStorage = {
                            storage.init
                                .connect(this)
                                .getCurrentWithEpoch(this)
                                .first
                                .mapValues { (_, flow) -> flow.init.connect(this) }
                                .asIterable()
                        },
                        getPatches = { patches },
                        storeFactory = ConcurrentHashMapK.Factory(),
                    )
                    .awaitValues(),
            )
        )
    }

    private fun <K, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementallyPromptInternal(
        storage: TState<Map<K, TFlow<V>>>,
        name: String?,
    ): TFlow<Map<K, V>> {
        val patches =
            mapImpl({ init.connect(this) }) { patch, _ ->
                patch.mapValues { (_, m) -> m.map { flow -> flow.init.connect(this) } }.asIterable()
            }
        return TFlowInit(
            constInit(
                name,
                switchPromptImpl(
                        name = name,
                        getStorage = {
                            storage.init
                                .connect(this)
                                .getCurrentWithEpoch(this)
                                .first
                                .mapValues { (_, flow) -> flow.init.connect(this) }
                                .asIterable()
                        },
                        getPatches = { patches },
                        storeFactory = ConcurrentHashMapK.Factory(),
                    )
                    .awaitValues(),
            )
        )
    }

    private fun <K, A, B> TFlow<Map<K, Maybe<FrpStateful<A>>>>.applyLatestStatefulForKeyInternal(
        init: FrpDeferredValue<Map<K, FrpStateful<B>>>,
        numKeys: Int?,
    ): Pair<TFlow<Map<K, Maybe<A>>>, FrpDeferredValue<Map<K, B>>> {
        val eventsByKey: GroupedTFlow<K, Maybe<FrpStateful<A>>> = groupByKey(numKeys)
        val initOut: Lazy<Map<K, B>> = deferAsync {
            init.unwrapped.value.mapValues { (k, stateful) ->
                val newEnd = with(frpScope) { eventsByKey[k] }
                val newScope = childStateScope(newEnd)
                newScope.runInStateScope(stateful)
            }
        }
        val changesNode: TFlowImpl<Map<K, Maybe<A>>> =
            mapImpl(
                upstream = { this@applyLatestStatefulForKeyInternal.init.connect(evalScope = this) }
            ) { upstreamMap, _ ->
                upstreamMap.mapValues { (k: K, ma: Maybe<FrpStateful<A>>) ->
                    reenterStateScope(this@StateScopeImpl).run {
                        ma.map { stateful ->
                            val newEnd = with(frpScope) { eventsByKey[k].skipNext() }
                            val newScope = childStateScope(newEnd)
                            newScope.runInStateScope(stateful)
                        }
                    }
                }
            }
        val operatorName = "applyLatestStatefulForKey"
        val name = operatorName
        val changes: TFlow<Map<K, Maybe<A>>> = TFlowInit(constInit(name, changesNode.cached()))
        return changes to FrpDeferredValue(initOut)
    }

    private fun <A> TFlow<FrpStateful<A>>.observeStatefulsInternal(): TFlow<A> {
        val operatorName = "observeStatefuls"
        val name = operatorName
        return TFlowInit(
            constInit(
                name,
                mapImpl(
                        upstream = { this@observeStatefulsInternal.init.connect(evalScope = this) }
                    ) { stateful, _ ->
                        reenterStateScope(outerScope = this@StateScopeImpl)
                            .runInStateScope(stateful)
                    }
                    .cached(),
            )
        )
    }

    override val frpScope: FrpStateScope = FrpStateScopeImpl()

    private inner class FrpStateScopeImpl :
        FrpStateScope, FrpTransactionScope by evalScope.frpScope {

        override fun <A> deferredStateScope(block: FrpStateScope.() -> A): FrpDeferredValue<A> =
            deferredInternal(block)

        override fun <A> TFlow<A>.holdDeferred(initialValue: FrpDeferredValue<A>): TState<A> =
            toTStateDeferredInternal(initialValue)

        override fun <K, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementally(
            name: String?,
            initialTFlows: FrpDeferredValue<Map<K, TFlow<V>>>,
        ): TFlow<Map<K, V>> {
            val storage: TState<Map<K, TFlow<V>>> = foldMapIncrementally(initialTFlows)
            return mergeIncrementallyInternal(name, storage)
        }

        override fun <K, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementallyPromptly(
            initialTFlows: FrpDeferredValue<Map<K, TFlow<V>>>,
            name: String?,
        ): TFlow<Map<K, V>> {
            val storage: TState<Map<K, TFlow<V>>> = foldMapIncrementally(initialTFlows)
            return mergeIncrementallyPromptInternal(storage, name)
        }

        override fun <K, A, B> TFlow<Map<K, Maybe<FrpStateful<A>>>>.applyLatestStatefulForKey(
            init: FrpDeferredValue<Map<K, FrpStateful<B>>>,
            numKeys: Int?,
        ): Pair<TFlow<Map<K, Maybe<A>>>, FrpDeferredValue<Map<K, B>>> =
            applyLatestStatefulForKeyInternal(init, numKeys)

        override fun <A> TFlow<FrpStateful<A>>.applyStatefuls(): TFlow<A> =
            observeStatefulsInternal()
    }

    override fun <R> runInStateScope(block: FrpStateScope.() -> R): R = frpScope.block()

    override fun childStateScope(newEnd: TFlow<Any>) =
        StateScopeImpl(evalScope, merge(newEnd, endSignal))
}

private fun EvalScope.reenterStateScope(outerScope: StateScopeImpl) =
    StateScopeImpl(evalScope = this, endSignal = outerScope.endSignal)
