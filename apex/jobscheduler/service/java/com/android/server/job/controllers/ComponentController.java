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
import android.app.AppGlobals;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArrayMap;
import android.util.proto.ProtoOutputStream;

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
                case Intent.ACTION_PACKAGE_CHANGED:
                    final Uri uri = intent.getData();
                    final String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
                    final String[] changedComponents = intent.getStringArrayExtra(
                            Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
                    if (pkg != null && changedComponents != null && changedComponents.length > 0) {
                        updateComponentStateForPackage(pkg);
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

    private final ComponentStateUpdateFunctor mComponentStateUpdateFunctor =
            new ComponentStateUpdateFunctor();

    public ComponentController(JobSchedulerService service) {
        super(service);

        final IntentFilter filter = new IntentFilter();
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
    public void maybeStartTrackingJobLocked(JobStatus jobStatus, JobStatus lastJob) {
        updateComponentEnabledStateLocked(jobStatus, null);
    }

    @Override
    public void maybeStopTrackingJobLocked(JobStatus jobStatus, JobStatus incomingJob,
            boolean forUpdate) {

    }

    @Nullable
    private ServiceInfo getServiceInfo(JobStatus jobStatus,
            @Nullable SparseArrayMap<ComponentName, ServiceInfo> cache) {
        final ComponentName cn = jobStatus.getServiceComponent();
        ServiceInfo si = null;
        if (cache != null) {
            si = cache.get(jobStatus.getUserId(), cn);
        }
        if (si == null) {
            try {
                si = AppGlobals.getPackageManager().getServiceInfo(
                        cn, PackageManager.MATCH_DIRECT_BOOT_AUTO, jobStatus.getUserId());
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
            if (cache != null) {
                cache.add(jobStatus.getUserId(), cn, si);
            }
        }
        return si;
    }

    private boolean updateComponentEnabledStateLocked(JobStatus jobStatus,
            @Nullable SparseArrayMap<ComponentName, ServiceInfo> cache) {
        final ServiceInfo service = getServiceInfo(jobStatus, cache);

        if (DEBUG && service == null) {
            Slog.v(TAG, jobStatus.toShortString() + " component not present");
        }
        final ServiceInfo ogService = jobStatus.serviceInfo;
        jobStatus.serviceInfo = service;
        return !Objects.equals(ogService, service);
    }

    private void updateComponentStateForPackage(final String pkg) {
        updateComponentStates(
                jobStatus -> jobStatus.getServiceComponent().getPackageName().equals(pkg));
    }

    private void updateComponentStateForUser(final int userId) {
        updateComponentStates(jobStatus -> {
            // Using user ID instead of source user ID because the service will run under the
            // user ID, not source user ID.
            return jobStatus.getUserId() == userId;
        });
    }

    private void updateComponentStates(@NonNull Predicate<JobStatus> filter) {
        synchronized (mLock) {
            mComponentStateUpdateFunctor.reset();
            mService.getJobStore().forEachJob(filter, mComponentStateUpdateFunctor);
            if (mComponentStateUpdateFunctor.mChanged) {
                mStateChangedListener.onControllerStateChanged();
            }
        }
    }

    final class ComponentStateUpdateFunctor implements Consumer<JobStatus> {
        boolean mChanged;
        final SparseArrayMap<ComponentName, ServiceInfo> mTempCache = new SparseArrayMap<>();

        @Override
        public void accept(JobStatus jobStatus) {
            mChanged |= updateComponentEnabledStateLocked(jobStatus, mTempCache);
        }

        private void reset() {
            mChanged = false;
            mTempCache.clear();
        }
    }

    @Override
    public void dumpControllerStateLocked(IndentingPrintWriter pw, Predicate<JobStatus> predicate) {

    }

    @Override
    public void dumpControllerStateLocked(ProtoOutputStream proto, long fieldId,
            Predicate<JobStatus> predicate) {

    }
}
