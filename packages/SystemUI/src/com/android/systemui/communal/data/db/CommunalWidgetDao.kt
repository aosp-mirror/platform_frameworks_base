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
import android.os.UserManager
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteDatabase
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.communal.nano.CommunalHubState
import com.android.systemui.communal.shared.model.SpanValue
import com.android.systemui.communal.shared.model.toFixed
import com.android.systemui.communal.shared.model.toResponsive
import com.android.systemui.communal.widgets.CommunalWidgetHost
import com.android.systemui.communal.widgets.CommunalWidgetModule.Companion.DEFAULT_WIDGETS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Callback that will be invoked when the Room database is created. Then the database will be
 * populated with pre-configured default widgets to be rendered in the glanceable hub.
 */
@SysUISingleton
class DefaultWidgetPopulation
@Inject
constructor(
    @Background private val bgScope: CoroutineScope,
    private val communalWidgetHost: CommunalWidgetHost,
    private val communalWidgetDaoProvider: Provider<CommunalWidgetDao>,
    @Named(DEFAULT_WIDGETS) private val defaultWidgets: Array<String>,
    @CommunalLog logBuffer: LogBuffer,
    private val userManager: UserManager,
) : RoomDatabase.Callback() {
    companion object {
        private const val TAG = "DefaultWidgetPopulation"
    }

    private val logger = Logger(logBuffer, TAG)

    /**
     * Reason for skipping default widgets population. Do not skip if this value is
     * [SkipReason.NONE].
     */
    private var skipReason = SkipReason.NONE

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        if (skipReason != SkipReason.NONE) {
            logger.i("Skipped populating default widgets. Reason: $skipReason")
            return
        }

        bgScope.launch {
            // Default widgets should be associated with the main user.
            val user = userManager.mainUser

            if (user == null) {
                logger.w(
                    "Skipped populating default widgets. Reason: device does not have a main user"
                )
                return@launch
            }

            val userSerialNumber = userManager.getUserSerialNumber(user.identifier)

            defaultWidgets.forEachIndexed { index, name ->
                val provider = ComponentName.unflattenFromString(name)
                provider?.let {
                    val id = communalWidgetHost.allocateIdAndBindWidget(provider, user)
                    id?.let {
                        communalWidgetDaoProvider
                            .get()
                            .addWidget(
                                widgetId = id,
                                componentName = name,
                                rank = index,
                                userSerialNumber = userSerialNumber,
                                spanY = SpanValue.Fixed(3),
                            )
                    }
                }
            }

            logger.i("Populated default widgets in the database.")
        }
    }

    /**
     * Skip populating default widgets in the Glanceable Hub when the database is created. This has
     * no effect if default widgets have been populated already.
     *
     * @param skipReason Reason for skipping the default widgets population.
     */
    fun skipDefaultWidgetsPopulation(skipReason: SkipReason) {
        this.skipReason = skipReason
    }

    /** Reason for skipping default widgets population. */
    enum class SkipReason {
        /** Do not skip. */
        NONE,
        /** Widgets are restored from a backup. */
        RESTORED_FROM_BACKUP,
    }
}

@Dao
interface CommunalWidgetDao {
    @Query(
        "SELECT * FROM communal_widget_table JOIN communal_item_rank_table " +
            "ON communal_item_rank_table.uid = communal_widget_table.item_id " +
            "ORDER BY communal_item_rank_table.rank ASC"
    )
    fun getWidgets(): Flow<Map<CommunalItemRank, CommunalWidgetItem>>

    @Query(
        "SELECT * FROM communal_widget_table JOIN communal_item_rank_table " +
            "ON communal_item_rank_table.uid = communal_widget_table.item_id " +
            "ORDER BY communal_item_rank_table.rank ASC"
    )
    fun getWidgetsNow(): Map<CommunalItemRank, CommunalWidgetItem>

    @Query("SELECT * FROM communal_widget_table WHERE widget_id = :id")
    fun getWidgetByIdNow(id: Int): CommunalWidgetItem?

    @Delete fun deleteWidgets(vararg widgets: CommunalWidgetItem)

