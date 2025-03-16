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
 * Returns a [TFlow] that emits the value sampled from the [Transactional] produced by each emission
 * of the original [TFlow], within the same transaction of the original emission.
 */
fun <A> TFlow<Transactional<A>>.sampleTransactionals(): TFlow<A> = map { it.sample() }

/** @see FrpTransactionScope.sample */
fun <A, B, C> TFlow<A>.sample(
    state: TState<B>,
    transform: suspend FrpTransactionScope.(A, B) -> C,
): TFlow<C> = map { transform(it, state.sample()) }

/** @see FrpTransactionScope.sample */
fun <A, B, C> TFlow<A>.sample(
    transactional: Transactional<B>,
    transform: suspend FrpTransactionScope.(A, B) -> C,
): TFlow<C> = map { transform(it, transactional.sample()) }

/**
 * Like [sample], but if [state] is changing at the time it is sampled ([stateChanges] is emitting),
 * then the new value is passed to [transform].
 *
 * Note that [sample] is both more performant, and safer to use with recursive definitions. You will
 * generally want to use it rather than this.
 *
 * @see sample
 */
fun <A, B, C> TFlow<A>.samplePromptly(
    state: TState<B>,
    transform: suspend FrpTransactionScope.(A, B) -> C,
): TFlow<C> =
    sample(state) { a, b -> These.thiz<Pair<A, B>, B>(a to b) }
        .mergeWith(state.stateChanges.map { These.that(it) }) { thiz, that ->
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
 * Returns a cold [Flow] that, when collected, emits from this [TFlow]. [network] is needed to
 * transactionally connect to / disconnect from the [TFlow] when collection starts/stops.
 */
fun <A> TFlow<A>.toColdConflatedFlow(network: FrpNetwork): Flow<A> =
    channelFlow { network.activateSpec { observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, emits from this [TState]. [network] is needed to
 * transactionally connect to / disconnect from the [TState] when collection starts/stops.
 */
fun <A> TState<A>.toColdConflatedFlow(network: FrpNetwork): Flow<A> =
    channelFlow { network.activateSpec { observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [FrpSpec] in a new transaction in this
 * [network], and then emits from the returned [TFlow].
 *
 * When collection is cancelled, so is the [FrpSpec]. This means all ongoing work is cleaned up.
 */
@JvmName("flowSpecToColdConflatedFlow")
fun <A> FrpSpec<TFlow<A>>.toColdConflatedFlow(network: FrpNetwork): Flow<A> =
    channelFlow { network.activateSpec { applySpec().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [FrpSpec] in a new transaction in this
 * [network], and then emits from the returned [TState].
 *
 * When collection is cancelled, so is the [FrpSpec]. This means all ongoing work is cleaned up.
 */
@JvmName("stateSpecToColdConflatedFlow")
fun <A> FrpSpec<TState<A>>.toColdConflatedFlow(network: FrpNetwork): Flow<A> =
    channelFlow { network.activateSpec { applySpec().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [Transactional] in a new transaction in
 * this [network], and then emits from the returned [TFlow].
 */
@JvmName("transactionalFlowToColdConflatedFlow")
fun <A> Transactional<TFlow<A>>.toColdConflatedFlow(network: FrpNetwork): Flow<A> =
    channelFlow { network.activateSpec { sample().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [Transactional] in a new transaction in
 * this [network], and then emits from the returned [TState].
 */
@JvmName("transactionalStateToColdConflatedFlow")
fun <A> Transactional<TState<A>>.toColdConflatedFlow(network: FrpNetwork): Flow<A> =
    channelFlow { network.activateSpec { sample().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [FrpStateful] in a new transaction in
 * this [network], and then emits from the returned [TFlow].
 *
 * When collection is cancelled, so is the [FrpStateful]. This means all ongoing work is cleaned up.
 */
@JvmName("statefulFlowToColdConflatedFlow")
fun <A> FrpStateful<TFlow<A>>.toColdConflatedFlow(network: FrpNetwork): Flow<A> =
    channelFlow { network.activateSpec { applyStateful().observe { trySend(it) } } }.conflate()

/**
 * Returns a cold [Flow] that, when collected, applies this [Transactional] in a new transaction in
 * this [network], and then emits from the returned [TState].
 *
 * When collection is cancelled, so is the [FrpStateful]. This means all ongoing work is cleaned up.
 */
@JvmName("statefulStateToColdConflatedFlow")
fun <A> FrpStateful<TState<A>>.toColdConflatedFlow(network: FrpNetwork): Flow<A> =
    channelFlow { network.activateSpec { applyStateful().observe { trySend(it) } } }.conflate()

/** Return a [TFlow] that emits from the original [TFlow] only when [state] is `true`. */
fun <A> TFlow<A>.filter(state: TState<Boolean>): TFlow<A> = filter { state.sample() }

private fun Iterable<Boolean>.allTrue() = all { it }

private fun Iterable<Boolean>.anyTrue() = any { it }

/** Returns a [TState] that is `true` only when all of [states] are `true`. */
fun allOf(vararg states: TState<Boolean>): TState<Boolean> = combine(*states) { it.allTrue() }

/** Returns a [TState] that is `true` when any of [states] are `true`. */
fun anyOf(vararg states: TState<Boolean>): TState<Boolean> = combine(*states) { it.anyTrue() }

/** Returns a [TState] containing the inverse of the Boolean held by the original [TState]. */
fun not(state: TState<Boolean>): TState<Boolean> = state.mapCheapUnsafe { !it }

/**
 * Represents a modal FRP sub-network.
 *
 * When [enabled][enableMode], all network modifications are applied immediately to the FRP network.
 * When the returned [TFlow] emits a [FrpBuildMode], that mode is enabled and replaces this mode,
 * undoing all modifications in the process (any registered [observers][FrpBuildScope.observe] are
 * unregistered, and any pending [side-effects][FrpBuildScope.effect] are cancelled).
 *
 * Use [compiledFrpSpec] to compile and stand-up a mode graph.
 *
 * @see FrpStatefulMode
 */
fun interface FrpBuildMode<out A> {
    /**
     * Invoked when this mode is enabled. Returns a value and a [TFlow] that signals a switch to a
     * new mode.
     */
    suspend fun FrpBuildScope.enableMode(): Pair<A, TFlow<FrpBuildMode<A>>>
}

/**
 * Returns an [FrpSpec] that, when [applied][FrpBuildScope.applySpec], stands up a modal-transition
 * graph starting with this [FrpBuildMode], automatically switching to new modes as they are
 * produced.
 *
 * @see FrpBuildMode
 */
val <A> FrpBuildMode<A>.compiledFrpSpec: FrpSpec<TState<A>>
    get() = frpSpec {
        var modeChangeEvents by TFlowLoop<FrpBuildMode<A>>()
        val activeMode: TState<Pair<A, TFlow<FrpBuildMode<A>>>> =
            modeChangeEvents
                .map { it.run { frpSpec { enableMode() } } }
                .holdLatestSpec(frpSpec { enableMode() })
        modeChangeEvents =
            activeMode.map { statefully { it.second.nextOnly() } }.applyLatestStateful().switch()
        activeMode.map { it.first }
    }

/**
 * Represents a modal FRP sub-network.
 *
 * When [enabled][enableMode], all state accumulation is immediately started. When the returned
 * [TFlow] emits a [FrpBuildMode], that mode is enabled and replaces this mode, stopping all state
 * accumulation in the process.
 *
 * Use [compiledStateful] to compile and stand-up a mode graph.
 *
 * @see FrpBuildMode
 */
fun interface FrpStatefulMode<out A> {
    /**
     * Invoked when this mode is enabled. Returns a value and a [TFlow] that signals a switch to a
     * new mode.
     */
    suspend fun FrpStateScope.enableMode(): Pair<A, TFlow<FrpStatefulMode<A>>>
}

/**
 * Returns an [FrpStateful] that, when [applied][FrpStateScope.applyStateful], stands up a
 * modal-transition graph starting with this [FrpStatefulMode], automatically switching to new modes
 * as they are produced.
 *
 * @see FrpBuildMode
 */
val <A> FrpStatefulMode<A>.compiledStateful: FrpStateful<TState<A>>
    get() = statefully {
        var modeChangeEvents by TFlowLoop<FrpStatefulMode<A>>()
        val activeMode: TState<Pair<A, TFlow<FrpStatefulMode<A>>>> =
            modeChangeEvents
                .map { it.run { statefully { enableMode() } } }
                .holdLatestStateful(statefully { enableMode() })
        modeChangeEvents =
            activeMode.map { statefully { it.second.nextOnly() } }.applyLatestStateful().switch()
        activeMode.map { it.first }
    }

/**
 * Runs [spec] in this [FrpBuildScope], and then re-runs it whenever [rebuildSignal] emits. Returns
 * a [TState] that holds the result of the currently-active [FrpSpec].
 */
fun <A> FrpBuildScope.rebuildOn(rebuildSignal: TFlow<*>, spec: FrpSpec<A>): TState<A> =
    rebuildSignal.map { spec }.holdLatestSpec(spec)

/**
 * Like [stateChanges] but also includes the old value of this [TState].
 *
 * Shorthand for:
 * ``` kotlin
 *     stateChanges.map { WithPrev(previousValue = sample(), newValue = it) }
 * ```
 */
val <A> TState<A>.transitions: TFlow<WithPrev<A, A>>
    get() = stateChanges.map { WithPrev(previousValue = sample(), newValue = it) }
