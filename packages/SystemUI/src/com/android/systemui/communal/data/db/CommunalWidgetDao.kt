/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.communal.data.db

import android.content.ComponentName
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.android.systemui.communal.nano.CommunalHubState
import com.android.systemui.communal.widgets.CommunalWidgetHost
import com.android.systemui.communal.widgets.CommunalWidgetModule.Companion.DEFAULT_WIDGETS
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Callback that will be invoked when the Room database is created. Then the database will be
 * populated with pre-configured default widgets to be rendered in the glanceable hub.
 */
class DefaultWidgetPopulation
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val communalWidgetHost: CommunalWidgetHost,
    private val communalWidgetDaoProvider: Provider<CommunalWidgetDao>,
    @Named(DEFAULT_WIDGETS) private val defaultWidgets: Array<String>,
    @CommunalLog logBuffer: LogBuffer,
) : RoomDatabase.Callback() {
    companion object {
        private const val TAG = "DefaultWidgetPopulation"
    }
    private val logger = Logger(logBuffer, TAG)

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        applicationScope.launch {
            addDefaultWidgets()
            logger.i("Default widgets were populated in the database.")
        }
    }

    // Read default widgets from config.xml and populate the database.
    private suspend fun addDefaultWidgets() =
        withContext(bgDispatcher) {
            defaultWidgets.forEachIndexed { index, name ->
                val provider = ComponentName.unflattenFromString(name)
                provider?.let {
                    val id = communalWidgetHost.allocateIdAndBindWidget(provider)
                    id?.let {
                        communalWidgetDaoProvider
                            .get()
                            .addWidget(
                                widgetId = id,
                                provider = provider,
                                priority = defaultWidgets.size - index
                            )
                    }
                }
            }
        }
}

@Dao
interface CommunalWidgetDao {
    @Query(
        "SELECT * FROM communal_widget_table JOIN communal_item_rank_table " +
            "ON communal_item_rank_table.uid = communal_widget_table.item_id " +
            "ORDER BY communal_item_rank_table.rank DESC"
    )
    fun getWidgets(): Flow<Map<CommunalItemRank, CommunalWidgetItem>>

    @Query("SELECT * FROM communal_widget_table WHERE widget_id = :id")
    fun getWidgetByIdNow(id: Int): CommunalWidgetItem?

    @Delete fun deleteWidgets(vararg widgets: CommunalWidgetItem)

    @Query("DELETE FROM communal_item_rank_table WHERE uid = :itemId")
    fun deleteItemRankById(itemId: Long)

    @Query(
        "INSERT INTO communal_widget_table(widget_id, component_name, item_id) " +
            "VALUES(:widgetId, :componentName, :itemId)"
    )
    fun insertWidget(widgetId: Int, componentName: String, itemId: Long): Long

    @Query("INSERT INTO communal_item_rank_table(rank) VALUES(:rank)")
    fun insertItemRank(rank: Int): Long

    @Query("UPDATE communal_item_rank_table SET rank = :order WHERE uid = :itemUid")
    fun updateItemRank(itemUid: Long, order: Int)

    @Query("DELETE FROM communal_widget_table") fun clearCommunalWidgetsTable()

    @Query("DELETE FROM communal_item_rank_table") fun clearCommunalItemRankTable()

    @Transaction
    fun updateWidgetOrder(widgetIdToPriorityMap: Map<Int, Int>) {
        widgetIdToPriorityMap.forEach { (id, priority) ->
            val widget = getWidgetByIdNow(id)
            if (widget != null) {
                updateItemRank(widget.itemId, priority)
            }
        }
    }

    @Transaction
    fun addWidget(widgetId: Int, provider: ComponentName, priority: Int): Long {
        return addWidget(
            widgetId = widgetId,
            componentName = provider.flattenToString(),
            priority = priority,
        )
    }

    @Transaction
    fun addWidget(widgetId: Int, componentName: String, priority: Int): Long {
        return insertWidget(
            widgetId = widgetId,
            componentName = componentName,
            itemId = insertItemRank(priority),
        )
    }

    @Transaction
    fun deleteWidgetById(widgetId: Int): Boolean {
        val widget =
            getWidgetByIdNow(widgetId) ?: // no entry to delete from db
            return false

        deleteItemRankById(widget.itemId)
        deleteWidgets(widget)
        return true
    }

    /** Wipes current database and restores the snapshot represented by [state]. */
    @Transaction
    fun restoreCommunalHubState(state: CommunalHubState) {
        clearCommunalWidgetsTable()
        clearCommunalItemRankTable()

        state.widgets.forEach { addWidget(it.widgetId, it.componentName, it.rank) }
    }
}
