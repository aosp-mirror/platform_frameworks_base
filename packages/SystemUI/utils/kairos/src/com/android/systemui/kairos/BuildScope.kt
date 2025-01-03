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
import com.android.systemui.kairos.util.map
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.scan

/** A computation that can modify the Kairos network. */
typealias BuildSpec<A> = BuildScope.() -> A

/**
 * Constructs a [BuildSpec]. The passed [block] will be invoked with a [BuildScope] that can be used
 * to perform network-building operations, including adding new inputs and outputs to the network,
 * as well as all operations available in [TransactionScope].
 */
@ExperimentalKairosApi
@Suppress("NOTHING_TO_INLINE")
inline fun <A> buildSpec(noinline block: BuildScope.() -> A): BuildSpec<A> = block

/** Applies the [BuildSpec] within this [BuildScope]. */
@ExperimentalKairosApi
inline operator fun <A> BuildScope.invoke(block: BuildScope.() -> A) = run(block)

/** Operations that add inputs and outputs to a Kairos network. */
@ExperimentalKairosApi
interface BuildScope : HasNetwork, StateScope {

    /**
     * Defers invoking [block] until after the current [BuildScope] code-path completes, returning a
     * [DeferredValue] that can be used to reference the result.
     *
     * Useful for recursive definitions.
     *
     * @see deferredBuildScopeAction
     * @see DeferredValue
     */
    fun <R> deferredBuildScope(block: BuildScope.() -> R): DeferredValue<R>

