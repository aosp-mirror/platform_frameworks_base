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
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetworkPolicyListener;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkPolicyManager;
import android.net.TrafficStats;
import android.os.Process;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobServiceContext;
import com.android.server.job.StateChangedListener;

import java.io.PrintWriter;

/**
 * Handles changes in connectivity.
 * <p>
 * Each app can have a different default networks or different connectivity
 * status due to user-requested network policies, so we need to check
 * constraints on a per-UID basis.
 */
public final class ConnectivityController extends StateController implements
        ConnectivityManager.OnNetworkActiveListener {
    private static final String TAG = "JobScheduler.Conn";
    private static final boolean DEBUG = false;

    private final ConnectivityManager mConnManager;
    private final NetworkPolicyManager mNetPolicyManager;
    private boolean mConnected;
    private boolean mValidated;

    @GuardedBy("mLock")
    private final ArraySet<JobStatus> mTrackedJobs = new ArraySet<>();

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

        mConnected = mValidated = false;

        mConnManager.registerDefaultNetworkCallback(mNetworkCallback);
        mNetPolicyManager.registerListener(mNetPolicyListener);
    }

    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (jobStatus.hasConnectivityConstraint()) {
            updateConstraintsSatisfied(jobStatus);
            mTrackedJobs.add(jobStatus);
            jobStatus.setTrackingController(JobStatus.TRACKING_CONNECTIVITY);
        }
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (jobStatus.clearTrackingController(JobStatus.TRACKING_CONNECTIVITY)) {
            mTrackedJobs.remove(jobStatus);
        }
    }

    /**
     * Test to see if running the given job on the given network is sane.
     * <p>
     * For example, if a job is trying to send 10MB over a 128Kbps EDGE
     * connection, it would take 10.4 minutes, and has no chance of succeeding
     * before the job times out, so we'd be insane to try running it.
     */
    private boolean isSane(JobStatus jobStatus, NetworkCapabilities capabilities) {
        final long estimatedBytes = jobStatus.getEstimatedNetworkBytes();
        if (estimatedBytes == JobInfo.NETWORK_BYTES_UNKNOWN) {
            // We don't know how large the job is; cross our fingers!
            return true;
        }
        if (capabilities == null) {
            // We don't know what the network is like; cross our fingers!
            return true;
        }

        // We don't ask developers to differentiate between upstream/downstream
        // in their size estimates, so test against the slowest link direction.
        final long downstream = capabilities.getLinkDownstreamBandwidthKbps();
        final long upstream = capabilities.getLinkUpstreamBandwidthKbps();
        final long slowest;
        if (downstream > 0 && upstream > 0) {
            slowest = Math.min(downstream, upstream);
        } else if (downstream > 0) {
            slowest = downstream;
        } else if (upstream > 0) {
            slowest = upstream;
        } else {
            // We don't know what the network is like; cross our fingers!
            return true;
        }

        final long estimatedMillis = ((estimatedBytes * DateUtils.SECOND_IN_MILLIS)
                / (slowest * TrafficStats.KB_IN_BYTES / 8));
        if (estimatedMillis > JobServiceContext.EXECUTING_TIMESLICE_MILLIS) {
            // If we'd never finish before the timeout, we'd be insane!
            Slog.w(TAG, "Estimated " + estimatedBytes + " bytes over " + slowest
                    + " kbps network would take " + estimatedMillis + "ms; that's insane!");
            return false;
        } else {
            return true;
        }
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus) {
        final int jobUid = jobStatus.getSourceUid();
        final boolean ignoreBlocked = (jobStatus.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0;
        final Network network = mConnManager.getActiveNetworkForUid(jobUid, ignoreBlocked);
        final NetworkInfo info = mConnManager.getNetworkInfoForUid(network, jobUid, ignoreBlocked);

        final NetworkCapabilities capabilities = (network != null)
                ? mConnManager.getNetworkCapabilities(network) : null;

        final boolean connected = (info != null) && info.isConnected();
        final boolean validated = (capabilities != null)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        final boolean sane = isSane(jobStatus, capabilities);
        final boolean connectionUsable = connected && validated && sane;

        final boolean metered = connected && (capabilities != null)
                && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        final boolean unmetered = connected && (capabilities != null)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        final boolean notRoaming = connected && (info != null)
                && !info.isRoaming();

        boolean changed = false;
        changed |= jobStatus.setConnectivityConstraintSatisfied(connectionUsable);
        changed |= jobStatus.setMeteredConstraintSatisfied(metered);
        changed |= jobStatus.setUnmeteredConstraintSatisfied(unmetered);
        changed |= jobStatus.setNotRoamingConstraintSatisfied(notRoaming);

        // Pass along the evaluated network for job to use; prevents race
        // conditions as default routes change over time, and opens the door to
        // using non-default routes.
        jobStatus.network = network;

        // Track system-uid connected/validated as a general reportable proxy for the
        // overall state of connectivity constraint satisfiability.
        if (jobUid == Process.SYSTEM_UID) {
            mConnected = connected;
            mValidated = validated;
        }

        if (DEBUG) {
            Slog.i(TAG, "Connectivity " + (changed ? "CHANGED" : "unchanged")
                    + " for " + jobStatus + ": usable=" + connectionUsable
                    + " connected=" + connected
                    + " validated=" + validated
                    + " metered=" + metered
                    + " unmetered=" + unmetered
                    + " notRoaming=" + notRoaming);
        }
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
            for (int i = mTrackedJobs.size()-1; i >= 0; i--) {
                final JobStatus js = mTrackedJobs.valueAt(i);
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
    public void onNetworkActive() {
        synchronized (mLock) {
            for (int i = mTrackedJobs.size()-1; i >= 0; i--) {
                final JobStatus js = mTrackedJobs.valueAt(i);
                if (js.isReady()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Running " + js + " due to network activity.");
                    }
                    mStateChangedListener.onRunJobNow(js);
                }
            }
        }
    }

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            if (DEBUG) {
                Slog.v(TAG, "onCapabilitiesChanged() : " + networkCapabilities);
            }
            updateTrackedJobs(-1);
        }

        @Override
        public void onLost(Network network) {
            if (DEBUG) {
                Slog.v(TAG, "Network lost");
            }
            updateTrackedJobs(-1);
        }
    };

    private final INetworkPolicyListener mNetPolicyListener = new INetworkPolicyListener.Stub() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            if (DEBUG) {
                Slog.v(TAG, "Uid rules changed for " + uid);
            }
            updateTrackedJobs(uid);
        }

        @Override
        public void onMeteredIfacesChanged(String[] meteredIfaces) {
            // We track this via our NetworkCallback
        }

        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            if (DEBUG) {
                Slog.v(TAG, "Background restriction change to " + restrictBackground);
            }
            updateTrackedJobs(-1);
        }

        @Override
        public void onUidPoliciesChanged(int uid, int uidPolicies) {
            if (DEBUG) {
                Slog.v(TAG, "Uid policy changed for " + uid);
            }
            updateTrackedJobs(uid);
        }
    };

    @Override
    public void dumpControllerStateLocked(PrintWriter pw, int filterUid) {
        pw.print("Connectivity: connected=");
        pw.print(mConnected);
        pw.print(" validated=");
        pw.println(mValidated);
        pw.print("Tracking ");
        pw.print(mTrackedJobs.size());
        pw.println(":");
        for (int i = 0; i < mTrackedJobs.size(); i++) {
            final JobStatus js = mTrackedJobs.valueAt(i);
            if (js.shouldDump(filterUid)) {
                pw.print("  #");
                js.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, js.getSourceUid());
                pw.print(": C="); pw.print(js.needsAnyConnectivity());
                pw.print(": M="); pw.print(js.needsMeteredConnectivity());
                pw.print(": UM="); pw.print(js.needsUnmeteredConnectivity());
                pw.print(": NR="); pw.println(js.needsNonRoamingConnectivity());
            }
        }
    }
}
