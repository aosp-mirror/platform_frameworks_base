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

import android.app.PendingIntent;
import android.net.Uri;
import android.provider.Settings;
import com.android.internal.os.BatteryStatsImpl;
import com.android.server.NotificationManagerService;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

/**
 * A running application service.
 */
class ServiceRecord extends Binder {
    // Maximum number of delivery attempts before giving up.
    static final int MAX_DELIVERY_COUNT = 3;

    // Maximum number of times it can fail during execution before giving up.
    static final int MAX_DONE_EXECUTING_COUNT = 6;

    final ActivityManagerService ams;
    final BatteryStatsImpl.Uid.Pkg.Serv stats;
    final ComponentName name; // service component.
    final String shortName; // name.flattenToShortString().
    final Intent.FilterComparison intent;
                            // original intent used to find service.
    final ServiceInfo serviceInfo;
                            // all information about the service.
    final ApplicationInfo appInfo;
                            // information about service's app.
    final int userId;       // user that this service is running as
    final String packageName; // the package implementing intent's component
    final String processName; // process where this component wants to run
    final String permission;// permission needed to access service
    final String baseDir;   // where activity source (resources etc) located
    final String resDir;   // where public activity source (public resources etc) located
    final String dataDir;   // where activity data should go
    final boolean exported; // from ServiceInfo.exported
    final Runnable restarter; // used to schedule retries of starting the service
    final long createTime;  // when this service was created
    final HashMap<Intent.FilterComparison, IntentBindRecord> bindings
            = new HashMap<Intent.FilterComparison, IntentBindRecord>();
                            // All active bindings to the service.
    final HashMap<IBinder, ArrayList<ConnectionRecord>> connections
            = new HashMap<IBinder, ArrayList<ConnectionRecord>>();
                            // IBinder -> ConnectionRecord of all bound clients

    ProcessRecord app;      // where this service is running or null.
    ProcessRecord isolatedProc; // keep track of isolated process, if requested
    boolean isForeground;   // is service currently in foreground mode?
    int foregroundId;       // Notification ID of last foreground req.
    Notification foregroundNoti; // Notification record of foreground state.
    long lastActivity;      // last time there was some activity on the service.
    boolean startRequested; // someone explicitly called start?
    boolean stopIfKilled;   // last onStart() said to stop if service killed?
    boolean callStart;      // last onStart() has asked to alway be called on restart.
    int executeNesting;     // number of outstanding operations keeping foreground.
    long executingStart;    // start time of last execute request.
    int crashCount;         // number of times proc has crashed with service running
    int totalRestartCount;  // number of times we have had to restart.
    int restartCount;       // number of restarts performed in a row.
    long restartDelay;      // delay until next restart attempt.
    long restartTime;       // time of last restart.
    long nextRestartTime;   // time when restartDelay will expire.

    String stringName;      // caching of toString
    
    private int lastStartId;    // identifier of most recent start request.

    static class StartItem {
        final ServiceRecord sr;
        final boolean taskRemoved;
        final int id;
        final Intent intent;
        final ActivityManagerService.NeededUriGrants neededGrants;
        long deliveredTime;
        int deliveryCount;
        int doneExecutingCount;
        UriPermissionOwner uriPermissions;

        String stringName;      // caching of toString

        StartItem(ServiceRecord _sr, boolean _taskRemoved, int _id, Intent _intent,
                ActivityManagerService.NeededUriGrants _neededGrants) {
            sr = _sr;
            taskRemoved = _taskRemoved;
            id = _id;
            intent = _intent;
            neededGrants = _neededGrants;
        }

        UriPermissionOwner getUriPermissionsLocked() {
            if (uriPermissions == null) {
                uriPermissions = new UriPermissionOwner(sr.ams, this);
            }
            return uriPermissions;
        }

        void removeUriPermissionsLocked() {
            if (uriPermissions != null) {
                uriPermissions.removeUriPermissionsLocked();
                uriPermissions = null;
            }
        }

        public String toString() {
            if (stringName != null) {
                return stringName;
            }
            StringBuilder sb = new StringBuilder(128);
            sb.append("ServiceRecord{")
                .append(Integer.toHexString(System.identityHashCode(sr)))
                .append(' ').append(sr.shortName)
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
                if (si.uriPermissions.readUriPermissions != null) {
                    pw.print(prefix); pw.print("  readUriPermissions=");
                            pw.println(si.uriPermissions.readUriPermissions);
                }
                if (si.uriPermissions.writeUriPermissions != null) {
                    pw.print(prefix); pw.print("  writeUriPermissions=");
                            pw.println(si.uriPermissions.writeUriPermissions);
                }
            }
        }
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
        pw.print(prefix); pw.print("baseDir="); pw.println(baseDir);
        if (!resDir.equals(baseDir)) {
            pw.print(prefix); pw.print("resDir="); pw.println(resDir);
        }
        pw.print(prefix); pw.print("dataDir="); pw.println(dataDir);
        pw.print(prefix); pw.print("app="); pw.println(app);
        if (isolatedProc != null) {
            pw.print(prefix); pw.print("isolatedProc="); pw.println(isolatedProc);
        }
        if (isForeground || foregroundId != 0) {
            pw.print(prefix); pw.print("isForeground="); pw.print(isForeground);
                    pw.print(" foregroundId="); pw.print(foregroundId);
                    pw.print(" foregroundNoti="); pw.println(foregroundNoti);
        }
        pw.print(prefix); pw.print("createTime=");
                TimeUtils.formatDuration(createTime, nowReal, pw);
                pw.print(" lastActivity=");
                TimeUtils.formatDuration(lastActivity, now, pw);
                pw.println("");
        pw.print(prefix); pw.print("executingStart=");
                TimeUtils.formatDuration(executingStart, now, pw);
                pw.print(" restartTime=");
                TimeUtils.formatDuration(restartTime, now, pw);
                pw.println("");
        if (startRequested || lastStartId != 0) {
            pw.print(prefix); pw.print("startRequested="); pw.print(startRequested);
                    pw.print(" stopIfKilled="); pw.print(stopIfKilled);
                    pw.print(" callStart="); pw.print(callStart);
                    pw.print(" lastStartId="); pw.println(lastStartId);
        }
        if (executeNesting != 0 || crashCount != 0 || restartCount != 0
                || restartDelay != 0 || nextRestartTime != 0) {
            pw.print(prefix); pw.print("executeNesting="); pw.print(executeNesting);
                    pw.print(" restartCount="); pw.print(restartCount);
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
            Iterator<IntentBindRecord> it = bindings.values().iterator();
            pw.print(prefix); pw.println("Bindings:");
            while (it.hasNext()) {
                IntentBindRecord b = it.next();
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
            Iterator<ArrayList<ConnectionRecord>> it = connections.values().iterator();
            while (it.hasNext()) {
                ArrayList<ConnectionRecord> c = it.next();
                for (int i=0; i<c.size(); i++) {
                    pw.print(prefix); pw.print("  "); pw.println(c.get(i));
                }
            }
        }
    }