    /**
     * Defers invoking [block] until after the current [BuildScope] code-path completes.
     *
     * Useful for recursive definitions.
     *
     * @see deferredBuildScope
     */
    fun deferredBuildScopeAction(block: BuildScope.() -> Unit)

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events].
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * Unlike [mapLatestBuild], these modifications are not undone with each subsequent emission of
     * the original [Events].
     *
     * **NOTE:** This API does not [observe] the original [Events], meaning that unless the returned
     * (or a downstream) [Events] is observed separately, [transform] will not be invoked, and no
     * internal side-effects will occur.
     */
    fun <A, B> Events<A>.mapBuild(transform: BuildScope.(A) -> B): Events<B>

    /**
     * Invokes [block] whenever this [Events] emits a value, allowing side-effects to be safely
     * performed in reaction to the emission.
     *
     * Specifically, [block] is deferred to the end of the transaction, and is only actually
     * executed if this [BuildScope] is still active by that time. It can be deactivated due to a
     * -Latest combinator, for example.
     *
     * [Disposing][DisposableHandle.dispose] of the returned [DisposableHandle] will stop the
     * observation of new emissions. It will however *not* cancel any running effects from previous
     * emissions. To achieve this behavior, use [launchScope] or [asyncScope] to create a child
     * build scope:
     * ``` kotlin
     *   val job = launchScope {
     *       events.observe { x ->
     *           launchEffect { longRunningEffect(x) }
     *       }
     *   }
     *   // cancels observer and any running effects:
     *   job.cancel()
     * ```
     */
    // TODO: remove disposable handle return? might add more confusion than convenience
    fun <A> Events<A>.observe(
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
        block: EffectScope.(A) -> Unit = {},
    ): DisposableHandle

    /**
     * Returns an [Events] containing the results of applying each [BuildSpec] emitted from the
     * original [Events], and a [DeferredValue] containing the result of applying [initialSpecs]
     * immediately.
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] with the
     * same key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildSpec] will be undone with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<BuildSpec<A>>>>.applyLatestSpecForKey(
        initialSpecs: DeferredValue<Map<K, BuildSpec<B>>>,
        numKeys: Int? = null,
    ): Pair<Events<Map<K, Maybe<A>>>, DeferredValue<Map<K, B>>>

    /**
     * Creates an instance of an [Events] with elements that are emitted from [builder].
     *
     * [builder] is run in its own coroutine, allowing for ongoing work that can emit to the
     * provided [EventProducerScope].
     *
     * By default, [builder] is only running while the returned [Events] is being
     * [observed][observe]. If you want it to run at all times, simply add a no-op observer:
     * ``` kotlin
     *   events { ... }.apply { observe() }
     * ```
     */
    // TODO: eventually this should be defined on KairosNetwork + an extension on HasNetwork
    //  - will require modifying InputNode so that it can be manually killed, as opposed to using
    //    takeUntil (which requires a StateScope).
    fun <T> events(builder: suspend EventProducerScope<T>.() -> Unit): Events<T>

    /**
     * Creates an instance of an [Events] with elements that are emitted from [builder].
     *
     * [builder] is run in its own coroutine, allowing for ongoing work that can emit to the
     * provided [CoalescingEventProducerScope].
     *
     * By default, [builder] is only running while the returned [Events] is being
     * [observed][observe]. If you want it to run at all times, simply add a no-op observer:
     * ``` kotlin
     *   events { ... }.apply { observe() }
     * ```
     *
     * In the event of backpressure, emissions are *coalesced* into batches. When a value is
     * [emitted][CoalescingEventProducerScope.emit] from [builder], it is merged into the batch via
     * [coalesce]. Once the batch is consumed by the kairos network in the next transaction, the
     * batch is reset back to [getInitialValue].
     */
    // TODO: see TODO for [events]
    fun <In, Out> coalescingEvents(
        getInitialValue: () -> Out,
        coalesce: (old: Out, new: In) -> Out,
        builder: suspend CoalescingEventProducerScope<In>.() -> Unit,
    ): Events<Out>

    /**
     * Creates a new [BuildScope] that is a child of this one.
     *
     * This new scope can be manually cancelled via the returned [Job], or will be cancelled
     * automatically when its parent is cancelled. Cancellation will unregister all
     * [observers][observe] and cancel all scheduled [effects][effect].
     *
     * The return value from [block] can be accessed via the returned [DeferredValue].
     */
    // TODO: return a DisposableHandle instead of Job?
    fun <A> asyncScope(block: BuildSpec<A>): Pair<DeferredValue<A>, Job>

    // TODO: once we have context params, these can all become extensions:

    /**
     * Returns an [Events] containing the results of applying the given [transform] function to each
     * value of the original [Events].
     *
     * Unlike [Events.map], [transform] can perform arbitrary asynchronous code. This code is run
     * outside of the current Kairos transaction; when [transform] returns, the returned value is
     * emitted from the result [Events] in a new transaction.
     *
     * ``` kotlin
     *     fun <A, B> Events<A>.mapAsyncLatest(transform: suspend (A) -> B): Events<B> =
     *         mapLatestBuild { a -> asyncEvent { transform(a) } }.flatten()
     * ```
     */
    fun <A, B> Events<A>.mapAsyncLatest(transform: suspend (A) -> B): Events<B> =
        mapLatestBuild { a -> asyncEvent { transform(a) } }.flatten()

    /**
     * Invokes [block] whenever this [Events] emits a value. [block] receives an [BuildScope] that
     * can be used to make further modifications to the Kairos network, and/or perform side-effects
     * via [effect].
     *
     * @see observe
     */
    fun <A> Events<A>.observeBuild(block: BuildScope.(A) -> Unit = {}): DisposableHandle =
        mapBuild(block).observe()

    /**
     * Returns a [StateFlow] whose [value][StateFlow.value] tracks the current
     * [value of this State][State.sample], and will emit at the same rate as [State.changes].
     */
    fun <A> State<A>.toStateFlow(): StateFlow<A> {
        val innerStateFlow = MutableStateFlow(sampleDeferred())
        changes.observe { innerStateFlow.value = deferredOf(it) }
        return object : StateFlow<A> {
            override val replayCache: List<A>
                get() = innerStateFlow.replayCache.map { it.value }

            override val value: A
                get() = innerStateFlow.value.value

            override suspend fun collect(collector: FlowCollector<A>): Nothing {
                innerStateFlow.collect { collector.emit(it.value) }
            }
        }
    }

    /**
     * Returns a [SharedFlow] configured with a replay cache of size [replay] that emits the current
     * [value][State.sample] of this [State] followed by all [changes].
     */
    fun <A> State<A>.toSharedFlow(replay: Int = 0): SharedFlow<A> {
        val result = MutableSharedFlow<A>(replay, extraBufferCapacity = 1)
        deferredBuildScope {
            result.tryEmit(sample())
            changes.observe { a -> result.tryEmit(a) }
        }
        return result
    }

    /**
     * Returns a [SharedFlow] configured with a replay cache of size [replay] that emits values
     * whenever this [Events] emits.
     */
    fun <A> Events<A>.toSharedFlow(replay: Int = 0): SharedFlow<A> {
        val result = MutableSharedFlow<A>(replay, extraBufferCapacity = 1)
        observe { a -> result.tryEmit(a) }
        return result
    }

    /**
     * Returns a [State] that holds onto the value returned by applying the most recently emitted
     * [BuildSpec] from the original [Events], or the value returned by applying [initialSpec] if
     * nothing has been emitted since it was constructed.
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] are undone
     * (any registered [observers][observe] are unregistered, and any pending [side-effects][effect]
     * are cancelled).
     */
    fun <A> Events<BuildSpec<A>>.holdLatestSpec(initialSpec: BuildSpec<A>): State<A> {
        val (changes: Events<A>, initApplied: DeferredValue<A>) = applyLatestSpec(initialSpec)
        return changes.holdStateDeferred(initApplied)
    }

    /**
     * Returns a [State] containing the value returned by applying the [BuildSpec] held by the
     * original [State].
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] are undone
     * (any registered [observers][observe] are unregistered, and any pending [side-effects][effect]
     * are cancelled).
     */
    fun <A> State<BuildSpec<A>>.applyLatestSpec(): State<A> {
        val (appliedChanges: Events<A>, init: DeferredValue<A>) =
            changes.applyLatestSpec(buildSpec { sample().applySpec() })
        return appliedChanges.holdStateDeferred(init)
    }

    /**
     * Returns an [Events] containing the results of applying each [BuildSpec] emitted from the
     * original [Events].
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] are undone
     * (any registered [observers][observe] are unregistered, and any pending [side-effects][effect]
     * are cancelled).
     */
    fun <A> Events<BuildSpec<A>>.applyLatestSpec(): Events<A> = applyLatestSpec(buildSpec {}).first

    /**
     * Returns an [Events] that switches to a new [Events] produced by [transform] every time the
     * original [Events] emits a value.
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * When the original [Events] emits a new value, those changes are undone (any registered
     * [observers][observe] are unregistered, and any pending [effects][effect] are cancelled).
     */
    fun <A, B> Events<A>.flatMapLatestBuild(transform: BuildScope.(A) -> Events<B>): Events<B> =
        mapCheap { buildSpec { transform(it) } }.applyLatestSpec().flatten()

    /**
     * Returns a [State] by applying [transform] to the value held by the original [State].
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * When the value held by the original [State] changes, those changes are undone (any registered
     * [observers][observe] are unregistered, and any pending [effects][effect] are cancelled).
     */
    fun <A, B> State<A>.flatMapLatestBuild(transform: BuildScope.(A) -> State<B>): State<B> =
        mapLatestBuild { transform(it) }.flatten()

    /**
     * Returns a [State] that transforms the value held inside this [State] by applying it to the
     * [transform].
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * When the value held by the original [State] changes, those changes are undone (any registered
     * [observers][observe] are unregistered, and any pending [effects][effect] are cancelled).
     */
    fun <A, B> State<A>.mapLatestBuild(transform: BuildScope.(A) -> B): State<B> =
        mapCheapUnsafe { buildSpec { transform(it) } }.applyLatestSpec()

    /**
     * Returns an [Events] containing the results of applying each [BuildSpec] emitted from the
     * original [Events], and a [DeferredValue] containing the result of applying [initialSpec]
     * immediately.
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] are undone
     * (any registered [observers][observe] are unregistered, and any pending [side-effects][effect]
     * are cancelled).
     */
    fun <A : Any?, B> Events<BuildSpec<B>>.applyLatestSpec(
        initialSpec: BuildSpec<A>
    ): Pair<Events<B>, DeferredValue<A>> {
        val (events, result) =
            mapCheap { spec -> mapOf(Unit to Maybe.present(spec)) }
                .applyLatestSpecForKey(initialSpecs = mapOf(Unit to initialSpec), numKeys = 1)
        val outEvents: Events<B> =
            events.mapMaybe {
                checkNotNull(it[Unit]) { "applyLatest: expected result, but none present in: $it" }
            }
        val outInit: DeferredValue<A> = deferredBuildScope {
            val initResult: Map<Unit, A> = result.value
            check(Unit in initResult) {
                "applyLatest: expected initial result, but none present in: $initResult"
            }
            @Suppress("UNCHECKED_CAST")
            initResult.getOrDefault(Unit) { null } as A
        }
        return Pair(outEvents, outInit)
    }

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events].
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    fun <A, B> Events<A>.mapLatestBuild(transform: BuildScope.(A) -> B): Events<B> =
        mapCheap { buildSpec { transform(it) } }.applyLatestSpec()

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events], and a [DeferredValue] containing the result of applying [transform] to
     * [initialValue] immediately.
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    fun <A, B> Events<A>.mapLatestBuild(
        initialValue: A,
        transform: BuildScope.(A) -> B,
    ): Pair<Events<B>, DeferredValue<B>> =
        mapLatestBuildDeferred(deferredOf(initialValue), transform)

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events], and a [DeferredValue] containing the result of applying [transform] to
     * [initialValue] immediately.
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    fun <A, B> Events<A>.mapLatestBuildDeferred(
        initialValue: DeferredValue<A>,
        transform: BuildScope.(A) -> B,
    ): Pair<Events<B>, DeferredValue<B>> =
        mapCheap { buildSpec { transform(it) } }
            .applyLatestSpec(initialSpec = buildSpec { transform(initialValue.value) })

    /**
     * Returns an [Events] containing the results of applying each [BuildSpec] emitted from the
     * original [Events], and a [DeferredValue] containing the result of applying [initialSpecs]
     * immediately.
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] with the
     * same key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildSpec] will be undone with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<BuildSpec<A>>>>.applyLatestSpecForKey(
        initialSpecs: Map<K, BuildSpec<B>>,
        numKeys: Int? = null,
    ): Pair<Events<Map<K, Maybe<A>>>, DeferredValue<Map<K, B>>> =
        applyLatestSpecForKey(deferredOf(initialSpecs), numKeys)

    /**
     * Returns an [Incremental] containing the results of applying each [BuildSpec] emitted from the
     * original [Incremental].
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] with the
     * same key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildSpec] will be undone with no replacement.
     */
    fun <K, V> Incremental<K, BuildSpec<V>>.applyLatestSpecForKey(
        numKeys: Int? = null
    ): Incremental<K, V> {
        val (events, initial) = updates.applyLatestSpecForKey(sampleDeferred(), numKeys)
        return events.foldStateMapIncrementally(initial)
    }

    /**
     * Returns an [Events] containing the results of applying each [BuildSpec] emitted from the
     * original [Events].
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] with the
     * same key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildSpec] will be undone with no replacement.
     */
    fun <K, V> Events<Map<K, Maybe<BuildSpec<V>>>>.applyLatestSpecForKey(
        numKeys: Int? = null
    ): Events<Map<K, Maybe<V>>> =
        applyLatestSpecForKey<K, V, Nothing>(deferredOf(emptyMap()), numKeys).first

    /**
     * Returns a [State] containing the latest results of applying each [BuildSpec] emitted from the
     * original [Events].
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] with the
     * same key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildSpec] will be undone with no replacement.
     */
    fun <K, V> Events<Map<K, Maybe<BuildSpec<V>>>>.holdLatestSpecForKey(
        initialSpecs: DeferredValue<Map<K, BuildSpec<V>>>,
        numKeys: Int? = null,
    ): Incremental<K, V> {
        val (changes, initialValues) = applyLatestSpecForKey(initialSpecs, numKeys)
        return changes.foldStateMapIncrementally(initialValues)
    }

    /**
     * Returns a [State] containing the latest results of applying each [BuildSpec] emitted from the
     * original [Events].
     *
     * When each [BuildSpec] is applied, changes from the previously-active [BuildSpec] with the
     * same key are undone (any registered [observers][observe] are unregistered, and any pending
     * [side-effects][effect] are cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildSpec] will be undone with no replacement.
     */
    fun <K, V> Events<Map<K, Maybe<BuildSpec<V>>>>.holdLatestSpecForKey(
        initialSpecs: Map<K, BuildSpec<V>> = emptyMap(),
        numKeys: Int? = null,
    ): Incremental<K, V> = holdLatestSpecForKey(deferredOf(initialSpecs), numKeys)

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events], and a [DeferredValue] containing the result of applying [transform] to
     * [initialValues] immediately.
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildScope] will be undone with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<A>>>.mapLatestBuildForKey(
        initialValues: DeferredValue<Map<K, A>>,
        numKeys: Int? = null,
        transform: BuildScope.(K, A) -> B,
    ): Pair<Events<Map<K, Maybe<B>>>, DeferredValue<Map<K, B>>> =
        map { patch -> patch.mapValues { (k, v) -> v.map { buildSpec { transform(k, it) } } } }
            .applyLatestSpecForKey(
                deferredBuildScope {
                    initialValues.value.mapValues { (k, v) -> buildSpec { transform(k, v) } }
                },
                numKeys = numKeys,
            )

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events], and a [DeferredValue] containing the result of applying [transform] to
     * [initialValues] immediately.
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildScope] will be undone with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<A>>>.mapLatestBuildForKey(
        initialValues: Map<K, A>,
        numKeys: Int? = null,
        transform: BuildScope.(K, A) -> B,
    ): Pair<Events<Map<K, Maybe<B>>>, DeferredValue<Map<K, B>>> =
        mapLatestBuildForKey(deferredOf(initialValues), numKeys, transform)

    /**
     * Returns an [Events] containing the results of applying [transform] to each value of the
     * original [Events].
     *
     * [transform] can perform modifications to the Kairos network via its [BuildScope] receiver.
     * With each invocation of [transform], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     *
     * If the [Maybe] value for an associated key is [absent][Maybe.absent], then the
     * previously-active [BuildScope] will be undone with no replacement.
     */
    fun <K, A, B> Events<Map<K, Maybe<A>>>.mapLatestBuildForKey(
        numKeys: Int? = null,
        transform: BuildScope.(K, A) -> B,
    ): Events<Map<K, Maybe<B>>> = mapLatestBuildForKey(emptyMap(), numKeys, transform).first

    /** Returns a [Deferred] containing the next value to be emitted from this [Events]. */
    fun <R> Events<R>.nextDeferred(): Deferred<R> {
        lateinit var next: CompletableDeferred<R>
        val job = launchScope { nextOnly().observe { next.complete(it) } }
        next = CompletableDeferred(parent = job)
        return next
    }

    /** Returns a [State] that reflects the [StateFlow.value] of this [StateFlow]. */
    fun <A> StateFlow<A>.toState(): State<A> {
        val initial = value
        return events { dropWhile { it == initial }.collect { emit(it) } }.holdState(initial)
    }

    /** Returns an [Events] that emits whenever this [Flow] emits. */
    fun <A> Flow<A>.toEvents(): Events<A> = events { collect { emit(it) } }

    /**
     * Shorthand for:
     * ``` kotlin
     * flow.toEvents().holdState(initialValue)
     * ```
     */
    fun <A> Flow<A>.toState(initialValue: A): State<A> = toEvents().holdState(initialValue)

    /**
     * Shorthand for:
     * ``` kotlin
     * flow.scan(initialValue, operation).toEvents().holdState(initialValue)
     * ```
     */
    fun <A, B> Flow<A>.scanToState(initialValue: B, operation: (B, A) -> B): State<B> =
        scan(initialValue, operation).toEvents().holdState(initialValue)

    /**
     * Shorthand for:
     * ``` kotlin
     * flow.scan(initialValue) { a, f -> f(a) }.toEvents().holdState(initialValue)
     * ```
     */
    fun <A> Flow<(A) -> A>.scanToState(initialValue: A): State<A> =
        scanToState(initialValue) { a, f -> f(a) }

    /**
     * Invokes [block] whenever this [Events] emits a value. [block] receives an [BuildScope] that
     * can be used to make further modifications to the Kairos network, and/or perform side-effects
     * via [effect].
     *
     * With each invocation of [block], changes from the previous invocation are undone (any
     * registered [observers][observe] are unregistered, and any pending [side-effects][effect] are
     * cancelled).
     */
    fun <A> Events<A>.observeLatestBuild(block: BuildScope.(A) -> Unit = {}): DisposableHandle =
        mapLatestBuild { block(it) }.observe()

    /**
     * Invokes [block] whenever this [Events] emits a value, allowing side-effects to be safely
     * performed in reaction to the emission.
     *
     * With each invocation of [block], running effects from the previous invocation are cancelled.
     */
    fun <A> Events<A>.observeLatest(block: EffectScope.(A) -> Unit = {}): DisposableHandle {
        var innerJob: Job? = null
        return observeBuild {
            innerJob?.cancel()
            innerJob = effect { block(it) }
        }
    }

    /**
     * Invokes [block] with the value held by this [State], allowing side-effects to be safely
     * performed in reaction to the state changing.
     *
     * With each invocation of [block], running effects from the previous invocation are cancelled.
     */
    fun <A> State<A>.observeLatest(block: EffectScope.(A) -> Unit = {}): Job = launchScope {
        var innerJob = effect { block(sample()) }
        changes.observeBuild {
            innerJob.cancel()
            innerJob = effect { block(it) }
        }
    }

    /**
     * Applies [block] to the value held by this [State]. [block] receives an [BuildScope] that can
     * be used to make further modifications to the Kairos network, and/or perform side-effects via
     * [effect].
     *
     * [block] can perform modifications to the Kairos network via its [BuildScope] receiver. With
     * each invocation of [block], changes from the previous invocation are undone (any registered
     * [observers][observe] are unregistered, and any pending [side-effects][effect] are cancelled).
     */
    fun <A> State<A>.observeLatestBuild(block: BuildScope.(A) -> Unit = {}): Job = launchScope {
        var innerJob: Job = launchScope { block(sample()) }
        changes.observeBuild {
            innerJob.cancel()
            innerJob = launchScope { block(it) }
        }
    }

    /** Applies the [BuildSpec] within this [BuildScope]. */
    fun <A> BuildSpec<A>.applySpec(): A = this()

    /**
     * Applies the [BuildSpec] within this [BuildScope], returning the result as an [DeferredValue].
     */
    fun <A> BuildSpec<A>.applySpecDeferred(): DeferredValue<A> = deferredBuildScope { applySpec() }

    /**
     * Invokes [block] on the value held in this [State]. [block] receives an [BuildScope] that can
     * be used to make further modifications to the Kairos network, and/or perform side-effects via
     * [effect].
     *
     * ``` kotlin
     *     fun <A> State<A>.observeBuild(block: BuildScope.(A) -> Unit = {}): Job = launchScope {
     *         block(sample())
     *         changes.observeBuild(block)
     *     }
     * ```
     */
    fun <A> State<A>.observeBuild(block: BuildScope.(A) -> Unit = {}): Job = launchScope {
        block(sample())
        changes.observeBuild(block)
    }

    /**
     * Invokes [block] with the current value of this [State], re-invoking whenever it changes,
     * allowing side-effects to be safely performed in reaction value changing.
     *
     * Specifically, [block] is deferred to the end of the transaction, and is only actually
     * executed if this [BuildScope] is still active by that time. It can be deactivated due to a
     * -Latest combinator, for example.
     *
     * If the [State] is changing within the *current* transaction (i.e. [changes] is presently
     * emitting) then [block] will be invoked for the first time with the new value; otherwise, it
     * will be invoked with the [current][sample] value.
     */
    fun <A> State<A>.observe(block: EffectScope.(A) -> Unit = {}): DisposableHandle =
        now.map { sample() }.mergeWith(changes) { _, new -> new }.observe { block(it) }
}

