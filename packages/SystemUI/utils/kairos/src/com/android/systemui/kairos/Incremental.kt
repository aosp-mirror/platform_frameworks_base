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
import com.android.systemui.kairos.internal.constIncremental
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.mapValuesImpl
import com.android.systemui.kairos.internal.util.hashString
import com.android.systemui.kairos.util.MapPatch
import com.android.systemui.kairos.util.mapPatchFromFullDiff
import kotlin.reflect.KProperty

/**
 * A [State] tracking a [Map] that receives incremental updates.
 *
 * [Incremental] allows one to react to the [subset of changes][updates] to the held map, without
 * having to perform a manual diff of the map to determine what changed.
 *
 * @sample com.android.systemui.kairos.KairosSamples.incrementals
 */
sealed class Incremental<K, out V> : State<Map<K, V>>() {
    abstract override val init: Init<IncrementalImpl<K, V>>
}

/**
 * Returns a constant [Incremental] that never changes. [changes] and [updates] are both equivalent
 * to [emptyEvents], and [TransactionScope.sample] will always produce [value].
 */
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
 *
 * ```
 *   fun <A> Lazy<Incremental<K, V>>.defer() = deferredIncremental { value }
 * ```
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
 *
 * ```
 *   fun <A> DeferredValue<Incremental<K, V>>.defer() = deferredIncremental { get() }
 * ```
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
 * A forward-reference to an [Incremental]. Useful for recursive definitions.
 *
 * This reference can be used like a standard [Incremental], but will throw an error if its
 * [loopback] is unset before it is [observed][BuildScope.observe] or
 * [sampled][TransactionScope.sample]. Note that it is safe to invoke
 * [TransactionScope.sampleDeferred] before [loopback] is set, provided the [DeferredValue] is not
 * [queried][KairosScope.get].
 */
@ExperimentalKairosApi
class IncrementalLoop<K, V>(private val name: String? = null) : Incremental<K, V>() {

    private val deferred = CompletableLazy<Incremental<K, V>>(name = name)

    override val init: Init<IncrementalImpl<K, V>> =
        init(name) { deferred.value.init.connect(evalScope = this) }

    /**
     * The [Incremental] this reference is referring to. Must be set before this [IncrementalLoop]
     * is [observed][BuildScope.observe].
     */
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
 * Returns an [Incremental] whose [updates] are calculated by [diffing][mapPatchFromFullDiff] the
 * given [State]'s [transitions].
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

private inline fun <K, V> deferInline(
    crossinline block: InitScope.() -> Incremental<K, V>
): Incremental<K, V> = IncrementalInit(init(name = null) { block().init.connect(evalScope = this) })
