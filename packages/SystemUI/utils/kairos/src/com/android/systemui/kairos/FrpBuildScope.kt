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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.kairos

import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.map
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.RestrictsSuspension
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

/** A function that modifies the FrpNetwork. */
typealias FrpSpec<A> = suspend FrpBuildScope.() -> A

/**
 * Constructs an [FrpSpec]. The passed [block] will be invoked with an [FrpBuildScope] that can be
 * used to perform network-building operations, including adding new inputs and outputs to the
 * network, as well as all operations available in [FrpTransactionScope].
 */
@ExperimentalFrpApi
@Suppress("NOTHING_TO_INLINE")
inline fun <A> frpSpec(noinline block: suspend FrpBuildScope.() -> A): FrpSpec<A> = block

/** Applies the [FrpSpec] within this [FrpBuildScope]. */
@ExperimentalFrpApi
inline operator fun <A> FrpBuildScope.invoke(block: FrpBuildScope.() -> A) = run(block)

/** Operations that add inputs and outputs to an FRP network. */
@ExperimentalFrpApi
@RestrictsSuspension
interface FrpBuildScope : FrpStateScope {

    /** TODO: Javadoc */
    @ExperimentalFrpApi
    fun <R> deferredBuildScope(block: suspend FrpBuildScope.() -> R): FrpDeferredValue<R>

