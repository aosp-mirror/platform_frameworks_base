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

package com.android.server.wm;

import static android.app.ActivityManager.INTENT_SENDER_ACTIVITY;
import static android.app.ActivityOptions.ANIM_OPEN_CROSS_PROFILE_APPS;
import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.app.admin.DevicePolicyManager.EXTRA_RESTRICTION;
import static android.app.admin.DevicePolicyManager.POLICY_SUSPEND_PACKAGES;
import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.Intent.EXTRA_INTENT;
import static android.content.Intent.EXTRA_PACKAGE_NAME;
import static android.content.Intent.EXTRA_TASK_ID;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.BlockedAppActivity;
import com.android.internal.app.HarmfulAppWarningActivity;
import com.android.internal.app.SuspendedAppActivity;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;

/**
 * A class that contains activity intercepting logic for {@link ActivityStarter#startActivityLocked}
 * It's initialized via setStates and interception occurs via the intercept method.
 *
 * Note that this class is instantiated when {@link ActivityManagerService} gets created so there
 * is no guarantee that other system services are already present.
 */
class ActivityStartInterceptor {

    private final ActivityTaskManagerService mService;
    private final ActivityTaskSupervisor mSupervisor;
    private final RootWindowContainer mRootWindowContainer;
    private final Context mServiceContext;

    // UserManager cannot be final as it's not ready when this class is instantiated during boot
    private UserManager mUserManager;

    /*
     * Per-intent states loaded from ActivityStarter than shouldn't be changed by any
     * interception routines.
     */
    private int mRealCallingPid;
    private int mRealCallingUid;
    private int mUserId;
    private int mStartFlags;
    private String mCallingPackage;
    private @Nullable String mCallingFeatureId;

    /*
     * Per-intent states that were load from ActivityStarter and are subject to modifications
     * by the interception routines. After calling {@link #intercept} the caller should assign
     * these values back to {@link ActivityStarter#startActivityLocked}'s local variables if
     * {@link #intercept} returns true.
     */
    Intent mIntent;
    int mCallingPid;
    int mCallingUid;
    ResolveInfo mRInfo;
    ActivityInfo mAInfo;
    String mResolvedType;
    Task mInTask;
    ActivityOptions mActivityOptions;

    ActivityStartInterceptor(
            ActivityTaskManagerService service, ActivityTaskSupervisor supervisor) {
        this(service, supervisor, service.mRootWindowContainer, service.mContext);
    }

    @VisibleForTesting
    ActivityStartInterceptor(ActivityTaskManagerService service, ActivityTaskSupervisor supervisor,
            RootWindowContainer root, Context context) {
        mService = service;
        mSupervisor = supervisor;
        mRootWindowContainer = root;
        mServiceContext = context;
    }

    /**
     * Effectively initialize the class before intercepting the start intent. The values set in this
     * method should not be changed during intercept.
     */
    void setStates(int userId, int realCallingPid, int realCallingUid, int startFlags,
            String callingPackage, @Nullable String callingFeatureId) {
        mRealCallingPid = realCallingPid;
        mRealCallingUid = realCallingUid;
        mUserId = userId;
        mStartFlags = startFlags;
        mCallingPackage = callingPackage;
        mCallingFeatureId = callingFeatureId;
    }

    private IntentSender createIntentSenderForOriginalIntent(int callingUid, int flags) {
        Bundle activityOptions = deferCrossProfileAppsAnimationIfNecessary();
        final IIntentSender target = mService.getIntentSenderLocked(
                INTENT_SENDER_ACTIVITY, mCallingPackage, mCallingFeatureId, callingUid, mUserId,
                null /*token*/, null /*resultCode*/, 0 /*requestCode*/,
                new Intent[] { mIntent }, new String[] { mResolvedType },
                flags, activityOptions);
        return new IntentSender(target);
    }

    /**
     * Intercept the launch intent based on various signals. If an interception happened the
     * internal variables get assigned and need to be read explicitly by the caller.
     *
     * @return true if an interception occurred
     */
    boolean intercept(Intent intent, ResolveInfo rInfo, ActivityInfo aInfo, String resolvedType,
            Task inTask, int callingPid, int callingUid, ActivityOptions activityOptions) {
        mUserManager = UserManager.get(mServiceContext);

        mIntent = intent;
        mCallingPid = callingPid;
        mCallingUid = callingUid;
        mRInfo = rInfo;
        mAInfo = aInfo;
        mResolvedType = resolvedType;
        mInTask = inTask;
        mActivityOptions = activityOptions;

        if (interceptQuietProfileIfNeeded()) {
            // If work profile is turned off, skip the work challenge since the profile can only
            // be unlocked when profile's user is running.
            return true;
        }
        if (interceptSuspendedPackageIfNeeded()) {
            // Skip the rest of interceptions as the package is suspended by device admin so
            // no user action can undo this.
            return true;
        }
        if (interceptLockTaskModeViolationPackageIfNeeded()) {
            return true;
        }
        if (interceptHarmfulAppIfNeeded()) {
            // If the app has a "harmful app" warning associated with it, we should ask to uninstall
            // before issuing the work challenge.
            return true;
        }
        return interceptLockedManagedProfileIfNeeded();
    }

    private boolean hasCrossProfileAnimation() {
        return mActivityOptions != null
                && mActivityOptions.getAnimationType() == ANIM_OPEN_CROSS_PROFILE_APPS;
    }

