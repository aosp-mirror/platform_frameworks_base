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

import static android.app.ActivityManager.procStateToString;
import static android.content.pm.PackageManager.GET_SIGNATURES;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessCapability;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.telephony.Annotation;
import android.telephony.SubscriptionPlan;
import android.util.DebugUtils;
import android.util.Pair;
import android.util.Range;

import com.android.internal.util.function.pooled.PooledLambda;

import com.google.android.collect.Sets;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Manager for creating and modifying network policy rules.
 *
 * @hide
 */
@TestApi
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@SystemService(Context.NETWORK_POLICY_SERVICE)
public class NetworkPolicyManager {

    /* POLICY_* are masks and can be ORed, although currently they are not.*/
    /**
     * No specific network policy, use system default.
     * @hide
     */
    public static final int POLICY_NONE = 0x0;
    /**
     * Reject network usage on metered networks when application in background.
     * @hide
     */
    public static final int POLICY_REJECT_METERED_BACKGROUND = 0x1;
    /**
     * Allow metered network use in the background even when in data usage save mode.
     * @hide
     */
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
     * is allowlisted for the former but not the latter, its status would be
     * RULE_REJECT_METERED | RULE_ALLOW_ALL, meaning it could have access to non-metered
     * networks but not to metered networks.
     *
     * See network-policy-restrictions.md for more info.
     */

    /**
     * No specific rule was set
     * @hide
     */
    public static final int RULE_NONE = 0;
    /**
     * Allow traffic on metered networks.
     * @hide
     */
    public static final int RULE_ALLOW_METERED = 1 << 0;
    /**
     * Temporarily allow traffic on metered networks because app is on foreground.
     * @hide
     */
    public static final int RULE_TEMPORARY_ALLOW_METERED = 1 << 1;
    /**
     * Reject traffic on metered networks.
     * @hide
     */
    public static final int RULE_REJECT_METERED = 1 << 2;
    /**
     * Network traffic should be allowed on all networks (metered or non-metered), although
     * metered-network restrictions could still apply.
     * @hide
     */
    public static final int RULE_ALLOW_ALL = 1 << 5;
    /**
     * Reject traffic on all networks.
     * @hide
     */
    public static final int RULE_REJECT_ALL = 1 << 6;
    /**
     * Reject traffic on all networks for restricted networking mode.
     * @hide
     */
    public static final int RULE_REJECT_RESTRICTED_MODE = 1 << 10;

    /**
     * Mask used to get the {@code RULE_xxx_METERED} rules
     * @hide
     */
    public static final int MASK_METERED_NETWORKS = 0b000000001111;
    /**
     * Mask used to get the {@code RULE_xxx_ALL} rules
     * @hide
     */
    public static final int MASK_ALL_NETWORKS     = 0b000011110000;
    /**
     * Mask used to get the {@code RULE_xxx_RESTRICTED_MODE} rules
     * @hide
     */
    public static final int MASK_RESTRICTED_MODE_NETWORKS     = 0b111100000000;

    /** @hide */
    public static final int FIREWALL_RULE_DEFAULT = 0;
    /** @hide */
    public static final String FIREWALL_CHAIN_NAME_NONE = "none";
    /** @hide */
    public static final String FIREWALL_CHAIN_NAME_DOZABLE = "dozable";
    /** @hide */
    public static final String FIREWALL_CHAIN_NAME_STANDBY = "standby";
    /** @hide */
    public static final String FIREWALL_CHAIN_NAME_POWERSAVE = "powersave";
    /** @hide */
    public static final String FIREWALL_CHAIN_NAME_RESTRICTED = "restricted";

    private static final boolean ALLOW_PLATFORM_APP_POLICY = true;

    /** @hide */
    public static final int FOREGROUND_THRESHOLD_STATE =
            ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;

    /**
     * {@link Intent} extra that indicates which {@link NetworkTemplate} rule it
     * applies to.
     * @hide
     */
    public static final String EXTRA_NETWORK_TEMPLATE = "android.net.NETWORK_TEMPLATE";

    /**
     * Mask used to check if an override value is marked as unmetered.
     * @hide
     */
    public static final int SUBSCRIPTION_OVERRIDE_UNMETERED = 1 << 0;

