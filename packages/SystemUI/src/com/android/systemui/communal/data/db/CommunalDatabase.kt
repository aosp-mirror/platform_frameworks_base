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
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.android.systemui.communal.shared.model.GlanceableHubMultiUserHelperImpl
import com.android.systemui.communal.shared.model.SpanValue
import com.android.systemui.communal.shared.model.toResponsive
import com.android.systemui.res.R

@Database(entities = [CommunalWidgetItem::class, CommunalItemRank::class], version = 5)
abstract class CommunalDatabase : RoomDatabase() {
    abstract fun communalWidgetDao(): CommunalWidgetDao

    companion object {
        private const val TAG = "CommunalDatabase"
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
        fun getInstance(context: Context, callback: Callback? = null): CommunalDatabase {
            with(GlanceableHubMultiUserHelperImpl(Process.myUserHandle())) {
                // Assert that the database is never accessed from a headless system user.
                assertNotInHeadlessSystemUser()
            }

            if (instance == null) {
                instance =
                    Room.databaseBuilder(
                            context,
                            CommunalDatabase::class.java,
                            context.resources.getString(R.string.config_communalDatabase),
                        )
                        .also { builder ->
                            builder.addMigrations(
                                MIGRATION_1_2,
                                MIGRATION_2_3,
                                MIGRATION_3_4,
                                MIGRATION_4_5,
                            )
                            builder.fallbackToDestructiveMigration(dropAllTables = true)
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

        /**
         * This migration adds a user_serial_number column and sets its default value as
         * [CommunalWidgetItem.USER_SERIAL_NUMBER_UNDEFINED]. Work profile widgets added before the
         * migration still work as expected, but they would be backed up as personal.
         */
        @VisibleForTesting
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    Log.i(TAG, "Migrating from version 1 to 2")
                    db.execSQL(
                        "ALTER TABLE communal_widget_table " +
                            "ADD COLUMN user_serial_number INTEGER NOT NULL DEFAULT " +
                            "${CommunalWidgetItem.USER_SERIAL_NUMBER_UNDEFINED}"
                    )
                }
            }

        /**
         * This migration reverses the ranks. For example, if the ranks are 2, 1, 0, then after the
         * migration they will be 0, 1, 2.
         */
        @VisibleForTesting
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    Log.i(TAG, "Migrating from version 2 to 3")
                    db.execSQL(
                        "UPDATE communal_item_rank_table " +
                            "SET rank = (SELECT MAX(rank) FROM communal_item_rank_table) - rank"
                    )
                }
            }

        /**
         * This migration adds a span_y column to the communal_widget_table and sets its default
         * value to 3.
         */
        @VisibleForTesting
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    Log.i(TAG, "Migrating from version 3 to 4")
                    db.execSQL(
                        "ALTER TABLE communal_widget_table " +
                            "ADD COLUMN span_y INTEGER NOT NULL DEFAULT 3"
                    )
                }
            }

        /** This migration adds a new spanY column for responsive grid sizing. */
        @VisibleForTesting
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    Log.i(TAG, "Migrating from version 4 to 5")
                    db.execSQL(
                        "ALTER TABLE communal_widget_table " +
                            "ADD COLUMN span_y_new INTEGER NOT NULL DEFAULT 1"
                    )
                    db.query("SELECT item_id, span_y FROM communal_widget_table").use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getInt(cursor.getColumnIndex("item_id"))
                            val spanYFixed =
                                SpanValue.Fixed(cursor.getInt(cursor.getColumnIndex("span_y")))
                            val spanYResponsive = spanYFixed.toResponsive()
                            db.execSQL(
                                "UPDATE communal_widget_table SET span_y_new = " +
                                    "${spanYResponsive.value} WHERE item_id = $id"
                            )
                        }
                    }
                }
            }
    }
}
