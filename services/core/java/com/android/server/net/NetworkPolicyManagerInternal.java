/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.net;

import static com.android.server.net.NetworkPolicyManagerService.isUidNetworkingBlockedInternal;

import android.annotation.NonNull;
import android.net.Network;
import android.net.NetworkTemplate;
import android.net.netstats.provider.NetworkStatsProvider;
import android.telephony.SubscriptionPlan;

import java.util.Set;

/**
 * Network Policy Manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class NetworkPolicyManagerInternal {

    /**
     * Resets all policies associated with a given user.
     */
    public abstract void resetUserState(int userId);

    /**
     * @return true if the given uid is restricted from doing networking on metered networks.
     */
    public abstract boolean isUidRestrictedOnMeteredNetworks(int uid);

    /**
     * @return true if networking is blocked on the given interface for the given uid according
     * to current networking policies.
     */
    public abstract boolean isUidNetworkingBlocked(int uid, String ifname);

    /**
     * Figure out if networking is blocked for a given set of conditions.
     *
     * This is used by ConnectivityService via passing stale copies of conditions, so it must not
     * take any locks.
     *
     * @param uid The target uid.
     * @param uidRules The uid rules which are obtained from NetworkPolicyManagerService.
     * @param isNetworkMetered True if the network is metered.
     * @param isBackgroundRestricted True if data saver is enabled.
     *
     * @return true if networking is blocked for the UID under the specified conditions.
     */
    public static boolean isUidNetworkingBlocked(int uid, int uidRules, boolean isNetworkMetered,
            boolean isBackgroundRestricted) {
        // Log of invoking internal function is disabled because it will be called very
        // frequently. And metrics are unlikely needed on this method because the callers are
        // external and this method doesn't take any locks or perform expensive operations.
        return isUidNetworkingBlockedInternal(uid, uidRules, isNetworkMetered,
                isBackgroundRestricted, null);
    }

    /**
     * Informs that an appId has been added or removed from the temp-powersave-whitelist so that
     * that network rules for that appId can be updated.
     *
     * @param appId The appId which has been updated in the whitelist.
     * @param added Denotes whether the {@param appId} has been added or removed from the whitelist.
     */
    public abstract void onTempPowerSaveWhitelistChange(int appId, boolean added);

    /**
     * Return the active {@link SubscriptionPlan} for the given network.
     */
    public abstract SubscriptionPlan getSubscriptionPlan(Network network);

    /**
     * Return the active {@link SubscriptionPlan} for the given template.
     */
    public abstract SubscriptionPlan getSubscriptionPlan(NetworkTemplate template);

    public static final int QUOTA_TYPE_JOBS = 1;
    public static final int QUOTA_TYPE_MULTIPATH = 2;

    /**
     * Return the daily quota (in bytes) that can be opportunistically used on
     * the given network to improve the end user experience. It's called
     * "opportunistic" because it's traffic that would typically not use the
     * given network.
     */
    public abstract long getSubscriptionOpportunisticQuota(Network network, int quotaType);

    /**
     * Informs that admin data is loaded and available.
     */
    public abstract void onAdminDataAvailable();

    /**
     * Control if a UID should be whitelisted even if it's in app idle mode. Other restrictions may
     * still be in effect.
     */
    public abstract void setAppIdleWhitelist(int uid, boolean shouldWhitelist);

    /**
     * Sets a list of packages which are restricted by admin from accessing metered data.
     *
     * @param packageNames the list of restricted packages.
     * @param userId the userId in which {@param packagesNames} are restricted.
     */
    public abstract void setMeteredRestrictedPackages(
            Set<String> packageNames, int userId);


    /**
     * Similar to {@link #setMeteredRestrictedPackages(Set, int)} but updates the restricted
     * packages list asynchronously.
     */
    public abstract void setMeteredRestrictedPackagesAsync(
            Set<String> packageNames, int userId);

    /**
     *  Notifies that the specified {@link NetworkStatsProvider} has reached its quota
     *  which was set through {@link NetworkStatsProvider#onSetLimit(String, long)} or
     *  {@link NetworkStatsProvider#onSetWarningAndLimit(String, long, long)}.
     *
     * @param tag the human readable identifier of the custom network stats provider.
     */
    public abstract void onStatsProviderWarningOrLimitReached(@NonNull String tag);
}
