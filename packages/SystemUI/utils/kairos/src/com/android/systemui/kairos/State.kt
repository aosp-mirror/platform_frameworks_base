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

package com.android.systemui.kairos

import com.android.systemui.kairos.internal.CompletableLazy
import com.android.systemui.kairos.internal.DerivedMapCheap
import com.android.systemui.kairos.internal.EventsImpl
import com.android.systemui.kairos.internal.Init
import com.android.systemui.kairos.internal.InitScope
import com.android.systemui.kairos.internal.Network
import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.Schedulable
import com.android.systemui.kairos.internal.StateImpl
import com.android.systemui.kairos.internal.StateSource
import com.android.systemui.kairos.internal.activated
import com.android.systemui.kairos.internal.cached
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.constState
import com.android.systemui.kairos.internal.filterImpl
import com.android.systemui.kairos.internal.flatMapStateImpl
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.mapImpl
import com.android.systemui.kairos.internal.mapStateImpl
import com.android.systemui.kairos.internal.mapStateImplCheap
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.internal.zipStateMap
import com.android.systemui.kairos.internal.zipStates
import kotlin.reflect.KProperty

/**
 * A time-varying value with discrete changes. Essentially, a combination of a [Transactional] that
 * holds a value, and an [Events] that emits when the value changes.
 */
@ExperimentalKairosApi
sealed class State<out A> {
    internal abstract val init: Init<StateImpl<A>>
}

/** A [State] that never changes. */
@ExperimentalKairosApi
fun <A> stateOf(value: A): State<A> {
    val operatorName = "stateOf"
    val name = "$operatorName($value)"
    return StateInit(constInit(name, constState(name, operatorName, value)))
}

/**
 * Returns a [State] that acts as a deferred-reference to the [State] produced by this [Lazy].
 *
 * When the returned [State] is accessed by the Kairos network, the [Lazy]'s [value][Lazy.value]
 * will be queried and used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi fun <A> Lazy<State<A>>.defer(): State<A> = deferInline { value }

/**
 * Returns a [State] that acts as a deferred-reference to the [State] produced by this
 * [DeferredValue].
 *
 * When the returned [State] is accessed by the Kairos network, the [DeferredValue] will be queried
 * and used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi
fun <A> DeferredValue<State<A>>.defer(): State<A> = deferInline { unwrapped.value }

/**
 * Returns a [State] that acts as a deferred-reference to the [State] produced by [block].
 *
 * When the returned [State] is accessed by the Kairos network, [block] will be invoked and the
 * returned [State] will be used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi
fun <A> deferredState(block: KairosScope.() -> State<A>): State<A> = deferInline { NoScope.block() }

/**
 * Returns a [State] containing the results of applying [transform] to the value held by the
 * original [State].
 */
@ExperimentalKairosApi
fun <A, B> State<A>.map(transform: KairosScope.(A) -> B): State<B> {
    val operatorName = "map"
    val name = operatorName
    return StateInit(
        init(name) {
            mapStateImpl({ init.connect(this) }, name, operatorName) { NoScope.transform(it) }
        }
    )
}

/**
 * Returns a [State] that transforms the value held inside this [State] by applying it to the
 * [transform].
 *
 * Note that unlike [map], the result is not cached. This means that not only should [transform] be
 * fast and pure, it should be *monomorphic* (1-to-1). Failure to do this means that [changes] for
 * the returned [State] will operate unexpectedly, emitting at rates that do not reflect an
 * observable change to the returned [State].
 */
@ExperimentalKairosApi
fun <A, B> State<A>.mapCheapUnsafe(transform: KairosScope.(A) -> B): State<B> {
    val operatorName = "map"
    val name = operatorName
    return StateInit(
        init(name) { mapStateImplCheap(init, name, operatorName) { NoScope.transform(it) } }
    )
}

/**
 * Returns a [State] by combining the values held inside the given [State]s by applying them to the
 * given function [transform].
 */
@ExperimentalKairosApi
fun <A, B, C> State<A>.combineWith(other: State<B>, transform: KairosScope.(A, B) -> C): State<C> =
    combine(this, other, transform)

/**
 * Splits a [State] of pairs into a pair of [Events][State], where each returned [State] holds half
 * of the original.
 *
 * Shorthand for:
 * ```kotlin
 * val lefts = map { it.first }
 * val rights = map { it.second }
 * return Pair(lefts, rights)
 * ```
 */
@ExperimentalKairosApi
fun <A, B> State<Pair<A, B>>.unzip(): Pair<State<A>, State<B>> {
    val left = map { it.first }
    val right = map { it.second }
    return left to right
}

