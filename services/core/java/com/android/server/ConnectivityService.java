/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import static android.Manifest.permission.RECEIVE_DATA_ACTIVITY_CHANGE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.NETID_UNSET;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.ConnectivityManager.TYPE_VPN;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.ConnectivityManager.isNetworkTypeValid;
import static android.net.INetworkMonitor.NETWORK_TEST_RESULT_VALID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOREGROUND;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkPolicyManager.RULE_NONE;
import static android.net.NetworkPolicyManager.uidRulesToString;
import static android.net.NetworkStack.NETWORKSTACK_PACKAGE_NAME;
import static android.net.shared.NetworkMonitorUtils.isValidationRequired;
import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;
import android.app.BroadcastOptions;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.net.ConnectionInfo;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.PacketKeepalive;
import android.net.IConnectivityManager;
import android.net.IIpConnectivityMetrics;
import android.net.INetd;
import android.net.INetdEventCallback;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkProperties;
import android.net.LinkProperties.CompareResult;
import android.net.MatchAllNetworkSpecifier;
import android.net.NattSocketKeepalive;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkMisc;
import android.net.NetworkPolicyManager;
import android.net.NetworkQuotaInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkStack;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.NetworkWatchlistManager;
import android.net.PrivateDnsConfigParcel;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.UidRange;
import android.net.Uri;
import android.net.VpnService;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkEvent;
import android.net.netlink.InetDiagMessage;
import android.net.shared.NetdService;
import android.net.shared.NetworkMonitorUtils;
import android.net.shared.PrivateDnsConfig;
import android.net.util.MultinetworkPolicyTracker;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnInfo;
import com.android.internal.net.VpnProfile;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.WakeupMessage;
import com.android.internal.util.XmlUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.connectivity.DataConnectionStats;
import com.android.server.connectivity.DnsManager;
import com.android.server.connectivity.DnsManager.PrivateDnsValidationUpdate;
import com.android.server.connectivity.IpConnectivityMetrics;
import com.android.server.connectivity.KeepaliveTracker;
import com.android.server.connectivity.LingerMonitor;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.connectivity.MultipathPolicyTracker;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkDiagnostics;
import com.android.server.connectivity.NetworkNotificationManager;
import com.android.server.connectivity.NetworkNotificationManager.NotificationType;
import com.android.server.connectivity.PermissionMonitor;
import com.android.server.connectivity.ProxyTracker;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;
import com.android.server.connectivity.tethering.TetheringDependencies;
import com.android.server.net.BaseNetdEventCallback;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.net.LockdownVpnTracker;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.utils.PriorityDump;

import com.google.android.collect.Lists;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * @hide
 */
