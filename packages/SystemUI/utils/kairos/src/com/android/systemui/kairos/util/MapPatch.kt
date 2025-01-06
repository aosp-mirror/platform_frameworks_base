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

package com.android.systemui.kairos.util

import com.android.systemui.kairos.util.Either.Left
import com.android.systemui.kairos.util.Either.Right
import com.android.systemui.kairos.util.Maybe.Just

/** A "patch" that can be used to batch-update a [Map], via [applyPatch]. */
typealias MapPatch<K, V> = Map<K, Maybe<V>>

/**
 * Returns a new [Map] that has [patch] applied to the original map.
 *
 * For each entry in [patch]:
 * * a [Just] value will be included in the new map, replacing the entry in the original map with
 *   the same key, if present.
 * * a [Maybe.None] value will be omitted from the new map, excluding the entry in the original map
 *   with the same key, if present.
 */
fun <K, V> Map<K, V>.applyPatch(patch: MapPatch<K, V>): Map<K, V> {
    val (adds: List<Pair<K, V>>, removes: List<K>) =
        patch
            .asSequence()
            .map { (k, v) -> if (v is Just) Left(k to v.value) else Right(k) }
            .partitionEithers()
    val removed: Map<K, V> = this - removes.toSet()
    val updated: Map<K, V> = removed + adds
    return updated
}

/**
 * Returns a [MapPatch] that, when applied, includes all of the values from the original [Map].
 *
 * Shorthand for:
 * ```kotlin
 * mapValues { just(it.value) }
 * ```
 */
fun <K, V> Map<K, V>.toMapPatch(): MapPatch<K, V> = mapValues { just(it.value) }

/**
 * Returns a [MapPatch] that, when applied, includes all of the entries from [new] whose keys are
 * not present in [old], and excludes all entries with keys present in [old] that are not also
 * present in [new].
 *
 * Note that, unlike [mapPatchFromFullDiff], only keys are taken into account. If the same key is
 * present in both [old] and [new], but the associated values are not equal, then the returned
 * [MapPatch] will *not* include any update to that key.
 */
fun <K, V> mapPatchFromKeyDiff(old: Map<K, V>, new: Map<K, V>): MapPatch<K, V> {
    val removes = old.keys - new.keys
    val adds = new - old.keys
    return buildMap {
        for (removed in removes) {
            put(removed, none)
        }
        for ((newKey, newValue) in adds) {
            put(newKey, just(newValue))
        }
    }
}

/**
 * Returns a [MapPatch] that, when applied, includes all of the entries from [new] that are not
 * present in [old], and excludes all entries with keys present in [old] that are not also present
 * in [new].
 *
 * Note that, unlike [mapPatchFromKeyDiff], both keys and values are taken into account. If the same
 * key is present in both [old] and [new], but the associated values are not equal, then the
 * returned [MapPatch] will include the entry from [new].
 */
fun <K, V> mapPatchFromFullDiff(old: Map<K, V>, new: Map<K, V>): MapPatch<K, V> {
    val removes = old.keys - new.keys
    val adds = new.mapMaybeValues { (k, v) -> if (k in old && v == old[k]) none else just(v) }
    return hashMapOf<K, Maybe<V>>().apply {
        for (removed in removes) {
            put(removed, none)
        }
        for ((newKey, newValue) in adds) {
            put(newKey, just(newValue))
        }
    }
}
