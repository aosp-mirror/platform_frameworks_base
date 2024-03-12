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

package com.android.systemui.people.ui.viewmodel

import android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID
import android.appwidget.AppWidgetManager.INVALID_APPWIDGET_ID
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.people.PeopleSpaceUtils
import com.android.systemui.people.PeopleTileViewHelper
import com.android.systemui.people.data.model.PeopleTileModel
import com.android.systemui.people.data.repository.PeopleTileRepository
import com.android.systemui.people.data.repository.PeopleWidgetRepository
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "PeopleViewModel"

/**
 * Models UI state for the people space, allowing the user to select which conversation should be
 * associated to a new or existing Conversation widget.
 */
class PeopleViewModel(
    /**
     * The list of the priority tiles/conversations.
     *
     * Important: Even though this is a Flow, the underlying API used to populate this Flow is not
     * reactive and you have to manually call [onTileRefreshRequested] to refresh the tiles.
     */
    val priorityTiles: StateFlow<List<PeopleTileViewModel>>,

    /**
     * The list of the priority tiles/conversations.
     *
     * Important: Even though this is a Flow, the underlying API used to populate this Flow is not
     * reactive and you have to manually call [onTileRefreshRequested] to refresh the tiles.
     */
    val recentTiles: StateFlow<List<PeopleTileViewModel>>,

    /** The ID of the widget currently being edited/added. */
    val appWidgetId: StateFlow<Int>,

    /** The result of this user journey. */
    val result: StateFlow<Result?>,

    /** Refresh the [priorityTiles] and [recentTiles]. */
    val onTileRefreshRequested: () -> Unit,

    /** Called when the [appWidgetId] should be changed to [widgetId]. */
    val onWidgetIdChanged: (widgetId: Int) -> Unit,

    /** Clear [result], setting it to null. */
    val clearResult: () -> Unit,

    /** Called when a tile is clicked. */
    val onTileClicked: (tile: PeopleTileViewModel) -> Unit,

    /** Called when this user journey is cancelled. */
    val onUserJourneyCancelled: () -> Unit,
) : ViewModel() {
    /** The Factory that should be used to create a [PeopleViewModel]. */
    class Factory
    @Inject
    constructor(
        @Application private val context: Context,
        private val tileRepository: PeopleTileRepository,
        private val widgetRepository: PeopleWidgetRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass == PeopleViewModel::class.java)
            return PeopleViewModel(context, tileRepository, widgetRepository) as T
        }
    }

    sealed class Result {
        class Success(val data: Intent) : Result()

        object Cancelled : Result()
    }
}

private fun PeopleViewModel(
    @Application context: Context,
    tileRepository: PeopleTileRepository,
    widgetRepository: PeopleWidgetRepository,
): PeopleViewModel {
    fun priorityTiles(): List<PeopleTileViewModel> {
        return try {
            tileRepository.priorityTiles().map { it.toViewModel(context) }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't retrieve priority conversations", e)
            emptyList()
        }
    }

    fun recentTiles(): List<PeopleTileViewModel> {
        return try {
            tileRepository.recentTiles().map { it.toViewModel(context) }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't retrieve recent conversations", e)
            emptyList()
        }
    }

    val priorityTiles = MutableStateFlow(priorityTiles())
    val recentTiles = MutableStateFlow(recentTiles())
    val appWidgetId = MutableStateFlow(INVALID_APPWIDGET_ID)
    val result = MutableStateFlow<PeopleViewModel.Result?>(null)

    fun onTileRefreshRequested() {
        priorityTiles.value = priorityTiles()
        recentTiles.value = recentTiles()
    }

    fun onWidgetIdChanged(widgetId: Int) {
        appWidgetId.value = widgetId
    }

    fun clearResult() {
        result.value = null
    }

    fun onTileClicked(tile: PeopleTileViewModel) {
        val widgetId = appWidgetId.value
        if (PeopleSpaceUtils.DEBUG) {
            Log.d(
                TAG,
                "Put ${tile.username}'s shortcut ID: ${tile.key.shortcutId} for widget ID $widgetId"
            )
        }
        widgetRepository.setWidgetTile(widgetId, tile.key)
        result.value =
            PeopleViewModel.Result.Success(
                Intent().apply { putExtra(EXTRA_APPWIDGET_ID, appWidgetId.value) }
            )
    }

    fun onUserJourneyCancelled() {
        result.value = PeopleViewModel.Result.Cancelled
    }

    return PeopleViewModel(
        priorityTiles = priorityTiles.asStateFlow(),
        recentTiles = recentTiles.asStateFlow(),
        appWidgetId = appWidgetId.asStateFlow(),
        result = result.asStateFlow(),
        onTileRefreshRequested = ::onTileRefreshRequested,
        onWidgetIdChanged = ::onWidgetIdChanged,
        clearResult = ::clearResult,
        onTileClicked = ::onTileClicked,
        onUserJourneyCancelled = ::onUserJourneyCancelled,
    )
}

fun PeopleTileModel.toViewModel(@Application context: Context): PeopleTileViewModel {
    val icon =
        PeopleTileViewHelper.getPersonIconBitmap(
            context,
            this,
            PeopleTileViewHelper.getSizeInDp(
                context,
                R.dimen.avatar_size_for_medium,
                context.resources.displayMetrics.density,
            )
        )
    return PeopleTileViewModel(key, icon, username)
}