/**
 * Returns an [Events] that emits the result of [block] once it completes. [block] is evaluated
 * outside of the current Kairos transaction; when it completes, the returned [Events] emits in a
 * new transaction.
 *
 * ``` kotlin
 *   fun <A> BuildScope.asyncEvent(block: suspend () -> A): Events<A> =
 *       events { emit(block()) }.apply { observe() }
 * ```
 */
@ExperimentalKairosApi
fun <A> BuildScope.asyncEvent(block: suspend () -> A): Events<A> =
    events {
            // TODO: if block completes synchronously, it would be nice to emit within this
            //  transaction
            emit(block())
        }
        .apply { observe() }

/**
 * Performs a side-effect in a safe manner w/r/t the current Kairos transaction.
 *
 * Specifically, [block] is deferred to the end of the current transaction, and is only actually
 * executed if this [BuildScope] is still active by that time. It can be deactivated due to a
 * -Latest combinator, for example.
 *
 * ``` kotlin
 *   fun BuildScope.effect(
 *       context: CoroutineContext = EmptyCoroutineContext,
 *       block: EffectScope.() -> Unit,
 *   ): Job =
 *       launchScope { now.observe(context) { block() } }
 * ```
 */
@ExperimentalKairosApi
fun BuildScope.effect(
    context: CoroutineContext = EmptyCoroutineContext,
    block: EffectScope.() -> Unit,
): Job = launchScope { now.observe(context) { block() } }

