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

import static com.android.server.net.NetworkPolicyManagerService.UidBlockedState.getAllowedReasonsForProcState;
import static com.android.server.net.NetworkPolicyManagerService.UidBlockedState.getEffectiveBlockedReasons;

import android.annotation.Nullable;
import android.net.Network;
import android.os.PowerExemptionManager.ReasonCode;
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
     * @param added Denotes whether the {@code appId} has been added or removed from the allowlist.
     * @param reasonCode one of {@link ReasonCode} indicating the reason for the change.
     *                   Only valid when {@code added} is {@code true}.
     * @param reason an optional human-readable reason explaining why the app is temp allow-listed.
     */
    public abstract void onTempPowerSaveWhitelistChange(int appId, boolean added,
            @ReasonCode int reasonCode, @Nullable String reason);

    /**
     * Return the active {@link SubscriptionPlan} for the given network.
     */
    public abstract SubscriptionPlan getSubscriptionPlan(Network network);

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

    /** Informs that Low Power Standby has become active */
    public abstract void setLowPowerStandbyActive(boolean active);

    /** Informs that the Low Power Standby allowlist has changed */
    public abstract void setLowPowerStandbyAllowlist(int[] uids);

    /** Update the {@code blockedReasons} taking into account the {@code procState} of the uid */
    public static int updateBlockedReasonsWithProcState(int blockedReasons, int procState) {
        final int allowedReasons = getAllowedReasonsForProcState(procState);
        return getEffectiveBlockedReasons(blockedReasons, allowedReasons);
    }
}
