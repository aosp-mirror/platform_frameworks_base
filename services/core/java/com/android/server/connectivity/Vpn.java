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

package com.android.server.connectivity;

import static android.Manifest.permission.BIND_VPN_SERVICE;
import static android.net.ConnectivityManager.NETID_UNSET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.RouteInfo.RTN_THROW;
import static android.net.RouteInfo.RTN_UNREACHABLE;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.Ikev2VpnProfile;
import android.net.IpPrefix;
import android.net.IpSecManager;
import android.net.IpSecManager.IpSecTunnelInterface;
import android.net.IpSecTransform;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkProvider;
import android.net.RouteInfo;
import android.net.UidRange;
import android.net.VpnManager;
import android.net.VpnService;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionConfiguration;
import android.net.ipsec.ike.ChildSessionParams;
import android.net.ipsec.ike.IkeSession;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionParams;
import android.os.Binder;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemService;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;
import com.android.internal.net.VpnProfile;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import com.android.server.ConnectivityService;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.net.BaseNetworkObserver;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @hide
 */
public class Vpn {
    private static final String NETWORKTYPE = "VPN";
    private static final String TAG = "Vpn";
    private static final boolean LOGD = true;

    // Length of time (in milliseconds) that an app hosting an always-on VPN is placed on
    // the device idle whitelist during service launch and VPN bootstrap.
    private static final long VPN_LAUNCH_IDLE_WHITELIST_DURATION_MS = 60 * 1000;

    // Settings for how much of the address space should be routed so that Vpn considers
    // "most" of the address space is routed. This is used to determine whether this Vpn
    // should be marked with the INTERNET capability.
    private static final long MOST_IPV4_ADDRESSES_COUNT;
    private static final BigInteger MOST_IPV6_ADDRESSES_COUNT;
    static {
        // 85% of the address space must be routed for Vpn to consider this VPN to provide
        // INTERNET access.
        final int howManyPercentIsMost = 85;

        final long twoPower32 = 1L << 32;
        MOST_IPV4_ADDRESSES_COUNT = twoPower32 * howManyPercentIsMost / 100;
        final BigInteger twoPower128 = BigInteger.ONE.shiftLeft(128);
        MOST_IPV6_ADDRESSES_COUNT = twoPower128
                .multiply(BigInteger.valueOf(howManyPercentIsMost))
                .divide(BigInteger.valueOf(100));
    }
    // How many routes to evaluate before bailing and declaring this Vpn should provide
    // the INTERNET capability. This is necessary because computing the address space is
    // O(n²) and this is running in the system service, so a limit is needed to alleviate
    // the risk of attack.
    // This is taken as a total of IPv4 + IPV6 routes for simplicity, but the algorithm
    // is actually O(n²)+O(n²).
    private static final int MAX_ROUTES_TO_EVALUATE = 150;

    /**
     * Largest profile size allowable for Platform VPNs.
     *
     * <p>The largest platform VPN profiles use IKEv2 RSA Certificate Authentication and have two
     * X509Certificates, and one RSAPrivateKey. This should lead to a max size of 2x 12kB for the
     * certificates, plus a reasonable upper bound on the private key of 32kB. The rest of the
     * profile is expected to be negligible in size.
     */
    @VisibleForTesting static final int MAX_VPN_PROFILE_SIZE_BYTES = 1 << 17; // 128kB

    // TODO: create separate trackers for each unique VPN to support
    // automated reconnection

    private final Context mContext;
    private final NetworkInfo mNetworkInfo;
    @VisibleForTesting protected String mPackage;
    private int mOwnerUID;
    private boolean mIsPackageTargetingAtLeastQ;
    private String mInterface;
    private Connection mConnection;

    /** Tracks the runners for all VPN types managed by the platform (eg. LegacyVpn, PlatformVpn) */
    @VisibleForTesting protected VpnRunner mVpnRunner;

    private PendingIntent mStatusIntent;
    private volatile boolean mEnableTeardown = true;
    private final INetworkManagementService mNetd;
    @VisibleForTesting
    protected VpnConfig mConfig;
    @VisibleForTesting
    protected NetworkAgent mNetworkAgent;
    private final Looper mLooper;
    @VisibleForTesting
    protected final NetworkCapabilities mNetworkCapabilities;
    private final SystemServices mSystemServices;
    private final Ikev2SessionCreator mIkev2SessionCreator;

    /**
     * Whether to keep the connection active after rebooting, or upgrading or reinstalling. This
     * only applies to {@link VpnService} connections.
     */
    @VisibleForTesting protected boolean mAlwaysOn = false;

    /**
     * Whether to disable traffic outside of this VPN even when the VPN is not connected. System
     * apps can still bypass by choosing explicit networks. Has no effect if {@link mAlwaysOn} is
     * not set. Applies to all types of VPNs.
     */
    @VisibleForTesting protected boolean mLockdown = false;

    /**
     * Set of packages in addition to the VPN app itself that can access the network directly when
     * VPN is not connected even if {@code mLockdown} is set.
     */
    private @NonNull List<String> mLockdownWhitelist = Collections.emptyList();

     /**
     * A memory of what UIDs this class told netd to block for the lockdown feature.
     *
     * Netd maintains ranges of UIDs for which network should be restricted to using only the VPN
     * for the lockdown feature. This class manages these UIDs and sends this information to netd.
     * To avoid sending the same commands multiple times (which would be wasteful) and to be able
     * to revoke lists (when the rules should change), it's simplest to keep this cache of what
     * netd knows, so it can be diffed and sent most efficiently.
     *
     * The contents of this list must only be changed when updating the UIDs lists with netd,
     * since it needs to keep in sync with the picture netd has of them.
     *
     * @see mLockdown
     */
    @GuardedBy("this")
    private final Set<UidRange> mBlockedUidsAsToldToNetd = new ArraySet<>();

    // Handle of the user initiating VPN.
    private final int mUserHandle;

    public Vpn(Looper looper, Context context, INetworkManagementService netService,
            @UserIdInt int userHandle, @NonNull KeyStore keyStore) {
        this(looper, context, netService, userHandle, keyStore,
                new SystemServices(context), new Ikev2SessionCreator());
    }

    @VisibleForTesting
    protected Vpn(Looper looper, Context context, INetworkManagementService netService,
            int userHandle, @NonNull KeyStore keyStore, SystemServices systemServices,
            Ikev2SessionCreator ikev2SessionCreator) {
        mContext = context;
        mNetd = netService;
        mUserHandle = userHandle;
        mLooper = looper;
        mSystemServices = systemServices;
        mIkev2SessionCreator = ikev2SessionCreator;

        mPackage = VpnConfig.LEGACY_VPN;
        mOwnerUID = getAppUid(mPackage, mUserHandle);
        mIsPackageTargetingAtLeastQ = doesPackageTargetAtLeastQ(mPackage);

        try {
            netService.registerObserver(mObserver);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Problem registering observer", e);
        }

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_VPN, 0 /* subtype */, NETWORKTYPE,
                "" /* subtypeName */);
        mNetworkCapabilities = new NetworkCapabilities();
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        mNetworkCapabilities.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        updateCapabilities(null /* defaultNetwork */);

