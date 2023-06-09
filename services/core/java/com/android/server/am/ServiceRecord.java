/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.app.ActivityManager.PROCESS_STATE_NONEXISTENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;
import static android.app.ProcessMemoryState.HOSTING_COMPONENT_TYPE_BOUND_SERVICE;
import static android.os.PowerExemptionManager.REASON_DENIED;
import static android.os.Process.INVALID_UID;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FOREGROUND_SERVICE;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.BackgroundStartPrivileges;
import android.app.IApplicationThread;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteServiceException.CannotPostForegroundServiceNotificationException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerExemptionManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.procstats.ServiceState;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;
import com.android.server.uri.NeededUriGrants;
import com.android.server.uri.UriPermissionOwner;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A running application service.
 */
final class ServiceRecord extends Binder implements ComponentName.WithComponentName {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ServiceRecord" : TAG_AM;

    // Maximum number of delivery attempts before giving up.
    static final int MAX_DELIVERY_COUNT = 3;

    // Maximum number of times it can fail during execution before giving up.
    static final int MAX_DONE_EXECUTING_COUNT = 6;

    final ActivityManagerService ams;
    final ComponentName name; // service component.
    final ComponentName instanceName; // service component's per-instance name.
    final String shortInstanceName; // instanceName.flattenToShortString().
    final String definingPackageName;
                            // Can be different from appInfo.packageName for external services
    final int definingUid;
                            // Can be different from appInfo.uid for external services
    final Intent.FilterComparison intent;
                            // original intent used to find service.
    final ServiceInfo serviceInfo;
                            // all information about the service.
    ApplicationInfo appInfo;
                            // information about service's app.
    final int userId;       // user that this service is running as
    final String packageName; // the package implementing intent's component
    final String processName; // process where this component wants to run
    final String permission;// permission needed to access service
    final boolean exported; // from ServiceInfo.exported
    final Runnable restarter; // used to schedule retries of starting the service
    final long createRealTime;  // when this service was created
    final boolean isSdkSandbox; // whether this is a sdk sandbox service
    final int sdkSandboxClientAppUid; // the app uid for which this sdk sandbox service is running
    final String sdkSandboxClientAppPackage; // the app package for which this sdk sandbox service
                                             // is running
    final ArrayMap<Intent.FilterComparison, IntentBindRecord> bindings
            = new ArrayMap<Intent.FilterComparison, IntentBindRecord>();
                            // All active bindings to the service.
    private final ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections
            = new ArrayMap<IBinder, ArrayList<ConnectionRecord>>();
                            // IBinder -> ConnectionRecord of all bound clients

    ProcessRecord app;      // where this service is running or null.
    ProcessRecord isolationHostProc; // process which we've started for this service (used for
                                     // isolated and sdk sandbox processes)
    ServiceState tracker; // tracking service execution, may be null
    ServiceState restartTracker; // tracking service restart
    boolean allowlistManager; // any bindings to this service have BIND_ALLOW_WHITELIST_MANAGEMENT?
    boolean delayed;        // are we waiting to start this service in the background?
    boolean fgRequired;     // is the service required to go foreground after starting?
    boolean fgWaiting;      // is a timeout for going foreground already scheduled?
    boolean isNotAppComponentUsage; // is service binding not considered component/package usage?
    boolean isForeground;   // is service currently in foreground mode?
    boolean inSharedIsolatedProcess; // is the service in a shared isolated process
    int foregroundId;       // Notification ID of last foreground req.
    Notification foregroundNoti; // Notification record of foreground state.
    long fgDisplayTime;     // time at which the FGS notification should become visible
    int foregroundServiceType; // foreground service types.
    long lastActivity;      // last time there was some activity on the service.
    long startingBgTimeout;  // time at which we scheduled this for a delayed start.
    boolean startRequested; // someone explicitly called start?
    boolean delayedStop;    // service has been stopped but is in a delayed start?
    boolean stopIfKilled;   // last onStart() said to stop if service killed?
    boolean callStart;      // last onStart() has asked to always be called on restart.
    int startCommandResult; // last result from onStartCommand(), only for dumpsys.
    int executeNesting;     // number of outstanding operations keeping foreground.
    boolean executeFg;      // should we be executing in the foreground?
    long executingStart;    // start time of last execute request.
    boolean createdFromFg;  // was this service last created due to a foreground process call?
    int crashCount;         // number of times proc has crashed with service running
    int totalRestartCount;  // number of times we have had to restart.
    int restartCount;       // number of restarts performed in a row.
    long restartDelay;      // delay until next restart attempt.
    long restartTime;       // time of last restart.
    long nextRestartTime;   // time when restartDelay will expire.
    boolean destroying;     // set when we have started destroying the service
    long destroyTime;       // time at which destory was initiated.
    int pendingConnectionGroup;        // To be filled in to ProcessRecord once it connects
    int pendingConnectionImportance;   // To be filled in to ProcessRecord once it connects

    /**
     * The last time (in uptime timebase) a bind request was made with BIND_ALMOST_PERCEPTIBLE for
     * this service while on TOP.
     */
    long lastTopAlmostPerceptibleBindRequestUptimeMs;

    // any current binding to this service has BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS flag?
    private boolean mIsAllowedBgActivityStartsByBinding;
    // used to clean up the state of mIsAllowedBgActivityStartsByStart after a timeout
    private Runnable mCleanUpAllowBgActivityStartsByStartCallback;
    private ProcessRecord mAppForAllowingBgActivityStartsByStart;
    // These are the privileges that currently allow bg activity starts by service start.
    // Each time the contents of this list change #mBackgroundStartPrivilegesByStartMerged has to
    // be updated to reflect the merged state. The merged state retains the attribution to the
    // originating token only if it is the only cause for being privileged.
    @GuardedBy("ams")
    private ArrayList<BackgroundStartPrivileges> mBackgroundStartPrivilegesByStart =
            new ArrayList<>();

    // merged privileges for mBackgroundStartPrivilegesByStart (for performance)
    private BackgroundStartPrivileges mBackgroundStartPrivilegesByStartMerged =
            BackgroundStartPrivileges.NONE;

    // allow while-in-use permissions in foreground service or not.
    // while-in-use permissions in FGS started from background might be restricted.
    boolean mAllowWhileInUsePermissionInFgs;
    @PowerExemptionManager.ReasonCode
    int mAllowWhileInUsePermissionInFgsReason = REASON_DENIED;

    // A copy of mAllowWhileInUsePermissionInFgs's value when the service is entering FGS state.
    boolean mAllowWhileInUsePermissionInFgsAtEntering;
    /** Allow scheduling user-initiated jobs from the background. */
    boolean mAllowUiJobScheduling;

    // the most recent package that start/bind this service.
    String mRecentCallingPackage;
    // the most recent uid that start/bind this service.
    int mRecentCallingUid;
    // ApplicationInfo of the most recent callingPackage that start/bind this service.
    @Nullable ApplicationInfo mRecentCallerApplicationInfo;

    // The uptime when the service enters FGS state.
    long mFgsEnterTime = 0;
    // The uptime when the service exits FGS state.
    long mFgsExitTime = 0;
    // FGS notification is deferred.
    boolean mFgsNotificationDeferred;
    // FGS notification was deferred.
    boolean mFgsNotificationWasDeferred;
    // FGS notification was shown before the FGS finishes, or it wasn't deferred in the first place.
    boolean mFgsNotificationShown;
    // Whether FGS package has permissions to show notifications.
    boolean mFgsHasNotificationPermission;

