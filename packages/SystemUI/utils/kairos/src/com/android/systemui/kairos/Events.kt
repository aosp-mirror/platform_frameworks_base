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
import com.android.systemui.kairos.internal.DemuxImpl
import com.android.systemui.kairos.internal.EventsImpl
import com.android.systemui.kairos.internal.Init
import com.android.systemui.kairos.internal.InitScope
import com.android.systemui.kairos.internal.InputNode
import com.android.systemui.kairos.internal.Network
import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.activated
import com.android.systemui.kairos.internal.cached
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.demuxMap
import com.android.systemui.kairos.internal.filterImpl
import com.android.systemui.kairos.internal.filterJustImpl
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.mapImpl
import com.android.systemui.kairos.internal.mergeNodes
import com.android.systemui.kairos.internal.mergeNodesLeft
import com.android.systemui.kairos.internal.neverImpl
import com.android.systemui.kairos.internal.switchDeferredImplSingle
import com.android.systemui.kairos.internal.switchPromptImplSingle
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.util.Either
import com.android.systemui.kairos.util.Either.Left
import com.android.systemui.kairos.util.Either.Right
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.toMaybe
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** A series of values of type [A] available at discrete points in time. */
@ExperimentalKairosApi
sealed class Events<out A> {
    companion object {
        /** An [Events] with no values. */
        val empty: Events<Nothing> = EmptyEvents
    }
}

/** An [Events] with no values. */
@ExperimentalKairosApi val emptyEvents: Events<Nothing> = Events.empty

/**
 * A forward-reference to an [Events]. Useful for recursive definitions.
 *
 * This reference can be used like a standard [Events], but will throw an error if its [loopback] is
 * unset before the end of the first transaction which accesses it.
 */
@ExperimentalKairosApi
class EventsLoop<A> : Events<A>() {
    private val deferred = CompletableLazy<Events<A>>()

    internal val init: Init<EventsImpl<A>> =
        init(name = null) { deferred.value.init.connect(evalScope = this) }

    /** The [Events] this reference is referring to. */
    var loopback: Events<A>? = null
        set(value) {
            value?.let {
                check(!deferred.isInitialized()) { "EventsLoop.loopback has already been set." }
                deferred.setValue(value)
                field = value
            }
        }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Events<A> = this

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Events<A>) {
        loopback = value
    }

    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

/**
 * Returns an [Events] that acts as a deferred-reference to the [Events] produced by this [Lazy].
 *
 * When the returned [Events] is accessed by the Kairos network, the [Lazy]'s [value][Lazy.value]
 * will be queried and used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi fun <A> Lazy<Events<A>>.defer(): Events<A> = deferInline { value }

/**
 * Returns an [Events] that acts as a deferred-reference to the [Events] produced by this
 * [DeferredValue].
 *
 * When the returned [Events] is accessed by the Kairos network, the [DeferredValue] will be queried
 * and used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi
fun <A> DeferredValue<Events<A>>.defer(): Events<A> = deferInline { unwrapped.value }

/**
 * Returns an [Events] that acts as a deferred-reference to the [Events] produced by [block].
 *
 * When the returned [Events] is accessed by the Kairos network, [block] will be invoked and the
 * returned [Events] will be used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi
fun <A> deferredEvents(block: KairosScope.() -> Events<A>): Events<A> = deferInline {
    NoScope.block()
}

/** Returns an [Events] that emits the new value of this [State] when it changes. */
@ExperimentalKairosApi
val <A> State<A>.changes: Events<A>
    get() = EventsInit(init(name = null) { init.connect(evalScope = this).changes })

/**
 * Returns an [Events] that contains only the [just] results of applying [transform] to each value
 * of the original [Events].
 *
 * @see mapNotNull
 */
@ExperimentalKairosApi
fun <A, B> Events<A>.mapMaybe(transform: TransactionScope.(A) -> Maybe<B>): Events<B> =
    map(transform).filterJust()