    ServiceRecord(ActivityManagerService ams,
            BatteryStatsImpl.Uid.Pkg.Serv servStats, ComponentName name,
            Intent.FilterComparison intent, ServiceInfo sInfo, Runnable restarter) {
        this.ams = ams;
        this.stats = servStats;
        this.name = name;
        shortName = name.flattenToShortString();
        this.intent = intent;
        serviceInfo = sInfo;
        appInfo = sInfo.applicationInfo;
        packageName = sInfo.applicationInfo.packageName;
        processName = sInfo.processName;
        permission = sInfo.permission;
        baseDir = sInfo.applicationInfo.sourceDir;
        resDir = sInfo.applicationInfo.publicSourceDir;
        dataDir = sInfo.applicationInfo.dataDir;
        exported = sInfo.exported;
        this.restarter = restarter;
        createTime = SystemClock.elapsedRealtime();
        lastActivity = SystemClock.uptimeMillis();
        userId = UserHandle.getUserId(appInfo.uid);
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

    public void resetRestartCounter() {
        restartCount = 0;
        restartDelay = 0;
        restartTime = 0;
    }
    
    public StartItem findDeliveredStart(int id, boolean remove) {
        final int N = deliveredStarts.size();
        for (int i=0; i<N; i++) {
            StartItem si = deliveredStarts.get(i);
            if (si.id == id) {
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
            final Notification localForegroundNoti = foregroundNoti;
            ams.mHandler.post(new Runnable() {
                public void run() {
                    NotificationManagerService nm =
                            (NotificationManagerService) NotificationManager.getService();
                    if (nm == null) {
                        return;
                    }
                    try {
                        if (localForegroundNoti.icon == 0) {
                            // It is not correct for the caller to supply a notification
                            // icon, but this used to be able to slip through, so for
                            // those dirty apps give it the app's icon.
                            localForegroundNoti.icon = appInfo.icon;

                            // Do not allow apps to present a sneaky invisible content view either.
                            localForegroundNoti.contentView = null;
                            localForegroundNoti.bigContentView = null;
                            CharSequence appName = appInfo.loadLabel(
                                    ams.mContext.getPackageManager());
                            if (appName == null) {
                                appName = appInfo.packageName;
                            }
                            Context ctx = null;
                            try {
                                ctx = ams.mContext.createPackageContext(
                                        appInfo.packageName, 0);
                                Intent runningIntent = new Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                runningIntent.setData(Uri.fromParts("package",
                                        appInfo.packageName, null));
                                PendingIntent pi = PendingIntent.getActivity(ams.mContext, 0,
                                        runningIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                                localForegroundNoti.setLatestEventInfo(ctx,
                                        ams.mContext.getString(
                                                com.android.internal.R.string
                                                        .app_running_notification_title,
                                                appName),
                                        ams.mContext.getString(
                                                com.android.internal.R.string
                                                        .app_running_notification_text,
                                                appName),
                                        pi);
                            } catch (PackageManager.NameNotFoundException e) {
                                localForegroundNoti.icon = 0;
                            }
                        }
                        if (localForegroundNoti.icon == 0) {
                            // Notifications whose icon is 0 are defined to not show
                            // a notification, silently ignoring it.  We don't want to
                            // just ignore it, we want to prevent the service from
                            // being foreground.
                            throw new RuntimeException("icon must be non-zero");
                        }
                        int[] outId = new int[1];
                        nm.enqueueNotificationInternal(localPackageName, localPackageName,
                                appUid, appPid, null, localForegroundId, localForegroundNoti,
                                outId, userId);
                    } catch (RuntimeException e) {
                        Slog.w(ActivityManagerService.TAG,
                                "Error showing notification for service", e);
                        // If it gave us a garbage notification, it doesn't
                        // get to be foreground.
                        ams.setServiceForeground(name, ServiceRecord.this,
                                0, null, true);
                        ams.crashApplication(appUid, appPid, localPackageName,
                                "Bad notification for startForeground: " + e);
                    }
                }
            });
        }
    }
    
    public void cancelNotification() {
        if (foregroundId != 0) {
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
                        Slog.w(ActivityManagerService.TAG,
                                "Error canceling notification for service", e);
                    } catch (RemoteException e) {
                    }
                }
            });
        }
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
            .append(' ').append(shortName).append('}');
        return stringName = sb.toString();
    }
}
