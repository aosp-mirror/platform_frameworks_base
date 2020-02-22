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
import static android.app.ActivityManager.StackId;
import static android.app.ActivityManager.StackId.ASSISTANT_STACK_ID;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.HOME_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static android.app.ActivityManager.StackId.RECENTS_STACK_ID;
import static android.app.ActivityManager.StackId.isDynamicStack;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
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
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK;
import static android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_CONFIGURATION;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FOCUS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_PERMISSIONS_REVIEW;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_RECENTS;
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
import static com.android.server.am.ActivityRecord.APPLICATION_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.ASSISTANT_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.HOME_ACTIVITY_TYPE;
import static com.android.server.am.ActivityStack.ActivityState.RESUMED;
import static com.android.server.am.ActivityStack.STACK_INVISIBLE;
import static com.android.server.am.ActivityStackSupervisor.CREATE_IF_NEEDED;
import static com.android.server.am.ActivityStackSupervisor.DEFER_RESUME;
import static com.android.server.am.ActivityStackSupervisor.ON_TOP;
import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;
import static com.android.server.am.ActivityStackSupervisor.TAG_TASKS;
import static com.android.server.am.EventLogTags.AM_NEW_INTENT;
import static com.android.server.am.TaskRecord.REPARENT_KEEP_STACK_AT_FRONT;
import static com.android.server.am.TaskRecord.REPARENT_MOVE_STACK_TO_FRONT;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
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
import android.hardware.power.V1_0.PowerHint;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.voice.IVoiceInteractionSession;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.ActivityStackSupervisor.PendingActivityLaunch;
import com.android.server.pm.InstantAppResolver;
import com.android.server.wm.WindowManagerService;

import java.io.PrintWriter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Controller for interpreting how and then launching activities.
 *
 * This class collects all the logic for determining how an intent and flags should be turned into
 * an activity and associated task and stack.
 */
class ActivityStarter {
    public static final int PID_NULL = 0;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStarter" : TAG_AM;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private ActivityStartInterceptor mInterceptor;
    private WindowManagerService mWindowManager;

    final ArrayList<PendingActivityLaunch> mPendingActivityLaunches = new ArrayList<>();

    // Share state variable among methods when starting an activity.
    private ActivityRecord mStartActivity;
    private Intent mIntent;
    private int mCallingUid;
    private ActivityOptions mOptions;

    private boolean mLaunchSingleTop;
    private boolean mLaunchSingleInstance;
    private boolean mLaunchSingleTask;
    private boolean mLaunchTaskBehind;
    private int mLaunchFlags;

    private Rect mLaunchBounds;

    private ActivityRecord mNotTop;
    private boolean mDoResume;
    private int mStartFlags;
    private ActivityRecord mSourceRecord;
    private int mSourceDisplayId;

    private TaskRecord mInTask;
    private boolean mAddingToTask;
    private TaskRecord mReuseTask;

    private ActivityInfo mNewTaskInfo;
    private Intent mNewTaskIntent;
    private ActivityStack mSourceStack;
    private ActivityStack mTargetStack;
    // Indicates that we moved other task and are going to put something on top soon, so
    // we don't want to show it redundantly or accidentally change what's shown below.
    private boolean mMovedOtherTask;
    private boolean mMovedToFront;
    private boolean mNoAnimation;
    private boolean mKeepCurTransition;
    private boolean mAvoidMoveToFront;
    private boolean mPowerHintSent;

    // We must track when we deliver the new intent since multiple code paths invoke
    // {@link #deliverNewIntent}. This is due to early returns in the code path. This flag is used
    // inside {@link #deliverNewIntent} to suppress duplicate requests and ensure the intent is
    // delivered at most once.
    private boolean mIntentDelivered;

    private IVoiceInteractionSession mVoiceSession;
    private IVoiceInteractor mVoiceInteractor;

    private boolean mUsingVr2dDisplay;

    // Last home activity record we attempted to start
    private final ActivityRecord[] mLastHomeActivityStartRecord = new ActivityRecord[1];
    // The result of the last home activity we attempted to start.
    private int mLastHomeActivityStartResult;
    // Last activity record we attempted to start
    private final ActivityRecord[] mLastStartActivityRecord = new ActivityRecord[1];
    // The result of the last activity we attempted to start.
    private int mLastStartActivityResult;
    // Time in milli seconds we attempted to start the last activity.
    private long mLastStartActivityTimeMs;
    // The reason we were trying to start the last activity
    private String mLastStartReason;

    private void reset() {
        mStartActivity = null;
        mIntent = null;
        mCallingUid = -1;
        mOptions = null;

        mLaunchSingleTop = false;
        mLaunchSingleInstance = false;
        mLaunchSingleTask = false;
        mLaunchTaskBehind = false;
        mLaunchFlags = 0;

        mLaunchBounds = null;

        mNotTop = null;
        mDoResume = false;
        mStartFlags = 0;
        mSourceRecord = null;
        mSourceDisplayId = INVALID_DISPLAY;

        mInTask = null;
        mAddingToTask = false;
        mReuseTask = null;

        mNewTaskInfo = null;
        mNewTaskIntent = null;
        mSourceStack = null;

        mTargetStack = null;
        mMovedOtherTask = false;
        mMovedToFront = false;
        mNoAnimation = false;
        mKeepCurTransition = false;
        mAvoidMoveToFront = false;

        mVoiceSession = null;
        mVoiceInteractor = null;

        mUsingVr2dDisplay = false;

        mIntentDelivered = false;
    }

    ActivityStarter(ActivityManagerService service, ActivityStackSupervisor supervisor) {
        mService = service;
        mSupervisor = supervisor;
        mInterceptor = new ActivityStartInterceptor(mService, mSupervisor);
        mUsingVr2dDisplay = false;
    }

    int startActivityLocked(IApplicationThread caller, Intent intent, Intent ephemeralIntent,
            String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid,
            String callingPackage, int realCallingPid, int realCallingUid, int startFlags,
            ActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified,
            ActivityRecord[] outActivity, TaskRecord inTask, String reason) {

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
                inTask);

        if (outActivity != null) {
            // mLastStartActivityRecord[0] is set in the call to startActivity above.
            outActivity[0] = mLastStartActivityRecord[0];
        }

