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
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.android.statementservice.utils.AndroidUtils
import com.android.statementservice.utils.StatementUtils
import kotlinx.coroutines.coroutineScope
import java.util.UUID

class SingleV2RequestWorker(appContext: Context, params: WorkerParameters) :
    BaseRequestWorker(appContext, params) {

    companion object {
        private const val DOMAIN_SET_ID_KEY = "domainSetId"
        private const val PACKAGE_NAME_KEY = "packageName"
        private const val HOST_KEY = "host"

        fun buildRequest(
            domainSetId: UUID,
            packageName: String,
            host: String,
            block: OneTimeWorkRequest.Builder.() -> Unit = {}
        ) = OneTimeWorkRequestBuilder<SingleV2RequestWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DOMAIN_SET_ID_KEY, domainSetId.toString())
                    .putString(PACKAGE_NAME_KEY, packageName)
                    .putString(HOST_KEY, host)
                    .build()
            )
            .apply(block)
            .build()
    }

    override suspend fun doWork() = coroutineScope {
        if (!AndroidUtils.isReceiverV2Enabled(appContext)) {
            return@coroutineScope Result.success()
        }

        val domainSetId = params.inputData.getString(DOMAIN_SET_ID_KEY)!!.let(UUID::fromString)
        val packageName = params.inputData.getString(PACKAGE_NAME_KEY)!!
        val host = params.inputData.getString(HOST_KEY)!!

        val (result, status, statement) = verifier.verifyHost(host, packageName, params.network)

        verificationManager.setDomainVerificationStatus(domainSetId, setOf(host), status.value)
        val groups = statement?.dynamicAppLinkComponents.orEmpty().map {
            StatementUtils.createUriRelativeFilterGroup(it)
        }
        updateUriRelativeFilterGroups(packageName, mapOf(host to groups))

        result
    }
}