/**
 * Returns a [State] by combining the values held inside the given [States][State] into a [List].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <A> Iterable<State<A>>.combine(): State<List<A>> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            val states = map { it.init }
            zipStates(
                name,
                operatorName,
                states.size,
                states = init(null) { states.map { it.connect(this) } },
            )
        }
    )
}

/**
 * Returns a [State] by combining the values held inside the given [States][State] into a [Map].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <K, A> Map<K, State<A>>.combine(): State<Map<K, A>> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStateMap(
                name,
                operatorName,
                size,
                states = init(null) { mapValues { it.value.init.connect(evalScope = this) } },
            )
        }
    )
}

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <A, B> Iterable<State<A>>.combine(transform: KairosScope.(List<A>) -> B): State<B> =
    combine().map(transform)

/**
 * Returns a [State] by combining the values held inside the given [State]s into a [List].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <A> combine(vararg states: State<A>): State<List<A>> = states.asIterable().combine()

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <A, B> combine(vararg states: State<A>, transform: KairosScope.(List<A>) -> B): State<B> =
    states.asIterable().combine(transform)

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <A, B, Z> combine(
    stateA: State<A>,
    stateB: State<B>,
    transform: KairosScope.(A, B) -> Z,
): State<Z> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStates(name, operatorName, stateA.init, stateB.init) { a, b ->
                NoScope.transform(a, b)
            }
        }
    )
}

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <A, B, C, Z> combine(
    stateA: State<A>,
    stateB: State<B>,
    stateC: State<C>,
    transform: KairosScope.(A, B, C) -> Z,
): State<Z> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStates(name, operatorName, stateA.init, stateB.init, stateC.init) { a, b, c ->
                NoScope.transform(a, b, c)
            }
        }
    )
}

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <A, B, C, D, Z> combine(
    stateA: State<A>,
    stateB: State<B>,
    stateC: State<C>,
    stateD: State<D>,
    transform: KairosScope.(A, B, C, D) -> Z,
): State<Z> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStates(name, operatorName, stateA.init, stateB.init, stateC.init, stateD.init) {
                a,
                b,
                c,
                d ->
                NoScope.transform(a, b, c, d)
            }
        }
    )
}

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combineWith
 */
@ExperimentalKairosApi
fun <A, B, C, D, E, Z> combine(
    stateA: State<A>,
    stateB: State<B>,
    stateC: State<C>,
    stateD: State<D>,
    stateE: State<E>,
    transform: KairosScope.(A, B, C, D, E) -> Z,
): State<Z> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStates(
                name,
                operatorName,
                stateA.init,
                stateB.init,
                stateC.init,
                stateD.init,
                stateE.init,
            ) { a, b, c, d, e ->
                NoScope.transform(a, b, c, d, e)
            }
        }
    )
}

/** Returns a [State] by applying [transform] to the value held by the original [State]. */
@ExperimentalKairosApi
fun <A, B> State<A>.flatMap(transform: KairosScope.(A) -> State<B>): State<B> {
    val operatorName = "flatMap"
    val name = operatorName
    return StateInit(
        init(name) {
            flatMapStateImpl({ init.connect(this) }, name, operatorName) { a ->
                NoScope.transform(a).init.connect(this)
            }
        }
    )
}

/** Shorthand for `flatMap { it }` */
@ExperimentalKairosApi fun <A> State<State<A>>.flatten() = flatMap { it }

/**
 * Returns a [StateSelector] that can be used to efficiently check if the input [State] is currently
 * holding a specific value.
 *
 * An example:
 * ```
 *   val intState: State<Int> = ...
 *   val intSelector: StateSelector<Int> = intState.selector()
 *   // Tracks if lInt is holding 1
 *   val isOne: State<Boolean> = intSelector.whenSelected(1)
 * ```
 *
 * This is semantically equivalent to `val isOne = intState.map { i -> i == 1 }`, but is
 * significantly more efficient; specifically, using [State.map] in this way incurs a `O(n)`
 * performance hit, where `n` is the number of different [State.map] operations used to track a
 * specific value. [selector] internally uses a [HashMap] to lookup the appropriate downstream
 * [State] to update, and so operates in `O(1)`.
 *
 * Note that the returned [StateSelector] should be cached and re-used to gain the performance
 * benefit.
 *
 * @see groupByKey
 */
@ExperimentalKairosApi
fun <A> State<A>.selector(numDistinctValues: Int? = null): StateSelector<A> =
    StateSelector(
        this,
        changes
            .map { new -> mapOf(new to true, sampleDeferred().get() to false) }
            .groupByKey(numDistinctValues),
    )

