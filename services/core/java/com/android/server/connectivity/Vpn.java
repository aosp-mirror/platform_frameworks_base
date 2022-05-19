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
import static android.Manifest.permission.CONTROL_VPN;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.RouteInfo.RTN_THROW;
import static android.net.RouteInfo.RTN_UNREACHABLE;
import static android.net.VpnManager.NOTIFICATION_CHANNEL_VPN;
import static android.os.PowerWhitelistManager.REASON_VPN;
import static android.os.UserHandle.PER_USER_RANGE;

import static com.android.server.vcn.util.PersistableBundleUtils.STRING_DESERIALIZER;

import static java.util.Objects.requireNonNull;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
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
import android.net.DnsResolver;
import android.net.INetd;
import android.net.INetworkManagementEventObserver;
import android.net.Ikev2VpnProfile;
import android.net.InetAddresses;
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
import android.net.NetworkRequest;
import android.net.NetworkScore;
import android.net.RouteInfo;
import android.net.UidRangeParcel;
import android.net.UnderlyingNetworkInfo;
import android.net.VpnManager;
import android.net.VpnProfileState;
import android.net.VpnService;
import android.net.VpnTransportInfo;
import android.net.ipsec.ike.ChildSessionCallback;
import android.net.ipsec.ike.ChildSessionConfiguration;
import android.net.ipsec.ike.ChildSessionParams;
import android.net.ipsec.ike.IkeSession;
import android.net.ipsec.ike.IkeSessionCallback;
import android.net.ipsec.ike.IkeSessionConfiguration;
import android.net.ipsec.ike.IkeSessionConnectionInfo;
import android.net.ipsec.ike.IkeSessionParams;
import android.net.ipsec.ike.IkeTunnelConnectionParams;
import android.net.ipsec.ike.exceptions.IkeNetworkLostException;
import android.net.ipsec.ike.exceptions.IkeNonProtocolException;
import android.net.ipsec.ike.exceptions.IkeProtocolException;
import android.net.ipsec.ike.exceptions.IkeTimeoutException;
import android.os.Binder;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemService;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore2;
import android.security.keystore.KeyProperties;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyPermission;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Range;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.NetdUtils;
import com.android.net.module.util.NetworkStackConstants;
import com.android.server.DeviceIdleInternal;
import com.android.server.LocalServices;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.vcn.util.PersistableBundleUtils;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @hide
 */
public class Vpn {
    private static final String NETWORKTYPE = "VPN";
    private static final String TAG = "Vpn";
    private static final String VPN_PROVIDER_NAME_BASE = "VpnNetworkProvider:";
    private static final boolean LOGD = true;
    private static final String ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore";
    /** Key containing prefix of vpn app excluded list */
    @VisibleForTesting static final String VPN_APP_EXCLUDED = "VPN_APP_EXCLUDED_";

    // Length of time (in milliseconds) that an app hosting an always-on VPN is placed on
    // the device idle allowlist during service launch and VPN bootstrap.
    private static final long VPN_LAUNCH_IDLE_ALLOWLIST_DURATION_MS = 60 * 1000;

    private static final String LOCKDOWN_ALLOWLIST_SETTING_NAME =
            Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN_WHITELIST;
    /**
     * Largest profile size allowable for Platform VPNs.
     *
     * <p>The largest platform VPN profiles use IKEv2 RSA Certificate Authentication and have two
     * X509Certificates, and one RSAPrivateKey. This should lead to a max size of 2x 12kB for the
     * certificates, plus a reasonable upper bound on the private key of 32kB. The rest of the
     * profile is expected to be negligible in size.
     */
    @VisibleForTesting static final int MAX_VPN_PROFILE_SIZE_BYTES = 1 << 17; // 128kB

    /**
     * Network score that VPNs will announce to ConnectivityService.
     * TODO: remove when the network scoring refactor lands.
     */
    private static final int VPN_DEFAULT_SCORE = 101;

    // TODO: create separate trackers for each unique VPN to support
    // automated reconnection

    private final Context mContext;
    private final ConnectivityManager mConnectivityManager;
    private final AppOpsManager mAppOpsManager;
    // The context is for specific user which is created from mUserId
    private final Context mUserIdContext;
    @VisibleForTesting final Dependencies mDeps;
    private final NetworkInfo mNetworkInfo;
    @GuardedBy("this")
    private int mLegacyState;
    @GuardedBy("this")
    @VisibleForTesting protected String mPackage;
    private int mOwnerUID;
    private boolean mIsPackageTargetingAtLeastQ;
    @VisibleForTesting
    protected String mInterface;
    private Connection mConnection;

    /** Tracks the runners for all VPN types managed by the platform (eg. LegacyVpn, PlatformVpn) */
    @VisibleForTesting protected VpnRunner mVpnRunner;

    private PendingIntent mStatusIntent;
    private volatile boolean mEnableTeardown = true;
    private final INetworkManagementService mNms;
    private final INetd mNetd;
    @VisibleForTesting
    protected VpnConfig mConfig;
    private final NetworkProvider mNetworkProvider;
    @VisibleForTesting
    protected NetworkAgent mNetworkAgent;
    private final Looper mLooper;
    @VisibleForTesting
    protected NetworkCapabilities mNetworkCapabilities;
    private final SystemServices mSystemServices;
    private final Ikev2SessionCreator mIkev2SessionCreator;
    private final UserManager mUserManager;

    private final VpnProfileStore mVpnProfileStore;

    @VisibleForTesting
    VpnProfileStore getVpnProfileStore() {
        return mVpnProfileStore;
    }

    /**
     * Whether to keep the connection active after rebooting, or upgrading or reinstalling. This
     * only applies to {@link VpnService} connections.
     */
    @GuardedBy("this")
    @VisibleForTesting protected boolean mAlwaysOn = false;

    /**
     * Whether to disable traffic outside of this VPN even when the VPN is not connected. System
     * apps can still bypass by choosing explicit networks. Has no effect if {@link mAlwaysOn} is
     * not set. Applies to all types of VPNs.
     */
    @GuardedBy("this")
    @VisibleForTesting protected boolean mLockdown = false;

    /**
     * Set of packages in addition to the VPN app itself that can access the network directly when
     * VPN is not connected even if {@code mLockdown} is set.
     */
    private @NonNull List<String> mLockdownAllowlist = Collections.emptyList();

