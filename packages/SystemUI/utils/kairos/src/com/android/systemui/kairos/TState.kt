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

import com.android.systemui.kairos.internal.DerivedMapCheap
import com.android.systemui.kairos.internal.Init
import com.android.systemui.kairos.internal.InitScope
import com.android.systemui.kairos.internal.Network
import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.Schedulable
import com.android.systemui.kairos.internal.TFlowImpl
import com.android.systemui.kairos.internal.TStateImpl
import com.android.systemui.kairos.internal.TStateSource
import com.android.systemui.kairos.internal.activated
import com.android.systemui.kairos.internal.cached
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.constS
import com.android.systemui.kairos.internal.filterNode
import com.android.systemui.kairos.internal.flatMap
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.map
import com.android.systemui.kairos.internal.mapCheap
import com.android.systemui.kairos.internal.mapImpl
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.internal.zipStates
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A time-varying value with discrete changes. Essentially, a combination of a [Transactional] that
 * holds a value, and a [TFlow] that emits when the value changes.
 */
@ExperimentalFrpApi sealed class TState<out A>

/** A [TState] that never changes. */
@ExperimentalFrpApi
fun <A> tStateOf(value: A): TState<A> {
    val operatorName = "tStateOf"
    val name = "$operatorName($value)"
    return TStateInit(constInit(name, constS(name, operatorName, value)))
}

/** TODO */
@ExperimentalFrpApi fun <A> Lazy<TState<A>>.defer(): TState<A> = deferInline { value }

/** TODO */
@ExperimentalFrpApi
fun <A> FrpDeferredValue<TState<A>>.defer(): TState<A> = deferInline { unwrapped.await() }

/** TODO */
@ExperimentalFrpApi
fun <A> deferTState(block: suspend FrpScope.() -> TState<A>): TState<A> = deferInline {
    NoScope.runInFrpScope(block)
}

/**
 * Returns a [TState] containing the results of applying [transform] to the value held by the
 * original [TState].
 */
@ExperimentalFrpApi
fun <A, B> TState<A>.map(transform: suspend FrpScope.(A) -> B): TState<B> {
    val operatorName = "map"
    val name = operatorName
    return TStateInit(
        init(name) {
            init.connect(evalScope = this).map(name, operatorName) {
                NoScope.runInFrpScope { transform(it) }
            }
        }
    )
}

/**
 * Returns a [TState] that transforms the value held inside this [TState] by applying it to the
 * [transform].
 *
 * Note that unlike [map], the result is not cached. This means that not only should [transform] be
 * fast and pure, it should be *monomorphic* (1-to-1). Failure to do this means that [stateChanges]
 * for the returned [TState] will operate unexpectedly, emitting at rates that do not reflect an
 * observable change to the returned [TState].
 */
@ExperimentalFrpApi
fun <A, B> TState<A>.mapCheapUnsafe(transform: suspend FrpScope.(A) -> B): TState<B> {
    val operatorName = "map"
    val name = operatorName
    return TStateInit(
        init(name) {
            init.connect(evalScope = this).mapCheap(name, operatorName) {
                NoScope.runInFrpScope { transform(it) }
            }
        }
    )
}

/**
 * Returns a [TState] by combining the values held inside the given [TState]s by applying them to
 * the given function [transform].
 */
@ExperimentalFrpApi
fun <A, B, C> TState<A>.combineWith(
    other: TState<B>,
    transform: suspend FrpScope.(A, B) -> C,
): TState<C> = combine(this, other, transform)

/**
 * Splits a [TState] of pairs into a pair of [TFlows][TState], where each returned [TState] holds
 * hald of the original.
 *
 * Shorthand for:
 * ```kotlin
 * val lefts = map { it.first }
 * val rights = map { it.second }
 * return Pair(lefts, rights)
 * ```
 */
@ExperimentalFrpApi
fun <A, B> TState<Pair<A, B>>.unzip(): Pair<TState<A>, TState<B>> {
    val left = map { it.first }
    val right = map { it.second }
    return left to right
}

