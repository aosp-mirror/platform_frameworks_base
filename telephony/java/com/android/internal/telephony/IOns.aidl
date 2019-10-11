/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.telephony;

import android.telephony.AvailableNetworkInfo;

import com.android.internal.telephony.ISetOpportunisticDataCallback;
import com.android.internal.telephony.IUpdateAvailableNetworksCallback;

interface IOns {

    /**
    * Enable or disable Opportunistic Network service.
    *
    * This method should be called to enable or disable
    * OpportunisticNetwork service on the device.
    *
    * <p>
    * Requires Permission:
    *   {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}
    * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
    *
    * @param enable enable(True) or disable(False)
    * @param callingPackage caller's package name
    * @return returns true if successfully set.
    */
    boolean setEnable(boolean enable, String callingPackage);

    /**
     * is Opportunistic Network service enabled
     *
     * This method should be called to determine if the Opportunistic Network service is enabled
    *
    * <p>
    * Requires Permission:
    *   {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
    * Or the calling app has carrier privileges. @see #hasCarrierPrivileges
    *
    * @param callingPackage caller's package name
    */
    boolean isEnabled(String callingPackage);

    /**
     * Set preferred opportunistic data subscription id.
     *
     * <p>Requires that the calling app has carrier privileges on both primary and
     * secondary subscriptions (see
     * {@link #hasCarrierPrivileges}), or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *
     * @param subId which opportunistic subscription
     * {@link SubscriptionManager#getOpportunisticSubscriptions} is preferred for cellular data.
     * Pass {@link SubscriptionManager#DEFAULT_SUBSCRIPTION_ID} to unset the preference
     * @param needValidation whether validation is needed before switch happens.
     * @param callback callback upon request completion.
     * @param callingPackage caller's package name
     *
     */
    void setPreferredDataSubscriptionId(int subId, boolean needValidation,
            ISetOpportunisticDataCallback callbackStub, String callingPackage);

    /**
     * Get preferred opportunistic data subscription Id
     *
     * <p>Requires that the calling app has carrier privileges (see {@link #hasCarrierPrivileges}),
     * or has permission {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}.
     * @return subId preferred opportunistic subscription id or
     * {@link SubscriptionManager#DEFAULT_SUBSCRIPTION_ID} if there are no preferred
     * subscription id
     *
     */
    int getPreferredDataSubscriptionId(String callingPackage);

    /**
     * Update availability of a list of networks in the current location.
     *
     * This api should be called if the caller is aware of the availability of a network
     * at the current location. This information will be used by OpportunisticNetwork service
     * to decide to attach to the network. If an empty list is passed,
     * it is assumed that no network is available.
     * Requires that the calling app has carrier privileges on both primary and
     * secondary subscriptions (see
     * {@link #hasCarrierPrivileges}), or has permission
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE MODIFY_PHONE_STATE}.
     *  @param availableNetworks is a list of available network information.
     *  @param callingPackage caller's package name
     *  @param callback callback upon request completion.
     *
     */
    void updateAvailableNetworks(in List<AvailableNetworkInfo> availableNetworks,
            IUpdateAvailableNetworksCallback callbackStub, String callingPackage);
}
