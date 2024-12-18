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

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.db.CommunalDatabase
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.nano.CommunalHubState
import com.android.systemui.lifecycle.InstantTaskExecutorRule
import com.google.common.truth.Correspondence
import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.nio.charset.Charset
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalBackupUtilsTest : SysuiTestCase() {
    @JvmField @Rule val instantTaskExecutor = InstantTaskExecutorRule()

    private lateinit var database: CommunalDatabase
    private lateinit var dao: CommunalWidgetDao
    private lateinit var underTest: CommunalBackupUtils

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(context, CommunalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        CommunalDatabase.setInstance(database)

        dao = database.communalWidgetDao()
        underTest = CommunalBackupUtils(context)
    }

    @After
    fun teardown() {
        database.close()
        underTest.clear()
    }

    @Test
    fun getCommunalHubState_returnsExpectedWidgets() {
        // Set up database
        val expectedWidgets =
            listOf(
                FakeWidgetMetadata(
                    widgetId = 11,
                    componentName = "com.android.fakePackage1/fakeWidget1",
                    rank = 0,
                    userSerialNumber = 0,
                ),
                FakeWidgetMetadata(
                    widgetId = 12,
                    componentName = "com.android.fakePackage2/fakeWidget2",
                    rank = 1,
                    userSerialNumber = 0,
                ),
                FakeWidgetMetadata(
                    widgetId = 13,
                    componentName = "com.android.fakePackage3/fakeWidget3",
                    rank = 2,
                    userSerialNumber = 10,
                ),
            )
        expectedWidgets.forEach {
            dao.addWidget(it.widgetId, it.componentName, it.rank, it.userSerialNumber)
        }

        // Get communal hub state
        val state = underTest.getCommunalHubState()
        val actualWidgets = state.widgets.toList()

        // Verify the state contains widgets as expected
        assertThat(actualWidgets)
            .comparingElementsUsing(represents)
            .containsExactlyElementsIn(expectedWidgets)
    }

    @Test
    fun write_existingContentIsOverwritten() {
        // Write old data
        val dataToWrite = "I am old data. Erase me."
        underTest.writeBytesToDisk(dataToWrite.toByteArray(Charset.defaultCharset()))

        // Verify old data written
        var dataRead = underTest.readBytesFromDisk().toString(Charset.defaultCharset())
        assertThat(dataRead).isEqualTo(dataToWrite)

        // Write new data
        val newDataToWrite = "I am new data."
        underTest.writeBytesToDisk(newDataToWrite.toByteArray(Charset.defaultCharset()))

        // Verify new data overwrites old
        dataRead = underTest.readBytesFromDisk().toString(Charset.defaultCharset())
        assertThat(dataRead).isEqualTo(newDataToWrite)
    }

    @Test(expected = FileNotFoundException::class)
    fun clear_returnsTrueWhenFileDeleted() {
        // Write bytes to disk
        underTest.writeBytesToDisk(byteArrayOf(1, 2, 3))

        assertThat(underTest.clear()).isTrue()

        // Verify a read after that throws a FileNotFoundException
        underTest.readBytesFromDisk()
    }

    @Test
    fun clear_returnsFalseWhenFileDoesNotExist() {
        assertThat(underTest.clear()).isFalse()
    }

    @Test
    fun fileExists() {
        assertThat(underTest.fileExists()).isFalse()

        underTest.writeBytesToDisk(byteArrayOf(1, 2, 3))
        assertThat(underTest.fileExists()).isTrue()

        underTest.clear()
        assertThat(underTest.fileExists()).isFalse()
    }

    data class FakeWidgetMetadata(
        val widgetId: Int,
        val componentName: String,
        val rank: Int,
        val userSerialNumber: Int,
    )

    companion object {
        /**
         * A comparator for whether a [CommunalHubState.CommunalWidgetItem] represents a
         * [FakeWidgetMetadata]
         */
        val represents: Correspondence<CommunalHubState.CommunalWidgetItem, FakeWidgetMetadata> =
            Correspondence.from(
                { actual, expected ->
                    actual?.widgetId == expected?.widgetId &&
                        actual?.componentName == expected?.componentName &&
                        actual?.rank == expected?.rank &&
                        actual?.userSerialNumber == expected?.userSerialNumber
                },
                "represents",
            )
    }
}
