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

import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.Manifest.permission.RECEIVE_DATA_ACTIVITY_CHANGE;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE;
import static android.net.ConnectivityManager.TYPE_BLUETOOTH;
import static android.net.ConnectivityManager.TYPE_DUMMY;
import static android.net.ConnectivityManager.TYPE_MOBILE;
import static android.net.ConnectivityManager.TYPE_MOBILE_MMS;
import static android.net.ConnectivityManager.TYPE_MOBILE_SUPL;
import static android.net.ConnectivityManager.TYPE_MOBILE_DUN;
import static android.net.ConnectivityManager.TYPE_MOBILE_FOTA;
import static android.net.ConnectivityManager.TYPE_MOBILE_IMS;
import static android.net.ConnectivityManager.TYPE_MOBILE_CBS;
import static android.net.ConnectivityManager.TYPE_MOBILE_IA;
import static android.net.ConnectivityManager.TYPE_MOBILE_HIPRI;
import static android.net.ConnectivityManager.TYPE_NONE;
import static android.net.ConnectivityManager.TYPE_WIFI;
import static android.net.ConnectivityManager.TYPE_WIMAX;
import static android.net.ConnectivityManager.TYPE_PROXY;
import static android.net.ConnectivityManager.getNetworkTypeName;
import static android.net.ConnectivityManager.isNetworkTypeValid;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_METERED;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.LinkProperties.CompareResult;
import android.net.LinkQualityInfo;
import android.net.MobileDataStateTracker;
import android.net.Network;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkConfig;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkFactory;
import android.net.NetworkMisc;
import android.net.NetworkQuotaInfo;
import android.net.NetworkRequest;
import android.net.NetworkState;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.net.Proxy;
import android.net.ProxyDataTracker;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.SamplingDataTracker;
import android.net.UidRange;
import android.net.Uri;
import android.net.wimax.WimaxManagerConstants;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.security.Credentials;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.IBatteryStats;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.NetworkStatsFactory;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;
import com.android.server.am.BatteryStatsService;
import com.android.server.connectivity.DataConnectionStats;
import com.android.server.connectivity.Nat464Xlat;
import com.android.server.connectivity.NetworkAgentInfo;
import com.android.server.connectivity.NetworkMonitor;
import com.android.server.connectivity.PacManager;
import com.android.server.connectivity.Tethering;
import com.android.server.connectivity.Vpn;
import com.android.server.net.BaseNetworkObserver;
import com.android.server.net.LockdownVpnTracker;
import com.google.android.collect.Lists;
import com.google.android.collect.Sets;

import dalvik.system.DexClassLoader;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;

/**
 * @hide
 */
public class ConnectivityService extends IConnectivityManager.Stub {
    private static final String TAG = "ConnectivityService";

    private static final boolean DBG = true;
    private static final boolean VDBG = true; // STOPSHIP

    // network sampling debugging
    private static final boolean SAMPLE_DBG = false;

    private static final boolean LOGD_RULES = false;

    // TODO: create better separation between radio types and network types

    // how long to wait before switching back to a radio's default network
    private static final int RESTORE_DEFAULT_NETWORK_DELAY = 1 * 60 * 1000;
    // system property that can override the above value
    private static final String NETWORK_RESTORE_DELAY_PROP_NAME =
            "android.telephony.apn-restore";

    // Default value if FAIL_FAST_TIME_MS is not set
    private static final int DEFAULT_FAIL_FAST_TIME_MS = 1 * 60 * 1000;
    // system property that can override DEFAULT_FAIL_FAST_TIME_MS
    private static final String FAIL_FAST_TIME_MS =
            "persist.radio.fail_fast_time_ms";

    private static final String ACTION_PKT_CNT_SAMPLE_INTERVAL_ELAPSED =
            "android.net.ConnectivityService.action.PKT_CNT_SAMPLE_INTERVAL_ELAPSED";

    private static final int SAMPLE_INTERVAL_ELAPSED_REQUEST_CODE = 0;

    private PendingIntent mSampleIntervalElapsedIntent;

    // Set network sampling interval at 12 minutes, this way, even if the timers get
    // aggregated, it will fire at around 15 minutes, which should allow us to
    // aggregate this timer with other timers (specially the socket keep alive timers)
    private static final int DEFAULT_SAMPLING_INTERVAL_IN_SECONDS = (SAMPLE_DBG ? 30 : 12 * 60);

    // start network sampling a minute after booting ...
    private static final int DEFAULT_START_SAMPLING_INTERVAL_IN_SECONDS = (SAMPLE_DBG ? 30 : 60);

    AlarmManager mAlarmManager;

    private Tethering mTethering;

    private KeyStore mKeyStore;

    @GuardedBy("mVpns")
    private final SparseArray<Vpn> mVpns = new SparseArray<Vpn>();

    private boolean mLockdownEnabled;
    private LockdownVpnTracker mLockdownTracker;

    private Nat464Xlat mClat;

    /** Lock around {@link #mUidRules} and {@link #mMeteredIfaces}. */
    private Object mRulesLock = new Object();
    /** Currently active network rules by UID. */
    private SparseIntArray mUidRules = new SparseIntArray();
    /** Set of ifaces that are costly. */
    private HashSet<String> mMeteredIfaces = Sets.newHashSet();

    /**
     * Sometimes we want to refer to the individual network state
     * trackers separately, and sometimes we just want to treat them
     * abstractly.
     */
    private NetworkStateTracker mNetTrackers[];

    private Context mContext;
    private int mNetworkPreference;
    private int mActiveDefaultNetwork = -1;
    // 0 is full bad, 100 is full good
    private int mDefaultInetConditionPublished = 0;

    private Object mDnsLock = new Object();
    private int mNumDnsEntries;

    private boolean mTestMode;
    private static ConnectivityService sServiceInstance;

    private INetworkManagementService mNetd;
    private INetworkPolicyManager mPolicyManager;

    private String mCurrentTcpBufferSizes;

    private static final int ENABLED  = 1;
    private static final int DISABLED = 0;

    /**
     * used internally to change our mobile data enabled flag
     */
    private static final int EVENT_CHANGE_MOBILE_DATA_ENABLED = 2;

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
     * used internally to set external dependency met/unmet
     * arg1 = ENABLED (met) or DISABLED (unmet)
     * arg2 = NetworkType
     */
    private static final int EVENT_SET_DEPENDENCY_MET = 10;

    /**
     * used internally to send a sticky broadcast delayed.
     */
    private static final int EVENT_SEND_STICKY_BROADCAST_INTENT = 11;

    /**
     * Used internally to
     * {@link NetworkStateTracker#setPolicyDataEnable(boolean)}.
     */
    private static final int EVENT_SET_POLICY_DATA_ENABLE = 12;

    /**
     * Used internally to disable fail fast of mobile data
     */
    private static final int EVENT_ENABLE_FAIL_FAST_MOBILE_DATA = 14;

    /**
     * used internally to indicate that data sampling interval is up
     */
    private static final int EVENT_SAMPLE_INTERVAL_ELAPSED = 15;

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
     * and if not, call the timeout calback (but leave the request live until they
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


    /** Handler used for internal events. */
    final private InternalHandler mHandler;
    /** Handler used for incoming {@link NetworkStateTracker} events. */
    final private NetworkStateTrackerHandler mTrackerHandler;

    private boolean mSystemReady;
    private Intent mInitialBroadcast;

    private PowerManager.WakeLock mNetTransitionWakeLock;
    private String mNetTransitionWakeLockCausedBy = "";
    private int mNetTransitionWakeLockSerialNumber;
    private int mNetTransitionWakeLockTimeout;

    private InetAddress mDefaultDns;

    // used in DBG mode to track inet condition reports
    private static final int INET_CONDITION_LOG_MAX_SIZE = 15;
    private ArrayList mInetLog;

    // track the current default http proxy - tell the world if we get a new one (real change)
    private ProxyInfo mDefaultProxy = null;
    private Object mProxyLock = new Object();
    private boolean mDefaultProxyDisabled = false;

    // track the global proxy.
    private ProxyInfo mGlobalProxy = null;

    private PacManager mPacManager = null;

    private SettingsObserver mSettingsObserver;

    private UserManager mUserManager;

    NetworkConfig[] mNetConfigs;
    int mNetworksDefined;

    // the set of network types that can only be enabled by system/sig apps
    List mProtectedNetworks;

    private DataConnectionStats mDataConnectionStats;

    private AtomicInteger mEnableFailFastMobileDataTag = new AtomicInteger(0);

    TelephonyManager mTelephonyManager;

    // sequence number for Networks
    private final static int MIN_NET_ID = 10; // some reserved marks
    private final static int MAX_NET_ID = 65535;
    private int mNextNetId = MIN_NET_ID;

    // sequence number of NetworkRequests
    private int mNextNetworkRequestId = 1;

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
        private static final String TAG = "CSLegacyTypeTracker";

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
         */
        private ArrayList<NetworkAgentInfo> mTypeLists[];

        public LegacyTypeTracker() {
            mTypeLists = (ArrayList<NetworkAgentInfo>[])
                    new ArrayList[ConnectivityManager.MAX_NETWORK_TYPE + 1];
        }

        public void addSupportedType(int type) {
            if (mTypeLists[type] != null) {
                throw new IllegalStateException(
                        "legacy list for type " + type + "already initialized");
            }
            mTypeLists[type] = new ArrayList<NetworkAgentInfo>();
        }

        public boolean isTypeSupported(int type) {
            return isNetworkTypeValid(type) && mTypeLists[type] != null;
        }

        public NetworkAgentInfo getNetworkForType(int type) {
            if (isTypeSupported(type) && !mTypeLists[type].isEmpty()) {
                return mTypeLists[type].get(0);
            } else {
                return null;
            }
        }

        private void maybeLogBroadcast(NetworkAgentInfo nai, boolean connected, int type) {
            if (DBG) {
                log("Sending " + (connected ? "connected" : "disconnected") +
                        " broadcast for type " + type + " " + nai.name() +
                        " isDefaultNetwork=" + isDefaultNetwork(nai));
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
                loge("Attempting to register duplicate agent for type " + type + ": " + nai);
                return;
            }

            if (list.isEmpty() || isDefaultNetwork(nai)) {
                maybeLogBroadcast(nai, true, type);
                sendLegacyNetworkBroadcast(nai, true, type);
            }
            list.add(nai);
        }

        /** Removes the given network from the specified legacy type list. */
        public void remove(int type, NetworkAgentInfo nai) {
            ArrayList<NetworkAgentInfo> list = mTypeLists[type];
            if (list == null || list.isEmpty()) {
                return;
            }

            boolean wasFirstNetwork = list.get(0).equals(nai);

            if (!list.remove(nai)) {
                return;
            }

            if (wasFirstNetwork || isDefaultNetwork(nai)) {
                maybeLogBroadcast(nai, false, type);
                sendLegacyNetworkBroadcast(nai, false, type);
            }

            if (!list.isEmpty() && wasFirstNetwork) {
                if (DBG) log("Other network available for type " + type +
                              ", sending connected broadcast");
                maybeLogBroadcast(list.get(0), false, type);
                sendLegacyNetworkBroadcast(list.get(0), false, type);
            }
        }

        /** Removes the given network from all legacy type lists. */
        public void remove(NetworkAgentInfo nai) {
            if (VDBG) log("Removing agent " + nai);
            for (int type = 0; type < mTypeLists.length; type++) {
                remove(type, nai);
            }
        }

        private String naiToString(NetworkAgentInfo nai) {
            String name = (nai != null) ? nai.name() : "null";
            String state = (nai.networkInfo != null) ?
                    nai.networkInfo.getState() + "/" + nai.networkInfo.getDetailedState() :
                    "???/???";
            return name + " " + state;
        }

        public void dump(IndentingPrintWriter pw) {
            for (int type = 0; type < mTypeLists.length; type++) {
                if (mTypeLists[type] == null) continue;
                pw.print(type + " ");
                pw.increaseIndent();
                if (mTypeLists[type].size() == 0) pw.println("none");
                for (NetworkAgentInfo nai : mTypeLists[type]) {
                    pw.println(naiToString(nai));
                }
                pw.decreaseIndent();
            }
        }

        // This class needs its own log method because it has a different TAG.
        private void log(String s) {
            Slog.d(TAG, s);
        }

    }
    private LegacyTypeTracker mLegacyTypeTracker = new LegacyTypeTracker();

    public ConnectivityService(Context context, INetworkManagementService netManager,
            INetworkStatsService statsService, INetworkPolicyManager policyManager) {
        if (DBG) log("ConnectivityService starting up");

        NetworkCapabilities netCap = new NetworkCapabilities();
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        netCap.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        mDefaultRequest = new NetworkRequest(netCap, TYPE_NONE, nextNetworkRequestId());
        NetworkRequestInfo nri = new NetworkRequestInfo(null, mDefaultRequest, new Binder(),
                NetworkRequestInfo.REQUEST);
        mNetworkRequests.put(mDefaultRequest, nri);

        HandlerThread handlerThread = new HandlerThread("ConnectivityServiceThread");
        handlerThread.start();
        mHandler = new InternalHandler(handlerThread.getLooper());
        mTrackerHandler = new NetworkStateTrackerHandler(handlerThread.getLooper());

        // setup our unique device name
        if (TextUtils.isEmpty(SystemProperties.get("net.hostname"))) {
            String id = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (id != null && id.length() > 0) {
                String name = new String("android-").concat(id);
                SystemProperties.set("net.hostname", name);
            }
        }

        // read our default dns server ip
        String dns = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.DEFAULT_DNS_SERVER);
        if (dns == null || dns.length() == 0) {
            dns = context.getResources().getString(
                    com.android.internal.R.string.config_default_dns_server);
        }
        try {
            mDefaultDns = NetworkUtils.numericToInetAddress(dns);
        } catch (IllegalArgumentException e) {
            loge("Error setting defaultDns using " + dns);
        }

