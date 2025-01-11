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
import com.android.systemui.kairos.internal.InputNode
import com.android.systemui.kairos.internal.Network
import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.activated
import com.android.systemui.kairos.internal.cached
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.mapImpl
import com.android.systemui.kairos.internal.neverImpl
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.toMaybe
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A series of values of type [A] available at discrete points in time.
 *
 * [Events] follow these rules:
 * 1. Within a single Kairos network transaction, an [Events] instance will only emit *once*.
 * 2. The order that different [Events] instances emit values within a transaction is undefined, and
 *    are conceptually *simultaneous*.
 * 3. [Events] emissions are *ephemeral* and do not last beyond the transaction they are emitted,
 *    unless explicitly [observed][BuildScope.observe] or [held][StateScope.holdState] as a [State].
 */
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
 * unset before it is [observed][BuildScope.observe].
 *
 * @sample com.android.systemui.kairos.KairosSamples.eventsLoop
 */
@ExperimentalKairosApi
class EventsLoop<A> : Events<A>() {
    private val deferred = CompletableLazy<Events<A>>()

    internal val init: Init<EventsImpl<A>> =
        init(name = null) { deferred.value.init.connect(evalScope = this) }

    /**
     * The [Events] this reference is referring to. Must be set before this [EventsLoop] is
     * [observed][BuildScope.observe].
     */
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
 *
 * ```
 *   fun <A> Lazy<Events<A>>.defer() = deferredEvents { value }
 * ```
 *
 * @see deferredEvents
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
 *
 * ```
 *   fun <A> DeferredValue<Events<A>>.defer() = deferredEvents { get() }
 * ```
 *
 * @see deferredEvents
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

/**
 * Returns an [Events] that contains only the
 * [present][com.android.systemui.kairos.util.Maybe.present] results of applying [transform] to each
 * value of the original [Events].
 *
 * @sample com.android.systemui.kairos.KairosSamples.mapMaybe
 * @see mapNotNull
 */
@ExperimentalKairosApi
fun <A, B> Events<A>.mapMaybe(transform: TransactionScope.(A) -> Maybe<B>): Events<B> =
    map(transform).filterPresent()

/**
 * Returns an [Events] that contains only the non-null results of applying [transform] to each value
 * of the original [Events].
 *
 * ```
 *  fun <A> Events<A>.mapNotNull(transform: TransactionScope.(A) -> B?): Events<B> =
 *      mapMaybe { if (it == null) absent else present(it) }
 * ```
 *
 * @see mapMaybe
 */
@ExperimentalKairosApi
fun <A, B> Events<A>.mapNotNull(transform: TransactionScope.(A) -> B?): Events<B> = mapMaybe {
    transform(it).toMaybe()
}

/**
 * Returns an [Events] containing the results of applying [transform] to each value of the original
 * [Events].
 *
 * @sample com.android.systemui.kairos.KairosSamples.mapEvents
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
 * @sample com.android.systemui.kairos.KairosSamples.mapCheap
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
 *   fun <A> Events<A>.onEach(action: TransactionScope.(A) -> Unit): Events<A> =
 *       map { it.also { action(it) } }
 * ```
 *
 * Note that the side effects performed in [onEach] are only performed while the resulting [Events]
 * is connected to an output of the Kairos network. If your goal is to reliably perform side effects
 * in response to an [Events], use the output combinators available in [BuildScope], such as
 * [BuildScope.toSharedFlow] or [BuildScope.observe].
 */
@ExperimentalKairosApi
fun <A> Events<A>.onEach(action: TransactionScope.(A) -> Unit): Events<A> = map {
    it.also { action(it) }
}

/**
 * Splits an [Events] of pairs into a pair of [Events], where each returned [Events] emits half of
 * the original.
 *
 * ```
 *   fun <A, B> Events<Pair<A, B>>.unzip(): Pair<Events<A>, Events<B>> {
 *       val lefts = map { it.first }
 *       val rights = map { it.second }
 *       return lefts to rights
 *   }
 * ```
 */
@ExperimentalKairosApi
fun <A, B> Events<Pair<A, B>>.unzip(): Pair<Events<A>, Events<B>> {
    val lefts = map { it.first }
    val rights = map { it.second }
    return lefts to rights
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
    private val storage = AtomicReference(false to lazy { getInitialValue() })

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