/**
 * Returns a [TState] by combining the values held inside the given [TStates][TState] into a [List].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <A> Iterable<TState<A>>.combine(): TState<List<A>> {
    val operatorName = "combine"
    val name = operatorName
    return TStateInit(
        init(name) {
            zipStates(name, operatorName, states = map { it.init.connect(evalScope = this) })
        }
    )
}

/**
 * Returns a [TState] by combining the values held inside the given [TStates][TState] into a [Map].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <K : Any, A> Map<K, TState<A>>.combine(): TState<Map<K, A>> {
    val operatorName = "combine"
    val name = operatorName
    return TStateInit(
        init(name) {
            zipStates(
                name,
                operatorName,
                states = mapValues { it.value.init.connect(evalScope = this) },
            )
        }
    )
}

/**
 * Returns a [TState] whose value is generated with [transform] by combining the current values of
 * each given [TState].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <A, B> Iterable<TState<A>>.combine(transform: suspend FrpScope.(List<A>) -> B): TState<B> =
    combine().map(transform)

/**
 * Returns a [TState] by combining the values held inside the given [TState]s into a [List].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <A> combine(vararg states: TState<A>): TState<List<A>> = states.asIterable().combine()

/**
 * Returns a [TState] whose value is generated with [transform] by combining the current values of
 * each given [TState].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <A, B> combine(
    vararg states: TState<A>,
    transform: suspend FrpScope.(List<A>) -> B,
): TState<B> = states.asIterable().combine(transform)

/**
 * Returns a [TState] whose value is generated with [transform] by combining the current values of
 * each given [TState].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <A, B, Z> combine(
    stateA: TState<A>,
    stateB: TState<B>,
    transform: suspend FrpScope.(A, B) -> Z,
): TState<Z> {
    val operatorName = "combine"
    val name = operatorName
    return TStateInit(
        init(name) {
            coroutineScope {
                val dl1: Deferred<TStateImpl<A>> = async {
                    stateA.init.connect(evalScope = this@init)
                }
                val dl2: Deferred<TStateImpl<B>> = async {
                    stateB.init.connect(evalScope = this@init)
                }
                zipStates(name, operatorName, dl1.await(), dl2.await()) { a, b ->
                    NoScope.runInFrpScope { transform(a, b) }
                }
            }
        }
    )
}

/**
 * Returns a [TState] whose value is generated with [transform] by combining the current values of
 * each given [TState].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <A, B, C, Z> combine(
    stateA: TState<A>,
    stateB: TState<B>,
    stateC: TState<C>,
    transform: suspend FrpScope.(A, B, C) -> Z,
): TState<Z> {
    val operatorName = "combine"
    val name = operatorName
    return TStateInit(
        init(name) {
            coroutineScope {
                val dl1: Deferred<TStateImpl<A>> = async {
                    stateA.init.connect(evalScope = this@init)
                }
                val dl2: Deferred<TStateImpl<B>> = async {
                    stateB.init.connect(evalScope = this@init)
                }
                val dl3: Deferred<TStateImpl<C>> = async {
                    stateC.init.connect(evalScope = this@init)
                }
                zipStates(name, operatorName, dl1.await(), dl2.await(), dl3.await()) { a, b, c ->
                    NoScope.runInFrpScope { transform(a, b, c) }
                }
            }
        }
    )
}

/**
 * Returns a [TState] whose value is generated with [transform] by combining the current values of
 * each given [TState].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <A, B, C, D, Z> combine(
    stateA: TState<A>,
    stateB: TState<B>,
    stateC: TState<C>,
    stateD: TState<D>,
    transform: suspend FrpScope.(A, B, C, D) -> Z,
): TState<Z> {
    val operatorName = "combine"
    val name = operatorName
    return TStateInit(
        init(name) {
            coroutineScope {
                val dl1: Deferred<TStateImpl<A>> = async {
                    stateA.init.connect(evalScope = this@init)
                }
                val dl2: Deferred<TStateImpl<B>> = async {
                    stateB.init.connect(evalScope = this@init)
                }
                val dl3: Deferred<TStateImpl<C>> = async {
                    stateC.init.connect(evalScope = this@init)
                }
                val dl4: Deferred<TStateImpl<D>> = async {
                    stateD.init.connect(evalScope = this@init)
                }
                zipStates(name, operatorName, dl1.await(), dl2.await(), dl3.await(), dl4.await()) {
                    a,
                    b,
                    c,
                    d ->
                    NoScope.runInFrpScope { transform(a, b, c, d) }
                }
            }
        }
    )
}

/**
 * Returns a [TState] whose value is generated with [transform] by combining the current values of
 * each given [TState].
 *
 * @see TState.combineWith
 */
