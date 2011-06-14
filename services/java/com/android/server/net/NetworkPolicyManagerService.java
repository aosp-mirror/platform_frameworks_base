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

package com.android.server.net;

import static android.Manifest.permission.CONNECTIVITY_INTERNAL;
import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.MANAGE_APP_TOKENS;
import static android.Manifest.permission.MANAGE_NETWORK_POLICY;
import static android.Manifest.permission.READ_PHONE_STATE;
import static android.net.ConnectivityManager.CONNECTIVITY_ACTION;
import static android.net.NetworkPolicy.LIMIT_DISABLED;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_PAID_BACKGROUND;
import static android.net.NetworkPolicyManager.RULE_ALLOW_ALL;
import static android.net.NetworkPolicyManager.RULE_REJECT_PAID;
import static android.net.NetworkPolicyManager.computeLastCycleBoundary;
import static android.net.NetworkPolicyManager.dumpPolicy;
import static android.net.NetworkPolicyManager.dumpRules;
import static android.net.TrafficStats.TEMPLATE_MOBILE_ALL;
import static android.net.TrafficStats.isNetworkTemplateMobile;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static com.android.internal.util.Preconditions.checkNotNull;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.IConnectivityManager;
import android.net.INetworkPolicyListener;
import android.net.INetworkPolicyManager;
import android.net.INetworkStatsService;
import android.net.NetworkPolicy;
import android.net.NetworkState;
import android.net.NetworkStats;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IPowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.text.format.Time;
import android.util.NtpTrustedTime;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TrustedTime;
import android.util.Xml;

import com.android.internal.os.AtomicFile;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Objects;
import com.google.android.collect.Lists;
import com.google.android.collect.Maps;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import libcore.io.IoUtils;

/**
 * Service that maintains low-level network policy rules and collects usage
 * statistics to drive those rules.
 * <p>
 * Derives active rules by combining a given policy with other system status,
 * and delivers to listeners, such as {@link ConnectivityManager}, for
 * enforcement.
 */
public class NetworkPolicyManagerService extends INetworkPolicyManager.Stub {
    private static final String TAG = "NetworkPolicy";
    private static final boolean LOGD = true;
    private static final boolean LOGV = false;

    private static final int VERSION_CURRENT = 1;

    private static final long KB_IN_BYTES = 1024;
    private static final long MB_IN_BYTES = KB_IN_BYTES * 1024;
    private static final long GB_IN_BYTES = MB_IN_BYTES * 1024;

    private static final String TAG_POLICY_LIST = "policy-list";
    private static final String TAG_NETWORK_POLICY = "network-policy";
    private static final String TAG_UID_POLICY = "uid-policy";

    private static final String ATTR_VERSION = "version";
    private static final String ATTR_NETWORK_TEMPLATE = "networkTemplate";
    private static final String ATTR_SUBSCRIBER_ID = "subscriberId";
    private static final String ATTR_CYCLE_DAY = "cycleDay";
    private static final String ATTR_WARNING_BYTES = "warningBytes";
    private static final String ATTR_LIMIT_BYTES = "limitBytes";
    private static final String ATTR_UID = "uid";
    private static final String ATTR_POLICY = "policy";

    private static final long TIME_CACHE_MAX_AGE = DAY_IN_MILLIS;

    private final Context mContext;
    private final IActivityManager mActivityManager;
    private final IPowerManager mPowerManager;
    private final INetworkStatsService mNetworkStats;
    private final TrustedTime mTime;

    private IConnectivityManager mConnManager;

    private final Object mRulesLock = new Object();

    private boolean mScreenOn;

    /** Current policy for network templates. */
    private ArrayList<NetworkPolicy> mNetworkPolicy = Lists.newArrayList();

    /** Current policy for each UID. */
    private SparseIntArray mUidPolicy = new SparseIntArray();
    /** Current derived network rules for each UID. */
    private SparseIntArray mUidRules = new SparseIntArray();