    // allow the service becomes foreground service? Service started from background may not be
    // allowed to become a foreground service.
    @PowerExemptionManager.ReasonCode int mAllowStartForeground = REASON_DENIED;
    // A copy of mAllowStartForeground's value when the service is entering FGS state.
    @PowerExemptionManager.ReasonCode int mAllowStartForegroundAtEntering = REASON_DENIED;
    // Debug info why mAllowStartForeground is allowed or denied.
    String mInfoAllowStartForeground;
    // Debug info if mAllowStartForeground is allowed because of a temp-allowlist.
    ActivityManagerService.FgsTempAllowListItem mInfoTempFgsAllowListReason;
    // Is the same mInfoAllowStartForeground string has been logged before? Used for dedup.
    boolean mLoggedInfoAllowStartForeground;
    // The number of times Service.startForeground() is called, after this service record is
    // created. (i.e. due to "bound" or "start".) It never decreases, even when stopForeground()
    // is called.
    int mStartForegroundCount;

    // This is a service record of a FGS delegate (not a service record of a real service)
    boolean mIsFgsDelegate;
    @Nullable ForegroundServiceDelegation mFgsDelegation;

    String stringName;      // caching of toString

    private int lastStartId;    // identifier of most recent start request.

    boolean mKeepWarming; // Whether or not it'll keep critical code path of the host warm

    /**
     * The original earliest restart time, which considers the number of crashes, etc.,
     * but doesn't include the extra delays we put in between to scatter the restarts;
     * it's the earliest time this auto service restart could happen alone(except those
     * batch restarts which happens at time of process attach).
     */
    long mEarliestRestartTime;

    /**
     * The original time when the service start is scheduled, it does NOT include the reschedules.
     *
     * <p>The {@link #restartDelay} would be updated when its restart is rescheduled, but this field
     * won't, so it could be used when dumping how long the restart is delayed actually.</p>
     */
    long mRestartSchedulingTime;

    /**
     * The snapshot process state when the service is requested (either start or bind).
     */
    int mProcessStateOnRequest;

    static class StartItem {
        final ServiceRecord sr;
        final boolean taskRemoved;
        final int id;
        final int callingId;
        final String mCallingProcessName;
        final Intent intent;
        final NeededUriGrants neededGrants;
        final @Nullable String mCallingPackageName;
        final int mCallingProcessState;
        long deliveredTime;
        int deliveryCount;
        int doneExecutingCount;
        UriPermissionOwner uriPermissions;

        String stringName;      // caching of toString

        StartItem(ServiceRecord _sr, boolean _taskRemoved, int _id,
                Intent _intent, NeededUriGrants _neededGrants, int _callingId,
                String callingProcessName, @Nullable String callingPackageName,
                int callingProcessState) {
            sr = _sr;
            taskRemoved = _taskRemoved;
            id = _id;
            intent = _intent;
            neededGrants = _neededGrants;
            callingId = _callingId;
            mCallingProcessName = callingProcessName;
            mCallingPackageName = callingPackageName;
            mCallingProcessState = callingProcessState;
        }

        UriPermissionOwner getUriPermissionsLocked() {
            if (uriPermissions == null) {
                uriPermissions = new UriPermissionOwner(sr.ams.mUgmInternal, this);
            }
            return uriPermissions;
        }

        void removeUriPermissionsLocked() {
            if (uriPermissions != null) {
                uriPermissions.removeUriPermissions();
                uriPermissions = null;
            }
        }

        public void dumpDebug(ProtoOutputStream proto, long fieldId, long now) {
            long token = proto.start(fieldId);
            proto.write(ServiceRecordProto.StartItem.ID, id);
            ProtoUtils.toDuration(proto,
                    ServiceRecordProto.StartItem.DURATION, deliveredTime, now);
            proto.write(ServiceRecordProto.StartItem.DELIVERY_COUNT, deliveryCount);
            proto.write(ServiceRecordProto.StartItem.DONE_EXECUTING_COUNT, doneExecutingCount);
            if (intent != null) {
                intent.dumpDebug(proto, ServiceRecordProto.StartItem.INTENT, true, true,
                        true, false);
            }
            if (neededGrants != null) {
                neededGrants.dumpDebug(proto, ServiceRecordProto.StartItem.NEEDED_GRANTS);
            }
            if (uriPermissions != null) {
                uriPermissions.dumpDebug(proto, ServiceRecordProto.StartItem.URI_PERMISSIONS);
            }
            proto.end(token);
        }

        public String toString() {
            if (stringName != null) {
                return stringName;
            }
            StringBuilder sb = new StringBuilder(128);
            sb.append("ServiceRecord{")
                .append(Integer.toHexString(System.identityHashCode(sr)))
                .append(' ').append(sr.shortInstanceName)
                .append(" StartItem ")
                .append(Integer.toHexString(System.identityHashCode(this)))
                .append(" id=").append(id).append('}');
            return stringName = sb.toString();
        }
    }

    final ArrayList<StartItem> deliveredStarts = new ArrayList<StartItem>();
                            // start() arguments which been delivered.
    final ArrayList<StartItem> pendingStarts = new ArrayList<StartItem>();
                            // start() arguments that haven't yet been delivered.

    /**
     * Information specific to "SHORT_SERVICE" FGS.
     */
    class ShortFgsInfo {
        /** Time FGS started */
        private final long mStartTime;

        /**
         * Copied from {@link #mStartForegroundCount}. If this is different from the parent's,
         * that means this instance is stale.
         */
        private int mStartForegroundCount;

        /** Service's "start ID" when this short-service started. */
        private int mStartId;

        ShortFgsInfo(long startTime) {
            mStartTime = startTime;
            update();
        }

        /**
         * Update {@link #mStartForegroundCount} and {@link #mStartId}.
         * (but not {@link #mStartTime})
         */
        public void update() {
            this.mStartForegroundCount = ServiceRecord.this.mStartForegroundCount;
            this.mStartId = getLastStartId();
        }

        long getStartTime() {
            return mStartTime;
        }

        int getStartForegroundCount() {
            return mStartForegroundCount;
        }

        int getStartId() {
            return mStartId;
        }

        /**
         * @return whether this {@link ShortFgsInfo} is still "current" or not -- i.e.
         * it's "start foreground count" is the same as that of the ServiceRecord's.
         *
         * Note, we do _not_ check the "start id" here, because the start id increments if the
         * app calls startService() or startForegroundService() on the same service,
         * but that will _not_ update the ShortFgsInfo, and will not extend the timeout.
         */
        boolean isCurrent() {
            return this.mStartForegroundCount == ServiceRecord.this.mStartForegroundCount;
        }

        /** Time when Service.onTimeout() should be called */
        long getTimeoutTime() {
            return mStartTime + ams.mConstants.mShortFgsTimeoutDuration;
        }

        /** Time when the procstate should be lowered. */
        long getProcStateDemoteTime() {
            return mStartTime + ams.mConstants.mShortFgsTimeoutDuration
                    + ams.mConstants.mShortFgsProcStateExtraWaitDuration;
        }

        /** Time when the app should be declared ANR. */
        long getAnrTime() {
            return mStartTime + ams.mConstants.mShortFgsTimeoutDuration
                    + ams.mConstants.mShortFgsAnrExtraWaitDuration;
        }

        String getDescription() {
            return "sfc=" + this.mStartForegroundCount
                    + " sid=" + this.mStartId
                    + " stime=" + this.mStartTime
                    + " tt=" + this.getTimeoutTime()
                    + " dt=" + this.getProcStateDemoteTime()
                    + " at=" + this.getAnrTime();
        }
    }

