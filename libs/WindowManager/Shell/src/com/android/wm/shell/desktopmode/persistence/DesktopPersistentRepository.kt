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

package com.android.wm.shell.desktopmode.persistence

import android.content.Context
import android.util.ArraySet
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.android.framework.protobuf.InvalidProtocolBufferException
import com.android.wm.shell.shared.annotations.ShellBackgroundThread
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * Persistent repository for storing desktop mode related data.
 *
 * The main constructor is public only for testing purposes.
 */
class DesktopPersistentRepository(
    private val dataStore: DataStore<DesktopPersistentRepositories>,
) {
    constructor(
        context: Context,
        @ShellBackgroundThread bgCoroutineScope: CoroutineScope,
    ) : this(
        DataStoreFactory.create(
            serializer = DesktopPersistentRepositoriesSerializer,
            produceFile = { context.dataStoreFile(DESKTOP_REPOSITORIES_DATASTORE_FILE) },
            scope = bgCoroutineScope))

    /** Provides `dataStore.data` flow and handles exceptions thrown during collection */
    private val dataStoreFlow: Flow<DesktopPersistentRepositories> =
        dataStore.data.catch { exception ->
            // dataStore.data throws an IOException when an error is encountered when reading data
            if (exception is IOException) {
                Log.e(
                    TAG,
                    "Error in reading desktop mode related data from datastore, data is " +
                        "stored in a file named $DESKTOP_REPOSITORIES_DATASTORE_FILE",
                    exception)
            } else {
                throw exception
            }
        }

    /**
     * Reads and returns the [DesktopRepositoryState] proto object from the DataStore for a user. If
     * the DataStore is empty or there's an error reading, it returns the default value of Proto.
     */
    private suspend fun getDesktopRepositoryState(
        userId: Int = DEFAULT_USER_ID
    ): DesktopRepositoryState =
        try {
            dataStoreFlow
                .first()
                .desktopRepoByUserMap
                .getOrDefault(userId, DesktopRepositoryState.getDefaultInstance())
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read from datastore", e)
            DesktopRepositoryState.getDefaultInstance()
        }

    /**
     * Reads the [Desktop] of a desktop filtering by the [userId] and [desktopId]. Executes the
     * [callback] using the [mainCoroutineScope].
     */
    suspend fun readDesktop(
        userId: Int = DEFAULT_USER_ID,
        desktopId: Int = DEFAULT_DESKTOP_ID,
    ): Desktop =
        try {
            val repository = getDesktopRepositoryState(userId)
            repository.getDesktopOrThrow(desktopId)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to get desktop info from persistent repository", e)
            Desktop.getDefaultInstance()
        }

    /** Adds or updates a desktop stored in the datastore */
    suspend fun addOrUpdateDesktop(
        userId: Int = DEFAULT_USER_ID,
        desktopId: Int = 0,
        visibleTasks: ArraySet<Int> = ArraySet(),
        minimizedTasks: ArraySet<Int> = ArraySet(),
        freeformTasksInZOrder: ArrayList<Int> = ArrayList(),
    ) {
        // TODO: b/367609270 - Improve the API to support multi-user
        try {
            dataStore.updateData { desktopPersistentRepositories: DesktopPersistentRepositories ->
                val currentRepository =
                    desktopPersistentRepositories.getDesktopRepoByUserOrDefault(
                        userId, DesktopRepositoryState.getDefaultInstance())
                val desktop =
                    getDesktop(currentRepository, desktopId)
                        .toBuilder()
                        .updateTaskStates(visibleTasks, minimizedTasks)
                        .updateZOrder(freeformTasksInZOrder)

                desktopPersistentRepositories
                    .toBuilder()
                    .putDesktopRepoByUser(
                        userId,
                        currentRepository
                            .toBuilder()
                            .putDesktop(desktopId, desktop.build())
                            .build())
                    .build()
            }
        } catch (exception: IOException) {
            Log.e(
                TAG,
                "Error in updating desktop mode related data, data is " +
                    "stored in a file named $DESKTOP_REPOSITORIES_DATASTORE_FILE",
                exception)
        }
    }

    private fun getDesktop(currentRepository: DesktopRepositoryState, desktopId: Int): Desktop =
        // If there are no desktops set up, create one on the default display
        currentRepository.getDesktopOrDefault(
            desktopId,
            Desktop.newBuilder().setDesktopId(desktopId).setDisplayId(DEFAULT_DISPLAY).build())

    companion object {
        private const val TAG = "DesktopPersistenceRepo"
        private const val DESKTOP_REPOSITORIES_DATASTORE_FILE = "desktop_persistent_repositories.pb"

        private const val DEFAULT_USER_ID = 1000
        private const val DEFAULT_DESKTOP_ID = 0

        object DesktopPersistentRepositoriesSerializer : Serializer<DesktopPersistentRepositories> {

            override val defaultValue: DesktopPersistentRepositories =
                DesktopPersistentRepositories.getDefaultInstance()

            override suspend fun readFrom(input: InputStream): DesktopPersistentRepositories =
                try {
                    DesktopPersistentRepositories.parseFrom(input)
                } catch (exception: InvalidProtocolBufferException) {
                    throw CorruptionException("Cannot read proto.", exception)
                }

            override suspend fun writeTo(t: DesktopPersistentRepositories, output: OutputStream) =
                t.writeTo(output)
        }

        private fun Desktop.Builder.updateTaskStates(
            visibleTasks: ArraySet<Int>,
            minimizedTasks: ArraySet<Int>
        ): Desktop.Builder {
            clearTasksByTaskId()
            putAllTasksByTaskId(
                visibleTasks.associateWith {
                    createDesktopTask(it, state = DesktopTaskState.VISIBLE)
                })
            putAllTasksByTaskId(
                minimizedTasks.associateWith {
                    createDesktopTask(it, state = DesktopTaskState.MINIMIZED)
                })
            return this
        }

        private fun Desktop.Builder.updateZOrder(
            freeformTasksInZOrder: ArrayList<Int>
        ): Desktop.Builder {
            clearZOrderedTasks()
            addAllZOrderedTasks(freeformTasksInZOrder)
            return this
        }

        private fun createDesktopTask(
            taskId: Int,
            state: DesktopTaskState = DesktopTaskState.VISIBLE
        ): DesktopTask =
            DesktopTask.newBuilder().setTaskId(taskId).setDesktopTaskState(state).build()
    }
}