    /**
     * Mask used to check if an override value is marked as congested.
     * @hide
     */
    public static final int SUBSCRIPTION_OVERRIDE_CONGESTED = 1 << 1;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "SUBSCRIPTION_OVERRIDE_" }, value = {
        SUBSCRIPTION_OVERRIDE_UNMETERED,
        SUBSCRIPTION_OVERRIDE_CONGESTED
    })
    public @interface SubscriptionOverrideMask {}

    /**
     * Flag to indicate that app is not exempt from any network restrictions.
     *
     * @hide
     */
    public static final int ALLOWED_REASON_NONE = 0;
    /**
     * Flag to indicate that app is exempt from certain network restrictions because of it being a
     * system component.
     *
     * @hide
     */
    public static final int ALLOWED_REASON_SYSTEM = 1 << 0;
    /**
     * Flag to indicate that app is exempt from certain network restrictions because of it being
     * in the foreground.
     *
     * @hide
     */
    public static final int ALLOWED_REASON_FOREGROUND = 1 << 1;
    /**
     * Flag to indicate that app is exempt from certain network restrictions because of it being
     * in the {@code allow-in-power-save} list.
     *
     * @hide
     */
    public static final int ALLOWED_REASON_POWER_SAVE_ALLOWLIST = 1 << 2;
    /**
     * Flag to indicate that app is exempt from certain network restrictions because of it being
     * in the {@code allow-in-power-save-except-idle} list.
     *
     * @hide
     */
    public static final int ALLOWED_REASON_POWER_SAVE_EXCEPT_IDLE_ALLOWLIST = 1 << 3;
    /**
     * Flag to indicate that app is exempt from certain network restrictions because of it holding
     * certain privileged permissions.
     *
     * @hide
     */
    public static final int ALLOWED_REASON_RESTRICTED_MODE_PERMISSIONS = 1 << 4;
    /**
     * Flag to indicate that app is exempt from certain metered network restrictions because user
     * explicitly exempted it.
     *
     * @hide
     */
    public static final int ALLOWED_METERED_REASON_USER_EXEMPTED = 1 << 16;
    /**
     * Flag to indicate that app is exempt from certain metered network restrictions because of it
     * being a system component.
     *
     * @hide
     */
    public static final int ALLOWED_METERED_REASON_SYSTEM = 1 << 17;
    /**
     * Flag to indicate that app is exempt from certain metered network restrictions because of it
     * being in the foreground.
     *
     * @hide
     */
    public static final int ALLOWED_METERED_REASON_FOREGROUND = 1 << 18;

    /** @hide */
    public static final int ALLOWED_METERED_REASON_MASK = 0xffff0000;

    private final Context mContext;
    @UnsupportedAppUsage
    private INetworkPolicyManager mService;

    private final Map<SubscriptionCallback, SubscriptionCallbackProxy>
            mSubscriptionCallbackMap = new ConcurrentHashMap<>();
    private final Map<NetworkPolicyCallback, NetworkPolicyCallbackProxy>
            mNetworkPolicyCallbackMap = new ConcurrentHashMap<>();

    /** @hide */
    public NetworkPolicyManager(Context context, INetworkPolicyManager service) {
        if (service == null) {
            throw new IllegalArgumentException("missing INetworkPolicyManager");
        }
        mContext = context;
        mService = service;
    }

    /** @hide */
    @UnsupportedAppUsage
    public static NetworkPolicyManager from(Context context) {
        return (NetworkPolicyManager) context.getSystemService(Context.NETWORK_POLICY_SERVICE);
    }

    /**
     * Set policy flags for specific UID.
     *
     * @param policy should be {@link #POLICY_NONE} or any combination of {@code POLICY_} flags,
     *     although it is not validated.
     * @hide
     */
    @UnsupportedAppUsage
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
     * @hide
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
     * @hide
     */
    public void removeUidPolicy(int uid, int policy) {
        try {
            mService.removeUidPolicy(uid, policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getUidPolicy(int uid) {
        try {
            return mService.getUidPolicy(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public int[] getUidsWithPolicy(int policy) {
        try {
            return mService.getUidsWithPolicy(policy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void registerListener(INetworkPolicyListener listener) {
        try {
            mService.registerListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void unregisterListener(INetworkPolicyListener listener) {
        try {
            mService.unregisterListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @RequiresPermission(android.Manifest.permission.OBSERVE_NETWORK_POLICY)
    public void registerSubscriptionCallback(@NonNull SubscriptionCallback callback) {
        if (callback == null) {
            throw new NullPointerException("Callback cannot be null.");
        }

        final SubscriptionCallbackProxy callbackProxy = new SubscriptionCallbackProxy(callback);
        if (null != mSubscriptionCallbackMap.putIfAbsent(callback, callbackProxy)) {
            throw new IllegalArgumentException("Callback is already registered.");
        }
        registerListener(callbackProxy);
    }

    /** @hide */
    @RequiresPermission(android.Manifest.permission.OBSERVE_NETWORK_POLICY)
    public void unregisterSubscriptionCallback(@NonNull SubscriptionCallback callback) {
        if (callback == null) {
            throw new NullPointerException("Callback cannot be null.");
        }

        final SubscriptionCallbackProxy callbackProxy = mSubscriptionCallbackMap.remove(callback);
        if (callbackProxy == null) return;

        unregisterListener(callbackProxy);
    }

    /** @hide */
    public void setNetworkPolicies(NetworkPolicy[] policies) {
        try {
            mService.setNetworkPolicies(policies);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public NetworkPolicy[] getNetworkPolicies() {
        try {
            return mService.getNetworkPolicies(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @TestApi
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setRestrictBackground(boolean restrictBackground) {
        try {
            mService.setRestrictBackground(restrictBackground);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @TestApi
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean getRestrictBackground() {
        try {
            return mService.getRestrictBackground();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Determines if an UID is subject to metered network restrictions while running in background.
     *
     * @param uid The UID whose status needs to be checked.
     * @return {@link ConnectivityManager#RESTRICT_BACKGROUND_STATUS_DISABLED},
     *         {@link ConnectivityManager##RESTRICT_BACKGROUND_STATUS_ENABLED},
     *         or {@link ConnectivityManager##RESTRICT_BACKGROUND_STATUS_WHITELISTED} to denote
     *         the current status of the UID.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK)
    public int getRestrictBackgroundStatus(int uid) {
        try {
            return mService.getRestrictBackgroundStatus(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Override connections to be temporarily marked as either unmetered or congested,
     * along with automatic timeouts if desired.
     *
     * @param subId the subscriber ID this override applies to.
     * @param overrideMask the bitmask that specifies which of the overrides is being
     *            set or cleared.
     * @param overrideValue the override values to set or clear.
     * @param networkTypes the network types this override applies to. If no
     *            network types are specified, override values will be ignored.
     *            {@see TelephonyManager#getAllNetworkTypes()}
     * @param timeoutMillis the timeout after which the requested override will
     *            be automatically cleared, or {@code 0} to leave in the
     *            requested state until explicitly cleared, or the next reboot,
     *            whichever happens first
     * @param callingPackage the name of the package making the call.
     * @hide
     */
    public void setSubscriptionOverride(int subId, @SubscriptionOverrideMask int overrideMask,
            @SubscriptionOverrideMask int overrideValue,
            @NonNull @Annotation.NetworkType int[] networkTypes, long timeoutMillis,
            @NonNull String callingPackage) {
        try {
            mService.setSubscriptionOverride(subId, overrideMask, overrideValue, networkTypes,
                    timeoutMillis, callingPackage);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the subscription plans for a specific subscriber.
     *
     * @param subId the subscriber this relationship applies to.
     * @param plans the list of plans.
     * @param callingPackage the name of the package making the call
     * @hide
     */
    public void setSubscriptionPlans(int subId, @NonNull SubscriptionPlan[] plans,
            @NonNull String callingPackage) {
        try {
            mService.setSubscriptionPlans(subId, plans, callingPackage);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get subscription plans for the given subscription id.
     *
     * @param subId the subscriber to get the subscription plans for.
     * @param callingPackage the name of the package making the call.
     * @hide
     */
    @NonNull
    public SubscriptionPlan[] getSubscriptionPlans(int subId, @NonNull String callingPackage) {
        try {
            return mService.getSubscriptionPlans(subId, callingPackage);
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

    /**
     * Check that networking is blocked for the given uid.
     *
     * @param uid The target uid.
     * @param meteredNetwork True if the network is metered.
     * @return true if networking is blocked for the given uid according to current networking
     *         policies.
     */
    @RequiresPermission(android.Manifest.permission.OBSERVE_NETWORK_POLICY)
    public boolean isUidNetworkingBlocked(int uid, boolean meteredNetwork) {
        try {
            return mService.isUidNetworkingBlocked(uid, meteredNetwork);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check that the given uid is restricted from doing networking on metered networks.
     *
     * @param uid The target uid.
     * @return true if the given uid is restricted from doing networking on metered networks.
     */
    @RequiresPermission(android.Manifest.permission.OBSERVE_NETWORK_POLICY)
    public boolean isUidRestrictedOnMeteredNetworks(int uid) {
        try {
            return mService.isUidRestrictedOnMeteredNetworks(uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets a hint on whether it is desirable to use multipath data transfer on the given network.
     *
     * @return One of the ConnectivityManager.MULTIPATH_PREFERENCE_* constants.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK)
    public int getMultipathPreference(@NonNull Network network) {
        try {
            return mService.getMultipathPreference(network);
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
                if (hasNext()) {
                    final Range<ZonedDateTime> r = it.next();
                    return Pair.create(r.getLower(), r.getUpper());
                } else {
                    return Pair.create(null, null);
                }
            }
        };
    }

    /**
     * Check if given UID can have a {@link #setUidPolicy(int, int)} defined,
     * usually to protect critical system services.
     * @hide
     */
    @Deprecated
    public static boolean isUidValidForPolicy(Context context, int uid) {
        // first, quick-reject non-applications
        if (!Process.isApplicationUid(uid)) {
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
     * @hide
     */
    public static boolean isProcStateAllowedWhileIdleOrPowerSaveMode(@Nullable UidState uidState) {
        if (uidState == null) {
            return false;
        }
        return isProcStateAllowedWhileIdleOrPowerSaveMode(uidState.procState, uidState.capability);
    }

    /** @hide */
    public static boolean isProcStateAllowedWhileIdleOrPowerSaveMode(
            int procState, @ProcessCapability int capability) {
        return procState <= FOREGROUND_THRESHOLD_STATE
                || (capability & ActivityManager.PROCESS_CAPABILITY_NETWORK) != 0;
    }

    /**
     * Returns true if {@param procState} is considered foreground and as such will be allowed
     * to access network when the device is in data saver mode. Otherwise, false.
     * @hide
     */
    public static boolean isProcStateAllowedWhileOnRestrictBackground(@Nullable UidState uidState) {
        if (uidState == null) {
            return false;
        }
        return isProcStateAllowedWhileOnRestrictBackground(uidState.procState);
    }

    /** @hide */
    public static boolean isProcStateAllowedWhileOnRestrictBackground(int procState) {
        // Data saver and bg policy restrictions will only take procstate into account.
        return procState <= FOREGROUND_THRESHOLD_STATE;
    }

    /** @hide */
    public static final class UidState {
        public int uid;
        public int procState;
        public int capability;

        public UidState(int uid, int procState, int capability) {
            this.uid = uid;
            this.procState = procState;
            this.capability = capability;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("{procState=");
            sb.append(procStateToString(procState));
            sb.append(",cap=");
            ActivityManager.printCapabilitiesSummary(sb, capability);
            sb.append("}");
            return sb.toString();
        }
    }

    /** @hide */
    @TestApi
    @NonNull
    public static String resolveNetworkId(@NonNull WifiConfiguration config) {
        return WifiInfo.sanitizeSsid(config.isPasspoint()
                ? config.providerFriendlyName : config.SSID);
    }

    /** @hide */
    public static String resolveNetworkId(String ssid) {
        return WifiInfo.sanitizeSsid(ssid);
    }

    /**
     * Returns the {@code string} representation of {@code blockedReasons} argument.
     *
     * @param blockedReasons Value indicating the reasons for why the network access of an UID is
     *                       blocked.
     * @hide
     */
    @NonNull
    public static String blockedReasonsToString(int blockedReasons) {
        return DebugUtils.flagsToString(ConnectivityManager.class, "BLOCKED_", blockedReasons);
    }

    /** @hide */
    @NonNull
    public static String allowedReasonsToString(int allowedReasons) {
        return DebugUtils.flagsToString(NetworkPolicyManager.class, "ALLOWED_", allowedReasons);
    }

    /**
     * Register a {@link NetworkPolicyCallback} to listen for changes to network blocked status
     * of apps.
     *
     * Note that when a caller tries to register a new callback, it might replace a previously
     * registered callback if it is considered equal to the new one, based on the
     * {@link Object#equals(Object)} check.
     *
     * @param executor The {@link Executor} to run the callback on.
     * @param callback The {@link NetworkPolicyCallback} to be registered.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(android.Manifest.permission.OBSERVE_NETWORK_POLICY)
    public void registerNetworkPolicyCallback(@Nullable Executor executor,
            @NonNull NetworkPolicyCallback callback) {
        if (callback == null) {
            throw new NullPointerException("Callback cannot be null.");
        }

        final NetworkPolicyCallbackProxy callbackProxy = new NetworkPolicyCallbackProxy(
                executor, callback);
        registerListener(callbackProxy);
        mNetworkPolicyCallbackMap.put(callback, callbackProxy);
    }

    /**
     * Unregister a previously registered {@link NetworkPolicyCallback}.
     *
     * @param callback The {@link NetworkPolicyCallback} to be unregistered.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(android.Manifest.permission.OBSERVE_NETWORK_POLICY)
    public void unregisterNetworkPolicyCallback(@NonNull NetworkPolicyCallback callback) {
        if (callback == null) {
            throw new NullPointerException("Callback cannot be null.");
        }

        final NetworkPolicyCallbackProxy callbackProxy = mNetworkPolicyCallbackMap.remove(callback);
        if (callbackProxy == null) return;
        unregisterListener(callbackProxy);
    }

    /**
     * Interface for the callback to listen for changes to network blocked status of apps.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public interface NetworkPolicyCallback {
        /**
         * Called when the reason for why the network access of an UID is blocked changes.
         *
         * @param uid The UID for which the blocked status changed.
         * @param blockedReasons Value indicating the reasons for why the network access of an
         *                       UID is blocked.
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        default void onUidBlockedReasonChanged(int uid, int blockedReasons) {}
    }

    /** @hide */
    public static class NetworkPolicyCallbackProxy extends Listener {
        private final Executor mExecutor;
        private final NetworkPolicyCallback mCallback;

        NetworkPolicyCallbackProxy(@Nullable Executor executor,
                @NonNull NetworkPolicyCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onBlockedReasonChanged(int uid, int oldBlockedReasons, int newBlockedReasons) {
            if (oldBlockedReasons != newBlockedReasons) {
                dispatchOnUidBlockedReasonChanged(mExecutor, mCallback, uid, newBlockedReasons);
            }
        }
    }

    private static void dispatchOnUidBlockedReasonChanged(@Nullable Executor executor,
            @NonNull NetworkPolicyCallback callback, int uid, int blockedReasons) {
        if (executor == null) {
            callback.onUidBlockedReasonChanged(uid, blockedReasons);
        } else {
            executor.execute(PooledLambda.obtainRunnable(
                    NetworkPolicyCallback::onUidBlockedReasonChanged,
                    callback, uid, blockedReasons).recycleOnUse());
        }
    }

    /** @hide */
    public static class SubscriptionCallback {
        /**
         * Notify clients of a new override about a given subscription.
         *
         * @param subId the subscriber this override applies to.
         * @param overrideMask a bitmask that specifies which of the overrides is set.
         * @param overrideValue a bitmask that specifies the override values.
         * @param networkTypes the network types this override applies to.
         */
        public void onSubscriptionOverride(int subId, @SubscriptionOverrideMask int overrideMask,
                @SubscriptionOverrideMask int overrideValue, int[] networkTypes) {}

        /**
         * Notify of subscription plans change about a given subscription.
         *
         * @param subId the subscriber id that got subscription plans change.
         * @param plans the list of subscription plans.
         */
        public void onSubscriptionPlansChanged(int subId, @NonNull SubscriptionPlan[] plans) {}
    }

    /**
     * SubscriptionCallback proxy for SubscriptionCallback object.
     * @hide
     */
    public class SubscriptionCallbackProxy extends Listener {
        private final SubscriptionCallback mCallback;

        SubscriptionCallbackProxy(SubscriptionCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onSubscriptionOverride(int subId, @SubscriptionOverrideMask int overrideMask,
                @SubscriptionOverrideMask int overrideValue, int[] networkTypes) {
            mCallback.onSubscriptionOverride(subId, overrideMask, overrideValue, networkTypes);
        }

        @Override
        public void onSubscriptionPlansChanged(int subId, SubscriptionPlan[] plans) {
            mCallback.onSubscriptionPlansChanged(subId, plans);
        }
    }

    /** {@hide} */
    public static class Listener extends INetworkPolicyListener.Stub {
        @Override public void onUidRulesChanged(int uid, int uidRules) { }
        @Override public void onMeteredIfacesChanged(String[] meteredIfaces) { }
        @Override public void onRestrictBackgroundChanged(boolean restrictBackground) { }
        @Override public void onUidPoliciesChanged(int uid, int uidPolicies) { }
        @Override public void onSubscriptionOverride(int subId, int overrideMask,
                int overrideValue, int[] networkTypes) { }
        @Override public void onSubscriptionPlansChanged(int subId, SubscriptionPlan[] plans) { }
        @Override public void onBlockedReasonChanged(int uid,
                int oldBlockedReasons, int newBlockedReasons) { }
    }
}
