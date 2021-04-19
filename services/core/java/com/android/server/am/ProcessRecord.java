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

import static com.android.server.Watchdog.NATIVE_STACKS_OF_INTEREST;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_ANR;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.MY_PID;

import android.app.ActivityManager;
import android.app.ApplicationErrorReport;
import android.app.ApplicationExitInfo;
import android.app.ApplicationExitInfo.Reason;
import android.app.ApplicationExitInfo.SubReason;
import android.app.Dialog;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProcessInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.VersionedPackage;
import android.content.res.CompatibilityInfo;
import android.os.Binder;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.server.ServerProtoEnums;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.procstats.ProcessState;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.Zygote;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.MemoryPressureUtil;
import com.android.server.wm.WindowProcessController;
import com.android.server.wm.WindowProcessListener;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Full information about a particular process that
 * is currently running.
 */
class ProcessRecord implements WindowProcessListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "ProcessRecord" : TAG_AM;

    private final ActivityManagerService mService; // where we came from
    volatile ApplicationInfo info; // all about the first app in the process
    final ProcessInfo processInfo; // if non-null, process-specific manifest info
    final boolean isolated;     // true if this is a special isolated process
    final boolean appZygote;    // true if this is forked from the app zygote
    final int uid;              // uid of process; may be different from 'info' if isolated
    final int userId;           // user of process.
    final String processName;   // name of the process
    // List of packages running in the process
    final PackageList pkgList = new PackageList();
    final class PackageList {
        final ArrayMap<String, ProcessStats.ProcessStateHolder> mPkgList = new ArrayMap<>();

        ProcessStats.ProcessStateHolder put(String key, ProcessStats.ProcessStateHolder value) {
            mWindowProcessController.addPackage(key);
            return mPkgList.put(key, value);
        }

        void clear() {
            mPkgList.clear();
            mWindowProcessController.clearPackageList();
        }

        int size() {
            return mPkgList.size();
        }

        String keyAt(int index) {
            return mPkgList.keyAt(index);
        }

        public ProcessStats.ProcessStateHolder valueAt(int index) {
            return mPkgList.valueAt(index);
        }

        ProcessStats.ProcessStateHolder get(String pkgName) {
            return mPkgList.get(pkgName);
        }

        boolean containsKey(Object key) {
            return mPkgList.containsKey(key);
        }
    }

    final ProcessList.ProcStateMemTracker procStateMemTracker
            = new ProcessList.ProcStateMemTracker();
    UidRecord uidRecord;        // overall state of process's uid.
    ArraySet<String> pkgDeps;   // additional packages we have a dependency on
    IApplicationThread thread;  // the actual proc...  may be null only if
                                // 'persistent' is true (in which case we
                                // are in the process of launching the app)
    ProcessState baseProcessTracker;
    BatteryStatsImpl.Uid.Proc curProcBatteryStats;
    int pid;                    // The process of this application; 0 if none
    String procStatFile;        // path to /proc/<pid>/stat
    int[] gids;                 // The gids this process was launched with
    private String mRequiredAbi;// The ABI this process was launched with
    String instructionSet;      // The instruction set this process was launched with
    boolean starting;           // True if the process is being started
    long lastActivityTime;      // For managing the LRU list
    long lastPssTime;           // Last time we retrieved PSS data
    long nextPssTime;           // Next time we want to request PSS data
    long lastStateTime;         // Last time setProcState changed
    long initialIdlePss;        // Initial memory pss of process for idle maintenance.
    long lastPss;               // Last computed memory pss.
    long lastSwapPss;           // Last computed SwapPss.
    long lastCachedPss;         // Last computed pss when in cached state.
    long lastCachedSwapPss;     // Last computed SwapPss when in cached state.
    int maxAdj;                 // Maximum OOM adjustment for this process
    private int mCurRawAdj;     // Current OOM unlimited adjustment for this process
    int setRawAdj;              // Last set OOM unlimited adjustment for this process
    int curAdj;                 // Current OOM adjustment for this process
    int setAdj;                 // Last set OOM adjustment for this process
    int verifiedAdj;            // The last adjustment that was verified as actually being set
    int curCapability;          // Current capability flags of this process. For example,
                                // PROCESS_CAPABILITY_FOREGROUND_LOCATION is one capability.
    int setCapability;          // Last set capability flags.
    long lastCompactTime;       // The last time that this process was compacted
    int reqCompactAction;       // The most recent compaction action requested for this app.
    int lastCompactAction;      // The most recent compaction action performed for this app.
    boolean frozen;             // True when the process is frozen.
    long freezeUnfreezeTime;    // Last time the app was (un)frozen, 0 for never
    boolean shouldNotFreeze;    // True if a process has a WPRI binding from an unfrozen process
    private int mCurSchedGroup; // Currently desired scheduling class
    int setSchedGroup;          // Last set to background scheduling class
    int trimMemoryLevel;        // Last selected memory trimming level
    private int mCurProcState = PROCESS_STATE_NONEXISTENT; // Currently computed process state
    private int mRepProcState = PROCESS_STATE_NONEXISTENT; // Last reported process state
    private int mCurRawProcState = PROCESS_STATE_NONEXISTENT; // Temp state during computation
    int setProcState = PROCESS_STATE_NONEXISTENT; // Last set process state in process tracker
    int pssProcState = PROCESS_STATE_NONEXISTENT; // Currently requesting pss for
    int pssStatType;            // The type of stat collection that we are currently requesting
    int savedPriority;          // Previous priority value if we're switching to non-SCHED_OTHER
    int renderThreadTid;        // TID for RenderThread
    ServiceRecord connectionService; // Service that applied current connectionGroup/Importance
    int connectionGroup;        // Last group set by a connection
    int connectionImportance;   // Last importance set by a connection
    boolean serviceb;           // Process currently is on the service B list
    boolean serviceHighRam;     // We are forcing to service B list due to its RAM use
    boolean notCachedSinceIdle; // Has this process not been in a cached state since last idle?
    private boolean mHasClientActivities;  // Are there any client services with activities?
    boolean hasStartedServices; // Are there any started services running in this process?
    private boolean mHasForegroundServices; // Running any services that are foreground?
    private int mFgServiceTypes; // Type of foreground service, if there is a foreground service.
    private int mRepFgServiceTypes; // Last reported foreground service types.
    private boolean mHasForegroundActivities; // Running any activities that are foreground?
    boolean repForegroundActivities; // Last reported foreground activities.
    boolean systemNoUi;         // This is a system process, but not currently showing UI.
    boolean hasShownUi;         // Has UI been shown in this process since it was started?
    private boolean mHasTopUi;  // Is this process currently showing a non-activity UI that the user
                                // is interacting with? E.g. The status bar when it is expanded, but
                                // not when it is minimized. When true the
                                // process will be set to use the ProcessList#SCHED_GROUP_TOP_APP
                                // scheduling group to boost performance.
    private boolean mHasOverlayUi; // Is the process currently showing a non-activity UI that
                                // overlays on-top of activity UIs on screen. E.g. display a window
                                // of type
                                // android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
                                // When true the process will oom adj score will be set to
                                // ProcessList#PERCEPTIBLE_APP_ADJ at minimum to reduce the chance
                                // of the process getting killed.
    boolean runningRemoteAnimation; // Is the process currently running a RemoteAnimation? When true
                                // the process will be set to use the
                                // ProcessList#SCHED_GROUP_TOP_APP scheduling group to boost
                                // performance, as well as oom adj score will be set to
                                // ProcessList#VISIBLE_APP_ADJ at minimum to reduce the chance
                                // of the process getting killed.
    private boolean mPendingUiClean; // Want to clean up resources from showing UI?
    boolean hasAboveClient;     // Bound using BIND_ABOVE_CLIENT, so want to be lower
    boolean treatLikeActivity;  // Bound using BIND_TREAT_LIKE_ACTIVITY
    boolean bad;                // True if disabled in the bad process list
    boolean killedByAm;         // True when proc has been killed by activity manager, not for RAM
    boolean killed;             // True once we know the process has been killed
    boolean procStateChanged;   // Keep track of whether we changed 'setAdj'.
    boolean reportedInteraction;// Whether we have told usage stats about it being an interaction
    boolean unlocked;           // True when proc was started in user unlocked state
    private long mInteractionEventTime; // The time we sent the last interaction event
    private long mFgInteractionTime; // When we became foreground for interaction purposes
    String waitingToKill;       // Process is waiting to be killed when in the bg, and reason
    Object forcingToImportant;  // Token that is forcing this process to be important
    int adjSeq;                 // Sequence id for identifying oom_adj assignment cycles
    int completedAdjSeq;        // Sequence id for identifying oom_adj assignment cycles
    boolean containsCycle;      // Whether this app has encountered a cycle in the most recent update
    int lruSeq;                 // Sequence id for identifying LRU update cycles
    CompatibilityInfo compat;   // last used compatibility mode
    IBinder.DeathRecipient deathRecipient; // Who is watching for the death.
    private ActiveInstrumentation mInstr; // Set to currently active instrumentation running in
                                          // process.
    private boolean mUsingWrapper; // Set to true when process was launched with a wrapper attached
    final ArraySet<BroadcastRecord> curReceivers = new ArraySet<BroadcastRecord>();// receivers currently running in the app
    private long mWhenUnimportant; // When (uptime) the process last became unimportant
    long lastCpuTime;           // How long proc has run CPU at last check
    long curCpuTime;            // How long proc has run CPU most recently
    long lastRequestedGc;       // When we last asked the app to do a gc
    long lastLowMemory;         // When we last told the app that memory is low
    long lastProviderTime;      // The last time someone else was using a provider in this process.
    long lastTopTime;           // The last time the process was in the TOP state or greater.
    boolean reportLowMemory;    // Set to true when waiting to report low mem
    boolean empty;              // Is this an empty background process?
    private volatile boolean mCached;    // Is this a cached process?
    String adjType;             // Debugging: primary thing impacting oom_adj.
    int adjTypeCode;            // Debugging: adj code to report to app.
    Object adjSource;           // Debugging: option dependent object.
    int adjSourceProcState;     // Debugging: proc state of adjSource's process.
    Object adjTarget;           // Debugging: target component impacting oom_adj.
    Runnable crashHandler;      // Optional local handler to be invoked in the process crash.
    boolean bindMountPending;   // True if Android/obb and Android/data need to be bind mount .

    // Cache of last retrieve memory info and uptime, to throttle how frequently
    // apps can requyest it.
    Debug.MemoryInfo lastMemInfo;
    long lastMemInfoTime;

    // Controller for error dialogs
    private final ErrorDialogController mDialogController = new ErrorDialogController();
    // Controller for driving the process state on the window manager side.
    private final WindowProcessController mWindowProcessController;
    // all ServiceRecord running in this process
    private final ArraySet<ServiceRecord> mServices = new ArraySet<>();
    // services that are currently executing code (need to remain foreground).
    final ArraySet<ServiceRecord> executingServices = new ArraySet<>();
    // All ConnectionRecord this process holds
    final ArraySet<ConnectionRecord> connections = new ArraySet<>();
    // all IIntentReceivers that are registered from this process.
    final ArraySet<ReceiverList> receivers = new ArraySet<>();
    // class (String) -> ContentProviderRecord
    final ArrayMap<String, ContentProviderRecord> pubProviders = new ArrayMap<>();
    // All ContentProviderRecord process is using
    final ArrayList<ContentProviderConnection> conProviders = new ArrayList<>();
    // A set of tokens that currently contribute to this process being temporarily whitelisted
    // to start activities even if it's not in the foreground
    final ArraySet<Binder> mAllowBackgroundActivityStartsTokens = new ArraySet<>();
    // a set of UIDs of all bound clients
    private ArraySet<Integer> mBoundClientUids = new ArraySet<>();

    String isolatedEntryPoint;  // Class to run on start if this is a special isolated process.
    String[] isolatedEntryPointArgs; // Arguments to pass to isolatedEntryPoint's main().

    boolean execServicesFg;     // do we need to be executing services in the foreground?
    private boolean mPersistent;// always keep this application running?
    private boolean mCrashing;  // are we in the process of crashing?
    boolean forceCrashReport;   // suppress normal auto-dismiss of crash dialog & report UI?
    private boolean mNotResponding; // does the app have a not responding dialog?
    volatile boolean removed;   // Whether this process should be killed and removed from process
                                // list. It is set when the package is force-stopped or the process
                                // has crashed too many times.
    private boolean mDebugging; // was app launched for debugging?
    boolean waitedForDebugger;  // has process show wait for debugger dialog?

    String shortStringName;     // caching of toShortString() result.
    String stringName;          // caching of toString() result.
    boolean pendingStart;       // Process start is pending.
    long startSeq;              // Seq no. indicating the latest process start associated with
                                // this process record.
    int mountMode;              // Indicates how the external storage was mounted for this process.

    // These reports are generated & stored when an app gets into an error condition.
    // They will be "null" when all is OK.
    ActivityManager.ProcessErrorStateInfo crashingReport;
    ActivityManager.ProcessErrorStateInfo notRespondingReport;

    // Who will be notified of the error. This is usually an activity in the
    // app that installed the package.
    ComponentName errorReportReceiver;

    // Process is currently hosting a backup agent for backup or restore
    public boolean inFullBackup;
    // App is allowed to manage whitelists such as temporary Power Save mode whitelist.
    boolean whitelistManager;

    // Params used in starting this process.
    HostingRecord hostingRecord;
    String seInfo;
    long startTime;
    // This will be same as {@link #uid} usually except for some apps used during factory testing.
    int startUid;
    // set of disabled compat changes for the process (all others are enabled)
    long[] mDisabledCompatChanges;

    long mLastRss;               // Last computed memory rss.

    // The precede instance of the process, which would exist when the previous process is killed
    // but not fully dead yet; in this case, the new instance of the process should be held until
    // this precede instance is fully dead.
    volatile ProcessRecord mPrecedence;
    // The succeeding instance of the process, which is going to be started after this process
    // is killed successfully.
    volatile ProcessRecord mSuccessor;

    // Cached task info for OomAdjuster
    private static final int VALUE_INVALID = -1;
    private static final int VALUE_FALSE = 0;
    private static final int VALUE_TRUE = 1;
    private int mCachedHasActivities = VALUE_INVALID;
    private int mCachedIsHeavyWeight = VALUE_INVALID;
    private int mCachedHasVisibleActivities = VALUE_INVALID;
    private int mCachedIsHomeProcess = VALUE_INVALID;
    private int mCachedIsPreviousProcess = VALUE_INVALID;
    private int mCachedHasRecentTasks = VALUE_INVALID;
    private int mCachedIsReceivingBroadcast = VALUE_INVALID;
    int mCachedAdj = ProcessList.INVALID_ADJ;
    boolean mCachedForegroundActivities = false;
    int mCachedProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
    int mCachedSchedGroup = ProcessList.SCHED_GROUP_BACKGROUND;

    boolean mReachable; // Whether or not this process is reachable from given process

    void setStartParams(int startUid, HostingRecord hostingRecord, String seInfo,
            long startTime) {
        this.startUid = startUid;
        this.hostingRecord = hostingRecord;
        this.seInfo = seInfo;
        this.startTime = startTime;
    }

    void dump(PrintWriter pw, String prefix) {
        final long nowUptime = SystemClock.uptimeMillis();

        pw.print(prefix); pw.print("user #"); pw.print(userId);
                pw.print(" uid="); pw.print(info.uid);
        if (uid != info.uid) {
            pw.print(" ISOLATED uid="); pw.print(uid);
        }
        pw.print(" gids={");
        if (gids != null) {
            for (int gi=0; gi<gids.length; gi++) {
                if (gi != 0) pw.print(", ");
                pw.print(gids[gi]);

            }
        }
        pw.println("}");
        if (processInfo != null) {
            pw.print(prefix); pw.println("processInfo:");
            if (processInfo.deniedPermissions != null) {
                for (int i = 0; i < processInfo.deniedPermissions.size(); i++) {
                    pw.print(prefix); pw.print("  deny: ");
                    pw.println(processInfo.deniedPermissions.valueAt(i));
                }
            }
            if (processInfo.gwpAsanMode != ApplicationInfo.GWP_ASAN_DEFAULT) {
                pw.print(prefix); pw.println("  gwpAsanMode=" + processInfo.gwpAsanMode);
            }
            if (processInfo.memtagMode != ApplicationInfo.MEMTAG_DEFAULT) {
                pw.print(prefix); pw.println("  memtagMode=" + processInfo.memtagMode);
            }
        }
        pw.print(prefix); pw.print("mRequiredAbi="); pw.print(mRequiredAbi);
                pw.print(" instructionSet="); pw.println(instructionSet);
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
        if (pkgDeps != null) {
            pw.print(prefix); pw.print("packageDependencies={");
            for (int i=0; i<pkgDeps.size(); i++) {
                if (i > 0) pw.print(", ");
                pw.print(pkgDeps.valueAt(i));
            }
            pw.println("}");
        }
        pw.print(prefix); pw.print("compat="); pw.println(compat);
        if (mInstr != null) {
            pw.print(prefix); pw.print("mInstr="); pw.println(mInstr);
        }
        pw.print(prefix); pw.print("thread="); pw.println(thread);
        pw.print(prefix); pw.print("pid="); pw.print(pid); pw.print(" starting=");
                pw.println(starting);
        pw.print(prefix); pw.print("lastActivityTime=");
                TimeUtils.formatDuration(lastActivityTime, nowUptime, pw);
                pw.print(" lastPssTime=");
                TimeUtils.formatDuration(lastPssTime, nowUptime, pw);
                pw.print(" pssStatType="); pw.print(pssStatType);
                pw.print(" nextPssTime=");
                TimeUtils.formatDuration(nextPssTime, nowUptime, pw);
                pw.println();
        pw.print(prefix); pw.print("lastPss="); DebugUtils.printSizeValue(pw, lastPss * 1024);
                pw.print(" lastSwapPss="); DebugUtils.printSizeValue(pw, lastSwapPss * 1024);
                pw.print(" lastCachedPss="); DebugUtils.printSizeValue(pw, lastCachedPss * 1024);
                pw.print(" lastCachedSwapPss="); DebugUtils.printSizeValue(pw,
                        lastCachedSwapPss * 1024);
                pw.print(" lastRss="); DebugUtils.printSizeValue(pw, mLastRss * 1024);
                pw.println();
        pw.print(prefix); pw.print("procStateMemTracker: ");
        procStateMemTracker.dumpLine(pw);
        pw.print(prefix); pw.print("adjSeq="); pw.print(adjSeq);
                pw.print(" lruSeq="); pw.println(lruSeq);
        pw.print(prefix); pw.print("oom adj: max="); pw.print(maxAdj);
                pw.print(" curRaw="); pw.print(mCurRawAdj);
                pw.print(" setRaw="); pw.print(setRawAdj);
                pw.print(" cur="); pw.print(curAdj);
                pw.print(" set="); pw.println(setAdj);
        pw.print(prefix); pw.print("lastCompactTime="); pw.print(lastCompactTime);
                pw.print(" lastCompactAction="); pw.println(lastCompactAction);
        pw.print(prefix); pw.print("mCurSchedGroup="); pw.print(mCurSchedGroup);
                pw.print(" setSchedGroup="); pw.print(setSchedGroup);
                pw.print(" systemNoUi="); pw.print(systemNoUi);
                pw.print(" trimMemoryLevel="); pw.println(trimMemoryLevel);
        pw.print(prefix); pw.print("curProcState="); pw.print(getCurProcState());
                pw.print(" mRepProcState="); pw.print(mRepProcState);
                pw.print(" pssProcState="); pw.print(pssProcState);
                pw.print(" setProcState="); pw.print(setProcState);
                pw.print(" lastStateTime=");
                TimeUtils.formatDuration(lastStateTime, nowUptime, pw);
                pw.println();
        pw.print(prefix); pw.print("curCapability=");
                ActivityManager.printCapabilitiesFull(pw, curCapability);
                pw.print(" setCapability=");
                ActivityManager.printCapabilitiesFull(pw, setCapability);
                pw.println();
        if (hasShownUi || mPendingUiClean || hasAboveClient || treatLikeActivity) {
            pw.print(prefix); pw.print("hasShownUi="); pw.print(hasShownUi);
                    pw.print(" pendingUiClean="); pw.print(mPendingUiClean);
                    pw.print(" hasAboveClient="); pw.print(hasAboveClient);
                    pw.print(" treatLikeActivity="); pw.println(treatLikeActivity);
        }
        pw.print(prefix); pw.print("cached="); pw.print(mCached);
                pw.print(" empty="); pw.println(empty);
        if (serviceb) {
            pw.print(prefix); pw.print("serviceb="); pw.print(serviceb);
                    pw.print(" serviceHighRam="); pw.println(serviceHighRam);
        }
        if (notCachedSinceIdle) {
            pw.print(prefix); pw.print("notCachedSinceIdle="); pw.print(notCachedSinceIdle);
                    pw.print(" initialIdlePss="); pw.println(initialIdlePss);
        }
        if (connectionService != null || connectionGroup != 0) {
            pw.print(prefix); pw.print("connectionGroup="); pw.print(connectionGroup);
            pw.print(" Importance="); pw.print(connectionImportance);
            pw.print(" Service="); pw.println(connectionService);
        }
        if (hasTopUi() || hasOverlayUi() || runningRemoteAnimation) {
            pw.print(prefix); pw.print("hasTopUi="); pw.print(hasTopUi());
                    pw.print(" hasOverlayUi="); pw.print(hasOverlayUi());
                    pw.print(" runningRemoteAnimation="); pw.println(runningRemoteAnimation);
        }
        if (mHasForegroundServices || forcingToImportant != null) {
            pw.print(prefix); pw.print("mHasForegroundServices="); pw.print(mHasForegroundServices);
                    pw.print(" forcingToImportant="); pw.println(forcingToImportant);
        }
        if (reportedInteraction || mFgInteractionTime != 0) {
            pw.print(prefix); pw.print("reportedInteraction=");
            pw.print(reportedInteraction);
            if (mInteractionEventTime != 0) {
                pw.print(" time=");
                TimeUtils.formatDuration(mInteractionEventTime, SystemClock.elapsedRealtime(), pw);
            }
            if (mFgInteractionTime != 0) {
                pw.print(" fgInteractionTime=");
                TimeUtils.formatDuration(mFgInteractionTime, SystemClock.elapsedRealtime(), pw);
            }
            pw.println();
        }
        if (mPersistent || removed) {
            pw.print(prefix); pw.print("persistent="); pw.print(mPersistent);
                    pw.print(" removed="); pw.println(removed);
        }
        if (mHasClientActivities || mHasForegroundActivities || repForegroundActivities) {
            pw.print(prefix); pw.print("hasClientActivities="); pw.print(mHasClientActivities);
                    pw.print(" foregroundActivities="); pw.print(mHasForegroundActivities);
                    pw.print(" (rep="); pw.print(repForegroundActivities); pw.println(")");
        }
        if (lastProviderTime > 0) {
            pw.print(prefix); pw.print("lastProviderTime=");
            TimeUtils.formatDuration(lastProviderTime, nowUptime, pw);
            pw.println();
        }
        if (lastTopTime > 0) {
            pw.print(prefix); pw.print("lastTopTime=");
            TimeUtils.formatDuration(lastTopTime, nowUptime, pw);
            pw.println();
        }
        if (hasStartedServices) {
            pw.print(prefix); pw.print("hasStartedServices="); pw.println(hasStartedServices);
        }
        if (pendingStart) {
            pw.print(prefix); pw.print("pendingStart="); pw.println(pendingStart);
        }
        pw.print(prefix); pw.print("startSeq="); pw.println(startSeq);
        pw.print(prefix); pw.print("mountMode="); pw.println(
                DebugUtils.valueToString(Zygote.class, "MOUNT_EXTERNAL_", mountMode));
        if (setProcState > ActivityManager.PROCESS_STATE_SERVICE) {
            pw.print(prefix); pw.print("lastCpuTime="); pw.print(lastCpuTime);
                    if (lastCpuTime > 0) {
                        pw.print(" timeUsed=");
                        TimeUtils.formatDuration(curCpuTime - lastCpuTime, pw);
                    }
                    pw.print(" whenUnimportant=");
                    TimeUtils.formatDuration(mWhenUnimportant - nowUptime, pw);
                    pw.println();
        }
        pw.print(prefix); pw.print("lastRequestedGc=");
                TimeUtils.formatDuration(lastRequestedGc, nowUptime, pw);
                pw.print(" lastLowMemory=");
                TimeUtils.formatDuration(lastLowMemory, nowUptime, pw);
                pw.print(" reportLowMemory="); pw.println(reportLowMemory);
        if (killed || killedByAm || waitingToKill != null) {
            pw.print(prefix); pw.print("killed="); pw.print(killed);
                    pw.print(" killedByAm="); pw.print(killedByAm);
                    pw.print(" waitingToKill="); pw.println(waitingToKill);
        }
        if (mDebugging || mCrashing || mDialogController.hasCrashDialogs() || mNotResponding
                || mDialogController.hasAnrDialogs() || bad) {
            pw.print(prefix); pw.print("mDebugging="); pw.print(mDebugging);
            pw.print(" mCrashing=" + mCrashing);
            pw.print(" " + mDialogController.mCrashDialogs);
            pw.print(" mNotResponding=" + mNotResponding);
            pw.print(" " + mDialogController.mAnrDialogs);
            pw.print(" bad=" + bad);

            // mCrashing or mNotResponding is always set before errorReportReceiver
            if (errorReportReceiver != null) {
                pw.print(" errorReportReceiver=");
                pw.print(errorReportReceiver.flattenToShortString());
            }
            pw.println();
        }
        if (whitelistManager) {
            pw.print(prefix); pw.print("whitelistManager="); pw.println(whitelistManager);
        }
        if (isolatedEntryPoint != null || isolatedEntryPointArgs != null) {
            pw.print(prefix); pw.print("isolatedEntryPoint="); pw.println(isolatedEntryPoint);
            pw.print(prefix); pw.print("isolatedEntryPointArgs=");
            pw.println(Arrays.toString(isolatedEntryPointArgs));
        }
        mWindowProcessController.dump(pw, prefix);
        if (mServices.size() > 0) {
            pw.print(prefix); pw.println("Services:");
            for (int i = 0; i < mServices.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(mServices.valueAt(i));
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
        if (!curReceivers.isEmpty()) {
            pw.print(prefix); pw.println("Current Receivers:");
            for (int i=0; i < curReceivers.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(curReceivers.valueAt(i));
            }
        }
        if (receivers.size() > 0) {
            pw.print(prefix); pw.println("Receivers:");
            for (int i=0; i<receivers.size(); i++) {
                pw.print(prefix); pw.print("  - "); pw.println(receivers.valueAt(i));
            }
        }
        if (mAllowBackgroundActivityStartsTokens.size() > 0) {
            pw.print(prefix); pw.println("Background activity start whitelist tokens:");
            for (int i = 0; i < mAllowBackgroundActivityStartsTokens.size(); i++) {
                pw.print(prefix); pw.print("  - ");
                pw.println(mAllowBackgroundActivityStartsTokens.valueAt(i));
            }
        }
    }

    ProcessRecord(ActivityManagerService _service, ApplicationInfo _info, String _processName,
            int _uid) {
        mService = _service;
        info = _info;
        ProcessInfo procInfo = null;
        if (_service.mPackageManagerInt != null) {
            ArrayMap<String, ProcessInfo> processes =
                    _service.mPackageManagerInt.getProcessesForUid(_uid);
            if (processes != null) {
                procInfo = processes.get(_processName);
                if (procInfo != null && procInfo.deniedPermissions == null
                        && procInfo.gwpAsanMode == ApplicationInfo.GWP_ASAN_DEFAULT
                        && procInfo.memtagMode == ApplicationInfo.MEMTAG_DEFAULT
                        && procInfo.nativeHeapZeroInitialized == ApplicationInfo.ZEROINIT_DEFAULT) {
                    // If this process hasn't asked for permissions to be denied, or for a
                    // non-default GwpAsan mode, or any other non-default setting, then we don't
                    // care about it.
                    procInfo = null;
                }
            }
        }
        processInfo = procInfo;
        isolated = _info.uid != _uid;
        appZygote = (UserHandle.getAppId(_uid) >= Process.FIRST_APP_ZYGOTE_ISOLATED_UID
                && UserHandle.getAppId(_uid) <= Process.LAST_APP_ZYGOTE_ISOLATED_UID);
        uid = _uid;
        userId = UserHandle.getUserId(_uid);
        processName = _processName;
        maxAdj = ProcessList.UNKNOWN_ADJ;
        mCurRawAdj = setRawAdj = ProcessList.INVALID_ADJ;
        curAdj = setAdj = verifiedAdj = ProcessList.INVALID_ADJ;
        mPersistent = false;
        removed = false;
        freezeUnfreezeTime = lastStateTime = lastPssTime = nextPssTime = SystemClock.uptimeMillis();
        mWindowProcessController = new WindowProcessController(
                mService.mActivityTaskManager, info, processName, uid, userId, this, this);
        pkgList.put(_info.packageName, new ProcessStats.ProcessStateHolder(_info.longVersionCode));
    }

    public void setPid(int _pid) {
        pid = _pid;
        mWindowProcessController.setPid(pid);
        procStatFile = null;
        shortStringName = null;
        stringName = null;
    }

    public void makeActive(IApplicationThread _thread, ProcessStatsService tracker) {
        if (thread == null) {
            final ProcessState origBase = baseProcessTracker;
            if (origBase != null) {
                origBase.setState(ProcessStats.STATE_NOTHING,
                        tracker.getMemFactorLocked(), SystemClock.uptimeMillis(), pkgList.mPkgList);
                for (int ipkg = pkgList.size() - 1; ipkg >= 0; ipkg--) {
                    FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_STATE_CHANGED,
                            uid, processName, pkgList.keyAt(ipkg),
                            ActivityManager.processStateAmToProto(ProcessStats.STATE_NOTHING),
                            pkgList.valueAt(ipkg).appVersion);
                }
                origBase.makeInactive();
            }
            baseProcessTracker = tracker.getProcessStateLocked(info.packageName, info.uid,
                    info.longVersionCode, processName);
            baseProcessTracker.makeActive();
            for (int i=0; i<pkgList.size(); i++) {
                ProcessStats.ProcessStateHolder holder = pkgList.valueAt(i);
                if (holder.state != null && holder.state != origBase) {
                    holder.state.makeInactive();
                }
                tracker.updateProcessStateHolderLocked(holder, pkgList.keyAt(i), info.uid,
                        info.longVersionCode, processName);
                if (holder.state != baseProcessTracker) {
                    holder.state.makeActive();
                }
            }
        }
        thread = _thread;
        mWindowProcessController.setThread(thread);
    }

    public void makeInactive(ProcessStatsService tracker) {
        thread = null;
        mWindowProcessController.setThread(null);
        final ProcessState origBase = baseProcessTracker;
        if (origBase != null) {
            if (origBase != null) {
                origBase.setState(ProcessStats.STATE_NOTHING,
                        tracker.getMemFactorLocked(), SystemClock.uptimeMillis(), pkgList.mPkgList);
                for (int ipkg = pkgList.size() - 1; ipkg >= 0; ipkg--) {
                    FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_STATE_CHANGED,
                            uid, processName, pkgList.keyAt(ipkg),
                            ActivityManager.processStateAmToProto(ProcessStats.STATE_NOTHING),
                            pkgList.valueAt(ipkg).appVersion);
                }
                origBase.makeInactive();
            }
            baseProcessTracker = null;
            for (int i=0; i<pkgList.size(); i++) {
                ProcessStats.ProcessStateHolder holder = pkgList.valueAt(i);
                if (holder.state != null && holder.state != origBase) {
                    holder.state.makeInactive();
                }
                holder.pkg = null;
                holder.state = null;
            }
        }
    }

    /**
     * Records a service as running in the process. Note that this method does not actually start
     * the service, but records the service as started for bookkeeping.
     *
     * @return true if the service was added, false otherwise.
     */
    boolean startService(ServiceRecord record) {
        if (record == null) {
            return false;
        }
        boolean added = mServices.add(record);
        if (added && record.serviceInfo != null) {
            mWindowProcessController.onServiceStarted(record.serviceInfo);
        }
        return added;
    }

    /**
     * Records a service as stopped. Note that like {@link #startService(ServiceRecord)} this method
     * does not actually stop the service, but records the service as stopped for bookkeeping.
     *
     * @return true if the service was removed, false otherwise.
     */
    boolean stopService(ServiceRecord record) {
        return mServices.remove(record);
    }

    /**
     * The same as calling {@link #stopService(ServiceRecord)} on all current running services.
     */
    void stopAllServices() {
        mServices.clear();
    }

    /**
     * Returns the number of services added with {@link #startService(ServiceRecord)} and not yet
     * removed by a call to {@link #stopService(ServiceRecord)} or {@link #stopAllServices()}.
     *
     * @see #startService(ServiceRecord)
     * @see #stopService(ServiceRecord)
     */
    int numberOfRunningServices() {
        return mServices.size();
    }

    /**
     * Returns the service at the specified {@code index}.
     *
     * @see #numberOfRunningServices()
     */
    ServiceRecord getRunningServiceAt(int index) {
        return mServices.valueAt(index);
    }

    void setCached(boolean cached) {
        if (mCached != cached) {
            mCached = cached;
            mWindowProcessController.onProcCachedStateChanged(cached);
        }
    }

    @Override
    public boolean isCached() {
        return mCached;
    }

    boolean hasActivities() {
        return mWindowProcessController.hasActivities();
    }

    boolean hasActivitiesOrRecentTasks() {
        return mWindowProcessController.hasActivitiesOrRecentTasks();
    }

    boolean hasRecentTasks() {
        return mWindowProcessController.hasRecentTasks();
    }

    /**
     * This method returns true if any of the activities within the process record are interesting
     * to the user. See HistoryRecord.isInterestingToUserLocked()
     */
    public boolean isInterestingToUserLocked() {
        if (mWindowProcessController.isInterestingToUser()) {
            return true;
        }

        final int servicesSize = mServices.size();
        for (int i = 0; i < servicesSize; i++) {
            ServiceRecord r = mServices.valueAt(i);
            if (r.isForeground) {
                return true;
            }
        }
        return false;
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
            } else if (adj < ProcessList.PERCEPTIBLE_LOW_APP_ADJ) {
                adj = ProcessList.PERCEPTIBLE_LOW_APP_ADJ;
            } else if (adj < ProcessList.CACHED_APP_MIN_ADJ) {
                adj = ProcessList.CACHED_APP_MIN_ADJ;
            } else if (adj < ProcessList.CACHED_APP_MAX_ADJ) {
                adj++;
            }
        }
        return adj;
    }

    void scheduleCrash(String message) {
        // Checking killedbyAm should keep it from showing the crash dialog if the process
        // was already dead for a good / normal reason.
        if (!killedByAm) {
            if (thread != null) {
                if (pid == Process.myPid()) {
                    Slog.w(TAG, "scheduleCrash: trying to crash system process!");
                    return;
                }
                long ident = Binder.clearCallingIdentity();
                try {
                    thread.scheduleCrash(message);
                } catch (RemoteException e) {
                    // If it's already dead our work is done. If it's wedged just kill it.
                    // We won't get the crash dialog or the error reporting.
                    kill("scheduleCrash for '" + message + "' failed",
                            ApplicationExitInfo.REASON_CRASH, true);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    void kill(String reason, @Reason int reasonCode, boolean noisy) {
        kill(reason, reasonCode, ApplicationExitInfo.SUBREASON_UNKNOWN, noisy);
    }

    void kill(String reason, @Reason int reasonCode, @SubReason int subReason, boolean noisy) {
        if (!killedByAm) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "kill");
            if (mService != null && (noisy || info.uid == mService.mCurOomAdjUid)) {
                mService.reportUidInfoMessageLocked(TAG,
                        "Killing " + toShortString() + " (adj " + setAdj + "): " + reason,
                        info.uid);
            }
            if (pid > 0) {
                mService.mProcessList.noteAppKill(this, reasonCode, subReason, reason);
                EventLog.writeEvent(EventLogTags.AM_KILL, userId, pid, processName, setAdj, reason);
                Process.killProcessQuiet(pid);
                ProcessList.killProcessGroup(uid, pid);
            } else {
                pendingStart = false;
            }
            if (!mPersistent) {
                killed = true;
                killedByAm = true;
            }
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        dumpDebug(proto, fieldId, -1);
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId, int lruIndex) {
        long token = proto.start(fieldId);
        proto.write(ProcessRecordProto.PID, pid);
        proto.write(ProcessRecordProto.PROCESS_NAME, processName);
        proto.write(ProcessRecordProto.UID, info.uid);
        if (UserHandle.getAppId(info.uid) >= Process.FIRST_APPLICATION_UID) {
            proto.write(ProcessRecordProto.USER_ID, userId);
            proto.write(ProcessRecordProto.APP_ID, UserHandle.getAppId(info.uid));
        }
        if (uid != info.uid) {
            proto.write(ProcessRecordProto.ISOLATED_APP_ID, UserHandle.getAppId(uid));
        }
        proto.write(ProcessRecordProto.PERSISTENT, mPersistent);
        if (lruIndex >= 0) {
            proto.write(ProcessRecordProto.LRU_INDEX, lruIndex);
        }
        proto.end(token);
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
    public boolean addPackage(String pkg, long versionCode, ProcessStatsService tracker) {
        if (!pkgList.containsKey(pkg)) {
            ProcessStats.ProcessStateHolder holder = new ProcessStats.ProcessStateHolder(
                    versionCode);
            if (baseProcessTracker != null) {
                tracker.updateProcessStateHolderLocked(holder, pkg, info.uid, versionCode,
                        processName);
                pkgList.put(pkg, holder);
                if (holder.state != baseProcessTracker) {
                    holder.state.makeActive();
                }
            } else {
                pkgList.put(pkg, holder);
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
        if (mRepProcState > newState) {
            mRepProcState = newState;
            setCurProcState(newState);
            setCurRawProcState(newState);
            for (int ipkg = pkgList.size() - 1; ipkg >= 0; ipkg--) {
                FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_STATE_CHANGED,
                        uid, processName, pkgList.keyAt(ipkg),
                        ActivityManager.processStateAmToProto(mRepProcState),
                        pkgList.valueAt(ipkg).appVersion);
            }
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
                    tracker.getMemFactorLocked(), now, pkgList.mPkgList);
            for (int ipkg = pkgList.size() - 1; ipkg >= 0; ipkg--) {
                FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_STATE_CHANGED,
                        uid, processName, pkgList.keyAt(ipkg),
                        ActivityManager.processStateAmToProto(ProcessStats.STATE_NOTHING),
                        pkgList.valueAt(ipkg).appVersion);
            }
            if (N != 1) {
                for (int i=0; i<N; i++) {
                    ProcessStats.ProcessStateHolder holder = pkgList.valueAt(i);
                    if (holder.state != null && holder.state != baseProcessTracker) {
                        holder.state.makeInactive();
                    }

                }
                pkgList.clear();
                ProcessStats.ProcessStateHolder holder = new ProcessStats.ProcessStateHolder(
                        info.longVersionCode);
                tracker.updateProcessStateHolderLocked(holder, info.packageName, info.uid,
                        info.longVersionCode, processName);
                pkgList.put(info.packageName, holder);
                if (holder.state != baseProcessTracker) {
                    holder.state.makeActive();
                }
            }
        } else if (N != 1) {
            pkgList.clear();
            pkgList.put(info.packageName, new ProcessStats.ProcessStateHolder(info.longVersionCode));
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

    public List<VersionedPackage> getPackageListWithVersionCode() {
        int size = pkgList.size();
        if (size == 0) {
            return null;
        }
        List<VersionedPackage> list = new ArrayList<>();
        for (int i = 0; i < pkgList.size(); i++) {
            list.add(new VersionedPackage(pkgList.keyAt(i), pkgList.valueAt(i).appVersion));
        }
        return list;
    }

    WindowProcessController getWindowProcessController() {
        return mWindowProcessController;
    }

    void setCurrentSchedulingGroup(int curSchedGroup) {
        mCurSchedGroup = curSchedGroup;
        mWindowProcessController.setCurrentSchedulingGroup(curSchedGroup);
    }

    int getCurrentSchedulingGroup() {
        return mCurSchedGroup;
    }

    void setCurProcState(int curProcState) {
        mCurProcState = curProcState;
        mWindowProcessController.setCurrentProcState(mCurProcState);
    }

    int getCurProcState() {
        return mCurProcState;
    }

    void setCurRawProcState(int curRawProcState) {
        mCurRawProcState = curRawProcState;
    }

    int getCurRawProcState() {
        return mCurRawProcState;
    }

    void setReportedProcState(int repProcState) {
        mRepProcState = repProcState;
        for (int ipkg = pkgList.size() - 1; ipkg >= 0; ipkg--) {
            FrameworkStatsLog.write(FrameworkStatsLog.PROCESS_STATE_CHANGED,
                    uid, processName, pkgList.keyAt(ipkg),
                    ActivityManager.processStateAmToProto(mRepProcState),
                    pkgList.valueAt(ipkg).appVersion);
        }
        mWindowProcessController.setReportedProcState(repProcState);
    }

    int getReportedProcState() {
        return mRepProcState;
    }

    void setCrashing(boolean crashing) {
        mCrashing = crashing;
        mWindowProcessController.setCrashing(crashing);
    }

    boolean isCrashing() {
        return mCrashing;
    }

    void setNotResponding(boolean notResponding) {
        mNotResponding = notResponding;
        mWindowProcessController.setNotResponding(notResponding);
    }

    boolean isNotResponding() {
        return mNotResponding;
    }

    void setPersistent(boolean persistent) {
        mPersistent = persistent;
        mWindowProcessController.setPersistent(persistent);
    }

    boolean isPersistent() {
        return mPersistent;
    }

    public void setRequiredAbi(String requiredAbi) {
        mRequiredAbi = requiredAbi;
        mWindowProcessController.setRequiredAbi(requiredAbi);
    }

    String getRequiredAbi() {
        return mRequiredAbi;
    }

    void setHasForegroundServices(boolean hasForegroundServices, int fgServiceTypes) {
        mHasForegroundServices = hasForegroundServices;
        mFgServiceTypes = fgServiceTypes;
        mWindowProcessController.setHasForegroundServices(hasForegroundServices);
    }

    boolean hasForegroundServices() {
        return mHasForegroundServices;
    }

    boolean hasLocationForegroundServices() {
        return mHasForegroundServices
                && (mFgServiceTypes & ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0;
    }

    boolean hasLocationCapability() {
        return (setCapability & ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION) != 0;
    }

    int getForegroundServiceTypes() {
        return mHasForegroundServices ? mFgServiceTypes : 0;
    }

    int getReportedForegroundServiceTypes() {
        return mRepFgServiceTypes;
    }

    void setReportedForegroundServiceTypes(int foregroundServiceTypes) {
        mRepFgServiceTypes = foregroundServiceTypes;
    }

    void setHasForegroundActivities(boolean hasForegroundActivities) {
        mHasForegroundActivities = hasForegroundActivities;
        mWindowProcessController.setHasForegroundActivities(hasForegroundActivities);
    }

    boolean hasForegroundActivities() {
        return mHasForegroundActivities;
    }

    void setHasClientActivities(boolean hasClientActivities) {
        mHasClientActivities = hasClientActivities;
        mWindowProcessController.setHasClientActivities(hasClientActivities);
    }

    boolean hasClientActivities() {
        return mHasClientActivities;
    }

    void setHasTopUi(boolean hasTopUi) {
        mHasTopUi = hasTopUi;
        mWindowProcessController.setHasTopUi(hasTopUi);
    }

    boolean hasTopUi() {
        return mHasTopUi;
    }

    void setHasOverlayUi(boolean hasOverlayUi) {
        mHasOverlayUi = hasOverlayUi;
        mWindowProcessController.setHasOverlayUi(hasOverlayUi);
    }

    boolean hasOverlayUi() {
        return mHasOverlayUi;
    }

    void setInteractionEventTime(long interactionEventTime) {
        mInteractionEventTime = interactionEventTime;
        mWindowProcessController.setInteractionEventTime(interactionEventTime);
    }

    long getInteractionEventTime() {
        return mInteractionEventTime;
    }

    void setFgInteractionTime(long fgInteractionTime) {
        mFgInteractionTime = fgInteractionTime;
        mWindowProcessController.setFgInteractionTime(fgInteractionTime);
    }

    long getFgInteractionTime() {
        return mFgInteractionTime;
    }

    void setWhenUnimportant(long whenUnimportant) {
        mWhenUnimportant = whenUnimportant;
        mWindowProcessController.setWhenUnimportant(whenUnimportant);
    }

    long getWhenUnimportant() {
        return mWhenUnimportant;
    }

    void setDebugging(boolean debugging) {
        mDebugging = debugging;
        mWindowProcessController.setDebugging(debugging);
    }

    boolean isDebugging() {
        return mDebugging;
    }

    void setUsingWrapper(boolean usingWrapper) {
        mUsingWrapper = usingWrapper;
        mWindowProcessController.setUsingWrapper(usingWrapper);
    }

    boolean isUsingWrapper() {
        return mUsingWrapper;
    }

    void addAllowBackgroundActivityStartsToken(Binder entity) {
        if (entity == null) return;
        mAllowBackgroundActivityStartsTokens.add(entity);
        mWindowProcessController.setAllowBackgroundActivityStarts(true);
    }

    void removeAllowBackgroundActivityStartsToken(Binder entity) {
        if (entity == null) return;
        mAllowBackgroundActivityStartsTokens.remove(entity);
        mWindowProcessController.setAllowBackgroundActivityStarts(
                !mAllowBackgroundActivityStartsTokens.isEmpty());
    }

    void addBoundClientUid(int clientUid) {
        mBoundClientUids.add(clientUid);
        mWindowProcessController.setBoundClientUids(mBoundClientUids);
    }

    void updateBoundClientUids() {
        if (mServices.isEmpty()) {
            clearBoundClientUids();
            return;
        }
        // grab a set of clientUids of all connections of all services
        ArraySet<Integer> boundClientUids = new ArraySet<>();
        final int serviceCount = mServices.size();
        for (int j = 0; j < serviceCount; j++) {
            ArrayMap<IBinder, ArrayList<ConnectionRecord>> conns =
                    mServices.valueAt(j).getConnections();
            final int N = conns.size();
            for (int conni = 0; conni < N; conni++) {
                ArrayList<ConnectionRecord> c = conns.valueAt(conni);
                for (int i = 0; i < c.size(); i++) {
                    boundClientUids.add(c.get(i).clientUid);
                }
            }
        }
        mBoundClientUids = boundClientUids;
        mWindowProcessController.setBoundClientUids(mBoundClientUids);
    }

    void addBoundClientUidsOfNewService(ServiceRecord sr) {
        if (sr == null) {
            return;
        }
        ArrayMap<IBinder, ArrayList<ConnectionRecord>> conns = sr.getConnections();
        for (int conni = conns.size() - 1; conni >= 0; conni--) {
            ArrayList<ConnectionRecord> c = conns.valueAt(conni);
            for (int i = 0; i < c.size(); i++) {
                mBoundClientUids.add(c.get(i).clientUid);
            }
        }
        mWindowProcessController.setBoundClientUids(mBoundClientUids);
    }

    void clearBoundClientUids() {
        mBoundClientUids.clear();
        mWindowProcessController.setBoundClientUids(mBoundClientUids);
    }

    void setActiveInstrumentation(ActiveInstrumentation instr) {
        mInstr = instr;
        boolean isInstrumenting = instr != null;
        mWindowProcessController.setInstrumenting(isInstrumenting,
                isInstrumenting && instr.mHasBackgroundActivityStartsPermission);
    }

    ActiveInstrumentation getActiveInstrumentation() {
        return mInstr;
    }

    void setCurRawAdj(int curRawAdj) {
        mCurRawAdj = curRawAdj;
        mWindowProcessController.setPerceptible(curRawAdj <= ProcessList.PERCEPTIBLE_APP_ADJ);
    }

    int getCurRawAdj() {
        return mCurRawAdj;
    }

    @Override
    public void clearProfilerIfNeeded() {
        synchronized (mService) {
            if (mService.mProfileData.getProfileProc() == null
                    || mService.mProfileData.getProfilerInfo() == null
                    || mService.mProfileData.getProfileProc() != this) {
                return;
            }
            mService.clearProfilerLocked();
        }
    }

    @Override
    public void updateServiceConnectionActivities() {
        synchronized (mService) {
            mService.mServices.updateServiceConnectionActivitiesLocked(this);
        }
    }

    @Override
    public void setPendingUiClean(boolean pendingUiClean) {
        synchronized (mService) {
            mPendingUiClean = pendingUiClean;
            mWindowProcessController.setPendingUiClean(pendingUiClean);
        }
    }

    boolean hasPendingUiClean() {
        return mPendingUiClean;
    }

    @Override
    public void setPendingUiCleanAndForceProcessStateUpTo(int newState) {
        synchronized (mService) {
            setPendingUiClean(true);
            forceProcessStateUpTo(newState);
        }
    }

    @Override
    public void updateProcessInfo(boolean updateServiceConnectionActivities, boolean activityChange,
            boolean updateOomAdj) {
        synchronized (mService) {
            if (updateServiceConnectionActivities) {
                mService.mServices.updateServiceConnectionActivitiesLocked(this);
            }
            mService.mProcessList.updateLruProcessLocked(this, activityChange, null /* client */);
            if (updateOomAdj) {
                mService.updateOomAdjLocked(this, OomAdjuster.OOM_ADJ_REASON_ACTIVITY);
            }
        }
    }

    @Override
    public boolean isRemoved() {
        return removed;
    }

    /**
     * Returns the total time (in milliseconds) spent executing in both user and system code.
     * Safe to call without lock held.
     */
    @Override
    public long getCpuTime() {
        return mService.mProcessCpuTracker.getCpuTimeForPid(pid);
    }

    @Override
    public void onStartActivity(int topProcessState, boolean setProfileProc, String packageName,
            long versionCode) {
        synchronized (mService) {
            waitingToKill = null;
            if (setProfileProc) {
                mService.mProfileData.setProfileProc(this);
            }
            if (packageName != null) {
                addPackage(packageName, versionCode, mService.mProcessStats);
            }

            // Update oom adj first, we don't want the additional states are involved in this round.
            updateProcessInfo(false /* updateServiceConnectionActivities */,
                    true /* activityChange */, true /* updateOomAdj */);
            hasShownUi = true;
            setPendingUiClean(true);
            forceProcessStateUpTo(topProcessState);
        }
    }

    @Override
    public void appDied(String reason) {
        synchronized (mService) {
            mService.appDiedLocked(this, reason);
        }
    }

    @Override
    public void setRunningRemoteAnimation(boolean runningRemoteAnimation) {
        if (pid == Process.myPid()) {
            Slog.wtf(TAG, "system can't run remote animation");
            return;
        }
        synchronized (mService) {
            if (this.runningRemoteAnimation == runningRemoteAnimation) {
                return;
            }
            this.runningRemoteAnimation = runningRemoteAnimation;
            if (DEBUG_OOM_ADJ) {
                Slog.i(TAG, "Setting runningRemoteAnimation=" + runningRemoteAnimation
                        + " for pid=" + pid);
            }
            mService.updateOomAdjLocked(this, true, OomAdjuster.OOM_ADJ_REASON_UI_VISIBILITY);
        }
    }

    public long getInputDispatchingTimeout() {
        return mWindowProcessController.getInputDispatchingTimeout();
    }

    public int getProcessClassEnum() {
        if (pid == MY_PID) {
            return ServerProtoEnums.SYSTEM_SERVER;
        }
        if (info == null) {
            return ServerProtoEnums.ERROR_SOURCE_UNKNOWN;
        }
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? ServerProtoEnums.SYSTEM_APP :
            ServerProtoEnums.DATA_APP;
    }

    /**
     * Unless configured otherwise, swallow ANRs in background processes & kill the process.
     * Non-private access is for tests only.
     */
    @VisibleForTesting
    boolean isSilentAnr() {
        return !getShowBackground() && !isInterestingForBackgroundTraces();
    }

    /** Non-private access is for tests only. */
    @VisibleForTesting
    List<ProcessRecord> getLruProcessList() {
        return mService.mProcessList.mLruProcesses;
    }

    /** Non-private access is for tests only. */
    @VisibleForTesting
    boolean isMonitorCpuUsage() {
        return mService.MONITOR_CPU_USAGE;
    }

    void appNotResponding(String activityShortComponentName, ApplicationInfo aInfo,
            String parentShortComponentName, WindowProcessController parentProcess,
            boolean aboveSystem, String annotation, boolean onlyDumpSelf) {
        ArrayList<Integer> firstPids = new ArrayList<>(5);
        SparseArray<Boolean> lastPids = new SparseArray<>(20);

        mWindowProcessController.appEarlyNotResponding(annotation, () -> kill("anr",
                  ApplicationExitInfo.REASON_ANR, true));

        long anrTime = SystemClock.uptimeMillis();
        if (isMonitorCpuUsage()) {
            mService.updateCpuStatsNow();
        }

        final boolean isSilentAnr;
        synchronized (mService) {
            // PowerManager.reboot() can block for a long time, so ignore ANRs while shutting down.
            if (mService.mAtmInternal.isShuttingDown()) {
                Slog.i(TAG, "During shutdown skipping ANR: " + this + " " + annotation);
                return;
            } else if (isNotResponding()) {
                Slog.i(TAG, "Skipping duplicate ANR: " + this + " " + annotation);
                return;
            } else if (isCrashing()) {
                Slog.i(TAG, "Crashing app skipping ANR: " + this + " " + annotation);
                return;
            } else if (killedByAm) {
                Slog.i(TAG, "App already killed by AM skipping ANR: " + this + " " + annotation);
                return;
            } else if (killed) {
                Slog.i(TAG, "Skipping died app ANR: " + this + " " + annotation);
                return;
            }

            // In case we come through here for the same app before completing
            // this one, mark as anring now so we will bail out.
            setNotResponding(true);

            // Log the ANR to the event log.
            EventLog.writeEvent(EventLogTags.AM_ANR, userId, pid, processName, info.flags,
                    annotation);

            // Dump thread traces as quickly as we can, starting with "interesting" processes.
            firstPids.add(pid);

            // Don't dump other PIDs if it's a background ANR or is requested to only dump self.
            isSilentAnr = isSilentAnr();
            if (!isSilentAnr && !onlyDumpSelf) {
                int parentPid = pid;
                if (parentProcess != null && parentProcess.getPid() > 0) {
                    parentPid = parentProcess.getPid();
                }
                if (parentPid != pid) firstPids.add(parentPid);

                if (MY_PID != pid && MY_PID != parentPid) firstPids.add(MY_PID);

                for (int i = getLruProcessList().size() - 1; i >= 0; i--) {
                    ProcessRecord r = getLruProcessList().get(i);
                    if (r != null && r.thread != null) {
                        int myPid = r.pid;
                        if (myPid > 0 && myPid != pid && myPid != parentPid && myPid != MY_PID) {
                            if (r.isPersistent()) {
                                firstPids.add(myPid);
                                if (DEBUG_ANR) Slog.i(TAG, "Adding persistent proc: " + r);
                            } else if (r.treatLikeActivity) {
                                firstPids.add(myPid);
                                if (DEBUG_ANR) Slog.i(TAG, "Adding likely IME: " + r);
                            } else {
                                lastPids.put(myPid, Boolean.TRUE);
                                if (DEBUG_ANR) Slog.i(TAG, "Adding ANR proc: " + r);
                            }
                        }
                    }
                }
            }
        }

        // Log the ANR to the main log.
        StringBuilder info = new StringBuilder();
        info.setLength(0);
        info.append("ANR in ").append(processName);
        if (activityShortComponentName != null) {
            info.append(" (").append(activityShortComponentName).append(")");
        }
        info.append("\n");
        info.append("PID: ").append(pid).append("\n");
        if (annotation != null) {
            info.append("Reason: ").append(annotation).append("\n");
        }
        if (parentShortComponentName != null
                && parentShortComponentName.equals(activityShortComponentName)) {
            info.append("Parent: ").append(parentShortComponentName).append("\n");
        }

        StringBuilder report = new StringBuilder();
        report.append(MemoryPressureUtil.currentPsiState());
        ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(true);

        // don't dump native PIDs for background ANRs unless it is the process of interest
        String[] nativeProcs = null;
        if (isSilentAnr || onlyDumpSelf) {
            for (int i = 0; i < NATIVE_STACKS_OF_INTEREST.length; i++) {
                if (NATIVE_STACKS_OF_INTEREST[i].equals(processName)) {
                    nativeProcs = new String[] { processName };
                    break;
                }
            }
        } else {
            nativeProcs = NATIVE_STACKS_OF_INTEREST;
        }

        int[] pids = nativeProcs == null ? null : Process.getPidsForCommands(nativeProcs);
        ArrayList<Integer> nativePids = null;

        if (pids != null) {
            nativePids = new ArrayList<>(pids.length);
            for (int i : pids) {
                nativePids.add(i);
            }
        }

        // For background ANRs, don't pass the ProcessCpuTracker to
        // avoid spending 1/2 second collecting stats to rank lastPids.
        StringWriter tracesFileException = new StringWriter();
        // To hold the start and end offset to the ANR trace file respectively.
        final long[] offsets = new long[2];
        File tracesFile = ActivityManagerService.dumpStackTraces(firstPids,
                isSilentAnr ? null : processCpuTracker, isSilentAnr ? null : lastPids,
                nativePids, tracesFileException, offsets);

        if (isMonitorCpuUsage()) {
            mService.updateCpuStatsNow();
            synchronized (mService.mProcessCpuTracker) {
                report.append(mService.mProcessCpuTracker.printCurrentState(anrTime));
            }
            info.append(processCpuTracker.printCurrentLoad());
            info.append(report);
        }
        report.append(tracesFileException.getBuffer());

        info.append(processCpuTracker.printCurrentState(anrTime));

        Slog.e(TAG, info.toString());
        if (tracesFile == null) {
            // There is no trace file, so dump (only) the alleged culprit's threads to the log
            Process.sendSignal(pid, Process.SIGNAL_QUIT);
        } else if (offsets[1] > 0) {
            // We've dumped into the trace file successfully
            mService.mProcessList.mAppExitInfoTracker.scheduleLogAnrTrace(
                    pid, uid, getPackageList(), tracesFile, offsets[0], offsets[1]);
        }

        FrameworkStatsLog.write(FrameworkStatsLog.ANR_OCCURRED, uid, processName,
                activityShortComponentName == null ? "unknown": activityShortComponentName,
                annotation,
                (this.info != null) ? (this.info.isInstantApp()
                        ? FrameworkStatsLog.ANROCCURRED__IS_INSTANT_APP__TRUE
                        : FrameworkStatsLog.ANROCCURRED__IS_INSTANT_APP__FALSE)
                        : FrameworkStatsLog.ANROCCURRED__IS_INSTANT_APP__UNAVAILABLE,
                isInterestingToUserLocked()
                        ? FrameworkStatsLog.ANROCCURRED__FOREGROUND_STATE__FOREGROUND
                        : FrameworkStatsLog.ANROCCURRED__FOREGROUND_STATE__BACKGROUND,
                getProcessClassEnum(),
                (this.info != null) ? this.info.packageName : "");
        final ProcessRecord parentPr = parentProcess != null
                ? (ProcessRecord) parentProcess.mOwner : null;
        mService.addErrorToDropBox("anr", this, processName, activityShortComponentName,
                parentShortComponentName, parentPr, annotation, report.toString(), tracesFile,
                null);

        if (mWindowProcessController.appNotResponding(info.toString(), () -> kill("anr",
                ApplicationExitInfo.REASON_ANR, true),
                () -> {
                    synchronized (mService) {
                        mService.mServices.scheduleServiceTimeoutLocked(this);
                    }
                })) {
            return;
        }

        synchronized (mService) {
            // mBatteryStatsService can be null if the AMS is constructed with injector only. This
            // will only happen in tests.
            if (mService.mBatteryStatsService != null) {
                mService.mBatteryStatsService.noteProcessAnr(processName, uid);
            }

            if (isSilentAnr() && !isDebugging()) {
                kill("bg anr", ApplicationExitInfo.REASON_ANR, true);
                return;
            }

            // Set the app's notResponding state, and look up the errorReportReceiver
            makeAppNotRespondingLocked(activityShortComponentName,
                    annotation != null ? "ANR " + annotation : "ANR", info.toString());

            // mUiHandler can be null if the AMS is constructed with injector only. This will only
            // happen in tests.
            if (mService.mUiHandler != null) {
                // Bring up the infamous App Not Responding dialog
                Message msg = Message.obtain();
                msg.what = ActivityManagerService.SHOW_NOT_RESPONDING_UI_MSG;
                msg.obj = new AppNotRespondingDialog.Data(this, aInfo, aboveSystem);

                mService.mUiHandler.sendMessage(msg);
            }
        }
    }

    private void makeAppNotRespondingLocked(String activity, String shortMsg, String longMsg) {
        setNotResponding(true);
        // mAppErrors can be null if the AMS is constructed with injector only. This will only
        // happen in tests.
        if (mService.mAppErrors != null) {
            notRespondingReport = mService.mAppErrors.generateProcessError(this,
                    ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING,
                    activity, shortMsg, longMsg, null);
        }
        startAppProblemLocked();
        getWindowProcessController().stopFreezingActivities();
    }

    void startAppProblemLocked() {
        // If this app is not running under the current user, then we can't give it a report button
        // because that would require launching the report UI under a different user.
        errorReportReceiver = null;

        for (int userId : mService.mUserController.getCurrentProfileIds()) {
            if (this.userId == userId) {
                errorReportReceiver = ApplicationErrorReport.getErrorReportReceiver(
                        mService.mContext, info.packageName, info.flags);
            }
        }
        mService.skipCurrentReceiverLocked(this);
    }

    private boolean isInterestingForBackgroundTraces() {
        // The system_server is always considered interesting.
        if (pid == MY_PID) {
            return true;
        }

        // A package is considered interesting if any of the following is true :
        //
        // - It's displaying an activity.
        // - It's the SystemUI.
        // - It has an overlay or a top UI visible.
        //
        // NOTE: The check whether a given ProcessRecord belongs to the systemui
        // process is a bit of a kludge, but the same pattern seems repeated at
        // several places in the system server.
        return isInterestingToUserLocked() ||
                (info != null && "com.android.systemui".equals(info.packageName))
                || (hasTopUi() || hasOverlayUi());
    }

    private boolean getShowBackground() {
        return Settings.Secure.getInt(mService.mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;
    }

    void resetCachedInfo() {
        mCachedHasActivities = VALUE_INVALID;
        mCachedIsHeavyWeight = VALUE_INVALID;
        mCachedHasVisibleActivities = VALUE_INVALID;
        mCachedIsHomeProcess = VALUE_INVALID;
        mCachedIsPreviousProcess = VALUE_INVALID;
        mCachedHasRecentTasks = VALUE_INVALID;
        mCachedIsReceivingBroadcast = VALUE_INVALID;
        mCachedAdj = ProcessList.INVALID_ADJ;
        mCachedForegroundActivities = false;
        mCachedProcState = ActivityManager.PROCESS_STATE_CACHED_EMPTY;
        mCachedSchedGroup = ProcessList.SCHED_GROUP_BACKGROUND;
    }

    boolean getCachedHasActivities() {
        if (mCachedHasActivities == VALUE_INVALID) {
            mCachedHasActivities = getWindowProcessController().hasActivities() ? VALUE_TRUE
                    : VALUE_FALSE;
        }
        return mCachedHasActivities == VALUE_TRUE;
    }

    boolean getCachedIsHeavyWeight() {
        if (mCachedIsHeavyWeight == VALUE_INVALID) {
            mCachedIsHeavyWeight = mService.mAtmInternal.isHeavyWeightProcess(
                    getWindowProcessController()) ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedIsHeavyWeight == VALUE_TRUE;
    }

    boolean getCachedHasVisibleActivities() {
        if (mCachedHasVisibleActivities == VALUE_INVALID) {
            mCachedHasVisibleActivities = getWindowProcessController().hasVisibleActivities()
                    ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedHasVisibleActivities == VALUE_TRUE;
    }

    boolean getCachedIsHomeProcess() {
        if (mCachedIsHomeProcess == VALUE_INVALID) {
            mCachedIsHomeProcess = getWindowProcessController().isHomeProcess()
                    ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedIsHomeProcess == VALUE_TRUE;
    }

    boolean getCachedIsPreviousProcess() {
        if (mCachedIsPreviousProcess == VALUE_INVALID) {
            mCachedIsPreviousProcess = getWindowProcessController().isPreviousProcess()
                    ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedIsPreviousProcess == VALUE_TRUE;
    }

    boolean getCachedHasRecentTasks() {
        if (mCachedHasRecentTasks == VALUE_INVALID) {
            mCachedHasRecentTasks = getWindowProcessController().hasRecentTasks()
                    ? VALUE_TRUE : VALUE_FALSE;
        }
        return mCachedHasRecentTasks == VALUE_TRUE;
    }

    boolean getCachedIsReceivingBroadcast(ArraySet<BroadcastQueue> tmpQueue) {
        if (mCachedIsReceivingBroadcast == VALUE_INVALID) {
            tmpQueue.clear();
            mCachedIsReceivingBroadcast = mService.isReceivingBroadcastLocked(this, tmpQueue)
                    ? VALUE_TRUE : VALUE_FALSE;
            if (mCachedIsReceivingBroadcast == VALUE_TRUE) {
                mCachedSchedGroup = tmpQueue.contains(mService.mFgBroadcastQueue)
                        ? ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
            }
        }
        return mCachedIsReceivingBroadcast == VALUE_TRUE;
    }

    void computeOomAdjFromActivitiesIfNecessary(OomAdjuster.ComputeOomAdjWindowCallback callback,
            int adj, boolean foregroundActivities, int procState, int schedGroup, int appUid,
            int logUid, int processCurTop) {
        if (mCachedAdj != ProcessList.INVALID_ADJ) {
            return;
        }
        callback.initialize(this, adj, foregroundActivities, procState, schedGroup, appUid, logUid,
                processCurTop);
        final int minLayer = getWindowProcessController().computeOomAdjFromActivities(
                ProcessList.VISIBLE_APP_LAYER_MAX, callback);

        mCachedAdj = callback.adj;
        mCachedForegroundActivities = callback.foregroundActivities;
        mCachedProcState = callback.procState;
        mCachedSchedGroup = callback.schedGroup;

        if (mCachedAdj == ProcessList.VISIBLE_APP_ADJ) {
            mCachedAdj += minLayer;
        }
    }

    ErrorDialogController getDialogController() {
        return mDialogController;
    }

    /** A controller to generate error dialogs in {@link ProcessRecord} */
    class ErrorDialogController {
        /** dialogs being displayed due to crash */
        private List<AppErrorDialog> mCrashDialogs;
        /** dialogs being displayed due to app not responding */
        private List<AppNotRespondingDialog> mAnrDialogs;
        /** dialogs displayed due to strict mode violation */
        private List<StrictModeViolationDialog> mViolationDialogs;
        /** current wait for debugger dialog */
        private AppWaitingForDebuggerDialog mWaitDialog;

        boolean hasCrashDialogs() {
            return mCrashDialogs != null;
        }

        boolean hasAnrDialogs() {
            return mAnrDialogs != null;
        }

        boolean hasViolationDialogs() {
            return mViolationDialogs != null;
        }

        boolean hasDebugWaitingDialog() {
            return mWaitDialog != null;
        }

        void clearAllErrorDialogs() {
            clearCrashDialogs();
            clearAnrDialogs();
            clearViolationDialogs();
            clearWaitingDialog();
        }

        void clearCrashDialogs() {
            clearCrashDialogs(true /* needDismiss */);
        }

        void clearCrashDialogs(boolean needDismiss) {
            if (mCrashDialogs == null) {
                return;
            }
            if (needDismiss) {
                forAllDialogs(mCrashDialogs, Dialog::dismiss);
            }
            mCrashDialogs = null;
        }

        void clearAnrDialogs() {
            if (mAnrDialogs == null) {
                return;
            }
            forAllDialogs(mAnrDialogs, Dialog::dismiss);
            mAnrDialogs = null;
        }

        void clearViolationDialogs() {
            if (mViolationDialogs == null) {
                return;
            }
            forAllDialogs(mViolationDialogs, Dialog::dismiss);
            mViolationDialogs = null;
        }

        void clearWaitingDialog() {
            if (mWaitDialog == null) {
                return;
            }
            mWaitDialog.dismiss();
            mWaitDialog = null;
        }

        void forAllDialogs(List<? extends BaseErrorDialog> dialogs, Consumer<BaseErrorDialog> c) {
            for (int i = dialogs.size() - 1; i >= 0; i--) {
                c.accept(dialogs.get(i));
            }
        }

        void showCrashDialogs(AppErrorDialog.Data data) {
            List<Context> contexts = getDisplayContexts(false /* lastUsedOnly */);
            mCrashDialogs = new ArrayList<>();
            for (int i = contexts.size() - 1; i >= 0; i--) {
                final Context c = contexts.get(i);
                mCrashDialogs.add(new AppErrorDialog(c, mService, data));
            }
            mService.mUiHandler.post(() -> {
                List<AppErrorDialog> dialogs;
                synchronized (mService) {
                    dialogs = mCrashDialogs;
                }
                if (dialogs != null) {
                    forAllDialogs(dialogs, Dialog::show);
                }
            });
        }

        void showAnrDialogs(AppNotRespondingDialog.Data data) {
            List<Context> contexts = getDisplayContexts(isSilentAnr() /* lastUsedOnly */);
            mAnrDialogs = new ArrayList<>();
            for (int i = contexts.size() - 1; i >= 0; i--) {
                final Context c = contexts.get(i);
                mAnrDialogs.add(new AppNotRespondingDialog(mService, c, data));
            }
            mService.mUiHandler.post(() -> {
                List<AppNotRespondingDialog> dialogs;
                synchronized (mService) {
                    dialogs = mAnrDialogs;
                }
                if (dialogs != null) {
                    forAllDialogs(dialogs, Dialog::show);
                }
            });
        }

        void showViolationDialogs(AppErrorResult res) {
            List<Context> contexts = getDisplayContexts(false /* lastUsedOnly */);
            mViolationDialogs = new ArrayList<>();
            for (int i = contexts.size() - 1; i >= 0; i--) {
                final Context c = contexts.get(i);
                mViolationDialogs.add(
                        new StrictModeViolationDialog(c, mService, res, ProcessRecord.this));
            }
            mService.mUiHandler.post(() -> {
                List<StrictModeViolationDialog> dialogs;
                synchronized (mService) {
                    dialogs = mViolationDialogs;
                }
                if (dialogs != null) {
                    forAllDialogs(dialogs, Dialog::show);
                }
            });
        }

        void showDebugWaitingDialogs() {
            List<Context> contexts = getDisplayContexts(true /* lastUsedOnly */);
            final Context c = contexts.get(0);
            mWaitDialog = new AppWaitingForDebuggerDialog(mService, c, ProcessRecord.this);

            mService.mUiHandler.post(() -> {
                Dialog dialog;
                synchronized (mService) {
                    dialog = mWaitDialog;
                }
                if (dialog != null) {
                    dialog.show();
                }
            });
        }

        /**
         * Helper function to collect contexts from crashed app located displays
         *
         * @param lastUsedOnly Sets to {@code true} to indicate to only get last used context.
         *                     Sets to {@code false} to collect contexts from crashed app located
         *                     displays.
         *
         * @return display context list
         */
        private List<Context> getDisplayContexts(boolean lastUsedOnly) {
            List<Context> displayContexts = new ArrayList<>();
            if (!lastUsedOnly) {
                mWindowProcessController.getDisplayContextsWithErrorDialogs(displayContexts);
            }
            // If there is no foreground window display, fallback to last used display.
            if (displayContexts.isEmpty() || lastUsedOnly) {
                displayContexts.add(mService.mWmInternal != null
                        ? mService.mWmInternal.getTopFocusedDisplayUiContext()
                        : mService.mUiContext);
            }
            return displayContexts;
        }
    }
}