/**
 * Launches [block] in a new coroutine, returning a [Job] bound to the coroutine.
 *
 * This coroutine is not actually started until the *end* of the current Kairos transaction. This is
 * done because the current [BuildScope] might be deactivated within this transaction, perhaps due
 * to a -Latest combinator. If this happens, then the coroutine will never actually be started.
 *
 * ``` kotlin
 *   fun BuildScope.launchEffect(block: suspend KairosScope.() -> Unit): Job =
 *       effect { effectCoroutineScope.launch { block() } }
 * ```
 */
@ExperimentalKairosApi
fun BuildScope.launchEffect(block: suspend KairosCoroutineScope.() -> Unit): Job =
    asyncEffect(block)

/**
 * Launches [block] in a new coroutine, returning the result as a [Deferred].
 *
 * This coroutine is not actually started until the *end* of the current Kairos transaction. This is
 * done because the current [BuildScope] might be deactivated within this transaction, perhaps due
 * to a -Latest combinator. If this happens, then the coroutine will never actually be started.
 *
 * Shorthand for:
 * ``` kotlin
 *   fun <R> BuildScope.asyncEffect(block: suspend KairosScope.() -> R): Deferred<R> =
 *       CompletableDeferred<R>.apply {
 *               effect { effectCoroutineScope.launch { complete(block()) } }
 *           }
 *           .await()
 * ```
 */
