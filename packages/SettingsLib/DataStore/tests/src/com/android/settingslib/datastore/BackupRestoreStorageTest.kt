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

import android.app.backup.BackupAgentHelper
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataInputStream
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import android.os.ParcelFileDescriptor.MODE_APPEND
import android.os.ParcelFileDescriptor.MODE_READ_ONLY
import android.os.ParcelFileDescriptor.MODE_WRITE_ONLY
import androidx.collection.MutableScatterMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify

/** Tests of [BackupRestoreStorage]. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreStorageTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val entity1 = Entity("key1", "value1".toByteArray())
    private val entity1NoOpCodec = Entity("key1", "value1".toByteArray(), BackupNoOpCodec())
    private val entity2 = Entity("key2", "value2".toByteArray(), BackupZipCodec.BEST_SPEED)

    @Test
    fun performBackup_disabled() {
        val storage = spy(TestStorage().apply { enabled = false })
        val unused = performBackup { data, newState -> storage.performBackup(null, data, newState) }
        verify(storage, never()).createBackupRestoreEntities()
        assertThat(storage.entities).isNull()
        assertThat(storage.entityStates.size).isEqualTo(0)
    }

    @Test
    fun performBackup_enabled() {
        val storage = spy(TestStorage())
        val unused = performBackup { data, newState -> storage.performBackup(null, data, newState) }
        verify(storage).createBackupRestoreEntities()
        assertThat(storage.entities).isNull()
        assertThat(storage.entityStates.size).isEqualTo(0)
    }

    @Test
    fun performBackup_entityBackupWithException() {
        val entity =
            mock<BackupRestoreEntity> {
                on { key } doReturn ""
                on { backup(any(), any()) } doThrow IllegalStateException()
            }
        val storage = TestStorage(entity, entity1)

        val (_, stateFile) =
            performBackup { data, newState -> storage.performBackup(null, data, newState) }

        assertThat(storage.readEntityStates(stateFile)).apply {
            hasSize(1)
            containsKey(entity1.key)
        }
    }

    @Test
    fun performBackup_update_unchanged() {
        performBackupTest({}) { entityStates, newEntityStates ->
            assertThat(entityStates).isEqualTo(newEntityStates)
        }
    }

    @Test
    fun performBackup_intact() {
        performBackupTest({ entity1.backupResult = EntityBackupResult.INTACT }) {
            entityStates,
            newEntityStates ->
            assertThat(entityStates).isEqualTo(newEntityStates)
        }
    }

    @Test
    fun performBackup_delete() {
        performBackupTest({ entity1.backupResult = EntityBackupResult.DELETE }) { _, newEntityStates
            ->
            assertThat(newEntityStates.size).isEqualTo(1)
            assertThat(newEntityStates).containsKey(entity2.key)
        }
    }

    private fun performBackupTest(
        update: () -> Unit,
        verification: (Map<String, Long>, Map<String, Long>) -> Unit,
    ) {
        val storage = TestStorage(entity1, entity2)
        val (_, stateFile) =
            performBackup { data, newState -> storage.performBackup(null, data, newState) }

        val entityStates = storage.readEntityStates(stateFile)
        assertThat(entityStates).apply {
            hasSize(2)
            containsKey(entity1.key)
            containsKey(entity2.key)
        }

        update.invoke()
        val (_, newStateFile) =
            performBackup { data, newState ->
                stateFile.toParcelFileDescriptor(MODE_READ_ONLY).use {
                    storage.performBackup(it, data, newState)
                }
            }
        verification.invoke(entityStates, storage.readEntityStates(newStateFile))
    }

    @Test
    fun restoreEntity_disabled() {
        val storage = spy(TestStorage().apply { enabled = false })
        temporaryFolder.newFile().toParcelFileDescriptor(MODE_READ_ONLY).use {
            storage.restoreEntity(it.toBackupDataInputStream())
        }
        verify(storage, never()).createBackupRestoreEntities()
        assertThat(storage.entities).isNull()
        assertThat(storage.entityStates.size).isEqualTo(0)
    }

    @Test
    fun restoreEntity_entityNotFound() {
        val storage = TestStorage()
        temporaryFolder.newFile().toParcelFileDescriptor(MODE_READ_ONLY).use {
            val backupDataInputStream = it.toBackupDataInputStream()
            backupDataInputStream.setKey("")
            storage.restoreEntity(backupDataInputStream)
        }
    }

    @Test
    fun restoreEntity_exception() {
        val storage = TestStorage(entity1)
        temporaryFolder.newFile().toParcelFileDescriptor(MODE_READ_ONLY).use {
            val backupDataInputStream = it.toBackupDataInputStream()
            backupDataInputStream.setKey(entity1.key)
            storage.restoreEntity(backupDataInputStream)
        }
    }

    @Test
    fun restoreEntity_codecChanged() {
        assertThat(entity1.codec()).isNotEqualTo(entity1NoOpCodec.codec())
        backupAndRestore(entity1) { _, data ->
            TestStorage(entity1NoOpCodec).apply { restoreEntity(data) }
        }
        assertThat(entity1.data).isEqualTo(entity1NoOpCodec.restoredData)
    }

    @Test
    fun restoreEntity() {
        val random = Random.Default
        fun test(codec: BackupCodec, size: Int) {
            val entity = Entity("key", random.nextBytes(size), codec)
            backupAndRestore(entity)
            entity.verifyRestoredData()
        }
        for (codec in allCodecs()) {
            // test small data to ensure correctness
            for (size in 0 until 100) test(codec, size)
            repeat(10) { test(codec, random.nextInt(100, MAX_DATA_SIZE)) }
        }
    }

    @Test
    fun readEntityStates_eof_exception() {
        val storage = TestStorage()
        val entityStates = MutableScatterMap<String, Long>()
        entityStates.put("", 0) // add an item to verify that exiting elements are clear
        temporaryFolder.newFile().toParcelFileDescriptor(MODE_READ_ONLY).use {
            storage.readEntityStates(it, entityStates)
        }
        assertThat(entityStates.size).isEqualTo(0)
    }

    @Test
    fun readEntityStates_other_exception() {
        val storage = TestStorage()
        val entityStates = MutableScatterMap<String, Long>()
        entityStates.put("", 0) // add an item to verify that exiting elements are clear
        temporaryFolder.newFile().toParcelFileDescriptor(MODE_READ_ONLY).apply {
            close() // cause exception when read state file
            storage.readEntityStates(this, entityStates)
        }
        assertThat(entityStates.size).isEqualTo(0)
    }

    @Test
    fun readEntityStates_unknownVersion() {
        val storage = TestStorage()
        val stateFile = temporaryFolder.newFile()
        stateFile.toParcelFileDescriptor(MODE_WRITE_ONLY or MODE_APPEND).use {
            DataOutputStream(FileOutputStream(it.fileDescriptor))
                .writeByte(BackupRestoreStorage.STATE_VERSION + 1)
        }
        val entityStates = MutableScatterMap<String, Long>()
        entityStates.put("", 0) // add an item to verify that exiting elements are clear
        stateFile.toParcelFileDescriptor(MODE_READ_ONLY).use {
            storage.readEntityStates(it, entityStates)
        }
        assertThat(entityStates.size).isEqualTo(0)
    }

    @Test
    fun writeNewStateDescription() {
        val storage = spy(TestStorage())
        // use read only mode to trigger exception when write state file
        temporaryFolder.newFile().toParcelFileDescriptor(MODE_READ_ONLY).use {
            storage.writeNewStateDescription(it)
        }
        verify(storage).onRestoreFinished()
    }

    @Test
    fun writeNewStateDescription_restoreDisabled() {
        val storage = spy(TestStorage().apply { enabled = false })
        temporaryFolder.newFile().toParcelFileDescriptor(MODE_WRITE_ONLY or MODE_APPEND).use {
            storage.writeNewStateDescription(it)
        }
        verify(storage, never()).onRestoreFinished()
    }

    @Test
    fun backupAndRestore() {
        val storage = spy(TestStorage(entity1, entity2))
        val backupAgentHelper = BackupAgentHelper()
        backupAgentHelper.addHelper(storage.name, storage)

        // backup
        val (dataFile, stateFile) =
            performBackup { data, newState -> backupAgentHelper.onBackup(null, data, newState) }
        storage.verifyFieldsArePurged()

        // verify state
        val entityStates = MutableScatterMap<String, Long>()
        entityStates[""] = 1
        storage.readEntityStates(null, entityStates)
        assertThat(entityStates.size).isEqualTo(0)
        stateFile.toParcelFileDescriptor(MODE_READ_ONLY).use {
            storage.readEntityStates(it, entityStates)
        }
        assertThat(entityStates.asMap()).apply {
            hasSize(2)
            containsKey(entity1.key)
            containsKey(entity2.key)
        }
        reset(storage)

        // restore
        val newStateFile = temporaryFolder.newFile()
        dataFile.toParcelFileDescriptor(MODE_READ_ONLY).use { dataPfd ->
            newStateFile.toParcelFileDescriptor(MODE_WRITE_ONLY or MODE_APPEND).use {
                backupAgentHelper.onRestore(dataPfd.toBackupDataInput(), 0, it)
            }
        }
        verify(storage).onRestoreFinished()
        storage.verifyFieldsArePurged()

        // ShadowBackupDataOutput does not write data to file, so restore is bypassed
        if (!isRobolectric()) {
            entity1.verifyRestoredData()
            entity2.verifyRestoredData()
            assertThat(entityStates.asMap()).isEqualTo(storage.readEntityStates(newStateFile))
        }
    }

    private fun backupAndRestore(
        entity: BackupRestoreEntity,
        restoreEntity: (TestStorage, BackupDataInputStream) -> TestStorage = { storage, data ->
            storage.restoreEntity(data)
            storage
        },
    ) {
        val storage = TestStorage(entity)
        val entityKey = argumentCaptor<String>()
        val entitySize = argumentCaptor<Int>()
        val entityData = argumentCaptor<ByteArray>()
        val data = mock<BackupDataOutput>()

        val stateFile = temporaryFolder.newFile()
        stateFile.toParcelFileDescriptor(MODE_WRITE_ONLY or MODE_APPEND).use {
            storage.performBackup(null, data, it)
        }
        val entityStates = MutableScatterMap<String, Long>()
        stateFile.toParcelFileDescriptor(MODE_READ_ONLY).use {
            storage.readEntityStates(it, entityStates)
        }
        assertThat(entityStates.size).isEqualTo(1)

        verify(data).writeEntityHeader(entityKey.capture(), entitySize.capture())
        verify(data).writeEntityData(entityData.capture(), entitySize.capture())
        assertThat(entityKey.allValues).isEqualTo(listOf(entity.key))
        assertThat(entityData.allValues).hasSize(1)
        val payload = entityData.firstValue
        assertThat(entitySize.allValues).isEqualTo(listOf(payload.size, payload.size))

        val dataFile = temporaryFolder.newFile()
        dataFile.toParcelFileDescriptor(MODE_WRITE_ONLY or MODE_APPEND).use {
            FileOutputStream(it.fileDescriptor).write(payload)
        }

        newBackupDataInputStream(entity.key, payload).apply {
            restoreEntity.invoke(storage, this).also {
                assertThat(it.entityStates).isEqualTo(entityStates)
            }
        }
    }

    fun performBackup(backup: (BackupDataOutput, ParcelFileDescriptor) -> Unit): Pair<File, File> {
        val dataFile = temporaryFolder.newFile()
        val stateFile = temporaryFolder.newFile()
        dataFile.toParcelFileDescriptor(MODE_WRITE_ONLY or MODE_APPEND).use { dataPfd ->
            stateFile.toParcelFileDescriptor(MODE_WRITE_ONLY or MODE_APPEND).use {
                backup.invoke(dataPfd.toBackupDataOutput(), it)
            }
        }
        return dataFile to stateFile
    }

    private fun BackupRestoreStorage.verifyFieldsArePurged() {
        assertThat(entities).isNull()
        assertThat(entityStates.size).isEqualTo(0)
        assertThat(entityStates.capacity).isEqualTo(0)
    }

    private fun BackupRestoreStorage.readEntityStates(stateFile: File): Map<String, Long> {
        val entityStates = MutableScatterMap<String, Long>()
        stateFile.toParcelFileDescriptor(MODE_READ_ONLY).use { readEntityStates(it, entityStates) }
        return entityStates.asMap()
    }

    private fun File.toParcelFileDescriptor(mode: Int) = ParcelFileDescriptor.open(this, mode)

    private fun ParcelFileDescriptor.toBackupDataOutput() = fileDescriptor.toBackupDataOutput()

    private fun ParcelFileDescriptor.toBackupDataInputStream(): BackupDataInputStream =
        BackupDataInputStream::class.java.newInstance(toBackupDataInput())

    private fun ParcelFileDescriptor.toBackupDataInput() = fileDescriptor.toBackupDataInput()

    private fun FileDescriptor.toBackupDataOutput(): BackupDataOutput =
        BackupDataOutput::class.java.newInstance(this)

    private fun FileDescriptor.toBackupDataInput(): BackupDataInput =
        BackupDataInput::class.java.newInstance(this)
}

private open class TestStorage(vararg val backupRestoreEntities: BackupRestoreEntity) :
    ObservableBackupRestoreStorage() {
    var enabled: Boolean? = null

    override val name
        get() = "TestBackup"

    override fun createBackupRestoreEntities() = backupRestoreEntities.toList()

    override fun enableBackup(backupContext: BackupContext) =
        enabled ?: super.enableBackup(backupContext)

    override fun enableRestore() = enabled ?: super.enableRestore()
}

private class Entity(
    override val key: String,
    val data: ByteArray,
    private val codec: BackupCodec? = null,
) : BackupRestoreEntity {
    var restoredData: ByteArray? = null
    var backupResult = EntityBackupResult.UPDATE

    override fun codec() = codec ?: super.codec()

    override fun backup(
        backupContext: BackupContext,
        outputStream: OutputStream,
    ): EntityBackupResult {
        outputStream.write(data)
        return backupResult
    }

    override fun restore(restoreContext: RestoreContext, inputStream: InputStream) {
        restoredData = inputStream.readBytes()
        inputStream.close()
    }

    fun verifyRestoredData() = assertThat(restoredData).isEqualTo(data)
}
