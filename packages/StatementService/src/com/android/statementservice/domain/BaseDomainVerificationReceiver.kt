/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType

abstract class BaseDomainVerificationReceiver : BroadcastReceiver() {

    companion object {
        const val DEBUG = false
    }

    protected abstract val tag: String

    protected val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    protected fun debugLog(block: () -> String) {
        if (DEBUG) {
            Log.d(tag, block())
        }
    }
}
