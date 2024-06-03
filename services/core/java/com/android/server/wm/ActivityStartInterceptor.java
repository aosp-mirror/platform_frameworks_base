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
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
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
import android.app.TaskInfo;
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
import android.content.pm.UserPackage;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.BlockedAppActivity;
import com.android.internal.app.HarmfulAppWarningActivity;
import com.android.internal.app.SuspendedAppActivity;
import com.android.internal.app.UnlaunchableAppActivity;
import com.android.server.LocalServices;
import com.android.server.am.ActivityManagerService;
import com.android.server.wm.ActivityInterceptorCallback.ActivityInterceptResult;

/**
 * A class that contains activity intercepting logic for {@link ActivityStarter#execute()}
 * It's initialized via setStates and interception occurs via the intercept method.
 *
 * Note that this class is instantiated when {@link ActivityManagerService} gets created so there
 * is no guarantee that other system services are already present.
 */
class ActivityStartInterceptor {
    private static final String TAG = "ActivityStartInterceptor";

    private final ActivityTaskManagerService mService;
    private final ActivityTaskSupervisor mSupervisor;
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
    TaskFragment mInTaskFragment;
    ActivityOptions mActivityOptions;

    /*
     * Note that this is just a hint of what the launch display area will be as it is
     * based only on the information at the early pre-interception stage of starting the
     * intent. The real launch display area calculated later may be different from this one.
     */
    TaskDisplayArea mPresumableLaunchDisplayArea;

    ActivityStartInterceptor(
            ActivityTaskManagerService service, ActivityTaskSupervisor supervisor) {
        this(service, supervisor, service.mContext);
    }

    @VisibleForTesting
    ActivityStartInterceptor(ActivityTaskManagerService service, ActivityTaskSupervisor supervisor,
            Context context) {
        mService = service;
        mSupervisor = supervisor;
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
        ActivityOptions activityOptions = deferCrossProfileAppsAnimationIfNecessary();
        activityOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        final TaskFragment taskFragment = getLaunchTaskFragment();
        // If the original intent is going to be embedded, try to forward the embedding TaskFragment
        // and its task id to embed back the original intent.
        if (taskFragment != null) {
            activityOptions.setLaunchTaskFragmentToken(taskFragment.getFragmentToken());
        }
        final IIntentSender target = mService.getIntentSenderLocked(
                INTENT_SENDER_ACTIVITY, mCallingPackage, mCallingFeatureId, callingUid, mUserId,
                null /*token*/, null /*resultCode*/, 0 /*requestCode*/,
                new Intent[] { mIntent }, new String[] { mResolvedType },
                flags, activityOptions.toBundle());
        return new IntentSender(target);
    }


    /**
     * A helper function to obtain the targeted {@link TaskFragment} during
     * {@link #intercept(Intent, ResolveInfo, ActivityInfo, String, Task, TaskFragment, int, int,
     * ActivityOptions, TaskDisplayArea)} if any.
     */
    @Nullable
    private TaskFragment getLaunchTaskFragment() {
        if (mInTaskFragment != null) {
            return mInTaskFragment;
        }
        if (mActivityOptions == null) {
            return null;
        }
        final IBinder taskFragToken = mActivityOptions.getLaunchTaskFragmentToken();
        if (taskFragToken == null) {
            return null;
        }
        return TaskFragment.fromTaskFragmentToken(taskFragToken, mService);
    }

