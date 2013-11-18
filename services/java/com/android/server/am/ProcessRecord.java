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

import android.util.ArraySet;
import com.android.internal.app.ProcessStats;
import com.android.internal.os.BatteryStatsImpl;

import android.app.ActivityManager;
import android.app.Dialog;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.IUiAutomationConnection;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.PrintWriterPrinter;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Full information about a particular process that
 * is currently running.
 */
final class ProcessRecord {
    private final BatteryStatsImpl mBatteryStats; // where to collect runtime statistics
    final ApplicationInfo info; // all about the first app in the process
    final boolean isolated;     // true if this is a special isolated process
    final int uid;              // uid of process; may be different from 'info' if isolated
    final int userId;           // user of process.
    final String processName;   // name of the process
    // List of packages running in the process
    final ArrayMap<String, ProcessStats.ProcessState> pkgList
            = new ArrayMap<String, ProcessStats.ProcessState>();
    IApplicationThread thread;  // the actual proc...  may be null only if
                                // 'persistent' is true (in which case we
                                // are in the process of launching the app)
    ProcessStats.ProcessState baseProcessTracker;
    int pid;                    // The process of this application; 0 if none
    boolean starting;           // True if the process is being started
    long lastActivityTime;      // For managing the LRU list
    long lastPssTime;           // Last time we retrieved PSS data
    long nextPssTime;           // Next time we want to request PSS data
    long lastStateTime;         // Last time setProcState changed
    long initialIdlePss;        // Initial memory pss of process for idle maintenance.
    long lastPss;               // Last computed memory pss.
    long lastCachedPss;         // Last computed pss when in cached state.
    int maxAdj;                 // Maximum OOM adjustment for this process
    int curRawAdj;              // Current OOM unlimited adjustment for this process
    int setRawAdj;              // Last set OOM unlimited adjustment for this process
    int curAdj;                 // Current OOM adjustment for this process
    int setAdj;                 // Last set OOM adjustment for this process
    int curSchedGroup;          // Currently desired scheduling class
    int setSchedGroup;          // Last set to background scheduling class
    int trimMemoryLevel;        // Last selected memory trimming level
    int memImportance;          // Importance constant computed from curAdj
    int curProcState = -1;      // Currently computed process state: ActivityManager.PROCESS_STATE_*
    int repProcState = -1;      // Last reported process state
    int setProcState = -1;      // Last set process state in process tracker
    int pssProcState = -1;      // The proc state we are currently requesting pss for
    boolean serviceb;           // Process currently is on the service B list
    boolean serviceHighRam;     // We are forcing to service B list due to its RAM use
    boolean keeping;            // Actively running code so don't kill due to that?
    boolean setIsForeground;    // Running foreground UI when last set?
    boolean notCachedSinceIdle; // Has this process not been in a cached state since last idle?
    boolean hasClientActivities;  // Are there any client services with activities?
    boolean hasStartedServices; // Are there any started services running in this process?
    boolean foregroundServices; // Running any services that are foreground?
    boolean foregroundActivities; // Running any activities that are foreground?
    boolean systemNoUi;         // This is a system process, but not currently showing UI.
    boolean hasShownUi;         // Has UI been shown in this process since it was started?
    boolean pendingUiClean;     // Want to clean up resources from showing UI?
    boolean hasAboveClient;     // Bound using BIND_ABOVE_CLIENT, so want to be lower
    boolean bad;                // True if disabled in the bad process list
    boolean killedByAm;         // True when proc has been killed by activity manager, not for RAM
    boolean procStateChanged;   // Keep track of whether we changed 'setAdj'.
    String waitingToKill;       // Process is waiting to be killed when in the bg, and reason
    IBinder forcingToForeground;// Token that is forcing this process to be foreground
    int adjSeq;                 // Sequence id for identifying oom_adj assignment cycles
    int lruSeq;                 // Sequence id for identifying LRU update cycles
    CompatibilityInfo compat;   // last used compatibility mode
    IBinder.DeathRecipient deathRecipient; // Who is watching for the death.
    ComponentName instrumentationClass;// class installed to instrument app
    ApplicationInfo instrumentationInfo; // the application being instrumented
    String instrumentationProfileFile; // where to save profiling
    IInstrumentationWatcher instrumentationWatcher; // who is waiting
    IUiAutomationConnection instrumentationUiAutomationConnection; // Connection to use the UI introspection APIs.
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
    boolean cached;             // Is this a cached process?
    String adjType;             // Debugging: primary thing impacting oom_adj.
    int adjTypeCode;            // Debugging: adj code to report to app.
    Object adjSource;           // Debugging: option dependent object.
    int adjSourceOom;           // Debugging: oom_adj of adjSource's process.
    Object adjTarget;           // Debugging: target component impacting oom_adj.
    
