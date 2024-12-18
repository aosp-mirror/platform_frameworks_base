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

import com.android.systemui.kairos.combine as combinePure
import com.android.systemui.kairos.map as mapPure
import com.android.systemui.kairos.util.Just
import com.android.systemui.kairos.util.Left
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.Right
import com.android.systemui.kairos.util.WithPrev
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.map
import com.android.systemui.kairos.util.none
import com.android.systemui.kairos.util.partitionEithers
import com.android.systemui.kairos.util.zipWith
import kotlin.coroutines.RestrictsSuspension

typealias FrpStateful<R> = suspend FrpStateScope.() -> R

/**
 * Returns a [FrpStateful] that, when [applied][FrpStateScope.applyStateful], invokes [block] with
 * the applier's [FrpStateScope].
 */
// TODO: caching story? should each Scope have a cache of applied FrpStateful instances?
@ExperimentalFrpApi
@Suppress("NOTHING_TO_INLINE")
inline fun <A> statefully(noinline block: suspend FrpStateScope.() -> A): FrpStateful<A> = block

/**
 * Operations that accumulate state within the FRP network.
 *
 * State accumulation is an ongoing process that has a lifetime. Use `-Latest` combinators, such as
 * [mapLatestStateful], to create smaller, nested lifecycles so that accumulation isn't running
 * longer than needed.
 */
@ExperimentalFrpApi
@RestrictsSuspension
interface FrpStateScope : FrpTransactionScope {

    /** TODO */
    @ExperimentalFrpApi
    // TODO: wish this could just be `deferred` but alas
    fun <A> deferredStateScope(block: suspend FrpStateScope.() -> A): FrpDeferredValue<A>

    /**
     * Returns a [TState] that holds onto the most recently emitted value from this [TFlow], or
     * [initialValue] if nothing has been emitted since it was constructed.
     *
     * Note that the value contained within the [TState] is not updated until *after* all [TFlow]s
     * have been processed; this keeps the value of the [TState] consistent during the entire FRP
     * transaction.
     */
    @ExperimentalFrpApi fun <A> TFlow<A>.holdDeferred(initialValue: FrpDeferredValue<A>): TState<A>

    /**
     * Returns a [TFlow] that emits from a merged, incrementally-accumulated collection of [TFlow]s
     * emitted from this, following the same "patch" rules as outlined in [foldMapIncrementally].
     *
     * Conceptually this is equivalent to:
     * ```kotlin
     *   fun <K, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementally(
     *     initialTFlows: Map<K, TFlow<V>>,
     *   ): TFlow<Map<K, V>> =
     *     foldMapIncrementally(initialTFlows).map { it.merge() }.switch()
     * ```
     *
     * While the behavior is equivalent to the conceptual definition above, the implementation is
     * significantly more efficient.
     *
     * @see merge
     */
    @ExperimentalFrpApi
    fun <K : Any, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementally(
        initialTFlows: FrpDeferredValue<Map<K, TFlow<V>>>
    ): TFlow<Map<K, V>>

    /**
     * Returns a [TFlow] that emits from a merged, incrementally-accumulated collection of [TFlow]s
     * emitted from this, following the same "patch" rules as outlined in [foldMapIncrementally].
     *
     * Conceptually this is equivalent to:
     * ```kotlin
     *   fun <K, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementallyPrompt(
     *     initialTFlows: Map<K, TFlow<V>>,
     *   ): TFlow<Map<K, V>> =
     *     foldMapIncrementally(initialTFlows).map { it.merge() }.switchPromptly()
     * ```
     *
     * While the behavior is equivalent to the conceptual definition above, the implementation is
     * significantly more efficient.
     *
     * @see merge
     */
    @ExperimentalFrpApi
    fun <K : Any, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementallyPromptly(
        initialTFlows: FrpDeferredValue<Map<K, TFlow<V>>>
    ): TFlow<Map<K, V>>

    // TODO: everything below this comment can be made into extensions once we have context params