@ExperimentalFrpApi
fun <A, B, C, D, E, Z> combine(
    stateA: TState<A>,
    stateB: TState<B>,
    stateC: TState<C>,
    stateD: TState<D>,
    stateE: TState<E>,
    transform: suspend FrpScope.(A, B, C, D, E) -> Z,
): TState<Z> {
    val operatorName = "combine"
    val name = operatorName
    return TStateInit(
        init(name) {
            coroutineScope {
                val dl1: Deferred<TStateImpl<A>> = async {
                    stateA.init.connect(evalScope = this@init)
                }
                val dl2: Deferred<TStateImpl<B>> = async {
                    stateB.init.connect(evalScope = this@init)
                }
                val dl3: Deferred<TStateImpl<C>> = async {
                    stateC.init.connect(evalScope = this@init)
                }
                val dl4: Deferred<TStateImpl<D>> = async {
                    stateD.init.connect(evalScope = this@init)
                }
                val dl5: Deferred<TStateImpl<E>> = async {
                    stateE.init.connect(evalScope = this@init)
                }
                zipStates(
                    name,
                    operatorName,
                    dl1.await(),
                    dl2.await(),
                    dl3.await(),
                    dl4.await(),
                    dl5.await(),
                ) { a, b, c, d, e ->
                    NoScope.runInFrpScope { transform(a, b, c, d, e) }
                }
            }
        }
    )
}

/** Returns a [TState] by applying [transform] to the value held by the original [TState]. */
@ExperimentalFrpApi
fun <A, B> TState<A>.flatMap(transform: suspend FrpScope.(A) -> TState<B>): TState<B> {
    val operatorName = "flatMap"
    val name = operatorName
    return TStateInit(
        init(name) {
            init.connect(this).flatMap(name, operatorName) { a ->
                NoScope.runInFrpScope { transform(a) }.init.connect(this)
            }
        }
    )
}

/** Shorthand for `flatMap { it }` */
@ExperimentalFrpApi fun <A> TState<TState<A>>.flatten() = flatMap { it }

/**
 * Returns a [TStateSelector] that can be used to efficiently check if the input [TState] is
 * currently holding a specific value.
 *
 * An example:
 * ```
 *   val lInt: TState<Int> = ...
 *   val intSelector: TStateSelector<Int> = lInt.selector()
 *   // Tracks if lInt is holding 1
 *   val isOne: TState<Boolean> = intSelector.whenSelected(1)
 * ```
 *
 * This is semantically equivalent to `val isOne = lInt.map { i -> i == 1 }`, but is significantly
 * more efficient; specifically, using [TState.map] in this way incurs a `O(n)` performance hit,
 * where `n` is the number of different [TState.map] operations used to track a specific value.
 * [selector] internally uses a [HashMap] to lookup the appropriate downstream [TState] to update,
 * and so operates in `O(1)`.
 *
 * Note that the result [TStateSelector] should be cached and re-used to gain the performance
 * benefit.
 *
 * @see groupByKey
 */
@ExperimentalFrpApi
fun <A> TState<A>.selector(numDistinctValues: Int? = null): TStateSelector<A> =
    TStateSelector(
        this,
        stateChanges
            .map { new -> mapOf(new to true, sampleDeferred().get() to false) }
            .groupByKey(numDistinctValues),
    )

