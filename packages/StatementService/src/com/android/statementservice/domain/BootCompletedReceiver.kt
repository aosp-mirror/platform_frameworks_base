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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.android.statementservice.domain.worker.RetryRequestWorker

/**
 * Handles [Intent.ACTION_BOOT_COMPLETED] to schedule recurring maintenance [WorkManager] tasks and
 * run a one-time retry request to attempt to verify domains that may have failed or been added
 * since last device reboot.
 *
 * Note that this requires the user to have unlocked the device, since [WorkManager] cannot handle
 * the encrypted user data directories.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val PACKAGE_BOOT_REQUEST_KEY = "package_boot_request"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val workManager = WorkManager.getInstance(context)
        DomainVerificationUtils.schedulePeriodicCheckUnlocked(workManager)
        workManager.beginUniqueWork(
            PACKAGE_BOOT_REQUEST_KEY,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RetryRequestWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        ).enqueue()
    }
}
