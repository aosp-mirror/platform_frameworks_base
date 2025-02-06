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

import com.android.systemui.kairos.DeferredValue
import com.android.systemui.kairos.Events
import com.android.systemui.kairos.EventsInit
import com.android.systemui.kairos.EventsLoop
import com.android.systemui.kairos.GroupedEvents
import com.android.systemui.kairos.Incremental
import com.android.systemui.kairos.IncrementalInit
import com.android.systemui.kairos.State
import com.android.systemui.kairos.StateInit
import com.android.systemui.kairos.StateScope
import com.android.systemui.kairos.Stateful
import com.android.systemui.kairos.emptyEvents
import com.android.systemui.kairos.groupByKey
import com.android.systemui.kairos.init
import com.android.systemui.kairos.mapCheap
import com.android.systemui.kairos.merge
import com.android.systemui.kairos.switchEvents
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.map

internal class StateScopeImpl(val evalScope: EvalScope, val endSignalLazy: Lazy<Events<Any>>) :
    InternalStateScope, EvalScope by evalScope {

    override val endSignal: Events<Any> by endSignalLazy

    override val endSignalOnce: Events<Any> by lazy {
        endSignal.nextOnlyInternal("StateScope.endSignal")
    }

    override fun <A> deferredStateScope(block: StateScope.() -> A): DeferredValue<A> =
        DeferredValue(deferAsync { block() })

    override fun <A> Events<A>.holdStateDeferred(initialValue: DeferredValue<A>): State<A> {
        val operatorName = "holdStateDeferred"
        // Ensure state is only collected until the end of this scope
        return truncateToScope(operatorName)
            .holdStateInternalDeferred(operatorName, initialValue.unwrapped)
    }

    override fun <K, V> Events<Map<K, Maybe<V>>>.foldStateMapIncrementally(
        initialValues: DeferredValue<Map<K, V>>
    ): Incremental<K, V> {
        val operatorName = "foldStateMapIncrementally"
        val name = operatorName
        return IncrementalInit(
            constInit(
                operatorName,
                activatedIncremental(
                    name,
                    operatorName,
                    evalScope,
                    { init.connect(this) },
                    initialValues.unwrapped,
                ),
            )
        )
    }

    override fun <K, A, B> Events<Map<K, Maybe<Stateful<A>>>>.applyLatestStatefulForKey(
        init: DeferredValue<Map<K, Stateful<B>>>,
        numKeys: Int?,
    ): Pair<Events<Map<K, Maybe<A>>>, DeferredValue<Map<K, B>>> {
        val eventsByKey: GroupedEvents<K, Maybe<Stateful<A>>> = groupByKey(numKeys)
        val initOut: Lazy<Map<K, B>> = deferAsync {
            init.unwrapped.value.mapValues { (k, stateful) ->
                val newEnd = eventsByKey[k]
                val newScope = childStateScope(newEnd)
                newScope.stateful()
            }
        }
        val changesNode: EventsImpl<Map<K, Maybe<A>>> =
            mapImpl(upstream = { this@applyLatestStatefulForKey.init.connect(evalScope = this) }) {
                upstreamMap,
                _ ->
                upstreamMap.mapValues { (k: K, ma: Maybe<Stateful<A>>) ->
                    reenterStateScope(this@StateScopeImpl).run {
                        ma.map { stateful ->
                            val newEnd = eventsByKey[k].skipNext()
                            val newScope = childStateScope(newEnd)
                            newScope.stateful()
                        }
                    }
                }
            }
        val operatorName = "applyLatestStatefulForKey"
        val name = operatorName
        val changes: Events<Map<K, Maybe<A>>> = EventsInit(constInit(name, changesNode.cached()))
        return changes to DeferredValue(initOut)
    }

    override fun <A> Events<Stateful<A>>.applyStatefuls(): Events<A> {
        val operatorName = "applyStatefuls"
        val name = operatorName
        return EventsInit(
            constInit(
                name,
                mapImpl(upstream = { this@applyStatefuls.init.connect(evalScope = this) }) {
                        stateful,
                        _ ->
                        reenterStateScope(outerScope = this@StateScopeImpl).stateful()
                    }
                    .cached(),
            )
        )
    }

    override fun childStateScope(newEnd: Events<Any>) =
        StateScopeImpl(evalScope, lazy { merge(newEnd, endSignal) })

    private fun <A> Events<A>.truncateToScope(operatorName: String): Events<A> =
        if (endSignalOnce === emptyEvents) {
            this
        } else {
            endSignalOnce
                .mapCheap { emptyEvents }
                .holdStateInternal(operatorName, this)
                .switchEvents()
        }

    private fun <A> Events<A>.nextOnlyInternal(operatorName: String): Events<A> =
        if (this === emptyEvents) {
            this
        } else {
            EventsLoop<A>().apply {
                loopback =
                    mapCheap { emptyEvents }
                        .holdStateInternal(operatorName, this@nextOnlyInternal)
                        .switchEvents()
            }
        }

    private fun <A> Events<A>.holdStateInternal(operatorName: String, init: A): State<A> =
        holdStateInternalDeferred(operatorName, CompletableLazy(init))

    private fun <A> Events<A>.holdStateInternalDeferred(
        operatorName: String,
        init: Lazy<A>,
    ): State<A> {
        val changes = this@holdStateInternalDeferred
        val name = operatorName
        val impl =
            activatedStateSource(
                name,
                operatorName,
                evalScope,
                { changes.init.connect(evalScope = this) },
                init,
            )
        return StateInit(constInit(name, impl))
    }
}

private fun EvalScope.reenterStateScope(outerScope: StateScopeImpl) =
    StateScopeImpl(evalScope = this, endSignalLazy = outerScope.endSignalLazy)
