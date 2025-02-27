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

package com.android.statementservice.domain.worker

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope

class GroupUpdateV1Worker(appContext: Context, params: WorkerParameters) :
    BaseRequestWorker(appContext, params) {

    companion object {

        private const val PACKAGE_NAME_KEY = "packageName"

        fun buildRequest(packageName: String) = OneTimeWorkRequestBuilder<GroupUpdateV1Worker>()
            .setInputData(
                Data.Builder()
                    .putString(PACKAGE_NAME_KEY, packageName)
                    .build()
            )
            .build()
    }

    override suspend fun doWork() = coroutineScope {
        val packageName = params.inputData.getString(PACKAGE_NAME_KEY)!!
        updateUriRelativeFilterGroups(packageName)
        Result.success()
    }

    private fun updateUriRelativeFilterGroups(packageName: String) {
        val groupUpdates = database.getDomainGroups(packageName)
        updateUriRelativeFilterGroups(
            packageName,
            groupUpdates.associateBy({it.domain}, {it.groups})
        )
        database.clear(packageName)
    }
}