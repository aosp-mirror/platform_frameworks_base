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

import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.zipStates

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @sample com.android.systemui.kairos.KairosSamples.combineState
 */
@ExperimentalKairosApi
@JvmName(name = "stateCombine")
fun <A, B, C> State<A>.combine(other: State<B>, transform: KairosScope.(A, B) -> C): State<C> =
    combine(this, other, transform)

/**
 * Returns a [State] by combining the values held inside the given [States][State] into a [List].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <A> Iterable<State<A>>.combine(): State<List<A>> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            val states = map { it.init }
            zipStates(
                name,
                operatorName,
                states.size,
                states = init(null) { states.map { it.connect(this) } },
            )
        }
    )
}

/**
 * Returns a [State] by combining the values held inside the given [States][State] into a [Map].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <K, A> Map<K, State<A>>.combine(): State<Map<K, A>> =
    asIterable().map { (k, state) -> state.map { v -> k to v } }.combine().map { it.toMap() }

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <A, B> Iterable<State<A>>.combine(transform: KairosScope.(List<A>) -> B): State<B> =
    combine().map(transform)

/**
 * Returns a [State] by combining the values held inside the given [State]s into a [List].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <A> combine(vararg states: State<A>): State<List<A>> = states.asIterable().combine()

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <A, B> combine(vararg states: State<A>, transform: KairosScope.(List<A>) -> B): State<B> =
    states.asIterable().combine(transform)

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <A, B, Z> combine(
    stateA: State<A>,
    stateB: State<B>,
    transform: KairosScope.(A, B) -> Z,
): State<Z> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStates(name, operatorName, stateA.init, stateB.init) { a, b ->
                NoScope.transform(a, b)
            }
        }
    )
}

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <A, B, C, Z> combine(
    stateA: State<A>,
    stateB: State<B>,
    stateC: State<C>,
    transform: KairosScope.(A, B, C) -> Z,
): State<Z> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStates(name, operatorName, stateA.init, stateB.init, stateC.init) { a, b, c ->
                NoScope.transform(a, b, c)
            }
        }
    )
}

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <A, B, C, D, Z> combine(
    stateA: State<A>,
    stateB: State<B>,
    stateC: State<C>,
    stateD: State<D>,
    transform: KairosScope.(A, B, C, D) -> Z,
): State<Z> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStates(name, operatorName, stateA.init, stateB.init, stateC.init, stateD.init) {
                a,
                b,
                c,
                d ->
                NoScope.transform(a, b, c, d)
            }
        }
    )
}

/**
 * Returns a [State] whose value is generated with [transform] by combining the current values of
 * each given [State].
 *
 * @see State.combine
 */
@ExperimentalKairosApi
fun <A, B, C, D, E, Z> combine(
    stateA: State<A>,
    stateB: State<B>,
    stateC: State<C>,
    stateD: State<D>,
    stateE: State<E>,
    transform: KairosScope.(A, B, C, D, E) -> Z,
): State<Z> {
    val operatorName = "combine"
    val name = operatorName
    return StateInit(
        init(name) {
            zipStates(
                name,
                operatorName,
                stateA.init,
                stateB.init,
                stateC.init,
                stateD.init,
                stateE.init,
            ) { a, b, c, d, e ->
                NoScope.transform(a, b, c, d, e)
            }
        }
    )
}
