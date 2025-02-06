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

import com.android.systemui.kairos.internal.IncrementalImpl
import com.android.systemui.kairos.internal.constInit
import com.android.systemui.kairos.internal.init
import com.android.systemui.kairos.internal.mapImpl
import com.android.systemui.kairos.internal.switchDeferredImplSingle
import com.android.systemui.kairos.internal.switchPromptImplSingle
import com.android.systemui.kairos.util.mapPatchFromFullDiff

/**
 * Returns an [Events] that switches to the [Events] contained within this [State] whenever it
 * changes.
 *
 * This switch does take effect until the *next* transaction after [State] changes. For a switch
 * that takes effect immediately, see [switchEventsPromptly].
 *
 * @sample com.android.systemui.kairos.KairosSamples.switchEvents
 */
@ExperimentalKairosApi
fun <A> State<Events<A>>.switchEvents(): Events<A> {
    val patches =
        mapImpl({ init.connect(this).changes }) { newEvents, _ -> newEvents.init.connect(this) }
    return EventsInit(
        constInit(
            name = null,
            switchDeferredImplSingle(
                getStorage = {
                    init.connect(this).getCurrentWithEpoch(this).first.init.connect(this)
                },
                getPatches = { patches },
            ),
        )
    )
}

/**
 * Returns an [Events] that switches to the [Events] contained within this [State] whenever it
 * changes.
 *
 * This switch takes effect immediately within the same transaction that [State] changes. If the
 * newly-switched-in [Events] is emitting a value within this transaction, then that value will be
 * emitted from this switch. If not, but the previously-switched-in [Events] *is* emitting, then
 * that value will be emitted from this switch instead. Otherwise, there will be no emission.
 *
 * In general, you should prefer [switchEvents] over this method. It is both safer and more
 * performant.
 *
 * @sample com.android.systemui.kairos.KairosSamples.switchEventsPromptly
 */
// TODO: parameter to handle coincidental emission from both old and new
@ExperimentalKairosApi
fun <A> State<Events<A>>.switchEventsPromptly(): Events<A> {
    val patches =
        mapImpl({ init.connect(this).changes }) { newEvents, _ -> newEvents.init.connect(this) }
    return EventsInit(
        constInit(
            name = null,
            switchPromptImplSingle(
                getStorage = {
                    init.connect(this).getCurrentWithEpoch(this).first.init.connect(this)
                },
                getPatches = { patches },
            ),
        )
    )
}

/** Returns an [Incremental] that behaves like current value of this [State]. */
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
