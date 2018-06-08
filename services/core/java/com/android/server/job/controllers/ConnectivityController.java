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

import static android.net.NetworkCapabilities.LINK_BANDWIDTH_UNSPECIFIED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;

import android.app.job.JobInfo;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetworkPolicyListener;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.net.TrafficStats;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.JobServiceContext;
import com.android.server.job.StateControllerProto;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Handles changes in connectivity.
 * <p>
 * Each app can have a different default networks or different connectivity
 * status due to user-requested network policies, so we need to check
 * constraints on a per-UID basis.
 */
public final class ConnectivityController extends StateController implements
        ConnectivityManager.OnNetworkActiveListener {
    private static final String TAG = "JobScheduler.Connectivity";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final ConnectivityManager mConnManager;
    private final NetworkPolicyManager mNetPolicyManager;

    @GuardedBy("mLock")
    private final ArraySet<JobStatus> mTrackedJobs = new ArraySet<>();

    public ConnectivityController(JobSchedulerService service) {
        super(service);

        mConnManager = mContext.getSystemService(ConnectivityManager.class);
        mNetPolicyManager = mContext.getSystemService(NetworkPolicyManager.class);

        // We're interested in all network changes; internally we match these
        // network changes against the active network for each UID with jobs.
        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        mConnManager.registerNetworkCallback(request, mNetworkCallback);

        mNetPolicyManager.registerListener(mNetPolicyListener);
    }

    @GuardedBy("mLock")
    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (jobStatus.hasConnectivityConstraint()) {
            updateConstraintsSatisfied(jobStatus);
            mTrackedJobs.add(jobStatus);
            jobStatus.setTrackingController(JobStatus.TRACKING_CONNECTIVITY);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (jobStatus.clearTrackingController(JobStatus.TRACKING_CONNECTIVITY)) {
            mTrackedJobs.remove(jobStatus);
        }
    }

    /**
     * Test to see if running the given job on the given network is insane.
     * <p>
     * For example, if a job is trying to send 10MB over a 128Kbps EDGE
     * connection, it would take 10.4 minutes, and has no chance of succeeding
     * before the job times out, so we'd be insane to try running it.
     */
    @SuppressWarnings("unused")
    private static boolean isInsane(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        final long estimatedBytes = jobStatus.getEstimatedNetworkBytes();
        if (estimatedBytes == JobInfo.NETWORK_BYTES_UNKNOWN) {
            // We don't know how large the job is; cross our fingers!
            return false;
        }

        // We don't ask developers to differentiate between upstream/downstream
        // in their size estimates, so test against the slowest link direction.
        final long slowest = NetworkCapabilities.minBandwidth(
                capabilities.getLinkDownstreamBandwidthKbps(),
                capabilities.getLinkUpstreamBandwidthKbps());
        if (slowest == LINK_BANDWIDTH_UNSPECIFIED) {
            // We don't know what the network is like; cross our fingers!
            return false;
        }

        final long estimatedMillis = ((estimatedBytes * DateUtils.SECOND_IN_MILLIS)
                / (slowest * TrafficStats.KB_IN_BYTES / 8));
        if (estimatedMillis > JobServiceContext.EXECUTING_TIMESLICE_MILLIS) {
            // If we'd never finish before the timeout, we'd be insane!
            Slog.w(TAG, "Estimated " + estimatedBytes + " bytes over " + slowest
                    + " kbps network would take " + estimatedMillis + "ms; that's insane!");
            return true;
        } else {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private static boolean isCongestionDelayed(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        // If network is congested, and job is less than 50% through the
        // developer-requested window, then we're okay delaying the job.
        if (!capabilities.hasCapability(NET_CAPABILITY_NOT_CONGESTED)) {
            return jobStatus.getFractionRunTime() < constants.CONN_CONGESTION_DELAY_FRAC;
        } else {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private static boolean isStrictSatisfied(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        return jobStatus.getJob().getRequiredNetwork().networkCapabilities
                .satisfiedByNetworkCapabilities(capabilities);
    }

    @SuppressWarnings("unused")
    private static boolean isRelaxedSatisfied(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        // Only consider doing this for prefetching jobs
        if (!jobStatus.getJob().isPrefetch()) {
            return false;
        }

        // See if we match after relaxing any unmetered request
        final NetworkCapabilities relaxed = new NetworkCapabilities(
                jobStatus.getJob().getRequiredNetwork().networkCapabilities)
                        .removeCapability(NET_CAPABILITY_NOT_METERED);
        if (relaxed.satisfiedByNetworkCapabilities(capabilities)) {
            // TODO: treat this as "maybe" response; need to check quotas
            return jobStatus.getFractionRunTime() > constants.CONN_PREFETCH_RELAX_FRAC;
        } else {
            return false;
        }
    }

    @VisibleForTesting
    static boolean isSatisfied(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        // Zeroth, we gotta have a network to think about being satisfied
        if (network == null || capabilities == null) return false;

        // First, are we insane?
        if (isInsane(jobStatus, network, capabilities, constants)) return false;

        // Second, is the network congested?
        if (isCongestionDelayed(jobStatus, network, capabilities, constants)) return false;

        // Third, is the network a strict match?
        if (isStrictSatisfied(jobStatus, network, capabilities, constants)) return true;

        // Third, is the network a relaxed match?
        if (isRelaxedSatisfied(jobStatus, network, capabilities, constants)) return true;

        return false;
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus) {
        final Network network = mConnManager.getActiveNetworkForUid(jobStatus.getSourceUid());
        final NetworkCapabilities capabilities = mConnManager.getNetworkCapabilities(network);
        return updateConstraintsSatisfied(jobStatus, network, capabilities);
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities) {
        // TODO: consider matching against non-active networks

        final boolean ignoreBlocked = (jobStatus.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0;
        final NetworkInfo info = mConnManager.getNetworkInfoForUid(network,
                jobStatus.getSourceUid(), ignoreBlocked);

        final boolean connected = (info != null) && info.isConnected();
        final boolean satisfied = isSatisfied(jobStatus, network, capabilities, mConstants);

        final boolean changed = jobStatus
                .setConnectivityConstraintSatisfied(connected && satisfied);

        // Pass along the evaluated network for job to use; prevents race
        // conditions as default routes change over time, and opens the door to
        // using non-default routes.
        jobStatus.network = network;

        if (DEBUG) {
            Slog.i(TAG, "Connectivity " + (changed ? "CHANGED" : "unchanged")
                    + " for " + jobStatus + ": connected=" + connected
                    + " satisfied=" + satisfied);
        }
        return changed;
    }

    /**
     * Update any jobs tracked by this controller that match given filters.
     *
     * @param filterUid only update jobs belonging to this UID, or {@code -1} to
     *            update all tracked jobs.
     * @param filterNetwork only update jobs that would use this
     *            {@link Network}, or {@code null} to update all tracked jobs.
     */
    private void updateTrackedJobs(int filterUid, Network filterNetwork) {
        synchronized (mLock) {
            // Since this is a really hot codepath, temporarily cache any
            // answers that we get from ConnectivityManager.
            final SparseArray<Network> uidToNetwork = new SparseArray<>();
            final SparseArray<NetworkCapabilities> networkToCapabilities = new SparseArray<>();

            boolean changed = false;
            for (int i = mTrackedJobs.size() - 1; i >= 0; i--) {
                final JobStatus js = mTrackedJobs.valueAt(i);
                final int uid = js.getSourceUid();

                final boolean uidMatch = (filterUid == -1 || filterUid == uid);
                if (uidMatch) {
                    Network network = uidToNetwork.get(uid);
                    if (network == null) {
                        network = mConnManager.getActiveNetworkForUid(uid);
                        uidToNetwork.put(uid, network);
                    }

                    // Update either when we have a network match, or when the
                    // job hasn't yet been evaluated against the currently
                    // active network; typically when we just lost a network.
                    final boolean networkMatch = (filterNetwork == null
                            || Objects.equals(filterNetwork, network));
                    final boolean forceUpdate = !Objects.equals(js.network, network);
                    if (networkMatch || forceUpdate) {
                        final int netId = network != null ? network.netId : -1;
                        NetworkCapabilities capabilities = networkToCapabilities.get(netId);
                        if (capabilities == null) {
                            capabilities = mConnManager.getNetworkCapabilities(network);
                            networkToCapabilities.put(netId, capabilities);
                        }
                        changed |= updateConstraintsSatisfied(js, network, capabilities);
                    }
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
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (DEBUG) {
                Slog.v(TAG, "onCapabilitiesChanged: " + network);
            }
            updateTrackedJobs(-1, network);
        }

        @Override
        public void onLost(Network network) {
            if (DEBUG) {
                Slog.v(TAG, "onLost: " + network);
            }
            updateTrackedJobs(-1, network);
        }
    };

    private final INetworkPolicyListener mNetPolicyListener = new NetworkPolicyManager.Listener() {
        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            if (DEBUG) {
                Slog.v(TAG, "onUidRulesChanged: " + uid);
            }
            updateTrackedJobs(uid, null);
        }
    };

    @GuardedBy("mLock")
    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {
        for (int i = 0; i < mTrackedJobs.size(); i++) {
            final JobStatus js = mTrackedJobs.valueAt(i);
            if (predicate.test(js)) {
                pw.print("#");
                js.printUniqueId(pw);
                pw.print(" from ");
                UserHandle.formatUid(pw, js.getSourceUid());
                pw.print(": ");
                pw.print(js.getJob().getRequiredNetwork());
                pw.println();
            }
        }
    }

    @GuardedBy("mLock")
    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {
        final long token = proto.start(fieldId);
        final long mToken = proto.start(StateControllerProto.CONNECTIVITY);

        for (int i = 0; i < mTrackedJobs.size(); i++) {
            final JobStatus js = mTrackedJobs.valueAt(i);
            if (!predicate.test(js)) {
                continue;
            }
            final long jsToken = proto.start(StateControllerProto.ConnectivityController.TRACKED_JOBS);
            js.writeToShortProto(proto, StateControllerProto.ConnectivityController.TrackedJob.INFO);
            proto.write(StateControllerProto.ConnectivityController.TrackedJob.SOURCE_UID,
                    js.getSourceUid());
            NetworkRequest rn = js.getJob().getRequiredNetwork();
            if (rn != null) {
                rn.writeToProto(proto,
                        StateControllerProto.ConnectivityController.TrackedJob.REQUIRED_NETWORK);
            }
            proto.end(jsToken);
        }

        proto.end(mToken);
        proto.end(token);
    }
}
