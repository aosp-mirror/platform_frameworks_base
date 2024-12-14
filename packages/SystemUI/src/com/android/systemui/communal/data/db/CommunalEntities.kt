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
    /**
     * A serial number of the user that the widget provider is associated with. For example, a work
     * profile widget.
     *
     * A serial number may be different from its user id in that user ids may be recycled but serial
     * numbers are unique until the device is wiped.
     *
     * Most commonly, this value would be 0 for the primary user, and 10 for the work profile.
     */
    @ColumnInfo(name = "user_serial_number", defaultValue = "$USER_SERIAL_NUMBER_UNDEFINED")
    val userSerialNumber: Int,
) {
    companion object {
        /**
         * The user serial number associated with the widget is undefined.
         *
         * This should only happen for widgets migrated from V1 before user serial number was
         * included in the schema.
         */
        const val USER_SERIAL_NUMBER_UNDEFINED = -1
    }
}

@Entity(tableName = "communal_item_rank_table")
data class CommunalItemRank(
    /** Unique id of an item persisted in the glanceable hub */
    @PrimaryKey(autoGenerate = true) val uid: Long,
    /** Order in which the item will be displayed */
    @ColumnInfo(name = "rank", defaultValue = "0") val rank: Int,
)
