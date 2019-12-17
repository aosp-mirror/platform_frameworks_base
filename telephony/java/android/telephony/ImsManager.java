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

package android.telephony.ims;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.telephony.SubscriptionManager;

/**
 * Provides access to information about Telephony IMS services on the device.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.TELEPHONY_IMS_SERVICE)
public class ImsManager {

    private Context mContext;

    /** @hide */
    public ImsManager(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Create an instance of ImsRcsManager for the subscription id specified.
     *
     * @param subscriptionId The ID of the subscription that this ImsRcsManager will use.
     * @throws IllegalArgumentException if the subscription is invalid.
     * @return a ImsRcsManager instance with the specific subscription ID.
     */
    @NonNull
    public ImsRcsManager getImsRcsManager(int subscriptionId) {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("Invalid subscription ID: " + subscriptionId);
        }

        return new ImsRcsManager(mContext, subscriptionId);
    }

    /**
     * Create an instance of ImsMmTelManager for the subscription id specified.
     *
     * @param subscriptionId The ID of the subscription that this ImsMmTelManager will use.
     * @throws IllegalArgumentException if the subscription is invalid.
     * @return a ImsMmTelManager instance with the specific subscription ID.
     */
    @NonNull
    public ImsMmTelManager getImsMmTelManager(int subscriptionId) {
        if (!SubscriptionManager.isValidSubscriptionId(subscriptionId)) {
            throw new IllegalArgumentException("Invalid subscription ID: " + subscriptionId);
        }

        return new ImsMmTelManager(subscriptionId);
    }
}
