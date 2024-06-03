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

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File

private fun defaultVerbose() = Build.TYPE == "eng"

/**
 * [SharedPreferences] based storage.
 *
 * The backup and restore is handled by [BackupRestoreFileArchiver] to achieve forward-compatibility
 * just like `PersistentBackupAgentHelper`.
 *
 * Simple file based backup and restore is not safe, which incurs multi-thread file writes in
 * SharedPreferences file. Additionally, SharedPreferences has in-memory state, so reload is needed.
 * However, there is no public reload API on SharedPreferences and listeners are not notified in
 * current private implementation. As such, an intermediate SharedPreferences file is introduced for
 * backup and restore.
 *
 * Note that existing entries in the SharedPreferences will NOT be deleted before restore.
 *
 * @param context Context to get SharedPreferences
 * @param name Name of the SharedPreferences
 * @param mode Operating mode, see [Context.getSharedPreferences]
 * @param verbose Verbose logging on key/value pairs during backup/restore. Enable for dev only!
 * @param filter Filter of key/value pairs for backup and restore.
 */
open class SharedPreferencesStorage
@JvmOverloads
constructor(
    context: Context,
    override val name: String,
    @get:VisibleForTesting internal val sharedPreferences: SharedPreferences,
    private val codec: BackupCodec? = null,
    private val verbose: Boolean = defaultVerbose(),
    private val filter: (String, Any?) -> Boolean = { _, _ -> true },
) :
    BackupRestoreFileStorage(context, context.getSharedPreferencesFilePath(name)),
    KeyedObservable<String> by KeyedDataObservable() {

    @JvmOverloads
    constructor(
        context: Context,
        name: String,
        mode: Int,
        codec: BackupCodec? = null,
        verbose: Boolean = defaultVerbose(),
        filter: (String, Any?) -> Boolean = { _, _ -> true },
    ) : this(context, name, context.getSharedPreferences(name, mode), codec, verbose, filter)

    /** Name of the intermediate SharedPreferences. */
    @VisibleForTesting
    internal val intermediateName: String
        get() = "_br_$name"

    @Suppress("DEPRECATION")
    private val intermediateSharedPreferences: SharedPreferences
        get() {
            // use MODE_MULTI_PROCESS to ensure a reload
            return context.getSharedPreferences(intermediateName, Context.MODE_MULTI_PROCESS)
        }

    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) {
                notifyChange(key, DataChangeReason.UPDATE)
            } else {
                // On Android >= R, SharedPreferences.Editor.clear() will trigger this case
                notifyChange(DataChangeReason.DELETE)
            }
        }

    init {
        // listener is weakly referenced, so unregister is optional
        sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
    }

    override fun defaultCodec() = codec ?: super.defaultCodec()

    override val backupFile: File
        // use a different file to avoid multi-thread file write
        get() = context.getSharedPreferencesFile(intermediateName)

    override fun prepareBackup(file: File) {
        val editor =
            mergeSharedPreferences(intermediateSharedPreferences, sharedPreferences.all, "Backup")
        // commit to ensure data is write to disk synchronously
        if (!editor.commit()) {
            Log.w(LOG_TAG, "[$name] fail to commit")
        }
    }

    override fun onBackupFinished(file: File) {
        intermediateSharedPreferences.delete(intermediateName)
    }

    override fun onRestoreFinished(file: File) {
        // Unregister listener to avoid notify observer during restore because there might be
        // dependency between keys. BackupRestoreStorageManager.onRestoreFinished will notify
        // observers consistently once restore finished.
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        val restored = intermediateSharedPreferences
        val editor = mergeSharedPreferences(sharedPreferences, restored.all, "Restore")
        editor.commit() // commit to avoid race condition
        sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        // clear the intermediate SharedPreferences
        restored.delete(intermediateName)
    }

    private fun SharedPreferences.delete(name: String) {
        if (deleteSharedPreferences(name)) {
            Log.i(LOG_TAG, "SharedPreferences $name deleted")
        } else {
            edit().clear().commit() // commit to avoid potential race condition
        }
    }

    private fun deleteSharedPreferences(name: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(name)
        } else {
            false
        }

    @VisibleForTesting
    internal open fun mergeSharedPreferences(
        sharedPreferences: SharedPreferences,
        entries: Map<String, Any?>,
        operation: String,
    ): SharedPreferences.Editor {
        val editor = sharedPreferences.edit()
        for ((key, value) in entries) {
            if (!filter.invoke(key, value)) {
                if (verbose) Log.v(LOG_TAG, "[$name] $operation skips $key=$value")
                continue
            }
            when (value) {
                is Boolean -> {
                    editor.putBoolean(key, value)
                    if (verbose) Log.v(LOG_TAG, "[$name] $operation Boolean $key=$value")
                }
                is Float -> {
                    editor.putFloat(key, value)
                    if (verbose) Log.v(LOG_TAG, "[$name] $operation Float $key=$value")
                }
                is Int -> {
                    editor.putInt(key, value)
                    if (verbose) Log.v(LOG_TAG, "[$name] $operation Int $key=$value")
                }
                is Long -> {
                    editor.putLong(key, value)
                    if (verbose) Log.v(LOG_TAG, "[$name] $operation Long $key=$value")
                }
                is String -> {
                    editor.putString(key, value)
                    if (verbose) Log.v(LOG_TAG, "[$name] $operation String $key=$value")
                }
                is Set<*> -> {
                    val nonString = value.firstOrNull { it !is String }
                    if (nonString != null) {
                        Log.e(
                            LOG_TAG,
                            "[$name] $operation StringSet $key=$value" +
                                " but non string found: $nonString (${nonString.javaClass})",
                        )
                    } else {
                        @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>)
                        if (verbose) Log.v(LOG_TAG, "[$name] $operation StringSet $key=$value")
                    }
                }
                else -> {
                    Log.e(
                        LOG_TAG,
                        "[$name] $operation $key=$value, unknown type: ${value?.javaClass}"
                    )
                }
            }
        }
        return editor
    }

    companion object {
        private fun Context.getSharedPreferencesFilePath(name: String): String {
            val file = getSharedPreferencesFile(name)
            return file.relativeTo(dataDirCompat).toString()
        }

        /** Returns the absolute path of shared preferences file. */
        @JvmStatic
        fun Context.getSharedPreferencesFile(name: String): File {
            // ContextImpl.getSharedPreferencesPath is private
            return File(getSharedPreferencesDir(), "$name.xml")
        }

        private fun Context.getSharedPreferencesDir() = File(dataDirCompat, "shared_prefs")
    }
}
