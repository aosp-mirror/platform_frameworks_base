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

package com.android.systemui.kairos.debug

import com.android.systemui.kairos.MutableState
import com.android.systemui.kairos.State
import com.android.systemui.kairos.StateInit
import com.android.systemui.kairos.StateLoop
import com.android.systemui.kairos.internal.DerivedFlatten
import com.android.systemui.kairos.internal.DerivedMap
import com.android.systemui.kairos.internal.DerivedMapCheap
import com.android.systemui.kairos.internal.DerivedZipped
import com.android.systemui.kairos.internal.Init
import com.android.systemui.kairos.internal.StateDerived
import com.android.systemui.kairos.internal.StateImpl
import com.android.systemui.kairos.internal.StateSource
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.None
import com.android.systemui.kairos.util.flatMap
import com.android.systemui.kairos.util.map
import com.android.systemui.kairos.util.none
import com.android.systemui.kairos.util.orElseGet

// object IdGen {
//    private val counter = AtomicLong()
//    fun getId() = counter.getAndIncrement()
// }

typealias StateGraph = Graph<ActivationInfo>

sealed class StateInfo(
    val name: String,
    val value: Maybe<Any?>,
    val operator: String,
    val epoch: Long?,
)

class Source(name: String, value: Maybe<Any?>, operator: String, epoch: Long) :
    StateInfo(name, value, operator, epoch)

class Derived(
    name: String,
    val type: DerivedStateType,
    value: Maybe<Any?>,
    operator: String,
    epoch: Long?,
) : StateInfo(name, value, operator, epoch)

sealed interface DerivedStateType

data object Flatten : DerivedStateType

data class Mapped(val cheap: Boolean) : DerivedStateType

data object Combine : DerivedStateType

sealed class InitInfo(val name: String)

class Uninitialized(name: String) : InitInfo(name)

class Initialized(val state: StateInfo) : InitInfo(state.name)

sealed interface ActivationInfo

class Inactive(val name: String) : ActivationInfo

class Active(val nodeInfo: StateInfo) : ActivationInfo

class Dead(val name: String) : ActivationInfo

data class Edge(val upstream: Any, val downstream: Any, val tag: Any? = null)

data class Graph<T>(val nodes: Map<Any, T>, val edges: List<Edge>)

internal fun State<*>.dump(infoMap: MutableMap<Any, InitInfo>, edges: MutableList<Edge>) {
    val init: Init<StateImpl<Any?>> =
        when (this) {
            is StateInit -> init
            is StateLoop -> init
            is MutableState -> state.init
        }
    when (val stateMaybe = init.getUnsafe()) {
        None -> {
            infoMap[this] = Uninitialized(init.name ?: init.toString())
        }
        is Just -> {
            stateMaybe.value.dump(infoMap, edges)
        }
    }
}

internal fun StateImpl<*>.dump(infoById: MutableMap<Any, InitInfo>, edges: MutableList<Edge>) {
    val state = this
    if (state in infoById) return
    val stateInfo =
        when (state) {
            is StateDerived -> {
                val type =
                    when (state) {
                        is DerivedFlatten -> {
                            state.upstream.dump(infoById, edges)
                            edges.add(
                                Edge(upstream = state.upstream, downstream = state, tag = "outer")
                            )
                            state.upstream
                                .getUnsafe()
                                .orElseGet { null }
                                ?.let {
                                    edges.add(
                                        Edge(upstream = it, downstream = state, tag = "inner")
                                    )
                                    it.dump(infoById, edges)
                                }
                            Flatten
                        }
                        is DerivedMap<*, *> -> {
                            state.upstream.dump(infoById, edges)
                            edges.add(Edge(upstream = state.upstream, downstream = state))
                            Mapped(cheap = false)
                        }
                        is DerivedZipped<*, *, *> -> {
                            state.upstream.forEach { (key, upstream) ->
                                edges.add(
                                    Edge(upstream = upstream, downstream = state, tag = "key=$key")
                                )
                                upstream.dump(infoById, edges)
                            }
                            Combine
                        }
                    }
                Derived(
                    state.name ?: state.operatorName,
                    type,
                    state.getCachedUnsafe(),
                    state.operatorName,
                    state.invalidatedEpoch,
                )
            }
            is StateSource ->
                Source(
                    state.name ?: state.operatorName,
                    state.getStorageUnsafe(),
                    state.operatorName,
                    state.writeEpoch,
                )
            is DerivedMapCheap<*, *> -> {
                state.upstream.dump(infoById, edges)
                edges.add(Edge(upstream = state.upstream, downstream = state))
                val type = Mapped(cheap = true)
                Derived(
                    state.name ?: state.operatorName,
                    type,
                    state.getUnsafe(),
                    state.operatorName,
                    null,
                )
            }
        }
    infoById[state] = Initialized(stateInfo)
}

private fun <A> StateImpl<A>.getUnsafe(): Maybe<A> =
    when (this) {
        is StateDerived -> getCachedUnsafe()
        is StateSource -> getStorageUnsafe()
        is DerivedMapCheap<*, *> -> none
    }

private fun <A> StateImpl<A>.getUnsafeWithEpoch(): Maybe<Pair<A, Long>> =
    when (this) {
        is StateDerived -> getCachedUnsafe().map { it to invalidatedEpoch }
        is StateSource -> getStorageUnsafe().map { it to writeEpoch }
        is DerivedMapCheap<*, *> -> none
    }

/**
 * Returns the current value held in this [State], or [none] if the [State] has not been
 * initialized.
 *
 * The returned [Long] is the *epoch* at which the internal cache was last updated. This can be used
 * to identify values which are out-of-date.
 */
fun <A> State<A>.sampleUnsafe(): Maybe<Pair<A, Long>> =
    when (this) {
        is MutableState -> state.init.getUnsafe().flatMap { it.getUnsafeWithEpoch() }
        is StateInit -> init.getUnsafe().flatMap { it.getUnsafeWithEpoch() }
        is StateLoop -> this.init.getUnsafe().flatMap { it.getUnsafeWithEpoch() }
    }
