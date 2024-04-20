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

package com.android.systemui.communal.data.backup

import android.content.Context
import androidx.annotation.WorkerThread
import com.android.systemui.communal.data.db.CommunalDatabase
import com.android.systemui.communal.nano.CommunalHubState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Utilities for communal backup and restore. */
class CommunalBackupUtils(
    private val context: Context,
) {

    /**
     * Retrieves a communal hub state protobuf that represents the current state of the communal
     * database.
     */
    @WorkerThread
    fun getCommunalHubState(): CommunalHubState {
        val database = CommunalDatabase.getInstance(context)
        val widgetsFromDb = runBlocking { database.communalWidgetDao().getWidgets().first() }
        val widgetsState = mutableListOf<CommunalHubState.CommunalWidgetItem>()
        widgetsFromDb.keys.forEach { rankItem ->
            widgetsState.add(
                CommunalHubState.CommunalWidgetItem().apply {
                    rank = rankItem.rank
                    widgetId = widgetsFromDb[rankItem]!!.widgetId
                    componentName = widgetsFromDb[rankItem]?.componentName
                }
            )
        }
        return CommunalHubState().apply { widgets = widgetsState.toTypedArray() }
    }
}
