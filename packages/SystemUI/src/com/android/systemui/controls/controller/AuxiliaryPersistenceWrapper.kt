/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.controller

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.backup.BackupHelper
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Class to track the auxiliary persistence of controls.
 *
 * This file is a copy of the `controls_favorites.xml` file restored from a back up. It is used to
 * keep track of controls that were restored but its corresponding app has not been installed yet.
 */
class AuxiliaryPersistenceWrapper @VisibleForTesting internal constructor(
    wrapper: ControlsFavoritePersistenceWrapper
) {

    constructor(
        file: File,
        executor: Executor
    ): this(ControlsFavoritePersistenceWrapper(file, executor))

    companion object {
        const val AUXILIARY_FILE_NAME = "aux_controls_favorites.xml"
    }

    private var persistenceWrapper: ControlsFavoritePersistenceWrapper = wrapper

    /**
     * Access the current list of favorites as tracked by the auxiliary file
     */
    var favorites: List<StructureInfo> = emptyList()
        private set

    init {
        initialize()
    }

    /**
     * Change the file that this class is tracking.
     *
     * This will reset [favorites].
     */
    fun changeFile(file: File) {
        persistenceWrapper.changeFileAndBackupManager(file, null)
        initialize()
    }

    /**
     * Initialize the list of favorites to the content of the auxiliary file. If the file does not
     * exist, it will be initialized to an empty list.
     */
    fun initialize() {
        favorites = if (persistenceWrapper.fileExists) {
            persistenceWrapper.readFavorites()
        } else {
            emptyList()
        }
    }

    /**
     * Gets the list of favorite controls as persisted in the auxiliary file for a given component.
     *
     * When the favorites for that application are returned, they will be removed from the
     * auxiliary file immediately, so they won't be retrieved again.
     * @param componentName the name of the service that provided the controls
     * @return a list of structures with favorites
     */
    fun getCachedFavoritesAndRemoveFor(componentName: ComponentName): List<StructureInfo> {
        if (!persistenceWrapper.fileExists) {
            return emptyList()
        }
        val (comp, noComp) = favorites.partition { it.componentName == componentName }
        return comp.also {
            favorites = noComp
            if (favorites.isNotEmpty()) {
                persistenceWrapper.storeFavorites(noComp)
            } else {
                persistenceWrapper.deleteFile()
            }
        }
    }

    /**
     * [JobService] to delete the auxiliary file after a week.
     */
    class DeletionJobService : JobService() {
        companion object {
            @VisibleForTesting
            internal val DELETE_FILE_JOB_ID = 1000
            private val WEEK_IN_MILLIS = TimeUnit.DAYS.toMillis(7)
            fun getJobForContext(context: Context): JobInfo {
                val jobId = DELETE_FILE_JOB_ID + context.userId
                val componentName = ComponentName(context, DeletionJobService::class.java)
                return JobInfo.Builder(jobId, componentName)
                    .setMinimumLatency(WEEK_IN_MILLIS)
                    .setPersisted(true)
                    .build()
            }
        }

        @VisibleForTesting
        fun attachContext(context: Context) {
            attachBaseContext(context)
        }

        override fun onStartJob(params: JobParameters): Boolean {
            synchronized(BackupHelper.controlsDataLock) {
                baseContext.deleteFile(AUXILIARY_FILE_NAME)
            }
            return false
        }

        override fun onStopJob(params: JobParameters?): Boolean {
            return true // reschedule and try again if the job was stopped without completing
        }
    }
}