     /**
     * A memory of what UIDs this class told ConnectivityService to block for the lockdown feature.
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
    private final Set<UidRangeParcel> mBlockedUidsAsToldToConnectivity = new ArraySet<>();

    // The user id of initiating VPN.
    private final int mUserId;

    interface RetryScheduler {
        void checkInterruptAndDelay(boolean sleepLonger) throws InterruptedException;
    }

    @VisibleForTesting
    public static class Dependencies {
        public boolean isCallerSystem() {
            return Binder.getCallingUid() == Process.SYSTEM_UID;
        }

        public void startService(final String serviceName) {
            SystemService.start(serviceName);
        }

        public void stopService(final String serviceName) {
            SystemService.stop(serviceName);
        }

        public boolean isServiceRunning(final String serviceName) {
            return SystemService.isRunning(serviceName);
        }

        public boolean isServiceStopped(final String serviceName) {
            return SystemService.isStopped(serviceName);
        }

        public File getStateFile() {
            return new File("/data/misc/vpn/state");
        }

        public DeviceIdleInternal getDeviceIdleInternal() {
            return LocalServices.getService(DeviceIdleInternal.class);
        }

        public PendingIntent getIntentForStatusPanel(Context context) {
            return VpnConfig.getIntentForStatusPanel(context);
        }

        public void sendArgumentsToDaemon(
                final String daemon, final LocalSocket socket, final String[] arguments,
                final RetryScheduler retryScheduler) throws IOException, InterruptedException {
            final LocalSocketAddress address = new LocalSocketAddress(
                    daemon, LocalSocketAddress.Namespace.RESERVED);

            // Wait for the socket to connect.
            while (true) {
                try {
                    socket.connect(address);
                    break;
                } catch (Exception e) {
                    // ignore
                }
                retryScheduler.checkInterruptAndDelay(true /* sleepLonger */);
            }
            socket.setSoTimeout(500);

            final OutputStream out = socket.getOutputStream();
            for (String argument : arguments) {
                byte[] bytes = argument.getBytes(StandardCharsets.UTF_8);
                if (bytes.length >= 0xFFFF) {
                    throw new IllegalArgumentException("Argument is too large");
                }
                out.write(bytes.length >> 8);
                out.write(bytes.length);
                out.write(bytes);
                retryScheduler.checkInterruptAndDelay(false /* sleepLonger */);
            }
            out.write(0xFF);
            out.write(0xFF);

            // Wait for End-of-File.
            final InputStream in = socket.getInputStream();
            while (true) {
                try {
                    if (in.read() == -1) {
                        break;
                    }
                } catch (Exception e) {
                    // ignore
                }
                retryScheduler.checkInterruptAndDelay(true /* sleepLonger */);
            }
        }

        @NonNull
        public InetAddress resolve(final String endpoint)
                throws ExecutionException, InterruptedException {
            try {
                return InetAddresses.parseNumericAddress(endpoint);
            } catch (IllegalArgumentException e) {
                // Endpoint is not numeric : fall through and resolve
            }

            final CancellationSignal cancellationSignal = new CancellationSignal();
            try {
                final DnsResolver resolver = DnsResolver.getInstance();
                final CompletableFuture<InetAddress> result = new CompletableFuture();
                final DnsResolver.Callback<List<InetAddress>> cb =
                        new DnsResolver.Callback<List<InetAddress>>() {
                            @Override
                            public void onAnswer(@NonNull final List<InetAddress> answer,
                                    final int rcode) {
                                if (answer.size() > 0) {
                                    result.complete(answer.get(0));
                                } else {
                                    result.completeExceptionally(
                                            new UnknownHostException(endpoint));
                                }
                            }

                            @Override
                            public void onError(@Nullable final DnsResolver.DnsException error) {
                                // Unfortunately UnknownHostException doesn't accept a cause, so
                                // print a message here instead. Only show the summary, not the
                                // full stack trace.
                                Log.e(TAG, "Async dns resolver error : " + error);
                                result.completeExceptionally(new UnknownHostException(endpoint));
                            }
                        };
                resolver.query(null /* network, null for default */, endpoint,
                        DnsResolver.FLAG_EMPTY, r -> r.run(), cancellationSignal, cb);
                return result.get();
            } catch (final ExecutionException e) {
                Log.e(TAG, "Cannot resolve VPN endpoint : " + endpoint + ".", e);
                throw e;
            } catch (final InterruptedException e) {
                Log.e(TAG, "Legacy VPN was interrupted while resolving the endpoint", e);
                cancellationSignal.cancel();
                throw e;
            }
        }

        public boolean isInterfacePresent(final Vpn vpn, final String iface) {
            return vpn.jniCheck(iface) != 0;
        }

        /**
         * @see ParcelFileDescriptor#adoptFd(int)
         */
        public ParcelFileDescriptor adoptFd(Vpn vpn, int mtu) {
            return ParcelFileDescriptor.adoptFd(jniCreate(vpn, mtu));
        }

        /**
         * Call native method to create the VPN interface and return the FileDescriptor of /dev/tun.
         */
        public int jniCreate(Vpn vpn, int mtu) {
            return vpn.jniCreate(mtu);
        }

        /**
         * Call native method to get the interface name of VPN.
         */
        public String jniGetName(Vpn vpn, int fd) {
            return vpn.jniGetName(fd);
        }

        /**
         * Call native method to set the VPN addresses and return the number of addresses.
         */
        public int jniSetAddresses(Vpn vpn, String interfaze, String addresses) {
            return vpn.jniSetAddresses(interfaze, addresses);
        }

        /**
         * @see IoUtils#setBlocking(FileDescriptor, boolean)
         */
        public void setBlocking(FileDescriptor fd, boolean blocking) {
            try {
                IoUtils.setBlocking(fd, blocking);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Cannot set tunnel's fd as blocking=" + blocking, e);
            }
        }
    }

    public Vpn(Looper looper, Context context, INetworkManagementService netService, INetd netd,
            @UserIdInt int userId, VpnProfileStore vpnProfileStore) {
        this(looper, context, new Dependencies(), netService, netd, userId, vpnProfileStore,
                new SystemServices(context), new Ikev2SessionCreator());
    }

    @VisibleForTesting
    public Vpn(Looper looper, Context context, Dependencies deps,
            INetworkManagementService netService, INetd netd, @UserIdInt int userId,
            VpnProfileStore vpnProfileStore) {
        this(looper, context, deps, netService, netd, userId, vpnProfileStore,
                new SystemServices(context), new Ikev2SessionCreator());
    }

    @VisibleForTesting
    protected Vpn(Looper looper, Context context, Dependencies deps,
            INetworkManagementService netService, INetd netd,
            int userId, VpnProfileStore vpnProfileStore, SystemServices systemServices,
            Ikev2SessionCreator ikev2SessionCreator) {
        mVpnProfileStore = vpnProfileStore;
        mContext = context;
        mConnectivityManager = mContext.getSystemService(ConnectivityManager.class);
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mUserIdContext = context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);
        mDeps = deps;
        mNms = netService;
        mNetd = netd;
        mUserId = userId;
        mLooper = looper;
        mSystemServices = systemServices;
        mIkev2SessionCreator = ikev2SessionCreator;
        mUserManager = mContext.getSystemService(UserManager.class);

        mPackage = VpnConfig.LEGACY_VPN;
        mOwnerUID = getAppUid(mPackage, mUserId);
        mIsPackageTargetingAtLeastQ = doesPackageTargetAtLeastQ(mPackage);

        try {
            netService.registerObserver(mObserver);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Problem registering observer", e);
        }

        mNetworkProvider = new NetworkProvider(context, looper, VPN_PROVIDER_NAME_BASE + mUserId);
        // This constructor is called in onUserStart and registers the provider. The provider
        // will be unregistered in onUserStop.
        mConnectivityManager.registerNetworkProvider(mNetworkProvider);
        mLegacyState = LegacyVpnInfo.STATE_DISCONNECTED;
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_VPN, 0 /* subtype */, NETWORKTYPE,
                "" /* subtypeName */);
        mNetworkCapabilities = new NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
                .setTransportInfo(new VpnTransportInfo(VpnManager.TYPE_VPN_NONE, null))
                .build();

        loadAlwaysOnPackage();
    }

    /**
     * Set whether this object is responsible for watching for {@link NetworkInfo}
     * teardown. When {@code false}, teardown is handled externally by someone
     * else.
     */
    public void setEnableTeardown(boolean enableTeardown) {
        mEnableTeardown = enableTeardown;
    }

    @VisibleForTesting
    public boolean getEnableTeardown() {
        return mEnableTeardown;
    }

    /**
     * Update current state, dispatching event to listeners.
     */
    @VisibleForTesting
    @GuardedBy("this")
    protected void updateState(DetailedState detailedState, String reason) {
        if (LOGD) Log.d(TAG, "setting state=" + detailedState + ", reason=" + reason);
        mLegacyState = LegacyVpnInfo.stateFromNetworkInfo(detailedState);
        mNetworkInfo.setDetailedState(detailedState, reason, null);
        // TODO : only accept transitions when the agent is in the correct state (non-null for
        // CONNECTED, DISCONNECTED and FAILED, null for CONNECTED).
        // This will require a way for tests to pretend the VPN is connected that's not
        // calling this method with CONNECTED.
        // It will also require audit of where the code calls this method with DISCONNECTED
        // with a null agent, which it was doing historically to make sure the agent is
        // disconnected as this was a no-op if the agent was null.
        switch (detailedState) {
            case CONNECTED:
                if (null != mNetworkAgent) {
                    mNetworkAgent.markConnected();
                }
                break;
            case DISCONNECTED:
            case FAILED:
                if (null != mNetworkAgent) {
                    mNetworkAgent.unregister();
                    mNetworkAgent = null;
                }
                break;
            case CONNECTING:
                if (null != mNetworkAgent) {
                    throw new IllegalStateException("VPN can only go to CONNECTING state when"
                            + " the agent is null.");
                }
                break;
            default:
                throw new IllegalArgumentException("Illegal state argument " + detailedState);
        }
        updateAlwaysOnNotification(detailedState);
    }

    private void resetNetworkCapabilities() {
        mNetworkCapabilities = new NetworkCapabilities.Builder(mNetworkCapabilities)
                .setUids(null)
                .setTransportInfo(new VpnTransportInfo(VpnManager.TYPE_VPN_NONE, null))
                .build();
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

    /** Returns the package name that is currently prepared. */
    public synchronized String getPackage() {
        return mPackage;
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
     * @return {@code true} if and only if the VPN app exists and supports always-on mode
     */
    public boolean isAlwaysOnPackageSupported(String packageName) {
        enforceSettingsPermission();

        if (packageName == null) {
            return false;
        }

        final long oldId = Binder.clearCallingIdentity();
        try {
            if (getVpnProfilePrivileged(packageName) != null) {
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }

        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo appInfo = null;
        try {
            appInfo = pm.getApplicationInfoAsUser(packageName, 0 /*flags*/, mUserId);
        } catch (NameNotFoundException unused) {
            Log.w(TAG, "Can't find \"" + packageName + "\" when checking always-on support");
        }
        if (appInfo == null || appInfo.targetSdkVersion < VERSION_CODES.N) {
            return false;
        }

        final Intent intent = new Intent(VpnConfig.SERVICE_INTERFACE);
        intent.setPackage(packageName);
        List<ResolveInfo> services =
                pm.queryIntentServicesAsUser(intent, PackageManager.GET_META_DATA, mUserId);
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

    private boolean sendEventToVpnManagerApp(@NonNull String category, int errorClass,
            int errorCode, @NonNull final String packageName, @Nullable final String sessionKey,
            @NonNull final VpnProfileState profileState, @Nullable final Network underlyingNetwork,
            @Nullable final NetworkCapabilities nc, @Nullable final LinkProperties lp) {
        final Intent intent = new Intent(VpnManager.ACTION_VPN_MANAGER_EVENT);
        intent.setPackage(packageName);
        intent.addCategory(category);
        intent.putExtra(VpnManager.EXTRA_VPN_PROFILE_STATE, profileState);
        intent.putExtra(VpnManager.EXTRA_SESSION_KEY, sessionKey);
        intent.putExtra(VpnManager.EXTRA_UNDERLYING_NETWORK, underlyingNetwork);
        intent.putExtra(VpnManager.EXTRA_UNDERLYING_NETWORK_CAPABILITIES, nc);
        intent.putExtra(VpnManager.EXTRA_UNDERLYING_LINK_PROPERTIES, lp);
        intent.putExtra(VpnManager.EXTRA_TIMESTAMP_MILLIS, System.currentTimeMillis());
        if (!VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER.equals(category)
                || !VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED.equals(category)) {
            intent.putExtra(VpnManager.EXTRA_ERROR_CLASS, errorClass);
            intent.putExtra(VpnManager.EXTRA_ERROR_CODE, errorCode);
        }
        try {
            return mUserIdContext.startService(intent) != null;
        } catch (RuntimeException e) {
            Log.e(TAG, "Service of VpnManager app " + intent + " failed to start", e);
            return false;
        }
    }

    private boolean isVpnApp(String packageName) {
        return packageName != null && !VpnConfig.LEGACY_VPN.equals(packageName);
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
     * @param lockdownAllowlist packages to be allowed from lockdown.
     * @return {@code true} if the package has been set as always-on, {@code false} otherwise.
     */
    public synchronized boolean setAlwaysOnPackage(
            @Nullable String packageName,
            boolean lockdown,
            @Nullable List<String> lockdownAllowlist) {
        enforceControlPermissionOrInternalCaller();
        // Store mPackage since it might be reset or might be replaced with the other VPN app.
        final String oldPackage = mPackage;
        final boolean isPackageChanged = !Objects.equals(packageName, oldPackage);
        // TODO: Remove "SdkLevel.isAtLeastT()" check once VpnManagerService is decoupled from
        //  ConnectivityServiceTest.
        // Only notify VPN apps that were already always-on, and only if the always-on provider
        // changed, or the lockdown mode changed.
        final boolean shouldNotifyOldPkg = isVpnApp(oldPackage) && mAlwaysOn
                && (lockdown != mLockdown || isPackageChanged);
        // Also notify the new package if there was a provider change.
        final boolean shouldNotifyNewPkg = isVpnApp(packageName) && isPackageChanged;

        if (!setAlwaysOnPackageInternal(packageName, lockdown, lockdownAllowlist)) {
            return false;
        }

        saveAlwaysOnPackage();

        // TODO(b/230548427): Remove SDK check once VPN related stuff are decoupled from
        //  ConnectivityServiceTest.
        if (!SdkLevel.isAtLeastT()) {
            return true;
        }

        if (shouldNotifyOldPkg) {
            // If both of shouldNotifyOldPkg & isPackageChanged are true, that means the
            // always-on of old package is disabled or the old package is replaced with the new
            // package. In this case, VpnProfileState should be disconnected.
            sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED,
                    -1 /* errorClass */, -1 /* errorCode*/, oldPackage,
                    null /* sessionKey */, isPackageChanged ? makeDisconnectedVpnProfileState()
                            : makeVpnProfileStateLocked(),
                    null /* underlyingNetwork */, null /* nc */, null /* lp */);
        }

        if (shouldNotifyNewPkg) {
            sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_ALWAYS_ON_STATE_CHANGED,
                    -1 /* errorClass */, -1 /* errorCode*/, packageName,
                    getSessionKeyLocked(), makeVpnProfileStateLocked(),
                    null /* underlyingNetwork */, null /* nc */, null /* lp */);
        }
        return true;
    }

    /**
     * Configures an always-on VPN connection through a specific application, the same as {@link
     * #setAlwaysOnPackage}.
     *
     * <p>Does not perform permission checks. Does not persist any of the changes to storage.
     *
     * @param packageName the package to designate as always-on VPN supplier.
     * @param lockdown whether to prevent traffic outside of a VPN, for example while connecting.
     * @param lockdownAllowlist packages to be allowed to bypass lockdown. This is only used if
     *     {@code lockdown} is {@code true}. Packages must not contain commas.
     * @return {@code true} if the package has been set as always-on, {@code false} otherwise.
     */
    @GuardedBy("this")
    private boolean setAlwaysOnPackageInternal(
            @Nullable String packageName, boolean lockdown,
            @Nullable List<String> lockdownAllowlist) {
        if (VpnConfig.LEGACY_VPN.equals(packageName)) {
            Log.w(TAG, "Not setting legacy VPN \"" + packageName + "\" as always-on.");
            return false;
        }

        if (lockdownAllowlist != null) {
            for (String pkg : lockdownAllowlist) {
                if (pkg.contains(",")) {
                    Log.w(TAG, "Not setting always-on vpn, invalid allowed package: " + pkg);
                    return false;
                }
            }
        }

        if (packageName != null) {
            final VpnProfile profile;
            final long oldId = Binder.clearCallingIdentity();
            try {
                profile = getVpnProfilePrivileged(packageName);
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
        mLockdownAllowlist = (mLockdown && lockdownAllowlist != null)
                ? Collections.unmodifiableList(new ArrayList<>(lockdownAllowlist))
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
     * @return an immutable list of packages allowed to bypass always-on VPN lockdown.
     */
    public synchronized List<String> getLockdownAllowlist() {
        return mLockdown ? mLockdownAllowlist : null;
    }

    /**
     * Save the always-on package and lockdown config into Settings.Secure
     */
    @GuardedBy("this")
    private void saveAlwaysOnPackage() {
        final long token = Binder.clearCallingIdentity();
        try {
            mSystemServices.settingsSecurePutStringForUser(Settings.Secure.ALWAYS_ON_VPN_APP,
                    getAlwaysOnPackage(), mUserId);
            mSystemServices.settingsSecurePutIntForUser(Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN,
                    (mAlwaysOn && mLockdown ? 1 : 0), mUserId);
            mSystemServices.settingsSecurePutStringForUser(
                    LOCKDOWN_ALLOWLIST_SETTING_NAME,
                    String.join(",", mLockdownAllowlist), mUserId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Load the always-on package and lockdown config from Settings. */
    @GuardedBy("this")
    private void loadAlwaysOnPackage() {
        final long token = Binder.clearCallingIdentity();
        try {
            final String alwaysOnPackage = mSystemServices.settingsSecureGetStringForUser(
                    Settings.Secure.ALWAYS_ON_VPN_APP, mUserId);
            final boolean alwaysOnLockdown = mSystemServices.settingsSecureGetIntForUser(
                    Settings.Secure.ALWAYS_ON_VPN_LOCKDOWN, 0 /*default*/, mUserId) != 0;
            final String allowlistString = mSystemServices.settingsSecureGetStringForUser(
                    LOCKDOWN_ALLOWLIST_SETTING_NAME, mUserId);
            final List<String> allowedPackages = TextUtils.isEmpty(allowlistString)
                    ? Collections.emptyList() : Arrays.asList(allowlistString.split(","));
            setAlwaysOnPackageInternal(
                    alwaysOnPackage, alwaysOnLockdown, allowedPackages);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Starts the currently selected always-on VPN
     *
     * @return {@code true} if the service was started, the service was already connected, or there
     *     was no always-on VPN to start. {@code false} otherwise.
     */
    public boolean startAlwaysOnVpn() {
        final String alwaysOnPackage;
        synchronized (this) {
            alwaysOnPackage = getAlwaysOnPackage();
            // Skip if there is no service to start.
            if (alwaysOnPackage == null) {
                return true;
            }
            // Remove always-on VPN if it's not supported.
            if (!isAlwaysOnPackageSupported(alwaysOnPackage)) {
                setAlwaysOnPackage(null, false, null);
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
            VpnProfile profile = getVpnProfilePrivileged(alwaysOnPackage);
            if (profile != null) {
                startVpnProfilePrivileged(profile, alwaysOnPackage);
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
            DeviceIdleInternal idleController = mDeps.getDeviceIdleInternal();
            idleController.addPowerSaveTempWhitelistApp(Process.myUid(), alwaysOnPackage,
                    VPN_LAUNCH_IDLE_ALLOWLIST_DURATION_MS, mUserId, false, REASON_VPN,
                    "vpn");

            // Start the VPN service declared in the app's manifest.
            Intent serviceIntent = new Intent(VpnConfig.SERVICE_INTERFACE);
            serviceIntent.setPackage(alwaysOnPackage);
            try {
                return mUserIdContext.startService(serviceIntent) != null;
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
     * - oldPackage null, newPackage null: always returns true for backward compatibility.
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
        // Except for Settings and VpnDialogs, the caller should be matched one of oldPackage or
        // newPackage. Otherwise, non VPN owner might get the VPN always-on status of the VPN owner.
        // See b/191382886.
        if (mContext.checkCallingOrSelfPermission(CONTROL_VPN) != PERMISSION_GRANTED) {
            if (oldPackage != null) {
                verifyCallingUidAndPackage(oldPackage);
            }
            if (newPackage != null) {
                verifyCallingUidAndPackage(newPackage);
            }
        }

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

    @GuardedBy("this")
    private boolean isCurrentPreparedPackage(String packageName) {
        // We can't just check that packageName matches mPackage, because if the app was uninstalled
        // and reinstalled it will no longer be prepared. Similarly if there is a shared UID, the
        // calling package may not be the same as the prepared package. Check both UID and package.
        return getAppUid(packageName, mUserId) == mOwnerUID && mPackage.equals(packageName);
    }

    /** Prepare the VPN for the given package. Does not perform permission checks. */
    @GuardedBy("this")
    private void prepareInternal(String newPackage) {
        final long token = Binder.clearCallingIdentity();
        try {
            // Reset the interface.
            if (mInterface != null) {
                mStatusIntent = null;
                agentDisconnect();
                jniReset(mInterface);
                mInterface = null;
                resetNetworkCapabilities();
            }

            // Revoke the connection or stop the VpnRunner.
            if (mConnection != null) {
                try {
                    mConnection.mService.transact(IBinder.LAST_CALL_TRANSACTION,
                            Parcel.obtain(), null, IBinder.FLAG_ONEWAY);
                } catch (Exception e) {
                    // ignore
                }
                mAppOpsManager.finishOp(
                        AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE, mOwnerUID, mPackage, null);
                mContext.unbindService(mConnection);
                cleanupVpnStateLocked();
            } else if (mVpnRunner != null) {
                if (!VpnConfig.LEGACY_VPN.equals(mPackage)) {
                    mAppOpsManager.finishOp(
                            AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER, mOwnerUID, mPackage, null);
                    // The underlying network, NetworkCapabilities and LinkProperties are not
                    // necessary to send to VPN app since the purpose of this event is to notify
                    // VPN app that VPN is deactivated by the user.
                    // TODO(b/230548427): Remove SDK check once VPN related stuff are decoupled from
                    //  ConnectivityServiceTest.
                    if (SdkLevel.isAtLeastT()) {
                        sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_DEACTIVATED_BY_USER,
                                -1 /* errorClass */, -1 /* errorCode*/, mPackage,
                                getSessionKeyLocked(), makeVpnProfileStateLocked(),
                                null /* underlyingNetwork */, null /* nc */, null /* lp */);
                    }
                }
                // cleanupVpnStateLocked() is called from mVpnRunner.exit()
                mVpnRunner.exit();
            }

            try {
                mNms.denyProtect(mOwnerUID);
            } catch (Exception e) {
                Log.wtf(TAG, "Failed to disallow UID " + mOwnerUID + " to call protect() " + e);
            }

            Log.i(TAG, "Switched from " + mPackage + " to " + newPackage);
            mPackage = newPackage;
            mOwnerUID = getAppUid(newPackage, mUserId);
            mIsPackageTargetingAtLeastQ = doesPackageTargetAtLeastQ(newPackage);
            try {
                mNms.allowProtect(mOwnerUID);
            } catch (Exception e) {
                Log.wtf(TAG, "Failed to allow UID " + mOwnerUID + " to call protect() " + e);
            }
            mConfig = null;

            updateState(DetailedState.DISCONNECTED, "prepare");
            setVpnForcedLocked(mLockdown);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Set whether a package has the ability to launch VPNs without user intervention. */
    public boolean setPackageAuthorization(String packageName, @VpnManager.VpnType int vpnType) {
        // Check if the caller is authorized.
        enforceControlPermissionOrInternalCaller();

        final int uid = getAppUid(packageName, mUserId);
        if (uid == -1 || VpnConfig.LEGACY_VPN.equals(packageName)) {
            // Authorization for nonexistent packages (or fake ones) can't be updated.
            return false;
        }

        final long token = Binder.clearCallingIdentity();
        try {
            final String[] toChange;

            // Clear all AppOps if the app is being unauthorized.
            switch (vpnType) {
                case VpnManager.TYPE_VPN_NONE:
                    toChange = new String[] {
                            AppOpsManager.OPSTR_ACTIVATE_VPN,
                            AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN
                    };
                    break;
                case VpnManager.TYPE_VPN_PLATFORM:
                    toChange = new String[] {AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN};
                    break;
                case VpnManager.TYPE_VPN_SERVICE:
                    toChange = new String[] {AppOpsManager.OPSTR_ACTIVATE_VPN};
                    break;
                case VpnManager.TYPE_VPN_LEGACY:
                    return false;
                default:
                    Log.wtf(TAG, "Unrecognized VPN type while granting authorization");
                    return false;
            }

            for (final String appOpStr : toChange) {
                mAppOpsManager.setMode(
                        appOpStr,
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
            case VpnManager.TYPE_VPN_LEGACY:
                return VpnConfig.LEGACY_VPN.equals(packageName);
            default:
                return false;
        }
    }

    private static boolean doesPackageHaveAppop(Context context, String packageName,
            String appOpStr) {
        final AppOpsManager appOps =
                (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);

        // Verify that the caller matches the given package and has the required permission.
        return appOps.noteOpNoThrow(appOpStr, Binder.getCallingUid(), packageName,
                null /* attributionTag */, null /* message */) == AppOpsManager.MODE_ALLOWED;
    }

    private static boolean isVpnServicePreConsented(Context context, String packageName) {
        return doesPackageHaveAppop(context, packageName, AppOpsManager.OPSTR_ACTIVATE_VPN);
    }

    private static boolean isVpnProfilePreConsented(Context context, String packageName) {
        return doesPackageHaveAppop(context, packageName, AppOpsManager.OPSTR_ACTIVATE_PLATFORM_VPN)
                || isVpnServicePreConsented(context, packageName);
    }

    private int getAppUid(final String app, final int userId) {
        if (VpnConfig.LEGACY_VPN.equals(app)) {
            return Process.myUid();
        }
        PackageManager pm = mContext.getPackageManager();
        final long token = Binder.clearCallingIdentity();
        try {
            return pm.getPackageUidAsUser(app, userId);
        } catch (NameNotFoundException e) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean doesPackageTargetAtLeastQ(String packageName) {
        if (VpnConfig.LEGACY_VPN.equals(packageName)) {
            return true;
        }
        PackageManager pm = mContext.getPackageManager();
        try {
            ApplicationInfo appInfo =
                    pm.getApplicationInfoAsUser(packageName, 0 /*flags*/, mUserId);
            return appInfo.targetSdkVersion >= VERSION_CODES.Q;
        } catch (NameNotFoundException unused) {
            Log.w(TAG, "Can't find \"" + packageName + "\"");
            return false;
        }
    }

    public NetworkInfo getNetworkInfo() {
        return mNetworkInfo;
    }

    /**
     * Return Network of current running VPN network.
     *
     * @return a Network if there is a running VPN network or null if there is no running VPN
     *         network or network is null.
     */
    @VisibleForTesting
    @Nullable
    public synchronized Network getNetwork() {
        final NetworkAgent agent = mNetworkAgent;
        if (null == agent) return null;
        final Network network = agent.getNetwork();
        if (null == network) return null;
        return network;
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

                if (route.getType() == RouteInfo.RTN_UNICAST) {
                    allowIPv4 |= address instanceof Inet4Address;
                    allowIPv6 |= address instanceof Inet6Address;
                }
            }
        }

        if (mConfig.dnsServers != null) {
            for (String dnsServer : mConfig.dnsServers) {
                InetAddress address = InetAddresses.parseNumericAddress(dnsServer);
                lp.addDnsServer(address);
                allowIPv4 |= address instanceof Inet4Address;
                allowIPv6 |= address instanceof Inet6Address;
            }
        }

        lp.setHttpProxy(mConfig.proxyInfo);

        if (!allowIPv4) {
            lp.addRoute(new RouteInfo(new IpPrefix(
                    NetworkStackConstants.IPV4_ADDR_ANY, 0), null /*gateway*/,
                    null /*iface*/, RTN_UNREACHABLE));
        }
        if (!allowIPv6) {
            lp.addRoute(new RouteInfo(new IpPrefix(
                    NetworkStackConstants.IPV6_ADDR_ANY, 0), null /*gateway*/,
                    null /*iface*/, RTN_UNREACHABLE));
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
            Log.i(TAG, "Handover not possible due to changes to allowed/denied apps");
            return false;
        }

        agent.sendLinkProperties(makeLinkProperties());
        return true;
    }

    @GuardedBy("this")
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
        final NetworkCapabilities.Builder capsBuilder =
                new NetworkCapabilities.Builder(mNetworkCapabilities);
        capsBuilder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

        mLegacyState = LegacyVpnInfo.STATE_CONNECTING;
        updateState(DetailedState.CONNECTING, "agentConnect");

        final NetworkAgentConfig networkAgentConfig = new NetworkAgentConfig.Builder()
                .setLegacyType(ConnectivityManager.TYPE_VPN)
                .setLegacyTypeName("VPN")
                .setBypassableVpn(mConfig.allowBypass && !mLockdown)
                .setVpnRequiresValidation(mConfig.requiresInternetValidation)
                .setLocalRoutesExcludedForVpn(mConfig.excludeLocalRoutes)
                .build();

        capsBuilder.setOwnerUid(mOwnerUID);
        capsBuilder.setAdministratorUids(new int[] {mOwnerUID});
        capsBuilder.setUids(createUserAndRestrictedProfilesRanges(mUserId,
                mConfig.allowedApplications, mConfig.disallowedApplications));

        capsBuilder.setTransportInfo(new VpnTransportInfo(getActiveVpnType(), mConfig.session));

        // Only apps targeting Q and above can explicitly declare themselves as metered.
        // These VPNs are assumed metered unless they state otherwise.
        if (mIsPackageTargetingAtLeastQ && mConfig.isMetered) {
            capsBuilder.removeCapability(NET_CAPABILITY_NOT_METERED);
        } else {
            capsBuilder.addCapability(NET_CAPABILITY_NOT_METERED);
        }

        capsBuilder.setUnderlyingNetworks((mConfig.underlyingNetworks != null)
                ? Arrays.asList(mConfig.underlyingNetworks) : null);

        mNetworkCapabilities = capsBuilder.build();
        mNetworkAgent = new NetworkAgent(mContext, mLooper, NETWORKTYPE /* logtag */,
                mNetworkCapabilities, lp,
                new NetworkScore.Builder().setLegacyInt(VPN_DEFAULT_SCORE).build(),
                networkAgentConfig, mNetworkProvider) {
            @Override
            public void onNetworkUnwanted() {
                // We are user controlled, not driven by NetworkRequest.
            }
        };
        final long token = Binder.clearCallingIdentity();
        try {
            mNetworkAgent.register();
        } catch (final Exception e) {
            // If register() throws, don't keep an unregistered agent.
            mNetworkAgent = null;
            throw e;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        updateState(DetailedState.CONNECTED, "agentConnect");
    }

    private boolean canHaveRestrictedProfile(int userId) {
        final long token = Binder.clearCallingIdentity();
        try {
            final Context userContext = mContext.createContextAsUser(UserHandle.of(userId), 0);
            return userContext.getSystemService(UserManager.class).canHaveRestrictedProfile();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void agentDisconnect(NetworkAgent networkAgent) {
        if (networkAgent != null) {
            networkAgent.unregister();
        }
    }

    private void agentDisconnect() {
        updateState(DetailedState.DISCONNECTED, "agentDisconnect");
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
        final long token = Binder.clearCallingIdentity();
        try {
            // Restricted users are not allowed to create VPNs, they are tied to Owner
            enforceNotRestrictedUser();

            final PackageManager packageManager = mUserIdContext.getPackageManager();
            if (packageManager == null) {
                throw new IllegalStateException("Cannot get PackageManager.");
            }
            final ResolveInfo info = packageManager.resolveService(intent, 0 /* flags */);
            if (info == null) {
                throw new SecurityException("Cannot find " + config.user);
            }
            if (!BIND_VPN_SERVICE.equals(info.serviceInfo.permission)) {
                throw new SecurityException(config.user + " does not require " + BIND_VPN_SERVICE);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Save the old config in case we need to go back.
        VpnConfig oldConfig = mConfig;
        String oldInterface = mInterface;
        Connection oldConnection = mConnection;
        NetworkAgent oldNetworkAgent = mNetworkAgent;
        Set<Range<Integer>> oldUsers = mNetworkCapabilities.getUids();

        // Configure the interface. Abort if any of these steps fails.
        final ParcelFileDescriptor tun = mDeps.adoptFd(this, config.mtu);
        try {
            final String interfaze = mDeps.jniGetName(this, tun.getFd());

            // TEMP use the old jni calls until there is support for netd address setting
            StringBuilder builder = new StringBuilder();
            for (LinkAddress address : config.addresses) {
                builder.append(" ");
                builder.append(address);
            }
            if (mDeps.jniSetAddresses(this, interfaze, builder.toString()) < 1) {
                throw new IllegalArgumentException("At least one address must be specified");
            }
            Connection connection = new Connection();
            if (!mContext.bindServiceAsUser(intent, connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                    new UserHandle(mUserId))) {
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
                // Update underlying networks if it is changed.
                if (!Arrays.equals(oldConfig.underlyingNetworks, config.underlyingNetworks)) {
                    setUnderlyingNetworks(config.underlyingNetworks);
                }
            } else {
                // Initialize the state for a new agent, while keeping the old one connected
                // in case this new connection fails.
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

            mDeps.setBlocking(tun.getFileDescriptor(), config.blocking);
            // Record that the VPN connection is established by an app which uses VpnService API.
            if (oldNetworkAgent != mNetworkAgent) {
                mAppOpsManager.startOp(
                        AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE, mOwnerUID, mPackage, null, null);
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
            mNetworkCapabilities =
                    new NetworkCapabilities.Builder(mNetworkCapabilities).setUids(oldUsers).build();
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
    // This method also adds the SDK sandbox UIDs corresponding to the applications by default,
    // since apps are generally not aware of them, yet they should follow the VPN configuration
    // of the app they belong to.
    private SortedSet<Integer> getAppsUids(List<String> packageNames, int userId) {
        SortedSet<Integer> uids = new TreeSet<>();
        for (String app : packageNames) {
            int uid = getAppUid(app, userId);
            if (uid != -1) uids.add(uid);
            // TODO(b/230548427): Remove SDK check once VPN related stuff are decoupled from
            // ConnectivityServiceTest.
            if (Process.isApplicationUid(uid) && SdkLevel.isAtLeastT()) {
                uids.add(Process.toSdkSandboxUid(uid));
            }
        }
        return uids;
    }

    /**
     * Creates a {@link Set} of non-intersecting {@code Range<Integer>} objects including all UIDs
     * associated with one user, and any restricted profiles attached to that user.
     *
     * <p>If one of {@param allowedApplications} or {@param disallowedApplications} is provided,
     * the UID ranges will match the app list specified there. Otherwise, all UIDs
     * in each user and profile will be included.
     *
     * @param userId The userId to create UID ranges for along with any of its restricted
     *                   profiles.
     * @param allowedApplications (optional) List of applications to allow.
     * @param disallowedApplications (optional) List of applications to deny.
     */
    @VisibleForTesting
    Set<Range<Integer>> createUserAndRestrictedProfilesRanges(@UserIdInt int userId,
            @Nullable List<String> allowedApplications,
            @Nullable List<String> disallowedApplications) {
        final Set<Range<Integer>> ranges = new ArraySet<>();

        // Assign the top-level user to the set of ranges
        addUserToRanges(ranges, userId, allowedApplications, disallowedApplications);

        // If the user can have restricted profiles, assign all its restricted profiles too
        if (canHaveRestrictedProfile(userId)) {
            final long token = Binder.clearCallingIdentity();
            List<UserInfo> users;
            try {
                users = mUserManager.getAliveUsers();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            for (UserInfo user : users) {
                if (user.isRestricted() && (user.restrictedProfileParentId == userId)) {
                    addUserToRanges(ranges, user.id, allowedApplications, disallowedApplications);
                }
            }
        }
        return ranges;
    }

    /**
     * Updates a {@link Set} of non-intersecting {@code Range<Integer>} objects to include all UIDs
     * associated with one user.
     *
     * <p>If one of {@param allowedApplications} or {@param disallowedApplications} is provided,
     * the UID ranges will match the app allowlist or denylist specified there. Otherwise, all UIDs
     * in the user will be included.
     *
     * @param ranges {@link Set} of {@code Range<Integer>}s to which to add.
     * @param userId The userId to add to {@param ranges}.
     * @param allowedApplications (optional) allowlist of applications to include.
     * @param disallowedApplications (optional) denylist of applications to exclude.
     */
    @VisibleForTesting
    void addUserToRanges(@NonNull Set<Range<Integer>> ranges, @UserIdInt int userId,
            @Nullable List<String> allowedApplications,
            @Nullable List<String> disallowedApplications) {
        if (allowedApplications != null) {
            // Add ranges covering all UIDs for allowedApplications.
            int start = -1, stop = -1;
            for (int uid : getAppsUids(allowedApplications, userId)) {
                if (start == -1) {
                    start = uid;
                } else if (uid != stop + 1) {
                    ranges.add(new Range<Integer>(start, stop));
                    start = uid;
                }
                stop = uid;
            }
            if (start != -1) ranges.add(new Range<Integer>(start, stop));
        } else if (disallowedApplications != null) {
            // Add all ranges for user skipping UIDs for disallowedApplications.
            final Range<Integer> userRange = createUidRangeForUser(userId);
            int start = userRange.getLower();
            for (int uid : getAppsUids(disallowedApplications, userId)) {
                if (uid == start) {
                    start++;
                } else {
                    ranges.add(new Range<Integer>(start, uid - 1));
                    start = uid + 1;
                }
            }
            if (start <= userRange.getUpper()) {
                ranges.add(new Range<Integer>(start, userRange.getUpper()));
            }
        } else {
            // Add all UIDs for the user.
            ranges.add(createUidRangeForUser(userId));
        }
    }

    // Returns the subset of the full list of active UID ranges the VPN applies to (mVpnUsers) that
    // apply to userId.
    private static List<Range<Integer>> uidRangesForUser(int userId,
            Set<Range<Integer>> existingRanges) {
        final Range<Integer> userRange = createUidRangeForUser(userId);
        final List<Range<Integer>> ranges = new ArrayList<>();
        for (Range<Integer> range : existingRanges) {
            if (userRange.contains(range)) {
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
    public void onUserAdded(int userId) {
        // If the user is restricted tie them to the parent user's VPN
        UserInfo user = mUserManager.getUserInfo(userId);
        if (user.isRestricted() && user.restrictedProfileParentId == mUserId) {
            synchronized(Vpn.this) {
                final Set<Range<Integer>> existingRanges = mNetworkCapabilities.getUids();
                if (existingRanges != null) {
                    try {
                        addUserToRanges(existingRanges, userId, mConfig.allowedApplications,
                                mConfig.disallowedApplications);
                        mNetworkCapabilities = new NetworkCapabilities.Builder(mNetworkCapabilities)
                                .setUids(existingRanges).build();
                    } catch (Exception e) {
                        Log.wtf(TAG, "Failed to add restricted user to owner", e);
                    }
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
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
    public void onUserRemoved(int userId) {
        // clean up if restricted
        UserInfo user = mUserManager.getUserInfo(userId);
        if (user.isRestricted() && user.restrictedProfileParentId == mUserId) {
            synchronized(Vpn.this) {
                final Set<Range<Integer>> existingRanges = mNetworkCapabilities.getUids();
                if (existingRanges != null) {
                    try {
                        final List<Range<Integer>> removedRanges =
                                uidRangesForUser(userId, existingRanges);
                        existingRanges.removeAll(removedRanges);
                        mNetworkCapabilities = new NetworkCapabilities.Builder(mNetworkCapabilities)
                                .setUids(existingRanges).build();
                    } catch (Exception e) {
                        Log.wtf(TAG, "Failed to remove restricted user to owner", e);
                    }
                    if (mNetworkAgent != null) {
                        mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
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
        setVpnForcedLocked(false);
        mAlwaysOn = false;

        // Quit any active connections
        agentDisconnect();

        // The provider has been registered in the constructor, which is called in onUserStart.
        mConnectivityManager.unregisterNetworkProvider(mNetworkProvider);
    }

    /**
     * Restricts network access from all UIDs affected by this {@link Vpn}, apart from the VPN
     * service app itself and allowed packages, to only sockets that have had {@code protect()}
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
     * @see #mBlockedUidsAsToldToConnectivity
     */
    @GuardedBy("this")
    private void setVpnForcedLocked(boolean enforce) {
        final List<String> exemptedPackages;
        if (isNullOrLegacyVpn(mPackage)) {
            exemptedPackages = null;
        } else {
            exemptedPackages = new ArrayList<>(mLockdownAllowlist);
            exemptedPackages.add(mPackage);
        }
        final Set<UidRangeParcel> rangesToRemove = new ArraySet<>(mBlockedUidsAsToldToConnectivity);
        final Set<UidRangeParcel> rangesToAdd;
        if (enforce) {
            final Set<Range<Integer>> restrictedProfilesRanges =
                    createUserAndRestrictedProfilesRanges(mUserId,
                    /* allowedApplications */ null,
                    /* disallowedApplications */ exemptedPackages);
            final Set<UidRangeParcel> rangesThatShouldBeBlocked = new ArraySet<>();

            // The UID range of the first user (0-99999) would block the IPSec traffic, which comes
            // directly from the kernel and is marked as uid=0. So we adjust the range to allow
            // it through (b/69873852).
            for (Range<Integer> range : restrictedProfilesRanges) {
                if (range.getLower() == 0 && range.getUpper() != 0) {
                    rangesThatShouldBeBlocked.add(new UidRangeParcel(1, range.getUpper()));
                } else if (range.getLower() != 0) {
                    rangesThatShouldBeBlocked.add(
                            new UidRangeParcel(range.getLower(), range.getUpper()));
                }
            }

            rangesToRemove.removeAll(rangesThatShouldBeBlocked);
            rangesToAdd = rangesThatShouldBeBlocked;
            // The ranges to tell ConnectivityService to add are the ones that should be blocked
            // minus the ones it already knows to block. Note that this will change the contents of
            // rangesThatShouldBeBlocked, but the list of ranges that should be blocked is
            // not used after this so it's fine to destroy it.
            rangesToAdd.removeAll(mBlockedUidsAsToldToConnectivity);
        } else {
            rangesToAdd = Collections.emptySet();
        }

        // If mBlockedUidsAsToldToNetd used to be empty, this will always be a no-op.
        setAllowOnlyVpnForUids(false, rangesToRemove);
        // If nothing should be blocked now, this will now be a no-op.
        setAllowOnlyVpnForUids(true, rangesToAdd);
    }

    /**
     * Tell ConnectivityService to add or remove a list of {@link UidRangeParcel}s to the list of
     * UIDs that are only allowed to make connections through sockets that have had
     * {@code protect()} called on them.
     *
     * @param enforce {@code true} to add to the denylist, {@code false} to remove.
     * @param ranges {@link Collection} of {@link UidRangeParcel}s to add (if {@param enforce} is
     *               {@code true}) or to remove.
     * @return {@code true} if all of the UIDs were added/removed. {@code false} otherwise,
     *         including added ranges that already existed or removed ones that didn't.
     */
    @GuardedBy("this")
    private boolean setAllowOnlyVpnForUids(boolean enforce, Collection<UidRangeParcel> ranges) {
        if (ranges.size() == 0) {
            return true;
        }
        // Convert to Collection<Range> which is what the ConnectivityManager API takes.
        ArrayList<Range<Integer>> integerRanges = new ArrayList<>(ranges.size());
        for (UidRangeParcel uidRange : ranges) {
            integerRanges.add(new Range<>(uidRange.start, uidRange.stop));
        }
        try {
            mConnectivityManager.setRequireVpnForUids(enforce, integerRanges);
        } catch (RuntimeException e) {
            Log.e(TAG, "Updating blocked=" + enforce
                    + " for UIDs " + Arrays.toString(ranges.toArray()) + " failed", e);
            return false;
        }
        if (enforce) {
            mBlockedUidsAsToldToConnectivity.addAll(ranges);
        } else {
            mBlockedUidsAsToldToConnectivity.removeAll(ranges);
        }
        return true;
    }

    /**
     * Return the configuration of the currently running VPN.
     */
    public synchronized VpnConfig getVpnConfig() {
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
                        mAppOpsManager.finishOp(
                                AppOpsManager.OPSTR_ESTABLISH_VPN_SERVICE, mOwnerUID, mPackage,
                                null);
                        mContext.unbindService(mConnection);
                        cleanupVpnStateLocked();
                    } else if (mVpnRunner != null) {
                        if (!VpnConfig.LEGACY_VPN.equals(mPackage)) {
                            mAppOpsManager.finishOp(
                                    AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER, mOwnerUID, mPackage,
                                    null);
                        }
                        // cleanupVpnStateLocked() is called from mVpnRunner.exit()
                        mVpnRunner.exit();
                    }
                }
            }
        }
    };

    private void cleanupVpnStateLocked() {
        mStatusIntent = null;
        resetNetworkCapabilities();
        mConfig = null;
        mInterface = null;

        // Unconditionally clear both VpnService and VpnRunner fields.
        mVpnRunner = null;
        mConnection = null;
        agentDisconnect();
    }

    private void enforceControlPermission() {
        mContext.enforceCallingPermission(CONTROL_VPN, "Unauthorized Caller");
    }

    private void enforceControlPermissionOrInternalCaller() {
        // Require the caller to be either an application with CONTROL_VPN permission or a process
        // in the system server.
        mContext.enforceCallingOrSelfPermission(CONTROL_VPN, "Unauthorized Caller");
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
            mStatusIntent = mDeps.getIntentForStatusPanel(mContext);
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
     */
    public synchronized boolean setUnderlyingNetworks(@Nullable Network[] networks) {
        if (!isCallerEstablishedOwnerLocked()) {
            return false;
        }
        // Make defensive copy since the content of array might be altered by the caller.
        mConfig.underlyingNetworks =
                (networks != null) ? Arrays.copyOf(networks, networks.length) : null;
        mNetworkAgent.setUnderlyingNetworks((mConfig.underlyingNetworks != null)
                ? Arrays.asList(mConfig.underlyingNetworks) : null);
        return true;
    }

    /**
     * This method should not be called if underlying interfaces field is needed, because it doesn't
     * have enough data to fill VpnInfo.underlyingIfaces field.
     */
    public synchronized UnderlyingNetworkInfo getUnderlyingNetworkInfo() {
        if (!isRunningLocked()) {
            return null;
        }

        return new UnderlyingNetworkInfo(mOwnerUID, mInterface, new ArrayList<>());
    }

    public synchronized boolean appliesToUid(int uid) {
        if (!isRunningLocked()) {
            return false;
        }
        final Set<Range<Integer>> uids = mNetworkCapabilities.getUids();
        if (uids == null) return true;
        for (final Range<Integer> range : uids) {
            if (range.contains(uid)) return true;
        }
        return false;
    }

    /**
     * Gets the currently running VPN type
     *
     * @return the {@link VpnManager.VpnType}. {@link VpnManager.TYPE_VPN_NONE} if not running a
     *     VPN. While VpnService-based VPNs are always app VPNs and LegacyVpn is always
     *     Settings-based, the Platform VPNs can be initiated by both apps and Settings.
     */
    public synchronized int getActiveVpnType() {
        if (!mNetworkInfo.isConnectedOrConnecting()) return VpnManager.TYPE_VPN_NONE;
        if (mVpnRunner == null) return VpnManager.TYPE_VPN_SERVICE;
        return isIkev2VpnRunner() ? VpnManager.TYPE_VPN_PLATFORM : VpnManager.TYPE_VPN_LEGACY;
    }

    @GuardedBy("this")
    private void updateAlwaysOnNotification(DetailedState networkState) {
        final boolean visible = (mAlwaysOn && networkState != DetailedState.CONNECTED);

        final UserHandle user = UserHandle.of(mUserId);
        final long token = Binder.clearCallingIdentity();
        try {
            final NotificationManager notificationManager =
                    mUserIdContext.getSystemService(NotificationManager.class);
            if (!visible) {
                notificationManager.cancel(TAG, SystemMessage.NOTE_VPN_DISCONNECTED);
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
                    new Notification.Builder(mContext, NOTIFICATION_CHANNEL_VPN)
                            .setSmallIcon(R.drawable.vpn_connected)
                            .setContentTitle(mContext.getString(R.string.vpn_lockdown_disconnected))
                            .setContentText(mContext.getString(R.string.vpn_lockdown_config))
                            .setContentIntent(configIntent)
                            .setCategory(Notification.CATEGORY_SYSTEM)
                            .setVisibility(Notification.VISIBILITY_PUBLIC)
                            .setOngoing(true)
                            .setColor(mContext.getColor(
                                    android.R.color.system_notification_accent_color));
            notificationManager.notify(TAG, SystemMessage.NOTE_VPN_DISCONNECTED, builder.build());
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
            return PendingIntent.getActivity(
                    mContext.createContextAsUser(user, 0 /* flags */), 0 /* requestCode */,
                    intent, flags);
        }

        /**
         * @see Settings.Secure#putStringForUser
         */
        public void settingsSecurePutStringForUser(String key, String value, int userId) {
            Settings.Secure.putString(getContentResolverAsUser(userId), key, value);
        }

        /**
         * @see Settings.Secure#putIntForUser
         */
        public void settingsSecurePutIntForUser(String key, int value, int userId) {
            Settings.Secure.putInt(getContentResolverAsUser(userId), key, value);
        }

        /**
         * @see Settings.Secure#getStringForUser
         */
        public String settingsSecureGetStringForUser(String key, int userId) {
            return Settings.Secure.getString(getContentResolverAsUser(userId), key);
        }

        /**
         * @see Settings.Secure#getIntForUser
         */
        public int settingsSecureGetIntForUser(String key, int def, int userId) {
            return Settings.Secure.getInt(getContentResolverAsUser(userId), key, def);
        }

        private ContentResolver getContentResolverAsUser(int userId) {
            return mContext.createContextAsUser(
                    UserHandle.of(userId), 0 /* flags */).getContentResolver();
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
        final long token = Binder.clearCallingIdentity();
        try {
            final UserInfo user = mUserManager.getUserInfo(mUserId);

            if (user.isRestricted()) {
                throw new SecurityException("Restricted users cannot configure VPNs");
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Start legacy VPN, controlling native daemons as needed. Creates a
     * secondary thread to perform connection work, returning quickly.
     *
     * Should only be called to respond to Binder requests as this enforces caller permission. Use
     * {@link #startLegacyVpnPrivileged(VpnProfile, Network, LinkProperties)} to skip the
     * permission check only when the caller is trusted (or the call is initiated by the system).
     */
    public void startLegacyVpn(VpnProfile profile, @Nullable Network underlying,
            LinkProperties egress) {
        enforceControlPermission();
        final long token = Binder.clearCallingIdentity();
        try {
            startLegacyVpnPrivileged(profile, underlying, egress);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private String makeKeystoreEngineGrantString(String alias) {
        if (alias == null) {
            return null;
        }
        final KeyStore2 keystore2 = KeyStore2.getInstance();

        KeyDescriptor key = new KeyDescriptor();
        key.domain = Domain.APP;
        key.nspace = KeyProperties.NAMESPACE_APPLICATION;
        key.alias = alias;
        key.blob = null;

        final int grantAccessVector = KeyPermission.USE | KeyPermission.GET_INFO;

        try {
            // The native vpn daemon is running as VPN_UID. This tells Keystore 2.0
            // to allow a process running with this UID to access the key designated by
            // the KeyDescriptor `key`. `grant` returns a new KeyDescriptor with a grant
            // identifier. This identifier needs to be communicated to the vpn daemon.
            key = keystore2.grant(key, android.os.Process.VPN_UID, grantAccessVector);
        } catch (android.security.KeyStoreException e) {
            Log.e(TAG, "Failed to get grant for keystore key.", e);
            throw new IllegalStateException("Failed to get grant for keystore key.", e);
        }

        // Turn the grant identifier into a string as understood by the keystore boringssl engine
        // in system/security/keystore-engine.
        return KeyStore2.makeKeystoreEngineGrantString(key.nspace);
    }

    private String getCaCertificateFromKeystoreAsPem(@NonNull KeyStore keystore,
            @NonNull String alias)
            throws KeyStoreException, IOException, CertificateEncodingException {
        if (keystore.isCertificateEntry(alias)) {
            final Certificate cert = keystore.getCertificate(alias);
            if (cert == null) return null;
            return new String(Credentials.convertToPem(cert), StandardCharsets.UTF_8);
        } else {
            final Certificate[] certs = keystore.getCertificateChain(alias);
            // If there is none or one entry it means there is no CA entry associated with this
            // alias.
            if (certs == null || certs.length <= 1) {
                return null;
            }
            // If this is not a (pure) certificate entry, then there is a user certificate which
            // will be included at the beginning of the certificate chain. But the caller of this
            // function does not expect this certificate to be included, so we cut it off.
            return new String(Credentials.convertToPem(
                    Arrays.copyOfRange(certs, 1, certs.length)), StandardCharsets.UTF_8);
        }
    }

    /**
     * Like {@link #startLegacyVpn(VpnProfile, Network, LinkProperties)}, but does not
     * check permissions under the assumption that the caller is the system.
     *
     * Callers are responsible for checking permissions if needed.
     */
    public void startLegacyVpnPrivileged(VpnProfile profile,
            @Nullable Network underlying, @NonNull LinkProperties egress) {
        UserInfo user = mUserManager.getUserInfo(mUserId);
        if (user.isRestricted() || mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN,
                    new UserHandle(mUserId))) {
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

        try {
            final KeyStore keystore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER);
            keystore.load(null);
            if (!profile.ipsecUserCert.isEmpty()) {
                privateKey = profile.ipsecUserCert;
                final Certificate cert = keystore.getCertificate(profile.ipsecUserCert);
                userCert = (cert == null) ? null
                         : new String(Credentials.convertToPem(cert), StandardCharsets.UTF_8);
            }
            if (!profile.ipsecCaCert.isEmpty()) {
                caCert = getCaCertificateFromKeystoreAsPem(keystore, profile.ipsecCaCert);
            }
            if (!profile.ipsecServerCert.isEmpty()) {
                final Certificate cert = keystore.getCertificate(profile.ipsecServerCert);
                serverCert = (cert == null) ? null
                        : new String(Credentials.convertToPem(cert), StandardCharsets.UTF_8);
            }
        } catch (CertificateException | KeyStoreException | IOException
                | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to load credentials from AndroidKeyStore", e);
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
                profile.setAllowedAlgorithms(Ikev2VpnProfile.DEFAULT_ALGORITHMS);
                startVpnProfilePrivileged(profile, VpnConfig.LEGACY_VPN);
                return;
            case VpnProfile.TYPE_IKEV2_IPSEC_PSK:
                // Ikev2VpnProfiles expect a base64-encoded preshared key.
                profile.ipsecSecret =
                        Ikev2VpnProfile.encodeForIpsecSecret(profile.ipsecSecret.getBytes());

                // Start VPN profile
                profile.setAllowedAlgorithms(Ikev2VpnProfile.DEFAULT_ALGORITHMS);
                startVpnProfilePrivileged(profile, VpnConfig.LEGACY_VPN);
                return;
            case VpnProfile.TYPE_IKEV2_FROM_IKE_TUN_CONN_PARAMS:
                // All the necessary IKE options should come from IkeTunnelConnectionParams in the
                // profile.
                startVpnProfilePrivileged(profile, VpnConfig.LEGACY_VPN);
                return;
            case VpnProfile.TYPE_L2TP_IPSEC_PSK:
                racoon = new String[] {
                    iface, profile.server, "udppsk", profile.ipsecIdentifier,
                    profile.ipsecSecret, "1701",
                };
                break;
            case VpnProfile.TYPE_L2TP_IPSEC_RSA:
                racoon = new String[] {
                    iface, profile.server, "udprsa", makeKeystoreEngineGrantString(privateKey),
                    userCert, caCert, serverCert, "1701",
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
                    iface, profile.server, "xauthrsa", makeKeystoreEngineGrantString(privateKey),
                    userCert, caCert, serverCert, profile.username, profile.password, "", gateway,
                };
                break;
            case VpnProfile.TYPE_IPSEC_HYBRID_RSA:
                racoon = new String[] {
                    iface, profile.server, "hybridrsa",
                    caCert, serverCert, profile.username, profile.password, "", gateway,
                };
                break;
        }

        // Prepare arguments for mtpd. MTU/MRU calculated conservatively. Only IPv4 supported
        // because LegacyVpn.
        // 1500 - 60 (Carrier-internal IPv6 + UDP + GTP) - 10 (PPP) - 16 (L2TP) - 8 (UDP)
        //   - 77 (IPsec w/ SHA-2 512, 256b trunc-len, AES-CBC) - 8 (UDP encap) - 20 (IPv4)
        //   - 28 (464xlat)
        String[] mtpd = null;
        switch (profile.type) {
            case VpnProfile.TYPE_PPTP:
                mtpd = new String[] {
                    iface, "pptp", profile.server, "1723",
                    "name", profile.username, "password", profile.password,
                    "linkname", "vpn", "refuse-eap", "nodefaultroute",
                    "usepeerdns", "idle", "1800", "mtu", "1270", "mru", "1270",
                    (profile.mppe ? "+mppe" : "nomppe"),
                };
                if (profile.mppe) {
                    // Disallow PAP authentication when MPPE is requested, as MPPE cannot work
                    // with PAP anyway, and users may not expect PAP (plain text) to be used when
                    // MPPE was requested.
                    mtpd = Arrays.copyOf(mtpd, mtpd.length + 1);
                    mtpd[mtpd.length - 1] = "-pap";
                }
                break;
            case VpnProfile.TYPE_L2TP_IPSEC_PSK:
            case VpnProfile.TYPE_L2TP_IPSEC_RSA:
                mtpd = new String[] {
                    iface, "l2tp", profile.server, "1701", profile.l2tpSecret,
                    "name", profile.username, "password", profile.password,
                    "linkname", "vpn", "refuse-eap", "nodefaultroute",
                    "usepeerdns", "idle", "1800", "mtu", "1270", "mru", "1270",
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
        if (underlying != null) {
            config.underlyingNetworks = new Network[] { underlying };
        }

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
        startLegacyVpnRunner();
    }

    @VisibleForTesting
    protected void startLegacyVpnRunner() {
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
        info.state = mLegacyState;
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

    @Nullable
    protected synchronized NetworkCapabilities getRedactedNetworkCapabilitiesOfUnderlyingNetwork(
            NetworkCapabilities nc) {
        if (nc == null) return null;
        return mConnectivityManager.getRedactedNetworkCapabilitiesForPackage(
                nc, mOwnerUID, mPackage);
    }

    @Nullable
    protected synchronized LinkProperties getRedactedLinkPropertiesOfUnderlyingNetwork(
            LinkProperties lp) {
        if (lp == null) return null;
        return mConnectivityManager.getRedactedLinkPropertiesForPackage(lp, mOwnerUID, mPackage);
    }

    /** This class represents the common interface for all VPN runners. */
    @VisibleForTesting
    abstract class VpnRunner extends Thread {

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

        void onDefaultNetworkCapabilitiesChanged(@NonNull NetworkCapabilities nc);

        void onDefaultNetworkLinkPropertiesChanged(@NonNull LinkProperties lp);

        void onDefaultNetworkLost(@NonNull Network network);

        void onIkeOpened(int token, @NonNull IkeSessionConfiguration ikeConfiguration);

        void onIkeConnectionInfoChanged(
                int token, @NonNull IkeSessionConnectionInfo ikeConnectionInfo);

        void onChildOpened(int token, @NonNull ChildSessionConfiguration childConfig);

        void onChildTransformCreated(int token, @NonNull IpSecTransform transform, int direction);

        void onChildMigrated(
                int token,
                @NonNull IpSecTransform inTransform,
                @NonNull IpSecTransform outTransform);

        void onSessionLost(int token, @Nullable Exception exception);
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

        // 5 seconds grace period before tearing down the IKE Session in case new default network
        // will come up
        private static final long NETWORK_LOST_TIMEOUT_MS = 5000L;

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
        @NonNull
        private final ScheduledThreadPoolExecutor mExecutor = new ScheduledThreadPoolExecutor(1);

        @Nullable private ScheduledFuture<?> mScheduledHandleNetworkLostTimeout;

        /** Signal to ensure shutdown is honored even if a new Network is connected. */
        private boolean mIsRunning = true;

        /**
         * The token used by the primary/current/active IKE session.
         *
         * <p>This token MUST be updated when the VPN switches to use a new IKE session.
         */
        private int mCurrentToken = -1;

        @Nullable private IpSecTunnelInterface mTunnelIface;
        @Nullable private Network mActiveNetwork;
        @Nullable private NetworkCapabilities mUnderlyingNetworkCapabilities;
        @Nullable private LinkProperties mUnderlyingLinkProperties;
        private final String mSessionKey;

        @Nullable private IkeSession mSession;
        @Nullable private IkeSessionConnectionInfo mIkeConnectionInfo;

        // mMobikeEnabled can only be updated after IKE AUTH is finished.
        private boolean mMobikeEnabled = false;

        IkeV2VpnRunner(@NonNull Ikev2VpnProfile profile) {
            super(TAG);
            mProfile = profile;
            mIpSecManager = (IpSecManager) mContext.getSystemService(Context.IPSEC_SERVICE);
            mNetworkCallback = new VpnIkev2Utils.Ikev2VpnNetworkCallback(TAG, this, mExecutor);
            mSessionKey = UUID.randomUUID().toString();

            // Set the policy so that cancelled tasks will be removed from the work queue
            mExecutor.setRemoveOnCancelPolicy(true);

            // Set the policy so that all delayed tasks will not be executed
            mExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);

            // To avoid hitting RejectedExecutionException upon shutdown of the mExecutor */
            mExecutor.setRejectedExecutionHandler(
                    (r, executor) -> {
                        Log.d(TAG, "Runnable " + r + " rejected by the mExecutor");
                    });
        }

        @Override
        public void run() {
            // Unless the profile is restricted to test networks, explicitly use only the network
            // that ConnectivityService thinks is the "best." In other words, only ever use the
            // currently selected default network. This does mean that in both onLost() and
            // onConnected(), any old sessions MUST be torn down. This does NOT include VPNs.
            //
            // When restricted to test networks, select any network with TRANSPORT_TEST. Since the
            // creator of the profile and the test network creator both have MANAGE_TEST_NETWORKS,
            // this is considered safe.
            final NetworkRequest req;

            if (mProfile.isRestrictedToTestNetworks()) {
                req = new NetworkRequest.Builder()
                        .clearCapabilities()
                        .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                        .addCapability(NET_CAPABILITY_NOT_VPN)
                        .build();
            } else {
                // Basically, the request here is referring to the default request which is defined
                // in ConnectivityService. Ideally, ConnectivityManager should provide an new API
                // which can provide the status of physical network even though there is a virtual
                // network. b/147280869 is used for tracking the new API.
                // TODO: Use the new API to register default physical network.
                req = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
            }

            mConnectivityManager.requestNetwork(req, mNetworkCallback);
        }

        private boolean isActiveNetwork(@Nullable Network network) {
            return Objects.equals(mActiveNetwork, network) && mIsRunning;
        }

        private boolean isActiveToken(int token) {
            return (mCurrentToken == token) && mIsRunning;
        }

        /**
         * Called when an IKE session has been opened
         *
         * <p>This method is only ever called once per IkeSession, and MUST run on the mExecutor
         * thread in order to ensure consistency of the Ikev2VpnRunner fields.
         */
        public void onIkeOpened(int token, @NonNull IkeSessionConfiguration ikeConfiguration) {
            if (!isActiveToken(token)) {
                Log.d(TAG, "onIkeOpened called for obsolete token " + token);
                return;
            }

            mMobikeEnabled =
                    ikeConfiguration.isIkeExtensionEnabled(
                            IkeSessionConfiguration.EXTENSION_TYPE_MOBIKE);
            onIkeConnectionInfoChanged(token, ikeConfiguration.getIkeSessionConnectionInfo());
        }

        /**
         * Called when an IKE session's {@link IkeSessionConnectionInfo} is available or updated
         *
         * <p>This callback is usually fired when an IKE session has been opened or migrated.
         *
         * <p>This method is called multiple times over the lifetime of an IkeSession, and MUST run
         * on the mExecutor thread in order to ensure consistency of the Ikev2VpnRunner fields.
         */
        public void onIkeConnectionInfoChanged(
                int token, @NonNull IkeSessionConnectionInfo ikeConnectionInfo) {
            if (!isActiveToken(token)) {
                Log.d(TAG, "onIkeConnectionInfoChanged called for obsolete token " + token);
                return;
            }

            // The update on VPN and the IPsec tunnel will be done when migration is fully complete
            // in onChildMigrated
            mIkeConnectionInfo = ikeConnectionInfo;
        }

        /**
         * Called when an IKE Child session has been opened, signalling completion of the startup.
         *
         * <p>This method is only ever called once per IkeSession, and MUST run on the mExecutor
         * thread in order to ensure consistency of the Ikev2VpnRunner fields.
         */
        public void onChildOpened(int token, @NonNull ChildSessionConfiguration childConfig) {
            if (!isActiveToken(token)) {
                Log.d(TAG, "onChildOpened called for obsolete token " + token);

                // Do nothing; this signals that either: (1) a new/better Network was found,
                // and the Ikev2VpnRunner has switched to it by restarting a new IKE session in
                // onDefaultNetworkChanged, or (2) this IKE session was already shut down (exited,
                // or an error was encountered somewhere else). In both cases, all resources and
                // sessions are torn down via resetIkeState().
                return;
            }

            try {
                final String interfaceName = mTunnelIface.getInterfaceName();
                final int maxMtu = mProfile.getMaxMtu();
                final List<LinkAddress> internalAddresses = childConfig.getInternalAddresses();
                final List<String> dnsAddrStrings = new ArrayList<>();

                final Collection<RouteInfo> newRoutes = VpnIkev2Utils.getRoutesFromTrafficSelectors(
                        childConfig.getOutboundTrafficSelectors());
                for (final LinkAddress address : internalAddresses) {
                    mTunnelIface.addAddress(address.getAddress(), address.getPrefixLength());
                }
                for (InetAddress addr : childConfig.getInternalDnsServers()) {
                    dnsAddrStrings.add(addr.getHostAddress());
                }

                // The actual network of this IKE session has been set up with is
                // mIkeConnectionInfo.getNetwork() instead of mActiveNetwork because
                // mActiveNetwork might have been updated after the setup was triggered.
                final Network network = mIkeConnectionInfo.getNetwork();

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

                    if (mConfig.dnsServers == null) mConfig.dnsServers = new ArrayList<>();
                    mConfig.dnsServers.clear();
                    mConfig.dnsServers.addAll(dnsAddrStrings);

                    mConfig.underlyingNetworks = new Network[] {network};

                    mConfig.disallowedApplications = getAppExclusionList(mPackage);

                    networkAgent = mNetworkAgent;

                    // The below must be done atomically with the mConfig update, otherwise
                    // isRunningLocked() will be racy.
                    if (networkAgent == null) {
                        if (isSettingsVpnLocked()) {
                            prepareStatusIntent();
                        }
                        agentConnect();
                        return; // Link properties are already sent.
                    } else {
                        // Underlying networks also set in agentConnect()
                        networkAgent.setUnderlyingNetworks(Collections.singletonList(network));
                    }

                    lp = makeLinkProperties(); // Accesses VPN instance fields; must be locked
                }

                networkAgent.sendLinkProperties(lp);
            } catch (Exception e) {
                Log.d(TAG, "Error in ChildOpened for token " + token, e);
                onSessionLost(token, e);
            }
        }

        /**
         * Called when an IPsec transform has been created, and should be applied.
         *
         * <p>This method is called multiple times over the lifetime of an IkeSession (or default
         * network), and MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        public void onChildTransformCreated(
                int token, @NonNull IpSecTransform transform, int direction) {
            if (!isActiveToken(token)) {
                Log.d(TAG, "ChildTransformCreated for obsolete token " + token);

                // Do nothing; this signals that either: (1) a new/better Network was found,
                // and the Ikev2VpnRunner has switched to it by restarting a new IKE session in
                // onDefaultNetworkChanged, or (2) this IKE session was already shut down (exited,
                // or an error was encountered somewhere else). In both cases, all resources and
                // sessions are torn down via resetIkeState().
                return;
            }

            try {
                // Transforms do not need to be persisted; the IkeSession will keep
                // them alive for us
                mIpSecManager.applyTunnelModeTransform(mTunnelIface, direction, transform);
            } catch (IOException e) {
                Log.d(TAG, "Transform application failed for token " + token, e);
                onSessionLost(token, e);
            }
        }

        /**
         * Called when an IPsec transform has been created, and should be re-applied.
         *
         * <p>This method is called multiple times over the lifetime of an IkeSession (or default
         * network), and MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        public void onChildMigrated(
                int token,
                @NonNull IpSecTransform inTransform,
                @NonNull IpSecTransform outTransform) {
            if (!isActiveToken(token)) {
                Log.d(TAG, "onChildMigrated for obsolete token " + token);
                return;
            }

            // The actual network of this IKE session has migrated to is
            // mIkeConnectionInfo.getNetwork() instead of mActiveNetwork because mActiveNetwork
            // might have been updated after the migration was triggered.
            final Network network = mIkeConnectionInfo.getNetwork();

            try {
                synchronized (Vpn.this) {
                    mConfig.underlyingNetworks = new Network[] {network};
                    mNetworkCapabilities =
                            new NetworkCapabilities.Builder(mNetworkCapabilities)
                                    .setUnderlyingNetworks(Collections.singletonList(network))
                                    .build();
                    mNetworkAgent.setUnderlyingNetworks(Collections.singletonList(network));
                }

                mTunnelIface.setUnderlyingNetwork(network);

                // Transforms do not need to be persisted; the IkeSession will keep them alive for
                // us
                mIpSecManager.applyTunnelModeTransform(
                        mTunnelIface, IpSecManager.DIRECTION_IN, inTransform);
                mIpSecManager.applyTunnelModeTransform(
                        mTunnelIface, IpSecManager.DIRECTION_OUT, outTransform);
            } catch (IOException e) {
                Log.d(TAG, "Transform application failed for token " + token, e);
                onSessionLost(token, e);
            }
        }

        /**
         * Called when a new default network is connected.
         *
         * <p>The Ikev2VpnRunner will unconditionally switch to the new network. If the IKE session
         * has mobility, Ikev2VpnRunner will migrate the existing IkeSession to the new network.
         * Otherwise, Ikev2VpnRunner will kill the old IKE state, and start a new IkeSession
         * instance.
         *
         * <p>This method MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        public void onDefaultNetworkChanged(@NonNull Network network) {
            Log.d(TAG, "onDefaultNetworkChanged: " + network);

            cancelHandleNetworkLostTimeout();

            try {
                if (!mIsRunning) {
                    Log.d(TAG, "onDefaultNetworkChanged after exit");
                    return; // VPN has been shut down.
                }

                mActiveNetwork = network;

                if (mSession != null && mMobikeEnabled) {
                    // IKE session can schedule a migration event only when IKE AUTH is finished
                    // and mMobikeEnabled is true.
                    Log.d(
                            TAG,
                            "Migrate IKE Session with token "
                                    + mCurrentToken
                                    + " to network "
                                    + network);
                    mSession.setNetwork(network);
                    return;
                }

                // Clear mInterface to prevent Ikev2VpnRunner being cleared when
                // interfaceRemoved() is called.
                mInterface = null;
                // Without MOBIKE, we have no way to seamlessly migrate. Close on old
                // (non-default) network, and start the new one.
                resetIkeState();

                // Get Ike options from IkeTunnelConnectionParams if it's available in the
                // profile.
                final IkeTunnelConnectionParams ikeTunConnParams =
                        mProfile.getIkeTunnelConnectionParams();
                final IkeSessionParams ikeSessionParams;
                final ChildSessionParams childSessionParams;
                if (ikeTunConnParams != null) {
                    final IkeSessionParams.Builder builder = new IkeSessionParams.Builder(
                            ikeTunConnParams.getIkeSessionParams()).setNetwork(network);
                    ikeSessionParams = builder.build();
                    childSessionParams = ikeTunConnParams.getTunnelModeChildSessionParams();
                } else {
                    ikeSessionParams = VpnIkev2Utils.buildIkeSessionParams(
                            mContext, mProfile, network);
                    childSessionParams = VpnIkev2Utils.buildChildSessionParams(
                            mProfile.getAllowedAlgorithms());
                }

                // TODO: Remove the need for adding two unused addresses with
                // IPsec tunnels.
                final InetAddress address = InetAddress.getLocalHost();

                // When onChildOpened is called and transforms are applied, it is
                // guaranteed that the underlying network is still "network", because the
                // all the network switch events will be deferred before onChildOpened is
                // called. Thus it is safe to build a mTunnelIface before IKE setup.
                mTunnelIface =
                        mIpSecManager.createIpSecTunnelInterface(
                                address /* unused */, address /* unused */, network);
                NetdUtils.setInterfaceUp(mNetd, mTunnelIface.getInterfaceName());

                final int token = ++mCurrentToken;
                mSession =
                        mIkev2SessionCreator.createIkeSession(
                                mContext,
                                ikeSessionParams,
                                childSessionParams,
                                mExecutor,
                                new VpnIkev2Utils.IkeSessionCallbackImpl(
                                        TAG, IkeV2VpnRunner.this, token),
                                new VpnIkev2Utils.ChildSessionCallbackImpl(
                                        TAG, IkeV2VpnRunner.this, token));
                Log.d(TAG, "IKE session started for token " + token);
            } catch (Exception e) {
                Log.i(TAG, "Setup failed for token " + mCurrentToken + ". Aborting", e);
                onSessionLost(mCurrentToken, e);
            }
        }

        /** Called when the NetworkCapabilities of underlying network is changed */
        public void onDefaultNetworkCapabilitiesChanged(@NonNull NetworkCapabilities nc) {
            mUnderlyingNetworkCapabilities = nc;
        }

        /** Called when the LinkProperties of underlying network is changed */
        public void onDefaultNetworkLinkPropertiesChanged(@NonNull LinkProperties lp) {
            mUnderlyingLinkProperties = lp;
        }

        /**
         * Handles loss of the default underlying network
         *
         * <p>If the IKE Session has mobility, Ikev2VpnRunner will schedule a teardown event with a
         * delay so that the IKE Session can migrate if a new network is available soon. Otherwise,
         * Ikev2VpnRunner will kill the IKE session and reset the VPN.
         *
         * <p>This method MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        public void onDefaultNetworkLost(@NonNull Network network) {
            if (!isActiveNetwork(network)) {
                Log.d(TAG, "onDefaultNetworkLost called for obsolete network " + network);

                // Do nothing; this signals that either: (1) a new/better Network was found,
                // and the Ikev2VpnRunner has switched to it by restarting a new IKE session in
                // onDefaultNetworkChanged, or (2) this IKE session was already shut down (exited,
                // or an error was encountered somewhere else). In both cases, all resources and
                // sessions are torn down via resetIkeState().
                return;
            }

            if (mScheduledHandleNetworkLostTimeout != null
                    && !mScheduledHandleNetworkLostTimeout.isCancelled()
                    && !mScheduledHandleNetworkLostTimeout.isDone()) {
                final IllegalStateException exception =
                        new IllegalStateException(
                                "Found a pending mScheduledHandleNetworkLostTimeout");
                Log.i(
                        TAG,
                        "Unexpected error in onDefaultNetworkLost. Tear down session",
                        exception);
                handleSessionLost(exception);
                return;
            }

            if (mSession != null && mMobikeEnabled) {
                Log.d(
                        TAG,
                        "IKE Session has mobility. Delay handleSessionLost for losing network "
                                + network
                                + "on session with token "
                                + mCurrentToken);

                // Delay the teardown in case a new network will be available soon. For example,
                // during handover between two WiFi networks, Android will disconnect from the
                // first WiFi and then connects to the second WiFi.
                mScheduledHandleNetworkLostTimeout =
                        mExecutor.schedule(
                                () -> {
                                    handleSessionLost(null);
                                },
                                NETWORK_LOST_TIMEOUT_MS,
                                TimeUnit.MILLISECONDS);
            } else {
                Log.d(TAG, "Call handleSessionLost for losing network " + network);
                handleSessionLost(null);
            }
        }

        private void cancelHandleNetworkLostTimeout() {
            if (mScheduledHandleNetworkLostTimeout != null
                    && !mScheduledHandleNetworkLostTimeout.isDone()) {
                // It does not matter what to put in #cancel(boolean), because it is impossible
                // that the task tracked by mScheduledHandleNetworkLostTimeout is
                // in-progress since both that task and onDefaultNetworkChanged are submitted to
                // mExecutor who has only one thread.
                Log.d(TAG, "Cancel the task for handling network lost timeout");
                mScheduledHandleNetworkLostTimeout.cancel(false /* mayInterruptIfRunning */);
            }
        }

        /** Marks the state as FAILED, and disconnects. */
        private void markFailedAndDisconnect(Exception exception) {
            synchronized (Vpn.this) {
                updateState(DetailedState.FAILED, exception.getMessage());
            }

            disconnectVpnRunner();
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
        public void onSessionLost(int token, @Nullable Exception exception) {
            Log.d(TAG, "onSessionLost() called for token " + token);

            if (!isActiveToken(token)) {
                Log.d(TAG, "onSessionLost() called for obsolete token " + token);

                // Do nothing; this signals that either: (1) a new/better Network was found,
                // and the Ikev2VpnRunner has switched to it by restarting a new IKE session in
                // onDefaultNetworkChanged, or (2) this IKE session was already shut down (exited,
                // or an error was encountered somewhere else). In both cases, all resources and
                // sessions are torn down via resetIkeState().
                return;
            }

            handleSessionLost(exception);
        }

        private void handleSessionLost(@Nullable Exception exception) {
            // Cancel mScheduledHandleNetworkLostTimeout if the session it is going to terminate is
            // already terminated due to other failures.
            cancelHandleNetworkLostTimeout();

            synchronized (Vpn.this) {
                if (exception instanceof IkeProtocolException) {
                    final IkeProtocolException ikeException = (IkeProtocolException) exception;

                    switch (ikeException.getErrorType()) {
                        case IkeProtocolException.ERROR_TYPE_NO_PROPOSAL_CHOSEN: // Fallthrough
                        case IkeProtocolException.ERROR_TYPE_INVALID_KE_PAYLOAD: // Fallthrough
                        case IkeProtocolException.ERROR_TYPE_AUTHENTICATION_FAILED: // Fallthrough
                        case IkeProtocolException.ERROR_TYPE_SINGLE_PAIR_REQUIRED: // Fallthrough
                        case IkeProtocolException.ERROR_TYPE_FAILED_CP_REQUIRED: // Fallthrough
                        case IkeProtocolException.ERROR_TYPE_TS_UNACCEPTABLE:
                            // All the above failures are configuration errors, and are terminal
                            // TODO(b/230548427): Remove SDK check once VPN related stuff are
                            //  decoupled from ConnectivityServiceTest.
                            if (SdkLevel.isAtLeastT()) {
                                sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_IKE_ERROR,
                                        VpnManager.ERROR_CLASS_NOT_RECOVERABLE,
                                        ikeException.getErrorType(),
                                        getPackage(), mSessionKey, makeVpnProfileStateLocked(),
                                        mActiveNetwork,
                                        getRedactedNetworkCapabilitiesOfUnderlyingNetwork(
                                                mUnderlyingNetworkCapabilities),
                                        getRedactedLinkPropertiesOfUnderlyingNetwork(
                                                mUnderlyingLinkProperties));
                            }
                            markFailedAndDisconnect(exception);
                            return;
                        // All other cases possibly recoverable.
                        default:
                            // All the above failures are configuration errors, and are terminal
                            // TODO(b/230548427): Remove SDK check once VPN related stuff are
                            //  decoupled from ConnectivityServiceTest.
                            if (SdkLevel.isAtLeastT()) {
                                sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_IKE_ERROR,
                                        VpnManager.ERROR_CLASS_RECOVERABLE,
                                        ikeException.getErrorType(),
                                        getPackage(), mSessionKey, makeVpnProfileStateLocked(),
                                        mActiveNetwork,
                                        getRedactedNetworkCapabilitiesOfUnderlyingNetwork(
                                                mUnderlyingNetworkCapabilities),
                                        getRedactedLinkPropertiesOfUnderlyingNetwork(
                                                mUnderlyingLinkProperties));
                            }
                    }
                } else if (exception instanceof IllegalArgumentException) {
                    // Failed to build IKE/ChildSessionParams; fatal profile configuration error
                    markFailedAndDisconnect(exception);
                    return;
                } else if (exception instanceof IkeNetworkLostException) {
                    // TODO(b/230548427): Remove SDK check once VPN related stuff are
                    //  decoupled from ConnectivityServiceTest.
                    if (SdkLevel.isAtLeastT()) {
                        sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_NETWORK_ERROR,
                                VpnManager.ERROR_CLASS_RECOVERABLE,
                                VpnManager.ERROR_CODE_NETWORK_LOST,
                                getPackage(), mSessionKey, makeVpnProfileStateLocked(),
                                mActiveNetwork,
                                getRedactedNetworkCapabilitiesOfUnderlyingNetwork(
                                        mUnderlyingNetworkCapabilities),
                                getRedactedLinkPropertiesOfUnderlyingNetwork(
                                        mUnderlyingLinkProperties));
                    }
                } else if (exception instanceof IkeNonProtocolException) {
                    if (exception.getCause() instanceof UnknownHostException) {
                        // TODO(b/230548427): Remove SDK check once VPN related stuff are
                        //  decoupled from ConnectivityServiceTest.
                        if (SdkLevel.isAtLeastT()) {
                            sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_NETWORK_ERROR,
                                    VpnManager.ERROR_CLASS_RECOVERABLE,
                                    VpnManager.ERROR_CODE_NETWORK_UNKNOWN_HOST,
                                    getPackage(), mSessionKey, makeVpnProfileStateLocked(),
                                    mActiveNetwork,
                                    getRedactedNetworkCapabilitiesOfUnderlyingNetwork(
                                            mUnderlyingNetworkCapabilities),
                                    getRedactedLinkPropertiesOfUnderlyingNetwork(
                                            mUnderlyingLinkProperties));
                        }
                    } else if (exception.getCause() instanceof IkeTimeoutException) {
                        // TODO(b/230548427): Remove SDK check once VPN related stuff are
                        //  decoupled from ConnectivityServiceTest.
                        if (SdkLevel.isAtLeastT()) {
                            sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_NETWORK_ERROR,
                                    VpnManager.ERROR_CLASS_RECOVERABLE,
                                    VpnManager.ERROR_CODE_NETWORK_PROTOCOL_TIMEOUT,
                                    getPackage(), mSessionKey, makeVpnProfileStateLocked(),
                                    mActiveNetwork,
                                    getRedactedNetworkCapabilitiesOfUnderlyingNetwork(
                                            mUnderlyingNetworkCapabilities),
                                    getRedactedLinkPropertiesOfUnderlyingNetwork(
                                            mUnderlyingLinkProperties));
                        }
                    } else if (exception.getCause() instanceof IOException) {
                        // TODO(b/230548427): Remove SDK check once VPN related stuff are
                        //  decoupled from ConnectivityServiceTest.
                        if (SdkLevel.isAtLeastT()) {
                            sendEventToVpnManagerApp(VpnManager.CATEGORY_EVENT_NETWORK_ERROR,
                                    VpnManager.ERROR_CLASS_RECOVERABLE,
                                    VpnManager.ERROR_CODE_NETWORK_IO,
                                    getPackage(), mSessionKey, makeVpnProfileStateLocked(),
                                    mActiveNetwork,
                                    getRedactedNetworkCapabilitiesOfUnderlyingNetwork(
                                            mUnderlyingNetworkCapabilities),
                                    getRedactedLinkPropertiesOfUnderlyingNetwork(
                                            mUnderlyingLinkProperties));
                        }
                    }
                } else if (exception != null) {
                    Log.wtf(TAG, "onSessionLost: exception = " + exception);
                }
            }

            mActiveNetwork = null;
            mUnderlyingNetworkCapabilities = null;
            mUnderlyingLinkProperties = null;

            // Close all obsolete state, but keep VPN alive incase a usable network comes up.
            // (Mirrors VpnService behavior)
            Log.d(TAG, "Resetting state for token: " + mCurrentToken);

            synchronized (Vpn.this) {
                // Since this method handles non-fatal errors only, set mInterface to null to
                // prevent the NetworkManagementEventObserver from killing this VPN based on the
                // interface going down (which we expect).
                mInterface = null;
                if (mConfig != null) {
                    mConfig.interfaze = null;

                    // Set as unroutable to prevent traffic leaking while the interface is down.
                    if (mConfig.routes != null) {
                        final List<RouteInfo> oldRoutes = new ArrayList<>(mConfig.routes);

                        mConfig.routes.clear();
                        for (final RouteInfo route : oldRoutes) {
                            mConfig.routes.add(new RouteInfo(route.getDestination(),
                                    null /*gateway*/, null /*iface*/, RTN_UNREACHABLE));
                        }
                        if (mNetworkAgent != null) {
                            mNetworkAgent.sendLinkProperties(makeLinkProperties());
                        }
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
            mIkeConnectionInfo = null;
            mMobikeEnabled = false;
        }

        /**
         * Disconnects and shuts down this VPN.
         *
         * <p>This method resets all internal Ikev2VpnRunner state, but unless called via
         * VpnRunner#exit(), this Ikev2VpnRunner will still be listed as the active VPN of record
         * until the next VPN is started, or the Ikev2VpnRunner is explicitly exited. This is
         * necessary to ensure that the detailed state is shown in the Settings VPN menus; if the
         * active VPN is cleared, Settings VPNs will not show the resultant state or errors.
         *
         * <p>This method MUST always be called on the mExecutor thread in order to ensure
         * consistency of the Ikev2VpnRunner fields.
         */
        private void disconnectVpnRunner() {
            mActiveNetwork = null;
            mUnderlyingNetworkCapabilities = null;
            mUnderlyingLinkProperties = null;
            mIsRunning = false;

            resetIkeState();

            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);

            mExecutor.shutdown();
        }

        @Override
        public void exitVpnRunner() {
            try {
                mExecutor.execute(() -> {
                    disconnectVpnRunner();
                });
            } catch (RejectedExecutionException ignored) {
                // The Ikev2VpnRunner has already shut down.
            }
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
            if (racoon == null && mtpd == null) {
                throw new IllegalArgumentException(
                        "Arguments to racoon and mtpd must not both be null");
            }
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
                for (Network network : mConnectivityManager.getAllNetworks()) {
                    final LinkProperties lp = mConnectivityManager.getLinkProperties(network);
                    if (lp != null && lp.getAllInterfaceNames().contains(mOuterInterface)) {
                        final NetworkInfo netInfo = mConnectivityManager.getNetworkInfo(network);
                        if (netInfo != null) {
                            mOuterConnection.set(netInfo.getType());
                            break;
                        }
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
                        mDeps.stopService(daemon);
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

        private void checkAndFixupArguments(@NonNull final InetAddress endpointAddress) {
            final String endpointAddressString = endpointAddress.getHostAddress();
            // Perform some safety checks before inserting the address in place.
            // Position 0 in mDaemons and mArguments must be racoon, and position 1 must be mtpd.
            if (!"racoon".equals(mDaemons[0]) || !"mtpd".equals(mDaemons[1])) {
                throw new IllegalStateException("Unexpected daemons order");
            }

            // Respectively, the positions at which racoon and mtpd take the server address
            // argument are 1 and 2. Not all types of VPN require both daemons however, and
            // in that case the corresponding argument array is null.
            if (mArguments[0] != null) {
                if (!mProfile.server.equals(mArguments[0][1])) {
                    throw new IllegalStateException("Invalid server argument for racoon");
                }
                mArguments[0][1] = endpointAddressString;
            }

            if (mArguments[1] != null) {
                if (!mProfile.server.equals(mArguments[1][2])) {
                    throw new IllegalStateException("Invalid server argument for mtpd");
                }
                mArguments[1][2] = endpointAddressString;
            }
        }

        private void bringup() {
            // Catch all exceptions so we can clean up a few things.
            try {
                // resolve never returns null. If it does because of some bug, it will be
                // caught by the catch() block below and cleanup gracefully.
                final InetAddress endpointAddress = mDeps.resolve(mProfile.server);

                // Big hack : dynamically replace the address of the server in the arguments
                // with the resolved address.
                checkAndFixupArguments(endpointAddress);

                // Initialize the timer.
                mBringupStartTime = SystemClock.elapsedRealtime();

                // Wait for the daemons to stop.
                for (String daemon : mDaemons) {
                    while (!mDeps.isServiceStopped(daemon)) {
                        checkInterruptAndDelay(true);
                    }
                }

                // Clear the previous state.
                final File state = mDeps.getStateFile();
                state.delete();
                if (state.exists()) {
                    throw new IllegalStateException("Cannot delete the state");
                }
                new File("/data/misc/vpn/abort").delete();

                updateState(DetailedState.CONNECTING, "execute");

                // Start the daemon with arguments.
                for (int i = 0; i < mDaemons.length; ++i) {
                    String[] arguments = mArguments[i];
                    if (arguments == null) {
                        continue;
                    }

                    // Start the daemon.
                    String daemon = mDaemons[i];
                    mDeps.startService(daemon);

                    // Wait for the daemon to start.
                    while (!mDeps.isServiceRunning(daemon)) {
                        checkInterruptAndDelay(true);
                    }

                    // Create the control socket.
                    mSockets[i] = new LocalSocket();

                    // Wait for the socket to connect and send over the arguments.
                    mDeps.sendArgumentsToDaemon(daemon, mSockets[i], arguments,
                            this::checkInterruptAndDelay);
                }

                // Wait for the daemons to create the new state.
                while (!state.exists()) {
                    // Check if a running daemon is dead.
                    for (int i = 0; i < mDaemons.length; ++i) {
                        String daemon = mDaemons[i];
                        if (mArguments[i] != null && !mDeps.isServiceRunning(daemon)) {
                            throw new IllegalStateException(daemon + " is dead");
                        }
                    }
                    checkInterruptAndDelay(true);
                }

                // Now we are connected. Read and parse the new state.
                String[] parameters = FileUtils.readTextFile(state, 0, null).split("\n", -1);
                if (parameters.length != 7) {
                    throw new IllegalStateException("Cannot parse the state: '"
                            + String.join("', '", parameters) + "'");
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
                if (endpointAddress instanceof Inet4Address) {
                    mConfig.routes.add(new RouteInfo(
                            new IpPrefix(endpointAddress, 32), null /*gateway*/,
                            null /*iface*/, RTN_THROW));
                } else if (endpointAddress instanceof Inet6Address) {
                    mConfig.routes.add(new RouteInfo(
                            new IpPrefix(endpointAddress, 128), null /*gateway*/,
                            null /*iface*/, RTN_THROW));
                } else {
                    Log.e(TAG, "Unknown IP address family for VPN endpoint: "
                            + endpointAddress);
                }

                // Here is the last step and it must be done synchronously.
                synchronized (Vpn.this) {
                    // Set the start time
                    mConfig.startTime = SystemClock.elapsedRealtime();

                    // Check if the thread was interrupted while we were waiting on the lock.
                    checkInterruptAndDelay(false);

                    // Check if the interface is gone while we are waiting.
                    if (!mDeps.isInterfacePresent(Vpn.this, mConfig.interfaze)) {
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
                    if (mArguments[i] != null && mDeps.isServiceStopped(mDaemons[i])) {
                        return;
                    }
                }
            }
        }
    }

    private void verifyCallingUidAndPackage(String packageName) {
        final int callingUid = Binder.getCallingUid();
        if (getAppUid(packageName, mUserId) != callingUid) {
            throw new SecurityException(packageName + " does not belong to uid " + callingUid);
        }
    }

    @VisibleForTesting
    String getProfileNameForPackage(String packageName) {
        return Credentials.PLATFORM_VPN + mUserId + "_" + packageName;
    }

    @VisibleForTesting
    void validateRequiredFeatures(VpnProfile profile) {
        switch (profile.type) {
            case VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS:
            case VpnProfile.TYPE_IKEV2_IPSEC_PSK:
            case VpnProfile.TYPE_IKEV2_IPSEC_RSA:
            case VpnProfile.TYPE_IKEV2_FROM_IKE_TUN_CONN_PARAMS:
                if (!mContext.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_IPSEC_TUNNELS)) {
                    throw new UnsupportedOperationException(
                            "Ikev2VpnProfile(s) requires PackageManager.FEATURE_IPSEC_TUNNELS");
                }
                break;
            default:
                return;
        }
    }

    /**
     * Stores an app-provisioned VPN profile and returns whether the app is already prepared.
     *
     * @param packageName the package name of the app provisioning this profile
     * @param profile the profile to be stored and provisioned
     * @returns whether or not the app has already been granted user consent
     */
    public synchronized boolean provisionVpnProfile(
            @NonNull String packageName, @NonNull VpnProfile profile) {
        requireNonNull(packageName, "No package name provided");
        requireNonNull(profile, "No profile provided");

        verifyCallingUidAndPackage(packageName);
        enforceNotRestrictedUser();
        validateRequiredFeatures(profile);

        if (profile.isRestrictedToTestNetworks) {
            mContext.enforceCallingPermission(Manifest.permission.MANAGE_TEST_NETWORKS,
                    "Test-mode profiles require the MANAGE_TEST_NETWORKS permission");
        }

        final byte[] encodedProfile = profile.encode();
        if (encodedProfile.length > MAX_VPN_PROFILE_SIZE_BYTES) {
            throw new IllegalArgumentException("Profile too big");
        }

        // Permissions checked during startVpnProfile()
        final long token = Binder.clearCallingIdentity();
        try {
            getVpnProfileStore().put(getProfileNameForPackage(packageName), encodedProfile);
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // TODO: if package has CONTROL_VPN, grant the ACTIVATE_PLATFORM_VPN appop.
        // This mirrors the prepareAndAuthorize that is used by VpnService.

        // Return whether the app is already pre-consented
        return isVpnProfilePreConsented(mContext, packageName);
    }

    private boolean isCurrentIkev2VpnLocked(@NonNull String packageName) {
        return isCurrentPreparedPackage(packageName) && isIkev2VpnRunner();
    }

    /**
     * Deletes an app-provisioned VPN profile.
     *
     * @param packageName the package name of the app provisioning this profile
     */
    public synchronized void deleteVpnProfile(
            @NonNull String packageName) {
        requireNonNull(packageName, "No package name provided");

        verifyCallingUidAndPackage(packageName);
        enforceNotRestrictedUser();

        final long token = Binder.clearCallingIdentity();
        try {
            // If this profile is providing the current VPN, turn it off, disabling
            // always-on as well if enabled.
            if (isCurrentIkev2VpnLocked(packageName)) {
                if (mAlwaysOn) {
                    // Will transitively call prepareInternal(VpnConfig.LEGACY_VPN).
                    setAlwaysOnPackage(null, false, null);
                } else {
                    prepareInternal(VpnConfig.LEGACY_VPN);
                }
            }

            getVpnProfileStore().remove(getProfileNameForPackage(packageName));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Retrieves the VpnProfile.
     *
     * <p>Must be used only as SYSTEM_UID, otherwise the key/UID pair will not match anything in the
     * keystore.
     */
    @VisibleForTesting
    @Nullable
    VpnProfile getVpnProfilePrivileged(@NonNull String packageName) {
        if (!mDeps.isCallerSystem()) {
            Log.wtf(TAG, "getVpnProfilePrivileged called as non-System UID ");
            return null;
        }

        final byte[] encoded = getVpnProfileStore().get(getProfileNameForPackage(packageName));
        if (encoded == null) return null;

        return VpnProfile.decode("" /* Key unused */, encoded);
    }

    private boolean isIkev2VpnRunner() {
        return (mVpnRunner instanceof IkeV2VpnRunner);
    }

    @GuardedBy("this")
    @Nullable
    private String getSessionKeyLocked() {
        return isIkev2VpnRunner() ? ((IkeV2VpnRunner) mVpnRunner).mSessionKey : null;
    }

    /**
     * Starts an already provisioned VPN Profile, keyed by package name.
     *
     * <p>This method is meant to be called by apps (via VpnManager and ConnectivityService).
     * Privileged (system) callers should use startVpnProfilePrivileged instead. Otherwise the UIDs
     * will not match during appop checks.
     *
     * @param packageName the package name of the app provisioning this profile
     */
    public synchronized String startVpnProfile(@NonNull String packageName) {
        requireNonNull(packageName, "No package name provided");

        enforceNotRestrictedUser();

        // Prepare VPN for startup
        if (!prepare(packageName, null /* newPackage */, VpnManager.TYPE_VPN_PLATFORM)) {
            throw new SecurityException("User consent not granted for package " + packageName);
        }

        final long token = Binder.clearCallingIdentity();
        try {
            final VpnProfile profile = getVpnProfilePrivileged(packageName);
            if (profile == null) {
                throw new IllegalArgumentException("No profile found for " + packageName);
            }

            startVpnProfilePrivileged(profile, packageName);
            if (!isIkev2VpnRunner()) {
                throw new IllegalStateException("mVpnRunner shouldn't be null and should also be "
                        + "an instance of Ikev2VpnRunner");
            }
            return getSessionKeyLocked();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private synchronized void startVpnProfilePrivileged(
            @NonNull VpnProfile profile, @NonNull String packageName) {
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
            mConfig.requiresInternetValidation = profile.requiresInternetValidation;
            mConfig.excludeLocalRoutes = profile.excludeLocalRoutes;

            switch (profile.type) {
                case VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS:
                case VpnProfile.TYPE_IKEV2_IPSEC_PSK:
                case VpnProfile.TYPE_IKEV2_IPSEC_RSA:
                case VpnProfile.TYPE_IKEV2_FROM_IKE_TUN_CONN_PARAMS:
                    mVpnRunner =
                            new IkeV2VpnRunner(Ikev2VpnProfile.fromVpnProfile(profile));
                    mVpnRunner.start();
                    break;
                default:
                    updateState(DetailedState.FAILED, "Invalid platform VPN type");
                    Log.d(TAG, "Unknown VPN profile type: " + profile.type);
                    break;
            }

            // Record that the VPN connection is established by an app which uses VpnManager API.
            if (!VpnConfig.LEGACY_VPN.equals(packageName)) {
                mAppOpsManager.startOp(
                        AppOpsManager.OPSTR_ESTABLISH_VPN_MANAGER, mOwnerUID, mPackage, null,
                        null);
            }
        } catch (GeneralSecurityException e) {
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
        requireNonNull(packageName, "No package name provided");

        enforceNotRestrictedUser();

        // To stop the VPN profile, the caller must be the current prepared package and must be
        // running an Ikev2VpnProfile.
        if (isCurrentIkev2VpnLocked(packageName)) {
            prepareInternal(VpnConfig.LEGACY_VPN);
        }
    }

    private boolean storeAppExclusionList(@NonNull String packageName,
            @NonNull List<String> excludedApps) {
        byte[] data;
        try {
            final PersistableBundle bundle = PersistableBundleUtils.fromList(
                    excludedApps, PersistableBundleUtils.STRING_SERIALIZER);
            data = PersistableBundleUtils.toDiskStableBytes(bundle);
        } catch (IOException e) {
            Log.e(TAG, "problem writing into stream", e);
            return false;
        }

        final long oldId = Binder.clearCallingIdentity();
        try {
            getVpnProfileStore().put(getVpnAppExcludedForPackage(packageName), data);
        } finally {
            Binder.restoreCallingIdentity(oldId);
        }
        return true;
    }

    @VisibleForTesting
    String getVpnAppExcludedForPackage(String packageName) {
        return VPN_APP_EXCLUDED + mUserId + "_" + packageName;
    }

    /**
     * Set the application exclusion list for the specified VPN profile.
     *
     * @param packageName the package name of the app provisioning this profile
     * @param excludedApps the list of excluded packages
     *
     * @return whether setting the list is successful or not
     */
    public synchronized boolean setAppExclusionList(@NonNull String packageName,
            @NonNull List<String> excludedApps) {
        enforceNotRestrictedUser();
        if (!storeAppExclusionList(packageName, excludedApps)) return false;
        // Re-build and update NetworkCapabilities via NetworkAgent.
        if (mNetworkAgent != null) {
            // Only update the platform VPN
            if (isIkev2VpnRunner()) {
                mConfig.disallowedApplications = List.copyOf(excludedApps);
                mNetworkCapabilities = new NetworkCapabilities.Builder(mNetworkCapabilities)
                        .setUids(createUserAndRestrictedProfilesRanges(
                                mUserId, null /* allowedApplications */, excludedApps))
                        .build();
                mNetworkAgent.sendNetworkCapabilities(mNetworkCapabilities);
            }
        }

        return true;
    }

    /**
     * Gets the application exclusion list for the specified VPN profile.
     *
     * @param packageName the package name of the app provisioning this profile
     * @return the list of excluded packages for the specified VPN profile or empty list if there is
     *         no provisioned VPN profile.
     */
    @NonNull
    public synchronized List<String> getAppExclusionList(@NonNull String packageName) {
        enforceNotRestrictedUser();

        final long oldId = Binder.clearCallingIdentity();
        try {
            final byte[] bytes = getVpnProfileStore().get(getVpnAppExcludedForPackage(packageName));

            if (bytes == null || bytes.length == 0) return new ArrayList<>();

            final PersistableBundle bundle = PersistableBundleUtils.fromDiskStableBytes(bytes);
            return PersistableBundleUtils.toList(bundle, STRING_DESERIALIZER);
        } catch (IOException e) {
            Log.e(TAG, "problem reading from stream", e);
        }  finally {
            Binder.restoreCallingIdentity(oldId);
        }

        return new ArrayList<>();
    }

    private @VpnProfileState.State int getStateFromLegacyState(int legacyState) {
        switch (legacyState) {
            case LegacyVpnInfo.STATE_CONNECTING:
                return VpnProfileState.STATE_CONNECTING;
            case LegacyVpnInfo.STATE_CONNECTED:
                return VpnProfileState.STATE_CONNECTED;
            case LegacyVpnInfo.STATE_DISCONNECTED:
                return VpnProfileState.STATE_DISCONNECTED;
            case LegacyVpnInfo.STATE_FAILED:
                return VpnProfileState.STATE_FAILED;
            default:
                Log.wtf(TAG, "Unhandled state " + legacyState
                        + ", treat it as STATE_DISCONNECTED");
                return VpnProfileState.STATE_DISCONNECTED;
        }
    }

    @GuardedBy("this")
    @NonNull
    private VpnProfileState makeVpnProfileStateLocked() {
        return new VpnProfileState(getStateFromLegacyState(mLegacyState),
                isIkev2VpnRunner() ? getSessionKeyLocked() : null, mAlwaysOn, mLockdown);
    }

    @NonNull
    private VpnProfileState makeDisconnectedVpnProfileState() {
        return new VpnProfileState(VpnProfileState.STATE_DISCONNECTED, null /* sessionKey */,
                false /* alwaysOn */, false /* lockdown */);
    }

    /**
     * Retrieve the VpnProfileState for the profile provisioned by the given package.
     *
     * @return the VpnProfileState with current information, or null if there was no profile
     *         provisioned by the given package.
     */
    @Nullable
    public synchronized VpnProfileState getProvisionedVpnProfileState(
            @NonNull String packageName) {
        requireNonNull(packageName, "No package name provided");
        enforceNotRestrictedUser();
        return isCurrentIkev2VpnLocked(packageName) ? makeVpnProfileStateLocked() : null;
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

    /**
     * Returns the entire range of UIDs available to a macro-user. This is something like 0-99999.
     */
    @VisibleForTesting
    static Range<Integer> createUidRangeForUser(int userId) {
        return new Range<Integer>(userId * PER_USER_RANGE, (userId + 1) * PER_USER_RANGE - 1);
    }
}
