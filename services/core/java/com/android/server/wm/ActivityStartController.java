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

import static android.app.ActivityManager.START_SUCCESS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.os.FactoryTest.FACTORY_TEST_LOW_LEVEL;

import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.RemoteAnimationAdapter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.PendingIntentRecord;
import com.android.server.wm.ActivityStackSupervisor.PendingActivityLaunch;
import com.android.server.wm.ActivityStarter.DefaultFactory;
import com.android.server.wm.ActivityStarter.Factory;

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
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ActivityStartController" : TAG_ATM;

    private static final int DO_PENDING_ACTIVITY_LAUNCHES_MSG = 1;

    private final ActivityTaskManagerService mService;
    private final ActivityStackSupervisor mSupervisor;

    /** Last home activity record we attempted to start. */
    private ActivityRecord mLastHomeActivityStartRecord;

    /** Temporary array to capture start activity results */
    private ActivityRecord[] tmpOutRecord = new ActivityRecord[1];

    /** The result of the last home activity we attempted to start. */
    private int mLastHomeActivityStartResult;

    /** A list of activities that are waiting to launch. */
    private final ArrayList<ActivityStackSupervisor.PendingActivityLaunch>
            mPendingActivityLaunches = new ArrayList<>();

    private final Factory mFactory;

    private final Handler mHandler;

    private final PendingRemoteAnimationRegistry mPendingRemoteAnimationRegistry;

    boolean mCheckedForSetup = false;

    private final class StartHandler extends Handler {
        public StartHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case DO_PENDING_ACTIVITY_LAUNCHES_MSG:
                    synchronized (mService.mGlobalLock) {
                        doPendingActivityLaunches(true);
                    }
                    break;
            }
        }
    }

    /**
     * TODO(b/64750076): Capture information necessary for dump and
     * {@link #postStartActivityProcessingForLastStarter} rather than keeping the entire object
     * around
     */
    private ActivityStarter mLastStarter;

    ActivityStartController(ActivityTaskManagerService service) {
        this(service, service.mStackSupervisor,
                new DefaultFactory(service, service.mStackSupervisor,
                    new ActivityStartInterceptor(service, service.mStackSupervisor)));
    }

    @VisibleForTesting
    ActivityStartController(ActivityTaskManagerService service, ActivityStackSupervisor supervisor,
            Factory factory) {
        mService = service;
        mSupervisor = supervisor;
        mHandler = new StartHandler(mService.mH.getLooper());
        mFactory = factory;
        mFactory.setController(this);
        mPendingRemoteAnimationRegistry = new PendingRemoteAnimationRegistry(service.mGlobalLock,
                service.mH);
    }

    /**
     * @return A starter to configure and execute starting an activity. It is valid until after
     *         {@link ActivityStarter#execute} is invoked. At that point, the starter should be
     *         considered invalid and no longer modified or used.
     */
    ActivityStarter obtainStarter(Intent intent, String reason) {
        return mFactory.obtain().setIntent(intent).setReason(reason);
    }

    void onExecutionComplete(ActivityStarter starter) {
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
            ActivityStack targetStack) {
        if (mLastStarter == null) {
            return;
        }

        mLastStarter.postStartActivityProcessing(r, result, targetStack);
    }

    void startHomeActivity(Intent intent, ActivityInfo aInfo, String reason,
            TaskDisplayArea taskDisplayArea) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
        if (!ActivityRecord.isResolverActivity(aInfo.name)) {
            // The resolver activity shouldn't be put in home stack because when the foreground is
            // standard type activity, the resolver activity should be put on the top of current
            // foreground instead of bring home stack to front.
            options.setLaunchActivityType(ACTIVITY_TYPE_HOME);
        }
        final int displayId = taskDisplayArea.getDisplayId();
        options.setLaunchDisplayId(displayId);
        options.setLaunchTaskDisplayArea(taskDisplayArea.mRemoteToken
                .toWindowContainerToken());

        // The home activity will be started later, defer resuming to avoid unneccerary operations
        // (e.g. start home recursively) when creating home stack.
        mSupervisor.beginDeferResume();
        final ActivityStack homeStack;
        try {
            // Make sure home stack exists on display area.
            // TODO(b/153624902): Replace with TaskDisplayArea#getOrCreateRootHomeTask()
            homeStack = taskDisplayArea.getOrCreateStack(WINDOWING_MODE_UNDEFINED,
                    ACTIVITY_TYPE_HOME, ON_TOP);
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
        if (homeStack.mInResumeTopActivity) {
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
                String lastVers = Settings.Secure.getString(
                        resolver, Settings.Secure.LAST_SETUP_SHOWN);
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
            boolean allowBackgroundActivityStart) {

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
                .setAllowBackgroundActivityStart(allowBackgroundActivityStart)
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
            PendingIntentRecord originatingPendingIntent, boolean allowBackgroundActivityStart) {
        return startActivitiesInPackage(uid, 0 /* realCallingPid */, -1 /* realCallingUid */,
                callingPackage, callingFeatureId, intents, resolvedTypes, resultTo, options, userId,
                validateIncomingUser, originatingPendingIntent, allowBackgroundActivityStart);
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
            boolean allowBackgroundActivityStart) {

        final String reason = "startActivityInPackage";

        userId = checkTargetUser(userId, validateIncomingUser, Binder.getCallingPid(),
                Binder.getCallingUid(), reason);

        // TODO: Switch to user app stacks here.
        return startActivities(null, uid, realCallingPid, realCallingUid, callingPackage,
                callingFeatureId, intents, resolvedTypes, resultTo, options, userId, reason,
                originatingPendingIntent, allowBackgroundActivityStart);
    }

    int startActivities(IApplicationThread caller, int callingUid, int incomingRealCallingPid,
            int incomingRealCallingUid, String callingPackage, @Nullable String callingFeatureId,
            Intent[] intents, String[] resolvedTypes, IBinder resultTo, SafeActivityOptions options,
            int userId, String reason, PendingIntentRecord originatingPendingIntent,
            boolean allowBackgroundActivityStart) {
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
        try {
            intents = ArrayUtils.filterNotNull(intents, Intent[]::new);
            final ActivityStarter[] starters = new ActivityStarter[intents.length];
            // Do not hold WM global lock on this loop because when resolving intent, it may
            // potentially acquire activity manager lock that leads to deadlock.
            for (int i = 0; i < intents.length; i++) {
                Intent intent = intents[i];

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
                        0 /* startFlags */, null /* profilerInfo */, userId, filterCallingUid);
                aInfo = mService.mAmInternal.getActivityInfoForUser(aInfo, userId);

                if (aInfo != null) {
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
                        : null;
                starters[i] = obtainStarter(intent, reason)
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
                        .setAllowBackgroundActivityStart(allowBackgroundActivityStart);
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
                        // Only the started activity which has the same uid as the source caller can
                        // be the caller of next activity.
                        resultTo = started.appToken;
                    } else {
                        resultTo = sourceResultTo;
                        // Different apps not adjacent to the caller are forced to be new task.
                        if (i < starters.length - 1) {
                            starters[i + 1].getIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        }
                    }
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
            final ActivityStarter starter = obtainStarter(null /* intent */,
                    "pendingActivityLaunch");
            try {
                starter.startResolvedActivity(pal.r, pal.sourceRecord, null, null, pal.startFlags,
                        resume, pal.r.pendingOptions, null);
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

    void registerRemoteAnimationForNextActivityStart(String packageName,
            RemoteAnimationAdapter adapter) {
        mPendingRemoteAnimationRegistry.addPendingAnimation(packageName, adapter);
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
            if (!dumped) {
                dumped = true;
                dumpLastHomeActivityStartResult(pw, prefix);
            }
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

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        for (PendingActivityLaunch activity: mPendingActivityLaunches) {
            activity.r.writeIdentifierToProto(proto, fieldId);
        }
    }
}
