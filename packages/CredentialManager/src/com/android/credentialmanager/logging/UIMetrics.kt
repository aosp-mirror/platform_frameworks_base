/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.credentialmanager.logging

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.android.internal.logging.InstanceId
import com.android.internal.logging.InstanceIdSequence
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.UiEventLoggerImpl

class UIMetrics() {
    private val INSTANCE_ID_MAX = 1 shl 20
    private val mUiEventLogger: UiEventLogger = UiEventLoggerImpl()
    val mInstanceIdSequence: InstanceIdSequence = InstanceIdSequence(INSTANCE_ID_MAX)

    var mInstanceId: InstanceId = mInstanceIdSequence.newInstanceId()

    fun resetInstanceId() {
        this.mInstanceId = mInstanceIdSequence.newInstanceId()
    }

    @Composable
    fun log(event: UiEventLogger.UiEventEnum) {
        val instanceId: InstanceId = mInstanceId
        LaunchedEffect(true) {
            mUiEventLogger.log(event, instanceId)
        }
    }

    @Composable
    fun log(event: UiEventLogger.UiEventEnum, packageName: String?) {
        val instanceId: InstanceId = mInstanceId
        LaunchedEffect(true) {
            mUiEventLogger.logWithInstanceId(event, /*uid=*/0, packageName, instanceId)
        }
    }

    @Composable
    fun log(event: UiEventLogger.UiEventEnum, instanceId: InstanceId, packageName: String?) {
        LaunchedEffect(true) {
            mUiEventLogger.logWithInstanceId(event, /*uid=*/0, packageName, instanceId)
        }
    }

    fun logNormal(event: UiEventLogger.UiEventEnum, packageName: String?) {
        mUiEventLogger.logWithInstanceId(event, /*uid=*/0, packageName, mInstanceId)
    }
}
