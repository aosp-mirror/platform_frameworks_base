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

import static android.Manifest.permission.ACTIVITY_EMBEDDING;
import static android.Manifest.permission.START_ACTIVITIES_FROM_BACKGROUND;
import static android.app.Activity.RESULT_CANCELED;
import static android.app.ActivityManager.START_ABORTED;
import static android.app.ActivityManager.START_CANCELED;
import static android.app.ActivityManager.START_CLASS_NOT_FOUND;
import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_FLAG_ONLY_IF_NEEDED;
import static android.app.ActivityManager.START_PERMISSION_DENIED;
import static android.app.ActivityManager.START_RETURN_INTENT_TO_CALLER;
import static android.app.ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION;
import static android.content.Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP;
import static android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
import static android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
import static android.content.Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
import static android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS;
import static android.content.pm.ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE_PER_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.INVALID_UID;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_OPEN;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_CONFIGURATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_TASKS;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_ACTIVITY_STARTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_FOCUS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.ActivityTaskManagerService.ANIMATE;
import static com.android.server.wm.ActivityTaskSupervisor.DEFER_RESUME;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.ActivityTaskSupervisor.PRESERVE_WINDOWS;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_BOUNDS;
import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_DISPLAY;
import static com.android.server.wm.Task.REPARENT_MOVE_ROOT_TASK_TO_FRONT;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.content.ComponentName;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.voice.IVoiceInteractionSession;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Pools.SynchronizedPool;
import android.util.Slog;
import android.window.RemoteTransition;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.protolog.common.ProtoLog;
import com.android.server.am.PendingIntentRecord;
import com.android.server.pm.InstantAppResolver;
import com.android.server.power.ShutdownCheckPoints;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.uri.NeededUriGrants;
import com.android.server.wm.ActivityMetricsLogger.LaunchingState;
import com.android.server.wm.LaunchParamsController.LaunchParams;

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;

/**
 * Controller for interpreting how and then launching an activity.
 *
 * This class collects all the logic for determining how an intent and flags should be turned into
 * an activity and associated task and root task.
 */
class ActivityStarter {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStarter" : TAG_ATM;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;
    private static final int INVALID_LAUNCH_MODE = -1;

    private final ActivityTaskManagerService mService;
    private final RootWindowContainer mRootWindowContainer;
    private final ActivityTaskSupervisor mSupervisor;
    private final ActivityStartInterceptor mInterceptor;
    private final ActivityStartController mController;

    // Share state variable among methods when starting an activity.
    @VisibleForTesting
    ActivityRecord mStartActivity;
    private Intent mIntent;
    private int mCallingUid;
    private ActivityOptions mOptions;

    // If it is true, background activity can only be started in an existing task that contains
    // an activity with same uid, or if activity starts are enabled in developer options.
    private boolean mRestrictedBgActivity;

    private int mLaunchMode;
    private boolean mLaunchTaskBehind;
    private int mLaunchFlags;

    private LaunchParams mLaunchParams = new LaunchParams();

    private ActivityRecord mNotTop;
    private boolean mDoResume;
    private int mStartFlags;
    private ActivityRecord mSourceRecord;

    // The task display area to launch the activity onto, barring any strong reason to do otherwise.
    private TaskDisplayArea mPreferredTaskDisplayArea;
    private int mPreferredWindowingMode;

    private Task mInTask;
    private TaskFragment mInTaskFragment;
    @VisibleForTesting
    boolean mAddingToTask;
    private Task mReuseTask;

    private ActivityInfo mNewTaskInfo;
    private Intent mNewTaskIntent;
    private Task mSourceRootTask;
    private Task mTargetRootTask;
    // The task that the last activity was started into. We currently reset the actual start
    // activity's task and as a result may not have a reference to the task in all cases
    private Task mTargetTask;
    private boolean mMovedToFront;
    private boolean mNoAnimation;
    private boolean mAvoidMoveToFront;
    private boolean mFrozeTaskList;
    private boolean mTransientLaunch;

    // We must track when we deliver the new intent since multiple code paths invoke
    // {@link #deliverNewIntent}. This is due to early returns in the code path. This flag is used
    // inside {@link #deliverNewIntent} to suppress duplicate requests and ensure the intent is
    // delivered at most once.
    private boolean mIntentDelivered;

    private IVoiceInteractionSession mVoiceSession;
    private IVoiceInteractor mVoiceInteractor;

    // Last activity record we attempted to start
    private ActivityRecord mLastStartActivityRecord;
    // The result of the last activity we attempted to start.
    private int mLastStartActivityResult;
    // Time in milli seconds we attempted to start the last activity.
    private long mLastStartActivityTimeMs;
    // The reason we were trying to start the last activity
    private String mLastStartReason;

    /*
     * Request details provided through setter methods. Should be reset after {@link #execute()}
     * to avoid unnecessarily retaining parameters. Note that the request is ignored when
     * {@link #startResolvedActivity} is invoked directly.
     */
    @VisibleForTesting
    Request mRequest = new Request();

    /**
     * An interface that to provide {@link ActivityStarter} instances to the controller. This is
     * used by tests to inject their own starter implementations for verification purposes.
     */
    @VisibleForTesting
    interface Factory {
        /**
         * Sets the {@link ActivityStartController} to be passed to {@link ActivityStarter}.
         */
        void setController(ActivityStartController controller);

        /**
         * Generates an {@link ActivityStarter} that is ready to handle a new start request.
         * @param controller The {@link ActivityStartController} which the starter who will own
         *                   this instance.
         * @return an {@link ActivityStarter}
         */
        ActivityStarter obtain();

        /**
         * Recycles a starter for reuse.
         */
        void recycle(ActivityStarter starter);
    }

    /**
     * Default implementation of {@link StarterFactory}.
     */
    static class DefaultFactory implements Factory {
        /**
         * The maximum count of starters that should be active at one time:
         * 1. last ran starter (for logging and post activity processing)
         * 2. current running starter
         * 3. starter from re-entry in (2)
         */
        private final int MAX_STARTER_COUNT = 3;

        private ActivityStartController mController;
        private ActivityTaskManagerService mService;
        private ActivityTaskSupervisor mSupervisor;
        private ActivityStartInterceptor mInterceptor;

        private SynchronizedPool<ActivityStarter> mStarterPool =
                new SynchronizedPool<>(MAX_STARTER_COUNT);

        DefaultFactory(ActivityTaskManagerService service,
                ActivityTaskSupervisor supervisor, ActivityStartInterceptor interceptor) {
            mService = service;
            mSupervisor = supervisor;
            mInterceptor = interceptor;
        }

        @Override
        public void setController(ActivityStartController controller) {
            mController = controller;
        }

        @Override
        public ActivityStarter obtain() {
            ActivityStarter starter = mStarterPool.acquire();

            if (starter == null) {
                if (mService.mRootWindowContainer == null) {
                    throw new IllegalStateException("Too early to start activity.");
                }
                starter = new ActivityStarter(mController, mService, mSupervisor, mInterceptor);
            }

            return starter;
        }

        @Override
        public void recycle(ActivityStarter starter) {
            starter.reset(true /* clearRequest*/);
            mStarterPool.release(starter);
        }
    }

    /**
     * Container for capturing initial start request details. This information is NOT reset until
     * the {@link ActivityStarter} is recycled, allowing for multiple invocations with the same
     * parameters.
     *
     * TODO(b/64750076): Investigate consolidating member variables of {@link ActivityStarter} with
     * the request object. Note that some member variables are referenced in
     * {@link #dump(PrintWriter, String)} and therefore cannot be cleared immediately after
     * execution.
     */
    @VisibleForTesting
    static class Request {
        private static final int DEFAULT_CALLING_UID = -1;
        private static final int DEFAULT_CALLING_PID = 0;
        static final int DEFAULT_REAL_CALLING_UID = -1;
        static final int DEFAULT_REAL_CALLING_PID = 0;

        IApplicationThread caller;
        Intent intent;
        NeededUriGrants intentGrants;
        // A copy of the original requested intent, in case for ephemeral app launch.
        Intent ephemeralIntent;
        String resolvedType;
        ActivityInfo activityInfo;
        ResolveInfo resolveInfo;
        IVoiceInteractionSession voiceSession;
        IVoiceInteractor voiceInteractor;
        IBinder resultTo;
        String resultWho;
        int requestCode;
        int callingPid = DEFAULT_CALLING_PID;
        int callingUid = DEFAULT_CALLING_UID;
        String callingPackage;
        @Nullable String callingFeatureId;
        int realCallingPid = DEFAULT_REAL_CALLING_PID;
        int realCallingUid = DEFAULT_REAL_CALLING_UID;
        int startFlags;
        SafeActivityOptions activityOptions;
        boolean ignoreTargetSecurity;
        boolean componentSpecified;
        boolean avoidMoveToFront;
        ActivityRecord[] outActivity;
        Task inTask;
        TaskFragment inTaskFragment;
        String reason;
        ProfilerInfo profilerInfo;
        Configuration globalConfig;
        int userId;
        WaitResult waitResult;
        int filterCallingUid;
        PendingIntentRecord originatingPendingIntent;
        boolean allowBackgroundActivityStart;

        /**
         * If set to {@code true}, allows this activity start to look into
         * {@link PendingRemoteAnimationRegistry}
         */
        boolean allowPendingRemoteAnimationRegistryLookup;

        /**
         * Ensure constructed request matches reset instance.
         */
        Request() {
            reset();
        }

        /**
         * Sets values back to the initial state, clearing any held references.
         */
        void reset() {
            caller = null;
            intent = null;
            intentGrants = null;
            ephemeralIntent = null;
            resolvedType = null;
            activityInfo = null;
            resolveInfo = null;
            voiceSession = null;
            voiceInteractor = null;
            resultTo = null;
            resultWho = null;
            requestCode = 0;
            callingPid = DEFAULT_CALLING_PID;
            callingUid = DEFAULT_CALLING_UID;
            callingPackage = null;
            callingFeatureId = null;
            realCallingPid = DEFAULT_REAL_CALLING_PID;
            realCallingUid = DEFAULT_REAL_CALLING_UID;
            startFlags = 0;
            activityOptions = null;
            ignoreTargetSecurity = false;
            componentSpecified = false;
            outActivity = null;
            inTask = null;
            inTaskFragment = null;
            reason = null;
            profilerInfo = null;
            globalConfig = null;
            userId = 0;
            waitResult = null;
            avoidMoveToFront = false;
            allowPendingRemoteAnimationRegistryLookup = true;
            filterCallingUid = UserHandle.USER_NULL;
            originatingPendingIntent = null;
            allowBackgroundActivityStart = false;
        }

        /**
         * Adopts all values from passed in request.
         */
        void set(@NonNull Request request) {
            caller = request.caller;
            intent = request.intent;
            intentGrants = request.intentGrants;
            ephemeralIntent = request.ephemeralIntent;
            resolvedType = request.resolvedType;
            activityInfo = request.activityInfo;
            resolveInfo = request.resolveInfo;
            voiceSession = request.voiceSession;
            voiceInteractor = request.voiceInteractor;
            resultTo = request.resultTo;
            resultWho = request.resultWho;
            requestCode = request.requestCode;
            callingPid = request.callingPid;
            callingUid = request.callingUid;
            callingPackage = request.callingPackage;
            callingFeatureId = request.callingFeatureId;
            realCallingPid = request.realCallingPid;
            realCallingUid = request.realCallingUid;
            startFlags = request.startFlags;
            activityOptions = request.activityOptions;
            ignoreTargetSecurity = request.ignoreTargetSecurity;
            componentSpecified = request.componentSpecified;
            outActivity = request.outActivity;
            inTask = request.inTask;
            inTaskFragment = request.inTaskFragment;
            reason = request.reason;
            profilerInfo = request.profilerInfo;
            globalConfig = request.globalConfig;
            userId = request.userId;
            waitResult = request.waitResult;
            avoidMoveToFront = request.avoidMoveToFront;
            allowPendingRemoteAnimationRegistryLookup
                    = request.allowPendingRemoteAnimationRegistryLookup;
            filterCallingUid = request.filterCallingUid;
            originatingPendingIntent = request.originatingPendingIntent;
            allowBackgroundActivityStart = request.allowBackgroundActivityStart;
        }

        /**
         * Resolve activity from the given intent for this launch.
         */
        void resolveActivity(ActivityTaskSupervisor supervisor) {
            if (realCallingPid == Request.DEFAULT_REAL_CALLING_PID) {
                realCallingPid = Binder.getCallingPid();
            }
            if (realCallingUid == Request.DEFAULT_REAL_CALLING_UID) {
                realCallingUid = Binder.getCallingUid();
            }

            if (callingUid >= 0) {
                callingPid = -1;
            } else if (caller == null) {
                callingPid = realCallingPid;
                callingUid = realCallingUid;
            } else {
                callingPid = callingUid = -1;
            }

            // To determine the set of needed Uri permission grants, we need the
            // "resolved" calling UID, where we try our best to identify the
            // actual caller that is starting this activity
            int resolvedCallingUid = callingUid;
            if (caller != null) {
                synchronized (supervisor.mService.mGlobalLock) {
                    final WindowProcessController callerApp = supervisor.mService
                            .getProcessController(caller);
                    if (callerApp != null) {
                        resolvedCallingUid = callerApp.mInfo.uid;
                    }
                }
            }

            // Save a copy in case ephemeral needs it
            ephemeralIntent = new Intent(intent);
            // Don't modify the client's object!
            intent = new Intent(intent);
            if (intent.getComponent() != null
                    && !(Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() == null)
                    && !Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE.equals(intent.getAction())
                    && !Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE.equals(intent.getAction())
                    && supervisor.mService.getPackageManagerInternalLocked()
                            .isInstantAppInstallerComponent(intent.getComponent())) {
                // Intercept intents targeted directly to the ephemeral installer the ephemeral
                // installer should never be started with a raw Intent; instead adjust the intent
                // so it looks like a "normal" instant app launch.
                intent.setComponent(null /* component */);
            }

            resolveInfo = supervisor.resolveIntent(intent, resolvedType, userId,
                    0 /* matchFlags */,
                    computeResolveFilterUid(callingUid, realCallingUid, filterCallingUid));
            if (resolveInfo == null) {
                final UserInfo userInfo = supervisor.getUserInfo(userId);
                if (userInfo != null && userInfo.isManagedProfile()) {
                    // Special case for managed profiles, if attempting to launch non-cryto aware
                    // app in a locked managed profile from an unlocked parent allow it to resolve
                    // as user will be sent via confirm credentials to unlock the profile.
                    final UserManager userManager = UserManager.get(supervisor.mService.mContext);
                    boolean profileLockedAndParentUnlockingOrUnlocked = false;
                    final long token = Binder.clearCallingIdentity();
                    try {
                        final UserInfo parent = userManager.getProfileParent(userId);
                        profileLockedAndParentUnlockingOrUnlocked = (parent != null)
                                && userManager.isUserUnlockingOrUnlocked(parent.id)
                                && !userManager.isUserUnlockingOrUnlocked(userId);
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    if (profileLockedAndParentUnlockingOrUnlocked) {
                        resolveInfo = supervisor.resolveIntent(intent, resolvedType, userId,
                                PackageManager.MATCH_DIRECT_BOOT_AWARE
                                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                                computeResolveFilterUid(callingUid, realCallingUid,
                                        filterCallingUid));
                    }
                }
            }

            // Collect information about the target of the Intent.
            activityInfo = supervisor.resolveActivity(intent, resolveInfo, startFlags,
                    profilerInfo);

            // Carefully collect grants without holding lock
            if (activityInfo != null) {
                intentGrants = supervisor.mService.mUgmInternal.checkGrantUriPermissionFromIntent(
                        intent, resolvedCallingUid, activityInfo.applicationInfo.packageName,
                        UserHandle.getUserId(activityInfo.applicationInfo.uid));
            }
        }
    }

