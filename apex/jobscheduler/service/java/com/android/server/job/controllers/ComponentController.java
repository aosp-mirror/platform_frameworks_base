/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.job.controllers;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.server.job.JobSchedulerService;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Controller that tracks changes in the service component's enabled state.
 */
public class ComponentController extends StateController {
    private static final String TAG = "JobScheduler.Component";
    private static final boolean DEBUG = JobSchedulerService.DEBUG
            || Log.isLoggable(TAG, Log.DEBUG);

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) {
                Slog.wtf(TAG, "Intent action was null");
                return;
            }
            switch (action) {
                case Intent.ACTION_PACKAGE_ADDED:
                    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                        // Only do this for app updates since new installs won't have any jobs
                        // scheduled.
                        final Uri uri = intent.getData();
                        final String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
                        if (pkg != null) {
                            final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                            final int userId = UserHandle.getUserId(pkgUid);
                            updateComponentStateForPackage(userId, pkg);
                        }
                    }
                    break;
                case Intent.ACTION_PACKAGE_CHANGED:
                    final Uri uri = intent.getData();
                    final String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
                    final String[] changedComponents = intent.getStringArrayExtra(
                            Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
                    if (pkg != null && changedComponents != null && changedComponents.length > 0) {
                        final int pkgUid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        final int userId = UserHandle.getUserId(pkgUid);
                        updateComponentStateForPackage(userId, pkg);
                    }
                    break;
                case Intent.ACTION_USER_UNLOCKED:
                case Intent.ACTION_USER_STOPPED:
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    updateComponentStateForUser(userId);
                    break;
            }
        }
    };

    /**
     * Cache containing the processName of the ServiceInfo (see {@link ServiceInfo#processName})
     * if the Service exists and is available.
     * {@code null} will be stored if the service is currently unavailable.
     */
    private final SparseArrayMap<ComponentName, String> mServiceProcessCache =
            new SparseArrayMap<>();

    private final ComponentStateUpdateFunctor mComponentStateUpdateFunctor =
            new ComponentStateUpdateFunctor();

    public ComponentController(JobSchedulerService service) {
        super(service);
    }

    @Override
    public void startTrackingLocked() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiverAsUser(
                mBroadcastReceiver, UserHandle.ALL, filter, null, null);
        final IntentFilter userFilter = new IntentFilter();
        userFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        userFilter.addAction(Intent.ACTION_USER_STOPPED);
        mContext.registerReceiverAsUser(
                mBroadcastReceiver, UserHandle.ALL, userFilter, null, null);
    }

    @Override
    @GuardedBy("mLock")
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        updateComponentEnabledStateLocked(jobStatus);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob) {
    }

    @Override
    @GuardedBy("mLock")
    public void onAppRemovedLocked(String packageName, int uid) {
        clearComponentsForPackageLocked(UserHandle.getUserId(uid), packageName);
    }

    @Override
    @GuardedBy("mLock")
    public void onUserRemovedLocked(int userId) {
        mServiceProcessCache.delete(userId);
    }

    @Nullable
    @GuardedBy("mLock")
    private String getServiceProcessLocked(JobStatus jobStatus) {
        final ComponentName service = jobStatus.getServiceComponent();
        final int userId = jobStatus.getUserId();
        if (mServiceProcessCache.contains(userId, service)) {
            // Return whatever is in the cache, even if it's null. When something changes, we
            // clear the cache.
            return mServiceProcessCache.get(userId, service);
        }

        ServiceInfo si;
        try {
            // createContextAsUser may potentially be expensive
            // TODO: cache user context or improve ContextImpl implementation if this becomes
            // a problem
            si = mContext.createContextAsUser(UserHandle.of(userId), 0)
                    .getPackageManager()
                    .getServiceInfo(service, PackageManager.MATCH_DIRECT_BOOT_AUTO);
        } catch (NameNotFoundException e) {
            if (mService.areUsersStartedLocked(jobStatus)) {
                // User is fully unlocked but PM still says the package doesn't exist.
                Slog.e(TAG, "Job exists for non-existent package: " + service.getPackageName());
            }
            // Write null to the cache so we don't keep querying PM.
            si = null;
        }
        final String processName = si == null ? null : si.processName;
        mServiceProcessCache.add(userId, service, processName);

        return processName;
    }

    @GuardedBy("mLock")
    private boolean updateComponentEnabledStateLocked(JobStatus jobStatus) {
        final String processName = getServiceProcessLocked(jobStatus);

        if (DEBUG && processName == null) {
            Slog.v(TAG, jobStatus.toShortString() + " component not present");
        }
        final String ogProcess = jobStatus.serviceProcessName;
        jobStatus.serviceProcessName = processName;
        return !Objects.equals(ogProcess, processName);
    }

    @GuardedBy("mLock")
    private void clearComponentsForPackageLocked(final int userId, final String pkg) {
        final int uIdx = mServiceProcessCache.indexOfKey(userId);
        for (int c = mServiceProcessCache.numElementsForKey(userId) - 1; c >= 0; --c) {
            final ComponentName cn = mServiceProcessCache.keyAt(uIdx, c);
            if (cn.getPackageName().equals(pkg)) {
                mServiceProcessCache.delete(userId, cn);
            }
        }
    }

    private void updateComponentStateForPackage(final int userId, final String pkg) {
        synchronized (mLock) {
            clearComponentsForPackageLocked(userId, pkg);
            updateComponentStatesLocked(jobStatus -> {
                // Using user ID instead of source user ID because the service will run under the
                // user ID, not source user ID.
                return jobStatus.getUserId() == userId
                        && jobStatus.getServiceComponent().getPackageName().equals(pkg);
            });
        }
    }

    private void updateComponentStateForUser(final int userId) {
        synchronized (mLock) {
            mServiceProcessCache.delete(userId);
            updateComponentStatesLocked(jobStatus -> {
                // Using user ID instead of source user ID because the service will run under the
                // user ID, not source user ID.
                return jobStatus.getUserId() == userId;
            });
        }
    }

    @GuardedBy("mLock")
    private void updateComponentStatesLocked(@NonNull Predicate<JobStatus> filter) {
        mComponentStateUpdateFunctor.reset();
        mService.getJobStore().forEachJob(filter, mComponentStateUpdateFunctor);
        if (mComponentStateUpdateFunctor.mChangedJobs.size() > 0) {
            mStateChangedListener.onControllerStateChanged(
                    mComponentStateUpdateFunctor.mChangedJobs);
        }
    }

    final class ComponentStateUpdateFunctor implements Consumer<JobStatus> {
        @GuardedBy("mLock")
        final ArraySet<JobStatus> mChangedJobs = new ArraySet<>();

        @Override
        @GuardedBy("mLock")
        public void accept(JobStatus jobStatus) {
            if (updateComponentEnabledStateLocked(jobStatus)) {
                mChangedJobs.add(jobStatus);
            }
        }

        @GuardedBy("mLock")
        private void reset() {
            mChangedJobs.clear();
        }
    }

    @Override
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {
        for (int u = 0; u < mServiceProcessCache.numMaps(); ++u) {
            final int userId = mServiceProcessCache.keyAt(u);
            for (int p = 0; p < mServiceProcessCache.numElementsForKey(userId); ++p) {
                final ComponentName componentName = mServiceProcessCache.keyAt(u, p);
                pw.print(userId);
                pw.print("-");
                pw.print(componentName);
                pw.print(": ");
                pw.print(mServiceProcessCache.valueAt(u, p));
                pw.println();
            }
        }
    }

    @Override
    @GuardedBy("mLock")
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {

    }
}
