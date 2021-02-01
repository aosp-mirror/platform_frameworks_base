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

import android.content.ComponentName
import android.util.Log
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.controls.ControlInterface
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlInfo
import java.util.Collections

/**
 * Model used to show and rearrange favorites.
 *
 * The model will show all the favorite controls and a divider that can be toggled visible/gone.
 * It will place the items selected as favorites before the divider and the ones unselected after.
 *
 * @property componentName used by the [ControlAdapter] to retrieve resources.
 * @property favorites list of current favorites
 * @property favoritesModelCallback callback to notify on first change and empty favorites
 */
class FavoritesModel(
    private val customIconCache: CustomIconCache,
    private val componentName: ComponentName,
    favorites: List<ControlInfo>,
    private val favoritesModelCallback: FavoritesModelCallback
) : ControlsModel {

    companion object {
        private const val TAG = "FavoritesModel"
    }

    private var adapter: RecyclerView.Adapter<*>? = null
    private var modified = false

    override val moveHelper = object : ControlsModel.MoveHelper {
        override fun canMoveBefore(position: Int): Boolean {
            return position > 0 && position < dividerPosition
        }

        override fun canMoveAfter(position: Int): Boolean {
            return position >= 0 && position < dividerPosition - 1
        }

        override fun moveBefore(position: Int) {
            if (!canMoveBefore(position)) {
                Log.w(TAG, "Cannot move position $position before")
            } else {
                onMoveItem(position, position - 1)
            }
        }

        override fun moveAfter(position: Int) {
            if (!canMoveAfter(position)) {
                Log.w(TAG, "Cannot move position $position after")
            } else {
                onMoveItem(position, position + 1)
            }
        }
    }

    override fun attachAdapter(adapter: RecyclerView.Adapter<*>) {
        this.adapter = adapter
    }

    override val favorites: List<ControlInfo>
        get() = elements.take(dividerPosition).map {
            (it as ControlInfoWrapper).controlInfo
        }

    override val elements: List<ElementWrapper> = favorites.map {
        ControlInfoWrapper(componentName, it, true, customIconCache::retrieve)
    } + DividerWrapper()

    /**
     * Indicates the position of the divider to determine
     */
    private var dividerPosition = elements.size - 1

    override fun changeFavoriteStatus(controlId: String, favorite: Boolean) {
        val position = elements.indexOfFirst { it is ControlInterface && it.controlId == controlId }
        if (position == -1) {
            return // controlId not found
        }
        if (position < dividerPosition && favorite || position > dividerPosition && !favorite) {
            return // Does not change favorite status
        }
        if (favorite) {
            onMoveItemInternal(position, dividerPosition)
        } else {
            onMoveItemInternal(position, elements.size - 1)
        }
    }

    override fun onMoveItem(from: Int, to: Int) {
        onMoveItemInternal(from, to)
    }

    private fun updateDividerNone(oldDividerPosition: Int, show: Boolean) {
        (elements[oldDividerPosition] as DividerWrapper).showNone = show
        favoritesModelCallback.onNoneChanged(show)
    }

    private fun updateDividerShow(oldDividerPosition: Int, show: Boolean) {
        (elements[oldDividerPosition] as DividerWrapper).showDivider = show
    }

    /**
     * Performs the update in the model.
     *
     *   * update the favorite field of the [ControlInterface]
     *   * update the fields of the [DividerWrapper]
     *   * move the corresponding element in [elements]
     *
     * It may emit the following signals:
     *   * [RecyclerView.Adapter.notifyItemChanged] if a [ControlInterface.favorite] has changed
     *     (in the new position) or if the information in [DividerWrapper] has changed (in the
     *     old position).
     *   * [RecyclerView.Adapter.notifyItemMoved]
     *   * [FavoritesModelCallback.onNoneChanged] whenever we go from 1 to 0 favorites and back
     *   * [ControlsModel.ControlsModelCallback.onFirstChange] upon the first change in the model
     */
    private fun onMoveItemInternal(from: Int, to: Int) {
        if (from == dividerPosition) return // divider does not move
        var changed = false
        if (from < dividerPosition && to >= dividerPosition ||
                from > dividerPosition && to <= dividerPosition) {
            if (from < dividerPosition && to >= dividerPosition) {
                // favorite to not favorite
                (elements[from] as ControlInfoWrapper).favorite = false
            } else if (from > dividerPosition && to <= dividerPosition) {
                // not favorite to favorite
                (elements[from] as ControlInfoWrapper).favorite = true
            }
            changed = true
            updateDivider(from, to)
        }
        moveElement(from, to)
        adapter?.notifyItemMoved(from, to)
        if (changed) {
            adapter?.notifyItemChanged(to, Any())
        }
        if (!modified) {
            modified = true
            favoritesModelCallback.onFirstChange()
        }
    }

    private fun updateDivider(from: Int, to: Int) {
        var dividerChanged = false
        val oldDividerPosition = dividerPosition
        if (from < dividerPosition && to >= dividerPosition) { // favorite to not favorite
            dividerPosition--
            if (dividerPosition == 0) {
                updateDividerNone(oldDividerPosition, true)
                dividerChanged = true
            }
            if (dividerPosition == elements.size - 2) {
                updateDividerShow(oldDividerPosition, true)
                dividerChanged = true
            }
        } else if (from > dividerPosition && to <= dividerPosition) { // not favorite to favorite
            dividerPosition++
            if (dividerPosition == 1) {
                updateDividerNone(oldDividerPosition, false)
                dividerChanged = true
            }
            if (dividerPosition == elements.size - 1) {
                updateDividerShow(oldDividerPosition, false)
                dividerChanged = true
            }
        }
        if (dividerChanged) {
            adapter?.notifyItemChanged(oldDividerPosition)
        }
    }

    private fun moveElement(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) {
                Collections.swap(elements, i, i + 1)
            }
        } else {
            for (i in from downTo to + 1) {
                Collections.swap(elements, i, i - 1)
            }
        }
    }

    /**
     * Touch helper to facilitate dragging in the [RecyclerView].
     *
     * Only views above the divider line (favorites) can be dragged or accept drops.
     */
    val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(0, 0) {

        private val MOVEMENT = ItemTouchHelper.UP or
                ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or
                ItemTouchHelper.RIGHT

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            onMoveItem(viewHolder.adapterPosition, target.adapterPosition)
            return true
        }

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            if (viewHolder.adapterPosition < dividerPosition) {
                return ItemTouchHelper.Callback.makeMovementFlags(MOVEMENT, 0)
            } else {
                return ItemTouchHelper.Callback.makeMovementFlags(0, 0)
            }
        }

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return target.adapterPosition < dividerPosition
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isItemViewSwipeEnabled() = false
    }

    interface FavoritesModelCallback : ControlsModel.ControlsModelCallback {
        fun onNoneChanged(showNoFavorites: Boolean)
    }
}