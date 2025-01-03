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

import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.Maybe.Just
import com.android.systemui.kairos.util.WithPrev
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.map
import com.android.systemui.kairos.util.mapMaybeValues
import com.android.systemui.kairos.util.none
import com.android.systemui.kairos.util.zipWith

// TODO: caching story? should each Scope have a cache of applied Stateful instances?
/** A computation that can accumulate [Events] into [State]. */
typealias Stateful<R> = StateScope.() -> R

/**
 * Returns a [Stateful] that, when [applied][StateScope.applyStateful], invokes [block] with the
 * applier's [StateScope].
 */
@ExperimentalKairosApi
@Suppress("NOTHING_TO_INLINE")
inline fun <A> statefully(noinline block: StateScope.() -> A): Stateful<A> = block

/**
 * Operations that accumulate state within the Kairos network.
 *
 * State accumulation is an ongoing process that has a lifetime. Use `-Latest` combinators, such as
 * [mapLatestStateful], to create smaller, nested lifecycles so that accumulation isn't running
 * longer than needed.
 */
@ExperimentalKairosApi
interface StateScope : TransactionScope {

    /**
     * Defers invoking [block] until after the current [StateScope] code-path completes, returning a
     * [DeferredValue] that can be used to reference the result.
     *
     * Useful for recursive definitions.
     *
     * @see DeferredValue
     */
    fun <A> deferredStateScope(block: StateScope.() -> A): DeferredValue<A>

    /**
     * Returns a [State] that holds onto the most recently emitted value from this [Events], or
     * [initialValue] if nothing has been emitted since it was constructed.
     *
     * Note that the value contained within the [State] is not updated until *after* all [Events]
     * have been processed; this keeps the value of the [State] consistent during the entire Kairos
     * transaction.
     */
    fun <A> Events<A>.holdStateDeferred(initialValue: DeferredValue<A>): State<A>

    /**
     * Returns a [State] holding a [Map] that is updated incrementally whenever this emits a value.
     *
     * The value emitted is used as a "patch" for the tracked [Map]; for each key [K] in the emitted
     * map, an associated value of [Just] will insert or replace the value in the tracked [Map], and
     * an associated value of [none] will remove the key from the tracked [Map].
     */
    fun <K, V> Events<Map<K, Maybe<V>>>.foldStateMapIncrementally(
        initialValues: DeferredValue<Map<K, V>>
    ): Incremental<K, V>

    // TODO: everything below this comment can be made into extensions once we have context params

    /**
     * Returns an [Events] that emits from a merged, incrementally-accumulated collection of
     * [Events] emitted from this, following the same "patch" rules as outlined in
     * [foldStateMapIncrementally].
     *
     * Conceptually this is equivalent to:
     * ```kotlin
     *   fun <K, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementally(
     *     initialEvents: Map<K, Events<V>>,
     *   ): Events<Map<K, V>> =
     *     foldMapIncrementally(initialEvents).map { it.merge() }.switchEvents()
     * ```
     *
     * While the behavior is equivalent to the conceptual definition above, the implementation is
     * significantly more efficient.
     *
     * @see merge
     */
    fun <K, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementally(
        name: String? = null,
        initialEvents: DeferredValue<Map<K, Events<V>>>,
    ): Events<Map<K, V>> = foldStateMapIncrementally(initialEvents).mergeEventsIncrementally()

    /**
     * Returns an [Events] that emits from a merged, incrementally-accumulated collection of
     * [Events] emitted from this, following the same "patch" rules as outlined in
     * [foldStateMapIncrementally].
     *
     * Conceptually this is equivalent to:
     * ```kotlin
     *   fun <K, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementallyPromptly(
     *     initialEvents: Map<K, Events<V>>,
     *   ): Events<Map<K, V>> =
     *     foldMapIncrementally(initialEvents).map { it.merge() }.switchEventsPromptly()
     * ```
     *
     * While the behavior is equivalent to the conceptual definition above, the implementation is
     * significantly more efficient.
     *
     * @see merge
     */
    fun <K, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementallyPromptly(
        initialEvents: DeferredValue<Map<K, Events<V>>>,
        name: String? = null,
    ): Events<Map<K, V>> =
        foldStateMapIncrementally(initialEvents).mergeEventsIncrementallyPromptly()

