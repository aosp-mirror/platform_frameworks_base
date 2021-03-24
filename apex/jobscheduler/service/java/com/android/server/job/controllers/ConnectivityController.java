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

import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED;
import static android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED;

import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.job.JobInfo;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetworkPolicyListener;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DataUnit;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.StateControllerProto;
import com.android.server.net.NetworkPolicyManagerInternal;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * Handles changes in connectivity.
 * <p>
 * Each app can have a different default networks or different connectivity
 * status due to user-requested network policies, so we need to check
 * constraints on a per-UID basis.
 *
 * Test: atest com.android.server.job.controllers.ConnectivityControllerTest
 */
public final class ConnectivityController extends RestrictingController implements
        ConnectivityManager.OnNetworkActiveListener {
    private static final String TAG = "JobScheduler.Connectivity";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final ConnectivityManager mConnManager;
    private final NetworkPolicyManager mNetPolicyManager;
    private final NetworkPolicyManagerInternal mNetPolicyManagerInternal;

    /** List of tracked jobs keyed by source UID. */
    @GuardedBy("mLock")
    private final SparseArray<ArraySet<JobStatus>> mTrackedJobs = new SparseArray<>();

    /**
     * Keep track of all the UID's jobs that the controller has requested that NetworkPolicyManager
     * grant an exception to in the app standby chain.
     */
    @GuardedBy("mLock")
    private final SparseArray<ArraySet<JobStatus>> mRequestedWhitelistJobs = new SparseArray<>();

    /**
     * Set of currently available networks mapped to their latest network capabilities. Cache the
     * latest capabilities to avoid unnecessary calls into ConnectivityManager.
     */
    @GuardedBy("mLock")
    private final ArrayMap<Network, NetworkCapabilities> mAvailableNetworks = new ArrayMap<>();

    private static final int MSG_DATA_SAVER_TOGGLED = 0;
    private static final int MSG_UID_RULES_CHANGES = 1;
    private static final int MSG_REEVALUATE_JOBS = 2;

    private final Handler mHandler;

    public ConnectivityController(JobSchedulerService service) {
        super(service);
        mHandler = new CcHandler(mContext.getMainLooper());

        mConnManager = mContext.getSystemService(ConnectivityManager.class);
        mNetPolicyManager = mContext.getSystemService(NetworkPolicyManager.class);
        mNetPolicyManagerInternal = LocalServices.getService(NetworkPolicyManagerInternal.class);

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
            ArraySet<JobStatus> jobs = mTrackedJobs.get(jobStatus.getSourceUid());
            if (jobs == null) {
                jobs = new ArraySet<>();
                mTrackedJobs.put(jobStatus.getSourceUid(), jobs);
            }
            jobs.add(jobStatus);
            jobStatus.setTrackingController(JobStatus.TRACKING_CONNECTIVITY);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {
        if (jobStatus.clearTrackingController(JobStatus.TRACKING_CONNECTIVITY)) {
            ArraySet<JobStatus> jobs = mTrackedJobs.get(jobStatus.getSourceUid());
            if (jobs != null) {
                jobs.remove(jobStatus);
            }
            maybeRevokeStandbyExceptionLocked(jobStatus);
        }
    }

    @Override
    public void startTrackingRestrictedJobLocked(JobStatus jobStatus) {
        // Don't need to start tracking the job. If the job needed network, it would already be
        // tracked.
        if (jobStatus.hasConnectivityConstraint()) {
            updateConstraintsSatisfied(jobStatus);
        }
    }

    @Override
    public void stopTrackingRestrictedJobLocked(JobStatus jobStatus) {
        // Shouldn't stop tracking the job here. If the job was tracked, it still needs network,
        // even after being unrestricted.
        if (jobStatus.hasConnectivityConstraint()) {
            updateConstraintsSatisfied(jobStatus);
        }
    }

    /**
     * Returns true if the job's requested network is available. This DOES NOT necessarily mean
     * that the UID has been granted access to the network.
     */
    public boolean isNetworkAvailable(JobStatus job) {
        synchronized (mLock) {
            for (int i = 0; i < mAvailableNetworks.size(); ++i) {
                final Network network = mAvailableNetworks.keyAt(i);
                final NetworkCapabilities capabilities = mAvailableNetworks.valueAt(i);
                final boolean satisfied = isSatisfied(job, network, capabilities, mConstants);
                if (DEBUG) {
                    Slog.v(TAG, "isNetworkAvailable(" + job + ") with network " + network
                            + " and capabilities " + capabilities + ". Satisfied=" + satisfied);
                }
                if (satisfied) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Request that NetworkPolicyManager grant an exception to the uid from its standby policy
     * chain.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void requestStandbyExceptionLocked(JobStatus job) {
        final int uid = job.getSourceUid();
        // Need to call this before adding the job.
        final boolean isExceptionRequested = isStandbyExceptionRequestedLocked(uid);
        ArraySet<JobStatus> jobs = mRequestedWhitelistJobs.get(uid);
        if (jobs == null) {
            jobs = new ArraySet<JobStatus>();
            mRequestedWhitelistJobs.put(uid, jobs);
        }
        if (!jobs.add(job) || isExceptionRequested) {
            if (DEBUG) {
                Slog.i(TAG, "requestStandbyExceptionLocked found exception already requested.");
            }
            return;
        }
        if (DEBUG) Slog.i(TAG, "Requesting standby exception for UID: " + uid);
        mNetPolicyManagerInternal.setAppIdleWhitelist(uid, true);
    }

    /** Returns whether a standby exception has been requested for the UID. */
    @VisibleForTesting
    @GuardedBy("mLock")
    boolean isStandbyExceptionRequestedLocked(final int uid) {
        ArraySet jobs = mRequestedWhitelistJobs.get(uid);
        return jobs != null && jobs.size() > 0;
    }

    /**
     * Tell NetworkPolicyManager not to block a UID's network connection if that's the only
     * thing stopping a job from running.
     */
    @GuardedBy("mLock")
    @Override
    public void evaluateStateLocked(JobStatus jobStatus) {
        if (!jobStatus.hasConnectivityConstraint()) {
            return;
        }

        if (jobStatus.shouldTreatAsExpeditedJob()) {
            if (!jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY)) {
                // Don't request a direct hole through any of the firewalls. Instead, mark the
                // constraint as satisfied if the network is available, and the job will get
                // through the firewalls once it starts running and the proc state is elevated.
                // This is the same behavior that FGS see.
                updateConstraintsSatisfied(jobStatus);
            }
            // Don't need to update constraint here if the network goes away. We'll do that as part
            // of regular processing when we're notified about the drop.
        } else if (jobStatus.isRequestedExpeditedJob()
                && jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY)) {
            // Make sure we don't accidentally keep the constraint as satisfied if the job went
            // from being expedited-ready to not-expeditable.
            updateConstraintsSatisfied(jobStatus);
        }

        // Always check the full job readiness stat in case the component has been disabled.
        if (wouldBeReadyWithConstraintLocked(jobStatus, JobStatus.CONSTRAINT_CONNECTIVITY)
                && isNetworkAvailable(jobStatus)) {
            if (DEBUG) {
                Slog.i(TAG, "evaluateStateLocked finds job " + jobStatus + " would be ready.");
            }
            requestStandbyExceptionLocked(jobStatus);
        } else {
            if (DEBUG) {
                Slog.i(TAG, "evaluateStateLocked finds job " + jobStatus + " would not be ready.");
            }
            maybeRevokeStandbyExceptionLocked(jobStatus);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void reevaluateStateLocked(final int uid) {
        // Check if we still need a connectivity exception in case the JobService was disabled.
        ArraySet<JobStatus> jobs = mTrackedJobs.get(uid);
        if (jobs == null) {
            return;
        }
        for (int i = jobs.size() - 1; i >= 0; i--) {
            evaluateStateLocked(jobs.valueAt(i));
        }
    }

    /** Cancel the requested standby exception if none of the jobs would be ready to run anyway. */
    @VisibleForTesting
    @GuardedBy("mLock")
    void maybeRevokeStandbyExceptionLocked(final JobStatus job) {
        final int uid = job.getSourceUid();
        if (!isStandbyExceptionRequestedLocked(uid)) {
            return;
        }
        ArraySet<JobStatus> jobs = mRequestedWhitelistJobs.get(uid);
        if (jobs == null) {
            Slog.wtf(TAG,
                    "maybeRevokeStandbyExceptionLocked found null jobs array even though a "
                            + "standby exception has been requested.");
            return;
        }
        if (!jobs.remove(job) || jobs.size() > 0) {
            if (DEBUG) {
                Slog.i(TAG,
                        "maybeRevokeStandbyExceptionLocked not revoking because there are still "
                                + jobs.size() + " jobs left.");
            }
            return;
        }
        // No more jobs that need an exception.
        revokeStandbyExceptionLocked(uid);
    }

    /**
     * Tell NetworkPolicyManager to revoke any exception it granted from its standby policy chain
     * for the uid.
     */
    @GuardedBy("mLock")
    private void revokeStandbyExceptionLocked(final int uid) {
        if (DEBUG) Slog.i(TAG, "Revoking standby exception for UID: " + uid);
        mNetPolicyManagerInternal.setAppIdleWhitelist(uid, false);
        mRequestedWhitelistJobs.remove(uid);
    }

    @GuardedBy("mLock")
    @Override
    public void onAppRemovedLocked(String pkgName, int uid) {
        mTrackedJobs.delete(uid);
    }

    /**
     * Test to see if running the given job on the given network is insane.
     * <p>
     * For example, if a job is trying to send 10MB over a 128Kbps EDGE
     * connection, it would take 10.4 minutes, and has no chance of succeeding
     * before the job times out, so we'd be insane to try running it.
     */
    private boolean isInsane(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        // Use the maximum possible time since it gives us an upper bound, even though the job
        // could end up stopping earlier.
        final long maxJobExecutionTimeMs = mService.getMaxJobExecutionTimeMs(jobStatus);

        final long downloadBytes = jobStatus.getEstimatedNetworkDownloadBytes();
        if (downloadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
            final long bandwidth = capabilities.getLinkDownstreamBandwidthKbps();
            // If we don't know the bandwidth, all we can do is hope the job finishes in time.
            if (bandwidth > 0) {
                // Divide by 8 to convert bits to bytes.
                final long estimatedMillis = ((downloadBytes * DateUtils.SECOND_IN_MILLIS)
                        / (DataUnit.KIBIBYTES.toBytes(bandwidth) / 8));
                if (estimatedMillis > maxJobExecutionTimeMs) {
                    // If we'd never finish before the timeout, we'd be insane!
                    Slog.w(TAG, "Estimated " + downloadBytes + " download bytes over " + bandwidth
                            + " kbps network would take " + estimatedMillis + "ms and job has "
                            + maxJobExecutionTimeMs + "ms to run; that's insane!");
                    return true;
                }
            }
        }

        final long uploadBytes = jobStatus.getEstimatedNetworkUploadBytes();
        if (uploadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
            final long bandwidth = capabilities.getLinkUpstreamBandwidthKbps();
            // If we don't know the bandwidth, all we can do is hope the job finishes in time.
            if (bandwidth > 0) {
                // Divide by 8 to convert bits to bytes.
                final long estimatedMillis = ((uploadBytes * DateUtils.SECOND_IN_MILLIS)
                        / (DataUnit.KIBIBYTES.toBytes(bandwidth) / 8));
                if (estimatedMillis > maxJobExecutionTimeMs) {
                    // If we'd never finish before the timeout, we'd be insane!
                    Slog.w(TAG, "Estimated " + uploadBytes + " upload bytes over " + bandwidth
                            + " kbps network would take " + estimatedMillis + "ms and job has "
                            + maxJobExecutionTimeMs + "ms to run; that's insane!");
                    return true;
                }
            }
        }

        return false;
    }

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

    private static NetworkCapabilities.Builder copyCapabilities(
            @NonNull final NetworkRequest request) {
        final NetworkCapabilities.Builder builder = new NetworkCapabilities.Builder();
        for (int transport : request.getTransportTypes()) builder.addTransportType(transport);
        for (int capability : request.getCapabilities()) builder.addCapability(capability);
        return builder;
    }

    private static boolean isStrictSatisfied(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        // A restricted job that's out of quota MUST use an unmetered network.
        if (jobStatus.getEffectiveStandbyBucket() == RESTRICTED_INDEX
                && !jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA)) {
            final NetworkCapabilities.Builder builder =
                    copyCapabilities(jobStatus.getJob().getRequiredNetwork());
            builder.addCapability(NET_CAPABILITY_NOT_METERED);
            return builder.build().satisfiedByNetworkCapabilities(capabilities);
        } else {
            return jobStatus.getJob().getRequiredNetwork().canBeSatisfiedBy(capabilities);
        }
    }

    private static boolean isRelaxedSatisfied(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        // Only consider doing this for unrestricted prefetching jobs
        if (!jobStatus.getJob().isPrefetch() || jobStatus.getStandbyBucket() == RESTRICTED_INDEX) {
            return false;
        }

        // See if we match after relaxing any unmetered request
        final NetworkCapabilities.Builder builder =
                copyCapabilities(jobStatus.getJob().getRequiredNetwork());
        builder.removeCapability(NET_CAPABILITY_NOT_METERED);
        if (builder.build().satisfiedByNetworkCapabilities(capabilities)) {
            // TODO: treat this as "maybe" response; need to check quotas
            return jobStatus.getFractionRunTime() > constants.CONN_PREFETCH_RELAX_FRAC;
        } else {
            return false;
        }
    }

    @VisibleForTesting
    boolean isSatisfied(JobStatus jobStatus, Network network,
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

    @Nullable
    private NetworkCapabilities getNetworkCapabilities(@Nullable Network network) {
        if (network == null) {
            return null;
        }
        synchronized (mLock) {
            // There is technically a race here if the Network object is reused. This can happen
            // only if that Network disconnects and the auto-incrementing network ID in
            // ConnectivityService wraps. This should no longer be a concern if/when we only make
            // use of asynchronous calls.
            if (mAvailableNetworks.get(network) != null) {
                return mAvailableNetworks.get(network);
            }

            // This should almost never happen because any time a new network connects, the
            // NetworkCallback would populate mAvailableNetworks. However, it's currently necessary
            // because we also call synchronous methods such as getActiveNetworkForUid.
            // TODO(134978280): remove after switching to callback-based APIs
            final NetworkCapabilities capabilities = mConnManager.getNetworkCapabilities(network);
            mAvailableNetworks.put(network, capabilities);
            return capabilities;
        }
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus) {
        final Network network = mConnManager.getActiveNetworkForUid(
                jobStatus.getSourceUid(), jobStatus.shouldIgnoreNetworkBlocking());
        final NetworkCapabilities capabilities = getNetworkCapabilities(network);
        return updateConstraintsSatisfied(jobStatus, sElapsedRealtimeClock.millis(),
                network, capabilities);
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus, final long nowElapsed,
            Network network, NetworkCapabilities capabilities) {
        // TODO: consider matching against non-active networks

        final boolean ignoreBlocked = jobStatus.shouldIgnoreNetworkBlocking();
        final NetworkInfo info = mConnManager.getNetworkInfoForUid(network,
                jobStatus.getSourceUid(), ignoreBlocked);

        final boolean connected = (info != null) && info.isConnected();
        final boolean satisfied = isSatisfied(jobStatus, network, capabilities, mConstants);

        final boolean changed = jobStatus
                .setConnectivityConstraintSatisfied(nowElapsed, connected && satisfied);

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
     * @param filterUid     only update jobs belonging to this UID, or {@code -1} to
     *                      update all tracked jobs.
     * @param filterNetwork only update jobs that would use this
     *                      {@link Network}, or {@code null} to update all tracked jobs.
     */
    private void updateTrackedJobs(int filterUid, Network filterNetwork) {
        synchronized (mLock) {
            boolean changed = false;
            if (filterUid == -1) {
                for (int i = mTrackedJobs.size() - 1; i >= 0; i--) {
                    changed |= updateTrackedJobsLocked(mTrackedJobs.valueAt(i), filterNetwork);
                }
            } else {
                changed = updateTrackedJobsLocked(mTrackedJobs.get(filterUid), filterNetwork);
            }
            if (changed) {
                mStateChangedListener.onControllerStateChanged();
            }
        }
    }

    private boolean updateTrackedJobsLocked(ArraySet<JobStatus> jobs, Network filterNetwork) {
        if (jobs == null || jobs.size() == 0) {
            return false;
        }

        final Network network =
                mConnManager.getActiveNetworkForUid(jobs.valueAt(0).getSourceUid(), false);
        final NetworkCapabilities capabilities = getNetworkCapabilities(network);
        final boolean networkMatch = (filterNetwork == null
                || Objects.equals(filterNetwork, network));
        boolean exemptedLoaded = false;
        Network exemptedNetwork = null;
        NetworkCapabilities exemptedNetworkCapabilities = null;
        boolean exemptedNetworkMatch = false;

        final long nowElapsed = sElapsedRealtimeClock.millis();
        boolean changed = false;
        for (int i = jobs.size() - 1; i >= 0; i--) {
            final JobStatus js = jobs.valueAt(i);

            Network net = network;
            NetworkCapabilities netCap = capabilities;
            boolean match = networkMatch;

            if (js.shouldIgnoreNetworkBlocking()) {
                if (!exemptedLoaded) {
                    exemptedLoaded = true;
                    exemptedNetwork = mConnManager.getActiveNetworkForUid(js.getSourceUid(), true);
                    exemptedNetworkCapabilities = getNetworkCapabilities(exemptedNetwork);
                    exemptedNetworkMatch = (filterNetwork == null
                            || Objects.equals(filterNetwork, exemptedNetwork));
                }
                net = exemptedNetwork;
                netCap = exemptedNetworkCapabilities;
                match = exemptedNetworkMatch;
            }

            // Update either when we have a network match, or when the
            // job hasn't yet been evaluated against the currently
            // active network; typically when we just lost a network.
            if (match || !Objects.equals(js.network, net)) {
                changed |= updateConstraintsSatisfied(js, nowElapsed, net, netCap);
            }
        }
        return changed;
    }

    /**
     * We know the network has just come up. We want to run any jobs that are ready.
     */
    @Override
    public void onNetworkActive() {
        synchronized (mLock) {
            for (int i = mTrackedJobs.size()-1; i >= 0; i--) {
                final ArraySet<JobStatus> jobs = mTrackedJobs.valueAt(i);
                for (int j = jobs.size() - 1; j >= 0; j--) {
                    final JobStatus js = jobs.valueAt(j);
                    if (js.isReady()) {
                        if (DEBUG) {
                            Slog.d(TAG, "Running " + js + " due to network activity.");
                        }
                        mStateChangedListener.onRunJobNow(js);
                    }
                }
            }
        }
    }

    private final NetworkCallback mNetworkCallback = new NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            if (DEBUG) Slog.v(TAG, "onAvailable: " + network);
            // Documentation says not to call getNetworkCapabilities here but wait for
            // onCapabilitiesChanged instead.  onCapabilitiesChanged should be called immediately
            // after this, so no need to update mAvailableNetworks here.
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            if (DEBUG) {
                Slog.v(TAG, "onCapabilitiesChanged: " + network);
            }
            synchronized (mLock) {
                mAvailableNetworks.put(network, capabilities);
            }
            updateTrackedJobs(-1, network);
        }

        @Override
        public void onLost(Network network) {
            if (DEBUG) {
                Slog.v(TAG, "onLost: " + network);
            }
            synchronized (mLock) {
                mAvailableNetworks.remove(network);
            }
            updateTrackedJobs(-1, network);
        }
    };

    private final INetworkPolicyListener mNetPolicyListener = new NetworkPolicyManager.Listener() {
        @Override
        public void onRestrictBackgroundChanged(boolean restrictBackground) {
            if (DEBUG) {
                Slog.v(TAG, "onRestrictBackgroundChanged: " + restrictBackground);
            }
            mHandler.obtainMessage(MSG_DATA_SAVER_TOGGLED).sendToTarget();
        }

        @Override
        public void onUidRulesChanged(int uid, int uidRules) {
            if (DEBUG) {
                Slog.v(TAG, "onUidRulesChanged: " + uid);
            }
            mHandler.obtainMessage(MSG_UID_RULES_CHANGES, uid, 0).sendToTarget();
        }
    };

    private class CcHandler extends Handler {
        CcHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                switch (msg.what) {
                    case MSG_DATA_SAVER_TOGGLED:
                        updateTrackedJobs(-1, null);
                        break;
                    case MSG_UID_RULES_CHANGES:
                        updateTrackedJobs(msg.arg1, null);
                        break;
                    case MSG_REEVALUATE_JOBS:
                        updateTrackedJobs(-1, null);
                        break;
                }
            }
        }
    };

    @GuardedBy("mLock")
    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {

        if (mRequestedWhitelistJobs.size() > 0) {
            pw.print("Requested standby exceptions:");
            for (int i = 0; i < mRequestedWhitelistJobs.size(); i++) {
                pw.print(" ");
                pw.print(mRequestedWhitelistJobs.keyAt(i));
                pw.print(" (");
                pw.print(mRequestedWhitelistJobs.valueAt(i).size());
                pw.print(" jobs)");
            }
            pw.println();
        }
        if (mAvailableNetworks.size() > 0) {
            pw.println("Available networks:");
            pw.increaseIndent();
            for (int i = 0; i < mAvailableNetworks.size(); i++) {
                pw.print(mAvailableNetworks.keyAt(i));
                pw.print(": ");
                pw.println(mAvailableNetworks.valueAt(i));
            }
            pw.decreaseIndent();
        } else {
            pw.println("No available networks");
        }
        for (int i = 0; i < mTrackedJobs.size(); i++) {
            final ArraySet<JobStatus> jobs = mTrackedJobs.valueAt(i);
            for (int j = 0; j < jobs.size(); j++) {
                final JobStatus js = jobs.valueAt(j);
                if (!predicate.test(js)) {
                    continue;
                }
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

        for (int i = 0; i < mRequestedWhitelistJobs.size(); i++) {
            proto.write(
                    StateControllerProto.ConnectivityController.REQUESTED_STANDBY_EXCEPTION_UIDS,
                    mRequestedWhitelistJobs.keyAt(i));
        }
        for (int i = 0; i < mTrackedJobs.size(); i++) {
            final ArraySet<JobStatus> jobs = mTrackedJobs.valueAt(i);
            for (int j = 0; j < jobs.size(); j++) {
                final JobStatus js = jobs.valueAt(j);
                if (!predicate.test(js)) {
                    continue;
                }
                final long jsToken = proto.start(
                        StateControllerProto.ConnectivityController.TRACKED_JOBS);
                js.writeToShortProto(proto,
                        StateControllerProto.ConnectivityController.TrackedJob.INFO);
                proto.write(StateControllerProto.ConnectivityController.TrackedJob.SOURCE_UID,
                        js.getSourceUid());
                proto.end(jsToken);
            }
        }

        proto.end(mToken);
        proto.end(token);
    }
}
