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

import static android.net.ConnectivityManager.BLOCKED_REASON_NONE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_STANDBY;
import static android.net.INetd.FIREWALL_RULE_ALLOW;
import static android.net.INetd.FIREWALL_RULE_DENY;
import static android.net.NetworkPolicyManager.ALLOWED_REASON_NONE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_DOZABLE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_LOW_POWER_STANDBY;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_POWERSAVE;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_RESTRICTED;
import static android.net.NetworkPolicyManager.FIREWALL_CHAIN_NAME_STANDBY;
import static android.net.NetworkPolicyManager.FIREWALL_RULE_DEFAULT;
import static android.os.PowerExemptionManager.reasonCodeToString;
import static android.os.Process.INVALID_UID;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessCapability;
import android.net.NetworkPolicyManager;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.RingBuffer;
import com.android.server.am.ProcessList;
import com.android.server.net.NetworkPolicyManagerService.UidBlockedState;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;

public class NetworkPolicyLogger {
    static final String TAG = "NetworkPolicy";

    static final boolean LOGD = Log.isLoggable(TAG, Log.DEBUG);
    static final boolean LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    private static final int MAX_LOG_SIZE =
            ActivityManager.isLowRamDeviceStatic() ? 100 : 400;
    private static final int MAX_NETWORK_BLOCKED_LOG_SIZE =
            ActivityManager.isLowRamDeviceStatic() ? 100 : 400;

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
    private static final int EVENT_APP_IDLE_WL_CHANGED = 14;
    private static final int EVENT_METERED_ALLOWLIST_CHANGED = 15;
    private static final int EVENT_METERED_DENYLIST_CHANGED = 16;
    private static final int EVENT_ROAMING_CHANGED = 17;
    private static final int EVENT_INTERFACES_CHANGED = 18;

    private final LogBuffer mNetworkBlockedBuffer = new LogBuffer(MAX_NETWORK_BLOCKED_LOG_SIZE);
    private final LogBuffer mUidStateChangeBuffer = new LogBuffer(MAX_LOG_SIZE);
    private final LogBuffer mEventsBuffer = new LogBuffer(MAX_LOG_SIZE);

    private int mDebugUid = INVALID_UID;

    private final Object mLock = new Object();

    void networkBlocked(int uid, @Nullable UidBlockedState uidBlockedState) {
        synchronized (mLock) {
            if (LOGD || uid == mDebugUid) {
                Slog.d(TAG, "Blocked state of " + uid + ": " + uidBlockedState);
            }
            if (uidBlockedState == null) {
                mNetworkBlockedBuffer.networkBlocked(uid, BLOCKED_REASON_NONE, ALLOWED_REASON_NONE,
                        BLOCKED_REASON_NONE);
            } else {
                mNetworkBlockedBuffer.networkBlocked(uid, uidBlockedState.blockedReasons,
                        uidBlockedState.allowedReasons, uidBlockedState.effectiveBlockedReasons);
            }
        }
    }

    void uidStateChanged(int uid, int procState, long procStateSeq,
            @ProcessCapability int capability) {
        synchronized (mLock) {
            if (LOGV || uid == mDebugUid) {
                Slog.v(TAG, uid + " state changed to "
                        + ProcessList.makeProcStateString(procState) + ",seq=" + procStateSeq
                        + ",cap=" + ActivityManager.getCapabilitiesSummary(capability));
            }
            mUidStateChangeBuffer.uidStateChanged(uid, procState, procStateSeq, capability);
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
            if (LOGV || uid == mDebugUid) {
                Slog.v(TAG,
                        getPolicyChangedLog(uid, oldPolicy, newPolicy));
            }
            mEventsBuffer.uidPolicyChanged(uid, oldPolicy, newPolicy);
        }
    }

