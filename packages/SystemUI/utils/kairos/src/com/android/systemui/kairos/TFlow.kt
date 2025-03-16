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

import com.android.systemui.kairos.internal.DemuxImpl
import com.android.systemui.kairos.internal.Init
import com.android.systemui.kairos.internal.InitScope
import com.android.systemui.kairos.internal.InputNode
import com.android.systemui.kairos.internal.Network
import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.TFlowImpl
import com.android.systemui.kairos.internal.activated
import com.android.systemui.kairos.internal.cached
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.filterNode
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.map
import com.android.systemui.kairos.internal.mapImpl
import com.android.systemui.kairos.internal.mapMaybeNode
import com.android.systemui.kairos.internal.mergeNodes
import com.android.systemui.kairos.internal.mergeNodesLeft
import com.android.systemui.kairos.internal.neverImpl
import com.android.systemui.kairos.internal.switchDeferredImplSingle
import com.android.systemui.kairos.internal.switchPromptImpl
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.util.Either
import com.android.systemui.kairos.util.Left
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.Right
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.map
import com.android.systemui.kairos.util.toMaybe
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A series of values of type [A] available at discrete points in time. */
@ExperimentalFrpApi
sealed class TFlow<out A> {
    companion object {
        /** A [TFlow] with no values. */
        val empty: TFlow<Nothing> = EmptyFlow
    }
}

/** A [TFlow] with no values. */
@ExperimentalFrpApi val emptyTFlow: TFlow<Nothing> = TFlow.empty

/**
 * A forward-reference to a [TFlow]. Useful for recursive definitions.
 *
 * This reference can be used like a standard [TFlow], but will hold up evaluation of the FRP
 * network until the [loopback] reference is set.
 */
@ExperimentalFrpApi
class TFlowLoop<A> : TFlow<A>() {
    private val deferred = CompletableDeferred<TFlow<A>>()

    internal val init: Init<TFlowImpl<A>> =
        init(name = null) { deferred.await().init.connect(evalScope = this) }

