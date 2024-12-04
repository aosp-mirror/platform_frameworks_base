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

import com.android.systemui.kairos.util.These
import com.android.systemui.kairos.util.WithPrev
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.none
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate

/**
 * Returns an [Events] that emits the value sampled from the [Transactional] produced by each
 * emission of the original [Events], within the same transaction of the original emission.
 */
@ExperimentalKairosApi
fun <A> Events<Transactional<A>>.sampleTransactionals(): Events<A> = map { it.sample() }

/** @see TransactionScope.sample */
@ExperimentalKairosApi
fun <A, B, C> Events<A>.sample(
    state: State<B>,
    transform: TransactionScope.(A, B) -> C,
): Events<C> = map { transform(it, state.sample()) }

/** @see TransactionScope.sample */
@ExperimentalKairosApi
fun <A, B, C> Events<A>.sample(
    sampleable: Transactional<B>,
    transform: TransactionScope.(A, B) -> C,
): Events<C> = map { transform(it, sampleable.sample()) }

/**
 * Like [sample], but if [state] is changing at the time it is sampled ([changes] is emitting), then
 * the new value is passed to [transform].
 *
 * Note that [sample] is both more performant, and safer to use with recursive definitions. You will
 * generally want to use it rather than this.
 *
 * @see sample
 */
@ExperimentalKairosApi
fun <A, B, C> Events<A>.samplePromptly(
    state: State<B>,
    transform: TransactionScope.(A, B) -> C,
): Events<C> =
    sample(state) { a, b -> These.thiz(a to b) }
        .mergeWith(state.changes.map { These.that(it) }) { thiz, that ->
            These.both((thiz as These.This).thiz, (that as These.That).that)
        }
        .mapMaybe { these ->
            when (these) {
                // both present, transform the upstream value and the new value
                is These.Both -> just(transform(these.thiz.first, these.that))
                // no upstream present, so don't perform the sample
                is These.That -> none()
                // just the upstream, so transform the upstream and the old value
                is These.This -> just(transform(these.thiz.first, these.thiz.second))
            }
        }

/**
 * Returns a cold [Flow] that, when collected, emits from this [Events]. [network] is needed to
 * transactionally connect to / disconnect from the [Events] when collection starts/stops.
 */