@ExperimentalKairosApi
fun <R> BuildScope.asyncEffect(block: suspend KairosCoroutineScope.() -> R): Deferred<R> {
    val result = CompletableDeferred<R>()
    val job = effect { launch { result.complete(block()) } }
    val handle = job.invokeOnCompletion { result.cancel() }
    result.invokeOnCompletion {
        handle.dispose()
        job.cancel()
    }
    return result
}

/** Like [BuildScope.asyncScope], but ignores the result of [block]. */
@ExperimentalKairosApi
fun BuildScope.launchScope(block: BuildSpec<*>): Job = asyncScope(block).second

/**
 * Creates an instance of an [Events] with elements that are emitted from [builder].
 *
 * [builder] is run in its own coroutine, allowing for ongoing work that can emit to the provided
 * [MutableState].
 *
 * By default, [builder] is only running while the returned [Events] is being
 * [observed][BuildScope.observe]. If you want it to run at all times, simply add a no-op observer:
 * ``` kotlin
 * events { ... }.apply { observe() }
 * ```
 *
 * In the event of backpressure, emissions are *coalesced* into batches. When a value is
 * [emitted][CoalescingEventProducerScope.emit] from [builder], it is merged into the batch via
 * [coalesce]. Once the batch is consumed by the Kairos network in the next transaction, the batch
 * is reset back to [initialValue].
 */
