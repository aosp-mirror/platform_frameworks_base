/*
 * Copyright (C) 2025 The Android Open Source Project
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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate

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