        // Aborted results are treated as successes externally, but we must track them internally.
        return mLastStartActivityResult != START_ABORTED ? mLastStartActivityResult : START_SUCCESS;
    }

    /** DO NOT call this method directly. Use {@link #startActivityLocked} instead. */
    private int startActivity(IApplicationThread caller, Intent intent, Intent ephemeralIntent,
            String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid,
            String callingPackage, int realCallingPid, int realCallingUid, int startFlags,
            ActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified,
            ActivityRecord[] outActivity, TaskRecord inTask) {
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

        final int userId = aInfo != null ? UserHandle.getUserId(aInfo.applicationInfo.uid) : 0;

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
                ActivityOptions.abort(options);
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
                    if (!AppGlobals.getPackageManager().activitySupportsIntent(
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
                if (!AppGlobals.getPackageManager().activitySupportsIntent(intent.getComponent(),
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
            ActivityOptions.abort(options);
            return err;
        }

        boolean abort = !mSupervisor.checkStartAnyActivityPermission(intent, aInfo, resultWho,
                requestCode, callingPid, callingUid, callingPackage, ignoreTargetSecurity, callerApp,
                resultRecord, resultStack, options);
        abort |= !mService.mIntentFirewall.checkStartActivity(intent, callingUid,
                callingPid, resolvedType, aInfo.applicationInfo);

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
        mInterceptor.intercept(intent, rInfo, aInfo, resolvedType, inTask, callingPid, callingUid,
                options);
        intent = mInterceptor.mIntent;
        rInfo = mInterceptor.mRInfo;
        aInfo = mInterceptor.mAInfo;
        resolvedType = mInterceptor.mResolvedType;
        inTask = mInterceptor.mInTask;
        callingPid = mInterceptor.mCallingPid;
        callingUid = mInterceptor.mCallingUid;
        options = mInterceptor.mActivityOptions;
        if (abort) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode,
                        RESULT_CANCELED, null);
            }
            // We pretend to the caller that it was really started, but
            // they will just get a cancel result.
            ActivityOptions.abort(options);
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

                rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId);
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
                mSupervisor, options, sourceRecord);
        if (outActivity != null) {
            outActivity[0] = r;
        }

        if (r.appTimeTracker == null && sourceRecord != null) {
            // If the caller didn't specify an explicit time tracker, we want to continue
            // tracking under any it has.
            r.appTimeTracker = sourceRecord.appTimeTracker;
        }

        final ActivityStack stack = mSupervisor.mFocusedStack;
        if (voiceSession == null && (stack.mResumedActivity == null
                || stack.mResumedActivity.info.applicationInfo.uid != callingUid)) {
            if (!mService.checkAppSwitchAllowedLocked(callingPid, callingUid,
                    realCallingPid, realCallingUid, "Activity start")) {
                PendingActivityLaunch pal =  new PendingActivityLaunch(r,
                        sourceRecord, startFlags, stack, callerApp);
                mPendingActivityLaunches.add(pal);
                ActivityOptions.abort(options);
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

        doPendingActivityLaunchesLocked(false);

        return startActivity(r, sourceRecord, voiceSession, voiceInteractor, startFlags, true,
                options, inTask, outActivity);
    }

    /**
     * Creates a launch intent for the given auxiliary resolution data.
     */
    private @NonNull Intent createLaunchIntent(@NonNull AuxiliaryResolveInfo auxiliaryResponse,
            Intent originalIntent, String callingPackage, Bundle verificationBundle,
            String resolvedType, int userId) {
        if (auxiliaryResponse.needsPhaseTwo) {
            // request phase two resolution
            mService.getPackageManagerInternalLocked().requestInstantAppResolutionPhaseTwo(
                    auxiliaryResponse, originalIntent, resolvedType, callingPackage,
                    verificationBundle, userId);
        }
        return InstantAppResolver.buildEphemeralInstallerIntent(
                Intent.ACTION_INSTALL_INSTANT_APP_PACKAGE, originalIntent,
                auxiliaryResponse.failureIntent, callingPackage, verificationBundle,
                resolvedType, userId, auxiliaryResponse.packageName, auxiliaryResponse.splitName,
                auxiliaryResponse.installFailureActivity, auxiliaryResponse.versionCode,
                auxiliaryResponse.token, auxiliaryResponse.needsPhaseTwo);
    }

    void postStartActivityProcessing(
            ActivityRecord r, int result, int prevFocusedStackId, ActivityRecord sourceRecord,
            ActivityStack targetStack) {

        if (ActivityManager.isStartResultFatalError(result)) {
            return;
        }

        // We're waiting for an activity launch to finish, but that activity simply
        // brought another activity to front. Let startActivityMayWait() know about
        // this, so it waits for the new activity to become visible instead.
        if (result == START_TASK_TO_FRONT && !mSupervisor.mWaitingActivityLaunched.isEmpty()) {
            mSupervisor.reportTaskToFrontNoLaunch(mStartActivity);
        }

        int startedActivityStackId = INVALID_STACK_ID;
        final ActivityStack currentStack = r.getStack();
        if (currentStack != null) {
            startedActivityStackId = currentStack.mStackId;
        } else if (mTargetStack != null) {
            startedActivityStackId = targetStack.mStackId;
        }

        if (startedActivityStackId == DOCKED_STACK_ID) {
            final ActivityStack homeStack = mSupervisor.getStack(HOME_STACK_ID);
            final boolean homeStackVisible = homeStack != null && homeStack.isVisible();
            if (homeStackVisible) {
                // We launch an activity while being in home stack, which means either launcher or
                // recents into docked stack. We don't want the launched activity to be alone in a
                // docked stack, so we want to immediately launch recents too.
                if (DEBUG_RECENTS) Slog.d(TAG, "Scheduling recents launch.");
                mWindowManager.showRecentApps(true /* fromHome */);
            }
            return;
        }

        boolean clearedTask = (mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK) && (mReuseTask != null);
        if (startedActivityStackId == PINNED_STACK_ID && (result == START_TASK_TO_FRONT
                || result == START_DELIVERED_TO_TOP || clearedTask)) {
            // The activity was already running in the pinned stack so it wasn't started, but either
            // brought to the front or the new intent was delivered to it since it was already in
            // front. Notify anyone interested in this piece of information.
            mService.mTaskChangeNotificationController.notifyPinnedActivityRestartAttempt(
                    clearedTask);
            return;
        }
    }

    void startHomeActivityLocked(Intent intent, ActivityInfo aInfo, String reason) {
        mSupervisor.moveHomeStackTaskToTop(reason);
        mLastHomeActivityStartResult = startActivityLocked(null /*caller*/, intent,
                null /*ephemeralIntent*/, null /*resolvedType*/, aInfo, null /*rInfo*/,
                null /*voiceSession*/, null /*voiceInteractor*/, null /*resultTo*/,
                null /*resultWho*/, 0 /*requestCode*/, 0 /*callingPid*/, 0 /*callingUid*/,
                null /*callingPackage*/, 0 /*realCallingPid*/, 0 /*realCallingUid*/,
                0 /*startFlags*/, null /*options*/, false /*ignoreTargetSecurity*/,
                false /*componentSpecified*/, mLastHomeActivityStartRecord /*outActivity*/,
                null /*inTask*/, "startHomeActivity: " + reason);
        if (mSupervisor.inResumeTopActivity) {
            // If we are in resume section already, home activity will be initialized, but not
            // resumed (to avoid recursive resume) and will stay that way until something pokes it
            // again. We need to schedule another resume.
            mSupervisor.scheduleResumeTopActivities();
        }
    }

    void startConfirmCredentialIntent(Intent intent, Bundle optionsBundle) {
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK |
                FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
                FLAG_ACTIVITY_TASK_ON_HOME);
        ActivityOptions options = (optionsBundle != null ? new ActivityOptions(optionsBundle)
                        : ActivityOptions.makeBasic());
        options.setLaunchTaskId(mSupervisor.getHomeActivity().getTask().taskId);
        mService.mContext.startActivityAsUser(intent, options.toBundle(), UserHandle.CURRENT);
    }

    final int startActivityMayWait(IApplicationThread caller, int callingUid,
            String callingPackage, Intent intent, String resolvedType,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, WaitResult outResult,
            Configuration globalConfig, Bundle bOptions, boolean ignoreTargetSecurity, int userId,
            TaskRecord inTask, String reason) {
        return startActivityMayWait(caller, callingUid, PID_NULL, UserHandle.USER_NULL,
             callingPackage, intent, resolvedType, voiceSession, voiceInteractor, resultTo,
             resultWho, requestCode, startFlags, profilerInfo, outResult, globalConfig, bOptions,
             ignoreTargetSecurity, userId, inTask, reason);
    }

    final int startActivityMayWait(IApplicationThread caller, int callingUid,
            int requestRealCallingPid, int requestRealCallingUid,
            String callingPackage, Intent intent, String resolvedType,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, WaitResult outResult,
            Configuration globalConfig, Bundle bOptions, boolean ignoreTargetSecurity, int userId,
            TaskRecord inTask, String reason) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        mSupervisor.mActivityMetricsLogger.notifyActivityLaunching();
        boolean componentSpecified = intent.getComponent() != null;

        // Save a copy in case ephemeral needs it
        final Intent ephemeralIntent = new Intent(intent);
        // Don't modify the client's object!
        intent = new Intent(intent);
        if (componentSpecified
                && intent.getData() != null
                && Intent.ACTION_VIEW.equals(intent.getAction())
                && mService.getPackageManagerInternalLocked()
                        .isInstantAppInstallerComponent(intent.getComponent())) {
            // intercept intents targeted directly to the ephemeral installer the
            // ephemeral installer should never be started with a raw URL; instead
            // adjust the intent so it looks like a "normal" instant app launch
            intent.setComponent(null /*component*/);
            componentSpecified = false;
        }

        ResolveInfo rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId);
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
                                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
                }
            }
        }
        // Collect information about the target of the Intent.
        ActivityInfo aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, profilerInfo);

        ActivityOptions options = ActivityOptions.fromBundle(bOptions);
        synchronized (mService) {

            final int realCallingPid = requestRealCallingPid != PID_NULL
                ? requestRealCallingPid
                : Binder.getCallingPid();
            final int realCallingUid = requestRealCallingUid != UserHandle.USER_NULL
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

            final ActivityStack stack = mSupervisor.mFocusedStack;
            stack.mConfigWillChange = globalConfig != null
                    && mService.getGlobalConfiguration().diff(globalConfig) != 0;
            if (DEBUG_CONFIGURATION) Slog.v(TAG_CONFIGURATION,
                    "Starting activity when config will change = " + stack.mConfigWillChange);

            final long origId = Binder.clearCallingIdentity();

            if (aInfo != null &&
                    (aInfo.applicationInfo.privateFlags
                            & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
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
                                ActivityOptions.abort(options);
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
                        rInfo = mSupervisor.resolveIntent(intent, null /*resolvedType*/, userId);
                        aInfo = rInfo != null ? rInfo.activityInfo : null;
                        if (aInfo != null) {
                            aInfo = mService.getActivityInfoForUser(aInfo, userId);
                        }
                    }
                }
            }

            final ActivityRecord[] outRecord = new ActivityRecord[1];
            int res = startActivityLocked(caller, intent, ephemeralIntent, resolvedType,
                    aInfo, rInfo, voiceSession, voiceInteractor,
                    resultTo, resultWho, requestCode, callingPid,
                    callingUid, callingPackage, realCallingPid, realCallingUid, startFlags,
                    options, ignoreTargetSecurity, componentSpecified, outRecord, inTask,
                    reason);

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

            if (outResult != null) {
                outResult.result = res;
                if (res == ActivityManager.START_SUCCESS) {
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
                }
                if (res == START_TASK_TO_FRONT) {
                    final ActivityRecord r = outRecord[0];

                    // ActivityRecord may represent a different activity, but it should not be in
                    // the resumed state.
                    if (r.nowVisible && r.state == RESUMED) {
                        outResult.timeout = false;
                        outResult.who = r.realActivity;
                        outResult.totalTime = 0;
                        outResult.thisTime = 0;
                    } else {
                        outResult.thisTime = SystemClock.uptimeMillis();
                        mSupervisor.waitActivityVisible(r.realActivity, outResult);
                        // Note: the timeout variable is not currently not ever set.
                        do {
                            try {
                                mService.wait();
                            } catch (InterruptedException e) {
                            }
                        } while (!outResult.timeout && outResult.who == null);
                    }
                }
            }

            mSupervisor.mActivityMetricsLogger.notifyActivityLaunched(res, outRecord[0]);
            return res;
        }
    }

    final int startActivities(IApplicationThread caller, int callingUid, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo,
            Bundle bOptions, int userId, String reason) {
        return startActivities(caller, callingUid, PID_NULL, UserHandle.USER_NULL, callingPackage,
             intents, resolvedTypes, resultTo, bOptions, userId, reason);
    }

    final int startActivities(IApplicationThread caller, int callingUid,
            int incomingRealCallingPid, int incomingRealCallingUid, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo,
            Bundle bOptions, int userId, String reason) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
        }

        final int realCallingPid = incomingRealCallingPid != PID_NULL
                     ? incomingRealCallingPid
                     : Binder.getCallingPid();

        final int realCallingUid = incomingRealCallingUid != UserHandle.USER_NULL
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
        boolean forceNewTask = false;
        final int filterCallingUid = callingUid >= 0 ? callingUid : realCallingUid;
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService) {
                ActivityRecord[] outActivity = new ActivityRecord[1];
                for (int i=0; i<intents.length; i++) {
                    Intent intent = intents[i];
                    if (intent == null) {
                        continue;
                    }

                    // Refuse possible leaked file descriptors
                    if (intent != null && intent.hasFileDescriptors()) {
                        throw new IllegalArgumentException("File descriptors passed in Intent");
                    }

                    boolean componentSpecified = intent.getComponent() != null;

                    // Don't modify the client's object!
                    intent = new Intent(intent);
                    if (forceNewTask) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    }

                    // Collect information about the target of the Intent.
                    ActivityInfo aInfo = mSupervisor.resolveActivity(intent, resolvedTypes[i], 0,
                            null, userId);
                    // TODO: New, check if this is correct
                    aInfo = mService.getActivityInfoForUser(aInfo, userId);

                    if (aInfo != null &&
                            (aInfo.applicationInfo.privateFlags
                                    & ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE)  != 0) {
                        throw new IllegalArgumentException(
                                "FLAG_CANT_SAVE_STATE not supported here");
                    }

                    ActivityOptions options = ActivityOptions.fromBundle(
                            i == intents.length - 1 ? bOptions : null);
                    int res = startActivityLocked(caller, intent, null /*ephemeralIntent*/,
                            resolvedTypes[i], aInfo, null /*rInfo*/, null, null, resultTo, null, -1,
                            callingPid, callingUid, callingPackage,
                            realCallingPid, realCallingUid, 0,
                            options, false, componentSpecified, outActivity, null, reason);
                    if (res < 0) {
                        return res;
                    }

                    final ActivityRecord started = outActivity[0];
                    if (started != null && started.getUid() == filterCallingUid) {
                        // Only the started activity which has the same uid as the source caller can
                        // be the caller of next activity.
                        resultTo = started.appToken;
                        forceNewTask = false;
                    } else {
                        // Different apps not adjacent to the caller are forced to be new task.
                        resultTo = null;
                        forceNewTask = true;
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return START_SUCCESS;
    }

    void sendPowerHintForLaunchStartIfNeeded(boolean forceSend, ActivityRecord targetActivity) {
        boolean sendHint = forceSend;

        if (!sendHint) {
            // If not forced, send power hint when the activity's process is different than the
            // current resumed activity.
            final ActivityRecord resumedActivity = mSupervisor.getResumedActivityLocked();
            sendHint = resumedActivity == null
                || resumedActivity.app == null
                || !resumedActivity.app.equals(targetActivity.app);
        }

        if (sendHint && mService.mLocalPowerManager != null) {
            mService.mLocalPowerManager.powerHint(PowerHint.LAUNCH, 1);
            mPowerHintSent = true;
        }
    }

    void sendPowerHintForLaunchEndIfNeeded() {
        // Trigger launch power hint if activity is launched
        if (mPowerHintSent && mService.mLocalPowerManager != null) {
            mService.mLocalPowerManager.powerHint(PowerHint.LAUNCH, 0);
            mPowerHintSent = false;
        }
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
            if (!ActivityManager.isStartResultSuccessful(result)
                    && mStartActivity.getTask() != null) {
                mStartActivity.getTask().removeActivity(mStartActivity);
            }
            mService.mWindowManager.continueSurfaceLayout();
        }

        postStartActivityProcessing(r, result, mSupervisor.getLastStack().mStackId,  mSourceRecord,
                mTargetStack);

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

        final int preferredLaunchStackId =
                (mOptions != null) ? mOptions.getLaunchStackId() : INVALID_STACK_ID;
        final int preferredLaunchDisplayId =
                (mOptions != null) ? mOptions.getLaunchDisplayId() : DEFAULT_DISPLAY;

        if (reusedActivity != null) {
            // When the flags NEW_TASK and CLEAR_TASK are set, then the task gets reused but
            // still needs to be a lock task mode violation since the task gets cleared out and
            // the device would otherwise leave the locked task.
            if (mSupervisor.isLockTaskModeViolation(reusedActivity.getTask(),
                    (mLaunchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                            == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))) {
                mSupervisor.showLockTaskToast();
                Slog.e(TAG, "startActivityUnchecked: Attempt to violate Lock Task Mode");
                return START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }

            if (mStartActivity.getTask() == null) {
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
                    || mLaunchSingleInstance || mLaunchSingleTask) {
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

            sendPowerHintForLaunchStartIfNeeded(false /* forceSend */, reusedActivity);

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
            setTaskFromIntentActivity(reusedActivity);

            if (!mAddingToTask && mReuseTask == null) {
                // We didn't do anything...  but it was needed (a.k.a., client don't use that
                // intent!)  And for paranoia, make sure we have correctly resumed the top activity.
                resumeTargetStackIfNeeded();
                if (outActivity != null && outActivity.length > 0) {
                    outActivity[0] = reusedActivity;
                }

                return START_TASK_TO_FRONT;
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
        final ActivityRecord topFocused = topStack.topActivity();
        final ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(mNotTop);
        final boolean dontStart = top != null && mStartActivity.resultTo == null
                && top.realActivity.equals(mStartActivity.realActivity)
                && top.userId == mStartActivity.userId
                && top.app != null && top.app.thread != null
                && ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
                || mLaunchSingleTop || mLaunchSingleTask);
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
            mSupervisor.handleNonResizableTaskIfNeeded(top.getTask(), preferredLaunchStackId,
                    preferredLaunchDisplayId, topStack.mStackId);

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
            result = setTaskFromReuseOrCreateNewTask(
                    taskToAffiliate, preferredLaunchStackId, topStack);
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
        if (mSourceRecord != null) {
            mStartActivity.getTask().setTaskToReturnTo(mSourceRecord);
        }
        if (newTask) {
            EventLog.writeEvent(
                    EventLogTags.AM_CREATE_TASK, mStartActivity.userId,
                    mStartActivity.getTask().taskId);
        }
        ActivityStack.logStartActivity(
                EventLogTags.AM_CREATE_ACTIVITY, mStartActivity, mStartActivity.getTask());
        mTargetStack.mLastPausedActivity = null;

        sendPowerHintForLaunchStartIfNeeded(false /* forceSend */, mStartActivity);

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
                mWindowManager.executeAppTransition();
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
        } else {
            mTargetStack.addRecentActivityLocked(mStartActivity);
        }
        mSupervisor.updateUserStackLocked(mStartActivity.userId, mTargetStack);

        mSupervisor.handleNonResizableTaskIfNeeded(mStartActivity.getTask(), preferredLaunchStackId,
                preferredLaunchDisplayId, mTargetStack.mStackId);

        return START_SUCCESS;
    }

    private void setInitialState(ActivityRecord r, ActivityOptions options, TaskRecord inTask,
            boolean doResume, int startFlags, ActivityRecord sourceRecord,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor) {
        reset();

        mStartActivity = r;
        mIntent = r.intent;
        mOptions = options;
        mCallingUid = r.launchedFromUid;
        mSourceRecord = sourceRecord;
        mVoiceSession = voiceSession;
        mVoiceInteractor = voiceInteractor;

        mSourceDisplayId = getSourceDisplayId(mSourceRecord, mStartActivity);

        mLaunchBounds = getOverrideBounds(r, options, inTask);

        mLaunchSingleTop = r.launchMode == LAUNCH_SINGLE_TOP;
        mLaunchSingleInstance = r.launchMode == LAUNCH_SINGLE_INSTANCE;
        mLaunchSingleTask = r.launchMode == LAUNCH_SINGLE_TASK;
        mLaunchFlags = adjustLaunchFlagsToDocumentMode(
                r, mLaunchSingleInstance, mLaunchSingleTask, mIntent.getFlags());
        mLaunchTaskBehind = r.mLaunchTaskBehind
                && !mLaunchSingleTask && !mLaunchSingleInstance
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

        if (mOptions != null && mOptions.getLaunchTaskId() != -1
                && mOptions.getTaskOverlay()) {
            r.mTaskOverlay = true;
            if (!mOptions.canTaskOverlayResume()) {
                final TaskRecord task = mSupervisor.anyTaskForIdLocked(mOptions.getLaunchTaskId());
                final ActivityRecord top = task != null ? task.getTopActivity() : null;
                if (top != null && top.state != RESUMED) {

                    // The caller specifies that we'd like to be avoided to be moved to the front,
                    // so be it!
                    mDoResume = false;
                    mAvoidMoveToFront = true;
                }
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
            if (mLaunchSingleInstance || mLaunchSingleTask) {
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
                    && mSourceRecord.isFreeform())  {
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
            } else if (mLaunchSingleInstance || mLaunchSingleTask) {
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
                || mLaunchSingleInstance || mLaunchSingleTask;
        // If bring to front is requested, and no result is requested and we have not been given
        // an explicit task to launch in to, and we can find a task that was started with this
        // same component, then instead of launching bring that one to the front.
        putIntoExistingTask &= mInTask == null && mStartActivity.resultTo == null;
        ActivityRecord intentActivity = null;
        if (mOptions != null && mOptions.getLaunchTaskId() != -1) {
            final TaskRecord task = mSupervisor.anyTaskForIdLocked(mOptions.getLaunchTaskId());
            intentActivity = task != null ? task.getTopActivity() : null;
        } else if (putIntoExistingTask) {
            if (mLaunchSingleInstance) {
                // There can be one and only one instance of single instance activity in the
                // history, and it is always in its own unique task, so we do a special search.
               intentActivity = mSupervisor.findActivityLocked(mIntent, mStartActivity.info,
                       mStartActivity.isHomeActivity());
            } else if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
                // For the launch adjacent case we only want to put the activity in an existing
                // task if the activity already exists in the history.
                intentActivity = mSupervisor.findActivityLocked(mIntent, mStartActivity.info,
                        !mLaunchSingleTask);
            } else {
                // Otherwise find the best task to put the activity in.
                intentActivity = mSupervisor.findTaskLocked(mStartActivity, mSourceDisplayId);
            }
        }
        return intentActivity;
    }

    /**
     * Returns the ID of the display to use for a new activity. If the device is in VR mode,
     * then return the Vr mode's virtual display ID. If not, if the source activity has
     * a explicit display ID set, use that to launch the activity.
     */
    private int getSourceDisplayId(ActivityRecord sourceRecord, ActivityRecord startingActivity) {
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
            mUsingVr2dDisplay = true;
            return displayId;
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
            if (mSourceRecord == null || (mSourceStack.topActivity() != null &&
                    mSourceStack.topActivity().getTask() == mSourceRecord.getTask())) {
                // We really do want to push this one into the user's face, right now.
                if (mLaunchTaskBehind && mSourceRecord != null) {
                    intentActivity.setTaskToAffiliateWith(mSourceRecord.getTask());
                }
                mMovedOtherTask = true;

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
                    } else if (launchStack.mStackId == DOCKED_STACK_ID
                            || launchStack.mStackId == FULLSCREEN_WORKSPACE_STACK_ID) {
                        if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
                            // If we want to launch adjacent and mTargetStack is not the computed
                            // launch stack - move task to top of computed stack.
                            intentTask.reparent(launchStack.mStackId, ON_TOP,
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
                        mMovedToFront = true;
                    } else if (launchStack.mDisplayId != mTargetStack.mDisplayId) {
                        // Target and computed stacks are on different displays and we've
                        // found a matching task - move the existing instance to that display and
                        // move it to front.
                        intentActivity.getTask().reparent(launchStack.mStackId, ON_TOP,
                                REPARENT_MOVE_STACK_TO_FRONT, ANIMATE, DEFER_RESUME,
                                "reparentToDisplay");
                        mMovedToFront = true;
                    } else if (launchStack.getStackId() == StackId.HOME_STACK_ID
                        && mTargetStack.getStackId() != StackId.HOME_STACK_ID) {
                        // It is possible for the home activity to be in another stack initially.
                        // For example, the activity may have been initially started with an intent
                        // which placed it in the fullscreen stack. To ensure the proper handling of
                        // the activity based on home stack assumptions, we must move it over.
                        intentActivity.getTask().reparent(launchStack.mStackId, ON_TOP,
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
                updateTaskReturnToType(intentActivity.getTask(), mLaunchFlags, focusStack);
            }
        }
        if (!mMovedToFront && mDoResume) {
            if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Bring to front target: " + mTargetStack
                    + " from " + intentActivity);
            mTargetStack.moveToFront("intentActivityFound");
        }

        mSupervisor.handleNonResizableTaskIfNeeded(intentActivity.getTask(), INVALID_STACK_ID,
                DEFAULT_DISPLAY, mTargetStack.mStackId);

        // If the caller has requested that the target task be reset, then do so.
        if ((mLaunchFlags & FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
            return mTargetStack.resetTaskIfNeededLocked(intentActivity, mStartActivity);
        }
        return intentActivity;
    }

    private void updateTaskReturnToType(
            TaskRecord task, int launchFlags, ActivityStack focusedStack) {
        if ((launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
            // Caller wants to appear on home activity.
            task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
            return;
        } else if (focusedStack == null || focusedStack.isHomeStack()) {
            // Task will be launched over the home stack, so return home.
            task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
            return;
        } else if (focusedStack != null && focusedStack != task.getStack() &&
                focusedStack.isAssistantStack()) {
            // Task was launched over the assistant stack, so return there
            task.setTaskToReturnTo(ASSISTANT_ACTIVITY_TYPE);
            return;
        }

        // Else we are coming from an application stack so return to an application.
        task.setTaskToReturnTo(APPLICATION_ACTIVITY_TYPE);
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

            // When we clear the task - focus will be adjusted, which will bring another task
            // to top before we launch the activity we need. This will temporary swap their
            // mTaskToReturnTo values and we don't want to overwrite them accidentally.
            mMovedOtherTask = true;
        } else if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                || mLaunchSingleInstance || mLaunchSingleTask) {
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
                            null /* bounds */, mLaunchFlags, mOptions);
                    mTargetStack.addTask(task,
                            !mLaunchTaskBehind /* toTop */, "startActivityUnchecked");
                }
            }
        } else if (mStartActivity.realActivity.equals(intentActivity.getTask().realActivity)) {
            // In this case the top activity on the task is the same as the one being launched,
            // so we take that as a request to bring the task to the foreground. If the top
            // activity in the task is the root activity, deliver this new intent to it if it
            // desires.
            if (((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0 || mLaunchSingleTop)
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
            TaskRecord taskToAffiliate, int preferredLaunchStackId, ActivityStack topStack) {
        mTargetStack = computeStackFocus(
                mStartActivity, true, mLaunchBounds, mLaunchFlags, mOptions);

        // Do no move the target stack to front yet, as we might bail if
        // isLockTaskModeViolation fails below.

        if (mReuseTask == null) {
            final TaskRecord task = mTargetStack.createTaskRecord(
                    mSupervisor.getNextTaskIdForUserLocked(mStartActivity.userId),
                    mNewTaskInfo != null ? mNewTaskInfo : mStartActivity.info,
                    mNewTaskIntent != null ? mNewTaskIntent : mIntent, mVoiceSession,
                    mVoiceInteractor, !mLaunchTaskBehind /* toTop */, mStartActivity.mActivityType);
            addOrReparentStartingActivity(task, "setTaskFromReuseOrCreateNewTask - mReuseTask");
            if (mLaunchBounds != null) {
                final int stackId = mTargetStack.mStackId;
                if (StackId.resizeStackWithLaunchBounds(stackId)) {
                    mService.resizeStack(
                            stackId, mLaunchBounds, true, !PRESERVE_WINDOWS, ANIMATE, -1);
                } else {
                    mStartActivity.getTask().updateOverrideConfiguration(mLaunchBounds);
                }
            }
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + mStartActivity
                    + " in new task " + mStartActivity.getTask());
        } else {
            addOrReparentStartingActivity(mReuseTask, "setTaskFromReuseOrCreateNewTask");
        }

        if (taskToAffiliate != null) {
            mStartActivity.setTaskToAffiliateWith(taskToAffiliate);
        }

        if (mSupervisor.isLockTaskModeViolation(mStartActivity.getTask())) {
            Slog.e(TAG, "Attempted Lock Task Mode violation mStartActivity=" + mStartActivity);
            return START_RETURN_LOCK_TASK_MODE_VIOLATION;
        }

        if (!mMovedOtherTask) {
            // If stack id is specified in activity options, usually it means that activity is
            // launched not from currently focused stack (e.g. from SysUI or from shell) - in
            // that case we check the target stack.
            updateTaskReturnToType(mStartActivity.getTask(), mLaunchFlags,
                    preferredLaunchStackId != INVALID_STACK_ID ? mTargetStack : topStack);
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
        if (mSupervisor.isLockTaskModeViolation(mSourceRecord.getTask())) {
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
            sourceTask.reparent(mTargetStack.mStackId, ON_TOP, REPARENT_MOVE_STACK_TO_FRONT,
                    !ANIMATE, DEFER_RESUME, "launchToSide");
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
        if (mSupervisor.isLockTaskModeViolation(mInTask)) {
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
                    || mLaunchSingleTop || mLaunchSingleTask) {
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

        if (mLaunchBounds != null) {
            mInTask.updateOverrideConfiguration(mLaunchBounds);
            int stackId = mInTask.getLaunchStackId();
            if (stackId != mInTask.getStackId()) {
                mInTask.reparent(stackId, ON_TOP, REPARENT_KEEP_STACK_AT_FRONT, !ANIMATE,
                        DEFER_RESUME, "inTaskToFront");
                stackId = mInTask.getStackId();
                mTargetStack = mInTask.getStack();
            }
            if (StackId.resizeStackWithLaunchBounds(stackId)) {
                mService.resizeStack(stackId, mLaunchBounds, true, !PRESERVE_WINDOWS, ANIMATE, -1);
            }
        }

        mTargetStack.moveTaskToFrontLocked(
                mInTask, mNoAnimation, mOptions, mStartActivity.appTimeTracker, "inTaskToFront");

        addOrReparentStartingActivity(mInTask, "setTaskFromInTask");
        if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + mStartActivity
                + " in explicit task " + mStartActivity.getTask());

        return START_SUCCESS;
    }

    private void setTaskToCurrentTopOrCreateNewTask() {
        mTargetStack = computeStackFocus(mStartActivity, false, null /* bounds */, mLaunchFlags,
                mOptions);
        if (mDoResume) {
            mTargetStack.moveToFront("addingToTopTask");
        }
        final ActivityRecord prev = mTargetStack.topActivity();
        final TaskRecord task = (prev != null) ? prev.getTask() : mTargetStack.createTaskRecord(
                mSupervisor.getNextTaskIdForUserLocked(mStartActivity.userId), mStartActivity.info,
                mIntent, null, null, true, mStartActivity.mActivityType);
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

    final void doPendingActivityLaunchesLocked(boolean doResume) {
        while (!mPendingActivityLaunches.isEmpty()) {
            final PendingActivityLaunch pal = mPendingActivityLaunches.remove(0);
            final boolean resume = doResume && mPendingActivityLaunches.isEmpty();
            try {
                startActivity(pal.r, pal.sourceRecord, null, null, pal.startFlags, resume, null,
                        null, null /*outRecords*/);
            } catch (Exception e) {
                Slog.e(TAG, "Exception during pending activity launch pal=" + pal, e);
                pal.sendErrorResult(e.getMessage());
            }
        }
    }

    private ActivityStack computeStackFocus(ActivityRecord r, boolean newTask, Rect bounds,
            int launchFlags, ActivityOptions aOptions) {
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

        if (mSourceDisplayId != DEFAULT_DISPLAY) {
            // Try to put the activity in a stack on a secondary display.
            stack = mSupervisor.getValidLaunchStackOnDisplay(mSourceDisplayId, r);
            if (stack == null) {
                // If source display is not suitable - look for topmost valid stack in the system.
                if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                        "computeStackFocus: Can't launch on mSourceDisplayId=" + mSourceDisplayId
                                + ", looking on all displays.");
                stack = mSupervisor.getNextValidLaunchStackLocked(r, mSourceDisplayId);
            }
        }
        if (stack == null) {
            // We first try to put the task in the first dynamic stack on home display.
            final ArrayList<ActivityStack> homeDisplayStacks = mSupervisor.mHomeStack.mStacks;
            for (int stackNdx = homeDisplayStacks.size() - 1; stackNdx >= 0; --stackNdx) {
                stack = homeDisplayStacks.get(stackNdx);
                if (isDynamicStack(stack.mStackId)) {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                            "computeStackFocus: Setting focused stack=" + stack);
                    return stack;
                }
            }
            // If there is no suitable dynamic stack then we figure out which static stack to use.
            final int stackId = task != null ? task.getLaunchStackId() :
                    bounds != null ? FREEFORM_WORKSPACE_STACK_ID :
                            FULLSCREEN_WORKSPACE_STACK_ID;
            stack = mSupervisor.getStack(stackId, CREATE_IF_NEEDED, ON_TOP);
        }
        if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS, "computeStackFocus: New stack r="
                + r + " stackId=" + stack.mStackId);
        return stack;
    }

    /** Check if provided activity record can launch in currently focused stack. */
    private boolean canLaunchIntoFocusedStack(ActivityRecord r, boolean newTask) {
        final ActivityStack focusedStack = mSupervisor.mFocusedStack;
        final int focusedStackId = mSupervisor.mFocusedStack.mStackId;
        final boolean canUseFocusedStack;
        switch (focusedStackId) {
            case FULLSCREEN_WORKSPACE_STACK_ID:
                // The fullscreen stack can contain any task regardless of if the task is resizeable
                // or not. So, we let the task go in the fullscreen task if it is the focus stack.
                canUseFocusedStack = true;
                break;
            case ASSISTANT_STACK_ID:
                canUseFocusedStack = r.isAssistantActivity();
                break;
            case DOCKED_STACK_ID:
                // Any activity which supports split screen can go in the docked stack.
                canUseFocusedStack = r.supportsSplitScreen();
                break;
            case FREEFORM_WORKSPACE_STACK_ID:
                // Any activity which supports freeform can go in the freeform stack.
                canUseFocusedStack = r.supportsFreeform();
                break;
            default:
                // Dynamic stacks behave similarly to the fullscreen stack and can contain any
                // resizeable task.
                canUseFocusedStack = isDynamicStack(focusedStackId)
                        && r.canBeLaunchedOnDisplay(focusedStack.mDisplayId);
        }

        return canUseFocusedStack && !newTask
                // We strongly prefer to launch activities on the same display as their source.
                && (mSourceDisplayId == focusedStack.mDisplayId);
    }

    private ActivityStack getLaunchStack(ActivityRecord r, int launchFlags, TaskRecord task,
            ActivityOptions aOptions) {
        // We are reusing a task, keep the stack!
        if (mReuseTask != null) {
            return mReuseTask.getStack();
        }

        // If the activity is of a specific type, return the associated stack, creating it if
        // necessary
        if (r.isHomeActivity()) {
            return mSupervisor.mHomeStack;
        }
        if (r.isRecentsActivity()) {
            return mSupervisor.getStack(RECENTS_STACK_ID, CREATE_IF_NEEDED, ON_TOP);
        }
        if (r.isAssistantActivity()) {
            return mSupervisor.getStack(ASSISTANT_STACK_ID, CREATE_IF_NEEDED, ON_TOP);
        }

        final int launchDisplayId =
                (aOptions != null) ? aOptions.getLaunchDisplayId() : INVALID_DISPLAY;

        final int launchStackId =
                (aOptions != null) ? aOptions.getLaunchStackId() : INVALID_STACK_ID;

        if (launchStackId != INVALID_STACK_ID && launchDisplayId != INVALID_DISPLAY) {
            throw new IllegalArgumentException(
                    "Stack and display id can't be set at the same time.");
        }

        if (isValidLaunchStackId(launchStackId, launchDisplayId, r)) {
            return mSupervisor.getStack(launchStackId, CREATE_IF_NEEDED, ON_TOP);
        }
        if (launchStackId == DOCKED_STACK_ID) {
            // The preferred launch stack is the docked stack, but it isn't a valid launch stack
            // for this activity, so we put the activity in the fullscreen stack.
            return mSupervisor.getStack(FULLSCREEN_WORKSPACE_STACK_ID, CREATE_IF_NEEDED, ON_TOP);
        }
        if (launchDisplayId != INVALID_DISPLAY) {
            // Stack id has higher priority than display id.
            return mSupervisor.getValidLaunchStackOnDisplay(launchDisplayId, r);
        }

        // If we are using Vr2d display, find the virtual display stack.
        if (mUsingVr2dDisplay) {
            ActivityStack as = mSupervisor.getValidLaunchStackOnDisplay(mSourceDisplayId, r);
            if (DEBUG_STACK) {
                Slog.v(TAG, "Launch stack for app: " + r.toString() +
                    ", on virtual display stack:" + as.toString());
            }
            return as;
        }

        if (((launchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) == 0)
                 || mSourceDisplayId != DEFAULT_DISPLAY) {
            return null;
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

            if (parentStack != null && parentStack.isDockedStack()) {
                // If parent was in docked stack, the natural place to launch another activity
                // will be fullscreen, so it can appear alongside the docked window.
                return mSupervisor.getStack(FULLSCREEN_WORKSPACE_STACK_ID, CREATE_IF_NEEDED,
                        ON_TOP);
            } else {
                // If the parent is not in the docked stack, we check if there is docked window
                // and if yes, we will launch into that stack. If not, we just put the new
                // activity into parent's stack, because we can't find a better place.
                final ActivityStack dockedStack = mSupervisor.getStack(DOCKED_STACK_ID);
                if (dockedStack != null
                        && dockedStack.shouldBeVisible(r) == STACK_INVISIBLE) {
                    // There is a docked stack, but it isn't visible, so we can't launch into that.
                    return null;
                } else {
                    return dockedStack;
                }
            }
        }
    }

    boolean isValidLaunchStackId(int stackId, int displayId, ActivityRecord r) {
        switch (stackId) {
            case INVALID_STACK_ID:
            case HOME_STACK_ID:
                return false;
            case FULLSCREEN_WORKSPACE_STACK_ID:
                return true;
            case FREEFORM_WORKSPACE_STACK_ID:
                return r.supportsFreeform();
            case DOCKED_STACK_ID:
                return r.supportsSplitScreen();
            case PINNED_STACK_ID:
                return r.supportsPictureInPicture();
            case RECENTS_STACK_ID:
                return r.isRecentsActivity();
            case ASSISTANT_STACK_ID:
                return r.isAssistantActivity();
            default:
                if (StackId.isDynamicStack(stackId)) {
                    return r.canBeLaunchedOnDisplay(displayId);
                }
                Slog.e(TAG, "isValidLaunchStackId: Unexpected stackId=" + stackId);
                return false;
        }
    }

    Rect getOverrideBounds(ActivityRecord r, ActivityOptions options, TaskRecord inTask) {
        Rect newBounds = null;
        if (options != null && (r.isResizeable() || (inTask != null && inTask.isResizeable()))) {
            if (mSupervisor.canUseActivityOptionsLaunchBounds(
                    options, options.getLaunchStackId())) {
                newBounds = TaskRecord.validateBounds(options.getLaunchBounds());
            }
        }
        return newBounds;
    }

    void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
    }

    void removePendingActivityLaunchesLocked(ActivityStack stack) {
        for (int palNdx = mPendingActivityLaunches.size() - 1; palNdx >= 0; --palNdx) {
            PendingActivityLaunch pal = mPendingActivityLaunches.get(palNdx);
            if (pal.stack == stack) {
                mPendingActivityLaunches.remove(palNdx);
            }
        }
    }

    static boolean isDocumentLaunchesIntoExisting(int flags) {
        return (flags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 &&
                (flags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0;
    }

    boolean clearPendingActivityLaunchesLocked(String packageName) {
        boolean didSomething = false;

        for (int palNdx = mPendingActivityLaunches.size() - 1; palNdx >= 0; --palNdx) {
            PendingActivityLaunch pal = mPendingActivityLaunches.get(palNdx);
            ActivityRecord r = pal.r;
            if (r != null && r.packageName.equals(packageName)) {
                mPendingActivityLaunches.remove(palNdx);
                didSomething = true;
            }
        }
        return didSomething;
    }

    void dump(PrintWriter pw, String prefix, String dumpPackage) {
        prefix = prefix + "  ";

        if (dumpPackage != null) {
            if ((mLastStartActivityRecord[0] == null ||
                    !dumpPackage.equals(mLastHomeActivityStartRecord[0].packageName)) &&
                    (mLastHomeActivityStartRecord[0] == null ||
                    !dumpPackage.equals(mLastHomeActivityStartRecord[0].packageName)) &&
                    (mStartActivity == null || !dumpPackage.equals(mStartActivity.packageName))) {
                pw.print(prefix);
                pw.println("(nothing)");
                return;
            }
        }

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
        pw.print(prefix);
        pw.print("mLastHomeActivityStartResult=");
        pw.println(mLastHomeActivityStartResult);
        r = mLastHomeActivityStartRecord[0];
        if (r != null) {
            pw.print(prefix);
            pw.println("mLastHomeActivityStartRecord:");
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
        pw.print(mLaunchSingleTop);
        pw.print(" mLaunchSingleInstance=");
        pw.print(mLaunchSingleInstance);
        pw.print(" mLaunchSingleTask=");
        pw.println(mLaunchSingleTask);
        pw.print(prefix);
        pw.print("mLaunchFlags=0x");
        pw.print(Integer.toHexString(mLaunchFlags));
        pw.print(" mDoResume=");
        pw.print(mDoResume);
        pw.print(" mAddingToTask=");
        pw.println(mAddingToTask);
    }
}
