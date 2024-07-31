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
import android.app.backup.BackupAgentHelper
import android.app.backup.BackupHelper
import android.app.backup.BackupManager
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowBackupManager

/** Tests of [BackupRestoreStorageManager]. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreStorageManagerTest {
    private val application: Application = getApplicationContext()
    private val manager = BackupRestoreStorageManager.getInstance(application)
    private val fileStorage = FileStorage("fileStorage")
    private val keyedStorage = KeyedStorage("keyedStorage")

    private val storage1 = mock<ObservableBackupRestoreStorage> { on { name } doReturn "1" }
    private val storage2 = mock<ObservableBackupRestoreStorage> { on { name } doReturn "1" }

    @After
    fun tearDown() {
        manager.removeAll()
        ShadowBackupManager.reset()
    }

    @Test
    fun getInstance() {
        assertThat(BackupRestoreStorageManager.getInstance(application)).isSameInstanceAs(manager)
    }

    @Test
    fun addBackupAgentHelpers() {
        val fs = FileStorage("fs")
        manager.add(keyedStorage, fileStorage, storage1, fs)
        val backupAgentHelper = DummyBackupAgentHelper()
        manager.addBackupAgentHelpers(backupAgentHelper)
        backupAgentHelper.backupHelpers.apply {
            assertThat(size).isEqualTo(3)
            assertThat(remove(keyedStorage.name)).isSameInstanceAs(keyedStorage)
            assertThat(remove(storage1.name)).isSameInstanceAs(storage1)
            val fileArchiver = entries.first().value as BackupRestoreFileArchiver
            assertThat(fileArchiver.fileStorages.toSet()).containsExactly(fs, fileStorage)
        }
    }

    @Test
    fun addBackupAgentHelpers_withoutFileStorage() {
        manager.add(keyedStorage, storage1)
        val backupAgentHelper = DummyBackupAgentHelper()
        manager.addBackupAgentHelpers(backupAgentHelper)
        backupAgentHelper.backupHelpers.apply {
            assertThat(size).isEqualTo(3)
            assertThat(remove(keyedStorage.name)).isSameInstanceAs(keyedStorage)
            assertThat(remove(storage1.name)).isSameInstanceAs(storage1)
            val fileArchiver = entries.first().value as BackupRestoreFileArchiver
            assertThat(fileArchiver.fileStorages).isEmpty()
        }
    }

    @Test
    fun add() {
        manager.add(keyedStorage, fileStorage, storage1)
        assertThat(manager.storageWrappers).apply {
            hasSize(3)
            containsKey(keyedStorage.name)
            containsKey(fileStorage.name)
            containsKey(storage1.name)
        }
    }

    @Test
    fun add_identicalName() {
        manager.add(storage1)
        assertFailsWith(IllegalStateException::class) { manager.add(storage1) }
        assertFailsWith(IllegalStateException::class) { manager.add(storage2) }
    }

    @Test
    fun add_nonObservable() {
        assertFailsWith(IllegalArgumentException::class) {
            manager.add(mock<BackupRestoreStorage>())
        }
    }

    @Test
    fun removeAll() {
        add()
        manager.removeAll()
        assertThat(manager.storageWrappers).isEmpty()
    }

    @Test
    fun remove() {
        manager.add(keyedStorage, fileStorage)
        assertThat(manager.remove(storage1.name)).isNull()
        assertThat(manager.remove(keyedStorage.name)).isSameInstanceAs(keyedStorage)
        assertThat(manager.remove(fileStorage.name)).isSameInstanceAs(fileStorage)
    }

    @Test
    fun get() {
        manager.add(keyedStorage, fileStorage)
        assertThat(manager.get(storage1.name)).isNull()
        assertThat(manager.get(keyedStorage.name)).isSameInstanceAs(keyedStorage)
        assertThat(manager.get(fileStorage.name)).isSameInstanceAs(fileStorage)
    }

    @Test
    fun getOrThrow() {
        manager.add(keyedStorage, fileStorage)
        assertFailsWith(NullPointerException::class) { manager.getOrThrow(storage1.name) }
        assertThat(manager.getOrThrow(keyedStorage.name)).isSameInstanceAs(keyedStorage)
        assertThat(manager.getOrThrow(fileStorage.name)).isSameInstanceAs(fileStorage)
    }

    @Test
    fun notifyRestoreFinished() {
        manager.add(keyedStorage, fileStorage)
        val keyedObserver = mock<KeyedObserver<String>>()
        val anyKeyObserver = mock<KeyedObserver<String?>>()
        val observer = mock<Observer>()
        val executor = directExecutor()
        keyedStorage.addObserver("key", keyedObserver, executor)
        keyedStorage.addObserver(anyKeyObserver, executor)
        fileStorage.addObserver(observer, executor)

        manager.onRestoreFinished()

        verify(keyedObserver).onKeyChanged("key", DataChangeReason.RESTORE)
        verify(anyKeyObserver).onKeyChanged(null, DataChangeReason.RESTORE)
        verify(observer).onChanged(fileStorage, DataChangeReason.RESTORE)
        if (isRobolectric()) {
            Shadows.shadowOf(BackupManager(application)).apply {
                assertThat(isDataChanged).isFalse()
                assertThat(dataChangedCount).isEqualTo(0)
            }
        }
    }

    @Test
    fun notifyBackupManager() {
        manager.add(keyedStorage, fileStorage)
        val keyedObserver = mock<KeyedObserver<String>>()
        val anyKeyObserver = mock<KeyedObserver<String?>>()
        val observer = mock<Observer>()
        val executor = directExecutor()
        keyedStorage.addObserver("key", keyedObserver, executor)
        keyedStorage.addObserver(anyKeyObserver, executor)
        fileStorage.addObserver(observer, executor)

        val backupManager =
            if (isRobolectric()) Shadows.shadowOf(BackupManager(application)) else null
        backupManager?.apply {
            assertThat(isDataChanged).isFalse()
            assertThat(dataChangedCount).isEqualTo(0)
        }

        fileStorage.notifyChange(DataChangeReason.UPDATE)
        verify(observer).onChanged(fileStorage, DataChangeReason.UPDATE)
        verify(keyedObserver, never()).onKeyChanged(any(), any())
        verify(anyKeyObserver, never()).onKeyChanged(any(), any())
        reset(observer)
        backupManager?.apply {
            assertThat(isDataChanged).isTrue()
            assertThat(dataChangedCount).isEqualTo(1)
        }

        keyedStorage.notifyChange("key", DataChangeReason.DELETE)
        verify(observer, never()).onChanged(any(), any())
        verify(keyedObserver).onKeyChanged("key", DataChangeReason.DELETE)
        verify(anyKeyObserver).onKeyChanged("key", DataChangeReason.DELETE)
        backupManager?.apply {
            assertThat(isDataChanged).isTrue()
            assertThat(dataChangedCount).isEqualTo(2)
        }
        reset(keyedObserver)

        // backup manager is not notified for restore event
        fileStorage.notifyChange(DataChangeReason.RESTORE)
        keyedStorage.notifyChange("key", DataChangeReason.RESTORE)
        verify(observer).onChanged(fileStorage, DataChangeReason.RESTORE)
        verify(keyedObserver).onKeyChanged("key", DataChangeReason.RESTORE)
        verify(anyKeyObserver).onKeyChanged("key", DataChangeReason.RESTORE)
        backupManager?.apply {
            assertThat(isDataChanged).isTrue()
            assertThat(dataChangedCount).isEqualTo(2)
        }
    }

    private class KeyedStorage(override val name: String) :
        BackupRestoreStorage(), KeyedObservable<String> by KeyedDataObservable() {

        override fun createBackupRestoreEntities(): List<BackupRestoreEntity> = listOf()
    }

    private class FileStorage(override val name: String) :
        BackupRestoreFileStorage(getApplicationContext(), "file"), ObservableDelegation {

        override val observableDelegate: Observable = DataObservable(this)
    }

    private class DummyBackupAgentHelper : BackupAgentHelper() {
        val backupHelpers = mutableMapOf<String, BackupHelper>()

        override fun addHelper(keyPrefix: String, helper: BackupHelper) {
            backupHelpers[keyPrefix] = helper
        }
    }
}
