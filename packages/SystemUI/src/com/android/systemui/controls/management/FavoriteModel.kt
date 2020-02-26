/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.util.Log
import com.android.systemui.controls.ControlStatus
import java.util.Collections
import java.util.Comparator

/**
 * Model for keeping track of current favorites and their order.
 *
 * This model is to be used with two [ControlAdapter] one that shows only favorites in the current
 * order and another that shows all controls, separated by zone. When the favorite state of any
 * control is modified or when the favorites are reordered, the adapters are notified of the change.
 *
 * @param listControls list of all the [ControlStatus] to display. This includes controls currently
 *                     marked as favorites as well as those that have been removed (not returned
 *                     from load)
 * @param listFavoritesIds list of the [Control.controlId] for all the favorites, including those
 *                         that have been removed.
 * @param favoritesAdapter [ControlAdapter] used by the [RecyclerView] that shows only favorites
 * @param allAdapter [ControlAdapter] used by the [RecyclerView] that shows all controls
 */
class FavoriteModel(
    private val listControls: List<ControlStatus>,
    listFavoritesIds: List<String>,
    private val favoritesAdapter: ControlAdapter,
    private val allAdapter: ControlAdapter
) {

    companion object {
        private const val TAG = "FavoriteModel"
    }

    /**
     * List of favorite controls ([ControlWrapper]) in order.
     *
     * Initially, this list will give a list of wrappers in the order specified by the constructor
     * variable `listFavoriteIds`.
     *
     * As the favorites are added, removed or moved, this list will keep track of those changes.
     */
    val favorites: List<ControlWrapper> = listFavoritesIds.map { id ->
            ControlWrapper(listControls.first { it.control.controlId == id })
        }.toMutableList()

    /**
     * List of all controls by zones.
     *
     * Lists all the controls with the zone names interleaved as a flat list. After each zone name,
     * the controls in that zone are listed. Zones are listed in alphabetical order
     */
    val all: List<ElementWrapper> = listControls.groupBy { it.control.zone }
            .mapKeys { it.key ?: "" } // map null to empty
            .toSortedMap(CharSequenceComparator())
            .flatMap {
                val controls = it.value.map { ControlWrapper(it) }
                if (!TextUtils.isEmpty(it.key)) {
                    listOf(ZoneNameWrapper(it.key)) + controls
                } else {
                    controls
                }
            }

    /**
     * Change the favorite status of a [Control].
     *
     * This can be invoked from any of the [ControlAdapter]. It will change the status of that
     * control and either add it to the list of favorites (at the end) or remove it from it.
     *
     * Removing the favorite status from a Removed control will make it disappear completely if
     * changes are saved.
     *
     * @param controlId the id of the [Control] to change the status
     * @param favorite `true` if and only if it's set to be a favorite.
     */
    fun changeFavoriteStatus(controlId: String, favorite: Boolean) {
        favorites as MutableList
        val index = all.indexOfFirst {
            it is ControlWrapper && it.controlStatus.control.controlId == controlId
        }
        val control = (all[index] as ControlWrapper).controlStatus
        if (control.favorite == favorite) {
            Log.d(TAG, "Changing favorite to same state for ${control.control.controlId} ")
            return
        } else {
            control.favorite = favorite
        }
        allAdapter.notifyItemChanged(index)
        if (favorite) {
            favorites.add(all[index] as ControlWrapper)
            favoritesAdapter.notifyItemInserted(favorites.size - 1)
        } else {
            val i = favorites.indexOfFirst { it.controlStatus.control.controlId == controlId }
            favorites.removeAt(i)
            favoritesAdapter.notifyItemRemoved(i)
        }
    }

    /**
     * Move items in the model and notify the [favoritesAdapter].
     */
    fun onMoveItem(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) {
                Collections.swap(favorites, i, i + 1)
            }
        } else {
            for (i in from downTo to + 1) {
                Collections.swap(favorites, i, i - 1)
            }
        }
        favoritesAdapter.notifyItemMoved(from, to)
    }
}

/**
 * Compares [CharSequence] as [String].
 *
 * It will have empty strings as the first element
 */
class CharSequenceComparator : Comparator<CharSequence> {
    override fun compare(p0: CharSequence?, p1: CharSequence?): Int {
        if (p0 == null && p1 == null) return 0
        else if (p0 == null && p1 != null) return -1
        else if (p0 != null && p1 == null) return 1
        return p0.toString().compareTo(p1.toString())
    }
}