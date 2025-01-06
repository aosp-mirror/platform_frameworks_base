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
import android.content.UriRelativeFilterGroup
import android.content.pm.verify.domain.DomainVerificationInfo
import android.content.pm.verify.domain.DomainVerificationManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.android.statementservice.database.DomainGroupsDatabase
import com.android.statementservice.domain.DomainVerifier

abstract class BaseRequestWorker(
    protected val appContext: Context,
    protected val params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    protected val database = DomainGroupsDatabase.getInstance(appContext).domainGroupsDao()

    protected val verificationManager =
        appContext.getSystemService(DomainVerificationManager::class.java)!!

    protected val verifier = DomainVerifier.getInstance(appContext)

    protected fun updateUriRelativeFilterGroups(packageName: String, domainGroupUpdates: Map<String, List<UriRelativeFilterGroup>>) {
        val verifiedDomains = verificationManager.getDomainVerificationInfo(packageName)?.hostToStateMap?.filterValues {
            it == DomainVerificationInfo.STATE_SUCCESS || it == DomainVerificationInfo.STATE_MODIFIABLE_VERIFIED
        }?.keys?.toList() ?: emptyList()
        val domainGroups = verificationManager.getUriRelativeFilterGroups(packageName, verifiedDomains)
        domainGroupUpdates.forEach { (domain, groups) -> domainGroups[domain] = groups }
        verificationManager.setUriRelativeFilterGroups(packageName, domainGroups)
    }
}
