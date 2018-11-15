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


interface IAns {

    /**
    * Enable or disable Alternative Network service.
    *
    * This method should be called to enable or disable
    * AlternativeNetwork service on the device.
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
     * is Alternative Network service enabled
     *
     * This method should be called to determine if the Alternative Network service is enabled
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
     * @param callingPackage caller's package name
     * @return true if request is accepted, else false.
     *
     */
    boolean setPreferredData(int subId, String callingPackage);

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
    int getPreferredData(String callingPackage);
}
