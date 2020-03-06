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
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OPPORTUNISTIC;
import static android.net.ConnectivityManager.TYPE_ETHERNET;
import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.ConnectivityManager.TYPE_VPN;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.ConnectivityManager.isNetworkTypeValid;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_PROBE_PRIVDNS;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_RESULT_PARTIAL;
import static android.net.INetworkMonitor.NETWORK_VALIDATION_RESULT_VALID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL;
import static android.net.NetworkCapabilities.NET_CAPABILITY_FOREGROUND;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkPolicyManager.RULE_NONE;
import static android.net.NetworkPolicyManager.uidRulesToString;
import static android.net.shared.NetworkMonitorUtils.isPrivateDnsValidationRequired;
import static android.os.Process.INVALID_UID;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

import static com.android.internal.util.Preconditions.checkNotNull;

import static java.util.Map.Entry;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
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
import android.net.CaptivePortal;
import android.net.CaptivePortalData;
import android.net.ConnectionInfo;
import android.net.ConnectivityDiagnosticsManager.ConnectivityReport;
import android.net.ConnectivityDiagnosticsManager.DataStallReport;
import android.net.ConnectivityManager;
import android.net.ICaptivePortal;
import android.net.IConnectivityDiagnosticsCallback;
import android.net.IConnectivityManager;
import android.net.IDnsResolver;
import android.net.IIpConnectivityMetrics;
import android.net.INetd;
import android.net.INetdEventCallback;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.ISocketKeepaliveCallback;
import android.net.InetAddresses;
import android.net.IpMemoryStore;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.MatchAllNetworkSpecifier;
import android.net.NattSocketKeepalive;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkAgentConfig;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkMonitorManager;
import android.net.NetworkPolicyManager;
import android.net.NetworkProvider;
import android.net.NetworkQuotaInfo;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkStack;
import android.net.NetworkStackClient;
import android.net.NetworkState;
import android.net.NetworkUtils;
import android.net.NetworkWatchlistManager;
import android.net.PrivateDnsConfigParcel;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.RouteInfoParcel;
import android.net.SocketKeepalive;
import android.net.TetheringManager;
import android.net.UidRange;
import android.net.Uri;
import android.net.VpnManager;
import android.net.VpnService;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkEvent;
import android.net.netlink.InetDiagMessage;
import android.net.shared.PrivateDnsConfig;
import android.net.util.LinkPropertiesUtils.CompareOrUpdateResult;
import android.net.util.LinkPropertiesUtils.CompareResult;
import android.net.util.MultinetworkPolicyTracker;
import android.net.util.NetdService;
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
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
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
import com.android.internal.util.LocationPermissionChecker;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.XmlUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.connectivity.AutodestructReference;
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
import com.android.server.connectivity.NetworkRanker;
import com.android.server.connectivity.PermissionMonitor;
import com.android.server.connectivity.ProxyTracker;
import com.android.server.connectivity.Vpn;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * @hide
 */
