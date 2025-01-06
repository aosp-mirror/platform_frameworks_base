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

import com.android.systemui.kairos.internal.awaitValues
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.mapImpl
import com.android.systemui.kairos.internal.mergeNodes
import com.android.systemui.kairos.internal.mergeNodesLeft
import com.android.systemui.kairos.internal.store.ConcurrentHashMapK
import com.android.systemui.kairos.internal.switchDeferredImpl
import com.android.systemui.kairos.internal.switchPromptImpl
import com.android.systemui.kairos.util.map

/**
 * Merges the given [Events] into a single [Events] that emits events from both.
 *
 * Because [Events] can only emit one value per transaction, the provided [transformCoincidence]
 * function is used to combine coincident emissions to produce the result value to be emitted by the
 * merged [Events].
 *
 * ``` kotlin
 * fun <A> Events<A>.mergeWith(
 *     other: Events<A>,
 *     transformCoincidence: TransactionScope.(A, A) -> A = { a, _ -> a },
 * ): Events<A> =
 *     listOf(this, other).merge().map { it.reduce(transformCoincidence) }
 * ```
 *
 * @see merge
 */
@ExperimentalKairosApi
fun <A> Events<A>.mergeWith(
    other: Events<A>,
    transformCoincidence: TransactionScope.(A, A) -> A = { a, _ -> a },
): Events<A> {
    val node =
        mergeNodes(
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
 * ``` kotlin
 *   fun <A> merge(vararg events: Events<A>): Events<List<A>> = events.asIterable().merge()
 * ```
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
 * ``` kotlin
 *   fun <A> mergeLeft(vararg events: Events<A>): Events<A> = events.asIterable().mergeLeft()
 * ```
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
 *
 * ``` kotlin
 *   fun <A> merge(vararg events: Events<A>, transformCoincidence: (A, A) -> A): Events<A> =
 *       merge(*events).map { l -> l.reduce(transformCoincidence) }
 * ```
 */
fun <A> merge(vararg events: Events<A>, transformCoincidence: (A, A) -> A): Events<A> =
    merge(*events).map { l -> l.reduce(transformCoincidence) }

/**
 * Merges the given [Events] into a single [Events] that emits events from all. All coincident
 * emissions are collected into the emitted [List], preserving the input ordering.
 *
 * @sample com.android.systemui.kairos.KairosSamples.merge
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
 * Semantically equivalent to the following definition:
 * ``` kotlin
 *   fun <A> Iterable<Events<A>>.mergeLeft(): Events<A> =
 *       merge().mapCheap { it.first() }
 * ```
 *
 * In reality, the implementation avoids allocating the intermediate list of all coincident
 * emissions.
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
 * ``` kotlin
 *   fun <A> Sequence<Events<A>>.merge(): Events<List<A>> = asIterable().merge()
 * ```
 *
 * @see mergeWith
 */
@ExperimentalKairosApi fun <A> Sequence<Events<A>>.merge(): Events<List<A>> = asIterable().merge()

/**
 * Creates a new [Events] that emits events from all given [Events]. All simultaneous emissions are
 * collected into the emitted [Map], and are given the same key of the associated [Events] in the
 * input [Map].
 *
 * ``` kotlin
 *   fun <K, A> Map<K, Events<A>>.merge(): Events<Map<K, A>> =
 *       asSequence()
 *           .map { (k, events) -> events.map { a -> k to a } }
 *           .toList()
 *           .merge()
 *           .map { it.toMap() }
 * ```
 *
 * @see merge
 */
@ExperimentalKairosApi
fun <K, A> Map<K, Events<A>>.merge(): Events<Map<K, A>> =
    asSequence()
        .map { (k, events) -> events.map { a -> k to a } }
        .toList()
        .merge()
        .map { it.toMap() }

/**
 * Returns an [Events] that emits from a merged, incrementally-accumulated collection of [Events]
 * emitted from this, following the patch rules outlined in
 * [Map.applyPatch][com.android.systemui.kairos.util.applyPatch].
 *
 * Conceptually this is equivalent to:
 * ``` kotlin
 *   fun <K, V> State<Map<K, V>>.mergeEventsIncrementally(): Events<Map<K, V>> =
 *       map { it.merge() }.switchEvents()
 * ```
 *
 * While the behavior is equivalent to the conceptual definition above, the implementation is
 * significantly more efficient.
 *
 * @sample com.android.systemui.kairos.KairosSamples.mergeEventsIncrementally
 * @see merge
 */
fun <K, V> Incremental<K, Events<V>>.mergeEventsIncrementally(): Events<Map<K, V>> {
    val operatorName = "mergeEventsIncrementally"
    val name = operatorName
    val patches =
        mapImpl({ init.connect(this).patches }) { patch, _ ->
            patch.mapValues { (_, m) -> m.map { events -> events.init.connect(this) } }.asIterable()
        }
    return EventsInit(
        constInit(
            name,
            switchDeferredImpl(
                    name = name,
                    getStorage = {
                        init
                            .connect(this)
                            .getCurrentWithEpoch(this)
                            .first
                            .mapValues { (_, events) -> events.init.connect(this) }
                            .asIterable()
                    },
                    getPatches = { patches },
                    storeFactory = ConcurrentHashMapK.Factory(),
                )
                .awaitValues(),
        )
    )
}

/**
 * Returns an [Events] that emits from a merged, incrementally-accumulated collection of [Events]
 * emitted from this, following the patch rules outlined in
 * [Map.applyPatch][com.android.systemui.kairos.util.applyPatch].
 *
 * Conceptually this is equivalent to:
 * ``` kotlin
 *   fun <K, V> State<Map<K, V>>.mergeEventsIncrementallyPromptly(): Events<Map<K, V>> =
 *       map { it.merge() }.switchEventsPromptly()
 * ```
 *
 * While the behavior is equivalent to the conceptual definition above, the implementation is
 * significantly more efficient.
 *
 * @sample com.android.systemui.kairos.KairosSamples.mergeEventsIncrementallyPromptly
 * @see merge
 */
fun <K, V> Incremental<K, Events<V>>.mergeEventsIncrementallyPromptly(): Events<Map<K, V>> {
    val operatorName = "mergeEventsIncrementallyPromptly"
    val name = operatorName
    val patches =
        mapImpl({ init.connect(this).patches }) { patch, _ ->
            patch.mapValues { (_, m) -> m.map { events -> events.init.connect(this) } }.asIterable()
        }
    return EventsInit(
        constInit(
            name,
            switchPromptImpl(
                    name = name,
                    getStorage = {
                        init
                            .connect(this)
                            .getCurrentWithEpoch(this)
                            .first
                            .mapValues { (_, events) -> events.init.connect(this) }
                            .asIterable()
                    },
                    getPatches = { patches },
                    storeFactory = ConcurrentHashMapK.Factory(),
                )
                .awaitValues(),
        )
    )
}
