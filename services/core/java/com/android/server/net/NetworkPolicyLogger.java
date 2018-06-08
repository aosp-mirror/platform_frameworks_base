/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_DOZABLE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_POWERSAVE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_STANDBY;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_STANDBY;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_ALLOW;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DEFAULT;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DENY;

import android.app.ActivityManager;
import android.net.NetworkPolicyManager;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.RingBuffer;
import com.android.server.am.ProcessList;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;

public class NetworkPolicyLogger {
    static final String TAG = "NetworkPolicy";

    static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);
    static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    private static final int MAX_LOG_SIZE =
            ActivityManager.isLowRamDeviceStatic() ? 20 : 50;
    private static final int MAX_NETWORK_BLOCKED_LOG_SIZE =
            ActivityManager.isLowRamDeviceStatic() ? 50 : 100;

    private static final int EVENT_TYPE_GENERIC = 0;
    private static final int EVENT_NETWORK_BLOCKED = 1;
    private static final int EVENT_UID_STATE_CHANGED = 2;
    private static final int EVENT_POLICIES_CHANGED = 3;
    private static final int EVENT_METEREDNESS_CHANGED = 4;
    private static final int EVENT_USER_STATE_REMOVED = 5;
    private static final int EVENT_RESTRICT_BG_CHANGED = 6;
    private static final int EVENT_DEVICE_IDLE_MODE_ENABLED = 7;
    private static final int EVENT_APP_IDLE_STATE_CHANGED = 8;
    private static final int EVENT_PAROLE_STATE_CHANGED = 9;
    private static final int EVENT_TEMP_POWER_SAVE_WL_CHANGED = 10;
    private static final int EVENT_UID_FIREWALL_RULE_CHANGED = 11;
    private static final int EVENT_FIREWALL_CHAIN_ENABLED = 12;
    private static final int EVENT_UPDATE_METERED_RESTRICTED_PKGS = 13;

    static final int NTWK_BLOCKED_POWER = 0;
    static final int NTWK_ALLOWED_NON_METERED = 1;
    static final int NTWK_BLOCKED_BLACKLIST = 2;
    static final int NTWK_ALLOWED_WHITELIST = 3;
    static final int NTWK_ALLOWED_TMP_WHITELIST = 4;
    static final int NTWK_BLOCKED_BG_RESTRICT = 5;
    static final int NTWK_ALLOWED_DEFAULT = 6;

    private final LogBuffer mNetworkBlockedBuffer = new LogBuffer(MAX_NETWORK_BLOCKED_LOG_SIZE);
    private final LogBuffer mUidStateChangeBuffer = new LogBuffer(MAX_LOG_SIZE);
    private final LogBuffer mEventsBuffer = new LogBuffer(MAX_LOG_SIZE);

    private final Object mLock = new Object();

    void networkBlocked(int uid, int reason) {
        synchronized (mLock) {
            if (LOGD) Slog.d(TAG, uid + " is " + getBlockedReason(reason));
            mNetworkBlockedBuffer.networkBlocked(uid, reason);
        }
    }

    void uidStateChanged(int uid, int procState, long procStateSeq) {
        synchronized (mLock) {
            if (LOGV) Slog.v(TAG,
                    uid + " state changed to " + procState + " with seq=" + procStateSeq);
            mUidStateChangeBuffer.uidStateChanged(uid, procState, procStateSeq);
        }
    }

    void event(String msg) {
        synchronized (mLock) {
            if (LOGV) Slog.v(TAG, msg);
            mEventsBuffer.event(msg);
        }
    }

    void uidPolicyChanged(int uid, int oldPolicy, int newPolicy) {
        synchronized (mLock) {
            if (LOGV) Slog.v(TAG, getPolicyChangedLog(uid, oldPolicy, newPolicy));
            mEventsBuffer.uidPolicyChanged(uid, oldPolicy, newPolicy);
        }
    }

    void meterednessChanged(int netId, boolean newMetered) {
        synchronized (mLock) {
            if (LOGD) Slog.d(TAG, getMeterednessChangedLog(netId, newMetered));
            mEventsBuffer.meterednessChanged(netId, newMetered);
        }
    }

    void removingUserState(int userId) {
        synchronized (mLock) {
            if (LOGD) Slog.d(TAG, getUserRemovedLog(userId));
            mEventsBuffer.userRemoved(userId);
        }
    }

    void restrictBackgroundChanged(boolean oldValue, boolean newValue) {
        synchronized (mLock) {
            if (LOGD) Slog.d(TAG,
                    getRestrictBackgroundChangedLog(oldValue, newValue));
            mEventsBuffer.restrictBackgroundChanged(oldValue, newValue);
        }
    }

    void deviceIdleModeEnabled(boolean enabled) {
        synchronized (mLock) {
            if (LOGD) Slog.d(TAG, getDeviceIdleModeEnabled(enabled));
            mEventsBuffer.deviceIdleModeEnabled(enabled);
        }
    }

    void appIdleStateChanged(int uid, boolean idle) {
        synchronized (mLock) {
            if (LOGD) Slog.d(TAG, getAppIdleChangedLog(uid, idle));
            mEventsBuffer.appIdleStateChanged(uid, idle);
        }
    }

    void paroleStateChanged(boolean paroleOn) {
        synchronized (mLock) {
            if (LOGD) Slog.d(TAG, getParoleStateChanged(paroleOn));
            mEventsBuffer.paroleStateChanged(paroleOn);
        }
    }

    void tempPowerSaveWlChanged(int appId, boolean added) {
        synchronized (mLock) {
            if (LOGV) Slog.v(TAG, getTempPowerSaveWlChangedLog(appId, added));
            mEventsBuffer.tempPowerSaveWlChanged(appId, added);
        }
    }

    void uidFirewallRuleChanged(int chain, int uid, int rule) {
        synchronized (mLock) {
            if (LOGV) Slog.v(TAG, getUidFirewallRuleChangedLog(chain, uid, rule));
            mEventsBuffer.uidFirewallRuleChanged(chain, uid, rule);
        }
    }

    void firewallChainEnabled(int chain, boolean enabled) {
        synchronized (mLock) {
            if (LOGD) Slog.d(TAG, getFirewallChainEnabledLog(chain, enabled));
            mEventsBuffer.firewallChainEnabled(chain, enabled);
        }
    }

    void firewallRulesChanged(int chain, int[] uids, int[] rules) {
        synchronized (mLock) {
            final String log = "Firewall rules changed for " + getFirewallChainName(chain)
                    + "; uids=" + Arrays.toString(uids) + "; rules=" + Arrays.toString(rules);
            if (LOGD) Slog.d(TAG, log);
            mEventsBuffer.event(log);
        }
    }

    void meteredRestrictedPkgsChanged(Set<Integer> restrictedUids) {
        synchronized (mLock) {
            final String log = "Metered restricted uids: " + restrictedUids;
            if (LOGD) Slog.d(TAG, log);
            mEventsBuffer.event(log);
        }
    }

    void dumpLogs(IndentingPrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("mEventLogs (most recent first):");
            pw.increaseIndent();
            mEventsBuffer.reverseDump(pw);
            pw.decreaseIndent();

            pw.println();
            pw.println("mNetworkBlockedLogs (most recent first):");
            pw.increaseIndent();
            mNetworkBlockedBuffer.reverseDump(pw);
            pw.decreaseIndent();

            pw.println();
            pw.println("mUidStateChangeLogs (most recent first):");
            pw.increaseIndent();
            mUidStateChangeBuffer.reverseDump(pw);
            pw.decreaseIndent();
        }
    }

    private static String getBlockedReason(int reason) {
        switch (reason) {
            case NTWK_BLOCKED_POWER:
                return "blocked by power restrictions";
            case NTWK_ALLOWED_NON_METERED:
                return "allowed on unmetered network";
            case NTWK_BLOCKED_BLACKLIST:
                return "blacklisted on metered network";
            case NTWK_ALLOWED_WHITELIST:
                return "whitelisted on metered network";
            case NTWK_ALLOWED_TMP_WHITELIST:
                return "temporary whitelisted on metered network";
            case NTWK_BLOCKED_BG_RESTRICT:
                return "blocked when background is restricted";
            case NTWK_ALLOWED_DEFAULT:
                return "allowed by default";
            default:
                return String.valueOf(reason);
        }
    }

    private static String getPolicyChangedLog(int uid, int oldPolicy, int newPolicy) {
        return "Policy for " + uid + " changed from "
                + NetworkPolicyManager.uidPoliciesToString(oldPolicy) + " to "
                + NetworkPolicyManager.uidPoliciesToString(newPolicy);
    }

    private static String getMeterednessChangedLog(int netId, boolean newMetered) {
        return "Meteredness of netId=" + netId + " changed to " + newMetered;
    }

    private static String getUserRemovedLog(int userId) {
        return "Remove state for u" + userId;
    }

    private static String getRestrictBackgroundChangedLog(boolean oldValue, boolean newValue) {
        return "Changed restrictBackground: " + oldValue + "->" + newValue;
    }

    private static String getDeviceIdleModeEnabled(boolean enabled) {
        return "DeviceIdleMode enabled: " + enabled;
    }

    private static String getAppIdleChangedLog(int uid, boolean idle) {
        return "App idle state of uid " + uid + ": " + idle;
    }

    private static String getParoleStateChanged(boolean paroleOn) {
        return "Parole state: " + paroleOn;
    }

    private static String getTempPowerSaveWlChangedLog(int appId, boolean added) {
        return "temp-power-save whitelist for " + appId + " changed to: " + added;
    }

    private static String getUidFirewallRuleChangedLog(int chain, int uid, int rule) {
        return String.format("Firewall rule changed: %d-%s-%s",
                uid, getFirewallChainName(chain), getFirewallRuleName(rule));
    }

    private static String getFirewallChainEnabledLog(int chain, boolean enabled) {
        return "Firewall chain " + getFirewallChainName(chain) + " state: " + enabled;
    }

    private static String getFirewallChainName(int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_DOZABLE:
                return FIREWALL_CHAIN_NAME_DOZABLE;
            case FIREWALL_CHAIN_STANDBY:
                return FIREWALL_CHAIN_NAME_STANDBY;
            case FIREWALL_CHAIN_POWERSAVE:
                return FIREWALL_CHAIN_NAME_POWERSAVE;
            default:
                return String.valueOf(chain);
        }
    }

    private static String getFirewallRuleName(int rule) {
        switch (rule) {
            case FIREWALL_RULE_DEFAULT:
                return "default";
            case FIREWALL_RULE_ALLOW:
                return "allow";
            case FIREWALL_RULE_DENY:
                return "deny";
            default:
                return String.valueOf(rule);
        }
    }

    private final static class LogBuffer extends RingBuffer<Data> {
        private static final SimpleDateFormat sFormatter
                = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss:SSS");
        private static final Date sDate = new Date();

        public LogBuffer(int capacity) {
            super(Data.class, capacity);
        }

        public void uidStateChanged(int uid, int procState, long procStateSeq) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_UID_STATE_CHANGED;
            data.ifield1 = uid;
            data.ifield2 = procState;
            data.lfield1 = procStateSeq;
            data.timeStamp = System.currentTimeMillis();
        }

        public void event(String msg) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_TYPE_GENERIC;
            data.sfield1 = msg;
            data.timeStamp = System.currentTimeMillis();
        }

        public void networkBlocked(int uid, int reason) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_NETWORK_BLOCKED;
            data.ifield1 = uid;
            data.ifield2 = reason;
            data.timeStamp = System.currentTimeMillis();
        }

        public void uidPolicyChanged(int uid, int oldPolicy, int newPolicy) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_POLICIES_CHANGED;
            data.ifield1 = uid;
            data.ifield2 = oldPolicy;
            data.ifield3 = newPolicy;
            data.timeStamp = System.currentTimeMillis();
        }

        public void meterednessChanged(int netId, boolean newMetered) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_METEREDNESS_CHANGED;
            data.ifield1 = netId;
            data.bfield1 = newMetered;
            data.timeStamp = System.currentTimeMillis();
        }

        public void userRemoved(int userId) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_USER_STATE_REMOVED;
            data.ifield1 = userId;
            data.timeStamp = System.currentTimeMillis();
        }

        public void restrictBackgroundChanged(boolean oldValue, boolean newValue) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_RESTRICT_BG_CHANGED;
            data.bfield1 = oldValue;
            data.bfield2 = newValue;
            data.timeStamp = System.currentTimeMillis();
        }

        public void deviceIdleModeEnabled(boolean enabled) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_DEVICE_IDLE_MODE_ENABLED;
            data.bfield1 = enabled;
            data.timeStamp = System.currentTimeMillis();
        }

        public void appIdleStateChanged(int uid, boolean idle) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_APP_IDLE_STATE_CHANGED;
            data.ifield1 = uid;
            data.bfield1 = idle;
            data.timeStamp = System.currentTimeMillis();
        }

        public void paroleStateChanged(boolean paroleOn) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_PAROLE_STATE_CHANGED;
            data.bfield1 = paroleOn;
            data.timeStamp = System.currentTimeMillis();
        }

        public void tempPowerSaveWlChanged(int appId, boolean added) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_TEMP_POWER_SAVE_WL_CHANGED;
            data.ifield1 = appId;
            data.bfield1 = added;
            data.timeStamp = System.currentTimeMillis();
        }

        public void uidFirewallRuleChanged(int chain, int uid, int rule) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_UID_FIREWALL_RULE_CHANGED;
            data.ifield1 = chain;
            data.ifield2 = uid;
            data.ifield3 = rule;
            data.timeStamp = System.currentTimeMillis();
        }

        public void firewallChainEnabled(int chain, boolean enabled) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_FIREWALL_CHAIN_ENABLED;
            data.ifield1 = chain;
            data.bfield1 = enabled;
            data.timeStamp = System.currentTimeMillis();
        }

        public void reverseDump(IndentingPrintWriter pw) {
            final Data[] allData = toArray();
            for (int i = allData.length - 1; i >= 0; --i) {
                if (allData[i] == null) {
                    pw.println("NULL");
                    continue;
                }
                pw.print(formatDate(allData[i].timeStamp));
                pw.print(" - ");
                pw.println(getContent(allData[i]));
            }
        }

        public String getContent(Data data) {
            switch (data.type) {
                case EVENT_TYPE_GENERIC:
                    return data.sfield1;
                case EVENT_NETWORK_BLOCKED:
                    return data.ifield1 + "-" + getBlockedReason(data.ifield2);
                case EVENT_UID_STATE_CHANGED:
                    return data.ifield1 + "-" + ProcessList.makeProcStateString(data.ifield2)
                            + "-" + data.lfield1;
                case EVENT_POLICIES_CHANGED:
                    return getPolicyChangedLog(data.ifield1, data.ifield2, data.ifield3);
                case EVENT_METEREDNESS_CHANGED:
                    return getMeterednessChangedLog(data.ifield1, data.bfield1);
                case EVENT_USER_STATE_REMOVED:
                    return getUserRemovedLog(data.ifield1);
                case EVENT_RESTRICT_BG_CHANGED:
                    return getRestrictBackgroundChangedLog(data.bfield1, data.bfield2);
                case EVENT_DEVICE_IDLE_MODE_ENABLED:
                    return getDeviceIdleModeEnabled(data.bfield1);
                case EVENT_APP_IDLE_STATE_CHANGED:
                    return getAppIdleChangedLog(data.ifield1, data.bfield1);
                case EVENT_PAROLE_STATE_CHANGED:
                    return getParoleStateChanged(data.bfield1);
                case EVENT_TEMP_POWER_SAVE_WL_CHANGED:
                    return getTempPowerSaveWlChangedLog(data.ifield1, data.bfield1);
                case EVENT_UID_FIREWALL_RULE_CHANGED:
                    return getUidFirewallRuleChangedLog(data.ifield1, data.ifield2, data.ifield3);
                case EVENT_FIREWALL_CHAIN_ENABLED:
                    return getFirewallChainEnabledLog(data.ifield1, data.bfield1);
                default:
                    return String.valueOf(data.type);
            }
        }

        private String formatDate(long millis) {
            sDate.setTime(millis);
            return sFormatter.format(sDate);
        }
    }

    public final static class Data {
        int type;
        long timeStamp;

        int ifield1;
        int ifield2;
        int ifield3;
        long lfield1;
        boolean bfield1;
        boolean bfield2;
        String sfield1;

        public void reset(){
            sfield1 = null;
        }
    }
}