    /** The [TFlow] this reference is referring to. */
    @ExperimentalFrpApi
    var loopback: TFlow<A>? = null
        set(value) {
            value?.let {
                check(deferred.complete(value)) { "TFlowLoop.loopback has already been set." }
                field = value
            }
        }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): TFlow<A> = this

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: TFlow<A>) {
        loopback = value
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

/** TODO */
@ExperimentalFrpApi fun <A> Lazy<TFlow<A>>.defer(): TFlow<A> = deferInline { value }

/** TODO */
@ExperimentalFrpApi
fun <A> FrpDeferredValue<TFlow<A>>.defer(): TFlow<A> = deferInline { unwrapped.await() }

/** TODO */
@ExperimentalFrpApi
fun <A> deferTFlow(block: suspend FrpScope.() -> TFlow<A>): TFlow<A> = deferInline {
    NoScope.runInFrpScope(block)
}

/** Returns a [TFlow] that emits the new value of this [TState] when it changes. */
@ExperimentalFrpApi
val <A> TState<A>.stateChanges: TFlow<A>
    get() = TFlowInit(init(name = null) { init.connect(evalScope = this).changes })

/**
 * Returns a [TFlow] that contains only the [just] results of applying [transform] to each value of
 * the original [TFlow].
 *
 * @see mapNotNull
 */
@ExperimentalFrpApi
fun <A, B> TFlow<A>.mapMaybe(transform: suspend FrpTransactionScope.(A) -> Maybe<B>): TFlow<B> {
    val pulse =
        mapMaybeNode({ init.connect(evalScope = this) }) { runInTransactionScope { transform(it) } }
    return TFlowInit(constInit(name = null, pulse))
}

/**
 * Returns a [TFlow] that contains only the non-null results of applying [transform] to each value
 * of the original [TFlow].
 *
 * @see mapMaybe
 */
@ExperimentalFrpApi
fun <A, B> TFlow<A>.mapNotNull(transform: suspend FrpTransactionScope.(A) -> B?): TFlow<B> =
    mapMaybe {
        transform(it).toMaybe()
    }

/** Returns a [TFlow] containing only values of the original [TFlow] that are not null. */
@ExperimentalFrpApi fun <A> TFlow<A?>.filterNotNull(): TFlow<A> = mapNotNull { it }

/** Shorthand for `mapNotNull { it as? A }`. */
@ExperimentalFrpApi
inline fun <reified A> TFlow<*>.filterIsInstance(): TFlow<A> = mapNotNull { it as? A }

/** Shorthand for `mapMaybe { it }`. */
@ExperimentalFrpApi fun <A> TFlow<Maybe<A>>.filterJust(): TFlow<A> = mapMaybe { it }

/**
 * Returns a [TFlow] containing the results of applying [transform] to each value of the original
 * [TFlow].
 */
@ExperimentalFrpApi
fun <A, B> TFlow<A>.map(transform: suspend FrpTransactionScope.(A) -> B): TFlow<B> {
    val mapped: TFlowImpl<B> =
        mapImpl({ init.connect(evalScope = this) }) { a -> runInTransactionScope { transform(a) } }
    return TFlowInit(constInit(name = null, mapped.cached()))
}

/**
 * Like [map], but the emission is not cached during the transaction. Use only if [transform] is
 * fast and pure.
 *
 * @see map
 */
@ExperimentalFrpApi
fun <A, B> TFlow<A>.mapCheap(transform: suspend FrpTransactionScope.(A) -> B): TFlow<B> =
    TFlowInit(
        constInit(
            name = null,
            mapImpl({ init.connect(evalScope = this) }) { a ->
                runInTransactionScope { transform(a) }
            },
        )
    )

/**
 * Returns a [TFlow] that invokes [action] before each value of the original [TFlow] is emitted.
 * Useful for logging and debugging.
 *
 * ```
 *   pulse.onEach { foo(it) } == pulse.map { foo(it); it }
 * ```
 *
 * Note that the side effects performed in [onEach] are only performed while the resulting [TFlow]
 * is connected to an output of the FRP network. If your goal is to reliably perform side effects in
 * response to a [TFlow], use the output combinators available in [FrpBuildScope], such as
 * [FrpBuildScope.toSharedFlow] or [FrpBuildScope.observe].
 */
@ExperimentalFrpApi
fun <A> TFlow<A>.onEach(action: suspend FrpTransactionScope.(A) -> Unit): TFlow<A> = map {
    action(it)
    it
}

/**
 * Returns a [TFlow] containing only values of the original [TFlow] that satisfy the given
 * [predicate].
 */
@ExperimentalFrpApi
fun <A> TFlow<A>.filter(predicate: suspend FrpTransactionScope.(A) -> Boolean): TFlow<A> {
    val pulse =
        filterNode({ init.connect(evalScope = this) }) { runInTransactionScope { predicate(it) } }
    return TFlowInit(constInit(name = null, pulse.cached()))
}

/**
 * Splits a [TFlow] of pairs into a pair of [TFlows][TFlow], where each returned [TFlow] emits half
 * of the original.
 *
 * Shorthand for:
 * ```kotlin
 * val lefts = map { it.first }
 * val rights = map { it.second }
 * return Pair(lefts, rights)
 * ```
 */
@ExperimentalFrpApi
fun <A, B> TFlow<Pair<A, B>>.unzip(): Pair<TFlow<A>, TFlow<B>> {
    val lefts = map { it.first }
    val rights = map { it.second }
    return lefts to rights
}

/**
 * Merges the given [TFlows][TFlow] into a single [TFlow] that emits events from both.
 *
 * Because [TFlow]s can only emit one value per transaction, the provided [transformCoincidence]
 * function is used to combine coincident emissions to produce the result value to be emitted by the
 * merged [TFlow].
 */
@ExperimentalFrpApi
fun <A> TFlow<A>.mergeWith(
    other: TFlow<A>,
    transformCoincidence: suspend FrpTransactionScope.(A, A) -> A = { a, _ -> a },
): TFlow<A> {
    val node =
        mergeNodes(
            getPulse = { init.connect(evalScope = this) },
            getOther = { other.init.connect(evalScope = this) },
        ) { a, b ->
            runInTransactionScope { transformCoincidence(a, b) }
        }
    return TFlowInit(constInit(name = null, node))
}

/**
 * Merges the given [TFlows][TFlow] into a single [TFlow] that emits events from all. All coincident
 * emissions are collected into the emitted [List], preserving the input ordering.
 *
 * @see mergeWith
 * @see mergeLeft
 */
@ExperimentalFrpApi
fun <A> merge(vararg flows: TFlow<A>): TFlow<List<A>> = flows.asIterable().merge()

/**
 * Merges the given [TFlows][TFlow] into a single [TFlow] that emits events from all. In the case of
 * coincident emissions, the emission from the left-most [TFlow] is emitted.
 *
 * @see merge
 */
@ExperimentalFrpApi
fun <A> mergeLeft(vararg flows: TFlow<A>): TFlow<A> = flows.asIterable().mergeLeft()

/**
 * Merges the given [TFlows][TFlow] into a single [TFlow] that emits events from all.
 *
 * Because [TFlow]s can only emit one value per transaction, the provided [transformCoincidence]
 * function is used to combine coincident emissions to produce the result value to be emitted by the
 * merged [TFlow].
 */
// TODO: can be optimized to avoid creating the intermediate list
fun <A> merge(vararg flows: TFlow<A>, transformCoincidence: (A, A) -> A): TFlow<A> =
    merge(*flows).map { l -> l.reduce(transformCoincidence) }

/**
 * Merges the given [TFlows][TFlow] into a single [TFlow] that emits events from all. All coincident
 * emissions are collected into the emitted [List], preserving the input ordering.
 *
 * @see mergeWith
 * @see mergeLeft
 */
@ExperimentalFrpApi
fun <A> Iterable<TFlow<A>>.merge(): TFlow<List<A>> =
    TFlowInit(constInit(name = null, mergeNodes { map { it.init.connect(evalScope = this) } }))

/**
 * Merges the given [TFlows][TFlow] into a single [TFlow] that emits events from all. In the case of
 * coincident emissions, the emission from the left-most [TFlow] is emitted.
 *
 * @see merge
 */
@ExperimentalFrpApi
fun <A> Iterable<TFlow<A>>.mergeLeft(): TFlow<A> =
    TFlowInit(constInit(name = null, mergeNodesLeft { map { it.init.connect(evalScope = this) } }))

/**
 * Creates a new [TFlow] that emits events from all given [TFlow]s. All simultaneous emissions are
 * collected into the emitted [List], preserving the input ordering.
 *
 * @see mergeWith
 */
@ExperimentalFrpApi fun <A> Sequence<TFlow<A>>.merge(): TFlow<List<A>> = asIterable().merge()

/**
 * Creates a new [TFlow] that emits events from all given [TFlow]s. All simultaneous emissions are
 * collected into the emitted [Map], and are given the same key of the associated [TFlow] in the
 * input [Map].
 *
 * @see mergeWith
 */
@ExperimentalFrpApi
fun <K, A> Map<K, TFlow<A>>.merge(): TFlow<Map<K, A>> =
    asSequence().map { (k, flowA) -> flowA.map { a -> k to a } }.toList().merge().map { it.toMap() }

/**
 * Returns a [GroupedTFlow] that can be used to efficiently split a single [TFlow] into multiple
 * downstream [TFlow]s.
 *
 * The input [TFlow] emits [Map] instances that specify which downstream [TFlow] the associated
 * value will be emitted from. These downstream [TFlow]s can be obtained via
 * [GroupedTFlow.eventsForKey].
 *
 * An example:
 * ```
 *   val sFoo: TFlow<Map<String, Foo>> = ...
 *   val fooById: GroupedTFlow<String, Foo> = sFoo.groupByKey()
 *   val fooBar: TFlow<Foo> = fooById["bar"]
 * ```
 *
 * This is semantically equivalent to `val fooBar = sFoo.mapNotNull { map -> map["bar"] }` but is
 * significantly more efficient; specifically, using [mapNotNull] in this way incurs a `O(n)`
 * performance hit, where `n` is the number of different [mapNotNull] operations used to filter on a
 * specific key's presence in the emitted [Map]. [groupByKey] internally uses a [HashMap] to lookup
 * the appropriate downstream [TFlow], and so operates in `O(1)`.
 *
 * Note that the result [GroupedTFlow] should be cached and re-used to gain the performance benefit.
 *
 * @see selector
 */
@ExperimentalFrpApi
fun <K, A> TFlow<Map<K, A>>.groupByKey(numKeys: Int? = null): GroupedTFlow<K, A> =
    GroupedTFlow(DemuxImpl({ init.connect(this) }, numKeys))

/**
 * Shorthand for `map { mapOf(extractKey(it) to it) }.groupByKey()`
 *
 * @see groupByKey
 */
@ExperimentalFrpApi
fun <K, A> TFlow<A>.groupBy(
    numKeys: Int? = null,
    extractKey: suspend FrpTransactionScope.(A) -> K,
): GroupedTFlow<K, A> = map { mapOf(extractKey(it) to it) }.groupByKey(numKeys)

/**
 * Returns two new [TFlow]s that contain elements from this [TFlow] that satisfy or don't satisfy
 * [predicate].
 *
 * Using this is equivalent to `upstream.filter(predicate) to upstream.filter { !predicate(it) }`
 * but is more efficient; specifically, [partition] will only invoke [predicate] once per element.
 */
@ExperimentalFrpApi
fun <A> TFlow<A>.partition(
    predicate: suspend FrpTransactionScope.(A) -> Boolean
): Pair<TFlow<A>, TFlow<A>> {
    val grouped: GroupedTFlow<Boolean, A> = groupBy(numKeys = 2, extractKey = predicate)
    return Pair(grouped.eventsForKey(true), grouped.eventsForKey(false))
}

/**
 * Returns two new [TFlow]s that contain elements from this [TFlow]; [Pair.first] will contain
 * [Left] values, and [Pair.second] will contain [Right] values.
 *
 * Using this is equivalent to using [filterIsInstance] in conjunction with [map] twice, once for
 * [Left]s and once for [Right]s, but is slightly more efficient; specifically, the
 * [filterIsInstance] check is only performed once per element.
 */
@ExperimentalFrpApi
fun <A, B> TFlow<Either<A, B>>.partitionEither(): Pair<TFlow<A>, TFlow<B>> {
    val (left, right) = partition { it is Left }
    return Pair(left.mapCheap { (it as Left).value }, right.mapCheap { (it as Right).value })
}

/**
 * A mapping from keys of type [K] to [TFlow]s emitting values of type [A].
 *
 * @see groupByKey
 */
@ExperimentalFrpApi
class GroupedTFlow<in K, out A> internal constructor(internal val impl: DemuxImpl<K, A>) {
    /**
     * Returns a [TFlow] that emits values of type [A] that correspond to the given [key].
     *
     * @see groupByKey
     */
    @ExperimentalFrpApi
    fun eventsForKey(key: K): TFlow<A> = TFlowInit(constInit(name = null, impl.eventsForKey(key)))

    /**
     * Returns a [TFlow] that emits values of type [A] that correspond to the given [key].
     *
     * @see groupByKey
     */
    @ExperimentalFrpApi operator fun get(key: K): TFlow<A> = eventsForKey(key)
}

/**
 * Returns a [TFlow] that switches to the [TFlow] contained within this [TState] whenever it
 * changes.
 *
 * This switch does take effect until the *next* transaction after [TState] changes. For a switch
 * that takes effect immediately, see [switchPromptly].
 */
@ExperimentalFrpApi
fun <A> TState<TFlow<A>>.switch(): TFlow<A> {
    return TFlowInit(
        constInit(
            name = null,
            switchDeferredImplSingle(
                getStorage = {
                    init.connect(this).getCurrentWithEpoch(this).first.init.connect(this)
                },
                getPatches = {
                    mapImpl({ init.connect(this).changes }) { newFlow ->
                        newFlow.init.connect(this)
                    }
                },
            ),
        )
    )
}

/**
 * Returns a [TFlow] that switches to the [TFlow] contained within this [TState] whenever it
 * changes.
 *
 * This switch takes effect immediately within the same transaction that [TState] changes. In
 * general, you should prefer [switch] over this method. It is both safer and more performant.
 */
// TODO: parameter to handle coincidental emission from both old and new
@ExperimentalFrpApi
fun <A> TState<TFlow<A>>.switchPromptly(): TFlow<A> {
    val switchNode =
        switchPromptImpl(
            getStorage = {
                mapOf(Unit to init.connect(this).getCurrentWithEpoch(this).first.init.connect(this))
            },
            getPatches = {
                val patches = init.connect(this).changes
                mapImpl({ patches }) { newFlow -> mapOf(Unit to just(newFlow.init.connect(this))) }
            },
        )
    return TFlowInit(constInit(name = null, mapImpl({ switchNode }) { it.getValue(Unit) }))
}

/**
 * A mutable [TFlow] that provides the ability to [emit] values to the flow, handling backpressure
 * by coalescing all emissions into batches.
 *
 * @see FrpNetwork.coalescingMutableTFlow
 */
@ExperimentalFrpApi
class CoalescingMutableTFlow<In, Out>
internal constructor(
    internal val coalesce: (old: Out, new: In) -> Out,
    internal val network: Network,
    private val getInitialValue: () -> Out,
    internal val impl: InputNode<Out> = InputNode(),
) : TFlow<Out>() {
    internal val name: String? = null
    internal val storage = AtomicReference(false to getInitialValue())

    override fun toString(): String = "${this::class.simpleName}@$hashString"

    /**
     * Inserts [value] into the current batch, enqueueing it for emission from this [TFlow] if not
     * already pending.
     *
     * Backpressure occurs when [emit] is called while the FRP network is currently in a
     * transaction; if called multiple times, then emissions will be coalesced into a single batch
     * that is then processed when the network is ready.
     */
    @ExperimentalFrpApi
    fun emit(value: In) {
        val (scheduled, _) = storage.getAndUpdate { (_, old) -> true to coalesce(old, value) }
        if (!scheduled) {
            @Suppress("DeferredResultUnused")
            network.transaction {
                impl.visit(this, storage.getAndSet(false to getInitialValue()).second)
            }
        }
    }
}

/**
 * A mutable [TFlow] that provides the ability to [emit] values to the flow, handling backpressure
 * by suspending the emitter.
 *
 * @see FrpNetwork.coalescingMutableTFlow
 */
@ExperimentalFrpApi
class MutableTFlow<T>
internal constructor(internal val network: Network, internal val impl: InputNode<T> = InputNode()) :
    TFlow<T>() {
    internal val name: String? = null

    private val storage = AtomicReference<Job?>(null)

    override fun toString(): String = "${this::class.simpleName}@$hashString"

    /**
     * Emits a [value] to this [TFlow], suspending the caller until the FRP transaction containing
     * the emission has completed.
     */
    @ExperimentalFrpApi
    suspend fun emit(value: T) {
        coroutineScope {
            var jobOrNull: Job? = null
            val newEmit =
                async(start = CoroutineStart.LAZY) {
                    jobOrNull?.join()
                    network.transaction { impl.visit(this, value) }.await()
                }
            jobOrNull = storage.getAndSet(newEmit)
            newEmit.await()
        }
    }

    //    internal suspend fun emitInCurrentTransaction(value: T, evalScope: EvalScope) {
    //        if (storage.getAndSet(just(value)) is None) {
    //            impl.visit(evalScope)
    //        }
    //    }
}

private data object EmptyFlow : TFlow<Nothing>()

internal class TFlowInit<out A>(val init: Init<TFlowImpl<A>>) : TFlow<A>() {
    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal val <A> TFlow<A>.init: Init<TFlowImpl<A>>
    get() =
        when (this) {
            is EmptyFlow -> constInit("EmptyFlow", neverImpl)
            is TFlowInit -> init
            is TFlowLoop -> init
            is CoalescingMutableTFlow<*, A> -> constInit(name, impl.activated())
            is MutableTFlow -> constInit(name, impl.activated())
        }

private inline fun <A> deferInline(crossinline block: suspend InitScope.() -> TFlow<A>): TFlow<A> =
    TFlowInit(init(name = null) { block().init.connect(evalScope = this) })
