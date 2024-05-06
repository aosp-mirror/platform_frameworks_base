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

package com.android.systemui.qs.panels.shared.model

/** Represents a tile of type [T] associated with a width */
data class SizedTile<T>(val tile: T, val width: Int)

/** Represents a row of [SizedTile] with a maximum width of [columns] */
class TileRow<T>(private val columns: Int) {
    private var availableColumns = columns
    private val _tiles: MutableList<SizedTile<T>> = mutableListOf()
    val tiles: List<SizedTile<T>>
        get() = _tiles.toList()

    fun maybeAddTile(tile: SizedTile<T>): Boolean {
        if (availableColumns - tile.width >= 0) {
            _tiles.add(tile)
            availableColumns -= tile.width
            return true
        }
        return false
    }

    fun findLastIconTile(): SizedTile<T>? {
        return _tiles.findLast { it.width == 1 }
    }

    fun removeTile(tile: SizedTile<T>) {
        _tiles.remove(tile)
        availableColumns += tile.width
    }

    fun clear() {
        _tiles.clear()
        availableColumns = columns
    }

    fun isFull(): Boolean = availableColumns == 0
}