    /**
     * Returns an [Events] that emits from a merged, incrementally-accumulated collection of
     * [Events] emitted from this, following the same "patch" rules as outlined in
     * [foldStateMapIncrementally].
     *
     * Conceptually this is equivalent to:
     * ```kotlin
     *   fun <K, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementally(
     *     initialEvents: Map<K, Events<V>>,
     *   ): Events<Map<K, V>> =
     *     foldMapIncrementally(initialEvents).map { it.merge() }.switchEvents()
     * ```
     *
     * While the behavior is equivalent to the conceptual definition above, the implementation is
     * significantly more efficient.
     *
     * @see merge
     */
    fun <K, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementally(
        name: String? = null,
        initialEvents: Map<K, Events<V>> = emptyMap(),
    ): Events<Map<K, V>> = mergeIncrementally(name, deferredOf(initialEvents))

    /**
     * Returns an [Events] that emits from a merged, incrementally-accumulated collection of
     * [Events] emitted from this, following the same "patch" rules as outlined in
     * [foldStateMapIncrementally].
     *
     * Conceptually this is equivalent to:
     * ```kotlin
     *   fun <K, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementallyPromptly(
     *     initialEvents: Map<K, Events<V>>,
     *   ): Events<Map<K, V>> =
     *     foldMapIncrementally(initialEvents).map { it.merge() }.switchEventsPromptly()
     * ```
     *
     * While the behavior is equivalent to the conceptual definition above, the implementation is
     * significantly more efficient.
     *
     * @see merge
     */
    fun <K, V> Events<Map<K, Maybe<Events<V>>>>.mergeIncrementallyPromptly(
        initialEvents: Map<K, Events<V>> = emptyMap(),
        name: String? = null,
    ): Events<Map<K, V>> = mergeIncrementallyPromptly(deferredOf(initialEvents), name)

    /** Applies the [Stateful] within this [StateScope]. */
    fun <A> Stateful<A>.applyStateful(): A = this()

    /**
     * Applies the [Stateful] within this [StateScope], returning the result as an [DeferredValue].
     */
    fun <A> Stateful<A>.applyStatefulDeferred(): DeferredValue<A> = deferredStateScope {
        applyStateful()
    }

    /**
     * Returns a [State] that holds onto the most recently emitted value from this [Events], or
     * [initialValue] if nothing has been emitted since it was constructed.
     *
     * Note that the value contained within the [State] is not updated until *after* all [Events]
     * have been processed; this keeps the value of the [State] consistent during the entire Kairos
     * transaction.
     */
    fun <A> Events<A>.holdState(initialValue: A): State<A> =
        holdStateDeferred(deferredOf(initialValue))

