/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.controls.management

import android.text.TextUtils
import android.util.ArrayMap
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.controller.ControlInfo

/**
 * This model is used to show controls separated by zones.
 *
 * The model will sort the controls and zones in the following manner:
 *  * The zones will be sorted in a first seen basis
 *  * The controls in each zone will be sorted in a first seen basis.
 *
 *  The controls passed should belong to the same structure, as an instance of this model will be
 *  created for each structure.
 *
 *  The list of favorite ids can contain ids for controls not passed to this model. Those will be
 *  filtered out.
 *
 * @property controls List of controls as returned by loading
 * @property initialFavoriteIds sorted ids of favorite controls.
 * @property noZoneString text to use as header for all controls that have blank or `null` zone.
 * @property controlsModelCallback callback to notify that favorites have changed for the first time
 */
class AllModel(
    private val controls: List<ControlStatus>,
    initialFavoriteIds: List<String>,
    private val emptyZoneString: CharSequence,
    private val controlsModelCallback: ControlsModel.ControlsModelCallback
) : ControlsModel {

    private var modified = false

    override val moveHelper = null

    override val favorites: List<ControlInfo>
        get() = favoriteIds.mapNotNull { id ->
            val control = controls.firstOrNull { it.control.controlId == id }?.control
            control?.let {
                ControlInfo.fromControl(it)
            }
        }

    private val favoriteIds = run {
        val ids = controls.mapTo(HashSet()) { it.control.controlId }
        initialFavoriteIds.filter { it in ids }.toMutableList()
    }

    override val elements: List<ElementWrapper> = createWrappers(controls)

    override fun changeFavoriteStatus(controlId: String, favorite: Boolean) {
        val toChange = elements.firstOrNull {
            it is ControlStatusWrapper && it.controlStatus.control.controlId == controlId
        } as ControlStatusWrapper?
        if (favorite == toChange?.controlStatus?.favorite) return
        val changed: Boolean = if (favorite) {
            favoriteIds.add(controlId)
        } else {
            favoriteIds.remove(controlId)
        }
        if (changed && !modified) {
            modified = true
            controlsModelCallback.onFirstChange()
        }
        toChange?.let {
            it.controlStatus.favorite = favorite
        }
    }

    private fun createWrappers(list: List<ControlStatus>): List<ElementWrapper> {
        val map = list.groupByTo(OrderedMap(ArrayMap<CharSequence, MutableList<ControlStatus>>())) {
            it.control.zone ?: ""
        }
        val output = mutableListOf<ElementWrapper>()
        var emptyZoneValues: Sequence<ControlStatusWrapper>? = null
        for (zoneName in map.orderedKeys) {
            val values = map.getValue(zoneName).asSequence().map { ControlStatusWrapper(it) }
            if (TextUtils.isEmpty(zoneName)) {
                emptyZoneValues = values
            } else {
                output.add(ZoneNameWrapper(zoneName))
                output.addAll(values)
            }
        }
        // Add controls with empty zone at the end
        if (emptyZoneValues != null) {
            if (map.size != 1) {
                output.add(ZoneNameWrapper(emptyZoneString))
            }
            output.addAll(emptyZoneValues)
        }
        return output
    }

    private class OrderedMap<K, V>(private val map: MutableMap<K, V>) : MutableMap<K, V> by map {

        val orderedKeys = mutableListOf<K>()

        override fun put(key: K, value: V): V? {
            if (key !in map) {
                orderedKeys.add(key)
            }
            return map.put(key, value)
        }

        override fun clear() {
            orderedKeys.clear()
            map.clear()
        }

        override fun remove(key: K): V? {
            val removed = map.remove(key)
            if (removed != null) {
                orderedKeys.remove(key)
            }
            return removed
        }
    }
}