    @Query("DELETE FROM communal_item_rank_table WHERE uid = :itemId")
    fun deleteItemRankById(itemId: Long)

    @Query(
        "INSERT INTO communal_widget_table" +
            "(widget_id, component_name, item_id, user_serial_number, span_y, span_y_new) " +
            "VALUES(:widgetId, :componentName, :itemId, :userSerialNumber, :spanY, :spanYNew)"
    )
    fun insertWidget(
        widgetId: Int,
        componentName: String,
        itemId: Long,
        userSerialNumber: Int,
        spanY: Int,
        spanYNew: Int,
    ): Long

    @Query("INSERT INTO communal_item_rank_table(rank) VALUES(:rank)")
    fun insertItemRank(rank: Int): Long

    @Query("UPDATE communal_item_rank_table SET rank = :order WHERE uid = :itemUid")
    fun updateItemRank(itemUid: Long, order: Int)

    @Update fun updateWidget(widget: CommunalWidgetItem)

    @Query("DELETE FROM communal_widget_table") fun clearCommunalWidgetsTable()

    @Query("DELETE FROM communal_item_rank_table") fun clearCommunalItemRankTable()

    @Transaction
    fun updateWidgetOrder(widgetIdToRankMap: Map<Int, Int>) {
        widgetIdToRankMap.forEach { (id, rank) ->
            val widget = getWidgetByIdNow(id)
            if (widget != null) {
                updateItemRank(widget.itemId, rank)
            }
        }
    }

    @Transaction
    fun resizeWidget(appWidgetId: Int, spanY: SpanValue, widgetIdToRankMap: Map<Int, Int>) {
        val widget = getWidgetByIdNow(appWidgetId)
        if (widget != null) {
            updateWidget(
                widget.copy(spanY = spanY.toFixed().value, spanYNew = spanY.toResponsive().value)
            )
        }
        updateWidgetOrder(widgetIdToRankMap)
    }

    @Transaction
    fun addWidget(
        widgetId: Int,
        provider: ComponentName,
        rank: Int? = null,
        userSerialNumber: Int,
        spanY: SpanValue,
    ): Long {
        return addWidget(
            widgetId = widgetId,
            componentName = provider.flattenToString(),
            rank = rank,
            userSerialNumber = userSerialNumber,
            spanY = spanY,
        )
    }

    @Transaction
    fun addWidget(
        widgetId: Int,
        componentName: String,
        rank: Int? = null,
        userSerialNumber: Int,
        spanY: SpanValue,
    ): Long {
        val widgets = getWidgetsNow()

        // If rank is not specified (null or less than 0), rank it last by finding the current
        // maximum rank and increment by 1. If the new widget is the first widget, set rank to 0.
        val newRank = rank?.takeIf { it >= 0 } ?: widgets.keys.maxOfOrNull { it.rank + 1 } ?: 0

        // Shift widgets after [rank], unless widget is added at the end.
        if (rank != null) {
            widgets.forEach { (rankEntry, widgetEntry) ->
                if (rankEntry.rank < newRank) return@forEach
                updateItemRank(widgetEntry.itemId, rankEntry.rank + 1)
            }
        }

        return insertWidget(
            widgetId = widgetId,
            componentName = componentName,
            itemId = insertItemRank(newRank),
            userSerialNumber = userSerialNumber,
            spanY = spanY.toFixed().value,
            spanYNew = spanY.toResponsive().value,
        )
    }

    @Transaction
    fun deleteWidgetById(widgetId: Int): Boolean {
        val widget =
            getWidgetByIdNow(widgetId)
                ?: // no entry to delete from db
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

        state.widgets.forEach {
            // Check if there is a new value to restore. If so, restore that new value.
            val spanYResponsive = if (it.spanYNew != 0) SpanValue.Responsive(it.spanYNew) else null
            // If no new value, restore any existing old values.
            val spanY = spanYResponsive ?: SpanValue.Fixed(it.spanY.coerceIn(3, 6))

            addWidget(it.widgetId, it.componentName, it.rank, it.userSerialNumber, spanY)
        }
    }
}
