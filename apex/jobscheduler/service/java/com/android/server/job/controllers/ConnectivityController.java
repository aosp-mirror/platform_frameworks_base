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
import static android.net.NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
import static android.net.NetworkCapabilities.TRANSPORT_SATELLITE;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import static com.android.server.job.JobSchedulerService.RESTRICTED_INDEX;
import static com.android.server.job.JobSchedulerService.sElapsedRealtimeClock;
import static com.android.server.job.Flags.FLAG_RELAX_PREFETCH_CONNECTIVITY_CONSTRAINT_ONLY_ON_CHARGER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.job.JobInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.INetworkPolicyListener;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkPolicyManager;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pools;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.AppSchedulingModuleThread;
import com.android.server.LocalServices;
import com.android.server.job.Flags;
import com.android.server.job.JobSchedulerService;
import com.android.server.job.JobSchedulerService.Constants;
import com.android.server.job.StateControllerProto;
import com.android.server.net.NetworkPolicyManagerInternal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    public static final long UNKNOWN_TIME = -1L;

    // The networking stack has a hard limit so we can't make this configurable.
    private static final int MAX_NETWORK_CALLBACKS = 125;
    /**
     * Minimum amount of time that should have elapsed before we'll update a {@link UidStats}
     * instance.
     */
    private static final long MIN_STATS_UPDATE_INTERVAL_MS = 30_000L;
    private static final long MIN_ADJUST_CALLBACK_INTERVAL_MS = 1_000L;

    private static final int UNBYPASSABLE_BG_BLOCKED_REASONS =
            ~ConnectivityManager.BLOCKED_REASON_NONE;
    private static final int UNBYPASSABLE_EJ_BLOCKED_REASONS =
            ~(ConnectivityManager.BLOCKED_REASON_APP_STANDBY
                    | ConnectivityManager.BLOCKED_REASON_BATTERY_SAVER
                    | ConnectivityManager.BLOCKED_REASON_DOZE);
    private static final int UNBYPASSABLE_UI_BLOCKED_REASONS =
            ~(ConnectivityManager.BLOCKED_REASON_APP_STANDBY
                    | ConnectivityManager.BLOCKED_REASON_BATTERY_SAVER
                    | ConnectivityManager.BLOCKED_REASON_DOZE
                    | ConnectivityManager.BLOCKED_METERED_REASON_DATA_SAVER
                    | ConnectivityManager.BLOCKED_METERED_REASON_USER_RESTRICTED);
    private static final int UNBYPASSABLE_FOREGROUND_BLOCKED_REASONS =
            ~(ConnectivityManager.BLOCKED_REASON_APP_STANDBY
                    | ConnectivityManager.BLOCKED_REASON_BATTERY_SAVER
                    | ConnectivityManager.BLOCKED_REASON_DOZE
                    | ConnectivityManager.BLOCKED_METERED_REASON_DATA_SAVER
                    | ConnectivityManager.BLOCKED_METERED_REASON_USER_RESTRICTED);

    @VisibleForTesting
    static final int TRANSPORT_AFFINITY_UNDEFINED = 0;
    @VisibleForTesting
    static final int TRANSPORT_AFFINITY_PREFER = 1;
    @VisibleForTesting
    static final int TRANSPORT_AFFINITY_AVOID = 2;
    /**
     * Set of affinities to different network transports. If a given network has multiple
     * transports, the avoided ones take priority --- a network with an avoided transport
     * should be avoided if possible, even if the network has preferred transports as well.
     */
    @VisibleForTesting
    static final SparseIntArray sNetworkTransportAffinities = new SparseIntArray();
    static {
        sNetworkTransportAffinities.put(TRANSPORT_CELLULAR, TRANSPORT_AFFINITY_AVOID);
        sNetworkTransportAffinities.put(TRANSPORT_ETHERNET, TRANSPORT_AFFINITY_PREFER);
        sNetworkTransportAffinities.put(TRANSPORT_SATELLITE, TRANSPORT_AFFINITY_AVOID);
        sNetworkTransportAffinities.put(TRANSPORT_WIFI, TRANSPORT_AFFINITY_PREFER);
    }

    private final CcConfig mCcConfig;
    private final ConnectivityManager mConnManager;
    private final NetworkPolicyManager mNetPolicyManager;
    private final NetworkPolicyManagerInternal mNetPolicyManagerInternal;
    private final FlexibilityController mFlexibilityController;

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
    private final ArrayMap<Network, CachedNetworkMetadata> mAvailableNetworks = new ArrayMap<>();

    @GuardedBy("mLock")
    @Nullable
    private Network mSystemDefaultNetwork;

    private final SparseArray<UidDefaultNetworkCallback> mCurrentDefaultNetworkCallbacks =
            new SparseArray<>();
    private final Comparator<UidStats> mUidStatsComparator = new Comparator<UidStats>() {
        private int prioritizeExistenceOver(int threshold, int v1, int v2) {
            // Check if they're both on the same side of the threshold.
            if ((v1 > threshold && v2 > threshold) || (v1 <= threshold && v2 <= threshold)) {
                return 0;
            }
            // They're on opposite sides of the threshold.
            if (v1 > threshold) {
                return -1;
            }
            return 1;
        }

        @Override
        public int compare(UidStats us1, UidStats us2) {
            // Prioritize a UID ahead of another based on:
            //   1. Already running connectivity jobs (so we don't drop the listener)
            //   2. Waiting connectivity jobs would be ready with connectivity
            //   3. An existing network satisfies a waiting connectivity job's requirements
            //   4. TOP proc state
            //   5. Existence of treat-as-UI UIJs (not just requested UIJs)
            //   6. Existence of treat-as-EJ EJs (not just requested EJs)
            //   7. FGS proc state
            //   8. UIJ enqueue time
            //   9. EJ enqueue time
            //   10. Any other important job priorities/proc states
            //   11. Enqueue time
            // TODO: maybe consider number of jobs
            // TODO: consider IMPORTANT_WHILE_FOREGROUND bit
            final int runningPriority = prioritizeExistenceOver(0,
                    us1.runningJobs.size(), us2.runningJobs.size());
            if (runningPriority != 0) {
                return runningPriority;
            }
            // Prioritize any UIDs that have jobs that would be ready ahead of UIDs that don't.
            final int readyWithConnPriority = prioritizeExistenceOver(0,
                    us1.numReadyWithConnectivity, us2.numReadyWithConnectivity);
            if (readyWithConnPriority != 0) {
                return readyWithConnPriority;
            }
            // They both have jobs that would be ready. Prioritize the UIDs whose requested
            // network is available ahead of UIDs that don't have their requested network available.
            final int reqAvailPriority = prioritizeExistenceOver(0,
                    us1.numRequestedNetworkAvailable, us2.numRequestedNetworkAvailable);
            if (reqAvailPriority != 0) {
                return reqAvailPriority;
            }
            // Prioritize the top app. If neither are top apps, then use a later prioritization
            // check.
            final int topPriority = prioritizeExistenceOver(JobInfo.BIAS_TOP_APP - 1,
                    us1.baseBias, us2.baseBias);
            if (topPriority != 0) {
                return topPriority;
            }
            // They're either both TOP or both not TOP. Prioritize the app that has runnable UIJs
            // pending.
            final int uijPriority = prioritizeExistenceOver(0, us1.numUIJs, us2.numUIJs);
            if (uijPriority != 0) {
                return uijPriority;
            }
            // Still equivalent. Prioritize the app that has runnable EJs pending.
            final int ejPriority = prioritizeExistenceOver(0, us1.numEJs, us2.numEJs);
            if (ejPriority != 0) {
                return ejPriority;
            }
            // They both have runnable EJs.
            // Prioritize an FGS+ app. If neither are FGS+ apps, then use a later prioritization
            // check.
            final int fgsPriority = prioritizeExistenceOver(JobInfo.BIAS_FOREGROUND_SERVICE - 1,
                    us1.baseBias, us2.baseBias);
            if (fgsPriority != 0) {
                return fgsPriority;
            }
            // Order them by UIJ enqueue time to help provide low UIJ latency.
            if (us1.earliestUIJEnqueueTime < us2.earliestUIJEnqueueTime) {
                return -1;
            } else if (us1.earliestUIJEnqueueTime > us2.earliestUIJEnqueueTime) {
                return 1;
            }
            // Order them by EJ enqueue time to help provide low EJ latency.
            if (us1.earliestEJEnqueueTime < us2.earliestEJEnqueueTime) {
                return -1;
            } else if (us1.earliestEJEnqueueTime > us2.earliestEJEnqueueTime) {
                return 1;
            }
            // Order by any latent important proc states.
            if (us1.baseBias != us2.baseBias) {
                return us2.baseBias - us1.baseBias;
            }
            // Order by enqueue time.
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
    @GuardedBy("mLock")
    private final SparseBooleanArray mBackgroundMeteredAllowed = new SparseBooleanArray();
    @GuardedBy("mLock")
    private long mLastCallbackAdjustmentTimeElapsed;
    @GuardedBy("mLock")
    private final SparseArray<CellSignalStrengthCallback> mSignalStrengths = new SparseArray<>();

    @GuardedBy("mLock")
    private long mLastAllJobUpdateTimeElapsed;

    private static final int MSG_ADJUST_CALLBACKS = 0;
    private static final int MSG_UPDATE_ALL_TRACKED_JOBS = 1;
    private static final int MSG_DATA_SAVER_TOGGLED = 2;
    private static final int MSG_UID_POLICIES_CHANGED = 3;
    private static final int MSG_PROCESS_ACTIVE_NETWORK = 4;

    private final Handler mHandler;

    public ConnectivityController(JobSchedulerService service,
            @NonNull FlexibilityController flexibilityController) {
        super(service);
        mHandler = new CcHandler(AppSchedulingModuleThread.get().getLooper());
        mCcConfig = new CcConfig();

        mConnManager = mContext.getSystemService(ConnectivityManager.class);
        mNetPolicyManager = mContext.getSystemService(NetworkPolicyManager.class);
        mNetPolicyManagerInternal = LocalServices.getService(NetworkPolicyManagerInternal.class);
        mFlexibilityController = flexibilityController;

        // We're interested in all network changes; internally we match these
        // network changes against the active network for each UID with jobs.
        final NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        mConnManager.registerNetworkCallback(request, mNetworkCallback);

        mNetPolicyManager.registerListener(mNetPolicyListener);

        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH)) {
            // For now, we don't have network affinities on watches.
            sNetworkTransportAffinities.clear();
        }
    }

    @Override
    public void startTrackingLocked() {
        if (Flags.batchConnectivityJobsPerNetwork()) {
            mConnManager.registerSystemDefaultNetworkCallback(mDefaultNetworkCallback, mHandler);
            mConnManager.addDefaultNetworkActiveListener(this);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        if (jobStatus.hasConnectivityConstraint()) {
            final UidStats uidStats =
                    getUidStats(jobStatus.getSourceUid(), jobStatus.getSourcePackageName(), false);
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
            final UidStats uidStats =
                    getUidStats(jobStatus.getSourceUid(), jobStatus.getSourcePackageName(), true);
            uidStats.runningJobs.add(jobStatus);
        }
    }

    @GuardedBy("mLock")
    @Override
    public void unprepareFromExecutionLocked(JobStatus jobStatus) {
        if (jobStatus.hasConnectivityConstraint()) {
            final UidStats uidStats =
                    getUidStats(jobStatus.getSourceUid(), jobStatus.getSourcePackageName(), true);
            uidStats.runningJobs.remove(jobStatus);
            postAdjustCallbacks();
        }
    }

    @GuardedBy("mLock")
    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob) {
        if (jobStatus.clearTrackingController(JobStatus.TRACKING_CONNECTIVITY)) {
            ArraySet<JobStatus> jobs = mTrackedJobs.get(jobStatus.getSourceUid());
            if (jobs != null) {
                jobs.remove(jobStatus);
            }
            final UidStats uidStats =
                    getUidStats(jobStatus.getSourceUid(), jobStatus.getSourcePackageName(), true);
            uidStats.numReadyWithConnectivity--;
            uidStats.runningJobs.remove(jobStatus);
            maybeRevokeStandbyExceptionLocked(jobStatus);
            postAdjustCallbacks();
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

    @NonNull
    private UidStats getUidStats(int uid, String packageName, boolean shouldExist) {
        UidStats us = mUidStats.get(uid);
        if (us == null) {
            if (shouldExist) {
                // This shouldn't be happening. We create a UidStats object for the app when the
                // first job is scheduled in maybeStartTrackingJobLocked() and only ever drop the
                // object if the app is uninstalled or the user is removed. That means that if we
                // end up in this situation, onAppRemovedLocked() or onUserRemovedLocked() was
                // called before maybeStopTrackingJobLocked(), which is the reverse order of what
                // JobSchedulerService does (JSS calls maybeStopTrackingJobLocked() for all jobs
                // before calling onAppRemovedLocked() or onUserRemovedLocked()).
                Slog.wtfStack(TAG,
                        "UidStats was null after job for " + packageName + " was registered");
            }
            us = new UidStats(uid);
            mUidStats.append(uid, us);
        }
        return us;
    }

    /**
     * Returns true if the job's requested network is available. This DOES NOT necessarily mean
     * that the UID has been granted access to the network.
     */
    public boolean isNetworkAvailable(JobStatus job) {
        synchronized (mLock) {
            for (int i = 0; i < mAvailableNetworks.size(); ++i) {
                final Network network = mAvailableNetworks.keyAt(i);
                final CachedNetworkMetadata metadata = mAvailableNetworks.valueAt(i);
                final NetworkCapabilities capabilities =
                        metadata == null ? null : metadata.networkCapabilities;
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

        final UidStats uidStats =
                getUidStats(jobStatus.getSourceUid(), jobStatus.getSourcePackageName(), true);

        if (jobStatus.shouldTreatAsExpeditedJob() || jobStatus.shouldTreatAsUserInitiatedJob()) {
            if (!jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY)) {
                // Don't request a direct hole through any of the firewalls. Instead, mark the
                // constraint as satisfied if the network is available, and the job will get
                // through the firewalls once it starts running and the proc state is elevated.
                // This is the same behavior that FGS see.
                updateConstraintsSatisfied(jobStatus);
            }
            // Don't need to update constraint here if the network goes away. We'll do that as part
            // of regular processing when we're notified about the drop.
        } else if (((jobStatus.isRequestedExpeditedJob() && !jobStatus.shouldTreatAsExpeditedJob())
                || (jobStatus.getJob().isUserInitiated()
                        && !jobStatus.shouldTreatAsUserInitiatedJob()))
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
        if (mService.getPackagesForUidLocked(uid) == null) {
            // All packages in the UID have been removed. It's safe to remove things based on
            // UID alone.
            mTrackedJobs.delete(uid);
            mBackgroundMeteredAllowed.delete(uid);
            UidStats uidStats = mUidStats.removeReturnOld(uid);
            unregisterDefaultNetworkCallbackLocked(uid, sElapsedRealtimeClock.millis());
            mSortedStats.remove(uidStats);
            registerPendingUidCallbacksLocked();
        }
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
        for (int u = mBackgroundMeteredAllowed.size() - 1; u >= 0; --u) {
            final int uid = mBackgroundMeteredAllowed.keyAt(u);
            if (UserHandle.getUserId(uid) == userId) {
                mBackgroundMeteredAllowed.removeAt(u);
            }
        }
        postAdjustCallbacks();
    }

    @GuardedBy("mLock")
    @Override
    public void onUidBiasChangedLocked(int uid, int prevBias, int newBias) {
        UidStats uidStats = mUidStats.get(uid);
        if (uidStats != null && uidStats.baseBias != newBias) {
            uidStats.baseBias = newBias;
            postAdjustCallbacks();
        }
    }

    @Override
    @GuardedBy("mLock")
    public void onBatteryStateChangedLocked() {
        // Update job bookkeeping out of band to avoid blocking broadcast progress.
        mHandler.sendEmptyMessage(MSG_UPDATE_ALL_TRACKED_JOBS);
    }

    @Override
    public void prepareForUpdatedConstantsLocked() {
        mCcConfig.mShouldReprocessNetworkCapabilities = false;
        mCcConfig.mFlexIsEnabled = mFlexibilityController.isEnabled();
    }

    @Override
    public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
            @NonNull String key) {
        mCcConfig.processConstantLocked(properties, key);
    }

    @Override
    public void onConstantsUpdatedLocked() {
        if (mCcConfig.mShouldReprocessNetworkCapabilities
                || (mFlexibilityController.isEnabled() != mCcConfig.mFlexIsEnabled)) {
            AppSchedulingModuleThread.getHandler().post(() -> {
                boolean flexAffinitiesChanged = false;
                boolean flexAffinitiesSatisfied = false;
                synchronized (mLock) {
                    for (int i = 0; i < mAvailableNetworks.size(); ++i) {
                        CachedNetworkMetadata metadata = mAvailableNetworks.valueAt(i);
                        if (metadata == null) {
                            continue;
                        }
                        if (updateTransportAffinitySatisfaction(metadata)) {
                            // Something changed. Update jobs.
                            flexAffinitiesChanged = true;
                        }
                        flexAffinitiesSatisfied |= metadata.satisfiesTransportAffinities;
                    }
                    if (flexAffinitiesChanged) {
                        mFlexibilityController.setConstraintSatisfied(
                                JobStatus.CONSTRAINT_CONNECTIVITY,
                                flexAffinitiesSatisfied, sElapsedRealtimeClock.millis());
                        updateAllTrackedJobsLocked(false);
                    }
                }
            });
        }
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

        final long minimumChunkBytes = jobStatus.getMinimumNetworkChunkBytes();
        if (minimumChunkBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
            final long bandwidthDown = capabilities.getLinkDownstreamBandwidthKbps();
            // If we don't know the bandwidth, all we can do is hope the job finishes the minimum
            // chunk in time.
            if (bandwidthDown > 0) {
                final long estimatedMillis =
                        calculateTransferTimeMs(minimumChunkBytes, bandwidthDown);
                if (estimatedMillis > maxJobExecutionTimeMs) {
                    // If we'd never finish the minimum chunk before the timeout, we'd be insane!
                    Slog.w(TAG, "Minimum chunk " + minimumChunkBytes + " bytes over "
                            + bandwidthDown + " kbps network would take "
                            + estimatedMillis + "ms and job has "
                            + maxJobExecutionTimeMs + "ms to run; that's insane!");
                    return true;
                }
            }
            final long bandwidthUp = capabilities.getLinkUpstreamBandwidthKbps();
            // If we don't know the bandwidth, all we can do is hope the job finishes in time.
            if (bandwidthUp > 0) {
                final long estimatedMillis =
                        calculateTransferTimeMs(minimumChunkBytes, bandwidthUp);
                if (estimatedMillis > maxJobExecutionTimeMs) {
                    // If we'd never finish the minimum chunk before the timeout, we'd be insane!
                    Slog.w(TAG, "Minimum chunk " + minimumChunkBytes + " bytes over " + bandwidthUp
                            + " kbps network would take " + estimatedMillis + "ms and job has "
                            + maxJobExecutionTimeMs + "ms to run; that's insane!");
                    return true;
                }
            }
            return false;
        }

        // Minimum chunk size isn't defined. Check using the estimated upload/download sizes.

        if (capabilities.hasCapability(NET_CAPABILITY_NOT_METERED)
                && mService.isBatteryCharging()) {
            // We're charging and on an unmetered network. We don't have to be as conservative about
            // making sure the job will run within its max execution time. Let's just hope the app
            // supports interruptible work.
            return false;
        }


        final long downloadBytes = jobStatus.getEstimatedNetworkDownloadBytes();
        if (downloadBytes != JobInfo.NETWORK_BYTES_UNKNOWN) {
            final long bandwidth = capabilities.getLinkDownstreamBandwidthKbps();
            // If we don't know the bandwidth, all we can do is hope the job finishes in time.
            if (bandwidth > 0) {
                final long estimatedMillis = calculateTransferTimeMs(downloadBytes, bandwidth);
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
                final long estimatedMillis = calculateTransferTimeMs(uploadBytes, bandwidth);
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

    private boolean isMeteredAllowed(@NonNull JobStatus jobStatus,
            @NonNull NetworkCapabilities networkCapabilities) {
        // Network isn't metered. Usage is allowed. The rest of this method doesn't apply.
        if (networkCapabilities.hasCapability(NET_CAPABILITY_NOT_METERED)
                || networkCapabilities.hasCapability(NET_CAPABILITY_TEMPORARILY_NOT_METERED)) {
            return true;
        }

        final int uid = jobStatus.getSourceUid();
        final int procState = mService.getUidProcState(uid);
        final int capabilities = mService.getUidCapabilities(uid);
        // Jobs don't raise the proc state to anything better than IMPORTANT_FOREGROUND.
        // If the app is in a better state, see if it has the capability to use the metered network.
        final boolean currentStateAllows = procState != ActivityManager.PROCESS_STATE_UNKNOWN
                && procState < ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
                && NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground(
                        procState, capabilities);
        if (DEBUG) {
            Slog.d(TAG, "UID " + uid
                    + " current state allows metered network=" + currentStateAllows
                    + " procState=" + ActivityManager.procStateToString(procState)
                    + " capabilities=" + ActivityManager.getCapabilitiesSummary(capabilities));
        }
        if (currentStateAllows) {
            return true;
        }

        if ((jobStatus.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0) {
            final int expectedProcState = ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
            final int mergedCapabilities = capabilities
                    | NetworkPolicyManager.getDefaultProcessNetworkCapabilities(expectedProcState);
            final boolean wouldBeAllowed =
                    NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground(
                            expectedProcState, mergedCapabilities);
            if (DEBUG) {
                Slog.d(TAG, "UID " + uid
                        + " willBeForeground flag allows metered network=" + wouldBeAllowed
                        + " capabilities="
                        + ActivityManager.getCapabilitiesSummary(mergedCapabilities));
            }
            if (wouldBeAllowed) {
                return true;
            }
        }

        if (jobStatus.shouldTreatAsUserInitiatedJob()) {
            // Since the job is initiated by the user and will be visible to the user, it
            // should be able to run on metered networks, similar to FGS.
            // With user-initiated jobs, JobScheduler will request that the process
            // run at IMPORTANT_FOREGROUND process state
            // and get the USER_RESTRICTED_NETWORK process capability.
            final int expectedProcState = ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND;
            final int mergedCapabilities = capabilities
                    | ActivityManager.PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK
                    | NetworkPolicyManager.getDefaultProcessNetworkCapabilities(expectedProcState);
            final boolean wouldBeAllowed =
                    NetworkPolicyManager.isProcStateAllowedWhileOnRestrictBackground(
                            expectedProcState, mergedCapabilities);
            if (DEBUG) {
                Slog.d(TAG, "UID " + uid
                        + " UI job state allows metered network=" + wouldBeAllowed
                        + " capabilities=" + mergedCapabilities);
            }
            if (wouldBeAllowed) {
                return true;
            }
        }

        if (mBackgroundMeteredAllowed.indexOfKey(uid) >= 0) {
            return mBackgroundMeteredAllowed.get(uid);
        }

        final boolean allowed =
                mNetPolicyManager.getRestrictBackgroundStatus(uid)
                        != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
        if (DEBUG) {
            Slog.d(TAG, "UID " + uid + " allowed in data saver=" + allowed);
        }
        mBackgroundMeteredAllowed.put(uid, allowed);
        return allowed;
    }

    /**
     * Return the estimated amount of time this job will be transferring data,
     * based on the current network speed.
     */
    public long getEstimatedTransferTimeMs(JobStatus jobStatus) {
        final long downloadBytes = jobStatus.getEstimatedNetworkDownloadBytes();
        final long uploadBytes = jobStatus.getEstimatedNetworkUploadBytes();
        if (downloadBytes == JobInfo.NETWORK_BYTES_UNKNOWN
                && uploadBytes == JobInfo.NETWORK_BYTES_UNKNOWN) {
            return UNKNOWN_TIME;
        }
        if (jobStatus.network == null) {
            // This job doesn't have a network assigned.
            return UNKNOWN_TIME;
        }
        NetworkCapabilities capabilities = getNetworkCapabilities(jobStatus.network);
        if (capabilities == null) {
            return UNKNOWN_TIME;
        }
        final long estimatedDownloadTimeMs = calculateTransferTimeMs(downloadBytes,
                capabilities.getLinkDownstreamBandwidthKbps());
        final long estimatedUploadTimeMs = calculateTransferTimeMs(uploadBytes,
                capabilities.getLinkUpstreamBandwidthKbps());
        if (estimatedDownloadTimeMs == UNKNOWN_TIME) {
            return estimatedUploadTimeMs;
        } else if (estimatedUploadTimeMs == UNKNOWN_TIME) {
            return estimatedDownloadTimeMs;
        }
        return estimatedDownloadTimeMs + estimatedUploadTimeMs;
    }

    @VisibleForTesting
    static long calculateTransferTimeMs(long transferBytes, long bandwidthKbps) {
        if (transferBytes == JobInfo.NETWORK_BYTES_UNKNOWN || bandwidthKbps <= 0) {
            return UNKNOWN_TIME;
        }
        return (transferBytes * DateUtils.SECOND_IN_MILLIS)
                // Multiply by 1000 to convert kilobits to bits.
                // Divide by 8 to convert bits to bytes.
                / (bandwidthKbps * 1000 / 8);
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

    @GuardedBy("mLock")
    private boolean isStrongEnough(JobStatus jobStatus, NetworkCapabilities capabilities,
            Constants constants) {
        final int priority = jobStatus.getEffectivePriority();
        if (priority >= JobInfo.PRIORITY_HIGH) {
            return true;
        }
        if (!constants.CONN_USE_CELL_SIGNAL_STRENGTH) {
            return true;
        }
        if (!capabilities.hasTransport(TRANSPORT_CELLULAR)) {
            return true;
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            // VPNs may have multiple underlying networks and determining the correct strength
            // may not be straightforward.
            // Transmitting data over a VPN is generally more battery-expensive than on the
            // underlying network, so:
            // TODO: find a good way to reduce job use of VPN when it'll be very expensive
            // For now, we just pretend VPNs are always strong enough
            return true;
        }

        // VCNs running over WiFi will declare TRANSPORT_CELLULAR. When connected, a VCN will
        // most likely be the default network. We ideally don't want this to restrict jobs when the
        // VCN incorrectly declares the CELLULAR transport, but there's currently no way to
        // determine if a network is a VCN. When there is:
        // TODO(216127782): exclude VCN running over WiFi from this check

        int signalStrength = CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN;
        // Use the best strength found.
        final Set<Integer> subscriptionIds = capabilities.getSubscriptionIds();
        for (int subId : subscriptionIds) {
            CellSignalStrengthCallback callback = mSignalStrengths.get(subId);
            if (callback != null) {
                signalStrength = Math.max(signalStrength, callback.signalStrength);
            } else {
                Slog.wtf(TAG,
                        "Subscription ID " + subId + " doesn't have a registered callback");
            }
        }
        if (DEBUG) {
            Slog.d(TAG, "Cell signal strength for job=" + signalStrength);
        }
        // Treat "NONE_OR_UNKNOWN" as "NONE".
        if (signalStrength <= CellSignalStrength.SIGNAL_STRENGTH_POOR) {
            // If signal strength is poor, don't run MIN or LOW priority jobs, and only
            // run DEFAULT priority jobs if the device is charging or the job has been waiting
            // long enough.
            if (priority > JobInfo.PRIORITY_DEFAULT) {
                return true;
            }
            if (priority < JobInfo.PRIORITY_DEFAULT) {
                return false;
            }
            // DEFAULT job.
            return (mService.isBatteryCharging() && mService.isBatteryNotLow())
                    || jobStatus.getFractionRunTime() > constants.CONN_PREFETCH_RELAX_FRAC;
        }
        if (signalStrength <= CellSignalStrength.SIGNAL_STRENGTH_MODERATE) {
            // If signal strength is moderate, only run MIN priority jobs when the device
            // is charging, or the job is already running.
            if (priority >= JobInfo.PRIORITY_LOW) {
                return true;
            }
            // MIN job.
            if (mService.isBatteryCharging() && mService.isBatteryNotLow()) {
                return true;
            }
            final UidStats uidStats = getUidStats(
                    jobStatus.getSourceUid(), jobStatus.getSourcePackageName(), true);
            return uidStats.runningJobs.contains(jobStatus);
        }
        return true;
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
                && (!jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_WITHIN_QUOTA)
                || !jobStatus.isConstraintSatisfied(JobStatus.CONSTRAINT_TARE_WEALTH))) {
            final NetworkCapabilities.Builder builder =
                    copyCapabilities(jobStatus.getJob().getRequiredNetwork());
            builder.addCapability(NET_CAPABILITY_NOT_METERED);
            return builder.build().satisfiedByNetworkCapabilities(capabilities);
        } else {
            return jobStatus.getJob().getRequiredNetwork().canBeSatisfiedBy(capabilities);
        }
    }

    private boolean isRelaxedSatisfied(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        // Only consider doing this for unrestricted prefetching jobs
        if (!jobStatus.getJob().isPrefetch() || jobStatus.getStandbyBucket() == RESTRICTED_INDEX) {
            return false;
        }
        final long estDownloadBytes = jobStatus.getEstimatedNetworkDownloadBytes();
        if (estDownloadBytes <= 0) {
            // Need to at least know the estimated download bytes for a prefetch job.
            return false;
        }
        if (Flags.relaxPrefetchConnectivityConstraintOnlyOnCharger()) {
            // Since the constraint relaxation isn't required by the job, only do it when the
            // device is charging and the battery level is above the "low battery" threshold.
            if (!mService.isBatteryCharging() || !mService.isBatteryNotLow()) {
                return false;
            }
        }

        // See if we match after relaxing any unmetered request
        final NetworkCapabilities.Builder builder =
                copyCapabilities(jobStatus.getJob().getRequiredNetwork());
        builder.removeCapability(NET_CAPABILITY_NOT_METERED);
        if (builder.build().satisfiedByNetworkCapabilities(capabilities)
                && jobStatus.getFractionRunTime() > constants.CONN_PREFETCH_RELAX_FRAC) {
            final long opportunisticQuotaBytes =
                    mNetPolicyManagerInternal.getSubscriptionOpportunisticQuota(
                            network, NetworkPolicyManagerInternal.QUOTA_TYPE_JOBS);
            final long estUploadBytes = jobStatus.getEstimatedNetworkUploadBytes();
            final long estimatedBytes = estDownloadBytes
                    + (estUploadBytes == JobInfo.NETWORK_BYTES_UNKNOWN ? 0 : estUploadBytes);
            return opportunisticQuotaBytes >= estimatedBytes;
        }

        return false;
    }

    @VisibleForTesting
    boolean isSatisfied(JobStatus jobStatus, Network network,
            NetworkCapabilities capabilities, Constants constants) {
        // Zeroth, we gotta have a network to think about being satisfied
        if (network == null || capabilities == null) return false;

        if (!isUsable(capabilities)) return false;

        // First, are we insane?
        if (isInsane(jobStatus, network, capabilities, constants)) return false;

        // User-initiated jobs might make NetworkPolicyManager open up network access for
        // the whole UID. If network access is opened up just because of UI jobs, we want
        // to make sure that non-UI jobs don't run during that time,
        // so make sure the job can make use of the metered network at this time.
        if (!isMeteredAllowed(jobStatus, capabilities)) return false;

        // Second, is the network congested?
        if (isCongestionDelayed(jobStatus, network, capabilities, constants)) return false;

        if (!isStrongEnough(jobStatus, capabilities, constants)) return false;

        // Is the network a strict match?
        if (isStrictSatisfied(jobStatus, network, capabilities, constants)) return true;

        // Is the network a relaxed match?
        if (isRelaxedSatisfied(jobStatus, network, capabilities, constants)) return true;

        return false;
    }

    /**
     * Updates {@link CachedNetworkMetadata#satisfiesTransportAffinities} in the given
     * {@link CachedNetworkMetadata} object.
     * @return true if the satisfaction changed
     */
    private boolean updateTransportAffinitySatisfaction(
            @NonNull CachedNetworkMetadata cachedNetworkMetadata) {
        final boolean satisfiesAffinities =
                satisfiesTransportAffinities(cachedNetworkMetadata.networkCapabilities);
        if (cachedNetworkMetadata.satisfiesTransportAffinities != satisfiesAffinities) {
            cachedNetworkMetadata.satisfiesTransportAffinities = satisfiesAffinities;
            return true;
        }
        return false;
    }

    private boolean satisfiesTransportAffinities(@Nullable NetworkCapabilities capabilities) {
        if (!mFlexibilityController.isEnabled()) {
            return true;
        }
        if (capabilities == null) {
            Slog.wtf(TAG, "Network constraint satisfied with null capabilities");
            return !mCcConfig.AVOID_UNDEFINED_TRANSPORT_AFFINITY;
        }

        if (sNetworkTransportAffinities.size() == 0) {
            return !mCcConfig.AVOID_UNDEFINED_TRANSPORT_AFFINITY;
        }

        final int[] transports = capabilities.getTransportTypes();
        if (transports.length == 0) {
            return !mCcConfig.AVOID_UNDEFINED_TRANSPORT_AFFINITY;
        }

        for (int t : transports) {
            int affinity = sNetworkTransportAffinities.get(t, TRANSPORT_AFFINITY_UNDEFINED);
            if (DEBUG) {
                Slog.d(TAG,
                        "satisfiesTransportAffinities transport=" + t + " aff=" + affinity);
            }
            switch (affinity) {
                case TRANSPORT_AFFINITY_UNDEFINED:
                    if (mCcConfig.AVOID_UNDEFINED_TRANSPORT_AFFINITY) {
                        // Avoided transports take precedence.
                        // Return as soon as we encounter a transport to avoid.
                        return false;
                    }
                    break;
                case TRANSPORT_AFFINITY_PREFER:
                    // Nothing to do here. We like this transport.
                    break;
                case TRANSPORT_AFFINITY_AVOID:
                    // Avoided transports take precedence.
                    // Return as soon as we encounter a transport to avoid.
                    return false;
            }
        }

        // Didn't see any transport to avoid.
        return true;
    }

    @GuardedBy("mLock")
    private void maybeRegisterDefaultNetworkCallbackLocked(JobStatus jobStatus) {
        final int sourceUid = jobStatus.getSourceUid();
        if (mCurrentDefaultNetworkCallbacks.contains(sourceUid)) {
            return;
        }
        final UidStats uidStats =
                getUidStats(jobStatus.getSourceUid(), jobStatus.getSourcePackageName(), true);
        if (!mSortedStats.contains(uidStats)) {
            mSortedStats.add(uidStats);
        }
        if (mCurrentDefaultNetworkCallbacks.size() >= MAX_NETWORK_CALLBACKS) {
            postAdjustCallbacks();
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
            mConnManager.registerDefaultNetworkCallbackForUid(uidStats.uid, callback, mHandler);
        }
    }

    private void postAdjustCallbacks() {
        postAdjustCallbacks(0);
    }

    private void postAdjustCallbacks(long delayMs) {
        mHandler.sendEmptyMessageDelayed(MSG_ADJUST_CALLBACKS, delayMs);
    }

    @GuardedBy("mLock")
    private void maybeAdjustRegisteredCallbacksLocked() {
        mHandler.removeMessages(MSG_ADJUST_CALLBACKS);

        final int count = mUidStats.size();
        if (count == mCurrentDefaultNetworkCallbacks.size()) {
            // All of them are registered and there are no blocked UIDs.
            // No point evaluating all UIDs.
            return;
        }

        final long nowElapsed = sElapsedRealtimeClock.millis();
        if (nowElapsed - mLastCallbackAdjustmentTimeElapsed < MIN_ADJUST_CALLBACK_INTERVAL_MS) {
            postAdjustCallbacks(MIN_ADJUST_CALLBACK_INTERVAL_MS);
            return;
        }

        mLastCallbackAdjustmentTimeElapsed = nowElapsed;
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
                us.earliestUIJEnqueueTime = Long.MAX_VALUE;
                us.numReadyWithConnectivity = 0;
                us.numRequestedNetworkAvailable = 0;
                us.numRegular = 0;
                us.numEJs = 0;
                us.numUIJs = 0;

                for (int j = 0; j < jobs.size(); ++j) {
                    JobStatus job = jobs.valueAt(j);
                    if (wouldBeReadyWithConstraintLocked(job, JobStatus.CONSTRAINT_CONNECTIVITY)) {
                        us.numReadyWithConnectivity++;
                        if (isNetworkAvailable(job)) {
                            us.numRequestedNetworkAvailable++;
                        }
                        // Only use the enqueue time of jobs that would be ready to prevent apps
                        // from gaming the system (eg. by scheduling a job that requires all
                        // constraints and has a minimum latency of 6 months to always have the
                        // earliest enqueue time).
                        us.earliestEnqueueTime = Math.min(us.earliestEnqueueTime, job.enqueueTime);
                        if (job.shouldTreatAsExpeditedJob() || job.startedAsExpeditedJob) {
                            us.earliestEJEnqueueTime =
                                    Math.min(us.earliestEJEnqueueTime, job.enqueueTime);
                        } else if (job.shouldTreatAsUserInitiatedJob()) {
                            us.earliestUIJEnqueueTime =
                                    Math.min(us.earliestUIJEnqueueTime, job.enqueueTime);
                        }
                    }
                    if (job.shouldTreatAsExpeditedJob() || job.startedAsExpeditedJob) {
                        us.numEJs++;
                    } else if (job.shouldTreatAsUserInitiatedJob()) {
                        us.numUIJs++;
                    } else {
                        us.numRegular++;
                    }
                }

                us.lastUpdatedElapsed = nowElapsed;
            }
            mSortedStats.add(us);
        }

        mSortedStats.sort(mUidStatsComparator);

        final ArraySet<JobStatus> changedJobs = new ArraySet<>();
        // Iterate in reverse order to remove existing callbacks before adding new ones.
        for (int i = mSortedStats.size() - 1; i >= 0; --i) {
            UidStats us = mSortedStats.get(i);
            if (i >= MAX_NETWORK_CALLBACKS) {
                if (unregisterDefaultNetworkCallbackLocked(us.uid, nowElapsed)) {
                    changedJobs.addAll(mTrackedJobs.get(us.uid));
                }
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
                    mConnManager.registerDefaultNetworkCallbackForUid(
                            us.uid, defaultNetworkCallback, mHandler);
                }
            }
        }
        if (changedJobs.size() > 0) {
            mStateChangedListener.onControllerStateChanged(changedJobs);
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
    public NetworkCapabilities getNetworkCapabilities(@Nullable Network network) {
        final CachedNetworkMetadata metadata = getNetworkMetadata(network);
        return metadata == null ? null : metadata.networkCapabilities;
    }

    @Nullable
    private CachedNetworkMetadata getNetworkMetadata(@Nullable Network network) {
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

    @GuardedBy("mLock")
    @Nullable
    private Network getNetworkLocked(@NonNull JobStatus jobStatus) {
        final UidDefaultNetworkCallback defaultNetworkCallback =
                mCurrentDefaultNetworkCallbacks.get(jobStatus.getSourceUid());
        if (defaultNetworkCallback == null) {
            return null;
        }

        UidStats uidStats = mUidStats.get(jobStatus.getSourceUid());

        final int unbypassableBlockedReasons;
        // TOP will probably have fewer reasons, so we may not have to worry about returning
        // BG_BLOCKED for a TOP app. However, better safe than sorry.
        if (uidStats.baseBias >= JobInfo.BIAS_BOUND_FOREGROUND_SERVICE
                || (jobStatus.getFlags() & JobInfo.FLAG_WILL_BE_FOREGROUND) != 0) {
            if (DEBUG) {
                Slog.d(TAG, "Using FG bypass for " + jobStatus.getSourceUid());
            }
            unbypassableBlockedReasons = UNBYPASSABLE_FOREGROUND_BLOCKED_REASONS;
        } else if (jobStatus.shouldTreatAsUserInitiatedJob()) {
            if (DEBUG) {
                Slog.d(TAG, "Using UI bypass for " + jobStatus.getSourceUid());
            }
            unbypassableBlockedReasons = UNBYPASSABLE_UI_BLOCKED_REASONS;
        } else if (jobStatus.shouldTreatAsExpeditedJob() || jobStatus.startedAsExpeditedJob) {
            if (DEBUG) {
                Slog.d(TAG, "Using EJ bypass for " + jobStatus.getSourceUid());
            }
            unbypassableBlockedReasons = UNBYPASSABLE_EJ_BLOCKED_REASONS;
        } else {
            if (DEBUG) {
                Slog.d(TAG, "Using BG bypass for " + jobStatus.getSourceUid());
            }
            unbypassableBlockedReasons = UNBYPASSABLE_BG_BLOCKED_REASONS;
        }

        return (unbypassableBlockedReasons & defaultNetworkCallback.mBlockedReasons) == 0
                ? defaultNetworkCallback.mDefaultNetwork : null;
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus) {
        final long nowElapsed = sElapsedRealtimeClock.millis();
        final UidDefaultNetworkCallback defaultNetworkCallback =
                mCurrentDefaultNetworkCallbacks.get(jobStatus.getSourceUid());
        if (defaultNetworkCallback == null) {
            maybeRegisterDefaultNetworkCallbackLocked(jobStatus);
            return updateConstraintsSatisfied(jobStatus, nowElapsed, null, null);
        }
        final Network network = getNetworkLocked(jobStatus);
        final CachedNetworkMetadata networkMetadata = getNetworkMetadata(network);
        return updateConstraintsSatisfied(jobStatus, nowElapsed, network, networkMetadata);
    }

    private boolean updateConstraintsSatisfied(JobStatus jobStatus, final long nowElapsed,
            Network network, @Nullable CachedNetworkMetadata networkMetadata) {
        // TODO: consider matching against non-default networks

        final NetworkCapabilities capabilities =
                networkMetadata == null ? null : networkMetadata.networkCapabilities;
        final boolean satisfied = isSatisfied(jobStatus, network, capabilities, mConstants);

        if (!satisfied && jobStatus.network != null
                && mService.isCurrentlyRunningLocked(jobStatus)
                && isSatisfied(jobStatus, jobStatus.network,
                        getNetworkCapabilities(jobStatus.network), mConstants)) {
            // A new network became available for a currently running job
            // (and most likely became the default network for the app),
            // but it doesn't yet satisfy the requested constraints and the old network
            // is still available and satisfies the constraints. Don't change the network
            // given to the job for now and let it keep running. We will re-evaluate when
            // the capabilities or connection state of either network change.
            if (DEBUG) {
                Slog.i(TAG, "Not reassigning network from " + jobStatus.network
                        + " to " + network + " for running job " + jobStatus);
            }
            return false;
        }

        final boolean changed = jobStatus.setConnectivityConstraintSatisfied(nowElapsed, satisfied);

        jobStatus.setTransportAffinitiesSatisfied(satisfied && networkMetadata != null
                && networkMetadata.satisfiesTransportAffinities);
        if (jobStatus.canApplyTransportAffinities()) {
            // Only modify the flex constraint if the job actually needs it.
            jobStatus.setFlexibilityConstraintSatisfied(nowElapsed,
                    mFlexibilityController.isFlexibilitySatisfiedLocked(jobStatus));
        }

        // Try to handle network transitions in a reasonable manner. See the lengthy note inside
        // UidDefaultNetworkCallback for more details.
        if (!changed && satisfied && jobStatus.network != null
                && mService.isCurrentlyRunningLocked(jobStatus)) {
            // The job's connectivity constraint continues to be satisfied even though the network
            // has changed.
            // Inform the job of the new network so that it can attempt to switch over. This is the
            // ideal behavior for certain transitions such as going from a metered network to an
            // unmetered network.
            mStateChangedListener.onNetworkChanged(jobStatus, network);
        }

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

    @GuardedBy("mLock")
    private void updateAllTrackedJobsLocked(boolean allowThrottle) {
        if (allowThrottle) {
            final long throttleTimeLeftMs =
                    (mLastAllJobUpdateTimeElapsed + mConstants.CONN_UPDATE_ALL_JOBS_MIN_INTERVAL_MS)
                            - sElapsedRealtimeClock.millis();
            if (throttleTimeLeftMs > 0) {
                Message msg = mHandler.obtainMessage(MSG_UPDATE_ALL_TRACKED_JOBS, 1, 0);
                mHandler.sendMessageDelayed(msg, throttleTimeLeftMs);
                return;
            }
        }

        mHandler.removeMessages(MSG_UPDATE_ALL_TRACKED_JOBS);
        updateTrackedJobsLocked(-1, null);
        mLastAllJobUpdateTimeElapsed = sElapsedRealtimeClock.millis();
    }

    /**
     * Update any jobs tracked by this controller that match given filters.
     *
     * @param filterUid     only update jobs belonging to this UID, or {@code -1} to
     *                      update all tracked jobs.
     * @param filterNetwork only update jobs that would use this
     *                      {@link Network}, or {@code null} to update all tracked jobs.
     */
    @GuardedBy("mLock")
    private void updateTrackedJobsLocked(int filterUid, @Nullable Network filterNetwork) {
        final ArraySet<JobStatus> changedJobs;
        if (filterUid == -1) {
            changedJobs = new ArraySet<>();
            for (int i = mTrackedJobs.size() - 1; i >= 0; i--) {
                if (updateTrackedJobsLocked(mTrackedJobs.valueAt(i), filterNetwork)) {
                    changedJobs.addAll(mTrackedJobs.valueAt(i));
                }
            }
        } else {
            if (updateTrackedJobsLocked(mTrackedJobs.get(filterUid), filterNetwork)) {
                changedJobs = mTrackedJobs.get(filterUid);
            } else {
                changedJobs = null;
            }
        }
        if (changedJobs != null && changedJobs.size() > 0) {
            mStateChangedListener.onControllerStateChanged(changedJobs);
        }
    }

    @GuardedBy("mLock")
    private boolean updateTrackedJobsLocked(ArraySet<JobStatus> jobs,
            @Nullable Network filterNetwork) {
        if (jobs == null || jobs.size() == 0) {
            return false;
        }

        UidDefaultNetworkCallback defaultNetworkCallback =
                mCurrentDefaultNetworkCallbacks.get(jobs.valueAt(0).getSourceUid());
        if (defaultNetworkCallback == null) {
            // This method is only called via a network callback object. That means something
            // changed about a general network characteristic (since we wouldn't be in this
            // situation if called from a UID_specific callback). The general network callback
            // will handle adjusting the per-UID callbacks, so nothing left to do here.
            return false;
        }

        final long nowElapsed = sElapsedRealtimeClock.millis();
        boolean changed = false;
        for (int i = jobs.size() - 1; i >= 0; i--) {
            final JobStatus js = jobs.valueAt(i);

            final Network net = getNetworkLocked(js);
            final boolean match = (filterNetwork == null
                    || Objects.equals(filterNetwork, net));

            // Update either when we have a network match, or when the
            // job hasn't yet been evaluated against the currently
            // active network; typically when we just lost a network.
            if (match || !Objects.equals(js.network, net)) {
                changed |= updateConstraintsSatisfied(js, nowElapsed, net, getNetworkMetadata(net));
            }
        }
        return changed;
    }

    /**
     * Returns {@code true} if the job's assigned network is active or otherwise considered to be
     * in a good state to run the job now.
     */
    @GuardedBy("mLock")
    public boolean isNetworkInStateForJobRunLocked(@NonNull JobStatus jobStatus) {
        if (jobStatus.network == null) {
            return false;
        }
        if (jobStatus.shouldTreatAsExpeditedJob() || jobStatus.shouldTreatAsUserInitiatedJob()
                || mService.getUidProcState(jobStatus.getSourceUid())
                        <= ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE) {
            // EJs, UIJs, and BFGS+ jobs should be able to activate the network.
            return true;
        }
        return isNetworkInStateForJobRunLocked(jobStatus.network);
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    boolean isNetworkInStateForJobRunLocked(@NonNull Network network) {
        if (!Flags.batchConnectivityJobsPerNetwork()) {
            // Active network batching isn't enabled. We don't care about the network state.
            return true;
        }

        CachedNetworkMetadata cachedNetworkMetadata = mAvailableNetworks.get(network);
        if (cachedNetworkMetadata == null) {
            return false;
        }

        final long nowElapsed = sElapsedRealtimeClock.millis();
        if (cachedNetworkMetadata.defaultNetworkActivationLastConfirmedTimeElapsed
                + mCcConfig.NETWORK_ACTIVATION_EXPIRATION_MS > nowElapsed) {
            // Network is still presumed to be active.
            return true;
        }

        final boolean inactiveForTooLong =
                cachedNetworkMetadata.capabilitiesFirstAcquiredTimeElapsed
                        < nowElapsed - mCcConfig.NETWORK_ACTIVATION_MAX_WAIT_TIME_MS
                && cachedNetworkMetadata.defaultNetworkActivationLastConfirmedTimeElapsed
                        < nowElapsed - mCcConfig.NETWORK_ACTIVATION_MAX_WAIT_TIME_MS;
        // We can only know the state of the system default network. If that's not available
        // or the network in question isn't the system default network,
        // then return true if we haven't gotten an active signal in a long time.
        if (mSystemDefaultNetwork == null) {
            return inactiveForTooLong;
        }

        if (!mSystemDefaultNetwork.equals(network)) {
            final NetworkCapabilities capabilities = cachedNetworkMetadata.networkCapabilities;
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                // VPNs won't have an active signal sent for them. Check their underlying networks
                // instead, prioritizing the system default if it's one of them.
                final List<Network> underlyingNetworks = capabilities.getUnderlyingNetworks();
                if (underlyingNetworks == null) {
                    return inactiveForTooLong;
                }

                if (underlyingNetworks.contains(mSystemDefaultNetwork)) {
                    if (DEBUG) {
                        Slog.i(TAG, "Substituting system default network "
                                + mSystemDefaultNetwork + " for VPN " + network);
                    }
                    return isNetworkInStateForJobRunLocked(mSystemDefaultNetwork);
                }

                for (int i = underlyingNetworks.size() - 1; i >= 0; --i) {
                    if (isNetworkInStateForJobRunLocked(underlyingNetworks.get(i))) {
                        return true;
                    }
                }
            }
            return inactiveForTooLong;
        }

        if (cachedNetworkMetadata.defaultNetworkActivationLastCheckTimeElapsed
                + mCcConfig.NETWORK_ACTIVATION_EXPIRATION_MS < nowElapsed) {
            // We haven't checked the state recently enough. Let's check if the network is active.
            // However, if we checked after the last confirmed active time and it wasn't active,
            // then the network is still not active (we would be told when it becomes active
            // via onNetworkActive()).
            if (cachedNetworkMetadata.defaultNetworkActivationLastCheckTimeElapsed
                    > cachedNetworkMetadata.defaultNetworkActivationLastConfirmedTimeElapsed) {
                return inactiveForTooLong;
            }
            // We need to explicitly check because there's no callback telling us when the network
            // leaves the high power state.
            cachedNetworkMetadata.defaultNetworkActivationLastCheckTimeElapsed = nowElapsed;
            final boolean isActive = mConnManager.isDefaultNetworkActive();
            if (isActive) {
                cachedNetworkMetadata.defaultNetworkActivationLastConfirmedTimeElapsed = nowElapsed;
                return true;
            }
            return inactiveForTooLong;
        }

        // We checked the state recently enough, but the network wasn't active. Assume it still
        // isn't active.
        return false;
    }

    /**
     * We know the network has just come up. We want to run any jobs that are ready.
     */
    @Override
    public void onNetworkActive() {
        synchronized (mLock) {
            if (mSystemDefaultNetwork == null) {
                Slog.wtf(TAG, "System default network is unknown but active");
                return;
            }

            CachedNetworkMetadata cachedNetworkMetadata =
                    mAvailableNetworks.get(mSystemDefaultNetwork);
            if (cachedNetworkMetadata == null) {
                Slog.wtf(TAG, "System default network capabilities are unknown but active");
                return;
            }

            // This method gets called on the system's main thread (not the
            // AppSchedulingModuleThread), so shift the processing work to a handler to avoid
            // blocking important operations on the main thread.
            cachedNetworkMetadata.defaultNetworkActivationLastConfirmedTimeElapsed =
                    cachedNetworkMetadata.defaultNetworkActivationLastCheckTimeElapsed =
                            sElapsedRealtimeClock.millis();
            mHandler.sendEmptyMessage(MSG_PROCESS_ACTIVE_NETWORK);
        }
    }

    /** NetworkCallback to track all network changes. */
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
                CachedNetworkMetadata cnm = mAvailableNetworks.get(network);
                if (cnm == null) {
                    cnm = new CachedNetworkMetadata();
                    cnm.capabilitiesFirstAcquiredTimeElapsed = sElapsedRealtimeClock.millis();
                    mAvailableNetworks.put(network, cnm);
                } else {
                    final NetworkCapabilities oldCaps = cnm.networkCapabilities;
                    if (oldCaps != null) {
                        maybeUnregisterSignalStrengthCallbackLocked(oldCaps);
                    }
                }
                cnm.networkCapabilities = capabilities;
                if (updateTransportAffinitySatisfaction(cnm)) {
                    maybeUpdateFlexConstraintLocked(cnm);
                }
                maybeRegisterSignalStrengthCallbackLocked(capabilities);
                updateTrackedJobsLocked(-1, network);
                postAdjustCallbacks();
            }
        }

        @Override
        public void onLost(Network network) {
            if (DEBUG) {
                Slog.v(TAG, "onLost: " + network);
            }
            synchronized (mLock) {
                final CachedNetworkMetadata cnm = mAvailableNetworks.remove(network);
                if (cnm != null) {
                    if (cnm.networkCapabilities != null) {
                        maybeUnregisterSignalStrengthCallbackLocked(cnm.networkCapabilities);
                    }
                    if (cnm.satisfiesTransportAffinities) {
                        maybeUpdateFlexConstraintLocked(null);
                    }
                }
                for (int u = 0; u < mCurrentDefaultNetworkCallbacks.size(); ++u) {
                    UidDefaultNetworkCallback callback = mCurrentDefaultNetworkCallbacks.valueAt(u);
                    if (Objects.equals(callback.mDefaultNetwork, network)) {
                        callback.mDefaultNetwork = null;
                    }
                }
                updateTrackedJobsLocked(-1, network);
                postAdjustCallbacks();
            }
        }

        @GuardedBy("mLock")
        private void maybeRegisterSignalStrengthCallbackLocked(
                @NonNull NetworkCapabilities capabilities) {
            if (!capabilities.hasTransport(TRANSPORT_CELLULAR)) {
                return;
            }
            TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);
            final Set<Integer> subscriptionIds = capabilities.getSubscriptionIds();
            for (int subId : subscriptionIds) {
                if (mSignalStrengths.indexOfKey(subId) >= 0) {
                    continue;
                }
                TelephonyManager idTm = telephonyManager.createForSubscriptionId(subId);
                CellSignalStrengthCallback callback = new CellSignalStrengthCallback();
                idTm.registerTelephonyCallback(
                        AppSchedulingModuleThread.getExecutor(), callback);
                mSignalStrengths.put(subId, callback);

                final SignalStrength signalStrength = idTm.getSignalStrength();
                if (signalStrength != null) {
                    callback.signalStrength = signalStrength.getLevel();
                }
            }
        }

        @GuardedBy("mLock")
        private void maybeUnregisterSignalStrengthCallbackLocked(
                @NonNull NetworkCapabilities capabilities) {
            if (!capabilities.hasTransport(TRANSPORT_CELLULAR)) {
                return;
            }
            ArraySet<Integer> activeIds = new ArraySet<>();
            for (int i = 0, size = mAvailableNetworks.size(); i < size; ++i) {
                final CachedNetworkMetadata metadata = mAvailableNetworks.valueAt(i);
                if (metadata == null || metadata.networkCapabilities == null) {
                    continue;
                }
                if (metadata.networkCapabilities.hasTransport(TRANSPORT_CELLULAR)) {
                    activeIds.addAll(metadata.networkCapabilities.getSubscriptionIds());
                }
            }
            if (DEBUG) {
                Slog.d(TAG, "Active subscription IDs: " + activeIds);
            }
            TelephonyManager telephonyManager = mContext.getSystemService(TelephonyManager.class);
            Set<Integer> subscriptionIds = capabilities.getSubscriptionIds();
            for (int subId : subscriptionIds) {
                if (activeIds.contains(subId)) {
                    continue;
                }
                TelephonyManager idTm = telephonyManager.createForSubscriptionId(subId);
                CellSignalStrengthCallback callback = mSignalStrengths.removeReturnOld(subId);
                if (callback != null) {
                    idTm.unregisterTelephonyCallback(callback);
                } else {
                    Slog.wtf(TAG, "Callback for sub " + subId + " didn't exist?!?!");
                }
            }
        }

        /**
         * Maybe call {@link FlexibilityController#setConstraintSatisfied(int, boolean, long)}
         * if the network affinity state has changed.
         */
        @GuardedBy("mLock")
        private void maybeUpdateFlexConstraintLocked(
                @Nullable CachedNetworkMetadata cachedNetworkMetadata) {
            if (cachedNetworkMetadata != null
                    && cachedNetworkMetadata.satisfiesTransportAffinities) {
                mFlexibilityController.setConstraintSatisfied(JobStatus.CONSTRAINT_CONNECTIVITY,
                        true, sElapsedRealtimeClock.millis());
            } else {
                // This network doesn't satisfy transport affinities. Check if any other
                // available networks do satisfy the affinities before saying that the
                // transport affinity is no longer satisfied for flex.
                boolean isTransportAffinitySatisfied = false;
                for (int i = mAvailableNetworks.size() - 1; i >= 0; --i) {
                    final CachedNetworkMetadata cnm = mAvailableNetworks.valueAt(i);
                    if (cnm != null && cnm.satisfiesTransportAffinities) {
                        isTransportAffinitySatisfied = true;
                        break;
                    }
                }
                if (!isTransportAffinitySatisfied) {
                    mFlexibilityController.setConstraintSatisfied(
                            JobStatus.CONSTRAINT_CONNECTIVITY, false,
                            sElapsedRealtimeClock.millis());
                }
            }
        }
    };

    /** NetworkCallback to track only changes to the default network. */
    private final NetworkCallback mDefaultNetworkCallback = new NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            if (DEBUG) Slog.v(TAG, "systemDefault-onAvailable: " + network);
            synchronized (mLock) {
                mSystemDefaultNetwork = network;
            }
        }

        @Override
        public void onLost(Network network) {
            if (DEBUG) {
                Slog.v(TAG, "systemDefault-onLost: " + network);
            }
            synchronized (mLock) {
                if (network.equals(mSystemDefaultNetwork)) {
                    mSystemDefaultNetwork = null;
                }
            }
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
        public void onUidPoliciesChanged(int uid, int uidPolicies) {
            if (DEBUG) {
                Slog.v(TAG, "onUidPoliciesChanged: " + uid);
            }
            mHandler.obtainMessage(MSG_UID_POLICIES_CHANGED,
                    uid, mNetPolicyManager.getRestrictBackgroundStatus(uid))
                    .sendToTarget();
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
                    case MSG_ADJUST_CALLBACKS:
                        synchronized (mLock) {
                            maybeAdjustRegisteredCallbacksLocked();
                        }
                        break;

                    case MSG_UPDATE_ALL_TRACKED_JOBS:
                        synchronized (mLock) {
                            final boolean allowThrottle = msg.arg1 == 1;
                            updateAllTrackedJobsLocked(allowThrottle);
                        }
                        break;

                    case MSG_DATA_SAVER_TOGGLED:
                        removeMessages(MSG_DATA_SAVER_TOGGLED);
                        synchronized (mLock) {
                            mBackgroundMeteredAllowed.clear();
                            updateTrackedJobsLocked(-1, null);
                        }
                        break;

                    case MSG_UID_POLICIES_CHANGED:
                        final int uid = msg.arg1;
                        final boolean newAllowed =
                                msg.arg2 != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
                        synchronized (mLock) {
                            final boolean oldAllowed = mBackgroundMeteredAllowed.get(uid);
                            if (oldAllowed != newAllowed) {
                                mBackgroundMeteredAllowed.put(uid, newAllowed);
                                updateTrackedJobsLocked(uid, null);
                            }
                        }
                        break;

                    case MSG_PROCESS_ACTIVE_NETWORK:
                        removeMessages(MSG_PROCESS_ACTIVE_NETWORK);
                        synchronized (mLock) {
                            if (mSystemDefaultNetwork == null) {
                                break;
                            }
                            if (!Flags.batchConnectivityJobsPerNetwork()) {
                                break;
                            }
                            if (!isNetworkInStateForJobRunLocked(mSystemDefaultNetwork)) {
                                break;
                            }

                            final ArrayMap<Network, Boolean> includeInProcessing = new ArrayMap<>();
                            // Try to get the jobs to piggyback on the active network.
                            for (int u = mTrackedJobs.size() - 1; u >= 0; --u) {
                                final ArraySet<JobStatus> jobs = mTrackedJobs.valueAt(u);
                                for (int j = jobs.size() - 1; j >= 0; --j) {
                                    final JobStatus js = jobs.valueAt(j);
                                    if (!mSystemDefaultNetwork.equals(js.network)) {
                                        final NetworkCapabilities capabilities =
                                                getNetworkCapabilities(js.network);
                                        if (capabilities == null
                                                || !capabilities.hasTransport(
                                                NetworkCapabilities.TRANSPORT_VPN)) {
                                            includeInProcessing.put(js.network, Boolean.FALSE);
                                            continue;
                                        }
                                        if (includeInProcessing.containsKey(js.network)) {
                                            if (!includeInProcessing.get(js.network)) {
                                                continue;
                                            }
                                        } else {
                                            // VPNs most likely use the system default network as
                                            // their underlying network. If so, process the job.
                                            final List<Network> underlyingNetworks =
                                                    capabilities.getUnderlyingNetworks();
                                            final boolean isSystemDefaultInUnderlying =
                                                    underlyingNetworks != null
                                                            && underlyingNetworks.contains(
                                                                    mSystemDefaultNetwork);
                                            includeInProcessing.put(js.network,
                                                    isSystemDefaultInUnderlying);
                                            if (!isSystemDefaultInUnderlying) {
                                                continue;
                                            }
                                        }
                                    }
                                    if (js.isReady()) {
                                        if (DEBUG) {
                                            Slog.d(TAG, "Potentially running " + js
                                                    + " due to network activity");
                                        }
                                        mStateChangedListener.onRunJobNow(js);
                                    }
                                }
                            }
                        }
                        break;
                }
            }
        }
    }

    @VisibleForTesting
    class CcConfig {
        private boolean mFlexIsEnabled =
                FlexibilityController.FcConfig.DEFAULT_APPLIED_CONSTRAINTS != 0;
        private boolean mShouldReprocessNetworkCapabilities = false;

        /**
         * Prefix to use with all constant keys in order to "sub-namespace" the keys.
         * "conn_" is used for legacy reasons.
         */
        private static final String CC_CONFIG_PREFIX = "conn_";

        @VisibleForTesting
        static final String KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY =
                CC_CONFIG_PREFIX + "avoid_undefined_transport_affinity";
        private static final String KEY_NETWORK_ACTIVATION_EXPIRATION_MS =
                CC_CONFIG_PREFIX + "network_activation_expiration_ms";
        private static final String KEY_NETWORK_ACTIVATION_MAX_WAIT_TIME_MS =
                CC_CONFIG_PREFIX + "network_activation_max_wait_time_ms";

        private static final boolean DEFAULT_AVOID_UNDEFINED_TRANSPORT_AFFINITY = false;
        private static final long DEFAULT_NETWORK_ACTIVATION_EXPIRATION_MS = 10000L;
        private static final long DEFAULT_NETWORK_ACTIVATION_MAX_WAIT_TIME_MS =
                31 * MINUTE_IN_MILLIS;

        /**
         * If true, will avoid network transports that don't have an explicitly defined affinity.
         */
        public boolean AVOID_UNDEFINED_TRANSPORT_AFFINITY =
                DEFAULT_AVOID_UNDEFINED_TRANSPORT_AFFINITY;

        /**
         * Amount of time that needs to pass before needing to determine if the network is still
         * active.
         */
        public long NETWORK_ACTIVATION_EXPIRATION_MS = DEFAULT_NETWORK_ACTIVATION_EXPIRATION_MS;

        /**
         * Max time to wait since the network was last activated before deciding to allow jobs to
         * run even if the network isn't active
         */
        public long NETWORK_ACTIVATION_MAX_WAIT_TIME_MS =
                DEFAULT_NETWORK_ACTIVATION_MAX_WAIT_TIME_MS;

        @GuardedBy("mLock")
        public void processConstantLocked(@NonNull DeviceConfig.Properties properties,
                @NonNull String key) {
            switch (key) {
                case KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY:
                    final boolean avoid = properties.getBoolean(key,
                            DEFAULT_AVOID_UNDEFINED_TRANSPORT_AFFINITY);
                    if (AVOID_UNDEFINED_TRANSPORT_AFFINITY != avoid) {
                        AVOID_UNDEFINED_TRANSPORT_AFFINITY = avoid;
                        mShouldReprocessNetworkCapabilities = true;
                    }
                    break;
                case KEY_NETWORK_ACTIVATION_EXPIRATION_MS:
                    final long gracePeriodMs = properties.getLong(key,
                            DEFAULT_NETWORK_ACTIVATION_EXPIRATION_MS);
                    if (NETWORK_ACTIVATION_EXPIRATION_MS != gracePeriodMs) {
                        NETWORK_ACTIVATION_EXPIRATION_MS = gracePeriodMs;
                        // This doesn't need to trigger network capability reprocessing.
                    }
                    break;
                case KEY_NETWORK_ACTIVATION_MAX_WAIT_TIME_MS:
                    final long maxWaitMs = properties.getLong(key,
                            DEFAULT_NETWORK_ACTIVATION_MAX_WAIT_TIME_MS);
                    if (NETWORK_ACTIVATION_MAX_WAIT_TIME_MS != maxWaitMs) {
                        NETWORK_ACTIVATION_MAX_WAIT_TIME_MS = maxWaitMs;
                        mShouldReprocessNetworkCapabilities = true;
                    }
                    break;
            }
        }

        private void dump(IndentingPrintWriter pw) {
            pw.println();
            pw.print(ConnectivityController.class.getSimpleName());
            pw.println(":");
            pw.increaseIndent();

            pw.print(KEY_AVOID_UNDEFINED_TRANSPORT_AFFINITY,
                    AVOID_UNDEFINED_TRANSPORT_AFFINITY).println();
            pw.print(KEY_NETWORK_ACTIVATION_EXPIRATION_MS,
                    NETWORK_ACTIVATION_EXPIRATION_MS).println();
            pw.print(KEY_NETWORK_ACTIVATION_MAX_WAIT_TIME_MS,
                    NETWORK_ACTIVATION_MAX_WAIT_TIME_MS).println();

            pw.decreaseIndent();
        }
    }

    private class UidDefaultNetworkCallback extends NetworkCallback {
        private int mUid;
        @Nullable
        private Network mDefaultNetwork;
        private int mBlockedReasons;

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
        public void onBlockedStatusChanged(Network network, int blockedReasons) {
            if (DEBUG) {
                Slog.v(TAG, "default-onBlockedStatusChanged(" + mUid + "): "
                        + network + " -> " + blockedReasons);
            }
            if (mUid == UserHandle.USER_NULL) {
                return;
            }
            synchronized (mLock) {
                mDefaultNetwork = network;
                mBlockedReasons = blockedReasons;
                updateTrackedJobsLocked(mUid, network);
            }
        }

        // Network transitions have some complicated behavior that JS doesn't handle very well.
        //
        // * If the default network changes from A to B without A disconnecting, then we'll only
        // get onAvailable(B) (and the subsequent onBlockedStatusChanged() call). Since we get
        // the onBlockedStatusChanged() call, we re-evaluate the job, but keep it running
        // (assuming the new network satisfies constraints). The app continues to use the old
        // network (if they use the network object provided through JobParameters.getNetwork())
        // because we don't notify them of the default network change. If the old network later
        // stops satisfying requested constraints, then we have a problem. Depending on the order
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
                    updateTrackedJobsLocked(mUid, network);
                    // Add a delay in case onAvailable()+onBlockedStatusChanged is called for a
                    // new network. If this onLost was called because the network is completely
                    // gone, the delay will hel make sure we don't have a short burst of adjusting
                    // callback calls.
                    postAdjustCallbacks(1000);
                }
            }
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
                pw.print(NetworkPolicyManager.blockedReasonsToString(mBlockedReasons));
                pw.print(")");
            }
            pw.println();
        }
    }

    private static class CachedNetworkMetadata {
        public NetworkCapabilities networkCapabilities;
        public boolean satisfiesTransportAffinities;
        /**
         * Track the first time ConnectivityController was informed about the capabilities of the
         * network after it became available.
         */
        public long capabilitiesFirstAcquiredTimeElapsed;
        public long defaultNetworkActivationLastCheckTimeElapsed;
        public long defaultNetworkActivationLastConfirmedTimeElapsed;

        public String toString() {
            return "CNM{"
                    + networkCapabilities.toString()
                    + ", satisfiesTransportAffinities=" + satisfiesTransportAffinities
                    + ", capabilitiesFirstAcquiredTimeElapsed="
                            + capabilitiesFirstAcquiredTimeElapsed
                    + ", defaultNetworkActivationLastCheckTimeElapsed="
                            + defaultNetworkActivationLastCheckTimeElapsed
                    + ", defaultNetworkActivationLastConfirmedTimeElapsed="
                            + defaultNetworkActivationLastConfirmedTimeElapsed
                    + "}";
        }
    }

    private static class UidStats {
        public final int uid;
        public int baseBias;
        public final ArraySet<JobStatus> runningJobs = new ArraySet<>();
        public int numReadyWithConnectivity;
        public int numRequestedNetworkAvailable;
        public int numEJs;
        public int numRegular;
        public int numUIJs;
        public long earliestEnqueueTime;
        public long earliestEJEnqueueTime;
        public long earliestUIJEnqueueTime;
        public long lastUpdatedElapsed;

        private UidStats(int uid) {
            this.uid = uid;
        }

        private void dumpLocked(IndentingPrintWriter pw, final long nowElapsed) {
            pw.print("UidStats{");
            pw.print("uid", uid);
            pw.print("pri", baseBias);
            pw.print("#run", runningJobs.size());
            pw.print("#readyWithConn", numReadyWithConnectivity);
            pw.print("#netAvail", numRequestedNetworkAvailable);
            pw.print("#EJs", numEJs);
            pw.print("#reg", numRegular);
            pw.print("earliestEnqueue", earliestEnqueueTime);
            pw.print("earliestEJEnqueue", earliestEJEnqueueTime);
            pw.print("earliestUIJEnqueue", earliestUIJEnqueueTime);
            pw.print("updated=");
            TimeUtils.formatDuration(lastUpdatedElapsed - nowElapsed, pw);
            pw.println("}");
        }
    }

    private class CellSignalStrengthCallback extends TelephonyCallback
            implements TelephonyCallback.SignalStrengthsListener {
        @GuardedBy("mLock")
        public int signalStrength = CellSignalStrength.SIGNAL_STRENGTH_GREAT;

        @Override
        public void onSignalStrengthsChanged(@NonNull SignalStrength signalStrength) {
            synchronized (mLock) {
                final int newSignalStrength = signalStrength.getLevel();
                if (DEBUG) {
                    Slog.d(TAG, "Signal strength changing from "
                            + this.signalStrength + " to " + newSignalStrength);
                    for (CellSignalStrength css : signalStrength.getCellSignalStrengths()) {
                        Slog.d(TAG, "CSS: " + css.getLevel() + " " + css);
                    }
                }
                if (this.signalStrength == newSignalStrength) {
                    // This happens a lot.
                    return;
                }
                this.signalStrength = newSignalStrength;
                // Update job bookkeeping out of band to avoid blocking callback progress.
                mHandler.obtainMessage(MSG_UPDATE_ALL_TRACKED_JOBS, 1, 0).sendToTarget();
            }
        }
    }

    @VisibleForTesting
    @NonNull
    CcConfig getCcConfig() {
        return mCcConfig;
    }

    @Override
    public void dumpConstants(IndentingPrintWriter pw) {
        mCcConfig.dump(pw);
    }

    @GuardedBy("mLock")
    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw,
            Predicate<JobStatus> predicate) {
        final long nowElapsed = sElapsedRealtimeClock.millis();

        pw.println("Aconfig flags:");
        pw.increaseIndent();
        pw.print(FLAG_RELAX_PREFETCH_CONNECTIVITY_CONSTRAINT_ONLY_ON_CHARGER,
                Flags.relaxPrefetchConnectivityConstraintOnlyOnCharger());
        pw.println();
        pw.decreaseIndent();
        pw.println();

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

        if (mSignalStrengths.size() > 0) {
            pw.println("Subscription ID signal strengths:");
            pw.increaseIndent();
            for (int i = 0; i < mSignalStrengths.size(); ++i) {
                pw.print(mSignalStrengths.keyAt(i));
                pw.print(": ");
                pw.println(mSignalStrengths.valueAt(i).signalStrength);
            }
            pw.decreaseIndent();
        } else {
            pw.println("No cached signal strengths");
        }
        pw.println();

        if (mBackgroundMeteredAllowed.size() > 0) {
            pw.print("Background metered allowed: ");
            pw.println(mBackgroundMeteredAllowed);
            pw.println();
        }

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
