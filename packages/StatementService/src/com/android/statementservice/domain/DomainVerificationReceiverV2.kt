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
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationRequest
import android.os.UserManager
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.android.statementservice.domain.worker.SingleV2RequestWorker
import com.android.statementservice.utils.component1
import com.android.statementservice.utils.component2
import com.android.statementservice.utils.component3

import java.time.Duration

/**
 * Handles [DomainVerificationRequest]s from the system, which indicates a package on the device
 * has domains which require verification against a server side assetlinks.json file, allowing the
 * app to resolve web [Intent]s.
 *
 * This will delegate to v1 or v2 depending on the received broadcast and which components are
 * enabled. See [DomainVerificationManager] for the full API.
 */
open class DomainVerificationReceiverV2 : BaseDomainVerificationReceiver() {

    companion object {

        private const val ENABLE_V2 = true

        /**
         * Toggle to always re-verify packages that this receiver is notified of. This means on
         * every package change, even previously successful requests are re-sent. Generally only
         * for debugging.
         */
        @Suppress("SimplifyBooleanWithConstants")
        private const val ALWAYS_VERIFY = false || DEBUG

        private const val PACKAGE_WORK_PREFIX_V2 = "package_request_v2-"
    }

    override val tag = DomainVerificationReceiverV2::class.java.simpleName

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DOMAINS_NEED_VERIFICATION -> {
                // If the user isn't unlocked yet, the request will be ignored, as WorkManager
                // cannot schedule workers when the user data directories are encrypted.
                if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == true) {
                    scheduleUnlockedV2(context, intent)
                }
            }
            else -> debugLog { "Received invalid broadcast: $intent" }
        }
    }

    private fun scheduleUnlockedV2(context: Context, intent: Intent) {
        if (!ENABLE_V2) {
            return
        }

        val manager = context.getSystemService(DomainVerificationManager::class.java) ?: return
        val workManager = WorkManager.getInstance(context)

        val request = intent.getParcelableExtra<DomainVerificationRequest>(
            DomainVerificationManager.EXTRA_VERIFICATION_REQUEST
        ) ?: return

        debugLog { "Attempting v2 verification for ${request.packageNames}" }

        request.packageNames.forEach { packageName ->
            val (domainSetId, _, hostToStateMap) = manager.getDomainVerificationInfo(packageName)
                ?: return@forEach

            val workRequests = hostToStateMap
                .filterValues {
                    // TODO(b/159952358): Should we support re-query? There's no good way to
                    //  signal to an AOSP implementation from an entity's website about when
                    //  to re-query, unless it's just done on each update.
                    // AOSP implementation does not support re-query
                    ALWAYS_VERIFY || VerifyStatus.shouldRetry(it)
                }
                .map { (host, _) ->
                    SingleV2RequestWorker.buildRequest(domainSetId, packageName, host) {
                        setConstraints(networkConstraints)
                        setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofHours(1))
                    }
                }

            if (workRequests.isNotEmpty()) {
                workManager.beginUniqueWork(
                    "$PACKAGE_WORK_PREFIX_V2$packageName",
                    ExistingWorkPolicy.REPLACE, workRequests
                )
                    .enqueue()
            }
        }
    }
}