@ExperimentalKairosApi
fun <A> Events<A>.toColdConflatedFlow(network: KairosNetwork): Flow<A> =
    channelFlow { network.activateSpec { observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, emits from this [State]. [network] is needed to
 * transactionally connect to / disconnect from the [State] when collection starts/stops.
 */
@ExperimentalKairosApi
fun <A> State<A>.toColdConflatedFlow(network: KairosNetwork): Flow<A> =
    channelFlow { network.activateSpec { observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [BuildSpec] in a new transaction in this
 * [network], and then emits from the returned [Events].
 *
 * When collection is cancelled, so is the [BuildSpec]. This means all ongoing work is cleaned up.
 */
@ExperimentalKairosApi
@JvmName("eventsSpecToColdConflatedFlow")
fun <A> BuildSpec<Events<A>>.toColdConflatedFlow(network: KairosNetwork): Flow<A> =
    channelFlow { network.activateSpec { applySpec().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [BuildSpec] in a new transaction in this
 * [network], and then emits from the returned [State].
 *
 * When collection is cancelled, so is the [BuildSpec]. This means all ongoing work is cleaned up.
 */
@ExperimentalKairosApi
@JvmName("stateSpecToColdConflatedFlow")
fun <A> BuildSpec<State<A>>.toColdConflatedFlow(network: KairosNetwork): Flow<A> =
    channelFlow { network.activateSpec { applySpec().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [Transactional] in a new transaction in
 * this [network], and then emits from the returned [Events].
 */
@ExperimentalKairosApi
@JvmName("transactionalFlowToColdConflatedFlow")
fun <A> Transactional<Events<A>>.toColdConflatedFlow(network: KairosNetwork): Flow<A> =
    channelFlow { network.activateSpec { sample().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [Transactional] in a new transaction in
 * this [network], and then emits from the returned [State].
 */
@ExperimentalKairosApi
@JvmName("transactionalStateToColdConflatedFlow")
fun <A> Transactional<State<A>>.toColdConflatedFlow(network: KairosNetwork): Flow<A> =
    channelFlow { network.activateSpec { sample().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [Stateful] in a new transaction in this
 * [network], and then emits from the returned [Events].
 *
 * When collection is cancelled, so is the [Stateful]. This means all ongoing work is cleaned up.
 */
@ExperimentalKairosApi
@JvmName("statefulFlowToColdConflatedFlow")
fun <A> Stateful<Events<A>>.toColdConflatedFlow(network: KairosNetwork): Flow<A> =
    channelFlow { network.activateSpec { applyStateful().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [Transactional] in a new transaction in
 * this [network], and then emits from the returned [State].
 *
 * When collection is cancelled, so is the [Stateful]. This means all ongoing work is cleaned up.
 */
@ExperimentalKairosApi
@JvmName("statefulStateToColdConflatedFlow")
fun <A> Stateful<State<A>>.toColdConflatedFlow(network: KairosNetwork): Flow<A> =
    channelFlow { network.activateSpec { applyStateful().observe { trySend(it) } } }.conflate()

/** Return an [Events] that emits from the original [Events] only when [state] is `true`. */
@ExperimentalKairosApi
fun <A> Events<A>.filter(state: State<Boolean>): Events<A> = filter { state.sample() }

private fun Iterable<Boolean>.allTrue() = all { it }

private fun Iterable<Boolean>.anyTrue() = any { it }

/** Returns a [State] that is `true` only when all of [states] are `true`. */
@ExperimentalKairosApi
fun allOf(vararg states: State<Boolean>): State<Boolean> = combine(*states) { it.allTrue() }

/** Returns a [State] that is `true` when any of [states] are `true`. */
@ExperimentalKairosApi
fun anyOf(vararg states: State<Boolean>): State<Boolean> = combine(*states) { it.anyTrue() }

/** Returns a [State] containing the inverse of the Boolean held by the original [State]. */
@ExperimentalKairosApi fun not(state: State<Boolean>): State<Boolean> = state.mapCheapUnsafe { !it }

/**
 * Represents a modal Kairos sub-network.
 *
 * When [enabled][enableMode], all network modifications are applied immediately to the Kairos
 * network. When the returned [Events] emits a [BuildMode], that mode is enabled and replaces this
 * mode, undoing all modifications in the process (any registered [observers][BuildScope.observe]
 * are unregistered, and any pending [side-effects][BuildScope.effect] are cancelled).
 *
 * Use [compiledBuildSpec] to compile and stand-up a mode graph.
 *
 * @see StatefulMode
 */
@ExperimentalKairosApi
fun interface BuildMode<out A> {
    /**
     * Invoked when this mode is enabled. Returns a value and an [Events] that signals a switch to a
     * new mode.
     */
    fun BuildScope.enableMode(): Pair<A, Events<BuildMode<A>>>
}

/**
 * Returns an [BuildSpec] that, when [applied][BuildScope.applySpec], stands up a modal-transition
 * graph starting with this [BuildMode], automatically switching to new modes as they are produced.
 *
 * @see BuildMode
 */
@ExperimentalKairosApi
val <A> BuildMode<A>.compiledBuildSpec: BuildSpec<State<A>>
    get() = buildSpec {
        var modeChangeEvents by EventsLoop<BuildMode<A>>()
        val activeMode: State<Pair<A, Events<BuildMode<A>>>> =
            modeChangeEvents
                .map { it.run { buildSpec { enableMode() } } }
                .holdLatestSpec(buildSpec { enableMode() })
        modeChangeEvents =
            activeMode
                .map { statefully { it.second.nextOnly() } }
                .applyLatestStateful()
                .switchEvents()
        activeMode.map { it.first }
    }

/**
 * Represents a modal Kairos sub-network.
 *
 * When [enabled][enableMode], all state accumulation is immediately started. When the returned
 * [Events] emits a [BuildMode], that mode is enabled and replaces this mode, stopping all state
 * accumulation in the process.
 *
 * Use [compiledStateful] to compile and stand-up a mode graph.
 *
 * @see BuildMode
 */
@ExperimentalKairosApi
fun interface StatefulMode<out A> {
    /**
     * Invoked when this mode is enabled. Returns a value and an [Events] that signals a switch to a
     * new mode.
     */
    fun StateScope.enableMode(): Pair<A, Events<StatefulMode<A>>>
}

/**
 * Returns an [Stateful] that, when [applied][StateScope.applyStateful], stands up a
 * modal-transition graph starting with this [StatefulMode], automatically switching to new modes as
 * they are produced.
 *
 * @see BuildMode
 */
@ExperimentalKairosApi
val <A> StatefulMode<A>.compiledStateful: Stateful<State<A>>
    get() = statefully {
        var modeChangeEvents by EventsLoop<StatefulMode<A>>()
        val activeMode: State<Pair<A, Events<StatefulMode<A>>>> =
            modeChangeEvents
                .map { it.run { statefully { enableMode() } } }
                .holdLatestStateful(statefully { enableMode() })
        modeChangeEvents =
            activeMode
                .map { statefully { it.second.nextOnly() } }
                .applyLatestStateful()
                .switchEvents()
        activeMode.map { it.first }
    }

/**
 * Runs [spec] in this [BuildScope], and then re-runs it whenever [rebuildSignal] emits. Returns a
 * [State] that holds the result of the currently-active [BuildSpec].
 */
@ExperimentalKairosApi
fun <A> BuildScope.rebuildOn(rebuildSignal: Events<*>, spec: BuildSpec<A>): State<A> =
    rebuildSignal.map { spec }.holdLatestSpec(spec)

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
