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

package com.android.systemui.communal.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.SysuiTestCase
import com.android.systemui.lifecycle.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalDatabaseMigrationsTest : SysuiTestCase() {

    @JvmField @Rule val instantTaskExecutor = InstantTaskExecutorRule()

    @get:Rule
    val migrationTestHelper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CommunalDatabase::class.java.canonicalName,
        )

    @Test
    fun migrate1To2() {
        // Create a communal database in version 1
        val databaseV1 = migrationTestHelper.createDatabase(DATABASE_NAME, version = 1)

        // Populate some fake data
        val fakeWidgetsV1 =
            listOf(
                FakeCommunalWidgetItemV1(1, "test_widget_1", 11),
                FakeCommunalWidgetItemV1(2, "test_widget_2", 12),
                FakeCommunalWidgetItemV1(3, "test_widget_3", 13),
            )
        databaseV1.insertWidgetsV1(fakeWidgetsV1)

        // Verify fake widgets populated
        databaseV1.verifyWidgetsV1(fakeWidgetsV1)

        // Run migration and get database V2, the migration test helper verifies that the schema is
        // updated correctly
        val databaseV2 =
            migrationTestHelper.runMigrationsAndValidate(
                name = DATABASE_NAME,
                version = 2,
                validateDroppedTables = false,
                CommunalDatabase.MIGRATION_1_2,
            )

        // Verify data is migrated correctly
        databaseV2.verifyWidgetsV2(fakeWidgetsV1.map { it.getV2() })
    }

    @Test
    fun migrate2To3_noGapBetweenRanks_ranksReversed() {
        // Create a communal database in version 2
        val databaseV2 = migrationTestHelper.createDatabase(DATABASE_NAME, version = 2)

        // Populate some fake data
        val fakeRanks =
            listOf(
                FakeCommunalItemRank(3),
                FakeCommunalItemRank(2),
                FakeCommunalItemRank(1),
                FakeCommunalItemRank(0),
            )
        databaseV2.insertRanks(fakeRanks)

        // Verify fake ranks populated
        databaseV2.verifyRanksInOrder(fakeRanks)

        // Run migration and get database V3
        val databaseV3 =
            migrationTestHelper.runMigrationsAndValidate(
                name = DATABASE_NAME,
                version = 3,
                validateDroppedTables = false,
                CommunalDatabase.MIGRATION_2_3,
            )

        // Verify ranks are reversed
        databaseV3.verifyRanksInOrder(
            listOf(
                FakeCommunalItemRank(0),
                FakeCommunalItemRank(1),
                FakeCommunalItemRank(2),
                FakeCommunalItemRank(3),
            )
        )
    }

    @Test
    fun migrate2To3_withGapBetweenRanks_ranksReversed() {
        // Create a communal database in version 2
        val databaseV2 = migrationTestHelper.createDatabase(DATABASE_NAME, version = 2)

        // Populate some fake data with gaps between ranks
        val fakeRanks =
            listOf(
                FakeCommunalItemRank(9),
                FakeCommunalItemRank(7),
                FakeCommunalItemRank(2),
                FakeCommunalItemRank(0),
            )
        databaseV2.insertRanks(fakeRanks)

        // Verify fake ranks populated
        databaseV2.verifyRanksInOrder(fakeRanks)

        // Run migration and get database V3
        val databaseV3 =
            migrationTestHelper.runMigrationsAndValidate(
                name = DATABASE_NAME,
                version = 3,
                validateDroppedTables = false,
                CommunalDatabase.MIGRATION_2_3,
            )

        // Verify ranks are reversed
        databaseV3.verifyRanksInOrder(
            listOf(
                FakeCommunalItemRank(0),
                FakeCommunalItemRank(2),
                FakeCommunalItemRank(7),
                FakeCommunalItemRank(9),
            )
        )
    }

    private fun SupportSQLiteDatabase.insertWidgetsV1(widgets: List<FakeCommunalWidgetItemV1>) {
        widgets.forEach { widget ->
            execSQL(
                "INSERT INTO communal_widget_table(widget_id, component_name, item_id) " +
                    "VALUES(${widget.widgetId}, '${widget.componentName}', ${widget.itemId})"
            )
        }
    }

    private fun SupportSQLiteDatabase.verifyWidgetsV1(widgets: List<FakeCommunalWidgetItemV1>) {
        val cursor = query("SELECT * FROM communal_widget_table")
        assertThat(cursor.moveToFirst()).isTrue()

        widgets.forEach { widget ->
            assertThat(cursor.getInt(cursor.getColumnIndex("widget_id"))).isEqualTo(widget.widgetId)
            assertThat(cursor.getString(cursor.getColumnIndex("component_name")))
                .isEqualTo(widget.componentName)
            assertThat(cursor.getInt(cursor.getColumnIndex("item_id"))).isEqualTo(widget.itemId)

            cursor.moveToNext()
        }

        // Verify there is no more columns
        assertThat(cursor.isAfterLast).isTrue()
    }

    private fun SupportSQLiteDatabase.verifyWidgetsV2(widgets: List<FakeCommunalWidgetItemV2>) {
        val cursor = query("SELECT * FROM communal_widget_table")
        assertThat(cursor.moveToFirst()).isTrue()

        widgets.forEach { widget ->
            assertThat(cursor.getInt(cursor.getColumnIndex("widget_id"))).isEqualTo(widget.widgetId)
            assertThat(cursor.getString(cursor.getColumnIndex("component_name")))
                .isEqualTo(widget.componentName)
            assertThat(cursor.getInt(cursor.getColumnIndex("item_id"))).isEqualTo(widget.itemId)
            assertThat(cursor.getInt(cursor.getColumnIndex("user_serial_number")))
                .isEqualTo(widget.userSerialNumber)

            cursor.moveToNext()
        }

        // Verify there is no more columns
        assertThat(cursor.isAfterLast).isTrue()
    }

    private fun SupportSQLiteDatabase.insertRanks(ranks: List<FakeCommunalItemRank>) {
        ranks.forEach { rank ->
            execSQL("INSERT INTO communal_item_rank_table(rank) VALUES(${rank.rank})")
        }
    }

    private fun SupportSQLiteDatabase.verifyRanksInOrder(ranks: List<FakeCommunalItemRank>) {
        val cursor = query("SELECT * FROM communal_item_rank_table ORDER BY uid")
        assertThat(cursor.moveToFirst()).isTrue()

        ranks.forEach { rank ->
            assertThat(cursor.getInt(cursor.getColumnIndex("rank"))).isEqualTo(rank.rank)
            cursor.moveToNext()
        }

        // Verify there is no more columns
        assertThat(cursor.isAfterLast).isTrue()
    }

    /**
     * Returns the expected data after migration from V1 to V2, which is simply that the new user
     * serial number field is now set to [CommunalWidgetItem.USER_SERIAL_NUMBER_UNDEFINED].
     */
    private fun FakeCommunalWidgetItemV1.getV2(): FakeCommunalWidgetItemV2 {
        return FakeCommunalWidgetItemV2(
            widgetId,
            componentName,
            itemId,
            CommunalWidgetItem.USER_SERIAL_NUMBER_UNDEFINED,
        )
    }

    private data class FakeCommunalWidgetItemV1(
        val widgetId: Int,
        val componentName: String,
        val itemId: Int,
    )

    private data class FakeCommunalWidgetItemV2(
        val widgetId: Int,
        val componentName: String,
        val itemId: Int,
        val userSerialNumber: Int,
    )

    private data class FakeCommunalItemRank(
        val rank: Int,
    )

    companion object {
        private const val DATABASE_NAME = "communal_db"
    }
}