    // contains HistoryRecord objects
    final ArrayList<ActivityRecord> activities = new ArrayList<ActivityRecord>();
    // all ServiceRecord running in this process
    final ArraySet<ServiceRecord> services = new ArraySet<ServiceRecord>();
    // services that are currently executing code (need to remain foreground).
    final ArraySet<ServiceRecord> executingServices
             = new ArraySet<ServiceRecord>();
    // All ConnectionRecord this process holds
    final ArraySet<ConnectionRecord> connections
            = new ArraySet<ConnectionRecord>();
    // all IIntentReceivers that are registered from this process.
    final ArraySet<ReceiverList> receivers = new ArraySet<ReceiverList>();
    // class (String) -> ContentProviderRecord
    final ArrayMap<String, ContentProviderRecord> pubProviders
            = new ArrayMap<String, ContentProviderRecord>();
    // All ContentProviderRecord process is using
    final ArrayList<ContentProviderConnection> conProviders
            = new ArrayList<ContentProviderConnection>();

    boolean execServicesFg;     // do we need to be executing services in the foreground?
    boolean persistent;         // always keep this application running?
    boolean crashing;           // are we in the process of crashing?
    Dialog crashDialog;         // dialog being displayed due to crash.
    boolean forceCrashReport;   // suppress normal auto-dismiss of crash dialog & report UI?
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
        pw.print(prefix); pw.print("packageList={");
        for (int i=0; i<pkgList.size(); i++) {
            if (i > 0) pw.print(", ");
            pw.print(pkgList.keyAt(i));
        }
        pw.println("}");
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
                pw.println(starting);
        pw.print(prefix); pw.print("lastActivityTime=");
                TimeUtils.formatDuration(lastActivityTime, now, pw);
                pw.print(" lastPssTime=");
                TimeUtils.formatDuration(lastPssTime, now, pw);
                pw.print(" nextPssTime=");
                TimeUtils.formatDuration(nextPssTime, now, pw);
                pw.println();
        pw.print(prefix); pw.print("adjSeq="); pw.print(adjSeq);
                pw.print(" lruSeq="); pw.print(lruSeq);
                pw.print(" lastPss="); pw.print(lastPss);
                pw.print(" lastCachedPss="); pw.println(lastCachedPss);
        pw.print(prefix); pw.print("keeping="); pw.print(keeping);
                pw.print(" cached="); pw.print(cached);
                pw.print(" empty="); pw.println(empty);
        if (serviceb) {
            pw.print(prefix); pw.print("serviceb="); pw.print(serviceb);
                    pw.print(" serviceHighRam="); pw.println(serviceHighRam);
        }
        if (notCachedSinceIdle) {
            pw.print(prefix); pw.print("notCachedSinceIdle="); pw.print(notCachedSinceIdle);
                    pw.print(" initialIdlePss="); pw.println(initialIdlePss);
        }
        pw.print(prefix); pw.print("oom: max="); pw.print(maxAdj);
                pw.print(" curRaw="); pw.print(curRawAdj);
                pw.print(" setRaw="); pw.print(setRawAdj);
                pw.print(" cur="); pw.print(curAdj);
                pw.print(" set="); pw.println(setAdj);
        pw.print(prefix); pw.print("curSchedGroup="); pw.print(curSchedGroup);
                pw.print(" setSchedGroup="); pw.print(setSchedGroup);
                pw.print(" systemNoUi="); pw.print(systemNoUi);
                pw.print(" trimMemoryLevel="); pw.println(trimMemoryLevel);
        pw.print(prefix); pw.print("curProcState="); pw.print(curProcState);
                pw.print(" repProcState="); pw.print(repProcState);
                pw.print(" pssProcState="); pw.print(pssProcState);
                pw.print(" setProcState="); pw.print(setProcState);
                pw.print(" lastStateTime=");
                TimeUtils.formatDuration(lastStateTime, now, pw);
                pw.println();
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
        if (hasClientActivities || foregroundActivities) {
            pw.print(prefix); pw.print("hasClientActivities="); pw.print(hasClientActivities);
                    pw.print(" foregroundActivities="); pw.println(foregroundActivities);
        }
        if (hasStartedServices) {
            pw.print(prefix); pw.print("hasStartedServices="); pw.println(hasStartedServices);
        }
        if (!keeping) {
            long wtime;
            synchronized (mBatteryStats) {
                wtime = mBatteryStats.getProcessWakeTime(info.uid,
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
        if (killedByAm || waitingToKill != null) {
            pw.print(prefix); pw.print("killedByAm="); pw.print(killedByAm);
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
            for (int i=0; i<services.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(services.valueAt(i));
            }
        }
        if (executingServices.size() > 0) {
            pw.print(prefix); pw.print("Executing Services (fg=");
            pw.print(execServicesFg); pw.println(")");
            for (int i=0; i<executingServices.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(executingServices.valueAt(i));
            }
        }
        if (connections.size() > 0) {
            pw.print(prefix); pw.println("Connections:");
            for (int i=0; i<connections.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(connections.valueAt(i));
            }
        }
        if (pubProviders.size() > 0) {
            pw.print(prefix); pw.println("Published Providers:");
            for (int i=0; i<pubProviders.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(pubProviders.keyAt(i));
                pw.print(prefix); pw.print("    -> "); pw.println(pubProviders.valueAt(i));
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
            for (int i=0; i<receivers.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(receivers.valueAt(i));
            }
        }
    }
    
    ProcessRecord(BatteryStatsImpl _batteryStats, ApplicationInfo _info,
            String _processName, int _uid) {
        mBatteryStats = _batteryStats;
        info = _info;
        isolated = _info.uid != _uid;
        uid = _uid;
        userId = UserHandle.getUserId(_uid);
        processName = _processName;
        pkgList.put(_info.packageName, null);
        maxAdj = ProcessList.UNKNOWN_ADJ;
        curRawAdj = setRawAdj = -100;
        curAdj = setAdj = -100;
        persistent = false;
        removed = false;
        lastStateTime = lastPssTime = nextPssTime = SystemClock.uptimeMillis();
    }

    public void setPid(int _pid) {
        pid = _pid;
        shortStringName = null;
        stringName = null;
    }

    public void makeActive(IApplicationThread _thread, ProcessStatsService tracker) {
        if (thread == null) {
            final ProcessStats.ProcessState origBase = baseProcessTracker;
            if (origBase != null) {
                origBase.setState(ProcessStats.STATE_NOTHING,
                        tracker.getMemFactorLocked(), SystemClock.uptimeMillis(), pkgList);
                origBase.makeInactive();
            }
            baseProcessTracker = tracker.getProcessStateLocked(info.packageName, info.uid,
                    processName);
            baseProcessTracker.makeActive();
            for (int i=0; i<pkgList.size(); i++) {
                ProcessStats.ProcessState ps = pkgList.valueAt(i);
                if (ps != null && ps != origBase) {
                    ps.makeInactive();
                }
                ps = tracker.getProcessStateLocked(pkgList.keyAt(i), info.uid, processName);
                if (ps != baseProcessTracker) {
                    ps.makeActive();
                }
                pkgList.setValueAt(i, ps);
            }
        }
        thread = _thread;
    }

    public void makeInactive(ProcessStatsService tracker) {
        thread = null;
        final ProcessStats.ProcessState origBase = baseProcessTracker;
        if (origBase != null) {
            if (origBase != null) {
                origBase.setState(ProcessStats.STATE_NOTHING,
                        tracker.getMemFactorLocked(), SystemClock.uptimeMillis(), pkgList);
                origBase.makeInactive();
            }
            baseProcessTracker = null;
            for (int i=0; i<pkgList.size(); i++) {
                ProcessStats.ProcessState ps = pkgList.valueAt(i);
                if (ps != null && ps != origBase) {
                    ps.makeInactive();
                }
                pkgList.setValueAt(i, null);
            }
        }
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
        for (int i=connections.size()-1; i>=0; i--) {
            ConnectionRecord cr = connections.valueAt(i);
            if ((cr.flags&Context.BIND_ABOVE_CLIENT) != 0) {
                hasAboveClient = true;
                break;
            }
        }
    }

    int modifyRawOomAdj(int adj) {
        if (hasAboveClient) {
            // If this process has bound to any services with BIND_ABOVE_CLIENT,
            // then we need to drop its adjustment to be lower than the service's
            // in order to honor the request.  We want to drop it by one adjustment
            // level...  but there is special meaning applied to various levels so
            // we will skip some of them.
            if (adj < ProcessList.FOREGROUND_APP_ADJ) {
                // System process will not get dropped, ever
            } else if (adj < ProcessList.VISIBLE_APP_ADJ) {
                adj = ProcessList.VISIBLE_APP_ADJ;
            } else if (adj < ProcessList.PERCEPTIBLE_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_APP_ADJ;
            } else if (adj < ProcessList.CACHED_APP_MIN_ADJ) {
                adj = ProcessList.CACHED_APP_MIN_ADJ;
            } else if (adj < ProcessList.CACHED_APP_MAX_ADJ) {
                adj++;
            }
        }
        return adj;
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
            int appId = UserHandle.getAppId(info.uid);
            if (appId >= Process.FIRST_APPLICATION_UID) {
                sb.append('a');
                sb.append(appId - Process.FIRST_APPLICATION_UID);
            } else {
                sb.append('s');
                sb.append(appId);
            }
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

    public String makeAdjReason() {
        if (adjSource != null || adjTarget != null) {
            StringBuilder sb = new StringBuilder(128);
            sb.append(' ');
            if (adjTarget instanceof ComponentName) {
                sb.append(((ComponentName)adjTarget).flattenToShortString());
            } else if (adjTarget != null) {
                sb.append(adjTarget.toString());
            } else {
                sb.append("{null}");
            }
            sb.append("<=");
            if (adjSource instanceof ProcessRecord) {
                sb.append("Proc{");
                sb.append(((ProcessRecord)adjSource).toShortString());
                sb.append("}");
            } else if (adjSource != null) {
                sb.append(adjSource.toString());
            } else {
                sb.append("{null}");
            }
            return sb.toString();
        }
        return null;
    }

    /*
     *  Return true if package has been added false if not
     */
    public boolean addPackage(String pkg, ProcessStatsService tracker) {
        if (!pkgList.containsKey(pkg)) {
            if (baseProcessTracker != null) {
                ProcessStats.ProcessState state = tracker.getProcessStateLocked(
                        pkg, info.uid, processName);
                pkgList.put(pkg, state);
                if (state != baseProcessTracker) {
                    state.makeActive();
                }
            } else {
                pkgList.put(pkg, null);
            }
            return true;
        }
        return false;
    }

    public int getSetAdjWithServices() {
        if (setAdj >= ProcessList.CACHED_APP_MIN_ADJ) {
            if (hasStartedServices) {
                return ProcessList.SERVICE_B_ADJ;
            }
        }
        return setAdj;
    }

    public void forceProcessStateUpTo(int newState) {
        if (repProcState > newState) {
            curProcState = repProcState = newState;
        }
    }

    /*
     *  Delete all packages from list except the package indicated in info
     */
    public void resetPackageList(ProcessStatsService tracker) {
        final int N = pkgList.size();
        if (baseProcessTracker != null) {
            long now = SystemClock.uptimeMillis();
            baseProcessTracker.setState(ProcessStats.STATE_NOTHING,
                    tracker.getMemFactorLocked(), now, pkgList);
            if (N != 1) {
                for (int i=0; i<N; i++) {
                    ProcessStats.ProcessState ps = pkgList.valueAt(i);
                    if (ps != null && ps != baseProcessTracker) {
                        ps.makeInactive();
                    }

                }
                pkgList.clear();
                ProcessStats.ProcessState ps = tracker.getProcessStateLocked(
                        info.packageName, info.uid, processName);
                pkgList.put(info.packageName, ps);
                if (ps != baseProcessTracker) {
                    ps.makeActive();
                }
            }
        } else if (N != 1) {
            pkgList.clear();
            pkgList.put(info.packageName, null);
        }
    }
    
    public String[] getPackageList() {
        int size = pkgList.size();
        if (size == 0) {
            return null;
        }
        String list[] = new String[size];
        for (int i=0; i<pkgList.size(); i++) {
            list[i] = pkgList.keyAt(i);
        }
        return list;
    }
}
