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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityManager.START_CANCELED;
import static android.app.ActivityManager.START_SUCCESS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.os.FactoryTest.FACTORY_TEST_LOW_LEVEL;

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.BackgroundStartPrivileges;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.view.RemoteAnimationAdapter;
import android.view.WindowManager;
import android.window.RemoteTransition;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.PendingIntentRecord;
import com.android.server.uri.NeededUriGrants;
import com.android.server.wm.ActivityStarter.DefaultFactory;
import com.android.server.wm.ActivityStarter.Factory;

import java.io.PrintWriter;
import java.util.List;

/**
 * Controller for delegating activity launches.
 *
 * This class' main objective is to take external activity start requests and prepare them into
 * a series of discrete activity launches that can be handled by an {@link ActivityStarter}. It is
 * also responsible for handling logic that happens around an activity launch, but doesn't
 * necessarily influence the activity start. Examples include power hint management, processing
 * through the pending activity list, and recording home activity launches.
 */
public class ActivityStartController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStartController" : TAG_ATM;

    private static final int DO_PENDING_ACTIVITY_LAUNCHES_MSG = 1;

    private final ActivityTaskManagerService mService;
    private final ActivityTaskSupervisor mSupervisor;

    /** Last home activity record we attempted to start. */
    private ActivityRecord mLastHomeActivityStartRecord;

    /** Temporary array to capture start activity results */
    private ActivityRecord[] tmpOutRecord = new ActivityRecord[1];

    /** The result of the last home activity we attempted to start. */
    private int mLastHomeActivityStartResult;

    private final Factory mFactory;

    private final PendingRemoteAnimationRegistry mPendingRemoteAnimationRegistry;

    boolean mCheckedForSetup = false;

    /** Whether an {@link ActivityStarter} is currently executing (starting an Activity). */
    private boolean mInExecution = false;

    private final BackgroundActivityStartController mBalController;

    /**
     * TODO(b/64750076): Capture information necessary for dump and
     * {@link #postStartActivityProcessingForLastStarter} rather than keeping the entire object
     * around
     */
    private ActivityStarter mLastStarter;

    ActivityStartController(ActivityTaskManagerService service) {
        this(service, service.mTaskSupervisor,
                new DefaultFactory(service, service.mTaskSupervisor,
                    new ActivityStartInterceptor(service, service.mTaskSupervisor)));
    }

    @VisibleForTesting
    ActivityStartController(ActivityTaskManagerService service, ActivityTaskSupervisor supervisor,
            Factory factory) {
        mService = service;
        mSupervisor = supervisor;
        mFactory = factory;
        mFactory.setController(this);
        mPendingRemoteAnimationRegistry = new PendingRemoteAnimationRegistry(service.mGlobalLock,
                service.mH);
        mBalController = new BackgroundActivityStartController(mService, mSupervisor);
    }

    /**
     * @return A starter to configure and execute starting an activity. It is valid until after
     *         {@link ActivityStarter#execute} is invoked. At that point, the starter should be
     *         considered invalid and no longer modified or used.
     */
    ActivityStarter obtainStarter(Intent intent, String reason) {
        return mFactory.obtain().setIntent(intent).setReason(reason);
    }

    void onExecutionStarted() {
        mInExecution = true;
    }

    boolean isInExecution() {
        return mInExecution;
    }
    void onExecutionComplete(ActivityStarter starter) {
        mInExecution = false;
        if (mLastStarter == null) {
            mLastStarter = mFactory.obtain();
        }

        mLastStarter.set(starter);
        mFactory.recycle(starter);
    }

    /**
     * TODO(b/64750076): usage of this doesn't seem right. We're making decisions based off the
     * last starter for an arbitrary task record. Re-evaluate whether we can remove.
     */
    void postStartActivityProcessingForLastStarter(ActivityRecord r, int result,
            Task targetRootTask) {
        if (mLastStarter == null) {
            return;
        }

        mLastStarter.postStartActivityProcessing(r, result, targetRootTask);
    }

    void startHomeActivity(Intent intent, ActivityInfo aInfo, String reason,
            TaskDisplayArea taskDisplayArea) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
        if (!ActivityRecord.isResolverActivity(aInfo.name)) {
            // The resolver activity shouldn't be put in root home task because when the
            // foreground is standard type activity, the resolver activity should be put on the
            // top of current foreground instead of bring root home task to front.
            options.setLaunchActivityType(ACTIVITY_TYPE_HOME);
        }
        final int displayId = taskDisplayArea.getDisplayId();
        options.setLaunchDisplayId(displayId);
        options.setLaunchTaskDisplayArea(taskDisplayArea.mRemoteToken
                .toWindowContainerToken());

        // The home activity will be started later, defer resuming to avoid unnecessary operations
        // (e.g. start home recursively) when creating root home task.
        mSupervisor.beginDeferResume();
        final Task rootHomeTask;
        try {
            // Make sure root home task exists on display area.
            rootHomeTask = taskDisplayArea.getOrCreateRootHomeTask(ON_TOP);
        } finally {
            mSupervisor.endDeferResume();
        }

        mLastHomeActivityStartResult = obtainStarter(intent, "startHomeActivity: " + reason)
                .setOutActivity(tmpOutRecord)
                .setCallingUid(0)
                .setActivityInfo(aInfo)
                .setActivityOptions(options.toBundle())
                .execute();
        mLastHomeActivityStartRecord = tmpOutRecord[0];
        if (rootHomeTask.mInResumeTopActivity) {
            // If we are in resume section already, home activity will be initialized, but not
            // resumed (to avoid recursive resume) and will stay that way until something pokes it
            // again. We need to schedule another resume.
            mSupervisor.scheduleResumeTopActivities();
        }
    }

    /**
     * Starts the "new version setup screen" if appropriate.
     */
    void startSetupActivity() {
        // Only do this once per boot.
        if (mCheckedForSetup) {
            return;
        }

        // We will show this screen if the current one is a different
        // version than the last one shown, and we are not running in
        // low-level factory test mode.
        final ContentResolver resolver = mService.mContext.getContentResolver();
        if (mService.mFactoryTest != FACTORY_TEST_LOW_LEVEL
                && Settings.Global.getInt(resolver, Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            mCheckedForSetup = true;

            // See if we should be showing the platform update setup UI.
            final Intent intent = new Intent(Intent.ACTION_UPGRADE_SETUP);
            final List<ResolveInfo> ris =
                    mService.mContext.getPackageManager().queryIntentActivities(intent,
                            PackageManager.MATCH_SYSTEM_ONLY | PackageManager.GET_META_DATA
                                    | ActivityManagerService.STOCK_PM_FLAGS);
            if (!ris.isEmpty()) {
                final ResolveInfo ri = ris.get(0);
                String vers = ri.activityInfo.metaData != null
                        ? ri.activityInfo.metaData.getString(Intent.METADATA_SETUP_VERSION)
                        : null;
                if (vers == null && ri.activityInfo.applicationInfo.metaData != null) {
                    vers = ri.activityInfo.applicationInfo.metaData.getString(
                            Intent.METADATA_SETUP_VERSION);
                }
                String lastVers = Settings.Secure.getStringForUser(
                        resolver, Settings.Secure.LAST_SETUP_SHOWN, resolver.getUserId());
                if (vers != null && !vers.equals(lastVers)) {
                    intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(new ComponentName(
                            ri.activityInfo.packageName, ri.activityInfo.name));
                    obtainStarter(intent, "startSetupActivity")
                            .setCallingUid(0)
                            .setActivityInfo(ri.activityInfo)
                            .execute();
                }
            }
        }
    }

    /**
     * If {@code validateIncomingUser} is true, check {@code targetUserId} against the real calling
     * user ID inferred from {@code realCallingUid}, then return the resolved user-id, taking into
     * account "current user", etc.
     *
     * If {@code validateIncomingUser} is false, it skips the above check, but instead
     * ensures {@code targetUserId} is a real user ID and not a special user ID such as
     * {@link android.os.UserHandle#USER_ALL}, etc.
     */
    int checkTargetUser(int targetUserId, boolean validateIncomingUser,
            int realCallingPid, int realCallingUid, String reason) {
        if (validateIncomingUser) {
            return mService.handleIncomingUser(
                    realCallingPid, realCallingUid, targetUserId, reason);
        } else {
            mService.mAmInternal.ensureNotSpecialUser(targetUserId);
            return targetUserId;
        }
    }

    final int startActivityInPackage(int uid, int realCallingPid, int realCallingUid,
            String callingPackage, @Nullable String callingFeatureId, Intent intent,
            String resolvedType, IBinder resultTo, String resultWho, int requestCode,
            int startFlags, SafeActivityOptions options, int userId, Task inTask, String reason,
            boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent,
            BackgroundStartPrivileges backgroundStartPrivileges) {

        userId = checkTargetUser(userId, validateIncomingUser, realCallingPid, realCallingUid,
                reason);

        // TODO: Switch to user app stacks here.
        return obtainStarter(intent, reason)
                .setCallingUid(uid)
                .setRealCallingPid(realCallingPid)
                .setRealCallingUid(realCallingUid)
                .setCallingPackage(callingPackage)
                .setCallingFeatureId(callingFeatureId)
                .setResolvedType(resolvedType)
                .setResultTo(resultTo)
                .setResultWho(resultWho)
                .setRequestCode(requestCode)
                .setStartFlags(startFlags)
                .setActivityOptions(options)
                .setUserId(userId)
                .setInTask(inTask)
                .setOriginatingPendingIntent(originatingPendingIntent)
                .setBackgroundStartPrivileges(backgroundStartPrivileges)
                .execute();
    }

    /**
     * Start intents as a package.
     *
     * @param uid Make a call as if this UID did.
     * @param callingPackage Make a call as if this package did.
     * @param callingFeatureId Make a call as if this feature in the package did.
     * @param intents Intents to start.
     * @param userId Start the intents on this user.
     * @param validateIncomingUser Set true to skip checking {@code userId} with the calling UID.
     * @param originatingPendingIntent PendingIntentRecord that originated this activity start or
     *        null if not originated by PendingIntent
     */
    final int startActivitiesInPackage(int uid, String callingPackage,
            @Nullable String callingFeatureId, Intent[] intents, String[] resolvedTypes,
            IBinder resultTo, SafeActivityOptions options, int userId, boolean validateIncomingUser,
            PendingIntentRecord originatingPendingIntent,
            BackgroundStartPrivileges backgroundStartPrivileges) {
        return startActivitiesInPackage(uid, 0 /* realCallingPid */, -1 /* realCallingUid */,
                callingPackage, callingFeatureId, intents, resolvedTypes, resultTo, options, userId,
                validateIncomingUser, originatingPendingIntent, backgroundStartPrivileges);
    }

    /**
     * Start intents as a package.
     *
     * @param uid Make a call as if this UID did.
     * @param realCallingPid PID of the real caller.
     * @param realCallingUid UID of the real caller.
     * @param callingPackage Make a call as if this package did.
     * @param intents Intents to start.
     * @param userId Start the intents on this user.
     * @param validateIncomingUser Set true to skip checking {@code userId} with the calling UID.
     * @param originatingPendingIntent PendingIntentRecord that originated this activity start or
     *        null if not originated by PendingIntent
     */
    final int startActivitiesInPackage(int uid, int realCallingPid, int realCallingUid,
            String callingPackage, @Nullable String callingFeatureId, Intent[] intents,
            String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options, int userId,
            boolean validateIncomingUser, PendingIntentRecord originatingPendingIntent,
            BackgroundStartPrivileges backgroundStartPrivileges) {

        final String reason = "startActivityInPackage";

        userId = checkTargetUser(userId, validateIncomingUser, Binder.getCallingPid(),
                Binder.getCallingUid(), reason);

        // TODO: Switch to user app stacks here.
        return startActivities(null, uid, realCallingPid, realCallingUid, callingPackage,
                callingFeatureId, intents, resolvedTypes, resultTo, options, userId, reason,
                originatingPendingIntent, backgroundStartPrivileges);
    }

    int startActivities(IApplicationThread caller, int callingUid, int incomingRealCallingPid,
            int incomingRealCallingUid, String callingPackage, @Nullable String callingFeatureId,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options,
            int userId, String reason, PendingIntentRecord originatingPendingIntent,
            BackgroundStartPrivileges backgroundStartPrivileges) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
        }

        final int realCallingPid = incomingRealCallingPid != 0
                ? incomingRealCallingPid
                : Binder.getCallingPid();
        final int realCallingUid = incomingRealCallingUid != -1
                ? incomingRealCallingUid
                : Binder.getCallingUid();

        int callingPid;
        if (callingUid >= 0) {
            callingPid = -1;
        } else if (caller == null) {
            callingPid = realCallingPid;
            callingUid = realCallingUid;
        } else {
            callingPid = callingUid = -1;
        }
        final int filterCallingUid = ActivityStarter.computeResolveFilterUid(
                callingUid, realCallingUid, UserHandle.USER_NULL);
        final SparseArray<String> startingUidPkgs = new SparseArray<>();
        final long origId = Binder.clearCallingIdentity();

        SafeActivityOptions bottomOptions = null;
        if (options != null) {
            // To ensure the first N-1 activities (N == total # of activities) are also launched
            // into the correct display and root task, use a copy of the passed-in options (keeping
            // only display-related and launch-root-task information) for these activities.
            bottomOptions = options.selectiveCloneLaunchOptions();
        }
        try {
            intents = ArrayUtils.filterNotNull(intents, Intent[]::new);
            final ActivityStarter[] starters = new ActivityStarter[intents.length];
            // Do not hold WM global lock on this loop because when resolving intent, it may
            // potentially acquire activity manager lock that leads to deadlock.
            for (int i = 0; i < intents.length; i++) {
                Intent intent = intents[i];
                NeededUriGrants intentGrants = null;

                // Refuse possible leaked file descriptors.
                if (intent.hasFileDescriptors()) {
                    throw new IllegalArgumentException("File descriptors passed in Intent");
                }

                // Get the flag earlier because the intent may be modified in resolveActivity below.
                final boolean componentSpecified = intent.getComponent() != null;
                // Don't modify the client's object!
                intent = new Intent(intent);

                // Collect information about the target of the Intent.
                ActivityInfo aInfo = mSupervisor.resolveActivity(intent, resolvedTypes[i],
                        0 /* startFlags */, null /* profilerInfo */, userId, filterCallingUid,
                        callingPid);
                aInfo = mService.mAmInternal.getActivityInfoForUser(aInfo, userId);

                if (aInfo != null) {
                    try {
                        // Carefully collect grants without holding lock
                        intentGrants = mSupervisor.mService.mUgmInternal
                                .checkGrantUriPermissionFromIntent(intent, filterCallingUid,
                                        aInfo.applicationInfo.packageName,
                                        UserHandle.getUserId(aInfo.applicationInfo.uid));
                    } catch (SecurityException e) {
                        Slog.d(TAG, "Not allowed to start activity since no uri permission.");
                        return START_CANCELED;
                    }

                    if ((aInfo.applicationInfo.privateFlags
                            & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
                        throw new IllegalArgumentException(
                                "FLAG_CANT_SAVE_STATE not supported here");
                    }
                    startingUidPkgs.put(aInfo.applicationInfo.uid,
                            aInfo.applicationInfo.packageName);
                }

                final boolean top = i == intents.length - 1;
                final SafeActivityOptions checkedOptions = top
                        ? options
                        : bottomOptions;
                starters[i] = obtainStarter(intent, reason)
                        .setIntentGrants(intentGrants)
                        .setCaller(caller)
                        .setResolvedType(resolvedTypes[i])
                        .setActivityInfo(aInfo)
                        .setRequestCode(-1)
                        .setCallingPid(callingPid)
                        .setCallingUid(callingUid)
                        .setCallingPackage(callingPackage)
                        .setCallingFeatureId(callingFeatureId)
                        .setRealCallingPid(realCallingPid)
                        .setRealCallingUid(realCallingUid)
                        .setActivityOptions(checkedOptions)
                        .setComponentSpecified(componentSpecified)

                        // Top activity decides on animation being run, so we allow only for the
                        // top one as otherwise an activity below might consume it.
                        .setAllowPendingRemoteAnimationRegistryLookup(top /* allowLookup*/)
                        .setOriginatingPendingIntent(originatingPendingIntent)
                        .setBackgroundStartPrivileges(backgroundStartPrivileges);
            }
            // Log if the activities to be started have different uids.
            if (startingUidPkgs.size() > 1) {
                final StringBuilder sb = new StringBuilder("startActivities: different apps [");
                final int size = startingUidPkgs.size();
                for (int i = 0; i < size; i++) {
                    sb.append(startingUidPkgs.valueAt(i)).append(i == size - 1 ? "]" : ", ");
                }
                sb.append(" from ").append(callingPackage);
                Slog.wtf(TAG, sb.toString());
            }

            final IBinder sourceResultTo = resultTo;
            final ActivityRecord[] outActivity = new ActivityRecord[1];
            // Lock the loop to ensure the activities launched in a sequence.
            synchronized (mService.mGlobalLock) {
                mService.deferWindowLayout();
                // To avoid creating multiple starting window when creating starting multiples
                // activities, we defer the creation of the starting window once all start request
                // are processed
                mService.mWindowManager.mStartingSurfaceController.beginDeferAddStartingWindow();
                try {
                    for (int i = 0; i < starters.length; i++) {
                        final int startResult = starters[i].setResultTo(resultTo)
                                .setOutActivity(outActivity).execute();
                        if (startResult < START_SUCCESS) {
                            // Abort by error result and recycle unused starters.
                            for (int j = i + 1; j < starters.length; j++) {
                                mFactory.recycle(starters[j]);
                            }
                            return startResult;
                        }
                        final ActivityRecord started = outActivity[0];
                        if (started != null && started.getUid() == filterCallingUid) {
                            // Only the started activity which has the same uid as the source caller
                            // can be the caller of next activity.
                            resultTo = started.token;
                        } else {
                            resultTo = sourceResultTo;
                            // Different apps not adjacent to the caller are forced to be new task.
                            if (i < starters.length - 1) {
                                starters[i + 1].getIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            }
                        }
                    }
                } finally {
                    mService.mWindowManager.mStartingSurfaceController.endDeferAddStartingWindow(
                            options != null ? options.getOriginalOptions() : null);
                    mService.continueWindowLayout();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return START_SUCCESS;
    }

    /**
     * Starts an activity in the TaskFragment.
     * @param taskFragment TaskFragment {@link TaskFragment} to start the activity in.
     * @param activityIntent intent to start the activity.
     * @param activityOptions ActivityOptions to start the activity with.
     * @param resultTo the caller activity
     * @param callingUid the caller uid
     * @param callingPid the caller pid
     * @return the start result.
     */
    int startActivityInTaskFragment(@NonNull TaskFragment taskFragment,
            @NonNull Intent activityIntent, @Nullable Bundle activityOptions,
            @Nullable IBinder resultTo, int callingUid, int callingPid,
            @Nullable IBinder errorCallbackToken) {
        final ActivityRecord caller =
                resultTo != null ? ActivityRecord.forTokenLocked(resultTo) : null;
        return obtainStarter(activityIntent, "startActivityInTaskFragment")
                .setActivityOptions(activityOptions)
                .setInTaskFragment(taskFragment)
                .setResultTo(resultTo)
                .setRequestCode(-1)
                .setCallingUid(callingUid)
                .setCallingPid(callingPid)
                .setRealCallingUid(callingUid)
                .setRealCallingPid(callingPid)
                .setUserId(caller != null ? caller.mUserId : mService.getCurrentUserId())
                .setErrorCallbackToken(errorCallbackToken)
                .execute();
    }

    boolean startExistingRecentsIfPossible(Intent intent, ActivityOptions options) {
        final int activityType = mService.getRecentTasks().getRecentsComponent()
                .equals(intent.getComponent()) ? ACTIVITY_TYPE_RECENTS : ACTIVITY_TYPE_HOME;
        final Task rootTask = mService.mRootWindowContainer.getDefaultTaskDisplayArea()
                .getRootTask(WINDOWING_MODE_UNDEFINED, activityType);
        if (rootTask == null) return false;
        final RemoteTransition remote = options.getRemoteTransition();
        final ActivityRecord r = rootTask.topRunningActivity();
        if (r == null || r.isVisibleRequested() || !r.attachedToProcess() || remote == null
                || !r.mActivityComponent.equals(intent.getComponent())
                // Recents keeps invisible while device is locked.
                || r.mDisplayContent.isKeyguardLocked()) {
            return false;
        }
        mService.mRootWindowContainer.startPowerModeLaunchIfNeeded(true /* forceSend */, r);
        final ActivityMetricsLogger.LaunchingState launchingState =
                mSupervisor.getActivityMetricsLogger().notifyActivityLaunching(intent);
        final Transition transition = new Transition(WindowManager.TRANSIT_TO_FRONT,
                0 /* flags */, r.mTransitionController, mService.mWindowManager.mSyncEngine);
        if (r.mTransitionController.isCollecting()) {
            // Special case: we are entering recents while an existing transition is running. In
            // this case, we know it's safe to "defer" the activity launch, so lets do so now so
            // that it can get its own transition and thus update launcher correctly.
            mService.mWindowManager.mSyncEngine.queueSyncSet(
                    () -> {
                        if (r.isAttached()) {
                            r.mTransitionController.moveToCollecting(transition);
                        }
                    },
                    () -> {
                        if (r.isAttached() && transition.isCollecting()) {
                            startExistingRecentsIfPossibleInner(options, r, rootTask,
                                    launchingState, remote, transition);
                        }
                    });
        } else {
            r.mTransitionController.moveToCollecting(transition);
            startExistingRecentsIfPossibleInner(options, r, rootTask, launchingState, remote,
                    transition);
        }
        return true;
    }

    private void startExistingRecentsIfPossibleInner(ActivityOptions options, ActivityRecord r,
            Task rootTask, ActivityMetricsLogger.LaunchingState launchingState,
            RemoteTransition remoteTransition, Transition transition) {
        final Task task = r.getTask();
        mService.deferWindowLayout();
        try {
            r.mTransitionController.requestStartTransition(transition,
                    task, remoteTransition, null /* displayChange */);
            r.mTransitionController.collect(task);
            r.mTransitionController.setTransientLaunch(r,
                    TaskDisplayArea.getRootTaskAbove(rootTask));
            task.moveToFront("startExistingRecents");
            task.mInResumeTopActivity = true;
            task.resumeTopActivity(null /* prev */, options, true /* deferPause */);
            mSupervisor.getActivityMetricsLogger().notifyActivityLaunched(launchingState,
                    ActivityManager.START_TASK_TO_FRONT, false, r, options);
        } finally {
            task.mInResumeTopActivity = false;
            mService.continueWindowLayout();
        }
    }

    void registerRemoteAnimationForNextActivityStart(String packageName,
            RemoteAnimationAdapter adapter, @Nullable IBinder launchCookie) {
        mPendingRemoteAnimationRegistry.addPendingAnimation(packageName, adapter, launchCookie);
    }

    PendingRemoteAnimationRegistry getPendingRemoteAnimationRegistry() {
        return mPendingRemoteAnimationRegistry;
    }

    void dumpLastHomeActivityStartResult(PrintWriter pw, String prefix) {
        pw.print(prefix);
        pw.print("mLastHomeActivityStartResult=");
        pw.println(mLastHomeActivityStartResult);
    }

    void dump(PrintWriter pw, String prefix, String dumpPackage) {
        boolean dumped = false;

        final boolean dumpPackagePresent = dumpPackage != null;

        if (mLastHomeActivityStartRecord != null && (!dumpPackagePresent
                || dumpPackage.equals(mLastHomeActivityStartRecord.packageName))) {
            dumped = true;
            dumpLastHomeActivityStartResult(pw, prefix);
            pw.print(prefix);
            pw.println("mLastHomeActivityStartRecord:");
            mLastHomeActivityStartRecord.dump(pw, prefix + "  ", true /* dumpAll */);
        }

        if (mLastStarter != null) {
            final boolean dump = !dumpPackagePresent
                    || mLastStarter.relatedToPackage(dumpPackage)
                    || (mLastHomeActivityStartRecord != null
                            && dumpPackage.equals(mLastHomeActivityStartRecord.packageName));

            if (dump) {
                if (!dumped) {
                    dumped = true;
                    dumpLastHomeActivityStartResult(pw, prefix);
                }
                pw.print(prefix);
                pw.println("mLastStarter:");
                mLastStarter.dump(pw, prefix + "  ");

                if (dumpPackagePresent) {
                    return;
                }
            }
        }

        if (!dumped) {
            pw.print(prefix);
            pw.println("(nothing)");
        }
    }

    BackgroundActivityStartController getBackgroundActivityLaunchController() {
        return mBalController;
    }
}