/**
 * Returns an [Events] that contains only the non-null results of applying [transform] to each value
 * of the original [Events].
 *
 * @see mapMaybe
 */
@ExperimentalKairosApi
fun <A, B> Events<A>.mapNotNull(transform: TransactionScope.(A) -> B?): Events<B> = mapMaybe {
    transform(it).toMaybe()
}

/** Returns an [Events] containing only values of the original [Events] that are not null. */
@ExperimentalKairosApi
fun <A> Events<A?>.filterNotNull(): Events<A> = mapCheap { it.toMaybe() }.filterJust()

/** Shorthand for `mapNotNull { it as? A }`. */
@ExperimentalKairosApi
inline fun <reified A> Events<*>.filterIsInstance(): Events<A> =
    mapCheap { it as? A }.filterNotNull()

/** Shorthand for `mapMaybe { it }`. */
@ExperimentalKairosApi
fun <A> Events<Maybe<A>>.filterJust(): Events<A> =
    EventsInit(constInit(name = null, filterJustImpl { init.connect(evalScope = this) }))

/**
 * Returns an [Events] containing the results of applying [transform] to each value of the original
 * [Events].
 */
@ExperimentalKairosApi
fun <A, B> Events<A>.map(transform: TransactionScope.(A) -> B): Events<B> {
    val mapped: EventsImpl<B> = mapImpl({ init.connect(evalScope = this) }) { a, _ -> transform(a) }
    return EventsInit(constInit(name = null, mapped.cached()))
}

/**
 * Like [map], but the emission is not cached during the transaction. Use only if [transform] is
 * fast and pure.
 *
 * @see map
 */
@ExperimentalKairosApi
fun <A, B> Events<A>.mapCheap(transform: TransactionScope.(A) -> B): Events<B> =
    EventsInit(
        constInit(name = null, mapImpl({ init.connect(evalScope = this) }) { a, _ -> transform(a) })
    )

/**
 * Returns an [Events] that invokes [action] before each value of the original [Events] is emitted.
 * Useful for logging and debugging.
 *
 * ```
 *   pulse.onEach { foo(it) } == pulse.map { foo(it); it }
 * ```
 *
 * Note that the side effects performed in [onEach] are only performed while the resulting [Events]
 * is connected to an output of the Kairos network. If your goal is to reliably perform side effects
 * in response to an [Events], use the output combinators available in [BuildScope], such as
 * [BuildScope.toSharedFlow] or [BuildScope.observe].
 */
@ExperimentalKairosApi
fun <A> Events<A>.onEach(action: TransactionScope.(A) -> Unit): Events<A> = map {
    action(it)
    it
}

/**
 * Returns an [Events] containing only values of the original [Events] that satisfy the given
 * [predicate].
 */
@ExperimentalKairosApi
fun <A> Events<A>.filter(predicate: TransactionScope.(A) -> Boolean): Events<A> {
    val pulse = filterImpl({ init.connect(evalScope = this) }) { predicate(it) }
    return EventsInit(constInit(name = null, pulse))
}

/**
 * Splits an [Events] of pairs into a pair of [Events], where each returned [Events] emits half of
 * the original.
 *
 * Shorthand for:
 * ```kotlin
 * val lefts = map { it.first }
 * val rights = map { it.second }
 * return Pair(lefts, rights)
 * ```
 */
@ExperimentalKairosApi
fun <A, B> Events<Pair<A, B>>.unzip(): Pair<Events<A>, Events<B>> {
    val lefts = map { it.first }
    val rights = map { it.second }
    return lefts to rights
}

/**
 * Merges the given [Events] into a single [Events] that emits events from both.
 *
 * Because [Events] can only emit one value per transaction, the provided [transformCoincidence]
 * function is used to combine coincident emissions to produce the result value to be emitted by the
 * merged [Events].
 */