    /**
     * Returns a [TFlow] that emits from a merged, incrementally-accumulated collection of [TFlow]s
     * emitted from this, following the same "patch" rules as outlined in [foldMapIncrementally].
     *
     * Conceptually this is equivalent to:
     * ```kotlin
     *   fun <K, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementally(
     *     initialTFlows: Map<K, TFlow<V>>,
     *   ): TFlow<Map<K, V>> =
     *     foldMapIncrementally(initialTFlows).map { it.merge() }.switch()
     * ```
     *
     * While the behavior is equivalent to the conceptual definition above, the implementation is
     * significantly more efficient.
     *
     * @see merge
     */
    @ExperimentalFrpApi
    fun <K : Any, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementally(
        initialTFlows: Map<K, TFlow<V>> = emptyMap()
    ): TFlow<Map<K, V>> = mergeIncrementally(deferredOf(initialTFlows))

    /**
     * Returns a [TFlow] that emits from a merged, incrementally-accumulated collection of [TFlow]s
     * emitted from this, following the same "patch" rules as outlined in [foldMapIncrementally].
     *
     * Conceptually this is equivalent to:
     * ```kotlin
     *   fun <K, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementallyPrompt(
     *     initialTFlows: Map<K, TFlow<V>>,
     *   ): TFlow<Map<K, V>> =
     *     foldMapIncrementally(initialTFlows).map { it.merge() }.switchPromptly()
     * ```
     *
     * While the behavior is equivalent to the conceptual definition above, the implementation is
     * significantly more efficient.
     *
     * @see merge
     */
    @ExperimentalFrpApi
    fun <K : Any, V> TFlow<Map<K, Maybe<TFlow<V>>>>.mergeIncrementallyPromptly(
        initialTFlows: Map<K, TFlow<V>> = emptyMap()
    ): TFlow<Map<K, V>> = mergeIncrementallyPromptly(deferredOf(initialTFlows))

    /** Applies the [FrpStateful] within this [FrpStateScope]. */
    @ExperimentalFrpApi suspend fun <A> FrpStateful<A>.applyStateful(): A = this()

    /**
     * Applies the [FrpStateful] within this [FrpStateScope], returning the result as an
     * [FrpDeferredValue].
     */
    @ExperimentalFrpApi
    fun <A> FrpStateful<A>.applyStatefulDeferred(): FrpDeferredValue<A> = deferredStateScope {
        applyStateful()
    }

