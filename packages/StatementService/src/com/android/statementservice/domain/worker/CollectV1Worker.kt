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
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.android.statementservice.utils.AndroidUtils
import kotlinx.coroutines.coroutineScope

class CollectV1Worker(appContext: Context, params: WorkerParameters) :
    BaseRequestWorker(appContext, params) {

    companion object {
        private val TAG = CollectV1Worker::class.java.simpleName
        private const val DEBUG = false

        private const val VERIFICATION_ID_KEY = "verificationId"
        private const val PACKAGE_NAME_KEY = "packageName"

        fun buildRequest(verificationId: Int, packageName: String) =
            OneTimeWorkRequestBuilder<CollectV1Worker>()
                .setInputData(
                    Data.Builder()
                        .putInt(VERIFICATION_ID_KEY, verificationId)
                        .apply {
                            if (DEBUG) {
                                putString(PACKAGE_NAME_KEY, packageName)
                            }
                        }
                        .build()
                )
                .build()
    }

    override suspend fun doWork() = coroutineScope {
        if (!AndroidUtils.isReceiverV1Enabled(appContext)) {
            return@coroutineScope Result.success()
        }

        val inputData = params.inputData
        val verificationId = inputData.getInt(VERIFICATION_ID_KEY, -1)
        val successfulHosts = mutableListOf<String>()
        val failedHosts = mutableListOf<String>()
        inputData.keyValueMap.entries.forEach { (key, _) ->
            when {
                key.startsWith(SingleV1RequestWorker.HOST_SUCCESS_PREFIX) ->
                    successfulHosts += key.removePrefix(SingleV1RequestWorker.HOST_SUCCESS_PREFIX)
                key.startsWith(SingleV1RequestWorker.HOST_FAILURE_PREFIX) ->
                    failedHosts += key.removePrefix(SingleV1RequestWorker.HOST_FAILURE_PREFIX)
            }
        }

        if (DEBUG) {
            val packageName = inputData.getString(PACKAGE_NAME_KEY)
            Log.d(
                TAG, "Domain verification v1 request for $packageName: " +
                        "success = $successfulHosts, failed = $failedHosts"
            )
        }

        val resultCode = if (failedHosts.isEmpty()) {
            PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS
        } else {
            PackageManager.INTENT_FILTER_VERIFICATION_FAILURE
        }

        appContext.packageManager.verifyIntentFilter(verificationId, resultCode, failedHosts)

        Result.success()
    }
}
