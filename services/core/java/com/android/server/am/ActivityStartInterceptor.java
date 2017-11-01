/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.INTENT_SENDER_ACTIVITY;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.Intent.EXTRA_INTENT;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_TASK_ID;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.app.UnlaunchableAppActivity;
import com.android.server.LocalServices;

/**
 * A class that contains activity intercepting logic for {@link ActivityStarter#startActivityLocked}
 * It's initialized
 */
class ActivityStartInterceptor {

    private final ActivityManagerService mService;
    private UserManager mUserManager;
    private final ActivityStackSupervisor mSupervisor;

    /*
     * Per-intent states loaded from ActivityStarter than shouldn't be changed by any
     * interception routines.
     */
    private int mRealCallingPid;
    private int mRealCallingUid;
    private int mUserId;
    private int mStartFlags;
    private String mCallingPackage;

    /*
     * Per-intent states that were load from ActivityStarter and are subject to modifications
     * by the interception routines. After calling {@link #intercept} the caller should assign
     * these values back to {@link ActivityStarter#startActivityLocked}'s local variables.
     */
    Intent mIntent;
    int mCallingPid;
    int mCallingUid;
    ResolveInfo mRInfo;
    ActivityInfo mAInfo;
    String mResolvedType;
    TaskRecord mInTask;
    ActivityOptions mActivityOptions;

    ActivityStartInterceptor(ActivityManagerService service, ActivityStackSupervisor supervisor) {
        mService = service;
        mSupervisor = supervisor;
    }

    void setStates(int userId, int realCallingPid, int realCallingUid, int startFlags,
            String callingPackage) {
        mRealCallingPid = realCallingPid;
        mRealCallingUid = realCallingUid;
        mUserId = userId;
        mStartFlags = startFlags;
        mCallingPackage = callingPackage;
    }

    void intercept(Intent intent, ResolveInfo rInfo, ActivityInfo aInfo, String resolvedType,
            TaskRecord inTask, int callingPid, int callingUid, ActivityOptions activityOptions) {
        mUserManager = UserManager.get(mService.mContext);
        mIntent = intent;
        mCallingPid = callingPid;
        mCallingUid = callingUid;
        mRInfo = rInfo;
        mAInfo = aInfo;
        mResolvedType = resolvedType;
        mInTask = inTask;
        mActivityOptions = activityOptions;
        if (interceptSuspendPackageIfNeed()) {
            // Skip the rest of interceptions as the package is suspended by device admin so
            // no user action can undo this.
            return;
        }
        if (interceptQuietProfileIfNeeded()) {
            // If work profile is turned off, skip the work challenge since the profile can only
            // be unlocked when profile's user is running.
            return;
        }
        interceptWorkProfileChallengeIfNeeded();
    }

    private boolean interceptQuietProfileIfNeeded() {
        // Do not intercept if the user has not turned off the profile
        if (!mUserManager.isQuietModeEnabled(UserHandle.of(mUserId))) {
            return false;
        }
        IIntentSender target = mService.getIntentSenderLocked(
                INTENT_SENDER_ACTIVITY, mCallingPackage, mCallingUid, mUserId, null, null, 0,
                new Intent[] {mIntent}, new String[] {mResolvedType},
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT, null);

        mIntent = UnlaunchableAppActivity.createInQuietModeDialogIntent(mUserId,
                new IntentSender(target));
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptSuspendPackageIfNeed() {
        // Do not intercept if the admin did not suspend the package
        if (mAInfo == null || mAInfo.applicationInfo == null ||
                (mAInfo.applicationInfo.flags & FLAG_SUSPENDED) == 0) {
            return false;
        }
        DevicePolicyManagerInternal devicePolicyManager = LocalServices.getService(
                DevicePolicyManagerInternal.class);
        if (devicePolicyManager == null) {
            return false;
        }
        mIntent = devicePolicyManager.createShowAdminSupportIntent(mUserId, true);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        if (parent != null) {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id);
        } else {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId);
        }
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptWorkProfileChallengeIfNeeded() {
        final Intent interceptingIntent = interceptWithConfirmCredentialsIfNeeded(mIntent,
                mResolvedType, mAInfo, mCallingPackage, mUserId);
        if (interceptingIntent == null) {
            return false;
        }
        mIntent = interceptingIntent;
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        // If we are intercepting and there was a task, convert it into an extra for the
        // ConfirmCredentials intent and unassign it, as otherwise the task will move to
        // front even if ConfirmCredentials is cancelled.
        if (mInTask != null) {
            mIntent.putExtra(EXTRA_TASK_ID, mInTask.taskId);
            mInTask = null;
        }
        if (mActivityOptions == null) {
            mActivityOptions = ActivityOptions.makeBasic();
        }

        ActivityRecord homeActivityRecord = mSupervisor.getHomeActivity();
        if (homeActivityRecord != null && homeActivityRecord.getTask() != null) {
            // Showing credential confirmation activity in home task to avoid stopping multi-windowed
            // mode after showing the full-screen credential confirmation activity.
            mActivityOptions.setLaunchTaskId(homeActivityRecord.getTask().taskId);
        }

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    /**
     * Creates an intent to intercept the current activity start with Confirm Credentials if needed.
     *
     * @return The intercepting intent if needed.
     */
    private Intent interceptWithConfirmCredentialsIfNeeded(Intent intent, String resolvedType,
            ActivityInfo aInfo, String callingPackage, int userId) {
        if (!mService.mUserController.shouldConfirmCredentials(userId)) {
            return null;
        }
        // TODO(b/28935539): should allow certain activities to bypass work challenge
        final IIntentSender target = mService.getIntentSenderLocked(
                INTENT_SENDER_ACTIVITY, callingPackage,
                Binder.getCallingUid(), userId, null, null, 0, new Intent[]{ intent },
                new String[]{ resolvedType },
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT | FLAG_IMMUTABLE, null);
        final KeyguardManager km = (KeyguardManager) mService.mContext
                .getSystemService(KEYGUARD_SERVICE);
        final Intent newIntent = km.createConfirmDeviceCredentialIntent(null, null, userId);
        if (newIntent == null) {
            return null;
        }
        newIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
                FLAG_ACTIVITY_TASK_ON_HOME);
        newIntent.putExtra(EXTRA_PACKAGE_NAME, aInfo.packageName);
        newIntent.putExtra(EXTRA_INTENT, new IntentSender(target));
        return newIntent;
    }

}