        mContext = checkNotNull(context, "missing Context");
        mNetd = checkNotNull(netManager, "missing INetworkManagementService");
        mPolicyManager = checkNotNull(policyManager, "missing INetworkPolicyManager");
        mKeyStore = KeyStore.getInstance();
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);

        try {
            mPolicyManager.registerListener(mPolicyListener);
        } catch (RemoteException e) {
            // ouch, no rules updates means some processes may never get network
            loge("unable to register INetworkPolicyListener" + e.toString());
        }

        final PowerManager powerManager = (PowerManager) context.getSystemService(
                Context.POWER_SERVICE);
        mNetTransitionWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mNetTransitionWakeLockTimeout = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_networkTransitionTimeout);

        mNetTrackers = new NetworkStateTracker[
                ConnectivityManager.MAX_NETWORK_TYPE+1];

        mNetConfigs = new NetworkConfig[ConnectivityManager.MAX_NETWORK_TYPE+1];

        // TODO: What is the "correct" way to do determine if this is a wifi only device?
        boolean wifiOnly = SystemProperties.getBoolean("ro.radio.noril", false);
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

        mTestMode = SystemProperties.get("cm.test.mode").equals("true")
                && SystemProperties.get("ro.build.type").equals("eng");

        mTethering = new Tethering(mContext, mNetd, statsService, mHandler.getLooper());

        //set up the listener for user state for creating user VPNs
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_STARTING);
        intentFilter.addAction(Intent.ACTION_USER_STOPPING);
        mContext.registerReceiverAsUser(
                mUserIntentReceiver, UserHandle.ALL, intentFilter, null, null);
        mClat = new Nat464Xlat(mContext, mNetd, this, mTrackerHandler);

        try {
            mNetd.registerObserver(mTethering);
            mNetd.registerObserver(mDataActivityObserver);
            mNetd.registerObserver(mClat);
        } catch (RemoteException e) {
            loge("Error registering observer :" + e);
        }

        if (DBG) {
            mInetLog = new ArrayList();
        }

        mSettingsObserver = new SettingsObserver(mHandler, EVENT_APPLY_GLOBAL_HTTP_PROXY);
        mSettingsObserver.observe(mContext);

        mDataConnectionStats = new DataConnectionStats(mContext);
        mDataConnectionStats.startMonitoring();

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PKT_CNT_SAMPLE_INTERVAL_ELAPSED);
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        if (action.equals(ACTION_PKT_CNT_SAMPLE_INTERVAL_ELAPSED)) {
                            mHandler.sendMessage(mHandler.obtainMessage
                                    (EVENT_SAMPLE_INTERVAL_ELAPSED));
                        }
                    }
                },
                new IntentFilter(filter));

        mPacManager = new PacManager(mContext, mHandler, EVENT_PROXY_HAS_CHANGED);

        filter = new IntentFilter();
        filter.addAction(CONNECTED_TO_PROVISIONING_NETWORK_ACTION);
        mContext.registerReceiver(mProvisioningReceiver, filter);

        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
    }

    private synchronized int nextNetworkRequestId() {
        return mNextNetworkRequestId++;
    }

    private synchronized int nextNetId() {
        int netId = mNextNetId;
        if (++mNextNetId > MAX_NET_ID) mNextNetId = MIN_NET_ID;
        return netId;
    }

    private int getConnectivityChangeDelay() {
        final ContentResolver cr = mContext.getContentResolver();

        /** Check system properties for the default value then use secure settings value, if any. */
        int defaultDelay = SystemProperties.getInt(
                "conn." + Settings.Global.CONNECTIVITY_CHANGE_DELAY,
                ConnectivityManager.CONNECTIVITY_CHANGE_DELAY_DEFAULT);
        return Settings.Global.getInt(cr, Settings.Global.CONNECTIVITY_CHANGE_DELAY,
                defaultDelay);
    }

    private boolean teardown(NetworkStateTracker netTracker) {
        if (netTracker.teardown()) {
            netTracker.setTeardownRequested(true);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Check if UID should be blocked from using the network represented by the
     * given {@link NetworkStateTracker}.
     */
    private boolean isNetworkBlocked(int networkType, int uid) {
        final boolean networkCostly;
        final int uidRules;

        LinkProperties lp = getLinkPropertiesForType(networkType);
        final String iface = (lp == null ? "" : lp.getInterfaceName());
        synchronized (mRulesLock) {
            networkCostly = mMeteredIfaces.contains(iface);
            uidRules = mUidRules.get(uid, RULE_ALLOW_ALL);
        }

        if (networkCostly && (uidRules & RULE_REJECT_METERED) != 0) {
            return true;
        }

        // no restrictive rules; network is visible
        return false;
    }

    /**
     * Return a filtered {@link NetworkInfo}, potentially marked
     * {@link DetailedState#BLOCKED} based on
     * {@link #isNetworkBlocked}.
     */
    private NetworkInfo getFilteredNetworkInfo(int networkType, int uid) {
        NetworkInfo info = getNetworkInfoForType(networkType);
        return getFilteredNetworkInfo(info, networkType, uid);
    }

    private NetworkInfo getFilteredNetworkInfo(NetworkInfo info, int networkType, int uid) {
        if (isNetworkBlocked(networkType, uid)) {
            // network is blocked; clone and override state
            info = new NetworkInfo(info);
            info.setDetailedState(DetailedState.BLOCKED, null, null);
            if (VDBG) log("returning Blocked NetworkInfo");
        }
        if (mLockdownTracker != null) {
            info = mLockdownTracker.augmentNetworkInfo(info);
            if (VDBG) log("returning Locked NetworkInfo");
        }
        return info;
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
        return getNetworkInfo(mActiveDefaultNetwork, uid);
    }

    // only called when the default request is satisfied
    private void updateActiveDefaultNetwork(NetworkAgentInfo nai) {
        if (nai != null) {
            mActiveDefaultNetwork = nai.networkInfo.getType();
        } else {
            mActiveDefaultNetwork = TYPE_NONE;
        }
    }

    /**
     * Find the first Provisioning network.
     *
     * @return NetworkInfo or null if none.
     */
    private NetworkInfo getProvisioningNetworkInfo() {
        enforceAccessPermission();

        // Find the first Provisioning Network
        NetworkInfo provNi = null;
        for (NetworkInfo ni : getAllNetworkInfo()) {
            if (ni.isConnectedToProvisioningNetwork()) {
                provNi = ni;
                break;
            }
        }
        if (DBG) log("getProvisioningNetworkInfo: X provNi=" + provNi);
        return provNi;
    }

    /**
     * Find the first Provisioning network or the ActiveDefaultNetwork
     * if there is no Provisioning network
     *
     * @return NetworkInfo or null if none.
     */
    @Override
    public NetworkInfo getProvisioningOrActiveNetworkInfo() {
        enforceAccessPermission();

        NetworkInfo provNi = getProvisioningNetworkInfo();
        if (provNi == null) {
            final int uid = Binder.getCallingUid();
            provNi = getNetworkInfo(mActiveDefaultNetwork, uid);
        }
        if (DBG) log("getProvisioningOrActiveNetworkInfo: X provNi=" + provNi);
        return provNi;
    }

    public NetworkInfo getActiveNetworkInfoUnfiltered() {
        enforceAccessPermission();
        if (isNetworkTypeValid(mActiveDefaultNetwork)) {
            return getNetworkInfoForType(mActiveDefaultNetwork);
        }
        return null;
    }

    @Override
    public NetworkInfo getActiveNetworkInfoForUid(int uid) {
        enforceConnectivityInternalPermission();
        return getNetworkInfo(mActiveDefaultNetwork, uid);
    }

    @Override
    public NetworkInfo getNetworkInfo(int networkType) {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        return getNetworkInfo(networkType, uid);
    }

    private NetworkInfo getNetworkInfo(int networkType, int uid) {
        NetworkInfo info = null;
        if (isNetworkTypeValid(networkType)) {
            if (getNetworkInfoForType(networkType) != null) {
                info = getFilteredNetworkInfo(networkType, uid);
            }
        }
        return info;
    }

    @Override
    public NetworkInfo getNetworkInfoForNetwork(Network network) {
        enforceAccessPermission();
        if (network == null) return null;

        final int uid = Binder.getCallingUid();
        NetworkAgentInfo nai = null;
        synchronized (mNetworkForNetId) {
            nai = mNetworkForNetId.get(network.netId);
        }
        if (nai == null) return null;
        synchronized (nai) {
            if (nai.networkInfo == null) return null;

            return getFilteredNetworkInfo(nai.networkInfo, nai.networkInfo.getType(), uid);
        }
    }

    @Override
    public NetworkInfo[] getAllNetworkInfo() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final ArrayList<NetworkInfo> result = Lists.newArrayList();
        synchronized (mRulesLock) {
            for (int networkType = 0; networkType <= ConnectivityManager.MAX_NETWORK_TYPE;
                    networkType++) {
                if (getNetworkInfoForType(networkType) != null) {
                    result.add(getFilteredNetworkInfo(networkType, uid));
                }
            }
        }
        return result.toArray(new NetworkInfo[result.size()]);
    }

    @Override
    public Network[] getAllNetworks() {
        enforceAccessPermission();
        final ArrayList<Network> result = new ArrayList();
        synchronized (mNetworkForNetId) {
            for (int i = 0; i < mNetworkForNetId.size(); i++) {
                result.add(new Network(mNetworkForNetId.valueAt(i).network));
            }
        }
        return result.toArray(new Network[result.size()]);
    }

    @Override
    public boolean isNetworkSupported(int networkType) {
        enforceAccessPermission();
        return (isNetworkTypeValid(networkType) && (getNetworkInfoForType(networkType) != null));
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
        return getLinkPropertiesForType(mActiveDefaultNetwork);
    }

    @Override
    public LinkProperties getLinkPropertiesForType(int networkType) {
        enforceAccessPermission();
        if (isNetworkTypeValid(networkType)) {
            return getLinkPropertiesForTypeInternal(networkType);
        }
        return null;
    }

    // TODO - this should be ALL networks
    @Override
    public LinkProperties getLinkProperties(Network network) {
        enforceAccessPermission();
        NetworkAgentInfo nai = null;
        synchronized (mNetworkForNetId) {
            nai = mNetworkForNetId.get(network.netId);
        }

        if (nai != null) {
            synchronized (nai) {
                return new LinkProperties(nai.linkProperties);
            }
        }
        return null;
    }

    @Override
    public NetworkCapabilities getNetworkCapabilities(Network network) {
        enforceAccessPermission();
        NetworkAgentInfo nai = null;
        synchronized (mNetworkForNetId) {
            nai = mNetworkForNetId.get(network.netId);
        }
        if (nai != null) {
            synchronized (nai) {
                return new NetworkCapabilities(nai.networkCapabilities);
            }
        }
        return null;
    }

    @Override
    public NetworkState[] getAllNetworkState() {
        enforceAccessPermission();
        final int uid = Binder.getCallingUid();
        final ArrayList<NetworkState> result = Lists.newArrayList();
        synchronized (mRulesLock) {
            for (int networkType = 0; networkType <= ConnectivityManager.MAX_NETWORK_TYPE;
                    networkType++) {
                if (getNetworkInfoForType(networkType) != null) {
                    final NetworkInfo info = getFilteredNetworkInfo(networkType, uid);
                    final LinkProperties lp = getLinkPropertiesForTypeInternal(networkType);
                    final NetworkCapabilities netcap = getNetworkCapabilitiesForType(networkType);
                    result.add(new NetworkState(info, lp, netcap));
                }
            }
        }
        return result.toArray(new NetworkState[result.size()]);
    }

    private NetworkState getNetworkStateUnchecked(int networkType) {
        if (isNetworkTypeValid(networkType)) {
            NetworkInfo info = getNetworkInfoForType(networkType);
            if (info != null) {
                return new NetworkState(info,
                        getLinkPropertiesForTypeInternal(networkType),
                        getNetworkCapabilitiesForType(networkType));
            }
        }
        return null;
    }

    @Override
    public NetworkQuotaInfo getActiveNetworkQuotaInfo() {
        enforceAccessPermission();

        final long token = Binder.clearCallingIdentity();
        try {
            final NetworkState state = getNetworkStateUnchecked(mActiveDefaultNetwork);
            if (state != null) {
                try {
                    return mPolicyManager.getNetworkQuotaInfo(state);
                } catch (RemoteException e) {
                }
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isActiveNetworkMetered() {
        enforceAccessPermission();
        final long token = Binder.clearCallingIdentity();
        try {
            return isNetworkMeteredUnchecked(mActiveDefaultNetwork);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isNetworkMeteredUnchecked(int networkType) {
        final NetworkState state = getNetworkStateUnchecked(networkType);
        if (state != null) {
            try {
                return mPolicyManager.isNetworkMetered(state);
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    private INetworkManagementEventObserver mDataActivityObserver = new BaseNetworkObserver() {
        @Override
        public void interfaceClassDataActivityChanged(String label, boolean active, long tsNanos) {
            int deviceType = Integer.parseInt(label);
            sendDataActivityBroadcast(deviceType, active, tsNanos);
        }
    };

    /**
     * Ensure that a network route exists to deliver traffic to the specified
     * host via the specified network interface.
     * @param networkType the type of the network over which traffic to the
     * specified host is to be routed
     * @param hostAddress the IP address of the host to which the route is
     * desired
     * @return {@code true} on success, {@code false} on failure
     */
    public boolean requestRouteToHostAddress(int networkType, byte[] hostAddress) {
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
        if (VDBG) log("Adding " + bestRoute + " for interface " + bestRoute.getInterface());
        try {
            mNetd.addLegacyRouteForNetId(netId, bestRoute, uid);
        } catch (Exception e) {
            // never crash - catch them all
            if (DBG) loge("Exception trying to add a route: " + e);
            return false;
        }
        return true;
    }

    public void setDataDependency(int networkType, boolean met) {
        enforceConnectivityInternalPermission();

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SET_DEPENDENCY_MET,
                (met ? ENABLED : DISABLED), networkType));
    }

    private void handleSetDependencyMet(int networkType, boolean met) {
        if (mNetTrackers[networkType] != null) {
            if (DBG) {
                log("handleSetDependencyMet(" + networkType + ", " + met + ")");
            }
            mNetTrackers[networkType].setDependencyMet(met);
        }
    }

    private INetworkPolicyListener mPolicyListener = new INetworkPolicyListener.Stub() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onUidRulesChanged(uid=" + uid + ", uidRules=" + uidRules + ")");
            }

            synchronized (mRulesLock) {
                // skip update when we've already applied rules
                final int oldRules = mUidRules.get(uid, RULE_ALLOW_ALL);
                if (oldRules == uidRules) return;

                mUidRules.put(uid, uidRules);
            }

            // TODO: notify UID when it has requested targeted updates
        }

        @Override
        public void onMeteredIfacesChanged(String[] meteredIfaces) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onMeteredIfacesChanged(ifaces=" + Arrays.toString(meteredIfaces) + ")");
            }

            synchronized (mRulesLock) {
                mMeteredIfaces.clear();
                for (String iface : meteredIfaces) {
                    mMeteredIfaces.add(iface);
                }
            }
        }

        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            // caller is NPMS, since we only register with them
            if (LOGD_RULES) {
                log("onRestrictBackgroundChanged(restrictBackground=" + restrictBackground + ")");
            }

            // kick off connectivity change broadcast for active network, since
            // global background policy change is radical.
            final int networkType = mActiveDefaultNetwork;
            if (isNetworkTypeValid(networkType)) {
                final NetworkStateTracker tracker = mNetTrackers[networkType];
                if (tracker != null) {
                    final NetworkInfo info = tracker.getNetworkInfo();
                    if (info != null && info.isConnected()) {
                        sendConnectedBroadcast(info);
                    }
                }
            }
        }
    };

    @Override
    public void setPolicyDataEnable(int networkType, boolean enabled) {
        // only someone like NPMS should only be calling us
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        mHandler.sendMessage(mHandler.obtainMessage(
                EVENT_SET_POLICY_DATA_ENABLE, networkType, (enabled ? ENABLED : DISABLED)));
    }

    private void handleSetPolicyDataEnable(int networkType, boolean enabled) {
   // TODO - handle this passing to factories
//        if (isNetworkTypeValid(networkType)) {
//            final NetworkStateTracker tracker = mNetTrackers[networkType];
//            if (tracker != null) {
//                tracker.setPolicyDataEnable(enabled);
//            }
//        }
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.ACCESS_NETWORK_STATE,
                "ConnectivityService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                "ConnectivityService");
    }

    // TODO Make this a special check when it goes public
    private void enforceTetherChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_NETWORK_STATE,
                "ConnectivityService");
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

    public void sendConnectedBroadcast(NetworkInfo info) {
        enforceConnectivityInternalPermission();
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION_IMMEDIATE);
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION);
    }

    private void sendConnectedBroadcastDelayed(NetworkInfo info, int delayMs) {
        sendGeneralBroadcast(info, CONNECTIVITY_ACTION_IMMEDIATE);
        sendGeneralBroadcastDelayed(info, CONNECTIVITY_ACTION, delayMs);
    }

    private void sendInetConditionBroadcast(NetworkInfo info) {
        sendGeneralBroadcast(info, ConnectivityManager.INET_CONDITION_ACTION);
    }

    private Intent makeGeneralIntent(NetworkInfo info, String bcastType) {
        if (mLockdownTracker != null) {
            info = mLockdownTracker.augmentNetworkInfo(info);
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

    private void sendGeneralBroadcastDelayed(NetworkInfo info, String bcastType, int delayMs) {
        sendStickyBroadcastDelayed(makeGeneralIntent(info, bcastType), delayMs);
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
        synchronized(this) {
            if (!mSystemReady) {
                mInitialBroadcast = new Intent(intent);
            }
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (VDBG) {
                log("sendStickyBroadcast: action=" + intent.getAction());
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private void sendStickyBroadcastDelayed(Intent intent, int delayMs) {
        if (delayMs <= 0) {
            sendStickyBroadcast(intent);
        } else {
            if (VDBG) {
                log("sendStickyBroadcastDelayed: delayMs=" + delayMs + ", action="
                        + intent.getAction());
            }
            mHandler.sendMessageDelayed(mHandler.obtainMessage(
                    EVENT_SEND_STICKY_BROADCAST_INTENT, intent), delayMs);
        }
    }

    void systemReady() {
        // start network sampling ..
        Intent intent = new Intent(ACTION_PKT_CNT_SAMPLE_INTERVAL_ELAPSED);
        intent.setPackage(mContext.getPackageName());

        mSampleIntervalElapsedIntent = PendingIntent.getBroadcast(mContext,
                SAMPLE_INTERVAL_ELAPSED_REQUEST_CODE, intent, 0);
        setAlarm(DEFAULT_START_SAMPLING_INTERVAL_IN_SECONDS * 1000, mSampleIntervalElapsedIntent);

        loadGlobalProxy();

        synchronized(this) {
            mSystemReady = true;
            if (mInitialBroadcast != null) {
                mContext.sendStickyBroadcastAsUser(mInitialBroadcast, UserHandle.ALL);
                mInitialBroadcast = null;
            }
        }
        // load the global proxy at startup
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_APPLY_GLOBAL_HTTP_PROXY));

        // Try bringing up tracker, but if KeyStore isn't ready yet, wait
        // for user to unlock device.
        if (!updateLockdownVpn()) {
            final IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
            mContext.registerReceiver(mUserPresentReceiver, filter);
        }

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_SYSTEM_READY));
    }

    private BroadcastReceiver mUserPresentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Try creating lockdown tracker, since user present usually means
            // unlocked keystore.
            if (updateLockdownVpn()) {
                mContext.unregisterReceiver(this);
            }
        }
    };

    /** @hide */
    @Override
    public void captivePortalCheckCompleted(NetworkInfo info, boolean isCaptivePortal) {
        enforceConnectivityInternalPermission();
        if (DBG) log("captivePortalCheckCompleted: ni=" + info + " captive=" + isCaptivePortal);
//        mNetTrackers[info.getType()].captivePortalCheckCompleted(isCaptivePortal);
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
                                             5);
            type = ConnectivityManager.TYPE_MOBILE;
        } else if (networkAgent.networkCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI)) {
            timeout = Settings.Global.getInt(mContext.getContentResolver(),
                                             Settings.Global.DATA_ACTIVITY_TIMEOUT_WIFI,
                                             0);
            type = ConnectivityManager.TYPE_WIFI;
        } else {
            // do not track any other networks
            timeout = 0;
        }

        if (timeout > 0 && iface != null && type != ConnectivityManager.TYPE_NONE) {
            try {
                mNetd.addIdleTimer(iface, timeout, type);
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
                // the call fails silently if no idletimer setup for this interface
                mNetd.removeIdleTimer(iface);
            } catch (Exception e) {
                loge("Exception in removeDataActivityTracking " + e);
            }
        }
    }

    /**
     * Reads the network specific MTU size from reources.
     * and set it on it's iface.
     */
    private void updateMtu(LinkProperties newLp, LinkProperties oldLp) {
        final String iface = newLp.getInterfaceName();
        final int mtu = newLp.getMtu();
        if (oldLp != null && newLp.isIdenticalMtu(oldLp)) {
            if (VDBG) log("identical MTU - not setting");
            return;
        }

        if (LinkProperties.isValidMtu(mtu, newLp.hasGlobalIPv6Address()) == false) {
            loge("Unexpected mtu value: " + mtu + ", " + iface);
            return;
        }

        // Cannot set MTU without interface name
        if (TextUtils.isEmpty(iface)) {
            loge("Setting MTU size with null iface.");
            return;
        }

        try {
            if (VDBG) log("Setting MTU size: " + iface + ", " + mtu);
            mNetd.setMtu(iface, mtu);
        } catch (Exception e) {
            Slog.e(TAG, "exception in setMtu()" + e);
        }
    }

    private static final String DEFAULT_TCP_BUFFER_SIZES = "4096,87380,110208,4096,16384,110208";

    private void updateTcpBufferSizes(NetworkAgentInfo nai) {
        if (isDefaultNetwork(nai) == false) {
            return;
        }

        String tcpBufferSizes = nai.linkProperties.getTcpBufferSizes();
        String[] values = null;
        if (tcpBufferSizes != null) {
            values = tcpBufferSizes.split(",");
        }

        if (values == null || values.length != 6) {
            if (VDBG) log("Invalid tcpBufferSizes string: " + tcpBufferSizes +", using defaults");
            tcpBufferSizes = DEFAULT_TCP_BUFFER_SIZES;
            values = tcpBufferSizes.split(",");
        }

        if (tcpBufferSizes.equals(mCurrentTcpBufferSizes)) return;

        try {
            if (VDBG) Slog.d(TAG, "Setting tx/rx TCP buffers to " + tcpBufferSizes);

            final String prefix = "/sys/kernel/ipv4/tcp_";
            FileUtils.stringToFile(prefix + "rmem_min", values[0]);
            FileUtils.stringToFile(prefix + "rmem_def", values[1]);
            FileUtils.stringToFile(prefix + "rmem_max", values[2]);
            FileUtils.stringToFile(prefix + "wmem_min", values[3]);
            FileUtils.stringToFile(prefix + "wmem_def", values[4]);
            FileUtils.stringToFile(prefix + "wmem_max", values[5]);
            mCurrentTcpBufferSizes = tcpBufferSizes;
        } catch (IOException e) {
            loge("Can't set TCP buffer sizes:" + e);
        }

        final String defaultRwndKey = "net.tcp.default_init_rwnd";
        int defaultRwndValue = SystemProperties.getInt(defaultRwndKey, 0);
        Integer rwndValue = Settings.Global.getInt(mContext.getContentResolver(),
            Settings.Global.TCP_DEFAULT_INIT_RWND, defaultRwndValue);
        final String sysctlKey = "sys.sysctl.tcp_def_init_rwnd";
        if (rwndValue != 0) {
            SystemProperties.set(sysctlKey, rwndValue.toString());
        }
    }

    private void flushVmDnsCache() {
        /*
         * Tell the VMs to toss their DNS caches
         */
        Intent intent = new Intent(Intent.ACTION_CLEAR_DNS_CACHE);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        /*
         * Connectivity events can happen before boot has completed ...
         */
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int getRestoreDefaultNetworkDelay(int networkType) {
        String restoreDefaultNetworkDelayStr = SystemProperties.get(
                NETWORK_RESTORE_DELAY_PROP_NAME);
        if(restoreDefaultNetworkDelayStr != null &&
                restoreDefaultNetworkDelayStr.length() != 0) {
            try {
                return Integer.valueOf(restoreDefaultNetworkDelayStr);
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

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ConnectivityService " +
                    "from from pid=" + Binder.getCallingPid() + ", uid=" +
                    Binder.getCallingUid());
            return;
        }

        pw.println("NetworkFactories for:");
        pw.increaseIndent();
        for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
            pw.println(nfi.name);
        }
        pw.decreaseIndent();
        pw.println();

        NetworkAgentInfo defaultNai = mNetworkForRequestId.get(mDefaultRequest.requestId);
        pw.print("Active default network: ");
        if (defaultNai == null) {
            pw.println("none");
        } else {
            pw.println(defaultNai.network.netId);
        }
        pw.println();

        pw.println("Current Networks:");
        pw.increaseIndent();
        for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
            pw.println(nai.toString());
            pw.increaseIndent();
            pw.println("Requests:");
            pw.increaseIndent();
            for (int i = 0; i < nai.networkRequests.size(); i++) {
                pw.println(nai.networkRequests.valueAt(i).toString());
            }
            pw.decreaseIndent();
            pw.println("Lingered:");
            pw.increaseIndent();
            for (NetworkRequest nr : nai.networkLingered) pw.println(nr.toString());
            pw.decreaseIndent();
            pw.decreaseIndent();
        }
        pw.decreaseIndent();
        pw.println();

        pw.println("Network Requests:");
        pw.increaseIndent();
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            pw.println(nri.toString());
        }
        pw.println();
        pw.decreaseIndent();

        pw.print("mActiveDefaultNetwork: " + mActiveDefaultNetwork);
        if (mActiveDefaultNetwork != TYPE_NONE) {
            NetworkInfo activeNetworkInfo = getActiveNetworkInfo();
            if (activeNetworkInfo != null) {
                pw.print(" " + activeNetworkInfo.getState() +
                         "/" + activeNetworkInfo.getDetailedState());
            }
        }
        pw.println();

        pw.println("mLegacyTypeTracker:");
        pw.increaseIndent();
        mLegacyTypeTracker.dump(pw);
        pw.decreaseIndent();
        pw.println();

        synchronized (this) {
            pw.println("NetworkTranstionWakeLock is currently " +
                    (mNetTransitionWakeLock.isHeld() ? "" : "not ") + "held.");
            pw.println("It was last requested for "+mNetTransitionWakeLockCausedBy);
        }
        pw.println();

        mTethering.dump(fd, pw, args);

        if (mInetLog != null) {
            pw.println();
            pw.println("Inet condition reports:");
            pw.increaseIndent();
            for(int i = 0; i < mInetLog.size(); i++) {
                pw.println(mInetLog.get(i));
            }
            pw.decreaseIndent();
        }
    }

    private boolean isLiveNetworkAgent(NetworkAgentInfo nai, String msg) {
        final NetworkAgentInfo officialNai;
        synchronized (mNetworkForNetId) {
            officialNai = mNetworkForNetId.get(nai.network.netId);
        }
        if (officialNai != null && officialNai.equals(nai)) return true;
        if (officialNai != null || VDBG) {
            loge(msg + " - validateNetworkAgent found mismatched netId: " + officialNai +
                " - " + nai);
        }
        return false;
    }

    // must be stateless - things change under us.
    private class NetworkStateTrackerHandler extends Handler {
        public NetworkStateTrackerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            NetworkInfo info;
            switch (msg.what) {
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
                case NetworkAgent.EVENT_NETWORK_CAPABILITIES_CHANGED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_NETWORK_CAPABILITIES_CHANGED from unknown NetworkAgent");
                    } else {
                        updateCapabilities(nai, (NetworkCapabilities)msg.obj);
                    }
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_PROPERTIES_CHANGED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("NetworkAgent not found for EVENT_NETWORK_PROPERTIES_CHANGED");
                    } else {
                        if (VDBG) {
                            log("Update of Linkproperties for " + nai.name() +
                                    "; created=" + nai.created);
                        }
                        LinkProperties oldLp = nai.linkProperties;
                        synchronized (nai) {
                            nai.linkProperties = (LinkProperties)msg.obj;
                        }
                        if (nai.created) updateLinkProperties(nai, oldLp);
                    }
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_INFO_CHANGED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_NETWORK_INFO_CHANGED from unknown NetworkAgent");
                        break;
                    }
                    info = (NetworkInfo) msg.obj;
                    updateNetworkInfo(nai, info);
                    break;
                }
                case NetworkAgent.EVENT_NETWORK_SCORE_CHANGED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_NETWORK_SCORE_CHANGED from unknown NetworkAgent");
                        break;
                    }
                    Integer score = (Integer) msg.obj;
                    if (score != null) updateNetworkScore(nai, score.intValue());
                    break;
                }
                case NetworkAgent.EVENT_UID_RANGES_ADDED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_UID_RANGES_ADDED from unknown NetworkAgent");
                        break;
                    }
                    try {
                        mNetd.addVpnUidRanges(nai.network.netId, (UidRange[])msg.obj);
                    } catch (Exception e) {
                        // Never crash!
                        loge("Exception in addVpnUidRanges: " + e);
                    }
                    break;
                }
                case NetworkAgent.EVENT_UID_RANGES_REMOVED: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_UID_RANGES_REMOVED from unknown NetworkAgent");
                        break;
                    }
                    try {
                        mNetd.removeVpnUidRanges(nai.network.netId, (UidRange[])msg.obj);
                    } catch (Exception e) {
                        // Never crash!
                        loge("Exception in removeVpnUidRanges: " + e);
                    }
                    break;
                }
                case NetworkAgent.EVENT_BLOCK_ADDRESS_FAMILY: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_BLOCK_ADDRESS_FAMILY from unknown NetworkAgent");
                        break;
                    }
                    try {
                        mNetd.blockAddressFamily((Integer) msg.obj, nai.network.netId,
                                nai.linkProperties.getInterfaceName());
                    } catch (Exception e) {
                        // Never crash!
                        loge("Exception in blockAddressFamily: " + e);
                    }
                    break;
                }
                case NetworkAgent.EVENT_UNBLOCK_ADDRESS_FAMILY: {
                    NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
                    if (nai == null) {
                        loge("EVENT_UNBLOCK_ADDRESS_FAMILY from unknown NetworkAgent");
                        break;
                    }
                    try {
                        mNetd.unblockAddressFamily((Integer) msg.obj, nai.network.netId,
                                nai.linkProperties.getInterfaceName());
                    } catch (Exception e) {
                        // Never crash!
                        loge("Exception in blockAddressFamily: " + e);
                    }
                    break;
                }
                case NetworkMonitor.EVENT_NETWORK_VALIDATED: {
                    NetworkAgentInfo nai = (NetworkAgentInfo)msg.obj;
                    if (isLiveNetworkAgent(nai, "EVENT_NETWORK_VALIDATED")) {
                        handleConnectionValidated(nai);
                    }
                    break;
                }
                case NetworkMonitor.EVENT_NETWORK_LINGER_COMPLETE: {
                    NetworkAgentInfo nai = (NetworkAgentInfo)msg.obj;
                    if (isLiveNetworkAgent(nai, "EVENT_NETWORK_LINGER_COMPLETE")) {
                        handleLingerComplete(nai);
                    }
                    break;
                }
                case NetworkMonitor.EVENT_PROVISIONING_NOTIFICATION: {
                    NetworkAgentInfo nai = null;
                    synchronized (mNetworkForNetId) {
                        nai = mNetworkForNetId.get(msg.arg2);
                    }
                    if (nai == null) {
                        loge("EVENT_PROVISIONING_NOTIFICATION from unknown NetworkMonitor");
                        break;
                    }
                    if (msg.arg1 == 0) {
                        setProvNotificationVisibleIntent(false, msg.arg2, 0, null, null);
                    } else {
                        setProvNotificationVisibleIntent(true, msg.arg2, nai.networkInfo.getType(),
                                nai.networkInfo.getExtraInfo(), (PendingIntent)msg.obj);
                    }
                    break;
                }
                case NetworkStateTracker.EVENT_STATE_CHANGED: {
                    info = (NetworkInfo) msg.obj;
                    NetworkInfo.State state = info.getState();

                    if (VDBG || (state == NetworkInfo.State.CONNECTED) ||
                            (state == NetworkInfo.State.DISCONNECTED) ||
                            (state == NetworkInfo.State.SUSPENDED)) {
                        log("ConnectivityChange for " +
                            info.getTypeName() + ": " +
                            state + "/" + info.getDetailedState());
                    }

                    // Since mobile has the notion of a network/apn that can be used for
                    // provisioning we need to check every time we're connected as
                    // CaptiveProtalTracker won't detected it because DCT doesn't report it
                    // as connected as ACTION_ANY_DATA_CONNECTION_STATE_CHANGED instead its
                    // reported as ACTION_DATA_CONNECTION_CONNECTED_TO_PROVISIONING_APN. Which
                    // is received by MDST and sent here as EVENT_STATE_CHANGED.
                    if (ConnectivityManager.isNetworkTypeMobile(info.getType())
                            && (0 != Settings.Global.getInt(mContext.getContentResolver(),
                                        Settings.Global.DEVICE_PROVISIONED, 0))
                            && (((state == NetworkInfo.State.CONNECTED)
                                    && (info.getType() == ConnectivityManager.TYPE_MOBILE))
                                || info.isConnectedToProvisioningNetwork())) {
                        log("ConnectivityChange checkMobileProvisioning for"
                                + " TYPE_MOBILE or ProvisioningNetwork");
                        checkMobileProvisioning(CheckMp.MAX_TIMEOUT_MS);
                    }

                    EventLogTags.writeConnectivityStateChanged(
                            info.getType(), info.getSubtype(), info.getDetailedState().ordinal());

                    if (info.isConnectedToProvisioningNetwork()) {
                        /**
                         * TODO: Create ConnectivityManager.TYPE_MOBILE_PROVISIONING
                         * for now its an in between network, its a network that
                         * is actually a default network but we don't want it to be
                         * announced as such to keep background applications from
                         * trying to use it. It turns out that some still try so we
                         * take the additional step of clearing any default routes
                         * to the link that may have incorrectly setup by the lower
                         * levels.
                         */
                        LinkProperties lp = getLinkPropertiesForTypeInternal(info.getType());
                        if (DBG) {
                            log("EVENT_STATE_CHANGED: connected to provisioning network, lp=" + lp);
                        }

                        // Clear any default routes setup by the radio so
                        // any activity by applications trying to use this
                        // connection will fail until the provisioning network
                        // is enabled.
                        /*
                        for (RouteInfo r : lp.getRoutes()) {
                            removeRoute(lp, r, TO_DEFAULT_TABLE,
                                        mNetTrackers[info.getType()].getNetwork().netId);
                        }
                        */
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                    } else if (state == NetworkInfo.State.SUSPENDED) {
                    } else if (state == NetworkInfo.State.CONNECTED) {
                    //    handleConnect(info);
                    }
                    if (mLockdownTracker != null) {
                        mLockdownTracker.onNetworkInfoChanged(info);
                    }
                    break;
                }
                case NetworkStateTracker.EVENT_CONFIGURATION_CHANGED: {
                    info = (NetworkInfo) msg.obj;
                    // TODO: Temporary allowing network configuration
                    //       change not resetting sockets.
                    //       @see bug/4455071
                    /*
                    handleConnectivityChange(info.getType(), mCurrentLinkProperties[info.getType()],
                            false);
                    */
                    break;
                }
            }
        }
    }

    private void handleAsyncChannelHalfConnect(Message msg) {
        AsyncChannel ac = (AsyncChannel) msg.obj;
        if (mNetworkFactoryInfos.containsKey(msg.replyTo)) {
            if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                if (VDBG) log("NetworkFactory connected");
                // A network factory has connected.  Send it all current NetworkRequests.
                for (NetworkRequestInfo nri : mNetworkRequests.values()) {
                    if (nri.isRequest == false) continue;
                    NetworkAgentInfo nai = mNetworkForRequestId.get(nri.request.requestId);
                    ac.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK,
                            (nai != null ? nai.currentScore : 0), 0, nri.request);
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
                    synchronized (mNetworkForNetId) {
                        mNetworkForNetId.remove(nai.network.netId);
                    }
                    // Just in case.
                    mLegacyTypeTracker.remove(nai);
                }
            }
        }
    }
    private void handleAsyncChannelDisconnected(Message msg) {
        NetworkAgentInfo nai = mNetworkAgentInfos.get(msg.replyTo);
        if (nai != null) {
            if (DBG) {
                log(nai.name() + " got DISCONNECTED, was satisfying " + nai.networkRequests.size());
            }
            // A network agent has disconnected.
            if (nai.created) {
                // Tell netd to clean up the configuration for this network
                // (routing rules, DNS, etc).
                try {
                    mNetd.removeNetwork(nai.network.netId);
                } catch (Exception e) {
                    loge("Exception removing network: " + e);
                }
            }
            // TODO - if we move the logic to the network agent (have them disconnect
            // because they lost all their requests or because their score isn't good)
            // then they would disconnect organically, report their new state and then
            // disconnect the channel.
            if (nai.networkInfo.isConnected()) {
                nai.networkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED,
                        null, null);
            }
            if (isDefaultNetwork(nai)) {
                mDefaultInetConditionPublished = 0;
            }
            notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOST);
            nai.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_DISCONNECTED);
            mNetworkAgentInfos.remove(msg.replyTo);
            updateClat(null, nai.linkProperties, nai);
            mLegacyTypeTracker.remove(nai);
            synchronized (mNetworkForNetId) {
                mNetworkForNetId.remove(nai.network.netId);
            }
            // Since we've lost the network, go through all the requests that
            // it was satisfying and see if any other factory can satisfy them.
            final ArrayList<NetworkAgentInfo> toActivate = new ArrayList<NetworkAgentInfo>();
            for (int i = 0; i < nai.networkRequests.size(); i++) {
                NetworkRequest request = nai.networkRequests.valueAt(i);
                NetworkAgentInfo currentNetwork = mNetworkForRequestId.get(request.requestId);
                if (VDBG) {
                    log(" checking request " + request + ", currentNetwork = " +
                            (currentNetwork != null ? currentNetwork.name() : "null"));
                }
                if (currentNetwork != null && currentNetwork.network.netId == nai.network.netId) {
                    mNetworkForRequestId.remove(request.requestId);
                    sendUpdatedScoreToFactories(request, 0);
                    NetworkAgentInfo alternative = null;
                    for (Map.Entry entry : mNetworkAgentInfos.entrySet()) {
                        NetworkAgentInfo existing = (NetworkAgentInfo)entry.getValue();
                        if (existing.networkInfo.isConnected() &&
                                request.networkCapabilities.satisfiedByNetworkCapabilities(
                                existing.networkCapabilities) &&
                                (alternative == null ||
                                 alternative.currentScore < existing.currentScore)) {
                            alternative = existing;
                        }
                    }
                    if (alternative != null && !toActivate.contains(alternative)) {
                        toActivate.add(alternative);
                    }
                }
            }
            if (nai.networkRequests.get(mDefaultRequest.requestId) != null) {
                removeDataActivityTracking(nai);
                mActiveDefaultNetwork = ConnectivityManager.TYPE_NONE;
                requestNetworkTransitionWakelock(nai.name());
            }
            for (NetworkAgentInfo networkToActivate : toActivate) {
                networkToActivate.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_CONNECTED);
            }
        }
    }

    private void handleRegisterNetworkRequest(Message msg) {
        final NetworkRequestInfo nri = (NetworkRequestInfo) (msg.obj);
        final NetworkCapabilities newCap = nri.request.networkCapabilities;
        int score = 0;

        // Check for the best currently alive network that satisfies this request
        NetworkAgentInfo bestNetwork = null;
        for (NetworkAgentInfo network : mNetworkAgentInfos.values()) {
            if (VDBG) log("handleRegisterNetworkRequest checking " + network.name());
            if (newCap.satisfiedByNetworkCapabilities(network.networkCapabilities)) {
                if (VDBG) log("apparently satisfied.  currentScore=" + network.currentScore);
                if ((bestNetwork == null) || bestNetwork.currentScore < network.currentScore) {
                    bestNetwork = network;
                }
            }
        }
        if (bestNetwork != null) {
            if (VDBG) log("using " + bestNetwork.name());
            if (nri.isRequest && bestNetwork.networkInfo.isConnected()) {
                // Cancel any lingering so the linger timeout doesn't teardown this network
                // even though we have a request for it.
                bestNetwork.networkLingered.clear();
                bestNetwork.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_CONNECTED);
            }
            bestNetwork.addRequest(nri.request);
            mNetworkForRequestId.put(nri.request.requestId, bestNetwork);
            notifyNetworkCallback(bestNetwork, nri);
            score = bestNetwork.currentScore;
            if (nri.isRequest && nri.request.legacyType != TYPE_NONE) {
                mLegacyTypeTracker.add(nri.request.legacyType, bestNetwork);
            }
        }
        mNetworkRequests.put(nri.request, nri);
        if (nri.isRequest) {
            if (DBG) log("sending new NetworkRequest to factories");
            for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
                nfi.asyncChannel.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK, score,
                        0, nri.request);
            }
        }
    }

    private void handleReleaseNetworkRequest(NetworkRequest request, int callingUid) {
        NetworkRequestInfo nri = mNetworkRequests.get(request);
        if (nri != null) {
            if (nri.mUid != callingUid) {
                if (DBG) log("Attempt to release unowned NetworkRequest " + request);
                return;
            }
            if (DBG) log("releasing NetworkRequest " + request);
            nri.unlinkDeathRecipient();
            mNetworkRequests.remove(request);
            // tell the network currently servicing this that it's no longer interested
            NetworkAgentInfo affectedNetwork = mNetworkForRequestId.get(nri.request.requestId);
            if (affectedNetwork != null) {
                mNetworkForRequestId.remove(nri.request.requestId);
                affectedNetwork.networkRequests.remove(nri.request.requestId);
                if (VDBG) {
                    log(" Removing from current network " + affectedNetwork.name() + ", leaving " +
                            affectedNetwork.networkRequests.size() + " requests.");
                }
                if (nri.isRequest && nri.request.legacyType != TYPE_NONE) {
                    mLegacyTypeTracker.remove(nri.request.legacyType, affectedNetwork);
                }
            }

            if (nri.isRequest) {
                for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
                    nfi.asyncChannel.sendMessage(android.net.NetworkFactory.CMD_CANCEL_REQUEST,
                            nri.request);
                }

                if (affectedNetwork != null) {
                    // check if this network still has live requests - otherwise, tear down
                    // TODO - probably push this to the NF/NA
                    boolean keep = affectedNetwork.isVPN();
                    for (int i = 0; i < affectedNetwork.networkRequests.size() && !keep; i++) {
                        NetworkRequest r = affectedNetwork.networkRequests.valueAt(i);
                        if (mNetworkRequests.get(r).isRequest) {
                            keep = true;
                        }
                    }
                    if (keep == false) {
                        if (DBG) log("no live requests for " + affectedNetwork.name() +
                                "; disconnecting");
                        affectedNetwork.asyncChannel.disconnect();
                    }
                }
            }
            callCallbackForRequest(nri, null, ConnectivityManager.CALLBACK_RELEASED);
        }
    }

    private class InternalHandler extends Handler {
        public InternalHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            NetworkInfo info;
            switch (msg.what) {
                case EVENT_EXPIRE_NET_TRANSITION_WAKELOCK:
                case EVENT_CLEAR_NET_TRANSITION_WAKELOCK: {
                    String causedBy = null;
                    synchronized (ConnectivityService.this) {
                        if (msg.arg1 == mNetTransitionWakeLockSerialNumber &&
                                mNetTransitionWakeLock.isHeld()) {
                            mNetTransitionWakeLock.release();
                            causedBy = mNetTransitionWakeLockCausedBy;
                        } else {
                            break;
                        }
                    }
                    if (msg.what == EVENT_EXPIRE_NET_TRANSITION_WAKELOCK) {
                        log("Failed to find a new network - expiring NetTransition Wakelock");
                    } else {
                        log("NetTransition Wakelock (" + (causedBy == null ? "unknown" : causedBy) +
                                " cleared because we found a replacement network");
                    }
                    break;
                }
                case EVENT_APPLY_GLOBAL_HTTP_PROXY: {
                    handleDeprecatedGlobalHttpProxy();
                    break;
                }
                case EVENT_SET_DEPENDENCY_MET: {
                    boolean met = (msg.arg1 == ENABLED);
                    handleSetDependencyMet(msg.arg2, met);
                    break;
                }
                case EVENT_SEND_STICKY_BROADCAST_INTENT: {
                    Intent intent = (Intent)msg.obj;
                    sendStickyBroadcast(intent);
                    break;
                }
                case EVENT_SET_POLICY_DATA_ENABLE: {
                    final int networkType = msg.arg1;
                    final boolean enabled = msg.arg2 == ENABLED;
                    handleSetPolicyDataEnable(networkType, enabled);
                    break;
                }
                case EVENT_ENABLE_FAIL_FAST_MOBILE_DATA: {
                    int tag = mEnableFailFastMobileDataTag.get();
                    if (msg.arg1 == tag) {
                        MobileDataStateTracker mobileDst =
                            (MobileDataStateTracker) mNetTrackers[ConnectivityManager.TYPE_MOBILE];
                        if (mobileDst != null) {
                            mobileDst.setEnableFailFastMobileData(msg.arg2);
                        }
                    } else {
                        log("EVENT_ENABLE_FAIL_FAST_MOBILE_DATA: stale arg1:" + msg.arg1
                                + " != tag:" + tag);
                    }
                    break;
                }
                case EVENT_SAMPLE_INTERVAL_ELAPSED: {
                    handleNetworkSamplingTimeout();
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
                    handleRegisterNetworkAgent((NetworkAgentInfo)msg.obj);
                    break;
                }
                case EVENT_REGISTER_NETWORK_REQUEST:
                case EVENT_REGISTER_NETWORK_LISTENER: {
                    handleRegisterNetworkRequest(msg);
                    break;
                }
                case EVENT_RELEASE_NETWORK_REQUEST: {
                    handleReleaseNetworkRequest((NetworkRequest) msg.obj, msg.arg1);
                    break;
                }
                case EVENT_SYSTEM_READY: {
                    for (NetworkAgentInfo nai : mNetworkAgentInfos.values()) {
                        nai.networkMonitor.systemReady = true;
                    }
                    break;
                }
            }
        }
    }

    // javadoc from interface
    public int tether(String iface) {
        enforceTetherChangePermission();

        if (isTetheringSupported()) {
            return mTethering.tether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    public int untether(String iface) {
        enforceTetherChangePermission();

        if (isTetheringSupported()) {
            return mTethering.untether(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // javadoc from interface
    public int getLastTetherError(String iface) {
        enforceTetherAccessPermission();

        if (isTetheringSupported()) {
            return mTethering.getLastTetherError(iface);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - proper iface API for selection by property, inspection, etc
    public String[] getTetherableUsbRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableUsbRegexs();
        } else {
            return new String[0];
        }
    }

    public String[] getTetherableWifiRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableWifiRegexs();
        } else {
            return new String[0];
        }
    }

    public String[] getTetherableBluetoothRegexs() {
        enforceTetherAccessPermission();
        if (isTetheringSupported()) {
            return mTethering.getTetherableBluetoothRegexs();
        } else {
            return new String[0];
        }
    }

    public int setUsbTethering(boolean enable) {
        enforceTetherChangePermission();
        if (isTetheringSupported()) {
            return mTethering.setUsbTethering(enable);
        } else {
            return ConnectivityManager.TETHER_ERROR_UNSUPPORTED;
        }
    }

    // TODO - move iface listing, queries, etc to new module
    // javadoc from interface
    public String[] getTetherableIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetherableIfaces();
    }

    public String[] getTetheredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getTetheredIfaces();
    }

    public String[] getTetheringErroredIfaces() {
        enforceTetherAccessPermission();
        return mTethering.getErroredIfaces();
    }

    public String[] getTetheredDhcpRanges() {
        enforceConnectivityInternalPermission();
        return mTethering.getTetheredDhcpRanges();
    }

    // if ro.tether.denied = true we default to no tethering
    // gservices could set the secure setting to 1 though to enable it on a build where it
    // had previously been turned off.
    public boolean isTetheringSupported() {
        enforceTetherAccessPermission();
        int defaultVal = (SystemProperties.get("ro.tether.denied").equals("true") ? 0 : 1);
        boolean tetherEnabledInSettings = (Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.TETHER_SUPPORTED, defaultVal) != 0)
                && !mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING);
        return tetherEnabledInSettings && ((mTethering.getTetherableUsbRegexs().length != 0 ||
                mTethering.getTetherableWifiRegexs().length != 0 ||
                mTethering.getTetherableBluetoothRegexs().length != 0) &&
                mTethering.getUpstreamIfaceTypes().length != 0);
    }

    // Called when we lose the default network and have no replacement yet.
    // This will automatically be cleared after X seconds or a new default network
    // becomes CONNECTED, whichever happens first.  The timer is started by the
    // first caller and not restarted by subsequent callers.
    private void requestNetworkTransitionWakelock(String forWhom) {
        int serialNum = 0;
        synchronized (this) {
            if (mNetTransitionWakeLock.isHeld()) return;
            serialNum = ++mNetTransitionWakeLockSerialNumber;
            mNetTransitionWakeLock.acquire();
            mNetTransitionWakeLockCausedBy = forWhom;
        }
        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                EVENT_EXPIRE_NET_TRANSITION_WAKELOCK, serialNum, 0),
                mNetTransitionWakeLockTimeout);
        return;
    }

    // 100 percent is full good, 0 is full bad.
    public void reportInetCondition(int networkType, int percentage) {
        if (percentage > 50) return;  // don't handle good network reports
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai != null) reportBadNetwork(nai.network);
    }

    public void reportBadNetwork(Network network) {
        //TODO
    }

    public ProxyInfo getProxy() {
        // this information is already available as a world read/writable jvm property
        // so this API change wouldn't have a benifit.  It also breaks the passing
        // of proxy info to all the JVMs.
        // enforceAccessPermission();
        synchronized (mProxyLock) {
            ProxyInfo ret = mGlobalProxy;
            if ((ret == null) && !mDefaultProxyDisabled) ret = mDefaultProxy;
            return ret;
        }
    }

    public void setGlobalProxy(ProxyInfo proxyProperties) {
        enforceConnectivityInternalPermission();

        synchronized (mProxyLock) {
            if (proxyProperties == mGlobalProxy) return;
            if (proxyProperties != null && proxyProperties.equals(mGlobalProxy)) return;
            if (mGlobalProxy != null && mGlobalProxy.equals(proxyProperties)) return;

            String host = "";
            int port = 0;
            String exclList = "";
            String pacFileUrl = "";
            if (proxyProperties != null && (!TextUtils.isEmpty(proxyProperties.getHost()) ||
                    (proxyProperties.getPacFileUrl() != null))) {
                if (!proxyProperties.isValid()) {
                    if (DBG)
                        log("Invalid proxy properties, ignoring: " + proxyProperties.toString());
                    return;
                }
                mGlobalProxy = new ProxyInfo(proxyProperties);
                host = mGlobalProxy.getHost();
                port = mGlobalProxy.getPort();
                exclList = mGlobalProxy.getExclusionListAsString();
                if (proxyProperties.getPacFileUrl() != null) {
                    pacFileUrl = proxyProperties.getPacFileUrl().toString();
                }
            } else {
                mGlobalProxy = null;
            }
            ContentResolver res = mContext.getContentResolver();
            final long token = Binder.clearCallingIdentity();
            try {
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST, host);
                Settings.Global.putInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, port);
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST,
                        exclList);
                Settings.Global.putString(res, Settings.Global.GLOBAL_HTTP_PROXY_PAC, pacFileUrl);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        if (mGlobalProxy == null) {
            proxyProperties = mDefaultProxy;
        }
        sendProxyBroadcast(proxyProperties);
    }

    private void loadGlobalProxy() {
        ContentResolver res = mContext.getContentResolver();
        String host = Settings.Global.getString(res, Settings.Global.GLOBAL_HTTP_PROXY_HOST);
        int port = Settings.Global.getInt(res, Settings.Global.GLOBAL_HTTP_PROXY_PORT, 0);
        String exclList = Settings.Global.getString(res,
                Settings.Global.GLOBAL_HTTP_PROXY_EXCLUSION_LIST);
        String pacFileUrl = Settings.Global.getString(res, Settings.Global.GLOBAL_HTTP_PROXY_PAC);
        if (!TextUtils.isEmpty(host) || !TextUtils.isEmpty(pacFileUrl)) {
            ProxyInfo proxyProperties;
            if (!TextUtils.isEmpty(pacFileUrl)) {
                proxyProperties = new ProxyInfo(pacFileUrl);
            } else {
                proxyProperties = new ProxyInfo(host, port, exclList);
            }
            if (!proxyProperties.isValid()) {
                if (DBG) log("Invalid proxy properties, ignoring: " + proxyProperties.toString());
                return;
            }

            synchronized (mProxyLock) {
                mGlobalProxy = proxyProperties;
            }
        }
    }

    public ProxyInfo getGlobalProxy() {
        // this information is already available as a world read/writable jvm property
        // so this API change wouldn't have a benifit.  It also breaks the passing
        // of proxy info to all the JVMs.
        // enforceAccessPermission();
        synchronized (mProxyLock) {
            return mGlobalProxy;
        }
    }

    private void handleApplyDefaultProxy(ProxyInfo proxy) {
        if (proxy != null && TextUtils.isEmpty(proxy.getHost())
                && (proxy.getPacFileUrl() == null)) {
            proxy = null;
        }
        synchronized (mProxyLock) {
            if (mDefaultProxy != null && mDefaultProxy.equals(proxy)) return;
            if (mDefaultProxy == proxy) return; // catches repeated nulls
            if (proxy != null &&  !proxy.isValid()) {
                if (DBG) log("Invalid proxy properties, ignoring: " + proxy.toString());
                return;
            }

            // This call could be coming from the PacManager, containing the port of the local
            // proxy.  If this new proxy matches the global proxy then copy this proxy to the
            // global (to get the correct local port), and send a broadcast.
            // TODO: Switch PacManager to have its own message to send back rather than
            // reusing EVENT_HAS_CHANGED_PROXY and this call to handleApplyDefaultProxy.
            if ((mGlobalProxy != null) && (proxy != null) && (proxy.getPacFileUrl() != null)
                    && proxy.getPacFileUrl().equals(mGlobalProxy.getPacFileUrl())) {
                mGlobalProxy = proxy;
                sendProxyBroadcast(mGlobalProxy);
                return;
            }
            mDefaultProxy = proxy;

            if (mGlobalProxy != null) return;
            if (!mDefaultProxyDisabled) {
                sendProxyBroadcast(proxy);
            }
        }
    }

    private void handleDeprecatedGlobalHttpProxy() {
        String proxy = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.HTTP_PROXY);
        if (!TextUtils.isEmpty(proxy)) {
            String data[] = proxy.split(":");
            if (data.length == 0) {
                return;
            }

            String proxyHost =  data[0];
            int proxyPort = 8080;
            if (data.length > 1) {
                try {
                    proxyPort = Integer.parseInt(data[1]);
                } catch (NumberFormatException e) {
                    return;
                }
            }
            ProxyInfo p = new ProxyInfo(data[0], proxyPort, "");
            setGlobalProxy(p);
        }
    }

    private void sendProxyBroadcast(ProxyInfo proxy) {
        if (proxy == null) proxy = new ProxyInfo("", 0, "");
        if (mPacManager.setCurrentProxyScriptUrl(proxy)) return;
        if (DBG) log("sending Proxy Broadcast for " + proxy);
        Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING |
            Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(Proxy.EXTRA_PROXY_INFO, proxy);
        final long ident = Binder.clearCallingIdentity();
        try {
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private static class SettingsObserver extends ContentObserver {
        private int mWhat;
        private Handler mHandler;
        SettingsObserver(Handler handler, int what) {
            super(handler);
            mHandler = handler;
            mWhat = what;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(Settings.Global.getUriFor(
                    Settings.Global.HTTP_PROXY), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mWhat).sendToTarget();
        }
    }

    private static void log(String s) {
        Slog.d(TAG, s);
    }

    private static void loge(String s) {
        Slog.e(TAG, s);
    }

    int convertFeatureToNetworkType(int networkType, String feature) {
        int usedNetworkType = networkType;

        if(networkType == ConnectivityManager.TYPE_MOBILE) {
            if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_MMS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_MMS;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_SUPL)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_SUPL;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_DUN) ||
                    TextUtils.equals(feature, Phone.FEATURE_ENABLE_DUN_ALWAYS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_DUN;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_HIPRI)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_HIPRI;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_FOTA)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_FOTA;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_IMS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_IMS;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_CBS)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_CBS;
            } else if (TextUtils.equals(feature, Phone.FEATURE_ENABLE_EMERGENCY)) {
                usedNetworkType = ConnectivityManager.TYPE_MOBILE_EMERGENCY;
            } else {
                Slog.e(TAG, "Can't match any mobile netTracker!");
            }
        } else if (networkType == ConnectivityManager.TYPE_WIFI) {
            if (TextUtils.equals(feature, "p2p")) {
                usedNetworkType = ConnectivityManager.TYPE_WIFI_P2P;
            } else {
                Slog.e(TAG, "Can't match any wifi netTracker!");
            }
        } else {
            Slog.e(TAG, "Unexpected network type");
        }
        return usedNetworkType;
    }

    private static <T> T checkNotNull(T value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
        return value;
    }

    /**
     * Prepare for a VPN application. This method is used by VpnDialogs
     * and not available in ConnectivityManager. Permissions are checked
     * in Vpn class.
     * @hide
     */
    @Override
    public boolean prepareVpn(String oldPackage, String newPackage) {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            return mVpns.get(user).prepare(oldPackage, newPackage);
        }
    }

    /**
     * Set whether the current VPN package has the ability to launch VPNs without
     * user intervention. This method is used by system UIs and not available
     * in ConnectivityManager. Permissions are checked in Vpn class.
     * @hide
     */
    @Override
    public void setVpnPackageAuthorization(boolean authorized) {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            mVpns.get(user).setPackageAuthorization(authorized);
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
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            return mVpns.get(user).establish(config);
        }
    }

    /**
     * Start legacy VPN, controlling native daemons as needed. Creates a
     * secondary thread to perform connection work, returning quickly.
     */
    @Override
    public void startLegacyVpn(VpnProfile profile) {
        throwIfLockdownEnabled();
        final LinkProperties egress = getActiveLinkProperties();
        if (egress == null) {
            throw new IllegalStateException("Missing active network connection");
        }
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            mVpns.get(user).startLegacyVpn(profile, mKeyStore, egress);
        }
    }

    /**
     * Return the information of the ongoing legacy VPN. This method is used
     * by VpnSettings and not available in ConnectivityManager. Permissions
     * are checked in Vpn class.
     * @hide
     */
    @Override
    public LegacyVpnInfo getLegacyVpnInfo() {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            return mVpns.get(user).getLegacyVpnInfo();
        }
    }

    /**
     * Returns the information of the ongoing VPN. This method is used by VpnDialogs and
     * not available in ConnectivityManager.
     * Permissions are checked in Vpn class.
     * @hide
     */
    @Override
    public VpnConfig getVpnConfig() {
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized(mVpns) {
            return mVpns.get(user).getVpnConfig();
        }
    }

    @Override
    public boolean updateLockdownVpn() {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            Slog.w(TAG, "Lockdown VPN only available to AID_SYSTEM");
            return false;
        }

        // Tear down existing lockdown if profile was removed
        mLockdownEnabled = LockdownVpnTracker.isEnabled();
        if (mLockdownEnabled) {
            if (!mKeyStore.isUnlocked()) {
                Slog.w(TAG, "KeyStore locked; unable to create LockdownTracker");
                return false;
            }

            final String profileName = new String(mKeyStore.get(Credentials.LOCKDOWN_VPN));
            final VpnProfile profile = VpnProfile.decode(
                    profileName, mKeyStore.get(Credentials.VPN + profileName));
            int user = UserHandle.getUserId(Binder.getCallingUid());
            synchronized(mVpns) {
                setLockdownTracker(new LockdownVpnTracker(mContext, mNetd, this, mVpns.get(user),
                            profile));
            }
        } else {
            setLockdownTracker(null);
        }

        return true;
    }

    /**
     * Internally set new {@link LockdownVpnTracker}, shutting down any existing
     * {@link LockdownVpnTracker}. Can be {@code null} to disable lockdown.
     */
    private void setLockdownTracker(LockdownVpnTracker tracker) {
        // Shutdown any existing tracker
        final LockdownVpnTracker existing = mLockdownTracker;
        mLockdownTracker = null;
        if (existing != null) {
            existing.shutdown();
        }

        try {
            if (tracker != null) {
                mNetd.setFirewallEnabled(true);
                mNetd.setFirewallInterfaceRule("lo", true);
                mLockdownTracker = tracker;
                mLockdownTracker.init();
            } else {
                mNetd.setFirewallEnabled(false);
            }
        } catch (RemoteException e) {
            // ignored; NMS lives inside system_server
        }
    }

    private void throwIfLockdownEnabled() {
        if (mLockdownEnabled) {
            throw new IllegalStateException("Unavailable in lockdown mode");
        }
    }

    public void supplyMessenger(int networkType, Messenger messenger) {
        enforceConnectivityInternalPermission();

        if (isNetworkTypeValid(networkType) && mNetTrackers[networkType] != null) {
            mNetTrackers[networkType].supplyMessenger(messenger);
        }
    }

    public int findConnectionTypeForIface(String iface) {
        enforceConnectivityInternalPermission();

        if (TextUtils.isEmpty(iface)) return ConnectivityManager.TYPE_NONE;
        for (NetworkStateTracker tracker : mNetTrackers) {
            if (tracker != null) {
                LinkProperties lp = tracker.getLinkProperties();
                if (lp != null && iface.equals(lp.getInterfaceName())) {
                    return tracker.getNetworkInfo().getType();
                }
            }
        }
        return ConnectivityManager.TYPE_NONE;
    }

    /**
     * Have mobile data fail fast if enabled.
     *
     * @param enabled DctConstants.ENABLED/DISABLED
     */
    private void setEnableFailFastMobileData(int enabled) {
        int tag;

        if (enabled == DctConstants.ENABLED) {
            tag = mEnableFailFastMobileDataTag.incrementAndGet();
        } else {
            tag = mEnableFailFastMobileDataTag.get();
        }
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_ENABLE_FAIL_FAST_MOBILE_DATA, tag,
                         enabled));
    }

    private boolean isMobileDataStateTrackerReady() {
        MobileDataStateTracker mdst =
                (MobileDataStateTracker) mNetTrackers[ConnectivityManager.TYPE_MOBILE_HIPRI];
        return (mdst != null) && (mdst.isReady());
    }

    /**
     * The ResultReceiver resultCode for checkMobileProvisioning (CMP_RESULT_CODE)
     */

    /**
     * No connection was possible to the network.
     * This is NOT a warm sim.
     */
    private static final int CMP_RESULT_CODE_NO_CONNECTION = 0;

    /**
     * A connection was made to the internet, all is well.
     * This is NOT a warm sim.
     */
    private static final int CMP_RESULT_CODE_CONNECTABLE = 1;

    /**
     * A connection was made but no dns server was available to resolve a name to address.
     * This is NOT a warm sim since provisioning network is supported.
     */
    private static final int CMP_RESULT_CODE_NO_DNS = 2;

    /**
     * A connection was made but could not open a TCP connection.
     * This is NOT a warm sim since provisioning network is supported.
     */
    private static final int CMP_RESULT_CODE_NO_TCP_CONNECTION = 3;

    /**
     * A connection was made but there was a redirection, we appear to be in walled garden.
     * This is an indication of a warm sim on a mobile network such as T-Mobile.
     */
    private static final int CMP_RESULT_CODE_REDIRECTED = 4;

    /**
     * The mobile network is a provisioning network.
     * This is an indication of a warm sim on a mobile network such as AT&T.
     */
    private static final int CMP_RESULT_CODE_PROVISIONING_NETWORK = 5;

    /**
     * The mobile network is provisioning
     */
    private static final int CMP_RESULT_CODE_IS_PROVISIONING = 6;

    private AtomicBoolean mIsProvisioningNetwork = new AtomicBoolean(false);
    private AtomicBoolean mIsStartingProvisioning = new AtomicBoolean(false);

    private AtomicBoolean mIsCheckingMobileProvisioning = new AtomicBoolean(false);

    @Override
    public int checkMobileProvisioning(int suggestedTimeOutMs) {
        int timeOutMs = -1;
        if (DBG) log("checkMobileProvisioning: E suggestedTimeOutMs=" + suggestedTimeOutMs);
        enforceConnectivityInternalPermission();

        final long token = Binder.clearCallingIdentity();
        try {
            timeOutMs = suggestedTimeOutMs;
            if (suggestedTimeOutMs > CheckMp.MAX_TIMEOUT_MS) {
                timeOutMs = CheckMp.MAX_TIMEOUT_MS;
            }

            // Check that mobile networks are supported
            if (!isNetworkSupported(ConnectivityManager.TYPE_MOBILE)
                    || !isNetworkSupported(ConnectivityManager.TYPE_MOBILE_HIPRI)) {
                if (DBG) log("checkMobileProvisioning: X no mobile network");
                return timeOutMs;
            }

            // If we're already checking don't do it again
            // TODO: Add a queue of results...
            if (mIsCheckingMobileProvisioning.getAndSet(true)) {
                if (DBG) log("checkMobileProvisioning: X already checking ignore for the moment");
                return timeOutMs;
            }

            // Start off with mobile notification off
            setProvNotificationVisible(false, ConnectivityManager.TYPE_MOBILE_HIPRI, null, null);

            CheckMp checkMp = new CheckMp(mContext, this);
            CheckMp.CallBack cb = new CheckMp.CallBack() {
                @Override
                void onComplete(Integer result) {
                    if (DBG) log("CheckMp.onComplete: result=" + result);
                    NetworkInfo ni =
                            mNetTrackers[ConnectivityManager.TYPE_MOBILE_HIPRI].getNetworkInfo();
                    switch(result) {
                        case CMP_RESULT_CODE_CONNECTABLE:
                        case CMP_RESULT_CODE_NO_CONNECTION:
                        case CMP_RESULT_CODE_NO_DNS:
                        case CMP_RESULT_CODE_NO_TCP_CONNECTION: {
                            if (DBG) log("CheckMp.onComplete: ignore, connected or no connection");
                            break;
                        }
                        case CMP_RESULT_CODE_REDIRECTED: {
                            if (DBG) log("CheckMp.onComplete: warm sim");
                            String url = getMobileProvisioningUrl();
                            if (TextUtils.isEmpty(url)) {
                                url = getMobileRedirectedProvisioningUrl();
                            }
                            if (TextUtils.isEmpty(url) == false) {
                                if (DBG) log("CheckMp.onComplete: warm (redirected), url=" + url);
                                setProvNotificationVisible(true,
                                        ConnectivityManager.TYPE_MOBILE_HIPRI, ni.getExtraInfo(),
                                        url);
                            } else {
                                if (DBG) log("CheckMp.onComplete: warm (redirected), no url");
                            }
                            break;
                        }
                        case CMP_RESULT_CODE_PROVISIONING_NETWORK: {
                            String url = getMobileProvisioningUrl();
                            if (TextUtils.isEmpty(url) == false) {
                                if (DBG) log("CheckMp.onComplete: warm (no dns/tcp), url=" + url);
                                setProvNotificationVisible(true,
                                        ConnectivityManager.TYPE_MOBILE_HIPRI, ni.getExtraInfo(),
                                        url);
                                // Mark that we've got a provisioning network and
                                // Disable Mobile Data until user actually starts provisioning.
                                mIsProvisioningNetwork.set(true);
                                MobileDataStateTracker mdst = (MobileDataStateTracker)
                                        mNetTrackers[ConnectivityManager.TYPE_MOBILE];

                                // Disable radio until user starts provisioning
                                mdst.setRadio(false);
                            } else {
                                if (DBG) log("CheckMp.onComplete: warm (no dns/tcp), no url");
                            }
                            break;
                        }
                        case CMP_RESULT_CODE_IS_PROVISIONING: {
                            // FIXME: Need to know when provisioning is done. Probably we can
                            // check the completion status if successful we're done if we
                            // "timedout" or still connected to provisioning APN turn off data?
                            if (DBG) log("CheckMp.onComplete: provisioning started");
                            mIsStartingProvisioning.set(false);
                            break;
                        }
                        default: {
                            loge("CheckMp.onComplete: ignore unexpected result=" + result);
                            break;
                        }
                    }
                    mIsCheckingMobileProvisioning.set(false);
                }
            };
            CheckMp.Params params =
                    new CheckMp.Params(checkMp.getDefaultUrl(), timeOutMs, cb);
            if (DBG) log("checkMobileProvisioning: params=" + params);
            // TODO: Reenable when calls to the now defunct
            //       MobileDataStateTracker.isProvisioningNetwork() are removed.
            //       This code should be moved to the Telephony code.
            // checkMp.execute(params);
        } finally {
            Binder.restoreCallingIdentity(token);
            if (DBG) log("checkMobileProvisioning: X");
        }
        return timeOutMs;
    }

    static class CheckMp extends
            AsyncTask<CheckMp.Params, Void, Integer> {
        private static final String CHECKMP_TAG = "CheckMp";

        // adb shell setprop persist.checkmp.testfailures 1 to enable testing failures
        private static boolean mTestingFailures;

        // Choosing 4 loops as half of them will use HTTPS and the other half HTTP
        private static final int MAX_LOOPS = 4;

        // Number of milli-seconds to complete all of the retires
        public static final int MAX_TIMEOUT_MS =  60000;

        // The socket should retry only 5 seconds, the default is longer
        private static final int SOCKET_TIMEOUT_MS = 5000;

        // Sleep time for network errors
        private static final int NET_ERROR_SLEEP_SEC = 3;

        // Sleep time for network route establishment
        private static final int NET_ROUTE_ESTABLISHMENT_SLEEP_SEC = 3;

        // Short sleep time for polling :(
        private static final int POLLING_SLEEP_SEC = 1;

        private Context mContext;
        private ConnectivityService mCs;
        private TelephonyManager mTm;
        private Params mParams;

        /**
         * Parameters for AsyncTask.execute
         */
        static class Params {
            private String mUrl;
            private long mTimeOutMs;
            private CallBack mCb;

            Params(String url, long timeOutMs, CallBack cb) {
                mUrl = url;
                mTimeOutMs = timeOutMs;
                mCb = cb;
            }

            @Override
            public String toString() {
                return "{" + " url=" + mUrl + " mTimeOutMs=" + mTimeOutMs + " mCb=" + mCb + "}";
            }
        }

        // As explained to me by Brian Carlstrom and Kenny Root, Certificates can be
        // issued by name or ip address, for Google its by name so when we construct
        // this HostnameVerifier we'll pass the original Uri and use it to verify
        // the host. If the host name in the original uril fails we'll test the
        // hostname parameter just incase things change.
        static class CheckMpHostnameVerifier implements HostnameVerifier {
            Uri mOrgUri;

            CheckMpHostnameVerifier(Uri orgUri) {
                mOrgUri = orgUri;
            }

            @Override
            public boolean verify(String hostname, SSLSession session) {
                HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
                String orgUriHost = mOrgUri.getHost();
                boolean retVal = hv.verify(orgUriHost, session) || hv.verify(hostname, session);
                if (DBG) {
                    log("isMobileOk: hostnameVerify retVal=" + retVal + " hostname=" + hostname
                        + " orgUriHost=" + orgUriHost);
                }
                return retVal;
            }
        }

        /**
         * The call back object passed in Params. onComplete will be called
         * on the main thread.
         */
        abstract static class CallBack {
            // Called on the main thread.
            abstract void onComplete(Integer result);
        }

        public CheckMp(Context context, ConnectivityService cs) {
            if (Build.IS_DEBUGGABLE) {
                mTestingFailures =
                        SystemProperties.getInt("persist.checkmp.testfailures", 0) == 1;
            } else {
                mTestingFailures = false;
            }

            mContext = context;
            mCs = cs;

            // Setup access to TelephonyService we'll be using.
            mTm = (TelephonyManager) mContext.getSystemService(
                    Context.TELEPHONY_SERVICE);
        }

        /**
         * Get the default url to use for the test.
         */
        public String getDefaultUrl() {
            // See http://go/clientsdns for usage approval
            String server = Settings.Global.getString(mContext.getContentResolver(),
                    Settings.Global.CAPTIVE_PORTAL_SERVER);
            if (server == null) {
                server = "clients3.google.com";
            }
            return "http://" + server + "/generate_204";
        }

        /**
         * Detect if its possible to connect to the http url. DNS based detection techniques
         * do not work at all hotspots. The best way to check is to perform a request to
         * a known address that fetches the data we expect.
         */
        private synchronized Integer isMobileOk(Params params) {
            Integer result = CMP_RESULT_CODE_NO_CONNECTION;
            Uri orgUri = Uri.parse(params.mUrl);
            Random rand = new Random();
            mParams = params;

            if (mCs.isNetworkSupported(ConnectivityManager.TYPE_MOBILE) == false) {
                result = CMP_RESULT_CODE_NO_CONNECTION;
                log("isMobileOk: X not mobile capable result=" + result);
                return result;
            }

            if (mCs.mIsStartingProvisioning.get()) {
                result = CMP_RESULT_CODE_IS_PROVISIONING;
                log("isMobileOk: X is provisioning result=" + result);
                return result;
            }

            // See if we've already determined we've got a provisioning connection,
            // if so we don't need to do anything active.
            MobileDataStateTracker mdstDefault = (MobileDataStateTracker)
                    mCs.mNetTrackers[ConnectivityManager.TYPE_MOBILE];
            boolean isDefaultProvisioning = mdstDefault.isProvisioningNetwork();
            log("isMobileOk: isDefaultProvisioning=" + isDefaultProvisioning);

            MobileDataStateTracker mdstHipri = (MobileDataStateTracker)
                    mCs.mNetTrackers[ConnectivityManager.TYPE_MOBILE_HIPRI];
            boolean isHipriProvisioning = mdstHipri.isProvisioningNetwork();
            log("isMobileOk: isHipriProvisioning=" + isHipriProvisioning);

            if (isDefaultProvisioning || isHipriProvisioning) {
                result = CMP_RESULT_CODE_PROVISIONING_NETWORK;
                log("isMobileOk: X default || hipri is provisioning result=" + result);
                return result;
            }

            try {
                // Continue trying to connect until time has run out
                long endTime = SystemClock.elapsedRealtime() + params.mTimeOutMs;

                if (!mCs.isMobileDataStateTrackerReady()) {
                    // Wait for MobileDataStateTracker to be ready.
                    if (DBG) log("isMobileOk: mdst is not ready");
                    while(SystemClock.elapsedRealtime() < endTime) {
                        if (mCs.isMobileDataStateTrackerReady()) {
                            // Enable fail fast as we'll do retries here and use a
                            // hipri connection so the default connection stays active.
                            if (DBG) log("isMobileOk: mdst ready, enable fail fast of mobile data");
                            mCs.setEnableFailFastMobileData(DctConstants.ENABLED);
                            break;
                        }
                        sleep(POLLING_SLEEP_SEC);
                    }
                }

                log("isMobileOk: start hipri url=" + params.mUrl);

                // First wait until we can start using hipri
                Binder binder = new Binder();
/*
                while(SystemClock.elapsedRealtime() < endTime) {
                    int ret = mCs.startUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
                            Phone.FEATURE_ENABLE_HIPRI, binder);
                    if ((ret == PhoneConstants.APN_ALREADY_ACTIVE)
                        || (ret == PhoneConstants.APN_REQUEST_STARTED)) {
                            log("isMobileOk: hipri started");
                            break;
                    }
                    if (VDBG) log("isMobileOk: hipri not started yet");
                    result = CMP_RESULT_CODE_NO_CONNECTION;
                    sleep(POLLING_SLEEP_SEC);
                }
*/
                // Continue trying to connect until time has run out
                while(SystemClock.elapsedRealtime() < endTime) {
                    try {
                        // Wait for hipri to connect.
                        // TODO: Don't poll and handle situation where hipri fails
                        // because default is retrying. See b/9569540
                        NetworkInfo.State state = mCs
                                .getNetworkInfo(ConnectivityManager.TYPE_MOBILE_HIPRI).getState();
                        if (state != NetworkInfo.State.CONNECTED) {
                            if (true/*VDBG*/) {
                                log("isMobileOk: not connected ni=" +
                                    mCs.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_HIPRI));
                            }
                            sleep(POLLING_SLEEP_SEC);
                            result = CMP_RESULT_CODE_NO_CONNECTION;
                            continue;
                        }

                        // Hipri has started check if this is a provisioning url
                        MobileDataStateTracker mdst = (MobileDataStateTracker)
                                mCs.mNetTrackers[ConnectivityManager.TYPE_MOBILE_HIPRI];
                        if (mdst.isProvisioningNetwork()) {
                            result = CMP_RESULT_CODE_PROVISIONING_NETWORK;
                            if (DBG) log("isMobileOk: X isProvisioningNetwork result=" + result);
                            return result;
                        } else {
                            if (DBG) log("isMobileOk: isProvisioningNetwork is false, continue");
                        }

                        // Get of the addresses associated with the url host. We need to use the
                        // address otherwise HttpURLConnection object will use the name to get
                        // the addresses and will try every address but that will bypass the
                        // route to host we setup and the connection could succeed as the default
                        // interface might be connected to the internet via wifi or other interface.
                        InetAddress[] addresses;
                        try {
                            addresses = InetAddress.getAllByName(orgUri.getHost());
                        } catch (UnknownHostException e) {
                            result = CMP_RESULT_CODE_NO_DNS;
                            log("isMobileOk: X UnknownHostException result=" + result);
                            return result;
                        }
                        log("isMobileOk: addresses=" + inetAddressesToString(addresses));

                        // Get the type of addresses supported by this link
                        LinkProperties lp = mCs.getLinkPropertiesForTypeInternal(
                                ConnectivityManager.TYPE_MOBILE_HIPRI);
                        boolean linkHasIpv4 = lp.hasIPv4Address();
                        boolean linkHasIpv6 = lp.hasGlobalIPv6Address();
                        log("isMobileOk: linkHasIpv4=" + linkHasIpv4
                                + " linkHasIpv6=" + linkHasIpv6);

                        final ArrayList<InetAddress> validAddresses =
                                new ArrayList<InetAddress>(addresses.length);

                        for (InetAddress addr : addresses) {
                            if (((addr instanceof Inet4Address) && linkHasIpv4) ||
                                    ((addr instanceof Inet6Address) && linkHasIpv6)) {
                                validAddresses.add(addr);
                            }
                        }

                        if (validAddresses.size() == 0) {
                            return CMP_RESULT_CODE_NO_CONNECTION;
                        }

                        int addrTried = 0;
                        while (true) {
                            // Loop through at most MAX_LOOPS valid addresses or until
                            // we run out of time
                            if (addrTried++ >= MAX_LOOPS) {
                                log("isMobileOk: too many loops tried - giving up");
                                break;
                            }
                            if (SystemClock.elapsedRealtime() >= endTime) {
                                log("isMobileOk: spend too much time - giving up");
                                break;
                            }

                            InetAddress hostAddr = validAddresses.get(rand.nextInt(
                                    validAddresses.size()));

                            // Make a route to host so we check the specific interface.
                            if (mCs.requestRouteToHostAddress(ConnectivityManager.TYPE_MOBILE_HIPRI,
                                    hostAddr.getAddress())) {
                                // Wait a short time to be sure the route is established ??
                                log("isMobileOk:"
                                        + " wait to establish route to hostAddr=" + hostAddr);
                                sleep(NET_ROUTE_ESTABLISHMENT_SLEEP_SEC);
                            } else {
                                log("isMobileOk:"
                                        + " could not establish route to hostAddr=" + hostAddr);
                                // Wait a short time before the next attempt
                                sleep(NET_ERROR_SLEEP_SEC);
                                continue;
                            }

                            // Rewrite the url to have numeric address to use the specific route
                            // using http for half the attempts and https for the other half.
                            // Doing https first and http second as on a redirected walled garden
                            // such as t-mobile uses we get a SocketTimeoutException: "SSL
                            // handshake timed out" which we declare as
                            // CMP_RESULT_CODE_NO_TCP_CONNECTION. We could change this, but by
                            // having http second we will be using logic used for some time.
                            URL newUrl;
                            String scheme = (addrTried <= (MAX_LOOPS/2)) ? "https" : "http";
                            newUrl = new URL(scheme, hostAddr.getHostAddress(),
                                        orgUri.getPath());
                            log("isMobileOk: newUrl=" + newUrl);

                            HttpURLConnection urlConn = null;
                            try {
                                // Open the connection set the request headers and get the response
                                urlConn = (HttpURLConnection)newUrl.openConnection(
                                        java.net.Proxy.NO_PROXY);
                                if (scheme.equals("https")) {
                                    ((HttpsURLConnection)urlConn).setHostnameVerifier(
                                            new CheckMpHostnameVerifier(orgUri));
                                }
                                urlConn.setInstanceFollowRedirects(false);
                                urlConn.setConnectTimeout(SOCKET_TIMEOUT_MS);
                                urlConn.setReadTimeout(SOCKET_TIMEOUT_MS);
                                urlConn.setUseCaches(false);
                                urlConn.setAllowUserInteraction(false);
                                // Set the "Connection" to "Close" as by default "Keep-Alive"
                                // is used which is useless in this case.
                                urlConn.setRequestProperty("Connection", "close");
                                int responseCode = urlConn.getResponseCode();

                                // For debug display the headers
                                Map<String, List<String>> headers = urlConn.getHeaderFields();
                                log("isMobileOk: headers=" + headers);

                                // Close the connection
                                urlConn.disconnect();
                                urlConn = null;

                                if (mTestingFailures) {
                                    // Pretend no connection, this tests using http and https
                                    result = CMP_RESULT_CODE_NO_CONNECTION;
                                    log("isMobileOk: TESTING_FAILURES, pretend no connction");
                                    continue;
                                }

                                if (responseCode == 204) {
                                    // Return
                                    result = CMP_RESULT_CODE_CONNECTABLE;
                                    log("isMobileOk: X got expected responseCode=" + responseCode
                                            + " result=" + result);
                                    return result;
                                } else {
                                    // Retry to be sure this was redirected, we've gotten
                                    // occasions where a server returned 200 even though
                                    // the device didn't have a "warm" sim.
                                    log("isMobileOk: not expected responseCode=" + responseCode);
                                    // TODO - it would be nice in the single-address case to do
                                    // another DNS resolve here, but flushing the cache is a bit
                                    // heavy-handed.
                                    result = CMP_RESULT_CODE_REDIRECTED;
                                }
                            } catch (Exception e) {
                                log("isMobileOk: HttpURLConnection Exception" + e);
                                result = CMP_RESULT_CODE_NO_TCP_CONNECTION;
                                if (urlConn != null) {
                                    urlConn.disconnect();
                                    urlConn = null;
                                }
                                sleep(NET_ERROR_SLEEP_SEC);
                                continue;
                            }
                        }
                        log("isMobileOk: X loops|timed out result=" + result);
                        return result;
                    } catch (Exception e) {
                        log("isMobileOk: Exception e=" + e);
                        continue;
                    }
                }
                log("isMobileOk: timed out");
            } finally {
                log("isMobileOk: F stop hipri");
                mCs.setEnableFailFastMobileData(DctConstants.DISABLED);
//                mCs.stopUsingNetworkFeature(ConnectivityManager.TYPE_MOBILE,
//                        Phone.FEATURE_ENABLE_HIPRI);

                // Wait for hipri to disconnect.
                long endTime = SystemClock.elapsedRealtime() + 5000;

                while(SystemClock.elapsedRealtime() < endTime) {
                    NetworkInfo.State state = mCs
                            .getNetworkInfo(ConnectivityManager.TYPE_MOBILE_HIPRI).getState();
                    if (state != NetworkInfo.State.DISCONNECTED) {
                        if (VDBG) {
                            log("isMobileOk: connected ni=" +
                                mCs.getNetworkInfo(ConnectivityManager.TYPE_MOBILE_HIPRI));
                        }
                        sleep(POLLING_SLEEP_SEC);
                        continue;
                    }
                }

                log("isMobileOk: X result=" + result);
            }
            return result;
        }

        @Override
        protected Integer doInBackground(Params... params) {
            return isMobileOk(params[0]);
        }

        @Override
        protected void onPostExecute(Integer result) {
            log("onPostExecute: result=" + result);
            if ((mParams != null) && (mParams.mCb != null)) {
                mParams.mCb.onComplete(result);
            }
        }

        private String inetAddressesToString(InetAddress[] addresses) {
            StringBuffer sb = new StringBuffer();
            boolean firstTime = true;
            for(InetAddress addr : addresses) {
                if (firstTime) {
                    firstTime = false;
                } else {
                    sb.append(",");
                }
                sb.append(addr);
            }
            return sb.toString();
        }

        private void printNetworkInfo() {
            boolean hasIccCard = mTm.hasIccCard();
            int simState = mTm.getSimState();
            log("hasIccCard=" + hasIccCard
                    + " simState=" + simState);
            NetworkInfo[] ni = mCs.getAllNetworkInfo();
            if (ni != null) {
                log("ni.length=" + ni.length);
                for (NetworkInfo netInfo: ni) {
                    log("netInfo=" + netInfo.toString());
                }
            } else {
                log("no network info ni=null");
            }
        }

        /**
         * Sleep for a few seconds then return.
         * @param seconds
         */
        private static void sleep(int seconds) {
            long stopTime = System.nanoTime() + (seconds * 1000000000);
            long sleepTime;
            while ((sleepTime = stopTime - System.nanoTime()) > 0) {
                try {
                    Thread.sleep(sleepTime / 1000000);
                } catch (InterruptedException ignored) {
                }
            }
        }

        private static void log(String s) {
            Slog.d(ConnectivityService.TAG, "[" + CHECKMP_TAG + "] " + s);
        }
    }

    // TODO: Move to ConnectivityManager and make public?
    private static final String CONNECTED_TO_PROVISIONING_NETWORK_ACTION =
            "com.android.server.connectivityservice.CONNECTED_TO_PROVISIONING_NETWORK_ACTION";

    private BroadcastReceiver mProvisioningReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(CONNECTED_TO_PROVISIONING_NETWORK_ACTION)) {
                handleMobileProvisioningAction(intent.getStringExtra("EXTRA_URL"));
            }
        }
    };

    private void handleMobileProvisioningAction(String url) {
        // Mark notification as not visible
        setProvNotificationVisible(false, ConnectivityManager.TYPE_MOBILE_HIPRI, null, null);

        // Check airplane mode
        boolean isAirplaneModeOn = Settings.System.getInt(mContext.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1;
        // If provisioning network and not in airplane mode handle as a special case,
        // otherwise launch browser with the intent directly.
        if (mIsProvisioningNetwork.get() && !isAirplaneModeOn) {
            if (DBG) log("handleMobileProvisioningAction: on prov network enable then launch");
            mIsProvisioningNetwork.set(false);
//            mIsStartingProvisioning.set(true);
//            MobileDataStateTracker mdst = (MobileDataStateTracker)
//                    mNetTrackers[ConnectivityManager.TYPE_MOBILE];
            // Radio was disabled on CMP_RESULT_CODE_PROVISIONING_NETWORK, enable it here
//            mdst.setRadio(true);
//            mdst.setEnableFailFastMobileData(DctConstants.ENABLED);
//            mdst.enableMobileProvisioning(url);
        } else {
            if (DBG) log("handleMobileProvisioningAction: not prov network");
            mIsProvisioningNetwork.set(false);
            // Check for  apps that can handle provisioning first
            Intent provisioningIntent = new Intent(TelephonyIntents.ACTION_CARRIER_SETUP);
            List<String> carrierPackages =
                    mTelephonyManager.getCarrierPackageNamesForBroadcastIntent(provisioningIntent);
            if (carrierPackages != null && !carrierPackages.isEmpty()) {
                if (carrierPackages.size() != 1) {
                    if (DBG) log("Multiple matching carrier apps found, launching the first.");
                }
                provisioningIntent.setPackage(carrierPackages.get(0));
                provisioningIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                        Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(provisioningIntent);
            } else {
                // If no apps exist, use standard URL ACTION_VIEW method
                Intent newIntent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,
                        Intent.CATEGORY_APP_BROWSER);
                newIntent.setData(Uri.parse(url));
                newIntent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                        Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    mContext.startActivity(newIntent);
                } catch (ActivityNotFoundException e) {
                    loge("handleMobileProvisioningAction: startActivity failed" + e);
                }
            }
        }
    }

    private static final String NOTIFICATION_ID = "CaptivePortal.Notification";
    private volatile boolean mIsNotificationVisible = false;

    private void setProvNotificationVisible(boolean visible, int networkType, String extraInfo,
            String url) {
        if (DBG) {
            log("setProvNotificationVisible: E visible=" + visible + " networkType=" + networkType
                + " extraInfo=" + extraInfo + " url=" + url);
        }
        Intent intent = null;
        PendingIntent pendingIntent = null;
        if (visible) {
            switch (networkType) {
                case ConnectivityManager.TYPE_WIFI:
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                            Intent.FLAG_ACTIVITY_NEW_TASK);
                    pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);
                    break;
                case ConnectivityManager.TYPE_MOBILE:
                case ConnectivityManager.TYPE_MOBILE_HIPRI:
                    intent = new Intent(CONNECTED_TO_PROVISIONING_NETWORK_ACTION);
                    intent.putExtra("EXTRA_URL", url);
                    intent.setFlags(0);
                    pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
                    break;
                default:
                    intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    intent.setFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT |
                            Intent.FLAG_ACTIVITY_NEW_TASK);
                    pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);
                    break;
            }
        }
        // Concatenate the range of types onto the range of NetIDs.
        int id = MAX_NET_ID + 1 + (networkType - ConnectivityManager.TYPE_NONE);
        setProvNotificationVisibleIntent(visible, id, networkType, extraInfo, pendingIntent);
    }

    /**
     * Show or hide network provisioning notificaitons.
     *
     * @param id an identifier that uniquely identifies this notification.  This must match
     *         between show and hide calls.  We use the NetID value but for legacy callers
     *         we concatenate the range of types with the range of NetIDs.
     */
    private void setProvNotificationVisibleIntent(boolean visible, int id, int networkType,
            String extraInfo, PendingIntent intent) {
        if (DBG) {
            log("setProvNotificationVisibleIntent: E visible=" + visible + " networkType=" +
                networkType + " extraInfo=" + extraInfo);
        }

        Resources r = Resources.getSystem();
        NotificationManager notificationManager = (NotificationManager) mContext
            .getSystemService(Context.NOTIFICATION_SERVICE);

        if (visible) {
            CharSequence title;
            CharSequence details;
            int icon;
            Notification notification = new Notification();
            switch (networkType) {
                case ConnectivityManager.TYPE_WIFI:
                    title = r.getString(R.string.wifi_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed,
                            extraInfo);
                    icon = R.drawable.stat_notify_wifi_in_range;
                    break;
                case ConnectivityManager.TYPE_MOBILE:
                case ConnectivityManager.TYPE_MOBILE_HIPRI:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    // TODO: Change this to pull from NetworkInfo once a printable
                    // name has been added to it
                    details = mTelephonyManager.getNetworkOperatorName();
                    icon = R.drawable.stat_notify_rssi_in_range;
                    break;
                default:
                    title = r.getString(R.string.network_available_sign_in, 0);
                    details = r.getString(R.string.network_available_sign_in_detailed,
                            extraInfo);
                    icon = R.drawable.stat_notify_rssi_in_range;
                    break;
            }

            notification.when = 0;
            notification.icon = icon;
            notification.flags = Notification.FLAG_AUTO_CANCEL;
            notification.tickerText = title;
            notification.color = mContext.getResources().getColor(
                    com.android.internal.R.color.system_notification_accent_color);
            notification.setLatestEventInfo(mContext, title, details, notification.contentIntent);
            notification.contentIntent = intent;

            try {
                notificationManager.notify(NOTIFICATION_ID, id, notification);
            } catch (NullPointerException npe) {
                loge("setNotificaitionVisible: visible notificationManager npe=" + npe);
                npe.printStackTrace();
            }
        } else {
            try {
                notificationManager.cancel(NOTIFICATION_ID, id);
            } catch (NullPointerException npe) {
                loge("setNotificaitionVisible: cancel notificationManager npe=" + npe);
                npe.printStackTrace();
            }
        }
        mIsNotificationVisible = visible;
    }

    /** Location to an updatable file listing carrier provisioning urls.
     *  An example:
     *
     * <?xml version="1.0" encoding="utf-8"?>
     *  <provisioningUrls>
     *   <provisioningUrl mcc="310" mnc="4">http://myserver.com/foo?mdn=%3$s&amp;iccid=%1$s&amp;imei=%2$s</provisioningUrl>
     *   <redirectedUrl mcc="310" mnc="4">http://www.google.com</redirectedUrl>
     *  </provisioningUrls>
     */
    private static final String PROVISIONING_URL_PATH =
            "/data/misc/radio/provisioning_urls.xml";
    private final File mProvisioningUrlFile = new File(PROVISIONING_URL_PATH);

    /** XML tag for root element. */
    private static final String TAG_PROVISIONING_URLS = "provisioningUrls";
    /** XML tag for individual url */
    private static final String TAG_PROVISIONING_URL = "provisioningUrl";
    /** XML tag for redirected url */
    private static final String TAG_REDIRECTED_URL = "redirectedUrl";
    /** XML attribute for mcc */
    private static final String ATTR_MCC = "mcc";
    /** XML attribute for mnc */
    private static final String ATTR_MNC = "mnc";

    private static final int REDIRECTED_PROVISIONING = 1;
    private static final int PROVISIONING = 2;

    private String getProvisioningUrlBaseFromFile(int type) {
        FileReader fileReader = null;
        XmlPullParser parser = null;
        Configuration config = mContext.getResources().getConfiguration();
        String tagType;

        switch (type) {
            case PROVISIONING:
                tagType = TAG_PROVISIONING_URL;
                break;
            case REDIRECTED_PROVISIONING:
                tagType = TAG_REDIRECTED_URL;
                break;
            default:
                throw new RuntimeException("getProvisioningUrlBaseFromFile: Unexpected parameter " +
                        type);
        }

        try {
            fileReader = new FileReader(mProvisioningUrlFile);
            parser = Xml.newPullParser();
            parser.setInput(fileReader);
            XmlUtils.beginDocument(parser, TAG_PROVISIONING_URLS);

            while (true) {
                XmlUtils.nextElement(parser);

                String element = parser.getName();
                if (element == null) break;

                if (element.equals(tagType)) {
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
    public String getMobileRedirectedProvisioningUrl() {
        enforceConnectivityInternalPermission();
        String url = getProvisioningUrlBaseFromFile(REDIRECTED_PROVISIONING);
        if (TextUtils.isEmpty(url)) {
            url = mContext.getResources().getString(R.string.mobile_redirected_provisioning_url);
        }
        return url;
    }

    @Override
    public String getMobileProvisioningUrl() {
        enforceConnectivityInternalPermission();
        String url = getProvisioningUrlBaseFromFile(PROVISIONING);
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
                    phoneNumber /* Phone numer */);
        }

        return url;
    }

    @Override
    public void setProvisioningNotificationVisible(boolean visible, int networkType,
            String extraInfo, String url) {
        enforceConnectivityInternalPermission();
        setProvNotificationVisible(visible, networkType, extraInfo, url);
    }

    @Override
    public void setAirplaneMode(boolean enable) {
        enforceConnectivityInternalPermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            final ContentResolver cr = mContext.getContentResolver();
            Settings.Global.putInt(cr, Settings.Global.AIRPLANE_MODE_ON, enable ? 1 : 0);
            Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            intent.putExtra("state", enable);
            mContext.sendBroadcast(intent);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void onUserStart(int userId) {
        synchronized(mVpns) {
            Vpn userVpn = mVpns.get(userId);
            if (userVpn != null) {
                loge("Starting user already has a VPN");
                return;
            }
            userVpn = new Vpn(mHandler.getLooper(), mContext, mNetd, this, userId);
            mVpns.put(userId, userVpn);
        }
    }

    private void onUserStop(int userId) {
        synchronized(mVpns) {
            Vpn userVpn = mVpns.get(userId);
            if (userVpn == null) {
                loge("Stopping user has no VPN");
                return;
            }
            mVpns.delete(userId);
        }
    }

    private BroadcastReceiver mUserIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (userId == UserHandle.USER_NULL) return;

            if (Intent.ACTION_USER_STARTING.equals(action)) {
                onUserStart(userId);
            } else if (Intent.ACTION_USER_STOPPING.equals(action)) {
                onUserStop(userId);
            }
        }
    };

    @Override
    public LinkQualityInfo getLinkQualityInfo(int networkType) {
        enforceAccessPermission();
        if (isNetworkTypeValid(networkType) && mNetTrackers[networkType] != null) {
            return mNetTrackers[networkType].getLinkQualityInfo();
        } else {
            return null;
        }
    }

    @Override
    public LinkQualityInfo getActiveLinkQualityInfo() {
        enforceAccessPermission();
        if (isNetworkTypeValid(mActiveDefaultNetwork) &&
                mNetTrackers[mActiveDefaultNetwork] != null) {
            return mNetTrackers[mActiveDefaultNetwork].getLinkQualityInfo();
        } else {
            return null;
        }
    }

    @Override
    public LinkQualityInfo[] getAllLinkQualityInfo() {
        enforceAccessPermission();
        final ArrayList<LinkQualityInfo> result = Lists.newArrayList();
        for (NetworkStateTracker tracker : mNetTrackers) {
            if (tracker != null) {
                LinkQualityInfo li = tracker.getLinkQualityInfo();
                if (li != null) {
                    result.add(li);
                }
            }
        }

        return result.toArray(new LinkQualityInfo[result.size()]);
    }

    /* Infrastructure for network sampling */

    private void handleNetworkSamplingTimeout() {

        log("Sampling interval elapsed, updating statistics ..");

        // initialize list of interfaces ..
        Map<String, SamplingDataTracker.SamplingSnapshot> mapIfaceToSample =
                new HashMap<String, SamplingDataTracker.SamplingSnapshot>();
        for (NetworkStateTracker tracker : mNetTrackers) {
            if (tracker != null) {
                String ifaceName = tracker.getNetworkInterfaceName();
                if (ifaceName != null) {
                    mapIfaceToSample.put(ifaceName, null);
                }
            }
        }

        // Read samples for all interfaces
        SamplingDataTracker.getSamplingSnapshots(mapIfaceToSample);

        // process samples for all networks
        for (NetworkStateTracker tracker : mNetTrackers) {
            if (tracker != null) {
                String ifaceName = tracker.getNetworkInterfaceName();
                SamplingDataTracker.SamplingSnapshot ss = mapIfaceToSample.get(ifaceName);
                if (ss != null) {
                    // end the previous sampling cycle
                    tracker.stopSampling(ss);
                    // start a new sampling cycle ..
                    tracker.startSampling(ss);
                }
            }
        }

        log("Done.");

        int samplingIntervalInSeconds = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.CONNECTIVITY_SAMPLING_INTERVAL_IN_SECONDS,
                DEFAULT_SAMPLING_INTERVAL_IN_SECONDS);

        if (DBG) log("Setting timer for " + String.valueOf(samplingIntervalInSeconds) + "seconds");

        setAlarm(samplingIntervalInSeconds * 1000, mSampleIntervalElapsedIntent);
    }

    /**
     * Sets a network sampling alarm.
     */
    void setAlarm(int timeoutInMilliseconds, PendingIntent intent) {
        long wakeupTime = SystemClock.elapsedRealtime() + timeoutInMilliseconds;
        int alarmType;
        if (Resources.getSystem().getBoolean(
                R.bool.config_networkSamplingWakesDevice)) {
            alarmType = AlarmManager.ELAPSED_REALTIME_WAKEUP;
        } else {
            alarmType = AlarmManager.ELAPSED_REALTIME;
        }
        mAlarmManager.set(alarmType, wakeupTime, intent);
    }

    private final HashMap<Messenger, NetworkFactoryInfo> mNetworkFactoryInfos =
            new HashMap<Messenger, NetworkFactoryInfo>();
    private final HashMap<NetworkRequest, NetworkRequestInfo> mNetworkRequests =
            new HashMap<NetworkRequest, NetworkRequestInfo>();

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

    /**
     * Tracks info about the requester.
     * Also used to notice when the calling process dies so we can self-expire
     */
    private class NetworkRequestInfo implements IBinder.DeathRecipient {
        static final boolean REQUEST = true;
        static final boolean LISTEN = false;

        final NetworkRequest request;
        IBinder mBinder;
        final int mPid;
        final int mUid;
        final Messenger messenger;
        final boolean isRequest;

        NetworkRequestInfo(Messenger m, NetworkRequest r, IBinder binder, boolean isRequest) {
            super();
            messenger = m;
            request = r;
            mBinder = binder;
            mPid = getCallingPid();
            mUid = getCallingUid();
            this.isRequest = isRequest;

            try {
                mBinder.linkToDeath(this, 0);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        void unlinkDeathRecipient() {
            mBinder.unlinkToDeath(this, 0);
        }

        public void binderDied() {
            log("ConnectivityService NetworkRequestInfo binderDied(" +
                    request + ", " + mBinder + ")");
            releaseNetworkRequest(request);
        }

        public String toString() {
            return (isRequest ? "Request" : "Listen") + " from uid/pid:" + mUid + "/" +
                    mPid + " for " + request;
        }
    }

    @Override
    public NetworkRequest requestNetwork(NetworkCapabilities networkCapabilities,
            Messenger messenger, int timeoutMs, IBinder binder, int legacyType) {
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                == false) {
            enforceConnectivityInternalPermission();
        } else {
            enforceChangePermission();
        }

        networkCapabilities = new NetworkCapabilities(networkCapabilities);

        // if UID is restricted, don't allow them to bring up metered APNs
        if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                == false) {
            final int uidRules;
            synchronized(mRulesLock) {
                uidRules = mUidRules.get(Binder.getCallingUid(), RULE_ALLOW_ALL);
            }
            if ((uidRules & RULE_REJECT_METERED) != 0) {
                // we could silently fail or we can filter the available nets to only give
                // them those they have access to.  Chose the more useful
                networkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }
        }

        if (timeoutMs < 0 || timeoutMs > ConnectivityManager.MAX_NETWORK_REQUEST_TIMEOUT_MS) {
            throw new IllegalArgumentException("Bad timeout specified");
        }
        NetworkRequest networkRequest = new NetworkRequest(networkCapabilities, legacyType,
                nextNetworkRequestId());
        if (DBG) log("requestNetwork for " + networkRequest);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder,
                NetworkRequestInfo.REQUEST);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_REQUEST, nri));
        if (timeoutMs > 0) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(EVENT_TIMEOUT_NETWORK_REQUEST,
                    nri), timeoutMs);
        }
        return networkRequest;
    }

    @Override
    public NetworkRequest pendingRequestForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation) {
        // TODO
        return null;
    }

    @Override
    public NetworkRequest listenForNetwork(NetworkCapabilities networkCapabilities,
            Messenger messenger, IBinder binder) {
        enforceAccessPermission();

        NetworkRequest networkRequest = new NetworkRequest(new NetworkCapabilities(
                networkCapabilities), TYPE_NONE, nextNetworkRequestId());
        if (DBG) log("listenForNetwork for " + networkRequest);
        NetworkRequestInfo nri = new NetworkRequestInfo(messenger, networkRequest, binder,
                NetworkRequestInfo.LISTEN);

        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_LISTENER, nri));
        return networkRequest;
    }

    @Override
    public void pendingListenForNetwork(NetworkCapabilities networkCapabilities,
            PendingIntent operation) {
    }

    @Override
    public void releaseNetworkRequest(NetworkRequest networkRequest) {
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_RELEASE_NETWORK_REQUEST, getCallingUid(),
                0, networkRequest));
    }

    @Override
    public void registerNetworkFactory(Messenger messenger, String name) {
        enforceConnectivityInternalPermission();
        NetworkFactoryInfo nfi = new NetworkFactoryInfo(name, messenger, new AsyncChannel());
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_FACTORY, nfi));
    }

    private void handleRegisterNetworkFactory(NetworkFactoryInfo nfi) {
        if (VDBG) log("Got NetworkFactory Messenger for " + nfi.name);
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
            if (VDBG) log("Failed to find Messenger in unregisterNetworkFactory");
            return;
        }
        if (VDBG) log("unregisterNetworkFactory for " + nfi.name);
    }

    /**
     * NetworkAgentInfo supporting a request by requestId.
     * These have already been vetted (their Capabilities satisfy the request)
     * and the are the highest scored network available.
     * the are keyed off the Requests requestId.
     */
    private final SparseArray<NetworkAgentInfo> mNetworkForRequestId =
            new SparseArray<NetworkAgentInfo>();

    private final SparseArray<NetworkAgentInfo> mNetworkForNetId =
            new SparseArray<NetworkAgentInfo>();

    // NetworkAgentInfo keyed off its connecting messenger
    // TODO - eval if we can reduce the number of lists/hashmaps/sparsearrays
    private final HashMap<Messenger, NetworkAgentInfo> mNetworkAgentInfos =
            new HashMap<Messenger, NetworkAgentInfo>();

    private final NetworkRequest mDefaultRequest;

    private boolean isDefaultNetwork(NetworkAgentInfo nai) {
        return mNetworkForRequestId.get(mDefaultRequest.requestId) == nai;
    }

    public void registerNetworkAgent(Messenger messenger, NetworkInfo networkInfo,
            LinkProperties linkProperties, NetworkCapabilities networkCapabilities,
            int currentScore, NetworkMisc networkMisc) {
        enforceConnectivityInternalPermission();

        NetworkAgentInfo nai = new NetworkAgentInfo(messenger, new AsyncChannel(), nextNetId(),
            new NetworkInfo(networkInfo), new LinkProperties(linkProperties),
            new NetworkCapabilities(networkCapabilities), currentScore, mContext, mTrackerHandler,
            networkMisc);
        synchronized (this) {
            nai.networkMonitor.systemReady = mSystemReady;
        }
        if (VDBG) log("registerNetworkAgent " + nai);
        mHandler.sendMessage(mHandler.obtainMessage(EVENT_REGISTER_NETWORK_AGENT, nai));
    }

    private void handleRegisterNetworkAgent(NetworkAgentInfo na) {
        if (VDBG) log("Got NetworkAgent Messenger");
        mNetworkAgentInfos.put(na.messenger, na);
        synchronized (mNetworkForNetId) {
            mNetworkForNetId.put(na.network.netId, na);
        }
        na.asyncChannel.connect(mContext, mTrackerHandler, na.messenger);
        NetworkInfo networkInfo = na.networkInfo;
        na.networkInfo = null;
        updateNetworkInfo(na, networkInfo);
    }

    private void updateLinkProperties(NetworkAgentInfo networkAgent, LinkProperties oldLp) {
        LinkProperties newLp = networkAgent.linkProperties;
        int netId = networkAgent.network.netId;

        updateInterfaces(newLp, oldLp, netId);
        updateMtu(newLp, oldLp);
        updateTcpBufferSizes(networkAgent);
        // TODO - figure out what to do for clat
//        for (LinkProperties lp : newLp.getStackedLinks()) {
//            updateMtu(lp, null);
//        }
        final boolean flushDns = updateRoutes(newLp, oldLp, netId);
        updateDnses(newLp, oldLp, netId, flushDns);
        updateClat(newLp, oldLp, networkAgent);
    }

    private void updateClat(LinkProperties newLp, LinkProperties oldLp, NetworkAgentInfo na) {
        // Update 464xlat state.
        if (mClat.requiresClat(na)) {

            // If the connection was previously using clat, but is not using it now, stop the clat
            // daemon. Normally, this happens automatically when the connection disconnects, but if
            // the disconnect is not reported, or if the connection's LinkProperties changed for
            // some other reason (e.g., handoff changes the IP addresses on the link), it would
            // still be running. If it's not running, then stopping it is a no-op.
            if (Nat464Xlat.isRunningClat(oldLp) && !Nat464Xlat.isRunningClat(newLp)) {
                mClat.stopClat();
            }
            // If the link requires clat to be running, then start the daemon now.
            if (na.networkInfo.isConnected()) {
                mClat.startClat(na);
            } else {
                mClat.stopClat();
            }
        }
    }

    private void updateInterfaces(LinkProperties newLp, LinkProperties oldLp, int netId) {
        CompareResult<String> interfaceDiff = new CompareResult<String>();
        if (oldLp != null) {
            interfaceDiff = oldLp.compareAllInterfaceNames(newLp);
        } else if (newLp != null) {
            interfaceDiff.added = newLp.getAllInterfaceNames();
        }
        for (String iface : interfaceDiff.added) {
            try {
                mNetd.addInterfaceToNetwork(iface, netId);
            } catch (Exception e) {
                loge("Exception adding interface: " + e);
            }
        }
        for (String iface : interfaceDiff.removed) {
            try {
                mNetd.removeInterfaceFromNetwork(iface, netId);
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
        CompareResult<RouteInfo> routeDiff = new CompareResult<RouteInfo>();
        if (oldLp != null) {
            routeDiff = oldLp.compareAllRoutes(newLp);
        } else if (newLp != null) {
            routeDiff.added = newLp.getAllRoutes();
        }

        // add routes before removing old in case it helps with continuous connectivity

        // do this twice, adding non-nexthop routes first, then routes they are dependent on
        for (RouteInfo route : routeDiff.added) {
            if (route.hasGateway()) continue;
            try {
                mNetd.addRoute(netId, route);
            } catch (Exception e) {
                loge("Exception in addRoute for non-gateway: " + e);
            }
        }
        for (RouteInfo route : routeDiff.added) {
            if (route.hasGateway() == false) continue;
            try {
                mNetd.addRoute(netId, route);
            } catch (Exception e) {
                loge("Exception in addRoute for gateway: " + e);
            }
        }

        for (RouteInfo route : routeDiff.removed) {
            try {
                mNetd.removeRoute(netId, route);
            } catch (Exception e) {
                loge("Exception in removeRoute: " + e);
            }
        }
        return !routeDiff.added.isEmpty() || !routeDiff.removed.isEmpty();
    }
    private void updateDnses(LinkProperties newLp, LinkProperties oldLp, int netId, boolean flush) {
        if (oldLp == null || (newLp.isIdenticalDnses(oldLp) == false)) {
            Collection<InetAddress> dnses = newLp.getDnsServers();
            if (dnses.size() == 0 && mDefaultDns != null) {
                dnses = new ArrayList();
                dnses.add(mDefaultDns);
                if (DBG) {
                    loge("no dns provided for netId " + netId + ", so using defaults");
                }
            }
            try {
                mNetd.setDnsServersForNetwork(netId, NetworkUtils.makeStrings(dnses),
                    newLp.getDomains());
            } catch (Exception e) {
                loge("Exception in setDnsServersForNetwork: " + e);
            }
            NetworkAgentInfo defaultNai = mNetworkForRequestId.get(mDefaultRequest.requestId);
            if (defaultNai != null && defaultNai.network.netId == netId) {
                setDefaultDnsSystemProperties(dnses);
            }
            flushVmDnsCache();
        } else if (flush) {
            try {
                mNetd.flushNetworkDnsCache(netId);
            } catch (Exception e) {
                loge("Exception in flushNetworkDnsCache: " + e);
            }
            flushVmDnsCache();
        }
    }

    private void setDefaultDnsSystemProperties(Collection<InetAddress> dnses) {
        int last = 0;
        for (InetAddress dns : dnses) {
            ++last;
            String key = "net.dns" + last;
            String value = dns.getHostAddress();
            SystemProperties.set(key, value);
        }
        for (int i = last + 1; i <= mNumDnsEntries; ++i) {
            String key = "net.dns" + i;
            SystemProperties.set(key, "");
        }
        mNumDnsEntries = last;
    }


    private void updateCapabilities(NetworkAgentInfo networkAgent,
            NetworkCapabilities networkCapabilities) {
        // TODO - what else here?  Verify still satisfies everybody?
        // Check if satisfies somebody new?  call callbacks?
        synchronized (networkAgent) {
            networkAgent.networkCapabilities = networkCapabilities;
        }
    }

    private void sendUpdatedScoreToFactories(NetworkRequest networkRequest, int score) {
        if (VDBG) log("sending new Min Network Score(" + score + "): " + networkRequest.toString());
        for (NetworkFactoryInfo nfi : mNetworkFactoryInfos.values()) {
            nfi.asyncChannel.sendMessage(android.net.NetworkFactory.CMD_REQUEST_NETWORK, score, 0,
                    networkRequest);
        }
    }

    private void callCallbackForRequest(NetworkRequestInfo nri,
            NetworkAgentInfo networkAgent, int notificationType) {
        if (nri.messenger == null) return;  // Default request has no msgr
        Object o;
        int a1 = 0;
        int a2 = 0;
        switch (notificationType) {
            case ConnectivityManager.CALLBACK_LOSING:
                a1 = 30 * 1000; // TODO - read this from NetworkMonitor
                // fall through
            case ConnectivityManager.CALLBACK_PRECHECK:
            case ConnectivityManager.CALLBACK_AVAILABLE:
            case ConnectivityManager.CALLBACK_LOST:
            case ConnectivityManager.CALLBACK_CAP_CHANGED:
            case ConnectivityManager.CALLBACK_IP_CHANGED: {
                o = new NetworkRequest(nri.request);
                a2 = networkAgent.network.netId;
                break;
            }
            case ConnectivityManager.CALLBACK_UNAVAIL:
            case ConnectivityManager.CALLBACK_RELEASED: {
                o = new NetworkRequest(nri.request);
                break;
            }
            default: {
                loge("Unknown notificationType " + notificationType);
                return;
            }
        }
        Message msg = Message.obtain();
        msg.arg1 = a1;
        msg.arg2 = a2;
        msg.obj = o;
        msg.what = notificationType;
        try {
            if (VDBG) log("sending notification " + notificationType + " for " + nri.request);
            nri.messenger.send(msg);
        } catch (RemoteException e) {
            // may occur naturally in the race of binder death.
            loge("RemoteException caught trying to send a callback msg for " + nri.request);
        }
    }

    private void handleLingerComplete(NetworkAgentInfo oldNetwork) {
        if (oldNetwork == null) {
            loge("Unknown NetworkAgentInfo in handleLingerComplete");
            return;
        }
        if (DBG) log("handleLingerComplete for " + oldNetwork.name());
        if (DBG) {
            if (oldNetwork.networkRequests.size() != 0) {
                loge("Dead network still had " + oldNetwork.networkRequests.size() + " requests");
            }
        }
        oldNetwork.asyncChannel.disconnect();
    }

    private void makeDefault(NetworkAgentInfo newNetwork) {
        if (VDBG) log("Switching to new default network: " + newNetwork);
        mActiveDefaultNetwork = newNetwork.networkInfo.getType();
        setupDataActivityTracking(newNetwork);
        try {
            mNetd.setDefaultNetId(newNetwork.network.netId);
        } catch (Exception e) {
            loge("Exception setting default network :" + e);
        }
        handleApplyDefaultProxy(newNetwork.linkProperties.getHttpProxy());
        updateTcpBufferSizes(newNetwork);
    }

    private void handleConnectionValidated(NetworkAgentInfo newNetwork) {
        if (newNetwork == null) {
            loge("Unknown NetworkAgentInfo in handleConnectionValidated");
            return;
        }
        boolean keep = newNetwork.isVPN();
        boolean isNewDefault = false;
        if (DBG) log("handleConnectionValidated for "+newNetwork.name());
        // check if any NetworkRequest wants this NetworkAgent
        ArrayList<NetworkAgentInfo> affectedNetworks = new ArrayList<NetworkAgentInfo>();
        if (VDBG) log(" new Network has: " + newNetwork.networkCapabilities);
        for (NetworkRequestInfo nri : mNetworkRequests.values()) {
            NetworkAgentInfo currentNetwork = mNetworkForRequestId.get(nri.request.requestId);
            if (newNetwork == currentNetwork) {
                if (VDBG) log("Network " + newNetwork.name() + " was already satisfying" +
                              " request " + nri.request.requestId + ". No change.");
                keep = true;
                continue;
            }

            // check if it satisfies the NetworkCapabilities
            if (VDBG) log("  checking if request is satisfied: " + nri.request);
            if (nri.request.networkCapabilities.satisfiedByNetworkCapabilities(
                    newNetwork.networkCapabilities)) {
                // next check if it's better than any current network we're using for
                // this request
                if (VDBG) {
                    log("currentScore = " +
                            (currentNetwork != null ? currentNetwork.currentScore : 0) +
                            ", newScore = " + newNetwork.currentScore);
                }
                if (currentNetwork == null ||
                        currentNetwork.currentScore < newNetwork.currentScore) {
                    if (currentNetwork != null) {
                        if (VDBG) log("   accepting network in place of " + currentNetwork.name());
                        currentNetwork.networkRequests.remove(nri.request.requestId);
                        currentNetwork.networkLingered.add(nri.request);
                        affectedNetworks.add(currentNetwork);
                    } else {
                        if (VDBG) log("   accepting network in place of null");
                    }
                    mNetworkForRequestId.put(nri.request.requestId, newNetwork);
                    newNetwork.addRequest(nri.request);
                    if (nri.isRequest && nri.request.legacyType != TYPE_NONE) {
                        mLegacyTypeTracker.add(nri.request.legacyType, newNetwork);
                    }
                    keep = true;
                    // TODO - this could get expensive if we have alot of requests for this
                    // network.  Think about if there is a way to reduce this.  Push
                    // netid->request mapping to each factory?
                    sendUpdatedScoreToFactories(nri.request, newNetwork.currentScore);
                    if (mDefaultRequest.requestId == nri.request.requestId) {
                        isNewDefault = true;
                        updateActiveDefaultNetwork(newNetwork);
                        if (newNetwork.linkProperties != null) {
                            updateTcpBufferSizes(newNetwork);
                            setDefaultDnsSystemProperties(
                                    newNetwork.linkProperties.getDnsServers());
                        } else {
                            setDefaultDnsSystemProperties(new ArrayList<InetAddress>());
                        }
                        // Maintain the illusion: since the legacy API only
                        // understands one network at a time, we must pretend
                        // that the current default network disconnected before
                        // the new one connected.
                        if (currentNetwork != null) {
                            mLegacyTypeTracker.remove(currentNetwork.networkInfo.getType(),
                                                      currentNetwork);
                        }
                        mDefaultInetConditionPublished = 100;
                        mLegacyTypeTracker.add(newNetwork.networkInfo.getType(), newNetwork);
                    }
                }
            }
        }
        for (NetworkAgentInfo nai : affectedNetworks) {
            boolean teardown = !nai.isVPN();
            for (int i = 0; i < nai.networkRequests.size() && teardown; i++) {
                NetworkRequest nr = nai.networkRequests.valueAt(i);
                try {
                if (mNetworkRequests.get(nr).isRequest) {
                    teardown = false;
                }
                } catch (Exception e) {
                    loge("Request " + nr + " not found in mNetworkRequests.");
                    loge("  it came from request list  of " + nai.name());
                }
            }
            if (teardown) {
                nai.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_LINGER);
                notifyNetworkCallbacks(nai, ConnectivityManager.CALLBACK_LOSING);
            } else {
                // not going to linger, so kill the list of linger networks..  only
                // notify them of linger if it happens as the result of gaining another,
                // but if they transition and old network stays up, don't tell them of linger
                // or very delayed loss
                nai.networkLingered.clear();
                if (VDBG) log("Lingered for " + nai.name() + " cleared");
            }
        }
        if (keep) {
            if (isNewDefault) {
                makeDefault(newNetwork);
                synchronized (ConnectivityService.this) {
                    // have a new default network, release the transition wakelock in
                    // a second if it's held.  The second pause is to allow apps
                    // to reconnect over the new network
                    if (mNetTransitionWakeLock.isHeld()) {
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(
                                EVENT_CLEAR_NET_TRANSITION_WAKELOCK,
                                mNetTransitionWakeLockSerialNumber, 0),
                                1000);
                    }
                }
            }

            // Notify battery stats service about this network, both the normal
            // interface and any stacked links.
            try {
                final IBatteryStats bs = BatteryStatsService.getService();
                final int type = newNetwork.networkInfo.getType();

                final String baseIface = newNetwork.linkProperties.getInterfaceName();
                bs.noteNetworkInterfaceType(baseIface, type);
                for (LinkProperties stacked : newNetwork.linkProperties.getStackedLinks()) {
                    final String stackedIface = stacked.getInterfaceName();
                    bs.noteNetworkInterfaceType(stackedIface, type);
                    NetworkStatsFactory.noteStackedIface(stackedIface, baseIface);
                }
            } catch (RemoteException ignored) {
            }

            notifyNetworkCallbacks(newNetwork, ConnectivityManager.CALLBACK_AVAILABLE);
        } else {
            if (DBG && newNetwork.networkRequests.size() != 0) {
                loge("tearing down network with live requests:");
                for (int i=0; i < newNetwork.networkRequests.size(); i++) {
                    loge("  " + newNetwork.networkRequests.valueAt(i));
                }
            }
            if (VDBG) log("Validated network turns out to be unwanted.  Tear it down.");
            newNetwork.asyncChannel.disconnect();
        }
    }


    private void updateNetworkInfo(NetworkAgentInfo networkAgent, NetworkInfo newInfo) {
        NetworkInfo.State state = newInfo.getState();
        NetworkInfo oldInfo = null;
        synchronized (networkAgent) {
            oldInfo = networkAgent.networkInfo;
            networkAgent.networkInfo = newInfo;
        }
        if (networkAgent.isVPN() && mLockdownTracker != null) {
            mLockdownTracker.onVpnStateChanged(newInfo);
        }

        if (oldInfo != null && oldInfo.getState() == state) {
            if (VDBG) log("ignoring duplicate network state non-change");
            return;
        }
        if (DBG) {
            log(networkAgent.name() + " EVENT_NETWORK_INFO_CHANGED, going from " +
                    (oldInfo == null ? "null" : oldInfo.getState()) +
                    " to " + state);
        }

        if (state == NetworkInfo.State.CONNECTED && !networkAgent.created) {
            try {
                // This should never fail.  Specifying an already in use NetID will cause failure.
                if (networkAgent.isVPN()) {
                    mNetd.createVirtualNetwork(networkAgent.network.netId,
                            !networkAgent.linkProperties.getDnsServers().isEmpty(),
                            (networkAgent.networkMisc == null ||
                                !networkAgent.networkMisc.allowBypass));
                } else {
                    mNetd.createPhysicalNetwork(networkAgent.network.netId);
                }
            } catch (Exception e) {
                loge("Error creating network " + networkAgent.network.netId + ": "
                        + e.getMessage());
                return;
            }
            networkAgent.created = true;
            updateLinkProperties(networkAgent, null);
            notifyNetworkCallbacks(networkAgent, ConnectivityManager.CALLBACK_PRECHECK);
            networkAgent.networkMonitor.sendMessage(NetworkMonitor.CMD_NETWORK_CONNECTED);
            if (networkAgent.isVPN()) {
                // Temporarily disable the default proxy (not global).
                synchronized (mProxyLock) {
                    if (!mDefaultProxyDisabled) {
                        mDefaultProxyDisabled = true;
                        if (mGlobalProxy == null && mDefaultProxy != null) {
                            sendProxyBroadcast(null);
                        }
                    }
                }
                // TODO: support proxy per network.
            }
            // Make default network if we have no default.  Any network is better than no network.
            if (mNetworkForRequestId.get(mDefaultRequest.requestId) == null &&
                    networkAgent.isVPN() == false &&
                    mDefaultRequest.networkCapabilities.satisfiedByNetworkCapabilities(
                    networkAgent.networkCapabilities)) {
                makeDefault(networkAgent);
            }
        } else if (state == NetworkInfo.State.DISCONNECTED ||
                state == NetworkInfo.State.SUSPENDED) {
            networkAgent.asyncChannel.disconnect();
            if (networkAgent.isVPN()) {
                synchronized (mProxyLock) {
                    if (mDefaultProxyDisabled) {
                        mDefaultProxyDisabled = false;
                        if (mGlobalProxy == null && mDefaultProxy != null) {
                            sendProxyBroadcast(mDefaultProxy);
                        }
                    }
                }
            }
        }
    }

    private void updateNetworkScore(NetworkAgentInfo nai, int score) {
        if (DBG) log("updateNetworkScore for " + nai.name() + " to " + score);

        nai.currentScore = score;

        // TODO - This will not do the right thing if this network is lowering
        // its score and has requests that can be served by other
        // currently-active networks, or if the network is increasing its
        // score and other networks have requests that can be better served
        // by this network.
        //
        // Really we want to see if any of our requests migrate to other
        // active/lingered networks and if any other requests migrate to us (depending
        // on increasing/decreasing currentScore.  That's a bit of work and probably our
        // score checking/network allocation code needs to be modularized so we can understand
        // (see handleConnectionValided for an example).
        //
        // As a first order approx, lets just advertise the new score to factories.  If
        // somebody can beat it they will nominate a network and our normal net replacement
        // code will fire.
        for (int i = 0; i < nai.networkRequests.size(); i++) {
            NetworkRequest nr = nai.networkRequests.valueAt(i);
            sendUpdatedScoreToFactories(nr, score);
        }
    }

    // notify only this one new request of the current state
    protected void notifyNetworkCallback(NetworkAgentInfo nai, NetworkRequestInfo nri) {
        int notifyType = ConnectivityManager.CALLBACK_AVAILABLE;
        // TODO - read state from monitor to decide what to send.
//        if (nai.networkMonitor.isLingering()) {
//            notifyType = NetworkCallbacks.LOSING;
//        } else if (nai.networkMonitor.isEvaluating()) {
//            notifyType = NetworkCallbacks.callCallbackForRequest(request, nai, notifyType);
//        }
        callCallbackForRequest(nri, nai, notifyType);
    }

    private void sendLegacyNetworkBroadcast(NetworkAgentInfo nai, boolean connected, int type) {
        // The NetworkInfo we actually send out has no bearing on the real
        // state of affairs. For example, if the default connection is mobile,
        // and a request for HIPRI has just gone away, we need to pretend that
        // HIPRI has just disconnected. So we need to set the type to HIPRI and
        // the state to DISCONNECTED, even though the network is of type MOBILE
        // and is still connected.
        NetworkInfo info = new NetworkInfo(nai.networkInfo);
        info.setType(type);
        if (connected) {
            info.setDetailedState(DetailedState.CONNECTED, null, info.getExtraInfo());
            sendConnectedBroadcastDelayed(info, getConnectivityChangeDelay());
        } else {
            info.setDetailedState(DetailedState.DISCONNECTED, null, info.getExtraInfo());
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
            if (nai.networkRequests.get(mDefaultRequest.requestId) != null) {
                newDefaultAgent = mNetworkForRequestId.get(mDefaultRequest.requestId);
                if (newDefaultAgent != null) {
                    intent.putExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO,
                            newDefaultAgent.networkInfo);
                } else {
                    intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);
                }
            }
            intent.putExtra(ConnectivityManager.EXTRA_INET_CONDITION,
                    mDefaultInetConditionPublished);
            final Intent immediateIntent = new Intent(intent);
            immediateIntent.setAction(CONNECTIVITY_ACTION_IMMEDIATE);
            sendStickyBroadcast(immediateIntent);
            sendStickyBroadcastDelayed(intent, getConnectivityChangeDelay());
            if (newDefaultAgent != null) {
                sendConnectedBroadcastDelayed(newDefaultAgent.networkInfo,
                getConnectivityChangeDelay());
            }
        }
    }

    protected void notifyNetworkCallbacks(NetworkAgentInfo networkAgent, int notifyType) {
        if (VDBG) log("notifyType " + notifyType + " for " + networkAgent.name());
        for (int i = 0; i < networkAgent.networkRequests.size(); i++) {
            NetworkRequest nr = networkAgent.networkRequests.valueAt(i);
            NetworkRequestInfo nri = mNetworkRequests.get(nr);
            if (VDBG) log(" sending notification for " + nr);
            callCallbackForRequest(nri, networkAgent, notifyType);
        }
    }

    private LinkProperties getLinkPropertiesForTypeInternal(int networkType) {
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai != null) {
            synchronized (nai) {
                return new LinkProperties(nai.linkProperties);
            }
        }
        return new LinkProperties();
    }

    private NetworkInfo getNetworkInfoForType(int networkType) {
        if (!mLegacyTypeTracker.isTypeSupported(networkType))
            return null;

        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai != null) {
            NetworkInfo result = new NetworkInfo(nai.networkInfo);
            result.setType(networkType);
            return result;
        } else {
           return new NetworkInfo(networkType, 0, "Unknown", "");
        }
    }

    private NetworkCapabilities getNetworkCapabilitiesForType(int networkType) {
        NetworkAgentInfo nai = mLegacyTypeTracker.getNetworkForType(networkType);
        if (nai != null) {
            synchronized (nai) {
                return new NetworkCapabilities(nai.networkCapabilities);
            }
        }
        return new NetworkCapabilities();
    }

    @Override
    public boolean addVpnAddress(String address, int prefixLength) {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            return mVpns.get(user).addAddress(address, prefixLength);
        }
    }

    @Override
    public boolean removeVpnAddress(String address, int prefixLength) {
        throwIfLockdownEnabled();
        int user = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mVpns) {
            return mVpns.get(user).removeAddress(address, prefixLength);
        }
    }
}