    /**
     * Returns an [Events] the emits the result of applying [Statefuls][Stateful] emitted from the
     * original [Events].
     *
     * Unlike [applyLatestStateful], state accumulation is not stopped with each subsequent emission
     * of the original [Events].
     */
    fun <A> Events<Stateful<A>>.applyStatefuls(): Events<A>

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events].
     *
     * [transform] can perform state accumulation via its [StateScope] receiver. Unlike
     * [mapLatestStateful], accumulation is not stopped with each subsequent emission of the
     * original [Events].
     */
    fun <A, B> Events<A>.mapStateful(transform: StateScope.(A) -> B): Events<B> =
        map { statefully { transform(it) } }.applyStatefuls()

    /**
     * Returns a [State] the holds the result of applying the [Stateful] held by the original
     * [State].
     *
     * Unlike [applyLatestStateful], state accumulation is not stopped with each state change.
     */
    fun <A> State<Stateful<A>>.applyStatefuls(): State<A> =
        changes
            .applyStatefuls()
            .holdStateDeferred(initialValue = deferredStateScope { sampleDeferred().get()() })

    /** Returns an [Events] that switches to the [Events] emitted by the original [Events]. */
    fun <A> Events<Events<A>>.flatten() = holdState(emptyEvents).switchEvents()

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events].
     *
     * [transform] can perform state accumulation via its [StateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     */
    fun <A, B> Events<A>.mapLatestStateful(transform: StateScope.(A) -> B): Events<B> =
        map { statefully { transform(it) } }.applyLatestStateful()

    /**
     * Returns an [Events] that switches to a new [Events] produced by [transform] every time the
     * original [Events] emits a value.
     *
     * [transform] can perform state accumulation via its [StateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     */
    fun <A, B> Events<A>.flatMapLatestStateful(transform: StateScope.(A) -> Events<B>): Events<B> =
        mapLatestStateful(transform).flatten()

    /**
     * Returns an [Events] containing the results of applying each [Stateful] emitted from the
     * original [Events].
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful] is
     * stopped.
     */
    fun <A> Events<Stateful<A>>.applyLatestStateful(): Events<A> = applyLatestStateful {}.first

    /**
     * Returns a [State] containing the value returned by applying the [Stateful] held by the
     * original [State].
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful] is
     * stopped.
     */
    fun <A> State<Stateful<A>>.applyLatestStateful(): State<A> {
        val (changes, init) = changes.applyLatestStateful { sample()() }
        return changes.holdStateDeferred(init)
    }

    /**
     * Returns an [Events] containing the results of applying each [Stateful] emitted from the
     * original [Events], and a [DeferredValue] containing the result of applying [init]
     * immediately.
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful] is
     * stopped.
     */
    fun <A, B> Events<Stateful<B>>.applyLatestStateful(
        init: Stateful<A>
    ): Pair<Events<B>, DeferredValue<A>> {
        val (events, result) =
            mapCheap { spec -> mapOf(Unit to just(spec)) }
                .applyLatestStatefulForKey(init = mapOf(Unit to init), numKeys = 1)
        val outEvents: Events<B> =
            events.mapMaybe {
                checkNotNull(it[Unit]) { "applyLatest: expected result, but none present in: $it" }
            }
        val outInit: DeferredValue<A> = deferredTransactionScope {
            val initResult: Map<Unit, A> = result.get()
            check(Unit in initResult) {
                "applyLatest: expected initial result, but none present in: $initResult"
            }
            @Suppress("UNCHECKED_CAST")
            initResult.getOrDefault(Unit) { null } as A
        }
        return Pair(outEvents, outInit)
    }

    /**
     * Returns an [Events] containing the results of applying each [Stateful] emitted from the
     * original [Events], and a [DeferredValue] containing the result of applying [init]
     * immediately.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [Stateful] will be stopped with no replacement.
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful]
     * with the same key is stopped.
     */
    fun <K, A, B> Events<Map<K, Maybe<Stateful<A>>>>.applyLatestStatefulForKey(
        init: DeferredValue<Map<K, Stateful<B>>>,
        numKeys: Int? = null,
    ): Pair<Events<Map<K, Maybe<A>>>, DeferredValue<Map<K, B>>>

    /**
     * Returns an [Events] containing the results of applying each [Stateful] emitted from the
     * original [Events], and a [DeferredValue] containing the result of applying [init]
     * immediately.
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful]
     * with the same key is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [Stateful] will be stopped with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<Stateful<A>>>>.applyLatestStatefulForKey(
        init: Map<K, Stateful<B>>,
        numKeys: Int? = null,
    ): Pair<Events<Map<K, Maybe<A>>>, DeferredValue<Map<K, B>>> =
        applyLatestStatefulForKey(deferredOf(init), numKeys)

    fun <K, V> Incremental<K, Stateful<V>>.applyLatestStatefulForKey(
        numKeys: Int? = null
    ): Incremental<K, V> {
        val (events, init) = updates.applyLatestStatefulForKey(sampleDeferred())
        return events.foldStateMapIncrementally(init)
    }

    /**
     * Returns a [State] containing the latest results of applying each [Stateful] emitted from the
     * original [Events].
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful]
     * with the same key is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [Stateful] will be stopped with no replacement.
     */
    fun <K, A> Events<Map<K, Maybe<Stateful<A>>>>.holdLatestStatefulForKey(
        init: DeferredValue<Map<K, Stateful<A>>>,
        numKeys: Int? = null,
    ): Incremental<K, A> {
        val (changes, initialValues) = applyLatestStatefulForKey(init, numKeys)
        return changes.foldStateMapIncrementally(initialValues)
    }

    /**
     * Returns a [State] containing the latest results of applying each [Stateful] emitted from the
     * original [Events].
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful]
     * with the same key is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [Stateful] will be stopped with no replacement.
     */
    fun <K, A> Events<Map<K, Maybe<Stateful<A>>>>.holdLatestStatefulForKey(
        init: Map<K, Stateful<A>> = emptyMap(),
        numKeys: Int? = null,
    ): Incremental<K, A> = holdLatestStatefulForKey(deferredOf(init), numKeys)

    /**
     * Returns an [Events] containing the results of applying each [Stateful] emitted from the
     * original [Events], and a [DeferredValue] containing the result of applying [stateInit]
     * immediately.
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful]
     * with the same key is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [Stateful] will be stopped with no replacement.
     */
    fun <K, A> Events<Map<K, Maybe<Stateful<A>>>>.applyLatestStatefulForKey(
        numKeys: Int? = null
    ): Events<Map<K, Maybe<A>>> =
        applyLatestStatefulForKey(init = emptyMap<K, Stateful<*>>(), numKeys = numKeys).first

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events], and a [DeferredValue] containing the result of applying [transform] to
     * [initialValues] immediately.
     *
     * [transform] can perform state accumulation via its [StateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [StateScope] will be stopped with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<A>>>.mapLatestStatefulForKey(
        initialValues: DeferredValue<Map<K, A>>,
        numKeys: Int? = null,
        transform: StateScope.(A) -> B,
    ): Pair<Events<Map<K, Maybe<B>>>, DeferredValue<Map<K, B>>> =
        map { patch -> patch.mapValues { (_, v) -> v.map { statefully { transform(it) } } } }
            .applyLatestStatefulForKey(
                deferredStateScope {
                    initialValues.get().mapValues { (_, v) -> statefully { transform(v) } }
                },
                numKeys = numKeys,
            )

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events], and a [DeferredValue] containing the result of applying [transform] to
     * [initialValues] immediately.
     *
     * [transform] can perform state accumulation via its [StateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [StateScope] will be stopped with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<A>>>.mapLatestStatefulForKey(
        initialValues: Map<K, A>,
        numKeys: Int? = null,
        transform: StateScope.(A) -> B,
    ): Pair<Events<Map<K, Maybe<B>>>, DeferredValue<Map<K, B>>> =
        mapLatestStatefulForKey(deferredOf(initialValues), numKeys, transform)

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events].
     *
     * [transform] can perform state accumulation via its [StateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [StateScope] will be stopped with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<A>>>.mapLatestStatefulForKey(
        numKeys: Int? = null,
        transform: StateScope.(A) -> B,
    ): Events<Map<K, Maybe<B>>> = mapLatestStatefulForKey(emptyMap(), numKeys, transform).first

    /**
     * Returns an [Events] that will only emit the next event of the original [Events], and then
     * will act as [emptyEvents].
     *
     * If the original [Events] is emitting an event at this exact time, then it will be the only
     * even emitted from the result [Events].
     */
    fun <A> Events<A>.nextOnly(name: String? = null): Events<A> =
        if (this === emptyEvents) {
            this
        } else {
            EventsLoop<A>().also {
                it.loopback =
                    it.mapCheap { emptyEvents }.holdState(this@nextOnly).switchEvents(name)
            }
        }

    /** Returns an [Events] that skips the next emission of the original [Events]. */
    fun <A> Events<A>.skipNext(): Events<A> =
        if (this === emptyEvents) {
            this
        } else {
            nextOnly().mapCheap { this@skipNext }.holdState(emptyEvents).switchEvents()
        }

    /**
     * Returns an [Events] that emits values from the original [Events] up until [stop] emits a
     * value.
     *
     * If the original [Events] emits at the same time as [stop], then the returned [Events] will
     * emit that value.
     */
    fun <A> Events<A>.takeUntil(stop: Events<*>): Events<A> =
        if (stop === emptyEvents) {
            this
        } else {
            stop.mapCheap { emptyEvents }.nextOnly().holdState(this).switchEvents()
        }

    /**
     * Invokes [stateful] in a new [StateScope] that is a child of this one.
     *
     * This new scope is stopped when [stop] first emits a value, or when the parent scope is
     * stopped. Stopping will end all state accumulation; any [States][State] returned from this
     * scope will no longer update.
     */
    fun <A> childStateScope(stop: Events<*>, stateful: Stateful<A>): DeferredValue<A> {
        val (_, init: DeferredValue<Map<Unit, A>>) =
            stop
                .nextOnly()
                .map { mapOf(Unit to none<Stateful<A>>()) }
                .applyLatestStatefulForKey(init = mapOf(Unit to stateful), numKeys = 1)
        return deferredStateScope { init.get().getValue(Unit) }
    }

    /**
     * Returns an [Events] that emits values from the original [Events] up to and including a value
     * is emitted that satisfies [predicate].
     */
    fun <A> Events<A>.takeUntil(predicate: TransactionScope.(A) -> Boolean): Events<A> =
        takeUntil(filter(predicate))

    /**
     * Returns a [State] that is incrementally updated when this [Events] emits a value, by applying
     * [transform] to both the emitted value and the currently tracked state.
     *
     * Note that the value contained within the [State] is not updated until *after* all [Events]
     * have been processed; this keeps the value of the [State] consistent during the entire Kairos
     * transaction.
     */
    fun <A, B> Events<A>.foldState(
        initialValue: B,
        transform: TransactionScope.(A, B) -> B,
    ): State<B> {
        lateinit var state: State<B>
        return map { a -> transform(a, state.sample()) }.holdState(initialValue).also { state = it }
    }

    /**
     * Returns a [State] that is incrementally updated when this [Events] emits a value, by applying
     * [transform] to both the emitted value and the currently tracked state.
     *
     * Note that the value contained within the [State] is not updated until *after* all [Events]
     * have been processed; this keeps the value of the [State] consistent during the entire Kairos
     * transaction.
     */
    fun <A, B> Events<A>.foldStateDeferred(
        initialValue: DeferredValue<B>,
        transform: TransactionScope.(A, B) -> B,
    ): State<B> {
        lateinit var state: State<B>
        return map { a -> transform(a, state.sample()) }
            .holdStateDeferred(initialValue)
            .also { state = it }
    }

    /**
     * Returns a [State] that holds onto the result of applying the most recently emitted [Stateful]
     * this [Events], or [init] if nothing has been emitted since it was constructed.
     *
     * When each [Stateful] is applied, state accumulation from the previously-active [Stateful] is
     * stopped.
     *
     * Note that the value contained within the [State] is not updated until *after* all [Events]
     * have been processed; this keeps the value of the [State] consistent during the entire Kairos
     * transaction.
     *
     * Shorthand for:
     * ```kotlin
     * val (changes, initApplied) = applyLatestStateful(init)
     * return changes.holdStateDeferred(initApplied)
     * ```
     */
    fun <A> Events<Stateful<A>>.holdLatestStateful(init: Stateful<A>): State<A> {
        val (changes, initApplied) = applyLatestStateful(init)
        return changes.holdStateDeferred(initApplied)
    }

    /**
     * Returns an [Events] that emits the two most recent emissions from the original [Events].
     * [initialValue] is used as the previous value for the first emission.
     *
     * Shorthand for `sample(hold(init)) { new, old -> Pair(old, new) }`
     */
    fun <S, T : S> Events<T>.pairwise(initialValue: S): Events<WithPrev<S, T>> {
        val previous = holdState(initialValue)
        return mapCheap { new -> WithPrev(previousValue = previous.sample(), newValue = new) }
    }

    /**
     * Returns an [Events] that emits the two most recent emissions from the original [Events]. Note
     * that the returned [Events] will not emit until the original [Events] has emitted twice.
     */
    fun <A> Events<A>.pairwise(): Events<WithPrev<A, A>> =
        mapCheap { just(it) }
            .pairwise(none)
            .mapMaybe { (prev, next) -> prev.zipWith(next, ::WithPrev) }

    /**
     * Returns a [State] that holds both the current and previous values of the original [State].
     * [initialPreviousValue] is used as the first previous value.
     *
     * Shorthand for `sample(hold(init)) { new, old -> Pair(old, new) }`
     */
    fun <S, T : S> State<T>.pairwise(initialPreviousValue: S): State<WithPrev<S, T>> =
        changes
            .pairwise(initialPreviousValue)
            .holdStateDeferred(
                deferredTransactionScope { WithPrev(initialPreviousValue, sample()) }
            )

    /**
     * Returns a [State] holding a [Map] that is updated incrementally whenever this emits a value.
     *
     * The value emitted is used as a "patch" for the tracked [Map]; for each key [K] in the emitted
     * map, an associated value of [Just] will insert or replace the value in the tracked [Map], and
     * an associated value of [none] will remove the key from the tracked [Map].
     */
    fun <K, V> Events<Map<K, Maybe<V>>>.foldStateMapIncrementally(
        initialValues: Map<K, V> = emptyMap()
    ): Incremental<K, V> = foldStateMapIncrementally(deferredOf(initialValues))

    /**
     * Returns an [Events] that wraps each emission of the original [Events] into an [IndexedValue],
     * containing the emitted value and its index (starting from zero).
     *
     * Shorthand for:
     * ```
     *   val index = fold(0) { _, oldIdx -> oldIdx + 1 }
     *   sample(index) { a, idx -> IndexedValue(idx, a) }
     * ```
     */
    fun <A> Events<A>.withIndex(): Events<IndexedValue<A>> {
        val index = foldState(0) { _, old -> old + 1 }
        return sample(index) { a, idx -> IndexedValue(idx, a) }
    }

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events] and its index (starting from zero).
     *
     * Shorthand for:
     * ```
     *   withIndex().map { (idx, a) -> transform(idx, a) }
     * ```
     */
    fun <A, B> Events<A>.mapIndexed(transform: TransactionScope.(Int, A) -> B): Events<B> {
        val index = foldState(0) { _, i -> i + 1 }
        return sample(index) { a, idx -> transform(idx, a) }
    }

    /** Returns an [Events] where all subsequent repetitions of the same value are filtered out. */
    fun <A> Events<A>.distinctUntilChanged(): Events<A> {
        val state: State<Any?> = holdState(Any())
        return filter { it != state.sample() }
    }

    /**
     * Returns a new [Events] that emits at the same rate as the original [Events], but combines the
     * emitted value with the most recent emission from [other] using [transform].
     *
     * Note that the returned [Events] will not emit anything until [other] has emitted at least one
     * value.
     */
    fun <A, B, C> Events<A>.sample(
        other: Events<B>,
        transform: TransactionScope.(A, B) -> C,
    ): Events<C> {
        val state = other.mapCheap { just(it) }.holdState(none)
        return sample(state) { a, b -> b.map { transform(a, it) } }.filterJust()
    }

    /**
     * Returns a [State] that samples the [Transactional] held by the given [State] within the same
     * transaction that the state changes.
     */
    fun <A> State<Transactional<A>>.sampleTransactionals(): State<A> =
        changes
            .sampleTransactionals()
            .holdStateDeferred(deferredTransactionScope { sample().sample() })

    /**
     * Returns a [State] that transforms the value held inside this [State] by applying it to the
     * given function [transform].
     */
    fun <A, B> State<A>.mapTransactionally(transform: TransactionScope.(A) -> B): State<B> =
        map { transactionally { transform(it) } }.sampleTransactionals()

    /**
     * Returns a [State] whose value is generated with [transform] by combining the current values
     * of each given [State].
     *
     * @see State.combineWithTransactionally
     */
    fun <A, B, Z> combineTransactionally(
        stateA: State<A>,
        stateB: State<B>,
        transform: TransactionScope.(A, B) -> Z,
    ): State<Z> =
        combine(stateA, stateB) { a, b -> transactionally { transform(a, b) } }
            .sampleTransactionals()

    /**
     * Returns a [State] whose value is generated with [transform] by combining the current values
     * of each given [State].
     *
     * @see State.combineWithTransactionally
     */
    fun <A, B, C, Z> combineTransactionally(
        stateA: State<A>,
        stateB: State<B>,
        stateC: State<C>,
        transform: TransactionScope.(A, B, C) -> Z,
    ): State<Z> =
        combine(stateA, stateB, stateC) { a, b, c -> transactionally { transform(a, b, c) } }
            .sampleTransactionals()

    /**
     * Returns a [State] whose value is generated with [transform] by combining the current values
     * of each given [State].
     *
     * @see State.combineWithTransactionally
     */
    fun <A, B, C, D, Z> combineTransactionally(
        stateA: State<A>,
        stateB: State<B>,
        stateC: State<C>,
        stateD: State<D>,
        transform: TransactionScope.(A, B, C, D) -> Z,
    ): State<Z> =
        combine(stateA, stateB, stateC, stateD) { a, b, c, d ->
                transactionally { transform(a, b, c, d) }
            }
            .sampleTransactionals()

    /** Returns a [State] by applying [transform] to the value held by the original [State]. */
    fun <A, B> State<A>.flatMapTransactionally(
        transform: TransactionScope.(A) -> State<B>
    ): State<B> = map { transactionally { transform(it) } }.sampleTransactionals().flatten()

    /**
     * Returns a [State] whose value is generated with [transform] by combining the current values
     * of each given [State].
     *
     * @see State.combineWithTransactionally
     */
    fun <A, Z> combineTransactionally(
        vararg states: State<A>,
        transform: TransactionScope.(List<A>) -> Z,
    ): State<Z> = combine(*states).mapTransactionally(transform)

    /**
     * Returns a [State] whose value is generated with [transform] by combining the current values
     * of each given [State].
     *
     * @see State.combineWithTransactionally
     */
    fun <A, Z> Iterable<State<A>>.combineTransactionally(
        transform: TransactionScope.(List<A>) -> Z
    ): State<Z> = combine().mapTransactionally(transform)

    /**
     * Returns a [State] by combining the values held inside the given [State]s by applying them to
     * the given function [transform].
     */
    fun <A, B, C> State<A>.combineWithTransactionally(
        other: State<B>,
        transform: TransactionScope.(A, B) -> C,
    ): State<C> = combineTransactionally(this, other, transform)

    /**
     * Returns an [Incremental] that reflects the state of the original [Incremental], but also adds
     * / removes entries based on the state of the original's values.
     */
    fun <K, V> Incremental<K, State<Maybe<V>>>.applyStateIncrementally(): Incremental<K, V> =
        mapValues { (_, v) -> v.changes }
            .mergeEventsIncrementallyPromptly()
            .foldStateMapIncrementally(
                deferredStateScope { sample().mapMaybeValues { (_, s) -> s.sample() } }
            )

    /**
     * Returns an [Incremental] that reflects the state of the original [Incremental], but also adds
     * / removes entries based on the [State] returned from applying [transform] to the original's
     * entries.
     */
    fun <K, V, U> Incremental<K, V>.mapIncrementalState(
        transform: KairosScope.(Map.Entry<K, V>) -> State<Maybe<U>>
    ): Incremental<K, U> = mapValues { transform(it) }.applyStateIncrementally()

    /**
     * Returns an [Incremental] that reflects the state of the original [Incremental], but also adds
     * / removes entries based on the [State] returned from applying [transform] to the original's
     * entries, such that entries are added when that state is `true`, and removed when `false`.
     */
    fun <K, V> Incremental<K, V>.filterIncrementally(
        transform: KairosScope.(Map.Entry<K, V>) -> State<Boolean>
    ): Incremental<K, V> = mapIncrementalState { entry ->
        transform(entry).map { if (it) just(entry.value) else none }
    }

    /**
     * Returns an [Incremental] that samples the [Transactionals][Transactional] held by the
     * original within the same transaction that the incremental [updates].
     */
    fun <K, V> Incremental<K, Transactional<V>>.sampleTransactionals(): Incremental<K, V> =
        updates
            .map { patch -> patch.mapValues { (k, mv) -> mv.map { it.sample() } } }
            .foldStateMapIncrementally(
                deferredStateScope { sample().mapValues { (k, v) -> v.sample() } }
            )

    /**
     * Returns an [Incremental] that tracks the entries of the original incremental, but values
     * replaced with those obtained by applying [transform] to each original entry.
     */
    fun <K, V, U> Incremental<K, V>.mapValuesTransactionally(
        transform: TransactionScope.(Map.Entry<K, V>) -> U
    ): Incremental<K, U> = mapValues { transactionally { transform(it) } }.sampleTransactionals()
}
