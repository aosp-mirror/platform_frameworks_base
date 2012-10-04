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

import android.app.ActivityManager;
import android.app.Dialog;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.PrintWriterPrinter;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Full information about a particular process that
 * is currently running.
 */
class ProcessRecord {
    final BatteryStatsImpl.Uid.Proc batteryStats; // where to collect runtime statistics
    final ApplicationInfo info; // all about the first app in the process
    final boolean isolated;     // true if this is a special isolated process
    final int uid;              // uid of process; may be different from 'info' if isolated
    final int userId;           // user of process.
    final String processName;   // name of the process
    // List of packages running in the process
    final HashSet<String> pkgList = new HashSet<String>();
    IApplicationThread thread;  // the actual proc...  may be null only if
                                // 'persistent' is true (in which case we
                                // are in the process of launching the app)
    int pid;                    // The process of this application; 0 if none
    boolean starting;           // True if the process is being started
    long lastActivityTime;      // For managing the LRU list
    long lruWeight;             // Weight for ordering in LRU list
    int maxAdj;                 // Maximum OOM adjustment for this process
    int hiddenAdj;              // If hidden, this is the adjustment to use
    int clientHiddenAdj;        // If empty but hidden client, this is the adjustment to use
    int emptyAdj;               // If empty, this is the adjustment to use
    int curRawAdj;              // Current OOM unlimited adjustment for this process
    int setRawAdj;              // Last set OOM unlimited adjustment for this process
    int nonStoppingAdj;         // Adjustment not counting any stopping activities
    int curAdj;                 // Current OOM adjustment for this process
    int setAdj;                 // Last set OOM adjustment for this process
    int curSchedGroup;          // Currently desired scheduling class
    int setSchedGroup;          // Last set to background scheduling class
    int trimMemoryLevel;        // Last selected memory trimming level
    int memImportance;          // Importance constant computed from curAdj
    boolean serviceb;           // Process currently is on the service B list
    boolean keeping;            // Actively running code so don't kill due to that?
    boolean setIsForeground;    // Running foreground UI when last set?
    boolean hasActivities;      // Are there any activities running in this process?
    boolean hasClientActivities;  // Are there any client services with activities?
    boolean foregroundServices; // Running any services that are foreground?
    boolean foregroundActivities; // Running any activities that are foreground?
    boolean systemNoUi;         // This is a system process, but not currently showing UI.
    boolean hasShownUi;         // Has UI been shown in this process since it was started?
    boolean pendingUiClean;     // Want to clean up resources from showing UI?
    boolean hasAboveClient;     // Bound using BIND_ABOVE_CLIENT, so want to be lower
    boolean bad;                // True if disabled in the bad process list
    boolean killedBackground;   // True when proc has been killed due to too many bg
    String waitingToKill;       // Process is waiting to be killed when in the bg; reason
    IBinder forcingToForeground;// Token that is forcing this process to be foreground
    int adjSeq;                 // Sequence id for identifying oom_adj assignment cycles
    int lruSeq;                 // Sequence id for identifying LRU update cycles
    CompatibilityInfo compat;   // last used compatibility mode
    IBinder.DeathRecipient deathRecipient; // Who is watching for the death.
    ComponentName instrumentationClass;// class installed to instrument app
    ApplicationInfo instrumentationInfo; // the application being instrumented
    String instrumentationProfileFile; // where to save profiling
    IInstrumentationWatcher instrumentationWatcher; // who is waiting
    Bundle instrumentationArguments;// as given to us
    ComponentName instrumentationResultClass;// copy of instrumentationClass
    boolean usingWrapper;       // Set to true when process was launched with a wrapper attached
    BroadcastRecord curReceiver;// receiver currently running in the app
    long lastWakeTime;          // How long proc held wake lock at last check
    long lastCpuTime;           // How long proc has run CPU at last check
    long curCpuTime;            // How long proc has run CPU most recently
    long lastRequestedGc;       // When we last asked the app to do a gc
    long lastLowMemory;         // When we last told the app that memory is low
    boolean reportLowMemory;    // Set to true when waiting to report low mem
    boolean empty;              // Is this an empty background process?
    boolean hidden;             // Is this a hidden process?
    int lastPss;                // Last pss size reported by app.
    String adjType;             // Debugging: primary thing impacting oom_adj.
    int adjTypeCode;            // Debugging: adj code to report to app.
    Object adjSource;           // Debugging: option dependent object.
    int adjSourceOom;           // Debugging: oom_adj of adjSource's process.
    Object adjTarget;           // Debugging: target component impacting oom_adj.
    
