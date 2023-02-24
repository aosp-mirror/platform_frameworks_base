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
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.people.PeopleSpaceUtils
import com.android.systemui.people.PeopleTileViewHelper
import com.android.systemui.people.data.model.PeopleTileModel
import com.android.systemui.people.data.repository.PeopleTileRepository
import com.android.systemui.people.data.repository.PeopleWidgetRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Models UI state for the people space, allowing the user to select which conversation should be
 * associated to a new or existing Conversation widget.
 */
class PeopleViewModel(
    @Application private val context: Context,
    private val tileRepository: PeopleTileRepository,
    private val widgetRepository: PeopleWidgetRepository,
) : ViewModel() {
    /**
     * The list of the priority tiles/conversations.
     *
     * Important: Even though this is a Flow, the underlying API used to populate this Flow is not
     * reactive and you have to manually call [onTileRefreshRequested] to refresh the tiles.
     */
    private val _priorityTiles = MutableStateFlow(priorityTiles())
    val priorityTiles: StateFlow<List<PeopleTileViewModel>> = _priorityTiles.asStateFlow()

    /**
     * The list of the priority tiles/conversations.
     *
     * Important: Even though this is a Flow, the underlying API used to populate this Flow is not
     * reactive and you have to manually call [onTileRefreshRequested] to refresh the tiles.
     */
    private val _recentTiles = MutableStateFlow(recentTiles())
    val recentTiles: StateFlow<List<PeopleTileViewModel>> = _recentTiles.asStateFlow()

    /** The ID of the widget currently being edited/added. */
    private val _appWidgetId = MutableStateFlow(INVALID_APPWIDGET_ID)
    val appWidgetId: StateFlow<Int> = _appWidgetId.asStateFlow()

    /** The result of this user journey. */
    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    /** Refresh the [priorityTiles] and [recentTiles]. */
    fun onTileRefreshRequested() {
        _priorityTiles.value = priorityTiles()
        _recentTiles.value = recentTiles()
    }

    /** Called when the [appWidgetId] should be changed to [widgetId]. */
    fun onWidgetIdChanged(widgetId: Int) {
        _appWidgetId.value = widgetId
    }

    /** Clear [result], setting it to null. */
    fun clearResult() {
        _result.value = null
    }

    /** Called when a tile is clicked. */
    fun onTileClicked(tile: PeopleTileViewModel) {
        val widgetId = _appWidgetId.value
        if (PeopleSpaceUtils.DEBUG) {
            Log.d(
                TAG,
                "Put ${tile.username}'s shortcut ID: ${tile.key.shortcutId} for widget ID $widgetId"
            )
        }
        widgetRepository.setWidgetTile(widgetId, tile.key)
        _result.value =
            Result.Success(Intent().apply { putExtra(EXTRA_APPWIDGET_ID, appWidgetId.value) })
    }

    /** Called when this user journey is cancelled. */
    fun onUserJourneyCancelled() {
        _result.value = Result.Cancelled
    }

    private fun priorityTiles(): List<PeopleTileViewModel> {
        return try {
            tileRepository.priorityTiles().map { it.toViewModel() }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't retrieve priority conversations", e)
            emptyList()
        }
    }

    private fun recentTiles(): List<PeopleTileViewModel> {
        return try {
            tileRepository.recentTiles().map { it.toViewModel() }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't retrieve recent conversations", e)
            emptyList()
        }
    }

    private fun PeopleTileModel.toViewModel(): PeopleTileViewModel {
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

    companion object {
        private const val TAG = "PeopleViewModel"
    }
}
