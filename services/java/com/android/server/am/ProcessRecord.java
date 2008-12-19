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
import com.android.server.Watchdog;

import android.app.ActivityManager;
import android.app.Dialog;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Full information about a particular process that
 * is currently running.
 */
class ProcessRecord implements Watchdog.PssRequestor {
    final BatteryStatsImpl.Uid.Proc batteryStats; // where to collect runtime statistics
    final ApplicationInfo info; // all about the first app in the process
    final String processName;   // name of the process
    // List of packages running in the process
    final HashSet<String> pkgList = new HashSet();
    IApplicationThread thread;  // the actual proc...  may be null only if
                                // 'persistent' is true (in which case we
                                // are in the process of launching the app)
    int pid;                    // The process of this application; 0 if none
    boolean starting;           // True if the process is being started
    int maxAdj;                 // Maximum OOM adjustment for this process
    int hiddenAdj;              // If hidden, this is the adjustment to use
    int curRawAdj;              // Current OOM unlimited adjustment for this process
    int setRawAdj;              // Last set OOM unlimited adjustment for this process
    int curAdj;                 // Current OOM adjustment for this process
    int setAdj;                 // Last set OOM adjustment for this process
    boolean isForeground;       // Is this app running the foreground UI?
    boolean setIsForeground;    // Running foreground UI when last set?
    boolean foregroundServices; // Running any services that are foreground?
    boolean bad;                // True if disabled in the bad process list
    IBinder forcingToForeground;// Token that is forcing this process to be foreground
    int adjSeq;                 // Sequence id for identifying repeated trav
    ComponentName instrumentationClass;// class installed to instrument app
    String instrumentationProfileFile; // where to save profiling
    IInstrumentationWatcher instrumentationWatcher; // who is waiting
    Bundle instrumentationArguments;// as given to us
    ComponentName instrumentationResultClass;// copy of instrumentationClass
    BroadcastRecord curReceiver;// receiver currently running in the app
    long lastRequestedGc;       // When we last asked the app to do a gc
    int lastPss;                // Last pss size reported by app.
    
    // contains HistoryRecord objects
    final ArrayList activities = new ArrayList();
    // all ServiceRecord running in this process
    final HashSet services = new HashSet();
    // services that are currently executing code (need to remain foreground).
    final HashSet<ServiceRecord> executingServices
             = new HashSet<ServiceRecord>();
    // All ConnectionRecord this process holds
    final HashSet<ConnectionRecord> connections
            = new HashSet<ConnectionRecord>();  
    // all IIntentReceivers that are registered from this process.
    final HashSet<ReceiverList> receivers = new HashSet<ReceiverList>();
    // class (String) -> ContentProviderRecord
    final HashMap pubProviders = new HashMap(); 
    // All ContentProviderRecord process is using
    final HashSet conProviders = new HashSet(); 
    
    boolean persistent;         // always keep this application running?
    boolean crashing;           // are we in the process of crashing?
    Dialog crashDialog;         // dialog being displayed due to crash.
    boolean notResponding;      // does the app have a not responding dialog?
    Dialog anrDialog;           // dialog being displayed due to app not resp.
    boolean removed;            // has app package been removed from device?
    boolean debugging;          // was app launched for debugging?
    int persistentActivities;   // number of activities that are persistent
    boolean waitedForDebugger;  // has process show wait for debugger dialog?
    Dialog waitDialog;          // current wait for debugger dialog
    
    // These reports are generated & stored when an app gets into an error condition.
    // They will be "null" when all is OK.
    ActivityManager.ProcessErrorStateInfo crashingReport;
    ActivityManager.ProcessErrorStateInfo notRespondingReport;

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + "class=" + info.className);
        pw.println(prefix+"manageSpaceActivityName="+info.manageSpaceActivityName);
        pw.println(prefix + "dir=" + info.sourceDir + " publicDir=" + info.publicSourceDir 
              + " data=" + info.dataDir);
        pw.println(prefix + "packageList=" + pkgList);
        pw.println(prefix + "instrumentationClass=" + instrumentationClass
              + " instrumentationProfileFile=" + instrumentationProfileFile);
        pw.println(prefix + "instrumentationArguments=" + instrumentationArguments);
        pw.println(prefix + "thread=" + thread + " curReceiver=" + curReceiver);
        pw.println(prefix + "pid=" + pid + " starting=" + starting
                + " lastPss=" + lastPss);
        pw.println(prefix + "maxAdj=" + maxAdj + " hiddenAdj=" + hiddenAdj
                + " curRawAdj=" + curRawAdj + " setRawAdj=" + setRawAdj
                + " curAdj=" + curAdj + " setAdj=" + setAdj);
        pw.println(prefix + "isForeground=" + isForeground
                + " setIsForeground=" + setIsForeground
                + " foregroundServices=" + foregroundServices
                + " forcingToForeground=" + forcingToForeground);
        pw.println(prefix + "persistent=" + persistent + " removed=" + removed
                + " persistentActivities=" + persistentActivities);
        pw.println(prefix + "debugging=" + debugging
                + " crashing=" + crashing + " " + crashDialog
                + " notResponding=" + notResponding + " " + anrDialog
                + " bad=" + bad);
        pw.println(prefix + "activities=" + activities);
        pw.println(prefix + "services=" + services);
        pw.println(prefix + "executingServices=" + executingServices);
        pw.println(prefix + "connections=" + connections);
        pw.println(prefix + "pubProviders=" + pubProviders);
        pw.println(prefix + "conProviders=" + conProviders);
        pw.println(prefix + "receivers=" + receivers);
    }
    
    ProcessRecord(BatteryStatsImpl.Uid.Proc _batteryStats, IApplicationThread _thread,
            ApplicationInfo _info, String _processName) {
        batteryStats = _batteryStats;
        info = _info;
        processName = _processName;
        pkgList.add(_info.packageName);
        thread = _thread;
        maxAdj = ActivityManagerService.EMPTY_APP_ADJ;
        hiddenAdj = ActivityManagerService.HIDDEN_APP_MIN_ADJ;
        curRawAdj = setRawAdj = -100;
        curAdj = setAdj = -100;
        persistent = false;
        removed = false;
        persistentActivities = 0;
    }

    /**
     * This method returns true if any of the activities within the process record are interesting
     * to the user. See HistoryRecord.isInterestingToUserLocked()
     */
    public boolean isInterestingToUserLocked() {
        final int size = activities.size();
        for (int i = 0 ; i < size ; i++) {
            HistoryRecord r = (HistoryRecord) activities.get(i);
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
            ((HistoryRecord)activities.get(i)).stopFreezingScreenLocked(true);
        }
    }
    
    public void requestPss() {
        IApplicationThread localThread = thread;
        if (localThread != null) {
            try {
                localThread.requestPss();
            } catch (RemoteException e) {
            }
        }
    }
    
    public String toString() {
        return "ProcessRecord{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + pid + ":" + processName + "/" + info.uid + "}";
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