    /** Foreground at both UID and PID granularity. */
    private SparseBooleanArray mUidForeground = new SparseBooleanArray();
    private SparseArray<SparseBooleanArray> mUidPidForeground = new SparseArray<
            SparseBooleanArray>();

    private final RemoteCallbackList<INetworkPolicyListener> mListeners = new RemoteCallbackList<
            INetworkPolicyListener>();

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final AtomicFile mPolicyFile;

    // TODO: keep whitelist of system-critical services that should never have
    // rules enforced, such as system, phone, and radio UIDs.

    public NetworkPolicyManagerService(Context context, IActivityManager activityManager,
            IPowerManager powerManager, INetworkStatsService networkStats) {
        // TODO: move to using cached NtpTrustedTime
        this(context, activityManager, powerManager, networkStats, new NtpTrustedTime(),
                getSystemDir());
    }

    private static File getSystemDir() {
        return new File(Environment.getDataDirectory(), "system");
    }

    public NetworkPolicyManagerService(Context context, IActivityManager activityManager,
            IPowerManager powerManager, INetworkStatsService networkStats, TrustedTime time,
            File systemDir) {
        mContext = checkNotNull(context, "missing context");
        mActivityManager = checkNotNull(activityManager, "missing activityManager");
        mPowerManager = checkNotNull(powerManager, "missing powerManager");
        mNetworkStats = checkNotNull(networkStats, "missing networkStats");
        mTime = checkNotNull(time, "missing TrustedTime");

        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mPolicyFile = new AtomicFile(new File(systemDir, "netpolicy.xml"));
    }

    public void bindConnectivityManager(IConnectivityManager connManager) {
        mConnManager = checkNotNull(connManager, "missing IConnectivityManager");
    }

    public void systemReady() {
        synchronized (mRulesLock) {
            // read policy from disk
            readPolicyLocked();
        }

        updateScreenOn();

        try {
            mActivityManager.registerProcessObserver(mProcessObserver);
        } catch (RemoteException e) {
            // ouch, no foregroundActivities updates means some processes may
            // never get network access.
            Slog.e(TAG, "unable to register IProcessObserver", e);
        }

        // TODO: traverse existing processes to know foreground state, or have
        // activitymanager dispatch current state when new observer attached.

        final IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, screenFilter);

