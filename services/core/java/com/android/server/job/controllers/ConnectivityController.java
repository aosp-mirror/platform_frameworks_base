/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.job.controllers;

import android.app.job.JobInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.INetworkPolicyListener;
import android.net.NetworkInfo;
import android.net.NetworkPolicyManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.StateChangedListener;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Handles changes in connectivity.
 * <p>
 * Each app can have a different default networks or different connectivity
 * status due to user-requested network policies, so we need to check
 * constraints on a per-UID basis.
 */
public class ConnectivityController extends StateController implements
        ConnectivityManager.OnNetworkActiveListener {
    private static final String TAG = "JobScheduler.Conn";

    private final ConnectivityManager mConnManager;
    private final NetworkPolicyManager mNetPolicyManager;

    @GuardedBy("mLock")
    private final ArrayList<JobStatus> mTrackedJobs = new ArrayList<JobStatus>();

    /** Singleton. */
    private static ConnectivityController mSingleton;
    private static Object sCreationLock = new Object();

    public static ConnectivityController get(JobSchedulerService jms) {
        synchronized (sCreationLock) {
            if (mSingleton == null) {
                mSingleton = new ConnectivityController(jms, jms.getContext(), jms.getLock());
            }
            return mSingleton;
        }
    }

    private ConnectivityController(StateChangedListener stateChangedListener, Context context,
            Object lock) {
        super(stateChangedListener, context, lock);

        mConnManager = mContext.getSystemService(ConnectivityManager.class);
        mNetPolicyManager = mContext.getSystemService(NetworkPolicyManager.class);

        final IntentFilter intentFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiverAsUser(
                mConnectivityReceiver, UserHandle.SYSTEM, intentFilter, null, null);

        mNetPolicyManager.registerListener(mNetPolicyListener);
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (jobStatus.hasConnectivityConstraint() || jobStatus.hasUnmeteredConstraint()
                || jobStatus.hasNotRoamingConstraint()) {
            updateConstraintsSatisfied(jobStatus);
            mTrackedJobs.add(jobStatus);
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (jobStatus.hasConnectivityConstraint() || jobStatus.hasUnmeteredConstraint()
                || jobStatus.hasNotRoamingConstraint()) {
            mTrackedJobs.remove(jobStatus);
        }
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus) {
        final boolean ignoreBlocked = (jobStatus.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0;
        final NetworkInfo info = mConnManager.getActiveNetworkInfoForUid(jobStatus.getSourceUid(),
                ignoreBlocked);
        final boolean connected = (info != null) && info.isConnected();
        final boolean unmetered = connected && !info.isMetered();
        final boolean notRoaming = connected && !info.isRoaming();

        boolean changed = false;
        changed |= jobStatus.setConnectivityConstraintSatisfied(connected);
        changed |= jobStatus.setUnmeteredConstraintSatisfied(unmetered);
        changed |= jobStatus.setNotRoamingConstraintSatisfied(notRoaming);
        return changed;
    }

    /**
     * Update all jobs tracked by this controller.
     *
     * @param uid only update jobs belonging to this UID, or {@code -1} to
     *            update all tracked jobs.
     */
    private void updateTrackedJobs(int uid) {
        synchronized (mLock) {
            boolean changed = false;
            for (int i = 0; i < mTrackedJobs.size(); i++) {
                final JobStatus js = mTrackedJobs.get(i);
                if (uid == -1 || uid == js.getSourceUid()) {
                    changed |= updateConstraintsSatisfied(js);
                }
            }
            if (changed) {
                mStateChangedListener.onControllerStateChanged();
            }
        }
    }

    /**
     * We know the network has just come up. We want to run any jobs that are ready.
     */
    @Override
    public synchronized void onNetworkActive() {
        synchronized (mLock) {
            for (int i = 0; i < mTrackedJobs.size(); i++) {
                final JobStatus js = mTrackedJobs.get(i);
                if (js.isReady()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Running " + js + " due to network activity.");
                    }
                    mStateChangedListener.onRunJobNow(js);
                }
            }
        }
    }

    private BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateTrackedJobs(-1);
        }
    };

    private INetworkPolicyListener mNetPolicyListener = new INetworkPolicyListener.Stub() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            updateTrackedJobs(uid);
        }

        @Override
        public void onMeteredIfacesChanged(String[] meteredIfaces) {
            updateTrackedJobs(-1);
        }

        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            updateTrackedJobs(-1);
        }

        @Override
        public void onRestrictBackgroundWhitelistChanged(int uid, boolean whitelisted) {
            updateTrackedJobs(uid);
        }

        @Override
        public void onRestrictBackgroundBlacklistChanged(int uid, boolean blacklisted) {
            updateTrackedJobs(uid);
        }
    };

    @Override
    public void dumpControllerStateLocked(PrintWriter pw, int filterUid) {
        pw.println("Connectivity.");
        pw.print("Tracking ");
        pw.print(mTrackedJobs.size());
        pw.println(":");
        for (int i = 0; i < mTrackedJobs.size(); i++) {
            final JobStatus js = mTrackedJobs.get(i);
            if (js.shouldDump(filterUid)) {
                pw.print("  #");
                js.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, js.getSourceUid());
                pw.print(": C="); pw.print(js.hasConnectivityConstraint());
                pw.print(": UM="); pw.print(js.hasUnmeteredConstraint());
                pw.print(": NR="); pw.println(js.hasNotRoamingConstraint());
            }
        }
    }
}
