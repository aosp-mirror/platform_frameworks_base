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

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.android.statementservice.domain.worker.RetryRequestWorker
import com.android.statementservice.domain.worker.UpdateVerifiedDomainsWorker
import java.time.Duration

object DomainVerificationUtils {

    private const val PERIODIC_SHORT_ID = "retry_short"
    private const val PERIODIC_SHORT_HOURS = 24L
    private const val PERIODIC_LONG_ID = "retry_long"
    private const val PERIODIC_LONG_HOURS = 72L
    private const val PERIODIC_UPDATE_ID = "update"
    private const val PERIODIC_UPDATE_HOURS = 720L

    private const val UPDATE_WORKER_ENABLED = false

    /**
     * In a majority of cases, the initial requests will be enough to verify domains, since they
     * are also restricted to [NetworkType.CONNECTED], but for cases where they aren't sufficient,
     * attempts are also made on a periodic basis.
     *
     * Once per 24 hours, a check of all packages is done with [NetworkType.CONNECTED]. To avoid
     * cases where a proxy or other unusual device configuration prevents [WorkManager] from
     * running, also schedule a 3 day task without constraints which will force the check to run.
     *
     * The actual logic may be skipped if a request was previously run successfully or there are no
     * more domains that need verifying.
     */
    fun schedulePeriodicCheckUnlocked(workManager: WorkManager) {
        workManager.apply {
            PeriodicWorkRequestBuilder<RetryRequestWorker>(Duration.ofHours(PERIODIC_SHORT_HOURS))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresDeviceIdle(true)
                        .build()
                )
                .build()
                .let {
                    enqueueUniquePeriodicWork(
                        PERIODIC_SHORT_ID,
                        ExistingPeriodicWorkPolicy.KEEP, it
                    )
                }
            PeriodicWorkRequestBuilder<RetryRequestWorker>(Duration.ofDays(PERIODIC_LONG_HOURS))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresDeviceIdle(true)
                        .build()
                )
                .build()
                .let {
                    enqueueUniquePeriodicWork(
                        PERIODIC_LONG_ID,
                        ExistingPeriodicWorkPolicy.KEEP, it
                    )
                }
        }
    }

    /**
     * Schedule a periodic worker to check for any updates to assetlink.json files for domains that
     * have already been verified.
     *
     * Due to the potential for this worker to generate enough traffic across all android devices
     * to overwhelm websites, this method is hardcoded to be disabled by default. It is highly
     * recommended to not enable this worker and instead implement a custom worker that pulls
     * updates from a caching service instead of directly from websites.
     */
    fun schedulePeriodicUpdateUnlocked(workManager: WorkManager) {
        if (UPDATE_WORKER_ENABLED) {
            workManager.apply {
                PeriodicWorkRequestBuilder<UpdateVerifiedDomainsWorker>(
                    Duration.ofDays(
                        PERIODIC_UPDATE_HOURS
                    )
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresDeviceIdle(true)
                            .build()
                    )
                    .build()
                    .let {
                        enqueueUniquePeriodicWork(
                            PERIODIC_UPDATE_ID,
                            ExistingPeriodicWorkPolicy.KEEP, it
                        )
                    }
            }
        }
    }
}