        // watch for network interfaces to be claimed
        final IntentFilter ifaceFilter = new IntentFilter();
        ifaceFilter.addAction(CONNECTIVITY_ACTION);
        mContext.registerReceiver(mIfaceReceiver, ifaceFilter, CONNECTIVITY_INTERNAL, mHandler);

    }

    private IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {
            // only someone like AMS should only be calling us
            mContext.enforceCallingOrSelfPermission(MANAGE_APP_TOKENS, TAG);

            synchronized (mRulesLock) {
                // because a uid can have multiple pids running inside, we need to
                // remember all pid states and summarize foreground at uid level.

                // record foreground for this specific pid
                SparseBooleanArray pidForeground = mUidPidForeground.get(uid);
                if (pidForeground == null) {
                    pidForeground = new SparseBooleanArray(2);
                    mUidPidForeground.put(uid, pidForeground);
                }
                pidForeground.put(pid, foregroundActivities);
                computeUidForegroundLocked(uid);
            }
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            // only someone like AMS should only be calling us
            mContext.enforceCallingOrSelfPermission(MANAGE_APP_TOKENS, TAG);

            synchronized (mRulesLock) {
                // clear records and recompute, when they exist
                final SparseBooleanArray pidForeground = mUidPidForeground.get(uid);
                if (pidForeground != null) {
                    pidForeground.delete(pid);
                    computeUidForegroundLocked(uid);
                }
            }
        }
    };

    private BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mRulesLock) {
                // screen-related broadcasts are protected by system, no need
                // for permissions check.
                updateScreenOn();
            }
        }
    };

    /**
     * Receiver that watches for {@link IConnectivityManager} to claim network
     * interfaces. Used to apply {@link NetworkPolicy} to matching networks.
     */
    private BroadcastReceiver mIfaceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // on background handler thread, and verified CONNECTIVITY_INTERNAL
            // permission above.
            synchronized (mRulesLock) {
                ensureActiveMobilePolicyLocked();
                updateIfacesLocked();
            }
        }
    };

    /**
     * Examine all connected {@link NetworkState}, looking for
     * {@link NetworkPolicy} that need to be enforced. When matches found, set
     * remaining quota based on usage cycle and historical stats.
     */
    private void updateIfacesLocked() {
        if (LOGV) Slog.v(TAG, "updateIfacesLocked()");

        final NetworkState[] states;
        try {
            states = mConnManager.getAllNetworkState();
        } catch (RemoteException e) {
            Slog.w(TAG, "problem reading network state");
            return;
        }

        // first, derive identity for all connected networks, which can be used
        // to match against templates.
        final HashMap<NetworkIdentity, String> networks = Maps.newHashMap();
        for (NetworkState state : states) {
            // stash identity and iface away for later use
            if (state.networkInfo.isConnected()) {
                final String iface = state.linkProperties.getInterfaceName();
                final NetworkIdentity ident = NetworkIdentity.buildNetworkIdentity(mContext, state);
                networks.put(ident, iface);
            }
        }

        // build list of rules and ifaces to enforce them against
        final HashMap<NetworkPolicy, String[]> rules = Maps.newHashMap();
        final ArrayList<String> ifaceList = Lists.newArrayList();
        for (NetworkPolicy policy : mNetworkPolicy) {

            // collect all active ifaces that match this template
            ifaceList.clear();
            for (NetworkIdentity ident : networks.keySet()) {
                if (ident.matchesTemplate(policy.networkTemplate, policy.subscriberId)) {
                    final String iface = networks.get(ident);
                    ifaceList.add(iface);
                }
            }

            if (ifaceList.size() > 0) {
                final String[] ifaces = ifaceList.toArray(new String[ifaceList.size()]);
                rules.put(policy, ifaces);
            }
        }

        // try refreshing time source when stale
        if (mTime.getCacheAge() > TIME_CACHE_MAX_AGE) {
            mTime.forceRefresh();
        }

        final long currentTime = mTime.hasCache() ? mTime.currentTimeMillis()
                : System.currentTimeMillis();

        // apply each policy that we found ifaces for; compute remaining data
        // based on current cycle and historical stats, and push to kernel.
        for (NetworkPolicy policy : rules.keySet()) {
            final String[] ifaces = rules.get(policy);

            final long start = computeLastCycleBoundary(currentTime, policy);
            final long end = currentTime;

            final NetworkStats stats;
            final long total;
            try {
                stats = mNetworkStats.getSummaryForNetwork(
                        start, end, policy.networkTemplate, policy.subscriberId);
                total = stats.rx[0] + stats.tx[0];
            } catch (RemoteException e) {
                Slog.w(TAG, "problem reading summary for template " + policy.networkTemplate);
                continue;
            }

            if (LOGD) {
                Slog.d(TAG, "applying policy " + policy.toString() + " to ifaces "
                        + Arrays.toString(ifaces));
            }

            // TODO: register for warning notification trigger through NMS

            if (policy.limitBytes != NetworkPolicy.LIMIT_DISABLED) {
                // remaining "quota" is based on usage in current cycle
                final long quota = Math.max(0, policy.limitBytes - total);

                // TODO: push quota rule down through NMS
            }
        }
    }

    /**
     * Once any {@link #mNetworkPolicy} are loaded from disk, ensure that we
     * have at least a default mobile policy defined.
     */
    private void ensureActiveMobilePolicyLocked() {
        if (LOGV) Slog.v(TAG, "ensureActiveMobilePolicyLocked()");
        final String subscriberId = getActiveSubscriberId();

        // examine to see if any policy is defined for active mobile
        boolean mobileDefined = false;
        for (NetworkPolicy policy : mNetworkPolicy) {
            if (isNetworkTemplateMobile(policy.networkTemplate)
                    && Objects.equal(subscriberId, policy.subscriberId)) {
                mobileDefined = true;
            }
        }

        if (!mobileDefined) {
            Slog.i(TAG, "no policy for active mobile network; generating default policy");

            // default mobile policy has combined 4GB warning, and assume usage
            // cycle starts today today.

            // TODO: move this policy definition to overlay or secure setting
            final Time time = new Time(Time.TIMEZONE_UTC);
            time.setToNow();
            final int cycleDay = time.monthDay;

            mNetworkPolicy.add(new NetworkPolicy(
                    TEMPLATE_MOBILE_ALL, subscriberId, cycleDay, 4 * GB_IN_BYTES, LIMIT_DISABLED));
            writePolicyLocked();
        }
    }

    private void readPolicyLocked() {
        if (LOGV) Slog.v(TAG, "readPolicyLocked()");

        // clear any existing policy and read from disk
        mNetworkPolicy.clear();
        mUidPolicy.clear();

        FileInputStream fis = null;
        try {
            fis = mPolicyFile.openRead();
            final XmlPullParser in = Xml.newPullParser();
            in.setInput(fis, null);

            int type;
            int version = VERSION_CURRENT;
            while ((type = in.next()) != END_DOCUMENT) {
                final String tag = in.getName();
                if (type == START_TAG) {
                    if (TAG_POLICY_LIST.equals(tag)) {
                        version = readIntAttribute(in, ATTR_VERSION);

                    } else if (TAG_NETWORK_POLICY.equals(tag)) {
                        final int networkTemplate = readIntAttribute(in, ATTR_NETWORK_TEMPLATE);
                        final String subscriberId = in.getAttributeValue(null, ATTR_SUBSCRIBER_ID);
                        final int cycleDay = readIntAttribute(in, ATTR_CYCLE_DAY);
                        final long warningBytes = readLongAttribute(in, ATTR_WARNING_BYTES);
                        final long limitBytes = readLongAttribute(in, ATTR_LIMIT_BYTES);

                        mNetworkPolicy.add(new NetworkPolicy(
                                networkTemplate, subscriberId, cycleDay, warningBytes, limitBytes));

                    } else if (TAG_UID_POLICY.equals(tag)) {
                        final int uid = readIntAttribute(in, ATTR_UID);
                        final int policy = readIntAttribute(in, ATTR_POLICY);

                        mUidPolicy.put(uid, policy);
                    }
                }
            }

        } catch (FileNotFoundException e) {
            // missing policy is okay, probably first boot
        } catch (IOException e) {
            Slog.e(TAG, "problem reading network stats", e);
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "problem reading network stats", e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    private void writePolicyLocked() {
        if (LOGV) Slog.v(TAG, "writePolicyLocked()");

        FileOutputStream fos = null;
        try {
            fos = mPolicyFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);

            out.startTag(null, TAG_POLICY_LIST);
            writeIntAttribute(out, ATTR_VERSION, VERSION_CURRENT);

            // write all known network policies
            for (NetworkPolicy policy : mNetworkPolicy) {
                out.startTag(null, TAG_NETWORK_POLICY);
                writeIntAttribute(out, ATTR_NETWORK_TEMPLATE, policy.networkTemplate);
                if (policy.subscriberId != null) {
                    out.attribute(null, ATTR_SUBSCRIBER_ID, policy.subscriberId);
                }
                writeIntAttribute(out, ATTR_CYCLE_DAY, policy.cycleDay);
                writeLongAttribute(out, ATTR_WARNING_BYTES, policy.warningBytes);
                writeLongAttribute(out, ATTR_LIMIT_BYTES, policy.limitBytes);
                out.endTag(null, TAG_NETWORK_POLICY);
            }

            // write all known uid policies
            for (int i = 0; i < mUidPolicy.size(); i++) {
                final int uid = mUidPolicy.keyAt(i);
                final int policy = mUidPolicy.valueAt(i);

                out.startTag(null, TAG_UID_POLICY);
                writeIntAttribute(out, ATTR_UID, uid);
                writeIntAttribute(out, ATTR_POLICY, policy);
                out.endTag(null, TAG_UID_POLICY);
            }

            out.endTag(null, TAG_POLICY_LIST);
            out.endDocument();

            mPolicyFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mPolicyFile.failWrite(fos);
            }
        }
    }

    @Override
    public void setUidPolicy(int uid, int policy) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        final int oldPolicy;
        synchronized (mRulesLock) {
            oldPolicy = getUidPolicy(uid);
            mUidPolicy.put(uid, policy);

            // uid policy changed, recompute rules and persist policy.
            updateRulesForUidLocked(uid);
            writePolicyLocked();
        }
    }

    @Override
    public int getUidPolicy(int uid) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            return mUidPolicy.get(uid, POLICY_NONE);
        }
    }

    @Override
    public void registerListener(INetworkPolicyListener listener) {
        mListeners.register(listener);

        synchronized (mRulesLock) {
            // dispatch any existing rules to new listeners
            final int size = mUidRules.size();
            for (int i = 0; i < size; i++) {
                final int uid = mUidRules.keyAt(i);
                final int uidRules = mUidRules.valueAt(i);
                if (uidRules != RULE_ALLOW_ALL) {
                    try {
                        listener.onRulesChanged(uid, uidRules);
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    @Override
    public void unregisterListener(INetworkPolicyListener listener) {
        mListeners.unregister(listener);
    }

    @Override
    public void setNetworkPolicies(NetworkPolicy[] policies) {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);

        synchronized (mRulesLock) {
            mNetworkPolicy.clear();
            for (NetworkPolicy policy : policies) {
                mNetworkPolicy.add(policy);
            }

            updateIfacesLocked();
            writePolicyLocked();
        }
    }

    @Override
    public NetworkPolicy[] getNetworkPolicies() {
        mContext.enforceCallingOrSelfPermission(MANAGE_NETWORK_POLICY, TAG);
        mContext.enforceCallingOrSelfPermission(READ_PHONE_STATE, TAG);

        synchronized (mRulesLock) {
            return mNetworkPolicy.toArray(new NetworkPolicy[mNetworkPolicy.size()]);
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        mContext.enforceCallingOrSelfPermission(DUMP, TAG);

        synchronized (mRulesLock) {
            fout.println("Network policies:");
            for (NetworkPolicy policy : mNetworkPolicy) {
                fout.print("  "); fout.println(policy.toString());
            }

            fout.println("Policy status for known UIDs:");

            final SparseBooleanArray knownUids = new SparseBooleanArray();
            collectKeys(mUidPolicy, knownUids);
            collectKeys(mUidForeground, knownUids);
            collectKeys(mUidRules, knownUids);

            final int size = knownUids.size();
            for (int i = 0; i < size; i++) {
                final int uid = knownUids.keyAt(i);
                fout.print("  UID=");
                fout.print(uid);

                fout.print(" policy=");
                final int policyIndex = mUidPolicy.indexOfKey(uid);
                if (policyIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpPolicy(fout, mUidPolicy.valueAt(policyIndex));
                }

                fout.print(" foreground=");
                final int foregroundIndex = mUidPidForeground.indexOfKey(uid);
                if (foregroundIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpSparseBooleanArray(fout, mUidPidForeground.valueAt(foregroundIndex));
                }

                fout.print(" rules=");
                final int rulesIndex = mUidRules.indexOfKey(uid);
                if (rulesIndex < 0) {
                    fout.print("UNKNOWN");
                } else {
                    dumpRules(fout, mUidRules.valueAt(rulesIndex));
                }

                fout.println();
            }
        }
    }

    @Override
    public boolean isUidForeground(int uid) {
        synchronized (mRulesLock) {
            // only really in foreground when screen is also on
            return mUidForeground.get(uid, false) && mScreenOn;
        }
    }

    /**
     * Foreground for PID changed; recompute foreground at UID level. If
     * changed, will trigger {@link #updateRulesForUidLocked(int)}.
     */
    private void computeUidForegroundLocked(int uid) {
        final SparseBooleanArray pidForeground = mUidPidForeground.get(uid);

        // current pid is dropping foreground; examine other pids
        boolean uidForeground = false;
        final int size = pidForeground.size();
        for (int i = 0; i < size; i++) {
            if (pidForeground.valueAt(i)) {
                uidForeground = true;
                break;
            }
        }

        final boolean oldUidForeground = mUidForeground.get(uid, false);
        if (oldUidForeground != uidForeground) {
            // foreground changed, push updated rules
            mUidForeground.put(uid, uidForeground);
            updateRulesForUidLocked(uid);
        }
    }

    private void updateScreenOn() {
        synchronized (mRulesLock) {
            try {
                mScreenOn = mPowerManager.isScreenOn();
            } catch (RemoteException e) {
            }
            updateRulesForScreenLocked();
        }
    }

    /**
     * Update rules that might be changed by {@link #mScreenOn} value.
     */
    private void updateRulesForScreenLocked() {
        // only update rules for anyone with foreground activities
        final int size = mUidForeground.size();
        for (int i = 0; i < size; i++) {
            if (mUidForeground.valueAt(i)) {
                final int uid = mUidForeground.keyAt(i);
                updateRulesForUidLocked(uid);
            }
        }
    }

    private void updateRulesForUidLocked(int uid) {
        final int uidPolicy = getUidPolicy(uid);
        final boolean uidForeground = isUidForeground(uid);

        // derive active rules based on policy and active state
        int uidRules = RULE_ALLOW_ALL;
        if (!uidForeground && (uidPolicy & POLICY_REJECT_PAID_BACKGROUND) != 0) {
            // uid in background, and policy says to block paid data
            uidRules = RULE_REJECT_PAID;
        }

        // TODO: only dispatch when rules actually change

        // record rule locally to dispatch to new listeners
        mUidRules.put(uid, uidRules);

        // dispatch changed rule to existing listeners
        final int length = mListeners.beginBroadcast();
        for (int i = 0; i < length; i++) {
            final INetworkPolicyListener listener = mListeners.getBroadcastItem(i);
            if (listener != null) {
                try {
                    listener.onRulesChanged(uid, uidRules);
                } catch (RemoteException e) {
                }
            }
        }
        mListeners.finishBroadcast();
    }

    private String getActiveSubscriberId() {
        final TelephonyManager telephony = (TelephonyManager) mContext.getSystemService(
                Context.TELEPHONY_SERVICE);
        return telephony.getSubscriberId();
    }

    private static void collectKeys(SparseIntArray source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    private static void collectKeys(SparseBooleanArray source, SparseBooleanArray target) {
        final int size = source.size();
        for (int i = 0; i < size; i++) {
            target.put(source.keyAt(i), true);
        }
    }

    private static void dumpSparseBooleanArray(PrintWriter fout, SparseBooleanArray value) {
        fout.print("[");
        final int size = value.size();
        for (int i = 0; i < size; i++) {
            fout.print(value.keyAt(i) + "=" + value.valueAt(i));
            if (i < size - 1) fout.print(",");
        }
        fout.print("]");
    }

    private static int readIntAttribute(XmlPullParser in, String name) throws IOException {
        final String value = in.getAttributeValue(null, name);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing " + name + "=" + value + " as int");
        }
    }

    private static long readLongAttribute(XmlPullParser in, String name) throws IOException {
        final String value = in.getAttributeValue(null, name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ProtocolException("problem parsing " + name + "=" + value + " as int");
        }
    }

    private static void writeIntAttribute(XmlSerializer out, String name, int value)
            throws IOException {
        out.attribute(null, name, Integer.toString(value));
    }

    private static void writeLongAttribute(XmlSerializer out, String name, long value)
            throws IOException {
        out.attribute(null, name, Long.toString(value));
    }

}