    // contains HistoryRecord objects
    final ArrayList<ActivityRecord> activities = new ArrayList<ActivityRecord>();
    // all ServiceRecord running in this process
    final HashSet<ServiceRecord> services = new HashSet<ServiceRecord>();
    // services that are currently executing code (need to remain foreground).
    final HashSet<ServiceRecord> executingServices
             = new HashSet<ServiceRecord>();
    // All ConnectionRecord this process holds
    final HashSet<ConnectionRecord> connections
            = new HashSet<ConnectionRecord>();  
    // all IIntentReceivers that are registered from this process.
    final HashSet<ReceiverList> receivers = new HashSet<ReceiverList>();
    // class (String) -> ContentProviderRecord
    final HashMap<String, ContentProviderRecord> pubProviders
            = new HashMap<String, ContentProviderRecord>(); 
    // All ContentProviderRecord process is using
    final ArrayList<ContentProviderConnection> conProviders
            = new ArrayList<ContentProviderConnection>();
    
    boolean persistent;         // always keep this application running?
    boolean crashing;           // are we in the process of crashing?
    Dialog crashDialog;         // dialog being displayed due to crash.
    boolean notResponding;      // does the app have a not responding dialog?
    Dialog anrDialog;           // dialog being displayed due to app not resp.
    boolean removed;            // has app package been removed from device?
    boolean debugging;          // was app launched for debugging?
    boolean waitedForDebugger;  // has process show wait for debugger dialog?
    Dialog waitDialog;          // current wait for debugger dialog
    
    String shortStringName;     // caching of toShortString() result.
    String stringName;          // caching of toString() result.
    
    // These reports are generated & stored when an app gets into an error condition.
    // They will be "null" when all is OK.
    ActivityManager.ProcessErrorStateInfo crashingReport;
    ActivityManager.ProcessErrorStateInfo notRespondingReport;

    // Who will be notified of the error. This is usually an activity in the
    // app that installed the package.
    ComponentName errorReportReceiver;

