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

package com.android.systemui.statusbar.pipeline.mobile.util

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface SubscriptionManagerProxy {
    fun getDefaultDataSubscriptionId(): Int
    fun isValidSubscriptionId(subId: Int): Boolean
    suspend fun getActiveSubscriptionInfo(subId: Int): SubscriptionInfo?
}

/** Injectable proxy class for [SubscriptionManager]'s static methods */
class SubscriptionManagerProxyImpl
@Inject
constructor(
    @Application private val applicationContext: Context,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val subscriptionManager: SubscriptionManager,
) : SubscriptionManagerProxy {
    /** The system default data subscription id, or INVALID_SUBSCRIPTION_ID on error */
    override fun getDefaultDataSubscriptionId() = SubscriptionManager.getDefaultDataSubscriptionId()

    override fun isValidSubscriptionId(subId: Int): Boolean {
        return SubscriptionManager.isValidSubscriptionId(subId)
    }

    @SuppressLint("MissingPermission")
    override suspend fun getActiveSubscriptionInfo(subId: Int): SubscriptionInfo? {
        return withContext(backgroundDispatcher) {
            subscriptionManager.getActiveSubscriptionInfo(subId)
        }
    }
}
