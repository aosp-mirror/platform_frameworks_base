/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.net;

import android.content.Context;
import android.net.NetworkTemplate;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.util.ArrayUtils;
/**
 * Utils class for data usage
 */
public class DataUsageUtils {
    private static final String TAG = "DataUsageUtils";

    /**
     * Return mobile NetworkTemplate based on {@code subId}
     */
    public static NetworkTemplate getMobileTemplate(Context context, int subId) {
        final TelephonyManager telephonyManager = context.getSystemService(
                TelephonyManager.class);
        final SubscriptionManager subscriptionManager = context.getSystemService(
                SubscriptionManager.class);
        final NetworkTemplate mobileAll = NetworkTemplate.buildTemplateMobileAll(
                telephonyManager.getSubscriberId(subId));

        if (!subscriptionManager.isActiveSubId(subId)) {
            Log.i(TAG, "Subscription is not active: " + subId);
            return mobileAll;
        }

        final String[] mergedSubscriberIds = telephonyManager.createForSubscriptionId(subId)
                .getMergedSubscriberIdsFromGroup();

        if (ArrayUtils.isEmpty(mergedSubscriberIds)) {
            Log.i(TAG, "mergedSubscriberIds is null.");
            return mobileAll;
        }

        return NetworkTemplate.normalize(mobileAll, mergedSubscriberIds);
    }
}
