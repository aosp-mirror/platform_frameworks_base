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

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataInputStream
import android.app.backup.BackupDataOutput
import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.backup.CommunalBackupUtilsTest.Companion.represents
import com.android.systemui.communal.data.backup.CommunalBackupUtilsTest.FakeWidgetMetadata
import com.android.systemui.communal.data.db.CommunalDatabase
import com.android.systemui.communal.data.db.CommunalWidgetDao
import com.android.systemui.communal.proto.toCommunalHubState
import com.android.systemui.lifecycle.InstantTaskExecutorRule
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalBackupHelperTest : SysuiTestCase() {
    @JvmField @Rule val instantTaskExecutor = InstantTaskExecutorRule()

    private lateinit var database: CommunalDatabase
    private lateinit var dao: CommunalWidgetDao
    private lateinit var backupUtils: CommunalBackupUtils

    // Temporary file used for storing backed-up data.
    private lateinit var backupDataFile: File

    private lateinit var underTest: CommunalBackupHelper

    @Before
    fun setup() {
        database =
            Room.inMemoryDatabaseBuilder(context, CommunalDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        CommunalDatabase.setInstance(database)

        dao = database.communalWidgetDao()
        backupUtils = CommunalBackupUtils(context)

        backupDataFile = File(context.cacheDir, "backup_data_file")

        underTest = CommunalBackupHelper(UserHandle.SYSTEM, backupUtils)
    }

    @After
    fun teardown() {
        backupDataFile.delete()
        database.close()
    }

    @Test
    @EnableFlags(Flags.FLAG_COMMUNAL_HUB)
    fun backupAndRestoreCommunalHub() {
        val expectedWidgets = setUpDatabase()

        underTest.performBackup(oldState = null, data = getBackupDataOutput(), newState = null)
        underTest.restoreEntity(getBackupDataInputStream())

        // Verify restored state matches backed-up state
        val restoredState = backupUtils.readBytesFromDisk().toCommunalHubState()
        val restoredWidgets = restoredState.widgets.toList()
        assertThat(restoredWidgets)
            .comparingElementsUsing(represents)
            .containsExactlyElementsIn(expectedWidgets)
    }

    @Test
    @DisableFlags(Flags.FLAG_COMMUNAL_HUB)
    fun backup_skippedWhenCommunalDisabled() {
        setUpDatabase()

        underTest.performBackup(oldState = null, data = getBackupDataOutput(), newState = null)

        // Verify nothing written to the backup
        assertThat(backupDataFile.length()).isEqualTo(0)
    }

    @Test
    @EnableFlags(Flags.FLAG_COMMUNAL_HUB)
    fun backup_skippedForNonSystemUser() {
        setUpDatabase()

        val helper = CommunalBackupHelper(UserHandle.CURRENT, backupUtils)
        helper.performBackup(oldState = null, data = getBackupDataOutput(), newState = null)

        // Verify nothing written to the backup
        assertThat(backupDataFile.length()).isEqualTo(0)
    }

    private fun setUpDatabase(): List<FakeWidgetMetadata> {
        return listOf(
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
            .onEach { dao.addWidget(it.widgetId, it.componentName, it.rank, it.userSerialNumber) }
    }

    private fun getBackupDataInputStream(): BackupDataInputStream {
        val input = BackupDataInput(FileInputStream(backupDataFile).fd).apply { readNextHeader() }

        // Construct BackupDataInputStream using reflection because its constructor is package
        // private
        val inputStream = BackupDataInputStream::class.constructors.first().call(input)

        // Set key
        with(inputStream.javaClass.getDeclaredField("key")) {
            isAccessible = true
            set(inputStream, input.key)
        }

        // Set dataSize
        with(inputStream.javaClass.getDeclaredField("dataSize")) {
            isAccessible = true
            set(inputStream, input.dataSize)
        }

        return inputStream
    }

    private fun getBackupDataOutput(): BackupDataOutput {
        return BackupDataOutput(FileOutputStream(backupDataFile).fd)
    }
}
