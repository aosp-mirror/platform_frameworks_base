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

package com.android.settingslib.datastore

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith

/** Tests of [BackupRestoreFileStorage]. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreFileStorageTest {
    private val application: Application = getApplicationContext()

    @Test
    fun dataDirCompat() {
        val expected =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                application.dataDir
            } else {
                File(application.applicationInfo.dataDir)
            }
        assertThat(application.dataDirCompat).isEqualTo(expected)
    }

    @Test
    fun backupFile() {
        assertThat(FileStorage("path").backupFile.toString())
            .startsWith(application.dataDirCompat.toString())
    }

    @Test
    fun restoreFile() {
        FileStorage("path").apply { assertThat(restoreFile).isEqualTo(backupFile) }
    }

    @Test
    fun checkFilePaths() {
        FileStorage("path").checkFilePaths()
    }

    @Test
    fun checkFilePaths_emptyFilePath() {
        assertFailsWith(IllegalArgumentException::class) { FileStorage("").checkFilePaths() }
    }

    @Test
    fun checkFilePaths_absoluteFilePath() {
        assertFailsWith(IllegalArgumentException::class) {
            FileStorage("${File.separatorChar}file").checkFilePaths()
        }
    }

    @Test
    fun checkFilePaths_backupFile() {
        assertFailsWith(IllegalArgumentException::class) {
            FileStorage("path", fileForBackup = File("path")).checkFilePaths()
        }
    }

    @Test
    fun checkFilePaths_restoreFile() {
        assertFailsWith(IllegalArgumentException::class) {
            FileStorage("path", fileForRestore = File("path")).checkFilePaths()
        }
    }

    @Test
    fun createBackupRestoreEntities() {
        assertThat(FileStorage("path").createBackupRestoreEntities()).isEmpty()
    }

    private class FileStorage(
        filePath: String,
        val fileForBackup: File? = null,
        val fileForRestore: File? = null,
    ) : BackupRestoreFileStorage(getApplicationContext(), filePath) {
        override val name = "storage"

        override val backupFile: File
            get() = fileForBackup ?: super.backupFile

        override val restoreFile: File
            get() = fileForRestore ?: super.restoreFile
    }
}
