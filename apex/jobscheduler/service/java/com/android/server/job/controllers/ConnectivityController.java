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
import android.net.Network;
import android.net.NetworkCapabilities;
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
import android.util.Pools;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.StateControllerProto;
import com.android.server.net.NetworkPolicyManagerInternal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    // The networking stack has a hard limit so we can't make this configurable.
    private static final int MAX_NETWORK_CALLBACKS = 50;
    /**
     * Minimum amount of time that should have elapsed before we'll update a {@link UidStats}
     * instance.
     */
    private static final long MIN_STATS_UPDATE_INTERVAL_MS = 30_000L;

    private final ConnectivityManager mConnManager;
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

    private final SparseArray<UidDefaultNetworkCallback> mCurrentDefaultNetworkCallbacks =
            new SparseArray<>();
    private final Comparator<UidStats> mUidStatsComparator = new Comparator<UidStats>() {
        private int prioritizeExistence(int v1, int v2) {
            if (v1 > 0 && v2 > 0) {
                return 0;
            }
            return v2 - v1;
        }

        @Override
        public int compare(UidStats us1, UidStats us2) {
            // TODO: build a better prioritization scheme
            // Some things to use:
            //   * Proc state
            //   * IMPORTANT_WHILE_IN_FOREGROUND bit
            final int runningPriority = prioritizeExistence(us1.numRunning, us2.numRunning);
            if (runningPriority != 0) {
                return runningPriority;
            }
            // Prioritize any UIDs that have jobs that would be ready ahead of UIDs that don't.
            final int readyWithConnPriority =
                    prioritizeExistence(us1.numReadyWithConnectivity, us2.numReadyWithConnectivity);
            if (readyWithConnPriority != 0) {
                return readyWithConnPriority;
            }
            // They both have jobs that would be ready. Prioritize the UIDs whose requested
            // network is available ahead of UIDs that don't have their requested network available.
            final int reqAvailPriority = prioritizeExistence(
                    us1.numRequestedNetworkAvailable, us2.numRequestedNetworkAvailable);
            if (reqAvailPriority != 0) {
                return reqAvailPriority;
            }
            // They both have jobs with available networks. Prioritize based on:
            //   1. (eventually) proc state
            //   2. Existence of runnable EJs (not just requested)
            //   3. Enqueue time
            // TODO: maybe consider number of jobs
            final int ejPriority = prioritizeExistence(us1.numEJs, us2.numEJs);
            if (ejPriority != 0) {
                return ejPriority;
            }
            // They both have EJs. Order them by EJ enqueue time to help provide low EJ latency.
            if (us1.earliestEJEnqueueTime < us2.earliestEJEnqueueTime) {
                return -1;
            } else if (us1.earliestEJEnqueueTime > us2.earliestEJEnqueueTime) {
                return 1;
            }
            if (us1.earliestEnqueueTime < us2.earliestEnqueueTime) {
                return -1;
            }
            return us1.earliestEnqueueTime > us2.earliestEnqueueTime ? 1 : 0;
        }
    };
    private final SparseArray<UidStats> mUidStats = new SparseArray<>();
    private final Pools.Pool<UidDefaultNetworkCallback> mDefaultNetworkCallbackPool =
            new Pools.SimplePool<>(MAX_NETWORK_CALLBACKS);
    /**
     * List of UidStats, sorted by priority as defined in {@link #mUidStatsComparator}. The sorting
     * is only done in {@link #maybeAdjustRegisteredCallbacksLocked()} and may sometimes be stale.
     */
    private final List<UidStats> mSortedStats = new ArrayList<>();

    private static final int MSG_REEVALUATE_JOBS = 2;

    private final Handler mHandler;

    public ConnectivityController(JobSchedulerService service) {
        super(service);
        mHandler = new CcHandler(mContext.getMainLooper());

        mConnManager = mContext.getSystemService(ConnectivityManager.class);
        mNetPolicyManagerInternal = LocalServices.getService(NetworkPolicyManagerInternal.class);

        // We're interested in all network changes; internally we match these
        // network changes against the active network for each UID with jobs.
        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        mConnManager.registerNetworkCallback(request, mNetworkCallback);
    }

    @GuardedBy("mLock")
    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (jobStatus.hasConnectivityConstraint()) {
            UidStats uidStats = mUidStats.get(jobStatus.getSourceUid());
            if (uidStats == null) {
                uidStats = new UidStats(jobStatus.getSourceUid());
                mUidStats.append(jobStatus.getSourceUid(), uidStats);
            }
            if (wouldBeReadyWithConstraintLocked(jobStatus, JobStatus.CONSTRAINT_CONNECTIVITY)) {
                uidStats.numReadyWithConnectivity++;
            }
            ArraySet<JobStatus> jobs = mTrackedJobs.get(jobStatus.getSourceUid());
            if (jobs == null) {
                jobs = new ArraySet<>();
                mTrackedJobs.put(jobStatus.getSourceUid(), jobs);
            }
            jobs.add(jobStatus);
            jobStatus.setTrackingController(JobStatus.TRACKING_CONNECTIVITY);
            updateConstraintsSatisfied(jobStatus);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void prepareForExecutionLocked(JobStatus jobStatus) {
        if (jobStatus.hasConnectivityConstraint()) {
            UidStats uidStats = mUidStats.get(jobStatus.getSourceUid());
            uidStats.numRunning++;
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
            UidStats us = mUidStats.get(jobStatus.getSourceUid());
            us.numReadyWithConnectivity--;
            if (jobStatus.madeActive != 0) {
                us.numRunning--;
            }
            maybeRevokeStandbyExceptionLocked(jobStatus);
            maybeAdjustRegisteredCallbacksLocked();
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

        UidStats uidStats = mUidStats.get(jobStatus.getSourceUid());

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
            uidStats.numReadyWithConnectivity++;
            requestStandbyExceptionLocked(jobStatus);
        } else {
            if (DEBUG) {
                Slog.i(TAG, "evaluateStateLocked finds job " + jobStatus + " would not be ready.");
            }
            // Don't decrement numReadyWithConnectivity here because we don't know if it was
            // incremented for this job. The count will be set properly in
            // maybeAdjustRegisteredCallbacksLocked().
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
        UidStats uidStats = mUidStats.removeReturnOld(uid);
        unregisterDefaultNetworkCallbackLocked(uid, sElapsedRealtimeClock.millis());
        mSortedStats.remove(uidStats);
        registerPendingUidCallbacksLocked();
    }

    @GuardedBy("mLock")
    @Override
    public void onUserRemovedLocked(int userId) {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        for (int u = mUidStats.size() - 1; u >= 0; --u) {
            UidStats uidStats = mUidStats.valueAt(u);
            if (UserHandle.getUserId(uidStats.uid) == userId) {
                unregisterDefaultNetworkCallbackLocked(uidStats.uid, nowElapsed);
                mSortedStats.remove(uidStats);
                mUidStats.removeAt(u);
            }
        }
        maybeAdjustRegisteredCallbacksLocked();
    }

    private boolean isUsable(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
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

        if (!isUsable(capabilities)) return false;

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

    @GuardedBy("mLock")
    private void maybeRegisterDefaultNetworkCallbackLocked(JobStatus jobStatus) {
        final int sourceUid = jobStatus.getSourceUid();
        if (mCurrentDefaultNetworkCallbacks.contains(sourceUid)) {
            return;
        }
        UidStats uidStats = mUidStats.get(sourceUid);
        if (!mSortedStats.contains(uidStats)) {
            mSortedStats.add(uidStats);
        }
        if (mCurrentDefaultNetworkCallbacks.size() >= MAX_NETWORK_CALLBACKS) {
            // TODO: offload to handler
            maybeAdjustRegisteredCallbacksLocked();
            return;
        }
        registerPendingUidCallbacksLocked();
    }

    /**
     * Register UID callbacks for UIDs that are next in line, based on the current order in {@link
     * #mSortedStats}. This assumes that there are only registered callbacks for UIDs in the top
     * {@value #MAX_NETWORK_CALLBACKS} UIDs and that the only UIDs missing callbacks are the lower
     * priority ones.
     */
    @GuardedBy("mLock")
    private void registerPendingUidCallbacksLocked() {
        final int numCallbacks = mCurrentDefaultNetworkCallbacks.size();
        final int numPending = mSortedStats.size();
        if (numPending < numCallbacks) {
            // This means there's a bug in the code >.<
            Slog.wtf(TAG, "There are more registered callbacks than sorted UIDs: "
                    + numCallbacks + " vs " + numPending);
        }
        for (int i = numCallbacks; i < numPending && i < MAX_NETWORK_CALLBACKS; ++i) {
            UidStats uidStats = mSortedStats.get(i);
            UidDefaultNetworkCallback callback = mDefaultNetworkCallbackPool.acquire();
            if (callback == null) {
                callback = new UidDefaultNetworkCallback();
            }
            callback.setUid(uidStats.uid);
            mCurrentDefaultNetworkCallbacks.append(uidStats.uid, callback);
            mConnManager.registerDefaultNetworkCallbackAsUid(uidStats.uid, callback, mHandler);
        }
    }

    @GuardedBy("mLock")
    private void maybeAdjustRegisteredCallbacksLocked() {
        final int count = mUidStats.size();
        if (count == mCurrentDefaultNetworkCallbacks.size()) {
            // All of them are registered and there are no blocked UIDs.
            // No point evaluating all UIDs.
            return;
        }
        final long nowElapsed = sElapsedRealtimeClock.millis();
        mSortedStats.clear();

        for (int u = 0; u < mUidStats.size(); ++u) {
            UidStats us = mUidStats.valueAt(u);
            ArraySet<JobStatus> jobs = mTrackedJobs.get(us.uid);
            if (jobs == null || jobs.size() == 0) {
                unregisterDefaultNetworkCallbackLocked(us.uid, nowElapsed);
                continue;
            }

            // We won't evaluate stats in the first 30 seconds after boot...That's probably okay.
            if (us.lastUpdatedElapsed + MIN_STATS_UPDATE_INTERVAL_MS < nowElapsed) {
                us.earliestEnqueueTime = Long.MAX_VALUE;
                us.earliestEJEnqueueTime = Long.MAX_VALUE;
                us.numReadyWithConnectivity = 0;
                us.numRequestedNetworkAvailable = 0;
                us.numRegular = 0;
                us.numEJs = 0;

                for (int j = 0; j < jobs.size(); ++j) {
                    JobStatus job = jobs.valueAt(j);
                    us.earliestEnqueueTime = Math.min(us.earliestEnqueueTime, job.enqueueTime);
                    if (wouldBeReadyWithConstraintLocked(job, JobStatus.CONSTRAINT_CONNECTIVITY)) {
                        us.numReadyWithConnectivity++;
                        if (isNetworkAvailable(job)) {
                            us.numRequestedNetworkAvailable++;
                        }
                    }
                    if (job.shouldTreatAsExpeditedJob() || job.startedAsExpeditedJob) {
                        us.numEJs++;
                        us.earliestEJEnqueueTime =
                                Math.min(us.earliestEJEnqueueTime, job.enqueueTime);
                    } else {
                        us.numRegular++;
                    }
                }

                us.lastUpdatedElapsed = nowElapsed;
            }
            mSortedStats.add(us);
        }

        mSortedStats.sort(mUidStatsComparator);

        boolean changed = false;
        // Iterate in reverse order to remove existing callbacks before adding new ones.
        for (int i = mSortedStats.size() - 1; i >= 0; --i) {
            UidStats us = mSortedStats.get(i);
            if (i >= MAX_NETWORK_CALLBACKS) {
                changed |= unregisterDefaultNetworkCallbackLocked(us.uid, nowElapsed);
            } else {
                UidDefaultNetworkCallback defaultNetworkCallback =
                        mCurrentDefaultNetworkCallbacks.get(us.uid);
                if (defaultNetworkCallback == null) {
                    // Not already registered.
                    defaultNetworkCallback = mDefaultNetworkCallbackPool.acquire();
                    if (defaultNetworkCallback == null) {
                        defaultNetworkCallback = new UidDefaultNetworkCallback();
                    }
                    defaultNetworkCallback.setUid(us.uid);
                    mCurrentDefaultNetworkCallbacks.append(us.uid, defaultNetworkCallback);
                    mConnManager.registerDefaultNetworkCallbackAsUid(
                            us.uid, defaultNetworkCallback, mHandler);
                }
            }
        }
        if (changed) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    @GuardedBy("mLock")
    private boolean unregisterDefaultNetworkCallbackLocked(int uid, long nowElapsed) {
        UidDefaultNetworkCallback defaultNetworkCallback = mCurrentDefaultNetworkCallbacks.get(uid);
        if (defaultNetworkCallback == null) {
            return false;
        }
        mCurrentDefaultNetworkCallbacks.remove(uid);
        mConnManager.unregisterNetworkCallback(defaultNetworkCallback);
        mDefaultNetworkCallbackPool.release(defaultNetworkCallback);
        defaultNetworkCallback.clear();

        boolean changed = false;
        final ArraySet<JobStatus> jobs = mTrackedJobs.get(uid);
        if (jobs != null) {
            // Since we're unregistering the callback, we can no longer monitor
            // changes to the app's network and so we should just mark the
            // connectivity constraint as not satisfied.
            for (int j = jobs.size() - 1; j >= 0; --j) {
                changed |= updateConstraintsSatisfied(
                        jobs.valueAt(j), nowElapsed, null, null);
            }
        }
        return changed;
    }

    @Nullable
    private NetworkCapabilities getNetworkCapabilities(@Nullable Network network) {
        if (network == null) {
            return null;
        }
        synchronized (mLock) {
            // There is technically a race here if the Network object is reused. This can happen
            // only if that Network disconnects and the auto-incrementing network ID in
            // ConnectivityService wraps. This shouldn't be a concern since we only make
            // use of asynchronous calls.
            return mAvailableNetworks.get(network);
        }
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus) {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final UidDefaultNetworkCallback defaultNetworkCallback =
                mCurrentDefaultNetworkCallbacks.get(jobStatus.getSourceUid());
        if (defaultNetworkCallback == null) {
            maybeRegisterDefaultNetworkCallbackLocked(jobStatus);
            return updateConstraintsSatisfied(jobStatus, nowElapsed, null, null);
        }
        final Network network =
                (jobStatus.shouldIgnoreNetworkBlocking() || !defaultNetworkCallback.mBlocked)
                        ? defaultNetworkCallback.mDefaultNetwork : null;
        final NetworkCapabilities capabilities = getNetworkCapabilities(network);
        return updateConstraintsSatisfied(jobStatus, nowElapsed, network, capabilities);
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus, final long nowElapsed,
            Network network, NetworkCapabilities capabilities) {
        // TODO: consider matching against non-default networks

        final boolean satisfied = isSatisfied(jobStatus, network, capabilities, mConstants);

        final boolean changed = jobStatus.setConnectivityConstraintSatisfied(nowElapsed, satisfied);

        // Pass along the evaluated network for job to use; prevents race
        // conditions as default routes change over time, and opens the door to
        // using non-default routes.
        jobStatus.network = network;

        if (DEBUG) {
            Slog.i(TAG, "Connectivity " + (changed ? "CHANGED" : "unchanged")
                    + " for " + jobStatus + ": usable=" + isUsable(capabilities)
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

        UidDefaultNetworkCallback defaultNetworkCallback =
                mCurrentDefaultNetworkCallbacks.get(jobs.valueAt(0).getSourceUid());
        if (defaultNetworkCallback == null) {
            maybeRegisterDefaultNetworkCallbackLocked(jobs.valueAt(0));
            return false;
        }

        final Network network = defaultNetworkCallback.mBlocked
                ? null : defaultNetworkCallback.mDefaultNetwork;
        final NetworkCapabilities capabilities = getNetworkCapabilities(network);
        final boolean networkMatch = (filterNetwork == null
                || Objects.equals(filterNetwork, network));
        // Ignore blocked
        final Network exemptedNetwork = defaultNetworkCallback.mDefaultNetwork;
        final NetworkCapabilities exemptedNetworkCapabilities =
                getNetworkCapabilities(exemptedNetwork);
        final boolean exemptedNetworkMatch =
                (filterNetwork == null || Objects.equals(filterNetwork, exemptedNetwork));

        final long nowElapsed = sElapsedRealtimeClock.millis();
        boolean changed = false;
        for (int i = jobs.size() - 1; i >= 0; i--) {
            final JobStatus js = jobs.valueAt(i);

            Network net = network;
            NetworkCapabilities netCap = capabilities;
            boolean match = networkMatch;

            if (js.shouldIgnoreNetworkBlocking()) {
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
            maybeAdjustRegisteredCallbacksLocked();
        }

        @Override
        public void onLost(Network network) {
            if (DEBUG) {
                Slog.v(TAG, "onLost: " + network);
            }
            synchronized (mLock) {
                mAvailableNetworks.remove(network);
                for (int u = 0; u < mCurrentDefaultNetworkCallbacks.size(); ++u) {
                    UidDefaultNetworkCallback callback = mCurrentDefaultNetworkCallbacks.valueAt(u);
                    if (Objects.equals(callback.mDefaultNetwork, network)) {
                        callback.mDefaultNetwork = null;
                    }
                }
            }
            updateTrackedJobs(-1, network);
            maybeAdjustRegisteredCallbacksLocked();
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
                    case MSG_REEVALUATE_JOBS:
                        updateTrackedJobs(-1, null);
                        break;
                }
            }
        }
    }

    private class UidDefaultNetworkCallback extends NetworkCallback {
        private int mUid;
        @Nullable
        private Network mDefaultNetwork;
        private boolean mBlocked;

        private void setUid(int uid) {
            mUid = uid;
            mDefaultNetwork = null;
        }

        private void clear() {
            mDefaultNetwork = null;
            mUid = UserHandle.USER_NULL;
        }

        @Override
        public void onAvailable(Network network) {
            if (DEBUG) Slog.v(TAG, "default-onAvailable(" + mUid + "): " + network);
        }

        @Override
        public void onBlockedStatusChanged(Network network, boolean blocked) {
            if (DEBUG) {
                Slog.v(TAG, "default-onBlockedStatusChanged(" + mUid + "): "
                        + network + " -> " + blocked);
            }
            if (mUid == UserHandle.USER_NULL) {
                return;
            }
            synchronized (mLock) {
                mDefaultNetwork = network;
                mBlocked = blocked;
            }
            updateTrackedJobs(mUid, network);
        }

        // Network transitions have some complicated behavior that JS doesn't handle very well.
        //
        // * If the default network changes from A to B without A disconnecting, then we'll only
        // get onAvailable(B) (and the subsequent onBlockedStatusChanged() call). Since we get
        // the onBlockedStatusChanged() call, we re-evaluate the job, but keep it running
        // (assuming the new network satisfies constraints). The app continues to use the old
        // network (if they use the network object provided through JobParameters.getNetwork())
        // because we don't notify them of the default network change. If the old network no
        // longer satisfies requested constraints, then we have a problem. Depending on the order
        // of calls, if the per-UID callback gets notified of the network change before the
        // general callback gets notified of the capabilities change, then the job's network
        // object will point to the new network and we won't stop the job, even though we told it
        // to use the old network that no longer satisfies its constraints. This is the behavior
        // we loosely had (ignoring race conditions between asynchronous and synchronous
        // connectivity calls) when we were calling the synchronous getActiveNetworkForUid() API.
        // However, we should fix it.
        // TODO: stop jobs when the existing capabilities change after default network change
        //
        // * If the default network changes from A to B because A disconnected, then we'll get
        // onLost(A) and then onAvailable(B). In this case, there will be a short period where JS
        // doesn't think there's an available network for the job, so we'll stop the job even
        // though onAvailable(B) will be called soon. One on hand, the app would have gotten a
        // network error as well because of A's disconnect, and this will allow JS to provide the
        // job with the new default network. On the other hand, we have to stop the job even
        // though it could have continued running with the new network and the job has to deal
        // with whatever backoff policy is set. For now, the current behavior is fine, but we may
        // want to see if there's a way to have a smoother transition.

        @Override
        public void onLost(Network network) {
            if (DEBUG) {
                Slog.v(TAG, "default-onLost(" + mUid + "): " + network);
            }
            if (mUid == UserHandle.USER_NULL) {
                return;
            }
            synchronized (mLock) {
                if (Objects.equals(mDefaultNetwork, network)) {
                    mDefaultNetwork = null;
                }
            }
            updateTrackedJobs(mUid, network);
        }

        private void dumpLocked(IndentingPrintWriter pw) {
            pw.print("UID: ");
            pw.print(mUid);
            pw.print("; ");
            if (mDefaultNetwork == null) {
                pw.print("No network");
            } else {
                pw.print("Network: ");
                pw.print(mDefaultNetwork);
                pw.print(" (blocked=");
                pw.print(mBlocked);
                pw.print(")");
            }
            pw.println();
        }
    }

    private static class UidStats {
        public final int uid;
        public int numRunning;
        public int numReadyWithConnectivity;
        public int numRequestedNetworkAvailable;
        public int numEJs;
        public int numRegular;
        public long earliestEnqueueTime;
        public long earliestEJEnqueueTime;
        public long lastUpdatedElapsed;

        private UidStats(int uid) {
            this.uid = uid;
        }

        private void dumpLocked(IndentingPrintWriter pw, final long nowElapsed) {
            pw.print("UidStats{");
            pw.print("uid", uid);
            pw.print("#run", numRunning);
            pw.print("#readyWithConn", numReadyWithConnectivity);
            pw.print("#netAvail", numRequestedNetworkAvailable);
            pw.print("#EJs", numEJs);
            pw.print("#reg", numRegular);
            pw.print("earliestEnqueue", earliestEnqueueTime);
            pw.print("earliestEJEnqueue", earliestEJEnqueueTime);
            pw.print("updated=");
            TimeUtils.formatDuration(lastUpdatedElapsed - nowElapsed, pw);
            pw.println("}");
        }
    }

    @GuardedBy("mLock")
    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {
        final long nowElapsed = sElapsedRealtimeClock.millis();

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
        pw.println();

        pw.println("Current default network callbacks:");
        pw.increaseIndent();
        for (int i = 0; i < mCurrentDefaultNetworkCallbacks.size(); i++) {
            mCurrentDefaultNetworkCallbacks.valueAt(i).dumpLocked(pw);
        }
        pw.decreaseIndent();
        pw.println();

        pw.println("UID Pecking Order:");
        pw.increaseIndent();
        for (int i = 0; i < mSortedStats.size(); ++i) {
            pw.print(i);
            pw.print(": ");
            mSortedStats.get(i).dumpLocked(pw, nowElapsed);
        }
        pw.decreaseIndent();
        pw.println();

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
