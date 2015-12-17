package com.android.server.am;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_TO_SIDE;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME;
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
import static com.android.server.am.ActivityRecord.HOME_ACTIVITY_TYPE;
import static com.android.server.am.ActivityRecord.RECENTS_ACTIVITY_TYPE;
import static com.android.server.am.ActivityStack.ActivityState.RESUMED;
import static com.android.server.am.ActivityStackSupervisor.CREATE_IF_NEEDED;
import static com.android.server.am.ActivityStackSupervisor.FORCE_FOCUS;
import static com.android.server.am.ActivityStackSupervisor.ON_TOP;
import static com.android.server.am.ActivityStackSupervisor.TAG_TASKS;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityContainer;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.ProfilerInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.voice.IVoiceInteractionSession;
import android.util.EventLog;
import android.util.Slog;
import android.view.Display;

import com.android.internal.app.HeavyWeightSwitcherActivity;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.am.ActivityStackSupervisor.PendingActivityLaunch;
import com.android.server.wm.WindowManagerService;

import java.util.ArrayList;

/**
 * Controller for interpreting how and then launching activities.
 *
 * This class collects all the logic for determining how an intent and flags should be turned into
 * an activity and associated task and stack.
 */
public class ActivityStarter {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStarter" : TAG_AM;
    private static final String TAG_RESULTS = TAG + POSTFIX_RESULTS;
    private static final String TAG_FOCUS = TAG + POSTFIX_FOCUS;
    private static final String TAG_CONFIGURATION = TAG + POSTFIX_CONFIGURATION;
    private static final String TAG_USER_LEAVING = TAG + POSTFIX_USER_LEAVING;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private WindowManagerService mWindowManager;

    final ArrayList<PendingActivityLaunch> mPendingActivityLaunches = new ArrayList<>();

    ActivityStarter(ActivityManagerService service, ActivityStackSupervisor supervisor) {
        mService = service;
        mSupervisor = supervisor;
    }