@ExperimentalKairosApi
fun <In, Out> BuildScope.coalescingEvents(
    initialValue: Out,
    coalesce: (old: Out, new: In) -> Out,
    builder: suspend CoalescingEventProducerScope<In>.() -> Unit,
): Events<Out> = coalescingEvents(getInitialValue = { initialValue }, coalesce, builder)

/**
 * Creates an instance of an [Events] with elements that are emitted from [builder].
 *
 * [builder] is run in its own coroutine, allowing for ongoing work that can emit to the provided
 * [MutableState].
 *
 * By default, [builder] is only running while the returned [Events] is being
 * [observed][BuildScope.observe]. If you want it to run at all times, simply add a no-op observer:
 * ``` kotlin
 * events { ... }.apply { observe() }
 * ```
 *
 * In the event of backpressure, emissions are *conflated*; any older emissions are dropped and only
 * the most recent emission will be used when the Kairos network is ready.
 */
@ExperimentalKairosApi
fun <T> BuildScope.conflatedEvents(
    builder: suspend CoalescingEventProducerScope<T>.() -> Unit
): Events<T> =
    coalescingEvents<T, Any?>(initialValue = Any(), coalesce = { _, new -> new }, builder = builder)
        .mapCheap {
            @Suppress("UNCHECKED_CAST")
            it as T
        }

