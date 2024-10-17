/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.BackgroundActivityStartController.BalVerdict;
import static com.android.server.wm.ActivityTaskSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS;

import android.app.ActivityManager;
import android.app.BackgroundStartPrivileges;
import android.app.IAppTask;
import android.app.IApplicationThread;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;

/**
 * An implementation of IAppTask, that allows an app to manage its own tasks via
 * {@link android.app.ActivityManager.AppTask}.  We keep track of the callingUid to ensure that
 * only the process that calls getAppTasks() can call the AppTask methods.
 */
class AppTaskImpl extends IAppTask.Stub {
    private static final String TAG = "AppTaskImpl";
    private final ActivityTaskManagerService mService;

    private final int mTaskId;
    private final int mCallingUid;

    public AppTaskImpl(ActivityTaskManagerService service, int taskId, int callingUid) {
        mService = service;
        mTaskId = taskId;
        mCallingUid = callingUid;
    }

    private void checkCallerOrSystemOrRoot() {
        if (mCallingUid != Binder.getCallingUid() && Process.SYSTEM_UID != Binder.getCallingUid()
                && Process.ROOT_UID != Binder.getCallingUid()) {
            throw new SecurityException("Caller " + mCallingUid
                    + " does not match caller of getAppTasks(): " + Binder.getCallingUid());
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            throw ActivityTaskManagerService.logAndRethrowRuntimeExceptionOnTransact(TAG, e);
        }
    }

    @Override
    public void finishAndRemoveTask() {
        checkCallerOrSystemOrRoot();

        synchronized (mService.mGlobalLock) {
            int origCallingUid = Binder.getCallingUid();
            int origCallingPid = Binder.getCallingPid();
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                // We remove the task from recents to preserve backwards
                if (!mService.mTaskSupervisor.removeTaskById(mTaskId, false,
                        REMOVE_FROM_RECENTS, "finish-and-remove-task", origCallingUid,
                        origCallingPid)) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }
    }

    @Override
    public ActivityManager.RecentTaskInfo getTaskInfo() {
        checkCallerOrSystemOrRoot();

        synchronized (mService.mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                Task task = mService.mRootWindowContainer.anyTaskForId(mTaskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
                return mService.getRecentTasks().createRecentTaskInfo(task,
                        false /* stripExtras */, true /* getTasksAllowed */);
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    @Override
    public void moveToFront(IApplicationThread appThread, String callingPackage) {
        checkCallerOrSystemOrRoot();
        // Will bring task to front if it already has a root activity.
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        mService.assertPackageMatchesCallingUid(callingPackage);
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService.mGlobalLock) {
                WindowProcessController callerApp = null;
                if (appThread != null) {
                    callerApp = mService.getProcessController(appThread);
                }
                final BackgroundActivityStartController balController =
                        mService.mTaskSupervisor.getBackgroundActivityLaunchController();
                BalVerdict balVerdict = balController.checkBackgroundActivityStart(
                        callingUid,
                        callingPid,
                        callingPackage,
                        -1,
                        -1,
                        callerApp,
                        null,
                        BackgroundStartPrivileges.NONE,
                        null,
                        null,
                        null);
                if (balVerdict.blocks() && !mService.isBackgroundActivityStartsEnabled()) {
                    Slog.w(TAG, "moveTaskToFront blocked: : " + balVerdict);
                    return;
                }
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "moveTaskToFront allowed: " + balVerdict);
                }
            }
            mService.mTaskSupervisor.startActivityFromRecents(callingPid, callingUid, mTaskId,
                    null /* options */);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public int startActivity(IBinder whoThread, String callingPackage, String callingFeatureId,
            Intent intent, String resolvedType, Bundle bOptions) {
        checkCallerOrSystemOrRoot();
        mService.assertPackageMatchesCallingUid(callingPackage);
        mService.mAmInternal.addCreatorToken(intent, callingPackage);

        int callingUser = UserHandle.getCallingUserId();
        Task task;
        IApplicationThread appThread;
        synchronized (mService.mGlobalLock) {
            task = mService.mRootWindowContainer.anyTaskForId(mTaskId,
                    MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
            if (task == null) {
                throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
            }
            appThread = IApplicationThread.Stub.asInterface(whoThread);
            if (appThread == null) {
                throw new IllegalArgumentException("Bad app thread " + appThread);
            }
        }

        return mService.getActivityStartController().obtainStarter(intent, "AppTaskImpl")
                .setCaller(appThread)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setResolvedType(resolvedType)
                .setActivityOptions(bOptions)
                .setUserId(callingUser)
                .setInTask(task)
                .execute();
    }

    @Override
    public void setExcludeFromRecents(boolean exclude) {
        checkCallerOrSystemOrRoot();

        synchronized (mService.mGlobalLock) {
            final long origId = Binder.clearCallingIdentity();
            try {
                Task task = mService.mRootWindowContainer.anyTaskForId(mTaskId,
                        MATCH_ATTACHED_TASK_OR_RECENT_TASKS);
                if (task == null) {
                    throw new IllegalArgumentException("Unable to find task ID " + mTaskId);
                }
                Intent intent = task.getBaseIntent();
                if (exclude) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                } else {
                    intent.setFlags(intent.getFlags()
                            & ~Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }
}
