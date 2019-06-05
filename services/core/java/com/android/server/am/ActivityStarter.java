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

import static android.app.Activity.RESULT_CANCELED;
import static android.app.ActivityManager.START_ABORTED;
import static android.app.ActivityManager.START_CANCELED;
import static android.app.ActivityManager.START_CLASS_NOT_FOUND;
import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_FLAG_ONLY_IF_NEEDED;
import static android.app.ActivityManager.START_RETURN_INTENT_TO_CALLER;
import static android.app.ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
import static android.app.ActivityManager.START_SUCCESS;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
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
import static android.content.pm.ActivityInfo.DOCUMENT_LAUNCH_ALWAYS;
import static android.content.pm.ActivityInfo.LAUNCH_MULTIPLE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RESULTS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_STACK;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_USER_LEAVING;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_CONFIGURATION;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_FOCUS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_RESULTS;
import static com.android.server.am.ActivityManagerDebugConfig.POSTFIX_USER_LEAVING;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.ANIMATE;
import static com.android.server.am.ActivityStack.ActivityState.RESUMED;
import static com.android.server.am.ActivityStackSupervisor.DEFER_RESUME;
import static com.android.server.am.ActivityStackSupervisor.ON_TOP;
import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.am.ActivityStackSupervisor.TAG_TASKS;
import static com.android.server.am.EventLogTags.AM_NEW_INTENT;
import static com.android.server.am.TaskRecord.REPARENT_KEEP_STACK_AT_FRONT;
import static com.android.server.am.TaskRecord.REPARENT_MOVE_STACK_TO_FRONT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.app.PendingIntent;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.AuxiliaryResolveInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.voice.IVoiceInteractionSession;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Pools.SynchronizedPool;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.ActivityStackSupervisor.PendingActivityLaunch;
import com.android.server.am.LaunchParamsController.LaunchParams;
import com.android.server.pm.InstantAppResolver;

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.Date;

/**
 * Controller for interpreting how and then launching an activity.
 *
 * This class collects all the logic for determining how an intent and flags should be turned into
 * an activity and associated task and stack.
 */
class ActivityStarter {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStarter" : TAG_AM;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;
    private static final int INVALID_LAUNCH_MODE = -1;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private final ActivityStartInterceptor mInterceptor;
    private final ActivityStartController mController;

    // Share state variable among methods when starting an activity.
    private ActivityRecord mStartActivity;
    private Intent mIntent;
    private int mCallingUid;
    private ActivityOptions mOptions;

    private int mLaunchMode;
    private boolean mLaunchTaskBehind;
    private int mLaunchFlags;

    private LaunchParams mLaunchParams = new LaunchParams();

    private ActivityRecord mNotTop;
    private boolean mDoResume;
    private int mStartFlags;
    private ActivityRecord mSourceRecord;

    // The display to launch the activity onto, barring any strong reason to do otherwise.
    private int mPreferredDisplayId;

    private TaskRecord mInTask;
    private boolean mAddingToTask;
    private TaskRecord mReuseTask;

    private ActivityInfo mNewTaskInfo;
    private Intent mNewTaskIntent;
    private ActivityStack mSourceStack;
    private ActivityStack mTargetStack;
    private boolean mMovedToFront;
    private boolean mNoAnimation;
    private boolean mKeepCurTransition;
    private boolean mAvoidMoveToFront;

    // We must track when we deliver the new intent since multiple code paths invoke
    // {@link #deliverNewIntent}. This is due to early returns in the code path. This flag is used
    // inside {@link #deliverNewIntent} to suppress duplicate requests and ensure the intent is
    // delivered at most once.
    private boolean mIntentDelivered;

    private IVoiceInteractionSession mVoiceSession;
    private IVoiceInteractor mVoiceInteractor;

    // Last activity record we attempted to start
    private final ActivityRecord[] mLastStartActivityRecord = new ActivityRecord[1];
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
    private Request mRequest = new Request();

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
        private ActivityManagerService mService;
        private ActivityStackSupervisor mSupervisor;
        private ActivityStartInterceptor mInterceptor;

        private SynchronizedPool<ActivityStarter> mStarterPool =
                new SynchronizedPool<>(MAX_STARTER_COUNT);

        DefaultFactory(ActivityManagerService service,
                ActivityStackSupervisor supervisor, ActivityStartInterceptor interceptor) {
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
    private static class Request {
        static final int DEFAULT_REAL_CALLING_PID = 0;
        static final int DEFAULT_REAL_CALLING_UID = UserHandle.USER_NULL;
        private static final int DEFAULT_CALLING_UID = -1;
        private static final int DEFAULT_CALLING_PID = 0;

        IApplicationThread caller;
        Intent intent;
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
        int realCallingPid = Request.DEFAULT_REAL_CALLING_PID;
        int realCallingUid = Request.DEFAULT_REAL_CALLING_UID;
        int startFlags;
        SafeActivityOptions activityOptions;
        boolean ignoreTargetSecurity;
        boolean componentSpecified;
        boolean avoidMoveToFront;
        ActivityRecord[] outActivity;
        TaskRecord inTask;
        String reason;
        ProfilerInfo profilerInfo;
        Configuration globalConfig;
        int userId;
        WaitResult waitResult;
        int filterCallingUid;
        PendingIntentRecord originatingPendingIntent;

        /**
         * If set to {@code true}, allows this activity start to look into
         * {@link PendingRemoteAnimationRegistry}
         */
        boolean allowPendingRemoteAnimationRegistryLookup;

        /**
         * Indicates that we should wait for the result of the start request. This flag is set when
         * {@link ActivityStarter#setMayWait(int)} is called.
         * {@see ActivityStarter#startActivityMayWait}.
         */
        boolean mayWait;

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
            realCallingPid = Request.DEFAULT_REAL_CALLING_PID;
            realCallingUid = Request.DEFAULT_REAL_CALLING_UID;
            startFlags = 0;
            activityOptions = null;
            ignoreTargetSecurity = false;
            componentSpecified = false;
            outActivity = null;
            inTask = null;
            reason = null;
            profilerInfo = null;
            globalConfig = null;
            userId = 0;
            waitResult = null;
            mayWait = false;
            avoidMoveToFront = false;
            allowPendingRemoteAnimationRegistryLookup = true;
            filterCallingUid = DEFAULT_REAL_CALLING_UID;
            originatingPendingIntent = null;
        }

        /**
         * Adopts all values from passed in request.
         */
        void set(Request request) {
            caller = request.caller;
            intent = request.intent;
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
            realCallingPid = request.realCallingPid;
            realCallingUid = request.realCallingUid;
            startFlags = request.startFlags;
            activityOptions = request.activityOptions;
            ignoreTargetSecurity = request.ignoreTargetSecurity;
            componentSpecified = request.componentSpecified;
            outActivity = request.outActivity;
            inTask = request.inTask;
            reason = request.reason;
            profilerInfo = request.profilerInfo;
            globalConfig = request.globalConfig;
            userId = request.userId;
            waitResult = request.waitResult;
            mayWait = request.mayWait;
            avoidMoveToFront = request.avoidMoveToFront;
            allowPendingRemoteAnimationRegistryLookup
                    = request.allowPendingRemoteAnimationRegistryLookup;
            filterCallingUid = request.filterCallingUid;
            originatingPendingIntent = request.originatingPendingIntent;
        }
    }

    ActivityStarter(ActivityStartController controller, ActivityManagerService service,
            ActivityStackSupervisor supervisor, ActivityStartInterceptor interceptor) {
        mController = controller;
        mService = service;
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

        mLaunchTaskBehind = starter.mLaunchTaskBehind;
        mLaunchFlags = starter.mLaunchFlags;
        mLaunchMode = starter.mLaunchMode;

        mLaunchParams.set(starter.mLaunchParams);

        mNotTop = starter.mNotTop;
        mDoResume = starter.mDoResume;
        mStartFlags = starter.mStartFlags;
        mSourceRecord = starter.mSourceRecord;
        mPreferredDisplayId = starter.mPreferredDisplayId;

        mInTask = starter.mInTask;
        mAddingToTask = starter.mAddingToTask;
        mReuseTask = starter.mReuseTask;

        mNewTaskInfo = starter.mNewTaskInfo;
        mNewTaskIntent = starter.mNewTaskIntent;
        mSourceStack = starter.mSourceStack;

        mTargetStack = starter.mTargetStack;
        mMovedToFront = starter.mMovedToFront;
        mNoAnimation = starter.mNoAnimation;
        mKeepCurTransition = starter.mKeepCurTransition;
        mAvoidMoveToFront = starter.mAvoidMoveToFront;

        mVoiceSession = starter.mVoiceSession;
        mVoiceInteractor = starter.mVoiceInteractor;

        mIntentDelivered = starter.mIntentDelivered;

        mRequest.set(starter.mRequest);
    }

    ActivityRecord getStartActivity() {
        return mStartActivity;
    }

    boolean relatedToPackage(String packageName) {
        return (mLastStartActivityRecord[0] != null
                && packageName.equals(mLastStartActivityRecord[0].packageName))
                || (mStartActivity != null && packageName.equals(mStartActivity.packageName));
    }

    /**
     * Starts an activity based on the request parameters provided earlier.
     * @return The starter result.
     */
    int execute() {
        try {
            // TODO(b/64750076): Look into passing request directly to these methods to allow
            // for transactional diffs and preprocessing.
            if (mRequest.mayWait) {
                return startActivityMayWait(mRequest.caller, mRequest.callingUid,
                        mRequest.callingPackage, mRequest.realCallingPid, mRequest.realCallingUid,
                        mRequest.intent, mRequest.resolvedType,
                        mRequest.voiceSession, mRequest.voiceInteractor, mRequest.resultTo,
                        mRequest.resultWho, mRequest.requestCode, mRequest.startFlags,
                        mRequest.profilerInfo, mRequest.waitResult, mRequest.globalConfig,
                        mRequest.activityOptions, mRequest.ignoreTargetSecurity, mRequest.userId,
                        mRequest.inTask, mRequest.reason,
                        mRequest.allowPendingRemoteAnimationRegistryLookup,
                        mRequest.originatingPendingIntent);
            } else {
                return startActivity(mRequest.caller, mRequest.intent, mRequest.ephemeralIntent,
                        mRequest.resolvedType, mRequest.activityInfo, mRequest.resolveInfo,
                        mRequest.voiceSession, mRequest.voiceInteractor, mRequest.resultTo,
                        mRequest.resultWho, mRequest.requestCode, mRequest.callingPid,
                        mRequest.callingUid, mRequest.callingPackage, mRequest.realCallingPid,
                        mRequest.realCallingUid, mRequest.startFlags, mRequest.activityOptions,
                        mRequest.ignoreTargetSecurity, mRequest.componentSpecified,
                        mRequest.outActivity, mRequest.inTask, mRequest.reason,
                        mRequest.allowPendingRemoteAnimationRegistryLookup,
                        mRequest.originatingPendingIntent);
            }
        } finally {
            onExecutionComplete();
        }
    }

