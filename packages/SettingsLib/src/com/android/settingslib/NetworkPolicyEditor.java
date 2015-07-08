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

package com.android.settingslib;

import static android.net.NetworkPolicy.CYCLE_NONE;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicy.SNOOZE_NEVER;
import static android.net.NetworkPolicy.WARNING_DISABLED;
import static android.net.NetworkTemplate.MATCH_WIFI;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.net.NetworkPolicy;
import android.net.NetworkPolicyManager;
import android.net.NetworkTemplate;
import android.net.wifi.WifiInfo;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.text.format.Time;

import com.google.android.collect.Lists;

import java.util.ArrayList;

/**
 * Utility class to modify list of {@link NetworkPolicy}. Specifically knows
 * about which policies can coexist. This editor offers thread safety when
 * talking with {@link NetworkPolicyManager}.
 *
 * @hide
 */
public class NetworkPolicyEditor {
    // TODO: be more robust when missing policies from service

    public static final boolean ENABLE_SPLIT_POLICIES = false;

    private NetworkPolicyManager mPolicyManager;
    private ArrayList<NetworkPolicy> mPolicies = Lists.newArrayList();

    public NetworkPolicyEditor(NetworkPolicyManager policyManager) {
        mPolicyManager = checkNotNull(policyManager);
    }

    public void read() {
        final NetworkPolicy[] policies = mPolicyManager.getNetworkPolicies();

        boolean modified = false;
        mPolicies.clear();
        for (NetworkPolicy policy : policies) {
            // TODO: find better place to clamp these
            if (policy.limitBytes < -1) {
                policy.limitBytes = LIMIT_DISABLED;
                modified = true;
            }
            if (policy.warningBytes < -1) {
                policy.warningBytes = WARNING_DISABLED;
                modified = true;
            }

            mPolicies.add(policy);
        }

        // when we cleaned policies above, write back changes
        if (modified) writeAsync();
    }

    public void writeAsync() {
        // TODO: consider making more robust by passing through service
        final NetworkPolicy[] policies = mPolicies.toArray(new NetworkPolicy[mPolicies.size()]);
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                write(policies);
                return null;
            }
        }.execute();
    }

    public void write(NetworkPolicy[] policies) {
        mPolicyManager.setNetworkPolicies(policies);
    }

    public boolean hasLimitedPolicy(NetworkTemplate template) {
        final NetworkPolicy policy = getPolicy(template);
        return policy != null && policy.limitBytes != LIMIT_DISABLED;
    }

    public NetworkPolicy getOrCreatePolicy(NetworkTemplate template) {
        NetworkPolicy policy = getPolicy(template);
        if (policy == null) {
            policy = buildDefaultPolicy(template);
            mPolicies.add(policy);
        }
        return policy;
    }

    public NetworkPolicy getPolicy(NetworkTemplate template) {
        for (NetworkPolicy policy : mPolicies) {
            if (policy.template.equals(template)) {
                return policy;
            }
        }
        return null;
    }

    public NetworkPolicy getPolicyMaybeUnquoted(NetworkTemplate template) {
        NetworkPolicy policy = getPolicy(template);
        if (policy != null) {
            return policy;
        } else {
            return getPolicy(buildUnquotedNetworkTemplate(template));
        }
    }

    @Deprecated
    private static NetworkPolicy buildDefaultPolicy(NetworkTemplate template) {
        // TODO: move this into framework to share with NetworkPolicyManagerService
        final int cycleDay;
        final String cycleTimezone;
        final boolean metered;

        if (template.getMatchRule() == MATCH_WIFI) {
            cycleDay = CYCLE_NONE;
            cycleTimezone = Time.TIMEZONE_UTC;
            metered = false;
        } else {
            final Time time = new Time();
            time.setToNow();
            cycleDay = time.monthDay;
            cycleTimezone = time.timezone;
            metered = true;
        }

        return new NetworkPolicy(template, cycleDay, cycleTimezone, WARNING_DISABLED,
                LIMIT_DISABLED, SNOOZE_NEVER, SNOOZE_NEVER, metered, true);
    }

    public int getPolicyCycleDay(NetworkTemplate template) {
        final NetworkPolicy policy = getPolicy(template);
        return (policy != null) ? policy.cycleDay : -1;
    }

    public void setPolicyCycleDay(NetworkTemplate template, int cycleDay, String cycleTimezone) {
        final NetworkPolicy policy = getOrCreatePolicy(template);
        policy.cycleDay = cycleDay;
        policy.cycleTimezone = cycleTimezone;
        policy.inferred = false;
        policy.clearSnooze();
        writeAsync();
    }

    public long getPolicyWarningBytes(NetworkTemplate template) {
        final NetworkPolicy policy = getPolicy(template);
        return (policy != null) ? policy.warningBytes : WARNING_DISABLED;
    }

    public void setPolicyWarningBytes(NetworkTemplate template, long warningBytes) {
        final NetworkPolicy policy = getOrCreatePolicy(template);
        policy.warningBytes = warningBytes;
        policy.inferred = false;
        policy.clearSnooze();
        writeAsync();
    }

    public long getPolicyLimitBytes(NetworkTemplate template) {
        final NetworkPolicy policy = getPolicy(template);
        return (policy != null) ? policy.limitBytes : LIMIT_DISABLED;
    }

    public void setPolicyLimitBytes(NetworkTemplate template, long limitBytes) {
        final NetworkPolicy policy = getOrCreatePolicy(template);
        policy.limitBytes = limitBytes;
        policy.inferred = false;
        policy.clearSnooze();
        writeAsync();
    }

    public boolean getPolicyMetered(NetworkTemplate template) {
        NetworkPolicy policy = getPolicy(template);
        if (policy != null) {
            return policy.metered;
        } else {
            return false;
        }
    }

    public void setPolicyMetered(NetworkTemplate template, boolean metered) {
        boolean modified = false;

        NetworkPolicy policy = getPolicy(template);
        if (metered) {
            if (policy == null) {
                policy = buildDefaultPolicy(template);
                policy.metered = true;
                policy.inferred = false;
                mPolicies.add(policy);
                modified = true;
            } else if (!policy.metered) {
                policy.metered = true;
                policy.inferred = false;
                modified = true;
            }

        } else {
            if (policy == null) {
                // ignore when policy doesn't exist
            } else if (policy.metered) {
                policy.metered = false;
                policy.inferred = false;
                modified = true;
            }
        }

        // Remove legacy unquoted policies while we're here
        final NetworkTemplate unquoted = buildUnquotedNetworkTemplate(template);
        final NetworkPolicy unquotedPolicy = getPolicy(unquoted);
        if (unquotedPolicy != null) {
            mPolicies.remove(unquotedPolicy);
            modified = true;
        }

        if (modified) writeAsync();
    }

    /**
     * Build a revised {@link NetworkTemplate} that matches the same rule, but
     * with an unquoted {@link NetworkTemplate#getNetworkId()}. Used to work
     * around legacy bugs.
     */
    private static NetworkTemplate buildUnquotedNetworkTemplate(NetworkTemplate template) {
        if (template == null) return null;
        final String networkId = template.getNetworkId();
        final String strippedNetworkId = WifiInfo.removeDoubleQuotes(networkId);
        if (!TextUtils.equals(strippedNetworkId, networkId)) {
            return new NetworkTemplate(
                    template.getMatchRule(), template.getSubscriberId(), strippedNetworkId);
        } else {
            return null;
        }
    }
}
