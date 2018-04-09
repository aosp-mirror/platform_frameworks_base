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

import static android.content.pm.PackageManager.GET_SIGNATURES;

import android.annotation.SystemService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.DebugUtils;
import android.util.Pair;
import android.util.Range;

import com.google.android.collect.Sets;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Manager for creating and modifying network policy rules.
 *
 * {@hide}
 */
@SystemService(Context.NETWORK_POLICY_SERVICE)
public class NetworkPolicyManager {

    /* POLICY_* are masks and can be ORed, although currently they are not.*/
    /** No specific network policy, use system default. */
    public static final int POLICY_NONE = 0x0;
    /** Reject network usage on metered networks when application in background. */
    public static final int POLICY_REJECT_METERED_BACKGROUND = 0x1;
    /** Allow metered network use in the background even when in data usage save mode. */
    public static final int POLICY_ALLOW_METERED_BACKGROUND = 0x4;

    /*
     * Rules defining whether an uid has access to a network given its type (metered / non-metered).
     *
     * These rules are bits and can be used in bitmask operations; in particular:
     * - rule & RULE_MASK_METERED: returns the metered-networks status.
     * - rule & RULE_MASK_ALL: returns the all-networks status.
     *
     * The RULE_xxx_ALL rules applies to all networks (metered or non-metered), but on
     * metered networks, the RULE_xxx_METERED rules should be checked first. For example,
     * if the device is on Battery Saver Mode and Data Saver Mode simulatenously, and a uid
     * is whitelisted for the former but not the latter, its status would be
     * RULE_REJECT_METERED | RULE_ALLOW_ALL, meaning it could have access to non-metered
     * networks but not to metered networks.
     *
     * See network-policy-restrictions.md for more info.
     */
    /** No specific rule was set */
    public static final int RULE_NONE = 0;
    /** Allow traffic on metered networks. */
    public static final int RULE_ALLOW_METERED = 1 << 0;
    /** Temporarily allow traffic on metered networks because app is on foreground. */
    public static final int RULE_TEMPORARY_ALLOW_METERED = 1 << 1;
    /** Reject traffic on metered networks. */
    public static final int RULE_REJECT_METERED = 1 << 2;
    /** Network traffic should be allowed on all networks (metered or non-metered), although
     * metered-network restrictions could still apply. */
    public static final int RULE_ALLOW_ALL = 1 << 5;
    /** Reject traffic on all networks. */
    public static final int RULE_REJECT_ALL = 1 << 6;
    /** Mask used to get the {@code RULE_xxx_METERED} rules */
    public static final int MASK_METERED_NETWORKS = 0b00001111;
    /** Mask used to get the {@code RULE_xxx_ALL} rules */
    public static final int MASK_ALL_NETWORKS     = 0b11110000;

    public static final int FIREWALL_RULE_DEFAULT = 0;
    public static final int FIREWALL_RULE_ALLOW = 1;
    public static final int FIREWALL_RULE_DENY = 2;

    public static final int FIREWALL_TYPE_WHITELIST = 0;
    public static final int FIREWALL_TYPE_BLACKLIST = 1;

    public static final int FIREWALL_CHAIN_NONE = 0;
    public static final int FIREWALL_CHAIN_DOZABLE = 1;
    public static final int FIREWALL_CHAIN_STANDBY = 2;
    public static final int FIREWALL_CHAIN_POWERSAVE = 3;

    public static final String FIREWALL_CHAIN_NAME_NONE = "none";
    public static final String FIREWALL_CHAIN_NAME_DOZABLE = "dozable";
    public static final String FIREWALL_CHAIN_NAME_STANDBY = "standby";
    public static final String FIREWALL_CHAIN_NAME_POWERSAVE = "powersave";

    private static final boolean ALLOW_PLATFORM_APP_POLICY = true;

    public static final int FOREGROUND_THRESHOLD_STATE =
            ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;

    /**
     * {@link Intent} extra that indicates which {@link NetworkTemplate} rule it
     * applies to.
     */
    public static final String EXTRA_NETWORK_TEMPLATE = "android.net.NETWORK_TEMPLATE";

    public static final int OVERRIDE_UNMETERED = 1 << 0;
    public static final int OVERRIDE_CONGESTED = 1 << 1;

    private final Context mContext;
    private INetworkPolicyManager mService;

    public NetworkPolicyManager(Context context, INetworkPolicyManager service) {
        if (service == null) {
            throw new IllegalArgumentException("missing INetworkPolicyManager");
        }
        mContext = context;
        mService = service;
    }

    public static NetworkPolicyManager from(Context context) {
        return (NetworkPolicyManager) context.getSystemService(Context.NETWORK_POLICY_SERVICE);
    }