    void meterednessChanged(int netId, boolean newMetered) {
        synchronized (mLock) {
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG,
                        getMeterednessChangedLog(netId, newMetered));
            }
            mEventsBuffer.meterednessChanged(netId, newMetered);
        }
    }

    void removingUserState(int userId) {
        synchronized (mLock) {
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG, getUserRemovedLog(userId));
            }
            mEventsBuffer.userRemoved(userId);
        }
    }

    void restrictBackgroundChanged(boolean oldValue, boolean newValue) {
        synchronized (mLock) {
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG,
                        getRestrictBackgroundChangedLog(oldValue, newValue));
            }
            mEventsBuffer.restrictBackgroundChanged(oldValue, newValue);
        }
    }

    void deviceIdleModeEnabled(boolean enabled) {
        synchronized (mLock) {
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG, getDeviceIdleModeEnabled(enabled));
            }
            mEventsBuffer.deviceIdleModeEnabled(enabled);
        }
    }

    void appIdleStateChanged(int uid, boolean idle) {
        synchronized (mLock) {
            if (LOGD || uid == mDebugUid) {
                Slog.d(TAG, getAppIdleChangedLog(uid, idle));
            }
            mEventsBuffer.appIdleStateChanged(uid, idle);
        }
    }

    void appIdleWlChanged(int uid, boolean isWhitelisted) {
        synchronized (mLock) {
            if (LOGD || uid == mDebugUid) {
                Slog.d(TAG, getAppIdleWlChangedLog(uid, isWhitelisted));
            }
            mEventsBuffer.appIdleWlChanged(uid, isWhitelisted);
        }
    }

    void paroleStateChanged(boolean paroleOn) {
        synchronized (mLock) {
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG, getParoleStateChanged(paroleOn));
            }
            mEventsBuffer.paroleStateChanged(paroleOn);
        }
    }

    void tempPowerSaveWlChanged(int appId, boolean added, int reasonCode, String reason) {
        synchronized (mLock) {
            if (LOGV || appId == UserHandle.getAppId(mDebugUid)) {
                Slog.v(TAG, getTempPowerSaveWlChangedLog(appId, added, reasonCode, reason));
            }
            mEventsBuffer.tempPowerSaveWlChanged(appId, added, reasonCode, reason);
        }
    }

    void uidFirewallRuleChanged(int chain, int uid, int rule) {
        synchronized (mLock) {
            if (LOGV || uid == mDebugUid) {
                Slog.v(TAG,
                        getUidFirewallRuleChangedLog(chain, uid, rule));
            }
            mEventsBuffer.uidFirewallRuleChanged(chain, uid, rule);
        }
    }

    void firewallChainEnabled(int chain, boolean enabled) {
        synchronized (mLock) {
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG,
                        getFirewallChainEnabledLog(chain, enabled));
            }
            mEventsBuffer.firewallChainEnabled(chain, enabled);
        }
    }

    void firewallRulesChanged(int chain, int[] uids, int[] rules) {
        synchronized (mLock) {
            final String log = "Firewall rules changed for " + getFirewallChainName(chain)
                    + "; uids=" + Arrays.toString(uids) + "; rules=" + Arrays.toString(rules);
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG, log);
            }
            mEventsBuffer.event(log);
        }
    }

    void meteredRestrictedPkgsChanged(Set<Integer> restrictedUids) {
        synchronized (mLock) {
            final String log = "Metered restricted uids: " + restrictedUids;
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG, log);
            }
            mEventsBuffer.event(log);
        }
    }

    void meteredAllowlistChanged(int uid, boolean added) {
        synchronized (mLock) {
            if (LOGD || mDebugUid == uid) {
                Slog.d(TAG, getMeteredAllowlistChangedLog(uid, added));
            }
            mEventsBuffer.meteredAllowlistChanged(uid, added);
        }
    }

    void meteredDenylistChanged(int uid, boolean added) {
        synchronized (mLock) {
            if (LOGD || mDebugUid == uid) {
                Slog.d(TAG, getMeteredDenylistChangedLog(uid, added));
            }
            mEventsBuffer.meteredDenylistChanged(uid, added);
        }
    }

    void roamingChanged(int netId, boolean newRoaming) {
        synchronized (mLock) {
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG, getRoamingChangedLog(netId, newRoaming));
            }
            mEventsBuffer.roamingChanged(netId, newRoaming);
        }
    }

    void interfacesChanged(int netId, ArraySet<String> newIfaces) {
        synchronized (mLock) {
            if (LOGD || mDebugUid != INVALID_UID) {
                Slog.d(TAG, getInterfacesChangedLog(netId, newIfaces.toString()));
            }
            mEventsBuffer.interfacesChanged(netId, newIfaces.toString());
        }
    }

    void setDebugUid(int uid) {
        mDebugUid = uid;
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

    private static String getAppIdleWlChangedLog(int uid, boolean isAllowlisted) {
        return "App idle whitelist state of uid " + uid + ": " + isAllowlisted;
    }

    private static String getParoleStateChanged(boolean paroleOn) {
        return "Parole state: " + paroleOn;
    }

    private static String getTempPowerSaveWlChangedLog(int appId, boolean added,
            int reasonCode, String reason) {
        return "temp-power-save whitelist for " + appId + " changed to: " + added
                + "; reason=" + reasonCodeToString(reasonCode) + " <" + reason + ">";
    }

    private static String getUidFirewallRuleChangedLog(int chain, int uid, int rule) {
        return String.format("Firewall rule changed: %d-%s-%s",
                uid, getFirewallChainName(chain), getFirewallRuleName(rule));
    }

    private static String getFirewallChainEnabledLog(int chain, boolean enabled) {
        return "Firewall chain " + getFirewallChainName(chain) + " state: " + enabled;
    }

    private static String getMeteredAllowlistChangedLog(int uid, boolean added) {
        return "metered-allowlist for " + uid + " changed to " + added;
    }

    private static String getMeteredDenylistChangedLog(int uid, boolean added) {
        return "metered-denylist for " + uid + " changed to " + added;
    }

    private static String getRoamingChangedLog(int netId, boolean newRoaming) {
        return "Roaming of netId=" + netId + " changed to " + newRoaming;
    }

    private static String getInterfacesChangedLog(int netId, String newIfaces) {
        return "Interfaces of netId=" + netId + " changed to " + newIfaces;
    }

    private static String getFirewallChainName(int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_DOZABLE:
                return FIREWALL_CHAIN_NAME_DOZABLE;
            case FIREWALL_CHAIN_STANDBY:
                return FIREWALL_CHAIN_NAME_STANDBY;
            case FIREWALL_CHAIN_POWERSAVE:
                return FIREWALL_CHAIN_NAME_POWERSAVE;
            case FIREWALL_CHAIN_RESTRICTED:
                return FIREWALL_CHAIN_NAME_RESTRICTED;
            case FIREWALL_CHAIN_LOW_POWER_STANDBY:
                return FIREWALL_CHAIN_NAME_LOW_POWER_STANDBY;
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

        public void uidStateChanged(int uid, int procState, long procStateSeq,
                @ProcessCapability int capability) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_UID_STATE_CHANGED;
            data.ifield1 = uid;
            data.ifield2 = procState;
            data.ifield3 = capability;
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

        public void networkBlocked(int uid, int blockedReasons, int allowedReasons,
                int effectiveBlockedReasons) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_NETWORK_BLOCKED;
            data.ifield1 = uid;
            data.ifield2 = blockedReasons;
            data.ifield3 = allowedReasons;
            data.ifield4 = effectiveBlockedReasons;
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

        public void appIdleWlChanged(int uid, boolean isAllowlisted) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_APP_IDLE_WL_CHANGED;
            data.ifield1 = uid;
            data.bfield1 = isAllowlisted;
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

        public void tempPowerSaveWlChanged(int appId, boolean added,
                int reasonCode, String reason) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_TEMP_POWER_SAVE_WL_CHANGED;
            data.ifield1 = appId;
            data.ifield2 = reasonCode;
            data.bfield1 = added;
            data.sfield1 = reason;
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

        public void meteredAllowlistChanged(int uid, boolean added) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_METERED_ALLOWLIST_CHANGED;
            data.ifield1 = uid;
            data.bfield1 = added;
            data.timeStamp = System.currentTimeMillis();
        }

        public void meteredDenylistChanged(int uid, boolean added) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_METERED_DENYLIST_CHANGED;
            data.ifield1 = uid;
            data.bfield1 = added;
            data.timeStamp = System.currentTimeMillis();
        }

        public void roamingChanged(int netId, boolean newRoaming) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_ROAMING_CHANGED;
            data.ifield1 = netId;
            data.bfield1 = newRoaming;
            data.timeStamp = System.currentTimeMillis();
        }

        public void interfacesChanged(int netId, String newIfaces) {
            final Data data = getNextSlot();
            if (data == null) return;

            data.reset();
            data.type = EVENT_INTERFACES_CHANGED;
            data.ifield1 = netId;
            data.sfield1 = newIfaces;
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
                    return data.ifield1 + "-" + UidBlockedState.toString(
                            data.ifield2, data.ifield3, data.ifield4);
                case EVENT_UID_STATE_CHANGED:
                    return data.ifield1 + ":" + ProcessList.makeProcStateString(data.ifield2)
                            + ":" + ActivityManager.getCapabilitiesSummary(data.ifield3)
                            + ":" + data.lfield1;
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
                case EVENT_APP_IDLE_WL_CHANGED:
                    return getAppIdleWlChangedLog(data.ifield1, data.bfield1);
                case EVENT_PAROLE_STATE_CHANGED:
                    return getParoleStateChanged(data.bfield1);
                case EVENT_TEMP_POWER_SAVE_WL_CHANGED:
                    return getTempPowerSaveWlChangedLog(data.ifield1, data.bfield1,
                            data.ifield2, data.sfield1);
                case EVENT_UID_FIREWALL_RULE_CHANGED:
                    return getUidFirewallRuleChangedLog(data.ifield1, data.ifield2, data.ifield3);
                case EVENT_FIREWALL_CHAIN_ENABLED:
                    return getFirewallChainEnabledLog(data.ifield1, data.bfield1);
                case EVENT_METERED_ALLOWLIST_CHANGED:
                    return getMeteredAllowlistChangedLog(data.ifield1, data.bfield1);
                case EVENT_METERED_DENYLIST_CHANGED:
                    return getMeteredDenylistChangedLog(data.ifield1, data.bfield1);
                case EVENT_ROAMING_CHANGED:
                    return getRoamingChangedLog(data.ifield1, data.bfield1);
                case EVENT_INTERFACES_CHANGED:
                    return getInterfacesChangedLog(data.ifield1, data.sfield1);
                default:
                    return String.valueOf(data.type);
            }
        }

        private String formatDate(long millis) {
            sDate.setTime(millis);
            return sFormatter.format(sDate);
        }
    }

    /**
     * Container class for all networkpolicy events data.
     *
     * Note: This class needs to be public for RingBuffer class to be able to create
     * new instances of this.
     */
    public static final class Data {
        public int type;
        public long timeStamp;

        public int ifield1;
        public int ifield2;
        public int ifield3;
        public int ifield4;
        public long lfield1;
        public boolean bfield1;
        public boolean bfield2;
        public String sfield1;

        public void reset(){
            sfield1 = null;
        }
    }
}
