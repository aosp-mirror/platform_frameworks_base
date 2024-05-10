/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net;

import android.net.INetworkPolicyListener;
import android.net.Network;
import android.net.NetworkPolicy;
import android.net.NetworkTemplate;
import android.telephony.SubscriptionPlan;

/**
 * Interface that creates and modifies network policy rules.
 *
 * {@hide}
 */
interface INetworkPolicyManager {

    /** Control UID policies. */
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    @UnsupportedAppUsage
    void setUidPolicy(int uid, int policy);
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    void addUidPolicy(int uid, int policy);
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    void removeUidPolicy(int uid, int policy);
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    @UnsupportedAppUsage
    int getUidPolicy(int uid);
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    int[] getUidsWithPolicy(int policy);

    void registerListener(INetworkPolicyListener listener);
    void unregisterListener(INetworkPolicyListener listener);

    /** Control network policies atomically. */
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    @UnsupportedAppUsage
    void setNetworkPolicies(in NetworkPolicy[] policies);
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    NetworkPolicy[] getNetworkPolicies(String callingPackage);

    /** Snooze limit on policy matching given template. */
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    @UnsupportedAppUsage
    void snoozeLimit(in NetworkTemplate template);

    /** Control if background data is restricted system-wide. */
    @UnsupportedAppUsage
    void setRestrictBackground(boolean restrictBackground);
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    @UnsupportedAppUsage
    boolean getRestrictBackground();

    /** Gets the restrict background status based on the caller's UID:
        1 - disabled
        2 - whitelisted
        3 - enabled
    */
    @EnforcePermission("ACCESS_NETWORK_STATE")
    int getRestrictBackgroundByCaller();
    int getRestrictBackgroundStatus(int uid);

    @EnforcePermission("MANAGE_NETWORK_POLICY")
    void setDeviceIdleMode(boolean enabled);
    @EnforcePermission("MANAGE_NETWORK_POLICY")
    void setWifiMeteredOverride(String networkId, int meteredOverride);

    int getMultipathPreference(in Network network);

    SubscriptionPlan getSubscriptionPlan(in NetworkTemplate template);
    void notifyStatsProviderWarningOrLimitReached();
    SubscriptionPlan[] getSubscriptionPlans(int subId, String callingPackage);
    void setSubscriptionPlans(int subId, in SubscriptionPlan[] plans, long expirationDurationMillis, String callingPackage);
    String getSubscriptionPlansOwner(int subId);
    void setSubscriptionOverride(int subId, int overrideMask, int overrideValue, in int[] networkTypes, long expirationDurationMillis, String callingPackage);

    @EnforcePermission("NETWORK_SETTINGS")
    void factoryReset(String subscriber);

    boolean isUidNetworkingBlocked(int uid, boolean meteredNetwork);
    @EnforcePermission("OBSERVE_NETWORK_POLICY")
    boolean isUidRestrictedOnMeteredNetworks(int uid);
}