/**
 * Tracks the currently selected value of type [A] from an upstream [TState].
 *
 * @see selector
 */
@ExperimentalFrpApi
class TStateSelector<in A>
internal constructor(
    private val upstream: TState<A>,
    private val groupedChanges: GroupedTFlow<A, Boolean>,
) {
    /**
     * Returns a [TState] that tracks whether the upstream [TState] is currently holding the given
     * [value].
     *
     * @see selector
     */
    @ExperimentalFrpApi
    fun whenSelected(value: A): TState<Boolean> {
        val operatorName = "TStateSelector#whenSelected"
        val name = "$operatorName[$value]"
        return TStateInit(
            init(name) {
                DerivedMapCheap(
                    name,
                    operatorName,
                    upstream = upstream.init.connect(evalScope = this),
                    changes = groupedChanges.impl.eventsForKey(value),
                ) {
                    it == value
                }
            }
        )
    }

    @ExperimentalFrpApi operator fun get(value: A): TState<Boolean> = whenSelected(value)
}

/** TODO */
@ExperimentalFrpApi
class MutableTState<T>
internal constructor(internal val network: Network, initialValue: Deferred<T>) : TState<T>() {

    private val input: CoalescingMutableTFlow<Deferred<T>, Deferred<T>?> =
        CoalescingMutableTFlow(
            coalesce = { _, new -> new },
            network = network,
            getInitialValue = { null },
        )

    internal val tState = run {
        val changes = input.impl
        val name = null
        val operatorName = "MutableTState"
        lateinit var state: TStateSource<T>
        val calm: TFlowImpl<T> =
            filterNode({ mapImpl(upstream = { changes.activated() }) { it!!.await() } }) { new ->
                    new != state.getCurrentWithEpoch(evalScope = this).first
                }
                .cached()
        state = TStateSource(name, operatorName, initialValue, calm)
        @Suppress("DeferredResultUnused")
        network.transaction {
            calm.activate(evalScope = this, downstream = Schedulable.S(state))?.let {
                (connection, needsEval) ->
                state.upstreamConnection = connection
                if (needsEval) {
                    schedule(state)
                }
            }
        }
        TStateInit(constInit(name, state))
    }

    /** TODO */
    @ExperimentalFrpApi fun setValue(value: T) = input.emit(CompletableDeferred(value))

    @ExperimentalFrpApi
    fun setValueDeferred(value: FrpDeferredValue<T>) = input.emit(value.unwrapped)
}

/** A forward-reference to a [TState], allowing for recursive definitions. */
@ExperimentalFrpApi
class TStateLoop<A> : TState<A>() {

    private val name: String? = null

    private val deferred = CompletableDeferred<TState<A>>()

    internal val init: Init<TStateImpl<A>> =
        init(name) { deferred.await().init.connect(evalScope = this) }

    /** The [TState] this [TStateLoop] will forward to. */
    @ExperimentalFrpApi
    var loopback: TState<A>? = null
        set(value) {
            value?.let {
                check(deferred.complete(value)) { "TStateLoop.loopback has already been set." }
                field = value
            }
        }

    @ExperimentalFrpApi
    operator fun getValue(thisRef: Any?, property: KProperty<*>): TState<A> = this

    @ExperimentalFrpApi
    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: TState<A>) {
        loopback = value
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal class TStateInit<A> internal constructor(internal val init: Init<TStateImpl<A>>) :
    TState<A>() {
    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal val <A> TState<A>.init: Init<TStateImpl<A>>
    get() =
        when (this) {
            is TStateInit -> init
            is TStateLoop -> init
            is MutableTState -> tState.init
        }

private inline fun <A> deferInline(
    crossinline block: suspend InitScope.() -> TState<A>
): TState<A> = TStateInit(init(name = null) { block().init.connect(evalScope = this) })