    final int startActivityLocked(IApplicationThread caller, Intent intent, Intent ephemeralIntent,
            String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid,
            String callingPackage, int realCallingPid, int realCallingUid, int startFlags,
            ActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified,
            ActivityRecord[] outActivity, ActivityStackSupervisor.ActivityContainer container,
            TaskRecord inTask) {
        int err = ActivityManager.START_SUCCESS;

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
                    + "} from uid " + callingUid
                    + " on display " + (container == null ? (mSupervisor.mFocusedStack == null ?
                    Display.DEFAULT_DISPLAY : mSupervisor.mFocusedStack.mDisplayId) :
                    (container.mActivityDisplay == null ? Display.DEFAULT_DISPLAY :
                            container.mActivityDisplay.mDisplayId)));
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
                && sourceRecord.task.voiceSession != null) {
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

        final ActivityStack resultStack = resultRecord == null ? null : resultRecord.task.stack;

        if (err != ActivityManager.START_SUCCESS) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1,
                        resultRecord, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null);
            }
            ActivityOptions.abort(options);
            return err;
        }

        boolean abort = !mSupervisor.checkStartAnyActivityPermission(intent, aInfo, resultWho,
                requestCode, callingPid, callingUid, callingPackage, ignoreTargetSecurity, callerApp,
                resultRecord, resultStack);
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

        UserInfo user = mSupervisor.getUserInfo(userId);
        KeyguardManager km = (KeyguardManager) mService.mContext
                .getSystemService(Context.KEYGUARD_SERVICE);
        if (user.isManagedProfile()
                && LockPatternUtils.isSeparateWorkChallengeEnabled()
                && km.isDeviceLocked(userId)) {
            IIntentSender target = mService.getIntentSenderLocked(
                    ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                    Binder.getCallingUid(), userId, null, null, 0, new Intent[]{ intent },
                    new String[]{ resolvedType },
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                            | PendingIntent.FLAG_IMMUTABLE, null);
            final int flags = intent.getFlags();
            final Intent newIntent = km.createConfirmDeviceCredentialIntent(null, null, user.id);
            if (newIntent != null) {
                intent = newIntent;
                intent.setFlags(flags
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                intent.putExtra(Intent.EXTRA_PACKAGE_NAME, aInfo.packageName);
                intent.putExtra(Intent.EXTRA_INTENT, new IntentSender(target));

                resolvedType = null;
                callingUid = realCallingUid;
                callingPid = realCallingPid;

                UserInfo parent = UserManager.get(mService.mContext).getProfileParent(userId);
                rInfo = mSupervisor.resolveIntent(intent, resolvedType, parent.id);
                aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags,
                        null /*profilerInfo*/);
            }
        }

        if (abort) {
            if (resultRecord != null) {
                resultStack.sendActivityResultLocked(-1, resultRecord, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null);
            }
            // We pretend to the caller that it was really started, but
            // they will just get a cancel result.
            ActivityOptions.abort(options);
            return ActivityManager.START_SUCCESS;
        }

        // If permissions need a review before any of the app components can run, we
        // launch the review activity and pass a pending intent to start the activity
        // we are to launching now after the review is completed.
        if (Build.PERMISSIONS_REVIEW_REQUIRED && aInfo != null) {
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
                            + (container == null ? (mSupervisor.mFocusedStack == null ?
                            Display.DEFAULT_DISPLAY : mSupervisor.mFocusedStack.mDisplayId) :
                            (container.mActivityDisplay == null ? Display.DEFAULT_DISPLAY :
                                    container.mActivityDisplay.mDisplayId)));
                }
            }
        }

        // If we have an ephemeral app, abort the process of launching the resolved intent.
        // Instead, launch the ephemeral installer. Once the installer is finished, it
        // starts either the intent we resolved here [on install error] or the ephemeral
        // app [on install success].
        if (rInfo != null && rInfo.ephemeralResolveInfo != null) {
            // Create a pending intent to start the intent resolved here.
            final IIntentSender failureTarget = mService.getIntentSenderLocked(
                    ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                    Binder.getCallingUid(), userId, null, null, 0, new Intent[]{ intent },
                    new String[]{ resolvedType },
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                            | PendingIntent.FLAG_IMMUTABLE, null);

            // Create a pending intent to start the ephemeral application; force it to be
            // directed to the ephemeral package.
            ephemeralIntent.setPackage(rInfo.ephemeralResolveInfo.getPackageName());
            final IIntentSender ephemeralTarget = mService.getIntentSenderLocked(
                    ActivityManager.INTENT_SENDER_ACTIVITY, callingPackage,
                    Binder.getCallingUid(), userId, null, null, 0, new Intent[]{ ephemeralIntent },
                    new String[]{ resolvedType },
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_ONE_SHOT
                            | PendingIntent.FLAG_IMMUTABLE, null);

            int flags = intent.getFlags();
            intent = new Intent();
            intent.setFlags(flags
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME,
                    rInfo.ephemeralResolveInfo.getPackageName());
            intent.putExtra(Intent.EXTRA_EPHEMERAL_FAILURE, new IntentSender(failureTarget));
            intent.putExtra(Intent.EXTRA_EPHEMERAL_SUCCESS, new IntentSender(ephemeralTarget));

            resolvedType = null;
            callingUid = realCallingUid;
            callingPid = realCallingPid;

            rInfo = rInfo.ephemeralInstaller;
            aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, null /*profilerInfo*/);
        }

        ActivityRecord r = new ActivityRecord(mService, callerApp, callingUid, callingPackage,
                intent, resolvedType, aInfo, mService.mConfiguration, resultRecord, resultWho,
                requestCode, componentSpecified, voiceSession != null, mSupervisor, container,
                options);
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

        err = startActivityUncheckedLocked(r, sourceRecord, voiceSession,
                voiceInteractor, startFlags, true, options, inTask);

        if (err < 0) {
            // If someone asked to have the keyguard dismissed on the next
            // activity start, but we are not actually doing an activity
            // switch...  just dismiss the keyguard now, because we
            // probably want to see whatever is behind it.
            mSupervisor.notifyActivityDrawnForKeyguard();
        }
        return err;
    }

    void startHomeActivityLocked(Intent intent, ActivityInfo aInfo, String reason) {
        mSupervisor.moveHomeStackTaskToTop(HOME_ACTIVITY_TYPE, reason);
        startActivityLocked(null /*caller*/, intent, null /*ephemeralIntent*/,
                null /*resolvedType*/, aInfo, null /*rInfo*/, null /*voiceSession*/,
                null /*voiceInteractor*/, null /*resultTo*/, null /*resultWho*/,
                0 /*requestCode*/, 0 /*callingPid*/, 0 /*callingUid*/, null /*callingPackage*/,
                0 /*realCallingPid*/, 0 /*realCallingUid*/, 0 /*startFlags*/, null /*options*/,
                false /*ignoreTargetSecurity*/, false /*componentSpecified*/,  null /*outActivity*/,
                null /*container*/, null /*inTask*/);
        if (mSupervisor.inResumeTopActivity) {
            // If we are in resume section already, home activity will be initialized, but not
            // resumed (to avoid recursive resume) and will stay that way until something pokes it
            // again. We need to schedule another resume.
            mSupervisor.scheduleResumeTopActivities();
        }
    }

    final int startActivityMayWait(IApplicationThread caller, int callingUid,
            String callingPackage, Intent intent, String resolvedType,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, IActivityManager.WaitResult outResult, Configuration config,
            Bundle bOptions, boolean ignoreTargetSecurity, int userId,
            IActivityContainer iContainer, TaskRecord inTask) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        boolean componentSpecified = intent.getComponent() != null;

        // Save a copy in case ephemeral needs it
        final Intent ephemeralIntent = new Intent(intent);
        // Don't modify the client's object!
        intent = new Intent(intent);

        ResolveInfo rInfo = mSupervisor.resolveIntent(intent, resolvedType, userId);
        // Collect information about the target of the Intent.
        ActivityInfo aInfo = mSupervisor.resolveActivity(intent, rInfo, startFlags, profilerInfo);

        ActivityOptions options = ActivityOptions.fromBundle(bOptions);
        ActivityStackSupervisor.ActivityContainer container =
                (ActivityStackSupervisor.ActivityContainer)iContainer;
        synchronized (mService) {
            if (container != null && container.mParentActivity != null &&
                    container.mParentActivity.state != RESUMED) {
                // Cannot start a child activity if the parent is not resumed.
                return ActivityManager.START_CANCELED;
            }
            final int realCallingPid = Binder.getCallingPid();
            final int realCallingUid = Binder.getCallingUid();
            int callingPid;
            if (callingUid >= 0) {
                callingPid = -1;
            } else if (caller == null) {
                callingPid = realCallingPid;
                callingUid = realCallingUid;
            } else {
                callingPid = callingUid = -1;
            }

            final ActivityStack stack;
            if (container == null || container.mStack.isOnHomeDisplay()) {
                stack = mSupervisor.mFocusedStack;
            } else {
                stack = container.mStack;
            }
            stack.mConfigWillChange = config != null && mService.mConfiguration.diff(config) != 0;
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
                                    hist.task.taskId);
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

            int res = startActivityLocked(caller, intent, ephemeralIntent, resolvedType,
                    aInfo, rInfo, voiceSession, voiceInteractor,
                    resultTo, resultWho, requestCode, callingPid,
                    callingUid, callingPackage, realCallingPid, realCallingUid, startFlags,
                    options, ignoreTargetSecurity, componentSpecified, null, container, inTask);

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
                mService.updateConfigurationLocked(config, null, false);
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
                    } while (!outResult.timeout && outResult.who == null);
                } else if (res == ActivityManager.START_TASK_TO_FRONT) {
                    ActivityRecord r = stack.topRunningActivityLocked();
                    if (r.nowVisible && r.state == RESUMED) {
                        outResult.timeout = false;
                        outResult.who = new ComponentName(r.info.packageName, r.info.name);
                        outResult.totalTime = 0;
                        outResult.thisTime = 0;
                    } else {
                        outResult.thisTime = SystemClock.uptimeMillis();
                        mSupervisor.mWaitingActivityVisible.add(outResult);
                        do {
                            try {
                                mService.wait();
                            } catch (InterruptedException e) {
                            }
                        } while (!outResult.timeout && outResult.who == null);
                    }
                }
            }

            return res;
        }
    }

    final int startActivityUncheckedLocked(final ActivityRecord r, ActivityRecord sourceRecord,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor, int startFlags,
            boolean doResume, ActivityOptions options, TaskRecord inTask) {
        final Intent intent = r.intent;
        final int callingUid = r.launchedFromUid;

        final Rect newBounds = mSupervisor.getOverrideBounds(r, options, inTask);
        final boolean overrideBounds = newBounds != null;

        // In some flows in to this function, we retrieve the task record and hold on to it
        // without a lock before calling back in to here...  so the task at this point may
        // not actually be in recents.  Check for that, and if it isn't in recents just
        // consider it invalid.
        if (inTask != null && !inTask.inRecents) {
            Slog.w(TAG, "Starting activity in task not in recents: " + inTask);
            inTask = null;
        }

        final boolean launchSingleTop = r.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP;
        final boolean launchSingleInstance = r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE;
        final boolean launchSingleTask = r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK;
        int launchFlags = adjustLaunchFlagsToDocumentMode(r, launchSingleInstance, launchSingleTask,
                intent.getFlags());
        final boolean launchTaskBehind = r.mLaunchTaskBehind
                && !launchSingleTask && !launchSingleInstance
                && (launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0;

        if (r.resultTo != null && (launchFlags & FLAG_ACTIVITY_NEW_TASK) != 0
                && r.resultTo.task.stack != null) {
            // For whatever reason this activity is being launched into a new
            // task...  yet the caller has requested a result back.  Well, that
            // is pretty messed up, so instead immediately send back a cancel
            // and let the new task continue launched as normal without a
            // dependency on its originator.
            Slog.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
            r.resultTo.task.stack.sendActivityResultLocked(-1,
                    r.resultTo, r.resultWho, r.requestCode,
                    Activity.RESULT_CANCELED, null);
            r.resultTo = null;
        }

        if ((launchFlags & Intent.FLAG_ACTIVITY_NEW_DOCUMENT) != 0 && r.resultTo == null) {
            launchFlags |= FLAG_ACTIVITY_NEW_TASK;
        }

        // If we are actually going to launch in to a new task, there are some cases where
        // we further want to do multiple task.
        if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            if (launchTaskBehind
                    || r.info.documentLaunchMode == ActivityInfo.DOCUMENT_LAUNCH_ALWAYS) {
                launchFlags |= FLAG_ACTIVITY_MULTIPLE_TASK;
            }
        }

        // We'll invoke onUserLeaving before onPause only if the launching
        // activity did not explicitly state that this is an automated launch.
        mSupervisor.mUserLeaving = (launchFlags & Intent.FLAG_ACTIVITY_NO_USER_ACTION) == 0;
        if (DEBUG_USER_LEAVING) Slog.v(TAG_USER_LEAVING,
                "startActivity() => mUserLeaving=" + mSupervisor.mUserLeaving);

        // If the caller has asked not to resume at this point, we make note
        // of this in the record so that we can skip it when trying to find
        // the top running activity.
        if (!doResume || !mSupervisor.okToShowLocked(r)) {
            r.delayedResume = true;
            doResume = false;
        }

        final ActivityRecord notTop =
                (launchFlags & Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP) != 0 ? r : null;

        // If the onlyIfNeeded flag is set, then we can do this if the activity
        // being launched is the same as the one making the call...  or, as
        // a special case, if we do not know the caller then we count the
        // current top activity as the caller.
        if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
            ActivityRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = mSupervisor.mFocusedStack.topRunningNonDelayedActivityLocked(
                        notTop);
            }
            if (!checkedCaller.realActivity.equals(r.realActivity)) {
                // Caller is not the same as launcher, so always needed.
                startFlags &= ~ActivityManager.START_FLAG_ONLY_IF_NEEDED;
            }
        }

        boolean addingToTask = false;
        TaskRecord reuseTask = null;

        // If the caller is not coming from another activity, but has given us an
        // explicit task into which they would like us to launch the new activity,
        // then let's see about doing that.
        if (sourceRecord == null && inTask != null && inTask.stack != null) {
            final Intent baseIntent = inTask.getBaseIntent();
            final ActivityRecord root = inTask.getRootActivity();
            if (baseIntent == null) {
                ActivityOptions.abort(options);
                throw new IllegalArgumentException("Launching into task without base intent: "
                        + inTask);
            }

            // If this task is empty, then we are adding the first activity -- it
            // determines the root, and must be launching as a NEW_TASK.
            if (launchSingleInstance || launchSingleTask) {
                if (!baseIntent.getComponent().equals(r.intent.getComponent())) {
                    ActivityOptions.abort(options);
                    throw new IllegalArgumentException("Trying to launch singleInstance/Task "
                            + r + " into different task " + inTask);
                }
                if (root != null) {
                    ActivityOptions.abort(options);
                    throw new IllegalArgumentException("Caller with inTask " + inTask
                            + " has root " + root + " but target is singleInstance/Task");
                }
            }

            // If task is empty, then adopt the interesting intent launch flags in to the
            // activity being started.
            if (root == null) {
                final int flagsOfInterest = FLAG_ACTIVITY_NEW_TASK
                        | FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS;
                launchFlags = (launchFlags&~flagsOfInterest)
                        | (baseIntent.getFlags()&flagsOfInterest);
                intent.setFlags(launchFlags);
                inTask.setIntent(r);
                addingToTask = true;

                // If the task is not empty and the caller is asking to start it as the root
                // of a new task, then we don't actually want to start this on the task.  We
                // will bring the task to the front, and possibly give it a new intent.
            } else if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
                addingToTask = false;

            } else {
                addingToTask = true;
            }

            reuseTask = inTask;
        } else {
            inTask = null;
            // Launch ResolverActivity in the source task, so that it stays in the task
            // bounds when in freeform workspace.
            // Also put noDisplay activities in the source task. These by itself can
            // be placed in any task/stack, however it could launch other activities
            // like ResolverActivity, and we want those to stay in the original task.
            if ((r.isResolverActivity() || r.noDisplay) && sourceRecord != null
                    && sourceRecord.isFreeform())  {
                addingToTask = true;
            }
        }

        if (inTask == null) {
            if (sourceRecord == null) {
                // This activity is not being started from another...  in this
                // case we -always- start a new task.
                if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) == 0 && inTask == null) {
                    Slog.w(TAG, "startActivity called from non-Activity context; forcing " +
                            "Intent.FLAG_ACTIVITY_NEW_TASK for: " + intent);
                    launchFlags |= FLAG_ACTIVITY_NEW_TASK;
                }
            } else if (sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                // The original activity who is starting us is running as a single
                // instance...  this new activity it is starting must go on its
                // own task.
                launchFlags |= FLAG_ACTIVITY_NEW_TASK;
            } else if (launchSingleInstance || launchSingleTask) {
                // The activity being started is a single instance...  it always
                // gets launched into its own task.
                launchFlags |= FLAG_ACTIVITY_NEW_TASK;
            }
        }

        ActivityInfo newTaskInfo = null;
        Intent newTaskIntent = null;
        final ActivityStack sourceStack;
        if (sourceRecord != null) {
            if (sourceRecord.finishing) {
                // If the source is finishing, we can't further count it as our source.  This
                // is because the task it is associated with may now be empty and on its way out,
                // so we don't want to blindly throw it in to that task.  Instead we will take
                // the NEW_TASK flow and try to find a task for it. But save the task information
                // so it can be used when creating the new task.
                if ((launchFlags & FLAG_ACTIVITY_NEW_TASK) == 0) {
                    Slog.w(TAG, "startActivity called from finishing " + sourceRecord
                            + "; forcing " + "Intent.FLAG_ACTIVITY_NEW_TASK for: " + intent);
                    launchFlags |= FLAG_ACTIVITY_NEW_TASK;
                    newTaskInfo = sourceRecord.info;
                    newTaskIntent = sourceRecord.task.intent;
                }
                sourceRecord = null;
                sourceStack = null;
            } else {
                sourceStack = sourceRecord.task.stack;
            }
        } else {
            sourceStack = null;
        }

        boolean movedHome = false;
        ActivityStack targetStack;

        intent.setFlags(launchFlags);
        final boolean noAnimation = (launchFlags & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0;

        ActivityRecord intentActivity = getReusableIntentActivity(r, inTask, intent,
                launchSingleInstance, launchSingleTask, launchFlags);
        if (intentActivity != null) {
            // When the flags NEW_TASK and CLEAR_TASK are set, then the task gets reused
            // but still needs to be a lock task mode violation since the task gets
            // cleared out and the device would otherwise leave the locked task.
            if (mSupervisor.isLockTaskModeViolation(intentActivity.task,
                    (launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                            == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))) {
                mSupervisor.showLockTaskToast();
                Slog.e(TAG, "startActivityUnchecked: Attempt to violate Lock Task Mode");
                return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
            if (r.task == null) {
                r.task = intentActivity.task;
            }
            if (intentActivity.task.intent == null) {
                // This task was started because of movement of the activity based on affinity...
                // Now that we are actually launching it, we can assign the base intent.
                intentActivity.task.setIntent(r);
            }

            targetStack = intentActivity.task.stack;
            targetStack.mLastPausedActivity = null;
            // If the target task is not in the front, then we need
            // to bring it to the front...  except...  well, with
            // SINGLE_TASK_LAUNCH it's not entirely clear.  We'd like
            // to have the same behavior as if a new instance was
            // being started, which means not bringing it to the front
            // if the caller is not itself in the front.
            final ActivityStack focusStack = mSupervisor.getFocusedStack();
            ActivityRecord curTop = (focusStack == null)
                    ? null : focusStack.topRunningNonDelayedActivityLocked(notTop);
            boolean movedToFront = false;
            if (curTop != null && (curTop.task != intentActivity.task ||
                    curTop.task != focusStack.topTask())) {
                r.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                if (sourceRecord == null || (sourceStack.topActivity() != null &&
                        sourceStack.topActivity().task == sourceRecord.task)) {
                    // We really do want to push this one into the user's face, right now.
                    if (launchTaskBehind && sourceRecord != null) {
                        intentActivity.setTaskToAffiliateWith(sourceRecord.task);
                    }
                    movedHome = true;
                    final ActivityStack sideStack = getLaunchToSideStack(r, launchFlags, r.task);
                    if (sideStack == null || sideStack == targetStack) {
                        // We only want to move to the front, if we aren't going to launch on a
                        // different stack. If we launch on a different stack, we will put the
                        // task on top there.
                        targetStack.moveTaskToFrontLocked(intentActivity.task, noAnimation,
                                options, r.appTimeTracker, "bringingFoundTaskToFront");
                        movedToFront = true;
                    }
                    if ((launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                            == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
                        // Caller wants to appear on home activity.
                        intentActivity.task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
                    }
                    options = null;
                }
            }
            if (!movedToFront && doResume) {
                if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Bring to front target: " + targetStack
                        + " from " + intentActivity);
                targetStack.moveToFront("intentActivityFound");
            }

            // If the caller has requested that the target task be
            // reset, then do so.
            if ((launchFlags & Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                intentActivity = targetStack.resetTaskIfNeededLocked(intentActivity, r);
            }
            if ((startFlags & ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                // We don't need to start a new activity, and
                // the client said not to do anything if that
                // is the case, so this is it!  And for paranoia, make
                // sure we have correctly resumed the top activity.
                if (doResume) {
                    mSupervisor.resumeTopActivitiesLocked(targetStack, null, options);

                    // Make sure to notify Keyguard as well if we are not running an app
                    // transition later.
                    if (!movedToFront) {
                        mSupervisor.notifyActivityDrawnForKeyguard();
                    }
                } else {
                    ActivityOptions.abort(options);
                }
                mSupervisor.updateUserStackLocked(r.userId, targetStack);
                return ActivityManager.START_RETURN_INTENT_TO_CALLER;
            }
            if ((launchFlags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK))
                    == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK)) {
                // The caller has requested to completely replace any
                // existing task with its new activity.  Well that should
                // not be too hard...
                reuseTask = intentActivity.task;
                reuseTask.performClearTaskLocked();
                reuseTask.setIntent(r);
            } else if ((launchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
                    || launchSingleInstance || launchSingleTask) {
                // In this situation we want to remove all activities
                // from the task up to the one being started.  In most
                // cases this means we are resetting the task to its
                // initial state.
                ActivityRecord top = intentActivity.task.performClearTaskLocked(r, launchFlags);
                if (top != null) {
                    if (top.frontOfTask) {
                        // Activity aliases may mean we use different
                        // intents for the top activity, so make sure
                        // the task now has the identity of the new
                        // intent.
                        top.task.setIntent(r);
                    }
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                    top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                } else {
                    // A special case: we need to start the activity because it is not
                    // currently running, and the caller has asked to clear the current
                    // task to have this activity at the top.
                    addingToTask = true;
                    // Now pretend like this activity is being started by the top of its
                    // task, so it is put in the right place.
                    sourceRecord = intentActivity;
                    TaskRecord task = sourceRecord.task;
                    if (task != null && task.stack == null) {
                        // Target stack got cleared when we all activities were removed
                        // above. Go ahead and reset it.
                        targetStack = computeStackFocus(
                                sourceRecord, false /* newTask */, null /* bounds */, launchFlags);
                        targetStack.addTask(task,
                                !launchTaskBehind /* toTop */, "startActivityUnchecked");
                    }

                }
            } else if (r.realActivity.equals(intentActivity.task.realActivity)) {
                // In this case the top activity on the task is the
                // same as the one being launched, so we take that
                // as a request to bring the task to the foreground.
                // If the top activity in the task is the root
                // activity, deliver this new intent to it if it
                // desires.
                if (((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0 || launchSingleTop)
                        && intentActivity.realActivity.equals(r.realActivity)) {
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r,
                            intentActivity.task);
                    if (intentActivity.frontOfTask) {
                        intentActivity.task.setIntent(r);
                    }
                    intentActivity.deliverNewIntentLocked(callingUid, r.intent,
                            r.launchedFromPackage);
                } else if (!r.intent.filterEquals(intentActivity.task.intent)) {
                    // In this case we are launching the root activity
                    // of the task, but with a different intent.  We
                    // should start a new instance on top.
                    addingToTask = true;
                    sourceRecord = intentActivity;
                }
            } else if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
                // In this case an activity is being launched in to an
                // existing task, without resetting that task.  This
                // is typically the situation of launching an activity
                // from a notification or shortcut.  We want to place
                // the new activity on top of the current task.
                addingToTask = true;
                sourceRecord = intentActivity;
            } else if (!intentActivity.task.rootWasReset) {
                // In this case we are launching in to an existing task
                // that has not yet been started from its front door.
                // The current task has been brought to the front.
                // Ideally, we'd probably like to place this new task
                // at the bottom of its stack, but that's a little hard
                // to do with the current organization of the code so
                // for now we'll just drop it.
                intentActivity.task.setIntent(r);
            }
            if (!addingToTask && reuseTask == null) {
                // We didn't do anything...  but it was needed (a.k.a., client
                // don't use that intent!)  And for paranoia, make
                // sure we have correctly resumed the top activity.
                if (doResume) {
                    targetStack.resumeTopActivityLocked(null, options);
                    if (!movedToFront) {
                        // Make sure to notify Keyguard as well if we are not running an app
                        // transition later.
                        mSupervisor.notifyActivityDrawnForKeyguard();
                    }
                } else {
                    ActivityOptions.abort(options);
                }
                mSupervisor.updateUserStackLocked(r.userId, targetStack);
                return ActivityManager.START_TASK_TO_FRONT;
            }
        }

        //String uri = r.intent.toURI();
        //Intent intent2 = new Intent(uri);
        //Slog.i(TAG, "Given intent: " + r.intent);
        //Slog.i(TAG, "URI is: " + uri);
        //Slog.i(TAG, "To intent: " + intent2);

        if (r.packageName != null) {
            // If the activity being launched is the same as the one currently
            // at the top, then we need to check if it should only be launched
            // once.
            ActivityStack topStack = mSupervisor.mFocusedStack;
            ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(notTop);
            final boolean dontStart = top != null && r.resultTo == null
                    && top.realActivity.equals(r.realActivity) && top.userId == r.userId
                    && top.app != null && top.app.thread != null
                    && ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                    || launchSingleTop || launchSingleTask);
            if (dontStart) {
                ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top, top.task);
                // For paranoia, make sure we have correctly resumed the top activity.
                topStack.mLastPausedActivity = null;
                if (doResume) {
                    mSupervisor.resumeTopActivitiesLocked();
                }
                ActivityOptions.abort(options);
                if ((startFlags & ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                    // We don't need to start a new activity, and the client said not to do
                    // anything if that is the case, so this is it!
                    return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                }
                top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                return ActivityManager.START_DELIVERED_TO_TOP;
            }
        } else {
            if (r.resultTo != null && r.resultTo.task.stack != null) {
                r.resultTo.task.stack.sendActivityResultLocked(-1, r.resultTo, r.resultWho,
                        r.requestCode, Activity.RESULT_CANCELED, null);
            }
            ActivityOptions.abort(options);
            return ActivityManager.START_CLASS_NOT_FOUND;
        }

        boolean newTask = false;
        boolean keepCurTransition = false;

        final TaskRecord taskToAffiliate = launchTaskBehind && sourceRecord != null ?
                sourceRecord.task : null;

        // Should this be considered a new task?
        if (r.resultTo == null && inTask == null && !addingToTask
                && (launchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
            newTask = true;
            targetStack = computeStackFocus(r, newTask, newBounds, launchFlags);
            if (doResume) {
                targetStack.moveToFront("startingNewTask");
            }

            if (reuseTask == null) {
                r.setTask(targetStack.createTaskRecord(mSupervisor.getNextTaskId(),
                        newTaskInfo != null ? newTaskInfo : r.info,
                        newTaskIntent != null ? newTaskIntent : intent,
                        voiceSession, voiceInteractor, !launchTaskBehind /* toTop */),
                        taskToAffiliate);
                if (overrideBounds) {
                    r.task.updateOverrideConfiguration(newBounds);
                }
                if (DEBUG_TASKS) Slog.v(TAG_TASKS,
                        "Starting new activity " + r + " in new task " + r.task);
            } else {
                r.setTask(reuseTask, taskToAffiliate);
            }
            if (mSupervisor.isLockTaskModeViolation(r.task)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
                return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
            if (!movedHome) {
                if ((launchFlags &
                        (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME))
                        == (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_TASK_ON_HOME)) {
                    // Caller wants to appear on home activity, so before starting
                    // their own activity we will bring home to the front.
                    r.task.setTaskToReturnTo(HOME_ACTIVITY_TYPE);
                }
            }
        } else if (sourceRecord != null) {
            final TaskRecord sourceTask = sourceRecord.task;
            if (mSupervisor.isLockTaskModeViolation(sourceTask)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
                return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
            targetStack = null;
            if (sourceTask.stack.topTask() != sourceTask) {
                // We only want to allow changing stack if the target task is not the top one,
                // otherwise we would move the launching task to the other side, rather than show
                // two side by side.
                targetStack = getLaunchToSideStack(r, launchFlags, r.task);
            }
            if (targetStack == null) {
                targetStack = sourceTask.stack;
            } else if (targetStack != sourceTask.stack) {
                mSupervisor.moveTaskToStackLocked(sourceTask.taskId, targetStack.mStackId,
                        ON_TOP, FORCE_FOCUS, "launchToSide", !ANIMATE);
            }
            if (doResume) {
                targetStack.moveToFront("sourceStackToFront");
            }
            final TaskRecord topTask = targetStack.topTask();
            if (topTask != sourceTask) {
                targetStack.moveTaskToFrontLocked(sourceTask, noAnimation, options,
                        r.appTimeTracker, "sourceTaskToFront");
            }
            if (!addingToTask && (launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                // In this case, we are adding the activity to an existing
                // task, but the caller has asked to clear that task if the
                // activity is already running.
                ActivityRecord top = sourceTask.performClearTaskLocked(r, launchFlags);
                keepCurTransition = true;
                if (top != null) {
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                    top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                    // For paranoia, make sure we have correctly
                    // resumed the top activity.
                    targetStack.mLastPausedActivity = null;
                    if (doResume) {
                        targetStack.resumeTopActivityLocked(null);
                    }
                    ActivityOptions.abort(options);
                    return ActivityManager.START_DELIVERED_TO_TOP;
                }
            } else if (!addingToTask &&
                    (launchFlags&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
                // In this case, we are launching an activity in our own task
                // that may already be running somewhere in the history, and
                // we want to shuffle it to the front of the stack if so.
                final ActivityRecord top = sourceTask.findActivityInHistoryLocked(r);
                if (top != null) {
                    final TaskRecord task = top.task;
                    task.moveActivityToFrontLocked(top);
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, r, task);
                    top.updateOptionsLocked(options);
                    top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                    targetStack.mLastPausedActivity = null;
                    if (doResume) {
                        targetStack.resumeTopActivityLocked(null);
                    }
                    return ActivityManager.START_DELIVERED_TO_TOP;
                }
            }
            // An existing activity is starting this new activity, so we want
            // to keep the new one in the same task as the one that is starting
            // it.
            r.setTask(sourceTask, null);
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                    + " in existing task " + r.task + " from source " + sourceRecord);

        } else if (inTask != null) {
            // The caller is asking that the new activity be started in an explicit
            // task it has provided to us.
            if (mSupervisor.isLockTaskModeViolation(inTask)) {
                Slog.e(TAG, "Attempted Lock Task Mode violation r=" + r);
                return ActivityManager.START_RETURN_LOCK_TASK_MODE_VIOLATION;
            }
            if (overrideBounds) {
                inTask.updateOverrideConfiguration(newBounds);
                int stackId = inTask.getLaunchStackId();
                if (stackId != inTask.stack.mStackId) {
                    mSupervisor.moveTaskToStackUncheckedLocked(
                            inTask, stackId, ON_TOP, !FORCE_FOCUS, "inTaskToFront");
                }
            }
            targetStack = inTask.stack;
            targetStack.moveTaskToFrontLocked(inTask, noAnimation, options, r.appTimeTracker,
                    "inTaskToFront");

            // Check whether we should actually launch the new activity in to the task,
            // or just reuse the current activity on top.
            ActivityRecord top = inTask.getTopActivity();
            if (top != null && top.realActivity.equals(r.realActivity) && top.userId == r.userId) {
                if ((launchFlags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                        || launchSingleTop || launchSingleTask) {
                    ActivityStack.logStartActivity(EventLogTags.AM_NEW_INTENT, top, top.task);
                    if ((startFlags&ActivityManager.START_FLAG_ONLY_IF_NEEDED) != 0) {
                        // We don't need to start a new activity, and
                        // the client said not to do anything if that
                        // is the case, so this is it!
                        return ActivityManager.START_RETURN_INTENT_TO_CALLER;
                    }
                    top.deliverNewIntentLocked(callingUid, r.intent, r.launchedFromPackage);
                    return ActivityManager.START_DELIVERED_TO_TOP;
                }
            }

            if (!addingToTask) {
                // We don't actually want to have this activity added to the task, so just
                // stop here but still tell the caller that we consumed the intent.
                ActivityOptions.abort(options);
                return ActivityManager.START_TASK_TO_FRONT;
            }

            r.setTask(inTask, null);
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                    + " in explicit task " + r.task);

        } else {
            // This not being started from an existing activity, and not part
            // of a new task...  just put it in the top task, though these days
            // this case should never happen.
            targetStack = computeStackFocus(r, newTask, null /* bounds */, launchFlags);
            if (doResume) {
                targetStack.moveToFront("addingToTopTask");
            }
            ActivityRecord prev = targetStack.topActivity();
            r.setTask(prev != null ? prev.task : targetStack.createTaskRecord(
                    mSupervisor.getNextTaskId(), r.info, intent, null, null, true), null);
            mWindowManager.moveTaskToTop(r.task.taskId);
            if (DEBUG_TASKS) Slog.v(TAG_TASKS, "Starting new activity " + r
                    + " in new guessed " + r.task);
        }

        mService.grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                intent, r.getUriPermissionsLocked(), r.userId);

        if (sourceRecord != null && sourceRecord.isRecentsActivity()) {
            r.task.setTaskToReturnTo(RECENTS_ACTIVITY_TYPE);
        }
        if (newTask) {
            EventLog.writeEvent(EventLogTags.AM_CREATE_TASK, r.userId, r.task.taskId);
        }
        ActivityStack.logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, r, r.task);
        targetStack.mLastPausedActivity = null;
        targetStack.startActivityLocked(r, newTask, keepCurTransition, options);
        if (doResume) {
            if (!launchTaskBehind) {
                mService.setFocusedActivityLocked(r, "startedActivity");
            }
            mSupervisor.resumeTopActivitiesLocked(targetStack, r, options);
        } else {
            targetStack.addRecentActivityLocked(r);
        }
        mSupervisor.updateUserStackLocked(r.userId, targetStack);

        if (!r.task.mResizeable && mSupervisor.isStackDockedInEffect(targetStack.mStackId)) {
            mSupervisor.showNonResizeableDockToast(r.task.taskId);
        }

        return ActivityManager.START_SUCCESS;
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

    final int startActivities(IApplicationThread caller, int callingUid, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo,
            Bundle bOptions, int userId) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
        }


        int callingPid;
        if (callingUid >= 0) {
            callingPid = -1;
        } else if (caller == null) {
            callingPid = Binder.getCallingPid();
            callingUid = Binder.getCallingUid();
        } else {
            callingPid = callingUid = -1;
        }
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
                            callingPid, callingUid, callingPackage, callingPid, callingUid, 0,
                            options, false, componentSpecified, outActivity, null, null);
                    if (res < 0) {
                        return res;
                    }

                    resultTo = outActivity[0] != null ? outActivity[0].appToken : null;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return ActivityManager.START_SUCCESS;
    }

    final void doPendingActivityLaunchesLocked(boolean doResume) {
        while (!mPendingActivityLaunches.isEmpty()) {
            PendingActivityLaunch pal = mPendingActivityLaunches.remove(0);

            try {
                startActivityUncheckedLocked(pal.r, pal.sourceRecord, null, null,
                        pal.startFlags, doResume && mPendingActivityLaunches.isEmpty(),
                        null, null);
            } catch (Exception e) {
                Slog.e(TAG, "Exception during pending activity launch pal=" + pal, e);
                pal.sendErrorResult(e.getMessage());
            }
        }
    }

    private ActivityStack computeStackFocus(ActivityRecord r, boolean newTask, Rect bounds,
            int launchFlags) {
        final TaskRecord task = r.task;
        if (!(r.isApplicationActivity() || (task != null && task.isApplicationTask()))) {
            return mSupervisor.mHomeStack;
        }

        ActivityStack stack = getLaunchToSideStack(r, launchFlags, task);
        if (stack != null) {
            return stack;
        }

        if (task != null && task.stack != null) {
            stack = task.stack;
            if (stack.isOnHomeDisplay()) {
                if (mSupervisor.mFocusedStack != stack) {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                            "computeStackFocus: Setting " + "focused stack to r=" + r
                                    + " task=" + task);
                } else {
                    if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                            "computeStackFocus: Focused stack already="
                                    + mSupervisor.mFocusedStack);
                }
            }
            return stack;
        }

        final ActivityStackSupervisor.ActivityContainer container = r.mInitialActivityContainer;
        if (container != null) {
            // The first time put it on the desired stack, after this put on task stack.
            r.mInitialActivityContainer = null;
            return container.mStack;
        }

        // The fullscreen stack can contain any task regardless of if the task is resizeable
        // or not. So, we let the task go in the fullscreen task if it is the focus stack.
        // If the freeform or docked stack has focus, and the activity to be launched is resizeable,
        // we can also put it in the focused stack.
        final int focusedStackId = mSupervisor.mFocusedStack.mStackId;
        final boolean canUseFocusedStack =
                focusedStackId == FULLSCREEN_WORKSPACE_STACK_ID
                        || focusedStackId == DOCKED_STACK_ID
                        || (focusedStackId == FREEFORM_WORKSPACE_STACK_ID && r.info.resizeable);
        if (canUseFocusedStack && (!newTask
                || mSupervisor.mFocusedStack.mActivityContainer.isEligibleForNewTasks())) {
            if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                    "computeStackFocus: Have a focused stack=" + mSupervisor.mFocusedStack);
            return mSupervisor.mFocusedStack;
        }

        // We first try to put the task in the first dynamic stack.
        final ArrayList<ActivityStack> homeDisplayStacks = mSupervisor.mHomeStack.mStacks;
        for (int stackNdx = homeDisplayStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            stack = homeDisplayStacks.get(stackNdx);
            if (!ActivityManager.StackId.isStaticStack(stack.mStackId)) {
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
        if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS, "computeStackFocus: New stack r="
                + r + " stackId=" + stack.mStackId);
        return stack;
    }

    private ActivityStack getLaunchToSideStack(ActivityRecord r, int launchFlags, TaskRecord task) {
        if ((launchFlags & FLAG_ACTIVITY_LAUNCH_TO_SIDE) == 0) {
            return null;
        }
        // The parent activity doesn't want to launch the activity on top of itself, but
        // instead tries to put it onto other side in side-by-side mode.
        final ActivityStack parentStack = task != null ? task.stack
                : r.mInitialActivityContainer != null ? r.mInitialActivityContainer.mStack
                : mSupervisor.mFocusedStack;
        if (parentStack != null && parentStack.mStackId == DOCKED_STACK_ID) {
            // If parent was in docked stack, the natural place to launch another activity
            // will be fullscreen, so it can appear alongside the docked window.
            return mSupervisor.getStack(FULLSCREEN_WORKSPACE_STACK_ID, CREATE_IF_NEEDED, ON_TOP);
        } else {
            // If the parent is not in the docked stack, we check if there is docked window
            // and if yes, we will launch into that stack. If not, we just put the new
            // activity into parent's stack, because we can't find a better place.
            final ActivityStack stack = mSupervisor.getStack(DOCKED_STACK_ID);
            if (stack != null && !stack.isStackVisibleLocked()) {
                // There is a docked stack, but it isn't visible, so we can't launch into that.
                return null;
            } else {
                return stack;
            }
        }
    }

    /**
     * Decide whether the new activity should be inserted into an existing task. Returns null if not
     * or an ActivityRecord with the task into which the new activity should be added.
     */
    private ActivityRecord getReusableIntentActivity(ActivityRecord r, TaskRecord inTask,
            Intent intent, boolean launchSingleInstance, boolean launchSingleTask,
            int launchFlags) {
        // We may want to try to place the new activity in to an existing task.  We always
        // do this if the target activity is singleTask or singleInstance; we will also do
        // this if NEW_TASK has been requested, and there is not an additional qualifier telling
        // us to still place it in a new task: multi task, always doc mode, or being asked to
        // launch this as a new task behind the current one.
        boolean putIntoExistingTask = ((launchFlags & FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (launchFlags & FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || launchSingleInstance || launchSingleTask;
        // If bring to front is requested, and no result is requested and we have not
        // been given an explicit task to launch in to, and
        // we can find a task that was started with this same
        // component, then instead of launching bring that one to the front.
        putIntoExistingTask &= inTask == null && r.resultTo == null;
        ActivityRecord intentActivity = null;
        if (putIntoExistingTask) {
            // See if there is a task to bring to the front.  If this is
            // a SINGLE_INSTANCE activity, there can be one and only one
            // instance of it in the history, and it is always in its own
            // unique task, so we do a special search.
            intentActivity = launchSingleInstance ? mSupervisor.findActivityLocked(intent, r.info)
                    : mSupervisor.findTaskLocked(r);
        }
        return intentActivity;
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
}