    /**
     * Intercept the launch intent based on various signals. If an interception happened the
     * internal variables get assigned and need to be read explicitly by the caller.
     *
     * @return true if an interception occurred
     */
    boolean intercept(Intent intent, ResolveInfo rInfo, ActivityInfo aInfo, String resolvedType,
            Task inTask, TaskFragment inTaskFragment, int callingPid, int callingUid,
            ActivityOptions activityOptions, TaskDisplayArea presumableLaunchDisplayArea) {
        mUserManager = UserManager.get(mServiceContext);

        mIntent = intent;
        mCallingPid = callingPid;
        mCallingUid = callingUid;
        mRInfo = rInfo;
        mAInfo = aInfo;
        mResolvedType = resolvedType;
        mInTask = inTask;
        mInTaskFragment = inTaskFragment;
        mActivityOptions = activityOptions;
        mPresumableLaunchDisplayArea = presumableLaunchDisplayArea;

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
        if (interceptLockedProfileIfNeeded()) {
            return true;
        }
        if (interceptHomeIfNeeded()) {
            // Replace primary home intents directed at displays that do not support primary home
            // but support secondary home with the relevant secondary home activity.
            return true;
        }

        final SparseArray<ActivityInterceptorCallback> callbacks =
                mService.getActivityInterceptorCallbacks();
        final ActivityInterceptorCallback.ActivityInterceptorInfo interceptorInfo =
                getInterceptorInfo(null /* clearOptionsAnimation */);

        for (int i = 0; i < callbacks.size(); i++) {
            final ActivityInterceptorCallback callback = callbacks.valueAt(i);
            final ActivityInterceptResult interceptResult = callback.onInterceptActivityLaunch(
                    interceptorInfo);
            if (interceptResult == null) {
                continue;
            }
            mIntent = interceptResult.getIntent();
            mActivityOptions = interceptResult.getActivityOptions();
            mCallingPid = mRealCallingPid;
            mCallingUid = mRealCallingUid;
            if (interceptResult.isActivityResolved()) {
                return true;
            }
            mRInfo = mSupervisor.resolveIntent(mIntent, null, mUserId, 0,
                    mRealCallingUid, mRealCallingPid);
            mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags,
                    null /*profilerInfo*/);
            return true;
        }
        return false;
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
    private ActivityOptions deferCrossProfileAppsAnimationIfNecessary() {
        if (hasCrossProfileAnimation()) {
            mActivityOptions = null;
            return ActivityOptions.makeOpenCrossProfileAppsAnimation();
        }
        return ActivityOptions.makeBasic();
    }

    private boolean interceptQuietProfileIfNeeded() {
        // Do not intercept if the user has not turned off the profile
        if (!mUserManager.isQuietModeEnabled(UserHandle.of(mUserId))) {
            return false;
        }
        Slog.i(TAG, "Intent : " + mIntent + " intercepted for user: " + mUserId
                + " because quiet mode is enabled.");

        IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_CANCEL_CURRENT | FLAG_ONE_SHOT);

