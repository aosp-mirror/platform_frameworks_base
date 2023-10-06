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

import android.telephony.SubscriptionManager
import javax.inject.Inject

interface SubscriptionManagerProxy {
    fun getDefaultDataSubscriptionId(): Int
}

/** Injectable proxy class for [SubscriptionManager]'s static methods */
class SubscriptionManagerProxyImpl @Inject constructor() : SubscriptionManagerProxy {
    /** The system default data subscription id, or INVALID_SUBSCRIPTION_ID on error */
    override fun getDefaultDataSubscriptionId() = SubscriptionManager.getDefaultDataSubscriptionId()
}