public class ConnectivityService extends IConnectivityManager.Stub
        implements PendingIntent.OnFinished {
    private static final String TAG = ConnectivityService.class.getSimpleName();

    private static final String DIAG_ARG = "--diag";
    public static final String SHORT_ARG = "--short";
    private static final String NETWORK_ARG = "networks";
    private static final String REQUEST_ARG = "requests";

    private static final boolean DBG = true;
    private static final boolean DDBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean VDBG = Log.isLoggable(TAG, Log.VERBOSE);

    private static final boolean LOGD_BLOCKED_NETWORKINFO = true;

    /**
     * Default URL to use for {@link #getCaptivePortalServerUrl()}. This should not be changed
     * by OEMs for configuration purposes, as this value is overridden by
     * Settings.Global.CAPTIVE_PORTAL_HTTP_URL.
     * R.string.config_networkCaptivePortalServerUrl should be overridden instead for this purpose
     * (preferably via runtime resource overlays).
     */
    private static final String DEFAULT_CAPTIVE_PORTAL_HTTP_URL =
            "http://connectivitycheck.gstatic.com/generate_204";

    // TODO: create better separation between radio types and network types

    // how long to wait before switching back to a radio's default network
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME =
            "android.telephony.apn-restore";

    // How long to wait before putting up a "This network doesn't have an Internet connection,
    // connect anyway?" dialog after the user selects a network that doesn't validate.
    private static final int PROMPT_UNVALIDATED_DELAY_MS = 8 * 1000;

    // Default to 30s linger time-out. Modifiable only for testing.
    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    private static final int DEFAULT_LINGER_DELAY_MS = 30_000;
    @VisibleForTesting
    protected int mLingerDelayMs;  // Can't be final, or test subclass constructors can't change it.

    // How long to delay to removal of a pending intent based request.
    // See Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS
    private final int mReleasePendingIntentDelayMs;

    private MockableSystemProperties mSystemProperties;

    @VisibleForTesting
    protected final PermissionMonitor mPermissionMonitor;

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

    private final Context mContext;
    private final Dependencies mDeps;
    // 0 is full bad, 100 is full good
    private int mDefaultInetConditionPublished = 0;

    private INetworkManagementService mNMS;
    @VisibleForTesting
    protected IDnsResolver mDnsResolver;
    @VisibleForTesting
    protected INetd mNetd;
    private INetworkStatsService mStatsService;
    private INetworkPolicyManager mPolicyManager;
    private NetworkPolicyManagerInternal mPolicyManagerInternal;

    /**
     * TestNetworkService (lazily) created upon first usage. Locked to prevent creation of multiple
     * instances.
     */
    @GuardedBy("mTNSLock")
    private TestNetworkService mTNS;

    private final Object mTNSLock = new Object();

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
     * used internally when registering NetworkProviders
     * obj = NetworkProviderInfo
     */
    private static final int EVENT_REGISTER_NETWORK_PROVIDER = 17;

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
     * used internally when registering NetworkProviders
     * obj = Messenger
     */
    private static final int EVENT_UNREGISTER_NETWORK_PROVIDER = 23;

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
      * obj = {@link NetworkTestedResults} representing information sent from NetworkMonitor.
      * data = PersistableBundle of extras passed from NetworkMonitor. If {@link
      * NetworkMonitorCallbacks#notifyNetworkTested} is called, this will be null.
      */
    private static final int EVENT_NETWORK_TESTED = 41;

    /**
     * Event for NetworkMonitor/NetworkAgentInfo to inform ConnectivityService that the private DNS
     * config was resolved.
     * obj = PrivateDnsConfig
     * arg2 = netid
     */
    private static final int EVENT_PRIVATE_DNS_CONFIG_RESOLVED = 42;

    /**
     * Request ConnectivityService display provisioning notification.
     * arg1    = Whether to make the notification visible.
     * arg2    = NetID.
     * obj     = Intent to be launched when notification selected by user, null if !arg1.
     */
    private static final int EVENT_PROVISIONING_NOTIFICATION = 43;

    /**
     * Used to specify whether a network should be used even if connectivity is partial.
     * arg1 = whether to accept the network if its connectivity is partial (1 for true or 0 for
     * false)
     * arg2 = whether to remember this choice in the future (1 for true or 0 for false)
     * obj  = network
     */
    private static final int EVENT_SET_ACCEPT_PARTIAL_CONNECTIVITY = 44;

    /**
     * Event for NetworkMonitor to inform ConnectivityService that the probe status has changed.
     * Both of the arguments are bitmasks, and the value of bits come from
     * INetworkMonitor.NETWORK_VALIDATION_PROBE_*.
     * arg1 = A bitmask to describe which probes are completed.
     * arg2 = A bitmask to describe which probes are successful.
     */
    public static final int EVENT_PROBE_STATUS_CHANGED = 45;

    /**
     * Event for NetworkMonitor to inform ConnectivityService that captive portal data has changed.
     * arg1 = unused
     * arg2 = netId
     * obj = captive portal data
     */
    private static final int EVENT_CAPPORT_DATA_CHANGED = 46;

    /**
     * Argument for {@link #EVENT_PROVISIONING_NOTIFICATION} to indicate that the notification
     * should be shown.
     */
    private static final int PROVISIONING_NOTIFICATION_SHOW = 1;

    /**
     * Argument for {@link #EVENT_PROVISIONING_NOTIFICATION} to indicate that the notification
     * should be hidden.
     */
    private static final int PROVISIONING_NOTIFICATION_HIDE = 0;

    private static String eventName(int what) {
        return sMagicDecoderRing.get(what, Integer.toString(what));
    }

    private static IDnsResolver getDnsResolver() {
        return IDnsResolver.Stub
                .asInterface(ServiceManager.getService("dnsresolver"));
    }

    /** Handler thread used for all of the handlers below. */
    @VisibleForTesting
    protected final HandlerThread mHandlerThread;
    /** Handler used for internal events. */
    final private InternalHandler mHandler;
    /** Handler used for incoming {@link NetworkStateTracker} events. */
    final private NetworkStateTrackerHandler mTrackerHandler;
    /** Handler used for processing {@link android.net.ConnectivityDiagnosticsManager} events */
    @VisibleForTesting
    final ConnectivityDiagnosticsHandler mConnectivityDiagnosticsHandler;

    private final DnsManager mDnsManager;
    private final NetworkRanker mNetworkRanker;

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

    private Set<String> mWolSupportedInterfaces;

    private final TelephonyManager mTelephonyManager;
    private final AppOpsManager mAppOpsManager;

    private final LocationPermissionChecker mLocationPermissionChecker;

    private KeepaliveTracker mKeepaliveTracker;
    private NetworkNotificationManager mNotifier;
    private LingerMonitor mLingerMonitor;

    // sequence number of NetworkRequests
    private int mNextNetworkRequestId = 1;

    // Sequence number for NetworkProvider IDs.
    private final AtomicInteger mNextNetworkProviderId = new AtomicInteger(
            NetworkProvider.FIRST_PROVIDER_ID);

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

    @VisibleForTesting
    final Map<IBinder, ConnectivityDiagnosticsCallbackInfo> mConnectivityDiagnosticsCallbacks =
            new HashMap<>();

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
    @VisibleForTesting
    static class LegacyTypeTracker {

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
        @NonNull
        private final ConnectivityService mService;

        LegacyTypeTracker(@NonNull ConnectivityService service) {
            mService = service;
            mTypeLists = new ArrayList[ConnectivityManager.MAX_NETWORK_TYPE + 1];
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
                log("Sending " + state
                        + " broadcast for type " + type + " " + nai.toShortString()
                        + " isDefaultNetwork=" + isDefaultNetwork);
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
            final boolean isDefaultNetwork = mService.isDefaultNetwork(nai);
            if ((list.size() == 1) || isDefaultNetwork) {
                maybeLogBroadcast(nai, DetailedState.CONNECTED, type, isDefaultNetwork);
                mService.sendLegacyNetworkBroadcast(nai, DetailedState.CONNECTED, type);
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

            if (wasFirstNetwork || wasDefault) {
                maybeLogBroadcast(nai, DetailedState.DISCONNECTED, type, wasDefault);
                mService.sendLegacyNetworkBroadcast(nai, DetailedState.DISCONNECTED, type);
            }

            if (!list.isEmpty() && wasFirstNetwork) {
                if (DBG) log("Other network available for type " + type +
                              ", sending connected broadcast");
                final NetworkAgentInfo replacement = list.get(0);
                maybeLogBroadcast(replacement, DetailedState.CONNECTED, type,
                        mService.isDefaultNetwork(replacement));
                mService.sendLegacyNetworkBroadcast(replacement, DetailedState.CONNECTED, type);
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
            final boolean isDefault = mService.isDefaultNetwork(nai);
            final DetailedState state = nai.networkInfo.getDetailedState();
            for (int type = 0; type < mTypeLists.length; type++) {
                final ArrayList<NetworkAgentInfo> list = mTypeLists[type];
                final boolean contains = (list != null && list.contains(nai));
                final boolean isFirst = contains && (nai == list.get(0));
                if (isFirst || contains && isDefault) {
                    maybeLogBroadcast(nai, state, type, isDefault);
                    mService.sendLegacyNetworkBroadcast(nai, state, type);
                }
            }
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
                        pw.println(type + " " + nai.toShortString());
                    }
                }
            }
            pw.decreaseIndent();
            pw.decreaseIndent();
            pw.println();
        }
    }
    private final LegacyTypeTracker mLegacyTypeTracker = new LegacyTypeTracker(this);

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

    /**
     * Dependencies of ConnectivityService, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Get system properties to use in ConnectivityService.
         */
        public MockableSystemProperties getSystemProperties() {
            return new MockableSystemProperties();
        }

        /**
         * Create a HandlerThread to use in ConnectivityService.
         */
        public HandlerThread makeHandlerThread() {
            return new HandlerThread("ConnectivityServiceThread");
        }

        /**
         * Get a reference to the NetworkStackClient.
         */
        public NetworkStackClient getNetworkStack() {
            return NetworkStackClient.getInstance();
        }

        /**
         * @see ProxyTracker
         */
        public ProxyTracker makeProxyTracker(@NonNull Context context,
                @NonNull Handler connServiceHandler) {
            return new ProxyTracker(context, connServiceHandler, EVENT_PROXY_HAS_CHANGED);
        }

        /**
         * @see NetIdManager
         */
        public NetIdManager makeNetIdManager() {
            return new NetIdManager();
        }

        /**
         * @see NetworkUtils#queryUserAccess(int, int)
         */
        public boolean queryUserAccess(int uid, int netId) {
            return NetworkUtils.queryUserAccess(uid, netId);
        }

        /**
         * @see MultinetworkPolicyTracker
         */
        public MultinetworkPolicyTracker makeMultinetworkPolicyTracker(
                @NonNull Context c, @NonNull Handler h, @NonNull Runnable r) {
            return new MultinetworkPolicyTracker(c, h, r);
        }

        /**
         * @see ServiceManager#checkService(String)
         */
        public boolean hasService(@NonNull String name) {
            return ServiceManager.checkService(name) != null;
        }

        /**
         * @see IpConnectivityMetrics.Logger
         */
        public IpConnectivityMetrics.Logger getMetricsLogger() {
            return checkNotNull(LocalServices.getService(IpConnectivityMetrics.Logger.class),
                    "no IpConnectivityMetrics service");
        }

        /**
         * @see IpConnectivityMetrics
         */
        public IIpConnectivityMetrics getIpConnectivityMetrics() {
            return IIpConnectivityMetrics.Stub.asInterface(
                    ServiceManager.getService(IpConnectivityLog.SERVICE_NAME));
        }

        public IBatteryStats getBatteryStatsService() {
            return BatteryStatsService.getService();
        }
    }

    public ConnectivityService(Context context, INetworkManagementService netManager,
            INetworkStatsService statsService, INetworkPolicyManager policyManager) {
        this(context, netManager, statsService, policyManager, getDnsResolver(),
                new IpConnectivityLog(), NetdService.getInstance(), new Dependencies());
    }

    @VisibleForTesting
    protected ConnectivityService(Context context, INetworkManagementService netManager,
            INetworkStatsService statsService, INetworkPolicyManager policyManager,
            IDnsResolver dnsresolver, IpConnectivityLog logger, INetd netd, Dependencies deps) {
        if (DBG) log("ConnectivityService starting up");

        mDeps = checkNotNull(deps, "missing Dependencies");
        mSystemProperties = mDeps.getSystemProperties();
        mNetIdManager = mDeps.makeNetIdManager();
        mContext = checkNotNull(context, "missing Context");

        mMetricsLog = logger;
        mDefaultRequest = createDefaultInternetRequestForTransport(-1, NetworkRequest.Type.REQUEST);
        mNetworkRanker = new NetworkRanker();
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

        mHandlerThread = mDeps.makeHandlerThread();
        mHandlerThread.start();
        mHandler = new InternalHandler(mHandlerThread.getLooper());
        mTrackerHandler = new NetworkStateTrackerHandler(mHandlerThread.getLooper());
        mConnectivityDiagnosticsHandler =
                new ConnectivityDiagnosticsHandler(mHandlerThread.getLooper());

        mReleasePendingIntentDelayMs = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS, 5_000);

        mLingerDelayMs = mSystemProperties.getInt(LINGER_DELAY_PROPERTY, DEFAULT_LINGER_DELAY_MS);

        mNMS = checkNotNull(netManager, "missing INetworkManagementService");
        mStatsService = checkNotNull(statsService, "missing INetworkStatsService");
        mPolicyManager = checkNotNull(policyManager, "missing INetworkPolicyManager");
        mPolicyManagerInternal = checkNotNull(
                LocalServices.getService(NetworkPolicyManagerInternal.class),
                "missing NetworkPolicyManagerInternal");
        mDnsResolver = checkNotNull(dnsresolver, "missing IDnsResolver");
        mProxyTracker = mDeps.makeProxyTracker(mContext, mHandler);

        mNetd = netd;
        mKeyStore = KeyStore.getInstance();
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mLocationPermissionChecker = new LocationPermissionChecker(mContext);

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
        if (mNetConfigs[TYPE_ETHERNET] == null && mDeps.hasService(Context.ETHERNET_SERVICE)) {
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

        mWolSupportedInterfaces = new ArraySet(
                mContext.getResources().getStringArray(
                        com.android.internal.R.array.config_wakeonlan_supported_interfaces));

        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);

        mPermissionMonitor = new PermissionMonitor(mContext, mNetd);

        // Set up the listener for user state for creating user VPNs.
        // Should run on mHandler to avoid any races.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_STARTED);
        intentFilter.addAction(Intent.ACTION_USER_STOPPED);
        intentFilter.addAction(Intent.ACTION_USER_ADDED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(
                mIntentReceiver,
                UserHandle.ALL,
                intentFilter,
                null /* broadcastPermission */,
                mHandler);
        mContext.registerReceiverAsUser(mUserPresentReceiver, UserHandle.SYSTEM,
                new IntentFilter(Intent.ACTION_USER_PRESENT), null, null);

        // Listen to package add and removal events for all users.
        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiverAsUser(
                mIntentReceiver,
                UserHandle.ALL,
                intentFilter,
                null /* broadcastPermission */,
                mHandler);

        try {
            mNMS.registerObserver(mDataActivityObserver);
        } catch (RemoteException e) {
            loge("Error registering observer :" + e);
        }

        mSettingsObserver = new SettingsObserver(mContext, mHandler);
        registerSettingsCallbacks();

        final DataConnectionStats dataConnectionStats = new DataConnectionStats(mContext, mHandler);
        dataConnectionStats.startMonitoring();

        mKeepaliveTracker = new KeepaliveTracker(mContext, mHandler);
        mNotifier = new NetworkNotificationManager(mContext, mTelephonyManager,
                mContext.getSystemService(NotificationManager.class));

        final int dailyLimit = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.NETWORK_SWITCH_NOTIFICATION_DAILY_LIMIT,
                LingerMonitor.DEFAULT_NOTIFICATION_DAILY_LIMIT);
        final long rateLimit = Settings.Global.getLong(mContext.getContentResolver(),
                Settings.Global.NETWORK_SWITCH_NOTIFICATION_RATE_LIMIT_MILLIS,
                LingerMonitor.DEFAULT_NOTIFICATION_RATE_LIMIT_MILLIS);
        mLingerMonitor = new LingerMonitor(mContext, mNotifier, dailyLimit, rateLimit);

        mMultinetworkPolicyTracker = mDeps.makeMultinetworkPolicyTracker(
                mContext, mHandler, () -> rematchForAvoidBadWifiUpdate());
        mMultinetworkPolicyTracker.start();

        mMultipathPolicyTracker = new MultipathPolicyTracker(mContext, mHandler);

        mDnsManager = new DnsManager(mContext, mDnsResolver, mSystemProperties);
        registerPrivateDnsSettingsCallbacks();
    }

    private static NetworkCapabilities createDefaultNetworkCapabilitiesForUid(int uid) {
        final NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(NET_CAPABILITY_INTERNET);
        netCap.removeCapability(NET_CAPABILITY_NOT_VPN);
        netCap.setSingleUid(uid);
        return netCap;
    }

    private NetworkRequest createDefaultInternetRequestForTransport(
            int transportType, NetworkRequest.Type type) {
        final NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(NET_CAPABILITY_INTERNET);
        netCap.setRequestorUidAndPackageName(Process.myUid(), mContext.getPackageName());
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
            handleReleaseNetworkRequest(networkRequest, Process.SYSTEM_UID,
                    /* callOnUnavailable */ false);
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
        NetworkStack.checkNetworkStackPermission(mContext);
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
        NetworkStack.checkNetworkStackPermission(mContext);
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
    public NetworkCapabilities[] getDefaultNetworkCapabilitiesForUser(
                int userId, String callingPackageName) {
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
            result.put(
                    nai.network,
                    maybeSanitizeLocationInfoForCaller(
                            nc, Binder.getCallingUid(), callingPackageName));
        }

        synchronized (mVpns) {
            if (!mLockdownEnabled) {
                Vpn vpn = mVpns.get(userId);
                if (vpn != null) {
                    Network[] networks = vpn.getUnderlyingNetworks();
                    if (networks != null) {
                        for (Network network : networks) {
                            nc = getNetworkCapabilitiesInternal(network);
                            if (nc != null) {
                                result.put(
                                        network,
                                        maybeSanitizeLocationInfoForCaller(
                                                nc, Binder.getCallingUid(), callingPackageName));
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
        if (state.linkProperties == null) return null;
        return linkPropertiesRestrictedForCallerPermissions(state.linkProperties,
                Binder.getCallingPid(), uid);
    }

    @Override
    public LinkProperties getLinkPropertiesForType(int networkType) {
        enforceAccessPermission();
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        final LinkProperties lp = getLinkProperties(nai);
        if (lp == null) return null;
        return linkPropertiesRestrictedForCallerPermissions(
                lp, Binder.getCallingPid(), Binder.getCallingUid());
    }

    // TODO - this should be ALL networks
    @Override
    public LinkProperties getLinkProperties(Network network) {
        enforceAccessPermission();
        final LinkProperties lp = getLinkProperties(getNetworkAgentInfoForNetwork(network));
        if (lp == null) return null;
        return linkPropertiesRestrictedForCallerPermissions(
                lp, Binder.getCallingPid(), Binder.getCallingUid());
    }

    @Nullable
    private LinkProperties getLinkProperties(@Nullable NetworkAgentInfo nai) {
        if (nai == null) {
            return null;
        }
        synchronized (nai) {
            return nai.linkProperties;
        }
    }

    private NetworkCapabilities getNetworkCapabilitiesInternal(Network network) {
        return getNetworkCapabilitiesInternal(getNetworkAgentInfoForNetwork(network));
    }

    private NetworkCapabilities getNetworkCapabilitiesInternal(NetworkAgentInfo nai) {
        if (nai == null) return null;
        synchronized (nai) {
            if (nai.networkCapabilities == null) return null;
            return networkCapabilitiesRestrictedForCallerPermissions(
                    nai.networkCapabilities, Binder.getCallingPid(), Binder.getCallingUid());
        }
    }

    @Override
    public NetworkCapabilities getNetworkCapabilities(Network network, String callingPackageName) {
        mAppOpsManager.checkPackage(Binder.getCallingUid(), callingPackageName);
        enforceAccessPermission();
        return maybeSanitizeLocationInfoForCaller(
                getNetworkCapabilitiesInternal(network),
                Binder.getCallingUid(), callingPackageName);
    }

    @VisibleForTesting
    NetworkCapabilities networkCapabilitiesRestrictedForCallerPermissions(
            NetworkCapabilities nc, int callerPid, int callerUid) {
        final NetworkCapabilities newNc = new NetworkCapabilities(nc);
        if (!checkSettingsPermission(callerPid, callerUid)) {
            newNc.setUids(null);
            newNc.setSSID(null);
        }
        if (newNc.getNetworkSpecifier() != null) {
            newNc.setNetworkSpecifier(newNc.getNetworkSpecifier().redact());
        }
        newNc.setAdministratorUids(new int[0]);

        return newNc;
    }

    @VisibleForTesting
    @Nullable
    NetworkCapabilities maybeSanitizeLocationInfoForCaller(
            @Nullable NetworkCapabilities nc, int callerUid, @NonNull String callerPkgName) {
        if (nc == null) {
            return null;
        }
        final NetworkCapabilities newNc = new NetworkCapabilities(nc);
        if (callerUid != newNc.getOwnerUid()) {
            newNc.setOwnerUid(INVALID_UID);
            return newNc;
        }

        Binder.withCleanCallingIdentity(
                () -> {
                    if (!mLocationPermissionChecker.checkLocationPermission(
                            callerPkgName, null /* featureId */, callerUid, null /* message */)) {
                        // Caller does not have the requisite location permissions. Reset the
                        // owner's UID in the NetworkCapabilities.
                        newNc.setOwnerUid(INVALID_UID);
                    }
                }
        );

        return newNc;
    }

    private LinkProperties linkPropertiesRestrictedForCallerPermissions(
            LinkProperties lp, int callerPid, int callerUid) {
        if (lp == null) return new LinkProperties();

        // Only do a permission check if sanitization is needed, to avoid unnecessary binder calls.
        final boolean needsSanitization =
                (lp.getCaptivePortalApiUrl() != null || lp.getCaptivePortalData() != null);
        if (!needsSanitization) {
            return new LinkProperties(lp);
        }

        if (checkSettingsPermission(callerPid, callerUid)) {
            return new LinkProperties(lp, true /* parcelSensitiveFields */);
        }

        final LinkProperties newLp = new LinkProperties(lp);
        // Sensitive fields would not be parceled anyway, but sanitize for consistency before the
        // object gets parceled.
        newLp.setCaptivePortalApiUrl(null);
        newLp.setCaptivePortalData(null);
        return newLp;
    }

    private void restrictRequestUidsForCallerAndSetRequestorInfo(NetworkCapabilities nc,
            int callerUid, String callerPackageName) {
        if (!checkSettingsPermission()) {
            nc.setSingleUid(callerUid);
        }
        nc.setRequestorUidAndPackageName(callerUid, callerPackageName);
        nc.setAdministratorUids(new int[0]);

        // Clear owner UID; this can never come from an app.
        nc.setOwnerUid(INVALID_UID);
    }

    private void restrictBackgroundRequestForCaller(NetworkCapabilities nc) {
        if (!mPermissionMonitor.hasUseBackgroundNetworksPermission(Binder.getCallingUid())) {
            nc.addCapability(NET_CAPABILITY_FOREGROUND);
        }
    }

    @Override
    public NetworkState[] getAllNetworkState() {
        // This contains IMSI details, so make sure the caller is privileged.
        NetworkStack.checkNetworkStackPermission(mContext);

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

        final NetworkCapabilities caps = getNetworkCapabilitiesInternal(getActiveNetwork());
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
        // requestRouteToHost. In Q, GnssLocationProvider is changed to not call requestRouteToHost
        // for devices launched with Q and above. However, existing devices upgrading to Q and
        // above must continued to be supported for few more releases.
        if (isSystem(Binder.getCallingUid()) && SystemProperties.getInt(
                "ro.product.first_api_level", 0) > Build.VERSION_CODES.P) {
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
            enforceConnectivityRestrictedNetworksPermission();
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
            // TODO: Move the Dns Event to NetworkMonitor. NetdEventListenerService only allow one
            // callback from each caller type. Need to re-factor NetdEventListenerService to allow
            // multiple NetworkMonitor registrants.
            if (nai != null && nai.satisfies(mDefaultRequest)) {
                nai.networkMonitor().notifyDnsResponse(returnCode);
            }
        }

        @Override
        public void onNat64PrefixEvent(int netId, boolean added,
                                       String prefixString, int prefixLength) {
            mHandler.post(() -> handleNat64PrefixEvent(netId, added, prefixString, prefixLength));
        }
    };

    private void registerNetdEventCallback() {
        final IIpConnectivityMetrics ipConnectivityMetrics = mDeps.getIpConnectivityMetrics();
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

        return NetworkPolicyManagerInternal.isUidNetworkingBlocked(uid, uidRules,
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

    private boolean checkAnyPermissionOf(String... permissions) {
        for (String permission : permissions) {
            if (mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private boolean checkAnyPermissionOf(int pid, int uid, String... permissions) {
        for (String permission : permissions) {
            if (mContext.checkPermission(permission, pid, uid) == PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    private void enforceAnyPermissionOf(String... permissions) {
        if (!checkAnyPermissionOf(permissions)) {
            throw new SecurityException("Requires one of the following permissions: "
                    + String.join(", ", permissions) + ".");
        }
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
        enforceAnyPermissionOf(
                android.Manifest.permission.NETWORK_SETTINGS,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private void enforceNetworkFactoryPermission() {
        enforceAnyPermissionOf(
                android.Manifest.permission.NETWORK_FACTORY,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private boolean checkSettingsPermission() {
        return checkAnyPermissionOf(
                android.Manifest.permission.NETWORK_SETTINGS,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private boolean checkSettingsPermission(int pid, int uid) {
        return PERMISSION_GRANTED == mContext.checkPermission(
                android.Manifest.permission.NETWORK_SETTINGS, pid, uid)
                || PERMISSION_GRANTED == mContext.checkPermission(
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK, pid, uid);
    }

    private void enforceControlAlwaysOnVpnPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_ALWAYS_ON_VPN,
                "ConnectivityService");
    }

    private void enforceNetworkStackOrSettingsPermission() {
        enforceAnyPermissionOf(
                android.Manifest.permission.NETWORK_SETTINGS,
                android.Manifest.permission.NETWORK_STACK,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private void enforceNetworkStackSettingsOrSetup() {
        enforceAnyPermissionOf(
                android.Manifest.permission.NETWORK_SETTINGS,
                android.Manifest.permission.NETWORK_SETUP_WIZARD,
                android.Manifest.permission.NETWORK_STACK,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private boolean checkNetworkStackPermission() {
        return checkAnyPermissionOf(
                android.Manifest.permission.NETWORK_STACK,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private boolean checkNetworkStackPermission(int pid, int uid) {
        return checkAnyPermissionOf(pid, uid,
                android.Manifest.permission.NETWORK_STACK,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private boolean checkNetworkSignalStrengthWakeupPermission(int pid, int uid) {
        return checkAnyPermissionOf(pid, uid,
                android.Manifest.permission.NETWORK_SIGNAL_STRENGTH_WAKEUP,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private void enforceConnectivityRestrictedNetworksPermission() {
        try {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.CONNECTIVITY_USE_RESTRICTED_NETWORKS,
                    "ConnectivityService");
            return;
        } catch (SecurityException e) { /* fallback to ConnectivityInternalPermission */ }
        //  TODO: Remove this fallback check after all apps have declared
        //   CONNECTIVITY_USE_RESTRICTED_NETWORKS.
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    private void enforceKeepalivePermission() {
        mContext.enforceCallingOrSelfPermission(KeepaliveTracker.PERMISSION, "ConnectivityService");
    }

    // Public because it's used by mLockdownTracker.
    public void sendConnectedBroadcast(NetworkInfo info) {
        NetworkStack.checkNetworkStackPermission(mContext);
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
                final BroadcastOptions opts = BroadcastOptions.makeBasic();
                opts.setMaxManifestReceiverApiLevel(Build.VERSION_CODES.M);
                options = opts.toBundle();
                final IBatteryStats bs = mDeps.getBatteryStatsService();
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

    /**
     * Called when the system is ready and ConnectivityService can initialize remaining components.
     */
    @VisibleForTesting
    public void systemReady() {
        // Let PermissionMonitor#startMonitoring() running in the beginning of the systemReady
        // before MultipathPolicyTracker.start(). Since mApps in PermissionMonitor needs to be
        // populated first to ensure that listening network request which is sent by
        // MultipathPolicyTracker won't be added NET_CAPABILITY_FOREGROUND capability.
        mPermissionMonitor.startMonitoring();
        mProxyTracker.loadGlobalProxy();
        registerNetdEventCallback();

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
        final int type;

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
            return; // do not track any other networks
        }

        if (timeout > 0 && iface != null) {
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
        if (!LinkProperties.isValidMtu(mtu, newLp.hasGlobalIpv6Address())) {
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
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
            @Nullable String[] args) {
        PriorityDump.dump(mPriorityDumper, fd, writer, args);
    }

    private void doDump(FileDescriptor fd, PrintWriter writer, String[] args, boolean asProto) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        if (asProto) return;

        if (ArrayUtils.contains(args, DIAG_ARG)) {
            dumpNetworkDiagnostics(pw);
            return;
        } else if (ArrayUtils.contains(args, NETWORK_ARG)) {
            dumpNetworks(pw);
            return;
        } else if (ArrayUtils.contains(args, REQUEST_ARG)) {
            dumpNetworkRequests(pw);
            return;
        }

        pw.print("NetworkProviders for:");
        for (NetworkProviderInfo npi : mNetworkProviderInfos.values()) {
            pw.print(" " + npi.name);
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

        pw.println();
        pw.println("NetworkStackClient logs:");
        pw.increaseIndent();
        NetworkStackClient.getInstance().dump(pw);
        pw.decreaseIndent();

        pw.println();
        pw.println("Permission Monitor:");
        pw.increaseIndent();
        mPermissionMonitor.dump(pw);
        pw.decreaseIndent();
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
                    if (networkCapabilities.hasConnectivityManagedCapability()) {
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
                    if (nai.everConnected) {
                        loge("ERROR: cannot call explicitlySelected on already-connected network");
                    }
                    nai.networkAgentConfig.explicitlySelected = toBool(msg.arg1);
                    nai.networkAgentConfig.acceptUnvalidated = toBool(msg.arg1) && toBool(msg.arg2);
                    // Mark the network as temporarily accepting partial connectivity so that it
                    // will be validated (and possibly become default) even if it only provides
                    // partial internet access. Note that if user connects to partial connectivity
                    // and choose "don't ask again", then wifi disconnected by some reasons(maybe
                    // out of wifi coverage) and if the same wifi is available again, the device
                    // will auto connect to this wifi even though the wifi has "no internet".
                    // TODO: Evaluate using a separate setting in IpMemoryStore.
                    nai.networkAgentConfig.acceptPartialConnectivity = toBool(msg.arg2);
                    break;
                }
                case NetworkAgent.EVENT_SOCKET_KEEPALIVE: {
                    mKeepaliveTracker.handleEventSocketKeepalive(nai, msg);
                    break;
                }
            }
        }

        private boolean maybeHandleNetworkMonitorMessage(Message msg) {
            switch (msg.what) {
                default:
                    return false;
                case EVENT_PROBE_STATUS_CHANGED: {
                    final Integer netId = (Integer) msg.obj;
                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(netId);
                    if (nai == null) {
                        break;
                    }
                    final boolean probePrivateDnsCompleted =
                            ((msg.arg1 & NETWORK_VALIDATION_PROBE_PRIVDNS) != 0);
                    final boolean privateDnsBroken =
                            ((msg.arg2 & NETWORK_VALIDATION_PROBE_PRIVDNS) == 0);
                    if (probePrivateDnsCompleted) {
                        if (nai.networkCapabilities.isPrivateDnsBroken() != privateDnsBroken) {
                            nai.networkCapabilities.setPrivateDnsBroken(privateDnsBroken);
                            final int oldScore = nai.getCurrentScore();
                            updateCapabilities(oldScore, nai, nai.networkCapabilities);
                        }
                        // Only show the notification when the private DNS is broken and the
                        // PRIVATE_DNS_BROKEN notification hasn't shown since last valid.
                        if (privateDnsBroken && !nai.networkAgentConfig.hasShownBroken) {
                            showNetworkNotification(nai, NotificationType.PRIVATE_DNS_BROKEN);
                        }
                        nai.networkAgentConfig.hasShownBroken = privateDnsBroken;
                    } else if (nai.networkCapabilities.isPrivateDnsBroken()) {
                        // If probePrivateDnsCompleted is false but nai.networkCapabilities says
                        // private DNS is broken, it means this network is being reevaluated.
                        // Either probing private DNS is not necessary any more or it hasn't been
                        // done yet. In either case, the networkCapabilities should be updated to
                        // reflect the new status.
                        nai.networkCapabilities.setPrivateDnsBroken(false);
                        final int oldScore = nai.getCurrentScore();
                        updateCapabilities(oldScore, nai, nai.networkCapabilities);
                        nai.networkAgentConfig.hasShownBroken = false;
                    }
                    break;
                }
                case EVENT_NETWORK_TESTED: {
                    final NetworkTestedResults results = (NetworkTestedResults) msg.obj;

                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(results.mNetId);
                    if (nai == null) break;

                    handleNetworkTested(nai, results.mTestResult,
                            (results.mRedirectUrl == null) ? "" : results.mRedirectUrl);

                    // Invoke ConnectivityReport generation for this Network test event.
                    final Message m =
                            mConnectivityDiagnosticsHandler.obtainMessage(
                                    ConnectivityDiagnosticsHandler.EVENT_NETWORK_TESTED,
                                    new ConnectivityReportEvent(results.mTimestampMillis, nai));
                    m.setData(msg.getData());
                    mConnectivityDiagnosticsHandler.sendMessage(m);
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
                        if (nai.lastCaptivePortalDetected &&
                            Settings.Global.CAPTIVE_PORTAL_MODE_AVOID == getCaptivePortalMode()) {
                            if (DBG) log("Avoiding captive portal network: " + nai.toShortString());
                            nai.asyncChannel.sendMessage(
                                    NetworkAgent.CMD_PREVENT_AUTOMATIC_RECONNECT);
                            teardownUnneededNetwork(nai);
                            break;
                        }
                        updateCapabilities(oldScore, nai, nai.networkCapabilities);
                    }
                    if (!visible) {
                        // Only clear SIGN_IN and NETWORK_SWITCH notifications here, or else other
                        // notifications belong to the same network may be cleared unexpectedly.
                        mNotifier.clearNotification(netId, NotificationType.SIGN_IN);
                        mNotifier.clearNotification(netId, NotificationType.NETWORK_SWITCH);
                    } else {
                        if (nai == null) {
                            loge("EVENT_PROVISIONING_NOTIFICATION from unknown NetworkMonitor");
                            break;
                        }
                        if (!nai.networkAgentConfig.provisioningNotificationDisabled) {
                            mNotifier.showNotification(netId, NotificationType.SIGN_IN, nai, null,
                                    (PendingIntent) msg.obj,
                                    nai.networkAgentConfig.explicitlySelected);
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
                case EVENT_CAPPORT_DATA_CHANGED: {
                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(msg.arg2);
                    if (nai == null) break;
                    handleCaptivePortalDataUpdate(nai, (CaptivePortalData) msg.obj);
                    break;
                }
            }
            return true;
        }

        private void handleNetworkTested(
                @NonNull NetworkAgentInfo nai, int testResult, @NonNull String redirectUrl) {
            final boolean wasPartial = nai.partialConnectivity;
            nai.partialConnectivity = ((testResult & NETWORK_VALIDATION_RESULT_PARTIAL) != 0);
            final boolean partialConnectivityChanged =
                    (wasPartial != nai.partialConnectivity);

            final boolean valid = ((testResult & NETWORK_VALIDATION_RESULT_VALID) != 0);
            final boolean wasValidated = nai.lastValidated;
            final boolean wasDefault = isDefaultNetwork(nai);

            if (DBG) {
                final String logMsg = !TextUtils.isEmpty(redirectUrl)
                        ? " with redirect to " + redirectUrl
                        : "";
                log(nai.toShortString() + " validation " + (valid ? "passed" : "failed") + logMsg);
            }
            if (valid != nai.lastValidated) {
                if (wasDefault) {
                    mDeps.getMetricsLogger()
                            .defaultNetworkMetrics().logDefaultNetworkValidity(
                            SystemClock.elapsedRealtime(), valid);
                }
                final int oldScore = nai.getCurrentScore();
                nai.lastValidated = valid;
                nai.everValidated |= valid;
                updateCapabilities(oldScore, nai, nai.networkCapabilities);
                // If score has changed, rebroadcast to NetworkProviders. b/17726566
                if (oldScore != nai.getCurrentScore()) sendUpdatedScoreToFactories(nai);
                if (valid) {
                    handleFreshlyValidatedNetwork(nai);
                    // Clear NO_INTERNET, PRIVATE_DNS_BROKEN, PARTIAL_CONNECTIVITY and
                    // LOST_INTERNET notifications if network becomes valid.
                    mNotifier.clearNotification(nai.network.netId,
                            NotificationType.NO_INTERNET);
                    mNotifier.clearNotification(nai.network.netId,
                            NotificationType.LOST_INTERNET);
                    mNotifier.clearNotification(nai.network.netId,
                            NotificationType.PARTIAL_CONNECTIVITY);
                    mNotifier.clearNotification(nai.network.netId,
                            NotificationType.PRIVATE_DNS_BROKEN);
                    // If network becomes valid, the hasShownBroken should be reset for
                    // that network so that the notification will be fired when the private
                    // DNS is broken again.
                    nai.networkAgentConfig.hasShownBroken = false;
                }
            } else if (partialConnectivityChanged) {
                updateCapabilities(nai.getCurrentScore(), nai, nai.networkCapabilities);
            }
            updateInetCondition(nai);
            // Let the NetworkAgent know the state of its network
            Bundle redirectUrlBundle = new Bundle();
            redirectUrlBundle.putString(NetworkAgent.REDIRECT_URL_KEY, redirectUrl);
            // TODO: Evaluate to update partial connectivity to status to NetworkAgent.
            nai.asyncChannel.sendMessage(
                    NetworkAgent.CMD_REPORT_NETWORK_STATUS,
                    (valid ? NetworkAgent.VALID_NETWORK : NetworkAgent.INVALID_NETWORK),
                    0, redirectUrlBundle);

            // If NetworkMonitor detects partial connectivity before
            // EVENT_PROMPT_UNVALIDATED arrives, show the partial connectivity notification
            // immediately. Re-notify partial connectivity silently if no internet
            // notification already there.
            if (!wasPartial && nai.partialConnectivity) {
                // Remove delayed message if there is a pending message.
                mHandler.removeMessages(EVENT_PROMPT_UNVALIDATED, nai.network);
                handlePromptUnvalidated(nai.network);
            }

            if (wasValidated && !nai.lastValidated) {
                handleNetworkUnvalidated(nai);
            }
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
            if (!maybeHandleAsyncChannelMessage(msg)
                    && !maybeHandleNetworkMonitorMessage(msg)
                    && !maybeHandleNetworkAgentInfoMessage(msg)) {
                maybeHandleNetworkAgentMessage(msg);
            }
        }
    }

    private class NetworkMonitorCallbacks extends INetworkMonitorCallbacks.Stub {
        private final int mNetId;
        private final AutodestructReference<NetworkAgentInfo> mNai;

        private NetworkMonitorCallbacks(NetworkAgentInfo nai) {
            mNetId = nai.network.netId;
            mNai = new AutodestructReference<>(nai);
        }

        @Override
        public void onNetworkMonitorCreated(INetworkMonitor networkMonitor) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_AGENT,
                    new Pair<>(mNai.getAndDestroy(), networkMonitor)));
        }

        @Override
        public void notifyNetworkTested(int testResult, @Nullable String redirectUrl) {
            notifyNetworkTestedWithExtras(testResult, redirectUrl, SystemClock.elapsedRealtime(),
                    PersistableBundle.EMPTY);
        }

        @Override
        public void notifyNetworkTestedWithExtras(
                int testResult,
                @Nullable String redirectUrl,
                long timestampMillis,
                @NonNull PersistableBundle extras) {
            final Message msg =
                    mTrackerHandler.obtainMessage(
                            EVENT_NETWORK_TESTED,
                            new NetworkTestedResults(
                                    mNetId, testResult, timestampMillis, redirectUrl));
            msg.setData(new Bundle(extras));
            mTrackerHandler.sendMessage(msg);
        }

        @Override
        public void notifyPrivateDnsConfigResolved(PrivateDnsConfigParcel config) {
            mTrackerHandler.sendMessage(mTrackerHandler.obtainMessage(
                    EVENT_PRIVATE_DNS_CONFIG_RESOLVED,
                    0, mNetId, PrivateDnsConfig.fromParcel(config)));
        }

        @Override
        public void notifyProbeStatusChanged(int probesCompleted, int probesSucceeded) {
            mTrackerHandler.sendMessage(mTrackerHandler.obtainMessage(
                    EVENT_PROBE_STATUS_CHANGED,
                    probesCompleted, probesSucceeded, new Integer(mNetId)));
        }

        @Override
        public void notifyCaptivePortalDataChanged(CaptivePortalData data) {
            mTrackerHandler.sendMessage(mTrackerHandler.obtainMessage(
                    EVENT_CAPPORT_DATA_CHANGED,
                    0, mNetId, data));
        }

        @Override
        public void showProvisioningNotification(String action, String packageName) {
            final Intent intent = new Intent(action);
            intent.setPackage(packageName);

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
                    mNetId, pendingIntent));
        }

        @Override
        public void hideProvisioningNotification() {
            mTrackerHandler.sendMessage(mTrackerHandler.obtainMessage(
                    EVENT_PROVISIONING_NOTIFICATION, PROVISIONING_NOTIFICATION_HIDE, mNetId));
        }

        @Override
        public void notifyDataStallSuspected(
                long timestampMillis, int detectionMethod, PersistableBundle extras) {
            final Message msg =
                    mConnectivityDiagnosticsHandler.obtainMessage(
                            ConnectivityDiagnosticsHandler.EVENT_DATA_STALL_SUSPECTED,
                            detectionMethod, mNetId, timestampMillis);
            msg.setData(new Bundle(extras));

            // NetworkStateTrackerHandler currently doesn't take any actions based on data
            // stalls so send the message directly to ConnectivityDiagnosticsHandler and avoid
            // the cost of going through two handlers.
            mConnectivityDiagnosticsHandler.sendMessage(msg);
        }

        @Override
        public int getInterfaceVersion() {
            return this.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return this.HASH;
        }
    }

    private boolean networkRequiresPrivateDnsValidation(NetworkAgentInfo nai) {
        return isPrivateDnsValidationRequired(nai.networkCapabilities);
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
            if (networkRequiresPrivateDnsValidation(nai)) {
                handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
            }
        }
    }

    private void handlePerNetworkPrivateDnsConfig(NetworkAgentInfo nai, PrivateDnsConfig cfg) {
        // Private DNS only ever applies to networks that might provide
        // Internet access and therefore also require validation.
        if (!networkRequiresPrivateDnsValidation(nai)) return;

        // Notify the NetworkAgentInfo/NetworkMonitor in case NetworkMonitor needs to cancel or
        // schedule DNS resolutions. If a DNS resolution is required the
        // result will be sent back to us.
        nai.networkMonitor().notifyPrivateDnsChanged(cfg.toParcel());

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

    private void handleNat64PrefixEvent(int netId, boolean added, String prefixString,
            int prefixLength) {
        NetworkAgentInfo nai = mNetworkForNetId.get(netId);
        if (nai == null) return;

        log(String.format("NAT64 prefix %s on netId %d: %s/%d",
                          (added ? "added" : "removed"), netId, prefixString, prefixLength));

        IpPrefix prefix = null;
        if (added) {
            try {
                prefix = new IpPrefix(InetAddresses.parseNumericAddress(prefixString),
                        prefixLength);
            } catch (IllegalArgumentException e) {
                loge("Invalid NAT64 prefix " + prefixString + "/" + prefixLength);
                return;
            }
        }

        nai.clatd.setNat64Prefix(prefix);
        handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
    }

    private void handleCaptivePortalDataUpdate(@NonNull final NetworkAgentInfo nai,
            @Nullable final CaptivePortalData data) {
        nai.captivePortalData = data;
        // CaptivePortalData will be merged into LinkProperties from NetworkAgentInfo
        handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
    }

    /**
     * Updates the linger state from the network requests inside the NAI.
     * @param nai the agent info to update
     * @param now the timestamp of the event causing this update
     * @return whether the network was lingered as a result of this update
     */
    private boolean updateLingerState(@NonNull final NetworkAgentInfo nai, final long now) {
        // 1. Update the linger timer. If it's changed, reschedule or cancel the alarm.
        // 2. If the network was lingering and there are now requests, unlinger it.
        // 3. If this network is unneeded (which implies it is not lingering), and there is at least
        //    one lingered request, start lingering.
        nai.updateLingerTimer();
        if (nai.isLingering() && nai.numForegroundNetworkRequests() > 0) {
            if (DBG) log("Unlingering " + nai.toShortString());
            nai.unlinger();
            logNetworkEvent(nai, NetworkEvent.NETWORK_UNLINGER);
        } else if (unneeded(nai, UnneededFor.LINGER) && nai.getLingerExpiry() > 0) {
            if (DBG) {
                final int lingerTime = (int) (nai.getLingerExpiry() - now);
                log("Lingering " + nai.toShortString() + " for " + lingerTime + "ms");
            }
            nai.linger();
            logNetworkEvent(nai, NetworkEvent.NETWORK_LINGER);
            return true;
        }
        return false;
    }

    private void handleAsyncChannelHalfConnect(Message msg) {
        ensureRunningOnConnectivityServiceThread();
        final AsyncChannel ac = (AsyncChannel) msg.obj;
        if (mNetworkProviderInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                if (VDBG) log("NetworkFactory connected");
                // Finish setting up the full connection
                NetworkProviderInfo npi = mNetworkProviderInfos.get(msg.replyTo);
                npi.completeConnection();
                sendAllRequestsToProvider(npi);
            } else {
                loge("Error connecting NetworkFactory");
                mNetworkProviderInfos.remove(msg.obj);
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
                    }
                    mNetIdManager.releaseNetId(nai.network.netId);
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
            NetworkProviderInfo npi = mNetworkProviderInfos.remove(msg.replyTo);
            if (DBG && npi != null) log("unregisterNetworkFactory for " + npi.name);
        }
    }

    // Destroys a network, remove references to it from the internal state managed by
    // ConnectivityService, free its interfaces and clean up.
    // Must be called on the Handler thread.
    private void disconnectAndDestroyNetwork(NetworkAgentInfo nai) {
        ensureRunningOnConnectivityServiceThread();
        if (DBG) {
            log(nai.toShortString() + " disconnected, was satisfying " + nai.numNetworkRequests());
        }
        // Clear all notifications of this network.
        mNotifier.clearNotification(nai.network.netId);
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
            mDeps.getMetricsLogger().defaultNetworkMetrics().logDefaultNetworkEvent(now, null, nai);
        }
        notifyIfacesChangedForNetworkStats();
        // TODO - we shouldn't send CALLBACK_LOST to requests that can be satisfied
        // by other networks that are already connected. Perhaps that can be done by
        // sending all CALLBACK_LOST messages (for requests, not listens) at the end
        // of rematchAllNetworksAndRequests
        notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOST);
        mKeepaliveTracker.handleStopAllKeepalives(nai, SocketKeepalive.ERROR_INVALID_NETWORK);
        for (String iface : nai.linkProperties.getAllInterfaceNames()) {
            // Disable wakeup packet monitoring for each interface.
            wakeupModifyInterface(iface, nai.networkCapabilities, false);
        }
        nai.networkMonitor().notifyNetworkDisconnected();
        mNetworkAgentInfos.remove(nai.messenger);
        nai.clatd.update();
        synchronized (mNetworkForNetId) {
            // Remove the NetworkAgent, but don't mark the netId as
            // available until we've told netd to delete it below.
            mNetworkForNetId.remove(nai.network.netId);
        }
        // Remove all previously satisfied requests.
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest request = nai.requestAt(i);
            final NetworkRequestInfo nri = mNetworkRequests.get(request);
            final NetworkAgentInfo currentNetwork = nri.mSatisfier;
            if (currentNetwork != null && currentNetwork.network.netId == nai.network.netId) {
                nri.mSatisfier = null;
                sendUpdatedScoreToFactories(request, null);
            }
        }
        nai.clearLingerState();
        if (nai.isSatisfyingRequest(mDefaultRequest.requestId)) {
            mDefaultNetworkNai = null;
            updateDataActivityTracking(null /* newNetwork */, nai);
            notifyLockdownVpn(nai);
            ensureNetworkTransitionWakelock(nai.toShortString());
        }
        mLegacyTypeTracker.remove(nai, wasDefault);
        if (!nai.networkCapabilities.hasTransport(TRANSPORT_VPN)) {
            updateAllVpnsCapabilities();
        }
        rematchAllNetworksAndRequests();
        mLingerMonitor.noteDisconnect(nai);
        if (nai.created) {
            // Tell netd to clean up the configuration for this network
            // (routing rules, DNS, etc).
            // This may be slow as it requires a lot of netd shelling out to ip and
            // ip[6]tables to flush routes and remove the incoming packet mark rule, so do it
            // after we've rematched networks with requests which should make a potential
            // fallback network the default or requested a new network from the
            // NetworkProviders, so network traffic isn't interrupted for an unnecessarily
            // long time.
            destroyNativeNetwork(nai);
            mDnsManager.removeNetwork(nai.network);
        }
        mNetIdManager.releaseNetId(nai.network.netId);
    }

    private boolean createNativeNetwork(@NonNull NetworkAgentInfo networkAgent) {
        try {
            // This should never fail.  Specifying an already in use NetID will cause failure.
            if (networkAgent.isVPN()) {
                mNetd.networkCreateVpn(networkAgent.network.netId,
                        (networkAgent.networkAgentConfig == null
                                || !networkAgent.networkAgentConfig.allowBypass));
            } else {
                mNetd.networkCreatePhysical(networkAgent.network.netId,
                        getNetworkPermission(networkAgent.networkCapabilities));
            }
            mDnsResolver.createNetworkCache(networkAgent.network.netId);
            return true;
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Error creating network " + networkAgent.network.netId + ": "
                    + e.getMessage());
            return false;
        }
    }

    private void destroyNativeNetwork(@NonNull NetworkAgentInfo networkAgent) {
        try {
            mNetd.networkDestroy(networkAgent.network.netId);
            mDnsResolver.destroyNetworkCache(networkAgent.network.netId);
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Exception destroying network: " + e);
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
            handleReleaseNetworkRequest(existingRequest.request, getCallingUid(),
                    /* callOnUnavailable */ false);
        }
        handleRegisterNetworkRequest(nri);
    }

    private void handleRegisterNetworkRequest(NetworkRequestInfo nri) {
        ensureRunningOnConnectivityServiceThread();
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
        rematchAllNetworksAndRequests();
        if (nri.request.isRequest() && nri.mSatisfier == null) {
            sendUpdatedScoreToFactories(nri.request, null);
        }
    }

    private void handleReleaseNetworkRequestWithIntent(PendingIntent pendingIntent,
            int callingUid) {
        NetworkRequestInfo nri = findExistingNetworkRequestInfo(pendingIntent);
        if (nri != null) {
            handleReleaseNetworkRequest(nri.request, callingUid, /* callOnUnavailable */ false);
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
        ensureRunningOnConnectivityServiceThread();
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
                    nri.mSatisfier.getCurrentScore()
                            < nai.getCurrentScoreAsValidated())) {
                return false;
            }
        }
        return true;
    }

    private NetworkRequestInfo getNriForAppRequest(
            NetworkRequest request, int callingUid, String requestedOperation) {
        final NetworkRequestInfo nri = mNetworkRequests.get(request);

        if (nri != null) {
            if (Process.SYSTEM_UID != callingUid && Process.NETWORK_STACK_UID != callingUid
                    && nri.mUid != callingUid) {
                log(String.format("UID %d attempted to %s for unowned request %s",
                        callingUid, requestedOperation, nri));
                return null;
            }
        }

        return nri;
    }

    private void handleTimedOutNetworkRequest(final NetworkRequestInfo nri) {
        ensureRunningOnConnectivityServiceThread();
        if (mNetworkRequests.get(nri.request) == null) {
            return;
        }
        if (nri.mSatisfier != null) {
            return;
        }
        if (VDBG || (DBG && nri.request.isRequest())) {
            log("releasing " + nri.request + " (timeout)");
        }
        handleRemoveNetworkRequest(nri);
        callCallbackForRequest(nri, null, ConnectivityManager.CALLBACK_UNAVAIL, 0);
    }

    private void handleReleaseNetworkRequest(NetworkRequest request, int callingUid,
            boolean callOnUnavailable) {
        final NetworkRequestInfo nri =
                getNriForAppRequest(request, callingUid, "release NetworkRequest");
        if (nri == null) {
            return;
        }
        if (VDBG || (DBG && nri.request.isRequest())) {
            log("releasing " + nri.request + " (release request)");
        }
        handleRemoveNetworkRequest(nri);
        if (callOnUnavailable) {
            callCallbackForRequest(nri, null, ConnectivityManager.CALLBACK_UNAVAIL, 0);
        }
    }

    private void handleRemoveNetworkRequest(final NetworkRequestInfo nri) {
        ensureRunningOnConnectivityServiceThread();

        nri.unlinkDeathRecipient();
        mNetworkRequests.remove(nri.request);

        decrementNetworkRequestPerUidCount(nri);

        mNetworkRequestInfoLogs.log("RELEASE " + nri);
        if (nri.request.isRequest()) {
            boolean wasKept = false;
            final NetworkAgentInfo nai = nri.mSatisfier;
            if (nai != null) {
                boolean wasBackgroundNetwork = nai.isBackgroundNetwork();
                nai.removeRequest(nri.request.requestId);
                if (VDBG || DDBG) {
                    log(" Removing from current network " + nai.toShortString()
                            + ", leaving " + nai.numNetworkRequests() + " requests.");
                }
                // If there are still lingered requests on this network, don't tear it down,
                // but resume lingering instead.
                final long now = SystemClock.elapsedRealtime();
                if (updateLingerState(nai, now)) {
                    notifyNetworkLosing(nai, now);
                }
                if (unneeded(nai, UnneededFor.TEARDOWN)) {
                    if (DBG) log("no live requests for " + nai.toShortString() + "; disconnecting");
                    teardownUnneededNetwork(nai);
                } else {
                    wasKept = true;
                }
                nri.mSatisfier = null;
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

            for (NetworkProviderInfo npi : mNetworkProviderInfos.values()) {
                npi.cancelRequest(nri.request);
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

    private void decrementNetworkRequestPerUidCount(final NetworkRequestInfo nri) {
        synchronized (mUidToNetworkRequestCount) {
            final int requests = mUidToNetworkRequestCount.get(nri.mUid, 0);
            if (requests < 1) {
                Slog.wtf(TAG, "BUG: too small request count " + requests + " for UID " + nri.mUid);
            } else if (requests == 1) {
                mUidToNetworkRequestCount.removeAt(mUidToNetworkRequestCount.indexOfKey(nri.mUid));
            } else {
                mUidToNetworkRequestCount.put(nri.mUid, requests - 1);
            }
        }
    }

    @Override
    public void setAcceptUnvalidated(Network network, boolean accept, boolean always) {
        enforceNetworkStackSettingsOrSetup();
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_ACCEPT_UNVALIDATED,
                encodeBool(accept), encodeBool(always), network));
    }

    @Override
    public void setAcceptPartialConnectivity(Network network, boolean accept, boolean always) {
        enforceNetworkStackSettingsOrSetup();
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_ACCEPT_PARTIAL_CONNECTIVITY,
                encodeBool(accept), encodeBool(always), network));
    }

    @Override
    public void setAvoidUnvalidated(Network network) {
        enforceNetworkStackSettingsOrSetup();
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

        if (!nai.networkAgentConfig.explicitlySelected) {
            Slog.wtf(TAG, "BUG: setAcceptUnvalidated non non-explicitly selected network");
        }

        if (accept != nai.networkAgentConfig.acceptUnvalidated) {
            nai.networkAgentConfig.acceptUnvalidated = accept;
            // If network becomes partial connectivity and user already accepted to use this
            // network, we should respect the user's option and don't need to popup the
            // PARTIAL_CONNECTIVITY notification to user again.
            nai.networkAgentConfig.acceptPartialConnectivity = accept;
            rematchAllNetworksAndRequests();
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

    private void handleSetAcceptPartialConnectivity(Network network, boolean accept,
            boolean always) {
        if (DBG) {
            log("handleSetAcceptPartialConnectivity network=" + network + " accept=" + accept
                    + " always=" + always);
        }

        final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null) {
            // Nothing to do.
            return;
        }

        if (nai.lastValidated) {
            // The network validated while the dialog box was up. Take no action.
            return;
        }

        if (accept != nai.networkAgentConfig.acceptPartialConnectivity) {
            nai.networkAgentConfig.acceptPartialConnectivity = accept;
        }

        // TODO: Use the current design or save the user choice into IpMemoryStore.
        if (always) {
            nai.asyncChannel.sendMessage(
                    NetworkAgent.CMD_SAVE_ACCEPT_UNVALIDATED, encodeBool(accept));
        }

        if (!accept) {
            // Tell the NetworkAgent to not automatically reconnect to the network.
            nai.asyncChannel.sendMessage(NetworkAgent.CMD_PREVENT_AUTOMATIC_RECONNECT);
            // Tear down the network.
            teardownUnneededNetwork(nai);
        } else {
            // Inform NetworkMonitor that partial connectivity is acceptable. This will likely
            // result in a partial connectivity result which will be processed by
            // maybeHandleNetworkMonitorMessage.
            //
            // TODO: NetworkMonitor does not refer to the "never ask again" bit. The bit is stored
            // per network. Therefore, NetworkMonitor may still do https probe.
            nai.networkMonitor().setAcceptPartialConnectivity();
        }
    }

    private void handleSetAvoidUnvalidated(Network network) {
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null || nai.lastValidated) {
            // Nothing to do. The network either disconnected or revalidated.
            return;
        }
        if (!nai.avoidUnvalidated) {
            nai.avoidUnvalidated = true;
            rematchAllNetworksAndRequests();
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
        enforceNetworkStackOrSettingsPermission();
        mHandler.post(() -> {
            NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
            if (nai == null) return;
            if (!nai.networkCapabilities.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)) return;
            nai.networkMonitor().launchCaptivePortalApp();
        });
    }

    /**
     * NetworkStack endpoint to start the captive portal app. The NetworkStack needs to use this
     * endpoint as it does not have INTERACT_ACROSS_USERS_FULL itself.
     * @param network Network on which the captive portal was detected.
     * @param appExtras Bundle to use as intent extras for the captive portal application.
     *                  Must be treated as opaque to avoid preventing the captive portal app to
     *                  update its arguments.
     */
    @Override
    public void startCaptivePortalAppInternal(Network network, Bundle appExtras) {
        mContext.enforceCallingOrSelfPermission(NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                "ConnectivityService");

        final Intent appIntent = new Intent(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN);
        appIntent.putExtras(appExtras);
        appIntent.putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL,
                new CaptivePortal(new CaptivePortalImpl(network).asBinder()));
        appIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);

        Binder.withCleanCallingIdentity(() ->
                mContext.startActivityAsUser(appIntent, UserHandle.CURRENT));
    }

    private class CaptivePortalImpl extends ICaptivePortal.Stub {
        private final Network mNetwork;

        private CaptivePortalImpl(Network network) {
            mNetwork = network;
        }

        @Override
        public void appResponse(final int response) {
            if (response == CaptivePortal.APP_RETURN_WANTED_AS_IS) {
                enforceSettingsPermission();
            }

            final NetworkMonitorManager nm = getNetworkMonitorManager(mNetwork);
            if (nm == null) return;
            nm.notifyCaptivePortalAppFinished(response);
        }

        @Override
        public void appRequest(final int request) {
            final NetworkMonitorManager nm = getNetworkMonitorManager(mNetwork);
            if (nm == null) return;

            if (request == CaptivePortal.APP_REQUEST_REEVALUATION_REQUIRED) {
                checkNetworkStackPermission();
                nm.forceReevaluation(Binder.getCallingUid());
            }
        }

        @Nullable
        private NetworkMonitorManager getNetworkMonitorManager(final Network network) {
            // getNetworkAgentInfoForNetwork is thread-safe
            final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
            if (nai == null) return null;

            // nai.networkMonitor() is thread-safe
            return nai.networkMonitor();
        }

        @Override
        public void logEvent(int eventId, String packageName) {
            enforceSettingsPermission();

            new MetricsLogger().action(eventId, packageName);
        }
    }

    public boolean avoidBadWifi() {
        return mMultinetworkPolicyTracker.getAvoidBadWifi();
    }

    /**
     * Return whether the device should maintain continuous, working connectivity by switching away
     * from WiFi networks having no connectivity.
     * @see MultinetworkPolicyTracker#getAvoidBadWifi()
     */
    public boolean shouldAvoidBadWifi() {
        if (!checkNetworkStackPermission()) {
            throw new SecurityException("avoidBadWifi requires NETWORK_STACK permission");
        }
        return avoidBadWifi();
    }


    private void rematchForAvoidBadWifiUpdate() {
        rematchAllNetworksAndRequests();
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
                pw.println(nai.toShortString());
            }
        }
        pw.decreaseIndent();
        pw.decreaseIndent();
    }

    private void showNetworkNotification(NetworkAgentInfo nai, NotificationType type) {
        final String action;
        final boolean highPriority;
        switch (type) {
            case NO_INTERNET:
                action = ConnectivityManager.ACTION_PROMPT_UNVALIDATED;
                // High priority because it is only displayed for explicitly selected networks.
                highPriority = true;
                break;
            case PRIVATE_DNS_BROKEN:
                action = Settings.ACTION_WIRELESS_SETTINGS;
                // High priority because we should let user know why there is no internet.
                highPriority = true;
                break;
            case LOST_INTERNET:
                action = ConnectivityManager.ACTION_PROMPT_LOST_VALIDATION;
                // High priority because it could help the user avoid unexpected data usage.
                highPriority = true;
                break;
            case PARTIAL_CONNECTIVITY:
                action = ConnectivityManager.ACTION_PROMPT_PARTIAL_CONNECTIVITY;
                // Don't bother the user with a high-priority notification if the network was not
                // explicitly selected by the user.
                highPriority = nai.networkAgentConfig.explicitlySelected;
                break;
            default:
                Slog.wtf(TAG, "Unknown notification type " + type);
                return;
        }

        Intent intent = new Intent(action);
        if (type != NotificationType.PRIVATE_DNS_BROKEN) {
            intent.setData(Uri.fromParts("netId", Integer.toString(nai.network.netId), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClassName("com.android.settings",
                    "com.android.settings.wifi.WifiNoInternetDialog");
        }

        PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT, null, UserHandle.CURRENT);

        mNotifier.showNotification(nai.network.netId, type, nai, null, pendingIntent, highPriority);
    }

    private boolean shouldPromptUnvalidated(NetworkAgentInfo nai) {
        // Don't prompt if the network is validated, and don't prompt on captive portals
        // because we're already prompting the user to sign in.
        if (nai.everValidated || nai.everCaptivePortalDetected) {
            return false;
        }

        // If a network has partial connectivity, always prompt unless the user has already accepted
        // partial connectivity and selected don't ask again. This ensures that if the device
        // automatically connects to a network that has partial Internet access, the user will
        // always be able to use it, either because they've already chosen "don't ask again" or
        // because we have prompt them.
        if (nai.partialConnectivity && !nai.networkAgentConfig.acceptPartialConnectivity) {
            return true;
        }

        // If a network has no Internet access, only prompt if the network was explicitly selected
        // and if the user has not already told us to use the network regardless of whether it
        // validated or not.
        if (nai.networkAgentConfig.explicitlySelected
                && !nai.networkAgentConfig.acceptUnvalidated) {
            return true;
        }

        return false;
    }

    private void handlePromptUnvalidated(Network network) {
        if (VDBG || DDBG) log("handlePromptUnvalidated " + network);
        NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);

        if (nai == null || !shouldPromptUnvalidated(nai)) {
            return;
        }

        // Stop automatically reconnecting to this network in the future. Automatically connecting
        // to a network that provides no or limited connectivity is not useful, because the user
        // cannot use that network except through the notification shown by this method, and the
        // notification is only shown if the network is explicitly selected by the user.
        nai.asyncChannel.sendMessage(NetworkAgent.CMD_PREVENT_AUTOMATIC_RECONNECT);

        // TODO: Evaluate if it's needed to wait 8 seconds for triggering notification when
        // NetworkMonitor detects the network is partial connectivity. Need to change the design to
        // popup the notification immediately when the network is partial connectivity.
        if (nai.partialConnectivity) {
            showNetworkNotification(nai, NotificationType.PARTIAL_CONNECTIVITY);
        } else {
            showNetworkNotification(nai, NotificationType.NO_INTERNET);
        }
    }

    private void handleNetworkUnvalidated(NetworkAgentInfo nai) {
        NetworkCapabilities nc = nai.networkCapabilities;
        if (DBG) log("handleNetworkUnvalidated " + nai.toShortString() + " cap=" + nc);

        if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return;
        }

        if (mMultinetworkPolicyTracker.shouldNotifyWifiUnvalidated()) {
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
                case EVENT_REGISTER_NETWORK_PROVIDER: {
                    handleRegisterNetworkProvider((NetworkProviderInfo) msg.obj);
                    break;
                }
                case EVENT_UNREGISTER_NETWORK_PROVIDER: {
                    handleUnregisterNetworkProvider((Messenger) msg.obj);
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
                    handleReleaseNetworkRequest((NetworkRequest) msg.obj, msg.arg1,
                            /* callOnUnavailable */ false);
                    break;
                }
                case EVENT_SET_ACCEPT_UNVALIDATED: {
                    Network network = (Network) msg.obj;
                    handleSetAcceptUnvalidated(network, toBool(msg.arg1), toBool(msg.arg2));
                    break;
                }
                case EVENT_SET_ACCEPT_PARTIAL_CONNECTIVITY: {
                    Network network = (Network) msg.obj;
                    handleSetAcceptPartialConnectivity(network, toBool(msg.arg1),
                            toBool(msg.arg2));
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
                case NetworkAgent.CMD_START_SOCKET_KEEPALIVE: {
                    mKeepaliveTracker.handleStartKeepalive(msg);
                    break;
                }
                // Sent by KeepaliveTracker to process an app request on the state machine thread.
                case NetworkAgent.CMD_STOP_SOCKET_KEEPALIVE: {
                    NetworkAgentInfo nai = getNetworkAgentInfoForNetwork((Network) msg.obj);
                    int slot = msg.arg1;
                    int reason = msg.arg2;
                    mKeepaliveTracker.handleStopKeepalive(nai, slot, reason);
                    break;
                }
                case EVENT_SYSTEM_READY: {
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
            }
        }
    }

    @Override
    @Deprecated
    public int getLastTetherError(String iface) {
        final TetheringManager tm = (TetheringManager) mContext.getSystemService(
                Context.TETHERING_SERVICE);
        return tm.getLastTetherError(iface);
    }

    @Override
    @Deprecated
    public String[] getTetherableIfaces() {
        final TetheringManager tm = (TetheringManager) mContext.getSystemService(
                Context.TETHERING_SERVICE);
        return tm.getTetherableIfaces();
    }

    @Override
    @Deprecated
    public String[] getTetheredIfaces() {
        final TetheringManager tm = (TetheringManager) mContext.getSystemService(
                Context.TETHERING_SERVICE);
        return tm.getTetheredIfaces();
    }


    @Override
    @Deprecated
    public String[] getTetheringErroredIfaces() {
        final TetheringManager tm = (TetheringManager) mContext.getSystemService(
                Context.TETHERING_SERVICE);

        return tm.getTetheringErroredIfaces();
    }

    @Override
    @Deprecated
    public String[] getTetherableUsbRegexs() {
        final TetheringManager tm = (TetheringManager) mContext.getSystemService(
                Context.TETHERING_SERVICE);

        return tm.getTetherableUsbRegexs();
    }

    @Override
    @Deprecated
    public String[] getTetherableWifiRegexs() {
        final TetheringManager tm = (TetheringManager) mContext.getSystemService(
                Context.TETHERING_SERVICE);
        return tm.getTetherableWifiRegexs();
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

        final NetworkAgentInfo nai;
        if (network == null) {
            nai = getDefaultNetwork();
        } else {
            nai = getNetworkAgentInfoForNetwork(network);
        }
        if (nai != null) {
            mConnectivityDiagnosticsHandler.sendMessage(
                    mConnectivityDiagnosticsHandler.obtainMessage(
                            ConnectivityDiagnosticsHandler.EVENT_NETWORK_CONNECTIVITY_REPORTED,
                            connectivityInfo, 0, nai));
        }
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
        nai.networkMonitor().forceReevaluation(uid);
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
        } else if (mDeps.queryUserAccess(Binder.getCallingUid(), network.netId)) {
            // Don't call getLinkProperties() as it requires ACCESS_NETWORK_STATE permission, which
            // caller may not have.
            return getLinkPropertiesProxyInfo(network);
        }
        // No proxy info available if the calling UID does not have network access.
        return null;
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
        NetworkStack.checkNetworkStackPermission(mContext);
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
                mHandler.obtainMessage(what).sendToTarget();
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
                return vpn.prepare(oldPackage, newPackage, VpnManager.TYPE_VPN_SERVICE);
            } else {
                return false;
            }
        }
    }

    /**
     * Set whether the VPN package has the ability to launch VPNs without user intervention. This
     * method is used by system-privileged apps. VPN permissions are checked in the {@link Vpn}
     * class. If the caller is not {@code userId}, {@link
     * android.Manifest.permission.INTERACT_ACROSS_USERS_FULL} permission is required.
     *
     * @param packageName The package for which authorization state should change.
     * @param userId User for whom {@code packageName} is installed.
     * @param authorized {@code true} if this app should be able to start a VPN connection without
     *     explicit user approval, {@code false} if not.
     * @param vpnType The {@link VpnManager.VpnType} constant representing what class of VPN
     *     permissions should be granted. When unauthorizing an app, {@link
     *     VpnManager.TYPE_VPN_NONE} should be used.
     * @hide
     */
    @Override
    public void setVpnPackageAuthorization(
            String packageName, int userId, @VpnManager.VpnType int vpnType) {
        enforceCrossUserPermission(userId);

        synchronized (mVpns) {
            Vpn vpn = mVpns.get(userId);
            if (vpn != null) {
                vpn.setPackageAuthorization(packageName, vpnType);
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
     * Stores the given VPN profile based on the provisioning package name.
     *
     * <p>If there is already a VPN profile stored for the provisioning package, this call will
     * overwrite the profile.
     *
     * <p>This is designed to serve the VpnManager only; settings-based VPN profiles are managed
     * exclusively by the Settings app, and passed into the platform at startup time.
     *
     * @return {@code true} if user consent has already been granted, {@code false} otherwise.
     * @hide
     */
    @Override
    public boolean provisionVpnProfile(@NonNull VpnProfile profile, @NonNull String packageName) {
        final int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            return mVpns.get(user).provisionVpnProfile(packageName, profile, mKeyStore);
        }
    }

    /**
     * Deletes the stored VPN profile for the provisioning package
     *
     * <p>If there are no profiles for the given package, this method will silently succeed.
     *
     * <p>This is designed to serve the VpnManager only; settings-based VPN profiles are managed
     * exclusively by the Settings app, and passed into the platform at startup time.
     *
     * @hide
     */
    @Override
    public void deleteVpnProfile(@NonNull String packageName) {
        final int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            mVpns.get(user).deleteVpnProfile(packageName, mKeyStore);
        }
    }

    /**
     * Starts the VPN based on the stored profile for the given package
     *
     * <p>This is designed to serve the VpnManager only; settings-based VPN profiles are managed
     * exclusively by the Settings app, and passed into the platform at startup time.
     *
     * @throws IllegalArgumentException if no profile was found for the given package name.
     * @hide
     */
    @Override
    public void startVpnProfile(@NonNull String packageName) {
        final int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            throwIfLockdownEnabled();
            mVpns.get(user).startVpnProfile(packageName, mKeyStore);
        }
    }

    /**
     * Stops the Platform VPN if the provided package is running one.
     *
     * <p>This is designed to serve the VpnManager only; settings-based VPN profiles are managed
     * exclusively by the Settings app, and passed into the platform at startup time.
     *
     * @hide
     */
    @Override
    public void stopVpnProfile(@NonNull String packageName) {
        final int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            mVpns.get(user).stopVpnProfile(packageName);
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
     * Return the information of all ongoing VPNs.
     *
     * <p>This method is used to update NetworkStatsService.
     *
     * <p>Must be called on the handler thread.
     */
    private VpnInfo[] getAllVpnInfo() {
        ensureRunningOnConnectivityServiceThread();
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
     *         information, e.g underlying ifaces.
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
            NetworkAgentInfo defaultNai = getDefaultNetwork();
            if (defaultNai != null) {
                underlyingNetworks = new Network[] { defaultNai.network };
            }
        }
        if (underlyingNetworks != null && underlyingNetworks.length > 0) {
            List<String> interfaces = new ArrayList<>();
            for (Network network : underlyingNetworks) {
                LinkProperties lp = getLinkProperties(network);
                if (lp != null) {
                    for (String iface : lp.getAllInterfaceNames()) {
                        if (!TextUtils.isEmpty(iface)) {
                            interfaces.add(iface);
                        }
                    }
                }
            }
            if (!interfaces.isEmpty()) {
                info.underlyingIfaces = interfaces.toArray(new String[interfaces.size()]);
            }
        }
        return info.underlyingIfaces == null ? null : info;
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
     */
    private void updateAllVpnsCapabilities() {
        Network defaultNetwork = getNetwork(getDefaultNetwork());
        synchronized (mVpns) {
            for (int i = 0; i < mVpns.size(); i++) {
                final Vpn vpn = mVpns.valueAt(i);
                NetworkCapabilities nc = vpn.updateCapabilities(defaultNetwork);
                updateVpnCapabilities(vpn, nc);
            }
        }
    }

    private void updateVpnCapabilities(Vpn vpn, @Nullable NetworkCapabilities nc) {
        ensureRunningOnConnectivityServiceThread();
        NetworkAgentInfo vpnNai = getNetworkAgentInfoForNetId(vpn.getNetId());
        if (vpnNai == null || nc == null) {
            return;
        }
        updateCapabilities(vpnNai.getCurrentScore(), vpnNai, nc);
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
                setLockdownTracker(new LockdownVpnTracker(mContext, this, mHandler, vpn, profile));
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

    /**
     * Throws if there is any currently running, always-on Legacy VPN.
     *
     * <p>The LockdownVpnTracker and mLockdownEnabled both track whether an always-on Legacy VPN is
     * running across the entire system. Tracking for app-based VPNs is done on a per-user,
     * per-package basis in Vpn.java
     */
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

            return vpn.startAlwaysOnVpn(mKeyStore);
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
            return vpn.isAlwaysOnPackageSupported(packageName, mKeyStore);
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
            if (!vpn.setAlwaysOnPackage(packageName, lockdown, lockdownWhitelist, mKeyStore)) {
                return false;
            }
            if (!startAlwaysOnVpn(userId)) {
                vpn.setAlwaysOnPackage(null, false, null, mKeyStore);
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
        XmlPullParser parser;
        Configuration config = mContext.getResources().getConfiguration();

        try (FileReader fileReader = new FileReader(mProvisioningUrlFile)) {
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
        }
        return null;
    }

    @Override
    public String getMobileProvisioningUrl() {
        enforceSettingsPermission();
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
        enforceSettingsPermission();
        if (!ConnectivityManager.isNetworkTypeValid(networkType)) {
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            // Concatenate the range of types onto the range of NetIDs.
            int id = NetIdManager.MAX_NET_ID + 1 + (networkType - ConnectivityManager.TYPE_NONE);
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
            userVpn = new Vpn(mHandler.getLooper(), mContext, mNMS, userId, mKeyStore);
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
        Network defaultNetwork = getNetwork(getDefaultNetwork());
        synchronized (mVpns) {
            final int vpnsSize = mVpns.size();
            for (int i = 0; i < vpnsSize; i++) {
                Vpn vpn = mVpns.valueAt(i);
                vpn.onUserAdded(userId);
                NetworkCapabilities nc = vpn.updateCapabilities(defaultNetwork);
                updateVpnCapabilities(vpn, nc);
            }
        }
    }

    private void onUserRemoved(int userId) {
        mPermissionMonitor.onUserRemoved(userId);
        Network defaultNetwork = getNetwork(getDefaultNetwork());
        synchronized (mVpns) {
            final int vpnsSize = mVpns.size();
            for (int i = 0; i < vpnsSize; i++) {
                Vpn vpn = mVpns.valueAt(i);
                vpn.onUserRemoved(userId);
                NetworkCapabilities nc = vpn.updateCapabilities(defaultNetwork);
                updateVpnCapabilities(vpn, nc);
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
                vpn.startAlwaysOnVpn(mKeyStore);
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
                vpn.setAlwaysOnPackage(null, false, null, mKeyStore);
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
            ensureRunningOnConnectivityServiceThread();
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

    private final HashMap<Messenger, NetworkProviderInfo> mNetworkProviderInfos = new HashMap<>();
    private final HashMap<NetworkRequest, NetworkRequestInfo> mNetworkRequests = new HashMap<>();

    private static final int MAX_NETWORK_REQUESTS_PER_UID = 100;
    // Map from UID to number of NetworkRequests that UID has filed.
    @GuardedBy("mUidToNetworkRequestCount")
    private final SparseIntArray mUidToNetworkRequestCount = new SparseIntArray();

    private static class NetworkProviderInfo {
        public final String name;
        public final Messenger messenger;
        private final AsyncChannel mAsyncChannel;
        private final IBinder.DeathRecipient mDeathRecipient;
        public final int providerId;

        NetworkProviderInfo(String name, Messenger messenger, AsyncChannel asyncChannel,
                int providerId, IBinder.DeathRecipient deathRecipient) {
            this.name = name;
            this.messenger = messenger;
            this.providerId = providerId;
            mAsyncChannel = asyncChannel;
            mDeathRecipient = deathRecipient;

            if ((mAsyncChannel == null) == (mDeathRecipient == null)) {
                throw new AssertionError("Must pass exactly one of asyncChannel or deathRecipient");
            }
        }

        boolean isLegacyNetworkFactory() {
            return mAsyncChannel != null;
        }

        void sendMessageToNetworkProvider(int what, int arg1, int arg2, Object obj) {
            try {
                messenger.send(Message.obtain(null /* handler */, what, arg1, arg2, obj));
            } catch (RemoteException e) {
                // Remote process died. Ignore; the death recipient will remove this
                // NetworkProviderInfo from mNetworkProviderInfos.
            }
        }

        void requestNetwork(NetworkRequest request, int score, int servingProviderId) {
            if (isLegacyNetworkFactory()) {
                mAsyncChannel.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK, score,
                        servingProviderId, request);
            } else {
                sendMessageToNetworkProvider(NetworkProvider.CMD_REQUEST_NETWORK, score,
                            servingProviderId, request);
            }
        }

        void cancelRequest(NetworkRequest request) {
            if (isLegacyNetworkFactory()) {
                mAsyncChannel.sendMessage(android.net.NetworkFactory.CMD_CANCEL_REQUEST, request);
            } else {
                sendMessageToNetworkProvider(NetworkProvider.CMD_CANCEL_REQUEST, 0, 0, request);
            }
        }

        void connect(Context context, Handler handler) {
            if (isLegacyNetworkFactory()) {
                mAsyncChannel.connect(context, handler, messenger);
            } else {
                try {
                    messenger.getBinder().linkToDeath(mDeathRecipient, 0);
                } catch (RemoteException e) {
                    mDeathRecipient.binderDied();
                }
            }
        }

        void completeConnection() {
            if (isLegacyNetworkFactory()) {
                mAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
            }
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
        // The network currently satisfying this request, or null if none. Must only be touched
        // on the handler thread. This only makes sense for network requests and not for listens,
        // as defined by NetworkRequest#isRequest(). For listens, this is always null.
        @Nullable
        NetworkAgentInfo mSatisfier;
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

        NetworkRequestInfo(NetworkRequest r) {
            this(r, null);
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
            return "uid/pid:" + mUid + "/" + mPid + " " + request
                    + (mPendingIntent == null ? "" : " to trigger " + mPendingIntent);
        }
    }

    private void ensureRequestableCapabilities(NetworkCapabilities networkCapabilities) {
        final String badCapability = networkCapabilities.describeFirstNonRequestableCapability();
        if (badCapability != null) {
            throw new IllegalArgumentException("Cannot request network with " + badCapability);
        }
    }

    // This checks that the passed capabilities either do not request a
    // specific SSID/SignalStrength, or the calling app has permission to do so.
    private void ensureSufficientPermissionsForRequest(NetworkCapabilities nc,
            int callerPid, int callerUid, String callerPackageName) {
        if (null != nc.getSSID() && !checkSettingsPermission(callerPid, callerUid)) {
            throw new SecurityException("Insufficient permissions to request a specific SSID");
        }

        if (nc.hasSignalStrength()
                && !checkNetworkSignalStrengthWakeupPermission(callerPid, callerUid)) {
            throw new SecurityException(
                    "Insufficient permissions to request a specific signal strength");
        }
        mAppOpsManager.checkPackage(callerUid, callerPackageName);
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
                    detail, Arrays.toString(thresholdsArray.toArray()), nai.toShortString()));
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
    }

    private void ensureValid(NetworkCapabilities nc) {
        ensureValidNetworkSpecifier(nc);
        if (nc.isPrivateDnsBroken()) {
            throw new IllegalArgumentException("Can't request broken private DNS");
        }
    }

    @Override
    public NetworkRequest requestNetwork(NetworkCapabilities networkCapabilities,
            Messenger messenger, int timeoutMs, IBinder binder, int legacyType,
            @NonNull String callingPackageName) {
        if (legacyType != TYPE_NONE && !checkNetworkStackPermission()) {
            throw new SecurityException("Insufficient permissions to specify legacy type");
        }
        final int callingUid = Binder.getCallingUid();
        final NetworkRequest.Type type = (networkCapabilities == null)
                ? NetworkRequest.Type.TRACK_DEFAULT
                : NetworkRequest.Type.REQUEST;
        // If the requested networkCapabilities is null, take them instead from
        // the default network request. This allows callers to keep track of
        // the system default network.
        if (type == NetworkRequest.Type.TRACK_DEFAULT) {
            networkCapabilities = createDefaultNetworkCapabilitiesForUid(callingUid);
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
                Binder.getCallingPid(), callingUid, callingPackageName);
        // Set the UID range for this request to the single UID of the requester, or to an empty
        // set of UIDs if the caller has the appropriate permission and UIDs have not been set.
        // This will overwrite any allowed UIDs in the requested capabilities. Though there
        // are no visible methods to set the UIDs, an app could use reflection to try and get
        // networks for other apps so it's essential that the UIDs are overwritten.
        restrictRequestUidsForCallerAndSetRequestorInfo(networkCapabilities,
                callingUid, callingPackageName);

        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Bad timeout specified");
        }
        ensureValid(networkCapabilities);

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
                    uidReqs = 0;
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
            PendingIntent operation, @NonNull String callingPackageName) {
        checkNotNull(operation, "PendingIntent cannot be null.");
        final int callingUid = Binder.getCallingUid();
        networkCapabilities = new NetworkCapabilities(networkCapabilities);
        enforceNetworkRequestPermissions(networkCapabilities);
        enforceMeteredApnPolicy(networkCapabilities);
        ensureRequestableCapabilities(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities,
                Binder.getCallingPid(), callingUid, callingPackageName);
        ensureValidNetworkSpecifier(networkCapabilities);
        restrictRequestUidsForCallerAndSetRequestorInfo(networkCapabilities,
                callingUid, callingPackageName);

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
            Messenger messenger, IBinder binder, @NonNull String callingPackageName) {
        final int callingUid = Binder.getCallingUid();
        if (!hasWifiNetworkListenPermission(networkCapabilities)) {
            enforceAccessPermission();
        }

        NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities,
                Binder.getCallingPid(), callingUid, callingPackageName);
        restrictRequestUidsForCallerAndSetRequestorInfo(nc, callingUid, callingPackageName);
        // Apps without the CHANGE_NETWORK_STATE permission can't use background networks, so
        // make all their listens include NET_CAPABILITY_FOREGROUND. That way, they will get
        // onLost and onAvailable callbacks when networks move in and out of the background.
        // There is no need to do this for requests because an app without CHANGE_NETWORK_STATE
        // can't request networks.
        restrictBackgroundRequestForCaller(nc);
        ensureValid(nc);

        NetworkRequest networkRequest = new NetworkRequest(nc, TYPE_NONE, nextNetworkRequestId(),
                NetworkRequest.Type.LISTEN);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder);
        if (VDBG) log("listenForNetwork for " + nri);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_LISTENER, nri));
        return networkRequest;
    }

    @Override
    public void pendingListenForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation, @NonNull String callingPackageName) {
        checkNotNull(operation, "PendingIntent cannot be null.");
        final int callingUid = Binder.getCallingUid();
        if (!hasWifiNetworkListenPermission(networkCapabilities)) {
            enforceAccessPermission();
        }
        ensureValid(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities,
                Binder.getCallingPid(), callingUid, callingPackageName);
        final NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        restrictRequestUidsForCallerAndSetRequestorInfo(nc, callingUid, callingPackageName);

        NetworkRequest networkRequest = new NetworkRequest(nc, TYPE_NONE, nextNetworkRequestId(),
                NetworkRequest.Type.LISTEN);
        NetworkRequestInfo nri = new NetworkRequestInfo(networkRequest, operation);
        if (VDBG) log("pendingListenForNetwork for " + nri);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_LISTENER, nri));
    }

    /** Returns the next Network provider ID. */
    public final int nextNetworkProviderId() {
        return mNextNetworkProviderId.getAndIncrement();
    }

    @Override
    public void releaseNetworkRequest(NetworkRequest networkRequest) {
        ensureNetworkRequestHasType(networkRequest);
        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_RELEASE_NETWORK_REQUEST, getCallingUid(), 0, networkRequest));
    }

    @Override
    public int registerNetworkFactory(Messenger messenger, String name) {
        enforceNetworkFactoryPermission();
        NetworkProviderInfo npi = new NetworkProviderInfo(name, messenger, new AsyncChannel(),
                nextNetworkProviderId(), null /* deathRecipient */);
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_PROVIDER, npi));
        return npi.providerId;
    }

    private void handleRegisterNetworkProvider(NetworkProviderInfo npi) {
        if (mNetworkProviderInfos.containsKey(npi.messenger)) {
            // Avoid creating duplicates. even if an app makes a direct AIDL call.
            // This will never happen if an app calls ConnectivityManager#registerNetworkProvider,
            // as that will throw if a duplicate provider is registered.
            Slog.e(TAG, "Attempt to register existing NetworkProviderInfo "
                    + mNetworkProviderInfos.get(npi.messenger).name);
            return;
        }

        if (DBG) log("Got NetworkProvider Messenger for " + npi.name);
        mNetworkProviderInfos.put(npi.messenger, npi);
        npi.connect(mContext, mTrackerHandler);
        if (!npi.isLegacyNetworkFactory()) {
            // Legacy NetworkFactories get their requests when their AsyncChannel connects.
            sendAllRequestsToProvider(npi);
        }
    }

    @Override
    public int registerNetworkProvider(Messenger messenger, String name) {
        enforceNetworkFactoryPermission();
        NetworkProviderInfo npi = new NetworkProviderInfo(name, messenger,
                null /* asyncChannel */, nextNetworkProviderId(),
                () -> unregisterNetworkProvider(messenger));
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_PROVIDER, npi));
        return npi.providerId;
    }

    @Override
    public void unregisterNetworkProvider(Messenger messenger) {
        enforceNetworkFactoryPermission();
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_UNREGISTER_NETWORK_PROVIDER, messenger));
    }

    @Override
    public void unregisterNetworkFactory(Messenger messenger) {
        unregisterNetworkProvider(messenger);
    }

    private void handleUnregisterNetworkProvider(Messenger messenger) {
        NetworkProviderInfo npi = mNetworkProviderInfos.remove(messenger);
        if (npi == null) {
            loge("Failed to find Messenger in unregisterNetworkProvider");
            return;
        }
        if (DBG) log("unregisterNetworkProvider for " + npi.name);
    }

    @Override
    public void declareNetworkRequestUnfulfillable(NetworkRequest request) {
        enforceNetworkFactoryPermission();
        mHandler.post(() -> handleReleaseNetworkRequest(request, Binder.getCallingUid(), true));
    }

    // NOTE: Accessed on multiple threads, must be synchronized on itself.
    @GuardedBy("mNetworkForNetId")
    private final SparseArray<NetworkAgentInfo> mNetworkForNetId = new SparseArray<>();
    // NOTE: Accessed on multiple threads, synchronized with mNetworkForNetId.
    // An entry is first reserved with NetIdManager, prior to being added to mNetworkForNetId, so
    // there may not be a strict 1:1 correlation between the two.
    private final NetIdManager mNetIdManager;

    // NetworkAgentInfo keyed off its connecting messenger
    // TODO - eval if we can reduce the number of lists/hashmaps/sparsearrays
    // NOTE: Only should be accessed on ConnectivityServiceThread, except dump().
    private final HashMap<Messenger, NetworkAgentInfo> mNetworkAgentInfos = new HashMap<>();

    @GuardedBy("mBlockedAppUids")
    private final HashSet<Integer> mBlockedAppUids = new HashSet<>();

    // Note: if mDefaultRequest is changed, NetworkMonitor needs to be updated.
    @NonNull
    private final NetworkRequest mDefaultRequest;
    // The NetworkAgentInfo currently satisfying the default request, if any.
    @Nullable
    private volatile NetworkAgentInfo mDefaultNetworkNai = null;

    // Request used to optionally keep mobile data active even when higher
    // priority networks like Wi-Fi are active.
    private final NetworkRequest mDefaultMobileDataRequest;

    // Request used to optionally keep wifi data active even when higher
    // priority networks like ethernet are active.
    private final NetworkRequest mDefaultWifiRequest;

    private NetworkAgentInfo getDefaultNetwork() {
        return mDefaultNetworkNai;
    }

    @Nullable
    private Network getNetwork(@Nullable NetworkAgentInfo nai) {
        return nai != null ? nai.network : null;
    }

    private void ensureRunningOnConnectivityServiceThread() {
        if (mHandler.getLooper().getThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                    "Not running on ConnectivityService thread: "
                            + Thread.currentThread().getName());
        }
    }

    @VisibleForTesting
    protected boolean isDefaultNetwork(NetworkAgentInfo nai) {
        return nai == getDefaultNetwork();
    }

    private boolean isDefaultRequest(NetworkRequestInfo nri) {
        return nri.request.requestId == mDefaultRequest.requestId;
    }

    // TODO : remove this method. It's a stopgap measure to help sheperding a number of dependent
    // changes that would conflict throughout the automerger graph. Having this method temporarily
    // helps with the process of going through with all these dependent changes across the entire
    // tree.
    /**
     * Register a new agent. {@see #registerNetworkAgent} below.
     */
    public Network registerNetworkAgent(Messenger messenger, NetworkInfo networkInfo,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int currentScore, NetworkAgentConfig networkAgentConfig) {
        return registerNetworkAgent(messenger, networkInfo, linkProperties, networkCapabilities,
                currentScore, networkAgentConfig, NetworkProvider.ID_NONE);
    }

    /**
     * Register a new agent with ConnectivityService to handle a network.
     *
     * @param messenger a messenger for ConnectivityService to contact the agent asynchronously.
     * @param networkInfo the initial info associated with this network. It can be updated later :
     *         see {@link #updateNetworkInfo}.
     * @param linkProperties the initial link properties of this network. They can be updated
     *         later : see {@link #updateLinkProperties}.
     * @param networkCapabilities the initial capabilites of this network. They can be updated
     *         later : see {@link #updateCapabilities}.
     * @param currentScore the initial score of the network. See
     *         {@link NetworkAgentInfo#getCurrentScore}.
     * @param networkAgentConfig metadata about the network. This is never updated.
     * @param providerId the ID of the provider owning this NetworkAgent.
     * @return the network created for this agent.
     */
    public Network registerNetworkAgent(Messenger messenger, NetworkInfo networkInfo,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int currentScore, NetworkAgentConfig networkAgentConfig, int providerId) {
        enforceNetworkFactoryPermission();

        LinkProperties lp = new LinkProperties(linkProperties);
        lp.ensureDirectlyConnectedRoutes();
        // TODO: Instead of passing mDefaultRequest, provide an API to determine whether a Network
        // satisfies mDefaultRequest.
        final NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        final NetworkAgentInfo nai = new NetworkAgentInfo(messenger, new AsyncChannel(),
                new Network(mNetIdManager.reserveNetId()), new NetworkInfo(networkInfo), lp, nc,
                currentScore, mContext, mTrackerHandler, new NetworkAgentConfig(networkAgentConfig),
                this, mNetd, mDnsResolver, mNMS, providerId);
        // Make sure the network capabilities reflect what the agent info says.
        nai.getAndSetNetworkCapabilities(mixInCapabilities(nai, nc));
        final String extraInfo = networkInfo.getExtraInfo();
        final String name = TextUtils.isEmpty(extraInfo)
                ? nai.networkCapabilities.getSSID() : extraInfo;
        if (DBG) log("registerNetworkAgent " + nai);
        final long token = Binder.clearCallingIdentity();
        try {
            mDeps.getNetworkStack().makeNetworkMonitor(
                    nai.network, name, new NetworkMonitorCallbacks(nai));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        // NetworkAgentInfo registration will finish when the NetworkMonitor is created.
        // If the network disconnects or sends any other event before that, messages are deferred by
        // NetworkAgent until nai.asyncChannel.connect(), which will be called when finalizing the
        // registration.
        return nai.network;
    }

    private void handleRegisterNetworkAgent(NetworkAgentInfo nai, INetworkMonitor networkMonitor) {
        nai.onNetworkMonitorCreated(networkMonitor);
        if (VDBG) log("Got NetworkAgent Messenger");
        mNetworkAgentInfos.put(nai.messenger, nai);
        synchronized (mNetworkForNetId) {
            mNetworkForNetId.put(nai.network.netId, nai);
        }

        try {
            networkMonitor.start();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        nai.asyncChannel.connect(mContext, mTrackerHandler, nai.messenger);
        NetworkInfo networkInfo = nai.networkInfo;
        updateNetworkInfo(nai, networkInfo);
        updateUids(nai, null, nai.networkCapabilities);
    }

    private void updateLinkProperties(NetworkAgentInfo networkAgent, LinkProperties newLp,
            @NonNull LinkProperties oldLp) {
        int netId = networkAgent.network.netId;

        // The NetworkAgentInfo does not know whether clatd is running on its network or not, or
        // whether there is a NAT64 prefix. Before we do anything else, make sure its LinkProperties
        // are accurate.
        networkAgent.clatd.fixupLinkProperties(oldLp, newLp);

        updateInterfaces(newLp, oldLp, netId, networkAgent.networkCapabilities,
                networkAgent.networkInfo.getType());

        // update filtering rules, need to happen after the interface update so netd knows about the
        // new interface (the interface name -> index map becomes initialized)
        updateVpnFiltering(newLp, oldLp, networkAgent);

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

        updateWakeOnLan(newLp);

        // Captive portal data is obtained from NetworkMonitor and stored in NetworkAgentInfo,
        // it is not contained in LinkProperties sent from NetworkAgents so needs to be merged here.
        newLp.setCaptivePortalData(networkAgent.captivePortalData);

        // TODO - move this check to cover the whole function
        if (!Objects.equals(newLp, oldLp)) {
            synchronized (networkAgent) {
                networkAgent.linkProperties = newLp;
            }
            // Start or stop DNS64 detection and 464xlat according to network state.
            networkAgent.clatd.update();
            notifyIfacesChangedForNetworkStats();
            networkAgent.networkMonitor().notifyLinkPropertiesChanged(newLp);
            if (networkAgent.everConnected) {
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

    private void updateInterfaces(final @Nullable LinkProperties newLp,
            final @Nullable LinkProperties oldLp, final int netId,
            final @Nullable NetworkCapabilities caps, final int legacyType) {
        final CompareResult<String> interfaceDiff = new CompareResult<>(
                oldLp != null ? oldLp.getAllInterfaceNames() : null,
                newLp != null ? newLp.getAllInterfaceNames() : null);
        if (!interfaceDiff.added.isEmpty()) {
            final IBatteryStats bs = mDeps.getBatteryStatsService();
            for (final String iface : interfaceDiff.added) {
                try {
                    if (DBG) log("Adding iface " + iface + " to network " + netId);
                    mNMS.addInterfaceToNetwork(iface, netId);
                    wakeupModifyInterface(iface, caps, true);
                    bs.noteNetworkInterfaceType(iface, legacyType);
                } catch (Exception e) {
                    loge("Exception adding interface: " + e);
                }
            }
        }
        for (final String iface : interfaceDiff.removed) {
            try {
                if (DBG) log("Removing iface " + iface + " from network " + netId);
                wakeupModifyInterface(iface, caps, false);
                mNMS.removeInterfaceFromNetwork(iface, netId);
            } catch (Exception e) {
                loge("Exception removing interface: " + e);
            }
        }
    }

    // TODO: move to frameworks/libs/net.
    private RouteInfoParcel convertRouteInfo(RouteInfo route) {
        final String nextHop;

        switch (route.getType()) {
            case RouteInfo.RTN_UNICAST:
                if (route.hasGateway()) {
                    nextHop = route.getGateway().getHostAddress();
                } else {
                    nextHop = INetd.NEXTHOP_NONE;
                }
                break;
            case RouteInfo.RTN_UNREACHABLE:
                nextHop = INetd.NEXTHOP_UNREACHABLE;
                break;
            case RouteInfo.RTN_THROW:
                nextHop = INetd.NEXTHOP_THROW;
                break;
            default:
                nextHop = INetd.NEXTHOP_NONE;
                break;
        }

        final RouteInfoParcel rip = new RouteInfoParcel();
        rip.ifName = route.getInterface();
        rip.destination = route.getDestination().toString();
        rip.nextHop = nextHop;
        rip.mtu = route.getMtu();

        return rip;
    }

    /**
     * Have netd update routes from oldLp to newLp.
     * @return true if routes changed between oldLp and newLp
     */
    private boolean updateRoutes(LinkProperties newLp, LinkProperties oldLp, int netId) {
        Function<RouteInfo, IpPrefix> getDestination = (r) -> r.getDestination();
        // compare the route diff to determine which routes have been updated
        CompareOrUpdateResult<IpPrefix, RouteInfo> routeDiff = new CompareOrUpdateResult<>(
                oldLp != null ? oldLp.getAllRoutes() : null,
                newLp != null ? newLp.getAllRoutes() : null,
                getDestination);

        // add routes before removing old in case it helps with continuous connectivity

        // do this twice, adding non-next-hop routes first, then routes they are dependent on
        for (RouteInfo route : routeDiff.added) {
            if (route.hasGateway()) continue;
            if (VDBG || DDBG) log("Adding Route [" + route + "] to network " + netId);
            try {
                mNetd.networkAddRouteParcel(netId, convertRouteInfo(route));
            } catch (Exception e) {
                if ((route.getDestination().getAddress() instanceof Inet4Address) || VDBG) {
                    loge("Exception in networkAddRouteParcel for non-gateway: " + e);
                }
            }
        }
        for (RouteInfo route : routeDiff.added) {
            if (!route.hasGateway()) continue;
            if (VDBG || DDBG) log("Adding Route [" + route + "] to network " + netId);
            try {
                mNetd.networkAddRouteParcel(netId, convertRouteInfo(route));
            } catch (Exception e) {
                if ((route.getGateway() instanceof Inet4Address) || VDBG) {
                    loge("Exception in networkAddRouteParcel for gateway: " + e);
                }
            }
        }

        for (RouteInfo route : routeDiff.removed) {
            if (VDBG || DDBG) log("Removing Route [" + route + "] from network " + netId);
            try {
                mNetd.networkRemoveRouteParcel(netId, convertRouteInfo(route));
            } catch (Exception e) {
                loge("Exception in networkRemoveRouteParcel: " + e);
            }
        }

        for (RouteInfo route : routeDiff.updated) {
            if (VDBG || DDBG) log("Updating Route [" + route + "] from network " + netId);
            try {
                mNetd.networkUpdateRouteParcel(netId, convertRouteInfo(route));
            } catch (Exception e) {
                loge("Exception in networkUpdateRouteParcel: " + e);
            }
        }
        return !routeDiff.added.isEmpty() || !routeDiff.removed.isEmpty()
                || !routeDiff.updated.isEmpty();
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

    private void updateVpnFiltering(LinkProperties newLp, LinkProperties oldLp,
            NetworkAgentInfo nai) {
        final String oldIface = oldLp != null ? oldLp.getInterfaceName() : null;
        final String newIface = newLp != null ? newLp.getInterfaceName() : null;
        final boolean wasFiltering = requiresVpnIsolation(nai, nai.networkCapabilities, oldLp);
        final boolean needsFiltering = requiresVpnIsolation(nai, nai.networkCapabilities, newLp);

        if (!wasFiltering && !needsFiltering) {
            // Nothing to do.
            return;
        }

        if (Objects.equals(oldIface, newIface) && (wasFiltering == needsFiltering)) {
            // Nothing changed.
            return;
        }

        final Set<UidRange> ranges = nai.networkCapabilities.getUids();
        final int vpnAppUid = nai.networkCapabilities.getOwnerUid();
        // TODO: this create a window of opportunity for apps to receive traffic between the time
        // when the old rules are removed and the time when new rules are added. To fix this,
        // make eBPF support two whitelisted interfaces so here new rules can be added before the
        // old rules are being removed.
        if (wasFiltering) {
            mPermissionMonitor.onVpnUidRangesRemoved(oldIface, ranges, vpnAppUid);
        }
        if (needsFiltering) {
            mPermissionMonitor.onVpnUidRangesAdded(newIface, ranges, vpnAppUid);
        }
    }

    private void updateWakeOnLan(@NonNull LinkProperties lp) {
        lp.setWakeOnLanSupported(mWolSupportedInterfaces.contains(lp.getInterfaceName()));
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

    private void updateNetworkPermissions(@NonNull final NetworkAgentInfo nai,
            @NonNull final NetworkCapabilities newNc) {
        final int oldPermission = getNetworkPermission(nai.networkCapabilities);
        final int newPermission = getNetworkPermission(newNc);
        if (oldPermission != newPermission && nai.created && !nai.isVPN()) {
            try {
                mNMS.setNetworkPermission(nai.network.netId, newPermission);
            } catch (RemoteException e) {
                loge("Exception in setNetworkPermission: " + e);
            }
        }
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
         // TODO: remove this altogether and make it the responsibility of the NetworkProviders to
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
        if (nai.partialConnectivity) {
            newNc.addCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY);
        } else {
            newNc.removeCapability(NET_CAPABILITY_PARTIAL_CONNECTIVITY);
        }
        newNc.setPrivateDnsBroken(nai.networkCapabilities.isPrivateDnsBroken());

        // TODO : remove this once all factories are updated to send NOT_SUSPENDED and NOT_ROAMING
        if (!newNc.hasTransport(TRANSPORT_CELLULAR)) {
            newNc.addCapability(NET_CAPABILITY_NOT_SUSPENDED);
            newNc.addCapability(NET_CAPABILITY_NOT_ROAMING);
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
    private void updateCapabilities(final int oldScore, @NonNull final NetworkAgentInfo nai,
            @NonNull final NetworkCapabilities nc) {
        NetworkCapabilities newNc = mixInCapabilities(nai, nc);
        if (Objects.equals(nai.networkCapabilities, newNc)) return;
        updateNetworkPermissions(nai, newNc);
        final NetworkCapabilities prevNc = nai.getAndSetNetworkCapabilities(newNc);

        updateUids(nai, prevNc, newNc);

        if (nai.getCurrentScore() == oldScore && newNc.equalRequestableCapabilities(prevNc)) {
            // If the requestable capabilities haven't changed, and the score hasn't changed, then
            // the change we're processing can't affect any requests, it can only affect the listens
            // on this network. We might have been called by rematchNetworkAndRequests when a
            // network changed foreground state.
            processListenRequests(nai);
            final boolean prevSuspended = !prevNc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED);
            final boolean suspended = !newNc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED);
            final boolean prevRoaming = !prevNc.hasCapability(NET_CAPABILITY_NOT_ROAMING);
            final boolean roaming = !newNc.hasCapability(NET_CAPABILITY_NOT_ROAMING);
            if (prevSuspended != suspended || prevRoaming != roaming) {
                // TODO (b/73132094) : remove this call once the few users of onSuspended and
                // onResumed have been removed.
                notifyNetworkCallbacks(nai, suspended ? ConnectivityManager.CALLBACK_SUSPENDED
                        : ConnectivityManager.CALLBACK_RESUMED);
                // updateNetworkInfo will mix in the suspended info from the capabilities and
                // take appropriate action for the network having possibly changed state.
                updateNetworkInfo(nai, nai.networkInfo);
            }
        } else {
            // If the requestable capabilities have changed or the score changed, we can't have been
            // called by rematchNetworkAndRequests, so it's safe to start a rematch.
            rematchAllNetworksAndRequests();
            notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_CAP_CHANGED);
        }

        // TODO : static analysis indicates that prevNc can't be null here (getAndSetNetworkCaps
        // never returns null), so mark the relevant members and functions in nai as @NonNull and
        // remove this test
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

    /**
     * Returns whether VPN isolation (ingress interface filtering) should be applied on the given
     * network.
     *
     * Ingress interface filtering enforces that all apps under the given network can only receive
     * packets from the network's interface (and loopback). This is important for VPNs because
     * apps that cannot bypass a fully-routed VPN shouldn't be able to receive packets from any
     * non-VPN interfaces.
     *
     * As a result, this method should return true iff
     *  1. the network is an app VPN (not legacy VPN)
     *  2. the VPN does not allow bypass
     *  3. the VPN is fully-routed
     *  4. the VPN interface is non-null
     *
     * @see INetd#firewallAddUidInterfaceRules
     * @see INetd#firewallRemoveUidInterfaceRules
     */
    private boolean requiresVpnIsolation(@NonNull NetworkAgentInfo nai, NetworkCapabilities nc,
            LinkProperties lp) {
        if (nc == null || lp == null) return false;
        return nai.isVPN()
                && !nai.networkAgentConfig.allowBypass
                && nc.getOwnerUid() != Process.SYSTEM_UID
                && lp.getInterfaceName() != null
                && (lp.hasIPv4DefaultRoute() || lp.hasIPv6DefaultRoute());
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
            // When updating the VPN uid routing rules, add the new range first then remove the old
            // range. If old range were removed first, there would be a window between the old
            // range being removed and the new range being added, during which UIDs contained
            // in both ranges are not subject to any VPN routing rules. Adding new range before
            // removing old range works because, unlike the filtering rules below, it's possible to
            // add duplicate UID routing rules.
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
            final boolean wasFiltering = requiresVpnIsolation(nai, prevNc, nai.linkProperties);
            final boolean shouldFilter = requiresVpnIsolation(nai, newNc, nai.linkProperties);
            final String iface = nai.linkProperties.getInterfaceName();
            // For VPN uid interface filtering, old ranges need to be removed before new ranges can
            // be added, due to the range being expanded and stored as invidiual UIDs. For example
            // the UIDs might be updated from [0, 99999] to ([0, 10012], [10014, 99999]) which means
            // prevRanges = [0, 99999] while newRanges = [0, 10012], [10014, 99999]. If prevRanges
            // were added first and then newRanges got removed later, there would be only one uid
            // 10013 left. A consequence of removing old ranges before adding new ranges is that
            // there is now a window of opportunity when the UIDs are not subject to any filtering.
            // Note that this is in contrast with the (more robust) update of VPN routing rules
            // above, where the addition of new ranges happens before the removal of old ranges.
            // TODO Fix this window by computing an accurate diff on Set<UidRange>, so the old range
            // to be removed will never overlap with the new range to be added.
            if (wasFiltering && !prevRanges.isEmpty()) {
                mPermissionMonitor.onVpnUidRangesRemoved(iface, prevRanges, prevNc.getOwnerUid());
            }
            if (shouldFilter && !newRanges.isEmpty()) {
                mPermissionMonitor.onVpnUidRangesAdded(iface, newRanges, newNc.getOwnerUid());
            }
        } catch (Exception e) {
            // Never crash!
            loge("Exception in updateUids: ", e);
        }
    }

    public void handleUpdateLinkProperties(NetworkAgentInfo nai, LinkProperties newLp) {
        ensureRunningOnConnectivityServiceThread();

        if (getNetworkAgentInfoForNetId(nai.network.netId) != nai) {
            // Ignore updates for disconnected networks
            return;
        }
        // newLp is already a defensive copy.
        newLp.ensureDirectlyConnectedRoutes();
        if (VDBG || DDBG) {
            log("Update of LinkProperties for " + nai.toShortString()
                    + "; created=" + nai.created
                    + "; everConnected=" + nai.everConnected);
        }
        updateLinkProperties(nai, newLp, new LinkProperties(nai.linkProperties));
    }

    private void sendUpdatedScoreToFactories(NetworkAgentInfo nai) {
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            // Don't send listening requests to factories. b/17393458
            if (nr.isListen()) continue;
            sendUpdatedScoreToFactories(nr, nai);
        }
    }

    private void sendUpdatedScoreToFactories(@NonNull NetworkRequest networkRequest,
            @Nullable NetworkAgentInfo nai) {
        final int score;
        final int serial;
        if (nai != null) {
            score = nai.getCurrentScore();
            serial = nai.factorySerialNumber;
        } else {
            score = 0;
            serial = 0;
        }
        if (VDBG || DDBG){
            log("sending new Min Network Score(" + score + "): " + networkRequest.toString());
        }
        for (NetworkProviderInfo npi : mNetworkProviderInfos.values()) {
            npi.requestNetwork(networkRequest, score, serial);
        }
    }

    /** Sends all current NetworkRequests to the specified factory. */
    private void sendAllRequestsToProvider(NetworkProviderInfo npi) {
        ensureRunningOnConnectivityServiceThread();
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            if (nri.request.isListen()) continue;
            NetworkAgentInfo nai = nri.mSatisfier;
            final int score;
            final int serial;
            if (nai != null) {
                score = nai.getCurrentScore();
                serial = nai.factorySerialNumber;
            } else {
                score = 0;
                serial = NetworkProvider.ID_NONE;
            }
            npi.requestNetwork(nri.request, score, serial);
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
            // Default request has no msgr. Also prevents callbacks from being invoked for
            // NetworkRequestInfos registered with ConnectivityDiagnostics requests. Those callbacks
            // are Type.LISTEN, but should not have NetworkCallbacks invoked.
            return;
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
                final NetworkCapabilities nc =
                        networkCapabilitiesRestrictedForCallerPermissions(
                                networkAgent.networkCapabilities, nri.mPid, nri.mUid);
                putParcelable(
                        bundle,
                        maybeSanitizeLocationInfoForCaller(
                                nc, nri.mUid, nri.request.getRequestorPackageName()));
                putParcelable(bundle, linkPropertiesRestrictedForCallerPermissions(
                        networkAgent.linkProperties, nri.mPid, nri.mUid));
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
                final NetworkCapabilities netCap =
                        networkCapabilitiesRestrictedForCallerPermissions(
                                networkAgent.networkCapabilities, nri.mPid, nri.mUid);
                putParcelable(
                        bundle,
                        maybeSanitizeLocationInfoForCaller(
                                netCap, nri.mUid, nri.request.getRequestorPackageName()));
                break;
            }
            case ConnectivityManager.CALLBACK_IP_CHANGED: {
                putParcelable(bundle, linkPropertiesRestrictedForCallerPermissions(
                        networkAgent.linkProperties, nri.mPid, nri.mUid));
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
        if (DBG) log("handleLingerComplete for " + oldNetwork.toShortString());

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

    private void makeDefault(@Nullable final NetworkAgentInfo newNetwork) {
        if (DBG) log("Switching to new default network: " + newNetwork);

        mDefaultNetworkNai = newNetwork;

        try {
            if (null != newNetwork) {
                mNMS.setDefaultNetId(newNetwork.network.netId);
            } else {
                mNMS.clearDefaultNetId();
            }
        } catch (Exception e) {
            loge("Exception setting default network :" + e);
        }

        notifyLockdownVpn(newNetwork);
        handleApplyDefaultProxy(null != newNetwork
                ? newNetwork.linkProperties.getHttpProxy() : null);
        updateTcpBufferSizes(null != newNetwork
                ? newNetwork.linkProperties.getTcpBufferSizes() : null);
        mDnsManager.setDefaultDnsSystemProperties(null != newNetwork
                ? newNetwork.linkProperties.getDnsServers() : Collections.EMPTY_LIST);
        notifyIfacesChangedForNetworkStats();
        // Fix up the NetworkCapabilities of any VPNs that don't specify underlying networks.
        updateAllVpnsCapabilities();
    }

    private void processListenRequests(@NonNull final NetworkAgentInfo nai) {
        // For consistency with previous behaviour, send onLost callbacks before onAvailable.
        processNewlyLostListenRequests(nai);
        notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_CAP_CHANGED);
        processNewlySatisfiedListenRequests(nai);
    }

    private void processNewlyLostListenRequests(@NonNull final NetworkAgentInfo nai) {
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            NetworkRequest nr = nri.request;
            if (!nr.isListen()) continue;
            if (nai.isSatisfyingRequest(nr.requestId) && !nai.satisfies(nr)) {
                nai.removeRequest(nri.request.requestId);
                callCallbackForRequest(nri, nai, ConnectivityManager.CALLBACK_LOST, 0);
            }
        }
    }

    private void processNewlySatisfiedListenRequests(@NonNull final NetworkAgentInfo nai) {
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            NetworkRequest nr = nri.request;
            if (!nr.isListen()) continue;
            if (nai.satisfies(nr) && !nai.isSatisfyingRequest(nr.requestId)) {
                nai.addRequest(nr);
                notifyNetworkAvailable(nai, nri);
            }
        }
    }

    // An accumulator class to gather the list of changes that result from a rematch.
    private static class NetworkReassignment {
        static class RequestReassignment {
            @NonNull public final NetworkRequestInfo mRequest;
            @Nullable public final NetworkAgentInfo mOldNetwork;
            @Nullable public final NetworkAgentInfo mNewNetwork;
            RequestReassignment(@NonNull final NetworkRequestInfo request,
                    @Nullable final NetworkAgentInfo oldNetwork,
                    @Nullable final NetworkAgentInfo newNetwork) {
                mRequest = request;
                mOldNetwork = oldNetwork;
                mNewNetwork = newNetwork;
            }

            public String toString() {
                return mRequest.request.requestId + " : "
                        + (null != mOldNetwork ? mOldNetwork.network.netId : "null")
                        + " → " + (null != mNewNetwork ? mNewNetwork.network.netId : "null");
            }
        }

        @NonNull private final ArrayList<RequestReassignment> mReassignments = new ArrayList<>();

        @NonNull Iterable<RequestReassignment> getRequestReassignments() {
            return mReassignments;
        }

        void addRequestReassignment(@NonNull final RequestReassignment reassignment) {
            if (!Build.IS_USER) {
                // The code is never supposed to add two reassignments of the same request. Make
                // sure this stays true, but without imposing this expensive check on all
                // reassignments on all user devices.
                for (final RequestReassignment existing : mReassignments) {
                    if (existing.mRequest.equals(reassignment.mRequest)) {
                        throw new IllegalStateException("Trying to reassign ["
                                + reassignment + "] but already have ["
                                + existing + "]");
                    }
                }
            }
            mReassignments.add(reassignment);
        }

        // Will return null if this reassignment does not change the network assigned to
        // the passed request.
        @Nullable
        private RequestReassignment getReassignment(@NonNull final NetworkRequestInfo nri) {
            for (final RequestReassignment event : getRequestReassignments()) {
                if (nri == event.mRequest) return event;
            }
            return null;
        }

        public String toString() {
            final StringJoiner sj = new StringJoiner(", " /* delimiter */,
                    "NetReassign [" /* prefix */, "]" /* suffix */);
            if (mReassignments.isEmpty()) return sj.add("no changes").toString();
            for (final RequestReassignment rr : getRequestReassignments()) {
                sj.add(rr.toString());
            }
            return sj.toString();
        }

        public String debugString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("NetworkReassignment :");
            if (mReassignments.isEmpty()) return sb.append(" no changes").toString();
            for (final RequestReassignment rr : getRequestReassignments()) {
                sb.append("\n  ").append(rr);
            }
            return sb.append("\n").toString();
        }
    }

    private void updateSatisfiersForRematchRequest(@NonNull final NetworkRequestInfo nri,
            @Nullable final NetworkAgentInfo previousSatisfier,
            @Nullable final NetworkAgentInfo newSatisfier,
            final long now) {
        if (newSatisfier != null) {
            if (VDBG) log("rematch for " + newSatisfier.toShortString());
            if (previousSatisfier != null) {
                if (VDBG || DDBG) {
                    log("   accepting network in place of " + previousSatisfier.toShortString());
                }
                previousSatisfier.removeRequest(nri.request.requestId);
                previousSatisfier.lingerRequest(nri.request, now, mLingerDelayMs);
            } else {
                if (VDBG || DDBG) log("   accepting network in place of null");
            }
            newSatisfier.unlingerRequest(nri.request);
            if (!newSatisfier.addRequest(nri.request)) {
                Slog.wtf(TAG, "BUG: " + newSatisfier.toShortString() + " already has "
                        + nri.request);
            }
        } else {
            if (DBG) {
                log("Network " + previousSatisfier.toShortString() + " stopped satisfying"
                        + " request " + nri.request.requestId);
            }
            previousSatisfier.removeRequest(nri.request.requestId);
        }
        nri.mSatisfier = newSatisfier;
    }

    @NonNull
    private NetworkReassignment computeNetworkReassignment() {
        ensureRunningOnConnectivityServiceThread();
        final NetworkReassignment changes = new NetworkReassignment();

        // Gather the list of all relevant agents and sort them by score.
        final ArrayList<NetworkAgentInfo> nais = new ArrayList<>();
        for (final NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
            if (!nai.everConnected) continue;
            nais.add(nai);
        }

        for (final NetworkRequestInfo nri : mNetworkRequests.values()) {
            if (nri.request.isListen()) continue;
            final NetworkAgentInfo bestNetwork = mNetworkRanker.getBestNetwork(nri.request, nais);
            if (bestNetwork != nri.mSatisfier) {
                // bestNetwork may be null if no network can satisfy this request.
                changes.addRequestReassignment(new NetworkReassignment.RequestReassignment(
                        nri, nri.mSatisfier, bestNetwork));
            }
        }
        return changes;
    }

    /**
     * Attempt to rematch all Networks with NetworkRequests.  This may result in Networks
     * being disconnected.
     */
    private void rematchAllNetworksAndRequests() {
        // TODO: This may be slow, and should be optimized.
        final long now = SystemClock.elapsedRealtime();
        final NetworkReassignment changes = computeNetworkReassignment();
        if (VDBG || DDBG) {
            log(changes.debugString());
        } else if (DBG) {
            log(changes.toString()); // Shorter form, only one line of log
        }
        applyNetworkReassignment(changes, now);
    }

    private void applyNetworkReassignment(@NonNull final NetworkReassignment changes,
            final long now) {
        final Collection<NetworkAgentInfo> nais = mNetworkAgentInfos.values();

        // Since most of the time there are only 0 or 1 background networks, it would probably
        // be more efficient to just use an ArrayList here. TODO : measure performance
        final ArraySet<NetworkAgentInfo> oldBgNetworks = new ArraySet<>();
        for (final NetworkAgentInfo nai : nais) {
            if (nai.isBackgroundNetwork()) oldBgNetworks.add(nai);
        }

        // First, update the lists of satisfied requests in the network agents. This is necessary
        // because some code later depends on this state to be correct, most prominently computing
        // the linger status.
        for (final NetworkReassignment.RequestReassignment event :
                changes.getRequestReassignments()) {
            updateSatisfiersForRematchRequest(event.mRequest, event.mOldNetwork,
                    event.mNewNetwork, now);
        }

        final NetworkAgentInfo oldDefaultNetwork = getDefaultNetwork();
        final NetworkRequestInfo defaultRequestInfo = mNetworkRequests.get(mDefaultRequest);
        final NetworkReassignment.RequestReassignment reassignment =
                changes.getReassignment(defaultRequestInfo);
        final NetworkAgentInfo newDefaultNetwork =
                null != reassignment ? reassignment.mNewNetwork : oldDefaultNetwork;

        if (oldDefaultNetwork != newDefaultNetwork) {
            if (oldDefaultNetwork != null) {
                mLingerMonitor.noteLingerDefaultNetwork(oldDefaultNetwork, newDefaultNetwork);
            }
            updateDataActivityTracking(newDefaultNetwork, oldDefaultNetwork);
            // Notify system services of the new default.
            makeDefault(newDefaultNetwork);
            // Log 0 -> X and Y -> X default network transitions, where X is the new default.
            mDeps.getMetricsLogger().defaultNetworkMetrics().logDefaultNetworkEvent(
                    now, newDefaultNetwork, oldDefaultNetwork);
            // Have a new default network, release the transition wakelock in
            scheduleReleaseNetworkTransitionWakelock();
        }

        // Notify requested networks are available after the default net is switched, but
        // before LegacyTypeTracker sends legacy broadcasts
        for (final NetworkReassignment.RequestReassignment event :
                changes.getRequestReassignments()) {
            // Tell NetworkProviders about the new score, so they can stop
            // trying to connect if they know they cannot match it.
            // TODO - this could get expensive if there are a lot of outstanding requests for this
            // network. Think of a way to reduce this. Push netid->request mapping to each factory?
            sendUpdatedScoreToFactories(event.mRequest.request, event.mNewNetwork);

            if (null != event.mNewNetwork) {
                notifyNetworkAvailable(event.mNewNetwork, event.mRequest);
            } else {
                callCallbackForRequest(event.mRequest, event.mOldNetwork,
                        ConnectivityManager.CALLBACK_LOST, 0);
            }
        }

        // Update the linger state before processing listen callbacks, because the background
        // computation depends on whether the network is lingering. Don't send the LOSING callbacks
        // just yet though, because they have to be sent after the listens are processed to keep
        // backward compatibility.
        final ArrayList<NetworkAgentInfo> lingeredNetworks = new ArrayList<>();
        for (final NetworkAgentInfo nai : nais) {
            // Rematching may have altered the linger state of some networks, so update all linger
            // timers. updateLingerState reads the state from the network agent and does nothing
            // if the state has not changed : the source of truth is controlled with
            // NetworkAgentInfo#lingerRequest and NetworkAgentInfo#unlingerRequest, which have been
            // called while rematching the individual networks above.
            if (updateLingerState(nai, now)) {
                lingeredNetworks.add(nai);
            }
        }

        for (final NetworkAgentInfo nai : nais) {
            if (!nai.everConnected) continue;
            final boolean oldBackground = oldBgNetworks.contains(nai);
            // Process listen requests and update capabilities if the background state has
            // changed for this network. For consistency with previous behavior, send onLost
            // callbacks before onAvailable.
            processNewlyLostListenRequests(nai);
            if (oldBackground != nai.isBackgroundNetwork()) {
                applyBackgroundChangeForRematch(nai);
            }
            processNewlySatisfiedListenRequests(nai);
        }

        for (final NetworkAgentInfo nai : lingeredNetworks) {
            notifyNetworkLosing(nai, now);
        }

        updateLegacyTypeTrackerAndVpnLockdownForRematch(oldDefaultNetwork, newDefaultNetwork, nais);

        // Tear down all unneeded networks.
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
                    if (updateLingerState(nai, now)) {
                        notifyNetworkLosing(nai, now);
                    }
                } else {
                    if (DBG) log("Reaping " + nai.toShortString());
                    teardownUnneededNetwork(nai);
                }
            }
        }
    }

    /**
     * Apply a change in background state resulting from rematching networks with requests.
     *
     * During rematch, a network may change background states by starting to satisfy or stopping
     * to satisfy a foreground request. Listens don't count for this. When a network changes
     * background states, its capabilities need to be updated and callbacks fired for the
     * capability change.
     *
     * @param nai The network that changed background states
     */
    private void applyBackgroundChangeForRematch(@NonNull final NetworkAgentInfo nai) {
        final NetworkCapabilities newNc = mixInCapabilities(nai, nai.networkCapabilities);
        if (Objects.equals(nai.networkCapabilities, newNc)) return;
        updateNetworkPermissions(nai, newNc);
        nai.getAndSetNetworkCapabilities(newNc);
        notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_CAP_CHANGED);
    }

    private void updateLegacyTypeTrackerAndVpnLockdownForRematch(
            @Nullable final NetworkAgentInfo oldDefaultNetwork,
            @Nullable final NetworkAgentInfo newDefaultNetwork,
            @NonNull final Collection<NetworkAgentInfo> nais) {
        if (oldDefaultNetwork != newDefaultNetwork) {
            // Maintain the illusion : since the legacy API only understands one network at a time,
            // if the default network changed, apps should see a disconnected broadcast for the
            // old default network before they see a connected broadcast for the new one.
            if (oldDefaultNetwork != null) {
                mLegacyTypeTracker.remove(oldDefaultNetwork.networkInfo.getType(),
                        oldDefaultNetwork, true);
            }
            if (newDefaultNetwork != null) {
                // The new default network can be newly null if and only if the old default
                // network doesn't satisfy the default request any more because it lost a
                // capability.
                mDefaultInetConditionPublished = newDefaultNetwork.lastValidated ? 100 : 0;
                mLegacyTypeTracker.add(newDefaultNetwork.networkInfo.getType(), newDefaultNetwork);
                // If the legacy VPN is connected, notifyLockdownVpn may end up sending a broadcast
                // to reflect the NetworkInfo of this new network. This broadcast has to be sent
                // after the disconnect broadcasts above, but before the broadcasts sent by the
                // legacy type tracker below.
                // TODO : refactor this, it's too complex
                notifyLockdownVpn(newDefaultNetwork);
            }
        }

        // Now that all the callbacks have been sent, send the legacy network broadcasts
        // as needed. This is necessary so that legacy requests correctly bind dns
        // requests to this network. The legacy users are listening for this broadcast
        // and will generally do a dns request so they can ensureRouteToHost and if
        // they do that before the callbacks happen they'll use the default network.
        //
        // TODO: Is there still a race here? The legacy broadcast will be sent after sending
        // callbacks, but if apps can receive the broadcast before the callback, they still might
        // have an inconsistent view of networking.
        //
        // This *does* introduce a race where if the user uses the new api
        // (notification callbacks) and then uses the old api (getNetworkInfo(type))
        // they may get old info. Reverse this after the old startUsing api is removed.
        // This is on top of the multiple intent sequencing referenced in the todo above.
        for (NetworkAgentInfo nai : nais) {
            if (nai.everConnected) {
                addNetworkToLegacyTypeTracker(nai);
            }
        }
    }

    private void addNetworkToLegacyTypeTracker(@NonNull final NetworkAgentInfo nai) {
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            if (nr.legacyType != TYPE_NONE && nr.isRequest()) {
                // legacy type tracker filters out repeat adds
                mLegacyTypeTracker.add(nr.legacyType, nai);
            }
        }

        // A VPN generally won't get added to the legacy tracker in the "for (nri)" loop above,
        // because usually there are no NetworkRequests it satisfies (e.g., mDefaultRequest
        // wants the NOT_VPN capability, so it will never be satisfied by a VPN). So, add the
        // newNetwork to the tracker explicitly (it's a no-op if it has already been added).
        if (nai.isVPN()) {
            mLegacyTypeTracker.add(TYPE_VPN, nai);
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

    @NonNull
    private NetworkInfo mixInInfo(@NonNull final NetworkAgentInfo nai, @NonNull NetworkInfo info) {
        final NetworkInfo newInfo = new NetworkInfo(info);
        // The suspended and roaming bits are managed in NetworkCapabilities.
        final boolean suspended =
                !nai.networkCapabilities.hasCapability(NET_CAPABILITY_NOT_SUSPENDED);
        if (suspended && info.getDetailedState() == NetworkInfo.DetailedState.CONNECTED) {
            // Only override the state with SUSPENDED if the network is currently in CONNECTED
            // state. This is because the network could have been suspended before connecting,
            // or it could be disconnecting while being suspended, and in both these cases
            // the state should not be overridden. Note that the only detailed state that
            // maps to State.CONNECTED is DetailedState.CONNECTED, so there is also no need to
            // worry about multiple different substates of CONNECTED.
            newInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED, info.getReason(),
                    info.getExtraInfo());
        } else if (!suspended && info.getDetailedState() == NetworkInfo.DetailedState.SUSPENDED) {
            // SUSPENDED state is currently only overridden from CONNECTED state. In the case the
            // network agent is created, then goes to suspended, then goes out of suspended without
            // ever setting connected. Check if network agent is ever connected to update the state.
            newInfo.setDetailedState(nai.everConnected
                    ? NetworkInfo.DetailedState.CONNECTED
                    : NetworkInfo.DetailedState.CONNECTING,
                    info.getReason(),
                    info.getExtraInfo());
        }
        newInfo.setRoaming(!nai.networkCapabilities.hasCapability(NET_CAPABILITY_NOT_ROAMING));
        return newInfo;
    }

    private void updateNetworkInfo(NetworkAgentInfo networkAgent, NetworkInfo info) {
        final NetworkInfo newInfo = mixInInfo(networkAgent, info);

        final NetworkInfo.State state = newInfo.getState();
        NetworkInfo oldInfo = null;
        synchronized (networkAgent) {
            oldInfo = networkAgent.networkInfo;
            networkAgent.networkInfo = newInfo;
        }
        notifyLockdownVpn(networkAgent);

        if (DBG) {
            log(networkAgent.toShortString() + " EVENT_NETWORK_INFO_CHANGED, going from "
                    + oldInfo.getState() + " to " + state);
        }

        if (!networkAgent.created
                && (state == NetworkInfo.State.CONNECTED
                || (state == NetworkInfo.State.CONNECTING && networkAgent.isVPN()))) {

            // A network that has just connected has zero requests and is thus a foreground network.
            networkAgent.networkCapabilities.addCapability(NET_CAPABILITY_FOREGROUND);

            if (!createNativeNetwork(networkAgent)) return;
            networkAgent.created = true;
        }

        if (!networkAgent.everConnected && state == NetworkInfo.State.CONNECTED) {
            networkAgent.everConnected = true;

            if (networkAgent.linkProperties == null) {
                Slog.wtf(TAG, networkAgent.toShortString() + " connected with null LinkProperties");
            }

            // NetworkCapabilities need to be set before sending the private DNS config to
            // NetworkMonitor, otherwise NetworkMonitor cannot determine if validation is required.
            networkAgent.getAndSetNetworkCapabilities(networkAgent.networkCapabilities);

            handlePerNetworkPrivateDnsConfig(networkAgent, mDnsManager.getPrivateDnsConfig());
            updateLinkProperties(networkAgent, new LinkProperties(networkAgent.linkProperties),
                    null);

            // Until parceled LinkProperties are sent directly to NetworkMonitor, the connect
            // command must be sent after updating LinkProperties to maximize chances of
            // NetworkMonitor seeing the correct LinkProperties when starting.
            // TODO: pass LinkProperties to the NetworkMonitor in the notifyNetworkConnected call.
            if (networkAgent.networkAgentConfig.acceptPartialConnectivity) {
                networkAgent.networkMonitor().setAcceptPartialConnectivity();
            }
            networkAgent.networkMonitor().notifyNetworkConnected(
                    networkAgent.linkProperties, networkAgent.networkCapabilities);
            scheduleUnvalidatedPrompt(networkAgent);

            // Whether a particular NetworkRequest listen should cause signal strength thresholds to
            // be communicated to a particular NetworkAgent depends only on the network's immutable,
            // capabilities, so it only needs to be done once on initial connect, not every time the
            // network's capabilities change. Note that we do this before rematching the network,
            // so we could decide to tear it down immediately afterwards. That's fine though - on
            // disconnection NetworkAgents should stop any signal strength monitoring they have been
            // doing.
            updateSignalStrengthThresholds(networkAgent, "CONNECT", null);

            if (networkAgent.isVPN()) {
                updateAllVpnsCapabilities();
            }

            // Consider network even though it is not yet validated.
            rematchAllNetworksAndRequests();

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
        } else if (networkAgent.created && (oldInfo.getState() == NetworkInfo.State.SUSPENDED ||
                state == NetworkInfo.State.SUSPENDED)) {
            mLegacyTypeTracker.update(networkAgent);
        }
    }

    private void updateNetworkScore(@NonNull final NetworkAgentInfo nai, final int score) {
        if (VDBG || DDBG) log("updateNetworkScore for " + nai.toShortString() + " to " + score);
        nai.setScore(score);
        rematchAllNetworksAndRequests();
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

    // Notify the requests on this NAI that the network is now lingered.
    private void notifyNetworkLosing(@NonNull final NetworkAgentInfo nai, final long now) {
        final int lingerTime = (int) (nai.getLingerExpiry() - now);
        notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOSING, lingerTime);
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
                continue;
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

    @VisibleForTesting
    protected void sendLegacyNetworkBroadcast(NetworkAgentInfo nai, DetailedState state, int type) {
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
            log("notifyType " + notification + " for " + networkAgent.toShortString());
        }
        for (int i = 0; i < networkAgent.numNetworkRequests(); i++) {
            NetworkRequest nr = networkAgent.requestAt(i);
            NetworkRequestInfo nri = mNetworkRequests.get(nr);
            if (VDBG) log(" sending notification for " + nr);
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
        ensureRunningOnConnectivityServiceThread();
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
     * active iface's tracked properties has changed.
     */
    private void notifyIfacesChangedForNetworkStats() {
        ensureRunningOnConnectivityServiceThread();
        String activeIface = null;
        LinkProperties activeLinkProperties = getActiveLinkProperties();
        if (activeLinkProperties != null) {
            activeIface = activeLinkProperties.getInterfaceName();
        }

        final VpnInfo[] vpnInfos = getAllVpnInfo();
        try {
            mStatsService.forceUpdateIfaces(
                    getDefaultNetworks(), getAllNetworkState(), activeIface, vpnInfos);
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
            mHandler.post(() -> {
                // Update VPN's capabilities based on updated underlying network set.
                updateAllVpnsCapabilities();
                notifyIfacesChangedForNetworkStats();
            });
        }
        return success;
    }

    @Override
    public String getCaptivePortalServerUrl() {
        enforceNetworkStackOrSettingsPermission();
        String settingUrl = mContext.getResources().getString(
                R.string.config_networkCaptivePortalServerUrl);

        if (!TextUtils.isEmpty(settingUrl)) {
            return settingUrl;
        }

        settingUrl = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.CAPTIVE_PORTAL_HTTP_URL);
        if (!TextUtils.isEmpty(settingUrl)) {
            return settingUrl;
        }

        return DEFAULT_CAPTIVE_PORTAL_HTTP_URL;
    }

    @Override
    public void startNattKeepalive(Network network, int intervalSeconds,
            ISocketKeepaliveCallback cb, String srcAddr, int srcPort, String dstAddr) {
        enforceKeepalivePermission();
        mKeepaliveTracker.startNattKeepalive(
                getNetworkAgentInfoForNetwork(network), null /* fd */,
                intervalSeconds, cb,
                srcAddr, srcPort, dstAddr, NattSocketKeepalive.NATT_PORT);
    }

    @Override
    public void startNattKeepaliveWithFd(Network network, FileDescriptor fd, int resourceId,
            int intervalSeconds, ISocketKeepaliveCallback cb, String srcAddr,
            String dstAddr) {
        mKeepaliveTracker.startNattKeepalive(
                getNetworkAgentInfoForNetwork(network), fd, resourceId,
                intervalSeconds, cb,
                srcAddr, dstAddr, NattSocketKeepalive.NATT_PORT);
    }

    @Override
    public void startTcpKeepalive(Network network, FileDescriptor fd, int intervalSeconds,
            ISocketKeepaliveCallback cb) {
        enforceKeepalivePermission();
        mKeepaliveTracker.startTcpKeepalive(
                getNetworkAgentInfoForNetwork(network), fd, intervalSeconds, cb);
    }

    @Override
    public void stopKeepalive(Network network, int slot) {
        mHandler.sendMessage(mHandler.obtainMessage(
                NetworkAgent.CMD_STOP_SOCKET_KEEPALIVE, slot, SocketKeepalive.SUCCESS, network));
    }

    @Override
    public void factoryReset() {
        enforceSettingsPermission();

        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_NETWORK_RESET)) {
            return;
        }

        final int userId = UserHandle.getCallingUserId();

        Binder.withCleanCallingIdentity(() -> {
            final IpMemoryStore ipMemoryStore = IpMemoryStore.getMemoryStore(mContext);
            ipMemoryStore.factoryReset();
        });

        // Turn airplane mode off
        setAirplaneMode(false);

        if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_VPN)) {
            // Remove always-on package
            synchronized (mVpns) {
                final String alwaysOnPackage = getAlwaysOnVpnPackage(userId);
                if (alwaysOnPackage != null) {
                    setAlwaysOnVpnPackage(userId, null, false, null);
                    setVpnPackageAuthorization(alwaysOnPackage, userId, VpnManager.TYPE_VPN_NONE);
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
                        setVpnPackageAuthorization(
                                vpnConfig.user, userId, VpnManager.TYPE_VPN_NONE);

                        prepareVpn(null, VpnConfig.LEGACY_VPN, userId);
                    }
                }
            }
        }

        // restore private DNS settings to default mode (opportunistic)
        if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)) {
            Settings.Global.putString(mContext.getContentResolver(),
                    Settings.Global.PRIVATE_DNS_MODE, PRIVATE_DNS_MODE_OPPORTUNISTIC);
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
    public void onShellCommand(@NonNull FileDescriptor in, @NonNull FileDescriptor out,
            FileDescriptor err, @NonNull String[] args, ShellCallback callback,
            @NonNull ResultReceiver resultReceiver) {
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
        return getVpnIfOwner(Binder.getCallingUid());
    }

    @GuardedBy("mVpns")
    private Vpn getVpnIfOwner(int uid) {
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

        // Only VpnService based VPNs should be able to get this information.
        if (vpn != null && vpn.getActiveAppVpnType() != VpnManager.TYPE_VPN_SERVICE) {
            throw new SecurityException(
                    "getConnectionOwnerUid() not allowed for non-VpnService VPNs");
        }

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

    /**
     * Returns a IBinder to a TestNetworkService. Will be lazily created as needed.
     *
     * <p>The TestNetworkService must be run in the system server due to TUN creation.
     */
    @Override
    public IBinder startOrGetTestNetworkService() {
        synchronized (mTNSLock) {
            TestNetworkService.enforceTestNetworkPermissions(mContext);

            if (mTNS == null) {
                mTNS = new TestNetworkService(mContext, mNMS);
            }

            return mTNS;
        }
    }

    /**
     * Handler used for managing all Connectivity Diagnostics related functions.
     *
     * @see android.net.ConnectivityDiagnosticsManager
     *
     * TODO(b/147816404): Explore moving ConnectivityDiagnosticsHandler to a separate file
     */
    @VisibleForTesting
    class ConnectivityDiagnosticsHandler extends Handler {
        private final String mTag = ConnectivityDiagnosticsHandler.class.getSimpleName();

        /**
         * Used to handle ConnectivityDiagnosticsCallback registration events from {@link
         * android.net.ConnectivityDiagnosticsManager}.
         * obj = ConnectivityDiagnosticsCallbackInfo with IConnectivityDiagnosticsCallback and
         * NetworkRequestInfo to be registered
         */
        private static final int EVENT_REGISTER_CONNECTIVITY_DIAGNOSTICS_CALLBACK = 1;

        /**
         * Used to handle ConnectivityDiagnosticsCallback unregister events from {@link
         * android.net.ConnectivityDiagnosticsManager}.
         * obj = the IConnectivityDiagnosticsCallback to be unregistered
         * arg1 = the uid of the caller
         */
        private static final int EVENT_UNREGISTER_CONNECTIVITY_DIAGNOSTICS_CALLBACK = 2;

        /**
         * Event for {@link NetworkStateTrackerHandler} to trigger ConnectivityReport callbacks
         * after processing {@link #EVENT_NETWORK_TESTED} events.
         * obj = {@link ConnectivityReportEvent} representing ConnectivityReport info reported from
         * NetworkMonitor.
         * data = PersistableBundle of extras passed from NetworkMonitor.
         *
         * <p>See {@link ConnectivityService#EVENT_NETWORK_TESTED}.
         */
        private static final int EVENT_NETWORK_TESTED = ConnectivityService.EVENT_NETWORK_TESTED;

        /**
         * Event for NetworkMonitor to inform ConnectivityService that a potential data stall has
         * been detected on the network.
         * obj = Long the timestamp (in millis) for when the suspected data stall was detected.
         * arg1 = {@link DataStallReport#DetectionMethod} indicating the detection method.
         * arg2 = NetID.
         * data = PersistableBundle of extras passed from NetworkMonitor.
         */
        private static final int EVENT_DATA_STALL_SUSPECTED = 4;

        /**
         * Event for ConnectivityDiagnosticsHandler to handle network connectivity being reported to
         * the platform. This event will invoke {@link
         * IConnectivityDiagnosticsCallback#onNetworkConnectivityReported} for permissioned
         * callbacks.
         * obj = Network that was reported on
         * arg1 = boolint for the quality reported
         */
        private static final int EVENT_NETWORK_CONNECTIVITY_REPORTED = 5;

        private ConnectivityDiagnosticsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_REGISTER_CONNECTIVITY_DIAGNOSTICS_CALLBACK: {
                    handleRegisterConnectivityDiagnosticsCallback(
                            (ConnectivityDiagnosticsCallbackInfo) msg.obj);
                    break;
                }
                case EVENT_UNREGISTER_CONNECTIVITY_DIAGNOSTICS_CALLBACK: {
                    handleUnregisterConnectivityDiagnosticsCallback(
                            (IConnectivityDiagnosticsCallback) msg.obj, msg.arg1);
                    break;
                }
                case EVENT_NETWORK_TESTED: {
                    final ConnectivityReportEvent reportEvent =
                            (ConnectivityReportEvent) msg.obj;

                    // This is safe because {@link
                    // NetworkMonitorCallbacks#notifyNetworkTestedWithExtras} receives a
                    // PersistableBundle and converts it to the Bundle in the incoming Message. If
                    // {@link NetworkMonitorCallbacks#notifyNetworkTested} is called, msg.data will
                    // not be set. This is also safe, as msg.getData() will return an empty Bundle.
                    final PersistableBundle extras = new PersistableBundle(msg.getData());
                    handleNetworkTestedWithExtras(reportEvent, extras);
                    break;
                }
                case EVENT_DATA_STALL_SUSPECTED: {
                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(msg.arg2);
                    if (nai == null) break;

                    // This is safe because NetworkMonitorCallbacks#notifyDataStallSuspected
                    // receives a PersistableBundle and converts it to the Bundle in the incoming
                    // Message.
                    final PersistableBundle extras = new PersistableBundle(msg.getData());
                    handleDataStallSuspected(nai, (long) msg.obj, msg.arg1, extras);
                    break;
                }
                case EVENT_NETWORK_CONNECTIVITY_REPORTED: {
                    handleNetworkConnectivityReported((NetworkAgentInfo) msg.obj, toBool(msg.arg1));
                    break;
                }
                default: {
                    Log.e(mTag, "Unrecognized event in ConnectivityDiagnostics: " + msg.what);
                }
            }
        }
    }

    /** Class used for cleaning up IConnectivityDiagnosticsCallback instances after their death. */
    @VisibleForTesting
    class ConnectivityDiagnosticsCallbackInfo implements Binder.DeathRecipient {
        @NonNull private final IConnectivityDiagnosticsCallback mCb;
        @NonNull private final NetworkRequestInfo mRequestInfo;
        @NonNull private final String mCallingPackageName;

        @VisibleForTesting
        ConnectivityDiagnosticsCallbackInfo(
                @NonNull IConnectivityDiagnosticsCallback cb,
                @NonNull NetworkRequestInfo nri,
                @NonNull String callingPackageName) {
            mCb = cb;
            mRequestInfo = nri;
            mCallingPackageName = callingPackageName;
        }

        @Override
        public void binderDied() {
            log("ConnectivityDiagnosticsCallback IBinder died.");
            unregisterConnectivityDiagnosticsCallback(mCb);
        }
    }

    /**
     * Class used for sending information from {@link
     * NetworkMonitorCallbacks#notifyNetworkTestedWithExtras} to the handler for processing it.
     */
    private static class NetworkTestedResults {
        private final int mNetId;
        private final int mTestResult;
        private final long mTimestampMillis;
        @Nullable private final String mRedirectUrl;

        private NetworkTestedResults(
                int netId, int testResult, long timestampMillis, @Nullable String redirectUrl) {
            mNetId = netId;
            mTestResult = testResult;
            mTimestampMillis = timestampMillis;
            mRedirectUrl = redirectUrl;
        }
    }

    /**
     * Class used for sending information from {@link NetworkStateTrackerHandler} to {@link
     * ConnectivityDiagnosticsHandler}.
     */
    private static class ConnectivityReportEvent {
        private final long mTimestampMillis;
        @NonNull private final NetworkAgentInfo mNai;

        private ConnectivityReportEvent(long timestampMillis, @NonNull NetworkAgentInfo nai) {
            mTimestampMillis = timestampMillis;
            mNai = nai;
        }
    }

    private void handleRegisterConnectivityDiagnosticsCallback(
            @NonNull ConnectivityDiagnosticsCallbackInfo cbInfo) {
        ensureRunningOnConnectivityServiceThread();

        final IConnectivityDiagnosticsCallback cb = cbInfo.mCb;
        final IBinder iCb = cb.asBinder();
        final NetworkRequestInfo nri = cbInfo.mRequestInfo;

        // This means that the client registered the same callback multiple times. Do
        // not override the previous entry, and exit silently.
        if (mConnectivityDiagnosticsCallbacks.containsKey(iCb)) {
            if (VDBG) log("Diagnostics callback is already registered");

            // Decrement the reference count for this NetworkRequestInfo. The reference count is
            // incremented when the NetworkRequestInfo is created as part of
            // enforceRequestCountLimit().
            decrementNetworkRequestPerUidCount(nri);
            return;
        }

        mConnectivityDiagnosticsCallbacks.put(iCb, cbInfo);

        try {
            iCb.linkToDeath(cbInfo, 0);
        } catch (RemoteException e) {
            cbInfo.binderDied();
            return;
        }

        // Once registered, provide ConnectivityReports for matching Networks
        final List<NetworkAgentInfo> matchingNetworks = new ArrayList<>();
        synchronized (mNetworkForNetId) {
            for (int i = 0; i < mNetworkForNetId.size(); i++) {
                final NetworkAgentInfo nai = mNetworkForNetId.valueAt(i);
                if (nai.satisfies(nri.request)) {
                    matchingNetworks.add(nai);
                }
            }
        }
        for (final NetworkAgentInfo nai : matchingNetworks) {
            final ConnectivityReport report = nai.getConnectivityReport();
            if (report == null) {
                continue;
            }
            if (!checkConnectivityDiagnosticsPermissions(
                    nri.mPid, nri.mUid, nai, cbInfo.mCallingPackageName)) {
                continue;
            }

            try {
                cb.onConnectivityReportAvailable(report);
            } catch (RemoteException e) {
                // Exception while sending the ConnectivityReport. Move on to the next network.
            }
        }
    }

    private void handleUnregisterConnectivityDiagnosticsCallback(
            @NonNull IConnectivityDiagnosticsCallback cb, int uid) {
        ensureRunningOnConnectivityServiceThread();
        final IBinder iCb = cb.asBinder();

        final ConnectivityDiagnosticsCallbackInfo cbInfo =
                mConnectivityDiagnosticsCallbacks.remove(iCb);
        if (cbInfo == null) {
            if (VDBG) log("Removing diagnostics callback that is not currently registered");
            return;
        }

        final NetworkRequestInfo nri = cbInfo.mRequestInfo;

        if (uid != nri.mUid) {
            if (VDBG) loge("Different uid than registrant attempting to unregister cb");
            return;
        }

        // Decrement the reference count for this NetworkRequestInfo. The reference count is
        // incremented when the NetworkRequestInfo is created as part of
        // enforceRequestCountLimit().
        decrementNetworkRequestPerUidCount(nri);

        iCb.unlinkToDeath(cbInfo, 0);
    }

    private void handleNetworkTestedWithExtras(
            @NonNull ConnectivityReportEvent reportEvent, @NonNull PersistableBundle extras) {
        final NetworkAgentInfo nai = reportEvent.mNai;
        final NetworkCapabilities networkCapabilities =
                getNetworkCapabilitiesWithoutUids(nai.networkCapabilities);
        final ConnectivityReport report =
                new ConnectivityReport(
                        reportEvent.mNai.network,
                        reportEvent.mTimestampMillis,
                        nai.linkProperties,
                        networkCapabilities,
                        extras);
        nai.setConnectivityReport(report);
        final List<IConnectivityDiagnosticsCallback> results =
                getMatchingPermissionedCallbacks(nai);
        for (final IConnectivityDiagnosticsCallback cb : results) {
            try {
                cb.onConnectivityReportAvailable(report);
            } catch (RemoteException ex) {
                loge("Error invoking onConnectivityReport", ex);
            }
        }
    }

    private void handleDataStallSuspected(
            @NonNull NetworkAgentInfo nai, long timestampMillis, int detectionMethod,
            @NonNull PersistableBundle extras) {
        final NetworkCapabilities networkCapabilities =
                getNetworkCapabilitiesWithoutUids(nai.networkCapabilities);
        final DataStallReport report =
                new DataStallReport(
                        nai.network,
                        timestampMillis,
                        detectionMethod,
                        nai.linkProperties,
                        networkCapabilities,
                        extras);
        final List<IConnectivityDiagnosticsCallback> results =
                getMatchingPermissionedCallbacks(nai);
        for (final IConnectivityDiagnosticsCallback cb : results) {
            try {
                cb.onDataStallSuspected(report);
            } catch (RemoteException ex) {
                loge("Error invoking onDataStallSuspected", ex);
            }
        }
    }

    private void handleNetworkConnectivityReported(
            @NonNull NetworkAgentInfo nai, boolean connectivity) {
        final List<IConnectivityDiagnosticsCallback> results =
                getMatchingPermissionedCallbacks(nai);
        for (final IConnectivityDiagnosticsCallback cb : results) {
            try {
                cb.onNetworkConnectivityReported(nai.network, connectivity);
            } catch (RemoteException ex) {
                loge("Error invoking onNetworkConnectivityReported", ex);
            }
        }
    }

    private NetworkCapabilities getNetworkCapabilitiesWithoutUids(@NonNull NetworkCapabilities nc) {
        final NetworkCapabilities sanitized = new NetworkCapabilities(nc);
        sanitized.setUids(null);
        sanitized.setAdministratorUids(new int[0]);
        sanitized.setOwnerUid(Process.INVALID_UID);
        return sanitized;
    }

    private List<IConnectivityDiagnosticsCallback> getMatchingPermissionedCallbacks(
            @NonNull NetworkAgentInfo nai) {
        final List<IConnectivityDiagnosticsCallback> results = new ArrayList<>();
        for (Entry<IBinder, ConnectivityDiagnosticsCallbackInfo> entry :
                mConnectivityDiagnosticsCallbacks.entrySet()) {
            final ConnectivityDiagnosticsCallbackInfo cbInfo = entry.getValue();
            final NetworkRequestInfo nri = cbInfo.mRequestInfo;
            if (nai.satisfies(nri.request)) {
                if (checkConnectivityDiagnosticsPermissions(
                        nri.mPid, nri.mUid, nai, cbInfo.mCallingPackageName)) {
                    results.add(entry.getValue().mCb);
                }
            }
        }
        return results;
    }

    @VisibleForTesting
    boolean checkConnectivityDiagnosticsPermissions(
            int callbackPid, int callbackUid, NetworkAgentInfo nai, String callbackPackageName) {
        if (checkNetworkStackPermission(callbackPid, callbackUid)) {
            return true;
        }

        // LocationPermissionChecker#checkLocationPermission can throw SecurityException if the uid
        // and package name don't match. Throwing on the CS thread is not acceptable, so wrap the
        // call in a try-catch.
        try {
            if (!mLocationPermissionChecker.checkLocationPermission(
                    callbackPackageName, null /* featureId */, callbackUid, null /* message */)) {
                return false;
            }
        } catch (SecurityException e) {
            return false;
        }

        final Network[] underlyingNetworks;
        synchronized (mVpns) {
            final Vpn vpn = getVpnIfOwner(callbackUid);
            underlyingNetworks = (vpn == null) ? null : vpn.getUnderlyingNetworks();
        }
        if (underlyingNetworks != null) {
            if (Arrays.asList(underlyingNetworks).contains(nai.network)) return true;
        }

        // Administrator UIDs also contains the Owner UID
        final int[] administratorUids = nai.networkCapabilities.getAdministratorUids();
        return ArrayUtils.contains(administratorUids, callbackUid);
    }

    @Override
    public void registerConnectivityDiagnosticsCallback(
            @NonNull IConnectivityDiagnosticsCallback callback,
            @NonNull NetworkRequest request,
            @NonNull String callingPackageName) {
        if (request.legacyType != TYPE_NONE) {
            throw new IllegalArgumentException("ConnectivityManager.TYPE_* are deprecated."
                    + " Please use NetworkCapabilities instead.");
        }
        final int callingUid = Binder.getCallingUid();
        mAppOpsManager.checkPackage(callingUid, callingPackageName);

        // This NetworkCapabilities is only used for matching to Networks. Clear out its owner uid
        // and administrator uids to be safe.
        final NetworkCapabilities nc = new NetworkCapabilities(request.networkCapabilities);
        restrictRequestUidsForCallerAndSetRequestorInfo(nc, callingUid, callingPackageName);

        final NetworkRequest requestWithId =
                new NetworkRequest(
                        nc, TYPE_NONE, nextNetworkRequestId(), NetworkRequest.Type.LISTEN);

        // NetworkRequestInfos created here count towards MAX_NETWORK_REQUESTS_PER_UID limit.
        //
        // nri is not bound to the death of callback. Instead, callback.bindToDeath() is set in
        // handleRegisterConnectivityDiagnosticsCallback(). nri will be cleaned up as part of the
        // callback's binder death.
        final NetworkRequestInfo nri = new NetworkRequestInfo(requestWithId);
        final ConnectivityDiagnosticsCallbackInfo cbInfo =
                new ConnectivityDiagnosticsCallbackInfo(callback, nri, callingPackageName);

        mConnectivityDiagnosticsHandler.sendMessage(
                mConnectivityDiagnosticsHandler.obtainMessage(
                        ConnectivityDiagnosticsHandler
                                .EVENT_REGISTER_CONNECTIVITY_DIAGNOSTICS_CALLBACK,
                        cbInfo));
    }

    @Override
    public void unregisterConnectivityDiagnosticsCallback(
            @NonNull IConnectivityDiagnosticsCallback callback) {
        mConnectivityDiagnosticsHandler.sendMessage(
                mConnectivityDiagnosticsHandler.obtainMessage(
                        ConnectivityDiagnosticsHandler
                                .EVENT_UNREGISTER_CONNECTIVITY_DIAGNOSTICS_CALLBACK,
                        Binder.getCallingUid(),
                        0,
                        callback));
    }
}