    void dump(PrintWriter pw, String prefix) {
        final long now = SystemClock.uptimeMillis();

        pw.print(prefix); pw.print("user #"); pw.print(userId);
                pw.print(" uid="); pw.print(info.uid);
        if (uid != info.uid) {
            pw.print(" ISOLATED uid="); pw.print(uid);
        }
        pw.println();
        if (info.className != null) {
            pw.print(prefix); pw.print("class="); pw.println(info.className);
        }
        if (info.manageSpaceActivityName != null) {
            pw.print(prefix); pw.print("manageSpaceActivityName=");
            pw.println(info.manageSpaceActivityName);
        }
        pw.print(prefix); pw.print("dir="); pw.print(info.sourceDir);
                pw.print(" publicDir="); pw.print(info.publicSourceDir);
                pw.print(" data="); pw.println(info.dataDir);
        pw.print(prefix); pw.print("packageList="); pw.println(pkgList);
        pw.print(prefix); pw.print("compat="); pw.println(compat);
        if (instrumentationClass != null || instrumentationProfileFile != null
                || instrumentationArguments != null) {
            pw.print(prefix); pw.print("instrumentationClass=");
                    pw.print(instrumentationClass);
                    pw.print(" instrumentationProfileFile=");
                    pw.println(instrumentationProfileFile);
            pw.print(prefix); pw.print("instrumentationArguments=");
                    pw.println(instrumentationArguments);
            pw.print(prefix); pw.print("instrumentationInfo=");
                    pw.println(instrumentationInfo);
            if (instrumentationInfo != null) {
                instrumentationInfo.dump(new PrintWriterPrinter(pw), prefix + "  ");
            }
        }
        pw.print(prefix); pw.print("thread="); pw.println(thread);
        pw.print(prefix); pw.print("pid="); pw.print(pid); pw.print(" starting=");
                pw.print(starting); pw.print(" lastPss="); pw.println(lastPss);
        pw.print(prefix); pw.print("lastActivityTime=");
                TimeUtils.formatDuration(lastActivityTime, now, pw);
                pw.print(" lruWeight="); pw.print(lruWeight);
                pw.print(" serviceb="); pw.print(serviceb);
                pw.print(" keeping="); pw.print(keeping);
                pw.print(" hidden="); pw.print(hidden);
                pw.print(" empty="); pw.println(empty);
        pw.print(prefix); pw.print("oom: max="); pw.print(maxAdj);
                pw.print(" hidden="); pw.print(hiddenAdj);
                pw.print(" client="); pw.print(clientHiddenAdj);
                pw.print(" empty="); pw.print(emptyAdj);
                pw.print(" curRaw="); pw.print(curRawAdj);
                pw.print(" setRaw="); pw.print(setRawAdj);
                pw.print(" nonStopping="); pw.print(nonStoppingAdj);
                pw.print(" cur="); pw.print(curAdj);
                pw.print(" set="); pw.println(setAdj);
        pw.print(prefix); pw.print("curSchedGroup="); pw.print(curSchedGroup);
                pw.print(" setSchedGroup="); pw.print(setSchedGroup);
                pw.print(" systemNoUi="); pw.print(systemNoUi);
                pw.print(" trimMemoryLevel="); pw.println(trimMemoryLevel);
        pw.print(prefix); pw.print("adjSeq="); pw.print(adjSeq);
                pw.print(" lruSeq="); pw.println(lruSeq);
        if (hasShownUi || pendingUiClean || hasAboveClient) {
            pw.print(prefix); pw.print("hasShownUi="); pw.print(hasShownUi);
                    pw.print(" pendingUiClean="); pw.print(pendingUiClean);
                    pw.print(" hasAboveClient="); pw.println(hasAboveClient);
        }
        if (setIsForeground || foregroundServices || forcingToForeground != null) {
            pw.print(prefix); pw.print("setIsForeground="); pw.print(setIsForeground);
                    pw.print(" foregroundServices="); pw.print(foregroundServices);
                    pw.print(" forcingToForeground="); pw.println(forcingToForeground);
        }
        if (persistent || removed) {
            pw.print(prefix); pw.print("persistent="); pw.print(persistent);
                    pw.print(" removed="); pw.println(removed);
        }
        if (hasActivities || hasClientActivities || foregroundActivities) {
            pw.print(prefix); pw.print("hasActivities="); pw.print(hasActivities);
                    pw.print(" hasClientActivities="); pw.print(hasClientActivities);
                    pw.print(" foregroundActivities="); pw.println(foregroundActivities);
        }
        if (!keeping) {
            long wtime;
            synchronized (batteryStats.getBatteryStats()) {
                wtime = batteryStats.getBatteryStats().getProcessWakeTime(info.uid,
                        pid, SystemClock.elapsedRealtime());
            }
            long timeUsed = wtime - lastWakeTime;
            pw.print(prefix); pw.print("lastWakeTime="); pw.print(lastWakeTime);
                    pw.print(" timeUsed=");
                    TimeUtils.formatDuration(timeUsed, pw); pw.println("");
            pw.print(prefix); pw.print("lastCpuTime="); pw.print(lastCpuTime);
                    pw.print(" timeUsed=");
                    TimeUtils.formatDuration(curCpuTime-lastCpuTime, pw); pw.println("");
        }
        pw.print(prefix); pw.print("lastRequestedGc=");
                TimeUtils.formatDuration(lastRequestedGc, now, pw);
                pw.print(" lastLowMemory=");
                TimeUtils.formatDuration(lastLowMemory, now, pw);
                pw.print(" reportLowMemory="); pw.println(reportLowMemory);
        if (killedBackground || waitingToKill != null) {
            pw.print(prefix); pw.print("killedBackground="); pw.print(killedBackground);
                    pw.print(" waitingToKill="); pw.println(waitingToKill);
        }
        if (debugging || crashing || crashDialog != null || notResponding
                || anrDialog != null || bad) {
            pw.print(prefix); pw.print("debugging="); pw.print(debugging);
                    pw.print(" crashing="); pw.print(crashing);
                    pw.print(" "); pw.print(crashDialog);
                    pw.print(" notResponding="); pw.print(notResponding);
                    pw.print(" " ); pw.print(anrDialog);
                    pw.print(" bad="); pw.print(bad);

                    // crashing or notResponding is always set before errorReportReceiver
                    if (errorReportReceiver != null) {
                        pw.print(" errorReportReceiver=");
                        pw.print(errorReportReceiver.flattenToShortString());
                    }
                    pw.println();
        }
        if (activities.size() > 0) {
            pw.print(prefix); pw.println("Activities:");
            for (int i=0; i<activities.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(activities.get(i));
            }
        }
        if (services.size() > 0) {
            pw.print(prefix); pw.println("Services:");
            for (ServiceRecord sr : services) {
                pw.print(prefix); pw.print("  - "); pw.println(sr);
            }
        }
        if (executingServices.size() > 0) {
            pw.print(prefix); pw.println("Executing Services:");
            for (ServiceRecord sr : executingServices) {
                pw.print(prefix); pw.print("  - "); pw.println(sr);
            }
        }
        if (connections.size() > 0) {
            pw.print(prefix); pw.println("Connections:");
            for (ConnectionRecord cr : connections) {
                pw.print(prefix); pw.print("  - "); pw.println(cr);
            }
        }
        if (pubProviders.size() > 0) {
            pw.print(prefix); pw.println("Published Providers:");
            for (HashMap.Entry<String, ContentProviderRecord> ent : pubProviders.entrySet()) {
                pw.print(prefix); pw.print("  - "); pw.println(ent.getKey());
                pw.print(prefix); pw.print("    -> "); pw.println(ent.getValue());
            }
        }
        if (conProviders.size() > 0) {
            pw.print(prefix); pw.println("Connected Providers:");
            for (int i=0; i<conProviders.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(conProviders.get(i).toShortString());
            }
        }
        if (curReceiver != null) {
            pw.print(prefix); pw.print("curReceiver="); pw.println(curReceiver);
        }
        if (receivers.size() > 0) {
            pw.print(prefix); pw.println("Receivers:");
            for (ReceiverList rl : receivers) {
                pw.print(prefix); pw.print("  - "); pw.println(rl);
            }
        }
    }
    
