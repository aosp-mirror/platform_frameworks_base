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
import static android.net.ConnectivityDiagnosticsManager.ConnectivityReport.KEY_NETWORK_PROBES_ATTEMPTED_BITMASK;
import static android.net.ConnectivityDiagnosticsManager.ConnectivityReport.KEY_NETWORK_PROBES_SUCCEEDED_BITMASK;
import static android.net.ConnectivityDiagnosticsManager.ConnectivityReport.KEY_NETWORK_VALIDATION_RESULT;
import static android.net.ConnectivityDiagnosticsManager.DataStallReport.DETECTION_METHOD_DNS_EVENTS;
import static android.net.ConnectivityDiagnosticsManager.DataStallReport.DETECTION_METHOD_TCP_METRICS;
import static android.net.ConnectivityDiagnosticsManager.DataStallReport.KEY_DNS_CONSECUTIVE_TIMEOUTS;
import static android.net.ConnectivityDiagnosticsManager.DataStallReport.KEY_TCP_METRICS_COLLECTION_PERIOD_MILLIS;
import static android.net.ConnectivityDiagnosticsManager.DataStallReport.KEY_TCP_PACKET_FAIL_RATE;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
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
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN;
import static android.net.NetworkCapabilities.NET_CAPABILITY_OEM_PAID;
import static android.net.NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE;
import static android.net.NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY;
import static android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;
import static android.net.NetworkCapabilities.TRANSPORT_VPN;
import static android.net.NetworkPolicyManager.RULE_NONE;
import static android.net.NetworkPolicyManager.uidRulesToString;
import static android.net.shared.NetworkMonitorUtils.isPrivateDnsValidationRequired;
import static android.os.Process.INVALID_UID;
import static android.os.Process.VPN_UID;
import static android.system.OsConstants.IPPROTO_TCP;
import static android.system.OsConstants.IPPROTO_UDP;

import static java.util.Map.Entry;