    ActivityStarter(ActivityStartController controller, ActivityTaskManagerService service,
            ActivityTaskSupervisor supervisor, ActivityStartInterceptor interceptor) {
        mController = controller;
        mService = service;
        mRootWindowContainer = service.mRootWindowContainer;
        mSupervisor = supervisor;
        mInterceptor = interceptor;
        reset(true);
    }

    /**
     * Effectively duplicates the starter passed in. All state and request values will be
     * mirrored.
     * @param starter
     */
    void set(ActivityStarter starter) {
        mStartActivity = starter.mStartActivity;
        mIntent = starter.mIntent;
        mCallingUid = starter.mCallingUid;
        mOptions = starter.mOptions;
        mRestrictedBgActivity = starter.mRestrictedBgActivity;

        mLaunchTaskBehind = starter.mLaunchTaskBehind;
        mLaunchFlags = starter.mLaunchFlags;
        mLaunchMode = starter.mLaunchMode;

        mLaunchParams.set(starter.mLaunchParams);

        mNotTop = starter.mNotTop;
        mDoResume = starter.mDoResume;
        mStartFlags = starter.mStartFlags;
        mSourceRecord = starter.mSourceRecord;
        mPreferredTaskDisplayArea = starter.mPreferredTaskDisplayArea;
        mPreferredWindowingMode = starter.mPreferredWindowingMode;

        mInTask = starter.mInTask;
        mInTaskFragment = starter.mInTaskFragment;
        mAddingToTask = starter.mAddingToTask;
        mReuseTask = starter.mReuseTask;

        mNewTaskInfo = starter.mNewTaskInfo;
        mNewTaskIntent = starter.mNewTaskIntent;
        mSourceRootTask = starter.mSourceRootTask;

        mTargetTask = starter.mTargetTask;
        mTargetRootTask = starter.mTargetRootTask;
        mMovedToFront = starter.mMovedToFront;
        mNoAnimation = starter.mNoAnimation;
        mAvoidMoveToFront = starter.mAvoidMoveToFront;
        mFrozeTaskList = starter.mFrozeTaskList;

        mVoiceSession = starter.mVoiceSession;
        mVoiceInteractor = starter.mVoiceInteractor;

        mIntentDelivered = starter.mIntentDelivered;

        mRequest.set(starter.mRequest);
    }

    boolean relatedToPackage(String packageName) {
        return (mLastStartActivityRecord != null
                && packageName.equals(mLastStartActivityRecord.packageName))
                || (mStartActivity != null && packageName.equals(mStartActivity.packageName));
    }

    /**
     * Resolve necessary information according the request parameters provided earlier, and execute
     * the request which begin the journey of starting an activity.
     * @return The starter result.
     */
    int execute() {
        try {
            // Refuse possible leaked file descriptors
            if (mRequest.intent != null && mRequest.intent.hasFileDescriptors()) {
                throw new IllegalArgumentException("File descriptors passed in Intent");
            }

            final LaunchingState launchingState;
            synchronized (mService.mGlobalLock) {
                final ActivityRecord caller = ActivityRecord.forTokenLocked(mRequest.resultTo);
                final int callingUid = mRequest.realCallingUid == Request.DEFAULT_REAL_CALLING_UID
                        ?  Binder.getCallingUid() : mRequest.realCallingUid;
                launchingState = mSupervisor.getActivityMetricsLogger().notifyActivityLaunching(
                        mRequest.intent, caller, callingUid);
            }

            // If the caller hasn't already resolved the activity, we're willing
            // to do so here. If the caller is already holding the WM lock here,
            // and we need to check dynamic Uri permissions, then we're forced
            // to assume those permissions are denied to avoid deadlocking.
            if (mRequest.activityInfo == null) {
                mRequest.resolveActivity(mSupervisor);
            }

            // Add checkpoint for this shutdown or reboot attempt, so we can record the original
            // intent action and package name.
            if (mRequest.intent != null) {
                String intentAction = mRequest.intent.getAction();
                String callingPackage = mRequest.callingPackage;
                if (intentAction != null && callingPackage != null
                        && (Intent.ACTION_REQUEST_SHUTDOWN.equals(intentAction)
                                || Intent.ACTION_SHUTDOWN.equals(intentAction)
                                || Intent.ACTION_REBOOT.equals(intentAction))) {
                    ShutdownCheckPoints.recordCheckPoint(intentAction, callingPackage, null);
                }
            }

            int res;
            synchronized (mService.mGlobalLock) {
                final boolean globalConfigWillChange = mRequest.globalConfig != null
                        && mService.getGlobalConfiguration().diff(mRequest.globalConfig) != 0;
                final Task rootTask = mRootWindowContainer.getTopDisplayFocusedRootTask();
                if (rootTask != null) {
                    rootTask.mConfigWillChange = globalConfigWillChange;
                }
                ProtoLog.v(WM_DEBUG_CONFIGURATION, "Starting activity when config "
                        + "will change = %b", globalConfigWillChange);

                final long origId = Binder.clearCallingIdentity();

                res = resolveToHeavyWeightSwitcherIfNeeded();
                if (res != START_SUCCESS) {
                    return res;
                }
                res = executeRequest(mRequest);

                Binder.restoreCallingIdentity(origId);

                if (globalConfigWillChange) {
                    // If the caller also wants to switch to a new configuration, do so now.
                    // This allows a clean switch, as we are waiting for the current activity
                    // to pause (so we will not destroy it), and have not yet started the
                    // next activity.
                    mService.mAmInternal.enforceCallingPermission(
                            android.Manifest.permission.CHANGE_CONFIGURATION,
                            "updateConfiguration()");
                    if (rootTask != null) {
                        rootTask.mConfigWillChange = false;
                    }
                    ProtoLog.v(WM_DEBUG_CONFIGURATION,
                                "Updating to new configuration after starting activity.");

                    mService.updateConfigurationLocked(mRequest.globalConfig, null, false);
                }

                // The original options may have additional info about metrics. The mOptions is not
                // used here because it may be cleared in setTargetRootTaskIfNeeded.
                final ActivityOptions originalOptions = mRequest.activityOptions != null
                        ? mRequest.activityOptions.getOriginalOptions() : null;
                // If the new record is the one that started, a new activity has created.
                final boolean newActivityCreated = mStartActivity == mLastStartActivityRecord;
                // Notify ActivityMetricsLogger that the activity has launched.
                // ActivityMetricsLogger will then wait for the windows to be drawn and populate
                // WaitResult.
                mSupervisor.getActivityMetricsLogger().notifyActivityLaunched(launchingState, res,
                        newActivityCreated, mLastStartActivityRecord, originalOptions);
                if (mRequest.waitResult != null) {
                    mRequest.waitResult.result = res;
                    res = waitResultIfNeeded(mRequest.waitResult, mLastStartActivityRecord,
                            launchingState);
                }
                return getExternalResult(res);
            }
        } finally {
            onExecutionComplete();
        }
    }