    ProcessRecord(BatteryStatsImpl.Uid.Proc _batteryStats, IApplicationThread _thread,
            ApplicationInfo _info, String _processName, int _uid) {
        batteryStats = _batteryStats;
        info = _info;
        isolated = _info.uid != _uid;
        uid = _uid;
        userId = UserHandle.getUserId(_uid);
        processName = _processName;
        pkgList.add(_info.packageName);
        thread = _thread;
        maxAdj = ProcessList.HIDDEN_APP_MAX_ADJ;
        hiddenAdj = clientHiddenAdj = emptyAdj = ProcessList.HIDDEN_APP_MIN_ADJ;
        curRawAdj = setRawAdj = -100;
        curAdj = setAdj = -100;
        persistent = false;
        removed = false;
    }

    public void setPid(int _pid) {
        pid = _pid;
        shortStringName = null;
        stringName = null;
    }
    
    /**
     * This method returns true if any of the activities within the process record are interesting
     * to the user. See HistoryRecord.isInterestingToUserLocked()
     */
    public boolean isInterestingToUserLocked() {
        final int size = activities.size();
        for (int i = 0 ; i < size ; i++) {
            ActivityRecord r = activities.get(i);
            if (r.isInterestingToUserLocked()) {
                return true;
            }
        }
        return false;
    }
    
    public void stopFreezingAllLocked() {
        int i = activities.size();
        while (i > 0) {
            i--;
            activities.get(i).stopFreezingScreenLocked(true);
        }
    }
    
    public void unlinkDeathRecipient() {
        if (deathRecipient != null && thread != null) {
            thread.asBinder().unlinkToDeath(deathRecipient, 0);
        }
        deathRecipient = null;
    }

    void updateHasAboveClientLocked() {
        hasAboveClient = false;
        if (connections.size() > 0) {
            for (ConnectionRecord cr : connections) {
                if ((cr.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                    hasAboveClient = true;
                    break;
                }
            }
        }
    }

    public String toShortString() {
        if (shortStringName != null) {
            return shortStringName;
        }
        StringBuilder sb = new StringBuilder(128);
        toShortString(sb);
        return shortStringName = sb.toString();
    }
    
    void toShortString(StringBuilder sb) {
        sb.append(pid);
        sb.append(':');
        sb.append(processName);
        sb.append('/');
        if (info.uid < Process.FIRST_APPLICATION_UID) {
            sb.append(uid);
        } else {
            sb.append('u');
            sb.append(userId);
            sb.append('a');
            sb.append(UserHandle.getAppId(info.uid));
            if (uid != info.uid) {
                sb.append('i');
                sb.append(UserHandle.getAppId(uid) - Process.FIRST_ISOLATED_UID);
            }
        }
    }
    
    public String toString() {
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ProcessRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        toShortString(sb);
        sb.append('}');
        return stringName = sb.toString();
    }
    
    /*
     *  Return true if package has been added false if not
     */
    public boolean addPackage(String pkg) {
        if (!pkgList.contains(pkg)) {
            pkgList.add(pkg);
            return true;
        }
        return false;
    }
    
    /*
     *  Delete all packages from list except the package indicated in info
     */
    public void resetPackageList() {
        pkgList.clear();
        pkgList.add(info.packageName);
    }
    
    public String[] getPackageList() {
        int size = pkgList.size();
        if (size == 0) {
            return null;
        }
        String list[] = new String[size];
        pkgList.toArray(list);
        return list;
    }
}