public class ConnectivityService extends IConnectivityManager.Stub
        implements PendingIntent.OnFinished {
    private static final String TAG = ConnectivityService.class.getSimpleName();

    private static final String DIAG_ARG = "--diag";
    public static final String SHORT_ARG = "--short";
    private static final String TETHERING_ARG = "tethering";
    private static final String NETWORK_ARG = "networks";
    private static final String REQUEST_ARG = "requests";

    private static final boolean DBG = true;
    private static final boolean DDBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);

    private static final boolean LOGD_BLOCKED_NETWORKINFO = true;

    // TODO: create better separation between radio types and network types

    // how long to wait before switching back to a radio's default network
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME =
            "android.telephony.apn-restore";

    // How long to wait before putting up a "This network doesn't have an Internet connection,
    // connect anyway?" dialog after the user selects a network that doesn't validate.
    private static final int PROMPT_UNVALIDATED_DELAY_MS = 8 * 1000;

    // How long to dismiss network notification.
    private static final int TIMEOUT_NOTIFICATION_DELAY_MS = 20 * 1000;

    // Default to 30s linger time-out. Modifiable only for testing.
    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    private static final int DEFAULT_LINGER_DELAY_MS = 30_000;
    @VisibleForTesting
    protected int mLingerDelayMs;  // Can't be final, or test subclass constructors can't change it.

    // How long to delay to removal of a pending intent based request.
    // See Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS
    private final int mReleasePendingIntentDelayMs;

    private MockableSystemProperties mSystemProperties;

    private Tethering mTethering;

    private final PermissionMonitor mPermissionMonitor;

    private KeyStore mKeyStore;

    @VisibleForTesting
    @GuardedBy("mVpns")
    protected final SparseArray<Vpn> mVpns = new SparseArray<>();

    // TODO: investigate if mLockdownEnabled can be removed and replaced everywhere by
    // a direct call to LockdownVpnTracker.isEnabled().
    @GuardedBy("mVpns")
    private boolean mLockdownEnabled;
    @GuardedBy("mVpns")
    private LockdownVpnTracker mLockdownTracker;

    /**
     * Stale copy of uid rules provided by NPMS. As long as they are accessed only in internal
     * handler thread, they don't need a lock.
     */
    private SparseIntArray mUidRules = new SparseIntArray();
    /** Flag indicating if background data is restricted. */
    private boolean mRestrictBackground;

    final private Context mContext;
    // 0 is full bad, 100 is full good
    private int mDefaultInetConditionPublished = 0;

    private INetworkManagementService mNMS;
    @VisibleForTesting
    protected INetd mNetd;
    private INetworkStatsService mStatsService;
    private INetworkPolicyManager mPolicyManager;
    private NetworkPolicyManagerInternal mPolicyManagerInternal;

    private String mCurrentTcpBufferSizes;

    private static final SparseArray<String> sMagicDecoderRing = MessageUtils.findMessageNames(
            new Class[] { AsyncChannel.class, ConnectivityService.class, NetworkAgent.class,
                    NetworkAgentInfo.class });

    private enum ReapUnvalidatedNetworks {
        // Tear down networks that have no chance (e.g. even if validated) of becoming
        // the highest scoring network satisfying a NetworkRequest.  This should be passed when
        // all networks have been rematched against all NetworkRequests.
        REAP,
        // Don't reap networks.  This should be passed when some networks have not yet been
        // rematched against all NetworkRequests.
        DONT_REAP
    }

    private enum UnneededFor {
        LINGER,    // Determine whether this network is unneeded and should be lingered.
        TEARDOWN,  // Determine whether this network is unneeded and should be torn down.
    }

    /**
     * used internally to clear a wakelock when transitioning
     * from one net to another.  Clear happens when we get a new
     * network - EVENT_EXPIRE_NET_TRANSITION_WAKELOCK happens
     * after a timeout if no network is found (typically 1 min).
     */
    private static final int EVENT_CLEAR_NET_TRANSITION_WAKELOCK = 8;

    /**
     * used internally to reload global proxy settings
     */
    private static final int EVENT_APPLY_GLOBAL_HTTP_PROXY = 9;

    /**
     * PAC manager has received new port.
     */
    private static final int EVENT_PROXY_HAS_CHANGED = 16;

    /**
     * used internally when registering NetworkFactories
     * obj = NetworkFactoryInfo
     */
    private static final int EVENT_REGISTER_NETWORK_FACTORY = 17;

    /**
     * used internally when registering NetworkAgents
     * obj = Messenger
     */
    private static final int EVENT_REGISTER_NETWORK_AGENT = 18;

    /**
     * used to add a network request
     * includes a NetworkRequestInfo
     */
    private static final int EVENT_REGISTER_NETWORK_REQUEST = 19;

    /**
     * indicates a timeout period is over - check if we had a network yet or not
     * and if not, call the timeout callback (but leave the request live until they
     * cancel it.
     * includes a NetworkRequestInfo
     */
    private static final int EVENT_TIMEOUT_NETWORK_REQUEST = 20;

    /**
     * used to add a network listener - no request
     * includes a NetworkRequestInfo
     */
    private static final int EVENT_REGISTER_NETWORK_LISTENER = 21;

    /**
     * used to remove a network request, either a listener or a real request
     * arg1 = UID of caller
     * obj  = NetworkRequest
     */
    private static final int EVENT_RELEASE_NETWORK_REQUEST = 22;

    /**
     * used internally when registering NetworkFactories
     * obj = Messenger
     */
    private static final int EVENT_UNREGISTER_NETWORK_FACTORY = 23;

    /**
     * used internally to expire a wakelock when transitioning
     * from one net to another.  Expire happens when we fail to find
     * a new network (typically after 1 minute) -
     * EVENT_CLEAR_NET_TRANSITION_WAKELOCK happens if we had found
     * a replacement network.
     */
    private static final int EVENT_EXPIRE_NET_TRANSITION_WAKELOCK = 24;

    /**
     * Used internally to indicate the system is ready.
     */
    private static final int EVENT_SYSTEM_READY = 25;

    /**
     * used to add a network request with a pending intent
     * obj = NetworkRequestInfo
     */
    private static final int EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT = 26;

    /**
     * used to remove a pending intent and its associated network request.
     * arg1 = UID of caller
     * obj  = PendingIntent
     */
    private static final int EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT = 27;

    /**
     * used to specify whether a network should be used even if unvalidated.
     * arg1 = whether to accept the network if it's unvalidated (1 or 0)
     * arg2 = whether to remember this choice in the future (1 or 0)
     * obj  = network
     */
    private static final int EVENT_SET_ACCEPT_UNVALIDATED = 28;

    /**
     * used to ask the user to confirm a connection to an unvalidated network.
     * obj  = network
     */
    private static final int EVENT_PROMPT_UNVALIDATED = 29;

    /**
     * used internally to (re)configure always-on networks.
     */
    private static final int EVENT_CONFIGURE_ALWAYS_ON_NETWORKS = 30;

    /**
     * used to add a network listener with a pending intent
     * obj = NetworkRequestInfo
     */
    private static final int EVENT_REGISTER_NETWORK_LISTENER_WITH_INTENT = 31;

    /**
     * used to specify whether a network should not be penalized when it becomes unvalidated.
     */
    private static final int EVENT_SET_AVOID_UNVALIDATED = 35;

    /**
     * used to trigger revalidation of a network.
     */
    private static final int EVENT_REVALIDATE_NETWORK = 36;

    // Handle changes in Private DNS settings.
    private static final int EVENT_PRIVATE_DNS_SETTINGS_CHANGED = 37;

    // Handle private DNS validation status updates.
    private static final int EVENT_PRIVATE_DNS_VALIDATION_UPDATE = 38;

    /**
     * Used to handle onUidRulesChanged event from NetworkPolicyManagerService.
     */
    private static final int EVENT_UID_RULES_CHANGED = 39;

    /**
     * Used to handle onRestrictBackgroundChanged event from NetworkPolicyManagerService.
     */
    private static final int EVENT_DATA_SAVER_CHANGED = 40;

     /**
      * Event for NetworkMonitor/NetworkAgentInfo to inform ConnectivityService that the network has
      * been tested.
      * obj = String representing URL that Internet probe was redirect to, if it was redirected.
      * arg1 = One of the NETWORK_TESTED_RESULT_* constants.
      * arg2 = NetID.
      */
    public static final int EVENT_NETWORK_TESTED = 41;

    /**
     * Event for NetworkMonitor/NetworkAgentInfo to inform ConnectivityService that the private DNS
     * config was resolved.
     * obj = PrivateDnsConfig
     * arg2 = netid
     */
    public static final int EVENT_PRIVATE_DNS_CONFIG_RESOLVED = 42;

    /**
     * Request ConnectivityService display provisioning notification.
     * arg1    = Whether to make the notification visible.
     * arg2    = NetID.
     * obj     = Intent to be launched when notification selected by user, null if !arg1.
     */
    public static final int EVENT_PROVISIONING_NOTIFICATION = 43;

    /**
     * This event can handle dismissing notification by given network id.
     */
    public static final int EVENT_TIMEOUT_NOTIFICATION = 44;

    /**
     * Argument for {@link #EVENT_PROVISIONING_NOTIFICATION} to indicate that the notification
     * should be shown.
     */
    public static final int PROVISIONING_NOTIFICATION_SHOW = 1;

    /**
     * Argument for {@link #EVENT_PROVISIONING_NOTIFICATION} to indicate that the notification
     * should be hidden.
     */
    public static final int PROVISIONING_NOTIFICATION_HIDE = 0;

    private static String eventName(int what) {
        return sMagicDecoderRing.get(what, Integer.toString(what));
    }

    /** Handler thread used for both of the handlers below. */
    @VisibleForTesting
    protected final HandlerThread mHandlerThread;
    /** Handler used for internal events. */
    final private InternalHandler mHandler;
    /** Handler used for incoming {@link NetworkStateTracker} events. */
    final private NetworkStateTrackerHandler mTrackerHandler;
    private final DnsManager mDnsManager;

    private boolean mSystemReady;
    private Intent mInitialBroadcast;

    private PowerManager.WakeLock mNetTransitionWakeLock;
    private int mNetTransitionWakeLockTimeout;
    private final PowerManager.WakeLock mPendingIntentWakeLock;

    // A helper object to track the current default HTTP proxy. ConnectivityService needs to tell
    // the world when it changes.
    @VisibleForTesting
    protected final ProxyTracker mProxyTracker;

    final private SettingsObserver mSettingsObserver;

    private UserManager mUserManager;

    private NetworkConfig[] mNetConfigs;
    private int mNetworksDefined;

    // the set of network types that can only be enabled by system/sig apps
    private List mProtectedNetworks;

    private TelephonyManager mTelephonyManager;

    private KeepaliveTracker mKeepaliveTracker;
    private NetworkNotificationManager mNotifier;
    private LingerMonitor mLingerMonitor;

    // sequence number for Networks; keep in sync with system/netd/NetworkController.cpp
    private static final int MIN_NET_ID = 100; // some reserved marks
    private static final int MAX_NET_ID = 65535 - 0x0400; // Top 1024 bits reserved by IpSecService
    private int mNextNetId = MIN_NET_ID;

    // sequence number of NetworkRequests
    private int mNextNetworkRequestId = 1;

    // NetworkRequest activity String log entries.
    private static final int MAX_NETWORK_REQUEST_LOGS = 20;
    private final LocalLog mNetworkRequestInfoLogs = new LocalLog(MAX_NETWORK_REQUEST_LOGS);

    // NetworkInfo blocked and unblocked String log entries
    private static final int MAX_NETWORK_INFO_LOGS = 40;
    private final LocalLog mNetworkInfoBlockingLogs = new LocalLog(MAX_NETWORK_INFO_LOGS);

    private static final int MAX_WAKELOCK_LOGS = 20;
    private final LocalLog mWakelockLogs = new LocalLog(MAX_WAKELOCK_LOGS);
    private int mTotalWakelockAcquisitions = 0;
    private int mTotalWakelockReleases = 0;
    private long mTotalWakelockDurationMs = 0;
    private long mMaxWakelockDurationMs = 0;
    private long mLastWakeLockAcquireTimestamp = 0;

    private final IpConnectivityLog mMetricsLog;

    @GuardedBy("mBandwidthRequests")
    private final SparseArray<Integer> mBandwidthRequests = new SparseArray(10);

    @VisibleForTesting
    final MultinetworkPolicyTracker mMultinetworkPolicyTracker;

    @VisibleForTesting
    final MultipathPolicyTracker mMultipathPolicyTracker;

    /**
     * Implements support for the legacy "one network per network type" model.
     *
     * We used to have a static array of NetworkStateTrackers, one for each
     * network type, but that doesn't work any more now that we can have,
     * for example, more that one wifi network. This class stores all the
     * NetworkAgentInfo objects that support a given type, but the legacy
     * API will only see the first one.
     *
     * It serves two main purposes:
     *
     * 1. Provide information about "the network for a given type" (since this
     *    API only supports one).
     * 2. Send legacy connectivity change broadcasts. Broadcasts are sent if
     *    the first network for a given type changes, or if the default network
     *    changes.
     */
    private class LegacyTypeTracker {

        private static final boolean DBG = true;
        private static final boolean VDBG = false;

        /**
         * Array of lists, one per legacy network type (e.g., TYPE_MOBILE_MMS).
         * Each list holds references to all NetworkAgentInfos that are used to
         * satisfy requests for that network type.
         *
         * This array is built out at startup such that an unsupported network
         * doesn't get an ArrayList instance, making this a tristate:
         * unsupported, supported but not active and active.
         *
         * The actual lists are populated when we scan the network types that
         * are supported on this device.
         *
         * Threading model:
         *  - addSupportedType() is only called in the constructor
         *  - add(), update(), remove() are only called from the ConnectivityService handler thread.
         *    They are therefore not thread-safe with respect to each other.
         *  - getNetworkForType() can be called at any time on binder threads. It is synchronized
         *    on mTypeLists to be thread-safe with respect to a concurrent remove call.
         *  - dump is thread-safe with respect to concurrent add and remove calls.
         */
        private final ArrayList<NetworkAgentInfo> mTypeLists[];

        public LegacyTypeTracker() {
            mTypeLists = (ArrayList<NetworkAgentInfo>[])
                    new ArrayList[ConnectivityManager.MAX_NETWORK_TYPE + 1];
        }

        public void addSupportedType(int type) {
            if (mTypeLists[type] != null) {
                throw new IllegalStateException(
                        "legacy list for type " + type + "already initialized");
            }
            mTypeLists[type] = new ArrayList<>();
        }

        public boolean isTypeSupported(int type) {
            return isNetworkTypeValid(type) && mTypeLists[type] != null;
        }

        public NetworkAgentInfo getNetworkForType(int type) {
            synchronized (mTypeLists) {
                if (isTypeSupported(type) && !mTypeLists[type].isEmpty()) {
                    return mTypeLists[type].get(0);
                }
            }
            return null;
        }

        private void maybeLogBroadcast(NetworkAgentInfo nai, DetailedState state, int type,
                boolean isDefaultNetwork) {
            if (DBG) {
                log("Sending " + state +
                        " broadcast for type " + type + " " + nai.name() +
                        " isDefaultNetwork=" + isDefaultNetwork);
            }
        }

        /** Adds the given network to the specified legacy type list. */
        public void add(int type, NetworkAgentInfo nai) {
            if (!isTypeSupported(type)) {
                return;  // Invalid network type.
            }
            if (VDBG) log("Adding agent " + nai + " for legacy network type " + type);

            ArrayList<NetworkAgentInfo> list = mTypeLists[type];
            if (list.contains(nai)) {
                return;
            }
            synchronized (mTypeLists) {
                list.add(nai);
            }

            // Send a broadcast if this is the first network of its type or if it's the default.
            final boolean isDefaultNetwork = isDefaultNetwork(nai);
            if ((list.size() == 1) || isDefaultNetwork) {
                maybeLogBroadcast(nai, DetailedState.CONNECTED, type, isDefaultNetwork);
                sendLegacyNetworkBroadcast(nai, DetailedState.CONNECTED, type);
            }
        }

        /** Removes the given network from the specified legacy type list. */
        public void remove(int type, NetworkAgentInfo nai, boolean wasDefault) {
            ArrayList<NetworkAgentInfo> list = mTypeLists[type];
            if (list == null || list.isEmpty()) {
                return;
            }
            final boolean wasFirstNetwork = list.get(0).equals(nai);

            synchronized (mTypeLists) {
                if (!list.remove(nai)) {
                    return;
                }
            }

            final DetailedState state = DetailedState.DISCONNECTED;

            if (wasFirstNetwork || wasDefault) {
                maybeLogBroadcast(nai, state, type, wasDefault);
                sendLegacyNetworkBroadcast(nai, state, type);
            }

            if (!list.isEmpty() && wasFirstNetwork) {
                if (DBG) log("Other network available for type " + type +
                              ", sending connected broadcast");
                final NetworkAgentInfo replacement = list.get(0);
                maybeLogBroadcast(replacement, state, type, isDefaultNetwork(replacement));
                sendLegacyNetworkBroadcast(replacement, state, type);
            }
        }

        /** Removes the given network from all legacy type lists. */
        public void remove(NetworkAgentInfo nai, boolean wasDefault) {
            if (VDBG) log("Removing agent " + nai + " wasDefault=" + wasDefault);
            for (int type = 0; type < mTypeLists.length; type++) {
                remove(type, nai, wasDefault);
            }
        }

        // send out another legacy broadcast - currently only used for suspend/unsuspend
        // toggle
        public void update(NetworkAgentInfo nai) {
            final boolean isDefault = isDefaultNetwork(nai);
            final DetailedState state = nai.networkInfo.getDetailedState();
            for (int type = 0; type < mTypeLists.length; type++) {
                final ArrayList<NetworkAgentInfo> list = mTypeLists[type];
                final boolean contains = (list != null && list.contains(nai));
                final boolean isFirst = contains && (nai == list.get(0));
                if (isFirst || contains && isDefault) {
                    maybeLogBroadcast(nai, state, type, isDefault);
                    sendLegacyNetworkBroadcast(nai, state, type);
                }
            }
        }

        private String naiToString(NetworkAgentInfo nai) {
            String name = nai.name();
            String state = (nai.networkInfo != null) ?
                    nai.networkInfo.getState() + "/" + nai.networkInfo.getDetailedState() :
                    "???/???";
            return name + " " + state;
        }

        public void dump(IndentingPrintWriter pw) {
            pw.println("mLegacyTypeTracker:");
            pw.increaseIndent();
            pw.print("Supported types:");
            for (int type = 0; type < mTypeLists.length; type++) {
                if (mTypeLists[type] != null) pw.print(" " + type);
            }
            pw.println();
            pw.println("Current state:");
            pw.increaseIndent();
            synchronized (mTypeLists) {
                for (int type = 0; type < mTypeLists.length; type++) {
                    if (mTypeLists[type] == null || mTypeLists[type].isEmpty()) continue;
                    for (NetworkAgentInfo nai : mTypeLists[type]) {
                        pw.println(type + " " + naiToString(nai));
                    }
                }
            }
            pw.decreaseIndent();
            pw.decreaseIndent();
            pw.println();
        }
    }
    private LegacyTypeTracker mLegacyTypeTracker = new LegacyTypeTracker();

    /**
     * Helper class which parses out priority arguments and dumps sections according to their
     * priority. If priority arguments are omitted, function calls the legacy dump command.
     */
    private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() {
        @Override
        public void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            doDump(fd, pw, new String[] {DIAG_ARG}, asProto);
            doDump(fd, pw, new String[] {SHORT_ARG}, asProto);
        }

        @Override
        public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            doDump(fd, pw, args, asProto);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
           doDump(fd, pw, args, asProto);
        }
    };

    public ConnectivityService(Context context, INetworkManagementService netManager,
            INetworkStatsService statsService, INetworkPolicyManager policyManager) {
        this(context, netManager, statsService, policyManager, new IpConnectivityLog());
    }

    @VisibleForTesting
    protected ConnectivityService(Context context, INetworkManagementService netManager,
            INetworkStatsService statsService, INetworkPolicyManager policyManager,
            IpConnectivityLog logger) {
        if (DBG) log("ConnectivityService starting up");

        mSystemProperties = getSystemProperties();

        mMetricsLog = logger;
        mDefaultRequest = createDefaultInternetRequestForTransport(-1, NetworkRequest.Type.REQUEST);
        NetworkRequestInfo defaultNRI = new NetworkRequestInfo(null, mDefaultRequest, new Binder());
        mNetworkRequests.put(mDefaultRequest, defaultNRI);
        mNetworkRequestInfoLogs.log("REGISTER " + defaultNRI);

        mDefaultMobileDataRequest = createDefaultInternetRequestForTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR, NetworkRequest.Type.BACKGROUND_REQUEST);

        // The default WiFi request is a background request so that apps using WiFi are
        // migrated to a better network (typically ethernet) when one comes up, instead
        // of staying on WiFi forever.
        mDefaultWifiRequest = createDefaultInternetRequestForTransport(
                NetworkCapabilities.TRANSPORT_WIFI, NetworkRequest.Type.BACKGROUND_REQUEST);

        mHandlerThread = new HandlerThread("ConnectivityServiceThread");
        mHandlerThread.start();
        mHandler = new InternalHandler(mHandlerThread.getLooper());
        mTrackerHandler = new NetworkStateTrackerHandler(mHandlerThread.getLooper());

        mReleasePendingIntentDelayMs = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS, 5_000);

        mLingerDelayMs = mSystemProperties.getInt(LINGER_DELAY_PROPERTY, DEFAULT_LINGER_DELAY_MS);

        mContext = checkNotNull(context, "missing Context");
        mNMS = checkNotNull(netManager, "missing INetworkManagementService");
        mStatsService = checkNotNull(statsService, "missing INetworkStatsService");
        mPolicyManager = checkNotNull(policyManager, "missing INetworkPolicyManager");
        mPolicyManagerInternal = checkNotNull(
                LocalServices.getService(NetworkPolicyManagerInternal.class),
                "missing NetworkPolicyManagerInternal");
        mProxyTracker = makeProxyTracker();

        mNetd = NetdService.getInstance();
        mKeyStore = KeyStore.getInstance();
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        // To ensure uid rules are synchronized with Network Policy, register for
        // NetworkPolicyManagerService events must happen prior to NetworkPolicyManagerService
        // reading existing policy from disk.
        try {
            mPolicyManager.registerListener(mPolicyListener);
        } catch (RemoteException e) {
            // ouch, no rules updates means some processes may never get network
            loge("unable to register INetworkPolicyListener" + e);
        }

        final PowerManager powerManager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mNetTransitionWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mNetTransitionWakeLockTimeout = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_networkTransitionTimeout);
        mPendingIntentWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        mNetConfigs = new NetworkConfig[ConnectivityManager.MAX_NETWORK_TYPE+1];

        // TODO: What is the "correct" way to do determine if this is a wifi only device?
        boolean wifiOnly = mSystemProperties.getBoolean("ro.radio.noril", false);
        log("wifiOnly=" + wifiOnly);
        String[] naStrings = context.getResources().getStringArray(
                com.android.internal.R.array.networkAttributes);
        for (String naString : naStrings) {
            try {
                NetworkConfig n = new NetworkConfig(naString);
                if (VDBG) log("naString=" + naString + " config=" + n);
                if (n.type > ConnectivityManager.MAX_NETWORK_TYPE) {
                    loge("Error in networkAttributes - ignoring attempt to define type " +
                            n.type);
                    continue;
                }
                if (wifiOnly && ConnectivityManager.isNetworkTypeMobile(n.type)) {
                    log("networkAttributes - ignoring mobile as this dev is wifiOnly " +
                            n.type);
                    continue;
                }
                if (mNetConfigs[n.type] != null) {
                    loge("Error in networkAttributes - ignoring attempt to redefine type " +
                            n.type);
                    continue;
                }
                mLegacyTypeTracker.addSupportedType(n.type);

                mNetConfigs[n.type] = n;
                mNetworksDefined++;
            } catch(Exception e) {
                // ignore it - leave the entry null
            }
        }

        // Forcibly add TYPE_VPN as a supported type, if it has not already been added via config.
        if (mNetConfigs[TYPE_VPN] == null) {
            // mNetConfigs is used only for "restore time", which isn't applicable to VPNs, so we
            // don't need to add TYPE_VPN to mNetConfigs.
            mLegacyTypeTracker.addSupportedType(TYPE_VPN);
            mNetworksDefined++;  // used only in the log() statement below.
        }

        // Do the same for Ethernet, since it's often not specified in the configs, although many
        // devices can use it via USB host adapters.
        if (mNetConfigs[TYPE_ETHERNET] == null && hasService(Context.ETHERNET_SERVICE)) {
            mLegacyTypeTracker.addSupportedType(TYPE_ETHERNET);
            mNetworksDefined++;
        }

        if (VDBG) log("mNetworksDefined=" + mNetworksDefined);

        mProtectedNetworks = new ArrayList<Integer>();
        int[] protectedNetworks = context.getResources().getIntArray(
                com.android.internal.R.array.config_protectedNetworks);
        for (int p : protectedNetworks) {
            if ((mNetConfigs[p] != null) && (mProtectedNetworks.contains(p) == false)) {
                mProtectedNetworks.add(p);
            } else {
                if (DBG) loge("Ignoring protectedNetwork " + p);
            }
        }

        mTethering = makeTethering();

        mPermissionMonitor = new PermissionMonitor(mContext, mNMS);

        //set up the listener for user state for creating user VPNs
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_STARTED);
        intentFilter.addAction(Intent.ACTION_USER_STOPPED);
        intentFilter.addAction(Intent.ACTION_USER_ADDED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(
                mIntentReceiver, UserHandle.ALL, intentFilter, null, null);
        mContext.registerReceiverAsUser(mUserPresentReceiver, UserHandle.SYSTEM,
                new IntentFilter(Intent.ACTION_USER_PRESENT), null, null);

        // Listen to package add and removal events for all users.
        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(
                mIntentReceiver, UserHandle.ALL, intentFilter, null, null);

        try {
            mNMS.registerObserver(mTethering);
            mNMS.registerObserver(mDataActivityObserver);
        } catch (RemoteException e) {
            loge("Error registering observer :" + e);
        }

        mSettingsObserver = new SettingsObserver(mContext, mHandler);
        registerSettingsCallbacks();

        final DataConnectionStats dataConnectionStats = new DataConnectionStats(mContext);
        dataConnectionStats.startMonitoring();

        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);

        mKeepaliveTracker = new KeepaliveTracker(mHandler);
        mNotifier = new NetworkNotificationManager(mContext, mTelephonyManager,
                mContext.getSystemService(NotificationManager.class));

        final int dailyLimit = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT,
                LingerMonitor.DEFAULT_NOTIFICATION_DAILY_LIMIT);
        final long rateLimit = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS,
                LingerMonitor.DEFAULT_NOTIFICATION_RATE_LIMIT_MILLIS);
        mLingerMonitor = new LingerMonitor(mContext, mNotifier, dailyLimit, rateLimit);

        mMultinetworkPolicyTracker = createMultinetworkPolicyTracker(
                mContext, mHandler, () -> rematchForAvoidBadWifiUpdate());
        mMultinetworkPolicyTracker.start();

        mMultipathPolicyTracker = new MultipathPolicyTracker(mContext, mHandler);

        mDnsManager = new DnsManager(mContext, mNMS, mSystemProperties);
        registerPrivateDnsSettingsCallbacks();
    }

    @VisibleForTesting
    protected Tethering makeTethering() {
        // TODO: Move other elements into @Overridden getters.
        final TetheringDependencies deps = new TetheringDependencies() {
            @Override
            public boolean isTetheringSupported() {
                return ConnectivityService.this.isTetheringSupported();
            }
            @Override
            public NetworkRequest getDefaultNetworkRequest() {
                return mDefaultRequest;
            }
        };
        return new Tethering(mContext, mNMS, mStatsService, mPolicyManager,
                IoThread.get().getLooper(), new MockableSystemProperties(),
                deps);
    }

    @VisibleForTesting
    protected ProxyTracker makeProxyTracker() {
        return new ProxyTracker(mContext, mHandler, EVENT_PROXY_HAS_CHANGED);
    }

    private static NetworkCapabilities createDefaultNetworkCapabilitiesForUid(int uid) {
        final NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(NET_CAPABILITY_INTERNET);
        netCap.addCapability(NET_CAPABILITY_NOT_RESTRICTED);
        netCap.removeCapability(NET_CAPABILITY_NOT_VPN);
        netCap.setSingleUid(uid);
        return netCap;
    }

    private NetworkRequest createDefaultInternetRequestForTransport(
            int transportType, NetworkRequest.Type type) {
        final NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(NET_CAPABILITY_INTERNET);
        netCap.addCapability(NET_CAPABILITY_NOT_RESTRICTED);
        if (transportType > -1) {
            netCap.addTransportType(transportType);
        }
        return new NetworkRequest(netCap, TYPE_NONE, nextNetworkRequestId(), type);
    }

    // Used only for testing.
    // TODO: Delete this and either:
    // 1. Give FakeSettingsProvider the ability to send settings change notifications (requires
    //    changing ContentResolver to make registerContentObserver non-final).
    // 2. Give FakeSettingsProvider an alternative notification mechanism and have the test use it
    //    by subclassing SettingsObserver.
    @VisibleForTesting
    void updateAlwaysOnNetworks() {
        mHandler.sendEmptyMessage(EVENT_CONFIGURE_ALWAYS_ON_NETWORKS);
    }

    // See FakeSettingsProvider comment above.
    @VisibleForTesting
    void updatePrivateDnsSettings() {
        mHandler.sendEmptyMessage(EVENT_PRIVATE_DNS_SETTINGS_CHANGED);
    }

    private void handleAlwaysOnNetworkRequest(
            NetworkRequest networkRequest, String settingName, boolean defaultValue) {
        final boolean enable = toBool(Settings.Global.getInt(
                mContext.getContentResolver(), settingName, encodeBool(defaultValue)));
        final boolean isEnabled = (mNetworkRequests.get(networkRequest) != null);
        if (enable == isEnabled) {
            return;  // Nothing to do.
        }

        if (enable) {
            handleRegisterNetworkRequest(new NetworkRequestInfo(
                    null, networkRequest, new Binder()));
        } else {
            handleReleaseNetworkRequest(networkRequest, Process.SYSTEM_UID);
        }
    }

    private void handleConfigureAlwaysOnNetworks() {
        handleAlwaysOnNetworkRequest(
                mDefaultMobileDataRequest,Settings.Global.MOBILE_DATA_ALWAYS_ON, true);
        handleAlwaysOnNetworkRequest(mDefaultWifiRequest, Settings.Global.WIFI_ALWAYS_REQUESTED,
                false);
    }

    private void registerSettingsCallbacks() {
        // Watch for global HTTP proxy changes.
        mSettingsObserver.observe(
                Settings.Global.getUriFor(Settings.Global.HTTP_PROXY),
                EVENT_APPLY_GLOBAL_HTTP_PROXY);

        // Watch for whether or not to keep mobile data always on.
        mSettingsObserver.observe(
                Settings.Global.getUriFor(Settings.Global.MOBILE_DATA_ALWAYS_ON),
                EVENT_CONFIGURE_ALWAYS_ON_NETWORKS);

        // Watch for whether or not to keep wifi always on.
        mSettingsObserver.observe(
                Settings.Global.getUriFor(Settings.Global.WIFI_ALWAYS_REQUESTED),
                EVENT_CONFIGURE_ALWAYS_ON_NETWORKS);
    }

    private void registerPrivateDnsSettingsCallbacks() {
        for (Uri uri : DnsManager.getPrivateDnsSettingsUris()) {
            mSettingsObserver.observe(uri, EVENT_PRIVATE_DNS_SETTINGS_CHANGED);
        }
    }

    private synchronized int nextNetworkRequestId() {
        return mNextNetworkRequestId++;
    }

    @VisibleForTesting
    protected int reserveNetId() {
        synchronized (mNetworkForNetId) {
            for (int i = MIN_NET_ID; i <= MAX_NET_ID; i++) {
                int netId = mNextNetId;
                if (++mNextNetId > MAX_NET_ID) mNextNetId = MIN_NET_ID;
                // Make sure NetID unused.  http://b/16815182
                if (!mNetIdInUse.get(netId)) {
                    mNetIdInUse.put(netId, true);
                    return netId;
                }
            }
        }
        throw new IllegalStateException("No free netIds");
    }

    private NetworkState getFilteredNetworkState(int networkType, int uid) {
        if (mLegacyTypeTracker.isTypeSupported(networkType)) {
            final NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
            final NetworkState state;
            if (nai != null) {
                state = nai.getNetworkState();
                state.networkInfo.setType(networkType);
            } else {
                final NetworkInfo info = new NetworkInfo(networkType, 0,
                        getNetworkTypeName(networkType), "");
                info.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
                info.setIsAvailable(true);
                final NetworkCapabilities capabilities = new NetworkCapabilities();
                capabilities.setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING,
                        !info.isRoaming());
                state = new NetworkState(info, new LinkProperties(), capabilities,
                        null, null, null);
            }
            filterNetworkStateForUid(state, uid, false);
            return state;
        } else {
            return NetworkState.EMPTY;
        }
    }

    @VisibleForTesting
    protected NetworkAgentInfo getNetworkAgentInfoForNetwork(Network network) {
        if (network == null) {
            return null;
        }
        return getNetworkAgentInfoForNetId(network.netId);
    }

    private NetworkAgentInfo getNetworkAgentInfoForNetId(int netId) {
        synchronized (mNetworkForNetId) {
            return mNetworkForNetId.get(netId);
        }
    }

    private Network[] getVpnUnderlyingNetworks(int uid) {
        synchronized (mVpns) {
            if (!mLockdownEnabled) {
                int user = UserHandle.getUserId(uid);
                Vpn vpn = mVpns.get(user);
                if (vpn != null && vpn.appliesToUid(uid)) {
                    return vpn.getUnderlyingNetworks();
                }
            }
        }
        return null;
    }

    private NetworkState getUnfilteredActiveNetworkState(int uid) {
        NetworkAgentInfo nai = getDefaultNetwork();

        final Network[] networks = getVpnUnderlyingNetworks(uid);
        if (networks != null) {
            // getUnderlyingNetworks() returns:
            // null => there was no VPN, or the VPN didn't specify anything, so we use the default.
            // empty array => the VPN explicitly said "no default network".
            // non-empty array => the VPN specified one or more default networks; we use the
            //                    first one.
            if (networks.length > 0) {
                nai = getNetworkAgentInfoForNetwork(networks[0]);
            } else {
                nai = null;
            }
        }

        if (nai != null) {
            return nai.getNetworkState();
        } else {
            return NetworkState.EMPTY;
        }
    }

    /**
     * Check if UID should be blocked from using the network with the given LinkProperties.
     */
    private boolean isNetworkWithLinkPropertiesBlocked(LinkProperties lp, int uid,
            boolean ignoreBlocked) {
        // Networks aren't blocked when ignoring blocked status
        if (ignoreBlocked) {
            return false;
        }
        synchronized (mVpns) {
            final Vpn vpn = mVpns.get(UserHandle.getUserId(uid));
            if (vpn != null && vpn.getLockdown() && vpn.isBlockingUid(uid)) {
                return true;
            }
        }
        final String iface = (lp == null ? "" : lp.getInterfaceName());
        return mPolicyManagerInternal.isUidNetworkingBlocked(uid, iface);
    }

    private void maybeLogBlockedNetworkInfo(NetworkInfo ni, int uid) {
        if (ni == null || !LOGD_BLOCKED_NETWORKINFO) {
            return;
        }
        final boolean blocked;
        synchronized (mBlockedAppUids) {
            if (ni.getDetailedState() == DetailedState.BLOCKED && mBlockedAppUids.add(uid)) {
                blocked = true;
            } else if (ni.isConnected() && mBlockedAppUids.remove(uid)) {
                blocked = false;
            } else {
                return;
            }
        }
        String action = blocked ? "BLOCKED" : "UNBLOCKED";
        log(String.format("Returning %s NetworkInfo to uid=%d", action, uid));
        mNetworkInfoBlockingLogs.log(action + " " + uid);
    }

    private void maybeLogBlockedStatusChanged(NetworkRequestInfo nri, Network net,
            boolean blocked) {
        if (nri == null || net == null || !LOGD_BLOCKED_NETWORKINFO) {
            return;
        }
        String action = blocked ? "BLOCKED" : "UNBLOCKED";
        log(String.format("Blocked status changed to %s for %d(%d) on netId %d", blocked,
                nri.mUid, nri.request.requestId, net.netId));
        mNetworkInfoBlockingLogs.log(action + " " + nri.mUid);
    }

    /**
     * Apply any relevant filters to {@link NetworkState} for the given UID. For
     * example, this may mark the network as {@link DetailedState#BLOCKED} based
     * on {@link #isNetworkWithLinkPropertiesBlocked}.
     */
    private void filterNetworkStateForUid(NetworkState state, int uid, boolean ignoreBlocked) {
        if (state == null || state.networkInfo == null || state.linkProperties == null) return;

        if (isNetworkWithLinkPropertiesBlocked(state.linkProperties, uid, ignoreBlocked)) {
            state.networkInfo.setDetailedState(DetailedState.BLOCKED, null, null);
        }
        synchronized (mVpns) {
            if (mLockdownTracker != null) {
                mLockdownTracker.augmentNetworkInfo(state.networkInfo);
            }
        }
    }

    /**
     * Return NetworkInfo for the active (i.e., connected) network interface.
     * It is assumed that at most one network is active at a time. If more
     * than one is active, it is indeterminate which will be returned.
     * @return the info for the active network, or {@code null} if none is
     * active
     */
    @Override
    public NetworkInfo getActiveNetworkInfo() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final NetworkState state = getUnfilteredActiveNetworkState(uid);
        filterNetworkStateForUid(state, uid, false);
        maybeLogBlockedNetworkInfo(state.networkInfo, uid);
        return state.networkInfo;
    }

    @Override
    public Network getActiveNetwork() {
        enforceAccessPermission();
        return getActiveNetworkForUidInternal(Binder.getCallingUid(), false);
    }

    @Override
    public Network getActiveNetworkForUid(int uid, boolean ignoreBlocked) {
        enforceConnectivityInternalPermission();
        return getActiveNetworkForUidInternal(uid, ignoreBlocked);
    }

    private Network getActiveNetworkForUidInternal(final int uid, boolean ignoreBlocked) {
        final int user = UserHandle.getUserId(uid);
        int vpnNetId = NETID_UNSET;
        synchronized (mVpns) {
            final Vpn vpn = mVpns.get(user);
            // TODO : now that capabilities contain the UID, the appliesToUid test should
            // be removed as the satisfying test below should be enough.
            if (vpn != null && vpn.appliesToUid(uid)) vpnNetId = vpn.getNetId();
        }
        NetworkAgentInfo nai;
        if (vpnNetId != NETID_UNSET) {
            nai = getNetworkAgentInfoForNetId(vpnNetId);
            if (nai != null) {
                final NetworkCapabilities requiredCaps =
                    createDefaultNetworkCapabilitiesForUid(uid);
                if (requiredCaps.satisfiedByNetworkCapabilities(nai.networkCapabilities)) {
                    return nai.network;
                }
            }
        }
        nai = getDefaultNetwork();
        if (nai != null
                && isNetworkWithLinkPropertiesBlocked(nai.linkProperties, uid, ignoreBlocked)) {
            nai = null;
        }
        return nai != null ? nai.network : null;
    }

    // Public because it's used by mLockdownTracker.
    public NetworkInfo getActiveNetworkInfoUnfiltered() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        return state.networkInfo;
    }

    @Override
    public NetworkInfo getActiveNetworkInfoForUid(int uid, boolean ignoreBlocked) {
        enforceConnectivityInternalPermission();
        final NetworkState state = getUnfilteredActiveNetworkState(uid);
        filterNetworkStateForUid(state, uid, ignoreBlocked);
        return state.networkInfo;
    }

    @Override
    public NetworkInfo getNetworkInfo(int networkType) {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        if (getVpnUnderlyingNetworks(uid) != null) {
            // A VPN is active, so we may need to return one of its underlying networks. This
            // information is not available in LegacyTypeTracker, so we have to get it from
            // getUnfilteredActiveNetworkState.
            final NetworkState state = getUnfilteredActiveNetworkState(uid);
            if (state.networkInfo != null && state.networkInfo.getType() == networkType) {
                filterNetworkStateForUid(state, uid, false);
                return state.networkInfo;
            }
        }
        final NetworkState state = getFilteredNetworkState(networkType, uid);
        return state.networkInfo;
    }

    @Override
    public NetworkInfo getNetworkInfoForUid(Network network, int uid, boolean ignoreBlocked) {
        enforceAccessPermission();
        final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null) {
            final NetworkState state = nai.getNetworkState();
            filterNetworkStateForUid(state, uid, ignoreBlocked);
            return state.networkInfo;
        } else {
            return null;
        }
    }

    @Override
    public NetworkInfo[] getAllNetworkInfo() {
        enforceAccessPermission();
        final ArrayList<NetworkInfo> result = Lists.newArrayList();
        for (int networkType = 0; networkType <= ConnectivityManager.MAX_NETWORK_TYPE;
                networkType++) {
            NetworkInfo info = getNetworkInfo(networkType);
            if (info != null) {
                result.add(info);
            }
        }
        return result.toArray(new NetworkInfo[result.size()]);
    }

    @Override
    public Network getNetworkForType(int networkType) {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        NetworkState state = getFilteredNetworkState(networkType, uid);
        if (!isNetworkWithLinkPropertiesBlocked(state.linkProperties, uid, false)) {
            return state.network;
        }
        return null;
    }

    @Override
    public Network[] getAllNetworks() {
        enforceAccessPermission();
        synchronized (mNetworkForNetId) {
            final Network[] result = new Network[mNetworkForNetId.size()];
            for (int i = 0; i < mNetworkForNetId.size(); i++) {
                result[i] = mNetworkForNetId.valueAt(i).network;
            }
            return result;
        }
    }

    @Override
    public NetworkCapabilities[] getDefaultNetworkCapabilitiesForUser(int userId) {
        // The basic principle is: if an app's traffic could possibly go over a
        // network, without the app doing anything multinetwork-specific,
        // (hence, by "default"), then include that network's capabilities in
        // the array.
        //
        // In the normal case, app traffic only goes over the system's default
        // network connection, so that's the only network returned.
        //
        // With a VPN in force, some app traffic may go into the VPN, and thus
        // over whatever underlying networks the VPN specifies, while other app
        // traffic may go over the system default network (e.g.: a split-tunnel
        // VPN, or an app disallowed by the VPN), so the set of networks
        // returned includes the VPN's underlying networks and the system
        // default.
        enforceAccessPermission();

        HashMap<Network, NetworkCapabilities> result = new HashMap<>();

        NetworkAgentInfo nai = getDefaultNetwork();
        NetworkCapabilities nc = getNetworkCapabilitiesInternal(nai);
        if (nc != null) {
            result.put(nai.network, nc);
        }

        synchronized (mVpns) {
            if (!mLockdownEnabled) {
                Vpn vpn = mVpns.get(userId);
                if (vpn != null) {
                    Network[] networks = vpn.getUnderlyingNetworks();
                    if (networks != null) {
                        for (Network network : networks) {
                            nai = getNetworkAgentInfoForNetwork(network);
                            nc = getNetworkCapabilitiesInternal(nai);
                            if (nc != null) {
                                result.put(network, nc);
                            }
                        }
                    }
                }
            }
        }

        NetworkCapabilities[] out = new NetworkCapabilities[result.size()];
        out = result.values().toArray(out);
        return out;
    }

    @Override
    public boolean isNetworkSupported(int networkType) {
        enforceAccessPermission();
        return mLegacyTypeTracker.isTypeSupported(networkType);
    }

    /**
     * Return LinkProperties for the active (i.e., connected) default
     * network interface.  It is assumed that at most one default network
     * is active at a time. If more than one is active, it is indeterminate
     * which will be returned.
     * @return the ip properties for the active network, or {@code null} if
     * none is active
     */
    @Override
    public LinkProperties getActiveLinkProperties() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        NetworkState state = getUnfilteredActiveNetworkState(uid);
        return state.linkProperties;
    }

    @Override
    public LinkProperties getLinkPropertiesForType(int networkType) {
        enforceAccessPermission();
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai != null) {
            synchronized (nai) {
                return new LinkProperties(nai.linkProperties);
            }
        }
        return null;
    }

    // TODO - this should be ALL networks
    @Override
    public LinkProperties getLinkProperties(Network network) {
        enforceAccessPermission();
        return getLinkProperties(getNetworkAgentInfoForNetwork(network));
    }

    private LinkProperties getLinkProperties(NetworkAgentInfo nai) {
        if (nai == null) {
            return null;
        }
        synchronized (nai) {
            return new LinkProperties(nai.linkProperties);
        }
    }

    private NetworkCapabilities getNetworkCapabilitiesInternal(NetworkAgentInfo nai) {
        if (nai != null) {
            synchronized (nai) {
                if (nai.networkCapabilities != null) {
                    return networkCapabilitiesRestrictedForCallerPermissions(
                            nai.networkCapabilities,
                            Binder.getCallingPid(), Binder.getCallingUid());
                }
            }
        }
        return null;
    }

    @Override
    public NetworkCapabilities getNetworkCapabilities(Network network) {
        enforceAccessPermission();
        return getNetworkCapabilitiesInternal(getNetworkAgentInfoForNetwork(network));
    }

    private NetworkCapabilities networkCapabilitiesRestrictedForCallerPermissions(
            NetworkCapabilities nc, int callerPid, int callerUid) {
        final NetworkCapabilities newNc = new NetworkCapabilities(nc);
        if (!checkSettingsPermission(callerPid, callerUid)) {
            newNc.setUids(null);
            newNc.setSSID(null);
        }
        if (newNc.getNetworkSpecifier() != null) {
            newNc.setNetworkSpecifier(newNc.getNetworkSpecifier().redact());
        }
        return newNc;
    }

    private void restrictRequestUidsForCaller(NetworkCapabilities nc) {
        if (!checkSettingsPermission()) {
            nc.setSingleUid(Binder.getCallingUid());
        }
    }

    private void restrictBackgroundRequestForCaller(NetworkCapabilities nc) {
        if (!mPermissionMonitor.hasUseBackgroundNetworksPermission(Binder.getCallingUid())) {
            nc.addCapability(NET_CAPABILITY_FOREGROUND);
        }
    }

    @Override
    public NetworkState[] getAllNetworkState() {
        // Require internal since we're handing out IMSI details
        enforceConnectivityInternalPermission();

        final ArrayList<NetworkState> result = Lists.newArrayList();
        for (Network network : getAllNetworks()) {
            final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
            if (nai != null) {
                // TODO (b/73321673) : NetworkState contains a copy of the
                // NetworkCapabilities, which may contain UIDs of apps to which the
                // network applies. Should the UIDs be cleared so as not to leak or
                // interfere ?
                result.add(nai.getNetworkState());
            }
        }
        return result.toArray(new NetworkState[result.size()]);
    }

    @Override
    @Deprecated
    public NetworkQuotaInfo getActiveNetworkQuotaInfo() {
        Log.w(TAG, "Shame on UID " + Binder.getCallingUid()
                + " for calling the hidden API getNetworkQuotaInfo(). Shame!");
        return new NetworkQuotaInfo();
    }

    @Override
    public boolean isActiveNetworkMetered() {
        enforceAccessPermission();

        final int uid = Binder.getCallingUid();
        final NetworkCapabilities caps = getUnfilteredActiveNetworkState(uid).networkCapabilities;
        if (caps != null) {
            return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } else {
            // Always return the most conservative value
            return true;
        }
    }

    private INetworkManagementEventObserver mDataActivityObserver = new BaseNetworkObserver() {
        @Override
        public void interfaceClassDataActivityChanged(String label, boolean active, long tsNanos) {
            int deviceType = Integer.parseInt(label);
            sendDataActivityBroadcast(deviceType, active, tsNanos);
        }
    };

    /**
     * Ensures that the system cannot call a particular method.
     */
    private boolean disallowedBecauseSystemCaller() {
        // TODO: start throwing a SecurityException when GnssLocationProvider stops calling
        // requestRouteToHost.
        if (isSystem(Binder.getCallingUid())) {
            log("This method exists only for app backwards compatibility"
                    + " and must not be called by system services.");
            return true;
        }
        return false;
    }

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the
     * specified host is to be routed
     * @param hostAddress the IP address of the host to which the route is
     * desired
     * @return {@code true} on success, {@code false} on failure
     */
    @Override
    public boolean requestRouteToHostAddress(int networkType, byte[] hostAddress) {
        if (disallowedBecauseSystemCaller()) {
            return false;
        }
        enforceChangePermission();
        if (mProtectedNetworks.contains(networkType)) {
            enforceConnectivityInternalPermission();
        }

        InetAddress addr;
        try {
            addr = InetAddress.getByAddress(hostAddress);
        } catch (UnknownHostException e) {
            if (DBG) log("requestRouteToHostAddress got " + e.toString());
            return false;
        }

        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            if (DBG) log("requestRouteToHostAddress on invalid network: " + networkType);
            return false;
        }

        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai == null) {
            if (mLegacyTypeTracker.isTypeSupported(networkType) == false) {
                if (DBG) log("requestRouteToHostAddress on unsupported network: " + networkType);
            } else {
                if (DBG) log("requestRouteToHostAddress on down network: " + networkType);
            }
            return false;
        }

        DetailedState netState;
        synchronized (nai) {
            netState = nai.networkInfo.getDetailedState();
        }

        if (netState != DetailedState.CONNECTED && netState != DetailedState.CAPTIVE_PORTAL_CHECK) {
            if (VDBG) {
                log("requestRouteToHostAddress on down network "
                        + "(" + networkType + ") - dropped"
                        + " netState=" + netState);
            }
            return false;
        }

        final int uid = Binder.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            LinkProperties lp;
            int netId;
            synchronized (nai) {
                lp = nai.linkProperties;
                netId = nai.network.netId;
            }
            boolean ok = addLegacyRouteToHost(lp, addr, netId, uid);
            if (DBG) log("requestRouteToHostAddress ok=" + ok);
            return ok;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean addLegacyRouteToHost(LinkProperties lp, InetAddress addr, int netId, int uid) {
        RouteInfo bestRoute = RouteInfo.selectBestRoute(lp.getAllRoutes(), addr);
        if (bestRoute == null) {
            bestRoute = RouteInfo.makeHostRoute(addr, lp.getInterfaceName());
        } else {
            String iface = bestRoute.getInterface();
            if (bestRoute.getGateway().equals(addr)) {
                // if there is no better route, add the implied hostroute for our gateway
                bestRoute = RouteInfo.makeHostRoute(addr, iface);
            } else {
                // if we will connect to this through another route, add a direct route
                // to it's gateway
                bestRoute = RouteInfo.makeHostRoute(addr, bestRoute.getGateway(), iface);
            }
        }
        if (DBG) log("Adding legacy route " + bestRoute +
                " for UID/PID " + uid + "/" + Binder.getCallingPid());
        try {
            mNMS.addLegacyRouteForNetId(netId, bestRoute, uid);
        } catch (Exception e) {
            // never crash - catch them all
            if (DBG) loge("Exception trying to add a route: " + e);
            return false;
        }
        return true;
    }

    @VisibleForTesting
    protected final INetdEventCallback mNetdEventCallback = new BaseNetdEventCallback() {
        @Override
        public void onPrivateDnsValidationEvent(int netId, String ipAddress,
                String hostname, boolean validated) {
            try {
                mHandler.sendMessage(mHandler.obtainMessage(
                        EVENT_PRIVATE_DNS_VALIDATION_UPDATE,
                        new PrivateDnsValidationUpdate(netId,
                                InetAddress.parseNumericAddress(ipAddress),
                                hostname, validated)));
            } catch (IllegalArgumentException e) {
                loge("Error parsing ip address in validation event");
            }
        }

        @Override
        public void onDnsEvent(int netId, int eventType, int returnCode, String hostname,
                String[] ipAddresses, int ipAddressesCount, long timestamp, int uid) {
            NetworkAgentInfo nai = getNetworkAgentInfoForNetId(netId);
            // Netd event only allow registrants from system. Each NetworkMonitor thread is under
            // the caller thread of registerNetworkAgent. Thus, it's not allowed to register netd
            // event callback for certain nai. e.g. cellular. Register here to pass to
            // NetworkMonitor instead.
            // TODO: Move the Dns Event to NetworkMonitor. Use Binder.clearCallingIdentity() in
            // registerNetworkAgent to have NetworkMonitor created with system process as design
            // expectation. Also, NetdEventListenerService only allow one callback from each
            // caller type. Need to re-factor NetdEventListenerService to allow multiple
            // NetworkMonitor registrants.
            if (nai != null && nai.satisfies(mDefaultRequest)) {
                try {
                    nai.networkMonitor().notifyDnsResponse(returnCode);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }
    };

    @VisibleForTesting
    protected void registerNetdEventCallback() {
        final IIpConnectivityMetrics ipConnectivityMetrics =
                IIpConnectivityMetrics.Stub.asInterface(
                        ServiceManager.getService(IpConnectivityLog.SERVICE_NAME));
        if (ipConnectivityMetrics == null) {
            Slog.wtf(TAG, "Missing IIpConnectivityMetrics");
            return;
        }

        try {
            ipConnectivityMetrics.addNetdEventCallback(
                    INetdEventCallback.CALLBACK_CALLER_CONNECTIVITY_SERVICE,
                    mNetdEventCallback);
        } catch (Exception e) {
            loge("Error registering netd callback: " + e);
        }
    }

    private final INetworkPolicyListener mPolicyListener = new NetworkPolicyManager.Listener() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_UID_RULES_CHANGED, uid, uidRules));
        }
        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            // caller is NPMS, since we only register with them
            if (LOGD_BLOCKED_NETWORKINFO) {
                log("onRestrictBackgroundChanged(restrictBackground=" + restrictBackground + ")");
            }
            mHandler.sendMessage(mHandler.obtainMessage(
                    EVENT_DATA_SAVER_CHANGED, restrictBackground ? 1 : 0, 0));

            // TODO: relocate this specific callback in Tethering.
            if (restrictBackground) {
                log("onRestrictBackgroundChanged(true): disabling tethering");
                mTethering.untetherAll();
            }
        }
    };

    void handleUidRulesChanged(int uid, int newRules) {
        // skip update when we've already applied rules
        final int oldRules = mUidRules.get(uid, RULE_NONE);
        if (oldRules == newRules) return;

        maybeNotifyNetworkBlockedForNewUidRules(uid, newRules);

        if (newRules == RULE_NONE) {
            mUidRules.delete(uid);
        } else {
            mUidRules.put(uid, newRules);
        }
    }

    void handleRestrictBackgroundChanged(boolean restrictBackground) {
        if (mRestrictBackground == restrictBackground) return;

        for (final NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
            final boolean curMetered = nai.networkCapabilities.isMetered();
            maybeNotifyNetworkBlocked(nai, curMetered, curMetered, mRestrictBackground,
                    restrictBackground);
        }

        mRestrictBackground = restrictBackground;
    }

    private boolean isUidNetworkingWithVpnBlocked(int uid, int uidRules, boolean isNetworkMetered,
            boolean isBackgroundRestricted) {
        synchronized (mVpns) {
            final Vpn vpn = mVpns.get(UserHandle.getUserId(uid));
            // Because the return value of this function depends on the list of UIDs the
            // always-on VPN blocks when in lockdown mode, when the always-on VPN changes that
            // list all state depending on the return value of this function has to be recomputed.
            // TODO: add a trigger when the always-on VPN sets its blocked UIDs to reevaluate and
            // send the necessary onBlockedStatusChanged callbacks.
            if (vpn != null && vpn.getLockdown() && vpn.isBlockingUid(uid)) {
                return true;
            }
        }

        return mPolicyManagerInternal.isUidNetworkingBlocked(uid, uidRules,
                isNetworkMetered, isBackgroundRestricted);
    }

    /**
     * Require that the caller is either in the same user or has appropriate permission to interact
     * across users.
     *
     * @param userId Target user for whatever operation the current IPC is supposed to perform.
     */
    private void enforceCrossUserPermission(int userId) {
        if (userId == UserHandle.getCallingUserId()) {
            // Not a cross-user call.
            return;
        }
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                "ConnectivityService");
    }

    private void enforceAnyPermissionOf(String... permissions) {
        for (String permission : permissions) {
            if (mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED) {
                return;
            }
        }
        throw new SecurityException(
            "Requires one of the following permissions: " + String.join(", ", permissions) + ".");
    }

    private void enforceInternetPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.INTERNET,
                "ConnectivityService");
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceChangePermission() {
        ConnectivityManager.enforceChangePermission(mContext);
    }

    private void enforceSettingsPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.NETWORK_SETTINGS,
                "ConnectivityService");
    }

    private boolean checkSettingsPermission() {
        return PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.NETWORK_SETTINGS);
    }

    private boolean checkSettingsPermission(int pid, int uid) {
        return PERMISSION_GRANTED == mContext.checkPermission(
                android.Manifest.permission.NETWORK_SETTINGS, pid, uid);
    }

    private void enforceTetherAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    private void enforceControlAlwaysOnVpnPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_ALWAYS_ON_VPN,
                "ConnectivityService");
    }

    private void enforceNetworkStackSettingsOrSetup() {
        enforceAnyPermissionOf(
            android.Manifest.permission.NETWORK_SETTINGS,
            android.Manifest.permission.NETWORK_SETUP_WIZARD,
            android.Manifest.permission.NETWORK_STACK);
    }

    private void enforceNetworkStackPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.NETWORK_STACK,
                "ConnectivityService");
    }

    private boolean checkNetworkStackPermission() {
        return PERMISSION_GRANTED == mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.NETWORK_STACK);
    }

    private void enforceConnectivityRestrictedNetworksPermission() {
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS,
                    "ConnectivityService");
            return;
        } catch (SecurityException e) { /* fallback to ConnectivityInternalPermission */ }
        enforceConnectivityInternalPermission();
    }

    private void enforceKeepalivePermission() {
        mContext.enforceCallingOrSelfPermission(KeepaliveTracker.PERMISSION, "ConnectivityService");
    }

    // Public because it's used by mLockdownTracker.
    public void sendConnectedBroadcast(NetworkInfo info) {
        enforceConnectivityInternalPermission();
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION);
    }

    private void sendInetConditionBroadcast(NetworkInfo info) {
        sendGeneralBroadcast(info, ConnectivityManager.INET_CONDITION_ACTION);
    }

    private Intent makeGeneralIntent(NetworkInfo info, String bcastType) {
        synchronized (mVpns) {
            if (mLockdownTracker != null) {
                info = new NetworkInfo(info);
                mLockdownTracker.augmentNetworkInfo(info);
            }
        }

        Intent intent = new Intent(bcastType);
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, new NetworkInfo(info));
        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, info.getType());
        if (info.isFailover()) {
            intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
            info.setFailover(false);
        }
        if (info.getReason() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
        }
        if (info.getExtraInfo() != null) {
            intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO,
                    info.getExtraInfo());
        }
        intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION, mDefaultInetConditionPublished);
        return intent;
    }

    private void sendGeneralBroadcast(NetworkInfo info, String bcastType) {
        sendStickyBroadcast(makeGeneralIntent(info, bcastType));
    }

    private void sendDataActivityBroadcast(int deviceType, boolean active, long tsNanos) {
        Intent intent = new Intent(ConnectivityManager.ACTION_DATA_ACTIVITY_CHANGE);
        intent.putExtra(ConnectivityManager.EXTRA_DEVICE_TYPE, deviceType);
        intent.putExtra(ConnectivityManager.EXTRA_IS_ACTIVE, active);
        intent.putExtra(ConnectivityManager.EXTRA_REALTIME_NS, tsNanos);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL,
                    RECEIVE_DATA_ACTIVITY_CHANGE, null, null, 0, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void sendStickyBroadcast(Intent intent) {
        synchronized (this) {
            if (!mSystemReady
                    && intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                mInitialBroadcast = new Intent(intent);
            }
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (VDBG) {
                log("sendStickyBroadcast: action=" + intent.getAction());
            }

            Bundle options = null;
            final long ident = Binder.clearCallingIdentity();
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
                final NetworkInfo ni = intent.getParcelableExtra(
                        ConnectivityManager.EXTRA_NETWORK_INFO);
                if (ni.getType() == ConnectivityManager.TYPE_MOBILE_SUPL) {
                    intent.setAction(ConnectivityManager.CONNECTIVITY_ACTION_SUPL);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                } else {
                    BroadcastOptions opts = BroadcastOptions.makeBasic();
                    opts.setMaxManifestReceiverApiLevel(Build.VERSION_CODES.M);
                    options = opts.toBundle();
                }
                final IBatteryStats bs = BatteryStatsService.getService();
                try {
                    bs.noteConnectivityChanged(intent.getIntExtra(
                            ConnectivityManager.EXTRA_NETWORK_TYPE, ConnectivityManager.TYPE_NONE),
                            ni.getState().toString());
                } catch (RemoteException e) {
                }
                intent.addFlags(Intent.FLAG_RECEIVER_VISIBLE_TO_INSTANT_APPS);
            }
            try {
                mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL, options);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    void systemReady() {
        mProxyTracker.loadGlobalProxy();
        registerNetdEventCallback();
        mTethering.systemReady();

        synchronized (this) {
            mSystemReady = true;
            if (mInitialBroadcast != null) {
                mContext.sendStickyBroadcastAsUser(mInitialBroadcast, UserHandle.ALL);
                mInitialBroadcast = null;
            }
        }

        // Try bringing up tracker, but KeyStore won't be ready yet for secondary users so wait
        // for user to unlock device too.
        updateLockdownVpn();

        // Create network requests for always-on networks.
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_CONFIGURE_ALWAYS_ON_NETWORKS));

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SYSTEM_READY));

        mPermissionMonitor.startMonitoring();
    }

    /**
     * Setup data activity tracking for the given network.
     *
     * Every {@code setupDataActivityTracking} should be paired with a
     * {@link #removeDataActivityTracking} for cleanup.
     */
    private void setupDataActivityTracking(NetworkAgentInfo networkAgent) {
        final String iface = networkAgent.linkProperties.getInterfaceName();

        final int timeout;
        int type = ConnectivityManager.TYPE_NONE;

        if (networkAgent.networkCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR)) {
            timeout = Settings.Global.getInt(mContext.getContentResolver(),
                                             Settings.Global.DATA_ACTIVITY_TIMEOUT_MOBILE,
                                             10);
            type = ConnectivityManager.TYPE_MOBILE;
        } else if (networkAgent.networkCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI)) {
            timeout = Settings.Global.getInt(mContext.getContentResolver(),
                                             Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI,
                                             15);
            type = ConnectivityManager.TYPE_WIFI;
        } else {
            // do not track any other networks
            timeout = 0;
        }

        if (timeout > 0 && iface != null && type != ConnectivityManager.TYPE_NONE) {
            try {
                mNMS.addIdleTimer(iface, timeout, type);
            } catch (Exception e) {
                // You shall not crash!
                loge("Exception in setupDataActivityTracking " + e);
            }
        }
    }

    /**
     * Remove data activity tracking when network disconnects.
     */
    private void removeDataActivityTracking(NetworkAgentInfo networkAgent) {
        final String iface = networkAgent.linkProperties.getInterfaceName();
        final NetworkCapabilities caps = networkAgent.networkCapabilities;

        if (iface != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                              caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) {
            try {
                // the call fails silently if no idle timer setup for this interface
                mNMS.removeIdleTimer(iface);
            } catch (Exception e) {
                loge("Exception in removeDataActivityTracking " + e);
            }
        }
    }

    /**
     * Update data activity tracking when network state is updated.
     */
    private void updateDataActivityTracking(NetworkAgentInfo newNetwork,
            NetworkAgentInfo oldNetwork) {
        if (newNetwork != null) {
            setupDataActivityTracking(newNetwork);
        }
        if (oldNetwork != null) {
            removeDataActivityTracking(oldNetwork);
        }
    }
    /**
     * Reads the network specific MTU size from resources.
     * and set it on it's iface.
     */
    private void updateMtu(LinkProperties newLp, LinkProperties oldLp) {
        final String iface = newLp.getInterfaceName();
        final int mtu = newLp.getMtu();
        if (oldLp == null && mtu == 0) {
            // Silently ignore unset MTU value.
            return;
        }
        if (oldLp != null && newLp.isIdenticalMtu(oldLp)) {
            if (VDBG) log("identical MTU - not setting");
            return;
        }
        if (LinkProperties.isValidMtu(mtu, newLp.hasGlobalIPv6Address()) == false) {
            if (mtu != 0) loge("Unexpected mtu value: " + mtu + ", " + iface);
            return;
        }

        // Cannot set MTU without interface name
        if (TextUtils.isEmpty(iface)) {
            loge("Setting MTU size with null iface.");
            return;
        }

        try {
            if (VDBG || DDBG) log("Setting MTU size: " + iface + ", " + mtu);
            mNMS.setMtu(iface, mtu);
        } catch (Exception e) {
            Slog.e(TAG, "exception in setMtu()" + e);
        }
    }

    @VisibleForTesting
    protected static final String DEFAULT_TCP_BUFFER_SIZES = "4096,87380,110208,4096,16384,110208";
    private static final String DEFAULT_TCP_RWND_KEY = "net.tcp.default_init_rwnd";

    // Overridden for testing purposes to avoid writing to SystemProperties.
    @VisibleForTesting
    protected MockableSystemProperties getSystemProperties() {
        return new MockableSystemProperties();
    }

    private void updateTcpBufferSizes(String tcpBufferSizes) {
        String[] values = null;
        if (tcpBufferSizes != null) {
            values = tcpBufferSizes.split(",");
        }

        if (values == null || values.length != 6) {
            if (DBG) log("Invalid tcpBufferSizes string: " + tcpBufferSizes +", using defaults");
            tcpBufferSizes = DEFAULT_TCP_BUFFER_SIZES;
            values = tcpBufferSizes.split(",");
        }

        if (tcpBufferSizes.equals(mCurrentTcpBufferSizes)) return;

        try {
            if (VDBG || DDBG) Slog.d(TAG, "Setting tx/rx TCP buffers to " + tcpBufferSizes);

            String rmemValues = String.join(" ", values[0], values[1], values[2]);
            String wmemValues = String.join(" ", values[3], values[4], values[5]);
            mNetd.setTcpRWmemorySize(rmemValues, wmemValues);
            mCurrentTcpBufferSizes = tcpBufferSizes;
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Can't set TCP buffer sizes:" + e);
        }

        Integer rwndValue = Settings.Global.getInt(mContext.getContentResolver(),
            Settings.Global.TCP_DEFAULT_INIT_RWND,
                    mSystemProperties.getInt("net.tcp.default_init_rwnd", 0));
        final String sysctlKey = "sys.sysctl.tcp_def_init_rwnd";
        if (rwndValue != 0) {
            mSystemProperties.set(sysctlKey, rwndValue.toString());
        }
    }

    @Override
    public int getRestoreDefaultNetworkDelay(int networkType) {
        String restoreDefaultNetworkDelayStr = mSystemProperties.get(
                NETWORK_RESTORE_DELAY_PROP_NAME);
        if(restoreDefaultNetworkDelayStr != null &&
                restoreDefaultNetworkDelayStr.length() != 0) {
            try {
                return Integer.parseInt(restoreDefaultNetworkDelayStr);
            } catch (NumberFormatException e) {
            }
        }
        // if the system property isn't set, use the value for the apn type
        int ret = RESTORE_DEFAULT_NETWORK_DELAY;

        if ((networkType <= ConnectivityManager.MAX_NETWORK_TYPE) &&
                (mNetConfigs[networkType] != null)) {
            ret = mNetConfigs[networkType].restoreTime;
        }
        return ret;
    }

    private void dumpNetworkDiagnostics(IndentingPrintWriter pw) {
        final List<NetworkDiagnostics> netDiags = new ArrayList<NetworkDiagnostics>();
        final long DIAG_TIME_MS = 5000;
        for (NetworkAgentInfo nai : networksSortedById()) {
            // Start gathering diagnostic information.
            netDiags.add(new NetworkDiagnostics(
                    nai.network,
                    new LinkProperties(nai.linkProperties),  // Must be a copy.
                    DIAG_TIME_MS));
        }

        for (NetworkDiagnostics netDiag : netDiags) {
            pw.println();
            netDiag.waitForMeasurements();
            netDiag.dump(pw);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        PriorityDump.dump(mPriorityDumper, fd, writer, args);
    }

    private void doDump(FileDescriptor fd, PrintWriter writer, String[] args, boolean asProto) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        if (asProto) return;

        if (ArrayUtils.contains(args, DIAG_ARG)) {
            dumpNetworkDiagnostics(pw);
            return;
        } else if (ArrayUtils.contains(args, TETHERING_ARG)) {
            mTethering.dump(fd, pw, args);
            return;
        } else if (ArrayUtils.contains(args, NETWORK_ARG)) {
            dumpNetworks(pw);
            return;
        } else if (ArrayUtils.contains(args, REQUEST_ARG)) {
            dumpNetworkRequests(pw);
            return;
        }

        pw.print("NetworkFactories for:");
        for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
            pw.print(" " + nfi.name);
        }
        pw.println();
        pw.println();

        final NetworkAgentInfo defaultNai = getDefaultNetwork();
        pw.print("Active default network: ");
        if (defaultNai == null) {
            pw.println("none");
        } else {
            pw.println(defaultNai.network.netId);
        }
        pw.println();

        pw.println("Current Networks:");
        pw.increaseIndent();
        dumpNetworks(pw);
        pw.decreaseIndent();
        pw.println();

        pw.print("Restrict background: ");
        pw.println(mRestrictBackground);
        pw.println();

        pw.println("Status for known UIDs:");
        pw.increaseIndent();
        final int size = mUidRules.size();
        for (int i = 0; i < size; i++) {
            // Don't crash if the array is modified while dumping in bugreports.
            try {
                final int uid = mUidRules.keyAt(i);
                final int uidRules = mUidRules.get(uid, RULE_NONE);
                pw.println("UID=" + uid + " rules=" + uidRulesToString(uidRules));
            } catch (ArrayIndexOutOfBoundsException e) {
                pw.println("  ArrayIndexOutOfBoundsException");
            } catch (ConcurrentModificationException e) {
                pw.println("  ConcurrentModificationException");
            }
        }
        pw.println();
        pw.decreaseIndent();

        pw.println("Network Requests:");
        pw.increaseIndent();
        dumpNetworkRequests(pw);
        pw.decreaseIndent();
        pw.println();

        mLegacyTypeTracker.dump(pw);

        pw.println();
        mTethering.dump(fd, pw, args);

        pw.println();
        mKeepaliveTracker.dump(pw);

        pw.println();
        dumpAvoidBadWifiSettings(pw);

        pw.println();
        mMultipathPolicyTracker.dump(pw);

        if (ArrayUtils.contains(args, SHORT_ARG) == false) {
            pw.println();
            pw.println("mNetworkRequestInfoLogs (most recent first):");
            pw.increaseIndent();
            mNetworkRequestInfoLogs.reverseDump(fd, pw, args);
            pw.decreaseIndent();

            pw.println();
            pw.println("mNetworkInfoBlockingLogs (most recent first):");
            pw.increaseIndent();
            mNetworkInfoBlockingLogs.reverseDump(fd, pw, args);
            pw.decreaseIndent();

            pw.println();
            pw.println("NetTransition WakeLock activity (most recent first):");
            pw.increaseIndent();
            pw.println("total acquisitions: " + mTotalWakelockAcquisitions);
            pw.println("total releases: " + mTotalWakelockReleases);
            pw.println("cumulative duration: " + (mTotalWakelockDurationMs / 1000) + "s");
            pw.println("longest duration: " + (mMaxWakelockDurationMs / 1000) + "s");
            if (mTotalWakelockAcquisitions > mTotalWakelockReleases) {
                long duration = SystemClock.elapsedRealtime() - mLastWakeLockAcquireTimestamp;
                pw.println("currently holding WakeLock for: " + (duration / 1000) + "s");
            }
            mWakelockLogs.reverseDump(fd, pw, args);

            pw.println();
            pw.println("bandwidth update requests (by uid):");
            pw.increaseIndent();
            synchronized (mBandwidthRequests) {
                for (int i = 0; i < mBandwidthRequests.size(); i++) {
                    pw.println("[" + mBandwidthRequests.keyAt(i)
                            + "]: " + mBandwidthRequests.valueAt(i));
                }
            }
            pw.decreaseIndent();

            pw.decreaseIndent();
        }
    }

    private void dumpNetworks(IndentingPrintWriter pw) {
        for (NetworkAgentInfo nai : networksSortedById()) {
            pw.println(nai.toString());
            pw.increaseIndent();
            pw.println(String.format(
                    "Requests: REQUEST:%d LISTEN:%d BACKGROUND_REQUEST:%d total:%d",
                    nai.numForegroundNetworkRequests(),
                    nai.numNetworkRequests() - nai.numRequestNetworkRequests(),
                    nai.numBackgroundNetworkRequests(),
                    nai.numNetworkRequests()));
            pw.increaseIndent();
            for (int i = 0; i < nai.numNetworkRequests(); i++) {
                pw.println(nai.requestAt(i).toString());
            }
            pw.decreaseIndent();
            pw.println("Lingered:");
            pw.increaseIndent();
            nai.dumpLingerTimers(pw);
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
    }

    private void dumpNetworkRequests(IndentingPrintWriter pw) {
        for (NetworkRequestInfo nri : requestsSortedById()) {
            pw.println(nri.toString());
        }
    }

    /**
     * Return an array of all current NetworkAgentInfos sorted by network id.
     */
    private NetworkAgentInfo[] networksSortedById() {
        NetworkAgentInfo[] networks = new NetworkAgentInfo[0];
        networks = mNetworkAgentInfos.values().toArray(networks);
        Arrays.sort(networks, Comparator.comparingInt(nai -> nai.network.netId));
        return networks;
    }

    /**
     * Return an array of all current NetworkRequest sorted by request id.
     */
    private NetworkRequestInfo[] requestsSortedById() {
        NetworkRequestInfo[] requests = new NetworkRequestInfo[0];
        requests = mNetworkRequests.values().toArray(requests);
        Arrays.sort(requests, Comparator.comparingInt(nri -> nri.request.requestId));
        return requests;
    }

    private boolean isLiveNetworkAgent(NetworkAgentInfo nai, int what) {
        if (nai.network == null) return false;
        final NetworkAgentInfo officialNai = getNetworkAgentInfoForNetwork(nai.network);
        if (officialNai != null && officialNai.equals(nai)) return true;
        if (officialNai != null || VDBG) {
            loge(eventName(what) + " - isLiveNetworkAgent found mismatched netId: " + officialNai +
                " - " + nai);
        }
        return false;
    }

    // must be stateless - things change under us.
    private class NetworkStateTrackerHandler extends Handler {
        public NetworkStateTrackerHandler(Looper looper) {
            super(looper);
        }

        private boolean maybeHandleAsyncChannelMessage(Message msg) {
            switch (msg.what) {
                default:
                    return false;
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    handleAsyncChannelHalfConnect(msg);
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai != null) nai.asyncChannel.disconnect();
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    handleAsyncChannelDisconnected(msg);
                    break;
                }
            }
            return true;
        }

        private void maybeHandleNetworkAgentMessage(Message msg) {
            NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
            if (nai == null) {
                if (VDBG) {
                    log(String.format("%s from unknown NetworkAgent", eventName(msg.what)));
                }
                return;
            }

            switch (msg.what) {
                case NetworkAgent.EVENT_NETWORK_CAPABILITIES_CHANGED: {
                    final NetworkCapabilities networkCapabilities = (NetworkCapabilities) msg.obj;
                    if (networkCapabilities.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL) ||
                            networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED) ||
                            networkCapabilities.hasCapability(NET_CAPABILITY_FOREGROUND)) {
                        Slog.wtf(TAG, "BUG: " + nai + " has CS-managed capability.");
                    }
                    updateCapabilities(nai.getCurrentScore(), nai, networkCapabilities);
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_PROPERTIES_CHANGED: {
                    handleUpdateLinkProperties(nai, (LinkProperties) msg.obj);
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_INFO_CHANGED: {
                    NetworkInfo info = (NetworkInfo) msg.obj;
                    updateNetworkInfo(nai, info);
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_SCORE_CHANGED: {
                    updateNetworkScore(nai, msg.arg1);
                    break;
                }
                case NetworkAgent.EVENT_SET_EXPLICITLY_SELECTED: {
                    if (nai.everConnected && !nai.networkMisc.explicitlySelected) {
                        loge("ERROR: already-connected network explicitly selected.");
                    }
                    nai.networkMisc.explicitlySelected = true;
                    nai.networkMisc.acceptUnvalidated = msg.arg1 == 1;
                    break;
                }
                case NetworkAgent.EVENT_PACKET_KEEPALIVE: {
                    mKeepaliveTracker.handleEventPacketKeepalive(nai, msg);
                    break;
                }
            }
        }

        private boolean maybeHandleNetworkMonitorMessage(Message msg) {
            switch (msg.what) {
                default:
                    return false;
                case EVENT_NETWORK_TESTED: {
                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(msg.arg2);
                    if (nai == null) break;

                    final boolean valid = (msg.arg1 == NETWORK_TEST_RESULT_VALID);
                    final boolean wasValidated = nai.lastValidated;
                    final boolean wasDefault = isDefaultNetwork(nai);
                    if (nai.everCaptivePortalDetected && !nai.captivePortalLoginNotified
                            && valid) {
                        nai.captivePortalLoginNotified = true;
                        showNetworkNotification(nai, NotificationType.LOGGED_IN);
                    }

                    final String redirectUrl = (msg.obj instanceof String) ? (String) msg.obj : "";

                    if (DBG) {
                        final String logMsg = !TextUtils.isEmpty(redirectUrl)
                                 ? " with redirect to " + redirectUrl
                                 : "";
                        log(nai.name() + " validation " + (valid ? "passed" : "failed") + logMsg);
                    }
                    if (valid != nai.lastValidated) {
                        if (wasDefault) {
                            metricsLogger().defaultNetworkMetrics().logDefaultNetworkValidity(
                                    SystemClock.elapsedRealtime(), valid);
                        }
                        final int oldScore = nai.getCurrentScore();
                        nai.lastValidated = valid;
                        nai.everValidated |= valid;
                        updateCapabilities(oldScore, nai, nai.networkCapabilities);
                        // If score has changed, rebroadcast to NetworkFactories. b/17726566
                        if (oldScore != nai.getCurrentScore()) sendUpdatedScoreToFactories(nai);
                        if (valid) {
                            handleFreshlyValidatedNetwork(nai);
                            // Clear NO_INTERNET and LOST_INTERNET notifications if network becomes
                            // valid.
                            mNotifier.clearNotification(nai.network.netId,
                                    NotificationType.NO_INTERNET);
                            mNotifier.clearNotification(nai.network.netId,
                                    NotificationType.LOST_INTERNET);
                        }
                    }
                    updateInetCondition(nai);
                    // Let the NetworkAgent know the state of its network
                    Bundle redirectUrlBundle = new Bundle();
                    redirectUrlBundle.putString(NetworkAgent.REDIRECT_URL_KEY, redirectUrl);
                    nai.asyncChannel.sendMessage(
                            NetworkAgent.CMD_REPORT_NETWORK_STATUS,
                            (valid ? NetworkAgent.VALID_NETWORK : NetworkAgent.INVALID_NETWORK),
                            0, redirectUrlBundle);
                    if (wasValidated && !nai.lastValidated) {
                        handleNetworkUnvalidated(nai);
                    }
                    break;
                }
                case EVENT_PROVISIONING_NOTIFICATION: {
                    final int netId = msg.arg2;
                    final boolean visible = toBool(msg.arg1);
                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(netId);
                    // If captive portal status has changed, update capabilities or disconnect.
                    if (nai != null && (visible != nai.lastCaptivePortalDetected)) {
                        final int oldScore = nai.getCurrentScore();
                        nai.lastCaptivePortalDetected = visible;
                        nai.everCaptivePortalDetected |= visible;
                        if (visible) {
                            nai.captivePortalLoginNotified = false;
                        }
                        if (nai.lastCaptivePortalDetected &&
                            Settings.Global.CAPTIVE_PORTAL_MODE_AVOID == getCaptivePortalMode()) {
                            if (DBG) log("Avoiding captive portal network: " + nai.name());
                            nai.asyncChannel.sendMessage(
                                    NetworkAgent.CMD_PREVENT_AUTOMATIC_RECONNECT);
                            teardownUnneededNetwork(nai);
                            break;
                        }
                        updateCapabilities(oldScore, nai, nai.networkCapabilities);
                    }
                    if (!visible) {
                        // Only clear SIGN_IN and NETWORK_SWITCH notifications here, or else other
                        // notifications belong to the same network may be cleared unexpected.
                        mNotifier.clearNotification(netId, NotificationType.SIGN_IN);
                        mNotifier.clearNotification(netId, NotificationType.NETWORK_SWITCH);
                    } else {
                        if (nai == null) {
                            loge("EVENT_PROVISIONING_NOTIFICATION from unknown NetworkMonitor");
                            break;
                        }
                        if (!nai.networkMisc.provisioningNotificationDisabled) {
                            mNotifier.showNotification(netId, NotificationType.SIGN_IN, nai, null,
                                    (PendingIntent) msg.obj, nai.networkMisc.explicitlySelected);
                        }
                    }
                    break;
                }
                case EVENT_PRIVATE_DNS_CONFIG_RESOLVED: {
                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(msg.arg2);
                    if (nai == null) break;

                    updatePrivateDns(nai, (PrivateDnsConfig) msg.obj);
                    break;
                }
            }
            return true;
        }

        private int getCaptivePortalMode() {
            return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.CAPTIVE_PORTAL_MODE,
                    Settings.Global.CAPTIVE_PORTAL_MODE_PROMPT);
        }

        private boolean maybeHandleNetworkAgentInfoMessage(Message msg) {
            switch (msg.what) {
                default:
                    return false;
                case NetworkAgentInfo.EVENT_NETWORK_LINGER_COMPLETE: {
                    NetworkAgentInfo nai = (NetworkAgentInfo) msg.obj;
                    if (nai != null && isLiveNetworkAgent(nai, msg.what)) {
                        handleLingerComplete(nai);
                    }
                    break;
                }
            }
            return true;
        }

        @Override
        public void handleMessage(Message msg) {
            if (!maybeHandleAsyncChannelMessage(msg) &&
                    !maybeHandleNetworkMonitorMessage(msg) &&
                    !maybeHandleNetworkAgentInfoMessage(msg)) {
                maybeHandleNetworkAgentMessage(msg);
            }
        }
    }

    private class NetworkMonitorCallbacks extends INetworkMonitorCallbacks.Stub {
        private final NetworkAgentInfo mNai;

        private NetworkMonitorCallbacks(NetworkAgentInfo nai) {
            mNai = nai;
        }

        @Override
        public void onNetworkMonitorCreated(INetworkMonitor networkMonitor) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_AGENT,
                    new Pair<>(mNai, networkMonitor)));
        }

        @Override
        public void notifyNetworkTested(int testResult, @Nullable String redirectUrl) {
            mTrackerHandler.sendMessage(mTrackerHandler.obtainMessage(EVENT_NETWORK_TESTED,
                    testResult, mNai.network.netId, redirectUrl));
        }

        @Override
        public void notifyPrivateDnsConfigResolved(PrivateDnsConfigParcel config) {
            mTrackerHandler.sendMessage(mTrackerHandler.obtainMessage(
                    EVENT_PRIVATE_DNS_CONFIG_RESOLVED,
                    0, mNai.network.netId, PrivateDnsConfig.fromParcel(config)));
        }

        @Override
        public void showProvisioningNotification(String action) {
            final Intent intent = new Intent(action);
            intent.setPackage(NETWORKSTACK_PACKAGE_NAME);

            final PendingIntent pendingIntent;
            // Only the system server can register notifications with package "android"
            final long token = Binder.clearCallingIdentity();
            try {
                pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            mTrackerHandler.sendMessage(mTrackerHandler.obtainMessage(
                    EVENT_PROVISIONING_NOTIFICATION, PROVISIONING_NOTIFICATION_SHOW,
                    mNai.network.netId,
                    pendingIntent));
        }

        @Override
        public void hideProvisioningNotification() {
            mTrackerHandler.sendMessage(mTrackerHandler.obtainMessage(
                    EVENT_PROVISIONING_NOTIFICATION, PROVISIONING_NOTIFICATION_HIDE,
                    mNai.network.netId));
        }

        @Override
        public void logCaptivePortalLoginEvent(int eventId, String packageName) {
            new MetricsLogger().action(eventId, packageName);
        }
    }

    private boolean networkRequiresValidation(NetworkAgentInfo nai) {
        return isValidationRequired(nai.networkCapabilities);
    }

    private void handleFreshlyValidatedNetwork(NetworkAgentInfo nai) {
        if (nai == null) return;
        // If the Private DNS mode is opportunistic, reprogram the DNS servers
        // in order to restart a validation pass from within netd.
        final PrivateDnsConfig cfg = mDnsManager.getPrivateDnsConfig();
        if (cfg.useTls && TextUtils.isEmpty(cfg.hostname)) {
            updateDnses(nai.linkProperties, null, nai.network.netId);
        }
    }

    private void handlePrivateDnsSettingsChanged() {
        final PrivateDnsConfig cfg = mDnsManager.getPrivateDnsConfig();

        for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
            handlePerNetworkPrivateDnsConfig(nai, cfg);
            if (networkRequiresValidation(nai)) {
                handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
            }
        }
    }

    private void handlePerNetworkPrivateDnsConfig(NetworkAgentInfo nai, PrivateDnsConfig cfg) {
        // Private DNS only ever applies to networks that might provide
        // Internet access and therefore also require validation.
        if (!networkRequiresValidation(nai)) return;

        // Notify the NetworkAgentInfo/NetworkMonitor in case NetworkMonitor needs to cancel or
        // schedule DNS resolutions. If a DNS resolution is required the
        // result will be sent back to us.
        try {
            nai.networkMonitor().notifyPrivateDnsChanged(cfg.toParcel());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        // With Private DNS bypass support, we can proceed to update the
        // Private DNS config immediately, even if we're in strict mode
        // and have not yet resolved the provider name into a set of IPs.
        updatePrivateDns(nai, cfg);
    }

    private void updatePrivateDns(NetworkAgentInfo nai, PrivateDnsConfig newCfg) {
        mDnsManager.updatePrivateDns(nai.network, newCfg);
        updateDnses(nai.linkProperties, null, nai.network.netId);
    }

    private void handlePrivateDnsValidationUpdate(PrivateDnsValidationUpdate update) {
        NetworkAgentInfo nai = getNetworkAgentInfoForNetId(update.netId);
        if (nai == null) {
            return;
        }
        mDnsManager.updatePrivateDnsValidation(update);
        handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
    }

    private void updateLingerState(NetworkAgentInfo nai, long now) {
        // 1. Update the linger timer. If it's changed, reschedule or cancel the alarm.
        // 2. If the network was lingering and there are now requests, unlinger it.
        // 3. If this network is unneeded (which implies it is not lingering), and there is at least
        //    one lingered request, start lingering.
        nai.updateLingerTimer();
        if (nai.isLingering() && nai.numForegroundNetworkRequests() > 0) {
            if (DBG) log("Unlingering " + nai.name());
            nai.unlinger();
            logNetworkEvent(nai, NetworkEvent.NETWORK_UNLINGER);
        } else if (unneeded(nai, UnneededFor.LINGER) && nai.getLingerExpiry() > 0) {
            int lingerTime = (int) (nai.getLingerExpiry() - now);
            if (DBG) log("Lingering " + nai.name() + " for " + lingerTime + "ms");
            nai.linger();
            logNetworkEvent(nai, NetworkEvent.NETWORK_LINGER);
            notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOSING, lingerTime);
        }
    }

    private void handleAsyncChannelHalfConnect(Message msg) {
        AsyncChannel ac = (AsyncChannel) msg.obj;
        if (mNetworkFactoryInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                if (VDBG) log("NetworkFactory connected");
                // A network factory has connected.  Send it all current NetworkRequests.
                for (NetworkRequestInfo nri : mNetworkRequests.values()) {
                    if (nri.request.isListen()) continue;
                    NetworkAgentInfo nai = getNetworkForRequest(nri.request.requestId);
                    ac.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK,
                            (nai != null ? nai.getCurrentScore() : 0), 0, nri.request);
                }
            } else {
                loge("Error connecting NetworkFactory");
                mNetworkFactoryInfos.remove(msg.obj);
            }
        } else if (mNetworkAgentInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                if (VDBG) log("NetworkAgent connected");
                // A network agent has requested a connection.  Establish the connection.
                mNetworkAgentInfos.get(msg.replyTo).asyncChannel.
                        sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
            } else {
                loge("Error connecting NetworkAgent");
                NetworkAgentInfo nai = mNetworkAgentInfos.remove(msg.replyTo);
                if (nai != null) {
                    final boolean wasDefault = isDefaultNetwork(nai);
                    synchronized (mNetworkForNetId) {
                        mNetworkForNetId.remove(nai.network.netId);
                        mNetIdInUse.delete(nai.network.netId);
                    }
                    // Just in case.
                    mLegacyTypeTracker.remove(nai, wasDefault);
                }
            }
        }
    }

    // This is a no-op if it's called with a message designating a network that has
    // already been destroyed, because its reference will not be found in the relevant
    // maps.
    private void handleAsyncChannelDisconnected(Message msg) {
        NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
        if (nai != null) {
            disconnectAndDestroyNetwork(nai);
        } else {
            NetworkFactoryInfo nfi = mNetworkFactoryInfos.remove(msg.replyTo);
            if (DBG && nfi != null) log("unregisterNetworkFactory for " + nfi.name);
        }
    }

    // Destroys a network, remove references to it from the internal state managed by
    // ConnectivityService, free its interfaces and clean up.
    // Must be called on the Handler thread.
    private void disconnectAndDestroyNetwork(NetworkAgentInfo nai) {
        if (DBG) {
            log(nai.name() + " got DISCONNECTED, was satisfying " + nai.numNetworkRequests());
        }
        // A network agent has disconnected.
        // TODO - if we move the logic to the network agent (have them disconnect
        // because they lost all their requests or because their score isn't good)
        // then they would disconnect organically, report their new state and then
        // disconnect the channel.
        if (nai.networkInfo.isConnected()) {
            nai.networkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED,
                    null, null);
        }
        final boolean wasDefault = isDefaultNetwork(nai);
        if (wasDefault) {
            mDefaultInetConditionPublished = 0;
            // Log default network disconnection before required book-keeping.
            // Let rematchAllNetworksAndRequests() below record a new default network event
            // if there is a fallback. Taken together, the two form a X -> 0, 0 -> Y sequence
            // whose timestamps tell how long it takes to recover a default network.
            long now = SystemClock.elapsedRealtime();
            metricsLogger().defaultNetworkMetrics().logDefaultNetworkEvent(now, null, nai);
        }
        notifyIfacesChangedForNetworkStats();
        // TODO - we shouldn't send CALLBACK_LOST to requests that can be satisfied
        // by other networks that are already connected. Perhaps that can be done by
        // sending all CALLBACK_LOST messages (for requests, not listens) at the end
        // of rematchAllNetworksAndRequests
        notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOST);
        mKeepaliveTracker.handleStopAllKeepalives(nai,
                ConnectivityManager.PacketKeepalive.ERROR_INVALID_NETWORK);
        for (String iface : nai.linkProperties.getAllInterfaceNames()) {
            // Disable wakeup packet monitoring for each interface.
            wakeupModifyInterface(iface, nai.networkCapabilities, false);
        }
        try {
            nai.networkMonitor().notifyNetworkDisconnected();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        mNetworkAgentInfos.remove(nai.messenger);
        nai.maybeStopClat();
        synchronized (mNetworkForNetId) {
            // Remove the NetworkAgent, but don't mark the netId as
            // available until we've told netd to delete it below.
            mNetworkForNetId.remove(nai.network.netId);
        }
        // Remove all previously satisfied requests.
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest request = nai.requestAt(i);
            NetworkAgentInfo currentNetwork = getNetworkForRequest(request.requestId);
            if (currentNetwork != null && currentNetwork.network.netId == nai.network.netId) {
                clearNetworkForRequest(request.requestId);
                sendUpdatedScoreToFactories(request, 0);
            }
        }
        nai.clearLingerState();
        if (nai.isSatisfyingRequest(mDefaultRequest.requestId)) {
            updateDataActivityTracking(null /* newNetwork */, nai);
            notifyLockdownVpn(nai);
            ensureNetworkTransitionWakelock(nai.name());
        }
        mLegacyTypeTracker.remove(nai, wasDefault);
        if (!nai.networkCapabilities.hasTransport(TRANSPORT_VPN)) {
            updateAllVpnsCapabilities();
        }
        rematchAllNetworksAndRequests(null, 0);
        mLingerMonitor.noteDisconnect(nai);
        if (nai.created) {
            // Tell netd to clean up the configuration for this network
            // (routing rules, DNS, etc).
            // This may be slow as it requires a lot of netd shelling out to ip and
            // ip[6]tables to flush routes and remove the incoming packet mark rule, so do it
            // after we've rematched networks with requests which should make a potential
            // fallback network the default or requested a new network from the
            // NetworkFactories, so network traffic isn't interrupted for an unnecessarily
            // long time.
            try {
                mNMS.removeNetwork(nai.network.netId);
            } catch (Exception e) {
                loge("Exception removing network: " + e);
            }
            mDnsManager.removeNetwork(nai.network);
        }
        synchronized (mNetworkForNetId) {
            mNetIdInUse.delete(nai.network.netId);
        }
    }

    // If this method proves to be too slow then we can maintain a separate
    // pendingIntent => NetworkRequestInfo map.
    // This method assumes that every non-null PendingIntent maps to exactly 1 NetworkRequestInfo.
    private NetworkRequestInfo findExistingNetworkRequestInfo(PendingIntent pendingIntent) {
        Intent intent = pendingIntent.getIntent();
        for (Map.Entry<NetworkRequest, NetworkRequestInfo> entry : mNetworkRequests.entrySet()) {
            PendingIntent existingPendingIntent = entry.getValue().mPendingIntent;
            if (existingPendingIntent != null &&
                    existingPendingIntent.getIntent().filterEquals(intent)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void handleRegisterNetworkRequestWithIntent(Message msg) {
        final NetworkRequestInfo nri = (NetworkRequestInfo) (msg.obj);

        NetworkRequestInfo existingRequest = findExistingNetworkRequestInfo(nri.mPendingIntent);
        if (existingRequest != null) { // remove the existing request.
            if (DBG) log("Replacing " + existingRequest.request + " with "
                    + nri.request + " because their intents matched.");
            handleReleaseNetworkRequest(existingRequest.request, getCallingUid());
        }
        handleRegisterNetworkRequest(nri);
    }

    private void handleRegisterNetworkRequest(NetworkRequestInfo nri) {
        mNetworkRequests.put(nri.request, nri);
        mNetworkRequestInfoLogs.log("REGISTER " + nri);
        if (nri.request.isListen()) {
            for (NetworkAgentInfo network : mNetworkAgentInfos.values()) {
                if (nri.request.networkCapabilities.hasSignalStrength() &&
                        network.satisfiesImmutableCapabilitiesOf(nri.request)) {
                    updateSignalStrengthThresholds(network, "REGISTER", nri.request);
                }
            }
        }
        rematchAllNetworksAndRequests(null, 0);
        if (nri.request.isRequest() && getNetworkForRequest(nri.request.requestId) == null) {
            sendUpdatedScoreToFactories(nri.request, 0);
        }
    }

    private void handleReleaseNetworkRequestWithIntent(PendingIntent pendingIntent,
            int callingUid) {
        NetworkRequestInfo nri = findExistingNetworkRequestInfo(pendingIntent);
        if (nri != null) {
            handleReleaseNetworkRequest(nri.request, callingUid);
        }
    }

    // Determines whether the network is the best (or could become the best, if it validated), for
    // none of a particular type of NetworkRequests. The type of NetworkRequests considered depends
    // on the value of reason:
    //
    // - UnneededFor.TEARDOWN: non-listen NetworkRequests. If a network is unneeded for this reason,
    //   then it should be torn down.
    // - UnneededFor.LINGER: foreground NetworkRequests. If a network is unneeded for this reason,
    //   then it should be lingered.
    private boolean unneeded(NetworkAgentInfo nai, UnneededFor reason) {
        final int numRequests;
        switch (reason) {
            case TEARDOWN:
                numRequests = nai.numRequestNetworkRequests();
                break;
            case LINGER:
                numRequests = nai.numForegroundNetworkRequests();
                break;
            default:
                Slog.wtf(TAG, "Invalid reason. Cannot happen.");
                return true;
        }

        if (!nai.everConnected || nai.isVPN() || nai.isLingering() || numRequests > 0) {
            return false;
        }
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            if (reason == UnneededFor.LINGER && nri.request.isBackgroundRequest()) {
                // Background requests don't affect lingering.
                continue;
            }

            // If this Network is already the highest scoring Network for a request, or if
            // there is hope for it to become one if it validated, then it is needed.
            if (nri.request.isRequest() && nai.satisfies(nri.request) &&
                    (nai.isSatisfyingRequest(nri.request.requestId) ||
                    // Note that this catches two important cases:
                    // 1. Unvalidated cellular will not be reaped when unvalidated WiFi
                    //    is currently satisfying the request.  This is desirable when
                    //    cellular ends up validating but WiFi does not.
                    // 2. Unvalidated WiFi will not be reaped when validated cellular
                    //    is currently satisfying the request.  This is desirable when
                    //    WiFi ends up validating and out scoring cellular.
                    getNetworkForRequest(nri.request.requestId).getCurrentScore() <
                            nai.getCurrentScoreAsValidated())) {
                return false;
            }
        }
        return true;
    }

    private NetworkRequestInfo getNriForAppRequest(
            NetworkRequest request, int callingUid, String requestedOperation) {
        final NetworkRequestInfo nri = mNetworkRequests.get(request);

        if (nri != null) {
            if (Process.SYSTEM_UID != callingUid && nri.mUid != callingUid) {
                log(String.format("UID %d attempted to %s for unowned request %s",
                        callingUid, requestedOperation, nri));
                return null;
            }
        }

        return nri;
    }

    private void handleTimedOutNetworkRequest(final NetworkRequestInfo nri) {
        if (mNetworkRequests.get(nri.request) == null) {
            return;
        }
        if (getNetworkForRequest(nri.request.requestId) != null) {
            return;
        }
        if (VDBG || (DBG && nri.request.isRequest())) {
            log("releasing " + nri.request + " (timeout)");
        }
        handleRemoveNetworkRequest(nri);
        callCallbackForRequest(nri, null, ConnectivityManager.CALLBACK_UNAVAIL, 0);
    }

    private void handleReleaseNetworkRequest(NetworkRequest request, int callingUid) {
        final NetworkRequestInfo nri =
                getNriForAppRequest(request, callingUid, "release NetworkRequest");
        if (nri == null) {
            return;
        }
        if (VDBG || (DBG && nri.request.isRequest())) {
            log("releasing " + nri.request + " (release request)");
        }
        handleRemoveNetworkRequest(nri);
    }

    private void handleRemoveNetworkRequest(final NetworkRequestInfo nri) {
        nri.unlinkDeathRecipient();
        mNetworkRequests.remove(nri.request);

        synchronized (mUidToNetworkRequestCount) {
            int requests = mUidToNetworkRequestCount.get(nri.mUid, 0);
            if (requests < 1) {
                Slog.wtf(TAG, "BUG: too small request count " + requests + " for UID " +
                        nri.mUid);
            } else if (requests == 1) {
                mUidToNetworkRequestCount.removeAt(
                        mUidToNetworkRequestCount.indexOfKey(nri.mUid));
            } else {
                mUidToNetworkRequestCount.put(nri.mUid, requests - 1);
            }
        }

        mNetworkRequestInfoLogs.log("RELEASE " + nri);
        if (nri.request.isRequest()) {
            boolean wasKept = false;
            NetworkAgentInfo nai = getNetworkForRequest(nri.request.requestId);
            if (nai != null) {
                boolean wasBackgroundNetwork = nai.isBackgroundNetwork();
                nai.removeRequest(nri.request.requestId);
                if (VDBG || DDBG) {
                    log(" Removing from current network " + nai.name() +
                            ", leaving " + nai.numNetworkRequests() + " requests.");
                }
                // If there are still lingered requests on this network, don't tear it down,
                // but resume lingering instead.
                updateLingerState(nai, SystemClock.elapsedRealtime());
                if (unneeded(nai, UnneededFor.TEARDOWN)) {
                    if (DBG) log("no live requests for " + nai.name() + "; disconnecting");
                    teardownUnneededNetwork(nai);
                } else {
                    wasKept = true;
                }
                clearNetworkForRequest(nri.request.requestId);
                if (!wasBackgroundNetwork && nai.isBackgroundNetwork()) {
                    // Went from foreground to background.
                    updateCapabilities(nai.getCurrentScore(), nai, nai.networkCapabilities);
                }
            }

            // Maintain the illusion.  When this request arrived, we might have pretended
            // that a network connected to serve it, even though the network was already
            // connected.  Now that this request has gone away, we might have to pretend
            // that the network disconnected.  LegacyTypeTracker will generate that
            // phantom disconnect for this type.
            if (nri.request.legacyType != TYPE_NONE && nai != null) {
                boolean doRemove = true;
                if (wasKept) {
                    // check if any of the remaining requests for this network are for the
                    // same legacy type - if so, don't remove the nai
                    for (int i = 0; i < nai.numNetworkRequests(); i++) {
                        NetworkRequest otherRequest = nai.requestAt(i);
                        if (otherRequest.legacyType == nri.request.legacyType &&
                                otherRequest.isRequest()) {
                            if (DBG) log(" still have other legacy request - leaving");
                            doRemove = false;
                        }
                    }
                }

                if (doRemove) {
                    mLegacyTypeTracker.remove(nri.request.legacyType, nai, false);
                }
            }

            for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
                nfi.asyncChannel.sendMessage(android.net.NetworkFactory.CMD_CANCEL_REQUEST,
                        nri.request);
            }
        } else {
            // listens don't have a singular affectedNetwork.  Check all networks to see
            // if this listen request applies and remove it.
            for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
                nai.removeRequest(nri.request.requestId);
                if (nri.request.networkCapabilities.hasSignalStrength() &&
                        nai.satisfiesImmutableCapabilitiesOf(nri.request)) {
                    updateSignalStrengthThresholds(nai, "RELEASE", nri.request);
                }
            }
        }
    }

    @Override
    public void setAcceptUnvalidated(Network network, boolean accept, boolean always) {
        enforceConnectivityInternalPermission();
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_ACCEPT_UNVALIDATED,
                encodeBool(accept), encodeBool(always), network));
    }

    @Override
    public void setAvoidUnvalidated(Network network) {
        enforceConnectivityInternalPermission();
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_AVOID_UNVALIDATED, network));
    }

    private void handleSetAcceptUnvalidated(Network network, boolean accept, boolean always) {
        if (DBG) log("handleSetAcceptUnvalidated network=" + network +
                " accept=" + accept + " always=" + always);

        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null) {
            // Nothing to do.
            return;
        }

        if (nai.everValidated) {
            // The network validated while the dialog box was up. Take no action.
            return;
        }

        if (!nai.networkMisc.explicitlySelected) {
            Slog.wtf(TAG, "BUG: setAcceptUnvalidated non non-explicitly selected network");
        }

        if (accept != nai.networkMisc.acceptUnvalidated) {
            int oldScore = nai.getCurrentScore();
            nai.networkMisc.acceptUnvalidated = accept;
            rematchAllNetworksAndRequests(nai, oldScore);
            sendUpdatedScoreToFactories(nai);
        }

        if (always) {
            nai.asyncChannel.sendMessage(
                    NetworkAgent.CMD_SAVE_ACCEPT_UNVALIDATED, encodeBool(accept));
        }

        if (!accept) {
            // Tell the NetworkAgent to not automatically reconnect to the network.
            nai.asyncChannel.sendMessage(NetworkAgent.CMD_PREVENT_AUTOMATIC_RECONNECT);
            // Teardown the network.
            teardownUnneededNetwork(nai);
        }

    }

    private void handleSetAvoidUnvalidated(Network network) {
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null || nai.lastValidated) {
            // Nothing to do. The network either disconnected or revalidated.
            return;
        }
        if (!nai.avoidUnvalidated) {
            int oldScore = nai.getCurrentScore();
            nai.avoidUnvalidated = true;
            rematchAllNetworksAndRequests(nai, oldScore);
            sendUpdatedScoreToFactories(nai);
        }
    }

    private void scheduleUnvalidatedPrompt(NetworkAgentInfo nai) {
        if (VDBG) log("scheduleUnvalidatedPrompt " + nai.network);
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(EVENT_PROMPT_UNVALIDATED, nai.network),
                PROMPT_UNVALIDATED_DELAY_MS);
    }

    @Override
    public void startCaptivePortalApp(Network network) {
        enforceConnectivityInternalPermission();
        mHandler.post(() -> {
            NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
            if (nai == null) return;
            if (!nai.networkCapabilities.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)) return;
            try {
                nai.networkMonitor().launchCaptivePortalApp();
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        });
    }

    public boolean avoidBadWifi() {
        return mMultinetworkPolicyTracker.getAvoidBadWifi();
    }

    @Override
    public boolean getAvoidBadWifi() {
        if (!checkNetworkStackPermission()) {
            throw new SecurityException("avoidBadWifi requires NETWORK_STACK permission");
        }
        return avoidBadWifi();
    }


    private void rematchForAvoidBadWifiUpdate() {
        rematchAllNetworksAndRequests(null, 0);
        for (NetworkAgentInfo nai: mNetworkAgentInfos.values()) {
            if (nai.networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                sendUpdatedScoreToFactories(nai);
            }
        }
    }

    // TODO: Evaluate whether this is of interest to other consumers of
    // MultinetworkPolicyTracker and worth moving out of here.
    private void dumpAvoidBadWifiSettings(IndentingPrintWriter pw) {
        final boolean configRestrict = mMultinetworkPolicyTracker.configRestrictsAvoidBadWifi();
        if (!configRestrict) {
            pw.println("Bad Wi-Fi avoidance: unrestricted");
            return;
        }

        pw.println("Bad Wi-Fi avoidance: " + avoidBadWifi());
        pw.increaseIndent();
        pw.println("Config restrict:   " + configRestrict);

        final String value = mMultinetworkPolicyTracker.getAvoidBadWifiSetting();
        String description;
        // Can't use a switch statement because strings are legal case labels, but null is not.
        if ("0".equals(value)) {
            description = "get stuck";
        } else if (value == null) {
            description = "prompt";
        } else if ("1".equals(value)) {
            description = "avoid";
        } else {
            description = value + " (?)";
        }
        pw.println("User setting:      " + description);
        pw.println("Network overrides:");
        pw.increaseIndent();
        for (NetworkAgentInfo nai : networksSortedById()) {
            if (nai.avoidUnvalidated) {
                pw.println(nai.name());
            }
        }
        pw.decreaseIndent();
        pw.decreaseIndent();
    }

    private void showNetworkNotification(NetworkAgentInfo nai, NotificationType type) {
        final String action;
        switch (type) {
            case LOGGED_IN:
                action = Settings.ACTION_WIFI_SETTINGS;
                mHandler.removeMessages(EVENT_TIMEOUT_NOTIFICATION);
                mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_TIMEOUT_NOTIFICATION,
                        nai.network.netId, 0), TIMEOUT_NOTIFICATION_DELAY_MS);
                break;
            case NO_INTERNET:
                action = ConnectivityManager.ACTION_PROMPT_UNVALIDATED;
                break;
            case LOST_INTERNET:
                action = ConnectivityManager.ACTION_PROMPT_LOST_VALIDATION;
                break;
            default:
                Slog.wtf(TAG, "Unknown notification type " + type);
                return;
        }

        Intent intent = new Intent(action);
        if (type != NotificationType.LOGGED_IN) {
            intent.setData(Uri.fromParts("netId", Integer.toString(nai.network.netId), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClassName("com.android.settings",
                    "com.android.settings.wifi.WifiNoInternetDialog");
        }

        PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);
        mNotifier.showNotification(nai.network.netId, type, nai, null, pendingIntent, true);
    }

    private void handlePromptUnvalidated(Network network) {
        if (VDBG || DDBG) log("handlePromptUnvalidated " + network);
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);

        // Only prompt if the network is unvalidated and was explicitly selected by the user, and if
        // we haven't already been told to switch to it regardless of whether it validated or not.
        // Also don't prompt on captive portals because we're already prompting the user to sign in.
        if (nai == null || nai.everValidated || nai.everCaptivePortalDetected ||
                !nai.networkMisc.explicitlySelected || nai.networkMisc.acceptUnvalidated) {
            return;
        }
        showNetworkNotification(nai, NotificationType.NO_INTERNET);
    }

    private void handleNetworkUnvalidated(NetworkAgentInfo nai) {
        NetworkCapabilities nc = nai.networkCapabilities;
        if (DBG) log("handleNetworkUnvalidated " + nai.name() + " cap=" + nc);

        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            mMultinetworkPolicyTracker.shouldNotifyWifiUnvalidated()) {
            showNetworkNotification(nai, NotificationType.LOST_INTERNET);
        }
    }

    @Override
    public int getMultipathPreference(Network network) {
        enforceAccessPermission();

        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai != null && nai.networkCapabilities
                .hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return ConnectivityManager.MULTIPATH_PREFERENCE_UNMETERED;
        }

        Integer networkPreference = mMultipathPolicyTracker.getMultipathPreference(network);
        if (networkPreference != null) {
            return networkPreference;
        }

        return mMultinetworkPolicyTracker.getMeteredMultipathPreference();
    }

    @Override
    public NetworkRequest getDefaultRequest() {
        return mDefaultRequest;
    }

    private class InternalHandler extends Handler {
        public InternalHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_EXPIRE_NET_TRANSITION_WAKELOCK:
                case EVENT_CLEAR_NET_TRANSITION_WAKELOCK: {
                    handleReleaseNetworkTransitionWakelock(msg.what);
                    break;
                }
                case EVENT_APPLY_GLOBAL_HTTP_PROXY: {
                    mProxyTracker.loadDeprecatedGlobalHttpProxy();
                    break;
                }
                case EVENT_PROXY_HAS_CHANGED: {
                    handleApplyDefaultProxy((ProxyInfo)msg.obj);
                    break;
                }
                case EVENT_REGISTER_NETWORK_FACTORY: {
                    handleRegisterNetworkFactory((NetworkFactoryInfo)msg.obj);
                    break;
                }
                case EVENT_UNREGISTER_NETWORK_FACTORY: {
                    handleUnregisterNetworkFactory((Messenger)msg.obj);
                    break;
                }
                case EVENT_REGISTER_NETWORK_AGENT: {
                    final Pair<NetworkAgentInfo, INetworkMonitor> arg =
                            (Pair<NetworkAgentInfo, INetworkMonitor>) msg.obj;
                    handleRegisterNetworkAgent(arg.first, arg.second);
                    break;
                }
                case EVENT_REGISTER_NETWORK_REQUEST:
                case EVENT_REGISTER_NETWORK_LISTENER: {
                    handleRegisterNetworkRequest((NetworkRequestInfo) msg.obj);
                    break;
                }
                case EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT:
                case EVENT_REGISTER_NETWORK_LISTENER_WITH_INTENT: {
                    handleRegisterNetworkRequestWithIntent(msg);
                    break;
                }
                case EVENT_TIMEOUT_NETWORK_REQUEST: {
                    NetworkRequestInfo nri = (NetworkRequestInfo) msg.obj;
                    handleTimedOutNetworkRequest(nri);
                    break;
                }
                case EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT: {
                    handleReleaseNetworkRequestWithIntent((PendingIntent) msg.obj, msg.arg1);
                    break;
                }
                case EVENT_RELEASE_NETWORK_REQUEST: {
                    handleReleaseNetworkRequest((NetworkRequest) msg.obj, msg.arg1);
                    break;
                }
                case EVENT_SET_ACCEPT_UNVALIDATED: {
                    Network network = (Network) msg.obj;
                    handleSetAcceptUnvalidated(network, toBool(msg.arg1), toBool(msg.arg2));
                    break;
                }
                case EVENT_SET_AVOID_UNVALIDATED: {
                    handleSetAvoidUnvalidated((Network) msg.obj);
                    break;
                }
                case EVENT_PROMPT_UNVALIDATED: {
                    handlePromptUnvalidated((Network) msg.obj);
                    break;
                }
                case EVENT_CONFIGURE_ALWAYS_ON_NETWORKS: {
                    handleConfigureAlwaysOnNetworks();
                    break;
                }
                // Sent by KeepaliveTracker to process an app request on the state machine thread.
                case NetworkAgent.CMD_START_PACKET_KEEPALIVE: {
                    mKeepaliveTracker.handleStartKeepalive(msg);
                    break;
                }
                // Sent by KeepaliveTracker to process an app request on the state machine thread.
                case NetworkAgent.CMD_STOP_PACKET_KEEPALIVE: {
                    NetworkAgentInfo nai = getNetworkAgentInfoForNetwork((Network) msg.obj);
                    int slot = msg.arg1;
                    int reason = msg.arg2;
                    mKeepaliveTracker.handleStopKeepalive(nai, slot, reason);
                    break;
                }
                case EVENT_SYSTEM_READY: {
                    for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
                        // Might have been called already in handleRegisterNetworkAgent since
                        // mSystemReady is set before sending EVENT_SYSTEM_READY, but calling
                        // this several times is fine.
                        try {
                            nai.networkMonitor().notifySystemReady();
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }
                    mMultipathPolicyTracker.start();
                    break;
                }
                case EVENT_REVALIDATE_NETWORK: {
                    handleReportNetworkConnectivity((Network) msg.obj, msg.arg1, toBool(msg.arg2));
                    break;
                }
                case EVENT_PRIVATE_DNS_SETTINGS_CHANGED:
                    handlePrivateDnsSettingsChanged();
                    break;
                case EVENT_PRIVATE_DNS_VALIDATION_UPDATE:
                    handlePrivateDnsValidationUpdate(
                            (PrivateDnsValidationUpdate) msg.obj);
                    break;
                case EVENT_UID_RULES_CHANGED:
                    handleUidRulesChanged(msg.arg1, msg.arg2);
                    break;
                case EVENT_DATA_SAVER_CHANGED:
                    handleRestrictBackgroundChanged(toBool(msg.arg1));
                    break;
                case EVENT_TIMEOUT_NOTIFICATION:
                    mNotifier.clearNotification(msg.arg1, NotificationType.LOGGED_IN);
                    break;
            }
        }
    }

    // javadoc from interface
    @Override
    public int tether(String iface, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(mContext, callerPkg);
        if (isTetheringSupported()) {
            return mTethering.tether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    @Override
    public int untether(String iface, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(mContext, callerPkg);

        if (isTetheringSupported()) {
            return mTethering.untether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    @Override
    public int getLastTetherError(String iface) {
        enforceTetherAccessPermission();

        if (isTetheringSupported()) {
            return mTethering.getLastTetherError(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - proper iface API for selection by property, inspection, etc
    @Override
    public String[] getTetherableUsbRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableUsbRegexs();
        } else {
            return new String[0];
        }
    }

    @Override
    public String[] getTetherableWifiRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableWifiRegexs();
        } else {
            return new String[0];
        }
    }

    @Override
    public String[] getTetherableBluetoothRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableBluetoothRegexs();
        } else {
            return new String[0];
        }
    }

    @Override
    public int setUsbTethering(boolean enable, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(mContext, callerPkg);
        if (isTetheringSupported()) {
            return mTethering.setUsbTethering(enable);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - move iface listing, queries, etc to new module
    // javadoc from interface
    @Override
    public String[] getTetherableIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetherableIfaces();
    }

    @Override
    public String[] getTetheredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetheredIfaces();
    }

    @Override
    public String[] getTetheringErroredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getErroredIfaces();
    }

    @Override
    public String[] getTetheredDhcpRanges() {
        enforceConnectivityInternalPermission();
        return mTethering.getTetheredDhcpRanges();
    }

    @Override
    public boolean isTetheringSupported(String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(mContext, callerPkg);
        return isTetheringSupported();
    }

    // if ro.tether.denied = true we default to no tethering
    // gservices could set the secure setting to 1 though to enable it on a build where it
    // had previously been turned off.
    private boolean isTetheringSupported() {
        int defaultVal = encodeBool(!mSystemProperties.get("ro.tether.denied").equals("true"));
        boolean tetherSupported = toBool(Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.TETHER_SUPPORTED, defaultVal));
        boolean tetherEnabledInSettings = tetherSupported
                && !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING);

        // Elevate to system UID to avoid caller requiring MANAGE_USERS permission.
        boolean adminUser = false;
        final long token = Binder.clearCallingIdentity();
        try {
            adminUser = mUserManager.isAdminUser();
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        return tetherEnabledInSettings && adminUser && mTethering.hasTetherableConfiguration();
    }

    @Override
    public void startTethering(int type, ResultReceiver receiver, boolean showProvisioningUi,
            String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(mContext, callerPkg);
        if (!isTetheringSupported()) {
            receiver.send(ConnectivityManager.TETHER_ERROR_UNSUPPORTED, null);
            return;
        }
        mTethering.startTethering(type, receiver, showProvisioningUi);
    }

    @Override
    public void stopTethering(int type, String callerPkg) {
        ConnectivityManager.enforceTetherChangePermission(mContext, callerPkg);
        mTethering.stopTethering(type);
    }

    // Called when we lose the default network and have no replacement yet.
    // This will automatically be cleared after X seconds or a new default network
    // becomes CONNECTED, whichever happens first.  The timer is started by the
    // first caller and not restarted by subsequent callers.
    private void ensureNetworkTransitionWakelock(String forWhom) {
        synchronized (this) {
            if (mNetTransitionWakeLock.isHeld()) {
                return;
            }
            mNetTransitionWakeLock.acquire();
            mLastWakeLockAcquireTimestamp = SystemClock.elapsedRealtime();
            mTotalWakelockAcquisitions++;
        }
        mWakelockLogs.log("ACQUIRE for " + forWhom);
        Message msg = mHandler.obtainMessage(EVENT_EXPIRE_NET_TRANSITION_WAKELOCK);
        mHandler.sendMessageDelayed(msg, mNetTransitionWakeLockTimeout);
    }

    // Called when we gain a new default network to release the network transition wakelock in a
    // second, to allow a grace period for apps to reconnect over the new network. Pending expiry
    // message is cancelled.
    private void scheduleReleaseNetworkTransitionWakelock() {
        synchronized (this) {
            if (!mNetTransitionWakeLock.isHeld()) {
                return; // expiry message released the lock first.
            }
        }
        // Cancel self timeout on wakelock hold.
        mHandler.removeMessages(EVENT_EXPIRE_NET_TRANSITION_WAKELOCK);
        Message msg = mHandler.obtainMessage(EVENT_CLEAR_NET_TRANSITION_WAKELOCK);
        mHandler.sendMessageDelayed(msg, 1000);
    }

    // Called when either message of ensureNetworkTransitionWakelock or
    // scheduleReleaseNetworkTransitionWakelock is processed.
    private void handleReleaseNetworkTransitionWakelock(int eventId) {
        String event = eventName(eventId);
        synchronized (this) {
            if (!mNetTransitionWakeLock.isHeld()) {
                mWakelockLogs.log(String.format("RELEASE: already released (%s)", event));
                Slog.w(TAG, "expected Net Transition WakeLock to be held");
                return;
            }
            mNetTransitionWakeLock.release();
            long lockDuration = SystemClock.elapsedRealtime() - mLastWakeLockAcquireTimestamp;
            mTotalWakelockDurationMs += lockDuration;
            mMaxWakelockDurationMs = Math.max(mMaxWakelockDurationMs, lockDuration);
            mTotalWakelockReleases++;
        }
        mWakelockLogs.log(String.format("RELEASE (%s)", event));
    }

    // 100 percent is full good, 0 is full bad.
    @Override
    public void reportInetCondition(int networkType, int percentage) {
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai == null) return;
        reportNetworkConnectivity(nai.network, percentage > 50);
    }

    @Override
    public void reportNetworkConnectivity(Network network, boolean hasConnectivity) {
        enforceAccessPermission();
        enforceInternetPermission();
        final int uid = Binder.getCallingUid();
        final int connectivityInfo = encodeBool(hasConnectivity);
        mHandler.sendMessage(
                mHandler.obtainMessage(EVENT_REVALIDATE_NETWORK, uid, connectivityInfo, network));
    }

    private void handleReportNetworkConnectivity(
            Network network, int uid, boolean hasConnectivity) {
        final NetworkAgentInfo nai;
        if (network == null) {
            nai = getDefaultNetwork();
        } else {
            nai = getNetworkAgentInfoForNetwork(network);
        }
        if (nai == null || nai.networkInfo.getState() == NetworkInfo.State.DISCONNECTING ||
            nai.networkInfo.getState() == NetworkInfo.State.DISCONNECTED) {
            return;
        }
        // Revalidate if the app report does not match our current validated state.
        if (hasConnectivity == nai.lastValidated) {
            return;
        }
        if (DBG) {
            int netid = nai.network.netId;
            log("reportNetworkConnectivity(" + netid + ", " + hasConnectivity + ") by " + uid);
        }
        // Validating a network that has not yet connected could result in a call to
        // rematchNetworkAndRequests() which is not meant to work on such networks.
        if (!nai.everConnected) {
            return;
        }
        LinkProperties lp = getLinkProperties(nai);
        if (isNetworkWithLinkPropertiesBlocked(lp, uid, false)) {
            return;
        }
        try {
            nai.networkMonitor().forceReevaluation(uid);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information about the proxy a certain network is using. If given a null network, it
     * it will return the proxy for the bound network for the caller app or the default proxy if
     * none.
     *
     * @param network the network we want to get the proxy information for.
     * @return Proxy information if a network has a proxy configured, or otherwise null.
     */
    @Override
    public ProxyInfo getProxyForNetwork(Network network) {
        final ProxyInfo globalProxy = mProxyTracker.getGlobalProxy();
        if (globalProxy != null) return globalProxy;
        if (network == null) {
            // Get the network associated with the calling UID.
            final Network activeNetwork = getActiveNetworkForUidInternal(Binder.getCallingUid(),
                    true);
            if (activeNetwork == null) {
                return null;
            }
            return getLinkPropertiesProxyInfo(activeNetwork);
        } else if (queryUserAccess(Binder.getCallingUid(), network.netId)) {
            // Don't call getLinkProperties() as it requires ACCESS_NETWORK_STATE permission, which
            // caller may not have.
            return getLinkPropertiesProxyInfo(network);
        }
        // No proxy info available if the calling UID does not have network access.
        return null;
    }

    @VisibleForTesting
    protected boolean queryUserAccess(int uid, int netId) {
        return NetworkUtils.queryUserAccess(uid, netId);
    }

    private ProxyInfo getLinkPropertiesProxyInfo(Network network) {
        final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null) return null;
        synchronized (nai) {
            final ProxyInfo linkHttpProxy = nai.linkProperties.getHttpProxy();
            return linkHttpProxy == null ? null : new ProxyInfo(linkHttpProxy);
        }
    }

    @Override
    public void setGlobalProxy(final ProxyInfo proxyProperties) {
        enforceConnectivityInternalPermission();
        mProxyTracker.setGlobalProxy(proxyProperties);
    }

    @Override
    @Nullable
    public ProxyInfo getGlobalProxy() {
        return mProxyTracker.getGlobalProxy();
    }

    private void handleApplyDefaultProxy(ProxyInfo proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost())
                && Uri.EMPTY.equals(proxy.getPacFileUrl())) {
            proxy = null;
        }
        mProxyTracker.setDefaultProxy(proxy);
    }

    // If the proxy has changed from oldLp to newLp, resend proxy broadcast. This method gets called
    // when any network changes proxy.
    // TODO: Remove usage of broadcast extras as they are deprecated and not applicable in a
    // multi-network world where an app might be bound to a non-default network.
    private void updateProxy(LinkProperties newLp, LinkProperties oldLp) {
        ProxyInfo newProxyInfo = newLp == null ? null : newLp.getHttpProxy();
        ProxyInfo oldProxyInfo = oldLp == null ? null : oldLp.getHttpProxy();

        if (!ProxyTracker.proxyInfoEqual(newProxyInfo, oldProxyInfo)) {
            mProxyTracker.sendProxyBroadcast();
        }
    }

    private static class SettingsObserver extends ContentObserver {
        final private HashMap<Uri, Integer> mUriEventMap;
        final private Context mContext;
        final private Handler mHandler;

        SettingsObserver(Context context, Handler handler) {
            super(null);
            mUriEventMap = new HashMap<>();
            mContext = context;
            mHandler = handler;
        }

        void observe(Uri uri, int what) {
            mUriEventMap.put(uri, what);
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(uri, false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            Slog.wtf(TAG, "Should never be reached.");
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            final Integer what = mUriEventMap.get(uri);
            if (what != null) {
                mHandler.obtainMessage(what.intValue()).sendToTarget();
            } else {
                loge("No matching event to send for URI=" + uri);
            }
        }
    }

    private static void log(String s) {
        Slog.d(TAG, s);
    }

    private static void loge(String s) {
        Slog.e(TAG, s);
    }

    private static void loge(String s, Throwable t) {
        Slog.e(TAG, s, t);
    }

    /**
     * Prepare for a VPN application.
     * VPN permissions are checked in the {@link Vpn} class. If the caller is not {@code userId},
     * {@link android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission is required.
     *
     * @param oldPackage Package name of the application which currently controls VPN, which will
     *                   be replaced. If there is no such application, this should should either be
     *                   {@code null} or {@link VpnConfig.LEGACY_VPN}.
     * @param newPackage Package name of the application which should gain control of VPN, or
     *                   {@code null} to disable.
     * @param userId User for whom to prepare the new VPN.
     *
     * @hide
     */
    @Override
    public boolean prepareVpn(@Nullable String oldPackage, @Nullable String newPackage,
            int userId) {
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            throwIfLockdownEnabled();
            Vpn vpn = mVpns.get(userId);
            if (vpn != null) {
                return vpn.prepare(oldPackage, newPackage);
            } else {
                return false;
            }
        }
    }

    /**
     * Set whether the VPN package has the ability to launch VPNs without user intervention.
     * This method is used by system-privileged apps.
     * VPN permissions are checked in the {@link Vpn} class. If the caller is not {@code userId},
     * {@link android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission is required.
     *
     * @param packageName The package for which authorization state should change.
     * @param userId User for whom {@code packageName} is installed.
     * @param authorized {@code true} if this app should be able to start a VPN connection without
     *                   explicit user approval, {@code false} if not.
     *
     * @hide
     */
    @Override
    public void setVpnPackageAuthorization(String packageName, int userId, boolean authorized) {
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn != null) {
                vpn.setPackageAuthorization(packageName, authorized);
            }
        }
    }

    /**
     * Configure a TUN interface and return its file descriptor. Parameters
     * are encoded and opaque to this class. This method is used by VpnBuilder
     * and not available in ConnectivityManager. Permissions are checked in
     * Vpn class.
     * @hide
     */
    @Override
    public ParcelFileDescriptor establishVpn(VpnConfig config) {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            return mVpns.get(user).establish(config);
        }
    }

    /**
     * Start legacy VPN, controlling native daemons as needed. Creates a
     * secondary thread to perform connection work, returning quickly.
     */
    @Override
    public void startLegacyVpn(VpnProfile profile) {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        final LinkProperties egress = getActiveLinkProperties();
        if (egress == null) {
            throw new IllegalStateException("Missing active network connection");
        }
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            mVpns.get(user).startLegacyVpn(profile, mKeyStore, egress);
        }
    }

    /**
     * Return the information of the ongoing legacy VPN. This method is used
     * by VpnSettings and not available in ConnectivityManager. Permissions
     * are checked in Vpn class.
     */
    @Override
    public LegacyVpnInfo getLegacyVpnInfo(int userId) {
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            return mVpns.get(userId).getLegacyVpnInfo();
        }
    }

    /**
     * Return the information of all ongoing VPNs. This method is used by NetworkStatsService
     * and not available in ConnectivityManager.
     */
    @Override
    public VpnInfo[] getAllVpnInfo() {
        enforceConnectivityInternalPermission();
        synchronized (mVpns) {
            if (mLockdownEnabled) {
                return new VpnInfo[0];
            }

            List<VpnInfo> infoList = new ArrayList<>();
            for (int i = 0; i < mVpns.size(); i++) {
                VpnInfo info = createVpnInfo(mVpns.valueAt(i));
                if (info != null) {
                    infoList.add(info);
                }
            }
            return infoList.toArray(new VpnInfo[infoList.size()]);
        }
    }

    /**
     * @return VPN information for accounting, or null if we can't retrieve all required
     *         information, e.g primary underlying iface.
     */
    @Nullable
    private VpnInfo createVpnInfo(Vpn vpn) {
        VpnInfo info = vpn.getVpnInfo();
        if (info == null) {
            return null;
        }
        Network[] underlyingNetworks = vpn.getUnderlyingNetworks();
        // see VpnService.setUnderlyingNetworks()'s javadoc about how to interpret
        // the underlyingNetworks list.
        if (underlyingNetworks == null) {
            NetworkAgentInfo defaultNetwork = getDefaultNetwork();
            if (defaultNetwork != null && defaultNetwork.linkProperties != null) {
                info.primaryUnderlyingIface = getDefaultNetwork().linkProperties.getInterfaceName();
            }
        } else if (underlyingNetworks.length > 0) {
            LinkProperties linkProperties = getLinkProperties(underlyingNetworks[0]);
            if (linkProperties != null) {
                info.primaryUnderlyingIface = linkProperties.getInterfaceName();
            }
        }
        return info.primaryUnderlyingIface == null ? null : info;
    }

    /**
     * Returns the information of the ongoing VPN for {@code userId}. This method is used by
     * VpnDialogs and not available in ConnectivityManager.
     * Permissions are checked in Vpn class.
     * @hide
     */
    @Override
    public VpnConfig getVpnConfig(int userId) {
        enforceCrossUserPermission(userId);
        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn != null) {
                return vpn.getVpnConfig();
            } else {
                return null;
            }
        }
    }

    /**
     * Ask all VPN objects to recompute and update their capabilities.
     *
     * When underlying networks change, VPNs may have to update capabilities to reflect things
     * like the metered bit, their transports, and so on. This asks the VPN objects to update
     * their capabilities, and as this will cause them to send messages to the ConnectivityService
     * handler thread through their agent, this is asynchronous. When the capabilities objects
     * are computed they will be up-to-date as they are computed synchronously from here and
     * this is running on the ConnectivityService thread.
     * TODO : Fix this and call updateCapabilities inline to remove out-of-order events.
     */
    private void updateAllVpnsCapabilities() {
        synchronized (mVpns) {
            for (int i = 0; i < mVpns.size(); i++) {
                final Vpn vpn = mVpns.valueAt(i);
                vpn.updateCapabilities();
            }
        }
    }

    @Override
    public boolean updateLockdownVpn() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            Slog.w(TAG, "Lockdown VPN only available to AID_SYSTEM");
            return false;
        }

        synchronized (mVpns) {
            // Tear down existing lockdown if profile was removed
            mLockdownEnabled = LockdownVpnTracker.isEnabled();
            if (mLockdownEnabled) {
                byte[] profileTag = mKeyStore.get(Credentials.LOCKDOWN_VPN);
                if (profileTag == null) {
                    Slog.e(TAG, "Lockdown VPN configured but cannot be read from keystore");
                    return false;
                }
                String profileName = new String(profileTag);
                final VpnProfile profile = VpnProfile.decode(
                        profileName, mKeyStore.get(Credentials.VPN + profileName));
                if (profile == null) {
                    Slog.e(TAG, "Lockdown VPN configured invalid profile " + profileName);
                    setLockdownTracker(null);
                    return true;
                }
                int user = UserHandle.getUserId(Binder.getCallingUid());
                Vpn vpn = mVpns.get(user);
                if (vpn == null) {
                    Slog.w(TAG, "VPN for user " + user + " not ready yet. Skipping lockdown");
                    return false;
                }
                setLockdownTracker(new LockdownVpnTracker(mContext, mNMS, this, vpn, profile));
            } else {
                setLockdownTracker(null);
            }
        }

        return true;
    }

    /**
     * Internally set new {@link LockdownVpnTracker}, shutting down any existing
     * {@link LockdownVpnTracker}. Can be {@code null} to disable lockdown.
     */
    @GuardedBy("mVpns")
    private void setLockdownTracker(LockdownVpnTracker tracker) {
        // Shutdown any existing tracker
        final LockdownVpnTracker existing = mLockdownTracker;
        // TODO: Add a trigger when the always-on VPN enable/disable to reevaluate and send the
        // necessary onBlockedStatusChanged callbacks.
        mLockdownTracker = null;
        if (existing != null) {
            existing.shutdown();
        }

        if (tracker != null) {
            mLockdownTracker = tracker;
            mLockdownTracker.init();
        }
    }

    @GuardedBy("mVpns")
    private void throwIfLockdownEnabled() {
        if (mLockdownEnabled) {
            throw new IllegalStateException("Unavailable in lockdown mode");
        }
    }

    /**
     * Starts the always-on VPN {@link VpnService} for user {@param userId}, which should perform
     * some setup and then call {@code establish()} to connect.
     *
     * @return {@code true} if the service was started, the service was already connected, or there
     *         was no always-on VPN to start. {@code false} otherwise.
     */
    private boolean startAlwaysOnVpn(int userId) {
        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                // Shouldn't happen as all code paths that point here should have checked the Vpn
                // exists already.
                Slog.wtf(TAG, "User " + userId + " has no Vpn configuration");
                return false;
            }

            return vpn.startAlwaysOnVpn();
        }
    }

    @Override
    public boolean isAlwaysOnVpnPackageSupported(int userId, String packageName) {
        enforceSettingsPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                Slog.w(TAG, "User " + userId + " has no Vpn configuration");
                return false;
            }
            return vpn.isAlwaysOnPackageSupported(packageName);
        }
    }

    @Override
    public boolean setAlwaysOnVpnPackage(
            int userId, String packageName, boolean lockdown, List<String> lockdownWhitelist) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            // Can't set always-on VPN if legacy VPN is already in lockdown mode.
            if (LockdownVpnTracker.isEnabled()) {
                return false;
            }

            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                Slog.w(TAG, "User " + userId + " has no Vpn configuration");
                return false;
            }
            if (!vpn.setAlwaysOnPackage(packageName, lockdown, lockdownWhitelist)) {
                return false;
            }
            if (!startAlwaysOnVpn(userId)) {
                vpn.setAlwaysOnPackage(null, false, null);
                return false;
            }
        }
        return true;
    }

    @Override
    public String getAlwaysOnVpnPackage(int userId) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                Slog.w(TAG, "User " + userId + " has no Vpn configuration");
                return null;
            }
            return vpn.getAlwaysOnPackage();
        }
    }

    @Override
    public boolean isVpnLockdownEnabled(int userId) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                Slog.w(TAG, "User " + userId + " has no Vpn configuration");
                return false;
            }
            return vpn.getLockdown();
        }
    }

    @Override
    public List<String> getVpnLockdownWhitelist(int userId) {
        enforceControlAlwaysOnVpnPermission();
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                Slog.w(TAG, "User " + userId + " has no Vpn configuration");
                return null;
            }
            return vpn.getLockdownWhitelist();
        }
    }

    @Override
    public int checkMobileProvisioning(int suggestedTimeOutMs) {
        // TODO: Remove?  Any reason to trigger a provisioning check?
        return -1;
    }

    /** Location to an updatable file listing carrier provisioning urls.
     *  An example:
     *
     * <?xml version="1.0" encoding="utf-8"?>
     *  <provisioningUrls>
     *   <provisioningUrl mcc="310" mnc="4">http://myserver.com/foo?mdn=%3$s&amp;iccid=%1$s&amp;imei=%2$s</provisioningUrl>
     *  </provisioningUrls>
     */
    private static final String PROVISIONING_URL_PATH =
            "/data/misc/radio/provisioning_urls.xml";
    private final File mProvisioningUrlFile = new File(PROVISIONING_URL_PATH);

    /** XML tag for root element. */
    private static final String TAG_PROVISIONING_URLS = "provisioningUrls";
    /** XML tag for individual url */
    private static final String TAG_PROVISIONING_URL = "provisioningUrl";
    /** XML attribute for mcc */
    private static final String ATTR_MCC = "mcc";
    /** XML attribute for mnc */
    private static final String ATTR_MNC = "mnc";

    private String getProvisioningUrlBaseFromFile() {
        FileReader fileReader = null;
        XmlPullParser parser = null;
        Configuration config = mContext.getResources().getConfiguration();

        try {
            fileReader = new FileReader(mProvisioningUrlFile);
            parser = Xml.newPullParser();
            parser.setInput(fileReader);
            XmlUtils.beginDocument(parser, TAG_PROVISIONING_URLS);

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (element.equals(TAG_PROVISIONING_URL)) {
                    String mcc = parser.getAttributeValue(null, ATTR_MCC);
                    try {
                        if (mcc != null && Integer.parseInt(mcc) == config.mcc) {
                            String mnc = parser.getAttributeValue(null, ATTR_MNC);
                            if (mnc != null && Integer.parseInt(mnc) == config.mnc) {
                                parser.next();
                                if (parser.getEventType() == XmlPullParser.TEXT) {
                                    return parser.getText();
                                }
                            }
                        }
                    } catch (NumberFormatException e) {
                        loge("NumberFormatException in getProvisioningUrlBaseFromFile: " + e);
                    }
                }
            }
            return null;
        } catch (FileNotFoundException e) {
            loge("Carrier Provisioning Urls file not found");
        } catch (XmlPullParserException e) {
            loge("Xml parser exception reading Carrier Provisioning Urls file: " + e);
        } catch (IOException e) {
            loge("I/O exception reading Carrier Provisioning Urls file: " + e);
        } finally {
            if (fileReader != null) {
                try {
                    fileReader.close();
                } catch (IOException e) {}
            }
        }
        return null;
    }

    @Override
    public String getMobileProvisioningUrl() {
        enforceConnectivityInternalPermission();
        String url = getProvisioningUrlBaseFromFile();
        if (TextUtils.isEmpty(url)) {
            url = mContext.getResources().getString(R.string.mobile_provisioning_url);
            log("getMobileProvisioningUrl: mobile_provisioining_url from resource =" + url);
        } else {
            log("getMobileProvisioningUrl: mobile_provisioning_url from File =" + url);
        }
        // populate the iccid, imei and phone number in the provisioning url.
        if (!TextUtils.isEmpty(url)) {
            String phoneNumber = mTelephonyManager.getLine1Number();
            if (TextUtils.isEmpty(phoneNumber)) {
                phoneNumber = "0000000000";
            }
            url = String.format(url,
                    mTelephonyManager.getSimSerialNumber() /* ICCID */,
                    mTelephonyManager.getDeviceId() /* IMEI */,
                    phoneNumber /* Phone number */);
        }

        return url;
    }

    @Override
    public void setProvisioningNotificationVisible(boolean visible, int networkType,
            String action) {
        enforceConnectivityInternalPermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            // Concatenate the range of types onto the range of NetIDs.
            int id = MAX_NET_ID + 1 + (networkType - ConnectivityManager.TYPE_NONE);
            mNotifier.setProvNotificationVisible(visible, id, action);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public void setAirplaneMode(boolean enable) {
        enforceNetworkStackSettingsOrSetup();
        final long ident = Binder.clearCallingIdentity();
        try {
            final ContentResolver cr = mContext.getContentResolver();
            Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, encodeBool(enable));
            Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intent.putExtra("state", enable);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void onUserStart(int userId) {
        synchronized (mVpns) {
            Vpn userVpn = mVpns.get(userId);
            if (userVpn != null) {
                loge("Starting user already has a VPN");
                return;
            }
            userVpn = new Vpn(mHandler.getLooper(), mContext, mNMS, userId);
            mVpns.put(userId, userVpn);
            if (mUserManager.getUserInfo(userId).isPrimary() && LockdownVpnTracker.isEnabled()) {
                updateLockdownVpn();
            }
        }
    }

    private void onUserStop(int userId) {
        synchronized (mVpns) {
            Vpn userVpn = mVpns.get(userId);
            if (userVpn == null) {
                loge("Stopped user has no VPN");
                return;
            }
            userVpn.onUserStopped();
            mVpns.delete(userId);
        }
    }

    private void onUserAdded(int userId) {
        mPermissionMonitor.onUserAdded(userId);
        synchronized (mVpns) {
            final int vpnsSize = mVpns.size();
            for (int i = 0; i < vpnsSize; i++) {
                Vpn vpn = mVpns.valueAt(i);
                vpn.onUserAdded(userId);
            }
        }
    }

    private void onUserRemoved(int userId) {
        mPermissionMonitor.onUserRemoved(userId);
        synchronized (mVpns) {
            final int vpnsSize = mVpns.size();
            for (int i = 0; i < vpnsSize; i++) {
                Vpn vpn = mVpns.valueAt(i);
                vpn.onUserRemoved(userId);
            }
        }
    }

    private void onPackageAdded(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName) || uid < 0) {
            Slog.wtf(TAG, "Invalid package in onPackageAdded: " + packageName + " | " + uid);
            return;
        }
        mPermissionMonitor.onPackageAdded(packageName, uid);
    }

    private void onPackageReplaced(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName) || uid < 0) {
            Slog.wtf(TAG, "Invalid package in onPackageReplaced: " + packageName + " | " + uid);
            return;
        }
        final int userId = UserHandle.getUserId(uid);
        synchronized (mVpns) {
            final Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                return;
            }
            // Legacy always-on VPN won't be affected since the package name is not set.
            if (TextUtils.equals(vpn.getAlwaysOnPackage(), packageName)) {
                Slog.d(TAG, "Restarting always-on VPN package " + packageName + " for user "
                        + userId);
                vpn.startAlwaysOnVpn();
            }
        }
    }

    private void onPackageRemoved(String packageName, int uid, boolean isReplacing) {
        if (TextUtils.isEmpty(packageName) || uid < 0) {
            Slog.wtf(TAG, "Invalid package in onPackageRemoved: " + packageName + " | " + uid);
            return;
        }
        mPermissionMonitor.onPackageRemoved(uid);

        final int userId = UserHandle.getUserId(uid);
        synchronized (mVpns) {
            final Vpn vpn = mVpns.get(userId);
            if (vpn == null) {
                return;
            }
            // Legacy always-on VPN won't be affected since the package name is not set.
            if (TextUtils.equals(vpn.getAlwaysOnPackage(), packageName) && !isReplacing) {
                Slog.d(TAG, "Removing always-on VPN package " + packageName + " for user "
                        + userId);
                vpn.setAlwaysOnPackage(null, false, null);
            }
        }
    }

    private void onUserUnlocked(int userId) {
        synchronized (mVpns) {
            // User present may be sent because of an unlock, which might mean an unlocked keystore.
            if (mUserManager.getUserInfo(userId).isPrimary() && LockdownVpnTracker.isEnabled()) {
                updateLockdownVpn();
            } else {
                startAlwaysOnVpn(userId);
            }
        }
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
            final Uri packageData = intent.getData();
            final String packageName =
                    packageData != null ? packageData.getSchemeSpecificPart() : null;
            if (userId == UserHandle.USER_NULL) return;

            if (Intent.ACTION_USER_STARTED.equals(action)) {
                onUserStart(userId);
            } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                onUserStop(userId);
            } else if (Intent.ACTION_USER_ADDED.equals(action)) {
                onUserAdded(userId);
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                onUserRemoved(userId);
            } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                onUserUnlocked(userId);
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                onPackageAdded(packageName, uid);
            } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                onPackageReplaced(packageName, uid);
            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                final boolean isReplacing = intent.getBooleanExtra(
                        Intent.EXTRA_REPLACING, false);
                onPackageRemoved(packageName, uid, isReplacing);
            }
        }
    };

    private BroadcastReceiver mUserPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Try creating lockdown tracker, since user present usually means
            // unlocked keystore.
            updateLockdownVpn();
            mContext.unregisterReceiver(this);
        }
    };

    private final HashMap<Messenger, NetworkFactoryInfo> mNetworkFactoryInfos = new HashMap<>();
    private final HashMap<NetworkRequest, NetworkRequestInfo> mNetworkRequests = new HashMap<>();

    private static final int MAX_NETWORK_REQUESTS_PER_UID = 100;
    // Map from UID to number of NetworkRequests that UID has filed.
    @GuardedBy("mUidToNetworkRequestCount")
    private final SparseIntArray mUidToNetworkRequestCount = new SparseIntArray();

    private static class NetworkFactoryInfo {
        public final String name;
        public final Messenger messenger;
        public final AsyncChannel asyncChannel;

        public NetworkFactoryInfo(String name, Messenger messenger, AsyncChannel asyncChannel) {
            this.name = name;
            this.messenger = messenger;
            this.asyncChannel = asyncChannel;
        }
    }

    private void ensureNetworkRequestHasType(NetworkRequest request) {
        if (request.type == NetworkRequest.Type.NONE) {
            throw new IllegalArgumentException(
                    "All NetworkRequests in ConnectivityService must have a type");
        }
    }

    /**
     * Tracks info about the requester.
     * Also used to notice when the calling process dies so we can self-expire
     */
    private class NetworkRequestInfo implements IBinder.DeathRecipient {
        final NetworkRequest request;
        final PendingIntent mPendingIntent;
        boolean mPendingIntentSent;
        private final IBinder mBinder;
        final int mPid;
        final int mUid;
        final Messenger messenger;

        NetworkRequestInfo(NetworkRequest r, PendingIntent pi) {
            request = r;
            ensureNetworkRequestHasType(request);
            mPendingIntent = pi;
            messenger = null;
            mBinder = null;
            mPid = getCallingPid();
            mUid = getCallingUid();
            enforceRequestCountLimit();
        }

        NetworkRequestInfo(Messenger m, NetworkRequest r, IBinder binder) {
            super();
            messenger = m;
            request = r;
            ensureNetworkRequestHasType(request);
            mBinder = binder;
            mPid = getCallingPid();
            mUid = getCallingUid();
            mPendingIntent = null;
            enforceRequestCountLimit();

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        private void enforceRequestCountLimit() {
            synchronized (mUidToNetworkRequestCount) {
                int networkRequests = mUidToNetworkRequestCount.get(mUid, 0) + 1;
                if (networkRequests >= MAX_NETWORK_REQUESTS_PER_UID) {
                    throw new ServiceSpecificException(
                            ConnectivityManager.Errors.TOO_MANY_REQUESTS);
                }
                mUidToNetworkRequestCount.put(mUid, networkRequests);
            }
        }

        void unlinkDeathRecipient() {
            if (mBinder != null) {
                mBinder.unlinkToDeath(this, 0);
            }
        }

        public void binderDied() {
            log("ConnectivityService NetworkRequestInfo binderDied(" +
                    request + ", " + mBinder + ")");
            releaseNetworkRequest(request);
        }

        public String toString() {
            return "uid/pid:" + mUid + "/" + mPid + " " + request +
                    (mPendingIntent == null ? "" : " to trigger " + mPendingIntent);
        }
    }

    private void ensureRequestableCapabilities(NetworkCapabilities networkCapabilities) {
        final String badCapability = networkCapabilities.describeFirstNonRequestableCapability();
        if (badCapability != null) {
            throw new IllegalArgumentException("Cannot request network with " + badCapability);
        }
    }

    // This checks that the passed capabilities either do not request a specific SSID, or the
    // calling app has permission to do so.
    private void ensureSufficientPermissionsForRequest(NetworkCapabilities nc,
            int callerPid, int callerUid) {
        if (null != nc.getSSID() && !checkSettingsPermission(callerPid, callerUid)) {
            throw new SecurityException("Insufficient permissions to request a specific SSID");
        }
    }

    private ArrayList<Integer> getSignalStrengthThresholds(NetworkAgentInfo nai) {
        final SortedSet<Integer> thresholds = new TreeSet<>();
        synchronized (nai) {
            for (NetworkRequestInfo nri : mNetworkRequests.values()) {
                if (nri.request.networkCapabilities.hasSignalStrength() &&
                        nai.satisfiesImmutableCapabilitiesOf(nri.request)) {
                    thresholds.add(nri.request.networkCapabilities.getSignalStrength());
                }
            }
        }
        return new ArrayList<>(thresholds);
    }

    private void updateSignalStrengthThresholds(
            NetworkAgentInfo nai, String reason, NetworkRequest request) {
        ArrayList<Integer> thresholdsArray = getSignalStrengthThresholds(nai);
        Bundle thresholds = new Bundle();
        thresholds.putIntegerArrayList("thresholds", thresholdsArray);

        if (VDBG || (DBG && !"CONNECT".equals(reason))) {
            String detail;
            if (request != null && request.networkCapabilities.hasSignalStrength()) {
                detail = reason + " " + request.networkCapabilities.getSignalStrength();
            } else {
                detail = reason;
            }
            log(String.format("updateSignalStrengthThresholds: %s, sending %s to %s",
                    detail, Arrays.toString(thresholdsArray.toArray()), nai.name()));
        }

        nai.asyncChannel.sendMessage(
                android.net.NetworkAgent.CMD_SET_SIGNAL_STRENGTH_THRESHOLDS,
                0, 0, thresholds);
    }

    private void ensureValidNetworkSpecifier(NetworkCapabilities nc) {
        if (nc == null) {
            return;
        }
        NetworkSpecifier ns = nc.getNetworkSpecifier();
        if (ns == null) {
            return;
        }
        MatchAllNetworkSpecifier.checkNotMatchAllNetworkSpecifier(ns);
        ns.assertValidFromUid(Binder.getCallingUid());
    }

    @Override
    public NetworkRequest requestNetwork(NetworkCapabilities networkCapabilities,
            Messenger messenger, int timeoutMs, IBinder binder, int legacyType) {
        final NetworkRequest.Type type = (networkCapabilities == null)
                ? NetworkRequest.Type.TRACK_DEFAULT
                : NetworkRequest.Type.REQUEST;
        // If the requested networkCapabilities is null, take them instead from
        // the default network request. This allows callers to keep track of
        // the system default network.
        if (type == NetworkRequest.Type.TRACK_DEFAULT) {
            networkCapabilities = createDefaultNetworkCapabilitiesForUid(Binder.getCallingUid());
            enforceAccessPermission();
        } else {
            networkCapabilities = new NetworkCapabilities(networkCapabilities);
            enforceNetworkRequestPermissions(networkCapabilities);
            // TODO: this is incorrect. We mark the request as metered or not depending on the state
            // of the app when the request is filed, but we never change the request if the app
            // changes network state. http://b/29964605
            enforceMeteredApnPolicy(networkCapabilities);
        }
        ensureRequestableCapabilities(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities,
                Binder.getCallingPid(), Binder.getCallingUid());
        // Set the UID range for this request to the single UID of the requester, or to an empty
        // set of UIDs if the caller has the appropriate permission and UIDs have not been set.
        // This will overwrite any allowed UIDs in the requested capabilities. Though there
        // are no visible methods to set the UIDs, an app could use reflection to try and get
        // networks for other apps so it's essential that the UIDs are overwritten.
        restrictRequestUidsForCaller(networkCapabilities);

        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Bad timeout specified");
        }
        ensureValidNetworkSpecifier(networkCapabilities);

        NetworkRequest networkRequest = new NetworkRequest(networkCapabilities, legacyType,
                nextNetworkRequestId(), type);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder);
        if (DBG) log("requestNetwork for " + nri);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_REQUEST, nri));
        if (timeoutMs > 0) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_TIMEOUT_NETWORK_REQUEST,
                    nri), timeoutMs);
        }
        return networkRequest;
    }

    private void enforceNetworkRequestPermissions(NetworkCapabilities networkCapabilities) {
        if (networkCapabilities.hasCapability(NET_CAPABILITY_NOT_RESTRICTED) == false) {
            enforceConnectivityRestrictedNetworksPermission();
        } else {
            enforceChangePermission();
        }
    }

    @Override
    public boolean requestBandwidthUpdate(Network network) {
        enforceAccessPermission();
        NetworkAgentInfo nai = null;
        if (network == null) {
            return false;
        }
        synchronized (mNetworkForNetId) {
            nai = mNetworkForNetId.get(network.netId);
        }
        if (nai != null) {
            nai.asyncChannel.sendMessage(android.net.NetworkAgent.CMD_REQUEST_BANDWIDTH_UPDATE);
            synchronized (mBandwidthRequests) {
                final int uid = Binder.getCallingUid();
                Integer uidReqs = mBandwidthRequests.get(uid);
                if (uidReqs == null) {
                    uidReqs = new Integer(0);
                }
                mBandwidthRequests.put(uid, ++uidReqs);
            }
            return true;
        }
        return false;
    }

    private boolean isSystem(int uid) {
        return uid < Process.FIRST_APPLICATION_UID;
    }

    private void enforceMeteredApnPolicy(NetworkCapabilities networkCapabilities) {
        final int uid = Binder.getCallingUid();
        if (isSystem(uid)) {
            // Exemption for system uid.
            return;
        }
        if (networkCapabilities.hasCapability(NET_CAPABILITY_NOT_METERED)) {
            // Policy already enforced.
            return;
        }
        if (mPolicyManagerInternal.isUidRestrictedOnMeteredNetworks(uid)) {
            // If UID is restricted, don't allow them to bring up metered APNs.
            networkCapabilities.addCapability(NET_CAPABILITY_NOT_METERED);
        }
    }

    @Override
    public NetworkRequest pendingRequestForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation) {
        checkNotNull(operation, "PendingIntent cannot be null.");
        networkCapabilities = new NetworkCapabilities(networkCapabilities);
        enforceNetworkRequestPermissions(networkCapabilities);
        enforceMeteredApnPolicy(networkCapabilities);
        ensureRequestableCapabilities(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities,
                Binder.getCallingPid(), Binder.getCallingUid());
        ensureValidNetworkSpecifier(networkCapabilities);
        restrictRequestUidsForCaller(networkCapabilities);

        NetworkRequest networkRequest = new NetworkRequest(networkCapabilities, TYPE_NONE,
                nextNetworkRequestId(), NetworkRequest.Type.REQUEST);
        NetworkRequestInfo nri = new NetworkRequestInfo(networkRequest, operation);
        if (DBG) log("pendingRequest for " + nri);
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_REQUEST_WITH_INTENT,
                nri));
        return networkRequest;
    }

    private void releasePendingNetworkRequestWithDelay(PendingIntent operation) {
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT,
                getCallingUid(), 0, operation), mReleasePendingIntentDelayMs);
    }

    @Override
    public void releasePendingNetworkRequest(PendingIntent operation) {
        checkNotNull(operation, "PendingIntent cannot be null.");
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST_WITH_INTENT,
                getCallingUid(), 0, operation));
    }

    // In order to implement the compatibility measure for pre-M apps that call
    // WifiManager.enableNetwork(..., true) without also binding to that network explicitly,
    // WifiManager registers a network listen for the purpose of calling setProcessDefaultNetwork.
    // This ensures it has permission to do so.
    private boolean hasWifiNetworkListenPermission(NetworkCapabilities nc) {
        if (nc == null) {
            return false;
        }
        int[] transportTypes = nc.getTransportTypes();
        if (transportTypes.length != 1 || transportTypes[0] != NetworkCapabilities.TRANSPORT_WIFI) {
            return false;
        }
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_WIFI_STATE,
                    "ConnectivityService");
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    @Override
    public NetworkRequest listenForNetwork(NetworkCapabilities networkCapabilities,
            Messenger messenger, IBinder binder) {
        if (!hasWifiNetworkListenPermission(networkCapabilities)) {
            enforceAccessPermission();
        }

        NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities,
                Binder.getCallingPid(), Binder.getCallingUid());
        restrictRequestUidsForCaller(nc);
        // Apps without the CHANGE_NETWORK_STATE permission can't use background networks, so
        // make all their listens include NET_CAPABILITY_FOREGROUND. That way, they will get
        // onLost and onAvailable callbacks when networks move in and out of the background.
        // There is no need to do this for requests because an app without CHANGE_NETWORK_STATE
        // can't request networks.
        restrictBackgroundRequestForCaller(nc);
        ensureValidNetworkSpecifier(nc);

        NetworkRequest networkRequest = new NetworkRequest(nc, TYPE_NONE, nextNetworkRequestId(),
                NetworkRequest.Type.LISTEN);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder);
        if (VDBG) log("listenForNetwork for " + nri);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_LISTENER, nri));
        return networkRequest;
    }

    @Override
    public void pendingListenForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation) {
        checkNotNull(operation, "PendingIntent cannot be null.");
        if (!hasWifiNetworkListenPermission(networkCapabilities)) {
            enforceAccessPermission();
        }
        ensureValidNetworkSpecifier(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities,
                Binder.getCallingPid(), Binder.getCallingUid());

        final NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        restrictRequestUidsForCaller(nc);

        NetworkRequest networkRequest = new NetworkRequest(nc, TYPE_NONE, nextNetworkRequestId(),
                NetworkRequest.Type.LISTEN);
        NetworkRequestInfo nri = new NetworkRequestInfo(networkRequest, operation);
        if (VDBG) log("pendingListenForNetwork for " + nri);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_LISTENER, nri));
    }

    @Override
    public void releaseNetworkRequest(NetworkRequest networkRequest) {
        ensureNetworkRequestHasType(networkRequest);
        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_RELEASE_NETWORK_REQUEST, getCallingUid(), 0, networkRequest));
    }

    @Override
    public void registerNetworkFactory(Messenger messenger, String name) {
        enforceConnectivityInternalPermission();
        NetworkFactoryInfo nfi = new NetworkFactoryInfo(name, messenger, new AsyncChannel());
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_FACTORY, nfi));
    }

    private void handleRegisterNetworkFactory(NetworkFactoryInfo nfi) {
        if (DBG) log("Got NetworkFactory Messenger for " + nfi.name);
        mNetworkFactoryInfos.put(nfi.messenger, nfi);
        nfi.asyncChannel.connect(mContext, mTrackerHandler, nfi.messenger);
    }

    @Override
    public void unregisterNetworkFactory(Messenger messenger) {
        enforceConnectivityInternalPermission();
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_UNREGISTER_NETWORK_FACTORY, messenger));
    }

    private void handleUnregisterNetworkFactory(Messenger messenger) {
        NetworkFactoryInfo nfi = mNetworkFactoryInfos.remove(messenger);
        if (nfi == null) {
            loge("Failed to find Messenger in unregisterNetworkFactory");
            return;
        }
        if (DBG) log("unregisterNetworkFactory for " + nfi.name);
    }

    /**
     * NetworkAgentInfo supporting a request by requestId.
     * These have already been vetted (their Capabilities satisfy the request)
     * and the are the highest scored network available.
     * the are keyed off the Requests requestId.
     */
    // NOTE: Accessed on multiple threads, must be synchronized on itself.
    @GuardedBy("mNetworkForRequestId")
    private final SparseArray<NetworkAgentInfo> mNetworkForRequestId = new SparseArray<>();

    // NOTE: Accessed on multiple threads, must be synchronized on itself.
    @GuardedBy("mNetworkForNetId")
    private final SparseArray<NetworkAgentInfo> mNetworkForNetId = new SparseArray<>();
    // NOTE: Accessed on multiple threads, synchronized with mNetworkForNetId.
    // An entry is first added to mNetIdInUse, prior to mNetworkForNetId, so
    // there may not be a strict 1:1 correlation between the two.
    @GuardedBy("mNetworkForNetId")
    private final SparseBooleanArray mNetIdInUse = new SparseBooleanArray();

    // NetworkAgentInfo keyed off its connecting messenger
    // TODO - eval if we can reduce the number of lists/hashmaps/sparsearrays
    // NOTE: Only should be accessed on ConnectivityServiceThread, except dump().
    private final HashMap<Messenger, NetworkAgentInfo> mNetworkAgentInfos = new HashMap<>();

    @GuardedBy("mBlockedAppUids")
    private final HashSet<Integer> mBlockedAppUids = new HashSet<>();

    // Note: if mDefaultRequest is changed, NetworkMonitor needs to be updated.
    private final NetworkRequest mDefaultRequest;

    // Request used to optionally keep mobile data active even when higher
    // priority networks like Wi-Fi are active.
    private final NetworkRequest mDefaultMobileDataRequest;

    // Request used to optionally keep wifi data active even when higher
    // priority networks like ethernet are active.
    private final NetworkRequest mDefaultWifiRequest;

    private NetworkAgentInfo getNetworkForRequest(int requestId) {
        synchronized (mNetworkForRequestId) {
            return mNetworkForRequestId.get(requestId);
        }
    }

    private void clearNetworkForRequest(int requestId) {
        synchronized (mNetworkForRequestId) {
            mNetworkForRequestId.remove(requestId);
        }
    }

    private void setNetworkForRequest(int requestId, NetworkAgentInfo nai) {
        synchronized (mNetworkForRequestId) {
            mNetworkForRequestId.put(requestId, nai);
        }
    }

    private NetworkAgentInfo getDefaultNetwork() {
        return getNetworkForRequest(mDefaultRequest.requestId);
    }

    private boolean isDefaultNetwork(NetworkAgentInfo nai) {
        return nai == getDefaultNetwork();
    }

    private boolean isDefaultRequest(NetworkRequestInfo nri) {
        return nri.request.requestId == mDefaultRequest.requestId;
    }

    public int registerNetworkAgent(Messenger messenger, NetworkInfo networkInfo,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int currentScore, NetworkMisc networkMisc) {
        enforceConnectivityInternalPermission();

        LinkProperties lp = new LinkProperties(linkProperties);
        lp.ensureDirectlyConnectedRoutes();
        // TODO: Instead of passing mDefaultRequest, provide an API to determine whether a Network
        // satisfies mDefaultRequest.
        final NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        final NetworkAgentInfo nai = new NetworkAgentInfo(messenger, new AsyncChannel(),
                new Network(reserveNetId()), new NetworkInfo(networkInfo), lp, nc, currentScore,
                mContext, mTrackerHandler, new NetworkMisc(networkMisc), this, mNetd, mNMS);
        // Make sure the network capabilities reflect what the agent info says.
        nai.networkCapabilities = mixInCapabilities(nai, nc);
        final String extraInfo = networkInfo.getExtraInfo();
        final String name = TextUtils.isEmpty(extraInfo)
                ? nai.networkCapabilities.getSSID() : extraInfo;
        if (DBG) log("registerNetworkAgent " + nai);
        final long token = Binder.clearCallingIdentity();
        try {
            mContext.getSystemService(NetworkStack.class)
                    .makeNetworkMonitor(nai.network, name, new NetworkMonitorCallbacks(nai));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        // NetworkAgentInfo registration will finish when the NetworkMonitor is created.
        // If the network disconnects or sends any other event before that, messages are deferred by
        // NetworkAgent until nai.asyncChannel.connect(), which will be called when finalizing the
        // registration.
        return nai.network.netId;
    }

    private void handleRegisterNetworkAgent(NetworkAgentInfo nai, INetworkMonitor networkMonitor) {
        nai.onNetworkMonitorCreated(networkMonitor);
        if (VDBG) log("Got NetworkAgent Messenger");
        mNetworkAgentInfos.put(nai.messenger, nai);
        synchronized (mNetworkForNetId) {
            mNetworkForNetId.put(nai.network.netId, nai);
        }
        synchronized (this) {
            if (mSystemReady) {
                try {
                    networkMonitor.notifySystemReady();
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }

        try {
            networkMonitor.start();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        nai.asyncChannel.connect(mContext, mTrackerHandler, nai.messenger);
        NetworkInfo networkInfo = nai.networkInfo;
        nai.networkInfo = null;
        updateNetworkInfo(nai, networkInfo);
        updateUids(nai, null, nai.networkCapabilities);
    }

    private void updateLinkProperties(NetworkAgentInfo networkAgent, LinkProperties newLp,
            LinkProperties oldLp) {
        int netId = networkAgent.network.netId;

        // The NetworkAgentInfo does not know whether clatd is running on its network or not. Before
        // we do anything else, make sure its LinkProperties are accurate.
        if (networkAgent.clatd != null) {
            networkAgent.clatd.fixupLinkProperties(oldLp, newLp);
        }

        updateInterfaces(newLp, oldLp, netId, networkAgent.networkCapabilities);
        updateMtu(newLp, oldLp);
        // TODO - figure out what to do for clat
//        for (LinkProperties lp : newLp.getStackedLinks()) {
//            updateMtu(lp, null);
//        }
        if (isDefaultNetwork(networkAgent)) {
            updateTcpBufferSizes(newLp.getTcpBufferSizes());
        }

        updateRoutes(newLp, oldLp, netId);
        updateDnses(newLp, oldLp, netId);
        // Make sure LinkProperties represents the latest private DNS status.
        // This does not need to be done before updateDnses because the
        // LinkProperties are not the source of the private DNS configuration.
        // updateDnses will fetch the private DNS configuration from DnsManager.
        mDnsManager.updatePrivateDnsStatus(netId, newLp);

        if (isDefaultNetwork(networkAgent)) {
            handleApplyDefaultProxy(newLp.getHttpProxy());
        } else {
            updateProxy(newLp, oldLp);
        }
        // TODO - move this check to cover the whole function
        if (!Objects.equals(newLp, oldLp)) {
            synchronized (networkAgent) {
                networkAgent.linkProperties = newLp;
            }
            // Start or stop clat accordingly to network state.
            networkAgent.updateClat(mNMS);
            notifyIfacesChangedForNetworkStats();
            if (networkAgent.everConnected) {
                try {
                    networkAgent.networkMonitor().notifyLinkPropertiesChanged();
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
                notifyNetworkCallbacks(networkAgent, ConnectivityManager.CALLBACK_IP_CHANGED);
            }
        }

        mKeepaliveTracker.handleCheckKeepalivesStillValid(networkAgent);
    }

    private void wakeupModifyInterface(String iface, NetworkCapabilities caps, boolean add) {
        // Marks are only available on WiFi interfaces. Checking for
        // marks on unsupported interfaces is harmless.
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return;
        }

        int mark = mContext.getResources().getInteger(
            com.android.internal.R.integer.config_networkWakeupPacketMark);
        int mask = mContext.getResources().getInteger(
            com.android.internal.R.integer.config_networkWakeupPacketMask);

        // Mask/mark of zero will not detect anything interesting.
        // Don't install rules unless both values are nonzero.
        if (mark == 0 || mask == 0) {
            return;
        }

        final String prefix = "iface:" + iface;
        try {
            if (add) {
                mNetd.wakeupAddInterface(iface, prefix, mark, mask);
            } else {
                mNetd.wakeupDelInterface(iface, prefix, mark, mask);
            }
        } catch (Exception e) {
            loge("Exception modifying wakeup packet monitoring: " + e);
        }

    }

    private void updateInterfaces(LinkProperties newLp, LinkProperties oldLp, int netId,
                                  NetworkCapabilities caps) {
        CompareResult<String> interfaceDiff = new CompareResult<>(
                oldLp != null ? oldLp.getAllInterfaceNames() : null,
                newLp != null ? newLp.getAllInterfaceNames() : null);
        for (String iface : interfaceDiff.added) {
            try {
                if (DBG) log("Adding iface " + iface + " to network " + netId);
                mNMS.addInterfaceToNetwork(iface, netId);
                wakeupModifyInterface(iface, caps, true);
            } catch (Exception e) {
                loge("Exception adding interface: " + e);
            }
        }
        for (String iface : interfaceDiff.removed) {
            try {
                if (DBG) log("Removing iface " + iface + " from network " + netId);
                wakeupModifyInterface(iface, caps, false);
                mNMS.removeInterfaceFromNetwork(iface, netId);
            } catch (Exception e) {
                loge("Exception removing interface: " + e);
            }
        }
    }

    /**
     * Have netd update routes from oldLp to newLp.
     * @return true if routes changed between oldLp and newLp
     */
    private boolean updateRoutes(LinkProperties newLp, LinkProperties oldLp, int netId) {
        // Compare the route diff to determine which routes should be added and removed.
        CompareResult<RouteInfo> routeDiff = new CompareResult<RouteInfo>(
                oldLp != null ? oldLp.getAllRoutes() : null,
                newLp != null ? newLp.getAllRoutes() : null);

        // add routes before removing old in case it helps with continuous connectivity

        // do this twice, adding non-next-hop routes first, then routes they are dependent on
        for (RouteInfo route : routeDiff.added) {
            if (route.hasGateway()) continue;
            if (VDBG || DDBG) log("Adding Route [" + route + "] to network " + netId);
            try {
                mNMS.addRoute(netId, route);
            } catch (Exception e) {
                if ((route.getDestination().getAddress() instanceof Inet4Address) || VDBG) {
                    loge("Exception in addRoute for non-gateway: " + e);
                }
            }
        }
        for (RouteInfo route : routeDiff.added) {
            if (route.hasGateway() == false) continue;
            if (VDBG || DDBG) log("Adding Route [" + route + "] to network " + netId);
            try {
                mNMS.addRoute(netId, route);
            } catch (Exception e) {
                if ((route.getGateway() instanceof Inet4Address) || VDBG) {
                    loge("Exception in addRoute for gateway: " + e);
                }
            }
        }

        for (RouteInfo route : routeDiff.removed) {
            if (VDBG || DDBG) log("Removing Route [" + route + "] from network " + netId);
            try {
                mNMS.removeRoute(netId, route);
            } catch (Exception e) {
                loge("Exception in removeRoute: " + e);
            }
        }
        return !routeDiff.added.isEmpty() || !routeDiff.removed.isEmpty();
    }

    private void updateDnses(LinkProperties newLp, LinkProperties oldLp, int netId) {
        if (oldLp != null && newLp.isIdenticalDnses(oldLp)) {
            return;  // no updating necessary
        }

        final NetworkAgentInfo defaultNai = getDefaultNetwork();
        final boolean isDefaultNetwork = (defaultNai != null && defaultNai.network.netId == netId);

        if (DBG) {
            final Collection<InetAddress> dnses = newLp.getDnsServers();
            log("Setting DNS servers for network " + netId + " to " + dnses);
        }
        try {
            mDnsManager.setDnsConfigurationForNetwork(netId, newLp, isDefaultNetwork);
        } catch (Exception e) {
            loge("Exception in setDnsConfigurationForNetwork: " + e);
        }
    }

    private int getNetworkPermission(NetworkCapabilities nc) {
        if (!nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)) {
            return INetd.PERMISSION_SYSTEM;
        }
        if (!nc.hasCapability(NET_CAPABILITY_FOREGROUND)) {
            return INetd.PERMISSION_NETWORK;
        }
        return INetd.PERMISSION_NONE;
    }

    /**
     * Augments the NetworkCapabilities passed in by a NetworkAgent with capabilities that are
     * maintained here that the NetworkAgent is not aware of (e.g., validated, captive portal,
     * and foreground status).
     */
    private NetworkCapabilities mixInCapabilities(NetworkAgentInfo nai, NetworkCapabilities nc) {
        // Once a NetworkAgent is connected, complain if some immutable capabilities are removed.
         // Don't complain for VPNs since they're not driven by requests and there is no risk of
         // causing a connect/teardown loop.
         // TODO: remove this altogether and make it the responsibility of the NetworkFactories to
         // avoid connect/teardown loops.
        if (nai.everConnected &&
                !nai.isVPN() &&
                !nai.networkCapabilities.satisfiedByImmutableNetworkCapabilities(nc)) {
            // TODO: consider not complaining when a network agent degrades its capabilities if this
            // does not cause any request (that is not a listen) currently matching that agent to
            // stop being matched by the updated agent.
            String diff = nai.networkCapabilities.describeImmutableDifferences(nc);
            if (!TextUtils.isEmpty(diff)) {
                Slog.wtf(TAG, "BUG: " + nai + " lost immutable capabilities:" + diff);
            }
        }

        // Don't modify caller's NetworkCapabilities.
        NetworkCapabilities newNc = new NetworkCapabilities(nc);
        if (nai.lastValidated) {
            newNc.addCapability(NET_CAPABILITY_VALIDATED);
        } else {
            newNc.removeCapability(NET_CAPABILITY_VALIDATED);
        }
        if (nai.lastCaptivePortalDetected) {
            newNc.addCapability(NET_CAPABILITY_CAPTIVE_PORTAL);
        } else {
            newNc.removeCapability(NET_CAPABILITY_CAPTIVE_PORTAL);
        }
        if (nai.isBackgroundNetwork()) {
            newNc.removeCapability(NET_CAPABILITY_FOREGROUND);
        } else {
            newNc.addCapability(NET_CAPABILITY_FOREGROUND);
        }
        if (nai.isSuspended()) {
            newNc.removeCapability(NET_CAPABILITY_NOT_SUSPENDED);
        } else {
            newNc.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
        }

        return newNc;
    }

    /**
     * Update the NetworkCapabilities for {@code nai} to {@code nc}. Specifically:
     *
     * 1. Calls mixInCapabilities to merge the passed-in NetworkCapabilities {@code nc} with the
     *    capabilities we manage and store in {@code nai}, such as validated status and captive
     *    portal status)
     * 2. Takes action on the result: changes network permissions, sends CAP_CHANGED callbacks, and
     *    potentially triggers rematches.
     * 3. Directly informs other network stack components (NetworkStatsService, VPNs, etc. of the
     *    change.)
     *
     * @param oldScore score of the network before any of the changes that prompted us
     *                 to call this function.
     * @param nai the network having its capabilities updated.
     * @param nc the new network capabilities.
     */
    private void updateCapabilities(int oldScore, NetworkAgentInfo nai, NetworkCapabilities nc) {
        NetworkCapabilities newNc = mixInCapabilities(nai, nc);

        if (Objects.equals(nai.networkCapabilities, newNc)) return;

        final int oldPermission = getNetworkPermission(nai.networkCapabilities);
        final int newPermission = getNetworkPermission(newNc);
        if (oldPermission != newPermission && nai.created && !nai.isVPN()) {
            try {
                mNMS.setNetworkPermission(nai.network.netId, newPermission);
            } catch (RemoteException e) {
                loge("Exception in setNetworkPermission: " + e);
            }
        }

        final NetworkCapabilities prevNc;
        synchronized (nai) {
            prevNc = nai.networkCapabilities;
            nai.networkCapabilities = newNc;
        }

        updateUids(nai, prevNc, newNc);

        if (nai.getCurrentScore() == oldScore && newNc.equalRequestableCapabilities(prevNc)) {
            // If the requestable capabilities haven't changed, and the score hasn't changed, then
            // the change we're processing can't affect any requests, it can only affect the listens
            // on this network. We might have been called by rematchNetworkAndRequests when a
            // network changed foreground state.
            processListenRequests(nai, true);
        } else {
            // If the requestable capabilities have changed or the score changed, we can't have been
            // called by rematchNetworkAndRequests, so it's safe to start a rematch.
            rematchAllNetworksAndRequests(nai, oldScore);
            try {
                nai.networkMonitor().notifyNetworkCapabilitiesChanged();
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_CAP_CHANGED);
        }

        if (prevNc != null) {
            final boolean oldMetered = prevNc.isMetered();
            final boolean newMetered = newNc.isMetered();
            final boolean meteredChanged = oldMetered != newMetered;

            if (meteredChanged) {
                maybeNotifyNetworkBlocked(nai, oldMetered, newMetered, mRestrictBackground,
                        mRestrictBackground);
            }

            final boolean roamingChanged = prevNc.hasCapability(NET_CAPABILITY_NOT_ROAMING) !=
                    newNc.hasCapability(NET_CAPABILITY_NOT_ROAMING);

            // Report changes that are interesting for network statistics tracking.
            if (meteredChanged || roamingChanged) {
                notifyIfacesChangedForNetworkStats();
            }
        }

        if (!newNc.hasTransport(TRANSPORT_VPN)) {
            // Tell VPNs about updated capabilities, since they may need to
            // bubble those changes through.
            updateAllVpnsCapabilities();
        }
    }

    private void updateUids(NetworkAgentInfo nai, NetworkCapabilities prevNc,
            NetworkCapabilities newNc) {
        Set<UidRange> prevRanges = null == prevNc ? null : prevNc.getUids();
        Set<UidRange> newRanges = null == newNc ? null : newNc.getUids();
        if (null == prevRanges) prevRanges = new ArraySet<>();
        if (null == newRanges) newRanges = new ArraySet<>();
        final Set<UidRange> prevRangesCopy = new ArraySet<>(prevRanges);

        prevRanges.removeAll(newRanges);
        newRanges.removeAll(prevRangesCopy);

        try {
            if (!newRanges.isEmpty()) {
                final UidRange[] addedRangesArray = new UidRange[newRanges.size()];
                newRanges.toArray(addedRangesArray);
                mNMS.addVpnUidRanges(nai.network.netId, addedRangesArray);
            }
            if (!prevRanges.isEmpty()) {
                final UidRange[] removedRangesArray = new UidRange[prevRanges.size()];
                prevRanges.toArray(removedRangesArray);
                mNMS.removeVpnUidRanges(nai.network.netId, removedRangesArray);
            }
        } catch (Exception e) {
            // Never crash!
            loge("Exception in updateUids: " + e);
        }
    }

    public void handleUpdateLinkProperties(NetworkAgentInfo nai, LinkProperties newLp) {
        if (getNetworkAgentInfoForNetId(nai.network.netId) != nai) {
            // Ignore updates for disconnected networks
            return;
        }
        // newLp is already a defensive copy.
        newLp.ensureDirectlyConnectedRoutes();
        if (VDBG || DDBG) {
            log("Update of LinkProperties for " + nai.name() +
                    "; created=" + nai.created +
                    "; everConnected=" + nai.everConnected);
        }
        updateLinkProperties(nai, newLp, new LinkProperties(nai.linkProperties));
    }

    private void sendUpdatedScoreToFactories(NetworkAgentInfo nai) {
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            // Don't send listening requests to factories. b/17393458
            if (nr.isListen()) continue;
            sendUpdatedScoreToFactories(nr, nai.getCurrentScore());
        }
    }

    private void sendUpdatedScoreToFactories(NetworkRequest networkRequest, int score) {
        if (VDBG || DDBG){
            log("sending new Min Network Score(" + score + "): " + networkRequest.toString());
        }
        for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
            nfi.asyncChannel.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK, score, 0,
                    networkRequest);
        }
    }

    private void sendPendingIntentForRequest(NetworkRequestInfo nri, NetworkAgentInfo networkAgent,
            int notificationType) {
        if (notificationType == ConnectivityManager.CALLBACK_AVAILABLE && !nri.mPendingIntentSent) {
            Intent intent = new Intent();
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK, networkAgent.network);
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK_REQUEST, nri.request);
            nri.mPendingIntentSent = true;
            sendIntent(nri.mPendingIntent, intent);
        }
        // else not handled
    }

    private void sendIntent(PendingIntent pendingIntent, Intent intent) {
        mPendingIntentWakeLock.acquire();
        try {
            if (DBG) log("Sending " + pendingIntent);
            pendingIntent.send(mContext, 0, intent, this /* onFinished */, null /* Handler */);
        } catch (PendingIntent.CanceledException e) {
            if (DBG) log(pendingIntent + " was not sent, it had been canceled.");
            mPendingIntentWakeLock.release();
            releasePendingNetworkRequest(pendingIntent);
        }
        // ...otherwise, mPendingIntentWakeLock.release() gets called by onSendFinished()
    }

    @Override
    public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode,
            String resultData, Bundle resultExtras) {
        if (DBG) log("Finished sending " + pendingIntent);
        mPendingIntentWakeLock.release();
        // Release with a delay so the receiving client has an opportunity to put in its
        // own request.
        releasePendingNetworkRequestWithDelay(pendingIntent);
    }

    private void callCallbackForRequest(NetworkRequestInfo nri,
            NetworkAgentInfo networkAgent, int notificationType, int arg1) {
        if (nri.messenger == null) {
            return;  // Default request has no msgr
        }
        Bundle bundle = new Bundle();
        // TODO: check if defensive copies of data is needed.
        putParcelable(bundle, new NetworkRequest(nri.request));
        Message msg = Message.obtain();
        if (notificationType != ConnectivityManager.CALLBACK_UNAVAIL) {
            putParcelable(bundle, networkAgent.network);
        }
        switch (notificationType) {
            case ConnectivityManager.CALLBACK_AVAILABLE: {
                putParcelable(bundle, networkCapabilitiesRestrictedForCallerPermissions(
                        networkAgent.networkCapabilities, nri.mPid, nri.mUid));
                putParcelable(bundle, new LinkProperties(networkAgent.linkProperties));
                // For this notification, arg1 contains the blocked status.
                msg.arg1 = arg1;
                break;
            }
            case ConnectivityManager.CALLBACK_LOSING: {
                msg.arg1 = arg1;
                break;
            }
            case ConnectivityManager.CALLBACK_CAP_CHANGED: {
                // networkAgent can't be null as it has been accessed a few lines above.
                final NetworkCapabilities nc = networkCapabilitiesRestrictedForCallerPermissions(
                        networkAgent.networkCapabilities, nri.mPid, nri.mUid);
                putParcelable(bundle, nc);
                break;
            }
            case ConnectivityManager.CALLBACK_IP_CHANGED: {
                putParcelable(bundle, new LinkProperties(networkAgent.linkProperties));
                break;
            }
            case ConnectivityManager.CALLBACK_BLK_CHANGED: {
                maybeLogBlockedStatusChanged(nri, networkAgent.network, arg1 != 0);
                msg.arg1 = arg1;
                break;
            }
        }
        msg.what = notificationType;
        msg.setData(bundle);
        try {
            if (VDBG) {
                String notification = ConnectivityManager.getCallbackName(notificationType);
                log("sending notification " + notification + " for " + nri.request);
            }
            nri.messenger.send(msg);
        } catch (RemoteException e) {
            // may occur naturally in the race of binder death.
            loge("RemoteException caught trying to send a callback msg for " + nri.request);
        }
    }

    private static <T extends Parcelable> void putParcelable(Bundle bundle, T t) {
        bundle.putParcelable(t.getClass().getSimpleName(), t);
    }

    private void teardownUnneededNetwork(NetworkAgentInfo nai) {
        if (nai.numRequestNetworkRequests() != 0) {
            for (int i = 0; i < nai.numNetworkRequests(); i++) {
                NetworkRequest nr = nai.requestAt(i);
                // Ignore listening requests.
                if (nr.isListen()) continue;
                loge("Dead network still had at least " + nr);
                break;
            }
        }
        nai.asyncChannel.disconnect();
    }

    private void handleLingerComplete(NetworkAgentInfo oldNetwork) {
        if (oldNetwork == null) {
            loge("Unknown NetworkAgentInfo in handleLingerComplete");
            return;
        }
        if (DBG) log("handleLingerComplete for " + oldNetwork.name());

        // If we get here it means that the last linger timeout for this network expired. So there
        // must be no other active linger timers, and we must stop lingering.
        oldNetwork.clearLingerState();

        if (unneeded(oldNetwork, UnneededFor.TEARDOWN)) {
            // Tear the network down.
            teardownUnneededNetwork(oldNetwork);
        } else {
            // Put the network in the background.
            updateCapabilities(oldNetwork.getCurrentScore(), oldNetwork,
                    oldNetwork.networkCapabilities);
        }
    }

    private void makeDefault(NetworkAgentInfo newNetwork) {
        if (DBG) log("Switching to new default network: " + newNetwork);

        try {
            mNMS.setDefaultNetId(newNetwork.network.netId);
        } catch (Exception e) {
            loge("Exception setting default network :" + e);
        }

        notifyLockdownVpn(newNetwork);
        handleApplyDefaultProxy(newNetwork.linkProperties.getHttpProxy());
        updateTcpBufferSizes(newNetwork.linkProperties.getTcpBufferSizes());
        mDnsManager.setDefaultDnsSystemProperties(newNetwork.linkProperties.getDnsServers());
        notifyIfacesChangedForNetworkStats();
    }

    private void processListenRequests(NetworkAgentInfo nai, boolean capabilitiesChanged) {
        // For consistency with previous behaviour, send onLost callbacks before onAvailable.
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            NetworkRequest nr = nri.request;
            if (!nr.isListen()) continue;
            if (nai.isSatisfyingRequest(nr.requestId) && !nai.satisfies(nr)) {
                nai.removeRequest(nri.request.requestId);
                callCallbackForRequest(nri, nai, ConnectivityManager.CALLBACK_LOST, 0);
            }
        }

        if (capabilitiesChanged) {
            try {
                nai.networkMonitor().notifyNetworkCapabilitiesChanged();
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_CAP_CHANGED);
        }

        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            NetworkRequest nr = nri.request;
            if (!nr.isListen()) continue;
            if (nai.satisfies(nr) && !nai.isSatisfyingRequest(nr.requestId)) {
                nai.addRequest(nr);
                notifyNetworkAvailable(nai, nri);
            }
        }
    }

    // Handles a network appearing or improving its score.
    //
    // - Evaluates all current NetworkRequests that can be
    //   satisfied by newNetwork, and reassigns to newNetwork
    //   any such requests for which newNetwork is the best.
    //
    // - Lingers any validated Networks that as a result are no longer
    //   needed. A network is needed if it is the best network for
    //   one or more NetworkRequests, or if it is a VPN.
    //
    // - Tears down newNetwork if it just became validated
    //   but turns out to be unneeded.
    //
    // - If reapUnvalidatedNetworks==REAP, tears down unvalidated
    //   networks that have no chance (i.e. even if validated)
    //   of becoming the highest scoring network.
    //
    // NOTE: This function only adds NetworkRequests that "newNetwork" could satisfy,
    // it does not remove NetworkRequests that other Networks could better satisfy.
    // If you need to handle decreases in score, use {@link rematchAllNetworksAndRequests}.
    // This function should be used when possible instead of {@code rematchAllNetworksAndRequests}
    // as it performs better by a factor of the number of Networks.
    //
    // @param newNetwork is the network to be matched against NetworkRequests.
    // @param reapUnvalidatedNetworks indicates if an additional pass over all networks should be
    //               performed to tear down unvalidated networks that have no chance (i.e. even if
    //               validated) of becoming the highest scoring network.
    private void rematchNetworkAndRequests(NetworkAgentInfo newNetwork,
            ReapUnvalidatedNetworks reapUnvalidatedNetworks, long now) {
        if (!newNetwork.everConnected) return;
        boolean keep = newNetwork.isVPN();
        boolean isNewDefault = false;
        NetworkAgentInfo oldDefaultNetwork = null;

        final boolean wasBackgroundNetwork = newNetwork.isBackgroundNetwork();
        final int score = newNetwork.getCurrentScore();

        if (VDBG || DDBG) log("rematching " + newNetwork.name());

        // Find and migrate to this Network any NetworkRequests for
        // which this network is now the best.
        ArrayList<NetworkAgentInfo> affectedNetworks = new ArrayList<>();
        ArrayList<NetworkRequestInfo> addedRequests = new ArrayList<>();
        NetworkCapabilities nc = newNetwork.networkCapabilities;
        if (VDBG) log(" network has: " + nc);
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            // Process requests in the first pass and listens in the second pass. This allows us to
            // change a network's capabilities depending on which requests it has. This is only
            // correct if the change in capabilities doesn't affect whether the network satisfies
            // requests or not, and doesn't affect the network's score.
            if (nri.request.isListen()) continue;

            final NetworkAgentInfo currentNetwork = getNetworkForRequest(nri.request.requestId);
            final boolean satisfies = newNetwork.satisfies(nri.request);
            if (newNetwork == currentNetwork && satisfies) {
                if (VDBG) {
                    log("Network " + newNetwork.name() + " was already satisfying" +
                            " request " + nri.request.requestId + ". No change.");
                }
                keep = true;
                continue;
            }

            // check if it satisfies the NetworkCapabilities
            if (VDBG) log("  checking if request is satisfied: " + nri.request);
            if (satisfies) {
                // next check if it's better than any current network we're using for
                // this request
                if (VDBG || DDBG) {
                    log("currentScore = " +
                            (currentNetwork != null ? currentNetwork.getCurrentScore() : 0) +
                            ", newScore = " + score);
                }
                if (currentNetwork == null || currentNetwork.getCurrentScore() < score) {
                    if (VDBG) log("rematch for " + newNetwork.name());
                    if (currentNetwork != null) {
                        if (VDBG || DDBG){
                            log("   accepting network in place of " + currentNetwork.name());
                        }
                        currentNetwork.removeRequest(nri.request.requestId);
                        currentNetwork.lingerRequest(nri.request, now, mLingerDelayMs);
                        affectedNetworks.add(currentNetwork);
                    } else {
                        if (VDBG || DDBG) log("   accepting network in place of null");
                    }
                    newNetwork.unlingerRequest(nri.request);
                    setNetworkForRequest(nri.request.requestId, newNetwork);
                    if (!newNetwork.addRequest(nri.request)) {
                        Slog.wtf(TAG, "BUG: " + newNetwork.name() + " already has " + nri.request);
                    }
                    addedRequests.add(nri);
                    keep = true;
                    // Tell NetworkFactories about the new score, so they can stop
                    // trying to connect if they know they cannot match it.
                    // TODO - this could get expensive if we have a lot of requests for this
                    // network.  Think about if there is a way to reduce this.  Push
                    // netid->request mapping to each factory?
                    sendUpdatedScoreToFactories(nri.request, score);
                    if (isDefaultRequest(nri)) {
                        isNewDefault = true;
                        oldDefaultNetwork = currentNetwork;
                        if (currentNetwork != null) {
                            mLingerMonitor.noteLingerDefaultNetwork(currentNetwork, newNetwork);
                        }
                    }
                }
            } else if (newNetwork.isSatisfyingRequest(nri.request.requestId)) {
                // If "newNetwork" is listed as satisfying "nri" but no longer satisfies "nri",
                // mark it as no longer satisfying "nri".  Because networks are processed by
                // rematchAllNetworksAndRequests() in descending score order, "currentNetwork" will
                // match "newNetwork" before this loop will encounter a "currentNetwork" with higher
                // score than "newNetwork" and where "currentNetwork" no longer satisfies "nri".
                // This means this code doesn't have to handle the case where "currentNetwork" no
                // longer satisfies "nri" when "currentNetwork" does not equal "newNetwork".
                if (DBG) {
                    log("Network " + newNetwork.name() + " stopped satisfying" +
                            " request " + nri.request.requestId);
                }
                newNetwork.removeRequest(nri.request.requestId);
                if (currentNetwork == newNetwork) {
                    clearNetworkForRequest(nri.request.requestId);
                    sendUpdatedScoreToFactories(nri.request, 0);
                } else {
                    Slog.wtf(TAG, "BUG: Removing request " + nri.request.requestId + " from " +
                            newNetwork.name() +
                            " without updating mNetworkForRequestId or factories!");
                }
                // TODO: Technically, sending CALLBACK_LOST here is
                // incorrect if there is a replacement network currently
                // connected that can satisfy nri, which is a request
                // (not a listen). However, the only capability that can both
                // a) be requested and b) change is NET_CAPABILITY_TRUSTED,
                // so this code is only incorrect for a network that loses
                // the TRUSTED capability, which is a rare case.
                callCallbackForRequest(nri, newNetwork, ConnectivityManager.CALLBACK_LOST, 0);
            }
        }
        if (isNewDefault) {
            updateDataActivityTracking(newNetwork, oldDefaultNetwork);
            // Notify system services that this network is up.
            makeDefault(newNetwork);
            // Log 0 -> X and Y -> X default network transitions, where X is the new default.
            metricsLogger().defaultNetworkMetrics().logDefaultNetworkEvent(
                    now, newNetwork, oldDefaultNetwork);
            // Have a new default network, release the transition wakelock in
            scheduleReleaseNetworkTransitionWakelock();
        }

        if (!newNetwork.networkCapabilities.equalRequestableCapabilities(nc)) {
            Slog.wtf(TAG, String.format(
                    "BUG: %s changed requestable capabilities during rematch: %s -> %s",
                    newNetwork.name(), nc, newNetwork.networkCapabilities));
        }
        if (newNetwork.getCurrentScore() != score) {
            Slog.wtf(TAG, String.format(
                    "BUG: %s changed score during rematch: %d -> %d",
                   newNetwork.name(), score, newNetwork.getCurrentScore()));
        }

        // Second pass: process all listens.
        if (wasBackgroundNetwork != newNetwork.isBackgroundNetwork()) {
            // If the network went from background to foreground or vice versa, we need to update
            // its foreground state. It is safe to do this after rematching the requests because
            // NET_CAPABILITY_FOREGROUND does not affect requests, as is not a requestable
            // capability and does not affect the network's score (see the Slog.wtf call above).
            updateCapabilities(score, newNetwork, newNetwork.networkCapabilities);
        } else {
            processListenRequests(newNetwork, false);
        }

        // do this after the default net is switched, but
        // before LegacyTypeTracker sends legacy broadcasts
        for (NetworkRequestInfo nri : addedRequests) notifyNetworkAvailable(newNetwork, nri);

        // Linger any networks that are no longer needed. This should be done after sending the
        // available callback for newNetwork.
        for (NetworkAgentInfo nai : affectedNetworks) {
            updateLingerState(nai, now);
        }
        // Possibly unlinger newNetwork. Unlingering a network does not send any callbacks so it
        // does not need to be done in any particular order.
        updateLingerState(newNetwork, now);

        if (isNewDefault) {
            // Maintain the illusion: since the legacy API only
            // understands one network at a time, we must pretend
            // that the current default network disconnected before
            // the new one connected.
            if (oldDefaultNetwork != null) {
                mLegacyTypeTracker.remove(oldDefaultNetwork.networkInfo.getType(),
                                          oldDefaultNetwork, true);
            }
            mDefaultInetConditionPublished = newNetwork.lastValidated ? 100 : 0;
            mLegacyTypeTracker.add(newNetwork.networkInfo.getType(), newNetwork);
            notifyLockdownVpn(newNetwork);
        }

        if (keep) {
            // Notify battery stats service about this network, both the normal
            // interface and any stacked links.
            // TODO: Avoid redoing this; this must only be done once when a network comes online.
            try {
                final IBatteryStats bs = BatteryStatsService.getService();
                final int type = newNetwork.networkInfo.getType();

                final String baseIface = newNetwork.linkProperties.getInterfaceName();
                bs.noteNetworkInterfaceType(baseIface, type);
                for (LinkProperties stacked : newNetwork.linkProperties.getStackedLinks()) {
                    final String stackedIface = stacked.getInterfaceName();
                    bs.noteNetworkInterfaceType(stackedIface, type);
                }
            } catch (RemoteException ignored) {
            }

            // This has to happen after the notifyNetworkCallbacks as that tickles each
            // ConnectivityManager instance so that legacy requests correctly bind dns
            // requests to this network.  The legacy users are listening for this broadcast
            // and will generally do a dns request so they can ensureRouteToHost and if
            // they do that before the callbacks happen they'll use the default network.
            //
            // TODO: Is there still a race here? We send the broadcast
            // after sending the callback, but if the app can receive the
            // broadcast before the callback, it might still break.
            //
            // This *does* introduce a race where if the user uses the new api
            // (notification callbacks) and then uses the old api (getNetworkInfo(type))
            // they may get old info.  Reverse this after the old startUsing api is removed.
            // This is on top of the multiple intent sequencing referenced in the todo above.
            for (int i = 0; i < newNetwork.numNetworkRequests(); i++) {
                NetworkRequest nr = newNetwork.requestAt(i);
                if (nr.legacyType != TYPE_NONE && nr.isRequest()) {
                    // legacy type tracker filters out repeat adds
                    mLegacyTypeTracker.add(nr.legacyType, newNetwork);
                }
            }

            // A VPN generally won't get added to the legacy tracker in the "for (nri)" loop above,
            // because usually there are no NetworkRequests it satisfies (e.g., mDefaultRequest
            // wants the NOT_VPN capability, so it will never be satisfied by a VPN). So, add the
            // newNetwork to the tracker explicitly (it's a no-op if it has already been added).
            if (newNetwork.isVPN()) {
                mLegacyTypeTracker.add(TYPE_VPN, newNetwork);
            }
        }
        if (reapUnvalidatedNetworks == ReapUnvalidatedNetworks.REAP) {
            for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
                if (unneeded(nai, UnneededFor.TEARDOWN)) {
                    if (nai.getLingerExpiry() > 0) {
                        // This network has active linger timers and no requests, but is not
                        // lingering. Linger it.
                        //
                        // One way (the only way?) this can happen if this network is unvalidated
                        // and became unneeded due to another network improving its score to the
                        // point where this network will no longer be able to satisfy any requests
                        // even if it validates.
                        updateLingerState(nai, now);
                    } else {
                        if (DBG) log("Reaping " + nai.name());
                        teardownUnneededNetwork(nai);
                    }
                }
            }
        }
    }

    /**
     * Attempt to rematch all Networks with NetworkRequests.  This may result in Networks
     * being disconnected.
     * @param changed If only one Network's score or capabilities have been modified since the last
     *         time this function was called, pass this Network in this argument, otherwise pass
     *         null.
     * @param oldScore If only one Network has been changed but its NetworkCapabilities have not
     *         changed, pass in the Network's score (from getCurrentScore()) prior to the change via
     *         this argument, otherwise pass {@code changed.getCurrentScore()} or 0 if
     *         {@code changed} is {@code null}. This is because NetworkCapabilities influence a
     *         network's score.
     */
    private void rematchAllNetworksAndRequests(NetworkAgentInfo changed, int oldScore) {
        // TODO: This may get slow.  The "changed" parameter is provided for future optimization
        // to avoid the slowness.  It is not simply enough to process just "changed", for
        // example in the case where "changed"'s score decreases and another network should begin
        // satisfying a NetworkRequest that "changed" currently satisfies.

        // Optimization: Only reprocess "changed" if its score improved.  This is safe because it
        // can only add more NetworkRequests satisfied by "changed", and this is exactly what
        // rematchNetworkAndRequests() handles.
        final long now = SystemClock.elapsedRealtime();
        if (changed != null && oldScore < changed.getCurrentScore()) {
            rematchNetworkAndRequests(changed, ReapUnvalidatedNetworks.REAP, now);
        } else {
            final NetworkAgentInfo[] nais = mNetworkAgentInfos.values().toArray(
                    new NetworkAgentInfo[mNetworkAgentInfos.size()]);
            // Rematch higher scoring networks first to prevent requests first matching a lower
            // scoring network and then a higher scoring network, which could produce multiple
            // callbacks and inadvertently unlinger networks.
            Arrays.sort(nais);
            for (NetworkAgentInfo nai : nais) {
                rematchNetworkAndRequests(nai,
                        // Only reap the last time through the loop.  Reaping before all rematching
                        // is complete could incorrectly teardown a network that hasn't yet been
                        // rematched.
                        (nai != nais[nais.length-1]) ? ReapUnvalidatedNetworks.DONT_REAP
                                : ReapUnvalidatedNetworks.REAP,
                        now);
            }
        }
    }

    private void updateInetCondition(NetworkAgentInfo nai) {
        // Don't bother updating until we've graduated to validated at least once.
        if (!nai.everValidated) return;
        // For now only update icons for default connection.
        // TODO: Update WiFi and cellular icons separately. b/17237507
        if (!isDefaultNetwork(nai)) return;

        int newInetCondition = nai.lastValidated ? 100 : 0;
        // Don't repeat publish.
        if (newInetCondition == mDefaultInetConditionPublished) return;

        mDefaultInetConditionPublished = newInetCondition;
        sendInetConditionBroadcast(nai.networkInfo);
    }

    private void notifyLockdownVpn(NetworkAgentInfo nai) {
        synchronized (mVpns) {
            if (mLockdownTracker != null) {
                if (nai != null && nai.isVPN()) {
                    mLockdownTracker.onVpnStateChanged(nai.networkInfo);
                } else {
                    mLockdownTracker.onNetworkInfoChanged();
                }
            }
        }
    }

    private void updateNetworkInfo(NetworkAgentInfo networkAgent, NetworkInfo newInfo) {
        final NetworkInfo.State state = newInfo.getState();
        NetworkInfo oldInfo = null;
        final int oldScore = networkAgent.getCurrentScore();
        synchronized (networkAgent) {
            oldInfo = networkAgent.networkInfo;
            networkAgent.networkInfo = newInfo;
        }
        notifyLockdownVpn(networkAgent);

        if (DBG) {
            log(networkAgent.name() + " EVENT_NETWORK_INFO_CHANGED, going from " +
                    (oldInfo == null ? "null" : oldInfo.getState()) +
                    " to " + state);
        }

        if (!networkAgent.created
                && (state == NetworkInfo.State.CONNECTED
                || (state == NetworkInfo.State.CONNECTING && networkAgent.isVPN()))) {

            // A network that has just connected has zero requests and is thus a foreground network.
            networkAgent.networkCapabilities.addCapability(NET_CAPABILITY_FOREGROUND);

            try {
                // This should never fail.  Specifying an already in use NetID will cause failure.
                if (networkAgent.isVPN()) {
                    mNMS.createVirtualNetwork(networkAgent.network.netId,
                            (networkAgent.networkMisc == null ||
                                !networkAgent.networkMisc.allowBypass));
                } else {
                    mNMS.createPhysicalNetwork(networkAgent.network.netId,
                            getNetworkPermission(networkAgent.networkCapabilities));
                }
            } catch (Exception e) {
                loge("Error creating network " + networkAgent.network.netId + ": "
                        + e.getMessage());
                return;
            }
            networkAgent.created = true;
        }

        if (!networkAgent.everConnected && state == NetworkInfo.State.CONNECTED) {
            networkAgent.everConnected = true;

            if (networkAgent.linkProperties == null) {
                Slog.wtf(TAG, networkAgent.name() + " connected with null LinkProperties");
            }

            handlePerNetworkPrivateDnsConfig(networkAgent, mDnsManager.getPrivateDnsConfig());
            updateLinkProperties(networkAgent, new LinkProperties(networkAgent.linkProperties),
                    null);

            // Until parceled LinkProperties are sent directly to NetworkMonitor, the connect
            // command must be sent after updating LinkProperties to maximize chances of
            // NetworkMonitor seeing the correct LinkProperties when starting.
            // TODO: pass LinkProperties to the NetworkMonitor in the notifyNetworkConnected call.
            try {
                networkAgent.networkMonitor().notifyNetworkConnected();
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            scheduleUnvalidatedPrompt(networkAgent);

            // Whether a particular NetworkRequest listen should cause signal strength thresholds to
            // be communicated to a particular NetworkAgent depends only on the network's immutable,
            // capabilities, so it only needs to be done once on initial connect, not every time the
            // network's capabilities change. Note that we do this before rematching the network,
            // so we could decide to tear it down immediately afterwards. That's fine though - on
            // disconnection NetworkAgents should stop any signal strength monitoring they have been
            // doing.
            updateSignalStrengthThresholds(networkAgent, "CONNECT", null);

            // Consider network even though it is not yet validated.
            final long now = SystemClock.elapsedRealtime();
            rematchNetworkAndRequests(networkAgent, ReapUnvalidatedNetworks.REAP, now);

            // This has to happen after matching the requests, because callbacks are just requests.
            notifyNetworkCallbacks(networkAgent, ConnectivityManager.CALLBACK_PRECHECK);
        } else if (state == NetworkInfo.State.DISCONNECTED) {
            networkAgent.asyncChannel.disconnect();
            if (networkAgent.isVPN()) {
                updateUids(networkAgent, networkAgent.networkCapabilities, null);
            }
            disconnectAndDestroyNetwork(networkAgent);
            if (networkAgent.isVPN()) {
                // As the active or bound network changes for apps, broadcast the default proxy, as
                // apps may need to update their proxy data. This is called after disconnecting from
                // VPN to make sure we do not broadcast the old proxy data.
                // TODO(b/122649188): send the broadcast only to VPN users.
                mProxyTracker.sendProxyBroadcast();
            }
        } else if ((oldInfo != null && oldInfo.getState() == NetworkInfo.State.SUSPENDED) ||
                state == NetworkInfo.State.SUSPENDED) {
            // going into or coming out of SUSPEND: re-score and notify
            if (networkAgent.getCurrentScore() != oldScore) {
                rematchAllNetworksAndRequests(networkAgent, oldScore);
            }
            updateCapabilities(networkAgent.getCurrentScore(), networkAgent,
                    networkAgent.networkCapabilities);
            // TODO (b/73132094) : remove this call once the few users of onSuspended and
            // onResumed have been removed.
            notifyNetworkCallbacks(networkAgent, (state == NetworkInfo.State.SUSPENDED ?
                    ConnectivityManager.CALLBACK_SUSPENDED :
                    ConnectivityManager.CALLBACK_RESUMED));
            mLegacyTypeTracker.update(networkAgent);
        }
    }

    private void updateNetworkScore(NetworkAgentInfo nai, int score) {
        if (VDBG || DDBG) log("updateNetworkScore for " + nai.name() + " to " + score);
        if (score < 0) {
            loge("updateNetworkScore for " + nai.name() + " got a negative score (" + score +
                    ").  Bumping score to min of 0");
            score = 0;
        }

        final int oldScore = nai.getCurrentScore();
        nai.setCurrentScore(score);

        rematchAllNetworksAndRequests(nai, oldScore);

        sendUpdatedScoreToFactories(nai);
    }

    // Notify only this one new request of the current state. Transfer all the
    // current state by calling NetworkCapabilities and LinkProperties callbacks
    // so that callers can be guaranteed to have as close to atomicity in state
    // transfer as can be supported by this current API.
    protected void notifyNetworkAvailable(NetworkAgentInfo nai, NetworkRequestInfo nri) {
        mHandler.removeMessages(EVENT_TIMEOUT_NETWORK_REQUEST, nri);
        if (nri.mPendingIntent != null) {
            sendPendingIntentForRequest(nri, nai, ConnectivityManager.CALLBACK_AVAILABLE);
            // Attempt no subsequent state pushes where intents are involved.
            return;
        }

        final boolean metered = nai.networkCapabilities.isMetered();
        final boolean blocked = isUidNetworkingWithVpnBlocked(nri.mUid, mUidRules.get(nri.mUid),
                metered, mRestrictBackground);
        callCallbackForRequest(nri, nai, ConnectivityManager.CALLBACK_AVAILABLE, blocked ? 1 : 0);
    }

    /**
     * Notify of the blocked state apps with a registered callback matching a given NAI.
     *
     * Unlike other callbacks, blocked status is different between each individual uid. So for
     * any given nai, all requests need to be considered according to the uid who filed it.
     *
     * @param nai The target NetworkAgentInfo.
     * @param oldMetered True if the previous network capabilities is metered.
     * @param newRestrictBackground True if data saver is enabled.
     */
    private void maybeNotifyNetworkBlocked(NetworkAgentInfo nai, boolean oldMetered,
            boolean newMetered, boolean oldRestrictBackground, boolean newRestrictBackground) {

        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            NetworkRequestInfo nri = mNetworkRequests.get(nr);
            final int uidRules = mUidRules.get(nri.mUid);
            final boolean oldBlocked, newBlocked;
            // mVpns lock needs to be hold here to ensure that the active VPN cannot be changed
            // between these two calls.
            synchronized (mVpns) {
                oldBlocked = isUidNetworkingWithVpnBlocked(nri.mUid, uidRules, oldMetered,
                        oldRestrictBackground);
                newBlocked = isUidNetworkingWithVpnBlocked(nri.mUid, uidRules, newMetered,
                        newRestrictBackground);
            }
            if (oldBlocked != newBlocked) {
                callCallbackForRequest(nri, nai, ConnectivityManager.CALLBACK_BLK_CHANGED,
                        encodeBool(newBlocked));
            }
        }
    }

    /**
     * Notify apps with a given UID of the new blocked state according to new uid rules.
     * @param uid The uid for which the rules changed.
     * @param newRules The new rules to apply.
     */
    private void maybeNotifyNetworkBlockedForNewUidRules(int uid, int newRules) {
        for (final NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
            final boolean metered = nai.networkCapabilities.isMetered();
            final boolean oldBlocked, newBlocked;
            // TODO: Consider that doze mode or turn on/off battery saver would deliver lots of uid
            // rules changed event. And this function actually loop through all connected nai and
            // its requests. It seems that mVpns lock will be grabbed frequently in this case.
            // Reduce the number of locking or optimize the use of lock are likely needed in future.
            synchronized (mVpns) {
                oldBlocked = isUidNetworkingWithVpnBlocked(
                        uid, mUidRules.get(uid), metered, mRestrictBackground);
                newBlocked = isUidNetworkingWithVpnBlocked(
                        uid, newRules, metered, mRestrictBackground);
            }
            if (oldBlocked == newBlocked) {
                return;
            }
            final int arg = encodeBool(newBlocked);
            for (int i = 0; i < nai.numNetworkRequests(); i++) {
                NetworkRequest nr = nai.requestAt(i);
                NetworkRequestInfo nri = mNetworkRequests.get(nr);
                if (nri != null && nri.mUid == uid) {
                    callCallbackForRequest(nri, nai, ConnectivityManager.CALLBACK_BLK_CHANGED, arg);
                }
            }
        }
    }

    private void sendLegacyNetworkBroadcast(NetworkAgentInfo nai, DetailedState state, int type) {
        // The NetworkInfo we actually send out has no bearing on the real
        // state of affairs. For example, if the default connection is mobile,
        // and a request for HIPRI has just gone away, we need to pretend that
        // HIPRI has just disconnected. So we need to set the type to HIPRI and
        // the state to DISCONNECTED, even though the network is of type MOBILE
        // and is still connected.
        NetworkInfo info = new NetworkInfo(nai.networkInfo);
        info.setType(type);
        if (state != DetailedState.DISCONNECTED) {
            info.setDetailedState(state, null, info.getExtraInfo());
            sendConnectedBroadcast(info);
        } else {
            info.setDetailedState(state, info.getReason(), info.getExtraInfo());
            Intent intent = new Intent(ConnectivityManager.CONNECTIVITY_ACTION);
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, info);
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, info.getType());
            if (info.isFailover()) {
                intent.putExtra(ConnectivityManager.EXTRA_IS_FAILOVER, true);
                nai.networkInfo.setFailover(false);
            }
            if (info.getReason() != null) {
                intent.putExtra(ConnectivityManager.EXTRA_REASON, info.getReason());
            }
            if (info.getExtraInfo() != null) {
                intent.putExtra(ConnectivityManager.EXTRA_EXTRA_INFO, info.getExtraInfo());
            }
            NetworkAgentInfo newDefaultAgent = null;
            if (nai.isSatisfyingRequest(mDefaultRequest.requestId)) {
                newDefaultAgent = getDefaultNetwork();
                if (newDefaultAgent != null) {
                    intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO,
                            newDefaultAgent.networkInfo);
                } else {
                    intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
                }
            }
            intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION,
                    mDefaultInetConditionPublished);
            sendStickyBroadcast(intent);
            if (newDefaultAgent != null) {
                sendConnectedBroadcast(newDefaultAgent.networkInfo);
            }
        }
    }

    protected void notifyNetworkCallbacks(NetworkAgentInfo networkAgent, int notifyType, int arg1) {
        if (VDBG || DDBG) {
            String notification = ConnectivityManager.getCallbackName(notifyType);
            log("notifyType " + notification + " for " + networkAgent.name());
        }
        for (int i = 0; i < networkAgent.numNetworkRequests(); i++) {
            NetworkRequest nr = networkAgent.requestAt(i);
            NetworkRequestInfo nri = mNetworkRequests.get(nr);
            if (VDBG) log(" sending notification for " + nr);
            // TODO: if we're in the middle of a rematch, can we send a CAP_CHANGED callback for
            // a network that no longer satisfies the listen?
            if (nri.mPendingIntent == null) {
                callCallbackForRequest(nri, networkAgent, notifyType, arg1);
            } else {
                sendPendingIntentForRequest(nri, networkAgent, notifyType);
            }
        }
    }

    protected void notifyNetworkCallbacks(NetworkAgentInfo networkAgent, int notifyType) {
        notifyNetworkCallbacks(networkAgent, notifyType, 0);
    }

    /**
     * Returns the list of all interfaces that could be used by network traffic that does not
     * explicitly specify a network. This includes the default network, but also all VPNs that are
     * currently connected.
     *
     * Must be called on the handler thread.
     */
    private Network[] getDefaultNetworks() {
        ArrayList<Network> defaultNetworks = new ArrayList<>();
        NetworkAgentInfo defaultNetwork = getDefaultNetwork();
        for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
            if (nai.everConnected && (nai == defaultNetwork || nai.isVPN())) {
                defaultNetworks.add(nai.network);
            }
        }
        return defaultNetworks.toArray(new Network[0]);
    }

    /**
     * Notify NetworkStatsService that the set of active ifaces has changed, or that one of the
     * properties tracked by NetworkStatsService on an active iface has changed.
     */
    private void notifyIfacesChangedForNetworkStats() {
        try {
            mStatsService.forceUpdateIfaces(getDefaultNetworks());
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean addVpnAddress(String address, int prefixLength) {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            return mVpns.get(user).addAddress(address, prefixLength);
        }
    }

    @Override
    public boolean removeVpnAddress(String address, int prefixLength) {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            return mVpns.get(user).removeAddress(address, prefixLength);
        }
    }

    @Override
    public boolean setUnderlyingNetworksForVpn(Network[] networks) {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        final boolean success;
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            success = mVpns.get(user).setUnderlyingNetworks(networks);
        }
        if (success) {
            mHandler.post(() -> notifyIfacesChangedForNetworkStats());
        }
        return success;
    }

    @Override
    public String getCaptivePortalServerUrl() {
        enforceConnectivityInternalPermission();
        return NetworkMonitorUtils.getCaptivePortalServerHttpUrl(mContext);
    }

    @Override
    public void startNattKeepalive(Network network, int intervalSeconds, Messenger messenger,
            IBinder binder, String srcAddr, int srcPort, String dstAddr) {
        enforceKeepalivePermission();
        mKeepaliveTracker.startNattKeepalive(
                getNetworkAgentInfoForNetwork(network),
                intervalSeconds, messenger, binder,
                srcAddr, srcPort, dstAddr, ConnectivityManager.PacketKeepalive.NATT_PORT);
    }

    @Override
    public void startNattKeepaliveWithFd(Network network, FileDescriptor fd, int resourceId,
            int intervalSeconds, Messenger messenger, IBinder binder, String srcAddr,
            String dstAddr) {
        enforceKeepalivePermission();
        mKeepaliveTracker.startNattKeepalive(
                getNetworkAgentInfoForNetwork(network), fd, resourceId,
                intervalSeconds, messenger, binder,
                srcAddr, dstAddr, NattSocketKeepalive.NATT_PORT);
    }

    @Override
    public void stopKeepalive(Network network, int slot) {
        mHandler.sendMessage(mHandler.obtainMessage(
                NetworkAgent.CMD_STOP_PACKET_KEEPALIVE, slot, PacketKeepalive.SUCCESS, network));
    }

    @Override
    public void factoryReset() {
        enforceConnectivityInternalPermission();

        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_NETWORK_RESET)) {
            return;
        }

        final int userId = UserHandle.getCallingUserId();

        // Turn airplane mode off
        setAirplaneMode(false);

        if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)) {
            // Untether
            String pkgName = mContext.getOpPackageName();
            for (String tether : getTetheredIfaces()) {
                untether(tether, pkgName);
            }
        }

        if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) {
            // Remove always-on package
            synchronized (mVpns) {
                final String alwaysOnPackage = getAlwaysOnVpnPackage(userId);
                if (alwaysOnPackage != null) {
                    setAlwaysOnVpnPackage(userId, null, false, null);
                    setVpnPackageAuthorization(alwaysOnPackage, userId, false);
                }

                // Turn Always-on VPN off
                if (mLockdownEnabled && userId == UserHandle.USER_SYSTEM) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mKeyStore.delete(Credentials.LOCKDOWN_VPN);
                        mLockdownEnabled = false;
                        setLockdownTracker(null);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }

                // Turn VPN off
                VpnConfig vpnConfig = getVpnConfig(userId);
                if (vpnConfig != null) {
                    if (vpnConfig.legacy) {
                        prepareVpn(VpnConfig.LEGACY_VPN, VpnConfig.LEGACY_VPN, userId);
                    } else {
                        // Prevent this app (packagename = vpnConfig.user) from initiating
                        // VPN connections in the future without user intervention.
                        setVpnPackageAuthorization(vpnConfig.user, userId, false);

                        prepareVpn(null, VpnConfig.LEGACY_VPN, userId);
                    }
                }
            }
        }

        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.NETWORK_AVOID_BAD_WIFI, null);
    }

    @Override
    public byte[] getNetworkWatchlistConfigHash() {
        NetworkWatchlistManager nwm = mContext.getSystemService(NetworkWatchlistManager.class);
        if (nwm == null) {
            loge("Unable to get NetworkWatchlistManager");
            return null;
        }
        // Redirect it to network watchlist service to access watchlist file and calculate hash.
        return nwm.getWatchlistConfigHash();
    }

    @VisibleForTesting
    MultinetworkPolicyTracker createMultinetworkPolicyTracker(Context c, Handler h, Runnable r) {
        return new MultinetworkPolicyTracker(c, h, r);
    }

    @VisibleForTesting
    public WakeupMessage makeWakeupMessage(Context c, Handler h, String s, int cmd, Object obj) {
        return new WakeupMessage(c, h, s, cmd, 0, 0, obj);
    }

    @VisibleForTesting
    public boolean hasService(String name) {
        return ServiceManager.checkService(name) != null;
    }

    @VisibleForTesting
    protected IpConnectivityMetrics.Logger metricsLogger() {
        return checkNotNull(LocalServices.getService(IpConnectivityMetrics.Logger.class),
                "no IpConnectivityMetrics service");
    }

    private void logNetworkEvent(NetworkAgentInfo nai, int evtype) {
        int[] transports = nai.networkCapabilities.getTransportTypes();
        mMetricsLog.log(nai.network.netId, transports, new NetworkEvent(evtype));
    }

    private static boolean toBool(int encodedBoolean) {
        return encodedBoolean != 0; // Only 0 means false.
    }

    private static int encodeBool(boolean b) {
        return b ? 1 : 0;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new ShellCmd()).exec(this, in, out, err, args, callback, resultReceiver);
    }

    private class ShellCmd extends ShellCommand {

        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            final PrintWriter pw = getOutPrintWriter();
            try {
                switch (cmd) {
                    case "airplane-mode":
                        final String action = getNextArg();
                        if ("enable".equals(action)) {
                            setAirplaneMode(true);
                            return 0;
                        } else if ("disable".equals(action)) {
                            setAirplaneMode(false);
                            return 0;
                        } else if (action == null) {
                            final ContentResolver cr = mContext.getContentResolver();
                            final int enabled = Settings.Global.getInt(cr,
                                    Settings.Global.AIRPLANE_MODE_ON);
                            pw.println(enabled == 0 ? "disabled" : "enabled");
                            return 0;
                        } else {
                            onHelp();
                            return -1;
                        }
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (Exception e) {
                pw.println(e);
            }
            return -1;
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Connectivity service commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  airplane-mode [enable|disable]");
            pw.println("    Turn airplane mode on or off.");
            pw.println("  airplane-mode");
            pw.println("    Get airplane mode.");
        }
    }

    @GuardedBy("mVpns")
    private Vpn getVpnIfOwner() {
        final int uid = Binder.getCallingUid();
        final int user = UserHandle.getUserId(uid);

        final Vpn vpn = mVpns.get(user);
        if (vpn == null) {
            return null;
        } else {
            final VpnInfo info = vpn.getVpnInfo();
            return (info == null || info.ownerUid != uid) ? null : vpn;
        }
    }

    /**
     * Caller either needs to be an active VPN, or hold the NETWORK_STACK permission
     * for testing.
     */
    private Vpn enforceActiveVpnOrNetworkStackPermission() {
        if (checkNetworkStackPermission()) {
            return null;
        }
        synchronized (mVpns) {
            Vpn vpn = getVpnIfOwner();
            if (vpn != null) {
                return vpn;
            }
        }
        throw new SecurityException("App must either be an active VPN or have the NETWORK_STACK "
                + "permission");
    }

    /**
     * @param connectionInfo the connection to resolve.
     * @return {@code uid} if the connection is found and the app has permission to observe it
     * (e.g., if it is associated with the calling VPN app's tunnel) or {@code INVALID_UID} if the
     * connection is not found.
     */
    public int getConnectionOwnerUid(ConnectionInfo connectionInfo) {
        final Vpn vpn = enforceActiveVpnOrNetworkStackPermission();
        if (connectionInfo.protocol != IPPROTO_TCP && connectionInfo.protocol != IPPROTO_UDP) {
            throw new IllegalArgumentException("Unsupported protocol " + connectionInfo.protocol);
        }

        final int uid = InetDiagMessage.getConnectionOwnerUid(connectionInfo.protocol,
                connectionInfo.local, connectionInfo.remote);

        /* Filter out Uids not associated with the VPN. */
        if (vpn != null && !vpn.appliesToUid(uid)) {
            return INVALID_UID;
        }

        return uid;
    }

    @Override
    public boolean isCallerCurrentAlwaysOnVpnApp() {
        synchronized (mVpns) {
            Vpn vpn = getVpnIfOwner();
            return vpn != null && vpn.getAlwaysOn();
        }
    }

    @Override
    public boolean isCallerCurrentAlwaysOnVpnLockdownApp() {
        synchronized (mVpns) {
            Vpn vpn = getVpnIfOwner();
            return vpn != null && vpn.getLockdown();
        }
    }
}
