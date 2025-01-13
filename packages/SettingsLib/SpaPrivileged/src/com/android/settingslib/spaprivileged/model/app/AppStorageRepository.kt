/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.model.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.android.settingslib.spaprivileged.framework.common.BytesFormatter
import com.android.settingslib.spaprivileged.framework.common.storageStatsManager

/** A repository interface for accessing and formatting app storage information. */
interface AppStorageRepository {
    /**
     * Formats the size of an application into a human-readable string.
     *
     * This function retrieves the total size of the application, including APK file and its
     * associated data.
     *
     * This function takes an [ApplicationInfo] object as input and returns a formatted string
     * representing the size of the application. The size is formatted in units like kB, MB, GB,
     * etc.
     *
     * @param app The [ApplicationInfo] object representing the application.
     * @return A formatted string representing the size of the application.
     */
    fun formatSize(app: ApplicationInfo): String

    /**
     * Formats the size about an application into a human-readable string.
     *
     * @param sizeBytes The size in bytes to format.
     * @return A formatted string representing the size about application.
     */
    fun formatSizeBytes(sizeBytes: Long): String

    /**
     * Calculates the size of an application in bytes.
     *
     * This function retrieves the total size of the application, including APK file and its
     * associated data.
     *
     * @param app The [ApplicationInfo] object representing the application.
     * @return The total size of the application in bytes, or null if the size could not be
     *   determined.
     */
    fun calculateSizeBytes(app: ApplicationInfo): Long?
}

class AppStorageRepositoryImpl(context: Context) : AppStorageRepository {
    private val storageStatsManager = context.storageStatsManager
    private val bytesFormatter = BytesFormatter(context)

    override fun formatSize(app: ApplicationInfo): String {
        val sizeBytes = calculateSizeBytes(app)
        return if (sizeBytes != null) formatSizeBytes(sizeBytes) else ""
    }

    override fun formatSizeBytes(sizeBytes: Long): String =
        bytesFormatter.format(sizeBytes, BytesFormatter.UseCase.FileSize)

    override fun calculateSizeBytes(app: ApplicationInfo): Long? =
        try {
            val stats =
                storageStatsManager.queryStatsForPackage(
                    app.storageUuid,
                    app.packageName,
                    app.userHandle,
                )
            stats.codeBytes + stats.dataBytes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query stats", e)
            null
        }

    companion object {
        private const val TAG = "AppStorageRepository"
    }
}
