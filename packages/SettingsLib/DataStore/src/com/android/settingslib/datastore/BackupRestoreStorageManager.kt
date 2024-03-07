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
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.ConcurrentHashMap

/** Manager of [BackupRestoreStorage]. */
class BackupRestoreStorageManager private constructor(private val application: Application) {
    private val storages = ConcurrentHashMap<String, BackupRestoreStorage>()

    private val executor = MoreExecutors.directExecutor()

    private val observer = Observer { reason -> notifyBackupManager(null, reason) }

    private val keyedObserver =
        KeyedObserver<Any?> { key, reason -> notifyBackupManager(key, reason) }

    private fun notifyBackupManager(key: Any?, reason: Int) {
        // prefer not triggering backup immediately after restore
        if (reason == ChangeReason.RESTORE) return
        // TODO: log storage name
        Log.d(LOG_TAG, "Notify BackupManager data changed for change: key=$key")
        BackupManager.dataChanged(application.packageName)
    }

    /**
     * Adds all the registered [BackupRestoreStorage] as the helpers of given [BackupAgentHelper].
     *
     * All [BackupRestoreFileStorage]s will be wrapped as a single [BackupRestoreFileArchiver].
     *
     * @see BackupAgentHelper.addHelper
     */
    fun addBackupAgentHelpers(backupAgentHelper: BackupAgentHelper) {
        val fileStorages = mutableListOf<BackupRestoreFileStorage>()
        for ((keyPrefix, storage) in storages) {
            if (storage is BackupRestoreFileStorage) {
                fileStorages.add(storage)
            } else {
                backupAgentHelper.addHelper(keyPrefix, storage)
            }
        }
        // Always add file archiver even fileStorages is empty to handle forward compatibility
        val fileArchiver = BackupRestoreFileArchiver(application, fileStorages)
        backupAgentHelper.addHelper(fileArchiver.name, fileArchiver)
    }

    /**
     * Callback when restore finished.
     *
     * The observers of the storages will be notified.
     */
    fun onRestoreFinished() {
        for (storage in storages.values) {
            storage.notifyRestoreFinished()
        }
    }

    private fun BackupRestoreStorage.notifyRestoreFinished() {
        when (this) {
            is KeyedObservable<*> -> notifyChange(ChangeReason.RESTORE)
            is Observable -> notifyChange(ChangeReason.RESTORE)
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
        val oldStorage = storages.put(name, storage)
        if (oldStorage != null) {
            throw IllegalStateException(
                "Storage name '$name' conflicts:\n\told: $oldStorage\n\tnew: $storage"
            )
        }
        storage.addObserver()
    }

    private fun BackupRestoreStorage.addObserver() {
        when (this) {
            is KeyedObservable<*> -> addObserver(keyedObserver, executor)
            is Observable -> addObserver(observer, executor)
            else ->
                throw IllegalArgumentException(
                    "$this does not implement either KeyedObservable or Observable"
                )
        }
    }

    /** Removes all the storages. */
    fun removeAll() {
        for ((name, _) in storages) remove(name)
    }

    /** Removes storage with given name. */
    fun remove(name: String): BackupRestoreStorage? {
        val storage = storages.remove(name)
        storage?.removeObserver()
        return storage
    }

    private fun BackupRestoreStorage.removeObserver() {
        when (this) {
            is KeyedObservable<*> -> removeObserver(keyedObserver)
            is Observable -> removeObserver(observer)
        }
    }

    /** Returns storage with given name. */
    fun get(name: String): BackupRestoreStorage? = storages[name]

    /** Returns storage with given name, exception is raised if not found. */
    fun getOrThrow(name: String): BackupRestoreStorage = storages[name]!!

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