    /**
     * Set policy flags for specific UID.
     *
     * @param policy should be {@link #POLICY_NONE} or any combination of {@code POLICY_} flags,
     *     although it is not validated.
     */
    public void setUidPolicy(int uid, int policy) {
        try {
            mService.setUidPolicy(uid, policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add policy flags for specific UID.
     *
     * <p>The given policy bits will be set for the uid.
     *
     * @param policy should be {@link #POLICY_NONE} or any combination of {@code POLICY_} flags,
     *     although it is not validated.
     */
    public void addUidPolicy(int uid, int policy) {
        try {
            mService.addUidPolicy(uid, policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clear/remove policy flags for specific UID.
     *
     * <p>The given policy bits will be set for the uid.
     *
     * @param policy should be {@link #POLICY_NONE} or any combination of {@code POLICY_} flags,
     *     although it is not validated.
     */
    public void removeUidPolicy(int uid, int policy) {
        try {
            mService.removeUidPolicy(uid, policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public int getUidPolicy(int uid) {
        try {
            return mService.getUidPolicy(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public int[] getUidsWithPolicy(int policy) {
        try {
            return mService.getUidsWithPolicy(policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void registerListener(INetworkPolicyListener listener) {
        try {
            mService.registerListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void unregisterListener(INetworkPolicyListener listener) {
        try {
            mService.unregisterListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void setNetworkPolicies(NetworkPolicy[] policies) {
        try {
            mService.setNetworkPolicies(policies);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public NetworkPolicy[] getNetworkPolicies() {
        try {
            return mService.getNetworkPolicies(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void setRestrictBackground(boolean restrictBackground) {
        try {
            mService.setRestrictBackground(restrictBackground);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean getRestrictBackground() {
        try {
            return mService.getRestrictBackground();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets network policy settings back to factory defaults.
     *
     * @hide
     */
    public void factoryReset(String subscriber) {
        try {
            mService.factoryReset(subscriber);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @Deprecated
    public static Iterator<Pair<ZonedDateTime, ZonedDateTime>> cycleIterator(NetworkPolicy policy) {
        final Iterator<Range<ZonedDateTime>> it = policy.cycleIterator();
        return new Iterator<Pair<ZonedDateTime, ZonedDateTime>>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Pair<ZonedDateTime, ZonedDateTime> next() {
                final Range<ZonedDateTime> r = it.next();
                return Pair.create(r.getLower(), r.getUpper());
            }
        };
    }

    /**
     * Check if given UID can have a {@link #setUidPolicy(int, int)} defined,
     * usually to protect critical system services.
     */
    @Deprecated
    public static boolean isUidValidForPolicy(Context context, int uid) {
        // first, quick-reject non-applications
        if (!UserHandle.isApp(uid)) {
            return false;
        }

        if (!ALLOW_PLATFORM_APP_POLICY) {
            final PackageManager pm = context.getPackageManager();
            final HashSet<Signature> systemSignature;
            try {
                systemSignature = Sets.newHashSet(
                        pm.getPackageInfo("android", GET_SIGNATURES).signatures);
            } catch (NameNotFoundException e) {
                throw new RuntimeException("problem finding system signature", e);
            }

            try {
                // reject apps signed with platform cert
                for (String packageName : pm.getPackagesForUid(uid)) {
                    final HashSet<Signature> packageSignature = Sets.newHashSet(
                            pm.getPackageInfo(packageName, GET_SIGNATURES).signatures);
                    if (packageSignature.containsAll(systemSignature)) {
                        return false;
                    }
                }
            } catch (NameNotFoundException e) {
            }
        }

        // nothing found above; we can apply policy to UID
        return true;
    }

    /**
     * @hide
     */
    public static String uidRulesToString(int uidRules) {
        final StringBuilder string = new StringBuilder().append(uidRules).append(" (");
        if (uidRules == RULE_NONE) {
            string.append("NONE");
        } else {
            string.append(DebugUtils.flagsToString(NetworkPolicyManager.class, "RULE_", uidRules));
        }
        string.append(")");
        return string.toString();
    }

    /**
     * @hide
     */
    public static String uidPoliciesToString(int uidPolicies) {
        final StringBuilder string = new StringBuilder().append(uidPolicies).append(" (");
        if (uidPolicies == POLICY_NONE) {
            string.append("NONE");
        } else {
            string.append(DebugUtils.flagsToString(NetworkPolicyManager.class,
                    "POLICY_", uidPolicies));
        }
        string.append(")");
        return string.toString();
    }

    /**
     * Returns true if {@param procState} is considered foreground and as such will be allowed
     * to access network when the device is idle or in battery saver mode. Otherwise, false.
     */
    public static boolean isProcStateAllowedWhileIdleOrPowerSaveMode(int procState) {
        return procState <= FOREGROUND_THRESHOLD_STATE;
    }

    /**
     * Returns true if {@param procState} is considered foreground and as such will be allowed
     * to access network when the device is in data saver mode. Otherwise, false.
     */
    public static boolean isProcStateAllowedWhileOnRestrictBackground(int procState) {
        return procState <= FOREGROUND_THRESHOLD_STATE;
    }

    public static String resolveNetworkId(WifiConfiguration config) {
        return WifiInfo.removeDoubleQuotes(config.isPasspoint()
                ? config.providerFriendlyName : config.SSID);
    }

    public static String resolveNetworkId(String ssid) {
        return WifiInfo.removeDoubleQuotes(ssid);
    }

    /** {@hide} */
    public static class Listener extends INetworkPolicyListener.Stub {
        @Override public void onUidRulesChanged(int uid, int uidRules) { }
        @Override public void onMeteredIfacesChanged(String[] meteredIfaces) { }
        @Override public void onRestrictBackgroundChanged(boolean restrictBackground) { }
        @Override public void onUidPoliciesChanged(int uid, int uidPolicies) { }
        @Override public void onSubscriptionOverride(int subId, int overrideMask, int overrideValue) { }
    }
}
