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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;
import android.util.proto.ProtoUtils;

import com.android.internal.app.procstats.ServiceState;
import com.android.internal.os.BatteryStatsImpl;
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
    final BatteryStatsImpl.Uid.Pkg.Serv stats;
    final ComponentName name; // service component.
    final ComponentName instanceName; // service component's per-instance name.
    final String shortInstanceName; // instanceName.flattenToShortString().
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
    final ArrayMap<Intent.FilterComparison, IntentBindRecord> bindings
            = new ArrayMap<Intent.FilterComparison, IntentBindRecord>();
                            // All active bindings to the service.
    final ArrayMap<IBinder, ArrayList<ConnectionRecord>> connections
            = new ArrayMap<IBinder, ArrayList<ConnectionRecord>>();
                            // IBinder -> ConnectionRecord of all bound clients

    ProcessRecord app;      // where this service is running or null.
    ProcessRecord isolatedProc; // keep track of isolated process, if requested
    ServiceState tracker; // tracking service execution, may be null
    ServiceState restartTracker; // tracking service restart
    boolean whitelistManager; // any bindings to this service have BIND_ALLOW_WHITELIST_MANAGEMENT?
    boolean delayed;        // are we waiting to start this service in the background?
    boolean fgRequired;     // is the service required to go foreground after starting?
    boolean fgWaiting;      // is a timeout for going foreground already scheduled?
    boolean isForeground;   // is service currently in foreground mode?
    int foregroundId;       // Notification ID of last foreground req.
    Notification foregroundNoti; // Notification record of foreground state.
    int foregroundServiceType; // foreground service types.
    long lastActivity;      // last time there was some activity on the service.
    long startingBgTimeout;  // time at which we scheduled this for a delayed start.
    boolean startRequested; // someone explicitly called start?
    boolean delayedStop;    // service has been stopped but is in a delayed start?
    boolean stopIfKilled;   // last onStart() said to stop if service killed?
    boolean callStart;      // last onStart() has asked to always be called on restart.
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

    // any current binding to this service has BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS flag?
    private boolean hasBindingWhitelistingBgActivityStarts;
    // is this service currently whitelisted to start activities from background by providing
    // allowBackgroundActivityStarts=true to startServiceLocked()?
    boolean hasStartedWhitelistingBgActivityStarts;
    // used to clean up the state of hasStartedWhitelistingBgActivityStarts after a timeout
    Runnable startedWhitelistingBgActivityStartsCleanUp;

    String stringName;      // caching of toString

    private int lastStartId;    // identifier of most recent start request.

    static class StartItem {
        final ServiceRecord sr;
        final boolean taskRemoved;
        final int id;
        final int callingId;
        final Intent intent;
        final NeededUriGrants neededGrants;
        long deliveredTime;
        int deliveryCount;
        int doneExecutingCount;
        UriPermissionOwner uriPermissions;

        String stringName;      // caching of toString

        StartItem(ServiceRecord _sr, boolean _taskRemoved, int _id, Intent _intent,
                NeededUriGrants _neededGrants, int _callingId) {
            sr = _sr;
            taskRemoved = _taskRemoved;
            id = _id;
            intent = _intent;
            neededGrants = _neededGrants;
            callingId = _callingId;
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

        public void writeToProto(ProtoOutputStream proto, long fieldId, long now) {
            long token = proto.start(fieldId);
            proto.write(ServiceRecordProto.StartItem.ID, id);
            ProtoUtils.toDuration(proto,
                    ServiceRecordProto.StartItem.DURATION, deliveredTime, now);
            proto.write(ServiceRecordProto.StartItem.DELIVERY_COUNT, deliveryCount);
            proto.write(ServiceRecordProto.StartItem.DONE_EXECUTING_COUNT, doneExecutingCount);
            if (intent != null) {
                intent.writeToProto(proto, ServiceRecordProto.StartItem.INTENT, true, true,
                        true, false);
            }
            if (neededGrants != null) {
                neededGrants.writeToProto(proto, ServiceRecordProto.StartItem.NEEDED_GRANTS);
            }
            if (uriPermissions != null) {
                uriPermissions.writeToProto(proto, ServiceRecordProto.StartItem.URI_PERMISSIONS);
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

    void writeToProto(ProtoOutputStream proto, long fieldId) {
        long token = proto.start(fieldId);
        proto.write(ServiceRecordProto.SHORT_NAME, this.shortInstanceName);
        proto.write(ServiceRecordProto.IS_RUNNING, app != null);
        if (app != null) {
            proto.write(ServiceRecordProto.PID, app.pid);
        }
        if (intent != null) {
            intent.getIntent().writeToProto(proto, ServiceRecordProto.INTENT, false, true, false,
                    true);
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
            app.writeToProto(proto, ServiceRecordProto.APP);
        }
        if (isolatedProc != null) {
            isolatedProc.writeToProto(proto, ServiceRecordProto.ISOLATED_PROC);
        }
        proto.write(ServiceRecordProto.WHITELIST_MANAGER, whitelistManager);
        proto.write(ServiceRecordProto.DELAYED, delayed);
        if (isForeground || foregroundId != 0) {
            long fgToken = proto.start(ServiceRecordProto.FOREGROUND);
            proto.write(ServiceRecordProto.Foreground.ID, foregroundId);
            foregroundNoti.writeToProto(proto, ServiceRecordProto.Foreground.NOTIFICATION);
            proto.end(fgToken);
        }
        ProtoUtils.toDuration(proto, ServiceRecordProto.CREATE_REAL_TIME, createRealTime, nowReal);
        ProtoUtils.toDuration(proto,
                ServiceRecordProto.STARTING_BG_TIMEOUT, startingBgTimeout, now);
        ProtoUtils.toDuration(proto, ServiceRecordProto.LAST_ACTIVITY_TIME, lastActivity, now);
        ProtoUtils.toDuration(proto, ServiceRecordProto.RESTART_TIME, restartTime, now);
        proto.write(ServiceRecordProto.CREATED_FROM_FG, createdFromFg);

        if (startRequested || delayedStop || lastStartId != 0) {
            long startToken = proto.start(ServiceRecordProto.START);
            proto.write(ServiceRecordProto.Start.START_REQUESTED, startRequested);
            proto.write(ServiceRecordProto.Start.DELAYED_STOP, delayedStop);
            proto.write(ServiceRecordProto.Start.STOP_IF_KILLED, stopIfKilled);
            proto.write(ServiceRecordProto.Start.LAST_START_ID, lastStartId);
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
        if (crashCount != 0 || restartCount != 0 || restartDelay != 0 || nextRestartTime != 0) {
            long crashToken = proto.start(ServiceRecordProto.CRASH);
            proto.write(ServiceRecordProto.Crash.RESTART_COUNT, restartCount);
            ProtoUtils.toDuration(proto, ServiceRecordProto.Crash.RESTART_DELAY, restartDelay, now);
            ProtoUtils.toDuration(proto,
                    ServiceRecordProto.Crash.NEXT_RESTART_TIME, nextRestartTime, now);
            proto.write(ServiceRecordProto.Crash.CRASH_COUNT, crashCount);
            proto.end(crashToken);
        }

        if (deliveredStarts.size() > 0) {
            final int N = deliveredStarts.size();
            for (int i = 0; i < N; i++) {
                deliveredStarts.get(i).writeToProto(proto,
                        ServiceRecordProto.DELIVERED_STARTS, now);
            }
        }
        if (pendingStarts.size() > 0) {
            final int N = pendingStarts.size();
            for (int i = 0; i < N; i++) {
                pendingStarts.get(i).writeToProto(proto, ServiceRecordProto.PENDING_STARTS, now);
            }
        }
        if (bindings.size() > 0) {
            final int N = bindings.size();
            for (int i=0; i<N; i++) {
                IntentBindRecord b = bindings.valueAt(i);
                b.writeToProto(proto, ServiceRecordProto.BINDINGS);
            }
        }
        if (connections.size() > 0) {
            final int N = connections.size();
            for (int conni=0; conni<N; conni++) {
                ArrayList<ConnectionRecord> c = connections.valueAt(conni);
                for (int i=0; i<c.size(); i++) {
                    c.get(i).writeToProto(proto, ServiceRecordProto.CONNECTIONS);
                }
            }
        }
        proto.end(token);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("intent={");
                pw.print(intent.getIntent().toShortString(false, true, false, true));
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
        if (isolatedProc != null) {
            pw.print(prefix); pw.print("isolatedProc="); pw.println(isolatedProc);
        }
        if (whitelistManager) {
            pw.print(prefix); pw.print("whitelistManager="); pw.println(whitelistManager);
        }
        if (hasBindingWhitelistingBgActivityStarts) {
            pw.print(prefix); pw.print("hasBindingWhitelistingBgActivityStarts=");
            pw.println(hasBindingWhitelistingBgActivityStarts);
        }
        if (hasStartedWhitelistingBgActivityStarts) {
            pw.print(prefix); pw.print("hasStartedWhitelistingBgActivityStarts=");
            pw.println(hasStartedWhitelistingBgActivityStarts);
        }
        if (delayed) {
            pw.print(prefix); pw.print("delayed="); pw.println(delayed);
        }
        if (isForeground || foregroundId != 0) {
            pw.print(prefix); pw.print("isForeground="); pw.print(isForeground);
                    pw.print(" foregroundId="); pw.print(foregroundId);
                    pw.print(" foregroundNoti="); pw.println(foregroundNoti);
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
                || restartDelay != 0 || nextRestartTime != 0) {
            pw.print(prefix); pw.print("restartCount="); pw.print(restartCount);
                    pw.print(" restartDelay=");
                    TimeUtils.formatDuration(restartDelay, now, pw);
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

    ServiceRecord(ActivityManagerService ams,
            BatteryStatsImpl.Uid.Pkg.Serv servStats, ComponentName name,
            ComponentName instanceName,
            Intent.FilterComparison intent, ServiceInfo sInfo, boolean callerIsFg,
            Runnable restarter) {
        this.ams = ams;
        this.stats = servStats;
        this.name = name;
        this.instanceName = instanceName;
        shortInstanceName = instanceName.flattenToShortString();
        this.intent = intent;
        serviceInfo = sInfo;
        appInfo = sInfo.applicationInfo;
        packageName = sInfo.applicationInfo.packageName;
        if ((sInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0) {
            processName = sInfo.processName + ":" + instanceName.getClassName();
        } else {
            processName = sInfo.processName;
        }
        permission = sInfo.permission;
        exported = sInfo.exported;
        this.restarter = restarter;
        createRealTime = SystemClock.elapsedRealtime();
        lastActivity = SystemClock.uptimeMillis();
        userId = UserHandle.getUserId(appInfo.uid);
        createdFromFg = callerIsFg;
    }

    public ServiceState getTracker() {
        if (tracker != null) {
            return tracker;
        }
        if ((serviceInfo.applicationInfo.flags&ApplicationInfo.FLAG_PERSISTENT) == 0) {
            tracker = ams.mProcessStats.getServiceStateLocked(serviceInfo.packageName,
                    serviceInfo.applicationInfo.uid, serviceInfo.applicationInfo.versionCode,
                    serviceInfo.processName, serviceInfo.name);
            tracker.applyNewOwner(this);
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
                restartTracker = ams.mProcessStats.getServiceStateLocked(serviceInfo.packageName,
                        serviceInfo.applicationInfo.uid, serviceInfo.applicationInfo.versionCode,
                        serviceInfo.processName, serviceInfo.name);
            }
            if (restartTracker == null) {
                return;
            }
        }
        restartTracker.setRestarting(true, memFactor, now);
    }

    public void setProcess(ProcessRecord _proc) {
        if (_proc != null) {
            if (hasStartedWhitelistingBgActivityStarts || hasBindingWhitelistingBgActivityStarts) {
                _proc.addAllowBackgroundActivityStartsToken(this);
            } else {
                _proc.removeAllowBackgroundActivityStartsToken(this);
            }
        } else if (app != null) {
            app.removeAllowBackgroundActivityStartsToken(this);
        }
        app = _proc;
        if (pendingConnectionGroup > 0 && _proc != null) {
            _proc.connectionService = this;
            _proc.connectionGroup = pendingConnectionGroup;
            _proc.connectionImportance = pendingConnectionImportance;
            pendingConnectionGroup = pendingConnectionImportance = 0;
        }
        if (ActivityManagerService.TRACK_PROCSTATS_ASSOCIATIONS) {
            for (int conni = connections.size() - 1; conni >= 0; conni--) {
                ArrayList<ConnectionRecord> cr = connections.valueAt(conni);
                for (int i = 0; i < cr.size(); i++) {
                    final ConnectionRecord conn = cr.get(i);
                    if (_proc != null) {
                        conn.startAssociationIfNeeded();
                    } else {
                        conn.stopAssociation();
                    }
                }
            }
        }
    }

    void updateHasBindingWhitelistingBgActivityStarts() {
        boolean hasWhitelistingBinding = false;
        for (int conni = connections.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> cr = connections.valueAt(conni);
            for (int i = 0; i < cr.size(); i++) {
                if ((cr.get(i).flags & Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS) != 0) {
                    hasWhitelistingBinding = true;
                    break;
                }
            }
            if (hasWhitelistingBinding) {
                break;
            }
        }
        if (hasBindingWhitelistingBgActivityStarts != hasWhitelistingBinding) {
            hasBindingWhitelistingBgActivityStarts = hasWhitelistingBinding;
            updateParentProcessBgActivityStartsWhitelistingToken();
        }
    }

    void setHasBindingWhitelistingBgActivityStarts(boolean newValue) {
        if (hasBindingWhitelistingBgActivityStarts != newValue) {
            hasBindingWhitelistingBgActivityStarts = newValue;
            updateParentProcessBgActivityStartsWhitelistingToken();
        }
    }

    void setHasStartedWhitelistingBgActivityStarts(boolean newValue) {
        if (hasStartedWhitelistingBgActivityStarts != newValue) {
            hasStartedWhitelistingBgActivityStarts = newValue;
            updateParentProcessBgActivityStartsWhitelistingToken();
        }
    }

    /**
     * Whether the process this service runs in should be temporarily whitelisted to start
     * activities from background depends on the current state of both
     * {@code hasStartedWhitelistingBgActivityStarts} and
     * {@code hasBindingWhitelistingBgActivityStarts}. If either is true, this ServiceRecord
     * should be contributing as a token in parent ProcessRecord.
     *
     * @see com.android.server.am.ProcessRecord#mAllowBackgroundActivityStartsTokens
     */
    private void updateParentProcessBgActivityStartsWhitelistingToken() {
        if (app == null) {
            return;
        }
        if (hasStartedWhitelistingBgActivityStarts || hasBindingWhitelistingBgActivityStarts) {
            // if the token is already there it's safe to "re-add it" - we're deadling with
            // a set of Binder objects
            app.addAllowBackgroundActivityStartsToken(this);
        } else {
            app.removeAllowBackgroundActivityStartsToken(this);
        }
    }

    public AppBindRecord retrieveAppBindingLocked(Intent intent,
            ProcessRecord app) {
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
        a = new AppBindRecord(this, i, app);
        i.apps.put(app, a);
        return a;
    }

    public boolean hasAutoCreateConnections() {
        // XXX should probably keep a count of the number of auto-create
        // connections directly in the service.
        for (int conni=connections.size()-1; conni>=0; conni--) {
            ArrayList<ConnectionRecord> cr = connections.valueAt(conni);
            for (int i=0; i<cr.size(); i++) {
                if ((cr.get(i).flags&Context.BIND_AUTO_CREATE) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    public void updateWhitelistManager() {
        whitelistManager = false;
        for (int conni=connections.size()-1; conni>=0; conni--) {
            ArrayList<ConnectionRecord> cr = connections.valueAt(conni);
            for (int i=0; i<cr.size(); i++) {
                if ((cr.get(i).flags&Context.BIND_ALLOW_WHITELIST_MANAGEMENT) != 0) {
                    whitelistManager = true;
                    return;
                }
            }
        }
    }

    public void resetRestartCounter() {
        restartCount = 0;
        restartDelay = 0;
        restartTime = 0;
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

    public void postNotification() {
        final int appUid = appInfo.uid;
        final int appPid = app.pid;
        if (foregroundId != 0 && foregroundNoti != null) {
            // Do asynchronous communication with notification manager to
            // avoid deadlocks.
            final String localPackageName = packageName;
            final int localForegroundId = foregroundId;
            final Notification _foregroundNoti = foregroundNoti;
            ams.mHandler.post(new Runnable() {
                public void run() {
                    NotificationManagerInternal nm = LocalServices.getService(
                            NotificationManagerInternal.class);
                    if (nm == null) {
                        return;
                    }
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
                                        runningIntent, PendingIntent.FLAG_UPDATE_CURRENT, null,
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
                            // a notification, silently ignoring it.  We don't want to
                            // just ignore it, we want to prevent the service from
                            // being foreground.
                            throw new RuntimeException("invalid service notification: "
                                    + foregroundNoti);
                        }
                        nm.enqueueNotification(localPackageName, localPackageName,
                                appUid, appPid, null, localForegroundId, localForegroundNoti,
                                userId);

                        foregroundNoti = localForegroundNoti; // save it for amending next time
                    } catch (RuntimeException e) {
                        Slog.w(TAG, "Error showing notification for service", e);
                        // If it gave us a garbage notification, it doesn't
                        // get to be foreground.
                        ams.setServiceForeground(instanceName, ServiceRecord.this,
                                0, null, 0, 0);
                        ams.crashApplication(appUid, appPid, localPackageName, -1,
                                "Bad notification for startForeground: " + e);
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
        ams.mHandler.post(new Runnable() {
            public void run() {
                INotificationManager inm = NotificationManager.getService();
                if (inm == null) {
                    return;
                }
                try {
                    inm.cancelNotificationWithTag(localPackageName, null,
                            localForegroundId, userId);
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Error canceling notification for service", e);
                } catch (RemoteException e) {
                }
            }
        });
    }

    public void stripForegroundServiceFlagFromNotification() {
        if (foregroundId == 0) {
            return;
        }

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
}