/**
 * Tracks the currently selected value of type [A] from an upstream [State].
 *
 * @see selector
 */
@ExperimentalKairosApi
class StateSelector<in A>
internal constructor(
    private val upstream: State<A>,
    private val groupedChanges: GroupedEvents<A, Boolean>,
) {
    /**
     * Returns a [State] that tracks whether the upstream [State] is currently holding the given
     * [value].
     *
     * @see selector
     */
    fun whenSelected(value: A): State<Boolean> {
        val operatorName = "StateSelector#whenSelected"
        val name = "$operatorName[$value]"
        return StateInit(
            init(name) {
                StateImpl(
                    name,
                    operatorName,
                    groupedChanges.impl.eventsForKey(value),
                    DerivedMapCheap(upstream.init) { it == value },
                )
            }
        )
    }

    operator fun get(value: A): State<Boolean> = whenSelected(value)
}

/**
 * A mutable [State] that provides the ability to manually [set its value][setValue].
 *
 * Multiple invocations of [setValue] that occur before a transaction are conflated; only the most
 * recent value is used.
 *
 * Effectively equivalent to:
 * ``` kotlin
 *     ConflatedMutableEvents(kairosNetwork).holdState(initialValue)
 * ```
 */
@ExperimentalKairosApi
class MutableState<T> internal constructor(internal val network: Network, initialValue: Lazy<T>) :
    State<T>() {

    private val input: CoalescingMutableEvents<Lazy<T>, Lazy<T>?> =
        CoalescingMutableEvents(
            name = null,
            coalesce = { _, new -> new },
            network = network,
            getInitialValue = { null },
        )

    override val init: Init<StateImpl<T>>
        get() = state.init

    internal val state = run {
        val changes = input.impl
        val name = null
        val operatorName = "MutableState"
        val state: StateSource<T> = StateSource(initialValue)
        val mapImpl = mapImpl(upstream = { changes.activated() }) { it, _ -> it!!.value }
        val calm: EventsImpl<T> =
            filterImpl({ mapImpl }) { new ->
                    new != state.getCurrentWithEpoch(evalScope = this).first
                }
                .cached()
        @Suppress("DeferredResultUnused")
        network.transaction("MutableState.init") {
            calm.activate(evalScope = this, downstream = Schedulable.S(state))?.let {
                (connection, needsEval) ->
                state.upstreamConnection = connection
                if (needsEval) {
                    schedule(state)
                }
            }
        }
        StateInit(constInit(name, StateImpl(name, operatorName, calm, state)))
    }

    /**
     * Sets the value held by this [State].
     *
     * Invoking will cause a [state change event][State.changes] to emit with the new value, which
     * will then be applied (and thus returned by [TransactionScope.sample]) after the transaction
     * is complete.
     *
     * Multiple invocations of [setValue] that occur before a transaction are conflated; only the
     * most recent value is used.
     */
    fun setValue(value: T) = input.emit(CompletableLazy(value))

    /**
     * Sets the value held by this [State]. The [DeferredValue] will not be queried until this
     * [State] is explicitly [sampled][TransactionScope.sample] or [observed][BuildScope.observe].
     *
     * Invoking will cause a [state change event][State.changes] to emit with the new value, which
     * will then be applied (and thus returned by [TransactionScope.sample]) after the transaction
     * is complete.
     *
     * Multiple invocations of [setValue] that occur before a transaction are conflated; only the
     * most recent value is used.
     */
    fun setValueDeferred(value: DeferredValue<T>) = input.emit(value.unwrapped)
}

/** A forward-reference to a [State], allowing for recursive definitions. */
@ExperimentalKairosApi
class StateLoop<A> : State<A>() {

    private val name: String? = null

    private val deferred = CompletableLazy<State<A>>()

    override val init: Init<StateImpl<A>> =
        init(name) { deferred.value.init.connect(evalScope = this) }

    /** The [State] this [StateLoop] will forward to. */
    var loopback: State<A>? = null
        set(value) {
            value?.let {
                check(!deferred.isInitialized()) { "StateLoop.loopback has already been set." }
                deferred.setValue(value)
                field = value
            }
        }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): State<A> = this

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: State<A>) {
        loopback = value
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal class StateInit<A> internal constructor(override val init: Init<StateImpl<A>>) :
    State<A>() {
    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

private inline fun <A> deferInline(crossinline block: InitScope.() -> State<A>): State<A> =
    StateInit(init(name = null) { block().init.connect(evalScope = this) })
