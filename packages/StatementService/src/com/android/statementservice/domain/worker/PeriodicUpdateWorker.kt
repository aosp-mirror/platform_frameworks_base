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
import android.content.UriRelativeFilterGroup
import android.content.pm.verify.domain.DomainVerificationManager
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.android.statementservice.domain.VerifyStatus
import com.android.statementservice.utils.AndroidUtils
import com.android.statementservice.utils.StatementUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

abstract class PeriodicUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : BaseRequestWorker(appContext, params) {

    data class VerifyResult(
        val host: String,
        val status: VerifyStatus,
        val groups: List<UriRelativeFilterGroup>
    )

    protected suspend fun updateDomainVerificationStatus(verifyStatusFilter: (Int) -> Boolean):
            ListenableWorker.Result {
        return coroutineScope {
            if (!AndroidUtils.isReceiverV2Enabled(appContext)) {
                return@coroutineScope Result.success()
            }

            val packageNames = verificationManager.queryValidVerificationPackageNames()

            verifier.collectHosts(packageNames, verifyStatusFilter)
                .map { (domainSetId, packageName, hosts) ->
                    hosts.map { host ->
                        async {
                            if (isActive && !isStopped) {
                                val (_, status, statement) = verifier.verifyHost(
                                    host,
                                    packageName,
                                    params.network
                                )
                                val groups = statement?.dynamicAppLinkComponents.orEmpty().map {
                                    StatementUtils.createUriRelativeFilterGroup(it)
                                }
                                VerifyResult(host, status, groups)
                            } else {
                                // If the job gets cancelled, stop the remaining hosts, but continue the
                                // job to commit the results for hosts that were already requested.
                                null
                            }
                        }
                    }.awaitAll().filterNotNull().groupBy { it.status }
                        .forEach { (status, results) ->
                            val error = verificationManager.setDomainVerificationStatus(
                                domainSetId,
                                results.map { it.host }.toSet(),
                                status.value
                            )
                            if (error == DomainVerificationManager.STATUS_OK
                                && status == VerifyStatus.SUCCESS
                            ) {
                                updateUriRelativeFilterGroups(
                                    packageName,
                                    results.associateBy({ it.host }, { it.groups })
                                )
                            }
                        }
                }

            // Succeed regardless of results since this retry is best effort and not required
            Result.success()
        }
    }
}