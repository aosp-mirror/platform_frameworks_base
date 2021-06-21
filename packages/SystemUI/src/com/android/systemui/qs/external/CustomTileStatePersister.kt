/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs.external

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject

data class TileServiceKey(val componentName: ComponentName, val user: Int) {
    private val string = "${componentName.flattenToString()}:$user"
    override fun toString() = string
}
private const val STATE = "state"
private const val LABEL = "label"
private const val SUBTITLE = "subtitle"
private const val CONTENT_DESCRIPTION = "content_description"
private const val STATE_DESCRIPTION = "state_description"

/**
 * Persists and retrieves state for [CustomTile].
 *
 * This class will persists to a fixed [SharedPreference] file a state for a pair of [ComponentName]
 * and user id ([TileServiceKey]).
 *
 * It persists the state from a [Tile] necessary to present the view in the same state when
 * retrieved, with the exception of the icon.
 */
class CustomTileStatePersister @Inject constructor(context: Context) {
    companion object {
        private const val FILE_NAME = "custom_tiles_state"
    }

    private val sharedPreferences = context.getSharedPreferences(FILE_NAME, 0)

    /**
     * Read the state from [SharedPreferences].
     *
     * Returns `null` if the tile has no saved state.
     *
     * Any fields that have not been saved will be set to `null`
     */
    fun readState(key: TileServiceKey): Tile? {
        val state = sharedPreferences.getString(key.toString(), null) ?: return null
        return try {
            readTileFromString(state)
        } catch (e: JSONException) {
            Log.e("TileServicePersistence", "Bad saved state: $state", e)
            null
        }
    }

    /**
     * Persists the state into [SharedPreferences].
     *
     * The implementation does not store fields that are `null` or icons.
     */
    fun persistState(key: TileServiceKey, tile: Tile) {
        val state = writeToString(tile)

        sharedPreferences.edit().putString(key.toString(), state).apply()
    }

    /**
     * Removes the state for a given tile, user pair.
     *
     * Used when the tile is removed by the user.
     */
    fun removeState(key: TileServiceKey) {
        sharedPreferences.edit().remove(key.toString()).apply()
    }
}

@VisibleForTesting
internal fun readTileFromString(stateString: String): Tile {
    val json = JSONObject(stateString)
    return Tile().apply {
        state = json.getInt(STATE)
        label = json.getStringOrNull(LABEL)
        subtitle = json.getStringOrNull(SUBTITLE)
        contentDescription = json.getStringOrNull(CONTENT_DESCRIPTION)
        stateDescription = json.getStringOrNull(STATE_DESCRIPTION)
    }
}

// Properties with null values will not be saved to the Json string in any way. This makes sure
// to properly retrieve a null in that case.
private fun JSONObject.getStringOrNull(name: String): String? {
    return if (has(name)) getString(name) else null
}

@VisibleForTesting
internal fun writeToString(tile: Tile): String {
    // Not storing the icon
    return with(tile) {
        JSONObject()
            .put(STATE, state)
            .put(LABEL, label)
            .put(SUBTITLE, subtitle)
            .put(CONTENT_DESCRIPTION, contentDescription)
            .put(STATE_DESCRIPTION, stateDescription)
            .toString()
    }
}