        loadAlwaysOnPackage(keyStore);
    }

    /**
     * Set whether this object is responsible for watching for {@link NetworkInfo}
     * teardown. When {@code false}, teardown is handled externally by someone
     * else.
     */
    public void setEnableTeardown(boolean enableTeardown) {
        mEnableTeardown = enableTeardown;
    }

    /**
     * Update current state, dispatching event to listeners.
     */
    @VisibleForTesting
    protected void updateState(DetailedState detailedState, String reason) {
        if (LOGD) Log.d(TAG, "setting state=" + detailedState + ", reason=" + reason);
        mNetworkInfo.setDetailedState(detailedState, reason, null);
        if (mNetworkAgent != null) {
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
        }
        updateAlwaysOnNotification(detailedState);
    }

    /**
     * Updates {@link #mNetworkCapabilities} based on current underlying networks and returns a
     * defensive copy.
     *
     * <p>Does not propagate updated capabilities to apps.
     *
     * @param defaultNetwork underlying network for VPNs following platform's default
     */
    public synchronized NetworkCapabilities updateCapabilities(
            @Nullable Network defaultNetwork) {
        if (mConfig == null) {
            // VPN is not running.
            return null;
        }

        Network[] underlyingNetworks = mConfig.underlyingNetworks;
        if (underlyingNetworks == null && defaultNetwork != null) {
            // null underlying networks means to track the default.
            underlyingNetworks = new Network[] { defaultNetwork };
        }
        // Only apps targeting Q and above can explicitly declare themselves as metered.
        final boolean isAlwaysMetered = mIsPackageTargetingAtLeastQ && mConfig.isMetered;

        applyUnderlyingCapabilities(
                mContext.getSystemService(ConnectivityManager.class),
                underlyingNetworks,
                mNetworkCapabilities,
                isAlwaysMetered);

        return new NetworkCapabilities(mNetworkCapabilities);
    }

    @VisibleForTesting
    public static void applyUnderlyingCapabilities(
            ConnectivityManager cm,
            Network[] underlyingNetworks,
            NetworkCapabilities caps,
            boolean isAlwaysMetered) {
        int[] transportTypes = new int[] { NetworkCapabilities.TRANSPORT_VPN };
        int downKbps = NetworkCapabilities.LINK_BANDWIDTH_UNSPECIFIED;
        int upKbps = NetworkCapabilities.LINK_BANDWIDTH_UNSPECIFIED;
        // VPN's meteredness is OR'd with isAlwaysMetered and meteredness of its underlying
        // networks.
        boolean metered = isAlwaysMetered;
        boolean roaming = false;
        boolean congested = false;

        boolean hadUnderlyingNetworks = false;
        if (null != underlyingNetworks) {
            for (Network underlying : underlyingNetworks) {
                // TODO(b/124469351): Get capabilities directly from ConnectivityService instead.
                final NetworkCapabilities underlyingCaps = cm.getNetworkCapabilities(underlying);
                if (underlyingCaps == null) continue;
                hadUnderlyingNetworks = true;
                for (int underlyingType : underlyingCaps.getTransportTypes()) {
                    transportTypes = ArrayUtils.appendInt(transportTypes, underlyingType);
                }

                // When we have multiple networks, we have to assume the
                // worst-case link speed and restrictions.
                downKbps = NetworkCapabilities.minBandwidth(downKbps,
                        underlyingCaps.getLinkDownstreamBandwidthKbps());
                upKbps = NetworkCapabilities.minBandwidth(upKbps,
                        underlyingCaps.getLinkUpstreamBandwidthKbps());
                metered |= !underlyingCaps.hasCapability(NET_CAPABILITY_NOT_METERED);
                roaming |= !underlyingCaps.hasCapability(NET_CAPABILITY_NOT_ROAMING);
                congested |= !underlyingCaps.hasCapability(NET_CAPABILITY_NOT_CONGESTED);
            }
        }
        if (!hadUnderlyingNetworks) {
            // No idea what the underlying networks are; assume sane defaults
            metered = true;
            roaming = false;
            congested = false;
        }

        caps.setTransportTypes(transportTypes);
        caps.setLinkDownstreamBandwidthKbps(downKbps);
        caps.setLinkUpstreamBandwidthKbps(upKbps);
        caps.setCapability(NET_CAPABILITY_NOT_METERED, !metered);
        caps.setCapability(NET_CAPABILITY_NOT_ROAMING, !roaming);
        caps.setCapability(NET_CAPABILITY_NOT_CONGESTED, !congested);
    }

    /**
     * Chooses whether to force all connections to go though VPN.
     *
     * Used to enable/disable legacy VPN lockdown.
     *
     * This uses the same ip rule mechanism as
     * {@link #setAlwaysOnPackage(String, boolean, List<String>)}; previous settings from calling
     * that function will be replaced and saved with the always-on state.
     *
     * @param lockdown whether to prevent all traffic outside of a VPN.
     */
    public synchronized void setLockdown(boolean lockdown) {
        enforceControlPermissionOrInternalCaller();

        setVpnForcedLocked(lockdown);
        mLockdown = lockdown;

        // Update app lockdown setting if it changed. Legacy VPN lockdown status is controlled by
        // LockdownVpnTracker.isEnabled() which keeps track of its own state.
        if (mAlwaysOn) {
            saveAlwaysOnPackage();
        }
    }

    /**
     * Check whether to prevent all traffic outside of a VPN even when the VPN is not connected.
     *
     * @return {@code true} if VPN lockdown is enabled.
     */
    public synchronized boolean getLockdown() {
        return mLockdown;
    }

    /**
     * Returns whether VPN is configured as always-on.
     */
    public synchronized boolean getAlwaysOn() {
        return mAlwaysOn;
    }

    /**
     * Checks if a VPN app supports always-on mode.
     *
     * <p>In order to support the always-on feature, an app has to either have an installed
     * PlatformVpnProfile, or:
     *
     * <ul>
     *   <li>target {@link VERSION_CODES#N API 24} or above, and
     *   <li>not opt out through the {@link VpnService#SERVICE_META_DATA_SUPPORTS_ALWAYS_ON}
     *       meta-data field.
     * </ul>
     *
     * @param packageName the canonical package name of the VPN app
     * @param keyStore the keystore instance to use for checking if the app has a Platform VPN
     *     profile installed.
     * @return {@code true} if and only if the VPN app exists and supports always-on mode
     */
    public boolean isAlwaysOnPackageSupported(String packageName, @NonNull KeyStore keyStore) {
        enforceSettingsPermission();

        if (packageName == null) {
            return false;
        }

        final long oldId = Binder.clearCallingIdentity();
        try {
            if (getVpnProfilePrivileged(packageName, keyStore) != null) {
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }

        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo appInfo = null;
        try {
            appInfo = pm.getApplicationInfoAsUser(packageName, 0 /*flags*/, mUserHandle);
        } catch (NameNotFoundException unused) {
            Log.w(TAG, "Can't find \"" + packageName + "\" when checking always-on support");
        }
        if (appInfo == null || appInfo.targetSdkVersion < VERSION_CODES.N) {
            return false;
        }

        final Intent intent = new Intent(VpnConfig.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        List<ResolveInfo> services =
                pm.queryIntentServicesAsUser(intent, PackageManager.GET_META_DATA, mUserHandle);
        if (services == null || services.size() == 0) {
            return false;
        }

        for (ResolveInfo rInfo : services) {
            final Bundle metaData = rInfo.serviceInfo.metaData;
            if (metaData != null &&
                    !metaData.getBoolean(VpnService.SERVICE_META_DATA_SUPPORTS_ALWAYS_ON, true)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Configures an always-on VPN connection through a specific application. This connection is
     * automatically granted and persisted after a reboot.
     *
     * <p>The designated package should either have a PlatformVpnProfile installed, or declare a
     * {@link VpnService} in its manifest guarded by {@link
     * android.Manifest.permission.BIND_VPN_SERVICE}, otherwise the call will fail.
     *
     * <p>Note that this method does not check if the VPN app supports always-on mode. The check is
     * delayed to {@link #startAlwaysOnVpn()}, which is always called immediately after this method
     * in {@link android.net.IConnectivityManager#setAlwaysOnVpnPackage}.
     *
     * @param packageName the package to designate as always-on VPN supplier.
     * @param lockdown whether to prevent traffic outside of a VPN, for example while connecting.
     * @param lockdownWhitelist packages to be whitelisted from lockdown.
     * @param keyStore the Keystore instance to use for checking of PlatformVpnProfile(s)
     * @return {@code true} if the package has been set as always-on, {@code false} otherwise.
     */
    public synchronized boolean setAlwaysOnPackage(
            @Nullable String packageName,
            boolean lockdown,
            @Nullable List<String> lockdownWhitelist,
            @NonNull KeyStore keyStore) {
        enforceControlPermissionOrInternalCaller();

        if (setAlwaysOnPackageInternal(packageName, lockdown, lockdownWhitelist, keyStore)) {
            saveAlwaysOnPackage();
            return true;
        }
        return false;
    }

    /**
     * Configures an always-on VPN connection through a specific application, the same as {@link
     * #setAlwaysOnPackage}.
     *
     * <p>Does not perform permission checks. Does not persist any of the changes to storage.
     *
     * @param packageName the package to designate as always-on VPN supplier.
     * @param lockdown whether to prevent traffic outside of a VPN, for example while connecting.
     * @param lockdownWhitelist packages to be whitelisted from lockdown. This is only used if
     *     {@code lockdown} is {@code true}. Packages must not contain commas.
     * @param keyStore the system keystore instance to check for profiles
     * @return {@code true} if the package has been set as always-on, {@code false} otherwise.
     */
    @GuardedBy("this")
    private boolean setAlwaysOnPackageInternal(
            @Nullable String packageName, boolean lockdown,
            @Nullable List<String> lockdownWhitelist, @NonNull KeyStore keyStore) {
        if (VpnConfig.LEGACY_VPN.equals(packageName)) {
            Log.w(TAG, "Not setting legacy VPN \"" + packageName + "\" as always-on.");
            return false;
        }

        if (lockdownWhitelist != null) {
            for (String pkg : lockdownWhitelist) {
                if (pkg.contains(",")) {
                    Log.w(TAG, "Not setting always-on vpn, invalid whitelisted package: " + pkg);
                    return false;
                }
            }
        }

        if (packageName != null) {
            final VpnProfile profile;
            final long oldId = Binder.clearCallingIdentity();
            try {
                profile = getVpnProfilePrivileged(packageName, keyStore);
            } finally {
                Binder.restoreCallingIdentity(oldId);
            }

            // Pre-authorize new always-on VPN package.
            final int grantType =
                    (profile == null) ? VpnManager.TYPE_VPN_SERVICE : VpnManager.TYPE_VPN_PLATFORM;
            if (!setPackageAuthorization(packageName, grantType)) {
                return false;
            }
            mAlwaysOn = true;
        } else {
            packageName = VpnConfig.LEGACY_VPN;
            mAlwaysOn = false;
        }

        mLockdown = (mAlwaysOn && lockdown);
        mLockdownWhitelist = (mLockdown && lockdownWhitelist != null)
                ? Collections.unmodifiableList(new ArrayList<>(lockdownWhitelist))
                : Collections.emptyList();

        if (isCurrentPreparedPackage(packageName)) {
            updateAlwaysOnNotification(mNetworkInfo.getDetailedState());
            setVpnForcedLocked(mLockdown);
        } else {
            // Prepare this app. The notification will update as a side-effect of updateState().
            // It also calls setVpnForcedLocked().
            prepareInternal(packageName);
        }
        return true;
    }

    private static boolean isNullOrLegacyVpn(String packageName) {
        return packageName == null || VpnConfig.LEGACY_VPN.equals(packageName);
    }

    /**
     * @return the package name of the VPN controller responsible for always-on VPN,
     *         or {@code null} if none is set or always-on VPN is controlled through
     *         lockdown instead.
     */
    public synchronized String getAlwaysOnPackage() {
        enforceControlPermissionOrInternalCaller();
        return (mAlwaysOn ? mPackage : null);
    }

    /**
     * @return an immutable list of packages whitelisted from always-on VPN lockdown.
     */
    public synchronized List<String> getLockdownWhitelist() {
        return mLockdown ? mLockdownWhitelist : null;
    }

    /**
     * Save the always-on package and lockdown config into Settings.Secure
     */
    @GuardedBy("this")
    private void saveAlwaysOnPackage() {
        final long token = Binder.clearCallingIdentity();
        try {
            mSystemServices.settingsSecurePutStringForUser(Settings.Secure.ALWAYS_ON_VPN_APP,
                    getAlwaysOnPackage(), mUserHandle);
            mSystemServices.settingsSecurePutIntForUser(Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN,
                    (mAlwaysOn && mLockdown ? 1 : 0), mUserHandle);
            mSystemServices.settingsSecurePutStringForUser(
                    Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST,
                    String.join(",", mLockdownWhitelist), mUserHandle);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Load the always-on package and lockdown config from Settings. */
    @GuardedBy("this")
    private void loadAlwaysOnPackage(@NonNull KeyStore keyStore) {
        final long token = Binder.clearCallingIdentity();
        try {
            final String alwaysOnPackage = mSystemServices.settingsSecureGetStringForUser(
                    Settings.Secure.ALWAYS_ON_VPN_APP, mUserHandle);
            final boolean alwaysOnLockdown = mSystemServices.settingsSecureGetIntForUser(
                    Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN, 0 /*default*/, mUserHandle) != 0;
            final String whitelistString = mSystemServices.settingsSecureGetStringForUser(
                    Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST, mUserHandle);
            final List<String> whitelistedPackages = TextUtils.isEmpty(whitelistString)
                    ? Collections.emptyList() : Arrays.asList(whitelistString.split(","));
            setAlwaysOnPackageInternal(
                    alwaysOnPackage, alwaysOnLockdown, whitelistedPackages, keyStore);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Starts the currently selected always-on VPN
     *
     * @param keyStore the keyStore instance for looking up PlatformVpnProfile(s)
     * @return {@code true} if the service was started, the service was already connected, or there
     *     was no always-on VPN to start. {@code false} otherwise.
     */
    public boolean startAlwaysOnVpn(@NonNull KeyStore keyStore) {
        final String alwaysOnPackage;
        synchronized (this) {
            alwaysOnPackage = getAlwaysOnPackage();
            // Skip if there is no service to start.
            if (alwaysOnPackage == null) {
                return true;
            }
            // Remove always-on VPN if it's not supported.
            if (!isAlwaysOnPackageSupported(alwaysOnPackage, keyStore)) {
                setAlwaysOnPackage(null, false, null, keyStore);
                return false;
            }
            // Skip if the service is already established. This isn't bulletproof: it's not bound
            // until after establish(), so if it's mid-setup onStartCommand will be sent twice,
            // which may restart the connection.
            if (getNetworkInfo().isConnected()) {
                return true;
            }
        }

        final long oldId = Binder.clearCallingIdentity();
        try {
            // Prefer VPN profiles, if any exist.
            VpnProfile profile = getVpnProfilePrivileged(alwaysOnPackage, keyStore);
            if (profile != null) {
                startVpnProfilePrivileged(profile, alwaysOnPackage,
                        null /* keyStore for private key retrieval - unneeded */);

                // If the above startVpnProfilePrivileged() call returns, the Ikev2VpnProfile was
                // correctly parsed, and the VPN has started running in a different thread. The only
                // other possibility is that the above call threw an exception, which will be
                // caught below, and returns false (clearing the always-on VPN). Once started, the
                // Platform VPN cannot permanently fail, and is resilient to temporary failures. It
                // will continue retrying until shut down by the user, or always-on is toggled off.
                return true;
            }

            // Tell the OS that background services in this app need to be allowed for
            // a short time, so we can bootstrap the VPN service.
            DeviceIdleInternal idleController =
                    LocalServices.getService(DeviceIdleInternal.class);
            idleController.addPowerSaveTempWhitelistApp(Process.myUid(), alwaysOnPackage,
                    VPN_LAUNCH_IDLE_WHITELIST_DURATION_MS, mUserHandle, false, "vpn");

            // Start the VPN service declared in the app's manifest.
            Intent serviceIntent = new Intent(VpnConfig.SERVICE_INTERFACE);
            serviceIntent.setPackage(alwaysOnPackage);
            try {
                return mContext.startServiceAsUser(serviceIntent, UserHandle.of(mUserHandle)) != null;
            } catch (RuntimeException e) {
                Log.e(TAG, "VpnService " + serviceIntent + " failed to start", e);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting always-on VPN", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
    }

    /**
     * Prepare for a VPN application. This method is designed to solve
     * race conditions. It first compares the current prepared package
     * with {@code oldPackage}. If they are the same, the prepared
     * package is revoked and replaced with {@code newPackage}. If
     * {@code oldPackage} is {@code null}, the comparison is omitted.
     * If {@code newPackage} is the same package or {@code null}, the
     * revocation is omitted. This method returns {@code true} if the
     * operation is succeeded.
     *
     * Legacy VPN is handled specially since it is not a real package.
     * It uses {@link VpnConfig#LEGACY_VPN} as its package name, and
     * it can be revoked by itself.
     *
     * The permission checks to verify that the VPN has already been granted
     * user consent are dependent on the type of the VPN being prepared. See
     * {@link AppOpsManager#OP_ACTIVATE_VPN} and {@link
     * AppOpsManager#OP_ACTIVATE_PLATFORM_VPN} for more information.
     *
     * Note: when we added VPN pre-consent in
     * https://android.googlesource.com/platform/frameworks/base/+/0554260
     * the names oldPackage and newPackage became misleading, because when
     * an app is pre-consented, we actually prepare oldPackage, not newPackage.
     *
     * Their meanings actually are:
     *
     * - oldPackage non-null, newPackage null: App calling VpnService#prepare().
     * - oldPackage null, newPackage non-null: ConfirmDialog calling prepareVpn().
     * - oldPackage null, newPackage=LEGACY_VPN: Used internally to disconnect
     *   and revoke any current app VPN and re-prepare legacy vpn.
     *
     * TODO: Rename the variables - or split this method into two - and end this confusion.
     * TODO: b/29032008 Migrate code from prepare(oldPackage=non-null, newPackage=LEGACY_VPN)
     * to prepare(oldPackage=null, newPackage=LEGACY_VPN)
     *
     * @param oldPackage The package name of the old VPN application
     * @param newPackage The package name of the new VPN application
     * @param vpnType The type of VPN being prepared. One of {@link VpnManager.VpnType} Preparing a
     *     platform VPN profile requires only the lesser ACTIVATE_PLATFORM_VPN appop.
     * @return true if the operation succeeded.
     */
    public synchronized boolean prepare(
            String oldPackage, String newPackage, @VpnManager.VpnType int vpnType) {
        if (oldPackage != null) {
            // Stop an existing always-on VPN from being dethroned by other apps.
            if (mAlwaysOn && !isCurrentPreparedPackage(oldPackage)) {
                return false;
            }

            // Package is not the same or old package was reinstalled.
            if (!isCurrentPreparedPackage(oldPackage)) {
                // The package doesn't match. We return false (to obtain user consent) unless the
                // user has already consented to that VPN package.
                if (!oldPackage.equals(VpnConfig.LEGACY_VPN)
                        && isVpnPreConsented(mContext, oldPackage, vpnType)) {
                    prepareInternal(oldPackage);
                    return true;
                }
                return false;
            } else if (!oldPackage.equals(VpnConfig.LEGACY_VPN)
                    && !isVpnPreConsented(mContext, oldPackage, vpnType)) {
                // Currently prepared VPN is revoked, so unprepare it and return false.
                prepareInternal(VpnConfig.LEGACY_VPN);
                return false;
            }
        }

        // Return true if we do not need to revoke.
        if (newPackage == null || (!newPackage.equals(VpnConfig.LEGACY_VPN) &&
                isCurrentPreparedPackage(newPackage))) {
            return true;
        }

        // Check that the caller is authorized.
        enforceControlPermission();

        // Stop an existing always-on VPN from being dethroned by other apps.
        if (mAlwaysOn && !isCurrentPreparedPackage(newPackage)) {
            return false;
        }

        prepareInternal(newPackage);
        return true;
    }

    private boolean isCurrentPreparedPackage(String packageName) {
        // We can't just check that packageName matches mPackage, because if the app was uninstalled
        // and reinstalled it will no longer be prepared. Similarly if there is a shared UID, the
        // calling package may not be the same as the prepared package. Check both UID and package.
        return getAppUid(packageName, mUserHandle) == mOwnerUID && mPackage.equals(packageName);
    }

    /** Prepare the VPN for the given package. Does not perform permission checks. */
    @GuardedBy("this")
    private void prepareInternal(String newPackage) {
        long token = Binder.clearCallingIdentity();
        try {
            // Reset the interface.
            if (mInterface != null) {
                mStatusIntent = null;
                agentDisconnect();
                jniReset(mInterface);
                mInterface = null;
                mNetworkCapabilities.setUids(null);
            }

            // Revoke the connection or stop the VpnRunner.
            if (mConnection != null) {
                try {
                    mConnection.mService.transact(IBinder.LAST_CALL_TRANSACTION,
                            Parcel.obtain(), null, IBinder.FLAG_ONEWAY);
                } catch (Exception e) {
                    // ignore
                }
                mContext.unbindService(mConnection);
                cleanupVpnStateLocked();
            } else if (mVpnRunner != null) {
                // cleanupVpnStateLocked() is called from mVpnRunner.exit()
                mVpnRunner.exit();
            }

            try {
                mNetd.denyProtect(mOwnerUID);
            } catch (Exception e) {
                Log.wtf(TAG, "Failed to disallow UID " + mOwnerUID + " to call protect() " + e);
            }

            Log.i(TAG, "Switched from " + mPackage + " to " + newPackage);
            mPackage = newPackage;
            mOwnerUID = getAppUid(newPackage, mUserHandle);
            mIsPackageTargetingAtLeastQ = doesPackageTargetAtLeastQ(newPackage);
            try {
                mNetd.allowProtect(mOwnerUID);
            } catch (Exception e) {
                Log.wtf(TAG, "Failed to allow UID " + mOwnerUID + " to call protect() " + e);
            }
            mConfig = null;

            updateState(DetailedState.IDLE, "prepare");
            setVpnForcedLocked(mLockdown);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Set whether a package has the ability to launch VPNs without user intervention. */
    public boolean setPackageAuthorization(String packageName, @VpnManager.VpnType int vpnType) {
        // Check if the caller is authorized.
        enforceControlPermissionOrInternalCaller();

        final int uid = getAppUid(packageName, mUserHandle);
        if (uid == -1 || VpnConfig.LEGACY_VPN.equals(packageName)) {
            // Authorization for nonexistent packages (or fake ones) can't be updated.
            return false;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            final int[] toChange;

            // Clear all AppOps if the app is being unauthorized.
            switch (vpnType) {
                case VpnManager.TYPE_VPN_NONE:
                    toChange = new int[] {
                            AppOpsManager.OP_ACTIVATE_VPN, AppOpsManager.OP_ACTIVATE_PLATFORM_VPN
                    };
                    break;
                case VpnManager.TYPE_VPN_PLATFORM:
                    toChange = new int[] {AppOpsManager.OP_ACTIVATE_PLATFORM_VPN};
                    break;
                case VpnManager.TYPE_VPN_SERVICE:
                    toChange = new int[] {AppOpsManager.OP_ACTIVATE_VPN};
                    break;
                default:
                    Log.wtf(TAG, "Unrecognized VPN type while granting authorization");
                    return false;
            }

            final AppOpsManager appOpMgr =
                    (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
            for (final int appOp : toChange) {
                appOpMgr.setMode(
                        appOp,
                        uid,
                        packageName,
                        vpnType == VpnManager.TYPE_VPN_NONE
                                ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED);
            }
            return true;
        } catch (Exception e) {
            Log.wtf(TAG, "Failed to set app ops for package " + packageName + ", uid " + uid, e);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return false;
    }

    private static boolean isVpnPreConsented(Context context, String packageName, int vpnType) {
        switch (vpnType) {
            case VpnManager.TYPE_VPN_SERVICE:
                return isVpnServicePreConsented(context, packageName);
            case VpnManager.TYPE_VPN_PLATFORM:
                return isVpnProfilePreConsented(context, packageName);
            default:
                return false;
        }
    }

    private static boolean doesPackageHaveAppop(Context context, String packageName, int appop) {
        final AppOpsManager appOps =
                (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);

        // Verify that the caller matches the given package and has the required permission.
        return appOps.noteOpNoThrow(appop, Binder.getCallingUid(), packageName)
                == AppOpsManager.MODE_ALLOWED;
    }

    private static boolean isVpnServicePreConsented(Context context, String packageName) {
        return doesPackageHaveAppop(context, packageName, AppOpsManager.OP_ACTIVATE_VPN);
    }

    private static boolean isVpnProfilePreConsented(Context context, String packageName) {
        return doesPackageHaveAppop(context, packageName, AppOpsManager.OP_ACTIVATE_PLATFORM_VPN)
                || isVpnServicePreConsented(context, packageName);
    }

    private int getAppUid(final String app, final int userHandle) {
        if (VpnConfig.LEGACY_VPN.equals(app)) {
            return Process.myUid();
        }
        PackageManager pm = mContext.getPackageManager();
        return Binder.withCleanCallingIdentity(() -> {
            try {
                return pm.getPackageUidAsUser(app, userHandle);
            } catch (NameNotFoundException e) {
                return -1;
            }
        });
    }

    private boolean doesPackageTargetAtLeastQ(String packageName) {
        if (VpnConfig.LEGACY_VPN.equals(packageName)) {
            return true;
        }
        PackageManager pm = mContext.getPackageManager();
        try {
            ApplicationInfo appInfo =
                    pm.getApplicationInfoAsUser(packageName, 0 /*flags*/, mUserHandle);
            return appInfo.targetSdkVersion >= VERSION_CODES.Q;
        } catch (NameNotFoundException unused) {
            Log.w(TAG, "Can't find \"" + packageName + "\"");
            return false;
        }
    }

    public NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    public int getNetId() {
        final NetworkAgent agent = mNetworkAgent;
        if (null == agent) return NETID_UNSET;
        final Network network = agent.getNetwork();
        if (null == network) return NETID_UNSET;
        return network.netId;
    }

    private LinkProperties makeLinkProperties() {
        boolean allowIPv4 = mConfig.allowIPv4;
        boolean allowIPv6 = mConfig.allowIPv6;

        LinkProperties lp = new LinkProperties();

        lp.setInterfaceName(mInterface);

        if (mConfig.addresses != null) {
            for (LinkAddress address : mConfig.addresses) {
                lp.addLinkAddress(address);
                allowIPv4 |= address.getAddress() instanceof Inet4Address;
                allowIPv6 |= address.getAddress() instanceof Inet6Address;
            }
        }

        if (mConfig.routes != null) {
            for (RouteInfo route : mConfig.routes) {
                lp.addRoute(route);
                InetAddress address = route.getDestination().getAddress();
                allowIPv4 |= address instanceof Inet4Address;
                allowIPv6 |= address instanceof Inet6Address;
            }
        }

        if (mConfig.dnsServers != null) {
            for (String dnsServer : mConfig.dnsServers) {
                InetAddress address = InetAddress.parseNumericAddress(dnsServer);
                lp.addDnsServer(address);
                allowIPv4 |= address instanceof Inet4Address;
                allowIPv6 |= address instanceof Inet6Address;
            }
        }

        lp.setHttpProxy(mConfig.proxyInfo);

        if (!allowIPv4) {
            lp.addRoute(new RouteInfo(new IpPrefix(Inet4Address.ANY, 0), RTN_UNREACHABLE));
        }
        if (!allowIPv6) {
            lp.addRoute(new RouteInfo(new IpPrefix(Inet6Address.ANY, 0), RTN_UNREACHABLE));
        }

        // Concatenate search domains into a string.
        StringBuilder buffer = new StringBuilder();
        if (mConfig.searchDomains != null) {
            for (String domain : mConfig.searchDomains) {
                buffer.append(domain).append(' ');
            }
        }
        lp.setDomains(buffer.toString().trim());

        if (mConfig.mtu > 0) {
            lp.setMtu(mConfig.mtu);
        }

        // TODO: Stop setting the MTU in jniCreate

        return lp;
    }

    /**
     * Attempt to perform a seamless handover of VPNs by only updating LinkProperties without
     * registering a new NetworkAgent. This is not always possible if the new VPN configuration
     * has certain changes, in which case this method would just return {@code false}.
     */
    private boolean updateLinkPropertiesInPlaceIfPossible(NetworkAgent agent, VpnConfig oldConfig) {
        // NetworkAgentConfig cannot be updated without registering a new NetworkAgent.
        if (oldConfig.allowBypass != mConfig.allowBypass) {
            Log.i(TAG, "Handover not possible due to changes to allowBypass");
            return false;
        }

        // TODO: we currently do not support seamless handover if the allowed or disallowed
        // applications have changed. Consider diffing UID ranges and only applying the delta.
        if (!Objects.equals(oldConfig.allowedApplications, mConfig.allowedApplications) ||
                !Objects.equals(oldConfig.disallowedApplications, mConfig.disallowedApplications)) {
            Log.i(TAG, "Handover not possible due to changes to whitelisted/blacklisted apps");
            return false;
        }

        agent.sendLinkProperties(makeLinkProperties());
        return true;
    }

    private void agentConnect() {
        LinkProperties lp = makeLinkProperties();

        // VPN either provide a default route (IPv4 or IPv6 or both), or they are a split tunnel
        // that falls back to the default network, which by definition provides INTERNET (unless
        // there is no default network, in which case none of this matters in any sense).
        // Also, always setting the INTERNET bit guarantees that when a VPN applies to an app,
        // the VPN will always be reported as the network by getDefaultNetwork and callbacks
        // registered with registerDefaultNetworkCallback. This in turn protects the invariant
        // that an app calling ConnectivityManager#bindProcessToNetwork(getDefaultNetwork())
        // behaves the same as when it uses the default network.
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        mNetworkInfo.setDetailedState(DetailedState.CONNECTING, null, null);

        NetworkAgentConfig networkAgentConfig = new NetworkAgentConfig();
        networkAgentConfig.allowBypass = mConfig.allowBypass && !mLockdown;

        mNetworkCapabilities.setOwnerUid(Binder.getCallingUid());
        mNetworkCapabilities.setUids(createUserAndRestrictedProfilesRanges(mUserHandle,
                mConfig.allowedApplications, mConfig.disallowedApplications));
        long token = Binder.clearCallingIdentity();
        try {
            mNetworkAgent = new NetworkAgent(mLooper, mContext, NETWORKTYPE /* logtag */,
                    mNetworkInfo, mNetworkCapabilities, lp,
                    ConnectivityConstants.VPN_DEFAULT_SCORE, networkAgentConfig,
                    NetworkProvider.ID_VPN) {
                            @Override
                            public void unwanted() {
                                // We are user controlled, not driven by NetworkRequest.
                            }
                        };
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        mNetworkInfo.setIsAvailable(true);
        updateState(DetailedState.CONNECTED, "agentConnect");
    }

    private boolean canHaveRestrictedProfile(int userId) {
        long token = Binder.clearCallingIdentity();
        try {
            return UserManager.get(mContext).canHaveRestrictedProfile(userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void agentDisconnect(NetworkAgent networkAgent) {
        if (networkAgent != null) {
            NetworkInfo networkInfo = new NetworkInfo(mNetworkInfo);
            networkInfo.setIsAvailable(false);
            networkInfo.setDetailedState(DetailedState.DISCONNECTED, null, null);
            networkAgent.sendNetworkInfo(networkInfo);
        }
    }

    private void agentDisconnect() {
        if (mNetworkInfo.isConnected()) {
            mNetworkInfo.setIsAvailable(false);
            updateState(DetailedState.DISCONNECTED, "agentDisconnect");
            mNetworkAgent = null;
        }
    }

    /**
     * Establish a VPN network and return the file descriptor of the VPN interface. This methods
     * returns {@code null} if the application is revoked or not prepared.
     *
     * <p>This method supports ONLY VpnService-based VPNs. For Platform VPNs, see {@link
     * provisionVpnProfile} and {@link startVpnProfile}
     *
     * @param config The parameters to configure the network.
     * @return The file descriptor of the VPN interface.
     */
    public synchronized ParcelFileDescriptor establish(VpnConfig config) {
        // Check if the caller is already prepared.
        if (Binder.getCallingUid() != mOwnerUID) {
            return null;
        }
        // Check to ensure consent hasn't been revoked since we were prepared.
        if (!isVpnServicePreConsented(mContext, mPackage)) {
            return null;
        }
        // Check if the service is properly declared.
        Intent intent = new Intent(VpnConfig.SERVICE_INTERFACE);
        intent.setClassName(mPackage, config.user);
        long token = Binder.clearCallingIdentity();
        try {
            // Restricted users are not allowed to create VPNs, they are tied to Owner
            enforceNotRestrictedUser();

            ResolveInfo info = AppGlobals.getPackageManager().resolveService(intent,
                    null, 0, mUserHandle);
            if (info == null) {
                throw new SecurityException("Cannot find " + config.user);
            }
            if (!BIND_VPN_SERVICE.equals(info.serviceInfo.permission)) {
                throw new SecurityException(config.user + " does not require " + BIND_VPN_SERVICE);
            }
        } catch (RemoteException e) {
            throw new SecurityException("Cannot find " + config.user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Save the old config in case we need to go back.
        VpnConfig oldConfig = mConfig;
        String oldInterface = mInterface;
        Connection oldConnection = mConnection;
        NetworkAgent oldNetworkAgent = mNetworkAgent;
        Set<UidRange> oldUsers = mNetworkCapabilities.getUids();

        // Configure the interface. Abort if any of these steps fails.
        ParcelFileDescriptor tun = ParcelFileDescriptor.adoptFd(jniCreate(config.mtu));
        try {
            String interfaze = jniGetName(tun.getFd());

            // TEMP use the old jni calls until there is support for netd address setting
            StringBuilder builder = new StringBuilder();
            for (LinkAddress address : config.addresses) {
                builder.append(" ");
                builder.append(address);
            }
            if (jniSetAddresses(interfaze, builder.toString()) < 1) {
                throw new IllegalArgumentException("At least one address must be specified");
            }
            Connection connection = new Connection();
            if (!mContext.bindServiceAsUser(intent, connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                    new UserHandle(mUserHandle))) {
                throw new IllegalStateException("Cannot bind " + config.user);
            }

            mConnection = connection;
            mInterface = interfaze;

            // Fill more values.
            config.user = mPackage;
            config.interfaze = mInterface;
            config.startTime = SystemClock.elapsedRealtime();
            mConfig = config;

            // Set up forwarding and DNS rules.
            // First attempt to do a seamless handover that only changes the interface name and
            // parameters. If that fails, disconnect.
            if (oldConfig != null
                    && updateLinkPropertiesInPlaceIfPossible(mNetworkAgent, oldConfig)) {
                // Keep mNetworkAgent unchanged
            } else {
                mNetworkAgent = null;
                updateState(DetailedState.CONNECTING, "establish");
                // Set up forwarding and DNS rules.
                agentConnect();
                // Remove the old tun's user forwarding rules
                // The new tun's user rules have already been added above so they will take over
                // as rules are deleted. This prevents data leakage as the rules are moved over.
                agentDisconnect(oldNetworkAgent);
            }

            if (oldConnection != null) {
                mContext.unbindService(oldConnection);
            }

            if (oldInterface != null && !oldInterface.equals(interfaze)) {
                jniReset(oldInterface);
            }

            try {
                IoUtils.setBlocking(tun.getFileDescriptor(), config.blocking);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot set tunnel's fd as blocking=" + config.blocking, e);
            }
        } catch (RuntimeException e) {
            IoUtils.closeQuietly(tun);
            // If this is not seamless handover, disconnect partially-established network when error
            // occurs.
            if (oldNetworkAgent != mNetworkAgent) {
                agentDisconnect();
            }
            // restore old state
            mConfig = oldConfig;
            mConnection = oldConnection;
            mNetworkCapabilities.setUids(oldUsers);
            mNetworkAgent = oldNetworkAgent;
            mInterface = oldInterface;
            throw e;
        }
        Log.i(TAG, "Established by " + config.user + " on " + mInterface);
        return tun;
    }

    private boolean isRunningLocked() {
        return mNetworkAgent != null && mInterface != null;
    }

    // Returns true if the VPN has been established and the calling UID is its owner. Used to check
    // that a call to mutate VPN state is admissible.
    @VisibleForTesting
    protected boolean isCallerEstablishedOwnerLocked() {
        return isRunningLocked() && Binder.getCallingUid() == mOwnerUID;
    }

    // Note: Return type guarantees results are deduped and sorted, which callers require.
    private SortedSet<Integer> getAppsUids(List<String> packageNames, int userHandle) {
        SortedSet<Integer> uids = new TreeSet<>();
        for (String app : packageNames) {
            int uid = getAppUid(app, userHandle);
            if (uid != -1) uids.add(uid);
        }
        return uids;
    }

    /**
     * Creates a {@link Set} of non-intersecting {@link UidRange} objects including all UIDs
     * associated with one user, and any restricted profiles attached to that user.
     *
     * <p>If one of {@param allowedApplications} or {@param disallowedApplications} is provided,
     * the UID ranges will match the app whitelist or blacklist specified there. Otherwise, all UIDs
     * in each user and profile will be included.
     *
     * @param userHandle The userId to create UID ranges for along with any of its restricted
     *                   profiles.
     * @param allowedApplications (optional) whitelist of applications to include.
     * @param disallowedApplications (optional) blacklist of applications to exclude.
     */
    @VisibleForTesting
    Set<UidRange> createUserAndRestrictedProfilesRanges(@UserIdInt int userHandle,
            @Nullable List<String> allowedApplications,
            @Nullable List<String> disallowedApplications) {
        final Set<UidRange> ranges = new ArraySet<>();

        // Assign the top-level user to the set of ranges
        addUserToRanges(ranges, userHandle, allowedApplications, disallowedApplications);

        // If the user can have restricted profiles, assign all its restricted profiles too
        if (canHaveRestrictedProfile(userHandle)) {
            final long token = Binder.clearCallingIdentity();
            List<UserInfo> users;
            try {
                users = UserManager.get(mContext).getUsers(true);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            for (UserInfo user : users) {
                if (user.isRestricted() && (user.restrictedProfileParentId == userHandle)) {
                    addUserToRanges(ranges, user.id, allowedApplications, disallowedApplications);
                }
            }
        }
        return ranges;
    }

    /**
     * Updates a {@link Set} of non-intersecting {@link UidRange} objects to include all UIDs
     * associated with one user.
     *
     * <p>If one of {@param allowedApplications} or {@param disallowedApplications} is provided,
     * the UID ranges will match the app whitelist or blacklist specified there. Otherwise, all UIDs
     * in the user will be included.
     *
     * @param ranges {@link Set} of {@link UidRange}s to which to add.
     * @param userHandle The userId to add to {@param ranges}.
     * @param allowedApplications (optional) whitelist of applications to include.
     * @param disallowedApplications (optional) blacklist of applications to exclude.
     */
    @VisibleForTesting
    void addUserToRanges(@NonNull Set<UidRange> ranges, @UserIdInt int userHandle,
            @Nullable List<String> allowedApplications,
            @Nullable List<String> disallowedApplications) {
        if (allowedApplications != null) {
            // Add ranges covering all UIDs for allowedApplications.
            int start = -1, stop = -1;
            for (int uid : getAppsUids(allowedApplications, userHandle)) {
                if (start == -1) {
                    start = uid;
                } else if (uid != stop + 1) {
                    ranges.add(new UidRange(start, stop));
                    start = uid;
                }
                stop = uid;
            }
            if (start != -1) ranges.add(new UidRange(start, stop));
        } else if (disallowedApplications != null) {
            // Add all ranges for user skipping UIDs for disallowedApplications.
            final UidRange userRange = UidRange.createForUser(userHandle);
            int start = userRange.start;
            for (int uid : getAppsUids(disallowedApplications, userHandle)) {
                if (uid == start) {
                    start++;
                } else {
                    ranges.add(new UidRange(start, uid - 1));
                    start = uid + 1;
                }
            }
            if (start <= userRange.stop) ranges.add(new UidRange(start, userRange.stop));
        } else {
            // Add all UIDs for the user.
            ranges.add(UidRange.createForUser(userHandle));
        }
    }

    // Returns the subset of the full list of active UID ranges the VPN applies to (mVpnUsers) that
    // apply to userHandle.
    static private List<UidRange> uidRangesForUser(int userHandle, Set<UidRange> existingRanges) {
        // UidRange#createForUser returns the entire range of UIDs available to a macro-user.
        // This is something like 0-99999 ; {@see UserHandle#PER_USER_RANGE}
        final UidRange userRange = UidRange.createForUser(userHandle);
        final List<UidRange> ranges = new ArrayList<>();
        for (UidRange range : existingRanges) {
            if (userRange.containsRange(range)) {
                ranges.add(range);
            }
        }
        return ranges;
    }

    /**
     * Updates UID ranges for this VPN and also updates its internal capabilities.
     *
     * <p>Should be called on primary ConnectivityService thread.
     */
    public void onUserAdded(int userHandle) {
        // If the user is restricted tie them to the parent user's VPN
        UserInfo user = UserManager.get(mContext).getUserInfo(userHandle);
        if (user.isRestricted() && user.restrictedProfileParentId == mUserHandle) {
            synchronized(Vpn.this) {
                final Set<UidRange> existingRanges = mNetworkCapabilities.getUids();
                if (existingRanges != null) {
                    try {
                        addUserToRanges(existingRanges, userHandle, mConfig.allowedApplications,
                                mConfig.disallowedApplications);
                        // ConnectivityService will call {@link #updateCapabilities} and apply
                        // those for VPN network.
                        mNetworkCapabilities.setUids(existingRanges);
                    } catch (Exception e) {
                        Log.wtf(TAG, "Failed to add restricted user to owner", e);
                    }
                }
                setVpnForcedLocked(mLockdown);
            }
        }
    }

    /**
     * Updates UID ranges for this VPN and also updates its capabilities.
     *
     * <p>Should be called on primary ConnectivityService thread.
     */
    public void onUserRemoved(int userHandle) {
        // clean up if restricted
        UserInfo user = UserManager.get(mContext).getUserInfo(userHandle);
        if (user.isRestricted() && user.restrictedProfileParentId == mUserHandle) {
            synchronized(Vpn.this) {
                final Set<UidRange> existingRanges = mNetworkCapabilities.getUids();
                if (existingRanges != null) {
                    try {
                        final List<UidRange> removedRanges =
                            uidRangesForUser(userHandle, existingRanges);
                        existingRanges.removeAll(removedRanges);
                        // ConnectivityService will call {@link #updateCapabilities} and
                        // apply those for VPN network.
                        mNetworkCapabilities.setUids(existingRanges);
                    } catch (Exception e) {
                        Log.wtf(TAG, "Failed to remove restricted user to owner", e);
                    }
                }
                setVpnForcedLocked(mLockdown);
            }
        }
    }

    /**
     * Called when the user associated with this VPN has just been stopped.
     */
    public synchronized void onUserStopped() {
        // Switch off networking lockdown (if it was enabled)
        setLockdown(false);
        mAlwaysOn = false;

        // Quit any active connections
        agentDisconnect();
    }

    /**
     * Restricts network access from all UIDs affected by this {@link Vpn}, apart from the VPN
     * service app itself and whitelisted packages, to only sockets that have had {@code protect()}
     * called on them. All non-VPN traffic is blocked via a {@code PROHIBIT} response from the
     * kernel.
     *
     * The exception for the VPN UID isn't technically necessary -- setup should use protected
     * sockets -- but in practice it saves apps that don't protect their sockets from breaking.
     *
     * Calling multiple times with {@param enforce} = {@code true} will recreate the set of UIDs to
     * block every time, and if anything has changed update using {@link #setAllowOnlyVpnForUids}.
     *
     * @param enforce {@code true} to require that all traffic under the jurisdiction of this
     *                {@link Vpn} goes through a VPN connection or is blocked until one is
     *                available, {@code false} to lift the requirement.
     *
     * @see #mBlockedUidsAsToldToNetd
     */
    @GuardedBy("this")
    private void setVpnForcedLocked(boolean enforce) {
        final List<String> exemptedPackages;
        if (isNullOrLegacyVpn(mPackage)) {
            exemptedPackages = null;
        } else {
            exemptedPackages = new ArrayList<>(mLockdownWhitelist);
            exemptedPackages.add(mPackage);
        }
        final Set<UidRange> rangesToTellNetdToRemove = new ArraySet<>(mBlockedUidsAsToldToNetd);

        final Set<UidRange> rangesToTellNetdToAdd;
        if (enforce) {
            final Set<UidRange> rangesThatShouldBeBlocked =
                    createUserAndRestrictedProfilesRanges(mUserHandle,
                            /* allowedApplications */ null,
                            /* disallowedApplications */ exemptedPackages);

            // The UID range of the first user (0-99999) would block the IPSec traffic, which comes
            // directly from the kernel and is marked as uid=0. So we adjust the range to allow
            // it through (b/69873852).
            for (UidRange range : rangesThatShouldBeBlocked) {
                if (range.start == 0) {
                    rangesThatShouldBeBlocked.remove(range);
                    if (range.stop != 0) {
                        rangesThatShouldBeBlocked.add(new UidRange(1, range.stop));
                    }
                }
            }

            rangesToTellNetdToRemove.removeAll(rangesThatShouldBeBlocked);
            rangesToTellNetdToAdd = rangesThatShouldBeBlocked;
            // The ranges to tell netd to add are the ones that should be blocked minus the
            // ones it already knows to block. Note that this will change the contents of
            // rangesThatShouldBeBlocked, but the list of ranges that should be blocked is
            // not used after this so it's fine to destroy it.
            rangesToTellNetdToAdd.removeAll(mBlockedUidsAsToldToNetd);
        } else {
            rangesToTellNetdToAdd = Collections.emptySet();
        }

        // If mBlockedUidsAsToldToNetd used to be empty, this will always be a no-op.
        setAllowOnlyVpnForUids(false, rangesToTellNetdToRemove);
        // If nothing should be blocked now, this will now be a no-op.
        setAllowOnlyVpnForUids(true, rangesToTellNetdToAdd);
    }

    /**
     * Tell netd to add or remove a list of {@link UidRange}s to the list of UIDs that are only
     * allowed to make connections through sockets that have had {@code protect()} called on them.
     *
     * @param enforce {@code true} to add to the blacklist, {@code false} to remove.
     * @param ranges {@link Collection} of {@link UidRange}s to add (if {@param enforce} is
     *               {@code true}) or to remove.
     * @return {@code true} if all of the UIDs were added/removed. {@code false} otherwise,
     *         including added ranges that already existed or removed ones that didn't.
     */
    @GuardedBy("this")
    private boolean setAllowOnlyVpnForUids(boolean enforce, Collection<UidRange> ranges) {
        if (ranges.size() == 0) {
            return true;
        }
        final UidRange[] rangesArray = ranges.toArray(new UidRange[ranges.size()]);
        try {
            mNetd.setAllowOnlyVpnForUids(enforce, rangesArray);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Updating blocked=" + enforce
                    + " for UIDs " + Arrays.toString(ranges.toArray()) + " failed", e);
            return false;
        }
        if (enforce) {
            mBlockedUidsAsToldToNetd.addAll(ranges);
        } else {
            mBlockedUidsAsToldToNetd.removeAll(ranges);
        }
        return true;
    }

    /**
     * Return the configuration of the currently running VPN.
     */
    public VpnConfig getVpnConfig() {
        enforceControlPermission();
        return mConfig;
    }

    @Deprecated
    public synchronized void interfaceStatusChanged(String iface, boolean up) {
        try {
            mObserver.interfaceStatusChanged(iface, up);
        } catch (RemoteException e) {
            // ignored; target is local
        }
    }

    private INetworkManagementEventObserver mObserver = new BaseNetworkObserver() {
        @Override
        public void interfaceStatusChanged(String interfaze, boolean up) {
            synchronized (Vpn.this) {
                if (!up && mVpnRunner != null && mVpnRunner instanceof LegacyVpnRunner) {
                    ((LegacyVpnRunner) mVpnRunner).exitIfOuterInterfaceIs(interfaze);
                }
            }
        }

        @Override
        public void interfaceRemoved(String interfaze) {
            synchronized (Vpn.this) {
                if (interfaze.equals(mInterface) && jniCheck(interfaze) == 0) {
                    if (mConnection != null) {
                        mContext.unbindService(mConnection);
                        cleanupVpnStateLocked();
                    } else if (mVpnRunner != null) {
                        // cleanupVpnStateLocked() is called from mVpnRunner.exit()
                        mVpnRunner.exit();
                    }
                }
            }
        }
    };

    private void cleanupVpnStateLocked() {
        mStatusIntent = null;
        mNetworkCapabilities.setUids(null);
        mConfig = null;
        mInterface = null;

        // Unconditionally clear both VpnService and VpnRunner fields.
        mVpnRunner = null;
        mConnection = null;
        agentDisconnect();
    }

    private void enforceControlPermission() {
        mContext.enforceCallingPermission(Manifest.permission.CONTROL_VPN, "Unauthorized Caller");
    }

    private void enforceControlPermissionOrInternalCaller() {
        // Require the caller to be either an application with CONTROL_VPN permission or a process
        // in the system server.
        mContext.enforceCallingOrSelfPermission(Manifest.permission.CONTROL_VPN,
                "Unauthorized Caller");
    }

    private void enforceSettingsPermission() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.NETWORK_SETTINGS,
                "Unauthorized Caller");
    }

    private class Connection implements ServiceConnection {
        private IBinder mService;

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = service;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    }

    private void prepareStatusIntent() {
        final long token = Binder.clearCallingIdentity();
        try {
            mStatusIntent = VpnConfig.getIntentForStatusPanel(mContext);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public synchronized boolean addAddress(String address, int prefixLength) {
        if (!isCallerEstablishedOwnerLocked()) {
            return false;
        }
        boolean success = jniAddAddress(mInterface, address, prefixLength);
        mNetworkAgent.sendLinkProperties(makeLinkProperties());
        return success;
    }

    public synchronized boolean removeAddress(String address, int prefixLength) {
        if (!isCallerEstablishedOwnerLocked()) {
            return false;
        }
        boolean success = jniDelAddress(mInterface, address, prefixLength);
        mNetworkAgent.sendLinkProperties(makeLinkProperties());
        return success;
    }

    /**
     * Updates underlying network set.
     *
     * <p>Note: Does not updates capabilities. Call {@link #updateCapabilities} from
     * ConnectivityService thread to get updated capabilities.
     */
    public synchronized boolean setUnderlyingNetworks(Network[] networks) {
        if (!isCallerEstablishedOwnerLocked()) {
            return false;
        }
        if (networks == null) {
            mConfig.underlyingNetworks = null;
        } else {
            mConfig.underlyingNetworks = new Network[networks.length];
            for (int i = 0; i < networks.length; ++i) {
                if (networks[i] == null) {
                    mConfig.underlyingNetworks[i] = null;
                } else {
                    mConfig.underlyingNetworks[i] = new Network(networks[i].netId);
                }
            }
        }
        return true;
    }

    public synchronized Network[] getUnderlyingNetworks() {
        if (!isRunningLocked()) {
            return null;
        }
        return mConfig.underlyingNetworks;
    }

    /**
     * This method should only be called by ConnectivityService because it doesn't
     * have enough data to fill VpnInfo.primaryUnderlyingIface field.
     */
    public synchronized VpnInfo getVpnInfo() {
        if (!isRunningLocked()) {
            return null;
        }

        VpnInfo info = new VpnInfo();
        info.ownerUid = mOwnerUID;
        info.vpnIface = mInterface;
        return info;
    }

    public synchronized boolean appliesToUid(int uid) {
        if (!isRunningLocked()) {
            return false;
        }
        return mNetworkCapabilities.appliesToUid(uid);
    }

    /**
     * Gets the currently running App-based VPN type
     *
     * @return the {@link VpnManager.VpnType}. {@link VpnManager.TYPE_VPN_NONE} if not running an
     *     app-based VPN. While VpnService-based VPNs are always app VPNs and LegacyVpn is always
     *     Settings-based, the Platform VPNs can be initiated by both apps and Settings.
     */
    public synchronized int getActiveAppVpnType() {
        if (VpnConfig.LEGACY_VPN.equals(mPackage)) {
            return VpnManager.TYPE_VPN_NONE;
        }

        if (mVpnRunner != null && mVpnRunner instanceof IkeV2VpnRunner) {
            return VpnManager.TYPE_VPN_PLATFORM;
        } else {
            return VpnManager.TYPE_VPN_SERVICE;
        }
    }

    /**
     * @param uid The target uid.
     *
     * @return {@code true} if {@code uid} is included in one of the mBlockedUidsAsToldToNetd
     * ranges and the VPN is not connected, or if the VPN is connected but does not apply to
     * the {@code uid}.
     *
     * @apiNote This method don't check VPN lockdown status.
     * @see #mBlockedUidsAsToldToNetd
     */
    public synchronized boolean isBlockingUid(int uid) {
        if (mNetworkInfo.isConnected()) {
            return !appliesToUid(uid);
        } else {
            return UidRange.containsUid(mBlockedUidsAsToldToNetd, uid);
        }
    }

    private void updateAlwaysOnNotification(DetailedState networkState) {
        final boolean visible = (mAlwaysOn && networkState != DetailedState.CONNECTED);

        final UserHandle user = UserHandle.of(mUserHandle);
        final long token = Binder.clearCallingIdentity();
        try {
            final NotificationManager notificationManager = NotificationManager.from(mContext);
            if (!visible) {
                notificationManager.cancelAsUser(TAG, SystemMessage.NOTE_VPN_DISCONNECTED, user);
                return;
            }
            final Intent intent = new Intent();
            intent.setComponent(ComponentName.unflattenFromString(mContext.getString(
                    R.string.config_customVpnAlwaysOnDisconnectedDialogComponent)));
            intent.putExtra("lockdown", mLockdown);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final PendingIntent configIntent = mSystemServices.pendingIntentGetActivityAsUser(
                    intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT, user);
            final Notification.Builder builder =
                    new Notification.Builder(mContext, SystemNotificationChannels.VPN)
                            .setSmallIcon(R.drawable.vpn_connected)
                            .setContentTitle(mContext.getString(R.string.vpn_lockdown_disconnected))
                            .setContentText(mContext.getString(R.string.vpn_lockdown_config))
                            .setContentIntent(configIntent)
                            .setCategory(Notification.CATEGORY_SYSTEM)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .setOngoing(true)
                            .setColor(mContext.getColor(R.color.system_notification_accent_color));
            notificationManager.notifyAsUser(TAG, SystemMessage.NOTE_VPN_DISCONNECTED,
                    builder.build(), user);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Facade for system service calls that change, or depend on, state outside of
     * {@link ConnectivityService} and have hard-to-mock interfaces.
     *
     * @see com.android.server.connectivity.VpnTest
     */
    @VisibleForTesting
    public static class SystemServices {
        private final Context mContext;

        public SystemServices(@NonNull Context context) {
            mContext = context;
        }

        /**
         * @see PendingIntent#getActivityAsUser()
         */
        public PendingIntent pendingIntentGetActivityAsUser(
                Intent intent, int flags, UserHandle user) {
            return PendingIntent.getActivityAsUser(mContext, 0 /*request*/, intent, flags,
                    null /*options*/, user);
        }

        /**
         * @see Settings.Secure#putStringForUser
         */
        public void settingsSecurePutStringForUser(String key, String value, int userId) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(), key, value, userId);
        }

        /**
         * @see Settings.Secure#putIntForUser
         */
        public void settingsSecurePutIntForUser(String key, int value, int userId) {
            Settings.Secure.putIntForUser(mContext.getContentResolver(), key, value, userId);
        }

        /**
         * @see Settings.Secure#getStringForUser
         */
        public String settingsSecureGetStringForUser(String key, int userId) {
            return Settings.Secure.getStringForUser(mContext.getContentResolver(), key, userId);
        }

        /**
         * @see Settings.Secure#getIntForUser
         */
        public int settingsSecureGetIntForUser(String key, int def, int userId) {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(), key, def, userId);
        }

        public boolean isCallerSystem() {
            return Binder.getCallingUid() == Process.SYSTEM_UID;
        }
    }

    private native int jniCreate(int mtu);
    private native String jniGetName(int tun);
    private native int jniSetAddresses(String interfaze, String addresses);
    private native void jniReset(String interfaze);
    private native int jniCheck(String interfaze);
    private native boolean jniAddAddress(String interfaze, String address, int prefixLen);
    private native boolean jniDelAddress(String interfaze, String address, int prefixLen);

    private static RouteInfo findIPv4DefaultRoute(LinkProperties prop) {
        for (RouteInfo route : prop.getAllRoutes()) {
            // Currently legacy VPN only works on IPv4.
            if (route.isDefaultRoute() && route.getGateway() instanceof Inet4Address) {
                return route;
            }
        }

        throw new IllegalStateException("Unable to find IPv4 default gateway");
    }

    private void enforceNotRestrictedUser() {
        Binder.withCleanCallingIdentity(() -> {
            final UserManager mgr = UserManager.get(mContext);
            final UserInfo user = mgr.getUserInfo(mUserHandle);

            if (user.isRestricted()) {
                throw new SecurityException("Restricted users cannot configure VPNs");
            }
        });
    }

    /**
     * Start legacy VPN, controlling native daemons as needed. Creates a
     * secondary thread to perform connection work, returning quickly.
     *
     * Should only be called to respond to Binder requests as this enforces caller permission. Use
     * {@link #startLegacyVpnPrivileged(VpnProfile, KeyStore, LinkProperties)} to skip the
     * permission check only when the caller is trusted (or the call is initiated by the system).
     */
    public void startLegacyVpn(VpnProfile profile, KeyStore keyStore, LinkProperties egress) {
        enforceControlPermission();
        long token = Binder.clearCallingIdentity();
        try {
            startLegacyVpnPrivileged(profile, keyStore, egress);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Like {@link #startLegacyVpn(VpnProfile, KeyStore, LinkProperties)}, but does not check
     * permissions under the assumption that the caller is the system.
     *
     * Callers are responsible for checking permissions if needed.
     */
    public void startLegacyVpnPrivileged(VpnProfile profile, KeyStore keyStore,
            LinkProperties egress) {
        UserManager mgr = UserManager.get(mContext);
        UserInfo user = mgr.getUserInfo(mUserHandle);
        if (user.isRestricted() || mgr.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN,
                    new UserHandle(mUserHandle))) {
            throw new SecurityException("Restricted users cannot establish VPNs");
        }

        final RouteInfo ipv4DefaultRoute = findIPv4DefaultRoute(egress);
        final String gateway = ipv4DefaultRoute.getGateway().getHostAddress();
        final String iface = ipv4DefaultRoute.getInterface();

        // Load certificates.
        String privateKey = "";
        String userCert = "";
        String caCert = "";
        String serverCert = "";
        if (!profile.ipsecUserCert.isEmpty()) {
            privateKey = Credentials.USER_PRIVATE_KEY + profile.ipsecUserCert;
            byte[] value = keyStore.get(Credentials.USER_CERTIFICATE + profile.ipsecUserCert);
            userCert = (value == null) ? null : new String(value, StandardCharsets.UTF_8);
        }
        if (!profile.ipsecCaCert.isEmpty()) {
            byte[] value = keyStore.get(Credentials.CA_CERTIFICATE + profile.ipsecCaCert);
            caCert = (value == null) ? null : new String(value, StandardCharsets.UTF_8);
        }
        if (!profile.ipsecServerCert.isEmpty()) {
            byte[] value = keyStore.get(Credentials.USER_CERTIFICATE + profile.ipsecServerCert);
            serverCert = (value == null) ? null : new String(value, StandardCharsets.UTF_8);
        }
        if (userCert == null || caCert == null || serverCert == null) {
            throw new IllegalStateException("Cannot load credentials");
        }

        // Prepare arguments for racoon.
        String[] racoon = null;
        switch (profile.type) {
            case VpnProfile.TYPE_IKEV2_IPSEC_RSA:
                // Secret key is still just the alias (not the actual private key). The private key
                // is retrieved from the KeyStore during conversion of the VpnProfile to an
                // Ikev2VpnProfile.
                profile.ipsecSecret = Ikev2VpnProfile.PREFIX_KEYSTORE_ALIAS + privateKey;
                profile.ipsecUserCert = userCert;
                // Fallthrough
            case VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS:
                profile.ipsecCaCert = caCert;

                // Start VPN profile
                startVpnProfilePrivileged(profile, VpnConfig.LEGACY_VPN, keyStore);
                return;
            case VpnProfile.TYPE_IKEV2_IPSEC_PSK:
                // Ikev2VpnProfiles expect a base64-encoded preshared key.
                profile.ipsecSecret =
                        Ikev2VpnProfile.encodeForIpsecSecret(profile.ipsecSecret.getBytes());

                // Start VPN profile
                startVpnProfilePrivileged(profile, VpnConfig.LEGACY_VPN, keyStore);
                return;
            case VpnProfile.TYPE_L2TP_IPSEC_PSK:
                racoon = new String[] {
                    iface, profile.server, "udppsk", profile.ipsecIdentifier,
                    profile.ipsecSecret, "1701",
                };
                break;
            case VpnProfile.TYPE_L2TP_IPSEC_RSA:
                racoon = new String[] {
                    iface, profile.server, "udprsa", privateKey, userCert,
                    caCert, serverCert, "1701",
                };
                break;
            case VpnProfile.TYPE_IPSEC_XAUTH_PSK:
                racoon = new String[] {
                    iface, profile.server, "xauthpsk", profile.ipsecIdentifier,
                    profile.ipsecSecret, profile.username, profile.password, "", gateway,
                };
                break;
            case VpnProfile.TYPE_IPSEC_XAUTH_RSA:
                racoon = new String[] {
                    iface, profile.server, "xauthrsa", privateKey, userCert,
                    caCert, serverCert, profile.username, profile.password, "", gateway,
                };
                break;
            case VpnProfile.TYPE_IPSEC_HYBRID_RSA:
                racoon = new String[] {
                    iface, profile.server, "hybridrsa",
                    caCert, serverCert, profile.username, profile.password, "", gateway,
                };
                break;
        }

        // Prepare arguments for mtpd.
        String[] mtpd = null;
        switch (profile.type) {
            case VpnProfile.TYPE_PPTP:
                mtpd = new String[] {
                    iface, "pptp", profile.server, "1723",
                    "name", profile.username, "password", profile.password,
                    "linkname", "vpn", "refuse-eap", "nodefaultroute",
                    "usepeerdns", "idle", "1800", "mtu", "1400", "mru", "1400",
                    (profile.mppe ? "+mppe" : "nomppe"),
                };
                break;
            case VpnProfile.TYPE_L2TP_IPSEC_PSK:
            case VpnProfile.TYPE_L2TP_IPSEC_RSA:
                mtpd = new String[] {
                    iface, "l2tp", profile.server, "1701", profile.l2tpSecret,
                    "name", profile.username, "password", profile.password,
                    "linkname", "vpn", "refuse-eap", "nodefaultroute",
                    "usepeerdns", "idle", "1800", "mtu", "1400", "mru", "1400",
                };
                break;
        }

        VpnConfig config = new VpnConfig();
        config.legacy = true;
        config.user = profile.key;
        config.interfaze = iface;
        config.session = profile.name;
        config.isMetered = false;
        config.proxyInfo = profile.proxy;

        config.addLegacyRoutes(profile.routes);
        if (!profile.dnsServers.isEmpty()) {
            config.dnsServers = Arrays.asList(profile.dnsServers.split(" +"));
        }
        if (!profile.searchDomains.isEmpty()) {
            config.searchDomains = Arrays.asList(profile.searchDomains.split(" +"));
        }
        startLegacyVpn(config, racoon, mtpd, profile);
    }

    private synchronized void startLegacyVpn(VpnConfig config, String[] racoon, String[] mtpd,
            VpnProfile profile) {
        stopVpnRunnerPrivileged();

        // Prepare for the new request.
        prepareInternal(VpnConfig.LEGACY_VPN);
        updateState(DetailedState.CONNECTING, "startLegacyVpn");

        // Start a new LegacyVpnRunner and we are done!
        mVpnRunner = new LegacyVpnRunner(config, racoon, mtpd, profile);
        mVpnRunner.start();
    }

    /**
     * Checks if this the currently running VPN (if any) was started by the Settings app
     *
     * <p>This includes both Legacy VPNs and Platform VPNs.
     */
    private boolean isSettingsVpnLocked() {
        return mVpnRunner != null && VpnConfig.LEGACY_VPN.equals(mPackage);
    }

    /** Stop VPN runner. Permissions must be checked by callers. */
    public synchronized void stopVpnRunnerPrivileged() {
        if (!isSettingsVpnLocked()) {
            return;
        }

        final boolean isLegacyVpn = mVpnRunner instanceof LegacyVpnRunner;

        mVpnRunner.exit();
        mVpnRunner = null;

        // LegacyVpn uses daemons that must be shut down before new ones are brought up.
        // The same limitation does not apply to Platform VPNs.
        if (isLegacyVpn) {
            synchronized (LegacyVpnRunner.TAG) {
                // wait for old thread to completely finish before spinning up
                // new instance, otherwise state updates can be out of order.
            }
        }
    }

    /**
     * Return the information of the current ongoing legacy VPN.
     */
    public synchronized LegacyVpnInfo getLegacyVpnInfo() {
        // Check if the caller is authorized.
        enforceControlPermission();
        return getLegacyVpnInfoPrivileged();
    }

    /**
     * Return the information of the current ongoing legacy VPN.
     * Callers are responsible for checking permissions if needed.
     */
    private synchronized LegacyVpnInfo getLegacyVpnInfoPrivileged() {
        if (!isSettingsVpnLocked()) return null;

        final LegacyVpnInfo info = new LegacyVpnInfo();
        info.key = mConfig.user;
        info.state = LegacyVpnInfo.stateFromNetworkInfo(mNetworkInfo);
        if (mNetworkInfo.isConnected()) {
            info.intent = mStatusIntent;
        }
        return info;
    }

    public synchronized VpnConfig getLegacyVpnConfig() {
        if (isSettingsVpnLocked()) {
            return mConfig;
        } else {
            return null;
        }
    }

    /** This class represents the common interface for all VPN runners. */
    private abstract class VpnRunner extends Thread {

        protected VpnRunner(String name) {
            super(name);
        }

        public abstract void run();

        /**
         * Disconnects the NetworkAgent and cleans up all state related to the VpnRunner.
         *
         * <p>All outer Vpn instance state is cleaned up in cleanupVpnStateLocked()
         */
        protected abstract void exitVpnRunner();

        /**
         * Triggers the cleanup of the VpnRunner, and additionally cleans up Vpn instance-wide state
         *
         * <p>This method ensures that simple calls to exit() will always clean up global state
         * properly.
         */
        protected final void exit() {
            synchronized (Vpn.this) {
                exitVpnRunner();
                cleanupVpnStateLocked();
            }
        }
    }

    interface IkeV2VpnRunnerCallback {
        void onDefaultNetworkChanged(@NonNull Network network);

        void onChildOpened(
                @NonNull Network network, @NonNull ChildSessionConfiguration childConfig);

        void onChildTransformCreated(
                @NonNull Network network, @NonNull IpSecTransform transform, int direction);

        void onSessionLost(@NonNull Network network);
    }

    /**
     * Internal class managing IKEv2/IPsec VPN connectivity
     *
     * <p>The IKEv2 VPN will listen to, and run based on the lifecycle of Android's default Network.
     * As a new default is selected, old IKE sessions will be torn down, and a new one will be
     * started.
     *
     * <p>This class uses locking minimally - the Vpn instance lock is only ever held when fields of
     * the outer class are modified. As such, care must be taken to ensure that no calls are added
     * that might modify the outer class' state without acquiring a lock.
     *
     * <p>The overall structure of the Ikev2VpnRunner is as follows:
     *
     * <ol>
     *   <li>Upon startup, a NetworkRequest is registered with ConnectivityManager. This is called
     *       any time a new default network is selected
     *   <li>When a new default is connected, an IKE session is started on that Network. If there
     *       were any existing IKE sessions on other Networks, they are torn down before starting
     *       the new IKE session
     *   <li>Upon establishment, the onChildTransformCreated() callback is called twice, one for
     *       each direction, and finally onChildOpened() is called
     *   <li>Upon the onChildOpened() call, the VPN is fully set up.
     *   <li>Subsequent Network changes result in new onDefaultNetworkChanged() callbacks. See (2).
     * </ol>
     */
    class IkeV2VpnRunner extends VpnRunner implements IkeV2VpnRunnerCallback {
        @NonNull private static final String TAG = "IkeV2VpnRunner";

        @NonNull private final IpSecManager mIpSecManager;
        @NonNull private final Ikev2VpnProfile mProfile;
        @NonNull private final ConnectivityManager.NetworkCallback mNetworkCallback;

        /**
         * Executor upon which ALL callbacks must be run.
         *
         * <p>This executor MUST be a single threaded executor, in order to ensure the consistency
         * of the mutable Ikev2VpnRunner fields. The Ikev2VpnRunner is built mostly lock-free by
         * virtue of everything being serialized on this executor.
         */
        @NonNull private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

        /** Signal to ensure shutdown is honored even if a new Network is connected. */
        private boolean mIsRunning = true;

        @Nullable private IpSecTunnelInterface mTunnelIface;
        @Nullable private IkeSession mSession;
        @Nullable private Network mActiveNetwork;

        IkeV2VpnRunner(@NonNull Ikev2VpnProfile profile) {
            super(TAG);
            mProfile = profile;
            mIpSecManager = (IpSecManager) mContext.getSystemService(Context.IPSEC_SERVICE);
            mNetworkCallback = new VpnIkev2Utils.Ikev2VpnNetworkCallback(TAG, this);
        }

        @Override
        public void run() {
            // Explicitly use only the network that ConnectivityService thinks is the "best." In
            // other words, only ever use the currently selected default network. This does mean
            // that in both onLost() and onConnected(), any old sessions MUST be torn down. This
            // does NOT include VPNs.
            final ConnectivityManager cm = ConnectivityManager.from(mContext);
            cm.requestNetwork(cm.getDefaultRequest(), mNetworkCallback);
        }

        private boolean isActiveNetwork(@Nullable Network network) {
            return Objects.equals(mActiveNetwork, network) && mIsRunning;
        }

        /**
         * Called when an IKE Child session has been opened, signalling completion of the startup.
         *
         * <p>This method is only ever called once per IkeSession, and MUST run on the mExecutor
         * thread in order to ensure consistency of the Ikev2VpnRunner fields.
         */
        public void onChildOpened(
                @NonNull Network network, @NonNull ChildSessionConfiguration childConfig) {
            if (!isActiveNetwork(network)) {
                Log.d(TAG, "onOpened called for obsolete network " + network);

                // Do nothing; this signals that either: (1) a new/better Network was found,
                // and the Ikev2VpnRunner has switched to it in onDefaultNetworkChanged, or (2) this
                // IKE session was already shut down (exited, or an error was encountered somewhere
                // else). In both cases, all resources and sessions are torn down via
                // resetIkeState().
                return;
            }

            try {
                final String interfaceName = mTunnelIface.getInterfaceName();
                final int maxMtu = mProfile.getMaxMtu();
                final List<LinkAddress> internalAddresses = childConfig.getInternalAddresses();

                final Collection<RouteInfo> newRoutes = VpnIkev2Utils.getRoutesFromTrafficSelectors(
                        childConfig.getOutboundTrafficSelectors());
                for (final LinkAddress address : internalAddresses) {
                    mTunnelIface.addAddress(address.getAddress(), address.getPrefixLength());
                }

                final NetworkAgent networkAgent;
                final LinkProperties lp;

                synchronized (Vpn.this) {
                    mInterface = interfaceName;
                    mConfig.mtu = maxMtu;
                    mConfig.interfaze = mInterface;

                    mConfig.addresses.clear();
                    mConfig.addresses.addAll(internalAddresses);

                    mConfig.routes.clear();
                    mConfig.routes.addAll(newRoutes);

                    // TODO: Add DNS servers from negotiation

                    networkAgent = mNetworkAgent;

                    // The below must be done atomically with the mConfig update, otherwise
                    // isRunningLocked() will be racy.
                    if (networkAgent == null) {
                        if (isSettingsVpnLocked()) {
                            prepareStatusIntent();
                        }
                        agentConnect();
                        return; // Link properties are already sent.
                    }

                    lp = makeLinkProperties(); // Accesses VPN instance fields; must be locked
                }

                networkAgent.sendLinkProperties(lp);
            } catch (Exception e) {
                Log.d(TAG, "Error in ChildOpened for network " + network, e);
                onSessionLost(network);
            }
        }

        /**
         * Called when an IPsec transform has been created, and should be applied.
         *
         * <p>This method is called multiple times over the lifetime of an IkeSession (or default
         * network), and is MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        public void onChildTransformCreated(
                @NonNull Network network, @NonNull IpSecTransform transform, int direction) {
            if (!isActiveNetwork(network)) {
                Log.d(TAG, "ChildTransformCreated for obsolete network " + network);

                // Do nothing; this signals that either: (1) a new/better Network was found,
                // and the Ikev2VpnRunner has switched to it in onDefaultNetworkChanged, or (2) this
                // IKE session was already shut down (exited, or an error was encountered somewhere
                // else). In both cases, all resources and sessions are torn down via
                // resetIkeState().
                return;
            }

            try {
                // Transforms do not need to be persisted; the IkeSession will keep
                // them alive for us
                mIpSecManager.applyTunnelModeTransform(mTunnelIface, direction, transform);
            } catch (IOException e) {
                Log.d(TAG, "Transform application failed for network " + network, e);
                onSessionLost(network);
            }
        }

        /**
         * Called when a new default network is connected.
         *
         * <p>The Ikev2VpnRunner will unconditionally switch to the new network, killing the old IKE
         * state in the process, and starting a new IkeSession instance.
         *
         * <p>This method is called multiple times over the lifetime of the Ikev2VpnRunner, and is
         * called on the ConnectivityService thread. Thus, the actual work MUST be proxied to the
         * mExecutor thread in order to ensure consistency of the Ikev2VpnRunner fields.
         */
        public void onDefaultNetworkChanged(@NonNull Network network) {
            Log.d(TAG, "Starting IKEv2/IPsec session on new network: " + network);

            // Proxy to the Ikev2VpnRunner (single-thread) executor to ensure consistency in lieu
            // of locking.
            mExecutor.execute(() -> {
                try {
                    if (!mIsRunning) {
                        Log.d(TAG, "onDefaultNetworkChanged after exit");
                        return; // VPN has been shut down.
                    }

                    // Without MOBIKE, we have no way to seamlessly migrate. Close on old
                    // (non-default) network, and start the new one.
                    resetIkeState();
                    mActiveNetwork = network;

                    final IkeSessionParams ikeSessionParams =
                            VpnIkev2Utils.buildIkeSessionParams(mContext, mProfile, network);
                    final ChildSessionParams childSessionParams =
                            VpnIkev2Utils.buildChildSessionParams();

                    // TODO: Remove the need for adding two unused addresses with
                    // IPsec tunnels.
                    final InetAddress address = InetAddress.getLocalHost();
                    mTunnelIface =
                            mIpSecManager.createIpSecTunnelInterface(
                                    address /* unused */,
                                    address /* unused */,
                                    network);
                    mNetd.setInterfaceUp(mTunnelIface.getInterfaceName());

                    mSession = mIkev2SessionCreator.createIkeSession(
                            mContext,
                            ikeSessionParams,
                            childSessionParams,
                            mExecutor,
                            new VpnIkev2Utils.IkeSessionCallbackImpl(
                                    TAG, IkeV2VpnRunner.this, network),
                            new VpnIkev2Utils.ChildSessionCallbackImpl(
                                    TAG, IkeV2VpnRunner.this, network));
                    Log.d(TAG, "Ike Session started for network " + network);
                } catch (Exception e) {
                    Log.i(TAG, "Setup failed for network " + network + ". Aborting", e);
                    onSessionLost(network);
                }
            });
        }

        /**
         * Handles loss of a session
         *
         * <p>The loss of a session might be due to an onLost() call, the IKE session getting torn
         * down for any reason, or an error in updating state (transform application, VPN setup)
         *
         * <p>This method MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        public void onSessionLost(@NonNull Network network) {
            if (!isActiveNetwork(network)) {
                Log.d(TAG, "onSessionLost() called for obsolete network " + network);

                // Do nothing; this signals that either: (1) a new/better Network was found,
                // and the Ikev2VpnRunner has switched to it in onDefaultNetworkChanged, or (2) this
                // IKE session was already shut down (exited, or an error was encountered somewhere
                // else). In both cases, all resources and sessions are torn down via
                // onSessionLost() and resetIkeState().
                return;
            }

            mActiveNetwork = null;

            // Close all obsolete state, but keep VPN alive incase a usable network comes up.
            // (Mirrors VpnService behavior)
            Log.d(TAG, "Resetting state for network: " + network);

            synchronized (Vpn.this) {
                // Since this method handles non-fatal errors only, set mInterface to null to
                // prevent the NetworkManagementEventObserver from killing this VPN based on the
                // interface going down (which we expect).
                mInterface = null;
                mConfig.interfaze = null;

                // Set as unroutable to prevent traffic leaking while the interface is down.
                if (mConfig != null && mConfig.routes != null) {
                    final List<RouteInfo> oldRoutes = new ArrayList<>(mConfig.routes);

                    mConfig.routes.clear();
                    for (final RouteInfo route : oldRoutes) {
                        mConfig.routes.add(new RouteInfo(route.getDestination(), RTN_UNREACHABLE));
                    }
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendLinkProperties(makeLinkProperties());
                    }
                }
            }

            resetIkeState();
        }

        /**
         * Cleans up all IKE state
         *
         * <p>This method MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        private void resetIkeState() {
            if (mTunnelIface != null) {
                // No need to call setInterfaceDown(); the IpSecInterface is being fully torn down.
                mTunnelIface.close();
                mTunnelIface = null;
            }
            if (mSession != null) {
                mSession.kill(); // Kill here to make sure all resources are released immediately
                mSession = null;
            }
        }

        /**
         * Cleans up all Ikev2VpnRunner internal state
         *
         * <p>This method MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        private void shutdownVpnRunner() {
            mActiveNetwork = null;
            mIsRunning = false;

            resetIkeState();

            final ConnectivityManager cm = ConnectivityManager.from(mContext);
            cm.unregisterNetworkCallback(mNetworkCallback);

            mExecutor.shutdown();
        }

        @Override
        public void exitVpnRunner() {
            mExecutor.execute(() -> {
                shutdownVpnRunner();
            });
        }
    }

    /**
     * Bringing up a VPN connection takes time, and that is all this thread
     * does. Here we have plenty of time. The only thing we need to take
     * care of is responding to interruptions as soon as possible. Otherwise
     * requests will pile up. This could be done in a Handler as a state
     * machine, but it is much easier to read in the current form.
     */
    private class LegacyVpnRunner extends VpnRunner {
        private static final String TAG = "LegacyVpnRunner";

        private final String[] mDaemons;
        private final String[][] mArguments;
        private final LocalSocket[] mSockets;
        private final String mOuterInterface;
        private final AtomicInteger mOuterConnection =
                new AtomicInteger(ConnectivityManager.TYPE_NONE);
        private final VpnProfile mProfile;

        private long mBringupStartTime = -1;

        /**
         * Watch for the outer connection (passing in the constructor) going away.
         */
        private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!mEnableTeardown) return;

                if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                    if (intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE,
                            ConnectivityManager.TYPE_NONE) == mOuterConnection.get()) {
                        NetworkInfo info = (NetworkInfo)intent.getExtra(
                                ConnectivityManager.EXTRA_NETWORK_INFO);
                        if (info != null && !info.isConnectedOrConnecting()) {
                            try {
                                mObserver.interfaceStatusChanged(mOuterInterface, false);
                            } catch (RemoteException e) {}
                        }
                    }
                }
            }
        };

        LegacyVpnRunner(VpnConfig config, String[] racoon, String[] mtpd, VpnProfile profile) {
            super(TAG);
            mConfig = config;
            mDaemons = new String[] {"racoon", "mtpd"};
            // TODO: clear arguments from memory once launched
            mArguments = new String[][] {racoon, mtpd};
            mSockets = new LocalSocket[mDaemons.length];

            // This is the interface which VPN is running on,
            // mConfig.interfaze will change to point to OUR
            // internal interface soon. TODO - add inner/outer to mconfig
            // TODO - we have a race - if the outer iface goes away/disconnects before we hit this
            // we will leave the VPN up.  We should check that it's still there/connected after
            // registering
            mOuterInterface = mConfig.interfaze;

            mProfile = profile;

            if (!TextUtils.isEmpty(mOuterInterface)) {
                final ConnectivityManager cm = ConnectivityManager.from(mContext);
                for (Network network : cm.getAllNetworks()) {
                    final LinkProperties lp = cm.getLinkProperties(network);
                    if (lp != null && lp.getAllInterfaceNames().contains(mOuterInterface)) {
                        final NetworkInfo networkInfo = cm.getNetworkInfo(network);
                        if (networkInfo != null) mOuterConnection.set(networkInfo.getType());
                    }
                }
            }

            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            mContext.registerReceiver(mBroadcastReceiver, filter);
        }

        /**
         * Checks if the parameter matches the underlying interface
         *
         * <p>If the underlying interface is torn down, the LegacyVpnRunner also should be. It has
         * no ability to migrate between interfaces (or Networks).
         */
        public void exitIfOuterInterfaceIs(String interfaze) {
            if (interfaze.equals(mOuterInterface)) {
                Log.i(TAG, "Legacy VPN is going down with " + interfaze);
                exitVpnRunner();
            }
        }

        /** Tears down this LegacyVpn connection */
        @Override
        public void exitVpnRunner() {
            // We assume that everything is reset after stopping the daemons.
            interrupt();

            // Always disconnect. This may be called again in cleanupVpnStateLocked() if
            // exitVpnRunner() was called from exit(), but it will be a no-op.
            agentDisconnect();
            try {
                mContext.unregisterReceiver(mBroadcastReceiver);
            } catch (IllegalArgumentException e) {}
        }

        @Override
        public void run() {
            // Wait for the previous thread since it has been interrupted.
            Log.v(TAG, "Waiting");
            synchronized (TAG) {
                Log.v(TAG, "Executing");
                try {
                    bringup();
                    waitForDaemonsToStop();
                    interrupted(); // Clear interrupt flag if execute called exit.
                } catch (InterruptedException e) {
                } finally {
                    for (LocalSocket socket : mSockets) {
                        IoUtils.closeQuietly(socket);
                    }
                    // This sleep is necessary for racoon to successfully complete sending delete
                    // message to server.
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                    }
                    for (String daemon : mDaemons) {
                        SystemService.stop(daemon);
                    }
                }
                agentDisconnect();
            }
        }

        private void checkInterruptAndDelay(boolean sleepLonger) throws InterruptedException {
            long now = SystemClock.elapsedRealtime();
            if (now - mBringupStartTime <= 60000) {
                Thread.sleep(sleepLonger ? 200 : 1);
            } else {
                updateState(DetailedState.FAILED, "checkpoint");
                throw new IllegalStateException("VPN bringup took too long");
            }
        }

        private void bringup() {
            // Catch all exceptions so we can clean up a few things.
            try {
                // Initialize the timer.
                mBringupStartTime = SystemClock.elapsedRealtime();

                // Wait for the daemons to stop.
                for (String daemon : mDaemons) {
                    while (!SystemService.isStopped(daemon)) {
                        checkInterruptAndDelay(true);
                    }
                }

                // Clear the previous state.
                File state = new File("/data/misc/vpn/state");
                state.delete();
                if (state.exists()) {
                    throw new IllegalStateException("Cannot delete the state");
                }
                new File("/data/misc/vpn/abort").delete();

                // Check if we need to restart any of the daemons.
                boolean restart = false;
                for (String[] arguments : mArguments) {
                    restart = restart || (arguments != null);
                }
                if (!restart) {
                    agentDisconnect();
                    return;
                }
                updateState(DetailedState.CONNECTING, "execute");

                // Start the daemon with arguments.
                for (int i = 0; i < mDaemons.length; ++i) {
                    String[] arguments = mArguments[i];
                    if (arguments == null) {
                        continue;
                    }

                    // Start the daemon.
                    String daemon = mDaemons[i];
                    SystemService.start(daemon);

                    // Wait for the daemon to start.
                    while (!SystemService.isRunning(daemon)) {
                        checkInterruptAndDelay(true);
                    }

                    // Create the control socket.
                    mSockets[i] = new LocalSocket();
                    LocalSocketAddress address = new LocalSocketAddress(
                            daemon, LocalSocketAddress.Namespace.RESERVED);

                    // Wait for the socket to connect.
                    while (true) {
                        try {
                            mSockets[i].connect(address);
                            break;
                        } catch (Exception e) {
                            // ignore
                        }
                        checkInterruptAndDelay(true);
                    }
                    mSockets[i].setSoTimeout(500);

                    // Send over the arguments.
                    OutputStream out = mSockets[i].getOutputStream();
                    for (String argument : arguments) {
                        byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
                        if (bytes.length >= 0xFFFF) {
                            throw new IllegalArgumentException("Argument is too large");
                        }
                        out.write(bytes.length >> 8);
                        out.write(bytes.length);
                        out.write(bytes);
                        checkInterruptAndDelay(false);
                    }
                    out.write(0xFF);
                    out.write(0xFF);

                    // Wait for End-of-File.
                    InputStream in = mSockets[i].getInputStream();
                    while (true) {
                        try {
                            if (in.read() == -1) {
                                break;
                            }
                        } catch (Exception e) {
                            // ignore
                        }
                        checkInterruptAndDelay(true);
                    }
                }

                // Wait for the daemons to create the new state.
                while (!state.exists()) {
                    // Check if a running daemon is dead.
                    for (int i = 0; i < mDaemons.length; ++i) {
                        String daemon = mDaemons[i];
                        if (mArguments[i] != null && !SystemService.isRunning(daemon)) {
                            throw new IllegalStateException(daemon + " is dead");
                        }
                    }
                    checkInterruptAndDelay(true);
                }

                // Now we are connected. Read and parse the new state.
                String[] parameters = FileUtils.readTextFile(state, 0, null).split("\n", -1);
                if (parameters.length != 7) {
                    throw new IllegalStateException("Cannot parse the state");
                }

                // Set the interface and the addresses in the config.
                mConfig.interfaze = parameters[0].trim();

                mConfig.addLegacyAddresses(parameters[1]);
                // Set the routes if they are not set in the config.
                if (mConfig.routes == null || mConfig.routes.isEmpty()) {
                    mConfig.addLegacyRoutes(parameters[2]);
                }

                // Set the DNS servers if they are not set in the config.
                if (mConfig.dnsServers == null || mConfig.dnsServers.size() == 0) {
                    String dnsServers = parameters[3].trim();
                    if (!dnsServers.isEmpty()) {
                        mConfig.dnsServers = Arrays.asList(dnsServers.split(" "));
                    }
                }

                // Set the search domains if they are not set in the config.
                if (mConfig.searchDomains == null || mConfig.searchDomains.size() == 0) {
                    String searchDomains = parameters[4].trim();
                    if (!searchDomains.isEmpty()) {
                        mConfig.searchDomains = Arrays.asList(searchDomains.split(" "));
                    }
                }

                // Add a throw route for the VPN server endpoint, if one was specified.
                String endpoint = parameters[5].isEmpty() ? mProfile.server : parameters[5];
                if (!endpoint.isEmpty()) {
                    try {
                        InetAddress addr = InetAddress.parseNumericAddress(endpoint);
                        if (addr instanceof Inet4Address) {
                            mConfig.routes.add(new RouteInfo(new IpPrefix(addr, 32), RTN_THROW));
                        } else if (addr instanceof Inet6Address) {
                            mConfig.routes.add(new RouteInfo(new IpPrefix(addr, 128), RTN_THROW));
                        } else {
                            Log.e(TAG, "Unknown IP address family for VPN endpoint: " + endpoint);
                        }
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "Exception constructing throw route to " + endpoint + ": " + e);
                    }
                }

                // Here is the last step and it must be done synchronously.
                synchronized (Vpn.this) {
                    // Set the start time
                    mConfig.startTime = SystemClock.elapsedRealtime();

                    // Check if the thread was interrupted while we were waiting on the lock.
                    checkInterruptAndDelay(false);

                    // Check if the interface is gone while we are waiting.
                    if (jniCheck(mConfig.interfaze) == 0) {
                        throw new IllegalStateException(mConfig.interfaze + " is gone");
                    }

                    // Now INetworkManagementEventObserver is watching our back.
                    mInterface = mConfig.interfaze;
                    prepareStatusIntent();

                    agentConnect();

                    Log.i(TAG, "Connected!");
                }
            } catch (Exception e) {
                Log.i(TAG, "Aborting", e);
                updateState(DetailedState.FAILED, e.getMessage());
                exitVpnRunner();
            }
        }

        /**
         * Check all daemons every two seconds. Return when one of them is stopped.
         * The caller will move to the disconnected state when this function returns,
         * which can happen if a daemon failed or if the VPN was torn down.
         */
        private void waitForDaemonsToStop() throws InterruptedException {
            if (!mNetworkInfo.isConnected()) {
                return;
            }
            while (true) {
                Thread.sleep(2000);
                for (int i = 0; i < mDaemons.length; i++) {
                    if (mArguments[i] != null && SystemService.isStopped(mDaemons[i])) {
                        return;
                    }
                }
            }
        }
    }

    private void verifyCallingUidAndPackage(String packageName) {
        if (getAppUid(packageName, mUserHandle) != Binder.getCallingUid()) {
            throw new SecurityException("Mismatched package and UID");
        }
    }

    @VisibleForTesting
    String getProfileNameForPackage(String packageName) {
        return Credentials.PLATFORM_VPN + mUserHandle + "_" + packageName;
    }

    /**
     * Stores an app-provisioned VPN profile and returns whether the app is already prepared.
     *
     * @param packageName the package name of the app provisioning this profile
     * @param profile the profile to be stored and provisioned
     * @param keyStore the System keystore instance to save VPN profiles
     * @returns whether or not the app has already been granted user consent
     */
    public synchronized boolean provisionVpnProfile(
            @NonNull String packageName, @NonNull VpnProfile profile, @NonNull KeyStore keyStore) {
        checkNotNull(packageName, "No package name provided");
        checkNotNull(profile, "No profile provided");
        checkNotNull(keyStore, "KeyStore missing");

        verifyCallingUidAndPackage(packageName);
        enforceNotRestrictedUser();

        final byte[] encodedProfile = profile.encode();
        if (encodedProfile.length > MAX_VPN_PROFILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Profile too big");
        }

        // Permissions checked during startVpnProfile()
        Binder.withCleanCallingIdentity(
                () -> {
                    keyStore.put(
                            getProfileNameForPackage(packageName),
                            encodedProfile,
                            Process.SYSTEM_UID,
                            0 /* flags */);
                });

        // TODO: if package has CONTROL_VPN, grant the ACTIVATE_PLATFORM_VPN appop.
        // This mirrors the prepareAndAuthorize that is used by VpnService.

        // Return whether the app is already pre-consented
        return isVpnProfilePreConsented(mContext, packageName);
    }

    private boolean isCurrentIkev2VpnLocked(@NonNull String packageName) {
        return isCurrentPreparedPackage(packageName) && mVpnRunner instanceof IkeV2VpnRunner;
    }

    /**
     * Deletes an app-provisioned VPN profile.
     *
     * @param packageName the package name of the app provisioning this profile
     * @param keyStore the System keystore instance to save VPN profiles
     */
    public synchronized void deleteVpnProfile(
            @NonNull String packageName, @NonNull KeyStore keyStore) {
        checkNotNull(packageName, "No package name provided");
        checkNotNull(keyStore, "KeyStore missing");

        verifyCallingUidAndPackage(packageName);
        enforceNotRestrictedUser();

        Binder.withCleanCallingIdentity(
                () -> {
                    // If this profile is providing the current VPN, turn it off, disabling
                    // always-on as well if enabled.
                    if (isCurrentIkev2VpnLocked(packageName)) {
                        if (mAlwaysOn) {
                            // Will transitively call prepareInternal(VpnConfig.LEGACY_VPN).
                            setAlwaysOnPackage(null, false, null, keyStore);
                        } else {
                            prepareInternal(VpnConfig.LEGACY_VPN);
                        }
                    }

                    keyStore.delete(getProfileNameForPackage(packageName), Process.SYSTEM_UID);
                });
    }

    /**
     * Retrieves the VpnProfile.
     *
     * <p>Must be used only as SYSTEM_UID, otherwise the key/UID pair will not match anything in the
     * keystore.
     */
    @VisibleForTesting
    @Nullable
    VpnProfile getVpnProfilePrivileged(@NonNull String packageName, @NonNull KeyStore keyStore) {
        if (!mSystemServices.isCallerSystem()) {
            Log.wtf(TAG, "getVpnProfilePrivileged called as non-System UID ");
            return null;
        }

        final byte[] encoded = keyStore.get(getProfileNameForPackage(packageName));
        if (encoded == null) return null;

        return VpnProfile.decode("" /* Key unused */, encoded);
    }

    /**
     * Starts an already provisioned VPN Profile, keyed by package name.
     *
     * <p>This method is meant to be called by apps (via VpnManager and ConnectivityService).
     * Privileged (system) callers should use startVpnProfilePrivileged instead. Otherwise the UIDs
     * will not match during appop checks.
     *
     * @param packageName the package name of the app provisioning this profile
     * @param keyStore the System keystore instance to retrieve VPN profiles
     */
    public synchronized void startVpnProfile(
            @NonNull String packageName, @NonNull KeyStore keyStore) {
        checkNotNull(packageName, "No package name provided");
        checkNotNull(keyStore, "KeyStore missing");

        enforceNotRestrictedUser();

        // Prepare VPN for startup
        if (!prepare(packageName, null /* newPackage */, VpnManager.TYPE_VPN_PLATFORM)) {
            throw new SecurityException("User consent not granted for package " + packageName);
        }

        Binder.withCleanCallingIdentity(
                () -> {
                    final VpnProfile profile = getVpnProfilePrivileged(packageName, keyStore);
                    if (profile == null) {
                        throw new IllegalArgumentException("No profile found for " + packageName);
                    }

                    startVpnProfilePrivileged(profile, packageName,
                            null /* keyStore for private key retrieval - unneeded */);
                });
    }

    private synchronized void startVpnProfilePrivileged(
            @NonNull VpnProfile profile, @NonNull String packageName, @Nullable KeyStore keyStore) {
        // Make sure VPN is prepared. This method can be called by user apps via startVpnProfile(),
        // by the Setting app via startLegacyVpn(), or by ConnectivityService via
        // startAlwaysOnVpn(), so this is the common place to prepare the VPN. This also has the
        // nice property of ensuring there are no other VpnRunner instances running.
        prepareInternal(packageName);
        updateState(DetailedState.CONNECTING, "startPlatformVpn");

        try {
            // Build basic config
            mConfig = new VpnConfig();
            if (VpnConfig.LEGACY_VPN.equals(packageName)) {
                mConfig.legacy = true;
                mConfig.session = profile.name;
                mConfig.user = profile.key;

                // TODO: Add support for configuring meteredness via Settings. Until then, use a
                // safe default.
                mConfig.isMetered = true;
            } else {
                mConfig.user = packageName;
                mConfig.isMetered = profile.isMetered;
            }
            mConfig.startTime = SystemClock.elapsedRealtime();
            mConfig.proxyInfo = profile.proxy;

            switch (profile.type) {
                case VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS:
                case VpnProfile.TYPE_IKEV2_IPSEC_PSK:
                case VpnProfile.TYPE_IKEV2_IPSEC_RSA:
                    mVpnRunner =
                            new IkeV2VpnRunner(Ikev2VpnProfile.fromVpnProfile(profile, keyStore));
                    mVpnRunner.start();
                    break;
                default:
                    updateState(DetailedState.FAILED, "Invalid platform VPN type");
                    Log.d(TAG, "Unknown VPN profile type: " + profile.type);
                    break;
            }
        } catch (IOException | GeneralSecurityException e) {
            // Reset mConfig
            mConfig = null;

            updateState(DetailedState.FAILED, "VPN startup failed");
            throw new IllegalArgumentException("VPN startup failed", e);
        }
    }

    /**
     * Stops an already running VPN Profile for the given package.
     *
     * <p>This method is meant to be called by apps (via VpnManager and ConnectivityService).
     * Privileged (system) callers should (re-)prepare the LEGACY_VPN instead.
     *
     * @param packageName the package name of the app provisioning this profile
     */
    public synchronized void stopVpnProfile(@NonNull String packageName) {
        checkNotNull(packageName, "No package name provided");

        enforceNotRestrictedUser();

        // To stop the VPN profile, the caller must be the current prepared package and must be
        // running an Ikev2VpnProfile.
        if (isCurrentIkev2VpnLocked(packageName)) {
            prepareInternal(VpnConfig.LEGACY_VPN);
        }
    }

    /**
     * Proxy to allow testing
     *
     * @hide
     */
    @VisibleForTesting
    public static class Ikev2SessionCreator {
        /** Creates a IKE session */
        public IkeSession createIkeSession(
                @NonNull Context context,
                @NonNull IkeSessionParams ikeSessionParams,
                @NonNull ChildSessionParams firstChildSessionParams,
                @NonNull Executor userCbExecutor,
                @NonNull IkeSessionCallback ikeSessionCallback,
                @NonNull ChildSessionCallback firstChildSessionCallback) {
            return new IkeSession(
                    context,
                    ikeSessionParams,
                    firstChildSessionParams,
                    userCbExecutor,
                    ikeSessionCallback,
                    firstChildSessionCallback);
        }
    }
}