@ExperimentalKairosApi
fun <A> Events<A>.mergeWith(
    other: Events<A>,
    name: String? = null,
    transformCoincidence: TransactionScope.(A, A) -> A = { a, _ -> a },
): Events<A> {
    val node =
        mergeNodes(
            name = name,
            getPulse = { init.connect(evalScope = this) },
            getOther = { other.init.connect(evalScope = this) },
        ) { a, b ->
            transformCoincidence(a, b)
        }
    return EventsInit(constInit(name = null, node))
}

/**
 * Merges the given [Events] into a single [Events] that emits events from all. All coincident
 * emissions are collected into the emitted [List], preserving the input ordering.
 *
 * @see mergeWith
 * @see mergeLeft
 */
@ExperimentalKairosApi
fun <A> merge(vararg events: Events<A>): Events<List<A>> = events.asIterable().merge()

/**
 * Merges the given [Events] into a single [Events] that emits events from all. In the case of
 * coincident emissions, the emission from the left-most [Events] is emitted.
 *
 * @see merge
 */
@ExperimentalKairosApi
fun <A> mergeLeft(vararg events: Events<A>): Events<A> = events.asIterable().mergeLeft()

/**
 * Merges the given [Events] into a single [Events] that emits events from all.
 *
 * Because [Events] can only emit one value per transaction, the provided [transformCoincidence]
 * function is used to combine coincident emissions to produce the result value to be emitted by the
 * merged [Events].
 */
// TODO: can be optimized to avoid creating the intermediate list
fun <A> merge(vararg events: Events<A>, transformCoincidence: (A, A) -> A): Events<A> =
    merge(*events).map { l -> l.reduce(transformCoincidence) }

/**
 * Merges the given [Events] into a single [Events] that emits events from all. All coincident
 * emissions are collected into the emitted [List], preserving the input ordering.
 *
 * @see mergeWith
 * @see mergeLeft
 */
@ExperimentalKairosApi
fun <A> Iterable<Events<A>>.merge(): Events<List<A>> =
    EventsInit(constInit(name = null, mergeNodes { map { it.init.connect(evalScope = this) } }))

/**
 * Merges the given [Events] into a single [Events] that emits events from all. In the case of
 * coincident emissions, the emission from the left-most [Events] is emitted.
 *
 * @see merge
 */
@ExperimentalKairosApi
fun <A> Iterable<Events<A>>.mergeLeft(): Events<A> =
    EventsInit(constInit(name = null, mergeNodesLeft { map { it.init.connect(evalScope = this) } }))

/**
 * Creates a new [Events] that emits events from all given [Events]. All simultaneous emissions are
 * collected into the emitted [List], preserving the input ordering.
 *
 * @see mergeWith
 */
@ExperimentalKairosApi fun <A> Sequence<Events<A>>.merge(): Events<List<A>> = asIterable().merge()

/**
 * Creates a new [Events] that emits events from all given [Events]. All simultaneous emissions are
 * collected into the emitted [Map], and are given the same key of the associated [Events] in the
 * input [Map].
 *
 * @see mergeWith
 */
@ExperimentalKairosApi
fun <K, A> Map<K, Events<A>>.merge(): Events<Map<K, A>> =
    asSequence()
        .map { (k, events) -> events.map { a -> k to a } }
        .toList()
        .merge()
        .map { it.toMap() }

/**
 * Returns a [GroupedEvents] that can be used to efficiently split a single [Events] into multiple
 * downstream [Events].
 *
 * The input [Events] emits [Map] instances that specify which downstream [Events] the associated
 * value will be emitted from. These downstream [Events] can be obtained via
 * [GroupedEvents.eventsForKey].
 *
 * An example:
 * ```
 *   val fooEvents: Events<Map<String, Foo>> = ...
 *   val fooById: GroupedEvents<String, Foo> = fooEvents.groupByKey()
 *   val fooBar: Events<Foo> = fooById["bar"]
 * ```
 *
 * This is semantically equivalent to `val fooBar = fooEvents.mapNotNull { map -> map["bar"] }` but
 * is significantly more efficient; specifically, using [mapNotNull] in this way incurs a `O(n)`
 * performance hit, where `n` is the number of different [mapNotNull] operations used to filter on a
 * specific key's presence in the emitted [Map]. [groupByKey] internally uses a [HashMap] to lookup
 * the appropriate downstream [Events], and so operates in `O(1)`.
 *
 * Note that the returned [GroupedEvents] should be cached and re-used to gain the performance
 * benefit.
 *
 * @see selector
 */
