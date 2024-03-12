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

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "communal_widget_table")
data class CommunalWidgetItem(
    @PrimaryKey(autoGenerate = true) val uid: Long,
    /** Id of an app widget */
    @ColumnInfo(name = "widget_id") val widgetId: Int,
    /** Component name of the app widget provider */
    @ColumnInfo(name = "component_name") val componentName: String,
    /** Reference the id of an item persisted in the glanceable hub */
    @ColumnInfo(name = "item_id") val itemId: Long,
)

@Entity(tableName = "communal_item_rank_table")
data class CommunalItemRank(
    /** Unique id of an item persisted in the glanceable hub */
    @PrimaryKey(autoGenerate = true) val uid: Long,
    /** Order in which the item will be displayed */
    @ColumnInfo(name = "rank", defaultValue = "0") val rank: Int,
)
