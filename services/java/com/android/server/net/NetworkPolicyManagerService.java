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

import static android.Manifest.permission.MANAGE_APP_TOKENS;
import static android.Manifest.permission.UPDATE_DEVICE_STATS;
import static android.net.NetworkPolicyManager.POLICY_NONE;
import static android.net.NetworkPolicyManager.POLICY_REJECT_PAID;
import static android.net.NetworkPolicyManager.POLICY_REJECT_BACKGROUND;

import android.content.Context;
import android.net.INetworkPolicyManager;
import android.os.ServiceManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

/**
 * Service that maintains low-level network policy rules and collects usage
 * statistics to drive those rules.
 */
public class NetworkPolicyManagerService extends INetworkPolicyManager.Stub {
    private static final String TAG = "NetworkPolicy";
    private static final boolean LOGD = true;

    private Context mContext;

    /** Current network policy for each UID. */
    private SparseIntArray mUidPolicy;

    /** Foreground at both UID and PID granularity. */
    private SparseBooleanArray mUidForeground;
    private SparseArray<SparseBooleanArray> mUidPidForeground;

    // TODO: periodically poll network stats and write to disk
    // TODO: save/restore policy information from disk

    // TODO: watch screen on/off broadcasts to track foreground

    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(Context.NETWORK_POLICY_SERVICE, asBinder());

        mUidPolicy = new SparseIntArray();
        mUidForeground = new SparseBooleanArray();
        mUidPidForeground = new SparseArray<SparseBooleanArray>();

        // TODO: register for NetworkManagementService callbacks
        // TODO: read current policy+stats from disk and generate NMS rules
    }

    public void shutdown() {
        // TODO: persist any pending stats during clean shutdown

        mUidPolicy = null;
        mUidForeground = null;
        mUidPidForeground = null;
    }

    @Override
    public void onForegroundActivitiesChanged(int uid, int pid, boolean foreground) {
        // only someone like AMS should only be calling us
        mContext.enforceCallingOrSelfPermission(
                MANAGE_APP_TOKENS, "requires MANAGE_APP_TOKENS permission");

        // because a uid can have multiple pids running inside, we need to
        // remember all pid states and summarize foreground at uid level.

        // record foreground for this specific pid
        SparseBooleanArray pidForeground = mUidPidForeground.get(uid);
        if (pidForeground == null) {
            pidForeground = new SparseBooleanArray(2);
            mUidPidForeground.put(uid, pidForeground);
        }
        pidForeground.put(pid, foreground);
        computeUidForeground(uid);
    }

    @Override
    public void onProcessDied(int uid, int pid) {
        // only someone like AMS should only be calling us
        mContext.enforceCallingOrSelfPermission(
                MANAGE_APP_TOKENS, "requires MANAGE_APP_TOKENS permission");

        // clear records and recompute, when they exist
        final SparseBooleanArray pidForeground = mUidPidForeground.get(uid);
        if (pidForeground != null) {
            pidForeground.delete(pid);
            computeUidForeground(uid);
        }
    }

    @Override
    public void setUidPolicy(int uid, int policy) {
        mContext.enforceCallingOrSelfPermission(
                UPDATE_DEVICE_STATS, "requires UPDATE_DEVICE_STATS permission");
        mUidPolicy.put(uid, policy);
    }

    @Override
    public int getUidPolicy(int uid) {
        return mUidPolicy.get(uid, POLICY_NONE);
    }

    /**
     * Foreground for PID changed; recompute foreground at UID level. If
     * changed, will trigger {@link #updateRulesForUid(int)}.
     */
    private void computeUidForeground(int uid) {
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
            updateRulesForUid(uid);
        }
    }

    private void updateRulesForUid(int uid) {
        final boolean uidForeground = mUidForeground.get(uid, false);
        final int uidPolicy = getUidPolicy(uid);

        if (LOGD) {
            Log.d(TAG, "updateRulesForUid(uid=" + uid + ") found foreground=" + uidForeground
                    + " and policy=" + uidPolicy);
        }

        if (!uidForeground && (uidPolicy & POLICY_REJECT_BACKGROUND) != 0) {
            // TODO: build updated rules and push to NMS
        } else if ((uidPolicy & POLICY_REJECT_PAID) != 0) {
            // TODO: build updated rules and push to NMS
        } else {
            // TODO: build updated rules and push to NMS
        }
    }

}
