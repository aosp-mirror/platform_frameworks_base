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

package com.android.statementservice.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.android.statementservice.domain.worker.CollectV1Worker
import com.android.statementservice.domain.worker.SingleV1RequestWorker

/**
 * Receiver for V1 API. Separated so that the receiver permission can be declared for only the
 * v1 and v2 permissions individually, exactly matching the intended usage.
 */
class DomainVerificationReceiverV1 : BaseDomainVerificationReceiver() {

    companion object {
        private const val ENABLE_V1 = true
        private const val PACKAGE_WORK_PREFIX_V1 = "package_request_v1-"
    }

    override val tag = DomainVerificationReceiverV1::class.java.simpleName

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION ->
                scheduleUnlockedV1(context, intent)
            else -> debugLog { "Received invalid broadcast: $intent" }
        }
    }

    private fun scheduleUnlockedV1(context: Context, intent: Intent) {
        if (!ENABLE_V1) {
            return
        }

        val verificationId =
            intent.getIntExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID, -1)
        val hosts =
            (intent.getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS) ?: return)
                .split(" ")
        val packageName =
            intent.getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME)
                ?: return

        debugLog { "Attempting v1 verification for $packageName" }

        val workRequests = hosts.map {
            SingleV1RequestWorker.buildRequest(packageName, it) {
                setConstraints(networkConstraints)
            }
        }

        WorkManager.getInstance(context)
            .beginUniqueWork(
                "$PACKAGE_WORK_PREFIX_V1$packageName",
                ExistingWorkPolicy.REPLACE,
                workRequests
            )
            .then(CollectV1Worker.buildRequest(verificationId, packageName))
            .enqueue()
    }
}