@ExperimentalKairosApi
fun <K, A> Events<Map<K, A>>.groupByKey(numKeys: Int? = null): GroupedEvents<K, A> =
    GroupedEvents(demuxMap({ init.connect(this) }, numKeys))

/**
 * Shorthand for `map { mapOf(extractKey(it) to it) }.groupByKey()`
 *
 * @see groupByKey
 */
@ExperimentalKairosApi
fun <K, A> Events<A>.groupBy(
    numKeys: Int? = null,
    extractKey: TransactionScope.(A) -> K,
): GroupedEvents<K, A> = map { mapOf(extractKey(it) to it) }.groupByKey(numKeys)

/**
 * Returns two new [Events] that contain elements from this [Events] that satisfy or don't satisfy
 * [predicate].
 *
 * Using this is equivalent to `upstream.filter(predicate) to upstream.filter { !predicate(it) }`
 * but is more efficient; specifically, [partition] will only invoke [predicate] once per element.
 */
@ExperimentalKairosApi
fun <A> Events<A>.partition(
    predicate: TransactionScope.(A) -> Boolean
): Pair<Events<A>, Events<A>> {
    val grouped: GroupedEvents<Boolean, A> = groupBy(numKeys = 2, extractKey = predicate)
    return Pair(grouped.eventsForKey(true), grouped.eventsForKey(false))
}

/**
 * Returns two new [Events] that contain elements from this [Events]; [Pair.first] will contain
 * [Left] values, and [Pair.second] will contain [Right] values.
 *
 * Using this is equivalent to using [filterIsInstance] in conjunction with [map] twice, once for
 * [Left]s and once for [Right]s, but is slightly more efficient; specifically, the
 * [filterIsInstance] check is only performed once per element.
 */
@ExperimentalKairosApi
fun <A, B> Events<Either<A, B>>.partitionEither(): Pair<Events<A>, Events<B>> {
    val (left, right) = partition { it is Left }
    return Pair(left.mapCheap { (it as Left).value }, right.mapCheap { (it as Right).value })
}

/**
 * A mapping from keys of type [K] to [Events] emitting values of type [A].
 *
 * @see groupByKey
 */
@ExperimentalKairosApi
class GroupedEvents<in K, out A> internal constructor(internal val impl: DemuxImpl<K, A>) {
    /**
     * Returns an [Events] that emits values of type [A] that correspond to the given [key].
     *
     * @see groupByKey
     */
    fun eventsForKey(key: K): Events<A> = EventsInit(constInit(name = null, impl.eventsForKey(key)))

    /**
     * Returns an [Events] that emits values of type [A] that correspond to the given [key].
     *
     * @see groupByKey
     */
    operator fun get(key: K): Events<A> = eventsForKey(key)
}

/**
 * Returns an [Events] that switches to the [Events] contained within this [State] whenever it
 * changes.
 *
 * This switch does take effect until the *next* transaction after [State] changes. For a switch
 * that takes effect immediately, see [switchEventsPromptly].
 */
@ExperimentalKairosApi
fun <A> State<Events<A>>.switchEvents(name: String? = null): Events<A> {
    val patches =
        mapImpl({ init.connect(this).changes }) { newEvents, _ -> newEvents.init.connect(this) }
    return EventsInit(
        constInit(
            name = null,
            switchDeferredImplSingle(
                name = name,
                getStorage = {
                    init.connect(this).getCurrentWithEpoch(this).first.init.connect(this)
                },
                getPatches = { patches },
            ),
        )
    )
}

/**
 * Returns an [Events] that switches to the [Events] contained within this [State] whenever it
 * changes.
 *
 * This switch takes effect immediately within the same transaction that [State] changes. In
 * general, you should prefer [switchEvents] over this method. It is both safer and more performant.
 */
