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
 * limitations under the License.
 */

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.job.IJobScheduler;
import android.app.job.IUserVisibleJobObserver;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.app.job.JobSnapshot;
import android.app.job.JobWorkItem;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.RemoteException;
import android.util.ArrayMap;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Concrete implementation of the JobScheduler interface
 *
 * Note android.app.job is the better package to put this class, but we can't move it there
 * because that'd break robolectric. Grr.
 *
 * @hide
 */
public class JobSchedulerImpl extends JobScheduler {
    IJobScheduler mBinder;
    private final Context mContext;
    private final String mNamespace;

    public JobSchedulerImpl(@NonNull Context context, IJobScheduler binder) {
        this(context, binder, null);
    }

    private JobSchedulerImpl(@NonNull Context context, IJobScheduler binder,
            @Nullable String namespace) {
        mContext = context;
        mBinder = binder;
        mNamespace = namespace;
    }

    private JobSchedulerImpl(JobSchedulerImpl jsi, @Nullable String namespace) {
        this(jsi.mContext, jsi.mBinder, namespace);
    }

    @NonNull
    @Override
    public JobScheduler forNamespace(@NonNull String namespace) {
        namespace = sanitizeNamespace(namespace);
        if (namespace == null) {
            throw new NullPointerException("namespace cannot be null");
        }
        if (namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace cannot be empty");
        }
        return new JobSchedulerImpl(this, namespace);
    }

    @Nullable
    @Override
    public String getNamespace() {
        return mNamespace;
    }

    @Override
    public int schedule(JobInfo job) {
        try {
            return mBinder.schedule(mNamespace, job);
        } catch (RemoteException e) {
            return JobScheduler.RESULT_FAILURE;
        }
    }

    @Override
    public int enqueue(JobInfo job, JobWorkItem work) {
        try {
            return mBinder.enqueue(mNamespace, job, work);
        } catch (RemoteException e) {
            return JobScheduler.RESULT_FAILURE;
        }
    }

    @Override
    public int scheduleAsPackage(JobInfo job, String packageName, int userId, String tag) {
        try {
            return mBinder.scheduleAsPackage(mNamespace, job, packageName, userId, tag);
        } catch (RemoteException e) {
            return JobScheduler.RESULT_FAILURE;
        }
    }

    @Override
    public void cancel(int jobId) {
        try {
            mBinder.cancel(mNamespace, jobId);
        } catch (RemoteException e) {}
    }

    @Override
    public void cancelAll() {
        try {
            mBinder.cancelAllInNamespace(mNamespace);
        } catch (RemoteException e) {}
    }

    @Override
    public void cancelInAllNamespaces() {
        try {
            mBinder.cancelAll();
        } catch (RemoteException e) {}
    }

    @Override
    public List<JobInfo> getAllPendingJobs() {
        try {
            return mBinder.getAllPendingJobsInNamespace(mNamespace).getList();
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public Map<String, List<JobInfo>> getPendingJobsInAllNamespaces() {
        try {
            final Map<String, ParceledListSlice<JobInfo>> parceledList =
                    mBinder.getAllPendingJobs();
            final ArrayMap<String, List<JobInfo>> jobMap = new ArrayMap<>();
            final Set<String> keys = parceledList.keySet();
            for (String key : keys) {
                jobMap.put(key, parceledList.get(key).getList());
            }
            return jobMap;
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public JobInfo getPendingJob(int jobId) {
        try {
            return mBinder.getPendingJob(mNamespace, jobId);
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public int getPendingJobReason(int jobId) {
        try {
            return mBinder.getPendingJobReason(mNamespace, jobId);
        } catch (RemoteException e) {
            return PENDING_JOB_REASON_UNDEFINED;
        }
    }

    @Override
    public boolean canRunUserInitiatedJobs() {
        try {
            return mBinder.canRunUserInitiatedJobs(mContext.getOpPackageName());
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public boolean hasRunUserInitiatedJobsPermission(String packageName, int userId) {
        try {
            return mBinder.hasRunUserInitiatedJobsPermission(packageName, userId);
        } catch (RemoteException e) {
            return false;
        }
    }

    @Override
    public List<JobInfo> getStartedJobs() {
        try {
            return mBinder.getStartedJobs();
        } catch (RemoteException e) {
            return null;
        }
    }

    @Override
    public List<JobSnapshot> getAllJobSnapshots() {
        try {
            return mBinder.getAllJobSnapshots().getList();
        } catch (RemoteException e) {
            return null;
        }
    }

    @RequiresPermission(allOf = {
            android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    @Override
    public void registerUserVisibleJobObserver(@NonNull IUserVisibleJobObserver observer) {
        try {
            mBinder.registerUserVisibleJobObserver(observer);
        } catch (RemoteException e) {
        }
    }

    @RequiresPermission(allOf = {
            android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    @Override
    public void unregisterUserVisibleJobObserver(@NonNull IUserVisibleJobObserver observer) {
        try {
            mBinder.unregisterUserVisibleJobObserver(observer);
        } catch (RemoteException e) {
        }
    }

    @RequiresPermission(allOf = {
            android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    @Override
    public void notePendingUserRequestedAppStop(@NonNull String packageName, int userId,
            @Nullable String debugReason) {
        try {
            mBinder.notePendingUserRequestedAppStop(packageName, userId, debugReason);
        } catch (RemoteException e) {
        }
    }
}
