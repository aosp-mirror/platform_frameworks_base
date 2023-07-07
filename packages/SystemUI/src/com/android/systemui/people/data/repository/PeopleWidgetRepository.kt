/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.people.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.people.widget.PeopleSpaceWidgetManager
import com.android.systemui.people.widget.PeopleTileKey
import javax.inject.Inject

interface PeopleWidgetRepository {
    /**
     * Bind the widget with ID [widgetId] to the tile keyed by [tileKey].
     *
     * If there is already a widget with [widgetId], this existing widget will be reconfigured and
     * associated to this tile. If there is no widget with [widgetId], a new one will be created.
     */
    fun setWidgetTile(widgetId: Int, tileKey: PeopleTileKey)
}

@SysUISingleton
class PeopleWidgetRepositoryImpl
@Inject
constructor(
    private val peopleSpaceWidgetManager: PeopleSpaceWidgetManager,
) : PeopleWidgetRepository {
    override fun setWidgetTile(widgetId: Int, tileKey: PeopleTileKey) {
        peopleSpaceWidgetManager.addNewWidget(widgetId, tileKey)
    }
}
