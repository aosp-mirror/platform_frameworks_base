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
import com.android.systemui.kairos.util.WithPrev
import kotlin.reflect.KProperty

/**
 * A time-varying value with discrete changes. Conceptually, a combination of a [Transactional] that
 * holds a value, and an [Events] that emits when the value [changes].
 *
 * [States][State] follow these rules:
 * 1. In the same transaction that [changes] emits a new value, [sample] will continue to return the
 *    previous value.
 * 2. Unless it is [constant][stateOf], [States][State] can only be created via [StateScope]
 *    operations, or derived from other existing [States][State] via [State.map], [combine], etc.
 * 3. [States][State] can only be [sampled][TransactionScope.sample] within a [TransactionScope].
 *
 * @sample com.android.systemui.kairos.KairosSamples.states
 */
@ExperimentalKairosApi
sealed class State<out A> {
    internal abstract val init: Init<StateImpl<A>>
}

/**
 * Returns a constant [State] that never changes. [changes] is equivalent to [emptyEvents] and
 * [TransactionScope.sample] will always produce [value].
 */
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
 *
 * ``` kotlin
 *   fun <A> Lazy<State<A>>.defer() = deferredState { value }
 * ```
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
 *
 * ``` kotlin
 *   fun <A> DeferredValue<State<A>>.defer() = deferredState { get() }
 * ```
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
 *
 * @sample com.android.systemui.kairos.KairosSamples.mapState
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
 * Note that unlike [State.map], the result is not cached. This means that not only should
 * [transform] be fast and pure, it should be *monomorphic* (1-to-1). Failure to do this means that
 * [changes] for the returned [State] will operate unexpectedly, emitting at rates that do not
 * reflect an observable change to the returned [State].
 *
 * @see State.map
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
 * Splits a [State] of pairs into a pair of [Events][State], where each returned [State] holds half
 * of the original.
 *
 * ``` kotlin
 *   fun <A, B> State<Pair<A, B>>.unzip(): Pair<State<A>, State<B>> {
 *       val first = map { it.first }
 *       val second = map { it.second }
 *       return first to second
 *   }
 * ```
 */
@ExperimentalKairosApi
fun <A, B> State<Pair<A, B>>.unzip(): Pair<State<A>, State<B>> {
    val first = map { it.first }
    val second = map { it.second }
    return first to second
}

/**
 * Returns a [State] by applying [transform] to the value held by the original [State].
 *
 * @sample com.android.systemui.kairos.KairosSamples.flatMap
 */
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

/**
 * Returns a [State] that behaves like the current value of the original [State].
 *
 * ``` kotlin
 *   fun <A> State<State<A>>.flatten() = flatMap { it }
 * ```
 *
 * @see flatMap
 */
@ExperimentalKairosApi fun <A> State<State<A>>.flatten() = flatMap { it }

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

    // TODO: not convinced this is totally safe
    //  - at least for the BuildScope smart-constructor, we can avoid the network.transaction { }
    //    call since we're already in a transaction
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

/**
 * A forward-reference to a [State]. Useful for recursive definitions.
 *
 * This reference can be used like a standard [State], but will throw an error if its [loopback] is
 * unset before it is [observed][BuildScope.observe] or [sampled][TransactionScope.sample].
 *
 * Note that it is safe to invoke [TransactionScope.sampleDeferred] before [loopback] is set,
 * provided the returned [DeferredValue] is not [queried][KairosScope.get].
 *
 * @sample com.android.systemui.kairos.KairosSamples.stateLoop
 */
@ExperimentalKairosApi
class StateLoop<A> : State<A>() {

    private val name: String? = null

    private val deferred = CompletableLazy<State<A>>()

    override val init: Init<StateImpl<A>> =
        init(name) { deferred.value.init.connect(evalScope = this) }

    /**
     * The [State] this reference is referring to. Must be set before this [StateLoop] is
     * [observed][BuildScope.observe] or [sampled][TransactionScope.sample].
     */
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

/**
 * Like [changes] but also includes the old value of this [State].
 *
 * Shorthand for:
 * ``` kotlin
 *     stateChanges.map { WithPrev(previousValue = sample(), newValue = it) }
 * ```
 */
@ExperimentalKairosApi
val <A> State<A>.transitions: Events<WithPrev<A, A>>
    get() = changes.map { WithPrev(previousValue = sample(), newValue = it) }

/**
 * Returns an [Events] that emits the new value of this [State] when it changes.
 *
 * @sample com.android.systemui.kairos.KairosSamples.changes
 */
@ExperimentalKairosApi
val <A> State<A>.changes: Events<A>
    get() = EventsInit(init(name = null) { init.connect(evalScope = this).changes })