    /**
     * Updates the request to heavy-weight switch if this is a heavy-weight process while there
     * already have another, different heavy-weight process running.
     */
    private int resolveToHeavyWeightSwitcherIfNeeded() {
        if (mRequest.activityInfo == null || !mService.mHasHeavyWeightFeature
                || (mRequest.activityInfo.applicationInfo.privateFlags
                        & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) == 0) {
            return START_SUCCESS;
        }

        if (!mRequest.activityInfo.processName.equals(
                mRequest.activityInfo.applicationInfo.packageName)) {
            return START_SUCCESS;
        }

        final WindowProcessController heavy = mService.mHeavyWeightProcess;
        if (heavy == null || (heavy.mInfo.uid == mRequest.activityInfo.applicationInfo.uid
                && heavy.mName.equals(mRequest.activityInfo.processName))) {
            return START_SUCCESS;
        }

        int appCallingUid = mRequest.callingUid;
        if (mRequest.caller != null) {
            WindowProcessController callerApp = mService.getProcessController(mRequest.caller);
            if (callerApp != null) {
                appCallingUid = callerApp.mInfo.uid;
            } else {
                Slog.w(TAG, "Unable to find app for caller " + mRequest.caller + " (pid="
                        + mRequest.callingPid + ") when starting: " + mRequest.intent.toString());
                SafeActivityOptions.abort(mRequest.activityOptions);
                return START_PERMISSION_DENIED;
            }
        }

        final IIntentSender target = mService.getIntentSenderLocked(
                ActivityManager.INTENT_SENDER_ACTIVITY, "android" /* packageName */,
                null /* featureId */, appCallingUid, mRequest.userId, null /* token */,
                null /* resultWho*/, 0 /* requestCode*/, new Intent[]{mRequest.intent},
                new String[]{mRequest.resolvedType},
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT,
                null /* bOptions */);

        final Intent newIntent = new Intent();
        if (mRequest.requestCode >= 0) {
            // Caller is requesting a result.
            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_HAS_RESULT, true);
        }
        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_INTENT, new IntentSender(target));
        heavy.updateIntentForHeavyWeightActivity(newIntent);
        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_NEW_APP,
                mRequest.activityInfo.packageName);
        newIntent.setFlags(mRequest.intent.getFlags());
        newIntent.setClassName("android" /* packageName */,
                HeavyWeightSwitcherActivity.class.getName());
        mRequest.intent = newIntent;
        mRequest.resolvedType = null;
        mRequest.caller = null;
        mRequest.callingUid = Binder.getCallingUid();
        mRequest.callingPid = Binder.getCallingPid();
        mRequest.componentSpecified = true;
        mRequest.resolveInfo = mSupervisor.resolveIntent(mRequest.intent, null /* resolvedType */,
                mRequest.userId, 0 /* matchFlags */,
                computeResolveFilterUid(mRequest.callingUid, mRequest.realCallingUid,
                        mRequest.filterCallingUid));
        mRequest.activityInfo =
                mRequest.resolveInfo != null ? mRequest.resolveInfo.activityInfo : null;
        if (mRequest.activityInfo != null) {
            mRequest.activityInfo = mService.mAmInternal.getActivityInfoForUser(
                    mRequest.activityInfo, mRequest.userId);
        }

        return START_SUCCESS;
    }

    /**
     * Wait for activity launch completes.
     */
    private int waitResultIfNeeded(WaitResult waitResult, ActivityRecord r,
            LaunchingState launchingState) {
        final int res = waitResult.result;
        if (res == START_DELIVERED_TO_TOP
                || (res == START_TASK_TO_FRONT && r.nowVisible && r.isState(RESUMED))) {
            // The activity should already be visible, so nothing to wait.
            waitResult.timeout = false;
            waitResult.who = r.mActivityComponent;
            waitResult.totalTime = 0;
            return res;
        }
        mSupervisor.waitActivityVisibleOrLaunched(waitResult, r, launchingState);
        if (res == START_SUCCESS && waitResult.result == START_TASK_TO_FRONT) {
            // A trampoline activity is launched and it brings another existing activity to front.
            return START_TASK_TO_FRONT;
        }
        return res;
    }

    /**
     * Executing activity start request and starts the journey of starting an activity. Here
     * begins with performing several preliminary checks. The normally activity launch flow will
     * go through {@link #startActivityUnchecked} to {@link #startActivityInner}.
     */
    private int executeRequest(Request request) {
        if (TextUtils.isEmpty(request.reason)) {
            throw new IllegalArgumentException("Need to specify a reason.");
        }
        mLastStartReason = request.reason;
        mLastStartActivityTimeMs = System.currentTimeMillis();
        mLastStartActivityRecord = null;

        final IApplicationThread caller = request.caller;
        Intent intent = request.intent;
        NeededUriGrants intentGrants = request.intentGrants;
        String resolvedType = request.resolvedType;
        ActivityInfo aInfo = request.activityInfo;
        ResolveInfo rInfo = request.resolveInfo;
        final IVoiceInteractionSession voiceSession = request.voiceSession;
        final IBinder resultTo = request.resultTo;
        String resultWho = request.resultWho;
        int requestCode = request.requestCode;
        int callingPid = request.callingPid;
        int callingUid = request.callingUid;
        String callingPackage = request.callingPackage;
        String callingFeatureId = request.callingFeatureId;
        final int realCallingPid = request.realCallingPid;
        final int realCallingUid = request.realCallingUid;
        final int startFlags = request.startFlags;
        final SafeActivityOptions options = request.activityOptions;
        Task inTask = request.inTask;
        TaskFragment inTaskFragment = request.inTaskFragment;

        int err = ActivityManager.START_SUCCESS;
        // Pull the optional Ephemeral Installer-only bundle out of the options early.
        final Bundle verificationBundle =
                options != null ? options.popAppVerificationBundle() : null;

        WindowProcessController callerApp = null;
        if (caller != null) {
            callerApp = mService.getProcessController(caller);
            if (callerApp != null) {
                callingPid = callerApp.getPid();
                callingUid = callerApp.mInfo.uid;
            } else {
                Slog.w(TAG, "Unable to find app for caller " + caller + " (pid=" + callingPid
                        + ") when starting: " + intent.toString());
                err = START_PERMISSION_DENIED;
            }
        }

        final int userId = aInfo != null && aInfo.applicationInfo != null
                ? UserHandle.getUserId(aInfo.applicationInfo.uid) : 0;
        if (err == ActivityManager.START_SUCCESS) {
            Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true, true, false)
                    + "} from uid " + callingUid);
        }

        ActivityRecord sourceRecord = null;
        ActivityRecord resultRecord = null;
        if (resultTo != null) {
            sourceRecord = ActivityRecord.isInAnyTask(resultTo);
            if (DEBUG_RESULTS) {
                Slog.v(TAG_RESULTS, "Will send result to " + resultTo + " " + sourceRecord);
            }
            if (sourceRecord != null) {
                if (requestCode >= 0 && !sourceRecord.finishing) {
                    resultRecord = sourceRecord;
                }
            }
        }

        final int launchFlags = intent.getFlags();
        if ((launchFlags & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0 && sourceRecord != null) {
            // Transfer the result target from the source activity to the new one being started,
            // including any failures.
            if (requestCode >= 0) {
                SafeActivityOptions.abort(options);
                return ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
            }
            resultRecord = sourceRecord.resultTo;
            if (resultRecord != null && !resultRecord.isInRootTaskLocked()) {
                resultRecord = null;
            }
            resultWho = sourceRecord.resultWho;
            requestCode = sourceRecord.requestCode;
            sourceRecord.resultTo = null;
            if (resultRecord != null) {
                resultRecord.removeResultsLocked(sourceRecord, resultWho, requestCode);
            }
            if (sourceRecord.launchedFromUid == callingUid) {
                // The new activity is being launched from the same uid as the previous activity
                // in the flow, and asking to forward its result back to the previous.  In this
                // case the activity is serving as a trampoline between the two, so we also want
                // to update its launchedFromPackage to be the same as the previous activity.
                // Note that this is safe, since we know these two packages come from the same
                // uid; the caller could just as well have supplied that same package name itself
                // . This specifially deals with the case of an intent picker/chooser being
                // launched in the app flow to redirect to an activity picked by the user, where
                // we want the final activity to consider it to have been launched by the
                // previous app activity.
                callingPackage = sourceRecord.launchedFromPackage;
                callingFeatureId = sourceRecord.launchedFromFeatureId;
            }
        }

        if (err == ActivityManager.START_SUCCESS && intent.getComponent() == null) {
            // We couldn't find a class that can handle the given Intent.
            // That's the end of that!
            err = ActivityManager.START_INTENT_NOT_RESOLVED;
        }

        if (err == ActivityManager.START_SUCCESS && aInfo == null) {
            // We couldn't find the specific class specified in the Intent.
            // Also the end of the line.
            err = ActivityManager.START_CLASS_NOT_FOUND;
        }

        if (err == ActivityManager.START_SUCCESS && sourceRecord != null
                && sourceRecord.getTask().voiceSession != null) {
            // If this activity is being launched as part of a voice session, we need to ensure
            // that it is safe to do so.  If the upcoming activity will also be part of the voice
            // session, we can only launch it if it has explicitly said it supports the VOICE
            // category, or it is a part of the calling app.
            if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) == 0
                    && sourceRecord.info.applicationInfo.uid != aInfo.applicationInfo.uid) {
                try {
                    intent.addCategory(Intent.CATEGORY_VOICE);
                    if (!mService.getPackageManager().activitySupportsIntent(
                            intent.getComponent(), intent, resolvedType)) {
                        Slog.w(TAG, "Activity being started in current voice task does not support "
                                + "voice: " + intent);
                        err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                    }
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failure checking voice capabilities", e);
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            }
        }

        if (err == ActivityManager.START_SUCCESS && voiceSession != null) {
            // If the caller is starting a new voice session, just make sure the target
            // is actually allowing it to run this way.
            try {
                if (!mService.getPackageManager().activitySupportsIntent(intent.getComponent(),
                        intent, resolvedType)) {
                    Slog.w(TAG,
                            "Activity being started in new voice task does not support: " + intent);
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure checking voice capabilities", e);
                err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
            }
        }

        final Task resultRootTask = resultRecord == null
                ? null : resultRecord.getRootTask();

        if (err != START_SUCCESS) {
            if (resultRecord != null) {
                resultRecord.sendResult(INVALID_UID, resultWho, requestCode, RESULT_CANCELED,
                        null /* data */, null /* dataGrants */);
            }
            SafeActivityOptions.abort(options);
            return err;
        }

        boolean abort = !mSupervisor.checkStartAnyActivityPermission(intent, aInfo, resultWho,
                requestCode, callingPid, callingUid, callingPackage, callingFeatureId,
                request.ignoreTargetSecurity, inTask != null, callerApp, resultRecord,
                resultRootTask);
        abort |= !mService.mIntentFirewall.checkStartActivity(intent, callingUid,
                callingPid, resolvedType, aInfo.applicationInfo);
        abort |= !mService.getPermissionPolicyInternal().checkStartActivity(intent, callingUid,
                callingPackage);

        boolean restrictedBgActivity = false;
        if (!abort) {
            try {
                Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER,
                        "shouldAbortBackgroundActivityStart");
                restrictedBgActivity = shouldAbortBackgroundActivityStart(callingUid,
                        callingPid, callingPackage, realCallingUid, realCallingPid, callerApp,
                        request.originatingPendingIntent, request.allowBackgroundActivityStart,
                        intent);
            } finally {
                Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
            }
        }

        // Merge the two options bundles, while realCallerOptions takes precedence.
        ActivityOptions checkedOptions = options != null
                ? options.getOptions(intent, aInfo, callerApp, mSupervisor) : null;
        if (request.allowPendingRemoteAnimationRegistryLookup) {
            checkedOptions = mService.getActivityStartController()
                    .getPendingRemoteAnimationRegistry()
                    .overrideOptionsIfNeeded(callingPackage, checkedOptions);
        }
        if (mService.mController != null) {
            try {
                // The Intent we give to the watcher has the extra data stripped off, since it
                // can contain private information.
                Intent watchIntent = intent.cloneFilter();
                abort |= !mService.mController.activityStarting(watchIntent,
                        aInfo.applicationInfo.packageName);
            } catch (RemoteException e) {
                mService.mController = null;
            }
        }

        mInterceptor.setStates(userId, realCallingPid, realCallingUid, startFlags, callingPackage,
                callingFeatureId);
        if (mInterceptor.intercept(intent, rInfo, aInfo, resolvedType, inTask, callingPid,
                callingUid, checkedOptions)) {
            // activity start was intercepted, e.g. because the target user is currently in quiet
            // mode (turn off work) or the target application is suspended
            intent = mInterceptor.mIntent;
            rInfo = mInterceptor.mRInfo;
            aInfo = mInterceptor.mAInfo;
            resolvedType = mInterceptor.mResolvedType;
            inTask = mInterceptor.mInTask;
            callingPid = mInterceptor.mCallingPid;
            callingUid = mInterceptor.mCallingUid;
            checkedOptions = mInterceptor.mActivityOptions;

            // The interception target shouldn't get any permission grants
            // intended for the original destination
            intentGrants = null;
        }

        if (abort) {
            if (resultRecord != null) {
                resultRecord.sendResult(INVALID_UID, resultWho, requestCode, RESULT_CANCELED,
                        null /* data */, null /* dataGrants */);
            }
            // We pretend to the caller that it was really started, but they will just get a
            // cancel result.
            ActivityOptions.abort(checkedOptions);
            return START_ABORTED;
        }

        // If permissions need a review before any of the app components can run, we
        // launch the review activity and pass a pending intent to start the activity
        // we are to launching now after the review is completed.
        if (aInfo != null) {
            if (mService.getPackageManagerInternalLocked().isPermissionsReviewRequired(
                    aInfo.packageName, userId)) {
                final IIntentSender target = mService.getIntentSenderLocked(
                        ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage, callingFeatureId,
                        callingUid, userId, null, null, 0, new Intent[]{intent},
                        new String[]{resolvedType}, PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_ONE_SHOT, null);

                Intent newIntent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);

                int flags = intent.getFlags();
                flags |= Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;

                /*
                 * Prevent reuse of review activity: Each app needs their own review activity. By
                 * default activities launched with NEW_TASK or NEW_DOCUMENT try to reuse activities
                 * with the same launch parameters (extras are ignored). Hence to avoid possible
                 * reuse force a new activity via the MULTIPLE_TASK flag.
                 *
                 * Activities that are not launched with NEW_TASK or NEW_DOCUMENT are not re-used,
                 * hence no need to add the flag in this case.
                 */
                if ((flags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT)) != 0) {
                    flags |= Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
                }
                newIntent.setFlags(flags);

                newIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, aInfo.packageName);
                newIntent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));
                if (resultRecord != null) {
                    newIntent.putExtra(Intent.EXTRA_RESULT_NEEDED, true);
                }
                intent = newIntent;

                // The permissions review target shouldn't get any permission
                // grants intended for the original destination
                intentGrants = null;

                resolvedType = null;
                callingUid = realCallingUid;
                callingPid = realCallingPid;

                rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId, 0,
                        computeResolveFilterUid(
                                callingUid, realCallingUid, request.filterCallingUid));
                aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags,
                        null /*profilerInfo*/);

                if (DEBUG_PERMISSIONS_REVIEW) {
                    final Task focusedRootTask =
                            mRootWindowContainer.getTopDisplayFocusedRootTask();
                    Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true,
                            true, false) + "} from uid " + callingUid + " on display "
                            + (focusedRootTask == null ? DEFAULT_DISPLAY
                                    : focusedRootTask.getDisplayId()));
                }
            }
        }

        // If we have an ephemeral app, abort the process of launching the resolved intent.
        // Instead, launch the ephemeral installer. Once the installer is finished, it
        // starts either the intent we resolved here [on install error] or the ephemeral
        // app [on install success].
        if (rInfo != null && rInfo.auxiliaryInfo != null) {
            intent = createLaunchIntent(rInfo.auxiliaryInfo, request.ephemeralIntent,
                    callingPackage, callingFeatureId, verificationBundle, resolvedType, userId);
            resolvedType = null;
            callingUid = realCallingUid;
            callingPid = realCallingPid;

            // The ephemeral installer shouldn't get any permission grants
            // intended for the original destination
            intentGrants = null;

            aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, null /*profilerInfo*/);
        }
        // TODO (b/187680964) Correcting the caller/pid/uid when start activity from shortcut
        // Pending intent launched from systemui also depends on caller app
        if (callerApp == null && realCallingPid > 0) {
            final WindowProcessController wpc = mService.mProcessMap.getProcess(realCallingPid);
            if (wpc != null) {
                callerApp = wpc;
            }
        }
        final ActivityRecord r = new ActivityRecord.Builder(mService)
                .setCaller(callerApp)
                .setLaunchedFromPid(callingPid)
                .setLaunchedFromUid(callingUid)
                .setLaunchedFromPackage(callingPackage)
                .setLaunchedFromFeature(callingFeatureId)
                .setIntent(intent)
                .setResolvedType(resolvedType)
                .setActivityInfo(aInfo)
                .setConfiguration(mService.getGlobalConfiguration())
                .setResultTo(resultRecord)
                .setResultWho(resultWho)
                .setRequestCode(requestCode)
                .setComponentSpecified(request.componentSpecified)
                .setRootVoiceInteraction(voiceSession != null)
                .setActivityOptions(checkedOptions)
                .setSourceRecord(sourceRecord)
                .build();

        mLastStartActivityRecord = r;

        if (r.appTimeTracker == null && sourceRecord != null) {
            // If the caller didn't specify an explicit time tracker, we want to continue
            // tracking under any it has.
            r.appTimeTracker = sourceRecord.appTimeTracker;
        }

        // Only allow app switching to be resumed if activity is not a restricted background
        // activity and target app is not home process, otherwise any background activity
        // started in background task can stop home button protection mode.
        // As the targeted app is not a home process and we don't need to wait for the 2nd
        // activity to be started to resume app switching, we can just enable app switching
        // directly.
        WindowProcessController homeProcess = mService.mHomeProcess;
        boolean isHomeProcess = homeProcess != null
                && aInfo.applicationInfo.uid == homeProcess.mUid;
        if (!restrictedBgActivity && !isHomeProcess) {
            mService.resumeAppSwitches();
        }

        mLastStartActivityResult = startActivityUnchecked(r, sourceRecord, voiceSession,
                request.voiceInteractor, startFlags, true /* doResume */, checkedOptions,
                inTask, inTaskFragment, restrictedBgActivity, intentGrants);

        if (request.outActivity != null) {
            request.outActivity[0] = mLastStartActivityRecord;
        }

        return mLastStartActivityResult;
    }

    /**
     * Return true if background activity is really aborted.
     *
     * TODO(b/131748165): Refactor the logic so we don't need to call this method everywhere.
     */
    private boolean handleBackgroundActivityAbort(ActivityRecord r) {
        // TODO(b/131747138): Remove toast and refactor related code in R release.
        final boolean abort = !mService.isBackgroundActivityStartsEnabled();
        if (!abort) {
            return false;
        }
        final ActivityRecord resultRecord = r.resultTo;
        final String resultWho = r.resultWho;
        int requestCode = r.requestCode;
        if (resultRecord != null) {
            resultRecord.sendResult(INVALID_UID, resultWho, requestCode, RESULT_CANCELED,
                    null /* data */, null /* dataGrants */);
        }
        // We pretend to the caller that it was really started to make it backward compatible, but
        // they will just get a cancel result.
        ActivityOptions.abort(r.getOptions());
        return true;
    }

    static int getExternalResult(int result) {
        // Aborted results are treated as successes externally, but we must track them internally.
        return result != START_ABORTED ? result : START_SUCCESS;
    }

    /**
     * Called when execution is complete. Sets state indicating completion and proceeds with
     * recycling if appropriate.
     */
    private void onExecutionComplete() {
        mController.onExecutionComplete(this);
    }

    private boolean isHomeApp(int uid, @Nullable String packageName) {
        if (mService.mHomeProcess != null) {
            // Fast check
            return uid == mService.mHomeProcess.mUid;
        }
        if (packageName == null) {
            return false;
        }
        ComponentName activity =
                mService.getPackageManagerInternalLocked().getDefaultHomeActivity(
                        UserHandle.getUserId(uid));
        return activity != null && packageName.equals(activity.getPackageName());
    }

    boolean shouldAbortBackgroundActivityStart(int callingUid, int callingPid,
            final String callingPackage, int realCallingUid, int realCallingPid,
            WindowProcessController callerApp, PendingIntentRecord originatingPendingIntent,
            boolean allowBackgroundActivityStart, Intent intent) {
        // don't abort for the most important UIDs
        final int callingAppId = UserHandle.getAppId(callingUid);
        if (callingUid == Process.ROOT_UID || callingAppId == Process.SYSTEM_UID
                || callingAppId == Process.NFC_UID) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Activity start allowed for important callingUid (" + callingUid + ")");
            }
            return false;
        }

        // Always allow home application to start activities.
        if (isHomeApp(callingUid, callingPackage)) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Activity start allowed for home app callingUid (" + callingUid + ")");
            }
            return false;
        }

        // IME should always be allowed to start activity, like IME settings.
        final WindowState imeWindow = mRootWindowContainer.getCurrentInputMethodWindow();
        if (imeWindow != null && callingAppId == imeWindow.mOwnerUid) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Activity start allowed for active ime (" + callingUid + ")");
            }
            return false;
        }

        // This is used to block background activity launch even if the app is still
        // visible to user after user clicking home button.
        final boolean appSwitchAllowed = mService.getBalAppSwitchesAllowed();

        // don't abort if the callingUid has a visible window or is a persistent system process
        final int callingUidProcState = mService.mActiveUids.getUidState(callingUid);
        final boolean callingUidHasAnyVisibleWindow = mService.hasActiveVisibleWindow(callingUid);
        final boolean isCallingUidForeground = callingUidHasAnyVisibleWindow
                || callingUidProcState == ActivityManager.PROCESS_STATE_TOP
                || callingUidProcState == ActivityManager.PROCESS_STATE_BOUND_TOP;
        final boolean isCallingUidPersistentSystemProcess =
                callingUidProcState <= ActivityManager.PROCESS_STATE_PERSISTENT_UI;

        // Normal apps with visible app window will be allowed to start activity if app switching
        // is allowed, or apps like live wallpaper with non app visible window will be allowed.
        if (((appSwitchAllowed || mService.mActiveUids.hasNonAppVisibleWindow(callingUid))
                && callingUidHasAnyVisibleWindow)
                || isCallingUidPersistentSystemProcess) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Activity start allowed: callingUidHasAnyVisibleWindow = " + callingUid
                        + ", isCallingUidPersistentSystemProcess = "
                        + isCallingUidPersistentSystemProcess);
            }
            return false;
        }
        // take realCallingUid into consideration
        final int realCallingUidProcState = (callingUid == realCallingUid)
                ? callingUidProcState
                : mService.mActiveUids.getUidState(realCallingUid);
        final boolean realCallingUidHasAnyVisibleWindow = (callingUid == realCallingUid)
                ? callingUidHasAnyVisibleWindow
                : mService.hasActiveVisibleWindow(realCallingUid);
        final boolean isRealCallingUidForeground = (callingUid == realCallingUid)
                ? isCallingUidForeground
                : realCallingUidHasAnyVisibleWindow
                        || realCallingUidProcState == ActivityManager.PROCESS_STATE_TOP;
        final int realCallingAppId = UserHandle.getAppId(realCallingUid);
        final boolean isRealCallingUidPersistentSystemProcess = (callingUid == realCallingUid)
                ? isCallingUidPersistentSystemProcess
                : (realCallingAppId == Process.SYSTEM_UID)
                        || realCallingUidProcState <= ActivityManager.PROCESS_STATE_PERSISTENT_UI;
        if (realCallingUid != callingUid) {
            // don't abort if the realCallingUid has a visible window
            // TODO(b/171459802): We should check appSwitchAllowed also
            if (realCallingUidHasAnyVisibleWindow) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "Activity start allowed: realCallingUid (" + realCallingUid
                            + ") has visible (non-toast) window");
                }
                return false;
            }
            // if the realCallingUid is a persistent system process, abort if the IntentSender
            // wasn't allowed to start an activity
            if (isRealCallingUidPersistentSystemProcess && allowBackgroundActivityStart) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "Activity start allowed: realCallingUid (" + realCallingUid
                            + ") is persistent system process AND intent sender allowed "
                            + "(allowBackgroundActivityStart = true)");
                }
                return false;
            }
            // don't abort if the realCallingUid is an associated companion app
            if (mService.isAssociatedCompanionApp(UserHandle.getUserId(realCallingUid),
                    realCallingUid)) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "Activity start allowed: realCallingUid (" + realCallingUid
                            + ") is companion app");
                }
                return false;
            }
        }
        // don't abort if the callingUid has START_ACTIVITIES_FROM_BACKGROUND permission
        if (mService.checkPermission(START_ACTIVITIES_FROM_BACKGROUND, callingPid, callingUid)
                == PERMISSION_GRANTED) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG,
                        "Background activity start allowed: START_ACTIVITIES_FROM_BACKGROUND "
                                + "permission granted for uid "
                                + callingUid);
            }
            return false;
        }
        // don't abort if the caller has the same uid as the recents component
        if (mSupervisor.mRecentTasks.isCallerRecents(callingUid)) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Background activity start allowed: callingUid (" + callingUid
                        + ") is recents");
            }
            return false;
        }
        // don't abort if the callingUid is the device owner
        if (mService.isDeviceOwner(callingUid)) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Background activity start allowed: callingUid (" + callingUid
                        + ") is device owner");
            }
            return false;
        }
        // don't abort if the callingUid has companion device
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (mService.isAssociatedCompanionApp(callingUserId, callingUid)) {
            if (DEBUG_ACTIVITY_STARTS) {
                Slog.d(TAG, "Background activity start allowed: callingUid (" + callingUid
                        + ") is companion app");
            }
            return false;
        }
        // don't abort if the callingUid has SYSTEM_ALERT_WINDOW permission
        if (mService.hasSystemAlertWindowPermission(callingUid, callingPid, callingPackage)) {
            Slog.w(TAG, "Background activity start for " + callingPackage
                    + " allowed because SYSTEM_ALERT_WINDOW permission is granted.");
            return false;
        }
        // If we don't have callerApp at this point, no caller was provided to startActivity().
        // That's the case for PendingIntent-based starts, since the creator's process might not be
        // up and alive. If that's the case, we retrieve the WindowProcessController for the send()
        // caller, so that we can make the decision based on its state.
        int callerAppUid = callingUid;
        if (callerApp == null) {
            callerApp = mService.getProcessController(realCallingPid, realCallingUid);
            callerAppUid = realCallingUid;
        }
        // don't abort if the callerApp or other processes of that uid are allowed in any way
        if (callerApp != null) {
            // first check the original calling process
            if (callerApp.areBackgroundActivityStartsAllowed(appSwitchAllowed)) {
                if (DEBUG_ACTIVITY_STARTS) {
                    Slog.d(TAG, "Background activity start allowed: callerApp process (pid = "
                            + callerApp.getPid() + ", uid = " + callerAppUid + ") is allowed");
                }
                return false;
            }
            // only if that one wasn't allowed, check the other ones
            final ArraySet<WindowProcessController> uidProcesses =
                    mService.mProcessMap.getProcesses(callerAppUid);
            if (uidProcesses != null) {
                for (int i = uidProcesses.size() - 1; i >= 0; i--) {
                    final WindowProcessController proc = uidProcesses.valueAt(i);
                    if (proc != callerApp
                            && proc.areBackgroundActivityStartsAllowed(appSwitchAllowed)) {
                        if (DEBUG_ACTIVITY_STARTS) {
                            Slog.d(TAG,
                                    "Background activity start allowed: process " + proc.getPid()
                                            + " from uid " + callerAppUid + " is allowed");
                        }
                        return false;
                    }
                }
            }
        }
        // anything that has fallen through would currently be aborted
        Slog.w(TAG, "Background activity start [callingPackage: " + callingPackage
                + "; callingUid: " + callingUid
                + "; appSwitchAllowed: " + appSwitchAllowed
                + "; isCallingUidForeground: " + isCallingUidForeground
                + "; callingUidHasAnyVisibleWindow: " + callingUidHasAnyVisibleWindow
                + "; callingUidProcState: " + DebugUtils.valueToString(ActivityManager.class,
                "PROCESS_STATE_", callingUidProcState)
                + "; isCallingUidPersistentSystemProcess: " + isCallingUidPersistentSystemProcess
                + "; realCallingUid: " + realCallingUid
                + "; isRealCallingUidForeground: " + isRealCallingUidForeground
                + "; realCallingUidHasAnyVisibleWindow: " + realCallingUidHasAnyVisibleWindow
                + "; realCallingUidProcState: " + DebugUtils.valueToString(ActivityManager.class,
                "PROCESS_STATE_", realCallingUidProcState)
                + "; isRealCallingUidPersistentSystemProcess: "
                + isRealCallingUidPersistentSystemProcess
                + "; originatingPendingIntent: " + originatingPendingIntent
                + "; allowBackgroundActivityStart: " + allowBackgroundActivityStart
                + "; intent: " + intent
                + "; callerApp: " + callerApp
                + "; inVisibleTask: " + (callerApp != null && callerApp.hasActivityInVisibleTask())
                + "]");
        // log aborted activity start to TRON
        if (mService.isActivityStartsLoggingEnabled()) {
            mSupervisor.getActivityMetricsLogger().logAbortedBgActivityStart(intent, callerApp,
                    callingUid, callingPackage, callingUidProcState, callingUidHasAnyVisibleWindow,
                    realCallingUid, realCallingUidProcState, realCallingUidHasAnyVisibleWindow,
                    (originatingPendingIntent != null));
        }
        return true;
    }

    /**
     * Creates a launch intent for the given auxiliary resolution data.
     */
    private @NonNull Intent createLaunchIntent(@Nullable AuxiliaryResolveInfo auxiliaryResponse,
            Intent originalIntent, String callingPackage, @Nullable String callingFeatureId,
            Bundle verificationBundle, String resolvedType, int userId) {
        if (auxiliaryResponse != null && auxiliaryResponse.needsPhaseTwo) {
            // request phase two resolution
            PackageManagerInternal packageManager = mService.getPackageManagerInternalLocked();
            boolean isRequesterInstantApp = packageManager.isInstantApp(callingPackage, userId);
            packageManager.requestInstantAppResolutionPhaseTwo(
                    auxiliaryResponse, originalIntent, resolvedType, callingPackage,
                    callingFeatureId, isRequesterInstantApp, verificationBundle, userId);
        }
        return InstantAppResolver.buildEphemeralInstallerIntent(
                originalIntent,
                InstantAppResolver.sanitizeIntent(originalIntent),
                auxiliaryResponse == null ? null : auxiliaryResponse.failureIntent,
                callingPackage,
                callingFeatureId,
                verificationBundle,
                resolvedType,
                userId,
                auxiliaryResponse == null ? null : auxiliaryResponse.installFailureActivity,
                auxiliaryResponse == null ? null : auxiliaryResponse.token,
                auxiliaryResponse != null && auxiliaryResponse.needsPhaseTwo,
                auxiliaryResponse == null ? null : auxiliaryResponse.filters);
    }

    void postStartActivityProcessing(ActivityRecord r, int result,
            Task startedActivityRootTask) {
        if (!ActivityManager.isStartResultSuccessful(result)) {
            if (mFrozeTaskList) {
                // If we specifically froze the task list as part of starting an activity, then
                // reset the frozen list state if it failed to start. This is normally otherwise
                // called when the freeze-timeout has elapsed.
                mSupervisor.mRecentTasks.resetFreezeTaskListReorderingOnTimeout();
            }
        }
        if (ActivityManager.isStartResultFatalError(result)) {
            return;
        }

        // We're waiting for an activity launch to finish, but that activity simply
        // brought another activity to front. We must also handle the case where the task is already
        // in the front as a result of the trampoline activity being in the same task (it will be
        // considered focused as the trampoline will be finished). Let them know about this, so
        // it waits for the new activity to become visible instead, {@link #waitResultIfNeeded}.
        mSupervisor.reportWaitingActivityLaunchedIfNeeded(r, result);

        final Task targetTask = r.getTask() != null
                ? r.getTask()
                : mTargetTask;
        if (startedActivityRootTask == null || targetTask == null || !targetTask.isAttached()) {
            return;
        }

        final int clearTaskFlags = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK;
        boolean clearedTask = (mLaunchFlags & clearTaskFlags) == clearTaskFlags
                && mReuseTask != null;
        if (result == START_TASK_TO_FRONT || result == START_DELIVERED_TO_TOP || clearedTask) {
            // The activity was already running so it wasn't started, but either brought to the
            // front or the new intent was delivered to it since it was already in front. Notify
            // anyone interested in this piece of information.
            final Task rootHomeTask = targetTask.getDisplayArea().getRootHomeTask();
            final boolean homeTaskVisible = rootHomeTask != null
                    && rootHomeTask.shouldBeVisible(null);
            final ActivityRecord top = targetTask.getTopNonFinishingActivity();
            final boolean visible = top != null && top.isVisible();
            mService.getTaskChangeNotificationController().notifyActivityRestartAttempt(
                    targetTask.getTaskInfo(), homeTaskVisible, clearedTask, visible);
        }
    }

    /**
     * Compute the logical UID based on which the package manager would filter
     * app components i.e. based on which the instant app policy would be applied
     * because it is the logical calling UID.
     *
     * @param customCallingUid The UID on whose behalf to make the call.
     * @param actualCallingUid The UID actually making the call.
     * @param filterCallingUid The UID to be used to filter for instant apps.
     * @return The logical UID making the call.
     */
    static int computeResolveFilterUid(int customCallingUid, int actualCallingUid,
            int filterCallingUid) {
        return filterCallingUid != UserHandle.USER_NULL
                ? filterCallingUid
                : (customCallingUid >= 0 ? customCallingUid : actualCallingUid);
    }

    /**
     * Start an activity while most of preliminary checks has been done and caller has been
     * confirmed that holds necessary permissions to do so.
     * Here also ensures that the starting activity is removed if the start wasn't successful.
     */
    private int startActivityUnchecked(final ActivityRecord r, ActivityRecord sourceRecord,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            int startFlags, boolean doResume, ActivityOptions options, Task inTask,
            TaskFragment inTaskFragment, boolean restrictedBgActivity,
            NeededUriGrants intentGrants) {
        int result = START_CANCELED;
        boolean startResultSuccessful = false;
        final Task startedActivityRootTask;

        // Create a transition now to record the original intent of actions taken within
        // startActivityInner. Otherwise, logic in startActivityInner could start a different
        // transition based on a sub-action.
        // Only do the create here (and defer requestStart) since startActivityInner might abort.
        final Transition newTransition = (!mService.getTransitionController().isCollecting()
                && mService.getTransitionController().getTransitionPlayer() != null)
                ? mService.getTransitionController().createTransition(TRANSIT_OPEN) : null;
        RemoteTransition remoteTransition = r.takeRemoteTransition();
        if (newTransition != null && remoteTransition != null) {
            newTransition.setRemoteTransition(remoteTransition);
        }
        mService.getTransitionController().collect(r);
        final boolean isTransient = r.getOptions() != null && r.getOptions().getTransientLaunch();
        try {
            mService.deferWindowLayout();
            Trace.traceBegin(Trace.TRACE_TAG_WINDOW_MANAGER, "startActivityInner");
            result = startActivityInner(r, sourceRecord, voiceSession, voiceInteractor,
                    startFlags, doResume, options, inTask, inTaskFragment, restrictedBgActivity,
                    intentGrants);
            startResultSuccessful = ActivityManager.isStartResultSuccessful(result);
            final boolean taskAlwaysOnTop = options != null && options.getTaskAlwaysOnTop();
            // Apply setAlwaysOnTop when starting an Activity is successful regardless of creating
            // a new Activity or recycling the existing Activity.
            if (taskAlwaysOnTop && startResultSuccessful) {
                final Task targetRootTask =
                        mTargetRootTask != null ? mTargetRootTask : mTargetTask.getRootTask();
                targetRootTask.setAlwaysOnTop(true);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_WINDOW_MANAGER);
            startedActivityRootTask = handleStartResult(r, result);
            mService.continueWindowLayout();
            mSupervisor.mUserLeaving = false;

            // Transition housekeeping
            if (!startResultSuccessful) {
                if (newTransition != null) {
                    newTransition.abort();
                }
            } else {
                if (!mAvoidMoveToFront && mDoResume
                        && mRootWindowContainer.hasVisibleWindowAboveButDoesNotOwnNotificationShade(
                            r.launchedFromUid)) {
                    // If the UID launching the activity has a visible window on top of the
                    // notification shade and it's launching an activity that's going to be at the
                    // front, we should move the shade out of the way so the user can see it.
                    // We want to avoid the case where the activity is launched on top of a
                    // background task which is not moved to the front.
                    StatusBarManagerInternal statusBar = mService.getStatusBarManagerInternal();
                    if (statusBar != null) {
                        // This results in a async call since the interface is one-way
                        statusBar.collapsePanels();
                    }
                }
                final boolean started = result == START_SUCCESS || result == START_TASK_TO_FRONT;
                if (started) {
                    // The activity is started new rather than just brought forward, so record
                    // it as an existence change.
                    mService.getTransitionController().collectExistenceChange(r);
                }
                if (isTransient) {
                    // `r` isn't guaranteed to be the actual relevant activity, so we must wait
                    // until after we launched to identify the relevant activity.
                    mService.getTransitionController().setTransientLaunch(mLastStartActivityRecord);
                }
                if (newTransition != null) {
                    mService.getTransitionController().requestStartTransition(newTransition,
                            mTargetTask, remoteTransition);
                } else if (started) {
                    // Make the collecting transition wait until this request is ready.
                    mService.getTransitionController().setReady(r, false);
                }
            }
        }

        postStartActivityProcessing(r, result, startedActivityRootTask);

        return result;
    }

    /**
     * If the start result is success, ensure that the configuration of the started activity matches
     * the current display. Otherwise clean up unassociated containers to avoid leakage.
     *
     * @return the root task where the successful started activity resides.
     */
    private @Nullable Task handleStartResult(@NonNull ActivityRecord started, int result) {
        final Task currentRootTask = started.getRootTask();
        Task startedActivityRootTask = currentRootTask != null ? currentRootTask : mTargetRootTask;

        if (ActivityManager.isStartResultSuccessful(result)) {
            if (startedActivityRootTask != null) {
                // If there is no state change (e.g. a resumed activity is reparented to top of
                // another display) to trigger a visibility/configuration checking, we have to
                // update the configuration for changing to different display.
                final ActivityRecord currentTop = startedActivityRootTask.topRunningActivity();
                if (currentTop != null && currentTop.shouldUpdateConfigForDisplayChanged()) {
                    mRootWindowContainer.ensureVisibilityAndConfig(
                            currentTop, currentTop.getDisplayId(),
                            true /* markFrozenIfConfigChanged */, false /* deferResume */);
                }
            }
            return startedActivityRootTask;
        }

        // If we are not able to proceed, disassociate the activity from the task. Leaving an
        // activity in an incomplete state can lead to issues, such as performing operations
        // without a window container.
        final Task rootTask = mStartActivity.getRootTask();
        if (rootTask != null) {
            mStartActivity.finishIfPossible("startActivity", true /* oomAdj */);
        }

        // Root task should also be detached from display and be removed if it's empty.
        if (startedActivityRootTask != null && startedActivityRootTask.isAttached()
                && !startedActivityRootTask.hasActivity()
                && !startedActivityRootTask.isActivityTypeHome()) {
            startedActivityRootTask.removeIfPossible("handleStartResult");
            startedActivityRootTask = null;
        }
        return startedActivityRootTask;
    }

    /**
     * Start an activity and determine if the activity should be adding to the top of an existing
     * task or delivered new intent to an existing activity. Also manipulating the activity task
     * onto requested or valid root-task/display.
     *
     * Note: This method should only be called from {@link #startActivityUnchecked}.
     */
    // TODO(b/152429287): Make it easier to exercise code paths through startActivityInner
    @VisibleForTesting
    int startActivityInner(final ActivityRecord r, ActivityRecord sourceRecord,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            int startFlags, boolean doResume, ActivityOptions options, Task inTask,
            TaskFragment inTaskFragment, boolean restrictedBgActivity,
            NeededUriGrants intentGrants) {
        setInitialState(r, options, inTask, inTaskFragment, doResume, startFlags, sourceRecord,
                voiceSession, voiceInteractor, restrictedBgActivity);

        computeLaunchingTaskFlags();

        computeSourceRootTask();

        mIntent.setFlags(mLaunchFlags);

        // Get top task at beginning because the order may be changed when reusing existing task.
        final Task prevTopTask = mPreferredTaskDisplayArea.getFocusedRootTask();
        final Task reusedTask = getReusableTask();

        // If requested, freeze the task list
        if (mOptions != null && mOptions.freezeRecentTasksReordering()
                && mSupervisor.mRecentTasks.isCallerRecents(r.launchedFromUid)
                && !mSupervisor.mRecentTasks.isFreezeTaskListReorderingSet()) {
            mFrozeTaskList = true;
            mSupervisor.mRecentTasks.setFreezeTaskListReordering();
        }

        // Compute if there is an existing task that should be used for.
        final Task targetTask = reusedTask != null ? reusedTask : computeTargetTask();
        final boolean newTask = targetTask == null;
        mTargetTask = targetTask;

        computeLaunchParams(r, sourceRecord, targetTask);

        // Check if starting activity on given task or on a new task is allowed.
        int startResult = isAllowedToStart(r, newTask, targetTask);
        if (startResult != START_SUCCESS) {
            return startResult;
        }

        final ActivityRecord targetTaskTop = newTask
                ? null : targetTask.getTopNonFinishingActivity();
        if (targetTaskTop != null) {
            // Recycle the target task for this launch.
            startResult = recycleTask(targetTask, targetTaskTop, reusedTask, intentGrants);
            if (startResult != START_SUCCESS) {
                return startResult;
            }
        } else {
            mAddingToTask = true;
        }

        // If the activity being launched is the same as the one currently at the top, then
        // we need to check if it should only be launched once.
        final Task topRootTask = mPreferredTaskDisplayArea.getFocusedRootTask();
        if (topRootTask != null) {
            startResult = deliverToCurrentTopIfNeeded(topRootTask, intentGrants);
            if (startResult != START_SUCCESS) {
                return startResult;
            }
        }

        if (mTargetRootTask == null) {
            mTargetRootTask = getLaunchRootTask(mStartActivity, mLaunchFlags, targetTask, mOptions);
        }
        if (newTask) {
            final Task taskToAffiliate = (mLaunchTaskBehind && mSourceRecord != null)
                    ? mSourceRecord.getTask() : null;
            setNewTask(taskToAffiliate);
        } else if (mAddingToTask) {
            addOrReparentStartingActivity(targetTask, "adding to task");
        }

        if (!mAvoidMoveToFront && mDoResume) {
            mTargetRootTask.getRootTask().moveToFront("reuseOrNewTask", targetTask);
            if (!mTargetRootTask.isTopRootTaskInDisplayArea() && mService.mInternal.isDreaming()) {
                // Launching underneath dream activity (fullscreen, always-on-top). Run the launch-
                // -behind transition so the Activity gets created and starts in visible state.
                mLaunchTaskBehind = true;
                r.mLaunchTaskBehind = true;
            }
        }

        mService.mUgmInternal.grantUriPermissionUncheckedFromIntent(intentGrants,
                mStartActivity.getUriPermissionsLocked());
        if (mStartActivity.resultTo != null && mStartActivity.resultTo.info != null) {
            // we need to resolve resultTo to a uid as grantImplicitAccess deals explicitly in UIDs
            final PackageManagerInternal pmInternal =
                    mService.getPackageManagerInternalLocked();
            final int resultToUid = pmInternal.getPackageUid(
                    mStartActivity.resultTo.info.packageName, 0 /* flags */,
                    mStartActivity.mUserId);
            pmInternal.grantImplicitAccess(mStartActivity.mUserId, mIntent,
                    UserHandle.getAppId(mStartActivity.info.applicationInfo.uid) /*recipient*/,
                    resultToUid /*visible*/, true /*direct*/);
        }
        final Task startedTask = mStartActivity.getTask();
        if (newTask) {
            EventLogTags.writeWmCreateTask(mStartActivity.mUserId, startedTask.mTaskId);
        }
        mStartActivity.logStartActivity(EventLogTags.WM_CREATE_ACTIVITY, startedTask);

        mStartActivity.getTaskFragment().clearLastPausedActivity();

        mRootWindowContainer.startPowerModeLaunchIfNeeded(
                false /* forceSend */, mStartActivity);

        final boolean isTaskSwitch = startedTask != prevTopTask && !startedTask.isEmbedded();
        mTargetRootTask.startActivityLocked(mStartActivity,
                topRootTask != null ? topRootTask.getTopNonFinishingActivity() : null, newTask,
                isTaskSwitch, mOptions, sourceRecord);
        if (mDoResume) {
            final ActivityRecord topTaskActivity = startedTask.topRunningActivityLocked();
            if (!mTargetRootTask.isTopActivityFocusable()
                    || (topTaskActivity != null && topTaskActivity.isTaskOverlay()
                    && mStartActivity != topTaskActivity)) {
                // If the activity is not focusable, we can't resume it, but still would like to
                // make sure it becomes visible as it starts (this will also trigger entry
                // animation). An example of this are PIP activities.
                // Also, we don't want to resume activities in a task that currently has an overlay
                // as the starting activity just needs to be in the visible paused state until the
                // over is removed.
                // Passing {@code null} as the start parameter ensures all activities are made
                // visible.
                mTargetRootTask.ensureActivitiesVisible(null /* starting */,
                        0 /* configChanges */, !PRESERVE_WINDOWS);
                // Go ahead and tell window manager to execute app transition for this activity
                // since the app transition will not be triggered through the resume channel.
                mTargetRootTask.mDisplayContent.executeAppTransition();
            } else {
                // If the target root-task was not previously focusable (previous top running
                // activity on that root-task was not visible) then any prior calls to move the
                // root-task to the will not update the focused root-task.  If starting the new
                // activity now allows the task root-task to be focusable, then ensure that we
                // now update the focused root-task accordingly.
                if (mTargetRootTask.isTopActivityFocusable()
                        && !mRootWindowContainer.isTopDisplayFocusedRootTask(mTargetRootTask)) {
                    mTargetRootTask.moveToFront("startActivityInner");
                }
                mRootWindowContainer.resumeFocusedTasksTopActivities(
                        mTargetRootTask, mStartActivity, mOptions, mTransientLaunch);
            }
        }
        mRootWindowContainer.updateUserRootTask(mStartActivity.mUserId, mTargetRootTask);

        // Update the recent tasks list immediately when the activity starts
        mSupervisor.mRecentTasks.add(startedTask);
        mSupervisor.handleNonResizableTaskIfNeeded(startedTask,
                mPreferredWindowingMode, mPreferredTaskDisplayArea, mTargetRootTask);

        return START_SUCCESS;
    }

    private Task computeTargetTask() {
        if (mStartActivity.resultTo == null && mInTask == null && !mAddingToTask
                && (mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            // A new task should be created instead of using existing one.
            return null;
        } else if (mSourceRecord != null) {
            return mSourceRecord.getTask();
        } else if (mInTask != null) {
            return mInTask;
        } else {
            final Task rootTask = getLaunchRootTask(mStartActivity, mLaunchFlags, null /* task */,
                    mOptions);
            final ActivityRecord top = rootTask.getTopNonFinishingActivity();
            if (top != null) {
                return top.getTask();
            } else {
                // Remove the root task if no activity in the root task.
                rootTask.removeIfPossible("computeTargetTask");
            }
        }
        return null;
    }

    private void computeLaunchParams(ActivityRecord r, ActivityRecord sourceRecord,
            Task targetTask) {
        final Task sourceRootTask = mSourceRootTask != null ? mSourceRootTask
                : mRootWindowContainer.getTopDisplayFocusedRootTask();
        if (sourceRootTask != null && sourceRootTask.inSplitScreenWindowingMode()
                && (mOptions == null
                        || mOptions.getLaunchWindowingMode() == WINDOWING_MODE_UNDEFINED)) {
            int windowingMode =
                    targetTask != null ? targetTask.getWindowingMode() : WINDOWING_MODE_UNDEFINED;
            if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
                if (sourceRootTask.inSplitScreenPrimaryWindowingMode()) {
                    windowingMode = WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
                } else if (sourceRootTask.inSplitScreenSecondaryWindowingMode()) {
                    windowingMode = WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
                }
            }

            if (mOptions == null) {
                mOptions = ActivityOptions.makeBasic();
            }
            mOptions.setLaunchWindowingMode(windowingMode);
        }

        mSupervisor.getLaunchParamsController().calculate(targetTask, r.info.windowLayout, r,
                sourceRecord, mOptions, mRequest, PHASE_BOUNDS, mLaunchParams);
        mPreferredTaskDisplayArea = mLaunchParams.hasPreferredTaskDisplayArea()
                ? mLaunchParams.mPreferredTaskDisplayArea
                : mRootWindowContainer.getDefaultTaskDisplayArea();
        mPreferredWindowingMode = mLaunchParams.mWindowingMode;
    }

    private int isAllowedToStart(ActivityRecord r, boolean newTask, Task targetTask) {
        if (mStartActivity.packageName == null) {
            if (mStartActivity.resultTo != null) {
                mStartActivity.resultTo.sendResult(INVALID_UID, mStartActivity.resultWho,
                        mStartActivity.requestCode, RESULT_CANCELED,
                        null /* data */, null /* dataGrants */);
            }
            ActivityOptions.abort(mOptions);
            return START_CLASS_NOT_FOUND;
        }

        // Do not start home activity if it cannot be launched on preferred display. We are not
        // doing this in ActivityTaskSupervisor#canPlaceEntityOnDisplay because it might
        // fallback to launch on other displays.
        if (r.isActivityTypeHome()) {
            if (!mRootWindowContainer.canStartHomeOnDisplayArea(r.info, mPreferredTaskDisplayArea,
                    true /* allowInstrumenting */)) {
                Slog.w(TAG, "Cannot launch home on display area " + mPreferredTaskDisplayArea);
                return START_CANCELED;
            }
        }

        // Do not allow background activity start in new task or in a task that uid is not present.
        // Also do not allow pinned window to start single instance activity in background,
        // as it will recreate the window and makes it to foreground.
        boolean blockBalInTask = (newTask
                || !targetTask.isUidPresent(mCallingUid)
                || (LAUNCH_SINGLE_INSTANCE == mLaunchMode && targetTask.inPinnedWindowingMode()));

        if (mRestrictedBgActivity && blockBalInTask
                && handleBackgroundActivityAbort(mStartActivity)) {
            Slog.e(TAG, "Abort background activity starts from " + mCallingUid);
            return START_ABORTED;
        }

        // When the flags NEW_TASK and CLEAR_TASK are set, then the task gets reused but still
        // needs to be a lock task mode violation since the task gets cleared out and the device
        // would otherwise leave the locked task.
        final boolean isNewClearTask =
                (mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                        == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
        if (!newTask) {
            if (mService.getLockTaskController().isLockTaskModeViolation(targetTask,
                    isNewClearTask)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
                return START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
        } else {
            if (mService.getLockTaskController().isNewTaskLockTaskModeViolation(mStartActivity)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
                return START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
        }

        if (mInTaskFragment != null && mInTaskFragment.getTask() != null) {
            final int hostUid = mInTaskFragment.getTask().effectiveUid;
            final int embeddingUid = targetTask != null ? targetTask.effectiveUid : r.getUid();
            if (!canTaskBeEmbedded(hostUid, embeddingUid)) {
                Slog.e(TAG, "Cannot embed activity to a task owned by " + hostUid + " targetTask= "
                        + targetTask);
                return START_PERMISSION_DENIED;
            }
        }

        return START_SUCCESS;
    }

    /**
     * Return {@code true} if the {@param task} can embed another task.
     * @param hostUid the uid of the host task
     * @param embeddedUid the uid of the task the are going to be embedded
     */
    private boolean canTaskBeEmbedded(int hostUid, int embeddedUid) {
        // Allowing the embedding if the task is owned by system.
        if (hostUid == Process.SYSTEM_UID) {
            return true;
        }

        // Allowing embedding if the host task is owned by an app that has the ACTIVITY_EMBEDDING
        // permission
        if (mService.checkPermission(ACTIVITY_EMBEDDING, -1, hostUid) == PERMISSION_GRANTED) {
            return true;
        }

        // Allowing embedding if it is from the same app that owned the task
        return hostUid == embeddedUid;
    }

    /**
     * Prepare the target task to be reused for this launch, which including:
     * - Position the target task on valid root task on preferred display.
     * - Comply to the specified activity launch flags
     * - Determine whether need to add a new activity on top or just brought the task to front.
     */
    @VisibleForTesting
    int recycleTask(Task targetTask, ActivityRecord targetTaskTop, Task reusedTask,
            NeededUriGrants intentGrants) {
        // Should not recycle task which is from a different user, just adding the starting
        // activity to the task.
        if (targetTask.mUserId != mStartActivity.mUserId) {
            mTargetRootTask = targetTask.getRootTask();
            mAddingToTask = true;
            return START_SUCCESS;
        }

        if (reusedTask != null) {
            if (targetTask.intent == null) {
                // This task was started because of movement of the activity based on
                // affinity...
                // Now that we are actually launching it, we can assign the base intent.
                targetTask.setIntent(mStartActivity);
            } else {
                final boolean taskOnHome =
                        (mStartActivity.intent.getFlags() & FLAG_ACTIVITY_TASK_ON_HOME) != 0;
                if (taskOnHome) {
                    targetTask.intent.addFlags(FLAG_ACTIVITY_TASK_ON_HOME);
                } else {
                    targetTask.intent.removeFlags(FLAG_ACTIVITY_TASK_ON_HOME);
                }
            }
        }

        mRootWindowContainer.startPowerModeLaunchIfNeeded(false /* forceSend */,
                targetTaskTop);

        setTargetRootTaskIfNeeded(targetTaskTop);

        // When there is a reused activity and the current result is a trampoline activity,
        // set the reused activity as the result.
        if (mLastStartActivityRecord != null
                && (mLastStartActivityRecord.finishing || mLastStartActivityRecord.noDisplay)) {
            mLastStartActivityRecord = targetTaskTop;
        }

        if ((mStartFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
            // We don't need to start a new activity, and the client said not to do anything
            // if that is the case, so this is it!  And for paranoia, make sure we have
            // correctly resumed the top activity.
            if (!mMovedToFront && mDoResume) {
                ProtoLog.d(WM_DEBUG_TASKS, "Bring to front target: %s from %s", mTargetRootTask,
                        targetTaskTop);
                mTargetRootTask.moveToFront("intentActivityFound");
            }
            resumeTargetRootTaskIfNeeded();
            return START_RETURN_INTENT_TO_CALLER;
        }

        complyActivityFlags(targetTask,
                reusedTask != null ? reusedTask.getTopNonFinishingActivity() : null, intentGrants);

        if (mAddingToTask) {
            return START_SUCCESS;
        }

        // The reusedActivity could be finishing, for example of starting an activity with
        // FLAG_ACTIVITY_CLEAR_TOP flag. In that case, use the top running activity in the
        // task instead.
        targetTaskTop = targetTaskTop.finishing
                ? targetTask.getTopNonFinishingActivity()
                : targetTaskTop;

        // At this point we are certain we want the task moved to the front. If we need to dismiss
        // any other always-on-top root tasks, now is the time to do it.
        if (targetTaskTop.canTurnScreenOn() && mService.mInternal.isDreaming()) {
            targetTaskTop.mTaskSupervisor.wakeUp("recycleTask#turnScreenOnFlag");
        }

        if (mMovedToFront) {
            // We moved the task to front, use starting window to hide initial drawn delay.
            targetTaskTop.showStartingWindow(true /* taskSwitch */);
        } else if (mDoResume) {
            // Make sure the root task and its belonging display are moved to topmost.
            mTargetRootTask.moveToFront("intentActivityFound");
        }
        // We didn't do anything...  but it was needed (a.k.a., client don't use that intent!)
        // And for paranoia, make sure we have correctly resumed the top activity.
        resumeTargetRootTaskIfNeeded();

        mLastStartActivityRecord = targetTaskTop;
        return mMovedToFront ? START_TASK_TO_FRONT : START_DELIVERED_TO_TOP;
    }

    /**
     * Check if the activity being launched is the same as the one currently at the top and it
     * should only be launched once.
     */
    private int deliverToCurrentTopIfNeeded(Task topRootTask, NeededUriGrants intentGrants) {
        final ActivityRecord top = topRootTask.topRunningNonDelayedActivityLocked(mNotTop);
        final boolean dontStart = top != null
                && top.mActivityComponent.equals(mStartActivity.mActivityComponent)
                && top.mUserId == mStartActivity.mUserId
                && top.attachedToProcess()
                && ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
                || LAUNCH_SINGLE_TOP == mLaunchMode)
                // This allows home activity to automatically launch on secondary task display area
                // when it was added, if home was the top activity on default task display area,
                // instead of sending new intent to the home activity on default display area.
                && (!top.isActivityTypeHome() || top.getDisplayArea() == mPreferredTaskDisplayArea);
        if (!dontStart) {
            return START_SUCCESS;
        }

        // For paranoia, make sure we have correctly resumed the top activity.
        top.getTaskFragment().clearLastPausedActivity();
        if (mDoResume) {
            mRootWindowContainer.resumeFocusedTasksTopActivities();
        }
        ActivityOptions.abort(mOptions);
        if ((mStartFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
            // We don't need to start a new activity, and the client said not to do anything if
            // that is the case, so this is it!
            return START_RETURN_INTENT_TO_CALLER;
        }

        if (mStartActivity.resultTo != null) {
            mStartActivity.resultTo.sendResult(INVALID_UID, mStartActivity.resultWho,
                    mStartActivity.requestCode, RESULT_CANCELED,
                    null /* data */, null /* dataGrants */);
            mStartActivity.resultTo = null;
        }

        deliverNewIntent(top, intentGrants);

        // Don't use mStartActivity.task to show the toast. We're not starting a new activity but
        // reusing 'top'. Fields in mStartActivity may not be fully initialized.
        mSupervisor.handleNonResizableTaskIfNeeded(top.getTask(),
                mLaunchParams.mWindowingMode, mPreferredTaskDisplayArea, topRootTask);

        return START_DELIVERED_TO_TOP;
    }

    /**
     * Applying the launching flags to the task, which might clear few or all the activities in the
     * task.
     */
    private void complyActivityFlags(Task targetTask, ActivityRecord reusedActivity,
            NeededUriGrants intentGrants) {
        ActivityRecord targetTaskTop = targetTask.getTopNonFinishingActivity();
        final boolean resetTask =
                reusedActivity != null && (mLaunchFlags & FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0;
        if (resetTask) {
            targetTaskTop = mTargetRootTask.resetTaskIfNeeded(targetTaskTop, mStartActivity);
        }

        if ((mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)) {
            // The caller has requested to completely replace any existing task with its new
            // activity. Well that should not be too hard...
            // Note: we must persist the {@link Task} first as intentActivity could be
            // removed from calling performClearTaskLocked (For example, if it is being brought out
            // of history or if it is finished immediately), thus disassociating the task. Also note
            // that mReuseTask is reset as a result of {@link Task#performClearTaskLocked}
            // launching another activity.
            targetTask.performClearTaskLocked();
            targetTask.setIntent(mStartActivity);
            mAddingToTask = true;
        } else if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                || isDocumentLaunchesIntoExisting(mLaunchFlags)
                || isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK,
                        LAUNCH_SINGLE_INSTANCE_PER_TASK)) {
            // In this situation we want to remove all activities from the task up to the one
            // being started. In most cases this means we are resetting the task to its initial
            // state.
            final ActivityRecord top = targetTask.performClearTaskForReuseLocked(mStartActivity,
                    mLaunchFlags);

            if (top != null) {
                if (top.isRootOfTask()) {
                    // Activity aliases may mean we use different intents for the top activity,
                    // so make sure the task now has the identity of the new intent.
                    top.getTask().setIntent(mStartActivity);
                }
                deliverNewIntent(top, intentGrants);
            } else {
                // A special case: we need to start the activity because it is not currently
                // running, and the caller has asked to clear the current task to have this
                // activity at the top.
                mAddingToTask = true;
                if (targetTask.getRootTask() == null) {
                    // Target root task got cleared when we all activities were removed above.
                    // Go ahead and reset it.
                    mTargetRootTask = getLaunchRootTask(mStartActivity, mLaunchFlags,
                        null /* task */, mOptions);
                    mTargetRootTask.addChild(targetTask, !mLaunchTaskBehind /* toTop */,
                            (mStartActivity.info.flags & FLAG_SHOW_FOR_ALL_USERS) != 0);
                }
            }
        } else if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) == 0 && !mAddingToTask
                && (mLaunchFlags & FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
            // In this case, we are launching an activity in our own task that may
            // already be running somewhere in the history, and we want to shuffle it to
            // the front of the root task if so.
            final ActivityRecord act =
                    targetTask.findActivityInHistory(mStartActivity.mActivityComponent);
            if (act != null) {
                final Task task = act.getTask();
                task.moveActivityToFrontLocked(act);
                act.updateOptionsLocked(mOptions);
                deliverNewIntent(act, intentGrants);
                act.getTaskFragment().clearLastPausedActivity();
            } else {
                mAddingToTask = true;
            }
        } else if (mStartActivity.mActivityComponent.equals(targetTask.realActivity)) {
            if (targetTask == mInTask) {
                // In this case we are bringing up an existing activity from a recent task. We
                // don't need to add a new activity instance on top.
            } else if (((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
                            || LAUNCH_SINGLE_TOP == mLaunchMode)
                    && targetTaskTop.mActivityComponent.equals(mStartActivity.mActivityComponent)
                    && mStartActivity.resultTo == null) {
                // In this case the top activity on the task is the same as the one being launched,
                // so we take that as a request to bring the task to the foreground. If the top
                // activity in the task is the root activity, deliver this new intent to it if it
                // desires.
                if (targetTaskTop.isRootOfTask()) {
                    targetTaskTop.getTask().setIntent(mStartActivity);
                }
                deliverNewIntent(targetTaskTop, intentGrants);
            } else if (!targetTask.isSameIntentFilter(mStartActivity)) {
                // In this case we are launching the root activity of the task, but with a
                // different intent. We should start a new instance on top.
                mAddingToTask = true;
            } else if (reusedActivity == null) {
                mAddingToTask = true;
            }
        } else if (!resetTask) {
            // In this case an activity is being launched in to an existing task, without
            // resetting that task. This is typically the situation of launching an activity
            // from a notification or shortcut. We want to place the new activity on top of the
            // current task.
            mAddingToTask = true;
        } else if (!targetTask.rootWasReset) {
            // In this case we are launching into an existing task that has not yet been started
            // from its front door. The current task has been brought to the front. Ideally,
            // we'd probably like to place this new task at the bottom of its root task, but that's
            // a little hard to do with the current organization of the code so for now we'll
            // just drop it.
            targetTask.setIntent(mStartActivity);
        }
    }

    /**
     * Resets the {@link ActivityStarter} state.
     * @param clearRequest whether the request should be reset to default values.
     */
    void reset(boolean clearRequest) {
        mStartActivity = null;
        mIntent = null;
        mCallingUid = -1;
        mOptions = null;
        mRestrictedBgActivity = false;

        mLaunchTaskBehind = false;
        mLaunchFlags = 0;
        mLaunchMode = INVALID_LAUNCH_MODE;

        mLaunchParams.reset();

        mNotTop = null;
        mDoResume = false;
        mStartFlags = 0;
        mSourceRecord = null;
        mPreferredTaskDisplayArea = null;
        mPreferredWindowingMode = WINDOWING_MODE_UNDEFINED;

        mInTask = null;
        mInTaskFragment = null;
        mAddingToTask = false;
        mReuseTask = null;

        mNewTaskInfo = null;
        mNewTaskIntent = null;
        mSourceRootTask = null;

        mTargetRootTask = null;
        mTargetTask = null;
        mMovedToFront = false;
        mNoAnimation = false;
        mAvoidMoveToFront = false;
        mFrozeTaskList = false;
        mTransientLaunch = false;

        mVoiceSession = null;
        mVoiceInteractor = null;

        mIntentDelivered = false;

        if (clearRequest) {
            mRequest.reset();
        }
    }

    private void setInitialState(ActivityRecord r, ActivityOptions options, Task inTask,
            TaskFragment inTaskFragment, boolean doResume, int startFlags,
            ActivityRecord sourceRecord, IVoiceInteractionSession voiceSession,
            IVoiceInteractor voiceInteractor, boolean restrictedBgActivity) {
        reset(false /* clearRequest */);

        mStartActivity = r;
        mIntent = r.intent;
        mOptions = options;
        mCallingUid = r.launchedFromUid;
        mSourceRecord = sourceRecord;
        mVoiceSession = voiceSession;
        mVoiceInteractor = voiceInteractor;
        mRestrictedBgActivity = restrictedBgActivity;

        mLaunchParams.reset();

        // Preferred display id is the only state we need for now and it could be updated again
        // after we located a reusable task (which might be resided in another display).
        mSupervisor.getLaunchParamsController().calculate(inTask, r.info.windowLayout, r,
                sourceRecord, options, mRequest, PHASE_DISPLAY, mLaunchParams);
        mPreferredTaskDisplayArea = mLaunchParams.hasPreferredTaskDisplayArea()
                ? mLaunchParams.mPreferredTaskDisplayArea
                : mRootWindowContainer.getDefaultTaskDisplayArea();
        mPreferredWindowingMode = mLaunchParams.mWindowingMode;

        mLaunchMode = r.launchMode;

        mLaunchFlags = adjustLaunchFlagsToDocumentMode(
                r, LAUNCH_SINGLE_INSTANCE == mLaunchMode,
                LAUNCH_SINGLE_TASK == mLaunchMode, mIntent.getFlags());
        mLaunchTaskBehind = r.mLaunchTaskBehind
                && !isLaunchModeOneOf(LAUNCH_SINGLE_TASK, LAUNCH_SINGLE_INSTANCE)
                && (mLaunchFlags & FLAG_ACTIVITY_NEW_DOCUMENT) != 0;

        if (mLaunchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK) {
            // Adding NEW_TASK flag for singleInstancePerTask launch mode activity, so that the
            // activity won't be launched in source record's task.
            mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
        }

        sendNewTaskResultRequestIfNeeded();

        if ((mLaunchFlags & FLAG_ACTIVITY_NEW_DOCUMENT) != 0 && r.resultTo == null) {
            mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
        }

        // If we are actually going to launch in to a new task, there are some cases where
        // we further want to do multiple task.
        if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            if (mLaunchTaskBehind
                    || r.info.documentLaunchMode == DOCUMENT_LAUNCH_ALWAYS) {
                mLaunchFlags |= FLAG_ACTIVITY_MULTIPLE_TASK;
            }
        }

        // We'll invoke onUserLeaving before onPause only if the launching
        // activity did not explicitly state that this is an automated launch.
        mSupervisor.mUserLeaving = (mLaunchFlags & FLAG_ACTIVITY_NO_USER_ACTION) == 0;
        if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                "startActivity() => mUserLeaving=" + mSupervisor.mUserLeaving);

        // If the caller has asked not to resume at this point, we make note
        // of this in the record so that we can skip it when trying to find
        // the top running activity.
        mDoResume = doResume;
        if (!doResume || !r.showToCurrentUser() || mLaunchTaskBehind) {
            r.delayedResume = true;
            mDoResume = false;
        }

        if (mOptions != null) {
            if (mOptions.getLaunchTaskId() != INVALID_TASK_ID && mOptions.getTaskOverlay()) {
                r.setTaskOverlay(true);
                if (!mOptions.canTaskOverlayResume()) {
                    final Task task = mRootWindowContainer.anyTaskForId(
                            mOptions.getLaunchTaskId());
                    final ActivityRecord top = task != null
                            ? task.getTopNonFinishingActivity() : null;
                    if (top != null && !top.isState(RESUMED)) {

                        // The caller specifies that we'd like to be avoided to be moved to the
                        // front, so be it!
                        mDoResume = false;
                        mAvoidMoveToFront = true;
                    }
                }
            } else if (mOptions.getAvoidMoveToFront()) {
                mDoResume = false;
                mAvoidMoveToFront = true;
            }
            mTransientLaunch = mOptions.getTransientLaunch();
            mTargetRootTask = Task.fromWindowContainerToken(mOptions.getLaunchRootTask());

            if (inTaskFragment == null) {
                inTaskFragment = TaskFragment.fromTaskFragmentToken(
                        mOptions.getLaunchTaskFragmentToken(), mService);
            }
        }

        mNotTop = (mLaunchFlags & FLAG_ACTIVITY_PREVIOUS_IS_TOP) != 0 ? sourceRecord : null;

        mInTask = inTask;
        // In some flows in to this function, we retrieve the task record and hold on to it
        // without a lock before calling back in to here...  so the task at this point may
        // not actually be in recents.  Check for that, and if it isn't in recents just
        // consider it invalid.
        if (inTask != null && !inTask.inRecents) {
            Slog.w(TAG, "Starting activity in task not in recents: " + inTask);
            mInTask = null;
        }
        mInTaskFragment = inTaskFragment;

        mStartFlags = startFlags;
        // If the onlyIfNeeded flag is set, then we can do this if the activity being launched
        // is the same as the one making the call...  or, as a special case, if we do not know
        // the caller then we count the current top activity as the caller.
        if ((startFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
            ActivityRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                Task topFocusedRootTask = mRootWindowContainer.getTopDisplayFocusedRootTask();
                if (topFocusedRootTask != null) {
                    checkedCaller = topFocusedRootTask.topRunningNonDelayedActivityLocked(mNotTop);
                }
            }
            if (checkedCaller == null
                    || !checkedCaller.mActivityComponent.equals(r.mActivityComponent)) {
                // Caller is not the same as launcher, so always needed.
                mStartFlags &= ~START_FLAG_ONLY_IF_NEEDED;
            }
        }

        mNoAnimation = (mLaunchFlags & FLAG_ACTIVITY_NO_ANIMATION) != 0;

        if (mRestrictedBgActivity && !mService.isBackgroundActivityStartsEnabled()) {
            mAvoidMoveToFront = true;
            mDoResume = false;
        }
    }

    private void sendNewTaskResultRequestIfNeeded() {
        if (mStartActivity.resultTo != null && (mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            // For whatever reason this activity is being launched into a new task...
            // yet the caller has requested a result back.  Well, that is pretty messed up,
            // so instead immediately send back a cancel and let the new task continue launched
            // as normal without a dependency on its originator.
            Slog.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
            mStartActivity.resultTo.sendResult(INVALID_UID, mStartActivity.resultWho,
                    mStartActivity.requestCode, RESULT_CANCELED,
                    null /* data */, null /* dataGrants */);
            mStartActivity.resultTo = null;
        }
    }

    private void computeLaunchingTaskFlags() {
        // If the caller is not coming from another activity, but has given us an explicit task into
        // which they would like us to launch the new activity, then let's see about doing that.
        if (mSourceRecord == null && mInTask != null && mInTask.getRootTask() != null) {
            final Intent baseIntent = mInTask.getBaseIntent();
            final ActivityRecord root = mInTask.getRootActivity();
            if (baseIntent == null) {
                ActivityOptions.abort(mOptions);
                throw new IllegalArgumentException("Launching into task without base intent: "
                        + mInTask);
            }

            // If this task is empty, then we are adding the first activity -- it
            // determines the root, and must be launching as a NEW_TASK.
            if (isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK)) {
                if (!baseIntent.getComponent().equals(mStartActivity.intent.getComponent())) {
                    ActivityOptions.abort(mOptions);
                    throw new IllegalArgumentException("Trying to launch singleInstance/Task "
                            + mStartActivity + " into different task " + mInTask);
                }
                if (root != null) {
                    ActivityOptions.abort(mOptions);
                    throw new IllegalArgumentException("Caller with mInTask " + mInTask
                            + " has root " + root + " but target is singleInstance/Task");
                }
            }

            // If task is empty, then adopt the interesting intent launch flags in to the
            // activity being started.
            if (root == null) {
                final int flagsOfInterest = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_MULTIPLE_TASK
                        | FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_RETAIN_IN_RECENTS;
                mLaunchFlags = (mLaunchFlags & ~flagsOfInterest)
                        | (baseIntent.getFlags() & flagsOfInterest);
                mIntent.setFlags(mLaunchFlags);
                mInTask.setIntent(mStartActivity);
                mAddingToTask = true;

                // If the task is not empty and the caller is asking to start it as the root of
                // a new task, then we don't actually want to start this on the task. We will
                // bring the task to the front, and possibly give it a new intent.
            } else if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
                mAddingToTask = false;

            } else {
                mAddingToTask = true;
            }

            mReuseTask = mInTask;
        } else {
            mInTask = null;
            // Launch ResolverActivity in the source task, so that it stays in the task bounds
            // when in freeform workspace.
            // Also put noDisplay activities in the source task. These by itself can be placed
            // in any task/root-task, however it could launch other activities like
            // ResolverActivity, and we want those to stay in the original task.
            if ((mStartActivity.isResolverOrDelegateActivity() || mStartActivity.noDisplay)
                    && mSourceRecord != null && mSourceRecord.inFreeformWindowingMode()) {
                mAddingToTask = true;
            }
        }

        if (mInTask == null) {
            if (mSourceRecord == null) {
                // This activity is not being started from another...  in this
                // case we -always- start a new task.
                if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) == 0 && mInTask == null) {
                    Slog.w(TAG, "startActivity called from non-Activity context; forcing " +
                            "Intent.FLAG_ACTIVITY_NEW_TASK for: " + mIntent);
                    mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
                }
            } else if (mSourceRecord.launchMode == LAUNCH_SINGLE_INSTANCE) {
                // The original activity who is starting us is running as a single
                // instance...  this new activity it is starting must go on its
                // own task.
                mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
            } else if (isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK)) {
                // The activity being started is a single instance...  it always
                // gets launched into its own task.
                mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
            }
        }
    }

    private void computeSourceRootTask() {
        if (mSourceRecord == null) {
            mSourceRootTask = null;
            return;
        }
        if (!mSourceRecord.finishing) {
            mSourceRootTask = mSourceRecord.getRootTask();
            return;
        }

        // If the source is finishing, we can't further count it as our source. This is because the
        // task it is associated with may now be empty and on its way out, so we don't want to
        // blindly throw it in to that task.  Instead we will take the NEW_TASK flow and try to find
        // a task for it. But save the task information so it can be used when creating the new task.
        if ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) == 0) {
            Slog.w(TAG, "startActivity called from finishing " + mSourceRecord
                    + "; forcing " + "Intent.FLAG_ACTIVITY_NEW_TASK for: " + mIntent);
            mLaunchFlags |= FLAG_ACTIVITY_NEW_TASK;
            mNewTaskInfo = mSourceRecord.info;

            // It is not guaranteed that the source record will have a task associated with it. For,
            // example, if this method is being called for processing a pending activity launch, it
            // is possible that the activity has been removed from the task after the launch was
            // enqueued.
            final Task sourceTask = mSourceRecord.getTask();
            mNewTaskIntent = sourceTask != null ? sourceTask.intent : null;
        }
        mSourceRecord = null;
        mSourceRootTask = null;
    }

    /**
     * Decide whether the new activity should be inserted into an existing task. Returns null
     * if not or an ActivityRecord with the task into which the new activity should be added.
     */
    private Task getReusableTask() {
        // If a target task is specified, try to reuse that one
        if (mOptions != null && mOptions.getLaunchTaskId() != INVALID_TASK_ID) {
            Task launchTask = mRootWindowContainer.anyTaskForId(mOptions.getLaunchTaskId());
            if (launchTask != null) {
                return launchTask;
            }
            return null;
        }

        // We may want to try to place the new activity in to an existing task.  We always
        // do this if the target activity is singleTask or singleInstance; we will also do
        // this if NEW_TASK has been requested, and there is not an additional qualifier telling
        // us to still place it in a new task: multi task, always doc mode, or being asked to
        // launch this as a new task behind the current one.
        boolean putIntoExistingTask = ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (mLaunchFlags & FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK);
        // If bring to front is requested, and no result is requested and we have not been given
        // an explicit task to launch in to, and we can find a task that was started with this
        // same component, then instead of launching bring that one to the front.
        putIntoExistingTask &= mInTask == null && mStartActivity.resultTo == null;
        ActivityRecord intentActivity = null;
        if (putIntoExistingTask) {
            if (LAUNCH_SINGLE_INSTANCE == mLaunchMode) {
                // There can be one and only one instance of single instance activity in the
                // history, and it is always in its own unique task, so we do a special search.
                intentActivity = mRootWindowContainer.findActivity(mIntent, mStartActivity.info,
                       mStartActivity.isActivityTypeHome());
            } else if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
                // For the launch adjacent case we only want to put the activity in an existing
                // task if the activity already exists in the history.
                intentActivity = mRootWindowContainer.findActivity(mIntent, mStartActivity.info,
                        !(LAUNCH_SINGLE_TASK == mLaunchMode));
            } else {
                // Otherwise find the best task to put the activity in.
                intentActivity =
                        mRootWindowContainer.findTask(mStartActivity, mPreferredTaskDisplayArea);
            }
        }

        if (intentActivity != null && mLaunchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK
                && !intentActivity.getTask().getRootActivity().mActivityComponent.equals(
                mStartActivity.mActivityComponent)) {
            // The task could be selected due to same task affinity. Do not reuse the task while
            // starting the singleInstancePerTask activity if it is not the task root activity.
            intentActivity = null;
        }

        if (intentActivity != null
                && (mStartActivity.isActivityTypeHome() || intentActivity.isActivityTypeHome())
                && intentActivity.getDisplayArea() != mPreferredTaskDisplayArea) {
            // Do not reuse home activity on other display areas.
            intentActivity = null;
        }

        return intentActivity != null ? intentActivity.getTask() : null;
    }

    /**
     * Figure out which task and activity to bring to front when we have found an existing matching
     * activity record in history. May also clear the task if needed.
     *
     * @param intentActivity Existing matching activity.
     * @return {@link ActivityRecord} brought to front.
     */
    private void setTargetRootTaskIfNeeded(ActivityRecord intentActivity) {
        intentActivity.getTaskFragment().clearLastPausedActivity();
        Task intentTask = intentActivity.getTask();

        // Only update the target-root-task when it is not indicated.
        if (mTargetRootTask == null) {
            if (mSourceRecord != null && mSourceRecord.mLaunchRootTask != null) {
                // Inherit the target-root-task from source to ensure trampoline activities will be
                // launched into the same root task.
                mTargetRootTask = Task.fromWindowContainerToken(mSourceRecord.mLaunchRootTask);
            } else {
                final Task launchRootTask =
                        getLaunchRootTask(mStartActivity, mLaunchFlags, intentTask, mOptions);
                mTargetRootTask =
                        launchRootTask != null ? launchRootTask : intentActivity.getRootTask();
            }
        }

        // If the target task is not in the front, then we need to bring it to the front...
        // except...  well, with SINGLE_TASK_LAUNCH it's not entirely clear. We'd like to have
        // the same behavior as if a new instance was being started, which means not bringing it
        // to the front if the caller is not itself in the front.
        final boolean differentTopTask;
        if (mTargetRootTask.getDisplayArea() == mPreferredTaskDisplayArea) {
            final Task focusRootTask = mTargetRootTask.mDisplayContent.getFocusedRootTask();
            final ActivityRecord curTop = (focusRootTask == null)
                    ? null : focusRootTask.topRunningNonDelayedActivityLocked(mNotTop);
            final Task topTask = curTop != null ? curTop.getTask() : null;
            differentTopTask = topTask != intentTask
                    || (focusRootTask != null && topTask != focusRootTask.getTopMostTask());
        } else {
            // The existing task should always be different from those in other displays.
            differentTopTask = true;
        }

        if (differentTopTask && !mAvoidMoveToFront) {
            mStartActivity.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
            if (mSourceRecord == null || (mSourceRootTask.getTopNonFinishingActivity() != null
                    && mSourceRootTask.getTopNonFinishingActivity().getTask()
                            == mSourceRecord.getTask())) {
                // We really do want to push this one into the user's face, right now.
                if (mLaunchTaskBehind && mSourceRecord != null) {
                    intentActivity.setTaskToAffiliateWith(mSourceRecord.getTask());
                }

                if (mTargetRootTask == intentActivity.getRootTask()) {
                    // TODO(b/151572268): Figure out a better way to move tasks in above 2-levels
                    //  tasks hierarchies.
                    if (mTargetRootTask != intentTask
                            && mTargetRootTask != intentTask.getParent().asTask()) {
                        intentTask.getParent().positionChildAt(POSITION_TOP, intentTask,
                                false /* includingParents */);
                        intentTask = intentTask.getParent().asTaskFragment().getTask();
                    }
                    // If the task is in multi-windowing mode, the activity may already be on
                    // the top (visible to user but not the global top), then the result code
                    // should be START_DELIVERED_TO_TOP instead of START_TASK_TO_FRONT.
                    final boolean wasTopOfVisibleRootTask = intentActivity.mVisibleRequested
                            && intentActivity == mTargetRootTask.topRunningActivity();
                    // We only want to move to the front, if we aren't going to launch on a
                    // different root task. If we launch on a different root task, we will put the
                    // task on top there.
                    // Defer resuming the top activity while moving task to top, since the
                    // current task-top activity may not be the activity that should be resumed.
                    mTargetRootTask.moveTaskToFront(intentTask, mNoAnimation, mOptions,
                            mStartActivity.appTimeTracker, DEFER_RESUME,
                            "bringingFoundTaskToFront");
                    mMovedToFront = !wasTopOfVisibleRootTask;
                } else if (intentActivity.getWindowingMode() != WINDOWING_MODE_PINNED) {
                    // Leaves reparenting pinned task operations to task organizer to make sure it
                    // dismisses pinned task properly.
                    // TODO(b/199997762): Consider leaving all reparent operation of organized tasks
                    //  to task organizer.
                    intentTask.reparent(mTargetRootTask, ON_TOP, REPARENT_MOVE_ROOT_TASK_TO_FRONT,
                            ANIMATE, DEFER_RESUME, "reparentToTargetRootTask");
                    mMovedToFront = true;
                }
                mOptions = null;
            }
        }

        if (mPreferredWindowingMode != WINDOWING_MODE_UNDEFINED
                && intentTask.getWindowingMode() != mPreferredWindowingMode) {
            intentTask.setWindowingMode(mPreferredWindowingMode);
        }

        // Update the target's launch cookie to those specified in the options if set
        if (mStartActivity.mLaunchCookie != null) {
            intentActivity.mLaunchCookie = mStartActivity.mLaunchCookie;
        }

        // Need to update mTargetRootTask because if task was moved out of it, the original root
        // task may be destroyed.
        mTargetRootTask = intentActivity.getRootTask();
        mSupervisor.handleNonResizableTaskIfNeeded(intentTask, WINDOWING_MODE_UNDEFINED,
                mRootWindowContainer.getDefaultTaskDisplayArea(), mTargetRootTask);

        // We need to check if there is a launch root task in TDA for this target root task.
        // If it exist, we need to reparent target root task from TDA to launch root task.
        final TaskDisplayArea tda = mTargetRootTask.getDisplayArea();
        final Task launchRootTask = tda.getLaunchRootTask(mTargetRootTask.getWindowingMode(),
                mTargetRootTask.getActivityType(), null /** options */, null /** sourceTask */,
                0 /** launchFlags */);
        if (launchRootTask != null && launchRootTask != mTargetRootTask) {
            mTargetRootTask.reparent(launchRootTask, POSITION_TOP);
            mTargetRootTask = launchRootTask;
        }
    }

    private void resumeTargetRootTaskIfNeeded() {
        if (mDoResume) {
            final ActivityRecord next = mTargetRootTask.topRunningActivity(
                    true /* focusableOnly */);
            if (next != null) {
                next.setCurrentLaunchCanTurnScreenOn(true);
            }
            if (mTargetRootTask.isFocusable()) {
                mRootWindowContainer.resumeFocusedTasksTopActivities(mTargetRootTask, null,
                        mOptions, mTransientLaunch);
            } else {
                mRootWindowContainer.ensureActivitiesVisible(null, 0, !PRESERVE_WINDOWS);
            }
        } else {
            ActivityOptions.abort(mOptions);
        }
        mRootWindowContainer.updateUserRootTask(mStartActivity.mUserId, mTargetRootTask);
    }

    private void setNewTask(Task taskToAffiliate) {
        final boolean toTop = !mLaunchTaskBehind && !mAvoidMoveToFront;
        final Task task = mTargetRootTask.reuseOrCreateTask(
                mNewTaskInfo != null ? mNewTaskInfo : mStartActivity.info,
                mNewTaskIntent != null ? mNewTaskIntent : mIntent, mVoiceSession,
                mVoiceInteractor, toTop, mStartActivity, mSourceRecord, mOptions);
        mService.getTransitionController().collectExistenceChange(task);
        addOrReparentStartingActivity(task, "setTaskFromReuseOrCreateNewTask - mReuseTask");

        ProtoLog.v(WM_DEBUG_TASKS, "Starting new activity %s in new task %s",
                mStartActivity, mStartActivity.getTask());

        if (taskToAffiliate != null) {
            mStartActivity.setTaskToAffiliateWith(taskToAffiliate);
        }
    }

    private void deliverNewIntent(ActivityRecord activity, NeededUriGrants intentGrants) {
        if (mIntentDelivered) {
            return;
        }

        activity.logStartActivity(EventLogTags.WM_NEW_INTENT, activity.getTask());
        activity.deliverNewIntentLocked(mCallingUid, mStartActivity.intent, intentGrants,
                mStartActivity.launchedFromPackage);
        mIntentDelivered = true;
    }

    private void addOrReparentStartingActivity(@NonNull Task task, String reason) {
        TaskFragment newParent = task;
        if (mInTaskFragment != null) {
            // mInTaskFragment is created and added to the leaf task by task fragment organizer's
            // request. If the task was resolved and different than mInTaskFragment, reparent the
            // task to mInTaskFragment for embedding.
            if (mInTaskFragment.getTask() != task) {
                task.reparent(mInTaskFragment, POSITION_TOP);
            } else {
                newParent = mInTaskFragment;
            }
        } else {
            // Use the child TaskFragment (if any) as the new parent if the activity can be embedded
            final ActivityRecord top = task.topRunningActivity(false /* focusableOnly */,
                    false /* includingEmbeddedTask */);
            newParent = top != null ? top.getTaskFragment() : task;
        }

        if (mStartActivity.getTaskFragment() == null
                || mStartActivity.getTaskFragment() == newParent) {
            newParent.addChild(mStartActivity, POSITION_TOP);
        } else {
            mStartActivity.reparent(newParent, newParent.getChildCount() /* top */, reason);
        }
    }

    private int adjustLaunchFlagsToDocumentMode(ActivityRecord r, boolean launchSingleInstance,
            boolean launchSingleTask, int launchFlags) {
        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 &&
                (launchSingleInstance || launchSingleTask)) {
            // We have a conflict between the Intent and the Activity manifest, manifest wins.
            Slog.i(TAG, "Ignoring FLAG_ACTIVITY_NEW_DOCUMENT, launchMode is " +
                    "\"singleInstance\" or \"singleTask\"");
            launchFlags &=
                    ~(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK);
        } else {
            switch (r.info.documentLaunchMode) {
                case ActivityInfo.DOCUMENT_LAUNCH_NONE:
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_INTO_EXISTING:
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_ALWAYS:
                    launchFlags |= Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
                    break;
                case ActivityInfo.DOCUMENT_LAUNCH_NEVER:
                    if (mLaunchMode == LAUNCH_SINGLE_INSTANCE_PER_TASK) {
                        // Remove MULTIPLE_TASK flag along with NEW_DOCUMENT only if NEW_DOCUMENT
                        // is set, otherwise we still want to keep the MULTIPLE_TASK flag (if
                        // any) for singleInstancePerTask that the multiple tasks can be created,
                        // or a singleInstancePerTask activity is basically the same as a
                        // singleTask activity when documentLaunchMode set to never.
                        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0) {
                            launchFlags &= ~(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                                    | FLAG_ACTIVITY_MULTIPLE_TASK);
                        }
                    } else {
                        // TODO(b/184903976): Should FLAG_ACTIVITY_MULTIPLE_TASK always be
                        // removed for document-never activity?
                        launchFlags &=
                                ~(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | FLAG_ACTIVITY_MULTIPLE_TASK);
                    }
                    break;
            }
        }
        return launchFlags;
    }

    private Task getLaunchRootTask(ActivityRecord r, int launchFlags, Task task,
            ActivityOptions aOptions) {
        // We are reusing a task, keep the root task!
        if (mReuseTask != null) {
            return mReuseTask.getRootTask();
        }

        final boolean onTop =
                (aOptions == null || !aOptions.getAvoidMoveToFront()) && !mLaunchTaskBehind;
        return mRootWindowContainer.getLaunchRootTask(r, aOptions, task, mSourceRootTask, onTop,
                mLaunchParams, launchFlags, mRequest.realCallingPid, mRequest.realCallingUid);
    }

    private boolean isLaunchModeOneOf(int mode1, int mode2) {
        return mode1 == mLaunchMode || mode2 == mLaunchMode;
    }

    private boolean isLaunchModeOneOf(int mode1, int mode2, int mode3) {
        return mode1 == mLaunchMode || mode2 == mLaunchMode || mode3 == mLaunchMode;
    }

    static boolean isDocumentLaunchesIntoExisting(int flags) {
        return (flags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 &&
                (flags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0;
    }

    ActivityStarter setIntent(Intent intent) {
        mRequest.intent = intent;
        return this;
    }

    Intent getIntent() {
        return mRequest.intent;
    }

    ActivityStarter setIntentGrants(NeededUriGrants intentGrants) {
        mRequest.intentGrants = intentGrants;
        return this;
    }

    ActivityStarter setReason(String reason) {
        mRequest.reason = reason;
        return this;
    }

    ActivityStarter setCaller(IApplicationThread caller) {
        mRequest.caller = caller;
        return this;
    }

    ActivityStarter setResolvedType(String type) {
        mRequest.resolvedType = type;
        return this;
    }

    ActivityStarter setActivityInfo(ActivityInfo info) {
        mRequest.activityInfo = info;
        return this;
    }

    ActivityStarter setResolveInfo(ResolveInfo info) {
        mRequest.resolveInfo = info;
        return this;
    }

    ActivityStarter setVoiceSession(IVoiceInteractionSession voiceSession) {
        mRequest.voiceSession = voiceSession;
        return this;
    }

    ActivityStarter setVoiceInteractor(IVoiceInteractor voiceInteractor) {
        mRequest.voiceInteractor = voiceInteractor;
        return this;
    }

    ActivityStarter setResultTo(IBinder resultTo) {
        mRequest.resultTo = resultTo;
        return this;
    }

    ActivityStarter setResultWho(String resultWho) {
        mRequest.resultWho = resultWho;
        return this;
    }

    ActivityStarter setRequestCode(int requestCode) {
        mRequest.requestCode = requestCode;
        return this;
    }

    /**
     * Sets the pid of the caller who originally started the activity.
     *
     * Normally, the pid/uid would be the calling pid from the binder call.
     * However, in case of a {@link PendingIntent}, the pid/uid pair of the caller is considered
     * the original entity that created the pending intent, in contrast to setRealCallingPid/Uid,
     * which represents the entity who invoked pending intent via {@link PendingIntent#send}.
     */
    ActivityStarter setCallingPid(int pid) {
        mRequest.callingPid = pid;
        return this;
    }

    /**
     * Sets the uid of the caller who originally started the activity.
     *
     * @see #setCallingPid
     */
    ActivityStarter setCallingUid(int uid) {
        mRequest.callingUid = uid;
        return this;
    }

    ActivityStarter setCallingPackage(String callingPackage) {
        mRequest.callingPackage = callingPackage;
        return this;
    }

    ActivityStarter setCallingFeatureId(String callingFeatureId) {
        mRequest.callingFeatureId = callingFeatureId;
        return this;
    }

    /**
     * Sets the pid of the caller who requested to launch the activity.
     *
     * The pid/uid represents the caller who launches the activity in this request.
     * It will almost same as setCallingPid/Uid except when processing {@link PendingIntent}:
     * the pid/uid will be the caller who called {@link PendingIntent#send()}.
     *
     * @see #setCallingPid
     */
    ActivityStarter setRealCallingPid(int pid) {
        mRequest.realCallingPid = pid;
        return this;
    }

    /**
     * Sets the uid of the caller who requested to launch the activity.
     *
     * @see #setRealCallingPid
     */
    ActivityStarter setRealCallingUid(int uid) {
        mRequest.realCallingUid = uid;
        return this;
    }

    ActivityStarter setStartFlags(int startFlags) {
        mRequest.startFlags = startFlags;
        return this;
    }

    ActivityStarter setActivityOptions(SafeActivityOptions options) {
        mRequest.activityOptions = options;
        return this;
    }

    ActivityStarter setActivityOptions(Bundle bOptions) {
        return setActivityOptions(SafeActivityOptions.fromBundle(bOptions));
    }

    ActivityStarter setIgnoreTargetSecurity(boolean ignoreTargetSecurity) {
        mRequest.ignoreTargetSecurity = ignoreTargetSecurity;
        return this;
    }

    ActivityStarter setFilterCallingUid(int filterCallingUid) {
        mRequest.filterCallingUid = filterCallingUid;
        return this;
    }

    ActivityStarter setComponentSpecified(boolean componentSpecified) {
        mRequest.componentSpecified = componentSpecified;
        return this;
    }

    ActivityStarter setOutActivity(ActivityRecord[] outActivity) {
        mRequest.outActivity = outActivity;
        return this;
    }

    ActivityStarter setInTask(Task inTask) {
        mRequest.inTask = inTask;
        return this;
    }

    ActivityStarter setInTaskFragment(TaskFragment taskFragment) {
        mRequest.inTaskFragment = taskFragment;
        return this;
    }

    ActivityStarter setWaitResult(WaitResult result) {
        mRequest.waitResult = result;
        return this;
    }

    ActivityStarter setProfilerInfo(ProfilerInfo info) {
        mRequest.profilerInfo = info;
        return this;
    }

    ActivityStarter setGlobalConfiguration(Configuration config) {
        mRequest.globalConfig = config;
        return this;
    }

    ActivityStarter setUserId(int userId) {
        mRequest.userId = userId;
        return this;
    }

    ActivityStarter setAllowPendingRemoteAnimationRegistryLookup(boolean allowLookup) {
        mRequest.allowPendingRemoteAnimationRegistryLookup = allowLookup;
        return this;
    }

    ActivityStarter setOriginatingPendingIntent(PendingIntentRecord originatingPendingIntent) {
        mRequest.originatingPendingIntent = originatingPendingIntent;
        return this;
    }

    ActivityStarter setAllowBackgroundActivityStart(boolean allowBackgroundActivityStart) {
        mRequest.allowBackgroundActivityStart = allowBackgroundActivityStart;
        return this;
    }

    void dump(PrintWriter pw, String prefix) {
        prefix = prefix + "  ";
        pw.print(prefix);
        pw.print("mCurrentUser=");
        pw.println(mRootWindowContainer.mCurrentUser);
        pw.print(prefix);
        pw.print("mLastStartReason=");
        pw.println(mLastStartReason);
        pw.print(prefix);
        pw.print("mLastStartActivityTimeMs=");
        pw.println(DateFormat.getDateTimeInstance().format(new Date(mLastStartActivityTimeMs)));
        pw.print(prefix);
        pw.print("mLastStartActivityResult=");
        pw.println(mLastStartActivityResult);
        if (mLastStartActivityRecord != null) {
            pw.print(prefix);
            pw.println("mLastStartActivityRecord:");
            mLastStartActivityRecord.dump(pw, prefix + "  ", true /* dumpAll */);
        }
        if (mStartActivity != null) {
            pw.print(prefix);
            pw.println("mStartActivity:");
            mStartActivity.dump(pw, prefix + "  ", true /* dumpAll */);
        }
        if (mIntent != null) {
            pw.print(prefix);
            pw.print("mIntent=");
            pw.println(mIntent);
        }
        if (mOptions != null) {
            pw.print(prefix);
            pw.print("mOptions=");
            pw.println(mOptions);
        }
        pw.print(prefix);
        pw.print("mLaunchSingleTop=");
        pw.print(LAUNCH_SINGLE_TOP == mLaunchMode);
        pw.print(" mLaunchSingleInstance=");
        pw.print(LAUNCH_SINGLE_INSTANCE == mLaunchMode);
        pw.print(" mLaunchSingleTask=");
        pw.println(LAUNCH_SINGLE_TASK == mLaunchMode);
        pw.print(prefix);
        pw.print("mLaunchFlags=0x");
        pw.print(Integer.toHexString(mLaunchFlags));
        pw.print(" mDoResume=");
        pw.print(mDoResume);
        pw.print(" mAddingToTask=");
        pw.println(mAddingToTask);
        pw.print(" mInTaskFragment=");
        pw.println(mInTaskFragment);
    }
}