    /**
     * Starts an activity based on the provided {@link ActivityRecord} and environment parameters.
     * Note that this method is called internally as well as part of {@link #startActivity}.
     *
     * @return The start result.
     */
    int startResolvedActivity(final ActivityRecord r, ActivityRecord sourceRecord,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask,
            ActivityRecord[] outActivity) {
        try {
            return startActivity(r, sourceRecord, voiceSession, voiceInteractor, startFlags,
                    doResume, options, inTask, outActivity);
        } finally {
            onExecutionComplete();
        }
    }

    private int startActivity(IApplicationThread caller, Intent intent, Intent ephemeralIntent,
            String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid,
            String callingPackage, int realCallingPid, int realCallingUid, int startFlags,
            SafeActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified,
            ActivityRecord[] outActivity, TaskRecord inTask, String reason,
            boolean allowPendingRemoteAnimationRegistryLookup,
            PendingIntentRecord originatingPendingIntent) {

        if (TextUtils.isEmpty(reason)) {
            throw new IllegalArgumentException("Need to specify a reason.");
        }
        mLastStartReason = reason;
        mLastStartActivityTimeMs = System.currentTimeMillis();
        mLastStartActivityRecord[0] = null;

        mLastStartActivityResult = startActivity(caller, intent, ephemeralIntent, resolvedType,
                aInfo, rInfo, voiceSession, voiceInteractor, resultTo, resultWho, requestCode,
                callingPid, callingUid, callingPackage, realCallingPid, realCallingUid, startFlags,
                options, ignoreTargetSecurity, componentSpecified, mLastStartActivityRecord,
                inTask, allowPendingRemoteAnimationRegistryLookup, originatingPendingIntent);

        if (outActivity != null) {
            // mLastStartActivityRecord[0] is set in the call to startActivity above.
            outActivity[0] = mLastStartActivityRecord[0];
        }

        return getExternalResult(mLastStartActivityResult);
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

    private int startActivity(IApplicationThread caller, Intent intent, Intent ephemeralIntent,
            String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid,
            String callingPackage, int realCallingPid, int realCallingUid, int startFlags,
            SafeActivityOptions options,
            boolean ignoreTargetSecurity, boolean componentSpecified, ActivityRecord[] outActivity,
            TaskRecord inTask, boolean allowPendingRemoteAnimationRegistryLookup,
            PendingIntentRecord originatingPendingIntent) {
        int err = ActivityManager.START_SUCCESS;
        // Pull the optional Ephemeral Installer-only bundle out of the options early.
        final Bundle verificationBundle
                = options != null ? options.popAppVerificationBundle() : null;

        ProcessRecord callerApp = null;
        if (caller != null) {
            callerApp = mService.getRecordForAppLocked(caller);
            if (callerApp != null) {
                callingPid = callerApp.pid;
                callingUid = callerApp.info.uid;
            } else {
                Slog.w(TAG, "Unable to find app for caller " + caller
                        + " (pid=" + callingPid + ") when starting: "
                        + intent.toString());
                err = ActivityManager.START_PERMISSION_DENIED;
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
            sourceRecord = mSupervisor.isInAnyStackLocked(resultTo);
            if (DEBUG_RESULTS) Slog.v(TAG_RESULTS,
                    "Will send result to " + resultTo + " " + sourceRecord);
            if (sourceRecord != null) {
                if (requestCode >= 0 && !sourceRecord.finishing) {
                    resultRecord = sourceRecord;
                }
            }
        }

        final int launchFlags = intent.getFlags();

        if ((launchFlags & Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0 && sourceRecord != null) {
            // Transfer the result target from the source activity to the new
            // one being started, including any failures.
            if (requestCode >= 0) {
                SafeActivityOptions.abort(options);
                return ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT;
            }
            resultRecord = sourceRecord.resultTo;
            if (resultRecord != null && !resultRecord.isInStackLocked()) {
                resultRecord = null;
            }
            resultWho = sourceRecord.resultWho;
            requestCode = sourceRecord.requestCode;
            sourceRecord.resultTo = null;
            if (resultRecord != null) {
                resultRecord.removeResultsLocked(sourceRecord, resultWho, requestCode);
            }
            if (sourceRecord.launchedFromUid == callingUid) {
                // The new activity is being launched from the same uid as the previous
                // activity in the flow, and asking to forward its result back to the
                // previous.  In this case the activity is serving as a trampoline between
                // the two, so we also want to update its launchedFromPackage to be the
                // same as the previous activity.  Note that this is safe, since we know
                // these two packages come from the same uid; the caller could just as
                // well have supplied that same package name itself.  This specifially
                // deals with the case of an intent picker/chooser being launched in the app
                // flow to redirect to an activity picked by the user, where we want the final
                // activity to consider it to have been launched by the previous app activity.
                callingPackage = sourceRecord.launchedFromPackage;
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
            // If this activity is being launched as part of a voice session, we need
            // to ensure that it is safe to do so.  If the upcoming activity will also
            // be part of the voice session, we can only launch it if it has explicitly
            // said it supports the VOICE category, or it is a part of the calling app.
            if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) == 0
                    && sourceRecord.info.applicationInfo.uid != aInfo.applicationInfo.uid) {
                try {
                    intent.addCategory(Intent.CATEGORY_VOICE);
                    if (!mService.getPackageManager().activitySupportsIntent(
                            intent.getComponent(), intent, resolvedType)) {
                        Slog.w(TAG,
                                "Activity being started in current voice task does not support voice: "
                                        + intent);
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
                            "Activity being started in new voice task does not support: "
                                    + intent);
                    err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure checking voice capabilities", e);
                err = ActivityManager.START_NOT_VOICE_COMPATIBLE;
            }
        }

        final ActivityStack resultStack = resultRecord == null ? null : resultRecord.getStack();

        if (err != START_SUCCESS) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(
                        -1, resultRecord, resultWho, requestCode, RESULT_CANCELED, null);
            }
            SafeActivityOptions.abort(options);
            return err;
        }

        boolean abort = !mSupervisor.checkStartAnyActivityPermission(intent, aInfo, resultWho,
                requestCode, callingPid, callingUid, callingPackage, ignoreTargetSecurity,
                inTask != null, callerApp, resultRecord, resultStack);
        abort |= !mService.mIntentFirewall.checkStartActivity(intent, callingUid,
                callingPid, resolvedType, aInfo.applicationInfo);

        // Merge the two options bundles, while realCallerOptions takes precedence.
        ActivityOptions checkedOptions = options != null
                ? options.getOptions(intent, aInfo, callerApp, mSupervisor)
                : null;
        if (allowPendingRemoteAnimationRegistryLookup) {
            checkedOptions = mService.getActivityStartController()
                    .getPendingRemoteAnimationRegistry()
                    .overrideOptionsIfNeeded(callingPackage, checkedOptions);
        }
        if (mService.mController != null) {
            try {
                // The Intent we give to the watcher has the extra data
                // stripped off, since it can contain private information.
                Intent watchIntent = intent.cloneFilter();
                abort |= !mService.mController.activityStarting(watchIntent,
                        aInfo.applicationInfo.packageName);
            } catch (RemoteException e) {
                mService.mController = null;
            }
        }

        mInterceptor.setStates(userId, realCallingPid, realCallingUid, startFlags, callingPackage);
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
        }

        if (abort) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode,
                        RESULT_CANCELED, null);
            }
            // We pretend to the caller that it was really started, but
            // they will just get a cancel result.
            ActivityOptions.abort(checkedOptions);
            return START_ABORTED;
        }

        // If permissions need a review before any of the app components can run, we
        // launch the review activity and pass a pending intent to start the activity
        // we are to launching now after the review is completed.
        if (mService.mPermissionReviewRequired && aInfo != null) {
            if (mService.getPackageManagerInternalLocked().isPermissionsReviewRequired(
                    aInfo.packageName, userId)) {
                IIntentSender target = mService.getIntentSenderLocked(
                        ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                        callingUid, userId, null, null, 0, new Intent[]{intent},
                        new String[]{resolvedType}, PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_ONE_SHOT, null);

                final int flags = intent.getFlags();
                Intent newIntent = new Intent(Intent.ACTION_REVIEW_PERMISSIONS);
                newIntent.setFlags(flags
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                newIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, aInfo.packageName);
                newIntent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));
                if (resultRecord != null) {
                    newIntent.putExtra(Intent.EXTRA_RESULT_NEEDED, true);
                }
                intent = newIntent;

                resolvedType = null;
                callingUid = realCallingUid;
                callingPid = realCallingPid;

                rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId, 0,
                        computeResolveFilterUid(
                                callingUid, realCallingUid, mRequest.filterCallingUid));
                aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags,
                        null /*profilerInfo*/);

                if (DEBUG_PERMISSIONS_REVIEW) {
                    Slog.i(TAG, "START u" + userId + " {" + intent.toShortString(true, true,
                            true, false) + "} from uid " + callingUid + " on display "
                            + (mSupervisor.mFocusedStack == null
                            ? DEFAULT_DISPLAY : mSupervisor.mFocusedStack.mDisplayId));
                }
            }
        }

        // If we have an ephemeral app, abort the process of launching the resolved intent.
        // Instead, launch the ephemeral installer. Once the installer is finished, it
        // starts either the intent we resolved here [on install error] or the ephemeral
        // app [on install success].
        if (rInfo != null && rInfo.auxiliaryInfo != null) {
            intent = createLaunchIntent(rInfo.auxiliaryInfo, ephemeralIntent,
                    callingPackage, verificationBundle, resolvedType, userId);
            resolvedType = null;
            callingUid = realCallingUid;
            callingPid = realCallingPid;

            aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, null /*profilerInfo*/);
        }

        ActivityRecord r = new ActivityRecord(mService, callerApp, callingPid, callingUid,
                callingPackage, intent, resolvedType, aInfo, mService.getGlobalConfiguration(),
                resultRecord, resultWho, requestCode, componentSpecified, voiceSession != null,
                mSupervisor, checkedOptions, sourceRecord);
        if (outActivity != null) {
            outActivity[0] = r;
        }

        if (r.appTimeTracker == null && sourceRecord != null) {
            // If the caller didn't specify an explicit time tracker, we want to continue
            // tracking under any it has.
            r.appTimeTracker = sourceRecord.appTimeTracker;
        }

        final ActivityStack stack = mSupervisor.mFocusedStack;

        // If we are starting an activity that is not from the same uid as the currently resumed
        // one, check whether app switches are allowed.
        if (voiceSession == null && (stack.getResumedActivity() == null
                || stack.getResumedActivity().info.applicationInfo.uid != realCallingUid)) {
            if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid,
                    realCallingPid, realCallingUid, "Activity start")) {
                mController.addPendingActivityLaunch(new PendingActivityLaunch(r,
                        sourceRecord, startFlags, stack, callerApp));
                ActivityOptions.abort(checkedOptions);
                return ActivityManager.START_SWITCHES_CANCELED;
            }
        }

        if (mService.mDidAppSwitch) {
            // This is the second allowed switch since we stopped switches,
            // so now just generally allow switches.  Use case: user presses
            // home (switches disabled, switch to home, mDidAppSwitch now true);
            // user taps a home icon (coming from home so allowed, we hit here
            // and now allow anyone to switch again).
            mService.mAppSwitchesAllowedTime = 0;
        } else {
            mService.mDidAppSwitch = true;
        }

        mController.doPendingActivityLaunches(false);

        maybeLogActivityStart(callingUid, callingPackage, realCallingUid, intent, callerApp, r,
                originatingPendingIntent);

        return startActivity(r, sourceRecord, voiceSession, voiceInteractor, startFlags,
                true /* doResume */, checkedOptions, inTask, outActivity);
    }

    private void maybeLogActivityStart(int callingUid, String callingPackage, int realCallingUid,
            Intent intent, ProcessRecord callerApp, ActivityRecord r,
            PendingIntentRecord originatingPendingIntent) {
        boolean callerAppHasForegroundActivity = (callerApp != null)
                ? callerApp.foregroundActivities
                : false;
        if (!mService.isActivityStartsLoggingEnabled() || callerAppHasForegroundActivity
                || r == null) {
            // skip logging in this case
            return;
        }

        try {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "logActivityStart");
            final int callingUidProcState = mService.getUidStateLocked(callingUid);
            final boolean callingUidHasAnyVisibleWindow =
                    mService.mWindowManager.isAnyWindowVisibleForUid(callingUid);
            final int realCallingUidProcState = (callingUid == realCallingUid)
                    ? callingUidProcState
                    : mService.getUidStateLocked(realCallingUid);
            final boolean realCallingUidHasAnyVisibleWindow = (callingUid == realCallingUid)
                    ? callingUidHasAnyVisibleWindow
                    : mService.mWindowManager.isAnyWindowVisibleForUid(realCallingUid);
            final String targetPackage = r.packageName;
            final int targetUid = (r.appInfo != null) ? r.appInfo.uid : -1;
            final int targetUidProcState = mService.getUidStateLocked(targetUid);
            final boolean targetUidHasAnyVisibleWindow = (targetUid != -1)
                    ? mService.mWindowManager.isAnyWindowVisibleForUid(targetUid)
                    : false;
            final String targetWhitelistTag = (targetUid != -1)
                    ? mService.getPendingTempWhitelistTagForUidLocked(targetUid)
                    : null;

            mSupervisor.getActivityMetricsLogger().logActivityStart(intent, callerApp, r,
                    callingUid, callingPackage, callingUidProcState,
                    callingUidHasAnyVisibleWindow,
                    realCallingUid, realCallingUidProcState,
                    realCallingUidHasAnyVisibleWindow,
                    targetUid, targetPackage, targetUidProcState,
                    targetUidHasAnyVisibleWindow, targetWhitelistTag,
                    (originatingPendingIntent != null));
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    /**
     * Creates a launch intent for the given auxiliary resolution data.
     */
    private @NonNull Intent createLaunchIntent(@Nullable AuxiliaryResolveInfo auxiliaryResponse,
            Intent originalIntent, String callingPackage, Bundle verificationBundle,
            String resolvedType, int userId) {
        if (auxiliaryResponse != null && auxiliaryResponse.needsPhaseTwo) {
            // request phase two resolution
            mService.getPackageManagerInternalLocked().requestInstantAppResolutionPhaseTwo(
                    auxiliaryResponse, originalIntent, resolvedType, callingPackage,
                    verificationBundle, userId);
        }
        return InstantAppResolver.buildEphemeralInstallerIntent(
                originalIntent,
                InstantAppResolver.sanitizeIntent(originalIntent),
                auxiliaryResponse == null ? null : auxiliaryResponse.failureIntent,
                callingPackage,
                verificationBundle,
                resolvedType,
                userId,
                auxiliaryResponse == null ? null : auxiliaryResponse.installFailureActivity,
                auxiliaryResponse == null ? null : auxiliaryResponse.token,
                auxiliaryResponse != null && auxiliaryResponse.needsPhaseTwo,
                auxiliaryResponse == null ? null : auxiliaryResponse.filters);
    }

    void postStartActivityProcessing(ActivityRecord r, int result, ActivityStack targetStack) {
        if (ActivityManager.isStartResultFatalError(result)) {
            return;
        }

        // We're waiting for an activity launch to finish, but that activity simply
        // brought another activity to front. We must also handle the case where the task is already
        // in the front as a result of the trampoline activity being in the same task (it will be
        // considered focused as the trampoline will be finished). Let startActivityMayWait() know
        // about this, so it waits for the new activity to become visible instead.
        mSupervisor.reportWaitingActivityLaunchedIfNeeded(r, result);

        ActivityStack startedActivityStack = null;
        final ActivityStack currentStack = r.getStack();
        if (currentStack != null) {
            startedActivityStack = currentStack;
        } else if (mTargetStack != null) {
            startedActivityStack = targetStack;
        }

        if (startedActivityStack == null) {
            return;
        }

        final int clearTaskFlags = FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK;
        boolean clearedTask = (mLaunchFlags & clearTaskFlags) == clearTaskFlags
                && mReuseTask != null;
        if (result == START_TASK_TO_FRONT || result == START_DELIVERED_TO_TOP || clearedTask) {
            // The activity was already running so it wasn't started, but either brought to the
            // front or the new intent was delivered to it since it was already in front. Notify
            // anyone interested in this piece of information.
            switch (startedActivityStack.getWindowingMode()) {
                case WINDOWING_MODE_PINNED:
                    mService.mTaskChangeNotificationController.notifyPinnedActivityRestartAttempt(
                            clearedTask);
                    break;
                case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
                    final ActivityStack homeStack = mSupervisor.mHomeStack;
                    if (homeStack != null && homeStack.shouldBeVisible(null /* starting */)) {
                        mService.mWindowManager.showRecentApps();
                    }
                    break;
            }
        }
    }

    private int startActivityMayWait(IApplicationThread caller, int callingUid,
            String callingPackage, int requestRealCallingPid, int requestRealCallingUid,
            Intent intent, String resolvedType,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, WaitResult outResult,
            Configuration globalConfig, SafeActivityOptions options, boolean ignoreTargetSecurity,
            int userId, TaskRecord inTask, String reason,
            boolean allowPendingRemoteAnimationRegistryLookup,
            PendingIntentRecord originatingPendingIntent) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        mSupervisor.getActivityMetricsLogger().notifyActivityLaunching();
        boolean componentSpecified = intent.getComponent() != null;

        final int realCallingPid = requestRealCallingPid != Request.DEFAULT_REAL_CALLING_PID
                                   ? requestRealCallingPid
                                   : Binder.getCallingPid();
        final int realCallingUid = requestRealCallingUid != Request.DEFAULT_REAL_CALLING_UID
                                   ? requestRealCallingUid
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

        // Save a copy in case ephemeral needs it
        final Intent ephemeralIntent = new Intent(intent);
        // Don't modify the client's object!
        intent = new Intent(intent);
        if (componentSpecified
                && !(Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() == null)
                && !Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE.equals(intent.getAction())
                && !Intent.ACTION_RESOLVE_INSTANT_APP_PACKAGE.equals(intent.getAction())
                && mService.getPackageManagerInternalLocked()
                        .isInstantAppInstallerComponent(intent.getComponent())) {
            // intercept intents targeted directly to the ephemeral installer the
            // ephemeral installer should never be started with a raw Intent; instead
            // adjust the intent so it looks like a "normal" instant app launch
            intent.setComponent(null /*component*/);
            componentSpecified = false;
        }

        ResolveInfo rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId,
                0 /* matchFlags */,
                        computeResolveFilterUid(
                                callingUid, realCallingUid, mRequest.filterCallingUid));
        if (rInfo == null) {
            UserInfo userInfo = mSupervisor.getUserInfo(userId);
            if (userInfo != null && userInfo.isManagedProfile()) {
                // Special case for managed profiles, if attempting to launch non-cryto aware
                // app in a locked managed profile from an unlocked parent allow it to resolve
                // as user will be sent via confirm credentials to unlock the profile.
                UserManager userManager = UserManager.get(mService.mContext);
                boolean profileLockedAndParentUnlockingOrUnlocked = false;
                long token = Binder.clearCallingIdentity();
                try {
                    UserInfo parent = userManager.getProfileParent(userId);
                    profileLockedAndParentUnlockingOrUnlocked = (parent != null)
                            && userManager.isUserUnlockingOrUnlocked(parent.id)
                            && !userManager.isUserUnlockingOrUnlocked(userId);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
                if (profileLockedAndParentUnlockingOrUnlocked) {
                    rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId,
                            PackageManager.MATCH_DIRECT_BOOT_AWARE
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                            computeResolveFilterUid(
                                    callingUid, realCallingUid, mRequest.filterCallingUid));
                }
            }
        }
        // Collect information about the target of the Intent.
        ActivityInfo aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, profilerInfo);

        synchronized (mService) {
            final ActivityStack stack = mSupervisor.mFocusedStack;
            stack.mConfigWillChange = globalConfig != null
                    && mService.getGlobalConfiguration().diff(globalConfig) != 0;
            if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Starting activity when config will change = " + stack.mConfigWillChange);

            final long origId = Binder.clearCallingIdentity();

            if (aInfo != null &&
                    (aInfo.applicationInfo.privateFlags
                            & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0 &&
                    mService.mHasHeavyWeightFeature) {
                // This may be a heavy-weight process!  Check to see if we already
                // have another, different heavy-weight process running.
                if (aInfo.processName.equals(aInfo.applicationInfo.packageName)) {
                    final ProcessRecord heavy = mService.mHeavyWeightProcess;
                    if (heavy != null && (heavy.info.uid != aInfo.applicationInfo.uid
                            || !heavy.processName.equals(aInfo.processName))) {
                        int appCallingUid = callingUid;
                        if (caller != null) {
                            ProcessRecord callerApp = mService.getRecordForAppLocked(caller);
                            if (callerApp != null) {
                                appCallingUid = callerApp.info.uid;
                            } else {
                                Slog.w(TAG, "Unable to find app for caller " + caller
                                        + " (pid=" + callingPid + ") when starting: "
                                        + intent.toString());
                                SafeActivityOptions.abort(options);
                                return ActivityManager.START_PERMISSION_DENIED;
                            }
                        }

                        IIntentSender target = mService.getIntentSenderLocked(
                                ActivityManager.INTENT_SENDER_ACTIVITY, "android",
                                appCallingUid, userId, null, null, 0, new Intent[] { intent },
                                new String[] { resolvedType }, PendingIntent.FLAG_CANCEL_CURRENT
                                        | PendingIntent.FLAG_ONE_SHOT, null);

                        Intent newIntent = new Intent();
                        if (requestCode >= 0) {
                            // Caller is requesting a result.
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_HAS_RESULT, true);
                        }
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_INTENT,
                                new IntentSender(target));
                        if (heavy.activities.size() > 0) {
                            ActivityRecord hist = heavy.activities.get(0);
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_APP,
                                    hist.packageName);
                            newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_CUR_TASK,
                                    hist.getTask().taskId);
                        }
                        newIntent.putExtra(HeavyWeightSwitcherActivity.KEY_NEW_APP,
                                aInfo.packageName);
                        newIntent.setFlags(intent.getFlags());
                        newIntent.setClassName("android",
                                HeavyWeightSwitcherActivity.class.getName());
                        intent = newIntent;
                        resolvedType = null;
                        caller = null;
                        callingUid = Binder.getCallingUid();
                        callingPid = Binder.getCallingPid();
                        componentSpecified = true;
                        rInfo = mSupervisor.resolveIntent(intent, null /*resolvedType*/, userId,
                                0 /* matchFlags */, computeResolveFilterUid(
                                        callingUid, realCallingUid, mRequest.filterCallingUid));
                        aInfo = rInfo != null ? rInfo.activityInfo : null;
                        if (aInfo != null) {
                            aInfo = mService.getActivityInfoForUser(aInfo, userId);
                        }
                    }
                }
            }

            final ActivityRecord[] outRecord = new ActivityRecord[1];
            int res = startActivity(caller, intent, ephemeralIntent, resolvedType, aInfo, rInfo,
                    voiceSession, voiceInteractor, resultTo, resultWho, requestCode, callingPid,
                    callingUid, callingPackage, realCallingPid, realCallingUid, startFlags, options,
                    ignoreTargetSecurity, componentSpecified, outRecord, inTask, reason,
                    allowPendingRemoteAnimationRegistryLookup, originatingPendingIntent);

            Binder.restoreCallingIdentity(origId);

            if (stack.mConfigWillChange) {
                // If the caller also wants to switch to a new configuration,
                // do so now.  This allows a clean switch, as we are waiting
                // for the current activity to pause (so we will not destroy
                // it), and have not yet started the next activity.
                mService.enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                        "updateConfiguration()");
                stack.mConfigWillChange = false;
                if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                        "Updating to new configuration after starting activity.");
                mService.updateConfigurationLocked(globalConfig, null, false);
            }

            // Notify ActivityMetricsLogger that the activity has launched. ActivityMetricsLogger
            // will then wait for the windows to be drawn and populate WaitResult.
            mSupervisor.getActivityMetricsLogger().notifyActivityLaunched(res, outRecord[0]);
            if (outResult != null) {
                outResult.result = res;

                final ActivityRecord r = outRecord[0];

                switch(res) {
                    case START_SUCCESS: {
                        mSupervisor.mWaitingActivityLaunched.add(outResult);
                        do {
                            try {
                                mService.wait();
                            } catch (InterruptedException e) {
                            }
                        } while (outResult.result != START_TASK_TO_FRONT
                                && !outResult.timeout && outResult.who == null);
                        if (outResult.result == START_TASK_TO_FRONT) {
                            res = START_TASK_TO_FRONT;
                        }
                        break;
                    }
                    case START_DELIVERED_TO_TOP: {
                        outResult.timeout = false;
                        outResult.who = r.realActivity;
                        outResult.totalTime = 0;
                        break;
                    }
                    case START_TASK_TO_FRONT: {
                        // ActivityRecord may represent a different activity, but it should not be
                        // in the resumed state.
                        if (r.nowVisible && r.isState(RESUMED)) {
                            outResult.timeout = false;
                            outResult.who = r.realActivity;
                            outResult.totalTime = 0;
                        } else {
                            final long startTimeMs = SystemClock.uptimeMillis();
                            mSupervisor.waitActivityVisible(r.realActivity, outResult, startTimeMs);
                            // Note: the timeout variable is not currently not ever set.
                            do {
                                try {
                                    mService.wait();
                                } catch (InterruptedException e) {
                                }
                            } while (!outResult.timeout && outResult.who == null);
                        }
                        break;
                    }
                }
            }

            return res;
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
        return filterCallingUid != Request.DEFAULT_REAL_CALLING_UID
                ? filterCallingUid
                : (customCallingUid >= 0 ? customCallingUid : actualCallingUid);
    }

    private int startActivity(final ActivityRecord r, ActivityRecord sourceRecord,
                IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
                int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask,
                ActivityRecord[] outActivity) {
        int result = START_CANCELED;
        try {
            mService.mWindowManager.deferSurfaceLayout();
            result = startActivityUnchecked(r, sourceRecord, voiceSession, voiceInteractor,
                    startFlags, doResume, options, inTask, outActivity);
        } finally {
            // If we are not able to proceed, disassociate the activity from the task. Leaving an
            // activity in an incomplete state can lead to issues, such as performing operations
            // without a window container.
            final ActivityStack stack = mStartActivity.getStack();
            if (!ActivityManager.isStartResultSuccessful(result) && stack != null) {
                stack.finishActivityLocked(mStartActivity, RESULT_CANCELED,
                        null /* intentResultData */, "startActivity", true /* oomAdj */);
            }
            mService.mWindowManager.continueSurfaceLayout();
        }

        postStartActivityProcessing(r, result, mTargetStack);

        return result;
    }

    // Note: This method should only be called from {@link startActivity}.
    private int startActivityUnchecked(final ActivityRecord r, ActivityRecord sourceRecord,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            int startFlags, boolean doResume, ActivityOptions options, TaskRecord inTask,
            ActivityRecord[] outActivity) {

        setInitialState(r, options, inTask, doResume, startFlags, sourceRecord, voiceSession,
                voiceInteractor);

        computeLaunchingTaskFlags();

        computeSourceStack();

        mIntent.setFlags(mLaunchFlags);

        ActivityRecord reusedActivity = getReusableIntentActivity();

        int preferredWindowingMode = WINDOWING_MODE_UNDEFINED;
        int preferredLaunchDisplayId = DEFAULT_DISPLAY;
        if (mOptions != null) {
            preferredWindowingMode = mOptions.getLaunchWindowingMode();
            preferredLaunchDisplayId = mOptions.getLaunchDisplayId();
        }

        // windowing mode and preferred launch display values from {@link LaunchParams} take
        // priority over those specified in {@link ActivityOptions}.
        if (!mLaunchParams.isEmpty()) {
            if (mLaunchParams.hasPreferredDisplay()) {
                preferredLaunchDisplayId = mLaunchParams.mPreferredDisplayId;
            }

            if (mLaunchParams.hasWindowingMode()) {
                preferredWindowingMode = mLaunchParams.mWindowingMode;
            }
        }

        if (reusedActivity != null) {
            // When the flags NEW_TASK and CLEAR_TASK are set, then the task gets reused but
            // still needs to be a lock task mode violation since the task gets cleared out and
            // the device would otherwise leave the locked task.
            if (mService.getLockTaskController().isLockTaskModeViolation(reusedActivity.getTask(),
                    (mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                            == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))) {
                Slog.e(TAG, "startActivityUnchecked: Attempt to violate Lock Task Mode");
                return START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }

            // True if we are clearing top and resetting of a standard (default) launch mode
            // ({@code LAUNCH_MULTIPLE}) activity. The existing activity will be finished.
            final boolean clearTopAndResetStandardLaunchMode =
                    (mLaunchFlags & (FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                            == (FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    && mLaunchMode == LAUNCH_MULTIPLE;

            // If mStartActivity does not have a task associated with it, associate it with the
            // reused activity's task. Do not do so if we're clearing top and resetting for a
            // standard launchMode activity.
            if (mStartActivity.getTask() == null && !clearTopAndResetStandardLaunchMode) {
                mStartActivity.setTask(reusedActivity.getTask());
            }

            if (reusedActivity.getTask().intent == null) {
                // This task was started because of movement of the activity based on affinity...
                // Now that we are actually launching it, we can assign the base intent.
                reusedActivity.getTask().setIntent(mStartActivity);
            }

            // This code path leads to delivering a new intent, we want to make sure we schedule it
            // as the first operation, in case the activity will be resumed as a result of later
            // operations.
            if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                    || isDocumentLaunchesIntoExisting(mLaunchFlags)
                    || isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK)) {
                final TaskRecord task = reusedActivity.getTask();

                // In this situation we want to remove all activities from the task up to the one
                // being started. In most cases this means we are resetting the task to its initial
                // state.
                final ActivityRecord top = task.performClearTaskForReuseLocked(mStartActivity,
                        mLaunchFlags);

                // The above code can remove {@code reusedActivity} from the task, leading to the
                // the {@code ActivityRecord} removing its reference to the {@code TaskRecord}. The
                // task reference is needed in the call below to
                // {@link setTargetStackAndMoveToFrontIfNeeded}.
                if (reusedActivity.getTask() == null) {
                    reusedActivity.setTask(task);
                }

                if (top != null) {
                    if (top.frontOfTask) {
                        // Activity aliases may mean we use different intents for the top activity,
                        // so make sure the task now has the identity of the new intent.
                        top.getTask().setIntent(mStartActivity);
                    }
                    deliverNewIntent(top);
                }
            }

            mSupervisor.sendPowerHintForLaunchStartIfNeeded(false /* forceSend */, reusedActivity);

            reusedActivity = setTargetStackAndMoveToFrontIfNeeded(reusedActivity);

            final ActivityRecord outResult =
                    outActivity != null && outActivity.length > 0 ? outActivity[0] : null;

            // When there is a reused activity and the current result is a trampoline activity,
            // set the reused activity as the result.
            if (outResult != null && (outResult.finishing || outResult.noDisplay)) {
                outActivity[0] = reusedActivity;
            }

            if ((mStartFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
                // We don't need to start a new activity, and the client said not to do anything
                // if that is the case, so this is it!  And for paranoia, make sure we have
                // correctly resumed the top activity.
                resumeTargetStackIfNeeded();
                return START_RETURN_INTENT_TO_CALLER;
            }

            if (reusedActivity != null) {
                setTaskFromIntentActivity(reusedActivity);

                if (!mAddingToTask && mReuseTask == null) {
                    // We didn't do anything...  but it was needed (a.k.a., client don't use that
                    // intent!)  And for paranoia, make sure we have correctly resumed the top activity.

                    resumeTargetStackIfNeeded();
                    if (outActivity != null && outActivity.length > 0) {
                        outActivity[0] = reusedActivity;
                    }

                    return mMovedToFront ? START_TASK_TO_FRONT : START_DELIVERED_TO_TOP;
                }
            }
        }

        if (mStartActivity.packageName == null) {
            final ActivityStack sourceStack = mStartActivity.resultTo != null
                    ? mStartActivity.resultTo.getStack() : null;
            if (sourceStack != null) {
                sourceStack.sendActivityResultLocked(-1 /* callingUid */, mStartActivity.resultTo,
                        mStartActivity.resultWho, mStartActivity.requestCode, RESULT_CANCELED,
                        null /* data */);
            }
            ActivityOptions.abort(mOptions);
            return START_CLASS_NOT_FOUND;
        }

        // If the activity being launched is the same as the one currently at the top, then
        // we need to check if it should only be launched once.
        final ActivityStack topStack = mSupervisor.mFocusedStack;
        final ActivityRecord topFocused = topStack.getTopActivity();
        final ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(mNotTop);
        final boolean dontStart = top != null && mStartActivity.resultTo == null
                && top.realActivity.equals(mStartActivity.realActivity)
                && top.userId == mStartActivity.userId
                && top.app != null && top.app.thread != null
                && ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
                || isLaunchModeOneOf(LAUNCH_SINGLE_TOP, LAUNCH_SINGLE_TASK));
        if (dontStart) {
            // For paranoia, make sure we have correctly resumed the top activity.
            topStack.mLastPausedActivity = null;
            if (mDoResume) {
                mSupervisor.resumeFocusedStackTopActivityLocked();
            }
            ActivityOptions.abort(mOptions);
            if ((mStartFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
                // We don't need to start a new activity, and the client said not to do
                // anything if that is the case, so this is it!
                return START_RETURN_INTENT_TO_CALLER;
            }

            deliverNewIntent(top);

            // Don't use mStartActivity.task to show the toast. We're not starting a new activity
            // but reusing 'top'. Fields in mStartActivity may not be fully initialized.
            mSupervisor.handleNonResizableTaskIfNeeded(top.getTask(), preferredWindowingMode,
                    preferredLaunchDisplayId, topStack);

            return START_DELIVERED_TO_TOP;
        }

        boolean newTask = false;
        final TaskRecord taskToAffiliate = (mLaunchTaskBehind && mSourceRecord != null)
                ? mSourceRecord.getTask() : null;

        // Should this be considered a new task?
        int result = START_SUCCESS;
        if (mStartActivity.resultTo == null && mInTask == null && !mAddingToTask
                && (mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            newTask = true;
            result = setTaskFromReuseOrCreateNewTask(taskToAffiliate, topStack);
        } else if (mSourceRecord != null) {
            result = setTaskFromSourceRecord();
        } else if (mInTask != null) {
            result = setTaskFromInTask();
        } else {
            // This not being started from an existing activity, and not part of a new task...
            // just put it in the top task, though these days this case should never happen.
            setTaskToCurrentTopOrCreateNewTask();
        }
        if (result != START_SUCCESS) {
            return result;
        }

        mService.grantUriPermissionFromIntentLocked(mCallingUid, mStartActivity.packageName,
                mIntent, mStartActivity.getUriPermissionsLocked(), mStartActivity.userId);
        mService.grantEphemeralAccessLocked(mStartActivity.userId, mIntent,
                mStartActivity.appInfo.uid, UserHandle.getAppId(mCallingUid));
        if (newTask) {
            EventLog.writeEvent(EventLogTags.AM_CREATE_TASK, mStartActivity.userId,
                    mStartActivity.getTask().taskId);
        }
        ActivityStack.logStartActivity(
                EventLogTags.AM_CREATE_ACTIVITY, mStartActivity, mStartActivity.getTask());
        mTargetStack.mLastPausedActivity = null;

        mSupervisor.sendPowerHintForLaunchStartIfNeeded(false /* forceSend */, mStartActivity);

        mTargetStack.startActivityLocked(mStartActivity, topFocused, newTask, mKeepCurTransition,
                mOptions);
        if (mDoResume) {
            final ActivityRecord topTaskActivity =
                    mStartActivity.getTask().topRunningActivityLocked();
            if (!mTargetStack.isFocusable()
                    || (topTaskActivity != null && topTaskActivity.mTaskOverlay
                    && mStartActivity != topTaskActivity)) {
                // If the activity is not focusable, we can't resume it, but still would like to
                // make sure it becomes visible as it starts (this will also trigger entry
                // animation). An example of this are PIP activities.
                // Also, we don't want to resume activities in a task that currently has an overlay
                // as the starting activity just needs to be in the visible paused state until the
                // over is removed.
                mTargetStack.ensureActivitiesVisibleLocked(null, 0, !PRESERVE_WINDOWS);
                // Go ahead and tell window manager to execute app transition for this activity
                // since the app transition will not be triggered through the resume channel.
                mService.mWindowManager.executeAppTransition();
            } else {
                // If the target stack was not previously focusable (previous top running activity
                // on that stack was not visible) then any prior calls to move the stack to the
                // will not update the focused stack.  If starting the new activity now allows the
                // task stack to be focusable, then ensure that we now update the focused stack
                // accordingly.
                if (mTargetStack.isFocusable() && !mSupervisor.isFocusedStack(mTargetStack)) {
                    mTargetStack.moveToFront("startActivityUnchecked");
                }
                mSupervisor.resumeFocusedStackTopActivityLocked(mTargetStack, mStartActivity,
                        mOptions);
            }
        } else if (mStartActivity != null) {
            mSupervisor.mRecentTasks.add(mStartActivity.getTask());
        }
        mSupervisor.updateUserStackLocked(mStartActivity.userId, mTargetStack);

        mSupervisor.handleNonResizableTaskIfNeeded(mStartActivity.getTask(), preferredWindowingMode,
                preferredLaunchDisplayId, mTargetStack);

        return START_SUCCESS;
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

        mLaunchTaskBehind = false;
        mLaunchFlags = 0;
        mLaunchMode = INVALID_LAUNCH_MODE;

        mLaunchParams.reset();

        mNotTop = null;
        mDoResume = false;
        mStartFlags = 0;
        mSourceRecord = null;
        mPreferredDisplayId = INVALID_DISPLAY;

        mInTask = null;
        mAddingToTask = false;
        mReuseTask = null;

        mNewTaskInfo = null;
        mNewTaskIntent = null;
        mSourceStack = null;

        mTargetStack = null;
        mMovedToFront = false;
        mNoAnimation = false;
        mKeepCurTransition = false;
        mAvoidMoveToFront = false;

        mVoiceSession = null;
        mVoiceInteractor = null;

        mIntentDelivered = false;

        if (clearRequest) {
            mRequest.reset();
        }
    }

    private void setInitialState(ActivityRecord r, ActivityOptions options, TaskRecord inTask,
            boolean doResume, int startFlags, ActivityRecord sourceRecord,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
        reset(false /* clearRequest */);

        mStartActivity = r;
        mIntent = r.intent;
        mOptions = options;
        mCallingUid = r.launchedFromUid;
        mSourceRecord = sourceRecord;
        mVoiceSession = voiceSession;
        mVoiceInteractor = voiceInteractor;

        mPreferredDisplayId = getPreferedDisplayId(mSourceRecord, mStartActivity, options);

        mLaunchParams.reset();

        mSupervisor.getLaunchParamsController().calculate(inTask, null /*layout*/, r, sourceRecord,
                options, mLaunchParams);

        mLaunchMode = r.launchMode;

        mLaunchFlags = adjustLaunchFlagsToDocumentMode(
                r, LAUNCH_SINGLE_INSTANCE == mLaunchMode,
                LAUNCH_SINGLE_TASK == mLaunchMode, mIntent.getFlags());
        mLaunchTaskBehind = r.mLaunchTaskBehind
                && !isLaunchModeOneOf(LAUNCH_SINGLE_TASK, LAUNCH_SINGLE_INSTANCE)
                && (mLaunchFlags & FLAG_ACTIVITY_NEW_DOCUMENT) != 0;

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
        if (!doResume || !r.okToShowLocked()) {
            r.delayedResume = true;
            mDoResume = false;
        }

        if (mOptions != null) {
            if (mOptions.getLaunchTaskId() != -1 && mOptions.getTaskOverlay()) {
                r.mTaskOverlay = true;
                if (!mOptions.canTaskOverlayResume()) {
                    final TaskRecord task = mSupervisor.anyTaskForIdLocked(
                            mOptions.getLaunchTaskId());
                    final ActivityRecord top = task != null ? task.getTopActivity() : null;
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
        }

        mNotTop = (mLaunchFlags & FLAG_ACTIVITY_PREVIOUS_IS_TOP) != 0 ? r : null;

        mInTask = inTask;
        // In some flows in to this function, we retrieve the task record and hold on to it
        // without a lock before calling back in to here...  so the task at this point may
        // not actually be in recents.  Check for that, and if it isn't in recents just
        // consider it invalid.
        if (inTask != null && !inTask.inRecents) {
            Slog.w(TAG, "Starting activity in task not in recents: " + inTask);
            mInTask = null;
        }

        mStartFlags = startFlags;
        // If the onlyIfNeeded flag is set, then we can do this if the activity being launched
        // is the same as the one making the call...  or, as a special case, if we do not know
        // the caller then we count the current top activity as the caller.
        if ((startFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
            ActivityRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = mSupervisor.mFocusedStack.topRunningNonDelayedActivityLocked(
                        mNotTop);
            }
            if (!checkedCaller.realActivity.equals(r.realActivity)) {
                // Caller is not the same as launcher, so always needed.
                mStartFlags &= ~START_FLAG_ONLY_IF_NEEDED;
            }
        }

        mNoAnimation = (mLaunchFlags & FLAG_ACTIVITY_NO_ANIMATION) != 0;
    }

    private void sendNewTaskResultRequestIfNeeded() {
        final ActivityStack sourceStack = mStartActivity.resultTo != null
                ? mStartActivity.resultTo.getStack() : null;
        if (sourceStack != null && (mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            // For whatever reason this activity is being launched into a new task...
            // yet the caller has requested a result back.  Well, that is pretty messed up,
            // so instead immediately send back a cancel and let the new task continue launched
            // as normal without a dependency on its originator.
            Slog.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
            sourceStack.sendActivityResultLocked(-1 /* callingUid */, mStartActivity.resultTo,
                    mStartActivity.resultWho, mStartActivity.requestCode, RESULT_CANCELED,
                    null /* data */);
            mStartActivity.resultTo = null;
        }
    }

    private void computeLaunchingTaskFlags() {
        // If the caller is not coming from another activity, but has given us an explicit task into
        // which they would like us to launch the new activity, then let's see about doing that.
        if (mSourceRecord == null && mInTask != null && mInTask.getStack() != null) {
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
            // in any task/stack, however it could launch other activities like ResolverActivity,
            // and we want those to stay in the original task.
            if ((mStartActivity.isResolverActivity() || mStartActivity.noDisplay) && mSourceRecord != null
                    && mSourceRecord.inFreeformWindowingMode())  {
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

    private void computeSourceStack() {
        if (mSourceRecord == null) {
            mSourceStack = null;
            return;
        }
        if (!mSourceRecord.finishing) {
            mSourceStack = mSourceRecord.getStack();
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
            final TaskRecord sourceTask = mSourceRecord.getTask();
            mNewTaskIntent = sourceTask != null ? sourceTask.intent : null;
        }
        mSourceRecord = null;
        mSourceStack = null;
    }

    /**
     * Decide whether the new activity should be inserted into an existing task. Returns null
     * if not or an ActivityRecord with the task into which the new activity should be added.
     */
    private ActivityRecord getReusableIntentActivity() {
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
        if (mOptions != null && mOptions.getLaunchTaskId() != -1) {
            final TaskRecord task = mSupervisor.anyTaskForIdLocked(mOptions.getLaunchTaskId());
            intentActivity = task != null ? task.getTopActivity() : null;
        } else if (putIntoExistingTask) {
            if (LAUNCH_SINGLE_INSTANCE == mLaunchMode) {
                // There can be one and only one instance of single instance activity in the
                // history, and it is always in its own unique task, so we do a special search.
               intentActivity = mSupervisor.findActivityLocked(mIntent, mStartActivity.info,
                       mStartActivity.isActivityTypeHome());
            } else if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
                // For the launch adjacent case we only want to put the activity in an existing
                // task if the activity already exists in the history.
                intentActivity = mSupervisor.findActivityLocked(mIntent, mStartActivity.info,
                        !(LAUNCH_SINGLE_TASK == mLaunchMode));
            } else {
                // Otherwise find the best task to put the activity in.
                intentActivity = mSupervisor.findTaskLocked(mStartActivity, mPreferredDisplayId);
            }
        }
        return intentActivity;
    }

    /**
     * Returns the ID of the display to use for a new activity. If the device is in VR mode,
     * then return the Vr mode's virtual display ID. If not,  if the activity was started with
     * a launchDisplayId, use that. Otherwise, if the source activity has a explicit display ID
     * set, use that to launch the activity.
     */
    private int getPreferedDisplayId(
            ActivityRecord sourceRecord, ActivityRecord startingActivity, ActivityOptions options) {
        // Check if the Activity is a VR activity. If so, the activity should be launched in
        // main display.
        if (startingActivity != null && startingActivity.requestedVrComponent != null) {
            return DEFAULT_DISPLAY;
        }

        // Get the virtual display id from ActivityManagerService.
        int displayId = mService.mVr2dDisplayId;
        if (displayId != INVALID_DISPLAY) {
            if (DEBUG_STACK) {
                Slog.d(TAG, "getSourceDisplayId :" + displayId);
            }
            return displayId;
        }

        // If the caller requested a display, prefer that display.
        final int launchDisplayId =
                (options != null) ? options.getLaunchDisplayId() : INVALID_DISPLAY;
        if (launchDisplayId != INVALID_DISPLAY) {
            return launchDisplayId;
        }

        displayId = sourceRecord != null ? sourceRecord.getDisplayId() : INVALID_DISPLAY;
        // If the activity has a displayId set explicitly, launch it on the same displayId.
        if (displayId != INVALID_DISPLAY) {
            return displayId;
        }
        return DEFAULT_DISPLAY;
    }

    /**
     * Figure out which task and activity to bring to front when we have found an existing matching
     * activity record in history. May also clear the task if needed.
     * @param intentActivity Existing matching activity.
     * @return {@link ActivityRecord} brought to front.
     */
    private ActivityRecord setTargetStackAndMoveToFrontIfNeeded(ActivityRecord intentActivity) {
        mTargetStack = intentActivity.getStack();
        mTargetStack.mLastPausedActivity = null;
        // If the target task is not in the front, then we need to bring it to the front...
        // except...  well, with SINGLE_TASK_LAUNCH it's not entirely clear. We'd like to have
        // the same behavior as if a new instance was being started, which means not bringing it
        // to the front if the caller is not itself in the front.
        final ActivityStack focusStack = mSupervisor.getFocusedStack();
        ActivityRecord curTop = (focusStack == null)
                ? null : focusStack.topRunningNonDelayedActivityLocked(mNotTop);

        final TaskRecord topTask = curTop != null ? curTop.getTask() : null;
        if (topTask != null
                && (topTask != intentActivity.getTask() || topTask != focusStack.topTask())
                && !mAvoidMoveToFront) {
            mStartActivity.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
            if (mSourceRecord == null || (mSourceStack.getTopActivity() != null &&
                    mSourceStack.getTopActivity().getTask() == mSourceRecord.getTask())) {
                // We really do want to push this one into the user's face, right now.
                if (mLaunchTaskBehind && mSourceRecord != null) {
                    intentActivity.setTaskToAffiliateWith(mSourceRecord.getTask());
                }

                // If the launch flags carry both NEW_TASK and CLEAR_TASK, the task's activities
                // will be cleared soon by ActivityStarter in setTaskFromIntentActivity().
                // So no point resuming any of the activities here, it just wastes one extra
                // resuming, plus enter AND exit transitions.
                // Here we only want to bring the target stack forward. Transition will be applied
                // to the new activity that's started after the old ones are gone.
                final boolean willClearTask =
                        (mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                            == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
                if (!willClearTask) {
                    final ActivityStack launchStack = getLaunchStack(
                            mStartActivity, mLaunchFlags, mStartActivity.getTask(), mOptions);
                    final TaskRecord intentTask = intentActivity.getTask();
                    if (launchStack == null || launchStack == mTargetStack) {
                        // We only want to move to the front, if we aren't going to launch on a
                        // different stack. If we launch on a different stack, we will put the
                        // task on top there.
                        mTargetStack.moveTaskToFrontLocked(intentTask, mNoAnimation, mOptions,
                                mStartActivity.appTimeTracker, "bringingFoundTaskToFront");
                        mMovedToFront = true;
                    } else if (launchStack.inSplitScreenWindowingMode()) {
                        if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
                            // If we want to launch adjacent and mTargetStack is not the computed
                            // launch stack - move task to top of computed stack.
                            intentTask.reparent(launchStack, ON_TOP,
                                    REPARENT_MOVE_STACK_TO_FRONT, ANIMATE, DEFER_RESUME,
                                    "launchToSide");
                        } else {
                            // TODO: This should be reevaluated in MW v2.
                            // We choose to move task to front instead of launching it adjacent
                            // when specific stack was requested explicitly and it appeared to be
                            // adjacent stack, but FLAG_ACTIVITY_LAUNCH_ADJACENT was not set.
                            mTargetStack.moveTaskToFrontLocked(intentTask,
                                    mNoAnimation, mOptions, mStartActivity.appTimeTracker,
                                    "bringToFrontInsteadOfAdjacentLaunch");
                        }
                        mMovedToFront = launchStack != launchStack.getDisplay()
                                .getTopStackInWindowingMode(launchStack.getWindowingMode());
                    } else if (launchStack.mDisplayId != mTargetStack.mDisplayId) {
                        // Target and computed stacks are on different displays and we've
                        // found a matching task - move the existing instance to that display and
                        // move it to front.
                        intentActivity.getTask().reparent(launchStack, ON_TOP,
                                REPARENT_MOVE_STACK_TO_FRONT, ANIMATE, DEFER_RESUME,
                                "reparentToDisplay");
                        mMovedToFront = true;
                    } else if (launchStack.isActivityTypeHome()
                            && !mTargetStack.isActivityTypeHome()) {
                        // It is possible for the home activity to be in another stack initially.
                        // For example, the activity may have been initially started with an intent
                        // which placed it in the fullscreen stack. To ensure the proper handling of
                        // the activity based on home stack assumptions, we must move it over.
                        intentActivity.getTask().reparent(launchStack, ON_TOP,
                                REPARENT_MOVE_STACK_TO_FRONT, ANIMATE, DEFER_RESUME,
                                "reparentingHome");
                        mMovedToFront = true;
                    }
                    mOptions = null;

                    // We are moving a task to the front, use starting window to hide initial drawn
                    // delay.
                    intentActivity.showStartingWindow(null /* prev */, false /* newTask */,
                            true /* taskSwitch */);
                }
            }
        }
        // Need to update mTargetStack because if task was moved out of it, the original stack may
        // be destroyed.
        mTargetStack = intentActivity.getStack();
        if (!mMovedToFront && mDoResume) {
            if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Bring to front target: " + mTargetStack
                    + " from " + intentActivity);
            mTargetStack.moveToFront("intentActivityFound");
        }

        mSupervisor.handleNonResizableTaskIfNeeded(intentActivity.getTask(),
                WINDOWING_MODE_UNDEFINED, DEFAULT_DISPLAY, mTargetStack);

        // If the caller has requested that the target task be reset, then do so.
        if ((mLaunchFlags & FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            return mTargetStack.resetTaskIfNeededLocked(intentActivity, mStartActivity);
        }
        return intentActivity;
    }

    private void setTaskFromIntentActivity(ActivityRecord intentActivity) {
        if ((mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)) {
            // The caller has requested to completely replace any existing task with its new
            // activity. Well that should not be too hard...
            // Note: we must persist the {@link TaskRecord} first as intentActivity could be
            // removed from calling performClearTaskLocked (For example, if it is being brought out
            // of history or if it is finished immediately), thus disassociating the task. Also note
            // that mReuseTask is reset as a result of {@link TaskRecord#performClearTaskLocked}
            // launching another activity.
            // TODO(b/36119896):  We shouldn't trigger activity launches in this path since we are
            // already launching one.
            final TaskRecord task = intentActivity.getTask();
            task.performClearTaskLocked();
            mReuseTask = task;
            mReuseTask.setIntent(mStartActivity);
        } else if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                || isLaunchModeOneOf(LAUNCH_SINGLE_INSTANCE, LAUNCH_SINGLE_TASK)) {
            ActivityRecord top = intentActivity.getTask().performClearTaskLocked(mStartActivity,
                    mLaunchFlags);
            if (top == null) {
                // A special case: we need to start the activity because it is not currently
                // running, and the caller has asked to clear the current task to have this
                // activity at the top.
                mAddingToTask = true;

                // We are no longer placing the activity in the task we previously thought we were.
                mStartActivity.setTask(null);
                // Now pretend like this activity is being started by the top of its task, so it
                // is put in the right place.
                mSourceRecord = intentActivity;
                final TaskRecord task = mSourceRecord.getTask();
                if (task != null && task.getStack() == null) {
                    // Target stack got cleared when we all activities were removed above.
                    // Go ahead and reset it.
                    mTargetStack = computeStackFocus(mSourceRecord, false /* newTask */,
                            mLaunchFlags, mOptions);
                    mTargetStack.addTask(task,
                            !mLaunchTaskBehind /* toTop */, "startActivityUnchecked");
                }
            }
        } else if (mStartActivity.realActivity.equals(intentActivity.getTask().realActivity)) {
            // In this case the top activity on the task is the same as the one being launched,
            // so we take that as a request to bring the task to the foreground. If the top
            // activity in the task is the root activity, deliver this new intent to it if it
            // desires.
            if (((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
                        || LAUNCH_SINGLE_TOP == mLaunchMode)
                    && intentActivity.realActivity.equals(mStartActivity.realActivity)) {
                if (intentActivity.frontOfTask) {
                    intentActivity.getTask().setIntent(mStartActivity);
                }
                deliverNewIntent(intentActivity);
            } else if (!intentActivity.getTask().isSameIntentFilter(mStartActivity)) {
                // In this case we are launching the root activity of the task, but with a
                // different intent. We should start a new instance on top.
                mAddingToTask = true;
                mSourceRecord = intentActivity;
            }
        } else if ((mLaunchFlags & FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
            // In this case an activity is being launched in to an existing task, without
            // resetting that task. This is typically the situation of launching an activity
            // from a notification or shortcut. We want to place the new activity on top of the
            // current task.
            mAddingToTask = true;
            mSourceRecord = intentActivity;
        } else if (!intentActivity.getTask().rootWasReset) {
            // In this case we are launching into an existing task that has not yet been started
            // from its front door. The current task has been brought to the front. Ideally,
            // we'd probably like to place this new task at the bottom of its stack, but that's
            // a little hard to do with the current organization of the code so for now we'll
            // just drop it.
            intentActivity.getTask().setIntent(mStartActivity);
        }
    }

    private void resumeTargetStackIfNeeded() {
        if (mDoResume) {
            mSupervisor.resumeFocusedStackTopActivityLocked(mTargetStack, null, mOptions);
        } else {
            ActivityOptions.abort(mOptions);
        }
        mSupervisor.updateUserStackLocked(mStartActivity.userId, mTargetStack);
    }

    private int setTaskFromReuseOrCreateNewTask(
            TaskRecord taskToAffiliate, ActivityStack topStack) {
        mTargetStack = computeStackFocus(mStartActivity, true, mLaunchFlags, mOptions);

        // Do no move the target stack to front yet, as we might bail if
        // isLockTaskModeViolation fails below.

        if (mReuseTask == null) {
            final TaskRecord task = mTargetStack.createTaskRecord(
                    mSupervisor.getNextTaskIdForUserLocked(mStartActivity.userId),
                    mNewTaskInfo != null ? mNewTaskInfo : mStartActivity.info,
                    mNewTaskIntent != null ? mNewTaskIntent : mIntent, mVoiceSession,
                    mVoiceInteractor, !mLaunchTaskBehind /* toTop */, mStartActivity, mSourceRecord,
                    mOptions);
            addOrReparentStartingActivity(task, "setTaskFromReuseOrCreateNewTask - mReuseTask");
            updateBounds(mStartActivity.getTask(), mLaunchParams.mBounds);

            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + mStartActivity
                    + " in new task " + mStartActivity.getTask());
        } else {
            addOrReparentStartingActivity(mReuseTask, "setTaskFromReuseOrCreateNewTask");
        }

        if (taskToAffiliate != null) {
            mStartActivity.setTaskToAffiliateWith(taskToAffiliate);
        }

        if (mService.getLockTaskController().isLockTaskModeViolation(mStartActivity.getTask())) {
            Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
            return START_RETURN_LOCK_TASK_MODE_VIOLATION;
        }

        if (mDoResume) {
            mTargetStack.moveToFront("reuseOrNewTask");
        }
        return START_SUCCESS;
    }

    private void deliverNewIntent(ActivityRecord activity) {
        if (mIntentDelivered) {
            return;
        }

        ActivityStack.logStartActivity(AM_NEW_INTENT, activity, activity.getTask());
        activity.deliverNewIntentLocked(mCallingUid, mStartActivity.intent,
                mStartActivity.launchedFromPackage);
        mIntentDelivered = true;
    }

    private int setTaskFromSourceRecord() {
        if (mService.getLockTaskController().isLockTaskModeViolation(mSourceRecord.getTask())) {
            Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
            return START_RETURN_LOCK_TASK_MODE_VIOLATION;
        }

        final TaskRecord sourceTask = mSourceRecord.getTask();
        final ActivityStack sourceStack = mSourceRecord.getStack();
        // We only want to allow changing stack in two cases:
        // 1. If the target task is not the top one. Otherwise we would move the launching task to
        //    the other side, rather than show two side by side.
        // 2. If activity is not allowed on target display.
        final int targetDisplayId = mTargetStack != null ? mTargetStack.mDisplayId
                : sourceStack.mDisplayId;
        final boolean moveStackAllowed = sourceStack.topTask() != sourceTask
                || !mStartActivity.canBeLaunchedOnDisplay(targetDisplayId);
        if (moveStackAllowed) {
            mTargetStack = getLaunchStack(mStartActivity, mLaunchFlags, mStartActivity.getTask(),
                    mOptions);
            // If target stack is not found now - we can't just rely on the source stack, as it may
            // be not suitable. Let's check other displays.
            if (mTargetStack == null && targetDisplayId != sourceStack.mDisplayId) {
                // Can't use target display, lets find a stack on the source display.
                mTargetStack = mService.mStackSupervisor.getValidLaunchStackOnDisplay(
                        sourceStack.mDisplayId, mStartActivity);
            }
            if (mTargetStack == null) {
                // There are no suitable stacks on the target and source display(s). Look on all
                // displays.
                mTargetStack = mService.mStackSupervisor.getNextValidLaunchStackLocked(
                        mStartActivity, -1 /* currentFocus */);
            }
        }

        if (mTargetStack == null) {
            mTargetStack = sourceStack;
        } else if (mTargetStack != sourceStack) {
            sourceTask.reparent(mTargetStack, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT, !ANIMATE,
                    DEFER_RESUME, "launchToSide");
        }

        final TaskRecord topTask = mTargetStack.topTask();
        if (topTask != sourceTask && !mAvoidMoveToFront) {
            mTargetStack.moveTaskToFrontLocked(sourceTask, mNoAnimation, mOptions,
                    mStartActivity.appTimeTracker, "sourceTaskToFront");
        } else if (mDoResume) {
            mTargetStack.moveToFront("sourceStackToFront");
        }

        if (!mAddingToTask && (mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0) {
            // In this case, we are adding the activity to an existing task, but the caller has
            // asked to clear that task if the activity is already running.
            ActivityRecord top = sourceTask.performClearTaskLocked(mStartActivity, mLaunchFlags);
            mKeepCurTransition = true;
            if (top != null) {
                ActivityStack.logStartActivity(AM_NEW_INTENT, mStartActivity, top.getTask());
                deliverNewIntent(top);
                // For paranoia, make sure we have correctly resumed the top activity.
                mTargetStack.mLastPausedActivity = null;
                if (mDoResume) {
                    mSupervisor.resumeFocusedStackTopActivityLocked();
                }
                ActivityOptions.abort(mOptions);
                return START_DELIVERED_TO_TOP;
            }
        } else if (!mAddingToTask && (mLaunchFlags & FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
            // In this case, we are launching an activity in our own task that may already be
            // running somewhere in the history, and we want to shuffle it to the front of the
            // stack if so.
            final ActivityRecord top = sourceTask.findActivityInHistoryLocked(mStartActivity);
            if (top != null) {
                final TaskRecord task = top.getTask();
                task.moveActivityToFrontLocked(top);
                top.updateOptionsLocked(mOptions);
                ActivityStack.logStartActivity(AM_NEW_INTENT, mStartActivity, task);
                deliverNewIntent(top);
                mTargetStack.mLastPausedActivity = null;
                if (mDoResume) {
                    mSupervisor.resumeFocusedStackTopActivityLocked();
                }
                return START_DELIVERED_TO_TOP;
            }
        }

        // An existing activity is starting this new activity, so we want to keep the new one in
        // the same task as the one that is starting it.
        addOrReparentStartingActivity(sourceTask, "setTaskFromSourceRecord");
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + mStartActivity
                + " in existing task " + mStartActivity.getTask() + " from source " + mSourceRecord);
        return START_SUCCESS;
    }

    private int setTaskFromInTask() {
        // The caller is asking that the new activity be started in an explicit
        // task it has provided to us.
        if (mService.getLockTaskController().isLockTaskModeViolation(mInTask)) {
            Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
            return START_RETURN_LOCK_TASK_MODE_VIOLATION;
        }

        mTargetStack = mInTask.getStack();

        // Check whether we should actually launch the new activity in to the task,
        // or just reuse the current activity on top.
        ActivityRecord top = mInTask.getTopActivity();
        if (top != null && top.realActivity.equals(mStartActivity.realActivity)
                && top.userId == mStartActivity.userId) {
            if ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
                    || isLaunchModeOneOf(LAUNCH_SINGLE_TOP, LAUNCH_SINGLE_TASK)) {
                mTargetStack.moveTaskToFrontLocked(mInTask, mNoAnimation, mOptions,
                        mStartActivity.appTimeTracker, "inTaskToFront");
                if ((mStartFlags & START_FLAG_ONLY_IF_NEEDED) != 0) {
                    // We don't need to start a new activity, and the client said not to do
                    // anything if that is the case, so this is it!
                    return START_RETURN_INTENT_TO_CALLER;
                }
                deliverNewIntent(top);
                return START_DELIVERED_TO_TOP;
            }
        }

        if (!mAddingToTask) {
            mTargetStack.moveTaskToFrontLocked(mInTask, mNoAnimation, mOptions,
                    mStartActivity.appTimeTracker, "inTaskToFront");
            // We don't actually want to have this activity added to the task, so just
            // stop here but still tell the caller that we consumed the intent.
            ActivityOptions.abort(mOptions);
            return START_TASK_TO_FRONT;
        }

        if (!mLaunchParams.mBounds.isEmpty()) {
            // TODO: Shouldn't we already know what stack to use by the time we get here?
            ActivityStack stack = mSupervisor.getLaunchStack(null, null, mInTask, ON_TOP);
            if (stack != mInTask.getStack()) {
                mInTask.reparent(stack, ON_TOP, REPARENT_KEEP_STACK_AT_FRONT, !ANIMATE,
                        DEFER_RESUME, "inTaskToFront");
                mTargetStack = mInTask.getStack();
            }

            updateBounds(mInTask, mLaunchParams.mBounds);
        }

        mTargetStack.moveTaskToFrontLocked(
                mInTask, mNoAnimation, mOptions, mStartActivity.appTimeTracker, "inTaskToFront");

        addOrReparentStartingActivity(mInTask, "setTaskFromInTask");
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + mStartActivity
                + " in explicit task " + mStartActivity.getTask());

        return START_SUCCESS;
    }

    @VisibleForTesting
    void updateBounds(TaskRecord task, Rect bounds) {
        if (bounds.isEmpty()) {
            return;
        }

        final ActivityStack stack = task.getStack();
        if (stack != null && stack.resizeStackWithLaunchBounds()) {
            mService.resizeStack(stack.mStackId, bounds, true, !PRESERVE_WINDOWS, ANIMATE, -1);
        } else {
            task.updateOverrideConfiguration(bounds);
        }
    }

    private void setTaskToCurrentTopOrCreateNewTask() {
        mTargetStack = computeStackFocus(mStartActivity, false, mLaunchFlags, mOptions);
        if (mDoResume) {
            mTargetStack.moveToFront("addingToTopTask");
        }
        final ActivityRecord prev = mTargetStack.getTopActivity();
        final TaskRecord task = (prev != null) ? prev.getTask() : mTargetStack.createTaskRecord(
                mSupervisor.getNextTaskIdForUserLocked(mStartActivity.userId), mStartActivity.info,
                mIntent, null, null, true, mStartActivity, mSourceRecord, mOptions);
        addOrReparentStartingActivity(task, "setTaskToCurrentTopOrCreateNewTask");
        mTargetStack.positionChildWindowContainerAtTop(task);
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + mStartActivity
                + " in new guessed " + mStartActivity.getTask());
    }

    private void addOrReparentStartingActivity(TaskRecord parent, String reason) {
        if (mStartActivity.getTask() == null || mStartActivity.getTask() == parent) {
            parent.addActivityToTop(mStartActivity);
        } else {
            mStartActivity.reparent(parent, parent.mActivities.size() /* top */, reason);
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
                    launchFlags &= ~FLAG_ACTIVITY_MULTIPLE_TASK;
                    break;
            }
        }
        return launchFlags;
    }

    private ActivityStack computeStackFocus(ActivityRecord r, boolean newTask, int launchFlags,
            ActivityOptions aOptions) {
        final TaskRecord task = r.getTask();
        ActivityStack stack = getLaunchStack(r, launchFlags, task, aOptions);
        if (stack != null) {
            return stack;
        }

        final ActivityStack currentStack = task != null ? task.getStack() : null;
        if (currentStack != null) {
            if (mSupervisor.mFocusedStack != currentStack) {
                if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                        "computeStackFocus: Setting " + "focused stack to r=" + r
                                + " task=" + task);
            } else {
                if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                        "computeStackFocus: Focused stack already="
                                + mSupervisor.mFocusedStack);
            }
            return currentStack;
        }

        if (canLaunchIntoFocusedStack(r, newTask)) {
            if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                    "computeStackFocus: Have a focused stack=" + mSupervisor.mFocusedStack);
            return mSupervisor.mFocusedStack;
        }

        if (mPreferredDisplayId != DEFAULT_DISPLAY) {
            // Try to put the activity in a stack on a secondary display.
            stack = mSupervisor.getValidLaunchStackOnDisplay(mPreferredDisplayId, r);
            if (stack == null) {
                // If source display is not suitable - look for topmost valid stack in the system.
                if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                        "computeStackFocus: Can't launch on mPreferredDisplayId="
                                + mPreferredDisplayId + ", looking on all displays.");
                stack = mSupervisor.getNextValidLaunchStackLocked(r, mPreferredDisplayId);
            }
        }
        if (stack == null) {
            // We first try to put the task in the first dynamic stack on home display.
            final ActivityDisplay display = mSupervisor.getDefaultDisplay();
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                stack = display.getChildAt(stackNdx);
                if (!stack.isOnHomeDisplay()) {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                            "computeStackFocus: Setting focused stack=" + stack);
                    return stack;
                }
            }
            // If there is no suitable dynamic stack then we figure out which static stack to use.
            stack = mSupervisor.getLaunchStack(r, aOptions, task, ON_TOP);
        }
        if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS, "computeStackFocus: New stack r="
                + r + " stackId=" + stack.mStackId);
        return stack;
    }

    /** Check if provided activity record can launch in currently focused stack. */
    // TODO: This method can probably be consolidated into getLaunchStack() below.
    private boolean canLaunchIntoFocusedStack(ActivityRecord r, boolean newTask) {
        final ActivityStack focusedStack = mSupervisor.mFocusedStack;
        final boolean canUseFocusedStack;
        if (focusedStack.isActivityTypeAssistant()) {
            canUseFocusedStack = r.isActivityTypeAssistant();
        } else {
            switch (focusedStack.getWindowingMode()) {
                case WINDOWING_MODE_FULLSCREEN:
                    // The fullscreen stack can contain any task regardless of if the task is
                    // resizeable or not. So, we let the task go in the fullscreen task if it is the
                    // focus stack.
                    canUseFocusedStack = true;
                    break;
                case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
                case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                    // Any activity which supports split screen can go in the docked stack.
                    canUseFocusedStack = r.supportsSplitScreenWindowingMode();
                    break;
                case WINDOWING_MODE_FREEFORM:
                    // Any activity which supports freeform can go in the freeform stack.
                    canUseFocusedStack = r.supportsFreeform();
                    break;
                default:
                    // Dynamic stacks behave similarly to the fullscreen stack and can contain any
                    // resizeable task.
                    canUseFocusedStack = !focusedStack.isOnHomeDisplay()
                            && r.canBeLaunchedOnDisplay(focusedStack.mDisplayId);
            }
        }
        return canUseFocusedStack && !newTask
                // Using the focus stack isn't important enough to override the preferred display.
                && (mPreferredDisplayId == focusedStack.mDisplayId);
    }

    private ActivityStack getLaunchStack(ActivityRecord r, int launchFlags, TaskRecord task,
            ActivityOptions aOptions) {
        // We are reusing a task, keep the stack!
        if (mReuseTask != null) {
            return mReuseTask.getStack();
        }

        if (((launchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) == 0)
                 || mPreferredDisplayId != DEFAULT_DISPLAY) {
            // We don't pass in the default display id into the get launch stack call so it can do a
            // full resolution.
            final int candidateDisplay =
                    mPreferredDisplayId != DEFAULT_DISPLAY ? mPreferredDisplayId : INVALID_DISPLAY;
            return mSupervisor.getLaunchStack(r, aOptions, task, ON_TOP, candidateDisplay);
        }
        // Otherwise handle adjacent launch.

        // The parent activity doesn't want to launch the activity on top of itself, but
        // instead tries to put it onto other side in side-by-side mode.
        final ActivityStack parentStack = task != null ? task.getStack(): mSupervisor.mFocusedStack;

        if (parentStack != mSupervisor.mFocusedStack) {
            // If task's parent stack is not focused - use it during adjacent launch.
            return parentStack;
        } else {
            if (mSupervisor.mFocusedStack != null && task == mSupervisor.mFocusedStack.topTask()) {
                // If task is already on top of focused stack - use it. We don't want to move the
                // existing focused task to adjacent stack, just deliver new intent in this case.
                return mSupervisor.mFocusedStack;
            }

            if (parentStack != null && parentStack.inSplitScreenPrimaryWindowingMode()) {
                // If parent was in docked stack, the natural place to launch another activity
                // will be fullscreen, so it can appear alongside the docked window.
                final int activityType = mSupervisor.resolveActivityType(r, mOptions, task);
                return parentStack.getDisplay().getOrCreateStack(
                        WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, activityType, ON_TOP);
            } else {
                // If the parent is not in the docked stack, we check if there is docked window
                // and if yes, we will launch into that stack. If not, we just put the new
                // activity into parent's stack, because we can't find a better place.
                final ActivityStack dockedStack =
                        mSupervisor.getDefaultDisplay().getSplitScreenPrimaryStack();
                if (dockedStack != null && !dockedStack.shouldBeVisible(r)) {
                    // There is a docked stack, but it isn't visible, so we can't launch into that.
                    return mSupervisor.getLaunchStack(r, aOptions, task, ON_TOP);
                } else {
                    return dockedStack;
                }
            }
        }
    }

    private boolean isLaunchModeOneOf(int mode1, int mode2) {
        return mode1 == mLaunchMode || mode2 == mLaunchMode;
    }

    static boolean isDocumentLaunchesIntoExisting(int flags) {
        return (flags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 &&
                (flags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0;
    }

    ActivityStarter setIntent(Intent intent) {
        mRequest.intent = intent;
        return this;
    }

    @VisibleForTesting
    Intent getIntent() {
        return mRequest.intent;
    }

    ActivityStarter setReason(String reason) {
        mRequest.reason = reason;
        return this;
    }

    ActivityStarter setCaller(IApplicationThread caller) {
        mRequest.caller = caller;
        return this;
    }

    ActivityStarter setEphemeralIntent(Intent intent) {
        mRequest.ephemeralIntent = intent;
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

    ActivityStarter setCallingPid(int pid) {
        mRequest.callingPid = pid;
        return this;
    }

    ActivityStarter setCallingUid(int uid) {
        mRequest.callingUid = uid;
        return this;
    }

    ActivityStarter setCallingPackage(String callingPackage) {
        mRequest.callingPackage = callingPackage;
        return this;
    }

    ActivityStarter setRealCallingPid(int pid) {
        mRequest.realCallingPid = pid;
        return this;
    }

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

    ActivityStarter setInTask(TaskRecord inTask) {
        mRequest.inTask = inTask;
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

    ActivityStarter setMayWait(int userId) {
        mRequest.mayWait = true;
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

    void dump(PrintWriter pw, String prefix) {
        prefix = prefix + "  ";
        pw.print(prefix);
        pw.print("mCurrentUser=");
        pw.println(mSupervisor.mCurrentUser);
        pw.print(prefix);
        pw.print("mLastStartReason=");
        pw.println(mLastStartReason);
        pw.print(prefix);
        pw.print("mLastStartActivityTimeMs=");
        pw.println(DateFormat.getDateTimeInstance().format(new Date(mLastStartActivityTimeMs)));
        pw.print(prefix);
        pw.print("mLastStartActivityResult=");
        pw.println(mLastStartActivityResult);
        ActivityRecord r = mLastStartActivityRecord[0];
        if (r != null) {
            pw.print(prefix);
            pw.println("mLastStartActivityRecord:");
            r.dump(pw, prefix + "  ");
        }
        if (mStartActivity != null) {
            pw.print(prefix);
            pw.println("mStartActivity:");
            mStartActivity.dump(pw, prefix + "  ");
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
    }
}
