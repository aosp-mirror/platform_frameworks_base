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
import android.app.backup.BackupManager
import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.ConcurrentHashMap

/** Manager of [BackupRestoreStorage]. */
class BackupRestoreStorageManager private constructor(private val application: Application) {
    @VisibleForTesting internal val storageWrappers = ConcurrentHashMap<String, StorageWrapper>()

    private val executor = MoreExecutors.directExecutor()

    /**
     * Adds all the registered [BackupRestoreStorage] as the helpers of given [BackupAgentHelper].
     *
     * All [BackupRestoreFileStorage]s will be wrapped as a single [BackupRestoreFileArchiver],
     * specify [fileArchiverName] to avoid key prefix conflict if needed.
     *
     * @param backupAgentHelper backup agent helper to add helpers
     * @param fileArchiverName key prefix of the [BackupRestoreFileArchiver], the value must not be
     *   changed in future
     * @see BackupAgentHelper.addHelper
     */
    @JvmOverloads
    fun addBackupAgentHelpers(
        backupAgentHelper: BackupAgentHelper,
        fileArchiverName: String = "file_archiver",
    ) {
        val fileStorages = mutableListOf<BackupRestoreFileStorage>()
        for ((keyPrefix, storageWrapper) in storageWrappers) {
            val storage = storageWrapper.storage
            if (storage is BackupRestoreFileStorage) {
                fileStorages.add(storage)
            } else {
                backupAgentHelper.addHelper(keyPrefix, storage)
            }
        }
        // Always add file archiver even fileStorages is empty to handle forward compatibility
        val fileArchiver = BackupRestoreFileArchiver(application, fileStorages, fileArchiverName)
        backupAgentHelper.addHelper(fileArchiver.name, fileArchiver)
    }

    /**
     * Callback when restore finished.
     *
     * The observers of the storages will be notified.
     */
    fun onRestoreFinished() {
        for (storageWrapper in storageWrappers.values) {
            storageWrapper.notifyRestoreFinished()
        }
    }

    /**
     * Adds a list of storages.
     *
     * The storage MUST implement [KeyedObservable] or [Observable].
     */
    fun add(vararg storages: BackupRestoreStorage) {
        for (storage in storages) add(storage)
    }

    /**
     * Adds a storage.
     *
     * The storage MUST implement [KeyedObservable] or [Observable].
     */
    fun add(storage: BackupRestoreStorage) {
        if (storage is BackupRestoreFileStorage) storage.checkFilePaths()
        val name = storage.name
        val oldStorage = storageWrappers.put(name, StorageWrapper(storage))?.storage
        if (oldStorage != null) {
            throw IllegalStateException(
                "Storage name '$name' conflicts:\n\told: $oldStorage\n\tnew: $storage"
            )
        }
    }

    /** Removes all the storages. */
    fun removeAll() {
        for ((name, _) in storageWrappers) remove(name)
    }

    /** Removes storage with given name. */
    fun remove(name: String): BackupRestoreStorage? {
        val storageWrapper = storageWrappers.remove(name)
        storageWrapper?.removeObserver()
        return storageWrapper?.storage
    }

    /** Returns storage with given name. */
    fun get(name: String): BackupRestoreStorage? = storageWrappers[name]?.storage

    /** Returns storage with given name, exception is raised if not found. */
    fun getOrThrow(name: String): BackupRestoreStorage = storageWrappers[name]!!.storage

    @VisibleForTesting
    internal inner class StorageWrapper(val storage: BackupRestoreStorage) :
        Observer, KeyedObserver<Any?> {
        init {
            when (storage) {
                is KeyedObservable<*> -> storage.addObserver(this, executor)
                is Observable -> storage.addObserver(this, executor)
                else ->
                    throw IllegalArgumentException(
                        "$this does not implement either KeyedObservable or Observable"
                    )
            }
        }

        override fun onChanged(reason: Int) = onKeyChanged(null, reason)

        override fun onKeyChanged(key: Any?, reason: Int) {
            notifyBackupManager(key, reason)
        }

        private fun notifyBackupManager(key: Any?, reason: Int) {
            val name = storage.name
            // prefer not triggering backup immediately after restore
            if (reason == DataChangeReason.RESTORE) {
                Log.d(
                    LOG_TAG,
                    "Notify BackupManager dataChanged ignored for restore: storage=$name key=$key"
                )
                return
            }
            Log.d(
                LOG_TAG,
                "Notify BackupManager dataChanged: storage=$name key=$key reason=$reason"
            )
            BackupManager(application).dataChanged()
        }

        fun removeObserver() {
            when (storage) {
                is KeyedObservable<*> -> storage.removeObserver(this)
                is Observable -> storage.removeObserver(this)
            }
        }

        fun notifyRestoreFinished() {
            when (storage) {
                is KeyedObservable<*> -> storage.notifyChange(DataChangeReason.RESTORE)
                is Observable -> storage.notifyChange(DataChangeReason.RESTORE)
            }
        }
    }

    companion object {
        @Volatile private var instance: BackupRestoreStorageManager? = null

        /** Returns the singleton of manager. */
        @JvmStatic
        fun getInstance(context: Context): BackupRestoreStorageManager {
            val result = instance
            if (result != null) return result
            synchronized(this) {
                if (instance == null) {
                    instance =
                        BackupRestoreStorageManager(context.applicationContext as Application)
                }
            }
            return instance!!
        }
    }
}