        mIntent = UnlaunchableAppActivity.createInQuietModeDialogIntent(mUserId, target, mRInfo);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id, 0,
                mRealCallingUid, mRealCallingPid);
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
                    mRealCallingUid, mRealCallingPid);
        } else {
            mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                    mRealCallingUid, mRealCallingPid);
        }
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptSuspendedPackageIfNeeded() {
        // Do not intercept if the package is not suspended
        if (!isPackageSuspended()) {
            return false;
        }
        final PackageManagerInternal pmi = mService.getPackageManagerInternalLocked();
        if (pmi == null) {
            return false;
        }
        final String suspendedPackage = mAInfo.applicationInfo.packageName;
        final UserPackage suspender = pmi.getSuspendingPackage(suspendedPackage, mUserId);
        if (suspender != null && PLATFORM_PACKAGE_NAME.equals(suspender.packageName)) {
            return interceptSuspendedByAdminPackage();
        }
        final SuspendDialogInfo dialogInfo = pmi.getSuspendedDialogInfo(suspendedPackage,
                suspender, mUserId);
        final Bundle crossProfileOptions = hasCrossProfileAnimation()
                ? ActivityOptions.makeOpenCrossProfileAppsAnimation().toBundle()
                : null;
        final IntentSender target = createIntentSenderForOriginalIntent(mCallingUid,
                FLAG_IMMUTABLE);
        mIntent = SuspendedAppActivity.createSuspendedAppInterceptIntent(suspendedPackage,
                suspender, dialogInfo, crossProfileOptions, target, mUserId);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                mRealCallingUid, mRealCallingPid);
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
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptLockedProfileIfNeeded() {
        final Intent interceptingIntent = interceptWithConfirmCredentialsIfNeeded(mAInfo, mUserId);
        if (interceptingIntent == null) {
            return false;
        }
        mIntent = interceptingIntent;
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;
        final TaskFragment taskFragment = getLaunchTaskFragment();
        // If we are intercepting and there was a task, convert it into an extra for the
        // ConfirmCredentials intent and unassign it, as otherwise the task will move to
        // front even if ConfirmCredentials is cancelled.
        if (mInTask != null) {
            mIntent.putExtra(EXTRA_TASK_ID, mInTask.mTaskId);
            mInTask = null;
        } else if (taskFragment != null) {
            // If the original intent is started to an embedded TaskFragment, append its parent task
            // id to extra. It is to embed back the original intent to the TaskFragment with the
            // same task.
            final Task parentTask = taskFragment.getTask();
            if (parentTask != null) {
                mIntent.putExtra(EXTRA_TASK_ID, parentTask.mTaskId);
            }
        }
        if (mActivityOptions == null) {
            mActivityOptions = ActivityOptions.makeBasic();
        }

        final UserInfo parent = mUserManager.getProfileParent(mUserId);
        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, parent.id, 0,
                mRealCallingUid, mRealCallingPid);
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
        if ((aInfo.flags & ActivityInfo.FLAG_SHOW_WHEN_LOCKED) != 0
                && (mUserManager.isUserUnlocked(userId) || aInfo.directBootAware)) {
            return null;
        }
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
        } catch (RemoteException | IllegalArgumentException ex) {
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

        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, null /*profilerInfo*/);
        return true;
    }

    private boolean interceptHomeIfNeeded() {
        if (mPresumableLaunchDisplayArea == null || mService.mRootWindowContainer == null) {
            return false;
        }
        if (!ActivityRecord.isHomeIntent(mIntent)) {
            return false;
        }
        if (!mIntent.hasCategory(Intent.CATEGORY_HOME)) {
            // Already a secondary home intent, leave it alone.
            return false;
        }
        if (mService.mRootWindowContainer.shouldPlacePrimaryHomeOnDisplay(
                mPresumableLaunchDisplayArea.getDisplayId())) {
            // Primary home can be launched to the display area.
            return false;
        }
        if (!mService.mRootWindowContainer.shouldPlaceSecondaryHomeOnDisplayArea(
                mPresumableLaunchDisplayArea)) {
            // Secondary home cannot be launched on the display area.
            return false;
        }

        // At this point we have a primary home intent for a display that does not support primary
        // home activity but it supports secondary home one. So replace it with secondary home.
        Pair<ActivityInfo, Intent> info = mService.mRootWindowContainer
                .resolveSecondaryHomeActivity(mUserId, mPresumableLaunchDisplayArea);
        mIntent = info.second;
        // The new task flag is needed because the home activity should already be in the root task
        // and should not be moved to the caller's task. Also, activities cannot change their type,
        // e.g. a standard activity cannot become a home activity.
        mIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        mCallingPid = mRealCallingPid;
        mCallingUid = mRealCallingUid;
        mResolvedType = null;

        mRInfo = mSupervisor.resolveIntent(mIntent, mResolvedType, mUserId, /* flags= */ 0,
                mRealCallingUid, mRealCallingPid);
        mAInfo = mSupervisor.resolveActivity(mIntent, mRInfo, mStartFlags, /*profilerInfo=*/ null);
        return true;
    }

    private boolean isPackageSuspended() {
        return mAInfo != null && mAInfo.applicationInfo != null
                && (mAInfo.applicationInfo.flags & FLAG_SUSPENDED) != 0;
    }

    /**
     * Called when an activity is successfully launched.
     */
    void onActivityLaunched(TaskInfo taskInfo, ActivityRecord r) {
        final SparseArray<ActivityInterceptorCallback> callbacks =
                mService.getActivityInterceptorCallbacks();
        final ActivityInterceptorCallback.ActivityInterceptorInfo info = getInterceptorInfo(() -> {
            synchronized (mService.mGlobalLock) {
                r.clearOptionsAnimationForSiblings();
            }
        });
        for (int i = 0; i < callbacks.size(); i++) {
            final ActivityInterceptorCallback callback = callbacks.valueAt(i);
            callback.onActivityLaunched(taskInfo, r.info, info);
        }
    }

    private ActivityInterceptorCallback.ActivityInterceptorInfo getInterceptorInfo(
            @Nullable Runnable clearOptionsAnimation) {
        return new ActivityInterceptorCallback.ActivityInterceptorInfo.Builder(mCallingUid,
                mCallingPid, mRealCallingUid, mRealCallingPid, mUserId, mIntent, mRInfo, mAInfo)
                .setResolvedType(mResolvedType)
                .setCallingPackage(mCallingPackage)
                .setCallingFeatureId(mCallingFeatureId)
                .setCheckedOptions(mActivityOptions)
                .setClearOptionsAnimationRunnable(clearOptionsAnimation)
                .build();
    }

}