/** Scope for emitting to a [BuildScope.coalescingEvents]. */
fun interface CoalescingEventProducerScope<in T> {
    /**
     * Inserts [value] into the current batch, enqueueing it for emission from this [Events] if not
     * already pending.
     *
     * Backpressure occurs when [emit] is called while the Kairos network is currently in a
     * transaction; if called multiple times, then emissions will be coalesced into a single batch
     * that is then processed when the network is ready.
     */
    fun emit(value: T)
}

/** Scope for emitting to a [BuildScope.events]. */
fun interface EventProducerScope<in T> {
    /**
     * Emits a [value] to this [Events], suspending the caller until the Kairos transaction
     * containing the emission has completed.
     */
    suspend fun emit(value: T)
}

/**
 * Suspends forever. Upon cancellation, runs [block]. Useful for unregistering callbacks inside of
 * [BuildScope.events] and [BuildScope.coalescingEvents].
 */
suspend fun awaitClose(block: () -> Unit): Nothing =
    try {
        awaitCancellation()
    } finally {
        block()
    }

/**
 * Runs [spec] in this [BuildScope], and then re-runs it whenever [rebuildSignal] emits. Returns a
 * [State] that holds the result of the currently-active [BuildSpec].
 */
@ExperimentalKairosApi
fun <A> BuildScope.rebuildOn(rebuildSignal: Events<*>, spec: BuildSpec<A>): State<A> =
    rebuildSignal.map { spec }.holdLatestSpec(spec)
