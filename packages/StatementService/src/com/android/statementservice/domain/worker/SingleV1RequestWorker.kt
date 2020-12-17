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

package com.android.statementservice.domain.worker

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.android.statementservice.utils.AndroidUtils
import kotlinx.coroutines.coroutineScope

class SingleV1RequestWorker(appContext: Context, params: WorkerParameters) :
    BaseRequestWorker(appContext, params) {

    companion object {
        private val TAG = SingleV1RequestWorker::class.java.simpleName
        private const val DEBUG = false

        private const val PACKAGE_NAME_KEY = "packageName"
        private const val HOST_KEY = "host"
        const val HOST_SUCCESS_PREFIX = "hostSuccess:"
        const val HOST_FAILURE_PREFIX = "hostFailure:"

        fun buildRequest(
            packageName: String,
            host: String,
            block: OneTimeWorkRequest.Builder.() -> Unit = {}
        ) = OneTimeWorkRequestBuilder<SingleV1RequestWorker>()
            .setInputData(
                Data.Builder()
                    .putString(PACKAGE_NAME_KEY, packageName)
                    .putString(HOST_KEY, host)
                    .build()
            )
            .apply(block)
            .build()
    }

    override suspend fun doWork() = coroutineScope {
        if (!AndroidUtils.isReceiverV1Enabled(appContext)) {
            return@coroutineScope Result.success()
        }

        val packageName = params.inputData.getString(PACKAGE_NAME_KEY)!!
        val host = params.inputData.getString(HOST_KEY)!!

        val (result, status) = verifier.verifyHost(host, packageName, params.network)

        if (DEBUG) {
            Log.d(
                TAG, "Domain verification v1 request for $packageName: " +
                        "host = $host, status = $status"
            )
        }

        // Coerce failure results into success so that final collection task gets a chance to run
        when (result) {
            is Result.Success -> Result.success(
                Data.Builder()
                    .putInt("$HOST_SUCCESS_PREFIX$host", status.value)
                    .build()
            )
            is Result.Failure -> Result.success(
                Data.Builder()
                    .putInt("$HOST_FAILURE_PREFIX$host", status.value)
                    .build()
            )
            else -> result
        }
    }
}