// TODO: parameter to handle coincidental emission from both old and new
@ExperimentalKairosApi
fun <A> State<Events<A>>.switchEventsPromptly(): Events<A> {
    val patches =
        mapImpl({ init.connect(this).changes }) { newEvents, _ -> newEvents.init.connect(this) }
    return EventsInit(
        constInit(
            name = null,
            switchPromptImplSingle(
                getStorage = {
                    init.connect(this).getCurrentWithEpoch(this).first.init.connect(this)
                },
                getPatches = { patches },
            ),
        )
    )
}

/**
 * A mutable [Events] that provides the ability to [emit] values to the network, handling
 * backpressure by coalescing all emissions into batches.
 *
 * @see KairosNetwork.coalescingMutableEvents
 */
@ExperimentalKairosApi
class CoalescingMutableEvents<in In, Out>
internal constructor(
    internal val name: String?,
    internal val coalesce: (old: Lazy<Out>, new: In) -> Out,
    internal val network: Network,
    private val getInitialValue: () -> Out,
    internal val impl: InputNode<Out> = InputNode(),
) : Events<Out>() {
    internal val storage = AtomicReference(false to lazy { getInitialValue() })

    override fun toString(): String = "${this::class.simpleName}@$hashString"

    /**
     * Inserts [value] into the current batch, enqueueing it for emission from this [Events] if not
     * already pending.
     *
     * Backpressure occurs when [emit] is called while the Kairos network is currently in a
     * transaction; if called multiple times, then emissions will be coalesced into a single batch
     * that is then processed when the network is ready.
     */
    fun emit(value: In) {
        val (scheduled, _) =
            storage.getAndUpdate { (_, batch) -> true to CompletableLazy(coalesce(batch, value)) }
        if (!scheduled) {
            @Suppress("DeferredResultUnused")
            network.transaction(
                "CoalescingMutableEvents${name?.let { "($name)" }.orEmpty()}.emit"
            ) {
                val (_, batch) = storage.getAndSet(false to lazy { getInitialValue() })
                impl.visit(this, batch.value)
            }
        }
    }
}

/**
 * A mutable [Events] that provides the ability to [emit] values to the network, handling
 * backpressure by suspending the emitter.
 *
 * @see KairosNetwork.coalescingMutableEvents
 */
@ExperimentalKairosApi
class MutableEvents<T>
internal constructor(internal val network: Network, internal val impl: InputNode<T> = InputNode()) :
    Events<T>() {
    internal val name: String? = null

    private val storage = AtomicReference<Job?>(null)

    override fun toString(): String = "${this::class.simpleName}@$hashString"

    /**
     * Emits a [value] to this [Events], suspending the caller until the Kairos transaction
     * containing the emission has completed.
     */
    suspend fun emit(value: T) {
        coroutineScope {
            var jobOrNull: Job? = null
            val newEmit =
                async(start = CoroutineStart.LAZY) {
                    jobOrNull?.join()
                    network.transaction("MutableEvents.emit") { impl.visit(this, value) }.await()
                }
            jobOrNull = storage.getAndSet(newEmit)
            newEmit.await()
        }
    }
}

private data object EmptyEvents : Events<Nothing>()

internal class EventsInit<out A>(val init: Init<EventsImpl<A>>) : Events<A>() {
    override fun toString(): String = "${this::class.simpleName}@$hashString"
}

internal val <A> Events<A>.init: Init<EventsImpl<A>>
    get() =
        when (this) {
            is EmptyEvents -> constInit("EmptyEvents", neverImpl)
            is EventsInit -> init
            is EventsLoop -> init
            is CoalescingMutableEvents<*, A> -> constInit(name, impl.activated())
            is MutableEvents -> constInit(name, impl.activated())
        }

private inline fun <A> deferInline(crossinline block: InitScope.() -> Events<A>): Events<A> =
    EventsInit(init(name = null) { block().init.connect(evalScope = this) })
