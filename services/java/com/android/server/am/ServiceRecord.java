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

import com.android.internal.os.BatteryStatsImpl;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * A running application service.
 */
class ServiceRecord extends Binder {
    final BatteryStatsImpl.Uid.Pkg.Serv stats;
    final ComponentName name; // service component.
    final String shortName; // name.flattenToShortString().
    final Intent.FilterComparison intent;
                            // original intent used to find service.
    final ServiceInfo serviceInfo;
                            // all information about the service.
    final ApplicationInfo appInfo;
                            // information about service's app.
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
    final HashMap<IBinder, ConnectionRecord> connections
            = new HashMap<IBinder, ConnectionRecord>();
                            // IBinder -> ConnectionRecord of all bound clients
    final List<Intent> startArgs = new ArrayList<Intent>();
                            // start() arguments that haven't yet been delivered.

    ProcessRecord app;      // where this service is running or null.
    boolean isForeground;   // is service currently in foreground mode?
    int foregroundId;       // Notification ID of last foreground req.
    Notification foregroundNoti; // Notification record of foreground state.
    long lastActivity;      // last time there was some activity on the service.
    boolean startRequested; // someone explicitly called start?
    int lastStartId;        // identifier of most recent start request.
    int executeNesting;     // number of outstanding operations keeping foreground.
    long executingStart;    // start time of last execute request.
    int crashCount;         // number of times proc has crashed with service running
    int totalRestartCount;  // number of times we have had to restart.
    int restartCount;       // number of restarts performed in a row.
    long restartDelay;      // delay until next restart attempt.
    long restartTime;       // time of last restart.
    long nextRestartTime;   // time when restartDelay will expire.

    String stringName;      // caching of toString
    
    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("intent={");
                pw.print(intent.getIntent().toShortString(true, false));
                pw.println('}');
        pw.print(prefix); pw.print("packageName="); pw.println(packageName);
        pw.print(prefix); pw.print("processName="); pw.println(processName);
        if (permission != null) {
            pw.print(prefix); pw.print("permission="); pw.println(permission);
        }
        pw.print(prefix); pw.print("baseDir="); pw.print(baseDir);
                if (!resDir.equals(baseDir)) pw.print(" resDir="); pw.print(resDir);
                pw.print(" dataDir="); pw.println(dataDir);
        pw.print(prefix); pw.print("app="); pw.println(app);
        if (isForeground || foregroundId != 0) {
            pw.print(prefix); pw.print("isForeground="); pw.print(isForeground);
                    pw.print(" foregroundId="); pw.print(foregroundId);
                    pw.print(" foregroundNoti="); pw.println(foregroundNoti);
        }
        pw.print(prefix); pw.print("lastActivity="); pw.print(lastActivity);
                pw.print(" executingStart="); pw.print(executingStart);
                pw.print(" restartTime="); pw.println(restartTime);
        if (startRequested || lastStartId != 0) {
            pw.print(prefix); pw.print("startRequested="); pw.print(startRequested);
                    pw.print(" lastStartId="); pw.println(lastStartId);
        }
        if (executeNesting != 0 || crashCount != 0 || restartCount != 0
                || restartDelay != 0 || nextRestartTime != 0) {
            pw.print(prefix); pw.print("executeNesting="); pw.print(executeNesting);
                    pw.print(" restartCount="); pw.print(restartCount);
                    pw.print(" restartDelay="); pw.print(restartDelay);
                    pw.print(" nextRestartTime="); pw.print(nextRestartTime);
                    pw.print(" crashCount="); pw.println(crashCount);
        }
        if (bindings.size() > 0) {
            Iterator<IntentBindRecord> it = bindings.values().iterator();
            while (it.hasNext()) {
                IntentBindRecord b = it.next();
                pw.print(prefix); pw.print("* IntentBindRecord{");
                        pw.print(Integer.toHexString(System.identityHashCode(b)));
                        pw.println("}:");
                b.dumpInService(pw, prefix + "  ");
            }
        }
        if (connections.size() > 0) {
            pw.print(prefix); pw.println("All Connections:");
            Iterator<ConnectionRecord> it = connections.values().iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                pw.print(prefix); pw.print("  "); pw.println(c);
            }
        }
    }

    ServiceRecord(BatteryStatsImpl.Uid.Pkg.Serv servStats, ComponentName name,
            Intent.FilterComparison intent, ServiceInfo sInfo, Runnable restarter) {
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
        createTime = lastActivity = SystemClock.uptimeMillis();
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
    
    public void postNotification() {
        if (foregroundId != 0 && foregroundNoti != null) {
            INotificationManager inm = NotificationManager.getService();
            if (inm != null) {
                try {
                    int[] outId = new int[1];
                    inm.enqueueNotification(packageName, foregroundId,
                            foregroundNoti, outId);
                } catch (RemoteException e) {
                }
            }
        }
    }
    
    public void cancelNotification() {
        if (foregroundId != 0) {
            INotificationManager inm = NotificationManager.getService();
            if (inm != null) {
                try {
                    inm.cancelNotification(packageName, foregroundId);
                } catch (RemoteException e) {
                }
            }
        }
    }
    
    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ServiceRecord{")
            .append(Integer.toHexString(System.identityHashCode(this)))
            .append(' ').append(shortName).append('}');
        return stringName = sb.toString();
    }
}
