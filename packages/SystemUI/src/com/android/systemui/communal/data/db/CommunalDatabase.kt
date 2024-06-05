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

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.android.systemui.res.R

@Database(entities = [CommunalWidgetItem::class, CommunalItemRank::class], version = 1)
abstract class CommunalDatabase : RoomDatabase() {
    abstract fun communalWidgetDao(): CommunalWidgetDao

    companion object {
        private var instance: CommunalDatabase? = null

        /**
         * Gets a singleton instance of the communal database. If this is called for the first time
         * globally, a new instance is created.
         *
         * @param context The context the database is created in. Only effective when a new instance
         *   is created.
         * @param callback An optional callback registered to the database. Only effective when a
         *   new instance is created.
         */
        fun getInstance(
            context: Context,
            callback: Callback? = null,
        ): CommunalDatabase {
            if (instance == null) {
                instance =
                    Room.databaseBuilder(
                            context,
                            CommunalDatabase::class.java,
                            context.resources.getString(R.string.config_communalDatabase)
                        )
                        .also { builder ->
                            builder.fallbackToDestructiveMigration(dropAllTables = false)
                            callback?.let { callback -> builder.addCallback(callback) }
                        }
                        .build()
            }

            return instance!!
        }

        @VisibleForTesting
        fun setInstance(database: CommunalDatabase) {
            instance = database
        }
    }
}