    /**
     * Keep track of short-fgs specific information. This field gets cleared when the timeout
     * stops.
     */
    private ShortFgsInfo mShortFgsInfo;

    void dumpStartList(PrintWriter pw, String prefix, List<StartItem> list, long now) {
        final int N = list.size();
        for (int i=0; i<N; i++) {
            StartItem si = list.get(i);
            pw.print(prefix); pw.print("#"); pw.print(i);
                    pw.print(" id="); pw.print(si.id);
                    if (now != 0) {
                        pw.print(" dur=");
                        TimeUtils.formatDuration(si.deliveredTime, now, pw);
                    }
                    if (si.deliveryCount != 0) {
                        pw.print(" dc="); pw.print(si.deliveryCount);
                    }
                    if (si.doneExecutingCount != 0) {
                        pw.print(" dxc="); pw.print(si.doneExecutingCount);
                    }
                    pw.println("");
            pw.print(prefix); pw.print("  intent=");
                    if (si.intent != null) pw.println(si.intent.toString());
                    else pw.println("null");
            if (si.neededGrants != null) {
                pw.print(prefix); pw.print("  neededGrants=");
                        pw.println(si.neededGrants);
            }
            if (si.uriPermissions != null) {
                si.uriPermissions.dump(pw, prefix);
            }
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(ServiceRecordProto.SHORT_NAME, this.shortInstanceName);
        proto.write(ServiceRecordProto.IS_RUNNING, app != null);
        if (app != null) {
            proto.write(ServiceRecordProto.PID, app.getPid());
        }
        if (intent != null) {
            intent.getIntent().dumpDebug(proto, ServiceRecordProto.INTENT, false, true, false,
                    false);
        }
        proto.write(ServiceRecordProto.PACKAGE_NAME, packageName);
        proto.write(ServiceRecordProto.PROCESS_NAME, processName);
        proto.write(ServiceRecordProto.PERMISSION, permission);

        long now = SystemClock.uptimeMillis();
        long nowReal = SystemClock.elapsedRealtime();
        if (appInfo != null) {
            long appInfoToken = proto.start(ServiceRecordProto.APPINFO);
            proto.write(ServiceRecordProto.AppInfo.BASE_DIR, appInfo.sourceDir);
            if (!Objects.equals(appInfo.sourceDir, appInfo.publicSourceDir)) {
                proto.write(ServiceRecordProto.AppInfo.RES_DIR, appInfo.publicSourceDir);
            }
            proto.write(ServiceRecordProto.AppInfo.DATA_DIR, appInfo.dataDir);
            proto.end(appInfoToken);
        }
        if (app != null) {
            app.dumpDebug(proto, ServiceRecordProto.APP);
        }
        if (isolationHostProc != null) {
            isolationHostProc.dumpDebug(proto, ServiceRecordProto.ISOLATED_PROC);
        }
        proto.write(ServiceRecordProto.WHITELIST_MANAGER, allowlistManager);
        proto.write(ServiceRecordProto.DELAYED, delayed);
        if (isForeground || foregroundId != 0) {
            long fgToken = proto.start(ServiceRecordProto.FOREGROUND);
            proto.write(ServiceRecordProto.Foreground.ID, foregroundId);
            foregroundNoti.dumpDebug(proto, ServiceRecordProto.Foreground.NOTIFICATION);
            proto.write(ServiceRecordProto.Foreground.FOREGROUND_SERVICE_TYPE,
                    foregroundServiceType);
            proto.end(fgToken);
        }
        ProtoUtils.toDuration(proto, ServiceRecordProto.CREATE_REAL_TIME, createRealTime, nowReal);
        ProtoUtils.toDuration(proto,
                ServiceRecordProto.STARTING_BG_TIMEOUT, startingBgTimeout, now);
        ProtoUtils.toDuration(proto, ServiceRecordProto.LAST_ACTIVITY_TIME, lastActivity, now);
        ProtoUtils.toDuration(proto, ServiceRecordProto.RESTART_TIME, restartTime, now);
        proto.write(ServiceRecordProto.CREATED_FROM_FG, createdFromFg);
        proto.write(ServiceRecordProto.ALLOW_WHILE_IN_USE_PERMISSION_IN_FGS,
                mAllowWhileInUsePermissionInFgs);

        if (startRequested || delayedStop || lastStartId != 0) {
            long startToken = proto.start(ServiceRecordProto.START);
            proto.write(ServiceRecordProto.Start.START_REQUESTED, startRequested);
            proto.write(ServiceRecordProto.Start.DELAYED_STOP, delayedStop);
            proto.write(ServiceRecordProto.Start.STOP_IF_KILLED, stopIfKilled);
            proto.write(ServiceRecordProto.Start.LAST_START_ID, lastStartId);
            proto.write(ServiceRecordProto.Start.START_COMMAND_RESULT, startCommandResult);
            proto.end(startToken);
        }

        if (executeNesting != 0) {
            long executNestingToken = proto.start(ServiceRecordProto.EXECUTE);
            proto.write(ServiceRecordProto.ExecuteNesting.EXECUTE_NESTING, executeNesting);
            proto.write(ServiceRecordProto.ExecuteNesting.EXECUTE_FG, executeFg);
            ProtoUtils.toDuration(proto,
                    ServiceRecordProto.ExecuteNesting.EXECUTING_START, executingStart, now);
            proto.end(executNestingToken);
        }
        if (destroying || destroyTime != 0) {
            ProtoUtils.toDuration(proto, ServiceRecordProto.DESTORY_TIME, destroyTime, now);
        }
        if (crashCount != 0 || restartCount != 0 || (nextRestartTime - mRestartSchedulingTime) != 0
                || nextRestartTime != 0) {
            long crashToken = proto.start(ServiceRecordProto.CRASH);
            proto.write(ServiceRecordProto.Crash.RESTART_COUNT, restartCount);
            ProtoUtils.toDuration(proto, ServiceRecordProto.Crash.RESTART_DELAY,
                    (nextRestartTime - mRestartSchedulingTime), now);
            ProtoUtils.toDuration(proto,
                    ServiceRecordProto.Crash.NEXT_RESTART_TIME, nextRestartTime, now);
            proto.write(ServiceRecordProto.Crash.CRASH_COUNT, crashCount);
            proto.end(crashToken);
        }

        if (deliveredStarts.size() > 0) {
            final int N = deliveredStarts.size();
            for (int i = 0; i < N; i++) {
                deliveredStarts.get(i).dumpDebug(proto,
                        ServiceRecordProto.DELIVERED_STARTS, now);
            }
        }
        if (pendingStarts.size() > 0) {
            final int N = pendingStarts.size();
            for (int i = 0; i < N; i++) {
                pendingStarts.get(i).dumpDebug(proto, ServiceRecordProto.PENDING_STARTS, now);
            }
        }
        if (bindings.size() > 0) {
            final int N = bindings.size();
            for (int i=0; i<N; i++) {
                IntentBindRecord b = bindings.valueAt(i);
                b.dumpDebug(proto, ServiceRecordProto.BINDINGS);
            }
        }
        if (connections.size() > 0) {
            final int N = connections.size();
            for (int conni=0; conni<N; conni++) {
                ArrayList<ConnectionRecord> c = connections.valueAt(conni);
                for (int i=0; i<c.size(); i++) {
                    c.get(i).dumpDebug(proto, ServiceRecordProto.CONNECTIONS);
                }
            }
        }
        if (mShortFgsInfo != null && mShortFgsInfo.isCurrent()) {
            final long shortFgsToken = proto.start(ServiceRecordProto.SHORT_FGS_INFO);
            proto.write(ServiceRecordProto.ShortFgsInfo.START_TIME,
                    mShortFgsInfo.getStartTime());
            proto.write(ServiceRecordProto.ShortFgsInfo.START_ID,
                    mShortFgsInfo.getStartId());
            proto.write(ServiceRecordProto.ShortFgsInfo.TIMEOUT_TIME,
                    mShortFgsInfo.getTimeoutTime());
            proto.write(ServiceRecordProto.ShortFgsInfo.PROC_STATE_DEMOTE_TIME,
                    mShortFgsInfo.getProcStateDemoteTime());
            proto.write(ServiceRecordProto.ShortFgsInfo.ANR_TIME,
                    mShortFgsInfo.getAnrTime());
            proto.end(shortFgsToken);
        }

        proto.end(token);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("intent={");
                pw.print(intent.getIntent().toShortString(false, true, false, false));
                pw.println('}');
        pw.print(prefix); pw.print("packageName="); pw.println(packageName);
        pw.print(prefix); pw.print("processName="); pw.println(processName);
        if (permission != null) {
            pw.print(prefix); pw.print("permission="); pw.println(permission);
        }
        long now = SystemClock.uptimeMillis();
        long nowReal = SystemClock.elapsedRealtime();
        if (appInfo != null) {
            pw.print(prefix); pw.print("baseDir="); pw.println(appInfo.sourceDir);
            if (!Objects.equals(appInfo.sourceDir, appInfo.publicSourceDir)) {
                pw.print(prefix); pw.print("resDir="); pw.println(appInfo.publicSourceDir);
            }
            pw.print(prefix); pw.print("dataDir="); pw.println(appInfo.dataDir);
        }
        pw.print(prefix); pw.print("app="); pw.println(app);
        if (isolationHostProc != null) {
            pw.print(prefix); pw.print("isolationHostProc="); pw.println(isolationHostProc);
        }
        if (allowlistManager) {
            pw.print(prefix); pw.print("allowlistManager="); pw.println(allowlistManager);
        }
        if (mIsAllowedBgActivityStartsByBinding) {
            pw.print(prefix); pw.print("mIsAllowedBgActivityStartsByBinding=");
            pw.println(mIsAllowedBgActivityStartsByBinding);
        }
        if (mBackgroundStartPrivilegesByStartMerged.allowsAny()) {
            pw.print(prefix); pw.print("mIsAllowedBgActivityStartsByStart=");
            pw.println(mBackgroundStartPrivilegesByStartMerged);
        }
        pw.print(prefix); pw.print("mAllowWhileInUsePermissionInFgsReason=");
        pw.println(PowerExemptionManager.reasonCodeToString(mAllowWhileInUsePermissionInFgsReason));

        pw.print(prefix); pw.print("allowUiJobScheduling="); pw.println(mAllowUiJobScheduling);
        pw.print(prefix); pw.print("recentCallingPackage=");
                pw.println(mRecentCallingPackage);
        pw.print(prefix); pw.print("recentCallingUid=");
        pw.println(mRecentCallingUid);
        pw.print(prefix); pw.print("allowStartForeground=");
        pw.println(PowerExemptionManager.reasonCodeToString(mAllowStartForeground));
        pw.print(prefix); pw.print("startForegroundCount=");
        pw.println(mStartForegroundCount);
        pw.print(prefix); pw.print("infoAllowStartForeground=");
        pw.println(mInfoAllowStartForeground);

        if (delayed) {
            pw.print(prefix); pw.print("delayed="); pw.println(delayed);
        }
        if (isForeground || foregroundId != 0) {
            pw.print(prefix); pw.print("isForeground="); pw.print(isForeground);
            pw.print(" foregroundId="); pw.print(foregroundId);
            pw.printf(" types=%08X", foregroundServiceType);
            pw.print(" foregroundNoti="); pw.println(foregroundNoti);

            if (isShortFgs() && mShortFgsInfo != null) {
                pw.print(prefix); pw.print("isShortFgs=true");
                pw.print(" startId="); pw.print(mShortFgsInfo.getStartId());
                pw.print(" startForegroundCount=");
                pw.print(mShortFgsInfo.getStartForegroundCount());
                pw.print(" startTime=");
                TimeUtils.formatDuration(mShortFgsInfo.getStartTime(), now, pw);
                pw.print(" timeout=");
                TimeUtils.formatDuration(mShortFgsInfo.getTimeoutTime(), now, pw);
                pw.print(" demoteTime=");
                TimeUtils.formatDuration(mShortFgsInfo.getProcStateDemoteTime(), now, pw);
                pw.print(" anrTime=");
                TimeUtils.formatDuration(mShortFgsInfo.getAnrTime(), now, pw);
                pw.println();
            }
        }
        if (mIsFgsDelegate) {
            pw.print(prefix); pw.print("isFgsDelegate="); pw.println(mIsFgsDelegate);
        }
        pw.print(prefix); pw.print("createTime=");
                TimeUtils.formatDuration(createRealTime, nowReal, pw);
                pw.print(" startingBgTimeout=");
                TimeUtils.formatDuration(startingBgTimeout, now, pw);
                pw.println();
        pw.print(prefix); pw.print("lastActivity=");
                TimeUtils.formatDuration(lastActivity, now, pw);
                pw.print(" restartTime=");
                TimeUtils.formatDuration(restartTime, now, pw);
                pw.print(" createdFromFg="); pw.println(createdFromFg);
        if (pendingConnectionGroup != 0) {
            pw.print(prefix); pw.print(" pendingConnectionGroup=");
            pw.print(pendingConnectionGroup);
            pw.print(" Importance="); pw.println(pendingConnectionImportance);
        }
        if (startRequested || delayedStop || lastStartId != 0) {
            pw.print(prefix); pw.print("startRequested="); pw.print(startRequested);
                    pw.print(" delayedStop="); pw.print(delayedStop);
                    pw.print(" stopIfKilled="); pw.print(stopIfKilled);
                    pw.print(" callStart="); pw.print(callStart);
                    pw.print(" lastStartId="); pw.println(lastStartId);
                    pw.print(" startCommandResult="); pw.println(startCommandResult);
        }
        if (executeNesting != 0) {
            pw.print(prefix); pw.print("executeNesting="); pw.print(executeNesting);
                    pw.print(" executeFg="); pw.print(executeFg);
                    pw.print(" executingStart=");
                    TimeUtils.formatDuration(executingStart, now, pw);
                    pw.println();
        }
        if (destroying || destroyTime != 0) {
            pw.print(prefix); pw.print("destroying="); pw.print(destroying);
                    pw.print(" destroyTime=");
                    TimeUtils.formatDuration(destroyTime, now, pw);
                    pw.println();
        }
        if (crashCount != 0 || restartCount != 0
                || (nextRestartTime - mRestartSchedulingTime) != 0 || nextRestartTime != 0) {
            pw.print(prefix); pw.print("restartCount="); pw.print(restartCount);
                    pw.print(" restartDelay=");
                    TimeUtils.formatDuration(nextRestartTime - mRestartSchedulingTime, now, pw);
                    pw.print(" nextRestartTime=");
                    TimeUtils.formatDuration(nextRestartTime, now, pw);
                    pw.print(" crashCount="); pw.println(crashCount);
        }
        if (deliveredStarts.size() > 0) {
            pw.print(prefix); pw.println("Delivered Starts:");
            dumpStartList(pw, prefix, deliveredStarts, now);
        }
        if (pendingStarts.size() > 0) {
            pw.print(prefix); pw.println("Pending Starts:");
            dumpStartList(pw, prefix, pendingStarts, 0);
        }
        if (bindings.size() > 0) {
            pw.print(prefix); pw.println("Bindings:");
            for (int i=0; i<bindings.size(); i++) {
                IntentBindRecord b = bindings.valueAt(i);
                pw.print(prefix); pw.print("* IntentBindRecord{");
                        pw.print(Integer.toHexString(System.identityHashCode(b)));
                        if ((b.collectFlags()&Context.BIND_AUTO_CREATE) != 0) {
                            pw.append(" CREATE");
                        }
                        pw.println("}:");
                b.dumpInService(pw, prefix + "  ");
            }
        }
        if (connections.size() > 0) {
            pw.print(prefix); pw.println("All Connections:");
            for (int conni=0; conni<connections.size(); conni++) {
                ArrayList<ConnectionRecord> c = connections.valueAt(conni);
                for (int i=0; i<c.size(); i++) {
                    pw.print(prefix); pw.print("  "); pw.println(c.get(i));
                }
            }
        }
    }

    /** Used only for tests */
    private ServiceRecord(ActivityManagerService ams) {
        this.ams = ams;
        name = null;
        instanceName = null;
        shortInstanceName = null;
        definingPackageName = null;
        definingUid = 0;
        intent = null;
        serviceInfo = null;
        userId = 0;
        packageName = null;
        processName = null;
        permission = null;
        exported = false;
        restarter = null;
        createRealTime = 0;
        isSdkSandbox = false;
        sdkSandboxClientAppUid = 0;
        sdkSandboxClientAppPackage = null;
        inSharedIsolatedProcess = false;
    }

    public static ServiceRecord newEmptyInstanceForTest(ActivityManagerService ams) {
        return new ServiceRecord(ams);
    }

    ServiceRecord(ActivityManagerService ams, ComponentName name,
            ComponentName instanceName, String definingPackageName, int definingUid,
            Intent.FilterComparison intent, ServiceInfo sInfo, boolean callerIsFg,
            Runnable restarter) {
        this(ams, name, instanceName, definingPackageName, definingUid, intent, sInfo, callerIsFg,
                restarter, sInfo.processName, INVALID_UID, null, false);
    }

    ServiceRecord(ActivityManagerService ams, ComponentName name,
            ComponentName instanceName, String definingPackageName, int definingUid,
            Intent.FilterComparison intent, ServiceInfo sInfo, boolean callerIsFg,
            Runnable restarter, String processName, int sdkSandboxClientAppUid,
            String sdkSandboxClientAppPackage, boolean inSharedIsolatedProcess) {
        this.ams = ams;
        this.name = name;
        this.instanceName = instanceName;
        shortInstanceName = instanceName.flattenToShortString();
        this.definingPackageName = definingPackageName;
        this.definingUid = definingUid;
        this.intent = intent;
        serviceInfo = sInfo;
        appInfo = sInfo.applicationInfo;
        packageName = sInfo.applicationInfo.packageName;
        this.isSdkSandbox = sdkSandboxClientAppUid != INVALID_UID;
        this.sdkSandboxClientAppUid = sdkSandboxClientAppUid;
        this.sdkSandboxClientAppPackage = sdkSandboxClientAppPackage;
        this.inSharedIsolatedProcess = inSharedIsolatedProcess;
        this.processName = processName;
        permission = sInfo.permission;
        exported = sInfo.exported;
        this.restarter = restarter;
        createRealTime = SystemClock.elapsedRealtime();
        lastActivity = SystemClock.uptimeMillis();
        userId = UserHandle.getUserId(appInfo.uid);
        createdFromFg = callerIsFg;
        updateKeepWarmLocked();
        // initialize notification permission state; this'll be updated whenever there's an attempt
        // to post or update a notification, but that doesn't cover the time before the first
        // notification
        updateFgsHasNotificationPermission();
    }

    public ServiceState getTracker() {
        if (tracker != null) {
            return tracker;
        }
        if ((serviceInfo.applicationInfo.flags&ApplicationInfo.FLAG_PERSISTENT) == 0) {
            tracker = ams.mProcessStats.getServiceState(serviceInfo.packageName,
                    serviceInfo.applicationInfo.uid,
                    serviceInfo.applicationInfo.longVersionCode,
                    serviceInfo.processName, serviceInfo.name);
            if (tracker != null) {
                tracker.applyNewOwner(this);
            }
        }
        return tracker;
    }

    public void forceClearTracker() {
        if (tracker != null) {
            tracker.clearCurrentOwner(this, true);
            tracker = null;
        }
    }

    public void makeRestarting(int memFactor, long now) {
        if (restartTracker == null) {
            if ((serviceInfo.applicationInfo.flags&ApplicationInfo.FLAG_PERSISTENT) == 0) {
                restartTracker = ams.mProcessStats.getServiceState(
                        serviceInfo.packageName,
                        serviceInfo.applicationInfo.uid,
                        serviceInfo.applicationInfo.longVersionCode,
                        serviceInfo.processName, serviceInfo.name);
            }
            if (restartTracker == null) {
                return;
            }
        }
        restartTracker.setRestarting(true, memFactor, now);
    }

    public void setProcess(ProcessRecord proc, IApplicationThread thread, int pid,
            UidRecord uidRecord) {
        if (proc != null) {
            // We're starting a new process for this service, but a previous one is allowed to start
            // background activities. Remove that ability now (unless the new process is the same as
            // the previous one, which is a common case).
            if (mAppForAllowingBgActivityStartsByStart != null) {
                if (mAppForAllowingBgActivityStartsByStart != proc) {
                    mAppForAllowingBgActivityStartsByStart
                            .removeBackgroundStartPrivileges(this);
                    ams.mHandler.removeCallbacks(mCleanUpAllowBgActivityStartsByStartCallback);
                }
            }
            // Make sure the cleanup callback knows about the new process.
            mAppForAllowingBgActivityStartsByStart =
                    mBackgroundStartPrivilegesByStartMerged.allowsAny()
                    ? proc : null;
            BackgroundStartPrivileges backgroundStartPrivileges =
                    getBackgroundStartPrivilegesWithExclusiveToken();
            if (backgroundStartPrivileges.allowsAny()) {
                proc.addOrUpdateBackgroundStartPrivileges(this,
                        backgroundStartPrivileges);
            } else {
                proc.removeBackgroundStartPrivileges(this);
            }
        }
        if (app != null && app != proc) {
            // If the old app is allowed to start bg activities because of a service start, leave it
            // that way until the cleanup callback runs. Otherwise we can remove its bg activity
            // start ability immediately (it can't be bound now).
            if (mBackgroundStartPrivilegesByStartMerged.allowsNothing()) {
                app.removeBackgroundStartPrivileges(this);
            }
            app.mServices.updateBoundClientUids();
            app.mServices.updateHostingComonentTypeForBindingsLocked();
        }
        app = proc;
        updateProcessStateOnRequest();
        if (pendingConnectionGroup > 0 && proc != null) {
            final ProcessServiceRecord psr = proc.mServices;
            psr.setConnectionService(this);
            psr.setConnectionGroup(pendingConnectionGroup);
            psr.setConnectionImportance(pendingConnectionImportance);
            pendingConnectionGroup = pendingConnectionImportance = 0;
        }
        if (ActivityManagerService.TRACK_PROCSTATS_ASSOCIATIONS) {
            for (int conni = connections.size() - 1; conni >= 0; conni--) {
                ArrayList<ConnectionRecord> cr = connections.valueAt(conni);
                for (int i = 0; i < cr.size(); i++) {
                    final ConnectionRecord conn = cr.get(i);
                    if (proc != null) {
                        conn.startAssociationIfNeeded();
                    } else {
                        conn.stopAssociation();
                    }
                }
            }
        }
        if (proc != null) {
            proc.mServices.updateBoundClientUids();
            proc.mServices.updateHostingComonentTypeForBindingsLocked();
        }
    }

    void updateProcessStateOnRequest() {
        mProcessStateOnRequest = app != null && app.getThread() != null && !app.isKilled()
                ? app.mState.getCurProcState() : PROCESS_STATE_NONEXISTENT;
    }

    @NonNull
    ArrayMap<IBinder, ArrayList<ConnectionRecord>> getConnections() {
        return connections;
    }

    void addConnection(IBinder binder, ConnectionRecord c) {
        ArrayList<ConnectionRecord> clist = connections.get(binder);
        if (clist == null) {
            clist = new ArrayList<>();
            connections.put(binder, clist);
        }
        clist.add(c);

        // if we have a process attached, add bound client uid of this connection to it
        if (app != null) {
            app.mServices.addBoundClientUid(c.clientUid, c.clientPackageName, c.getFlags());
            app.mProfile.addHostingComponentType(HOSTING_COMPONENT_TYPE_BOUND_SERVICE);
        }
    }

    void removeConnection(IBinder binder) {
        connections.remove(binder);
        // if we have a process attached, tell it to update the state of bound clients
        if (app != null) {
            app.mServices.updateBoundClientUids();
            app.mServices.updateHostingComonentTypeForBindingsLocked();
        }
    }

    /**
     * @return {@code true} if the killed service which was started by {@link Context#startService}
     *         has no reason to start again. Note this condition doesn't consider the bindings.
     */
    boolean canStopIfKilled(boolean isStartCanceled) {
        if (isShortFgs()) { // Short-FGS should always stop if killed.
            return true;
        }
        return startRequested && (stopIfKilled || isStartCanceled) && pendingStarts.isEmpty();
    }

    void updateIsAllowedBgActivityStartsByBinding() {
        boolean isAllowedByBinding = false;
        for (int conni = connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> cr = connections.valueAt(conni);
            for (int i = 0; i < cr.size(); i++) {
                if (cr.get(i).hasFlag(Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS)) {
                    isAllowedByBinding = true;
                    break;
                }
            }
            if (isAllowedByBinding) {
                break;
            }
        }
        setAllowedBgActivityStartsByBinding(isAllowedByBinding);
    }

    void setAllowedBgActivityStartsByBinding(boolean newValue) {
        mIsAllowedBgActivityStartsByBinding = newValue;
        updateParentProcessBgActivityStartsToken();
    }

    /**
     * Called when the service is started with allowBackgroundActivityStarts set. We allow
     * it for background activity starts, setting up a callback to remove this ability after a
     * timeout. Note that the ability for starting background activities persists for the process
     * even if the service is subsequently stopped.
     */
    void allowBgActivityStartsOnServiceStart(BackgroundStartPrivileges backgroundStartPrivileges) {
        checkArgument(backgroundStartPrivileges.allowsAny());
        mBackgroundStartPrivilegesByStart.add(backgroundStartPrivileges);
        setAllowedBgActivityStartsByStart(
                backgroundStartPrivileges.merge(mBackgroundStartPrivilegesByStartMerged));
        if (app != null) {
            mAppForAllowingBgActivityStartsByStart = app;
        }

        // This callback is stateless, so we create it once when we first need it.
        if (mCleanUpAllowBgActivityStartsByStartCallback == null) {
            mCleanUpAllowBgActivityStartsByStartCallback = () -> {
                synchronized (ams) {
                    mBackgroundStartPrivilegesByStart.remove(0);
                    if (!mBackgroundStartPrivilegesByStart.isEmpty()) {
                        // recalculate the merged token
                        mBackgroundStartPrivilegesByStartMerged =
                                BackgroundStartPrivileges.merge(mBackgroundStartPrivilegesByStart);

                        // There are other callbacks in the queue, let's just update the originating
                        // token
                        if (mBackgroundStartPrivilegesByStartMerged.allowsAny()) {
                            // mAppForAllowingBgActivityStartsByStart can be null here for example
                            // if get 2 calls to allowBgActivityStartsOnServiceStart() without a
                            // process attached to this ServiceRecord, so we need to perform a null
                            // check here.
                            if (mAppForAllowingBgActivityStartsByStart != null) {
                                mAppForAllowingBgActivityStartsByStart
                                        .addOrUpdateBackgroundStartPrivileges(this,
                                                getBackgroundStartPrivilegesWithExclusiveToken());
                            }
                        } else {
                            Slog.wtf(TAG,
                                    "Service callback to revoke bg activity starts by service "
                                            + "start triggered but "
                                            + "mBackgroundStartPrivilegesByStartMerged = "
                                            + mBackgroundStartPrivilegesByStartMerged
                                            + ". This should never happen.");
                        }
                    } else {
                        // Last callback on the queue
                        if (app == mAppForAllowingBgActivityStartsByStart) {
                            // The process we allowed is still running the service. We remove
                            // the ability by start, but it may still be allowed via bound
                            // connections.
                            setAllowedBgActivityStartsByStart(BackgroundStartPrivileges.NONE);
                        } else if (mAppForAllowingBgActivityStartsByStart != null) {
                            // The process we allowed is not running the service. It therefore can't
                            // be bound so we can unconditionally remove the ability.
                            mAppForAllowingBgActivityStartsByStart
                                    .removeBackgroundStartPrivileges(ServiceRecord.this);
                        }
                        mAppForAllowingBgActivityStartsByStart = null;
                    }
                }
            };
        }

        // Existing callbacks will only update the originating token, only when the last callback is
        // executed is the grant revoked.
        ams.mHandler.postDelayed(mCleanUpAllowBgActivityStartsByStartCallback,
                ams.mConstants.SERVICE_BG_ACTIVITY_START_TIMEOUT);
    }

    void updateAllowUiJobScheduling(boolean allowUiJobScheduling) {
        if (mAllowUiJobScheduling == allowUiJobScheduling) {
            return;
        }
        mAllowUiJobScheduling = allowUiJobScheduling;
    }

    private void setAllowedBgActivityStartsByStart(BackgroundStartPrivileges newValue) {
        if (mBackgroundStartPrivilegesByStartMerged == newValue) {
            return;
        }
        mBackgroundStartPrivilegesByStartMerged = newValue;
        updateParentProcessBgActivityStartsToken();
    }

    /**
     * Whether the process this service runs in should be temporarily allowed to start
     * activities from background depends on the current state of both
     * {@code mIsAllowedBgActivityStartsByStart} and
     * {@code mIsAllowedBgActivityStartsByBinding}. If either is true, this ServiceRecord
     * should be contributing as a token in parent ProcessRecord.
     *
     * @see com.android.server.am.ProcessRecord#addOrUpdateBackgroundStartPrivileges(Binder,
     *         BackgroundStartPrivileges)
     * @see com.android.server.am.ProcessRecord#removeBackgroundStartPrivileges(Binder)
     */
    private void updateParentProcessBgActivityStartsToken() {
        if (app == null) {
            return;
        }
        BackgroundStartPrivileges backgroundStartPrivileges =
                getBackgroundStartPrivilegesWithExclusiveToken();
        if (backgroundStartPrivileges.allowsAny()) {
            // if the token is already there it's safe to "re-add it" - we're dealing with
            // a set of Binder objects
            app.addOrUpdateBackgroundStartPrivileges(this,
                    backgroundStartPrivileges);
        } else {
            app.removeBackgroundStartPrivileges(this);
        }
    }

    /**
     * Returns {@link BackgroundStartPrivileges} that represents the privileges a specific
     * originating token or a generic aggregate token.
     *
     * If all privileges are associated with the same token (i.e. the service is only allowed due
     * to starts) the token will be retained, otherwise (e.g. the privileges were granted by
     * bindings) the originating token will be empty.
     */
    @Nullable
    private BackgroundStartPrivileges getBackgroundStartPrivilegesWithExclusiveToken() {
        if (mIsAllowedBgActivityStartsByBinding) {
            return BackgroundStartPrivileges.ALLOW_BAL;
        }
        if (mBackgroundStartPrivilegesByStart.isEmpty()) {
            return BackgroundStartPrivileges.NONE;
        }
        return mBackgroundStartPrivilegesByStartMerged;
    }

    @GuardedBy("ams")
    void updateKeepWarmLocked() {
        mKeepWarming = ams.mConstants.KEEP_WARMING_SERVICES.contains(name)
                && (ams.mUserController.getCurrentUserId() == userId
                || ams.mUserController.isCurrentProfile(userId)
                || ams.isSingleton(processName, appInfo, instanceName.getClassName(),
                        serviceInfo.flags));
    }

    public AppBindRecord retrieveAppBindingLocked(Intent intent,
            ProcessRecord app, ProcessRecord attributedApp) {
        Intent.FilterComparison filter = new Intent.FilterComparison(intent);
        IntentBindRecord i = bindings.get(filter);
        if (i == null) {
            i = new IntentBindRecord(this, filter);
            bindings.put(filter, i);
        }
        AppBindRecord a = i.apps.get(app);
        if (a != null) {
            return a;
        }
        a = new AppBindRecord(this, i, app, attributedApp);
        i.apps.put(app, a);
        return a;
    }

    public boolean hasAutoCreateConnections() {
        // XXX should probably keep a count of the number of auto-create
        // connections directly in the service.
        for (int conni=connections.size()-1; conni>=0; conni--) {
            ArrayList<ConnectionRecord> cr = connections.valueAt(conni);
            for (int i=0; i<cr.size(); i++) {
                if (cr.get(i).hasFlag(Context.BIND_AUTO_CREATE)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void updateAllowlistManager() {
        allowlistManager = false;
        for (int conni=connections.size()-1; conni>=0; conni--) {
            ArrayList<ConnectionRecord> cr = connections.valueAt(conni);
            for (int i=0; i<cr.size(); i++) {
                if (cr.get(i).hasFlag(Context.BIND_ALLOW_WHITELIST_MANAGEMENT)) {
                    allowlistManager = true;
                    return;
                }
            }
        }
    }

    public void resetRestartCounter() {
        restartCount = 0;
        restartDelay = 0;
        restartTime = 0;
        mEarliestRestartTime  = 0;
        mRestartSchedulingTime = 0;
    }

    public StartItem findDeliveredStart(int id, boolean taskRemoved, boolean remove) {
        final int N = deliveredStarts.size();
        for (int i=0; i<N; i++) {
            StartItem si = deliveredStarts.get(i);
            if (si.id == id && si.taskRemoved == taskRemoved) {
                if (remove) deliveredStarts.remove(i);
                return si;
            }
        }

        return null;
    }

    public int getLastStartId() {
        return lastStartId;
    }

    public int makeNextStartId() {
        lastStartId++;
        if (lastStartId < 1) {
            lastStartId = 1;
        }
        return lastStartId;
    }

    private void updateFgsHasNotificationPermission() {
        // Do asynchronous communication with notification manager to avoid deadlocks.
        final String localPackageName = packageName;
        final int appUid = appInfo.uid;

        ams.mHandler.post(new Runnable() {
            public void run() {
                NotificationManagerInternal nm = LocalServices.getService(
                        NotificationManagerInternal.class);
                if (nm == null) {
                    return;
                }
                // Record whether the package has permission to notify the user
                mFgsHasNotificationPermission = nm.areNotificationsEnabledForPackage(
                        localPackageName, appUid);
            }
        });
    }

    public void postNotification() {
        if (isForeground && foregroundNoti != null && app != null) {
            final int appUid = appInfo.uid;
            final int appPid = app.getPid();
            // Do asynchronous communication with notification manager to
            // avoid deadlocks.
            final String localPackageName = packageName;
            final int localForegroundId = foregroundId;
            final Notification _foregroundNoti = foregroundNoti;
            final ServiceRecord record = this;
            if (DEBUG_FOREGROUND_SERVICE) {
                Slog.d(TAG, "Posting notification " + _foregroundNoti
                        + " for foreground service " + this);
            }
            ams.mHandler.post(new Runnable() {
                public void run() {
                    NotificationManagerInternal nm = LocalServices.getService(
                            NotificationManagerInternal.class);
                    if (nm == null) {
                        return;
                    }
                    // Record whether the package has permission to notify the user
                    mFgsHasNotificationPermission = nm.areNotificationsEnabledForPackage(
                            localPackageName, appUid);
                    Notification localForegroundNoti = _foregroundNoti;
                    try {
                        if (localForegroundNoti.getSmallIcon() == null) {
                            // It is not correct for the caller to not supply a notification
                            // icon, but this used to be able to slip through, so for
                            // those dirty apps we will create a notification clearly
                            // blaming the app.
                            Slog.v(TAG, "Attempted to start a foreground service ("
                                    + shortInstanceName
                                    + ") with a broken notification (no icon: "
                                    + localForegroundNoti
                                    + ")");

                            CharSequence appName = appInfo.loadLabel(
                                    ams.mContext.getPackageManager());
                            if (appName == null) {
                                appName = appInfo.packageName;
                            }
                            Context ctx = null;
                            try {
                                ctx = ams.mContext.createPackageContextAsUser(
                                        appInfo.packageName, 0, new UserHandle(userId));

                                Notification.Builder notiBuilder = new Notification.Builder(ctx,
                                        localForegroundNoti.getChannelId());

                                // it's ugly, but it clearly identifies the app
                                notiBuilder.setSmallIcon(appInfo.icon);

                                // mark as foreground
                                notiBuilder.setFlag(Notification.FLAG_FOREGROUND_SERVICE, true);

                                Intent runningIntent = new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                runningIntent.setData(Uri.fromParts("package",
                                        appInfo.packageName, null));
                                PendingIntent pi = PendingIntent.getActivityAsUser(ams.mContext, 0,
                                        runningIntent, FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE, null,
                                        UserHandle.of(userId));
                                notiBuilder.setColor(ams.mContext.getColor(
                                        com.android.internal
                                                .R.color.system_notification_accent_color));
                                notiBuilder.setContentTitle(
                                        ams.mContext.getString(
                                                com.android.internal.R.string
                                                        .app_running_notification_title,
                                                appName));
                                notiBuilder.setContentText(
                                        ams.mContext.getString(
                                                com.android.internal.R.string
                                                        .app_running_notification_text,
                                                appName));
                                notiBuilder.setContentIntent(pi);

                                localForegroundNoti = notiBuilder.build();
                            } catch (PackageManager.NameNotFoundException e) {
                            }
                        }
                        if (nm.getNotificationChannel(localPackageName, appUid,
                                localForegroundNoti.getChannelId()) == null) {
                            int targetSdkVersion = Build.VERSION_CODES.O_MR1;
                            try {
                                final ApplicationInfo applicationInfo =
                                        ams.mContext.getPackageManager().getApplicationInfoAsUser(
                                                appInfo.packageName, 0, userId);
                                targetSdkVersion = applicationInfo.targetSdkVersion;
                            } catch (PackageManager.NameNotFoundException e) {
                            }
                            if (targetSdkVersion >= Build.VERSION_CODES.O_MR1) {
                                throw new RuntimeException(
                                        "invalid channel for service notification: "
                                                + foregroundNoti);
                            }
                        }
                        if (localForegroundNoti.getSmallIcon() == null) {
                            // Notifications whose icon is 0 are defined to not show
                            // a notification.  We don't want to
                            // just ignore it, we want to prevent the service from
                            // being foreground.
                            throw new RuntimeException("invalid service notification: "
                                    + foregroundNoti);
                        }
                        nm.enqueueNotification(localPackageName, localPackageName,
                                appUid, appPid, null, localForegroundId, localForegroundNoti,
                                userId);

                        foregroundNoti = localForegroundNoti; // save it for amending next time

                        signalForegroundServiceNotification(packageName, appInfo.uid,
                                localForegroundId, false /* canceling */);

                    } catch (RuntimeException e) {
                        Slog.w(TAG, "Error showing notification for service", e);
                        // If it gave us a garbage notification, it doesn't
                        // get to be foreground.
                        ams.mServices.killMisbehavingService(record,
                                appUid, appPid, localPackageName,
                                CannotPostForegroundServiceNotificationException.TYPE_ID);
                    }
                }
            });
        }
    }

    public void cancelNotification() {
        // Do asynchronous communication with notification manager to
        // avoid deadlocks.
        final String localPackageName = packageName;
        final int localForegroundId = foregroundId;
        final int appUid = appInfo.uid;
        final int appPid = app != null ? app.getPid() : 0;
        ams.mHandler.post(new Runnable() {
            public void run() {
                NotificationManagerInternal nm = LocalServices.getService(
                        NotificationManagerInternal.class);
                if (nm == null) {
                    return;
                }
                try {
                    nm.cancelNotification(localPackageName, localPackageName, appUid, appPid,
                            null, localForegroundId, userId);
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Error canceling notification for service", e);
                }
                signalForegroundServiceNotification(packageName, appInfo.uid, localForegroundId,
                        true /* canceling */);
            }
        });
    }

    private void signalForegroundServiceNotification(String packageName, int uid,
            int foregroundId, boolean canceling) {
        synchronized (ams) {
            for (int i = ams.mForegroundServiceStateListeners.size() - 1; i >= 0; i--) {
                ams.mForegroundServiceStateListeners.get(i).onForegroundServiceNotificationUpdated(
                        packageName, appInfo.uid, foregroundId, canceling);
            }
        }
    }

    public void stripForegroundServiceFlagFromNotification() {
        final int localForegroundId = foregroundId;
        final int localUserId = userId;
        final String localPackageName = packageName;

        // Do asynchronous communication with notification manager to
        // avoid deadlocks.
        ams.mHandler.post(new Runnable() {
            @Override
            public void run() {
                NotificationManagerInternal nmi = LocalServices.getService(
                        NotificationManagerInternal.class);
                if (nmi == null) {
                    return;
                }
                nmi.removeForegroundServiceFlagFromNotification(localPackageName, localForegroundId,
                        localUserId);
            }
        });
    }

    public void clearDeliveredStartsLocked() {
        for (int i=deliveredStarts.size()-1; i>=0; i--) {
            deliveredStarts.get(i).removeUriPermissionsLocked();
        }
        deliveredStarts.clear();
    }

    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ServiceRecord{")
            .append(Integer.toHexString(System.identityHashCode(this)))
            .append(" u").append(userId)
            .append(' ').append(shortInstanceName).append('}');
        return stringName = sb.toString();
    }

    public ComponentName getComponentName() {
        return name;
    }

    /**
     * @return true if it's a foreground service of the "short service" type and don't have
     * other fgs type bits set.
     */
    public boolean isShortFgs() {
        // Note if the type contains FOREGROUND_SERVICE_TYPE_SHORT_SERVICE but also other bits
        // set, it's _not_ considered be a short service. (because we shouldn't apply
        // the short-service restrictions)
        // (But we should be preventing mixture of FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        // and other types in Service.startForeground().)
        return startRequested && isForeground
                && (foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
    }

    public ShortFgsInfo getShortFgsInfo() {
        return isShortFgs() ? mShortFgsInfo : null;
    }

    /**
     * Call it when a short FGS starts.
     */
    public void setShortFgsInfo(long uptimeNow) {
        this.mShortFgsInfo = new ShortFgsInfo(uptimeNow);
    }

    /** @return whether {@link #mShortFgsInfo} is set or not. */
    public boolean hasShortFgsInfo() {
        return mShortFgsInfo != null;
    }

    /**
     * Call it when a short FGS stops.
     */
    public void clearShortFgsInfo() {
        this.mShortFgsInfo = null;
    }

    private boolean shouldTriggerShortFgsTimedEvent(long targetTime, long nowUptime) {
        if (!isAppAlive()) {
            return false;
        }
        if (!this.startRequested || !isShortFgs() || mShortFgsInfo == null
                || !mShortFgsInfo.isCurrent()) {
            return false;
        }
        return targetTime <= nowUptime;
    }

    /**
     * @return true if it's a short FGS that's still up and running, and should be timed out.
     */
    public boolean shouldTriggerShortFgsTimeout(long nowUptime) {
        return shouldTriggerShortFgsTimedEvent(
                (mShortFgsInfo == null ? 0 : mShortFgsInfo.getTimeoutTime()),
                nowUptime);
    }

    /**
     * @return true if it's a short FGS's procstate should be demoted.
     */
    public boolean shouldDemoteShortFgsProcState(long nowUptime) {
        return shouldTriggerShortFgsTimedEvent(
                (mShortFgsInfo == null ? 0 : mShortFgsInfo.getProcStateDemoteTime()),
                nowUptime);
    }

    /**
     * @return true if it's a short FGS that's still up and running, and should be declared
     * an ANR.
     */
    public boolean shouldTriggerShortFgsAnr(long nowUptime) {
        return shouldTriggerShortFgsTimedEvent(
                (mShortFgsInfo == null ? 0 : mShortFgsInfo.getAnrTime()),
                nowUptime);
    }

    /**
     * Human readable description about short-FGS internal states.
     */
    public String getShortFgsTimedEventDescription(long nowUptime) {
        return "aa=" + isAppAlive()
                + " sreq=" + this.startRequested
                + " isfg=" + this.isForeground
                + " type=" + Integer.toHexString(this.foregroundServiceType)
                + " sfc=" + this.mStartForegroundCount
                + " now=" + nowUptime
                + " " + (mShortFgsInfo == null ? "" : mShortFgsInfo.getDescription());
    }

    private boolean isAppAlive() {
        if (app == null) {
            return false;
        }
        if (app.getThread() == null || app.isKilled() || app.isKilledByAm()) {
            return false;
        }
        return true;
    }
}