    /** TODO: Javadoc */
    @ExperimentalFrpApi fun deferredBuildScopeAction(block: suspend FrpBuildScope.() -> Unit)

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow].
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * Unlike [mapLatestBuild], these modifications are not undone with each subsequent emission of
     * the original [TFlow].
     *
     * **NOTE:** This API does not [observe] the original [TFlow], meaning that unless the returned
     * (or a downstream) [TFlow] is observed separately, [transform] will not be invoked, and no
     * internal side-effects will occur.
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.mapBuild(transform: suspend FrpBuildScope.(A) -> B): TFlow<B>

    /**
     * Invokes [block] whenever this [TFlow] emits a value, allowing side-effects to be safely
     * performed in reaction to the emission.
     *
     * Specifically, [block] is deferred to the end of the transaction, and is only actually
     * executed if this [FrpBuildScope] is still active by that time. It can be deactivated due to a
     * -Latest combinator, for example.
     *
     * Shorthand for:
     * ```kotlin
     *   tFlow.observe { effect { ... } }
     * ```
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.observe(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        block: suspend FrpEffectScope.(A) -> Unit = {},
    ): Job

    /**
     * Returns a [TFlow] containing the results of applying each [FrpSpec] emitted from the original
     * [TFlow], and a [FrpDeferredValue] containing the result of applying [initialSpecs]
     * immediately.
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] with the same
     * key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpSpec] will be undone with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<FrpSpec<A>>>>.applyLatestSpecForKey(
        initialSpecs: FrpDeferredValue<Map<K, FrpSpec<B>>>,
        numKeys: Int? = null,
    ): Pair<TFlow<Map<K, Maybe<A>>>, FrpDeferredValue<Map<K, B>>>

    /**
     * Creates an instance of a [TFlow] with elements that are from [builder].
     *
     * [builder] is run in its own coroutine, allowing for ongoing work that can emit to the
     * provided [MutableTFlow].
     *
     * By default, [builder] is only running while the returned [TFlow] is being
     * [observed][observe]. If you want it to run at all times, simply add a no-op observer:
     * ```kotlin
     * tFlow { ... }.apply { observe() }
     * ```
     */
    @ExperimentalFrpApi fun <T> tFlow(builder: suspend FrpProducerScope<T>.() -> Unit): TFlow<T>

    /**
     * Creates an instance of a [TFlow] with elements that are emitted from [builder].
     *
     * [builder] is run in its own coroutine, allowing for ongoing work that can emit to the
     * provided [MutableTFlow].
     *
     * By default, [builder] is only running while the returned [TFlow] is being
     * [observed][observe]. If you want it to run at all times, simply add a no-op observer:
     * ```kotlin
     * tFlow { ... }.apply { observe() }
     * ```
     *
     * In the event of backpressure, emissions are *coalesced* into batches. When a value is
     * [emitted][FrpCoalescingProducerScope.emit] from [builder], it is merged into the batch via
     * [coalesce]. Once the batch is consumed by the frp network in the next transaction, the batch
     * is reset back to [getInitialValue].
     */
    @ExperimentalFrpApi
    fun <In, Out> coalescingTFlow(
        getInitialValue: () -> Out,
        coalesce: (old: Out, new: In) -> Out,
        builder: suspend FrpCoalescingProducerScope<In>.() -> Unit,
    ): TFlow<Out>

    /**
     * Creates a new [FrpBuildScope] that is a child of this one.
     *
     * This new scope can be manually cancelled via the returned [Job], or will be cancelled
     * automatically when its parent is cancelled. Cancellation will unregister all
     * [observers][observe] and cancel all scheduled [effects][effect].
     *
     * The return value from [block] can be accessed via the returned [FrpDeferredValue].
     */
    @ExperimentalFrpApi fun <A> asyncScope(block: FrpSpec<A>): Pair<FrpDeferredValue<A>, Job>

    // TODO: once we have context params, these can all become extensions:

    /**
     * Returns a [TFlow] containing the results of applying the given [transform] function to each
     * value of the original [TFlow].
     *
     * Unlike [TFlow.map], [transform] can perform arbitrary asynchronous code. This code is run
     * outside of the current FRP transaction; when [transform] returns, the returned value is
     * emitted from the result [TFlow] in a new transaction.
     *
     * Shorthand for:
     * ```kotlin
     * tflow.mapLatestBuild { a -> asyncTFlow { transform(a) } }.flatten()
     * ```
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.mapAsyncLatest(transform: suspend (A) -> B): TFlow<B> =
        mapLatestBuild { a -> asyncTFlow { transform(a) } }.flatten()

    /**
     * Invokes [block] whenever this [TFlow] emits a value. [block] receives an [FrpBuildScope] that
     * can be used to make further modifications to the FRP network, and/or perform side-effects via
     * [effect].
     *
     * @see observe
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.observeBuild(block: suspend FrpBuildScope.(A) -> Unit = {}): Job =
        mapBuild(block).observe()

    /**
     * Returns a [StateFlow] whose [value][StateFlow.value] tracks the current
     * [value of this TState][TState.sample], and will emit at the same rate as
     * [TState.stateChanges].
     *
     * Note that the [value][StateFlow.value] is not available until the *end* of the current
     * transaction. If you need the current value before this time, then use [TState.sample].
     */
    @ExperimentalFrpApi
    fun <A> TState<A>.toStateFlow(): StateFlow<A> {
        val uninitialized = Any()
        var initialValue: Any? = uninitialized
        val innerStateFlow = MutableStateFlow<Any?>(uninitialized)
        deferredBuildScope {
            initialValue = sample()
            stateChanges.observe {
                innerStateFlow.value = it
                initialValue = null
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun getValue(innerValue: Any?): A =
            when {
                innerValue !== uninitialized -> innerValue as A
                initialValue !== uninitialized -> initialValue as A
                else ->
                    error(
                        "Attempted to access StateFlow.value before FRP transaction has completed."
                    )
            }

        return object : StateFlow<A> {
            override val replayCache: List<A>
                get() = innerStateFlow.replayCache.map(::getValue)

            override val value: A
                get() = getValue(innerStateFlow.value)

            override suspend fun collect(collector: FlowCollector<A>): Nothing {
                innerStateFlow.collect { collector.emit(getValue(it)) }
            }
        }
    }

    /**
     * Returns a [SharedFlow] configured with a replay cache of size [replay] that emits the current
     * [value][TState.sample] of this [TState] followed by all [stateChanges].
     */
    @ExperimentalFrpApi
    fun <A> TState<A>.toSharedFlow(replay: Int = 0): SharedFlow<A> {
        val result = MutableSharedFlow<A>(replay, extraBufferCapacity = 1)
        deferredBuildScope {
            result.tryEmit(sample())
            stateChanges.observe { a -> result.tryEmit(a) }
        }
        return result
    }

    /**
     * Returns a [SharedFlow] configured with a replay cache of size [replay] that emits values
     * whenever this [TFlow] emits.
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.toSharedFlow(replay: Int = 0): SharedFlow<A> {
        val result = MutableSharedFlow<A>(replay, extraBufferCapacity = 1)
        observe { a -> result.tryEmit(a) }
        return result
    }

    /**
     * Returns a [TState] that holds onto the value returned by applying the most recently emitted
     * [FrpSpec] from the original [TFlow], or the value returned by applying [initialSpec] if
     * nothing has been emitted since it was constructed.
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A> TFlow<FrpSpec<A>>.holdLatestSpec(initialSpec: FrpSpec<A>): TState<A> {
        val (changes: TFlow<A>, initApplied: FrpDeferredValue<A>) = applyLatestSpec(initialSpec)
        return changes.holdDeferred(initApplied)
    }

    /**
     * Returns a [TState] containing the value returned by applying the [FrpSpec] held by the
     * original [TState].
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A> TState<FrpSpec<A>>.applyLatestSpec(): TState<A> {
        val (appliedChanges: TFlow<A>, init: FrpDeferredValue<A>) =
            stateChanges.applyLatestSpec(frpSpec { sample().applySpec() })
        return appliedChanges.holdDeferred(init)
    }

    /**
     * Returns a [TFlow] containing the results of applying each [FrpSpec] emitted from the original
     * [TFlow].
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A> TFlow<FrpSpec<A>>.applyLatestSpec(): TFlow<A> = applyLatestSpec(frpSpec {}).first

    /**
     * Returns a [TFlow] that switches to a new [TFlow] produced by [transform] every time the
     * original [TFlow] emits a value.
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * When the original [TFlow] emits a new value, those changes are undone (any registered
     * [observers][observe] are unregistered, and any pending [effects][effect] are cancelled).
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.flatMapLatestBuild(
        transform: suspend FrpBuildScope.(A) -> TFlow<B>
    ): TFlow<B> = mapCheap { frpSpec { transform(it) } }.applyLatestSpec().flatten()

    /**
     * Returns a [TState] by applying [transform] to the value held by the original [TState].
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * When the value held by the original [TState] changes, those changes are undone (any
     * registered [observers][observe] are unregistered, and any pending [effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A, B> TState<A>.flatMapLatestBuild(
        transform: suspend FrpBuildScope.(A) -> TState<B>
    ): TState<B> = mapLatestBuild { transform(it) }.flatten()

    /**
     * Returns a [TState] that transforms the value held inside this [TState] by applying it to the
     * [transform].
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * When the value held by the original [TState] changes, those changes are undone (any
     * registered [observers][observe] are unregistered, and any pending [effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A, B> TState<A>.mapLatestBuild(transform: suspend FrpBuildScope.(A) -> B): TState<B> =
        mapCheapUnsafe { frpSpec { transform(it) } }.applyLatestSpec()

    /**
     * Returns a [TFlow] containing the results of applying each [FrpSpec] emitted from the original
     * [TFlow], and a [FrpDeferredValue] containing the result of applying [initialSpec]
     * immediately.
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A : Any?, B> TFlow<FrpSpec<B>>.applyLatestSpec(
        initialSpec: FrpSpec<A>
    ): Pair<TFlow<B>, FrpDeferredValue<A>> {
        val (flow, result) =
            mapCheap { spec -> mapOf(Unit to just(spec)) }
                .applyLatestSpecForKey(initialSpecs = mapOf(Unit to initialSpec), numKeys = 1)
        val outFlow: TFlow<B> =
            flow.mapMaybe {
                checkNotNull(it[Unit]) { "applyLatest: expected result, but none present in: $it" }
            }
        val outInit: FrpDeferredValue<A> = deferredBuildScope {
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
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow].
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.mapLatestBuild(transform: suspend FrpBuildScope.(A) -> B): TFlow<B> =
        mapCheap { frpSpec { transform(it) } }.applyLatestSpec()

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [transform] to
     * [initialValue] immediately.
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.mapLatestBuild(
        initialValue: A,
        transform: suspend FrpBuildScope.(A) -> B,
    ): Pair<TFlow<B>, FrpDeferredValue<B>> =
        mapLatestBuildDeferred(deferredOf(initialValue), transform)

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [transform] to
     * [initialValue] immediately.
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A, B> TFlow<A>.mapLatestBuildDeferred(
        initialValue: FrpDeferredValue<A>,
        transform: suspend FrpBuildScope.(A) -> B,
    ): Pair<TFlow<B>, FrpDeferredValue<B>> =
        mapCheap { frpSpec { transform(it) } }
            .applyLatestSpec(initialSpec = frpSpec { transform(initialValue.get()) })

    /**
     * Returns a [TFlow] containing the results of applying each [FrpSpec] emitted from the original
     * [TFlow], and a [FrpDeferredValue] containing the result of applying [initialSpecs]
     * immediately.
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] with the same
     * key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpSpec] will be undone with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<FrpSpec<A>>>>.applyLatestSpecForKey(
        initialSpecs: Map<K, FrpSpec<B>>,
        numKeys: Int? = null,
    ): Pair<TFlow<Map<K, Maybe<A>>>, FrpDeferredValue<Map<K, B>>> =
        applyLatestSpecForKey(deferredOf(initialSpecs), numKeys)

    /**
     * Returns a [TFlow] containing the results of applying each [FrpSpec] emitted from the original
     * [TFlow].
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] with the same
     * key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpSpec] will be undone with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A> TFlow<Map<K, Maybe<FrpSpec<A>>>>.applyLatestSpecForKey(
        numKeys: Int? = null
    ): TFlow<Map<K, Maybe<A>>> =
        applyLatestSpecForKey<K, A, Nothing>(deferredOf(emptyMap()), numKeys).first

    /**
     * Returns a [TState] containing the latest results of applying each [FrpSpec] emitted from the
     * original [TFlow].
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] with the same
     * key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpSpec] will be undone with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A> TFlow<Map<K, Maybe<FrpSpec<A>>>>.holdLatestSpecForKey(
        initialSpecs: FrpDeferredValue<Map<K, FrpSpec<A>>>,
        numKeys: Int? = null,
    ): TState<Map<K, A>> {
        val (changes, initialValues) = applyLatestSpecForKey(initialSpecs, numKeys)
        return changes.foldMapIncrementally(initialValues)
    }

    /**
     * Returns a [TState] containing the latest results of applying each [FrpSpec] emitted from the
     * original [TFlow].
     *
     * When each [FrpSpec] is applied, changes from the previously-active [FrpSpec] with the same
     * key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpSpec] will be undone with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A> TFlow<Map<K, Maybe<FrpSpec<A>>>>.holdLatestSpecForKey(
        initialSpecs: Map<K, FrpSpec<A>> = emptyMap(),
        numKeys: Int? = null,
    ): TState<Map<K, A>> = holdLatestSpecForKey(deferredOf(initialSpecs), numKeys)

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [transform] to
     * [initialValues] immediately.
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpBuildScope] will be undone with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<A>>>.mapLatestBuildForKey(
        initialValues: FrpDeferredValue<Map<K, A>>,
        numKeys: Int? = null,
        transform: suspend FrpBuildScope.(A) -> B,
    ): Pair<TFlow<Map<K, Maybe<B>>>, FrpDeferredValue<Map<K, B>>> =
        map { patch -> patch.mapValues { (_, v) -> v.map { frpSpec { transform(it) } } } }
            .applyLatestSpecForKey(
                deferredBuildScope {
                    initialValues.get().mapValues { (_, v) -> frpSpec { transform(v) } }
                },
                numKeys = numKeys,
            )

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow], and a [FrpDeferredValue] containing the result of applying [transform] to
     * [initialValues] immediately.
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpBuildScope] will be undone with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<A>>>.mapLatestBuildForKey(
        initialValues: Map<K, A>,
        numKeys: Int? = null,
        transform: suspend FrpBuildScope.(A) -> B,
    ): Pair<TFlow<Map<K, Maybe<B>>>, FrpDeferredValue<Map<K, B>>> =
        mapLatestBuildForKey(deferredOf(initialValues), numKeys, transform)

    /**
     * Returns a [TFlow] containing the results of applying [transform] to each value of the
     * original [TFlow].
     *
     * [transform] can perform modifications to the FRP network via its [FrpBuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     *
     * If the [Maybe] contained within the value for an associated key is [none], then the
     * previously-active [FrpBuildScope] will be undone with no replacement.
     */
    @ExperimentalFrpApi
    fun <K, A, B> TFlow<Map<K, Maybe<A>>>.mapLatestBuildForKey(
        numKeys: Int? = null,
        transform: suspend FrpBuildScope.(A) -> B,
    ): TFlow<Map<K, Maybe<B>>> = mapLatestBuildForKey(emptyMap(), numKeys, transform).first

    /** Returns a [Deferred] containing the next value to be emitted from this [TFlow]. */
    @ExperimentalFrpApi
    fun <R> TFlow<R>.nextDeferred(): Deferred<R> {
        lateinit var next: CompletableDeferred<R>
        val job = nextOnly().observe { next.complete(it) }
        next = CompletableDeferred<R>(parent = job)
        return next
    }

    /** Returns a [TState] that reflects the [StateFlow.value] of this [StateFlow]. */
    @ExperimentalFrpApi
    fun <A> StateFlow<A>.toTState(): TState<A> {
        val initial = value
        return tFlow { dropWhile { it == initial }.collect { emit(it) } }.hold(initial)
    }

    /** Returns a [TFlow] that emits whenever this [Flow] emits. */
    @ExperimentalFrpApi fun <A> Flow<A>.toTFlow(): TFlow<A> = tFlow { collect { emit(it) } }

    /**
     * Shorthand for:
     * ```kotlin
     * flow.toTFlow().hold(initialValue)
     * ```
     */
    @ExperimentalFrpApi
    fun <A> Flow<A>.toTState(initialValue: A): TState<A> = toTFlow().hold(initialValue)

    /**
     * Shorthand for:
     * ```kotlin
     * flow.scan(initialValue, operation).toTFlow().hold(initialValue)
     * ```
     */
    @ExperimentalFrpApi
    fun <A, B> Flow<A>.scanToTState(initialValue: B, operation: (B, A) -> B): TState<B> =
        scan(initialValue, operation).toTFlow().hold(initialValue)

    /**
     * Shorthand for:
     * ```kotlin
     * flow.scan(initialValue) { a, f -> f(a) }.toTFlow().hold(initialValue)
     * ```
     */
    @ExperimentalFrpApi
    fun <A> Flow<(A) -> A>.scanToTState(initialValue: A): TState<A> =
        scanToTState(initialValue) { a, f -> f(a) }

    /**
     * Invokes [block] whenever this [TFlow] emits a value. [block] receives an [FrpBuildScope] that
     * can be used to make further modifications to the FRP network, and/or perform side-effects via
     * [effect].
     *
     * With each invocation of [block], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.observeLatestBuild(block: suspend FrpBuildScope.(A) -> Unit = {}): Job =
        mapLatestBuild { block(it) }.observe()

    /**
     * Invokes [block] whenever this [TFlow] emits a value, allowing side-effects to be safely
     * performed in reaction to the emission.
     *
     * With each invocation of [block], running effects from the previous invocation are cancelled.
     */
    @ExperimentalFrpApi
    fun <A> TFlow<A>.observeLatest(block: suspend FrpEffectScope.(A) -> Unit = {}): Job {
        var innerJob: Job? = null
        return observeBuild {
            innerJob?.cancel()
            innerJob = effect { block(it) }
        }
    }

    /**
     * Invokes [block] with the value held by this [TState], allowing side-effects to be safely
     * performed in reaction to the state changing.
     *
     * With each invocation of [block], running effects from the previous invocation are cancelled.
     */
    @ExperimentalFrpApi
    fun <A> TState<A>.observeLatest(block: suspend FrpEffectScope.(A) -> Unit = {}): Job =
        launchScope {
            var innerJob = effect { block(sample()) }
            stateChanges.observeBuild {
                innerJob.cancel()
                innerJob = effect { block(it) }
            }
        }

    /**
     * Applies [block] to the value held by this [TState]. [block] receives an [FrpBuildScope] that
     * can be used to make further modifications to the FRP network, and/or perform side-effects via
     * [effect].
     *
     * [block] can perform modifications to the FRP network via its [FrpBuildScope] receiver. With
     * each invocation of [block], changes from the previous invocation are undone (any registered
     * [observers][observe] are unregistered, and any pending [side-effects][effect] are cancelled).
     */
    @ExperimentalFrpApi
    fun <A> TState<A>.observeLatestBuild(block: suspend FrpBuildScope.(A) -> Unit = {}): Job =
        launchScope {
            var innerJob: Job = launchScope { block(sample()) }
            stateChanges.observeBuild {
                innerJob.cancel()
                innerJob = launchScope { block(it) }
            }
        }

    /** Applies the [FrpSpec] within this [FrpBuildScope]. */
    @ExperimentalFrpApi suspend fun <A> FrpSpec<A>.applySpec(): A = this()

    /**
     * Applies the [FrpSpec] within this [FrpBuildScope], returning the result as an
     * [FrpDeferredValue].
     */
    @ExperimentalFrpApi
    fun <A> FrpSpec<A>.applySpecDeferred(): FrpDeferredValue<A> = deferredBuildScope { applySpec() }

    /**
     * Invokes [block] on the value held in this [TState]. [block] receives an [FrpBuildScope] that
     * can be used to make further modifications to the FRP network, and/or perform side-effects via
     * [effect].
     */
    @ExperimentalFrpApi
    fun <A> TState<A>.observeBuild(block: suspend FrpBuildScope.(A) -> Unit = {}): Job =
        launchScope {
            block(sample())
            stateChanges.observeBuild(block)
        }

    /**
     * Invokes [block] with the current value of this [TState], re-invoking whenever it changes,
     * allowing side-effects to be safely performed in reaction value changing.
     *
     * Specifically, [block] is deferred to the end of the transaction, and is only actually
     * executed if this [FrpBuildScope] is still active by that time. It can be deactivated due to a
     * -Latest combinator, for example.
     *
     * If the [TState] is changing within the *current* transaction (i.e. [stateChanges] is
     * presently emitting) then [block] will be invoked for the first time with the new value;
     * otherwise, it will be invoked with the [current][sample] value.
     */
    @ExperimentalFrpApi
    fun <A> TState<A>.observe(block: suspend FrpEffectScope.(A) -> Unit = {}): Job =
        now.map { sample() }.mergeWith(stateChanges) { _, new -> new }.observe { block(it) }
}

/**
 * Returns a [TFlow] that emits the result of [block] once it completes. [block] is evaluated
 * outside of the current FRP transaction; when it completes, the returned [TFlow] emits in a new
 * transaction.
 *
 * Shorthand for:
 * ```
 * tFlow { emitter: MutableTFlow<A> ->
 *     val a = block()
 *     emitter.emit(a)
 * }
 * ```
 */
@ExperimentalFrpApi
fun <A> FrpBuildScope.asyncTFlow(block: suspend () -> A): TFlow<A> =
    tFlow {
            // TODO: if block completes synchronously, it would be nice to emit within this
            //  transaction
            emit(block())
        }
        .apply { observe() }

/**
 * Performs a side-effect in a safe manner w/r/t the current FRP transaction.
 *
 * Specifically, [block] is deferred to the end of the current transaction, and is only actually
 * executed if this [FrpBuildScope] is still active by that time. It can be deactivated due to a
 * -Latest combinator, for example.
 *
 * Shorthand for:
 * ```kotlin
 *   now.observe { block() }
 * ```
 */
@ExperimentalFrpApi
fun FrpBuildScope.effect(block: suspend FrpEffectScope.() -> Unit): Job = now.observe { block() }

/**
 * Launches [block] in a new coroutine, returning a [Job] bound to the coroutine.
 *
 * This coroutine is not actually started until the *end* of the current FRP transaction. This is
 * done because the current [FrpBuildScope] might be deactivated within this transaction, perhaps
 * due to a -Latest combinator. If this happens, then the coroutine will never actually be started.
 *
 * Shorthand for:
 * ```kotlin
 *   effect { frpCoroutineScope.launch { block() } }
 * ```
 */
@ExperimentalFrpApi
fun FrpBuildScope.launchEffect(block: suspend CoroutineScope.() -> Unit): Job = asyncEffect(block)

/**
 * Launches [block] in a new coroutine, returning the result as a [Deferred].
 *
 * This coroutine is not actually started until the *end* of the current FRP transaction. This is
 * done because the current [FrpBuildScope] might be deactivated within this transaction, perhaps
 * due to a -Latest combinator. If this happens, then the coroutine will never actually be started.
 *
 * Shorthand for:
 * ```kotlin
 *   CompletableDeferred<R>.apply {
 *       effect { frpCoroutineScope.launch { complete(coroutineScope { block() }) } }
 *     }
 *     .await()
 * ```
 */
@ExperimentalFrpApi
fun <R> FrpBuildScope.asyncEffect(block: suspend CoroutineScope.() -> R): Deferred<R> {
    val result = CompletableDeferred<R>()
    val job = now.observe { frpCoroutineScope.launch { result.complete(coroutineScope(block)) } }
    val handle = job.invokeOnCompletion { result.cancel() }
    result.invokeOnCompletion {
        handle.dispose()
        job.cancel()
    }
    return result
}

/** Like [FrpBuildScope.asyncScope], but ignores the result of [block]. */
@ExperimentalFrpApi fun FrpBuildScope.launchScope(block: FrpSpec<*>): Job = asyncScope(block).second

/**
 * Creates an instance of a [TFlow] with elements that are emitted from [builder].
 *
 * [builder] is run in its own coroutine, allowing for ongoing work that can emit to the provided
 * [MutableTFlow].
 *
 * By default, [builder] is only running while the returned [TFlow] is being
 * [observed][FrpBuildScope.observe]. If you want it to run at all times, simply add a no-op
 * observer:
 * ```kotlin
 * tFlow { ... }.apply { observe() }
 * ```
 *
 * In the event of backpressure, emissions are *coalesced* into batches. When a value is
 * [emitted][FrpCoalescingProducerScope.emit] from [builder], it is merged into the batch via
 * [coalesce]. Once the batch is consumed by the FRP network in the next transaction, the batch is
 * reset back to [initialValue].
 */
@ExperimentalFrpApi
fun <In, Out> FrpBuildScope.coalescingTFlow(
    initialValue: Out,
    coalesce: (old: Out, new: In) -> Out,
    builder: suspend FrpCoalescingProducerScope<In>.() -> Unit,
): TFlow<Out> = coalescingTFlow(getInitialValue = { initialValue }, coalesce, builder)

/**
 * Creates an instance of a [TFlow] with elements that are emitted from [builder].
 *
 * [builder] is run in its own coroutine, allowing for ongoing work that can emit to the provided
 * [MutableTFlow].
 *
 * By default, [builder] is only running while the returned [TFlow] is being
 * [observed][FrpBuildScope.observe]. If you want it to run at all times, simply add a no-op
 * observer:
 * ```kotlin
 * tFlow { ... }.apply { observe() }
 * ```
 *
 * In the event of backpressure, emissions are *conflated*; any older emissions are dropped and only
 * the most recent emission will be used when the FRP network is ready.
 */
@ExperimentalFrpApi
fun <T> FrpBuildScope.conflatedTFlow(
    builder: suspend FrpCoalescingProducerScope<T>.() -> Unit
): TFlow<T> =
    coalescingTFlow<T, Any?>(initialValue = Any(), coalesce = { _, new -> new }, builder = builder)
        .mapCheap {
            @Suppress("UNCHECKED_CAST")
            it as T
        }

/** Scope for emitting to a [FrpBuildScope.coalescingTFlow]. */
interface FrpCoalescingProducerScope<in T> {
    /**
     * Inserts [value] into the current batch, enqueueing it for emission from this [TFlow] if not
     * already pending.
     *
     * Backpressure occurs when [emit] is called while the FRP network is currently in a
     * transaction; if called multiple times, then emissions will be coalesced into a single batch
     * that is then processed when the network is ready.
     */
    fun emit(value: T)
}

/** Scope for emitting to a [FrpBuildScope.tFlow]. */
interface FrpProducerScope<in T> {
    /**
     * Emits a [value] to this [TFlow], suspending the caller until the FRP transaction containing
     * the emission has completed.
     */
    suspend fun emit(value: T)
}

/**
 * Suspends forever. Upon cancellation, runs [block]. Useful for unregistering callbacks inside of
 * [FrpBuildScope.tFlow] and [FrpBuildScope.coalescingTFlow].
 */
suspend fun awaitClose(block: () -> Unit): Nothing =
    try {
        awaitCancellation()
    } finally {
        block()
    }
