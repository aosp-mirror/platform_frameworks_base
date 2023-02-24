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

import android.app.people.PeopleSpaceTile
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.people.PeopleTileViewHelper
import com.android.systemui.people.data.model.PeopleTileModel
import com.android.systemui.people.widget.PeopleSpaceWidgetManager
import com.android.systemui.people.widget.PeopleTileKey
import javax.inject.Inject

/** A Repository to fetch the current tiles/conversations. */
// TODO(b/238993727): Make the tiles API reactive.
interface PeopleTileRepository {
    /* The current priority tiles. */
    fun priorityTiles(): List<PeopleTileModel>

    /* The current recent tiles. */
    fun recentTiles(): List<PeopleTileModel>
}

@SysUISingleton
class PeopleTileRepositoryImpl
@Inject
constructor(
    private val peopleSpaceWidgetManager: PeopleSpaceWidgetManager,
) : PeopleTileRepository {
    override fun priorityTiles(): List<PeopleTileModel> {
        return peopleSpaceWidgetManager.priorityTiles.map { it.toModel() }
    }

    override fun recentTiles(): List<PeopleTileModel> {
        return peopleSpaceWidgetManager.recentTiles.map { it.toModel() }
    }

    private fun PeopleSpaceTile.toModel(): PeopleTileModel {
        return PeopleTileModel(
            PeopleTileKey(this),
            userName.toString(),
            userIcon,
            PeopleTileViewHelper.getHasNewStory(this),
            isImportantConversation,
            PeopleTileViewHelper.isDndBlockingTileData(this),
        )
    }
}
