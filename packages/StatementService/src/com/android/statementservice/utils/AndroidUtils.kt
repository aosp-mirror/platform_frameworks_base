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

package com.android.statementservice.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationInfo
import android.content.pm.verify.domain.DomainVerificationRequest
import android.content.pm.verify.domain.DomainVerificationUserState
import com.android.statementservice.domain.DomainVerificationReceiverV1
import com.android.statementservice.domain.DomainVerificationReceiverV2

// Top level extensions for models to allow Kotlin deconstructing declarations

operator fun DomainVerificationRequest.component1() = packageNames

operator fun DomainVerificationInfo.component1() = identifier
operator fun DomainVerificationInfo.component2() = packageName
operator fun DomainVerificationInfo.component3() = hostToStateMap

operator fun DomainVerificationUserState.component1() = identifier
operator fun DomainVerificationUserState.component2() = packageName
operator fun DomainVerificationUserState.component3() = user
operator fun DomainVerificationUserState.component4() = isLinkHandlingAllowed
operator fun DomainVerificationUserState.component5() = hostToStateMap

object AndroidUtils {

    fun isReceiverV1Enabled(context: Context): Boolean {
        val receiver = ComponentName(context, DomainVerificationReceiverV1::class.java)
        return when (context.packageManager.getComponentEnabledSetting(receiver)) {
            // Must change this if the manifest ever changes
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> true
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
    }

    fun isReceiverV2Enabled(context: Context): Boolean {
        val receiver = ComponentName(context, DomainVerificationReceiverV2::class.java)
        return when (context.packageManager.getComponentEnabledSetting(receiver)) {
            // Must change this if the manifest ever changes
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> false
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
    }
}
