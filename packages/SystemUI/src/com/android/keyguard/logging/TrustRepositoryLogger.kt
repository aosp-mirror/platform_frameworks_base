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

package com.android.keyguard.logging

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.shared.model.ActiveUnlockModel
import com.android.systemui.keyguard.shared.model.TrustManagedModel
import com.android.systemui.keyguard.shared.model.TrustModel
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.LogLevel.DEBUG
import com.android.systemui.log.dagger.KeyguardUpdateMonitorLog
import javax.inject.Inject

/** Logging helper for trust repository. */
@SysUISingleton
class TrustRepositoryLogger
@Inject
constructor(
    @KeyguardUpdateMonitorLog private val logBuffer: LogBuffer,
) {
    fun onTrustChanged(
        enabled: Boolean,
        newlyUnlocked: Boolean,
        userId: Int,
        flags: Int,
        trustGrantedMessages: List<String>?
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = enabled
                bool2 = newlyUnlocked
                int1 = userId
                int2 = flags
                str1 = trustGrantedMessages?.joinToString()
            },
            {
                "onTrustChanged enabled: $bool1, newlyUnlocked: $bool2, " +
                    "userId: $int1, flags: $int2, grantMessages: $str1"
            }
        )
    }

    fun trustListenerRegistered() {
        logBuffer.log(TAG, LogLevel.VERBOSE, "TrustRepository#registerTrustListener")
    }

    fun trustListenerUnregistered() {
        logBuffer.log(TAG, LogLevel.VERBOSE, "TrustRepository#unregisterTrustListener")
    }

    fun trustModelEmitted(value: TrustModel) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = value.userId
                bool1 = value.isTrusted
            },
            { "trustModel emitted: userId: $int1 isTrusted: $bool1" }
        )
    }

    fun activeUnlockModelEmitted(value: ActiveUnlockModel) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = value.userId
                bool1 = value.isRunning
            },
            { "activeUnlockModel emitted: userId: $int1 isRunning: $bool1" }
        )
    }

    fun isCurrentUserTrusted(isCurrentUserTrusted: Boolean) {
        logBuffer.log(
            TAG,
            DEBUG,
            { bool1 = isCurrentUserTrusted },
            { "isCurrentUserTrusted emitted: $bool1" }
        )
    }

    fun isCurrentUserActiveUnlockRunning(isCurrentUserActiveUnlockRunning: Boolean) {
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            { bool1 = isCurrentUserActiveUnlockRunning },
            { "isCurrentUserActiveUnlockRunning emitted: $bool1" }
        )
    }

    fun isCurrentUserTrustManaged(isTrustManaged: Boolean) {
        logBuffer.log(TAG, DEBUG, { bool1 = isTrustManaged }, { "isTrustManaged emitted: $bool1" })
    }

    fun onTrustManagedChanged(trustManaged: Boolean, userId: Int) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = trustManaged
                int1 = userId
            },
            { "onTrustManagedChanged isTrustManaged: $bool1 for user: $int1" }
        )
    }

    fun trustManagedModelEmitted(it: TrustManagedModel) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = it.isTrustManaged
                int1 = it.userId
            },
            { "trustManagedModel emitted: userId: $int1, isTrustManaged: $bool1" }
        )
    }

    companion object {
        const val TAG = "TrustRepositoryLog"
    }
}
