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

package com.android.server.am;

import static android.app.ActivityManager.START_SUCCESS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.ALLOW_FULL_ONLY;

import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Bundle;
import android.os.FactoryTest;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.service.voice.IVoiceInteractionSession;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.am.ActivityStackSupervisor.PendingActivityLaunch;
import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.ActivityStarter.DefaultFactory;
import com.android.server.am.ActivityStarter.Factory;

import java.io.PrintWriter;
import java.util.ArrayList;
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
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStartController" : TAG_AM;

    private static final int DO_PENDING_ACTIVITY_LAUNCHES_MSG = 1;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mSupervisor;
    private final ActivityStartInterceptor mInterceptor;

    /** Last home activity record we attempted to start. */
    private ActivityRecord mLastHomeActivityStartRecord;

    /** Temporary array to capture start activity results */
    private ActivityRecord[] tmpOutRecord = new ActivityRecord[1];

    /**The result of the last home activity we attempted to start. */
    private int mLastHomeActivityStartResult;

    /** A list of activities that are waiting to launch. */
    private final ArrayList<ActivityStackSupervisor.PendingActivityLaunch>
            mPendingActivityLaunches = new ArrayList<>();

    private final Factory mFactory;

    private final Handler mHandler;

    private final class StartHandler extends Handler {
        public StartHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case DO_PENDING_ACTIVITY_LAUNCHES_MSG:
                    synchronized (mService) {
                        doPendingActivityLaunches(true);
                    }
                    break;
            }
        }
    }

    /**
     * TODO(b/64750076): Capture information necessary for dump and
     * {@link #postStartActivityProcessingForLastStarter} rather than keeping the entire object
     * around */
    private ActivityStarter mLastStarter;

    ActivityStartController(ActivityManagerService service) {
        this(service, service.mStackSupervisor, new DefaultFactory());
    }

    @VisibleForTesting
    ActivityStartController(ActivityManagerService service, ActivityStackSupervisor supervisor,
            Factory factory) {
        mService = service;
        mSupervisor = supervisor;
        mHandler = new StartHandler(mService.mHandlerThread.getLooper());
        mInterceptor = new ActivityStartInterceptor(mService, mSupervisor);
        mFactory = factory;
    }

    /**
     * Retrieves a starter to be used for a new start request. The starter will be added to the
     * active starters list.
     *
     * TODO(b/64750076): This should be removed when {@link #obtainStarter} is implemented. At that
     * time, {@link ActivityStarter#execute} will be able to handle cleaning up the starter's
     * internal references.
     */
    private ActivityStarter createStarter() {
        mLastStarter = mFactory.getStarter(this, mService, mService.mStackSupervisor, mInterceptor);
        return mLastStarter;
    }

    /**
     * TODO(b/64750076): Remove once we directly expose starter interface to callers through
     * {@link #obtainStarter}.
     */
    int startActivity(IApplicationThread caller, Intent intent, Intent ephemeralIntent,
            String resolvedType, ActivityInfo aInfo, ResolveInfo rInfo,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int callingPid, int callingUid,
            String callingPackage, int realCallingPid, int realCallingUid, int startFlags,
            ActivityOptions options, boolean ignoreTargetSecurity, boolean componentSpecified,
            ActivityRecord[] outActivity, TaskRecord inTask, String reason) {
        return createStarter().startActivityLocked(caller, intent, ephemeralIntent, resolvedType,
                aInfo, rInfo, voiceSession, voiceInteractor, resultTo, resultWho, requestCode,
                callingPid, callingUid, callingPackage, realCallingPid, realCallingUid, startFlags,
                options, ignoreTargetSecurity, componentSpecified, outActivity, inTask, reason);
    }

    /**
     * TODO(b/64750076): usage of this doesn't seem right. We're making decisions based off the
     * last starter for an arbitrary task record. Re-evaluate whether we can remove.
     */
    void postStartActivityProcessingForLastStarter(ActivityRecord r, int result,
            ActivityStack targetStack) {
        mLastStarter.postStartActivityProcessing(r, result, targetStack);
    }

    void startHomeActivity(Intent intent, ActivityInfo aInfo, String reason) {
        mSupervisor.moveHomeStackTaskToTop(reason);

        final ActivityStarter starter = createStarter();

        mLastHomeActivityStartResult = starter.startActivityLocked(null /*caller*/, intent,
                null /*ephemeralIntent*/, null /*resolvedType*/, aInfo, null /*rInfo*/,
                null /*voiceSession*/, null /*voiceInteractor*/, null /*resultTo*/,
                null /*resultWho*/, 0 /*requestCode*/, 0 /*callingPid*/, 0 /*callingUid*/,
                null /*callingPackage*/, 0 /*realCallingPid*/, 0 /*realCallingUid*/,
                0 /*startFlags*/, null /*options*/, false /*ignoreTargetSecurity*/,
                false /*componentSpecified*/, tmpOutRecord, null /*inTask*/,
                "startHomeActivity: " + reason);
        mLastHomeActivityStartRecord = tmpOutRecord[0];

        if (mSupervisor.inResumeTopActivity) {
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
        if (mService.getCheckedForSetup()) {
            return;
        }

        // We will show this screen if the current one is a different
        // version than the last one shown, and we are not running in
        // low-level factory test mode.
        final ContentResolver resolver = mService.mContext.getContentResolver();
        if (mService.mFactoryTest != FactoryTest.FACTORY_TEST_LOW_LEVEL &&
                Settings.Global.getInt(resolver,
                        Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            mService.setCheckedForSetup(true);

            // See if we should be showing the platform update setup UI.
            final Intent intent = new Intent(Intent.ACTION_UPGRADE_SETUP);
            final List<ResolveInfo> ris = mService.mContext.getPackageManager()
                    .queryIntentActivities(intent,
                            PackageManager.MATCH_SYSTEM_ONLY | PackageManager.GET_META_DATA);
            if (!ris.isEmpty()) {
                final ResolveInfo ri = ris.get(0);
                String vers = ri.activityInfo.metaData != null
                        ? ri.activityInfo.metaData.getString(Intent.METADATA_SETUP_VERSION)
                        : null;
                if (vers == null && ri.activityInfo.applicationInfo.metaData != null) {
                    vers = ri.activityInfo.applicationInfo.metaData.getString(
                            Intent.METADATA_SETUP_VERSION);
                }
                String lastVers = Settings.Secure.getString(
                        resolver, Settings.Secure.LAST_SETUP_SHOWN);
                if (vers != null && !vers.equals(lastVers)) {
                    intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(new ComponentName(
                            ri.activityInfo.packageName, ri.activityInfo.name));
                    startActivity(null, intent, null /*ephemeralIntent*/, null, ri.activityInfo,
                            null /*rInfo*/, null, null, null, null, 0, 0, 0, null, 0, 0, 0, null,
                            false, false, null, null, "startSetupActivity");
                }
            }
        }
    }

    final int startActivityInPackage(int uid, String callingPackage,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, int startFlags, Bundle bOptions, int userId,
            TaskRecord inTask, String reason) {

        userId = mService.mUserController.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, ALLOW_FULL_ONLY, "startActivityInPackage",
                null);

        // TODO: Switch to user app stacks here.
        return startActivityMayWait(null, uid, callingPackage,
                intent, resolvedType, null, null, resultTo, resultWho, requestCode, startFlags,
                null, null, null, bOptions, false, userId, inTask, reason);
    }

    final int startActivitiesInPackage(int uid, String callingPackage, Intent[] intents,
            String[] resolvedTypes, IBinder resultTo, Bundle bOptions, int userId) {
        final String reason = "startActivityInPackage";
        userId = mService.mUserController.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, ALLOW_FULL_ONLY, reason, null);
        // TODO: Switch to user app stacks here.
        int ret = startActivities(null, uid, callingPackage, intents, resolvedTypes, resultTo,
                bOptions, userId, reason);
        return ret;
    }

    /**
     * TODO(b/64750076): Remove once we directly expose starter interface to callers through
     * {@link #obtainStarter}.
     */
    int startActivityMayWait(IApplicationThread caller, int callingUid,
            String callingPackage, Intent intent, String resolvedType,
            IVoiceInteractionSession voiceSession, IVoiceInteractor voiceInteractor,
            IBinder resultTo, String resultWho, int requestCode, int startFlags,
            ProfilerInfo profilerInfo, WaitResult outResult,
            Configuration globalConfig, Bundle bOptions, boolean ignoreTargetSecurity, int userId,
            TaskRecord inTask, String reason) {
        return createStarter().startActivityMayWait(caller, callingUid, callingPackage, intent,
                resolvedType, voiceSession, voiceInteractor, resultTo, resultWho, requestCode,
                startFlags, profilerInfo, outResult, globalConfig, bOptions,
                ignoreTargetSecurity,
                userId, inTask, reason);
    }

    int startActivities(IApplicationThread caller, int callingUid, String callingPackage,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo, Bundle bOptions, int userId,
            String reason) {
        if (intents == null) {
            throw new NullPointerException("intents is null");
        }
        if (resolvedTypes == null) {
            throw new NullPointerException("resolvedTypes is null");
        }
        if (intents.length != resolvedTypes.length) {
            throw new IllegalArgumentException("intents are length different than resolvedTypes");
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
        final long origId = Binder.clearCallingIdentity();
        try {
            synchronized (mService) {
                ActivityRecord[] outActivity = new ActivityRecord[1];
                for (int i=0; i < intents.length; i++) {
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
                    int res = startActivity(caller, intent, null /*ephemeralIntent*/,
                            resolvedTypes[i], aInfo, null /*rInfo*/, null, null, resultTo, null, -1,
                            callingPid, callingUid, callingPackage,
                            realCallingPid, realCallingUid, 0,
                            options, false, componentSpecified, outActivity, null, reason);
                    if (res < 0) {
                        return res;
                    }

                    resultTo = outActivity[0] != null ? outActivity[0].appToken : null;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }

        return START_SUCCESS;
    }

    void schedulePendingActivityLaunches(long delayMs) {
        mHandler.removeMessages(DO_PENDING_ACTIVITY_LAUNCHES_MSG);
        Message msg = mHandler.obtainMessage(DO_PENDING_ACTIVITY_LAUNCHES_MSG);
        mHandler.sendMessageDelayed(msg, delayMs);
    }

    void doPendingActivityLaunches(boolean doResume) {
        while (!mPendingActivityLaunches.isEmpty()) {
            final PendingActivityLaunch pal = mPendingActivityLaunches.remove(0);
            final boolean resume = doResume && mPendingActivityLaunches.isEmpty();
            final ActivityStarter starter = createStarter();
            try {
                starter.startActivity(pal.r, pal.sourceRecord, null, null, pal.startFlags, resume,
                        null, null, null /*outRecords*/);
            } catch (Exception e) {
                Slog.e(TAG, "Exception during pending activity launch pal=" + pal, e);
                pal.sendErrorResult(e.getMessage());
            }
        }
    }

    void addPendingActivityLaunch(PendingActivityLaunch launch) {
        mPendingActivityLaunches.add(launch);
    }

    boolean clearPendingActivityLaunches(String packageName) {
        final int pendingLaunches = mPendingActivityLaunches.size();

        for (int palNdx = pendingLaunches - 1; palNdx >= 0; --palNdx) {
            final PendingActivityLaunch pal = mPendingActivityLaunches.get(palNdx);
            final ActivityRecord r = pal.r;
            if (r != null && r.packageName.equals(packageName)) {
                mPendingActivityLaunches.remove(palNdx);
            }
        }
        return mPendingActivityLaunches.size() < pendingLaunches;
    }

    void dump(PrintWriter pw, String prefix, String dumpPackage) {
        pw.print(prefix);
        pw.print("mLastHomeActivityStartResult=");
        pw.println(mLastHomeActivityStartResult);

        if (mLastHomeActivityStartRecord != null) {
            pw.print(prefix);
            pw.println("mLastHomeActivityStartRecord:");
            mLastHomeActivityStartRecord.dump(pw, prefix + "  ");
        }

        final boolean dumpPackagePresent = dumpPackage != null;

        if (mLastStarter != null) {
            final boolean dump = !dumpPackagePresent
                    || mLastStarter.relatedToPackage(dumpPackage)
                    || (mLastHomeActivityStartRecord != null
                            && dumpPackage.equals(mLastHomeActivityStartRecord.packageName));

            if (dump) {
                pw.print(prefix);
                mLastStarter.dump(pw, prefix + "  ");

                if (dumpPackagePresent) {
                    return;
                }
            }
        }

        if (dumpPackagePresent) {
            pw.print(prefix);
            pw.println("(nothing)");
        }
    }
}