import android.Manifest;
import android.annotation.BoolRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.usage.NetworkStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.CaptivePortal;
import android.net.CaptivePortalData;
import android.net.ConnectionInfo;
import android.net.ConnectivityDiagnosticsManager.ConnectivityReport;
import android.net.ConnectivityDiagnosticsManager.DataStallReport;
import android.net.ConnectivityManager;
import android.net.DataStallReportParcelable;
import android.net.DnsResolverServiceManager;
import android.net.ICaptivePortal;
import android.net.IConnectivityDiagnosticsCallback;
import android.net.IConnectivityManager;
import android.net.IDnsResolver;
import android.net.INetd;
import android.net.INetworkActivityListener;
import android.net.INetworkMonitor;
import android.net.INetworkMonitorCallbacks;
import android.net.INetworkPolicyListener;
import android.net.IOnSetOemNetworkPreferenceListener;
import android.net.IQosCallback;
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
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.NetworkStack;
import android.net.NetworkStackClient;
import android.net.NetworkState;
import android.net.NetworkStateSnapshot;
import android.net.NetworkTestResultParcelable;
import android.net.NetworkUtils;
import android.net.NetworkWatchlistManager;
import android.net.OemNetworkPreferences;
import android.net.PrivateDnsConfigParcel;
import android.net.ProxyInfo;
import android.net.QosCallbackException;
import android.net.QosFilter;
import android.net.QosSocketFilter;
import android.net.QosSocketInfo;
import android.net.RouteInfo;
import android.net.RouteInfoParcel;
import android.net.SocketKeepalive;
import android.net.TetheringManager;
import android.net.TransportInfo;
import android.net.UidRange;
import android.net.UidRangeParcel;
import android.net.UnderlyingNetworkInfo;
import android.net.Uri;
import android.net.VpnManager;
import android.net.VpnTransportInfo;
import android.net.metrics.IpConnectivityLog;
import android.net.metrics.NetworkEvent;
import android.net.netlink.InetDiagMessage;
import android.net.resolv.aidl.DnsHealthEventParcel;
import android.net.resolv.aidl.IDnsResolverUnsolicitedEventListener;
import android.net.resolv.aidl.Nat64PrefixEventParcel;
import android.net.resolv.aidl.PrivateDnsValidationEventParcel;
import android.net.shared.PrivateDnsConfig;
import android.net.util.MultinetworkPolicyTracker;
import android.net.util.NetdService;
import android.os.BatteryStatsManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.connectivity.aidl.INetworkAgent;
import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.BitUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.LocationPermissionChecker;
import com.android.internal.util.MessageUtils;
import com.android.modules.utils.BasicShellCommandHandler;
import com.android.net.module.util.BaseNetdUnsolicitedEventListener;
import com.android.net.module.util.CollectionUtils;
import com.android.net.module.util.LinkPropertiesUtils.CompareOrUpdateResult;
import com.android.net.module.util.LinkPropertiesUtils.CompareResult;
import com.android.net.module.util.PermissionUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.connectivity.AutodestructReference;
import com.android.server.connectivity.DataConnectionStats;
import com.android.server.connectivity.DnsManager;
import com.android.server.connectivity.DnsManager.PrivateDnsValidationUpdate;
import com.android.server.connectivity.KeepaliveTracker;
import com.android.server.connectivity.LingerMonitor;
import com.android.server.connectivity.MockableSystemProperties;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkDiagnostics;
import com.android.server.connectivity.NetworkNotificationManager;
import com.android.server.connectivity.NetworkNotificationManager.NotificationType;
import com.android.server.connectivity.NetworkRanker;
import com.android.server.connectivity.PermissionMonitor;
import com.android.server.connectivity.ProxyTracker;
import com.android.server.connectivity.QosCallbackTracker;
import com.android.server.net.NetworkPolicyManagerInternal;
import com.android.server.utils.PriorityDump;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

    // Default to 30s linger time-out, and 5s for nascent network. Modifiable only for testing.
    private static final String LINGER_DELAY_PROPERTY = "persist.netmon.linger";
    private static final int DEFAULT_LINGER_DELAY_MS = 30_000;
    private static final int DEFAULT_NASCENT_DELAY_MS = 5_000;

    // The maximum number of network request allowed per uid before an exception is thrown.
    private static final int MAX_NETWORK_REQUESTS_PER_UID = 100;

    @VisibleForTesting
    protected int mLingerDelayMs;  // Can't be final, or test subclass constructors can't change it.
    @VisibleForTesting
    protected int mNascentDelayMs;

    // How long to delay to removal of a pending intent based request.
    // See Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS
    private final int mReleasePendingIntentDelayMs;

    private MockableSystemProperties mSystemProperties;

    @VisibleForTesting
    protected final PermissionMonitor mPermissionMonitor;

    private final PerUidCounter mNetworkRequestCounter;

    private volatile boolean mLockdownEnabled;

    /**
     * Stale copy of uid rules provided by NPMS. As long as they are accessed only in internal
     * handler thread, they don't need a lock.
     */
    private SparseIntArray mUidRules = new SparseIntArray();
    /** Flag indicating if background data is restricted. */
    private boolean mRestrictBackground;

    private final Context mContext;
    // The Context is created for UserHandle.ALL.
    private final Context mUserAllContext;
    private final Dependencies mDeps;
    // 0 is full bad, 100 is full good
    private int mDefaultInetConditionPublished = 0;

    @VisibleForTesting
    protected IDnsResolver mDnsResolver;
    @VisibleForTesting
    protected INetd mNetd;
    private NetworkStatsManager mStatsManager;
    private NetworkPolicyManager mPolicyManager;
    private NetworkPolicyManagerInternal mPolicyManagerInternal;
    private final NetdCallback mNetdCallback;

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
     * Used by setRequireVpnForUids.
     * arg1 = whether the specified UID ranges are required to use a VPN.
     * obj  = Array of UidRange objects.
     */
    private static final int EVENT_SET_REQUIRE_VPN_FOR_UIDS = 47;

    /**
     * used internally when setting the default networks for OemNetworkPreferences.
     * obj = OemNetworkPreferences
     */
    private static final int EVENT_SET_OEM_NETWORK_PREFERENCE = 48;

    /**
     * Used to indicate the system default network becomes active.
     */
    private static final int EVENT_REPORT_NETWORK_ACTIVITY = 49;

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

    private static IDnsResolver getDnsResolver(Context context) {
        return IDnsResolver.Stub.asInterface(DnsResolverServiceManager.getService(context));
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
    private QosCallbackTracker mQosCallbackTracker;
    private NetworkNotificationManager mNotifier;
    private LingerMonitor mLingerMonitor;

    // sequence number of NetworkRequests
    private int mNextNetworkRequestId = NetworkRequest.FIRST_REQUEST_ID;

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

        // When a lockdown VPN connects, send another CONNECTED broadcast for the underlying
        // network type, to preserve previous behaviour.
        private void maybeSendLegacyLockdownBroadcast(@NonNull NetworkAgentInfo vpnNai) {
            if (vpnNai != mService.getLegacyLockdownNai()) return;

            if (vpnNai.declaredUnderlyingNetworks == null
                    || vpnNai.declaredUnderlyingNetworks.length != 1) {
                Log.wtf(TAG, "Legacy lockdown VPN must have exactly one underlying network: "
                        + Arrays.toString(vpnNai.declaredUnderlyingNetworks));
                return;
            }
            final NetworkAgentInfo underlyingNai = mService.getNetworkAgentInfoForNetwork(
                    vpnNai.declaredUnderlyingNetworks[0]);
            if (underlyingNai == null) return;

            final int type = underlyingNai.networkInfo.getType();
            final DetailedState state = DetailedState.CONNECTED;
            maybeLogBroadcast(underlyingNai, state, type, true /* isDefaultNetwork */);
            mService.sendLegacyNetworkBroadcast(underlyingNai, state, type);
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

            // If a legacy lockdown VPN is active, override the NetworkInfo state in all broadcasts
            // to preserve previous behaviour.
            final DetailedState state = mService.getLegacyLockdownState(DetailedState.CONNECTED);
            if ((list.size() == 1) || isDefaultNetwork) {
                maybeLogBroadcast(nai, state, type, isDefaultNetwork);
                mService.sendLegacyNetworkBroadcast(nai, state, type);
            }

            if (type == TYPE_VPN && state == DetailedState.CONNECTED) {
                maybeSendLegacyLockdownBroadcast(nai);
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
     * Keeps track of the number of requests made under different uids.
     */
    public static class PerUidCounter {
        private final int mMaxCountPerUid;

        // Map from UID to number of NetworkRequests that UID has filed.
        @GuardedBy("mUidToNetworkRequestCount")
        private final SparseIntArray mUidToNetworkRequestCount = new SparseIntArray();

        /**
         * Constructor
         *
         * @param maxCountPerUid the maximum count per uid allowed
         */
        public PerUidCounter(final int maxCountPerUid) {
            mMaxCountPerUid = maxCountPerUid;
        }

        /**
         * Increments the request count of the given uid.  Throws an exception if the number
         * of open requests for the uid exceeds the value of maxCounterPerUid which is the value
         * passed into the constructor. see: {@link #PerUidCounter(int)}.
         *
         * @throws ServiceSpecificException with
         * {@link ConnectivityManager.Errors.TOO_MANY_REQUESTS} if the number of requests for
         * the uid exceed the allowed number.
         *
         * @param uid the uid that the request was made under
         */
        public void incrementCountOrThrow(final int uid) {
            synchronized (mUidToNetworkRequestCount) {
                final int networkRequests = mUidToNetworkRequestCount.get(uid, 0) + 1;
                if (networkRequests >= mMaxCountPerUid) {
                    throw new ServiceSpecificException(
                            ConnectivityManager.Errors.TOO_MANY_REQUESTS);
                }
                mUidToNetworkRequestCount.put(uid, networkRequests);
            }
        }

        /**
         * Decrements the request count of the given uid.
         *
         * @param uid the uid that the request was made under
         */
        public void decrementCount(final int uid) {
            synchronized (mUidToNetworkRequestCount) {
                final int requests = mUidToNetworkRequestCount.get(uid, 0);
                if (requests < 1) {
                    logwtf("BUG: too small request count " + requests + " for UID " + uid);
                } else if (requests == 1) {
                    mUidToNetworkRequestCount.delete(uid);
                } else {
                    mUidToNetworkRequestCount.put(uid, requests - 1);
                }
            }
        }
    }

    /**
     * Dependencies of ConnectivityService, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        public int getCallingUid() {
            return Binder.getCallingUid();
        }

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
         * Gets the UID that owns a socket connection. Needed because opening SOCK_DIAG sockets
         * requires CAP_NET_ADMIN, which the unit tests do not have.
         */
        public int getConnectionOwnerUid(int protocol, InetSocketAddress local,
                InetSocketAddress remote) {
            return InetDiagMessage.getConnectionOwnerUid(protocol, local, remote);
        }

        /**
         * @see MultinetworkPolicyTracker
         */
        public MultinetworkPolicyTracker makeMultinetworkPolicyTracker(
                @NonNull Context c, @NonNull Handler h, @NonNull Runnable r) {
            return new MultinetworkPolicyTracker(c, h, r);
        }

        public IBatteryStats getBatteryStatsService() {
            return BatteryStatsService.getService();
        }
    }

    public ConnectivityService(Context context) {
        this(context, getDnsResolver(context), new IpConnectivityLog(),
                NetdService.getInstance(), new Dependencies());
    }

    @VisibleForTesting
    protected ConnectivityService(Context context, IDnsResolver dnsresolver,
            IpConnectivityLog logger, INetd netd, Dependencies deps) {
        if (DBG) log("ConnectivityService starting up");

        mDeps = Objects.requireNonNull(deps, "missing Dependencies");
        mSystemProperties = mDeps.getSystemProperties();
        mNetIdManager = mDeps.makeNetIdManager();
        mContext = Objects.requireNonNull(context, "missing Context");
        mNetworkRequestCounter = new PerUidCounter(MAX_NETWORK_REQUESTS_PER_UID);

        mMetricsLog = logger;
        mNetworkRanker = new NetworkRanker();
        final NetworkRequest defaultInternetRequest = createDefaultRequest();
        mDefaultRequest = new NetworkRequestInfo(
                defaultInternetRequest, null, new Binder(),
                null /* attributionTags */);
        mNetworkRequests.put(defaultInternetRequest, mDefaultRequest);
        mDefaultNetworkRequests.add(mDefaultRequest);
        mNetworkRequestInfoLogs.log("REGISTER " + mDefaultRequest);

        mDefaultMobileDataRequest = createDefaultInternetRequestForTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR, NetworkRequest.Type.BACKGROUND_REQUEST);

        // The default WiFi request is a background request so that apps using WiFi are
        // migrated to a better network (typically ethernet) when one comes up, instead
        // of staying on WiFi forever.
        mDefaultWifiRequest = createDefaultInternetRequestForTransport(
                NetworkCapabilities.TRANSPORT_WIFI, NetworkRequest.Type.BACKGROUND_REQUEST);

        mDefaultVehicleRequest = createAlwaysOnRequestForCapability(
                NetworkCapabilities.NET_CAPABILITY_VEHICLE_INTERNAL,
                NetworkRequest.Type.BACKGROUND_REQUEST);

        mHandlerThread = mDeps.makeHandlerThread();
        mHandlerThread.start();
        mHandler = new InternalHandler(mHandlerThread.getLooper());
        mTrackerHandler = new NetworkStateTrackerHandler(mHandlerThread.getLooper());
        mConnectivityDiagnosticsHandler =
                new ConnectivityDiagnosticsHandler(mHandlerThread.getLooper());

        mReleasePendingIntentDelayMs = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.CONNECTIVITY_RELEASE_PENDING_INTENT_DELAY_MS, 5_000);

        mLingerDelayMs = mSystemProperties.getInt(LINGER_DELAY_PROPERTY, DEFAULT_LINGER_DELAY_MS);
        // TODO: Consider making the timer customizable.
        mNascentDelayMs = DEFAULT_NASCENT_DELAY_MS;

        mStatsManager = mContext.getSystemService(NetworkStatsManager.class);
        mPolicyManager = mContext.getSystemService(NetworkPolicyManager.class);
        mPolicyManagerInternal = Objects.requireNonNull(
                LocalServices.getService(NetworkPolicyManagerInternal.class),
                "missing NetworkPolicyManagerInternal");
        mDnsResolver = Objects.requireNonNull(dnsresolver, "missing IDnsResolver");
        mProxyTracker = mDeps.makeProxyTracker(mContext, mHandler);

        mNetd = netd;
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAppOpsManager = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mLocationPermissionChecker = new LocationPermissionChecker(mContext);

        // To ensure uid rules are synchronized with Network Policy, register for
        // NetworkPolicyManagerService events must happen prior to NetworkPolicyManagerService
        // reading existing policy from disk.
        mPolicyManager.registerListener(mPolicyListener);

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
        if (mNetConfigs[TYPE_ETHERNET] == null
                && mContext.getSystemService(Context.ETHERNET_SERVICE) != null) {
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

        // Listen for user add/removes to inform PermissionMonitor.
        // Should run on mHandler to avoid any races.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_ADDED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);

        mUserAllContext = mContext.createContextAsUser(UserHandle.ALL, 0 /* flags */);
        mUserAllContext.registerReceiver(mIntentReceiver, intentFilter,
                null /* broadcastPermission */, mHandler);

        mNetworkActivityTracker = new LegacyNetworkActivityTracker(mContext, mHandler, mNetd);

        mNetdCallback = new NetdCallback();
        try {
            mNetd.registerUnsolicitedEventListener(mNetdCallback);
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Error registering event listener :" + e);
        }

        mSettingsObserver = new SettingsObserver(mContext, mHandler);
        registerSettingsCallbacks();

        final DataConnectionStats dataConnectionStats = new DataConnectionStats(mContext, mHandler);
        dataConnectionStats.startMonitoring();

        mKeepaliveTracker = new KeepaliveTracker(mContext, mHandler);
        mNotifier = new NetworkNotificationManager(mContext, mTelephonyManager);
        mQosCallbackTracker = new QosCallbackTracker(mHandler, mNetworkRequestCounter);

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

        mDnsManager = new DnsManager(mContext, mDnsResolver);
        registerPrivateDnsSettingsCallbacks();

        mNoServiceNetwork = new NetworkAgentInfo(null,
                new Network(NO_SERVICE_NET_ID),
                new NetworkInfo(TYPE_NONE, 0, "", ""),
                new LinkProperties(), new NetworkCapabilities(), 0, mContext,
                null, new NetworkAgentConfig(), this, null,
                null, 0, INVALID_UID,
                mQosCallbackTracker);
    }

    private static NetworkCapabilities createDefaultNetworkCapabilitiesForUid(int uid) {
        final NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(NET_CAPABILITY_INTERNET);
        netCap.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        netCap.removeCapability(NET_CAPABILITY_NOT_VPN);
        netCap.setSingleUid(uid);
        return netCap;
    }

    private NetworkRequest createDefaultRequest() {
        return createDefaultInternetRequestForTransport(
                TYPE_NONE, NetworkRequest.Type.REQUEST);
    }

    private NetworkRequest createDefaultInternetRequestForTransport(
            int transportType, NetworkRequest.Type type) {
        final NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(NET_CAPABILITY_INTERNET);
        netCap.addCapability(NET_CAPABILITY_NOT_VCN_MANAGED);
        netCap.setRequestorUidAndPackageName(Process.myUid(), mContext.getPackageName());
        if (transportType > TYPE_NONE) {
            netCap.addTransportType(transportType);
        }
        return createNetworkRequest(type, netCap);
    }

    private NetworkRequest createNetworkRequest(
            NetworkRequest.Type type, NetworkCapabilities netCap) {
        return new NetworkRequest(netCap, TYPE_NONE, nextNetworkRequestId(), type);
    }

    private NetworkRequest createAlwaysOnRequestForCapability(int capability,
            NetworkRequest.Type type) {
        final NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.clearAll();
        netCap.addCapability(capability);
        netCap.setRequestorUidAndPackageName(Process.myUid(), mContext.getPackageName());
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

    private void handleAlwaysOnNetworkRequest(NetworkRequest networkRequest, @BoolRes int id) {
        final boolean enable = mContext.getResources().getBoolean(id);
        handleAlwaysOnNetworkRequest(networkRequest, enable);
    }

    private void handleAlwaysOnNetworkRequest(
            NetworkRequest networkRequest, String settingName, boolean defaultValue) {
        final boolean enable = toBool(Settings.Global.getInt(
                mContext.getContentResolver(), settingName, encodeBool(defaultValue)));
        handleAlwaysOnNetworkRequest(networkRequest, enable);
    }

    private void handleAlwaysOnNetworkRequest(NetworkRequest networkRequest, boolean enable) {
        final boolean isEnabled = (mNetworkRequests.get(networkRequest) != null);
        if (enable == isEnabled) {
            return;  // Nothing to do.
        }

        if (enable) {
            handleRegisterNetworkRequest(new NetworkRequestInfo(
                    networkRequest, null, new Binder(),
                    null /* attributionTags */));
        } else {
            handleReleaseNetworkRequest(networkRequest, Process.SYSTEM_UID,
                    /* callOnUnavailable */ false);
        }
    }

    private void handleConfigureAlwaysOnNetworks() {
        handleAlwaysOnNetworkRequest(
                mDefaultMobileDataRequest, Settings.Global.MOBILE_DATA_ALWAYS_ON, true);
        handleAlwaysOnNetworkRequest(mDefaultWifiRequest, Settings.Global.WIFI_ALWAYS_REQUESTED,
                false);
        handleAlwaysOnNetworkRequest(mDefaultVehicleRequest,
                com.android.internal.R.bool.config_vehicleInternalNetworkAlwaysRequested);
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
        // TODO: Consider handle wrapping and exclude {@link NetworkRequest#REQUEST_ID_NONE} if
        //  doing that.
        return mNextNetworkRequestId++;
    }

    @VisibleForTesting
    protected NetworkAgentInfo getNetworkAgentInfoForNetwork(Network network) {
        if (network == null) {
            return null;
        }
        return getNetworkAgentInfoForNetId(network.getNetId());
    }

    private NetworkAgentInfo getNetworkAgentInfoForNetId(int netId) {
        synchronized (mNetworkForNetId) {
            return mNetworkForNetId.get(netId);
        }
    }

    // TODO: determine what to do when more than one VPN applies to |uid|.
    private NetworkAgentInfo getVpnForUid(int uid) {
        synchronized (mNetworkForNetId) {
            for (int i = 0; i < mNetworkForNetId.size(); i++) {
                final NetworkAgentInfo nai = mNetworkForNetId.valueAt(i);
                if (nai.isVPN() && nai.everConnected && nai.networkCapabilities.appliesToUid(uid)) {
                    return nai;
                }
            }
        }
        return null;
    }

    private Network[] getVpnUnderlyingNetworks(int uid) {
        if (mLockdownEnabled) return null;
        final NetworkAgentInfo nai = getVpnForUid(uid);
        if (nai != null) return nai.declaredUnderlyingNetworks;
        return null;
    }

    private NetworkAgentInfo getNetworkAgentInfoForUid(int uid) {
        NetworkAgentInfo nai = getDefaultNetworkForUid(uid);

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
        return nai;
    }

    /**
     * Check if UID should be blocked from using the specified network.
     */
    private boolean isNetworkWithCapabilitiesBlocked(@Nullable final NetworkCapabilities nc,
            final int uid, final boolean ignoreBlocked) {
        // Networks aren't blocked when ignoring blocked status
        if (ignoreBlocked) {
            return false;
        }
        if (isUidBlockedByVpn(uid, mVpnBlockedUidRanges)) return true;
        final long ident = Binder.clearCallingIdentity();
        try {
            final boolean metered = nc == null ? true : nc.isMetered();
            return mPolicyManager.isUidNetworkingBlocked(uid, metered);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
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
        final String action = blocked ? "BLOCKED" : "UNBLOCKED";
        final int requestId = nri.getActiveRequest() != null
                ? nri.getActiveRequest().requestId : nri.mRequests.get(0).requestId;
        mNetworkInfoBlockingLogs.log(String.format(
                "%s %d(%d) on netId %d", action, nri.mUid, requestId, net.getNetId()));
    }

    /**
     * Apply any relevant filters to the specified {@link NetworkInfo} for the given UID. For
     * example, this may mark the network as {@link DetailedState#BLOCKED} based
     * on {@link #isNetworkWithCapabilitiesBlocked}.
     */
    @NonNull
    private NetworkInfo filterNetworkInfo(@NonNull NetworkInfo networkInfo, int type,
            @NonNull NetworkCapabilities nc, int uid, boolean ignoreBlocked) {
        final NetworkInfo filtered = new NetworkInfo(networkInfo);
        // Many legacy types (e.g,. TYPE_MOBILE_HIPRI) are not actually a property of the network
        // but only exists if an app asks about them or requests them. Ensure the requesting app
        // gets the type it asks for.
        filtered.setType(type);
        final DetailedState state = isNetworkWithCapabilitiesBlocked(nc, uid, ignoreBlocked)
                ? DetailedState.BLOCKED
                : filtered.getDetailedState();
        filtered.setDetailedState(getLegacyLockdownState(state),
                "" /* reason */, null /* extraInfo */);
        return filtered;
    }

    private NetworkInfo getFilteredNetworkInfo(NetworkAgentInfo nai, int uid,
            boolean ignoreBlocked) {
        return filterNetworkInfo(nai.networkInfo, nai.networkInfo.getType(),
                nai.networkCapabilities, uid, ignoreBlocked);
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
        final int uid = mDeps.getCallingUid();
        final NetworkAgentInfo nai = getNetworkAgentInfoForUid(uid);
        if (nai == null) return null;
        final NetworkInfo networkInfo = getFilteredNetworkInfo(nai, uid, false);
        maybeLogBlockedNetworkInfo(networkInfo, uid);
        return networkInfo;
    }

    @Override
    public Network getActiveNetwork() {
        enforceAccessPermission();
        return getActiveNetworkForUidInternal(mDeps.getCallingUid(), false);
    }

    @Override
    public Network getActiveNetworkForUid(int uid, boolean ignoreBlocked) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        return getActiveNetworkForUidInternal(uid, ignoreBlocked);
    }

    private Network getActiveNetworkForUidInternal(final int uid, boolean ignoreBlocked) {
        final NetworkAgentInfo vpnNai = getVpnForUid(uid);
        if (vpnNai != null) {
            final NetworkCapabilities requiredCaps = createDefaultNetworkCapabilitiesForUid(uid);
            if (requiredCaps.satisfiedByNetworkCapabilities(vpnNai.networkCapabilities)) {
                return vpnNai.network;
            }
        }

        NetworkAgentInfo nai = getDefaultNetworkForUid(uid);
        if (nai == null || isNetworkWithCapabilitiesBlocked(nai.networkCapabilities, uid,
                ignoreBlocked)) {
            return null;
        }
        return nai.network;
    }

    @Override
    public NetworkInfo getActiveNetworkInfoForUid(int uid, boolean ignoreBlocked) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        final NetworkAgentInfo nai = getNetworkAgentInfoForUid(uid);
        if (nai == null) return null;
        return getFilteredNetworkInfo(nai, uid, ignoreBlocked);
    }

    /** Returns a NetworkInfo object for a network that doesn't exist. */
    private NetworkInfo makeFakeNetworkInfo(int networkType, int uid) {
        final NetworkInfo info = new NetworkInfo(networkType, 0 /* subtype */,
                getNetworkTypeName(networkType), "" /* subtypeName */);
        info.setIsAvailable(true);
        // For compatibility with legacy code, return BLOCKED instead of DISCONNECTED when
        // background data is restricted.
        final NetworkCapabilities nc = new NetworkCapabilities();  // Metered.
        final DetailedState state = isNetworkWithCapabilitiesBlocked(nc, uid, false)
                ? DetailedState.BLOCKED
                : DetailedState.DISCONNECTED;
        info.setDetailedState(getLegacyLockdownState(state),
                "" /* reason */, null /* extraInfo */);
        return info;
    }

    private NetworkInfo getFilteredNetworkInfoForType(int networkType, int uid) {
        if (!mLegacyTypeTracker.isTypeSupported(networkType)) {
            return null;
        }
        final NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai == null) {
            return makeFakeNetworkInfo(networkType, uid);
        }
        return filterNetworkInfo(nai.networkInfo, networkType, nai.networkCapabilities, uid,
                false);
    }

    @Override
    public NetworkInfo getNetworkInfo(int networkType) {
        enforceAccessPermission();
        final int uid = mDeps.getCallingUid();
        if (getVpnUnderlyingNetworks(uid) != null) {
            // A VPN is active, so we may need to return one of its underlying networks. This
            // information is not available in LegacyTypeTracker, so we have to get it from
            // getNetworkAgentInfoForUid.
            final NetworkAgentInfo nai = getNetworkAgentInfoForUid(uid);
            if (nai == null) return null;
            final NetworkInfo networkInfo = getFilteredNetworkInfo(nai, uid, false);
            if (networkInfo.getType() == networkType) {
                return networkInfo;
            }
        }
        return getFilteredNetworkInfoForType(networkType, uid);
    }

    @Override
    public NetworkInfo getNetworkInfoForUid(Network network, int uid, boolean ignoreBlocked) {
        enforceAccessPermission();
        final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null) return null;
        return getFilteredNetworkInfo(nai, uid, ignoreBlocked);
    }

    @Override
    public NetworkInfo[] getAllNetworkInfo() {
        enforceAccessPermission();
        final ArrayList<NetworkInfo> result = new ArrayList<>();
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
        if (!mLegacyTypeTracker.isTypeSupported(networkType)) {
            return null;
        }
        final NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai == null) {
            return null;
        }
        final int uid = mDeps.getCallingUid();
        if (isNetworkWithCapabilitiesBlocked(nai.networkCapabilities, uid, false)) {
            return null;
        }
        return nai.network;
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
                int userId, String callingPackageName, @Nullable String callingAttributionTag) {
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

        for (final NetworkRequestInfo nri : mDefaultNetworkRequests) {
            if (!nri.isBeingSatisfied()) {
                continue;
            }
            final NetworkAgentInfo nai = nri.getSatisfier();
            final NetworkCapabilities nc = getNetworkCapabilitiesInternal(nai);
            if (null != nc
                    && nc.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)
                    && !result.containsKey(nai.network)) {
                result.put(
                        nai.network,
                        createWithLocationInfoSanitizedIfNecessaryWhenParceled(
                                nc, mDeps.getCallingUid(), callingPackageName,
                                callingAttributionTag));
            }
        }

        // No need to check mLockdownEnabled. If it's true, getVpnUnderlyingNetworks returns null.
        final Network[] networks = getVpnUnderlyingNetworks(Binder.getCallingUid());
        if (null != networks) {
            for (final Network network : networks) {
                final NetworkCapabilities nc = getNetworkCapabilitiesInternal(network);
                if (null != nc) {
                    result.put(
                            network,
                            createWithLocationInfoSanitizedIfNecessaryWhenParceled(
                                    nc, mDeps.getCallingUid(), callingPackageName,
                                    callingAttributionTag));
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
     * network interface for the calling uid.
     * @return the ip properties for the active network, or {@code null} if
     * none is active
     */
    @Override
    public LinkProperties getActiveLinkProperties() {
        enforceAccessPermission();
        final int uid = mDeps.getCallingUid();
        NetworkAgentInfo nai = getNetworkAgentInfoForUid(uid);
        if (nai == null) return null;
        return linkPropertiesRestrictedForCallerPermissions(nai.linkProperties,
                Binder.getCallingPid(), uid);
    }

    @Override
    public LinkProperties getLinkPropertiesForType(int networkType) {
        enforceAccessPermission();
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        final LinkProperties lp = getLinkProperties(nai);
        if (lp == null) return null;
        return linkPropertiesRestrictedForCallerPermissions(
                lp, Binder.getCallingPid(), mDeps.getCallingUid());
    }

    // TODO - this should be ALL networks
    @Override
    public LinkProperties getLinkProperties(Network network) {
        enforceAccessPermission();
        final LinkProperties lp = getLinkProperties(getNetworkAgentInfoForNetwork(network));
        if (lp == null) return null;
        return linkPropertiesRestrictedForCallerPermissions(
                lp, Binder.getCallingPid(), mDeps.getCallingUid());
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
            return networkCapabilitiesRestrictedForCallerPermissions(
                    nai.networkCapabilities, Binder.getCallingPid(), mDeps.getCallingUid());
        }
    }

    @Override
    public NetworkCapabilities getNetworkCapabilities(Network network, String callingPackageName,
            @Nullable String callingAttributionTag) {
        mAppOpsManager.checkPackage(mDeps.getCallingUid(), callingPackageName);
        enforceAccessPermission();
        return createWithLocationInfoSanitizedIfNecessaryWhenParceled(
                getNetworkCapabilitiesInternal(network),
                mDeps.getCallingUid(), callingPackageName, callingAttributionTag);
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

    private boolean hasLocationPermission(int callerUid, @NonNull String callerPkgName,
            @Nullable String callingAttributionTag) {
        final long token = Binder.clearCallingIdentity();
        try {
            return mLocationPermissionChecker.checkLocationPermission(
                    callerPkgName, callingAttributionTag, callerUid, null /* message */);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @VisibleForTesting
    @Nullable
    NetworkCapabilities createWithLocationInfoSanitizedIfNecessaryWhenParceled(
            @Nullable NetworkCapabilities nc, int callerUid, @NonNull String callerPkgName,
            @Nullable String callingAttributionTag) {
        if (nc == null) {
            return null;
        }
        Boolean hasLocationPermission = null;
        final NetworkCapabilities newNc;
        // Avoid doing location permission check if the transport info has no location sensitive
        // data.
        if (nc.getTransportInfo() != null && nc.getTransportInfo().hasLocationSensitiveFields()) {
            hasLocationPermission =
                    hasLocationPermission(callerUid, callerPkgName, callingAttributionTag);
            newNc = new NetworkCapabilities(nc, hasLocationPermission);
        } else {
            newNc = new NetworkCapabilities(nc, false /* parcelLocationSensitiveFields */);
        }
        // Reset owner uid if not destined for the owner app.
        if (callerUid != nc.getOwnerUid()) {
            newNc.setOwnerUid(INVALID_UID);
            return newNc;
        }
        // Allow VPNs to see ownership of their own VPN networks - not location sensitive.
        if (nc.hasTransport(TRANSPORT_VPN)) {
            // Owner UIDs already checked above. No need to re-check.
            return newNc;
        }
        if (hasLocationPermission == null) {
            // Location permission not checked yet, check now for masking owner UID.
            hasLocationPermission =
                    hasLocationPermission(callerUid, callerPkgName, callingAttributionTag);
        }
        // Reset owner uid if the app has no location permission.
        if (!hasLocationPermission) {
            newNc.setOwnerUid(INVALID_UID);
        }
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
        if (!mPermissionMonitor.hasUseBackgroundNetworksPermission(mDeps.getCallingUid())) {
            nc.addCapability(NET_CAPABILITY_FOREGROUND);
        }
    }

    @Override
    public NetworkState[] getAllNetworkState() {
        // This contains IMSI details, so make sure the caller is privileged.
        PermissionUtils.enforceNetworkStackPermission(mContext);

        final ArrayList<NetworkState> result = new ArrayList<>();
        for (Network network : getAllNetworks()) {
            final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
            // TODO: Consider include SUSPENDED networks.
            if (nai != null && nai.networkInfo.isConnected()) {
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

    /**
     * Ensures that the system cannot call a particular method.
     */
    private boolean disallowedBecauseSystemCaller() {
        // TODO: start throwing a SecurityException when GnssLocationProvider stops calling
        // requestRouteToHost. In Q, GnssLocationProvider is changed to not call requestRouteToHost
        // for devices launched with Q and above. However, existing devices upgrading to Q and
        // above must continued to be supported for few more releases.
        if (isSystem(mDeps.getCallingUid()) && SystemProperties.getInt(
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
    public boolean requestRouteToHostAddress(int networkType, byte[] hostAddress,
            String callingPackageName, String callingAttributionTag) {
        if (disallowedBecauseSystemCaller()) {
            return false;
        }
        enforceChangePermission(callingPackageName, callingAttributionTag);
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

        final int uid = mDeps.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            LinkProperties lp;
            int netId;
            synchronized (nai) {
                lp = nai.linkProperties;
                netId = nai.network.getNetId();
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

        final String dst = bestRoute.getDestinationLinkAddress().toString();
        final String nextHop = bestRoute.hasGateway()
                ? bestRoute.getGateway().getHostAddress() : "";
        try {
            mNetd.networkAddLegacyRoute(netId, bestRoute.getInterface(), dst, nextHop , uid);
        } catch (RemoteException | ServiceSpecificException e) {
            if (DBG) loge("Exception trying to add a route: " + e);
            return false;
        }
        return true;
    }

    class DnsResolverUnsolicitedEventCallback extends
            IDnsResolverUnsolicitedEventListener.Stub {
        @Override
        public void onPrivateDnsValidationEvent(final PrivateDnsValidationEventParcel event) {
            try {
                mHandler.sendMessage(mHandler.obtainMessage(
                        EVENT_PRIVATE_DNS_VALIDATION_UPDATE,
                        new PrivateDnsValidationUpdate(event.netId,
                                InetAddresses.parseNumericAddress(event.ipAddress),
                                event.hostname, event.validation)));
            } catch (IllegalArgumentException e) {
                loge("Error parsing ip address in validation event");
            }
        }

        @Override
        public void onDnsHealthEvent(final DnsHealthEventParcel event) {
            NetworkAgentInfo nai = getNetworkAgentInfoForNetId(event.netId);
            // Netd event only allow registrants from system. Each NetworkMonitor thread is under
            // the caller thread of registerNetworkAgent. Thus, it's not allowed to register netd
            // event callback for certain nai. e.g. cellular. Register here to pass to
            // NetworkMonitor instead.
            // TODO: Move the Dns Event to NetworkMonitor. NetdEventListenerService only allow one
            // callback from each caller type. Need to re-factor NetdEventListenerService to allow
            // multiple NetworkMonitor registrants.
            if (nai != null && nai.satisfies(mDefaultRequest.mRequests.get(0))) {
                nai.networkMonitor().notifyDnsResponse(event.healthResult);
            }
        }

        @Override
        public void onNat64PrefixEvent(final Nat64PrefixEventParcel event) {
            mHandler.post(() -> handleNat64PrefixEvent(event.netId, event.prefixOperation,
                    event.prefixAddress, event.prefixLength));
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

    @VisibleForTesting
    protected final DnsResolverUnsolicitedEventCallback mResolverUnsolEventCallback =
            new DnsResolverUnsolicitedEventCallback();

    private void registerDnsResolverUnsolicitedEventListener() {
        try {
            mDnsResolver.registerUnsolicitedEventListener(mResolverUnsolEventCallback);
        } catch (Exception e) {
            loge("Error registering DnsResolver unsolicited event callback: " + e);
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

        final List<UidRange> blockedRanges = mVpnBlockedUidRanges;
        for (final NetworkAgentInfo nai : mNetworkAgentInfos) {
            final boolean curMetered = nai.networkCapabilities.isMetered();
            maybeNotifyNetworkBlocked(nai, curMetered, curMetered, mRestrictBackground,
                    restrictBackground, blockedRanges, blockedRanges);
        }

        mRestrictBackground = restrictBackground;
    }

    private boolean isUidBlockedByRules(int uid, int uidRules, boolean isNetworkMetered,
            boolean isBackgroundRestricted) {
        return mPolicyManager.checkUidNetworkingBlocked(uid, uidRules, isNetworkMetered,
                isBackgroundRestricted);
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

    /**
     * Performs a strict and comprehensive check of whether a calling package is allowed to
     * change the state of network, as the condition differs for pre-M, M+, and
     * privileged/preinstalled apps. The caller is expected to have either the
     * CHANGE_NETWORK_STATE or the WRITE_SETTINGS permission declared. Either of these
     * permissions allow changing network state; WRITE_SETTINGS is a runtime permission and
     * can be revoked, but (except in M, excluding M MRs), CHANGE_NETWORK_STATE is a normal
     * permission and cannot be revoked. See http://b/23597341
     *
     * Note: if the check succeeds because the application holds WRITE_SETTINGS, the operation
     * of this app will be updated to the current time.
     */
    private void enforceChangePermission(String callingPkg, String callingAttributionTag) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.CHANGE_NETWORK_STATE)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (callingPkg == null) {
            throw new SecurityException("Calling package name is null.");
        }

        final AppOpsManager appOpsMgr = mContext.getSystemService(AppOpsManager.class);
        final int uid = mDeps.getCallingUid();
        final int mode = appOpsMgr.noteOpNoThrow(AppOpsManager.OPSTR_WRITE_SETTINGS, uid,
                callingPkg, callingAttributionTag, null /* message */);

        if (mode == AppOpsManager.MODE_ALLOWED) {
            return;
        }

        if ((mode == AppOpsManager.MODE_DEFAULT) && (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SETTINGS) == PackageManager.PERMISSION_GRANTED)) {
            return;
        }

        throw new SecurityException(callingPkg + " was not granted either of these permissions:"
                + android.Manifest.permission.CHANGE_NETWORK_STATE + ","
                + android.Manifest.permission.WRITE_SETTINGS + ".");
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

    private void enforceNetworkFactoryOrSettingsPermission() {
        enforceAnyPermissionOf(
                android.Manifest.permission.NETWORK_SETTINGS,
                android.Manifest.permission.NETWORK_FACTORY,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private void enforceNetworkFactoryOrTestNetworksPermission() {
        enforceAnyPermissionOf(
                android.Manifest.permission.MANAGE_TEST_NETWORKS,
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

    private void enforceAirplaneModePermission() {
        enforceAnyPermissionOf(
                android.Manifest.permission.NETWORK_AIRPLANE_MODE,
                android.Manifest.permission.NETWORK_SETTINGS,
                android.Manifest.permission.NETWORK_SETUP_WIZARD,
                android.Manifest.permission.NETWORK_STACK,
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK);
    }

    private void enforceOemNetworkPreferencesPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONTROL_OEM_PAID_NETWORK_PREFERENCE,
                "ConnectivityService");
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
                NetworkStack.PERMISSION_MAINLINE_NETWORK_STACK,
                android.Manifest.permission.NETWORK_SETTINGS);
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
        PermissionUtils.enforceNetworkStackPermission(mContext);
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION);
    }

    private void sendInetConditionBroadcast(NetworkInfo info) {
        sendGeneralBroadcast(info, ConnectivityManager.INET_CONDITION_ACTION);
    }

    private Intent makeGeneralIntent(NetworkInfo info, String bcastType) {
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
                mUserAllContext.sendStickyBroadcast(intent, options);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /**
     * Called by SystemServer through ConnectivityManager when the system is ready.
     */
    @Override
    public void systemReady() {
        if (mDeps.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Calling Uid is not system uid.");
        }
        systemReadyInternal();
    }

    /**
     * Called when ConnectivityService can initialize remaining components.
     */
    @VisibleForTesting
    public void systemReadyInternal() {
        // Since mApps in PermissionMonitor needs to be populated first to ensure that
        // listening network request which is sent by MultipathPolicyTracker won't be added
        // NET_CAPABILITY_FOREGROUND capability. Thus, MultipathPolicyTracker.start() must
        // be called after PermissionMonitor#startMonitoring().
        // Calling PermissionMonitor#startMonitoring() in systemReadyInternal() and the
        // MultipathPolicyTracker.start() is called in NetworkPolicyManagerService#systemReady()
        // to ensure the tracking will be initialized correctly.
        mPermissionMonitor.startMonitoring();
        mProxyTracker.loadGlobalProxy();
        registerDnsResolverUnsolicitedEventListener();

        synchronized (this) {
            mSystemReady = true;
            if (mInitialBroadcast != null) {
                mContext.sendStickyBroadcastAsUser(mInitialBroadcast, UserHandle.ALL);
                mInitialBroadcast = null;
            }
        }

        // Create network requests for always-on networks.
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_CONFIGURE_ALWAYS_ON_NETWORKS));
    }

    /**
     * Start listening for default data network activity state changes.
     */
    @Override
    public void registerNetworkActivityListener(@NonNull INetworkActivityListener l) {
        mNetworkActivityTracker.registerNetworkActivityListener(l);
    }

    /**
     * Stop listening for default data network activity state changes.
     */
    @Override
    public void unregisterNetworkActivityListener(@NonNull INetworkActivityListener l) {
        mNetworkActivityTracker.unregisterNetworkActivityListener(l);
    }

    /**
     * Check whether the default network radio is currently active.
     */
    @Override
    public boolean isDefaultNetworkActive() {
        return mNetworkActivityTracker.isDefaultNetworkActive();
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
            mNetd.interfaceSetMtu(iface, mtu);
        } catch (RemoteException | ServiceSpecificException e) {
            loge("exception in interfaceSetMtu()" + e);
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
            if (VDBG || DDBG) log("Setting tx/rx TCP buffers to " + tcpBufferSizes);

            String rmemValues = String.join(" ", values[0], values[1], values[2]);
            String wmemValues = String.join(" ", values[3], values[4], values[5]);
            mNetd.setTcpRWmemorySize(rmemValues, wmemValues);
            mCurrentTcpBufferSizes = tcpBufferSizes;
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Can't set TCP buffer sizes:" + e);
        }

        final Integer rwndValue = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.TCP_DEFAULT_INIT_RWND,
                    mSystemProperties.getInt("net.tcp.default_init_rwnd", 0));
        if (rwndValue != 0) {
            mSystemProperties.setTcpInitRwnd(rwndValue);
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
            PrivateDnsConfig privateDnsCfg = mDnsManager.getPrivateDnsConfig(nai.network);
            // Start gathering diagnostic information.
            netDiags.add(new NetworkDiagnostics(
                    nai.network,
                    new LinkProperties(nai.linkProperties),  // Must be a copy.
                    privateDnsCfg,
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

    private boolean checkDumpPermission(Context context, String tag, PrintWriter pw) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump " + tag + " from from pid="
                    + Binder.getCallingPid() + ", uid=" + mDeps.getCallingUid()
                    + " due to missing android.permission.DUMP permission");
            return false;
        } else {
            return true;
        }
    }

    private void doDump(FileDescriptor fd, PrintWriter writer, String[] args, boolean asProto) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (!checkDumpPermission(mContext, TAG, pw)) return;
        if (asProto) return;

        if (CollectionUtils.contains(args, DIAG_ARG)) {
            dumpNetworkDiagnostics(pw);
            return;
        } else if (CollectionUtils.contains(args, NETWORK_ARG)) {
            dumpNetworks(pw);
            return;
        } else if (CollectionUtils.contains(args, REQUEST_ARG)) {
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
            pw.println(defaultNai.network.getNetId());
        }
        pw.println();

        pw.print("Current per-app default networks: ");
        pw.increaseIndent();
        dumpPerAppNetworkPreferences(pw);
        pw.decreaseIndent();
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

        if (!CollectionUtils.contains(args, SHORT_ARG)) {
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

        pw.println();
        pw.println("Legacy network activity:");
        pw.increaseIndent();
        mNetworkActivityTracker.dump(pw);
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
            pw.println("Inactivity Timers:");
            pw.increaseIndent();
            nai.dumpInactivityTimers(pw);
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
    }

    private void dumpPerAppNetworkPreferences(IndentingPrintWriter pw) {
        pw.println("Per-App Network Preference:");
        pw.increaseIndent();
        if (0 == mOemNetworkPreferences.getNetworkPreferences().size()) {
            pw.println("none");
        } else {
            pw.println(mOemNetworkPreferences.toString());
        }
        pw.decreaseIndent();

        for (final NetworkRequestInfo defaultRequest : mDefaultNetworkRequests) {
            if (mDefaultRequest == defaultRequest) {
                continue;
            }

            final boolean isActive = null != defaultRequest.getSatisfier();
            pw.println("Is per-app network active:");
            pw.increaseIndent();
            pw.println(isActive);
            if (isActive) {
                pw.println("Active network: " + defaultRequest.getSatisfier().network.netId);
            }
            pw.println("Tracked UIDs:");
            pw.increaseIndent();
            if (0 == defaultRequest.mRequests.size()) {
                pw.println("none, this should never occur.");
            } else {
                pw.println(defaultRequest.mRequests.get(0).networkCapabilities.getUids());
            }
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
        networks = mNetworkAgentInfos.toArray(networks);
        Arrays.sort(networks, Comparator.comparingInt(nai -> nai.network.getNetId()));
        return networks;
    }

    /**
     * Return an array of all current NetworkRequest sorted by request id.
     */
    @VisibleForTesting
    NetworkRequestInfo[] requestsSortedById() {
        NetworkRequestInfo[] requests = new NetworkRequestInfo[0];
        requests = getNrisFromGlobalRequests().toArray(requests);
        // Sort the array based off the NRI containing the min requestId in its requests.
        Arrays.sort(requests,
                Comparator.comparingInt(nri -> Collections.min(nri.mRequests,
                        Comparator.comparingInt(req -> req.requestId)).requestId
                )
        );
        return requests;
    }

    private boolean isLiveNetworkAgent(NetworkAgentInfo nai, int what) {
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
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    handleAsyncChannelDisconnected(msg);
                    break;
                }
            }
            return true;
        }

        private void maybeHandleNetworkAgentMessage(Message msg) {
            final Pair<NetworkAgentInfo, Object> arg = (Pair<NetworkAgentInfo, Object>) msg.obj;
            final NetworkAgentInfo nai = arg.first;
            if (!mNetworkAgentInfos.contains(nai)) {
                if (VDBG) {
                    log(String.format("%s from unknown NetworkAgent", eventName(msg.what)));
                }
                return;
            }

            switch (msg.what) {
                case NetworkAgent.EVENT_NETWORK_CAPABILITIES_CHANGED: {
                    NetworkCapabilities networkCapabilities = (NetworkCapabilities) arg.second;
                    if (networkCapabilities.hasConnectivityManagedCapability()) {
                        Log.wtf(TAG, "BUG: " + nai + " has CS-managed capability.");
                    }
                    if (networkCapabilities.hasTransport(TRANSPORT_TEST)) {
                        // Make sure the original object is not mutated. NetworkAgent normally
                        // makes a copy of the capabilities when sending the message through
                        // the Messenger, but if this ever changes, not making a defensive copy
                        // here will give attack vectors to clients using this code path.
                        networkCapabilities = new NetworkCapabilities(networkCapabilities);
                        networkCapabilities.restrictCapabilitesForTestNetwork(nai.creatorUid);
                    }
                    processCapabilitiesFromAgent(nai, networkCapabilities);
                    updateCapabilities(nai.getCurrentScore(), nai, networkCapabilities);
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_PROPERTIES_CHANGED: {
                    LinkProperties newLp = (LinkProperties) arg.second;
                    processLinkPropertiesFromAgent(nai, newLp);
                    handleUpdateLinkProperties(nai, newLp);
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_INFO_CHANGED: {
                    NetworkInfo info = (NetworkInfo) arg.second;
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
                    mKeepaliveTracker.handleEventSocketKeepalive(nai, msg.arg1, msg.arg2);
                    break;
                }
                case NetworkAgent.EVENT_UNDERLYING_NETWORKS_CHANGED: {
                    // TODO: prevent loops, e.g., if a network declares itself as underlying.
                    if (!nai.supportsUnderlyingNetworks()) {
                        Log.wtf(TAG, "Non-virtual networks cannot have underlying networks");
                        break;
                    }

                    final List<Network> underlying = (List<Network>) arg.second;

                    if (isLegacyLockdownNai(nai)
                            && (underlying == null || underlying.size() != 1)) {
                        Log.wtf(TAG, "Legacy lockdown VPN " + nai.toShortString()
                                + " must have exactly one underlying network: " + underlying);
                    }

                    final Network[] oldUnderlying = nai.declaredUnderlyingNetworks;
                    nai.declaredUnderlyingNetworks = (underlying != null)
                            ? underlying.toArray(new Network[0]) : null;

                    if (!Arrays.equals(oldUnderlying, nai.declaredUnderlyingNetworks)) {
                        if (DBG) {
                            log(nai.toShortString() + " changed underlying networks to "
                                    + Arrays.toString(nai.declaredUnderlyingNetworks));
                        }
                        updateCapabilitiesForNetwork(nai);
                        notifyIfacesChangedForNetworkStats();
                    }
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
                            updateCapabilitiesForNetwork(nai);
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
                        updateCapabilitiesForNetwork(nai);
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
                    break;
                }
                case EVENT_PROVISIONING_NOTIFICATION: {
                    final int netId = msg.arg2;
                    final boolean visible = toBool(msg.arg1);
                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(netId);
                    // If captive portal status has changed, update capabilities or disconnect.
                    if (nai != null && (visible != nai.lastCaptivePortalDetected)) {
                        nai.lastCaptivePortalDetected = visible;
                        nai.everCaptivePortalDetected |= visible;
                        if (nai.lastCaptivePortalDetected &&
                            Settings.Global.CAPTIVE_PORTAL_MODE_AVOID == getCaptivePortalMode()) {
                            if (DBG) log("Avoiding captive portal network: " + nai.toShortString());
                            nai.onPreventAutomaticReconnect();
                            teardownUnneededNetwork(nai);
                            break;
                        }
                        updateCapabilitiesForNetwork(nai);
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
                    handleCapportApiDataUpdate(nai, (CaptivePortalData) msg.obj);
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
                    mNotifier.clearNotification(nai.network.getNetId(),
                            NotificationType.NO_INTERNET);
                    mNotifier.clearNotification(nai.network.getNetId(),
                            NotificationType.LOST_INTERNET);
                    mNotifier.clearNotification(nai.network.getNetId(),
                            NotificationType.PARTIAL_CONNECTIVITY);
                    mNotifier.clearNotification(nai.network.getNetId(),
                            NotificationType.PRIVATE_DNS_BROKEN);
                    // If network becomes valid, the hasShownBroken should be reset for
                    // that network so that the notification will be fired when the private
                    // DNS is broken again.
                    nai.networkAgentConfig.hasShownBroken = false;
                }
            } else if (partialConnectivityChanged) {
                updateCapabilitiesForNetwork(nai);
            }
            updateInetCondition(nai);
            // Let the NetworkAgent know the state of its network
            // TODO: Evaluate to update partial connectivity to status to NetworkAgent.
            nai.onValidationStatusChanged(
                    valid ? NetworkAgent.VALID_NETWORK : NetworkAgent.INVALID_NETWORK,
                    redirectUrl);

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
                case NetworkAgentInfo.EVENT_AGENT_REGISTERED: {
                    handleNetworkAgentRegistered(msg);
                    break;
                }
                case NetworkAgentInfo.EVENT_AGENT_DISCONNECTED: {
                    handleNetworkAgentDisconnected(msg);
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
            mNetId = nai.network.getNetId();
            mNai = new AutodestructReference<>(nai);
        }

        @Override
        public void onNetworkMonitorCreated(INetworkMonitor networkMonitor) {
            mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_AGENT,
                    new Pair<>(mNai.getAndDestroy(), networkMonitor)));
        }

        @Override
        public void notifyNetworkTested(int testResult, @Nullable String redirectUrl) {
            // Legacy version of notifyNetworkTestedWithExtras.
            // Would only be called if the system has a NetworkStack module older than the
            // framework, which does not happen in practice.
            Log.wtf(TAG, "Deprecated notifyNetworkTested called: no action taken");
        }

        @Override
        public void notifyNetworkTestedWithExtras(NetworkTestResultParcelable p) {
            // Notify mTrackerHandler and mConnectivityDiagnosticsHandler of the event. Both use
            // the same looper so messages will be processed in sequence.
            final Message msg = mTrackerHandler.obtainMessage(
                    EVENT_NETWORK_TESTED,
                    new NetworkTestedResults(
                            mNetId, p.result, p.timestampMillis, p.redirectUrl));
            mTrackerHandler.sendMessage(msg);

            // Invoke ConnectivityReport generation for this Network test event.
            final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(mNetId);
            if (nai == null) return;

            final PersistableBundle extras = new PersistableBundle();
            extras.putInt(KEY_NETWORK_VALIDATION_RESULT, p.result);
            extras.putInt(KEY_NETWORK_PROBES_SUCCEEDED_BITMASK, p.probesSucceeded);
            extras.putInt(KEY_NETWORK_PROBES_ATTEMPTED_BITMASK, p.probesAttempted);

            ConnectivityReportEvent reportEvent =
                    new ConnectivityReportEvent(p.timestampMillis, nai, extras);
            final Message m = mConnectivityDiagnosticsHandler.obtainMessage(
                    ConnectivityDiagnosticsHandler.EVENT_NETWORK_TESTED, reportEvent);
            mConnectivityDiagnosticsHandler.sendMessage(m);
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
                pendingIntent = PendingIntent.getBroadcast(
                        mContext,
                        0 /* requestCode */,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE);
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
        public void notifyDataStallSuspected(DataStallReportParcelable p) {
            ConnectivityService.this.notifyDataStallSuspected(p, mNetId);
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

    private void notifyDataStallSuspected(DataStallReportParcelable p, int netId) {
        log("Data stall detected with methods: " + p.detectionMethod);

        final PersistableBundle extras = new PersistableBundle();
        int detectionMethod = 0;
        if (hasDataStallDetectionMethod(p, DETECTION_METHOD_DNS_EVENTS)) {
            extras.putInt(KEY_DNS_CONSECUTIVE_TIMEOUTS, p.dnsConsecutiveTimeouts);
            detectionMethod |= DETECTION_METHOD_DNS_EVENTS;
        }
        if (hasDataStallDetectionMethod(p, DETECTION_METHOD_TCP_METRICS)) {
            extras.putInt(KEY_TCP_PACKET_FAIL_RATE, p.tcpPacketFailRate);
            extras.putInt(KEY_TCP_METRICS_COLLECTION_PERIOD_MILLIS,
                    p.tcpMetricsCollectionPeriodMillis);
            detectionMethod |= DETECTION_METHOD_TCP_METRICS;
        }

        final Message msg = mConnectivityDiagnosticsHandler.obtainMessage(
                ConnectivityDiagnosticsHandler.EVENT_DATA_STALL_SUSPECTED, detectionMethod, netId,
                new Pair<>(p.timestampMillis, extras));

        // NetworkStateTrackerHandler currently doesn't take any actions based on data
        // stalls so send the message directly to ConnectivityDiagnosticsHandler and avoid
        // the cost of going through two handlers.
        mConnectivityDiagnosticsHandler.sendMessage(msg);
    }

    private boolean hasDataStallDetectionMethod(DataStallReportParcelable p, int detectionMethod) {
        return (p.detectionMethod & detectionMethod) != 0;
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
            updateDnses(nai.linkProperties, null, nai.network.getNetId());
        }
    }

    private void handlePrivateDnsSettingsChanged() {
        final PrivateDnsConfig cfg = mDnsManager.getPrivateDnsConfig();

        for (NetworkAgentInfo nai : mNetworkAgentInfos) {
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
        updateDnses(nai.linkProperties, null, nai.network.getNetId());
    }

    private void handlePrivateDnsValidationUpdate(PrivateDnsValidationUpdate update) {
        NetworkAgentInfo nai = getNetworkAgentInfoForNetId(update.netId);
        if (nai == null) {
            return;
        }
        mDnsManager.updatePrivateDnsValidation(update);
        handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
    }

    private void handleNat64PrefixEvent(int netId, int operation, String prefixAddress,
            int prefixLength) {
        NetworkAgentInfo nai = mNetworkForNetId.get(netId);
        if (nai == null) return;

        log(String.format("NAT64 prefix changed on netId %d: operation=%d, %s/%d",
                netId, operation, prefixAddress, prefixLength));

        IpPrefix prefix = null;
        if (operation == IDnsResolverUnsolicitedEventListener.PREFIX_OPERATION_ADDED) {
            try {
                prefix = new IpPrefix(InetAddresses.parseNumericAddress(prefixAddress),
                        prefixLength);
            } catch (IllegalArgumentException e) {
                loge("Invalid NAT64 prefix " + prefixAddress + "/" + prefixLength);
                return;
            }
        }

        nai.clatd.setNat64PrefixFromDns(prefix);
        handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
    }

    private void handleCapportApiDataUpdate(@NonNull final NetworkAgentInfo nai,
            @Nullable final CaptivePortalData data) {
        nai.capportApiData = data;
        // CaptivePortalData will be merged into LinkProperties from NetworkAgentInfo
        handleUpdateLinkProperties(nai, new LinkProperties(nai.linkProperties));
    }

    /**
     * Updates the inactivity state from the network requests inside the NAI.
     * @param nai the agent info to update
     * @param now the timestamp of the event causing this update
     * @return whether the network was inactive as a result of this update
     */
    private boolean updateInactivityState(@NonNull final NetworkAgentInfo nai, final long now) {
        // 1. Update the inactivity timer. If it's changed, reschedule or cancel the alarm.
        // 2. If the network was inactive and there are now requests, unset inactive.
        // 3. If this network is unneeded (which implies it is not lingering), and there is at least
        //    one lingered request, set inactive.
        nai.updateInactivityTimer();
        if (nai.isInactive() && nai.numForegroundNetworkRequests() > 0) {
            if (DBG) log("Unsetting inactive " + nai.toShortString());
            nai.unsetInactive();
            logNetworkEvent(nai, NetworkEvent.NETWORK_UNLINGER);
        } else if (unneeded(nai, UnneededFor.LINGER) && nai.getInactivityExpiry() > 0) {
            if (DBG) {
                final int lingerTime = (int) (nai.getInactivityExpiry() - now);
                log("Setting inactive " + nai.toShortString() + " for " + lingerTime + "ms");
            }
            nai.setInactive();
            logNetworkEvent(nai, NetworkEvent.NETWORK_LINGER);
            return true;
        }
        return false;
    }

    private void handleAsyncChannelHalfConnect(Message msg) {
        ensureRunningOnConnectivityServiceThread();
        if (mNetworkProviderInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                if (VDBG) log("NetworkFactory connected");
                // Finish setting up the full connection
                NetworkProviderInfo npi = mNetworkProviderInfos.get(msg.replyTo);
                sendAllRequestsToProvider(npi);
            } else {
                loge("Error connecting NetworkFactory");
                mNetworkProviderInfos.remove(msg.obj);
            }
        }
    }

    private void handleNetworkAgentRegistered(Message msg) {
        final NetworkAgentInfo nai = (NetworkAgentInfo) msg.obj;
        if (!mNetworkAgentInfos.contains(nai)) {
            return;
        }

        if (msg.arg1 == NetworkAgentInfo.ARG_AGENT_SUCCESS) {
            if (VDBG) log("NetworkAgent registered");
        } else {
            loge("Error connecting NetworkAgent");
            mNetworkAgentInfos.remove(nai);
            if (nai != null) {
                final boolean wasDefault = isDefaultNetwork(nai);
                synchronized (mNetworkForNetId) {
                    mNetworkForNetId.remove(nai.network.getNetId());
                }
                mNetIdManager.releaseNetId(nai.network.getNetId());
                // Just in case.
                mLegacyTypeTracker.remove(nai, wasDefault);
            }
        }
    }

    private void handleNetworkAgentDisconnected(Message msg) {
        NetworkAgentInfo nai = (NetworkAgentInfo) msg.obj;
        if (mNetworkAgentInfos.contains(nai)) {
            disconnectAndDestroyNetwork(nai);
        }
    }

    // This is a no-op if it's called with a message designating a provider that has
    // already been destroyed, because its reference will not be found in the relevant
    // maps.
    private void handleAsyncChannelDisconnected(Message msg) {
        NetworkProviderInfo npi = mNetworkProviderInfos.remove(msg.replyTo);
        if (DBG && npi != null) log("unregisterNetworkFactory for " + npi.name);
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
        mNotifier.clearNotification(nai.network.getNetId());
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
        }
        notifyIfacesChangedForNetworkStats();
        // TODO - we shouldn't send CALLBACK_LOST to requests that can be satisfied
        // by other networks that are already connected. Perhaps that can be done by
        // sending all CALLBACK_LOST messages (for requests, not listens) at the end
        // of rematchAllNetworksAndRequests
        notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOST);
        mKeepaliveTracker.handleStopAllKeepalives(nai, SocketKeepalive.ERROR_INVALID_NETWORK);

        mQosCallbackTracker.handleNetworkReleased(nai.network);
        for (String iface : nai.linkProperties.getAllInterfaceNames()) {
            // Disable wakeup packet monitoring for each interface.
            wakeupModifyInterface(iface, nai.networkCapabilities, false);
        }
        nai.networkMonitor().notifyNetworkDisconnected();
        mNetworkAgentInfos.remove(nai);
        nai.clatd.update();
        synchronized (mNetworkForNetId) {
            // Remove the NetworkAgent, but don't mark the netId as
            // available until we've told netd to delete it below.
            mNetworkForNetId.remove(nai.network.getNetId());
        }
        propagateUnderlyingNetworkCapabilities(nai.network);
        // Remove all previously satisfied requests.
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            final NetworkRequest request = nai.requestAt(i);
            final NetworkRequestInfo nri = mNetworkRequests.get(request);
            final NetworkAgentInfo currentNetwork = nri.getSatisfier();
            if (currentNetwork != null
                    && currentNetwork.network.getNetId() == nai.network.getNetId()) {
                // uid rules for this network will be removed in destroyNativeNetwork(nai).
                nri.setSatisfier(null, null);
                if (request.isRequest()) {
                    sendUpdatedScoreToFactories(request, null);
                }

                if (mDefaultRequest == nri) {
                    // TODO : make battery stats aware that since 2013 multiple interfaces may be
                    //  active at the same time. For now keep calling this with the default
                    //  network, because while incorrect this is the closest to the old (also
                    //  incorrect) behavior.
                    mNetworkActivityTracker.updateDataActivityTracking(
                            null /* newNetwork */, nai);
                    ensureNetworkTransitionWakelock(nai.toShortString());
                }
            }
        }
        nai.clearInactivityState();
        // TODO: mLegacyTypeTracker.remove seems redundant given there's a full rematch right after.
        //  Currently, deleting it breaks tests that check for the default network disconnecting.
        //  Find out why, fix the rematch code, and delete this.
        mLegacyTypeTracker.remove(nai, wasDefault);
        rematchAllNetworksAndRequests();
        mLingerMonitor.noteDisconnect(nai);
        if (nai.created) {
            // Tell netd to clean up the configuration for this network
            // (routing rules, DNS, etc).
            // This may be slow as it requires a lot of netd shelling out to ip and
            // ip[6]tables to flush routes and remove the incoming packet mark rule, so do it
            // after we've rematched networks with requests (which might change the default
            // network or service a new request from an app), so network traffic isn't interrupted
            // for an unnecessarily long time.
            destroyNativeNetwork(nai);
            mDnsManager.removeNetwork(nai.network);
        }
        mNetIdManager.releaseNetId(nai.network.getNetId());
    }

    private boolean createNativeNetwork(@NonNull NetworkAgentInfo networkAgent) {
        try {
            // This should never fail.  Specifying an already in use NetID will cause failure.
            if (networkAgent.isVPN()) {
                mNetd.networkCreateVpn(networkAgent.network.getNetId(),
                        (networkAgent.networkAgentConfig == null
                                || !networkAgent.networkAgentConfig.allowBypass));
            } else {
                mNetd.networkCreatePhysical(networkAgent.network.getNetId(),
                        getNetworkPermission(networkAgent.networkCapabilities));
            }
            mDnsResolver.createNetworkCache(networkAgent.network.getNetId());
            mDnsManager.updateTransportsForNetwork(networkAgent.network.getNetId(),
                    networkAgent.networkCapabilities.getTransportTypes());
            return true;
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Error creating network " + networkAgent.network.getNetId() + ": "
                    + e.getMessage());
            return false;
        }
    }

    private void destroyNativeNetwork(@NonNull NetworkAgentInfo networkAgent) {
        try {
            mNetd.networkDestroy(networkAgent.network.getNetId());
            mDnsResolver.destroyNetworkCache(networkAgent.network.getNetId());
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

    private void handleRegisterNetworkRequestWithIntent(@NonNull final Message msg) {
        final NetworkRequestInfo nri = (NetworkRequestInfo) (msg.obj);
        // handleRegisterNetworkRequestWithIntent() doesn't apply to multilayer requests.
        ensureNotMultilayerRequest(nri, "handleRegisterNetworkRequestWithIntent");
        final NetworkRequestInfo existingRequest =
                findExistingNetworkRequestInfo(nri.mPendingIntent);
        if (existingRequest != null) { // remove the existing request.
            if (DBG) {
                log("Replacing " + existingRequest.mRequests.get(0) + " with "
                        + nri.mRequests.get(0) + " because their intents matched.");
            }
            handleReleaseNetworkRequest(existingRequest.mRequests.get(0), getCallingUid(),
                    /* callOnUnavailable */ false);
        }
        handleRegisterNetworkRequest(nri);
    }

    private void handleRegisterNetworkRequest(@NonNull final NetworkRequestInfo nri) {
        handleRegisterNetworkRequests(Collections.singleton(nri));
    }

    private void handleRegisterNetworkRequests(@NonNull final Set<NetworkRequestInfo> nris) {
        ensureRunningOnConnectivityServiceThread();
        for (final NetworkRequestInfo nri : nris) {
            mNetworkRequestInfoLogs.log("REGISTER " + nri);
            for (final NetworkRequest req : nri.mRequests) {
                mNetworkRequests.put(req, nri);
                if (req.isListen()) {
                    for (final NetworkAgentInfo network : mNetworkAgentInfos) {
                        if (req.networkCapabilities.hasSignalStrength()
                                && network.satisfiesImmutableCapabilitiesOf(req)) {
                            updateSignalStrengthThresholds(network, "REGISTER", req);
                        }
                    }
                }
            }
        }

        rematchAllNetworksAndRequests();
        for (final NetworkRequestInfo nri : nris) {
            // If the nri is satisfied, return as its score has already been sent if needed.
            if (nri.isBeingSatisfied()) {
                return;
            }

            // As this request was not satisfied on rematch and thus never had any scores sent to
            // the factories, send null now for each request of type REQUEST.
            for (final NetworkRequest req : nri.mRequests) {
                if (req.isRequest()) sendUpdatedScoreToFactories(req, null);
            }
        }
    }

    private void handleReleaseNetworkRequestWithIntent(@NonNull final PendingIntent pendingIntent,
            final int callingUid) {
        final NetworkRequestInfo nri = findExistingNetworkRequestInfo(pendingIntent);
        if (nri != null) {
            // handleReleaseNetworkRequestWithIntent() paths don't apply to multilayer requests.
            ensureNotMultilayerRequest(nri, "handleReleaseNetworkRequestWithIntent");
            handleReleaseNetworkRequest(
                    nri.mRequests.get(0),
                    callingUid,
                    /* callOnUnavailable */ false);
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
                Log.wtf(TAG, "Invalid reason. Cannot happen.");
                return true;
        }

        if (!nai.everConnected || nai.isVPN() || nai.isInactive() || numRequests > 0) {
            return false;
        }
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            if (reason == UnneededFor.LINGER
                    && !nri.isMultilayerRequest()
                    && nri.mRequests.get(0).isBackgroundRequest()) {
                // Background requests don't affect lingering.
                continue;
            }

            if (isNetworkPotentialSatisfier(nai, nri)) {
                return false;
            }
        }
        return true;
    }

    private boolean isNetworkPotentialSatisfier(
            @NonNull final NetworkAgentInfo candidate, @NonNull final NetworkRequestInfo nri) {
        // listen requests won't keep up a network satisfying it. If this is not a multilayer
        // request, return immediately. For multilayer requests, check to see if any of the
        // multilayer requests may have a potential satisfier.
        if (!nri.isMultilayerRequest() && nri.mRequests.get(0).isListen()) {
            return false;
        }
        for (final NetworkRequest req : nri.mRequests) {
            // This multilayer listen request is satisfied therefore no further requests need to be
            // evaluated deeming this network not a potential satisfier.
            if (req.isListen() && nri.getActiveRequest() == req) {
                return false;
            }
            // As non-multilayer listen requests have already returned, the below would only happen
            // for a multilayer request therefore continue to the next request if available.
            if (req.isListen()) {
                continue;
            }
            // If this Network is already the highest scoring Network for a request, or if
            // there is hope for it to become one if it validated, then it is needed.
            if (candidate.satisfies(req)) {
                // As soon as a network is found that satisfies a request, return. Specifically for
                // multilayer requests, returning as soon as a NetworkAgentInfo satisfies a request
                // is important so as to not evaluate lower priority requests further in
                // nri.mRequests.
                final boolean isNetworkNeeded = candidate.isSatisfyingRequest(req.requestId)
                        // Note that this catches two important cases:
                        // 1. Unvalidated cellular will not be reaped when unvalidated WiFi
                        //    is currently satisfying the request.  This is desirable when
                        //    cellular ends up validating but WiFi does not.
                        // 2. Unvalidated WiFi will not be reaped when validated cellular
                        //    is currently satisfying the request.  This is desirable when
                        //    WiFi ends up validating and out scoring cellular.
                        || nri.getSatisfier().getCurrentScore()
                        < candidate.getCurrentScoreAsValidated();
                return isNetworkNeeded;
            }
        }

        return false;
    }

    private NetworkRequestInfo getNriForAppRequest(
            NetworkRequest request, int callingUid, String requestedOperation) {
        // Looking up the app passed param request in mRequests isn't possible since it may return
        // null for a request managed by a per-app default. Therefore use getNriForAppRequest() to
        // do the lookup since that will also find per-app default managed requests.
        // Additionally, this lookup needs to be relatively fast (hence the lookup optimization)
        // to avoid potential race conditions when validating a package->uid mapping when sending
        // the callback on the very low-chance that an application shuts down prior to the callback
        // being sent.
        final NetworkRequestInfo nri = mNetworkRequests.get(request) != null
                ? mNetworkRequests.get(request) : getNriForAppRequest(request);

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

    private void ensureNotMultilayerRequest(@NonNull final NetworkRequestInfo nri,
            final String callingMethod) {
        if (nri.isMultilayerRequest()) {
            throw new IllegalStateException(
                    callingMethod + " does not support multilayer requests.");
        }
    }

    private void handleTimedOutNetworkRequest(@NonNull final NetworkRequestInfo nri) {
        ensureRunningOnConnectivityServiceThread();
        // handleTimedOutNetworkRequest() is part of the requestNetwork() flow which works off of a
        // single NetworkRequest and thus does not apply to multilayer requests.
        ensureNotMultilayerRequest(nri, "handleTimedOutNetworkRequest");
        if (mNetworkRequests.get(nri.mRequests.get(0)) == null) {
            return;
        }
        if (nri.isBeingSatisfied()) {
            return;
        }
        if (VDBG || (DBG && nri.mRequests.get(0).isRequest())) {
            log("releasing " + nri.mRequests.get(0) + " (timeout)");
        }
        handleRemoveNetworkRequest(nri);
        callCallbackForRequest(
                nri, null, ConnectivityManager.CALLBACK_UNAVAIL, 0);
    }

    private void handleReleaseNetworkRequest(@NonNull final NetworkRequest request,
            final int callingUid,
            final boolean callOnUnavailable) {
        final NetworkRequestInfo nri =
                getNriForAppRequest(request, callingUid, "release NetworkRequest");
        if (nri == null) {
            return;
        }
        if (VDBG || (DBG && request.isRequest())) {
            log("releasing " + request + " (release request)");
        }
        handleRemoveNetworkRequest(nri);
        if (callOnUnavailable) {
            callCallbackForRequest(nri, null, ConnectivityManager.CALLBACK_UNAVAIL, 0);
        }
    }

    private void handleRemoveNetworkRequest(@NonNull final NetworkRequestInfo nri) {
        ensureRunningOnConnectivityServiceThread();
        nri.unlinkDeathRecipient();
        for (final NetworkRequest req : nri.mRequests) {
            mNetworkRequests.remove(req);
            if (req.isListen()) {
                removeListenRequestFromNetworks(req);
            }
        }
        mDefaultNetworkRequests.remove(nri);
        mNetworkRequestCounter.decrementCount(nri.mUid);
        mNetworkRequestInfoLogs.log("RELEASE " + nri);

        if (null != nri.getActiveRequest()) {
            if (!nri.getActiveRequest().isListen()) {
                removeSatisfiedNetworkRequestFromNetwork(nri);
            } else {
                nri.setSatisfier(null, null);
            }
        }

        cancelNpiRequests(nri);
    }

    private void handleRemoveNetworkRequests(@NonNull final Set<NetworkRequestInfo> nris) {
        for (final NetworkRequestInfo nri : nris) {
            if (mDefaultRequest == nri) {
                // Make sure we never remove the default request.
                continue;
            }
            handleRemoveNetworkRequest(nri);
        }
    }

    private void cancelNpiRequests(@NonNull final NetworkRequestInfo nri) {
        for (final NetworkRequest req : nri.mRequests) {
            cancelNpiRequest(req);
        }
    }

    private void cancelNpiRequest(@NonNull final NetworkRequest req) {
        if (req.isRequest()) {
            for (final NetworkProviderInfo npi : mNetworkProviderInfos.values()) {
                npi.cancelRequest(req);
            }
        }
    }

    private void removeListenRequestFromNetworks(@NonNull final NetworkRequest req) {
        // listens don't have a singular affected Network. Check all networks to see
        // if this listen request applies and remove it.
        for (final NetworkAgentInfo nai : mNetworkAgentInfos) {
            nai.removeRequest(req.requestId);
            if (req.networkCapabilities.hasSignalStrength()
                    && nai.satisfiesImmutableCapabilitiesOf(req)) {
                updateSignalStrengthThresholds(nai, "RELEASE", req);
            }
        }
    }

    /**
     * Remove a NetworkRequestInfo's satisfied request from its 'satisfier' (NetworkAgentInfo) and
     * manage the necessary upkeep (linger, teardown networks, etc.) when doing so.
     * @param nri the NetworkRequestInfo to disassociate from its current NetworkAgentInfo
     */
    private void removeSatisfiedNetworkRequestFromNetwork(@NonNull final NetworkRequestInfo nri) {
        boolean wasKept = false;
        final NetworkAgentInfo nai = nri.getSatisfier();
        if (nai != null) {
            final int requestLegacyType = nri.getActiveRequest().legacyType;
            final boolean wasBackgroundNetwork = nai.isBackgroundNetwork();
            nai.removeRequest(nri.getActiveRequest().requestId);
            if (VDBG || DDBG) {
                log(" Removing from current network " + nai.toShortString()
                        + ", leaving " + nai.numNetworkRequests() + " requests.");
            }
            // If there are still lingered requests on this network, don't tear it down,
            // but resume lingering instead.
            final long now = SystemClock.elapsedRealtime();
            if (updateInactivityState(nai, now)) {
                notifyNetworkLosing(nai, now);
            }
            if (unneeded(nai, UnneededFor.TEARDOWN)) {
                if (DBG) log("no live requests for " + nai.toShortString() + "; disconnecting");
                teardownUnneededNetwork(nai);
            } else {
                wasKept = true;
            }
            nri.setSatisfier(null, null);
            if (!wasBackgroundNetwork && nai.isBackgroundNetwork()) {
                // Went from foreground to background.
                updateCapabilitiesForNetwork(nai);
            }

            // Maintain the illusion.  When this request arrived, we might have pretended
            // that a network connected to serve it, even though the network was already
            // connected.  Now that this request has gone away, we might have to pretend
            // that the network disconnected.  LegacyTypeTracker will generate that
            // phantom disconnect for this type.
            if (requestLegacyType != TYPE_NONE) {
                boolean doRemove = true;
                if (wasKept) {
                    // check if any of the remaining requests for this network are for the
                    // same legacy type - if so, don't remove the nai
                    for (int i = 0; i < nai.numNetworkRequests(); i++) {
                        NetworkRequest otherRequest = nai.requestAt(i);
                        if (otherRequest.legacyType == requestLegacyType
                                && otherRequest.isRequest()) {
                            if (DBG) log(" still have other legacy request - leaving");
                            doRemove = false;
                        }
                    }
                }

                if (doRemove) {
                    mLegacyTypeTracker.remove(requestLegacyType, nai, false);
                }
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
            Log.wtf(TAG, "BUG: setAcceptUnvalidated non non-explicitly selected network");
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
            nai.onSaveAcceptUnvalidated(accept);
        }

        if (!accept) {
            // Tell the NetworkAgent to not automatically reconnect to the network.
            nai.onPreventAutomaticReconnect();
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
            nai.onSaveAcceptUnvalidated(accept);
        }

        if (!accept) {
            // Tell the NetworkAgent to not automatically reconnect to the network.
            nai.onPreventAutomaticReconnect();
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

        final long token = Binder.clearCallingIdentity();
        try {
            mContext.startActivityAsUser(appIntent, UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
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
                nm.forceReevaluation(mDeps.getCallingUid());
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
        for (NetworkAgentInfo nai: mNetworkAgentInfos) {
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

    // TODO: This method is copied from TetheringNotificationUpdater. Should have a utility class to
    // unify the method.
    private static @NonNull String getSettingsPackageName(@NonNull final PackageManager pm) {
        final Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
        final ComponentName settingsComponent = settingsIntent.resolveActivity(pm);
        return settingsComponent != null
                ? settingsComponent.getPackageName() : "com.android.settings";
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
                Log.wtf(TAG, "Unknown notification type " + type);
                return;
        }

        Intent intent = new Intent(action);
        if (type != NotificationType.PRIVATE_DNS_BROKEN) {
            intent.setData(Uri.fromParts("netId", Integer.toString(nai.network.getNetId()), null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Some OEMs have their own Settings package. Thus, need to get the current using
            // Settings package name instead of just use default name "com.android.settings".
            final String settingsPkgName = getSettingsPackageName(mContext.getPackageManager());
            intent.setClassName(settingsPkgName,
                    settingsPkgName + ".wifi.WifiNoInternetDialog");
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
                mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */),
                0 /* requestCode */,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        mNotifier.showNotification(
                nai.network.getNetId(), type, nai, null, pendingIntent, highPriority);
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
        nai.onPreventAutomaticReconnect();

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

        final NetworkPolicyManager netPolicyManager =
                 mContext.getSystemService(NetworkPolicyManager.class);

        final int networkPreference = netPolicyManager.getMultipathPreference(network);
        if (networkPreference != 0) {
            return networkPreference;
        }
        return mMultinetworkPolicyTracker.getMeteredMultipathPreference();
    }

    @Override
    public NetworkRequest getDefaultRequest() {
        return mDefaultRequest.mRequests.get(0);
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
                case EVENT_SET_REQUIRE_VPN_FOR_UIDS:
                    handleSetRequireVpnForUids(toBool(msg.arg1), (UidRange[]) msg.obj);
                    break;
                case EVENT_SET_OEM_NETWORK_PREFERENCE:
                    final Pair<OemNetworkPreferences, IOnSetOemNetworkPreferenceListener> arg =
                            (Pair<OemNetworkPreferences,
                                    IOnSetOemNetworkPreferenceListener>) msg.obj;
                    try {
                        handleSetOemNetworkPreference(arg.first, arg.second);
                    } catch (RemoteException e) {
                        loge("handleMessage.EVENT_SET_OEM_NETWORK_PREFERENCE failed", e);
                    }
                    break;
                case EVENT_REPORT_NETWORK_ACTIVITY:
                    mNetworkActivityTracker.handleReportNetworkActivity();
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
                Log.w(TAG, "expected Net Transition WakeLock to be held");
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
        final int uid = mDeps.getCallingUid();
        final int connectivityInfo = encodeBool(hasConnectivity);

        // Handle ConnectivityDiagnostics event before attempting to revalidate the network. This
        // forces an ordering of ConnectivityDiagnostics events in the case where hasConnectivity
        // does not match the known connectivity of the network - this causes NetworkMonitor to
        // revalidate the network and generate a ConnectivityDiagnostics ConnectivityReport event.
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
            int netid = nai.network.getNetId();
            log("reportNetworkConnectivity(" + netid + ", " + hasConnectivity + ") by " + uid);
        }
        // Validating a network that has not yet connected could result in a call to
        // rematchNetworkAndRequests() which is not meant to work on such networks.
        if (!nai.everConnected) {
            return;
        }
        final NetworkCapabilities nc = getNetworkCapabilitiesInternal(nai);
        if (isNetworkWithCapabilitiesBlocked(nc, uid, false)) {
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
            final Network activeNetwork = getActiveNetworkForUidInternal(mDeps.getCallingUid(),
                    true);
            if (activeNetwork == null) {
                return null;
            }
            return getLinkPropertiesProxyInfo(activeNetwork);
        } else if (mDeps.queryUserAccess(mDeps.getCallingUid(), network.getNetId())) {
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
        PermissionUtils.enforceNetworkStackPermission(mContext);
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
            Log.wtf(TAG, "Should never be reached.");
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
        Log.d(TAG, s);
    }

    private static void logw(String s) {
        Log.w(TAG, s);
    }

    private static void logwtf(String s) {
        Log.wtf(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }

    private static void loge(String s, Throwable t) {
        Log.e(TAG, s, t);
    }

    /**
     * Return the information of all ongoing VPNs.
     *
     * <p>This method is used to update NetworkStatsService.
     *
     * <p>Must be called on the handler thread.
     */
    private UnderlyingNetworkInfo[] getAllVpnInfo() {
        ensureRunningOnConnectivityServiceThread();
        if (mLockdownEnabled) {
            return new UnderlyingNetworkInfo[0];
        }
        List<UnderlyingNetworkInfo> infoList = new ArrayList<>();
        for (NetworkAgentInfo nai : mNetworkAgentInfos) {
            UnderlyingNetworkInfo info = createVpnInfo(nai);
            if (info != null) {
                infoList.add(info);
            }
        }
        return infoList.toArray(new UnderlyingNetworkInfo[infoList.size()]);
    }

    /**
     * @return VPN information for accounting, or null if we can't retrieve all required
     *         information, e.g underlying ifaces.
     */
    private UnderlyingNetworkInfo createVpnInfo(NetworkAgentInfo nai) {
        if (!nai.isVPN()) return null;

        Network[] underlyingNetworks = nai.declaredUnderlyingNetworks;
        // see VpnService.setUnderlyingNetworks()'s javadoc about how to interpret
        // the underlyingNetworks list.
        if (underlyingNetworks == null) {
            final NetworkAgentInfo defaultNai = getDefaultNetworkForUid(
                    nai.networkCapabilities.getOwnerUid());
            if (defaultNai != null) {
                underlyingNetworks = new Network[] { defaultNai.network };
            }
        }

        if (CollectionUtils.isEmpty(underlyingNetworks)) return null;

        List<String> interfaces = new ArrayList<>();
        for (Network network : underlyingNetworks) {
            NetworkAgentInfo underlyingNai = getNetworkAgentInfoForNetwork(network);
            if (underlyingNai == null) continue;
            LinkProperties lp = underlyingNai.linkProperties;
            for (String iface : lp.getAllInterfaceNames()) {
                if (!TextUtils.isEmpty(iface)) {
                    interfaces.add(iface);
                }
            }
        }

        if (interfaces.isEmpty()) return null;

        // Must be non-null or NetworkStatsService will crash.
        // Cannot happen in production code because Vpn only registers the NetworkAgent after the
        // tun or ipsec interface is created.
        // TODO: Remove this check.
        if (nai.linkProperties.getInterfaceName() == null) return null;

        return new UnderlyingNetworkInfo(nai.networkCapabilities.getOwnerUid(),
                nai.linkProperties.getInterfaceName(), interfaces);
    }

    // TODO This needs to be the default network that applies to the NAI.
    private Network[] underlyingNetworksOrDefault(final int ownerUid,
            Network[] underlyingNetworks) {
        final Network defaultNetwork = getNetwork(getDefaultNetworkForUid(ownerUid));
        if (underlyingNetworks == null && defaultNetwork != null) {
            // null underlying networks means to track the default.
            underlyingNetworks = new Network[] { defaultNetwork };
        }
        return underlyingNetworks;
    }

    // Returns true iff |network| is an underlying network of |nai|.
    private boolean hasUnderlyingNetwork(NetworkAgentInfo nai, Network network) {
        // TODO: support more than one level of underlying networks, either via a fixed-depth search
        // (e.g., 2 levels of underlying networks), or via loop detection, or....
        if (!nai.supportsUnderlyingNetworks()) return false;
        final Network[] underlying = underlyingNetworksOrDefault(
                nai.networkCapabilities.getOwnerUid(), nai.declaredUnderlyingNetworks);
        return CollectionUtils.contains(underlying, network);
    }

    /**
     * Recompute the capabilities for any networks that had a specific network as underlying.
     *
     * When underlying networks change, such networks may have to update capabilities to reflect
     * things like the metered bit, their transports, and so on. The capabilities are calculated
     * immediately. This method runs on the ConnectivityService thread.
     */
    private void propagateUnderlyingNetworkCapabilities(Network updatedNetwork) {
        ensureRunningOnConnectivityServiceThread();
        for (NetworkAgentInfo nai : mNetworkAgentInfos) {
            if (updatedNetwork == null || hasUnderlyingNetwork(nai, updatedNetwork)) {
                updateCapabilitiesForNetwork(nai);
            }
        }
    }

    private boolean isUidBlockedByVpn(int uid, List<UidRange> blockedUidRanges) {
        // Determine whether this UID is blocked because of always-on VPN lockdown. If a VPN applies
        // to the UID, then the UID is not blocked because always-on VPN lockdown applies only when
        // a VPN is not up.
        final NetworkAgentInfo vpnNai = getVpnForUid(uid);
        if (vpnNai != null && !vpnNai.networkAgentConfig.allowBypass) return false;
        for (UidRange range : blockedUidRanges) {
            if (range.contains(uid)) return true;
        }
        return false;
    }

    @Override
    public void setRequireVpnForUids(boolean requireVpn, UidRange[] ranges) {
        PermissionUtils.enforceNetworkStackPermission(mContext);
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_REQUIRE_VPN_FOR_UIDS,
                encodeBool(requireVpn), 0 /* arg2 */, ranges));
    }

    private void handleSetRequireVpnForUids(boolean requireVpn, UidRange[] ranges) {
        if (DBG) {
            Log.d(TAG, "Setting VPN " + (requireVpn ? "" : "not ") + "required for UIDs: "
                    + Arrays.toString(ranges));
        }
        // Cannot use a Set since the list of UID ranges might contain duplicates.
        final List<UidRange> newVpnBlockedUidRanges = new ArrayList(mVpnBlockedUidRanges);
        for (int i = 0; i < ranges.length; i++) {
            if (requireVpn) {
                newVpnBlockedUidRanges.add(ranges[i]);
            } else {
                newVpnBlockedUidRanges.remove(ranges[i]);
            }
        }

        try {
            mNetd.networkRejectNonSecureVpn(requireVpn, toUidRangeStableParcels(ranges));
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "setRequireVpnForUids(" + requireVpn + ", "
                    + Arrays.toString(ranges) + "): netd command failed: " + e);
        }

        for (final NetworkAgentInfo nai : mNetworkAgentInfos) {
            final boolean curMetered = nai.networkCapabilities.isMetered();
            maybeNotifyNetworkBlocked(nai, curMetered, curMetered, mRestrictBackground,
                    mRestrictBackground, mVpnBlockedUidRanges, newVpnBlockedUidRanges);
        }

        mVpnBlockedUidRanges = newVpnBlockedUidRanges;
    }

    @Override
    public void setLegacyLockdownVpnEnabled(boolean enabled) {
        enforceSettingsPermission();
        mHandler.post(() -> mLockdownEnabled = enabled);
    }

    private boolean isLegacyLockdownNai(NetworkAgentInfo nai) {
        return mLockdownEnabled
                && getVpnType(nai) == VpnManager.TYPE_VPN_LEGACY
                && nai.networkCapabilities.appliesToUid(Process.FIRST_APPLICATION_UID);
    }

    private NetworkAgentInfo getLegacyLockdownNai() {
        if (!mLockdownEnabled) {
            return null;
        }
        // The legacy lockdown VPN always only applies to userId 0.
        final NetworkAgentInfo nai = getVpnForUid(Process.FIRST_APPLICATION_UID);
        if (nai == null || !isLegacyLockdownNai(nai)) return null;

        // The legacy lockdown VPN must always have exactly one underlying network.
        // This code may run on any thread and declaredUnderlyingNetworks may change, so store it in
        // a local variable. There is no need to make a copy because its contents cannot change.
        final Network[] underlying = nai.declaredUnderlyingNetworks;
        if (underlying == null ||  underlying.length != 1) {
            return null;
        }

        // The legacy lockdown VPN always uses the default network.
        // If the VPN's underlying network is no longer the current default network, it means that
        // the default network has just switched, and the VPN is about to disconnect.
        // Report that the VPN is not connected, so when the state of NetworkInfo objects
        // overwritten by getLegacyLockdownState will be set to CONNECTING and not CONNECTED.
        final NetworkAgentInfo defaultNetwork = getDefaultNetwork();
        if (defaultNetwork == null || !defaultNetwork.network.equals(underlying[0])) {
            return null;
        }

        return nai;
    };

    private DetailedState getLegacyLockdownState(DetailedState origState) {
        if (origState != DetailedState.CONNECTED) {
            return origState;
        }
        return (mLockdownEnabled && getLegacyLockdownNai() == null)
                ? DetailedState.CONNECTING
                : DetailedState.CONNECTED;
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
        enforceAirplaneModePermission();
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

    private void onUserAdded(UserHandle user) {
        mPermissionMonitor.onUserAdded(user);
    }

    private void onUserRemoved(UserHandle user) {
        mPermissionMonitor.onUserRemoved(user);
    }

    private BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ensureRunningOnConnectivityServiceThread();
            final String action = intent.getAction();
            final UserHandle user = intent.getParcelableExtra(Intent.EXTRA_USER);

            // User should be filled for below intents, check the existence.
            if (user == null) {
                Log.wtf(TAG, intent.getAction() + " broadcast without EXTRA_USER");
                return;
            }

            if (Intent.ACTION_USER_ADDED.equals(action)) {
                onUserAdded(user);
            } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                onUserRemoved(user);
            }  else {
                Log.wtf(TAG, "received unexpected intent: " + action);
            }
        }
    };

    private final HashMap<Messenger, NetworkProviderInfo> mNetworkProviderInfos = new HashMap<>();
    private final HashMap<NetworkRequest, NetworkRequestInfo> mNetworkRequests = new HashMap<>();

    private static class NetworkProviderInfo {
        public final String name;
        public final Messenger messenger;
        private final IBinder.DeathRecipient mDeathRecipient;
        public final int providerId;

        NetworkProviderInfo(String name, Messenger messenger, AsyncChannel asyncChannel,
                int providerId, @NonNull IBinder.DeathRecipient deathRecipient) {
            this.name = name;
            this.messenger = messenger;
            this.providerId = providerId;
            mDeathRecipient = deathRecipient;

            if (mDeathRecipient == null) {
                throw new AssertionError("Must pass a deathRecipient");
            }
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
            sendMessageToNetworkProvider(NetworkProvider.CMD_REQUEST_NETWORK, score,
                            servingProviderId, request);
        }

        void cancelRequest(NetworkRequest request) {
            sendMessageToNetworkProvider(NetworkProvider.CMD_CANCEL_REQUEST, 0, 0, request);
        }

        void connect(Context context, Handler handler) {
            try {
                messenger.getBinder().linkToDeath(mDeathRecipient, 0);
            } catch (RemoteException e) {
                mDeathRecipient.binderDied();
            }
        }
    }

    private void ensureAllNetworkRequestsHaveType(List<NetworkRequest> requests) {
        for (int i = 0; i < requests.size(); i++) {
            ensureNetworkRequestHasType(requests.get(i));
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
     * Also used to notice when the calling process dies so as to self-expire
     */
    @VisibleForTesting
    protected class NetworkRequestInfo implements IBinder.DeathRecipient {
        // The requests to be satisfied in priority order. Non-multilayer requests will only have a
        // single NetworkRequest in mRequests.
        final List<NetworkRequest> mRequests;

        // mSatisfier and mActiveRequest rely on one another therefore set them together.
        void setSatisfier(
                @Nullable final NetworkAgentInfo satisfier,
                @Nullable final NetworkRequest activeRequest) {
            mSatisfier = satisfier;
            mActiveRequest = activeRequest;
        }

        // The network currently satisfying this NRI. Only one request in an NRI can have a
        // satisfier. For non-multilayer requests, only non-listen requests can have a satisfier.
        @Nullable
        private NetworkAgentInfo mSatisfier;
        NetworkAgentInfo getSatisfier() {
            return mSatisfier;
        }

        // The request in mRequests assigned to a network agent. This is null if none of the
        // requests in mRequests can be satisfied. This member has the constraint of only being
        // accessible on the handler thread.
        @Nullable
        private NetworkRequest mActiveRequest;
        NetworkRequest getActiveRequest() {
            return mActiveRequest;
        }

        final PendingIntent mPendingIntent;
        boolean mPendingIntentSent;
        @Nullable
        final Messenger mMessenger;
        @Nullable
        private final IBinder mBinder;
        final int mPid;
        final int mUid;
        @Nullable
        final String mCallingAttributionTag;
        // In order to preserve the mapping of NetworkRequest-to-callback when apps register
        // callbacks using a returned NetworkRequest, the original NetworkRequest needs to be
        // maintained for keying off of. This is only a concern when the original nri
        // mNetworkRequests changes which happens currently for apps that register callbacks to
        // track the default network. In those cases, the nri is updated to have mNetworkRequests
        // that match the per-app default nri that currently tracks the calling app's uid so that
        // callbacks are fired at the appropriate time. When the callbacks fire,
        // mNetworkRequestForCallback will be used so as to preserve the caller's mapping. When
        // callbacks are updated to key off of an nri vs NetworkRequest, this stops being an issue.
        // TODO b/177608132: make sure callbacks are indexed by NRIs and not NetworkRequest objects.
        @NonNull
        private final NetworkRequest mNetworkRequestForCallback;
        NetworkRequest getNetworkRequestForCallback() {
            return mNetworkRequestForCallback;
        }

        /**
         * Get the list of UIDs this nri applies to.
         */
        @NonNull
        private Set<UidRange> getUids() {
            // networkCapabilities.getUids() returns a defensive copy.
            // multilayer requests will all have the same uids so return the first one.
            final Set<UidRange> uids = null == mRequests.get(0).networkCapabilities.getUids()
                    ? new ArraySet<>() : mRequests.get(0).networkCapabilities.getUids();
            return uids;
        }

        NetworkRequestInfo(@NonNull final NetworkRequest r, @Nullable final PendingIntent pi,
                @Nullable String callingAttributionTag) {
            this(Collections.singletonList(r), r, pi, callingAttributionTag);
        }

        NetworkRequestInfo(@NonNull final List<NetworkRequest> r,
                @NonNull final NetworkRequest requestForCallback, @Nullable final PendingIntent pi,
                @Nullable String callingAttributionTag) {
            ensureAllNetworkRequestsHaveType(r);
            mRequests = initializeRequests(r);
            mNetworkRequestForCallback = requestForCallback;
            mPendingIntent = pi;
            mMessenger = null;
            mBinder = null;
            mPid = getCallingPid();
            mUid = mDeps.getCallingUid();
            mNetworkRequestCounter.incrementCountOrThrow(mUid);
            mCallingAttributionTag = callingAttributionTag;
        }

        NetworkRequestInfo(@NonNull final NetworkRequest r, @Nullable final Messenger m,
                @Nullable final IBinder binder, @Nullable String callingAttributionTag) {
            this(Collections.singletonList(r), r, m, binder, callingAttributionTag);
        }

        NetworkRequestInfo(@NonNull final List<NetworkRequest> r,
                @NonNull final NetworkRequest requestForCallback, @Nullable final Messenger m,
                @Nullable final IBinder binder, @Nullable String callingAttributionTag) {
            super();
            ensureAllNetworkRequestsHaveType(r);
            mRequests = initializeRequests(r);
            mNetworkRequestForCallback = requestForCallback;
            mMessenger = m;
            mBinder = binder;
            mPid = getCallingPid();
            mUid = mDeps.getCallingUid();
            mPendingIntent = null;
            mNetworkRequestCounter.incrementCountOrThrow(mUid);
            mCallingAttributionTag = callingAttributionTag;

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        NetworkRequestInfo(@NonNull final NetworkRequestInfo nri,
                @NonNull final List<NetworkRequest> r) {
            super();
            ensureAllNetworkRequestsHaveType(r);
            mRequests = initializeRequests(r);
            mNetworkRequestForCallback = nri.getNetworkRequestForCallback();
            mMessenger = nri.mMessenger;
            mBinder = nri.mBinder;
            mPid = nri.mPid;
            mUid = nri.mUid;
            mPendingIntent = nri.mPendingIntent;
            mCallingAttributionTag = nri.mCallingAttributionTag;
        }

        NetworkRequestInfo(@NonNull final NetworkRequest r) {
            this(Collections.singletonList(r));
        }

        NetworkRequestInfo(@NonNull final List<NetworkRequest> r) {
            this(r, r.get(0), null /* pi */, null /* callingAttributionTag */);
        }

        // True if this NRI is being satisfied. It also accounts for if the nri has its satisifer
        // set to the mNoServiceNetwork in which case mActiveRequest will be null thus returning
        // false.
        boolean isBeingSatisfied() {
            return (null != mSatisfier && null != mActiveRequest);
        }

        boolean isMultilayerRequest() {
            return mRequests.size() > 1;
        }

        private List<NetworkRequest> initializeRequests(List<NetworkRequest> r) {
            // Creating a defensive copy to prevent the sender from modifying the list being
            // reflected in the return value of this method.
            final List<NetworkRequest> tempRequests = new ArrayList<>(r);
            return Collections.unmodifiableList(tempRequests);
        }

        void unlinkDeathRecipient() {
            if (mBinder != null) {
                mBinder.unlinkToDeath(this, 0);
            }
        }

        @Override
        public void binderDied() {
            log("ConnectivityService NetworkRequestInfo binderDied(" +
                    mRequests + ", " + mBinder + ")");
            releaseNetworkRequests(mRequests);
        }

        @Override
        public String toString() {
            return "uid/pid:" + mUid + "/" + mPid + " active request Id: "
                    + (mActiveRequest == null ? null : mActiveRequest.requestId)
                    + " " + mRequests
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
        if (null != nc.getSsid() && !checkSettingsPermission(callerPid, callerUid)) {
            throw new SecurityException("Insufficient permissions to request a specific SSID");
        }

        if (nc.hasSignalStrength()
                && !checkNetworkSignalStrengthWakeupPermission(callerPid, callerUid)) {
            throw new SecurityException(
                    "Insufficient permissions to request a specific signal strength");
        }
        mAppOpsManager.checkPackage(callerUid, callerPackageName);
    }

    private int[] getSignalStrengthThresholds(@NonNull final NetworkAgentInfo nai) {
        final SortedSet<Integer> thresholds = new TreeSet<>();
        synchronized (nai) {
            // mNetworkRequests may contain the same value multiple times in case of
            // multilayer requests. It won't matter in this case because the thresholds
            // will then be the same and be deduplicated as they enter the `thresholds` set.
            // TODO : have mNetworkRequests be a Set<NetworkRequestInfo> or the like.
            for (final NetworkRequestInfo nri : mNetworkRequests.values()) {
                for (final NetworkRequest req : nri.mRequests) {
                    if (req.networkCapabilities.hasSignalStrength()
                            && nai.satisfiesImmutableCapabilitiesOf(req)) {
                        thresholds.add(req.networkCapabilities.getSignalStrength());
                    }
                }
            }
        }
        return CollectionUtils.toIntArray(new ArrayList<>(thresholds));
    }

    private void updateSignalStrengthThresholds(
            NetworkAgentInfo nai, String reason, NetworkRequest request) {
        final int[] thresholdsArray = getSignalStrengthThresholds(nai);

        if (VDBG || (DBG && !"CONNECT".equals(reason))) {
            String detail;
            if (request != null && request.networkCapabilities.hasSignalStrength()) {
                detail = reason + " " + request.networkCapabilities.getSignalStrength();
            } else {
                detail = reason;
            }
            log(String.format("updateSignalStrengthThresholds: %s, sending %s to %s",
                    detail, Arrays.toString(thresholdsArray), nai.toShortString()));
        }

        nai.onSignalStrengthThresholdsUpdated(thresholdsArray);
    }

    private void ensureValidNetworkSpecifier(NetworkCapabilities nc) {
        if (nc == null) {
            return;
        }
        NetworkSpecifier ns = nc.getNetworkSpecifier();
        if (ns == null) {
            return;
        }
        if (ns instanceof MatchAllNetworkSpecifier) {
            throw new IllegalArgumentException("A MatchAllNetworkSpecifier is not permitted");
        }
    }

    private void ensureValid(NetworkCapabilities nc) {
        ensureValidNetworkSpecifier(nc);
        if (nc.isPrivateDnsBroken()) {
            throw new IllegalArgumentException("Can't request broken private DNS");
        }
    }

    private boolean checkUnsupportedStartingFrom(int version, String callingPackageName) {
        final UserHandle user = UserHandle.getUserHandleForUid(mDeps.getCallingUid());
        final PackageManager pm =
                mContext.createContextAsUser(user, 0 /* flags */).getPackageManager();
        try {
            final int callingVersion = pm.getApplicationInfo(
                    callingPackageName, 0 /* flags */).targetSdkVersion;
            if (callingVersion < version) return false;
        } catch (PackageManager.NameNotFoundException e) { }
        return true;
    }

    @Override
    public NetworkRequest requestNetwork(NetworkCapabilities networkCapabilities,
            int reqTypeInt, Messenger messenger, int timeoutMs, IBinder binder,
            int legacyType, @NonNull String callingPackageName,
            @Nullable String callingAttributionTag) {
        if (legacyType != TYPE_NONE && !checkNetworkStackPermission()) {
            if (checkUnsupportedStartingFrom(Build.VERSION_CODES.M, callingPackageName)) {
                throw new SecurityException("Insufficient permissions to specify legacy type");
            }
        }
        final NetworkCapabilities defaultNc = mDefaultRequest.mRequests.get(0).networkCapabilities;
        final int callingUid = mDeps.getCallingUid();
        final NetworkRequest.Type reqType;
        try {
            reqType = NetworkRequest.Type.values()[reqTypeInt];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Unsupported request type " + reqTypeInt);
        }
        switch (reqType) {
            case TRACK_DEFAULT:
                // If the request type is TRACK_DEFAULT, the passed {@code networkCapabilities}
                // is unused and will be replaced by ones appropriate for the caller.
                // This allows callers to keep track of the default network for their app.
                networkCapabilities = copyDefaultNetworkCapabilitiesForUid(
                        defaultNc, callingUid, callingPackageName);
                enforceAccessPermission();
                break;
            case TRACK_SYSTEM_DEFAULT:
                enforceSettingsPermission();
                networkCapabilities = new NetworkCapabilities(defaultNc);
                break;
            case BACKGROUND_REQUEST:
                enforceNetworkStackOrSettingsPermission();
                // Fall-through since other checks are the same with normal requests.
            case REQUEST:
                networkCapabilities = new NetworkCapabilities(networkCapabilities);
                enforceNetworkRequestPermissions(networkCapabilities, callingPackageName,
                        callingAttributionTag);
                // TODO: this is incorrect. We mark the request as metered or not depending on
                //  the state of the app when the request is filed, but we never change the
                //  request if the app changes network state. http://b/29964605
                enforceMeteredApnPolicy(networkCapabilities);
                break;
            default:
                throw new IllegalArgumentException("Unsupported request type " + reqType);
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

        final NetworkRequest networkRequest = new NetworkRequest(networkCapabilities, legacyType,
                nextNetworkRequestId(), reqType);
        final NetworkRequestInfo nri = getNriToRegister(
                networkRequest, messenger, binder, callingAttributionTag);
        if (DBG) log("requestNetwork for " + nri);

        // For TRACK_SYSTEM_DEFAULT callbacks, the capabilities have been modified since they were
        // copied from the default request above. (This is necessary to ensure, for example, that
        // the callback does not leak sensitive information to unprivileged apps.) Check that the
        // changes don't alter request matching.
        if (reqType == NetworkRequest.Type.TRACK_SYSTEM_DEFAULT &&
                (!networkCapabilities.equalRequestableCapabilities(defaultNc))) {
            throw new IllegalStateException(
                    "TRACK_SYSTEM_DEFAULT capabilities don't match default request: "
                    + networkCapabilities + " vs. " + defaultNc);
        }

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_REQUEST, nri));
        if (timeoutMs > 0) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_TIMEOUT_NETWORK_REQUEST,
                    nri), timeoutMs);
        }
        return networkRequest;
    }

    /**
     * Return the nri to be used when registering a network request. Specifically, this is used with
     * requests registered to track the default request. If there is currently a per-app default
     * tracking the app requestor, then we need to create a version of this nri that mirrors that of
     * the tracking per-app default so that callbacks are sent to the app requestor appropriately.
     * @param nr the network request for the nri.
     * @param msgr the messenger for the nri.
     * @param binder the binder for the nri.
     * @param callingAttributionTag the calling attribution tag for the nri.
     * @return the nri to register.
     */
    private NetworkRequestInfo getNriToRegister(@NonNull final NetworkRequest nr,
            @Nullable final Messenger msgr, @Nullable final IBinder binder,
            @Nullable String callingAttributionTag) {
        final List<NetworkRequest> requests;
        if (NetworkRequest.Type.TRACK_DEFAULT == nr.type) {
            requests = copyDefaultNetworkRequestsForUid(
                    nr.getRequestorUid(), nr.getRequestorPackageName());
        } else {
            requests = Collections.singletonList(nr);
        }
        return new NetworkRequestInfo(requests, nr, msgr, binder, callingAttributionTag);
    }

    private void enforceNetworkRequestPermissions(NetworkCapabilities networkCapabilities,
            String callingPackageName, String callingAttributionTag) {
        if (networkCapabilities.hasCapability(NET_CAPABILITY_NOT_RESTRICTED) == false) {
            enforceConnectivityRestrictedNetworksPermission();
        } else {
            enforceChangePermission(callingPackageName, callingAttributionTag);
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
            nai = mNetworkForNetId.get(network.getNetId());
        }
        if (nai != null) {
            nai.onBandwidthUpdateRequested();
            synchronized (mBandwidthRequests) {
                final int uid = mDeps.getCallingUid();
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
        final int uid = mDeps.getCallingUid();
        if (isSystem(uid)) {
            // Exemption for system uid.
            return;
        }
        if (networkCapabilities.hasCapability(NET_CAPABILITY_NOT_METERED)) {
            // Policy already enforced.
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            if (mPolicyManager.isUidRestrictedOnMeteredNetworks(uid)) {
                // If UID is restricted, don't allow them to bring up metered APNs.
                networkCapabilities.addCapability(NET_CAPABILITY_NOT_METERED);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public NetworkRequest pendingRequestForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation, @NonNull String callingPackageName,
            @Nullable String callingAttributionTag) {
        Objects.requireNonNull(operation, "PendingIntent cannot be null.");
        final int callingUid = mDeps.getCallingUid();
        networkCapabilities = new NetworkCapabilities(networkCapabilities);
        enforceNetworkRequestPermissions(networkCapabilities, callingPackageName,
                callingAttributionTag);
        enforceMeteredApnPolicy(networkCapabilities);
        ensureRequestableCapabilities(networkCapabilities);
        ensureSufficientPermissionsForRequest(networkCapabilities,
                Binder.getCallingPid(), callingUid, callingPackageName);
        ensureValidNetworkSpecifier(networkCapabilities);
        restrictRequestUidsForCallerAndSetRequestorInfo(networkCapabilities,
                callingUid, callingPackageName);

        NetworkRequest networkRequest = new NetworkRequest(networkCapabilities, TYPE_NONE,
                nextNetworkRequestId(), NetworkRequest.Type.REQUEST);
        NetworkRequestInfo nri =
                new NetworkRequestInfo(networkRequest, operation, callingAttributionTag);
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
        Objects.requireNonNull(operation, "PendingIntent cannot be null.");
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
            Messenger messenger, IBinder binder, @NonNull String callingPackageName,
            @Nullable String callingAttributionTag) {
        final int callingUid = mDeps.getCallingUid();
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
        NetworkRequestInfo nri =
                new NetworkRequestInfo(networkRequest, messenger, binder, callingAttributionTag);
        if (VDBG) log("listenForNetwork for " + nri);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_LISTENER, nri));
        return networkRequest;
    }

    @Override
    public void pendingListenForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation, @NonNull String callingPackageName,
            @Nullable String callingAttributionTag) {
        Objects.requireNonNull(operation, "PendingIntent cannot be null.");
        final int callingUid = mDeps.getCallingUid();
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
        NetworkRequestInfo nri =
                new NetworkRequestInfo(networkRequest, operation, callingAttributionTag);
        if (VDBG) log("pendingListenForNetwork for " + nri);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_LISTENER, nri));
    }

    /** Returns the next Network provider ID. */
    public final int nextNetworkProviderId() {
        return mNextNetworkProviderId.getAndIncrement();
    }

    private void releaseNetworkRequests(List<NetworkRequest> networkRequests) {
        for (int i = 0; i < networkRequests.size(); i++) {
            releaseNetworkRequest(networkRequests.get(i));
        }
    }

    @Override
    public void releaseNetworkRequest(NetworkRequest networkRequest) {
        ensureNetworkRequestHasType(networkRequest);
        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_RELEASE_NETWORK_REQUEST, getCallingUid(), 0, networkRequest));
    }

    private void handleRegisterNetworkProvider(NetworkProviderInfo npi) {
        if (mNetworkProviderInfos.containsKey(npi.messenger)) {
            // Avoid creating duplicates. even if an app makes a direct AIDL call.
            // This will never happen if an app calls ConnectivityManager#registerNetworkProvider,
            // as that will throw if a duplicate provider is registered.
            loge("Attempt to register existing NetworkProviderInfo "
                    + mNetworkProviderInfos.get(npi.messenger).name);
            return;
        }

        if (DBG) log("Got NetworkProvider Messenger for " + npi.name);
        mNetworkProviderInfos.put(npi.messenger, npi);
        npi.connect(mContext, mTrackerHandler);
        sendAllRequestsToProvider(npi);
    }

    @Override
    public int registerNetworkProvider(Messenger messenger, String name) {
        enforceNetworkFactoryOrSettingsPermission();
        NetworkProviderInfo npi = new NetworkProviderInfo(name, messenger,
                null /* asyncChannel */, nextNetworkProviderId(),
                () -> unregisterNetworkProvider(messenger));
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_PROVIDER, npi));
        return npi.providerId;
    }

    @Override
    public void unregisterNetworkProvider(Messenger messenger) {
        enforceNetworkFactoryOrSettingsPermission();
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_UNREGISTER_NETWORK_PROVIDER, messenger));
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
    public void declareNetworkRequestUnfulfillable(@NonNull final NetworkRequest request) {
        if (request.hasTransport(TRANSPORT_TEST)) {
            enforceNetworkFactoryOrTestNetworksPermission();
        } else {
            enforceNetworkFactoryPermission();
        }
        final NetworkRequestInfo nri = mNetworkRequests.get(request);
        if (nri != null) {
            // declareNetworkRequestUnfulfillable() paths don't apply to multilayer requests.
            ensureNotMultilayerRequest(nri, "declareNetworkRequestUnfulfillable");
            mHandler.post(() -> handleReleaseNetworkRequest(
                    nri.mRequests.get(0), mDeps.getCallingUid(), true));
        }
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
    private final ArraySet<NetworkAgentInfo> mNetworkAgentInfos = new ArraySet<>();

    // UID ranges for users that are currently blocked by VPNs.
    // This array is accessed and iterated on multiple threads without holding locks, so its
    // contents must never be mutated. When the ranges change, the array is replaced with a new one
    // (on the handler thread).
    private volatile List<UidRange> mVpnBlockedUidRanges = new ArrayList<>();

    @GuardedBy("mBlockedAppUids")
    private final HashSet<Integer> mBlockedAppUids = new HashSet<>();

    // Current OEM network preferences.
    @NonNull
    private OemNetworkPreferences mOemNetworkPreferences =
            new OemNetworkPreferences.Builder().build();

    // The always-on request for an Internet-capable network that apps without a specific default
    // fall back to.
    @VisibleForTesting
    @NonNull
    final NetworkRequestInfo mDefaultRequest;
    // Collection of NetworkRequestInfo's used for default networks.
    @VisibleForTesting
    @NonNull
    final ArraySet<NetworkRequestInfo> mDefaultNetworkRequests = new ArraySet<>();

    private boolean isPerAppDefaultRequest(@NonNull final NetworkRequestInfo nri) {
        return (mDefaultNetworkRequests.contains(nri) && mDefaultRequest != nri);
    }

    /**
     * Return the default network request currently tracking the given uid.
     * @param uid the uid to check.
     * @return the NetworkRequestInfo tracking the given uid.
     */
    @NonNull
    private NetworkRequestInfo getDefaultRequestTrackingUid(@NonNull final int uid) {
        for (final NetworkRequestInfo nri : mDefaultNetworkRequests) {
            if (nri == mDefaultRequest) {
                continue;
            }
            // Checking the first request is sufficient as only multilayer requests will have more
            // than one request and for multilayer, all requests will track the same uids.
            if (nri.mRequests.get(0).networkCapabilities.appliesToUid(uid)) {
                return nri;
            }
        }
        return mDefaultRequest;
    }

    /**
     * Get a copy of the network requests of the default request that is currently tracking the
     * given uid.
     * @param requestorUid the uid to check the default for.
     * @param requestorPackageName the requestor's package name.
     * @return a copy of the default's NetworkRequest that is tracking the given uid.
     */
    @NonNull
    private List<NetworkRequest> copyDefaultNetworkRequestsForUid(
            @NonNull final int requestorUid, @NonNull final String requestorPackageName) {
        return copyNetworkRequestsForUid(
                getDefaultRequestTrackingUid(requestorUid).mRequests,
                requestorUid, requestorPackageName);
    }

    /**
     * Copy the given nri's NetworkRequest collection.
     * @param requestsToCopy the NetworkRequest collection to be copied.
     * @param requestorUid the uid to set on the copied collection.
     * @param requestorPackageName the package name to set on the copied collection.
     * @return the copied NetworkRequest collection.
     */
    @NonNull
    private List<NetworkRequest> copyNetworkRequestsForUid(
            @NonNull final List<NetworkRequest> requestsToCopy, @NonNull final int requestorUid,
            @NonNull final String requestorPackageName) {
        final List<NetworkRequest> requests = new ArrayList<>();
        for (final NetworkRequest nr : requestsToCopy) {
            requests.add(new NetworkRequest(copyDefaultNetworkCapabilitiesForUid(
                            nr.networkCapabilities, requestorUid, requestorPackageName),
                    nr.legacyType, nextNetworkRequestId(), nr.type));
        }
        return requests;
    }

    @NonNull
    private NetworkCapabilities copyDefaultNetworkCapabilitiesForUid(
            @NonNull final NetworkCapabilities netCapToCopy, @NonNull final int requestorUid,
            @NonNull final String requestorPackageName) {
        final NetworkCapabilities netCap = new NetworkCapabilities(netCapToCopy);
        netCap.removeCapability(NET_CAPABILITY_NOT_VPN);
        netCap.setSingleUid(requestorUid);
        netCap.setUids(new ArraySet<>());
        restrictRequestUidsForCallerAndSetRequestorInfo(
                netCap, requestorUid, requestorPackageName);
        return netCap;
    }

    /**
     * Get the nri that is currently being tracked for callbacks by per-app defaults.
     * @param nr the network request to check for equality against.
     * @return the nri if one exists, null otherwise.
     */
    @Nullable
    private NetworkRequestInfo getNriForAppRequest(@NonNull final NetworkRequest nr) {
        for (final NetworkRequestInfo nri : mNetworkRequests.values()) {
            if (nri.getNetworkRequestForCallback().equals(nr)) {
                return nri;
            }
        }
        return null;
    }

    /**
     * Check if an nri is currently being managed by per-app default networking.
     * @param nri the nri to check.
     * @return true if this nri is currently being managed by per-app default networking.
     */
    private boolean isPerAppTrackedNri(@NonNull final NetworkRequestInfo nri) {
        // nri.mRequests.get(0) is only different from the original request filed in
        // nri.getNetworkRequestForCallback() if nri.mRequests was changed by per-app default
        // functionality therefore if these two don't match, it means this particular nri is
        // currently being managed by a per-app default.
        return nri.getNetworkRequestForCallback() != nri.mRequests.get(0);
    }

    /**
     * Determine if an nri is a managed default request that disallows default networking.
     * @param nri the request to evaluate
     * @return true if device-default networking is disallowed
     */
    private boolean isDefaultBlocked(@NonNull final NetworkRequestInfo nri) {
        // Check if this nri is a managed default that supports the default network at its
        // lowest priority request.
        final NetworkRequest defaultNetworkRequest = mDefaultRequest.mRequests.get(0);
        final NetworkCapabilities lowestPriorityNetCap =
                nri.mRequests.get(nri.mRequests.size() - 1).networkCapabilities;
        return isPerAppDefaultRequest(nri)
                && !(defaultNetworkRequest.networkCapabilities.equalRequestableCapabilities(
                        lowestPriorityNetCap));
    }

    // Request used to optionally keep mobile data active even when higher
    // priority networks like Wi-Fi are active.
    private final NetworkRequest mDefaultMobileDataRequest;

    // Request used to optionally keep wifi data active even when higher
    // priority networks like ethernet are active.
    private final NetworkRequest mDefaultWifiRequest;

    // Request used to optionally keep vehicle internal network always active
    private final NetworkRequest mDefaultVehicleRequest;

    // TODO replace with INetd.DUMMY_NET_ID when available.
    private static final int NO_SERVICE_NET_ID = 51;
    // Sentinel NAI used to direct apps with default networks that should have no connectivity to a
    // network with no service. This NAI should never be matched against, nor should any public API
    // ever return the associated network. For this reason, this NAI is not in the list of available
    // NAIs. It is used in computeNetworkReassignment() to be set as the satisfier for non-device
    // default requests that don't support using the device default network which will ultimately
    // allow ConnectivityService to use this no-service network when calling makeDefaultForApps().
    @VisibleForTesting
    final NetworkAgentInfo mNoServiceNetwork;

    // The NetworkAgentInfo currently satisfying the default request, if any.
    private NetworkAgentInfo getDefaultNetwork() {
        return mDefaultRequest.mSatisfier;
    }

    private NetworkAgentInfo getDefaultNetworkForUid(final int uid) {
        for (final NetworkRequestInfo nri : mDefaultNetworkRequests) {
            // Currently, all network requests will have the same uids therefore checking the first
            // one is sufficient. If/when uids are tracked at the nri level, this can change.
            final Set<UidRange> uids = nri.mRequests.get(0).networkCapabilities.getUids();
            if (null == uids) {
                continue;
            }
            for (final UidRange range : uids) {
                if (range.contains(uid)) {
                    return nri.getSatisfier();
                }
            }
        }
        return getDefaultNetwork();
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

    // TODO : remove this method. It's a stopgap measure to help sheperding a number of dependent
    // changes that would conflict throughout the automerger graph. Having this method temporarily
    // helps with the process of going through with all these dependent changes across the entire
    // tree.
    /**
     * Register a new agent. {@see #registerNetworkAgent} below.
     */
    public Network registerNetworkAgent(INetworkAgent na, NetworkInfo networkInfo,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int currentScore, NetworkAgentConfig networkAgentConfig) {
        return registerNetworkAgent(na, networkInfo, linkProperties, networkCapabilities,
                currentScore, networkAgentConfig, NetworkProvider.ID_NONE);
    }

    /**
     * Register a new agent with ConnectivityService to handle a network.
     *
     * @param na a reference for ConnectivityService to contact the agent asynchronously.
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
    public Network registerNetworkAgent(INetworkAgent na, NetworkInfo networkInfo,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int currentScore, NetworkAgentConfig networkAgentConfig, int providerId) {
        Objects.requireNonNull(networkInfo, "networkInfo must not be null");
        Objects.requireNonNull(linkProperties, "linkProperties must not be null");
        Objects.requireNonNull(networkCapabilities, "networkCapabilities must not be null");
        Objects.requireNonNull(networkAgentConfig, "networkAgentConfig must not be null");
        if (networkCapabilities.hasTransport(TRANSPORT_TEST)) {
            enforceAnyPermissionOf(Manifest.permission.MANAGE_TEST_NETWORKS);
        } else {
            enforceNetworkFactoryPermission();
        }

        final int uid = mDeps.getCallingUid();
        final long token = Binder.clearCallingIdentity();
        try {
            return registerNetworkAgentInternal(na, networkInfo, linkProperties,
                    networkCapabilities, currentScore, networkAgentConfig, providerId, uid);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private Network registerNetworkAgentInternal(INetworkAgent na, NetworkInfo networkInfo,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int currentScore, NetworkAgentConfig networkAgentConfig, int providerId, int uid) {
        if (networkCapabilities.hasTransport(TRANSPORT_TEST)) {
            // Strictly, sanitizing here is unnecessary as the capabilities will be sanitized in
            // the call to mixInCapabilities below anyway, but sanitizing here means the NAI never
            // sees capabilities that may be malicious, which might prevent mistakes in the future.
            networkCapabilities = new NetworkCapabilities(networkCapabilities);
            networkCapabilities.restrictCapabilitesForTestNetwork(uid);
        }

        LinkProperties lp = new LinkProperties(linkProperties);

        final NetworkCapabilities nc = new NetworkCapabilities(networkCapabilities);
        final NetworkAgentInfo nai = new NetworkAgentInfo(na,
                new Network(mNetIdManager.reserveNetId()), new NetworkInfo(networkInfo), lp, nc,
                currentScore, mContext, mTrackerHandler, new NetworkAgentConfig(networkAgentConfig),
                this, mNetd, mDnsResolver, providerId, uid, mQosCallbackTracker);

        // Make sure the LinkProperties and NetworkCapabilities reflect what the agent info says.
        processCapabilitiesFromAgent(nai, nc);
        nai.getAndSetNetworkCapabilities(mixInCapabilities(nai, nc));
        processLinkPropertiesFromAgent(nai, nai.linkProperties);

        final String extraInfo = networkInfo.getExtraInfo();
        final String name = TextUtils.isEmpty(extraInfo)
                ? nai.networkCapabilities.getSsid() : extraInfo;
        if (DBG) log("registerNetworkAgent " + nai);
        mDeps.getNetworkStack().makeNetworkMonitor(
                nai.network, name, new NetworkMonitorCallbacks(nai));
        // NetworkAgentInfo registration will finish when the NetworkMonitor is created.
        // If the network disconnects or sends any other event before that, messages are deferred by
        // NetworkAgent until nai.connect(), which will be called when finalizing the
        // registration.
        return nai.network;
    }

    private void handleRegisterNetworkAgent(NetworkAgentInfo nai, INetworkMonitor networkMonitor) {
        nai.onNetworkMonitorCreated(networkMonitor);
        if (VDBG) log("Got NetworkAgent Messenger");
        mNetworkAgentInfos.add(nai);
        synchronized (mNetworkForNetId) {
            mNetworkForNetId.put(nai.network.getNetId(), nai);
        }

        try {
            networkMonitor.start();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        nai.notifyRegistered();
        NetworkInfo networkInfo = nai.networkInfo;
        updateNetworkInfo(nai, networkInfo);
        updateUids(nai, null, nai.networkCapabilities);
    }

    /**
     * Called when receiving LinkProperties directly from a NetworkAgent.
     * Stores into |nai| any data coming from the agent that might also be written to the network's
     * LinkProperties by ConnectivityService itself. This ensures that the data provided by the
     * agent is not lost when updateLinkProperties is called.
     * This method should never alter the agent's LinkProperties, only store data in |nai|.
     */
    private void processLinkPropertiesFromAgent(NetworkAgentInfo nai, LinkProperties lp) {
        lp.ensureDirectlyConnectedRoutes();
        nai.clatd.setNat64PrefixFromRa(lp.getNat64Prefix());
        nai.networkAgentPortalData = lp.getCaptivePortalData();
    }

    private void updateLinkProperties(NetworkAgentInfo networkAgent, @NonNull LinkProperties newLp,
            @NonNull LinkProperties oldLp) {
        int netId = networkAgent.network.getNetId();

        // The NetworkAgent does not know whether clatd is running on its network or not, or whether
        // a NAT64 prefix was discovered by the DNS resolver. Before we do anything else, make sure
        // the LinkProperties for the network are accurate.
        networkAgent.clatd.fixupLinkProperties(oldLp, newLp);

        updateInterfaces(newLp, oldLp, netId, networkAgent.networkCapabilities);

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

        // Captive portal data is obtained from NetworkMonitor and stored in NetworkAgentInfo.
        // It is not always contained in the LinkProperties sent from NetworkAgents, and if it
        // does, it needs to be merged here.
        newLp.setCaptivePortalData(mergeCaptivePortalData(networkAgent.networkAgentPortalData,
                networkAgent.capportApiData));

        // TODO - move this check to cover the whole function
        if (!Objects.equals(newLp, oldLp)) {
            synchronized (networkAgent) {
                networkAgent.linkProperties = newLp;
            }
            // Start or stop DNS64 detection and 464xlat according to network state.
            networkAgent.clatd.update();
            notifyIfacesChangedForNetworkStats();
            networkAgent.networkMonitor().notifyLinkPropertiesChanged(
                    new LinkProperties(newLp, true /* parcelSensitiveFields */));
            if (networkAgent.everConnected) {
                notifyNetworkCallbacks(networkAgent, ConnectivityManager.CALLBACK_IP_CHANGED);
            }
        }

        mKeepaliveTracker.handleCheckKeepalivesStillValid(networkAgent);
    }

    /**
     * @param naData captive portal data from NetworkAgent
     * @param apiData captive portal data from capport API
     */
    @Nullable
    private CaptivePortalData mergeCaptivePortalData(CaptivePortalData naData,
            CaptivePortalData apiData) {
        if (naData == null || apiData == null) {
            return naData == null ? apiData : naData;
        }
        final CaptivePortalData.Builder captivePortalBuilder =
                new CaptivePortalData.Builder(naData);

        if (apiData.isCaptive()) {
            captivePortalBuilder.setCaptive(true);
        }
        if (apiData.isSessionExtendable()) {
            captivePortalBuilder.setSessionExtendable(true);
        }
        if (apiData.getExpiryTimeMillis() >= 0 || apiData.getByteLimit() >= 0) {
            // Expiry time, bytes remaining, refresh time all need to come from the same source,
            // otherwise data would be inconsistent. Prefer the capport API info if present,
            // as it can generally be refreshed more often.
            captivePortalBuilder.setExpiryTime(apiData.getExpiryTimeMillis());
            captivePortalBuilder.setBytesRemaining(apiData.getByteLimit());
            captivePortalBuilder.setRefreshTime(apiData.getRefreshTimeMillis());
        } else if (naData.getExpiryTimeMillis() < 0 && naData.getByteLimit() < 0) {
            // No source has time / bytes remaining information: surface the newest refresh time
            // for other fields
            captivePortalBuilder.setRefreshTime(
                    Math.max(naData.getRefreshTimeMillis(), apiData.getRefreshTimeMillis()));
        }

        // Prioritize the user portal URL from the network agent if the source is authenticated.
        if (apiData.getUserPortalUrl() != null && naData.getUserPortalUrlSource()
                != CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT) {
            captivePortalBuilder.setUserPortalUrl(apiData.getUserPortalUrl(),
                    apiData.getUserPortalUrlSource());
        }
        // Prioritize the venue information URL from the network agent if the source is
        // authenticated.
        if (apiData.getVenueInfoUrl() != null && naData.getVenueInfoUrlSource()
                != CaptivePortalData.CAPTIVE_PORTAL_DATA_SOURCE_PASSPOINT) {
            captivePortalBuilder.setVenueInfoUrl(apiData.getVenueInfoUrl(),
                    apiData.getVenueInfoUrlSource());
        }
        return captivePortalBuilder.build();
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
            final @NonNull NetworkCapabilities caps) {
        final CompareResult<String> interfaceDiff = new CompareResult<>(
                oldLp != null ? oldLp.getAllInterfaceNames() : null,
                newLp != null ? newLp.getAllInterfaceNames() : null);
        if (!interfaceDiff.added.isEmpty()) {
            final IBatteryStats bs = mDeps.getBatteryStatsService();
            for (final String iface : interfaceDiff.added) {
                try {
                    if (DBG) log("Adding iface " + iface + " to network " + netId);
                    mNetd.networkAddInterface(netId, iface);
                    wakeupModifyInterface(iface, caps, true);
                    bs.noteNetworkInterfaceForTransports(iface, caps.getTransportTypes());
                } catch (Exception e) {
                    loge("Exception adding interface: " + e);
                }
            }
        }
        for (final String iface : interfaceDiff.removed) {
            try {
                if (DBG) log("Removing iface " + iface + " from network " + netId);
                wakeupModifyInterface(iface, caps, false);
                mNetd.networkRemoveInterface(netId, iface);
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
        // compare the route diff to determine which routes have been updated
        final CompareOrUpdateResult<RouteInfo.RouteKey, RouteInfo> routeDiff =
                new CompareOrUpdateResult<>(
                        oldLp != null ? oldLp.getAllRoutes() : null,
                        newLp != null ? newLp.getAllRoutes() : null,
                        (r) -> r.getRouteKey());

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

        if (DBG) {
            final Collection<InetAddress> dnses = newLp.getDnsServers();
            log("Setting DNS servers for network " + netId + " to " + dnses);
        }
        try {
            mDnsManager.noteDnsServersForNetwork(netId, newLp);
            mDnsManager.flushVmDnsCache();
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
        // make eBPF support two allowlisted interfaces so here new rules can be added before the
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
                mNetd.networkSetPermissionForNetwork(nai.network.getNetId(), newPermission);
            } catch (RemoteException | ServiceSpecificException e) {
                loge("Exception in networkSetPermissionForNetwork: " + e);
            }
        }
    }

    /**
     * Called when receiving NetworkCapabilities directly from a NetworkAgent.
     * Stores into |nai| any data coming from the agent that might also be written to the network's
     * NetworkCapabilities by ConnectivityService itself. This ensures that the data provided by the
     * agent is not lost when updateCapabilities is called.
     * This method should never alter the agent's NetworkCapabilities, only store data in |nai|.
     */
    private void processCapabilitiesFromAgent(NetworkAgentInfo nai, NetworkCapabilities nc) {
        // Note: resetting the owner UID before storing the agent capabilities in NAI means that if
        // the agent attempts to change the owner UID, then nai.declaredCapabilities will not
        // actually be the same as the capabilities sent by the agent. Still, it is safer to reset
        // the owner UID here and behave as if the agent had never tried to change it.
        if (nai.networkCapabilities.getOwnerUid() != nc.getOwnerUid()) {
            Log.e(TAG, nai.toShortString() + ": ignoring attempt to change owner from "
                    + nai.networkCapabilities.getOwnerUid() + " to " + nc.getOwnerUid());
            nc.setOwnerUid(nai.networkCapabilities.getOwnerUid());
        }
        nai.declaredCapabilities = new NetworkCapabilities(nc);
    }

    /** Modifies |newNc| based on the capabilities of |underlyingNetworks| and |agentCaps|. */
    @VisibleForTesting
    void applyUnderlyingCapabilities(@Nullable Network[] underlyingNetworks,
            @NonNull NetworkCapabilities agentCaps, @NonNull NetworkCapabilities newNc) {
        underlyingNetworks = underlyingNetworksOrDefault(
                agentCaps.getOwnerUid(), underlyingNetworks);
        long transportTypes = BitUtils.packBits(agentCaps.getTransportTypes());
        int downKbps = NetworkCapabilities.LINK_BANDWIDTH_UNSPECIFIED;
        int upKbps = NetworkCapabilities.LINK_BANDWIDTH_UNSPECIFIED;
        // metered if any underlying is metered, or originally declared metered by the agent.
        boolean metered = !agentCaps.hasCapability(NET_CAPABILITY_NOT_METERED);
        boolean roaming = false; // roaming if any underlying is roaming
        boolean congested = false; // congested if any underlying is congested
        boolean suspended = true; // suspended if all underlying are suspended

        boolean hadUnderlyingNetworks = false;
        if (null != underlyingNetworks) {
            for (Network underlyingNetwork : underlyingNetworks) {
                final NetworkAgentInfo underlying =
                        getNetworkAgentInfoForNetwork(underlyingNetwork);
                if (underlying == null) continue;

                final NetworkCapabilities underlyingCaps = underlying.networkCapabilities;
                hadUnderlyingNetworks = true;
                for (int underlyingType : underlyingCaps.getTransportTypes()) {
                    transportTypes |= 1L << underlyingType;
                }

                // Merge capabilities of this underlying network. For bandwidth, assume the
                // worst case.
                downKbps = NetworkCapabilities.minBandwidth(downKbps,
                        underlyingCaps.getLinkDownstreamBandwidthKbps());
                upKbps = NetworkCapabilities.minBandwidth(upKbps,
                        underlyingCaps.getLinkUpstreamBandwidthKbps());
                // If this underlying network is metered, the VPN is metered (it may cost money
                // to send packets on this network).
                metered |= !underlyingCaps.hasCapability(NET_CAPABILITY_NOT_METERED);
                // If this underlying network is roaming, the VPN is roaming (the billing structure
                // is different than the usual, local one).
                roaming |= !underlyingCaps.hasCapability(NET_CAPABILITY_NOT_ROAMING);
                // If this underlying network is congested, the VPN is congested (the current
                // condition of the network affects the performance of this network).
                congested |= !underlyingCaps.hasCapability(NET_CAPABILITY_NOT_CONGESTED);
                // If this network is not suspended, the VPN is not suspended (the VPN
                // is able to transfer some data).
                suspended &= !underlyingCaps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED);
            }
        }
        if (!hadUnderlyingNetworks) {
            // No idea what the underlying networks are; assume reasonable defaults
            metered = true;
            roaming = false;
            congested = false;
            suspended = false;
        }

        newNc.setTransportTypes(BitUtils.unpackBits(transportTypes));
        newNc.setLinkDownstreamBandwidthKbps(downKbps);
        newNc.setLinkUpstreamBandwidthKbps(upKbps);
        newNc.setCapability(NET_CAPABILITY_NOT_METERED, !metered);
        newNc.setCapability(NET_CAPABILITY_NOT_ROAMING, !roaming);
        newNc.setCapability(NET_CAPABILITY_NOT_CONGESTED, !congested);
        newNc.setCapability(NET_CAPABILITY_NOT_SUSPENDED, !suspended);
    }

    /**
     * Augments the NetworkCapabilities passed in by a NetworkAgent with capabilities that are
     * maintained here that the NetworkAgent is not aware of (e.g., validated, captive portal,
     * and foreground status).
     */
    @NonNull
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
                Log.wtf(TAG, "BUG: " + nai + " lost immutable capabilities:" + diff);
            }
        }

        // Don't modify caller's NetworkCapabilities.
        final NetworkCapabilities newNc = new NetworkCapabilities(nc);
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

        if (nai.supportsUnderlyingNetworks()) {
            applyUnderlyingCapabilities(nai.declaredUnderlyingNetworks, nai.declaredCapabilities,
                    newNc);
        }

        return newNc;
    }

    private void updateNetworkInfoForRoamingAndSuspended(NetworkAgentInfo nai,
            NetworkCapabilities prevNc, NetworkCapabilities newNc) {
        final boolean prevSuspended = !prevNc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED);
        final boolean suspended = !newNc.hasCapability(NET_CAPABILITY_NOT_SUSPENDED);
        final boolean prevRoaming = !prevNc.hasCapability(NET_CAPABILITY_NOT_ROAMING);
        final boolean roaming = !newNc.hasCapability(NET_CAPABILITY_NOT_ROAMING);
        if (prevSuspended != suspended) {
            // TODO (b/73132094) : remove this call once the few users of onSuspended and
            // onResumed have been removed.
            notifyNetworkCallbacks(nai, suspended ? ConnectivityManager.CALLBACK_SUSPENDED
                    : ConnectivityManager.CALLBACK_RESUMED);
        }
        if (prevSuspended != suspended || prevRoaming != roaming) {
            // updateNetworkInfo will mix in the suspended info from the capabilities and
            // take appropriate action for the network having possibly changed state.
            updateNetworkInfo(nai, nai.networkInfo);
        }
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
        } else {
            // If the requestable capabilities have changed or the score changed, we can't have been
            // called by rematchNetworkAndRequests, so it's safe to start a rematch.
            rematchAllNetworksAndRequests();
            notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_CAP_CHANGED);
        }
        updateNetworkInfoForRoamingAndSuspended(nai, prevNc, newNc);

        final boolean oldMetered = prevNc.isMetered();
        final boolean newMetered = newNc.isMetered();
        final boolean meteredChanged = oldMetered != newMetered;

        if (meteredChanged) {
            maybeNotifyNetworkBlocked(nai, oldMetered, newMetered, mRestrictBackground,
                    mRestrictBackground, mVpnBlockedUidRanges, mVpnBlockedUidRanges);
        }

        final boolean roamingChanged = prevNc.hasCapability(NET_CAPABILITY_NOT_ROAMING)
                != newNc.hasCapability(NET_CAPABILITY_NOT_ROAMING);

        // Report changes that are interesting for network statistics tracking.
        if (meteredChanged || roamingChanged) {
            notifyIfacesChangedForNetworkStats();
        }

        // This network might have been underlying another network. Propagate its capabilities.
        propagateUnderlyingNetworkCapabilities(nai.network);

        if (!newNc.equalsTransportTypes(prevNc)) {
            mDnsManager.updateTransportsForNetwork(
                    nai.network.getNetId(), newNc.getTransportTypes());
        }
    }

    /** Convenience method to update the capabilities for a given network. */
    private void updateCapabilitiesForNetwork(NetworkAgentInfo nai) {
        updateCapabilities(nai.getCurrentScore(), nai, nai.networkCapabilities);
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
                && (lp.hasIpv4DefaultRoute() || lp.hasIpv4UnreachableDefaultRoute())
                && (lp.hasIpv6DefaultRoute() || lp.hasIpv6UnreachableDefaultRoute());
    }

    private static UidRangeParcel[] toUidRangeStableParcels(final @NonNull Set<UidRange> ranges) {
        final UidRangeParcel[] stableRanges = new UidRangeParcel[ranges.size()];
        int index = 0;
        for (UidRange range : ranges) {
            stableRanges[index] = new UidRangeParcel(range.start, range.stop);
            index++;
        }
        return stableRanges;
    }

    private static UidRangeParcel[] toUidRangeStableParcels(UidRange[] ranges) {
        final UidRangeParcel[] stableRanges = new UidRangeParcel[ranges.length];
        for (int i = 0; i < ranges.length; i++) {
            stableRanges[i] = new UidRangeParcel(ranges[i].start, ranges[i].stop);
        }
        return stableRanges;
    }

    private void maybeCloseSockets(NetworkAgentInfo nai, UidRangeParcel[] ranges,
            int[] exemptUids) {
        if (nai.isVPN() && !nai.networkAgentConfig.allowBypass) {
            try {
                mNetd.socketDestroy(ranges, exemptUids);
            } catch (Exception e) {
                loge("Exception in socket destroy: ", e);
            }
        }
    }

    private void updateUidRanges(boolean add, NetworkAgentInfo nai, Set<UidRange> uidRanges) {
        int[] exemptUids = new int[2];
        // TODO: Excluding VPN_UID is necessary in order to not to kill the TCP connection used
        // by PPTP. Fix this by making Vpn set the owner UID to VPN_UID instead of system when
        // starting a legacy VPN, and remove VPN_UID here. (b/176542831)
        exemptUids[0] = VPN_UID;
        exemptUids[1] = nai.networkCapabilities.getOwnerUid();
        UidRangeParcel[] ranges = toUidRangeStableParcels(uidRanges);

        maybeCloseSockets(nai, ranges, exemptUids);
        try {
            if (add) {
                mNetd.networkAddUidRanges(nai.network.netId, ranges);
            } else {
                mNetd.networkRemoveUidRanges(nai.network.netId, ranges);
            }
        } catch (Exception e) {
            loge("Exception while " + (add ? "adding" : "removing") + " uid ranges " + uidRanges +
                    " on netId " + nai.network.netId + ". " + e);
        }
        maybeCloseSockets(nai, ranges, exemptUids);
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
            // TODO: calculate the intersection of add & remove. Imagining that we are trying to
            // remove uid 3 from a set containing 1-5. Intersection of the prev and new sets is:
            //   [1-5] & [1-2],[4-5] == [3]
            // Then we can do:
            //   maybeCloseSockets([3])
            //   mNetd.networkAddUidRanges([1-2],[4-5])
            //   mNetd.networkRemoveUidRanges([1-5])
            //   maybeCloseSockets([3])
            // This can prevent the sockets of uid 1-2, 4-5 from being closed. It also reduce the
            // number of binder calls from 6 to 4.
            if (!newRanges.isEmpty()) {
                updateUidRanges(true, nai, newRanges);
            }
            if (!prevRanges.isEmpty()) {
                updateUidRanges(false, nai, prevRanges);
            }
            final boolean wasFiltering = requiresVpnIsolation(nai, prevNc, nai.linkProperties);
            final boolean shouldFilter = requiresVpnIsolation(nai, newNc, nai.linkProperties);
            final String iface = nai.linkProperties.getInterfaceName();
            // For VPN uid interface filtering, old ranges need to be removed before new ranges can
            // be added, due to the range being expanded and stored as individual UIDs. For example
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

        if (getNetworkAgentInfoForNetId(nai.network.getNetId()) != nai) {
            // Ignore updates for disconnected networks
            return;
        }
        if (VDBG || DDBG) {
            log("Update of LinkProperties for " + nai.toShortString()
                    + "; created=" + nai.created
                    + "; everConnected=" + nai.everConnected);
        }
        // TODO: eliminate this defensive copy after confirming that updateLinkProperties does not
        // modify its oldLp parameter.
        updateLinkProperties(nai, newLp, new LinkProperties(nai.linkProperties));
    }

    private void sendUpdatedScoreToFactories(NetworkAgentInfo nai) {
        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            // Don't send listening or track default request to factories. b/17393458
            if (!nr.isRequest()) continue;
            sendUpdatedScoreToFactories(nr, nai);
        }
    }

    private void sendUpdatedScoreToFactories(
            @NonNull final NetworkReassignment.RequestReassignment event) {
        // If a request of type REQUEST is now being satisfied by a new network.
        if (null != event.mNewNetworkRequest && event.mNewNetworkRequest.isRequest()) {
            sendUpdatedScoreToFactories(event.mNewNetworkRequest, event.mNewNetwork);
        }

        // If a previously satisfied request of type REQUEST is no longer being satisfied.
        if (null != event.mOldNetworkRequest && event.mOldNetworkRequest.isRequest()
                && event.mOldNetworkRequest != event.mNewNetworkRequest) {
            sendUpdatedScoreToFactories(event.mOldNetworkRequest, null);
        }

        cancelMultilayerLowerPriorityNpiRequests(event.mNetworkRequestInfo);
    }

    /**
     *  Cancel with all NPIs the given NRI's multilayer requests that are a lower priority than
     *  its currently satisfied active request.
     * @param nri the NRI to cancel lower priority requests for.
     */
    private void cancelMultilayerLowerPriorityNpiRequests(
            @NonNull final NetworkRequestInfo nri) {
        if (!nri.isMultilayerRequest() || null == nri.mActiveRequest) {
            return;
        }

        final int indexOfNewRequest = nri.mRequests.indexOf(nri.mActiveRequest);
        for (int i = indexOfNewRequest + 1; i < nri.mRequests.size(); i++) {
            cancelNpiRequest(nri.mRequests.get(i));
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
    private void sendAllRequestsToProvider(@NonNull final NetworkProviderInfo npi) {
        ensureRunningOnConnectivityServiceThread();
        for (final NetworkRequestInfo nri : getNrisFromGlobalRequests()) {
            for (final NetworkRequest req : nri.mRequests) {
                if (!req.isRequest() && nri.getActiveRequest() == req) {
                    break;
                }
                if (!req.isRequest()) {
                    continue;
                }
                // Only set the nai for the request it is satisfying.
                final NetworkAgentInfo nai =
                        nri.getActiveRequest() == req ? nri.getSatisfier() : null;
                final int score;
                final int serial;
                if (null != nai) {
                    score = nai.getCurrentScore();
                    serial = nai.factorySerialNumber;
                } else {
                    score = 0;
                    serial = NetworkProvider.ID_NONE;
                }
                npi.requestNetwork(req, score, serial);
                // For multilayer requests, don't send lower priority requests if a higher priority
                // request is already satisfied.
                if (null != nai) {
                    break;
                }
            }
        }
    }

    private void sendPendingIntentForRequest(NetworkRequestInfo nri, NetworkAgentInfo networkAgent,
            int notificationType) {
        if (notificationType == ConnectivityManager.CALLBACK_AVAILABLE && !nri.mPendingIntentSent) {
            Intent intent = new Intent();
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK, networkAgent.network);
            // If apps could file multi-layer requests with PendingIntents, they'd need to know
            // which of the layer is satisfied alongside with some ID for the request. Hence, if
            // such an API is ever implemented, there is no doubt the right request to send in
            // EXTRA_NETWORK_REQUEST is mActiveRequest, and whatever ID would be added would need to
            // be sent as a separate extra.
            intent.putExtra(ConnectivityManager.EXTRA_NETWORK_REQUEST, nri.getActiveRequest());
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

    private void callCallbackForRequest(@NonNull final NetworkRequestInfo nri,
            @NonNull final NetworkAgentInfo networkAgent, final int notificationType,
            final int arg1) {
        if (nri.mMessenger == null) {
            // Default request has no msgr. Also prevents callbacks from being invoked for
            // NetworkRequestInfos registered with ConnectivityDiagnostics requests. Those callbacks
            // are Type.LISTEN, but should not have NetworkCallbacks invoked.
            return;
        }
        Bundle bundle = new Bundle();
        // TODO b/177608132: make sure callbacks are indexed by NRIs and not NetworkRequest objects.
        // TODO: check if defensive copies of data is needed.
        final NetworkRequest nrForCallback = nri.getNetworkRequestForCallback();
        putParcelable(bundle, nrForCallback);
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
                        createWithLocationInfoSanitizedIfNecessaryWhenParceled(
                                nc, nri.mUid, nrForCallback.getRequestorPackageName(),
                                nri.mCallingAttributionTag));
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
                        createWithLocationInfoSanitizedIfNecessaryWhenParceled(
                                netCap, nri.mUid, nrForCallback.getRequestorPackageName(),
                                nri.mCallingAttributionTag));
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
                log("sending notification " + notification + " for " + nrForCallback);
            }
            nri.mMessenger.send(msg);
        } catch (RemoteException e) {
            // may occur naturally in the race of binder death.
            loge("RemoteException caught trying to send a callback msg for " + nrForCallback);
        }
    }

    private static <T extends Parcelable> void putParcelable(Bundle bundle, T t) {
        bundle.putParcelable(t.getClass().getSimpleName(), t);
    }

    private void teardownUnneededNetwork(NetworkAgentInfo nai) {
        if (nai.numRequestNetworkRequests() != 0) {
            for (int i = 0; i < nai.numNetworkRequests(); i++) {
                NetworkRequest nr = nai.requestAt(i);
                // Ignore listening and track default requests.
                if (!nr.isRequest()) continue;
                loge("Dead network still had at least " + nr);
                break;
            }
        }
        nai.disconnect();
    }

    private void handleLingerComplete(NetworkAgentInfo oldNetwork) {
        if (oldNetwork == null) {
            loge("Unknown NetworkAgentInfo in handleLingerComplete");
            return;
        }
        if (DBG) log("handleLingerComplete for " + oldNetwork.toShortString());

        // If we get here it means that the last linger timeout for this network expired. So there
        // must be no other active linger timers, and we must stop lingering.
        oldNetwork.clearInactivityState();

        if (unneeded(oldNetwork, UnneededFor.TEARDOWN)) {
            // Tear the network down.
            teardownUnneededNetwork(oldNetwork);
        } else {
            // Put the network in the background if it doesn't satisfy any foreground request.
            updateCapabilitiesForNetwork(oldNetwork);
        }
    }

    private void processDefaultNetworkChanges(@NonNull final NetworkReassignment changes) {
        boolean isDefaultChanged = false;
        for (final NetworkRequestInfo defaultRequestInfo : mDefaultNetworkRequests) {
            final NetworkReassignment.RequestReassignment reassignment =
                    changes.getReassignment(defaultRequestInfo);
            if (null == reassignment) {
                continue;
            }
            // reassignment only contains those instances where the satisfying network changed.
            isDefaultChanged = true;
            // Notify system services of the new default.
            makeDefault(defaultRequestInfo, reassignment.mOldNetwork, reassignment.mNewNetwork);
        }

        if (isDefaultChanged) {
            // Hold a wakelock for a short time to help apps in migrating to a new default.
            scheduleReleaseNetworkTransitionWakelock();
        }
    }

    private void makeDefault(@NonNull final NetworkRequestInfo nri,
            @Nullable final NetworkAgentInfo oldDefaultNetwork,
            @Nullable final NetworkAgentInfo newDefaultNetwork) {
        if (DBG) {
            log("Switching to new default network for: " + nri + " using " + newDefaultNetwork);
        }

        // Fix up the NetworkCapabilities of any networks that have this network as underlying.
        if (newDefaultNetwork != null) {
            propagateUnderlyingNetworkCapabilities(newDefaultNetwork.network);
        }

        // Set an app level managed default and return since further processing only applies to the
        // default network.
        if (mDefaultRequest != nri) {
            makeDefaultForApps(nri, oldDefaultNetwork, newDefaultNetwork);
            return;
        }

        makeDefaultNetwork(newDefaultNetwork);

        if (oldDefaultNetwork != null) {
            mLingerMonitor.noteLingerDefaultNetwork(oldDefaultNetwork, newDefaultNetwork);
        }
        mNetworkActivityTracker.updateDataActivityTracking(newDefaultNetwork, oldDefaultNetwork);
        handleApplyDefaultProxy(null != newDefaultNetwork
                ? newDefaultNetwork.linkProperties.getHttpProxy() : null);
        updateTcpBufferSizes(null != newDefaultNetwork
                ? newDefaultNetwork.linkProperties.getTcpBufferSizes() : null);
        notifyIfacesChangedForNetworkStats();
    }

    private void makeDefaultForApps(@NonNull final NetworkRequestInfo nri,
            @Nullable final NetworkAgentInfo oldDefaultNetwork,
            @Nullable final NetworkAgentInfo newDefaultNetwork) {
        try {
            if (VDBG) {
                log("Setting default network for " + nri
                        + " using UIDs " + nri.getUids()
                        + " with old network " + (oldDefaultNetwork != null
                        ? oldDefaultNetwork.network().getNetId() : "null")
                        + " and new network " + (newDefaultNetwork != null
                        ? newDefaultNetwork.network().getNetId() : "null"));
            }
            if (nri.getUids().isEmpty()) {
                throw new IllegalStateException("makeDefaultForApps called without specifying"
                        + " any applications to set as the default." + nri);
            }
            if (null != newDefaultNetwork) {
                mNetd.networkAddUidRanges(
                        newDefaultNetwork.network.getNetId(),
                        toUidRangeStableParcels(nri.getUids()));
            }
            if (null != oldDefaultNetwork) {
                mNetd.networkRemoveUidRanges(
                        oldDefaultNetwork.network.getNetId(),
                        toUidRangeStableParcels(nri.getUids()));
            }
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Exception setting OEM network preference default network :" + e);
        }
    }

    private void makeDefaultNetwork(@Nullable final NetworkAgentInfo newDefaultNetwork) {
        try {
            if (null != newDefaultNetwork) {
                mNetd.networkSetDefault(newDefaultNetwork.network.getNetId());
            } else {
                mNetd.networkClearDefault();
            }
        } catch (RemoteException | ServiceSpecificException e) {
            loge("Exception setting default network :" + e);
        }
    }

    private void processListenRequests(@NonNull final NetworkAgentInfo nai) {
        // For consistency with previous behaviour, send onLost callbacks before onAvailable.
        processNewlyLostListenRequests(nai);
        notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_CAP_CHANGED);
        processNewlySatisfiedListenRequests(nai);
    }

    private void processNewlyLostListenRequests(@NonNull final NetworkAgentInfo nai) {
        for (final NetworkRequestInfo nri : mNetworkRequests.values()) {
            if (nri.isMultilayerRequest()) {
                continue;
            }
            final NetworkRequest nr = nri.mRequests.get(0);
            if (!nr.isListen()) continue;
            if (nai.isSatisfyingRequest(nr.requestId) && !nai.satisfies(nr)) {
                nai.removeRequest(nr.requestId);
                callCallbackForRequest(nri, nai, ConnectivityManager.CALLBACK_LOST, 0);
            }
        }
    }

    private void processNewlySatisfiedListenRequests(@NonNull final NetworkAgentInfo nai) {
        for (final NetworkRequestInfo nri : mNetworkRequests.values()) {
            if (nri.isMultilayerRequest()) {
                continue;
            }
            final NetworkRequest nr = nri.mRequests.get(0);
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
            @NonNull public final NetworkRequestInfo mNetworkRequestInfo;
            @NonNull public final NetworkRequest mOldNetworkRequest;
            @NonNull public final NetworkRequest mNewNetworkRequest;
            @Nullable public final NetworkAgentInfo mOldNetwork;
            @Nullable public final NetworkAgentInfo mNewNetwork;
            RequestReassignment(@NonNull final NetworkRequestInfo networkRequestInfo,
                    @NonNull final NetworkRequest oldNetworkRequest,
                    @NonNull final NetworkRequest newNetworkRequest,
                    @Nullable final NetworkAgentInfo oldNetwork,
                    @Nullable final NetworkAgentInfo newNetwork) {
                mNetworkRequestInfo = networkRequestInfo;
                mOldNetworkRequest = oldNetworkRequest;
                mNewNetworkRequest = newNetworkRequest;
                mOldNetwork = oldNetwork;
                mNewNetwork = newNetwork;
            }

            public String toString() {
                return mNetworkRequestInfo.mRequests.get(0).requestId + " : "
                        + (null != mOldNetwork ? mOldNetwork.network.getNetId() : "null")
                        + " → " + (null != mNewNetwork ? mNewNetwork.network.getNetId() : "null");
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
                    if (existing.mNetworkRequestInfo.equals(reassignment.mNetworkRequestInfo)) {
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
                if (nri == event.mNetworkRequestInfo) return event;
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
            @NonNull final NetworkRequest previousRequest,
            @NonNull final NetworkRequest newRequest,
            @Nullable final NetworkAgentInfo previousSatisfier,
            @Nullable final NetworkAgentInfo newSatisfier,
            final long now) {
        if (null != newSatisfier && mNoServiceNetwork != newSatisfier) {
            if (VDBG) log("rematch for " + newSatisfier.toShortString());
            if (null != previousSatisfier && mNoServiceNetwork != previousSatisfier) {
                if (VDBG || DDBG) {
                    log("   accepting network in place of " + previousSatisfier.toShortString());
                }
                previousSatisfier.removeRequest(previousRequest.requestId);
                previousSatisfier.lingerRequest(previousRequest.requestId, now, mLingerDelayMs);
            } else {
                if (VDBG || DDBG) log("   accepting network in place of null");
            }

            // To prevent constantly CPU wake up for nascent timer, if a network comes up
            // and immediately satisfies a request then remove the timer. This will happen for
            // all networks except in the case of an underlying network for a VCN.
            if (newSatisfier.isNascent()) {
                newSatisfier.unlingerRequest(NetworkRequest.REQUEST_ID_NONE);
            }

            newSatisfier.unlingerRequest(newRequest.requestId);
            if (!newSatisfier.addRequest(newRequest)) {
                Log.wtf(TAG, "BUG: " + newSatisfier.toShortString() + " already has "
                        + newRequest);
            }
        } else if (null != previousSatisfier) {
            if (DBG) {
                log("Network " + previousSatisfier.toShortString() + " stopped satisfying"
                        + " request " + previousRequest.requestId);
            }
            previousSatisfier.removeRequest(previousRequest.requestId);
        }
        nri.setSatisfier(newSatisfier, newRequest);
    }

    /**
     * This function is triggered when something can affect what network should satisfy what
     * request, and it computes the network reassignment from the passed collection of requests to
     * network match to the one that the system should now have. That data is encoded in an
     * object that is a list of changes, each of them having an NRI, and old satisfier, and a new
     * satisfier.
     *
     * After the reassignment is computed, it is applied to the state objects.
     *
     * @param networkRequests the nri objects to evaluate for possible network reassignment
     * @return NetworkReassignment listing of proposed network assignment changes
     */
    @NonNull
    private NetworkReassignment computeNetworkReassignment(
            @NonNull final Collection<NetworkRequestInfo> networkRequests) {
        final NetworkReassignment changes = new NetworkReassignment();

        // Gather the list of all relevant agents and sort them by score.
        final ArrayList<NetworkAgentInfo> nais = new ArrayList<>();
        for (final NetworkAgentInfo nai : mNetworkAgentInfos) {
            if (!nai.everConnected) {
                continue;
            }
            nais.add(nai);
        }

        for (final NetworkRequestInfo nri : networkRequests) {
            // Non-multilayer listen requests can be ignored.
            if (!nri.isMultilayerRequest() && nri.mRequests.get(0).isListen()) {
                continue;
            }
            NetworkAgentInfo bestNetwork = null;
            NetworkRequest bestRequest = null;
            for (final NetworkRequest req : nri.mRequests) {
                bestNetwork = mNetworkRanker.getBestNetwork(req, nais);
                // Stop evaluating as the highest possible priority request is satisfied.
                if (null != bestNetwork) {
                    bestRequest = req;
                    break;
                }
            }
            if (null == bestNetwork && isDefaultBlocked(nri)) {
                // Remove default networking if disallowed for managed default requests.
                bestNetwork = mNoServiceNetwork;
            }
            if (nri.getSatisfier() != bestNetwork) {
                // bestNetwork may be null if no network can satisfy this request.
                changes.addRequestReassignment(new NetworkReassignment.RequestReassignment(
                        nri, nri.mActiveRequest, bestRequest, nri.getSatisfier(), bestNetwork));
            }
        }
        return changes;
    }

    private Set<NetworkRequestInfo> getNrisFromGlobalRequests() {
        return new HashSet<>(mNetworkRequests.values());
    }

    /**
     * Attempt to rematch all Networks with all NetworkRequests.  This may result in Networks
     * being disconnected.
     */
    private void rematchAllNetworksAndRequests() {
        rematchNetworksAndRequests(getNrisFromGlobalRequests());
    }

    /**
     * Attempt to rematch all Networks with given NetworkRequests.  This may result in Networks
     * being disconnected.
     */
    private void rematchNetworksAndRequests(
            @NonNull final Set<NetworkRequestInfo> networkRequests) {
        ensureRunningOnConnectivityServiceThread();
        // TODO: This may be slow, and should be optimized.
        final long now = SystemClock.elapsedRealtime();
        final NetworkReassignment changes = computeNetworkReassignment(networkRequests);
        if (VDBG || DDBG) {
            log(changes.debugString());
        } else if (DBG) {
            log(changes.toString()); // Shorter form, only one line of log
        }
        applyNetworkReassignment(changes, now);
    }

    private void applyNetworkReassignment(@NonNull final NetworkReassignment changes,
            final long now) {
        final Collection<NetworkAgentInfo> nais = mNetworkAgentInfos;

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
            updateSatisfiersForRematchRequest(event.mNetworkRequestInfo,
                    event.mOldNetworkRequest, event.mNewNetworkRequest,
                    event.mOldNetwork, event.mNewNetwork,
                    now);
        }

        // Process default network changes if applicable.
        processDefaultNetworkChanges(changes);

        // Notify requested networks are available after the default net is switched, but
        // before LegacyTypeTracker sends legacy broadcasts
        for (final NetworkReassignment.RequestReassignment event :
                changes.getRequestReassignments()) {
            // Tell NetworkProviders about the new score, so they can stop
            // trying to connect if they know they cannot match it.
            // TODO - this could get expensive if there are a lot of outstanding requests for this
            // network. Think of a way to reduce this. Push netid->request mapping to each factory?
            sendUpdatedScoreToFactories(event);

            if (null != event.mNewNetwork) {
                notifyNetworkAvailable(event.mNewNetwork, event.mNetworkRequestInfo);
            } else {
                callCallbackForRequest(event.mNetworkRequestInfo, event.mOldNetwork,
                        ConnectivityManager.CALLBACK_LOST, 0);
            }
        }

        // Update the inactivity state before processing listen callbacks, because the background
        // computation depends on whether the network is inactive. Don't send the LOSING callbacks
        // just yet though, because they have to be sent after the listens are processed to keep
        // backward compatibility.
        final ArrayList<NetworkAgentInfo> inactiveNetworks = new ArrayList<>();
        for (final NetworkAgentInfo nai : nais) {
            // Rematching may have altered the inactivity state of some networks, so update all
            // inactivity timers. updateInactivityState reads the state from the network agent
            // and does nothing if the state has not changed : the source of truth is controlled
            // with NetworkAgentInfo#lingerRequest and NetworkAgentInfo#unlingerRequest, which
            // have been called while rematching the individual networks above.
            if (updateInactivityState(nai, now)) {
                inactiveNetworks.add(nai);
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

        for (final NetworkAgentInfo nai : inactiveNetworks) {
            // For nascent networks, if connecting with no foreground request, skip broadcasting
            // LOSING for backward compatibility. This is typical when mobile data connected while
            // wifi connected with mobile data always-on enabled.
            if (nai.isNascent()) continue;
            notifyNetworkLosing(nai, now);
        }

        updateLegacyTypeTrackerAndVpnLockdownForRematch(changes, nais);

        // Tear down all unneeded networks.
        for (NetworkAgentInfo nai : mNetworkAgentInfos) {
            if (unneeded(nai, UnneededFor.TEARDOWN)) {
                if (nai.getInactivityExpiry() > 0) {
                    // This network has active linger timers and no requests, but is not
                    // lingering. Linger it.
                    //
                    // One way (the only way?) this can happen if this network is unvalidated
                    // and became unneeded due to another network improving its score to the
                    // point where this network will no longer be able to satisfy any requests
                    // even if it validates.
                    if (updateInactivityState(nai, now)) {
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
            @NonNull final NetworkReassignment changes,
            @NonNull final Collection<NetworkAgentInfo> nais) {
        final NetworkReassignment.RequestReassignment reassignmentOfDefault =
                changes.getReassignment(mDefaultRequest);
        final NetworkAgentInfo oldDefaultNetwork =
                null != reassignmentOfDefault ? reassignmentOfDefault.mOldNetwork : null;
        final NetworkAgentInfo newDefaultNetwork =
                null != reassignmentOfDefault ? reassignmentOfDefault.mNewNetwork : null;

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
                mLegacyTypeTracker.add(
                        newDefaultNetwork.networkInfo.getType(), newDefaultNetwork);
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
        // For now only update icons for the default connection.
        // TODO: Update WiFi and cellular icons separately. b/17237507
        if (!isDefaultNetwork(nai)) return;

        int newInetCondition = nai.lastValidated ? 100 : 0;
        // Don't repeat publish.
        if (newInetCondition == mDefaultInetConditionPublished) return;

        mDefaultInetConditionPublished = newInetCondition;
        sendInetConditionBroadcast(nai.networkInfo);
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
            if (networkAgent.supportsUnderlyingNetworks()) {
                // Initialize the network's capabilities to their starting values according to the
                // underlying networks. This ensures that the capabilities are correct before
                // anything happens to the network.
                updateCapabilitiesForNetwork(networkAgent);
            }
            networkAgent.created = true;
        }

        if (!networkAgent.everConnected && state == NetworkInfo.State.CONNECTED) {
            networkAgent.everConnected = true;

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
                    new LinkProperties(networkAgent.linkProperties,
                            true /* parcelSensitiveFields */),
                    networkAgent.networkCapabilities);
            scheduleUnvalidatedPrompt(networkAgent);

            // Whether a particular NetworkRequest listen should cause signal strength thresholds to
            // be communicated to a particular NetworkAgent depends only on the network's immutable,
            // capabilities, so it only needs to be done once on initial connect, not every time the
            // network's capabilities change. Note that we do this before rematching the network,
            // so we could decide to tear it down immediately afterwards. That's fine though - on
            // disconnection NetworkAgents should stop any signal strength monitoring they have been
            // doing.
            updateSignalStrengthThresholds(networkAgent, "CONNECT", null);

            // Before first rematching networks, put an inactivity timer without any request, this
            // allows {@code updateInactivityState} to update the state accordingly and prevent
            // tearing down for any {@code unneeded} evaluation in this period.
            // Note that the timer will not be rescheduled since the expiry time is
            // fixed after connection regardless of the network satisfying other requests or not.
            // But it will be removed as soon as the network satisfies a request for the first time.
            networkAgent.lingerRequest(NetworkRequest.REQUEST_ID_NONE,
                    SystemClock.elapsedRealtime(), mNascentDelayMs);

            // Consider network even though it is not yet validated.
            rematchAllNetworksAndRequests();

            // This has to happen after matching the requests, because callbacks are just requests.
            notifyNetworkCallbacks(networkAgent, ConnectivityManager.CALLBACK_PRECHECK);
        } else if (state == NetworkInfo.State.DISCONNECTED) {
            networkAgent.disconnect();
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
        boolean blocked;
        blocked = isUidBlockedByVpn(nri.mUid, mVpnBlockedUidRanges);
        blocked |= isUidBlockedByRules(nri.mUid, mUidRules.get(nri.mUid),
                metered, mRestrictBackground);
        callCallbackForRequest(nri, nai, ConnectivityManager.CALLBACK_AVAILABLE, blocked ? 1 : 0);
    }

    // Notify the requests on this NAI that the network is now lingered.
    private void notifyNetworkLosing(@NonNull final NetworkAgentInfo nai, final long now) {
        final int lingerTime = (int) (nai.getInactivityExpiry() - now);
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
            boolean newMetered, boolean oldRestrictBackground, boolean newRestrictBackground,
            List<UidRange> oldBlockedUidRanges, List<UidRange> newBlockedUidRanges) {

        for (int i = 0; i < nai.numNetworkRequests(); i++) {
            NetworkRequest nr = nai.requestAt(i);
            NetworkRequestInfo nri = mNetworkRequests.get(nr);
            final int uidRules = mUidRules.get(nri.mUid);
            final boolean oldBlocked, newBlocked, oldVpnBlocked, newVpnBlocked;

            oldVpnBlocked = isUidBlockedByVpn(nri.mUid, oldBlockedUidRanges);
            newVpnBlocked = (oldBlockedUidRanges != newBlockedUidRanges)
                    ? isUidBlockedByVpn(nri.mUid, newBlockedUidRanges)
                    : oldVpnBlocked;

            oldBlocked = oldVpnBlocked || isUidBlockedByRules(nri.mUid, uidRules, oldMetered,
                    oldRestrictBackground);
            newBlocked = newVpnBlocked || isUidBlockedByRules(nri.mUid, uidRules, newMetered,
                    newRestrictBackground);

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
        for (final NetworkAgentInfo nai : mNetworkAgentInfos) {
            final boolean metered = nai.networkCapabilities.isMetered();
            final boolean vpnBlocked = isUidBlockedByVpn(uid, mVpnBlockedUidRanges);
            final boolean oldBlocked, newBlocked;
            oldBlocked = vpnBlocked || isUidBlockedByRules(
                    uid, mUidRules.get(uid), metered, mRestrictBackground);
            newBlocked = vpnBlocked || isUidBlockedByRules(
                    uid, newRules, metered, mRestrictBackground);
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
            if (nai.isSatisfyingRequest(mDefaultRequest.mRequests.get(0).requestId)) {
                newDefaultAgent = mDefaultRequest.getSatisfier();
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
    @NonNull
    private ArrayList<Network> getDefaultNetworks() {
        ensureRunningOnConnectivityServiceThread();
        final ArrayList<Network> defaultNetworks = new ArrayList<>();
        final Set<Integer> activeNetIds = new ArraySet<>();
        for (final NetworkRequestInfo nri : mDefaultNetworkRequests) {
            if (nri.isBeingSatisfied()) {
                activeNetIds.add(nri.getSatisfier().network().netId);
            }
        }
        for (NetworkAgentInfo nai : mNetworkAgentInfos) {
            if (nai.everConnected && (activeNetIds.contains(nai.network().netId) || nai.isVPN())) {
                defaultNetworks.add(nai.network);
            }
        }
        return defaultNetworks;
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

        final UnderlyingNetworkInfo[] underlyingNetworkInfos = getAllVpnInfo();
        try {
            final ArrayList<NetworkStateSnapshot> snapshots = new ArrayList<>();
            // TODO: Directly use NetworkStateSnapshot when feasible.
            for (final NetworkState state : getAllNetworkState()) {
                final NetworkStateSnapshot snapshot = new NetworkStateSnapshot(state.network,
                        state.networkCapabilities, state.linkProperties, state.subscriberId,
                        state.legacyNetworkType);
                snapshots.add(snapshot);
            }
            mStatsManager.notifyNetworkStatus(getDefaultNetworks(),
                    snapshots, activeIface, Arrays.asList(underlyingNetworkInfos));
        } catch (Exception ignored) {
        }
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
    public void startNattKeepaliveWithFd(Network network, ParcelFileDescriptor pfd, int resourceId,
            int intervalSeconds, ISocketKeepaliveCallback cb, String srcAddr,
            String dstAddr) {
        try {
            final FileDescriptor fd = pfd.getFileDescriptor();
            mKeepaliveTracker.startNattKeepalive(
                    getNetworkAgentInfoForNetwork(network), fd, resourceId,
                    intervalSeconds, cb,
                    srcAddr, dstAddr, NattSocketKeepalive.NATT_PORT);
        } finally {
            // FileDescriptors coming from AIDL calls must be manually closed to prevent leaks.
            // startNattKeepalive calls Os.dup(fd) before returning, so we can close immediately.
            if (pfd != null && Binder.getCallingPid() != Process.myPid()) {
                IoUtils.closeQuietly(pfd);
            }
        }
    }

    @Override
    public void startTcpKeepalive(Network network, ParcelFileDescriptor pfd, int intervalSeconds,
            ISocketKeepaliveCallback cb) {
        try {
            enforceKeepalivePermission();
            final FileDescriptor fd = pfd.getFileDescriptor();
            mKeepaliveTracker.startTcpKeepalive(
                    getNetworkAgentInfoForNetwork(network), fd, intervalSeconds, cb);
        } finally {
            // FileDescriptors coming from AIDL calls must be manually closed to prevent leaks.
            // startTcpKeepalive calls Os.dup(fd) before returning, so we can close immediately.
            if (pfd != null && Binder.getCallingPid() != Process.myPid()) {
                IoUtils.closeQuietly(pfd);
            }
        }
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

        final long token = Binder.clearCallingIdentity();
        try {
            final IpMemoryStore ipMemoryStore = IpMemoryStore.getMemoryStore(mContext);
            ipMemoryStore.factoryReset();
        } finally {
            Binder.restoreCallingIdentity(token);
        }

        // Turn airplane mode off
        setAirplaneMode(false);

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
        mMetricsLog.log(nai.network.getNetId(), transports, new NetworkEvent(evtype));
    }

    private static boolean toBool(int encodedBoolean) {
        return encodedBoolean != 0; // Only 0 means false.
    }

    private static int encodeBool(boolean b) {
        return b ? 1 : 0;
    }

    @Override
    public int handleShellCommand(@NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        return new ShellCmd().exec(this, in.getFileDescriptor(), out.getFileDescriptor(),
                err.getFileDescriptor(), args);
    }

    private class ShellCmd extends BasicShellCommandHandler {
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

    private @VpnManager.VpnType int getVpnType(@Nullable NetworkAgentInfo vpn) {
        if (vpn == null) return VpnManager.TYPE_VPN_NONE;
        final TransportInfo ti = vpn.networkCapabilities.getTransportInfo();
        if (!(ti instanceof VpnTransportInfo)) return VpnManager.TYPE_VPN_NONE;
        return ((VpnTransportInfo) ti).type;
    }

    /**
     * @param connectionInfo the connection to resolve.
     * @return {@code uid} if the connection is found and the app has permission to observe it
     * (e.g., if it is associated with the calling VPN app's tunnel) or {@code INVALID_UID} if the
     * connection is not found.
     */
    public int getConnectionOwnerUid(ConnectionInfo connectionInfo) {
        if (connectionInfo.protocol != IPPROTO_TCP && connectionInfo.protocol != IPPROTO_UDP) {
            throw new IllegalArgumentException("Unsupported protocol " + connectionInfo.protocol);
        }

        final int uid = mDeps.getConnectionOwnerUid(connectionInfo.protocol,
                connectionInfo.local, connectionInfo.remote);

        if (uid == INVALID_UID) return uid;  // Not found.

        // Connection owner UIDs are visible only to the network stack and to the VpnService-based
        // VPN, if any, that applies to the UID that owns the connection.
        if (checkNetworkStackPermission()) return uid;

        final NetworkAgentInfo vpn = getVpnForUid(uid);
        if (vpn == null || getVpnType(vpn) != VpnManager.TYPE_VPN_SERVICE
                || vpn.networkCapabilities.getOwnerUid() != Binder.getCallingUid()) {
            return INVALID_UID;
        }

        return uid;
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
                mTNS = new TestNetworkService(mContext);
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

                    handleNetworkTestedWithExtras(reportEvent, reportEvent.mExtras);
                    break;
                }
                case EVENT_DATA_STALL_SUSPECTED: {
                    final NetworkAgentInfo nai = getNetworkAgentInfoForNetId(msg.arg2);
                    final Pair<Long, PersistableBundle> arg =
                            (Pair<Long, PersistableBundle>) msg.obj;
                    if (nai == null) break;

                    handleDataStallSuspected(nai, arg.first, msg.arg1, arg.second);
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
        private final PersistableBundle mExtras;

        private ConnectivityReportEvent(long timestampMillis, @NonNull NetworkAgentInfo nai,
                PersistableBundle p) {
            mTimestampMillis = timestampMillis;
            mNai = nai;
            mExtras = p;
        }
    }

    private void handleRegisterConnectivityDiagnosticsCallback(
            @NonNull ConnectivityDiagnosticsCallbackInfo cbInfo) {
        ensureRunningOnConnectivityServiceThread();

        final IConnectivityDiagnosticsCallback cb = cbInfo.mCb;
        final IBinder iCb = cb.asBinder();
        final NetworkRequestInfo nri = cbInfo.mRequestInfo;

        // Connectivity Diagnostics are meant to be used with a single network request. It would be
        // confusing for these networks to change when an NRI is satisfied in another layer.
        if (nri.isMultilayerRequest()) {
            throw new IllegalArgumentException("Connectivity Diagnostics do not support multilayer "
                + "network requests.");
        }

        // This means that the client registered the same callback multiple times. Do
        // not override the previous entry, and exit silently.
        if (mConnectivityDiagnosticsCallbacks.containsKey(iCb)) {
            if (VDBG) log("Diagnostics callback is already registered");

            // Decrement the reference count for this NetworkRequestInfo. The reference count is
            // incremented when the NetworkRequestInfo is created as part of
            // enforceRequestCountLimit().
            mNetworkRequestCounter.decrementCount(nri.mUid);
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
                // Connectivity Diagnostics rejects multilayer requests at registration hence get(0)
                if (nai.satisfies(nri.mRequests.get(0))) {
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

        // Caller's UID must either be the registrants (if they are unregistering) or the System's
        // (if the Binder died)
        if (uid != nri.mUid && uid != Process.SYSTEM_UID) {
            if (DBG) loge("Uid(" + uid + ") not registrant's (" + nri.mUid + ") or System's");
            return;
        }

        // Decrement the reference count for this NetworkRequestInfo. The reference count is
        // incremented when the NetworkRequestInfo is created as part of
        // enforceRequestCountLimit().
        mNetworkRequestCounter.decrementCount(nri.mUid);

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
            // Connectivity Diagnostics rejects multilayer requests at registration hence get(0).
            if (nai.satisfies(nri.mRequests.get(0))) {
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

        for (NetworkAgentInfo virtual : mNetworkAgentInfos) {
            if (virtual.supportsUnderlyingNetworks()
                    && virtual.networkCapabilities.getOwnerUid() == callbackUid
                    && CollectionUtils.contains(virtual.declaredUnderlyingNetworks, nai.network)) {
                return true;
            }
        }

        // Administrator UIDs also contains the Owner UID
        final int[] administratorUids = nai.networkCapabilities.getAdministratorUids();
        return CollectionUtils.contains(administratorUids, callbackUid);
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
        final int callingUid = mDeps.getCallingUid();
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
                        mDeps.getCallingUid(),
                        0,
                        callback));
    }

    @Override
    public void simulateDataStall(int detectionMethod, long timestampMillis,
            @NonNull Network network, @NonNull PersistableBundle extras) {
        enforceAnyPermissionOf(android.Manifest.permission.MANAGE_TEST_NETWORKS,
                android.Manifest.permission.NETWORK_STACK);
        final NetworkCapabilities nc = getNetworkCapabilitiesInternal(network);
        if (!nc.hasTransport(TRANSPORT_TEST)) {
            throw new SecurityException("Data Stall simluation is only possible for test networks");
        }

        final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(network);
        if (nai == null || nai.creatorUid != mDeps.getCallingUid()) {
            throw new SecurityException("Data Stall simulation is only possible for network "
                + "creators");
        }

        // Instead of passing the data stall directly to the ConnectivityDiagnostics handler, treat
        // this as a Data Stall received directly from NetworkMonitor. This requires wrapping the
        // Data Stall information as a DataStallReportParcelable and passing to
        // #notifyDataStallSuspected. This ensures that unknown Data Stall detection methods are
        // still passed to ConnectivityDiagnostics (with new detection methods masked).
        final DataStallReportParcelable p = new DataStallReportParcelable();
        p.timestampMillis = timestampMillis;
        p.detectionMethod = detectionMethod;

        if (hasDataStallDetectionMethod(p, DETECTION_METHOD_DNS_EVENTS)) {
            p.dnsConsecutiveTimeouts = extras.getInt(KEY_DNS_CONSECUTIVE_TIMEOUTS);
        }
        if (hasDataStallDetectionMethod(p, DETECTION_METHOD_TCP_METRICS)) {
            p.tcpPacketFailRate = extras.getInt(KEY_TCP_PACKET_FAIL_RATE);
            p.tcpMetricsCollectionPeriodMillis = extras.getInt(
                    KEY_TCP_METRICS_COLLECTION_PERIOD_MILLIS);
        }

        notifyDataStallSuspected(p, network.getNetId());
    }

    private class NetdCallback extends BaseNetdUnsolicitedEventListener {
        @Override
        public void onInterfaceClassActivityChanged(boolean isActive, int transportType,
                long timestampNs, int uid) {
            mNetworkActivityTracker.setAndReportNetworkActive(isActive, transportType, timestampNs);
        }

        @Override
        public void onInterfaceLinkStateChanged(String iface, boolean up) {
            for (NetworkAgentInfo nai : mNetworkAgentInfos) {
                nai.clatd.interfaceLinkStateChanged(iface, up);
            }
        }

        @Override
        public void onInterfaceRemoved(String iface) {
            for (NetworkAgentInfo nai : mNetworkAgentInfos) {
                nai.clatd.interfaceRemoved(iface);
            }
        }
    }

    private final LegacyNetworkActivityTracker mNetworkActivityTracker;

    /**
     * Class used for updating network activity tracking with netd and notify network activity
     * changes.
     */
    private static final class LegacyNetworkActivityTracker {
        private static final int NO_UID = -1;
        private final Context mContext;
        private final INetd mNetd;
        private final RemoteCallbackList<INetworkActivityListener> mNetworkActivityListeners =
                new RemoteCallbackList<>();
        // Indicate the current system default network activity is active or not.
        @GuardedBy("mActiveIdleTimers")
        private boolean mNetworkActive;
        @GuardedBy("mActiveIdleTimers")
        private final ArrayMap<String, IdleTimerParams> mActiveIdleTimers = new ArrayMap();
        private final Handler mHandler;

        private class IdleTimerParams {
            public final int timeout;
            public final int transportType;

            IdleTimerParams(int timeout, int transport) {
                this.timeout = timeout;
                this.transportType = transport;
            }
        }

        LegacyNetworkActivityTracker(@NonNull Context context, @NonNull Handler handler,
                @NonNull INetd netd) {
            mContext = context;
            mNetd = netd;
            mHandler = handler;
        }

        public void setAndReportNetworkActive(boolean active, int transportType, long tsNanos) {
            sendDataActivityBroadcast(transportTypeToLegacyType(transportType), active, tsNanos);
            synchronized (mActiveIdleTimers) {
                mNetworkActive = active;
                // If there are no idle timers, it means that system is not monitoring
                // activity, so the system default network for those default network
                // unspecified apps is always considered active.
                //
                // TODO: If the mActiveIdleTimers is empty, netd will actually not send
                // any network activity change event. Whenever this event is received,
                // the mActiveIdleTimers should be always not empty. The legacy behavior
                // is no-op. Remove to refer to mNetworkActive only.
                if (mNetworkActive || mActiveIdleTimers.isEmpty()) {
                    mHandler.sendMessage(mHandler.obtainMessage(EVENT_REPORT_NETWORK_ACTIVITY));
                }
            }
        }

        // The network activity should only be updated from ConnectivityService handler thread
        // when mActiveIdleTimers lock is held.
        @GuardedBy("mActiveIdleTimers")
        private void reportNetworkActive() {
            final int length = mNetworkActivityListeners.beginBroadcast();
            if (DDBG) log("reportNetworkActive, notify " + length + " listeners");
            try {
                for (int i = 0; i < length; i++) {
                    try {
                        mNetworkActivityListeners.getBroadcastItem(i).onNetworkActive();
                    } catch (RemoteException | RuntimeException e) {
                        loge("Fail to send network activie to listener " + e);
                    }
                }
            } finally {
                mNetworkActivityListeners.finishBroadcast();
            }
        }

        @GuardedBy("mActiveIdleTimers")
        public void handleReportNetworkActivity() {
            synchronized (mActiveIdleTimers) {
                reportNetworkActive();
            }
        }

        // This is deprecated and only to support legacy use cases.
        private int transportTypeToLegacyType(int type) {
            switch (type) {
                case NetworkCapabilities.TRANSPORT_CELLULAR:
                    return ConnectivityManager.TYPE_MOBILE;
                case NetworkCapabilities.TRANSPORT_WIFI:
                    return ConnectivityManager.TYPE_WIFI;
                case NetworkCapabilities.TRANSPORT_BLUETOOTH:
                    return ConnectivityManager.TYPE_BLUETOOTH;
                case NetworkCapabilities.TRANSPORT_ETHERNET:
                    return ConnectivityManager.TYPE_ETHERNET;
                default:
                    loge("Unexpected transport in transportTypeToLegacyType: " + type);
            }
            return ConnectivityManager.TYPE_NONE;
        }

        public void sendDataActivityBroadcast(int deviceType, boolean active, long tsNanos) {
            final Intent intent = new Intent(ConnectivityManager.ACTION_DATA_ACTIVITY_CHANGE);
            intent.putExtra(ConnectivityManager.EXTRA_DEVICE_TYPE, deviceType);
            intent.putExtra(ConnectivityManager.EXTRA_IS_ACTIVE, active);
            intent.putExtra(ConnectivityManager.EXTRA_REALTIME_NS, tsNanos);
            final long ident = Binder.clearCallingIdentity();
            try {
                mContext.sendOrderedBroadcastAsUser(intent, UserHandle.ALL,
                        RECEIVE_DATA_ACTIVITY_CHANGE,
                        null /* resultReceiver */,
                        null /* scheduler */,
                        0 /* initialCode */,
                        null /* initialData */,
                        null /* initialExtra */);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
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
                type = NetworkCapabilities.TRANSPORT_CELLULAR;
            } else if (networkAgent.networkCapabilities.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI)) {
                timeout = Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI,
                        15);
                type = NetworkCapabilities.TRANSPORT_WIFI;
            } else {
                return; // do not track any other networks
            }

            updateRadioPowerState(true /* isActive */, type);

            if (timeout > 0 && iface != null) {
                try {
                    synchronized (mActiveIdleTimers) {
                        // Networks start up.
                        mNetworkActive = true;
                        mActiveIdleTimers.put(iface, new IdleTimerParams(timeout, type));
                        mNetd.idletimerAddInterface(iface, timeout, Integer.toString(type));
                        reportNetworkActive();
                    }
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

            if (iface == null) return;

            final int type;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                type = NetworkCapabilities.TRANSPORT_CELLULAR;
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                type = NetworkCapabilities.TRANSPORT_WIFI;
            } else {
                return; // do not track any other networks
            }

            try {
                updateRadioPowerState(false /* isActive */, type);
                synchronized (mActiveIdleTimers) {
                    final IdleTimerParams params = mActiveIdleTimers.remove(iface);
                    // The call fails silently if no idle timer setup for this interface
                    mNetd.idletimerRemoveInterface(iface, params.timeout,
                            Integer.toString(params.transportType));
                }
            } catch (Exception e) {
                // You shall not crash!
                loge("Exception in removeDataActivityTracking " + e);
            }
        }

        /**
         * Update data activity tracking when network state is updated.
         */
        public void updateDataActivityTracking(NetworkAgentInfo newNetwork,
                NetworkAgentInfo oldNetwork) {
            if (newNetwork != null) {
                setupDataActivityTracking(newNetwork);
            }
            if (oldNetwork != null) {
                removeDataActivityTracking(oldNetwork);
            }
        }

        private void updateRadioPowerState(boolean isActive, int transportType) {
            final BatteryStatsManager bs = mContext.getSystemService(BatteryStatsManager.class);
            switch (transportType) {
                case NetworkCapabilities.TRANSPORT_CELLULAR:
                    bs.reportMobileRadioPowerState(isActive, NO_UID);
                    break;
                case NetworkCapabilities.TRANSPORT_WIFI:
                    bs.reportWifiRadioPowerState(isActive, NO_UID);
                    break;
                default:
                    logw("Untracked transport type:" + transportType);
            }
        }

        public boolean isDefaultNetworkActive() {
            synchronized (mActiveIdleTimers) {
                // If there are no idle timers, it means that system is not monitoring activity,
                // so the default network is always considered active.
                //
                // TODO : Distinguish between the cases where mActiveIdleTimers is empty because
                // tracking is disabled (negative idle timer value configured), or no active default
                // network. In the latter case, this reports active but it should report inactive.
                return mNetworkActive || mActiveIdleTimers.isEmpty();
            }
        }

        public void registerNetworkActivityListener(@NonNull INetworkActivityListener l) {
            mNetworkActivityListeners.register(l);
        }

        public void unregisterNetworkActivityListener(@NonNull INetworkActivityListener l) {
            mNetworkActivityListeners.unregister(l);
        }

        public void dump(IndentingPrintWriter pw) {
            synchronized (mActiveIdleTimers) {
                pw.print("mNetworkActive="); pw.println(mNetworkActive);
                pw.println("Idle timers:");
                for (HashMap.Entry<String, IdleTimerParams> ent : mActiveIdleTimers.entrySet()) {
                    pw.print("  "); pw.print(ent.getKey()); pw.println(":");
                    final IdleTimerParams params = ent.getValue();
                    pw.print("    timeout="); pw.print(params.timeout);
                    pw.print(" type="); pw.println(params.transportType);
                }
            }
        }
    }

    /**
     * Registers {@link QosSocketFilter} with {@link IQosCallback}.
     *
     * @param socketInfo the socket information
     * @param callback the callback to register
     */
    @Override
    public void registerQosSocketCallback(@NonNull final QosSocketInfo socketInfo,
            @NonNull final IQosCallback callback) {
        final NetworkAgentInfo nai = getNetworkAgentInfoForNetwork(socketInfo.getNetwork());
        if (nai == null || nai.networkCapabilities == null) {
            try {
                callback.onError(QosCallbackException.EX_TYPE_FILTER_NETWORK_RELEASED);
            } catch (final RemoteException ex) {
                loge("registerQosCallbackInternal: RemoteException", ex);
            }
            return;
        }
        registerQosCallbackInternal(new QosSocketFilter(socketInfo), callback, nai);
    }

    /**
     * Register a {@link IQosCallback} with base {@link QosFilter}.
     *
     * @param filter the filter to register
     * @param callback the callback to register
     * @param nai the agent information related to the filter's network
     */
    @VisibleForTesting
    public void registerQosCallbackInternal(@NonNull final QosFilter filter,
            @NonNull final IQosCallback callback, @NonNull final NetworkAgentInfo nai) {
        if (filter == null) throw new IllegalArgumentException("filter must be non-null");
        if (callback == null) throw new IllegalArgumentException("callback must be non-null");

        if (!nai.networkCapabilities.hasCapability(NET_CAPABILITY_NOT_RESTRICTED)) {
            enforceConnectivityRestrictedNetworksPermission();
        }
        mQosCallbackTracker.registerCallback(callback, filter, nai);
    }

    /**
     * Unregisters the given callback.
     *
     * @param callback the callback to unregister
     */
    @Override
    public void unregisterQosCallback(@NonNull final IQosCallback callback) {
        mQosCallbackTracker.unregisterCallback(callback);
    }

    private void enforceAutomotiveDevice() {
        final boolean isAutomotiveDevice =
                mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
        if (!isAutomotiveDevice) {
            throw new UnsupportedOperationException(
                    "setOemNetworkPreference() is only available on automotive devices.");
        }
    }

    /**
     * Used by automotive devices to set the network preferences used to direct traffic at an
     * application level as per the given OemNetworkPreferences. An example use-case would be an
     * automotive OEM wanting to provide connectivity for applications critical to the usage of a
     * vehicle via a particular network.
     *
     * Calling this will overwrite the existing preference.
     *
     * @param preference {@link OemNetworkPreferences} The application network preference to be set.
     * @param listener {@link ConnectivityManager.OnSetOemNetworkPreferenceListener} Listener used
     * to communicate completion of setOemNetworkPreference();
     */
    @Override
    public void setOemNetworkPreference(
            @NonNull final OemNetworkPreferences preference,
            @Nullable final IOnSetOemNetworkPreferenceListener listener) {

        enforceAutomotiveDevice();
        enforceOemNetworkPreferencesPermission();

        Objects.requireNonNull(preference, "OemNetworkPreferences must be non-null");
        validateOemNetworkPreferences(preference);
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_OEM_NETWORK_PREFERENCE,
                new Pair<>(preference, listener)));
    }

    private void validateOemNetworkPreferences(@NonNull OemNetworkPreferences preference) {
        for (@OemNetworkPreferences.OemNetworkPreference final int pref
                : preference.getNetworkPreferences().values()) {
            if (OemNetworkPreferences.OEM_NETWORK_PREFERENCE_UNINITIALIZED == pref) {
                final String msg = "OEM_NETWORK_PREFERENCE_UNINITIALIZED is an invalid value.";
                throw new IllegalArgumentException(msg);
            }
        }
    }

    private void handleSetOemNetworkPreference(
            @NonNull final OemNetworkPreferences preference,
            @NonNull final IOnSetOemNetworkPreferenceListener listener) throws RemoteException {
        Objects.requireNonNull(preference, "OemNetworkPreferences must be non-null");
        if (DBG) {
            log("set OEM network preferences :" + preference.toString());
        }
        final ArraySet<NetworkRequestInfo> nris =
                new OemNetworkRequestFactory().createNrisFromOemNetworkPreferences(preference);
        updateDefaultNetworksForOemNetworkPreference(nris);
        mOemNetworkPreferences = preference;
        // TODO http://b/176496396 persist data to shared preferences.

        if (null != listener) {
            listener.onComplete();
        }
    }

    private void updateDefaultNetworksForOemNetworkPreference(
            @NonNull final Set<NetworkRequestInfo> nris) {
        // Pass in a defensive copy as this collection will be updated on remove.
        handleRemoveNetworkRequests(new ArraySet<>(mDefaultNetworkRequests));
        addPerAppDefaultNetworkRequests(nris);
    }

    private void addPerAppDefaultNetworkRequests(@NonNull final Set<NetworkRequestInfo> nris) {
        ensureRunningOnConnectivityServiceThread();
        mDefaultNetworkRequests.addAll(nris);
        final ArraySet<NetworkRequestInfo> perAppCallbackRequestsToUpdate =
                getPerAppCallbackRequestsToUpdate();
        handleRemoveNetworkRequests(perAppCallbackRequestsToUpdate);
        final ArraySet<NetworkRequestInfo> nrisToRegister = new ArraySet<>(nris);
        nrisToRegister.addAll(
                createPerAppCallbackRequestsToRegister(perAppCallbackRequestsToUpdate));
        handleRegisterNetworkRequests(nrisToRegister);
    }

    /**
     * All current requests that are tracking the default network need to be assessed as to whether
     * or not the current set of per-application default requests will be changing their default
     * network. If so, those requests will need to be updated so that they will send callbacks for
     * default network changes at the appropriate time. Additionally, those requests tracking the
     * default that were previously updated by this flow will need to be reassessed.
     * @return the nris which will need to be updated.
     */
    private ArraySet<NetworkRequestInfo> getPerAppCallbackRequestsToUpdate() {
        final ArraySet<NetworkRequestInfo> defaultCallbackRequests = new ArraySet<>();
        // Get the distinct nris to check since for multilayer requests, it is possible to have the
        // same nri in the map's values for each of its NetworkRequest objects.
        final ArraySet<NetworkRequestInfo> nris = new ArraySet<>(mNetworkRequests.values());
        for (final NetworkRequestInfo nri : nris) {
            // Include this nri if it is currently being tracked.
            if (isPerAppTrackedNri(nri)) {
                defaultCallbackRequests.add(nri);
                continue;
            }
            // We only track callbacks for requests tracking the default.
            if (NetworkRequest.Type.TRACK_DEFAULT != nri.mRequests.get(0).type) {
                continue;
            }
            // Include this nri if it will be tracked by the new per-app default requests.
            final boolean isNriGoingToBeTracked =
                    getDefaultRequestTrackingUid(nri.mUid) != mDefaultRequest;
            if (isNriGoingToBeTracked) {
                defaultCallbackRequests.add(nri);
            }
        }
        return defaultCallbackRequests;
    }

    /**
     * Create nris for those network requests that are currently tracking the default network that
     * are being controlled by a per-application default.
     * @param perAppCallbackRequestsForUpdate the baseline network requests to be used as the
     * foundation when creating the nri. Important items include the calling uid's original
     * NetworkRequest to be used when mapping callbacks as well as the caller's uid and name. These
     * requests are assumed to have already been validated as needing to be updated.
     * @return the Set of nris to use when registering network requests.
     */
    private ArraySet<NetworkRequestInfo> createPerAppCallbackRequestsToRegister(
            @NonNull final ArraySet<NetworkRequestInfo> perAppCallbackRequestsForUpdate) {
        final ArraySet<NetworkRequestInfo> callbackRequestsToRegister = new ArraySet<>();
        for (final NetworkRequestInfo callbackRequest : perAppCallbackRequestsForUpdate) {
            final NetworkRequestInfo trackingNri =
                    getDefaultRequestTrackingUid(callbackRequest.mUid);

            // If this nri is not being tracked, the change it back to an untracked nri.
            if (trackingNri == mDefaultRequest) {
                callbackRequestsToRegister.add(new NetworkRequestInfo(
                        callbackRequest,
                        Collections.singletonList(callbackRequest.getNetworkRequestForCallback())));
                continue;
            }

            final String requestorPackageName =
                    callbackRequest.mRequests.get(0).getRequestorPackageName();
            callbackRequestsToRegister.add(new NetworkRequestInfo(
                    callbackRequest,
                    copyNetworkRequestsForUid(
                            trackingNri.mRequests, callbackRequest.mUid, requestorPackageName)));
        }
        return callbackRequestsToRegister;
    }

    /**
     * Class used to generate {@link NetworkRequestInfo} based off of {@link OemNetworkPreferences}.
     */
    @VisibleForTesting
    final class OemNetworkRequestFactory {
        ArraySet<NetworkRequestInfo> createNrisFromOemNetworkPreferences(
                @NonNull final OemNetworkPreferences preference) {
            final ArraySet<NetworkRequestInfo> nris = new ArraySet<>();
            final SparseArray<Set<Integer>> uids =
                    createUidsFromOemNetworkPreferences(preference);
            for (int i = 0; i < uids.size(); i++) {
                final int key = uids.keyAt(i);
                final Set<Integer> value = uids.valueAt(i);
                final NetworkRequestInfo nri = createNriFromOemNetworkPreferences(key, value);
                // No need to add an nri without any requests.
                if (0 == nri.mRequests.size()) {
                    continue;
                }
                nris.add(nri);
            }

            return nris;
        }

        private SparseArray<Set<Integer>> createUidsFromOemNetworkPreferences(
                @NonNull final OemNetworkPreferences preference) {
            final SparseArray<Set<Integer>> uids = new SparseArray<>();
            final PackageManager pm = mContext.getPackageManager();
            for (final Map.Entry<String, Integer> entry :
                    preference.getNetworkPreferences().entrySet()) {
                @OemNetworkPreferences.OemNetworkPreference final int pref = entry.getValue();
                try {
                    final int uid = pm.getApplicationInfo(entry.getKey(), 0).uid;
                    if (!uids.contains(pref)) {
                        uids.put(pref, new ArraySet<>());
                    }
                    uids.get(pref).add(uid);
                } catch (PackageManager.NameNotFoundException e) {
                    // Although this may seem like an error scenario, it is ok that uninstalled
                    // packages are sent on a network preference as the system will watch for
                    // package installations associated with this network preference and update
                    // accordingly. This is done so as to minimize race conditions on app install.
                    // TODO b/177092163 add app install watching.
                    continue;
                }
            }
            return uids;
        }

        private NetworkRequestInfo createNriFromOemNetworkPreferences(
                @OemNetworkPreferences.OemNetworkPreference final int preference,
                @NonNull final Set<Integer> uids) {
            final List<NetworkRequest> requests = new ArrayList<>();
            // Requests will ultimately be evaluated by order of insertion therefore it matters.
            switch (preference) {
                case OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID:
                    requests.add(createUnmeteredNetworkRequest());
                    requests.add(createOemPaidNetworkRequest());
                    requests.add(createDefaultRequest());
                    break;
                case OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_NO_FALLBACK:
                    requests.add(createUnmeteredNetworkRequest());
                    requests.add(createOemPaidNetworkRequest());
                    break;
                case OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PAID_ONLY:
                    requests.add(createOemPaidNetworkRequest());
                    break;
                case OemNetworkPreferences.OEM_NETWORK_PREFERENCE_OEM_PRIVATE_ONLY:
                    requests.add(createOemPrivateNetworkRequest());
                    break;
                default:
                    // This should never happen.
                    throw new IllegalArgumentException("createNriFromOemNetworkPreferences()"
                            + " called with invalid preference of " + preference);
            }

            setOemNetworkRequestUids(requests, uids);
            return new NetworkRequestInfo(requests);
        }

        private NetworkRequest createUnmeteredNetworkRequest() {
            final NetworkCapabilities netcap = createDefaultPerAppNetCap()
                    .addCapability(NET_CAPABILITY_NOT_METERED)
                    .addCapability(NET_CAPABILITY_VALIDATED);
            return createNetworkRequest(NetworkRequest.Type.LISTEN, netcap);
        }

        private NetworkRequest createOemPaidNetworkRequest() {
            // NET_CAPABILITY_OEM_PAID is a restricted capability.
            final NetworkCapabilities netcap = createDefaultPerAppNetCap()
                    .addCapability(NET_CAPABILITY_OEM_PAID)
                    .removeCapability(NET_CAPABILITY_NOT_RESTRICTED);
            return createNetworkRequest(NetworkRequest.Type.REQUEST, netcap);
        }

        private NetworkRequest createOemPrivateNetworkRequest() {
            // NET_CAPABILITY_OEM_PRIVATE is a restricted capability.
            final NetworkCapabilities netcap = createDefaultPerAppNetCap()
                    .addCapability(NET_CAPABILITY_OEM_PRIVATE)
                    .removeCapability(NET_CAPABILITY_NOT_RESTRICTED);
            return createNetworkRequest(NetworkRequest.Type.REQUEST, netcap);
        }

        private NetworkCapabilities createDefaultPerAppNetCap() {
            final NetworkCapabilities netCap = new NetworkCapabilities();
            netCap.addCapability(NET_CAPABILITY_INTERNET);
            netCap.setRequestorUidAndPackageName(Process.myUid(), mContext.getPackageName());
            return netCap;
        }

        private void setOemNetworkRequestUids(@NonNull final List<NetworkRequest> requests,
                @NonNull final Set<Integer> uids) {
            final Set<UidRange> ranges = new ArraySet<>();
            for (final int uid : uids) {
                ranges.add(new UidRange(uid, uid));
            }
            for (final NetworkRequest req : requests) {
                req.networkCapabilities.setUids(ranges);
            }
        }
    }
}
