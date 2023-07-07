/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.data.repository

import android.content.ComponentName
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.UserFileManager
import javax.inject.Inject

/**
 * Repository for keeping track of whether a given [CustomTile] [ComponentName] has been added to
 * the set of current tiles for a user. This is used to determine when lifecycle methods in
 * `TileService` about the tile being added/removed need to be called.
 */
interface CustomTileAddedRepository {
    /**
     * Check if a particular [CustomTile] associated with [componentName] has been added for
     * [userId] and has not been removed since.
     */
    fun isTileAdded(componentName: ComponentName, userId: Int): Boolean

    /**
     * Persists whether a particular [CustomTile] associated with [componentName] has been added and
     * it's currently in the set of selected tiles for [userId].
     */
    fun setTileAdded(componentName: ComponentName, userId: Int, added: Boolean)
}

@SysUISingleton
class CustomTileAddedSharedPrefsRepository
@Inject
constructor(private val userFileManager: UserFileManager) : CustomTileAddedRepository {

    override fun isTileAdded(componentName: ComponentName, userId: Int): Boolean {
        return userFileManager
            .getSharedPreferences(TILES, 0, userId)
            .getBoolean(componentName.flattenToString(), false)
    }

    override fun setTileAdded(componentName: ComponentName, userId: Int, added: Boolean) {
        userFileManager
            .getSharedPreferences(TILES, 0, userId)
            .edit()
            .putBoolean(componentName.flattenToString(), added)
            .apply()
    }

    companion object {
        private const val TILES = "tiles_prefs"
    }
}