    /**
     * Returns a [TState] that holds onto the most recently emitted value from this [TFlow], or
     * [initialValue] if nothing has been emitted since it was constructed.
     *
     * Note that the value contained within the [TState] is not updated until *after* all [TFlow]s
     * have been processed; this keeps the value of the [TState] consistent during the entire FRP
     * transaction.
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.hold(initialValue: A): TState<A> = holdDeferred(deferredOf(initialValue))

    /**
     * Returns a [TFlow] the emits the result of applying [FrpStatefuls][FrpStateful] emitted from
     * the original [TFlow].
     *
     * Unlike [applyLatestStateful], state accumulation is not stopped with each subsequent emission
     * of the original [TFlow].
     */
    @ExperimentalFrpApi fun <A> TFlow<FrpStateful<A>>.applyStatefuls(): TFlow<A>

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow].
     *
     * [transform] can perform state accumulation via its [FrpStateScope] receiver. Unlike
     * [mapLatestStateful], accumulation is not stopped with each subsequent emission of the
     * original [TFlow].
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.mapStateful(transform: suspend FrpStateScope.(A) -> B): TFlow<B> =
        mapPure { statefully { transform(it) } }.applyStatefuls()

    /**
     * Returns a [TState] the holds the result of applying the [FrpStateful] held by the original
     * [TState].
     *
     * Unlike [applyLatestStateful], state accumulation is not stopped with each state change.
     */
    @ExperimentalFrpApi
    fun <A> TState<FrpStateful<A>>.applyStatefuls(): TState<A> =
        stateChanges
            .applyStatefuls()
            .holdDeferred(initialValue = deferredStateScope { sampleDeferred().get()() })

    /** Returns a [TFlow] that switches to the [TFlow] emitted by the original [TFlow]. */
    @ExperimentalFrpApi fun <A> TFlow<TFlow<A>>.flatten() = hold(emptyTFlow).switch()

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow].
     *
     * [transform] can perform state accumulation via its [FrpStateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.mapLatestStateful(transform: suspend FrpStateScope.(A) -> B): TFlow<B> =
        mapPure { statefully { transform(it) } }.applyLatestStateful()

    /**
     * Returns a [TFlow] that switches to a new [TFlow] produced by [transform] every time the
     * original [TFlow] emits a value.
     *
     * [transform] can perform state accumulation via its [FrpStateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.flatMapLatestStateful(
        transform: suspend FrpStateScope.(A) -> TFlow<B>
    ): TFlow<B> = mapLatestStateful(transform).flatten()

    /**
     * Returns a [TFlow] containing the results of applying each [FrpStateful] emitted from the
     * original [TFlow].
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] is stopped.
     */
    @ExperimentalFrpApi
    fun <A> TFlow<FrpStateful<A>>.applyLatestStateful(): TFlow<A> = applyLatestStateful {}.first

    /**
     * Returns a [TState] containing the value returned by applying the [FrpStateful] held by the
     * original [TState].
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] is stopped.
     */
    @ExperimentalFrpApi
    fun <A> TState<FrpStateful<A>>.applyLatestStateful(): TState<A> {
        val (changes, init) = stateChanges.applyLatestStateful { sample()() }
        return changes.holdDeferred(init)
    }

    /**
     * Returns a [TFlow] containing the results of applying each [FrpStateful] emitted from the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [init]
     * immediately.
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] is stopped.
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<FrpStateful<B>>.applyLatestStateful(
        init: FrpStateful<A>
    ): Pair<TFlow<B>, FrpDeferredValue<A>> {
        val (flow, result) =
            mapCheap { spec -> mapOf(Unit to just(spec)) }
                .applyLatestStatefulForKey(init = mapOf(Unit to init), numKeys = 1)
        val outFlow: TFlow<B> =
            flow.mapMaybe {
                checkNotNull(it[Unit]) { "applyLatest: expected result, but none present in: $it" }
            }
        val outInit: FrpDeferredValue<A> = deferredTransactionScope {
            val initResult: Map<Unit, A> = result.get()
            check(Unit in initResult) {
                "applyLatest: expected initial result, but none present in: $initResult"
            }
            @Suppress("UNCHECKED_CAST")
            initResult.getOrDefault(Unit) { null } as A
        }
        return Pair(outFlow, outInit)
    }

    /**
     * Returns a [TFlow] containing the results of applying each [FrpStateful] emitted from the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [init]
     * immediately.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpStateful] will be stopped with no replacement.
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] with the same key is stopped.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<FrpStateful<A>>>>.applyLatestStatefulForKey(
        init: FrpDeferredValue<Map<K, FrpStateful<B>>>,
        numKeys: Int? = null,
    ): Pair<TFlow<Map<K, Maybe<A>>>, FrpDeferredValue<Map<K, B>>>

    /**
     * Returns a [TFlow] containing the results of applying each [FrpStateful] emitted from the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [init]
     * immediately.
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] with the same key is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpStateful] will be stopped with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<FrpStateful<A>>>>.applyLatestStatefulForKey(
        init: Map<K, FrpStateful<B>>,
        numKeys: Int? = null,
    ): Pair<TFlow<Map<K, Maybe<A>>>, FrpDeferredValue<Map<K, B>>> =
        applyLatestStatefulForKey(deferredOf(init), numKeys)

    /**
     * Returns a [TState] containing the latest results of applying each [FrpStateful] emitted from
     * the original [TFlow].
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] with the same key is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpStateful] will be stopped with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A> TFlow<Map<K, Maybe<FrpStateful<A>>>>.holdLatestStatefulForKey(
        init: FrpDeferredValue<Map<K, FrpStateful<A>>>,
        numKeys: Int? = null,
    ): TState<Map<K, A>> {
        val (changes, initialValues) = applyLatestStatefulForKey(init, numKeys)
        return changes.foldMapIncrementally(initialValues)
    }

    /**
     * Returns a [TState] containing the latest results of applying each [FrpStateful] emitted from
     * the original [TFlow].
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] with the same key is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpStateful] will be stopped with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A> TFlow<Map<K, Maybe<FrpStateful<A>>>>.holdLatestStatefulForKey(
        init: Map<K, FrpStateful<A>> = emptyMap(),
        numKeys: Int? = null,
    ): TState<Map<K, A>> = holdLatestStatefulForKey(deferredOf(init), numKeys)

    /**
     * Returns a [TFlow] containing the results of applying each [FrpStateful] emitted from the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [init]
     * immediately.
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] with the same key is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpStateful] will be stopped with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A> TFlow<Map<K, Maybe<FrpStateful<A>>>>.applyLatestStatefulForKey(
        numKeys: Int? = null
    ): TFlow<Map<K, Maybe<A>>> =
        applyLatestStatefulForKey(init = emptyMap<K, FrpStateful<*>>(), numKeys = numKeys).first

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [transform] to
     * [initialValues] immediately.
     *
     * [transform] can perform state accumulation via its [FrpStateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpStateScope] will be stopped with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<A>>>.mapLatestStatefulForKey(
        initialValues: FrpDeferredValue<Map<K, A>>,
        numKeys: Int? = null,
        transform: suspend FrpStateScope.(A) -> B,
    ): Pair<TFlow<Map<K, Maybe<B>>>, FrpDeferredValue<Map<K, B>>> =
        mapPure { patch -> patch.mapValues { (_, v) -> v.map { statefully { transform(it) } } } }
            .applyLatestStatefulForKey(
                deferredStateScope {
                    initialValues.get().mapValues { (_, v) -> statefully { transform(v) } }
                },
                numKeys = numKeys,
            )

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [transform] to
     * [initialValues] immediately.
     *
     * [transform] can perform state accumulation via its [FrpStateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpStateScope] will be stopped with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<A>>>.mapLatestStatefulForKey(
        initialValues: Map<K, A>,
        numKeys: Int? = null,
        transform: suspend FrpStateScope.(A) -> B,
    ): Pair<TFlow<Map<K, Maybe<B>>>, FrpDeferredValue<Map<K, B>>> =
        mapLatestStatefulForKey(deferredOf(initialValues), numKeys, transform)

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow].
     *
     * [transform] can perform state accumulation via its [FrpStateScope] receiver. With each
     * invocation of [transform], state accumulation from previous invocation is stopped.
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpStateScope] will be stopped with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<A>>>.mapLatestStatefulForKey(
        numKeys: Int? = null,
        transform: suspend FrpStateScope.(A) -> B,
    ): TFlow<Map<K, Maybe<B>>> = mapLatestStatefulForKey(emptyMap(), numKeys, transform).first

    /**
     * Returns a [TFlow] that will only emit the next event of the original [TFlow], and then will
     * act as [emptyTFlow].
     *
     * If the original [TFlow] is emitting an event at this exact time, then it will be the only
     * even emitted from the result [TFlow].
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.nextOnly(): TFlow<A> =
        if (this === emptyTFlow) {
            this
        } else {
            TFlowLoop<A>().also {
                it.loopback = it.mapCheap { emptyTFlow }.hold(this@nextOnly).switch()
            }
        }

    /** Returns a [TFlow] that skips the next emission of the original [TFlow]. */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.skipNext(): TFlow<A> =
        if (this === emptyTFlow) {
            this
        } else {
            nextOnly().mapCheap { this@skipNext }.hold(emptyTFlow).switch()
        }

    /**
     * Returns a [TFlow] that emits values from the original [TFlow] up until [stop] emits a value.
     *
     * If the original [TFlow] emits at the same time as [stop], then the returned [TFlow] will emit
     * that value.
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.takeUntil(stop: TFlow<*>): TFlow<A> =
        if (stop === emptyTFlow) {
            this
        } else {
            stop.mapCheap { emptyTFlow }.nextOnly().hold(this).switch()
        }

    /**
     * Invokes [stateful] in a new [FrpStateScope] that is a child of this one.
     *
     * This new scope is stopped when [stop] first emits a value, or when the parent scope is
     * stopped. Stopping will end all state accumulation; any [TStates][TState] returned from this
     * scope will no longer update.
     */
    @ExperimentalFrpApi
    fun <A> childStateScope(stop: TFlow<*>, stateful: FrpStateful<A>): FrpDeferredValue<A> {
        val (_, init: FrpDeferredValue<Map<Unit, A>>) =
            stop
                .nextOnly()
                .mapPure { mapOf(Unit to none<FrpStateful<A>>()) }
                .applyLatestStatefulForKey(init = mapOf(Unit to stateful), numKeys = 1)
        return deferredStateScope { init.get().getValue(Unit) }
    }

    /**
     * Returns a [TFlow] that emits values from the original [TFlow] up to and including a value is
     * emitted that satisfies [predicate].
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.takeUntil(predicate: suspend FrpTransactionScope.(A) -> Boolean): TFlow<A> =
        takeUntil(filter(predicate))

    /**
     * Returns a [TState] that is incrementally updated when this [TFlow] emits a value, by applying
     * [transform] to both the emitted value and the currently tracked state.
     *
     * Note that the value contained within the [TState] is not updated until *after* all [TFlow]s
     * have been processed; this keeps the value of the [TState] consistent during the entire FRP
     * transaction.
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.fold(
        initialValue: B,
        transform: suspend FrpTransactionScope.(A, B) -> B,
    ): TState<B> {
        lateinit var state: TState<B>
        return mapPure { a -> transform(a, state.sample()) }.hold(initialValue).also { state = it }
    }

    /**
     * Returns a [TState] that is incrementally updated when this [TFlow] emits a value, by applying
     * [transform] to both the emitted value and the currently tracked state.
     *
     * Note that the value contained within the [TState] is not updated until *after* all [TFlow]s
     * have been processed; this keeps the value of the [TState] consistent during the entire FRP
     * transaction.
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.foldDeferred(
        initialValue: FrpDeferredValue<B>,
        transform: suspend FrpTransactionScope.(A, B) -> B,
    ): TState<B> {
        lateinit var state: TState<B>
        return mapPure { a -> transform(a, state.sample()) }
            .holdDeferred(initialValue)
            .also { state = it }
    }

    /**
     * Returns a [TState] that holds onto the result of applying the most recently emitted
     * [FrpStateful] this [TFlow], or [init] if nothing has been emitted since it was constructed.
     *
     * When each [FrpStateful] is applied, state accumulation from the previously-active
     * [FrpStateful] is stopped.
     *
     * Note that the value contained within the [TState] is not updated until *after* all [TFlow]s
     * have been processed; this keeps the value of the [TState] consistent during the entire FRP
     * transaction.
     *
     * Shorthand for:
     * ```kotlin
     * val (changes, initApplied) = applyLatestStateful(init)
     * return changes.toTStateDeferred(initApplied)
     * ```
     */
    @ExperimentalFrpApi
    fun <A> TFlow<FrpStateful<A>>.holdLatestStateful(init: FrpStateful<A>): TState<A> {
        val (changes, initApplied) = applyLatestStateful(init)
        return changes.holdDeferred(initApplied)
    }

    /**
     * Returns a [TFlow] that emits the two most recent emissions from the original [TFlow].
     * [initialValue] is used as the previous value for the first emission.
     *
     * Shorthand for `sample(hold(init)) { new, old -> Pair(old, new) }`
     */
    @ExperimentalFrpApi
    fun <S, T : S> TFlow<T>.pairwise(initialValue: S): TFlow<WithPrev<S, T>> {
        val previous = hold(initialValue)
        return mapCheap { new -> WithPrev(previousValue = previous.sample(), newValue = new) }
    }

    /**
     * Returns a [TFlow] that emits the two most recent emissions from the original [TFlow]. Note
     * that the returned [TFlow] will not emit until the original [TFlow] has emitted twice.
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.pairwise(): TFlow<WithPrev<A, A>> =
        mapCheap { just(it) }
            .pairwise(none)
            .mapMaybe { (prev, next) -> prev.zipWith(next, ::WithPrev) }

    /**
     * Returns a [TState] that holds both the current and previous values of the original [TState].
     * [initialPreviousValue] is used as the first previous value.
     *
     * Shorthand for `sample(hold(init)) { new, old -> Pair(old, new) }`
     */
    @ExperimentalFrpApi
    fun <S, T : S> TState<T>.pairwise(initialPreviousValue: S): TState<WithPrev<S, T>> =
        stateChanges
            .pairwise(initialPreviousValue)
            .holdDeferred(deferredTransactionScope { WithPrev(initialPreviousValue, sample()) })

    /**
     * Returns a [TState] holding a [Map] that is updated incrementally whenever this emits a value.
     *
     * The value emitted is used as a "patch" for the tracked [Map]; for each key [K] in the emitted
     * map, an associated value of [Just] will insert or replace the value in the tracked [Map], and
     * an associated value of [none] will remove the key from the tracked [Map].
     */
    @ExperimentalFrpApi
    fun <K, V> TFlow<Map<K, Maybe<V>>>.foldMapIncrementally(
        initialValues: FrpDeferredValue<Map<K, V>>
    ): TState<Map<K, V>> =
        foldDeferred(initialValues) { patch, map ->
            val (adds: List<Pair<K, V>>, removes: List<K>) =
                patch
                    .asSequence()
                    .map { (k, v) -> if (v is Just) Left(k to v.value) else Right(k) }
                    .partitionEithers()
            val removed: Map<K, V> = map - removes.toSet()
            val updated: Map<K, V> = removed + adds
            updated
        }

    /**
     * Returns a [TState] holding a [Map] that is updated incrementally whenever this emits a value.
     *
     * The value emitted is used as a "patch" for the tracked [Map]; for each key [K] in the emitted
     * map, an associated value of [Just] will insert or replace the value in the tracked [Map], and
     * an associated value of [none] will remove the key from the tracked [Map].
     */
    @ExperimentalFrpApi
    fun <K, V> TFlow<Map<K, Maybe<V>>>.foldMapIncrementally(
        initialValues: Map<K, V> = emptyMap()
    ): TState<Map<K, V>> = foldMapIncrementally(deferredOf(initialValues))

    /**
     * Returns a [TFlow] that wraps each emission of the original [TFlow] into an [IndexedValue],
     * containing the emitted value and its index (starting from zero).
     *
     * Shorthand for:
     * ```
     *   val index = fold(0) { _, oldIdx -> oldIdx + 1 }
     *   sample(index) { a, idx -> IndexedValue(idx, a) }
     * ```
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.withIndex(): TFlow<IndexedValue<A>> {
        val index = fold(0) { _, old -> old + 1 }
        return sample(index) { a, idx -> IndexedValue(idx, a) }
    }

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow] and its index (starting from zero).
     *
     * Shorthand for:
     * ```
     *   withIndex().map { (idx, a) -> transform(idx, a) }
     * ```
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.mapIndexed(transform: suspend FrpTransactionScope.(Int, A) -> B): TFlow<B> {
        val index = fold(0) { _, i -> i + 1 }
        return sample(index) { a, idx -> transform(idx, a) }
    }

    /** Returns a [TFlow] where all subsequent repetitions of the same value are filtered out. */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.distinctUntilChanged(): TFlow<A> {
        val state: TState<Any?> = hold(Any())
        return filter { it != state.sample() }
    }

    /**
     * Returns a new [TFlow] that emits at the same rate as the original [TFlow], but combines the
     * emitted value with the most recent emission from [other] using [transform].
     *
     * Note that the returned [TFlow] will not emit anything until [other] has emitted at least one
     * value.
     */
    @ExperimentalFrpApi
    fun <A, B, C> TFlow<A>.sample(
        other: TFlow<B>,
        transform: suspend FrpTransactionScope.(A, B) -> C,
    ): TFlow<C> {
        val state = other.mapCheap { just(it) }.hold(none)
        return sample(state) { a, b -> b.map { transform(a, it) } }.filterJust()
    }

    /**
     * Returns a [TState] that samples the [Transactional] held by the given [TState] within the
     * same transaction that the state changes.
     */
    @ExperimentalFrpApi
    fun <A> TState<Transactional<A>>.sampleTransactionals(): TState<A> =
        stateChanges
            .sampleTransactionals()
            .holdDeferred(deferredTransactionScope { sample().sample() })

    /**
     * Returns a [TState] that transforms the value held inside this [TState] by applying it to the
     * given function [transform].
     */
    @ExperimentalFrpApi
    fun <A, B> TState<A>.map(transform: suspend FrpTransactionScope.(A) -> B): TState<B> =
        mapPure { transactionally { transform(it) } }.sampleTransactionals()

    /**
     * Returns a [TState] whose value is generated with [transform] by combining the current values
     * of each given [TState].
     *
     * @see TState.combineWith
     */
    @ExperimentalFrpApi
    fun <A, B, Z> combine(
        stateA: TState<A>,
        stateB: TState<B>,
        transform: suspend FrpTransactionScope.(A, B) -> Z,
    ): TState<Z> =
        com.android.systemui.kairos
            .combine(stateA, stateB) { a, b -> transactionally { transform(a, b) } }
            .sampleTransactionals()

    /**
     * Returns a [TState] whose value is generated with [transform] by combining the current values
     * of each given [TState].
     *
     * @see TState.combineWith
     */
    @ExperimentalFrpApi
    fun <A, B, C, D, Z> combine(
        stateA: TState<A>,
        stateB: TState<B>,
        stateC: TState<C>,
        stateD: TState<D>,
        transform: suspend FrpTransactionScope.(A, B, C, D) -> Z,
    ): TState<Z> =
        com.android.systemui.kairos
            .combine(stateA, stateB, stateC, stateD) { a, b, c, d ->
                transactionally { transform(a, b, c, d) }
            }
            .sampleTransactionals()

    /** Returns a [TState] by applying [transform] to the value held by the original [TState]. */
    @ExperimentalFrpApi
    fun <A, B> TState<A>.flatMap(
        transform: suspend FrpTransactionScope.(A) -> TState<B>
    ): TState<B> = mapPure { transactionally { transform(it) } }.sampleTransactionals().flatten()

    /**
     * Returns a [TState] whose value is generated with [transform] by combining the current values
     * of each given [TState].
     *
     * @see TState.combineWith
     */
    @ExperimentalFrpApi
    fun <A, Z> combine(
        vararg states: TState<A>,
        transform: suspend FrpTransactionScope.(List<A>) -> Z,
    ): TState<Z> = combinePure(*states).map(transform)

    /**
     * Returns a [TState] whose value is generated with [transform] by combining the current values
     * of each given [TState].
     *
     * @see TState.combineWith
     */
    @ExperimentalFrpApi
    fun <A, Z> Iterable<TState<A>>.combine(
        transform: suspend FrpTransactionScope.(List<A>) -> Z
    ): TState<Z> = combinePure().map(transform)

    /**
     * Returns a [TState] by combining the values held inside the given [TState]s by applying them
     * to the given function [transform].
     */
    @ExperimentalFrpApi
    fun <A, B, C> TState<A>.combineWith(
        other: TState<B>,
        transform: suspend FrpTransactionScope.(A, B) -> C,
    ): TState<C> = combine(this, other, transform)
}
