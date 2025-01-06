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

package com.android.systemui.kairos.internal

import com.android.systemui.kairos.internal.store.StoreEntry
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.applyPatch
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.map
import com.android.systemui.kairos.util.none

internal class IncrementalImpl<K, out V>(
    name: String?,
    operatorName: String,
    changes: EventsImpl<Map<K, V>>,
    val patches: EventsImpl<Map<K, Maybe<V>>>,
    store: StateStore<Map<K, V>>,
) : StateImpl<Map<K, V>>(name, operatorName, changes, store)

internal fun <K, V> constIncremental(
    name: String?,
    operatorName: String,
    init: Map<K, V>,
): IncrementalImpl<K, V> =
    IncrementalImpl(name, operatorName, neverImpl, neverImpl, StateSource(init))

internal inline fun <K, V> activatedIncremental(
    name: String?,
    operatorName: String,
    evalScope: EvalScope,
    crossinline getPatches: EvalScope.() -> EventsImpl<Map<K, Maybe<V>>>,
    init: Lazy<Map<K, V>>,
): IncrementalImpl<K, V> {
    val store = StateSource(init)
    val maybeChanges =
        mapImpl(getPatches) { patch, _ ->
                val (old, _) = store.getCurrentWithEpoch(evalScope = this)
                val new = old.applyPatch(patch)
                if (new != old) just(patch to new) else none
            }
            .cached()
    val calm = filterJustImpl { maybeChanges }
    val changes = mapImpl({ calm }) { (_, change), _ -> change }
    val patches = mapImpl({ calm }) { (patch, _), _ -> patch }
    evalScope.scheduleOutput(
        OneShot {
            changes.activate(evalScope = this, downstream = Schedulable.S(store))?.let {
                (connection, needsEval) ->
                store.upstreamConnection = connection
                if (needsEval) {
                    schedule(store)
                }
            }
        }
    )
    return IncrementalImpl(name, operatorName, changes, patches, store)
}

internal inline fun <K, V> EventsImpl<Map<K, Maybe<V>>>.calmUpdates(
    state: StateDerived<Map<K, V>>
): Pair<EventsImpl<Map<K, Maybe<V>>>, EventsImpl<Map<K, V>>> {
    val maybeUpdate =
        mapImpl({ this@calmUpdates }) { patch, _ ->
                val (current, _) = state.getCurrentWithEpoch(evalScope = this)
                val new = current.applyPatch(patch)
                if (new != current) {
                    state.setCacheFromPush(new, epoch)
                    just(patch to new)
                } else {
                    none
                }
            }
            .cached()
    val calm = filterJustImpl { maybeUpdate }
    val patches = mapImpl({ calm }) { (p, _), _ -> p }
    val changes = mapImpl({ calm }) { (_, s), _ -> s }
    return patches to changes
}

internal fun <K, A, B> mapValuesImpl(
    incrementalImpl: InitScope.() -> IncrementalImpl<K, A>,
    name: String?,
    operatorName: String,
    transform: EvalScope.(Map.Entry<K, A>) -> B,
): IncrementalImpl<K, B> {
    val store = DerivedMap(incrementalImpl) { map -> map.mapValues { transform(it) } }
    val mappedPatches =
        mapImpl({ incrementalImpl().patches }) { patch, _ ->
                patch.mapValues { (k, mv) -> mv.map { v -> transform(StoreEntry(k, v)) } }
            }
            .cached()
    val (calmPatches, calmChanges) = mappedPatches.calmUpdates(store)
    return IncrementalImpl(name, operatorName, calmChanges, calmPatches, store)
}
