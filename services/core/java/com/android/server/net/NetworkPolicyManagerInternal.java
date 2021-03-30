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
     * Informs that an appId has been added or removed from the temp-powersave-allowlist so that
     * that network rules for that appId can be updated.
     *
     * @param appId The appId which has been updated in the allowlist.
     * @param added Denotes whether the {@param appId} has been added or removed from the allowlist.
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
     * Control if a UID should be allowlisted even if it's in app idle mode. Other restrictions may
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
