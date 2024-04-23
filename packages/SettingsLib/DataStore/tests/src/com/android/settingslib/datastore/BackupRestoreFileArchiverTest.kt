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
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Tests of [BackupRestoreFileArchiver]. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreFileArchiverTest {
    private val random = Random.Default
    private val application: Application = getApplicationContext()
    @get:Rule val temporaryFolder = TemporaryFolder(application.dataDirCompat)

    @Test
    fun createBackupRestoreEntities() {
        val fileStorages = mutableListOf<BackupRestoreFileStorage>()
        for (count in 0 until 3) {
            val fileArchiver = BackupRestoreFileArchiver(application, fileStorages, "")
            fileArchiver.createBackupRestoreEntities().apply {
                assertThat(this).hasSize(fileStorages.size)
                for (index in 0 until count) {
                    assertThat(get(index).key).isEqualTo(fileStorages[index].storageFilePath)
                }
            }
            fileStorages.add(FileStorage("storage", "path$count"))
        }
    }

    @Test
    fun wrapBackupOutputStream() {
        val fileArchiver = BackupRestoreFileArchiver(application, listOf(), "")
        val outputStream = ByteArrayOutputStream()
        assertThat(fileArchiver.wrapBackupOutputStream(BackupZipCodec.BEST_SPEED, outputStream))
            .isSameInstanceAs(outputStream)
    }

    @Test
    fun wrapRestoreInputStream() {
        val fileArchiver = BackupRestoreFileArchiver(application, listOf(), "")
        val inputStream = ByteArrayInputStream(byteArrayOf())
        assertThat(fileArchiver.wrapRestoreInputStream(BackupZipCodec.BEST_SPEED, inputStream))
            .isSameInstanceAs(inputStream)
    }

    @Test
    fun restoreEntity_disabled() {
        val file = temporaryFolder.newFile()
        val key = file.name
        val fileStorage = FileStorage("fs", key, restoreEnabled = false)

        BackupRestoreFileArchiver(application, listOf(fileStorage), "archiver").apply {
            restoreEntity(newBackupDataInputStream(key, byteArrayOf()))
            assertThat(entityStates.asMap()).isEmpty()
        }
    }

    @Test
    fun restoreEntity_raiseIOException() {
        val key = "key"
        val fileStorage = FileStorage("fs", key)
        BackupRestoreFileArchiver(application, listOf(fileStorage), "archiver").apply {
            restoreEntity(newBackupDataInputStream(key, byteArrayOf(), IOException()))
            assertThat(entityStates.asMap()).isEmpty()
        }
    }

    @Test
    fun restoreEntity_onRestoreFinished_raiseException() {
        val key = "key"
        val fileStorage = FileStorage("fs", key, restoreException = IllegalStateException())
        BackupRestoreFileArchiver(application, listOf(fileStorage), "archiver").apply {
            val data = random.nextBytes(random.nextInt(10))
            val outputStream = ByteArrayOutputStream()
            fileStorage.wrapBackupOutputStream(fileStorage.defaultCodec(), outputStream).use {
                it.write(data)
            }
            val payload = outputStream.toByteArray()
            restoreEntity(newBackupDataInputStream(key, payload))
            assertThat(entityStates.asMap()).isEmpty()
        }
    }

    @Test
    fun restoreEntity_forwardCompatibility() {
        val key = "key"
        val fileStorage = FileStorage("fs", key)
        for (codec in allCodecs()) {
            BackupRestoreFileArchiver(application, listOf(), "archiver").apply {
                val data = random.nextBytes(random.nextInt(MAX_DATA_SIZE))
                val outputStream = ByteArrayOutputStream()
                fileStorage.wrapBackupOutputStream(codec, outputStream).use { it.write(data) }
                val payload = outputStream.toByteArray()

                restoreEntity(newBackupDataInputStream(key, payload))

                assertThat(entityStates.asMap()).apply {
                    hasSize(1)
                    containsKey(key)
                }
                assertThat(fileStorage.restoreFile.readBytes()).isEqualTo(data)
            }
        }
    }

    @Test
    fun restoreEntity() {
        val folder = File(application.dataDirCompat, "backup")
        val file = File(folder, "file")
        val key = "${folder.name}${File.separator}${file.name}"
        fun test(codec: BackupCodec, size: Int) {
            val fileStorage = FileStorage("fs", key, if (size % 2 == 0) codec else null)
            val data = random.nextBytes(size)
            val outputStream = ByteArrayOutputStream()
            fileStorage.wrapBackupOutputStream(codec, outputStream).use { it.write(data) }
            val payload = outputStream.toByteArray()

            val fileArchiver =
                BackupRestoreFileArchiver(application, listOf(fileStorage), "archiver")
            fileArchiver.restoreEntity(newBackupDataInputStream(key, payload))

            assertThat(fileArchiver.entityStates.asMap()).apply {
                hasSize(1)
                containsKey(key)
            }
            assertThat(file.readBytes()).isEqualTo(data)
        }

        for (codec in allCodecs()) {
            for (size in 0 until 100) test(codec, size)
            repeat(10) { test(codec, random.nextInt(100, MAX_DATA_SIZE)) }
        }
    }

    @Test
    fun onRestoreFinished() {
        val fileStorage = mock<BackupRestoreFileStorage>()
        val fileArchiver = BackupRestoreFileArchiver(application, listOf(fileStorage), "")

        fileArchiver.onRestoreFinished()

        verify(fileStorage).onRestoreFinished()
    }

    @Test
    fun toBackupRestoreEntity_backup_disabled() {
        val context = BackupContext(mock())
        val fileStorage =
            mock<BackupRestoreFileStorage> { on { enableBackup(context) } doReturn false }

        assertThat(fileStorage.toBackupRestoreEntity().backup(context, ByteArrayOutputStream()))
            .isEqualTo(EntityBackupResult.INTACT)

        verify(fileStorage, never()).prepareBackup(any())
    }

    @Test
    fun toBackupRestoreEntity_backup_fileNotExist() {
        val context = BackupContext(mock())
        val file = File("NotExist")
        val fileStorage =
            mock<BackupRestoreFileStorage> {
                on { enableBackup(context) } doReturn true
                on { backupFile } doReturn file
            }

        assertThat(fileStorage.toBackupRestoreEntity().backup(context, ByteArrayOutputStream()))
            .isEqualTo(EntityBackupResult.DELETE)

        verify(fileStorage).prepareBackup(file)
        verify(fileStorage, never()).defaultCodec()
    }

    @Test
    fun toBackupRestoreEntity_backup() {
        val context = BackupContext(mock())
        val file = temporaryFolder.newFile()

        fun test(codec: BackupCodec, size: Int) {
            val data = random.nextBytes(size)
            file.outputStream().use { it.write(data) }

            val outputStream = ByteArrayOutputStream()
            val fileStorage =
                mock<BackupRestoreFileStorage> {
                    on { enableBackup(context) } doReturn true
                    on { backupFile } doReturn file
                    on { defaultCodec() } doReturn codec
                    on { wrapBackupOutputStream(any(), any()) }.thenCallRealMethod()
                    on { wrapRestoreInputStream(any(), any()) }.thenCallRealMethod()
                    on { prepareBackup(any()) }.thenCallRealMethod()
                    on { onBackupFinished(any()) }.thenCallRealMethod()
                }

            assertThat(fileStorage.toBackupRestoreEntity().backup(context, outputStream))
                .isEqualTo(EntityBackupResult.UPDATE)

            verify(fileStorage).prepareBackup(file)
            verify(fileStorage).onBackupFinished(file)

            val decodedData =
                fileStorage
                    .wrapRestoreInputStream(codec, ByteArrayInputStream(outputStream.toByteArray()))
                    .readBytes()
            assertThat(decodedData).isEqualTo(data)
        }

        for (codec in allCodecs()) {
            // test small data to ensure correctness
            for (size in 0 until 100) test(codec, size)
            repeat(10) { test(codec, random.nextInt(100, MAX_DATA_SIZE)) }
        }
    }

    @Test
    fun toBackupRestoreEntity_restore() {
        val restoreContext = RestoreContext("storage")
        val inputStream =
            object : InputStream() {
                override fun read() = throw IllegalStateException()

                override fun read(b: ByteArray, off: Int, len: Int) = throw IllegalStateException()
            }
        FileStorage("storage", "path").toBackupRestoreEntity().restore(restoreContext, inputStream)
    }

    private open class FileStorage(
        override val name: String,
        filePath: String,
        private val codec: BackupCodec? = null,
        private val restoreEnabled: Boolean? = null,
        private val restoreException: Exception? = null,
    ) : BackupRestoreFileStorage(getApplicationContext(), filePath) {

        override fun defaultCodec() = codec ?: super.defaultCodec()

        override fun enableRestore() = restoreEnabled ?: super.enableRestore()

        override fun onRestoreFinished(file: File) {
            super.onRestoreFinished(file)
            if (restoreException != null) throw restoreException
        }
    }
}