    /**
     * If the activity option is the {@link ActivityOptions#ANIM_OPEN_CROSS_PROFILE_APPS} one,
     * defer the animation until the original intent is started.
     *
     * @return the activity option used to start the original intent.
     */
    private Bundle deferCrossProfileAppsAnimationIfNecessary() {
        if (hasCrossProfileAnimation()) {
            mActivityOptions = null;
            return ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle();
        }
        return null;
    }

    private boolean interceptQuietProfileIfNeeded() {
        // Do not intercept if the user has not turned off the profile
        if (!mUserManager.isQuietModeEnabled(UserHandle.of(mUserId))) {
            return false;
        }

        IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT);

        mIntent = UnlaunchableAppActivity.createInQuietModeDialogIntent(mUserId, target);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id, 0, mRealCallingUid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptSuspendedByAdminPackage() {
        DevicePolicyManagerInternal devicePolicyManager = LocalServices
                .getService(DevicePolicyManagerInternal.class);
        if (devicePolicyManager == null) {
            return false;
        }
        mIntent = devicePolicyManager.createShowAdminSupportIntent(mUserId, true);
        mIntent.putExtra(EXTRA_RESTRICTION, POLICY_SUSPEND_PACKAGES);

        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        if (parent != null) {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id, 0,
                    mRealCallingUid);
        } else {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                    mRealCallingUid);
        }
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptSuspendedPackageIfNeeded() {
        // Do not intercept if the package is not suspended
        if (mAInfo == null || mAInfo.applicationInfo == null ||
                (mAInfo.applicationInfo.flags & FLAG_SUSPENDED) == 0) {
            return false;
        }
        final PackageManagerInternal pmi = mService.getPackageManagerInternalLocked();
        if (pmi == null) {
            return false;
        }
        final String suspendedPackage = mAInfo.applicationInfo.packageName;
        final String suspendingPackage = pmi.getSuspendingPackage(suspendedPackage, mUserId);
        if (PLATFORM_PACKAGE_NAME.equals(suspendingPackage)) {
            return interceptSuspendedByAdminPackage();
        }
        final SuspendDialogInfo dialogInfo = pmi.getSuspendedDialogInfo(suspendedPackage,
                suspendingPackage, mUserId);
        final Bundle crossProfileOptions = hasCrossProfileAnimation()
                ? ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle()
                : null;
        final IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_IMMUTABLE);
        mIntent = SuspendedAppActivity.createSuspendedAppInterceptIntent(suspendedPackage,
                suspendingPackage, dialogInfo, crossProfileOptions, target, mUserId);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0, mRealCallingUid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptLockTaskModeViolationPackageIfNeeded() {
        if (mAInfo == null || mAInfo.applicationInfo == null) {
            return false;
        }
        LockTaskController controller = mService.getLockTaskController();
        String packageName = mAInfo.applicationInfo.packageName;
        int lockTaskLaunchMode = ActivityRecord.getLockTaskLaunchMode(mAInfo, mActivityOptions);
        if (controller.isActivityAllowed(mUserId, packageName, lockTaskLaunchMode)) {
            return false;
        }
        mIntent = BlockedAppActivity.createIntent(mUserId, mAInfo.applicationInfo.packageName);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0, mRealCallingUid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptLockedManagedProfileIfNeeded() {
        final Intent interceptingIntent = interceptWithConfirmCredentialsIfNeeded(mAInfo, mUserId);
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
            mIntent.putExtra(EXTRA_TASK_ID, mInTask.mTaskId);
            mInTask = null;
        }
        if (mActivityOptions == null) {
            mActivityOptions = ActivityOptions.makeBasic();
        }

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id, 0, mRealCallingUid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    /**
     * Creates an intent to intercept the current activity start with Confirm Credentials if needed.
     *
     * @return The intercepting intent if needed.
     */
    private Intent interceptWithConfirmCredentialsIfNeeded(ActivityInfo aInfo, int userId) {
        if (!mService.mAmInternal.shouldConfirmCredentials(userId)) {
            return null;
        }
        // TODO(b/28935539): should allow certain activities to bypass work challenge
        final IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT | FLAG_IMMUTABLE);
        final KeyguardManager km = (KeyguardManager) mServiceContext
                .getSystemService(KEYGUARD_SERVICE);
        final Intent newIntent = km.createConfirmDeviceCredentialIntent(null, null, userId,
                true /* disallowBiometricsIfPolicyExists */);
        if (newIntent == null) {
            return null;
        }
        newIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
                FLAG_ACTIVITY_TASK_ON_HOME);
        newIntent.putExtra(EXTRA_PACKAGE_NAME, aInfo.packageName);
        newIntent.putExtra(EXTRA_INTENT, target);
        return newIntent;
    }

    private boolean interceptHarmfulAppIfNeeded() {
        CharSequence harmfulAppWarning;
        try {
            harmfulAppWarning = mService.getPackageManager()
                    .getHarmfulAppWarning(mAInfo.packageName, mUserId);
        } catch (RemoteException ex) {
            return false;
        }

        if (harmfulAppWarning == null) {
            return false;
        }

        final IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT | FLAG_IMMUTABLE);

        mIntent = HarmfulAppWarningActivity.createHarmfulAppWarningIntent(mServiceContext,
                mAInfo.packageName, target, harmfulAppWarning);

        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0, mRealCallingUid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }
}
