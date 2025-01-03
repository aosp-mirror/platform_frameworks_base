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
import com.android.systemui.kairos.internal.IncrementalImpl
import com.android.systemui.kairos.internal.Init
import com.android.systemui.kairos.internal.InitScope
import com.android.systemui.kairos.internal.NoScope
import com.android.systemui.kairos.internal.awaitValues
import com.android.systemui.kairos.internal.constIncremental
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.mapImpl
import com.android.systemui.kairos.internal.mapValuesImpl
import com.android.systemui.kairos.internal.store.ConcurrentHashMapK
import com.android.systemui.kairos.internal.switchDeferredImpl
import com.android.systemui.kairos.internal.switchPromptImpl
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.util.MapPatch
import com.android.systemui.kairos.util.map
import com.android.systemui.kairos.util.mapPatchFromFullDiff
import kotlin.reflect.KProperty

/** A [State] tracking a [Map] that receives incremental updates. */
sealed class Incremental<K, out V> : State<Map<K, V>>() {
    abstract override val init: Init<IncrementalImpl<K, V>>
}

/** An [Incremental] that never changes. */
@ExperimentalKairosApi
fun <K, V> incrementalOf(value: Map<K, V>): Incremental<K, V> {
    val operatorName = "stateOf"
    val name = "$operatorName($value)"
    return IncrementalInit(constInit(name, constIncremental(name, operatorName, value)))
}

/**
 * Returns an [Incremental] that acts as a deferred-reference to the [Incremental] produced by this
 * [Lazy].
 *
 * When the returned [Incremental] is accessed by the Kairos network, the [Lazy]'s
 * [value][Lazy.value] will be queried and used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi
fun <K, V> Lazy<Incremental<K, V>>.defer(): Incremental<K, V> = deferInline { value }

/**
 * Returns an [Incremental] that acts as a deferred-reference to the [Incremental] produced by this
 * [DeferredValue].
 *
 * When the returned [Incremental] is accessed by the Kairos network, the [DeferredValue] will be
 * queried and used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi
fun <K, V> DeferredValue<Incremental<K, V>>.defer(): Incremental<K, V> = deferInline {
    unwrapped.value
}

/**
 * Returns an [Incremental] that acts as a deferred-reference to the [Incremental] produced by
 * [block].
 *
 * When the returned [Incremental] is accessed by the Kairos network, [block] will be invoked and
 * the returned [Incremental] will be used.
 *
 * Useful for recursive definitions.
 */
@ExperimentalKairosApi
fun <K, V> deferredIncremental(block: KairosScope.() -> Incremental<K, V>): Incremental<K, V> =
    deferInline {
        NoScope.block()
    }

/**
 * An [Events] that emits every time this [Incremental] changes, containing the subset of the map
 * that has changed.
 *
 * @see MapPatch
 */
val <K, V> Incremental<K, V>.updates: Events<MapPatch<K, V>>
    get() = EventsInit(init("patches") { init.connect(this).patches })

internal class IncrementalInit<K, V>(override val init: Init<IncrementalImpl<K, V>>) :
    Incremental<K, V>()

/**
 * Returns an [Incremental] that tracks the entries of the original incremental, but values replaced
 * with those obtained by applying [transform] to each original entry.
 */
fun <K, V, U> Incremental<K, V>.mapValues(
    transform: KairosScope.(Map.Entry<K, V>) -> U
): Incremental<K, U> {
    val operatorName = "mapValues"
    val name = operatorName
    return IncrementalInit(
        init(name) {
            mapValuesImpl({ init.connect(this) }, name, operatorName) { NoScope.transform(it) }
        }
    )
}

/**
 * Returns an [Events] that emits from a merged, incrementally-accumulated collection of [Events]
 * emitted from this, following the same "patch" rules as outlined in
 * [StateScope.foldStateMapIncrementally].
 *
 * Conceptually this is equivalent to:
 * ```kotlin
 *   fun <K, V> State<Map<K, V>>.mergeEventsIncrementally(): Events<Map<K, V>> =
 *     map { it.merge() }.switchEvents()
 * ```
 *
 * While the behavior is equivalent to the conceptual definition above, the implementation is
 * significantly more efficient.
 *
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
 * emitted from this, following the same "patch" rules as outlined in
 * [StateScope.foldStateMapIncrementally].
 *
 * Conceptually this is equivalent to:
 * ```kotlin
 *   fun <K, V> State<Map<K, V>>.mergeEventsIncrementallyPromptly(): Events<Map<K, V>> =
 *     map { it.merge() }.switchEventsPromptly()
 * ```
 *
 * While the behavior is equivalent to the conceptual definition above, the implementation is
 * significantly more efficient.
 *
 * @see merge
 */
fun <K, V> Incremental<K, Events<V>>.mergeEventsIncrementallyPromptly(): Events<Map<K, V>> {
    val operatorName = "mergeEventsIncrementally"
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

/** A forward-reference to an [Incremental], allowing for recursive definitions. */
@ExperimentalKairosApi
class IncrementalLoop<K, V>(private val name: String? = null) : Incremental<K, V>() {

    private val deferred = CompletableLazy<Incremental<K, V>>(name = name)

    override val init: Init<IncrementalImpl<K, V>> =
        init(name) { deferred.value.init.connect(evalScope = this) }

    /** The [Incremental] this [IncrementalLoop] will forward to. */
    var loopback: Incremental<K, V>? = null
        set(value) {
            value?.let {
                check(!deferred.isInitialized()) {
                    "IncrementalLoop($name).loopback has already been set."
                }
                deferred.setValue(value)
                field = value
            }
        }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): Incremental<K, V> = this

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Incremental<K, V>) {
        loopback = value
    }

    override fun toString(): String = "${this::class.simpleName}($name)@$hashString"
}

/**
 * Returns an [Incremental] whose [updates] are calculated by diffing the given [State]'s
 * [transitions].
 */
fun <K, V> State<Map<K, V>>.asIncremental(): Incremental<K, V> {
    if (this is Incremental<K, V>) return this

    val hashState = map { if (it is HashMap) it else HashMap(it) }

    val patches =
        transitions.mapNotNull { (old, new) ->
            mapPatchFromFullDiff(old, new).takeIf { it.isNotEmpty() }
        }

    return IncrementalInit(
        init("asIncremental") {
            val upstream = hashState.init.connect(this)
            IncrementalImpl(
                upstream.name,
                upstream.operatorName,
                upstream.changes,
                patches.init.connect(this),
                upstream.store,
            )
        }
    )
}

/** Returns an [Incremental] that acts like the current value of the given [State]. */
fun <K, V> State<Incremental<K, V>>.switchIncremental(): Incremental<K, V> {
    val stateChangePatches =
        transitions.mapNotNull { (old, new) ->
            mapPatchFromFullDiff(old.sample(), new.sample()).takeIf { it.isNotEmpty() }
        }
    val innerChanges =
        map { inner ->
                merge(stateChangePatches, inner.updates) { switchPatch, upcomingPatch ->
                    switchPatch + upcomingPatch
                }
            }
            .switchEventsPromptly()
    val flattened = flatten()
    return IncrementalInit(
        init("switchIncremental") {
            val upstream = flattened.init.connect(this)
            IncrementalImpl(
                "switchIncremental",
                "switchIncremental",
                upstream.changes,
                innerChanges.init.connect(this),
                upstream.store,
            )
        }
    )
}

private inline fun <K, V> deferInline(
    crossinline block: InitScope.() -> Incremental<K, V>
): Incremental<K, V> = IncrementalInit(init(name = null) { block().init.connect(evalScope = this) })
