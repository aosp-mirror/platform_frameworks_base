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

import static android.app.ActivityManagerInternal.OOM_ADJ_REASON_ACTIVITY;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.am.OomAdjusterModernImpl.ProcessRecordNode.NUM_NODE_TYPE;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.ApplicationExitInfo.Reason;
import android.app.ApplicationExitInfo.SubReason;
import android.app.BackgroundStartPrivileges;
import android.app.IApplicationThread;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ProcessInfo;
import android.content.pm.VersionedPackage;
import android.content.res.CompatibilityInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.server.ServerProtoEnums;
import android.system.OsConstants;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.CompositeRWLock;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.procstats.ProcessState;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.Zygote;
import com.android.server.FgThread;
import com.android.server.am.OomAdjusterModernImpl.ProcessRecordNode;
import com.android.server.wm.WindowProcessController;
import com.android.server.wm.WindowProcessListener;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Full information about a particular process that
 * is currently running.
 */
class ProcessRecord implements WindowProcessListener {
    static final String TAG = TAG_WITH_CLASS_NAME ? "ProcessRecord" : TAG_AM;

    final ActivityManagerService mService; // where we came from
    private final ActivityManagerGlobalLock mProcLock;

    // =========================================================
    // Basic info of the process, immutable or semi-immutable over
    // the lifecycle of the process
    // =========================================================
    volatile ApplicationInfo info; // all about the first app in the process
    final ProcessInfo processInfo; // if non-null, process-specific manifest info
    final boolean isolated;     // true if this is a special isolated process
    public final boolean isSdkSandbox; // true if this is an SDK sandbox process
    final boolean appZygote;    // true if this is forked from the app zygote
    final int uid;              // uid of process; may be different from 'info' if isolated
    final int userId;           // user of process.
    final String processName;   // name of the process
    final String sdkSandboxClientAppPackage; // if this is an sdk sandbox process, name of the
                                             // app package for which it is running
    final String sdkSandboxClientAppVolumeUuid; // uuid of the app for which the sandbox is running

    /**
     * Overall state of process's uid.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private UidRecord mUidRecord;

    /**
     * List of packages running in the process.
     */
    private final PackageList mPkgList = new PackageList(this);

    /**
     * Additional packages we have a dependency on.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private ArraySet<String> mPkgDeps;

    /**
     * The process of this application; 0 if none.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    int mPid;

    /**
     * The process ID which will be set when we're killing this process.
     */
    @GuardedBy("mService")
    private int mDyingPid;

    /**
     * The gids this process was launched with.
     */
    @GuardedBy("mService")
    private int[] mGids;

    /**
     * The ABI this process was launched with.
     */
    @GuardedBy("mService")
    private String mRequiredAbi;

    /**
     * The instruction set this process was launched with.
     */
    @GuardedBy("mService")
    private String mInstructionSet;

    /**
     * The actual proc...  may be null only if 'persistent' is true
     * (in which case we are in the process of launching the app).
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private IApplicationThread mThread;

    /**
     * Instance of {@link #mThread} that will always meet the {@code oneway}
     * contract, possibly by using {@link SameProcessApplicationThread}.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private IApplicationThread mOnewayThread;

    /**
     * Always keep this application running?
     */
    private volatile boolean mPersistent;

    /**
     * Caching of toShortString() result.
     * <p>Note: No lock here, it doesn't matter in case of race condition</p>
     */
    private String mShortStringName;

    /**
     * Caching of toString() result.
     * <p>Note: No lock here, it doesn't matter in case of race condition</p>
     */
    private String mStringName;

    /**
     * Process start is pending.
     */
    @GuardedBy("mService")
    private boolean mPendingStart;

    /**
     * Process finish attach application is pending.
     */
    @GuardedBy("mService")
    private boolean mPendingFinishAttach;

    /**
     * Seq no. Indicating the latest process start associated with this process record.
     */
    @GuardedBy("mService")
    private long mStartSeq;

    /**
     * Params used in starting this process.
     */
    private volatile HostingRecord mHostingRecord;

    /**
     * Selinux info of this process.
     */
    private volatile String mSeInfo;

    /**
     * When the process is started. (before zygote fork)
     */
    private volatile long mStartUptime;

    /**
     * When the process is started. (before zygote fork)
     */
    private volatile long mStartElapsedTime;

    /**
     * When the process was sent the bindApplication request
     */
    private volatile long mBindApplicationTime;

    /**
     * This will be same as {@link #uid} usually except for some apps used during factory testing.
     */
    private volatile int mStartUid;

    /**
     * Indicates how the external storage was mounted for this process.
     */
    private volatile int mMountMode;

    /**
     * True if Android/obb and Android/data need to be bind mount.
     */
    private volatile boolean mBindMountPending;

    /**
     * True when proc was started in user unlocked state.
     */
    @GuardedBy("mProcLock")
    private boolean mUnlocked;

    /**
     * TID for RenderThread.
     */
    @GuardedBy("mProcLock")
    private int mRenderThreadTid;

    /**
     * Last used compatibility mode.
     */
    @GuardedBy("mService")
    private CompatibilityInfo mCompat;

    /**
     * Set of disabled compat changes for the process (all others are enabled).
     */
    @GuardedBy("mService")
    private long[] mDisabledCompatChanges;

    /**
     * Set of compat changes for the process that are intended to be logged to logcat.
     */
    @GuardedBy("mService")
    private long[] mLoggableCompatChanges;

    /**
     * Who is watching for the death.
     */
    @GuardedBy("mService")
    private IBinder.DeathRecipient mDeathRecipient;

    /**
     * Set to currently active instrumentation running in process.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private ActiveInstrumentation mInstr;

    /**
     * True when proc has been killed by activity manager, not for RAM.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mKilledByAm;

    /**
     * True once we know the process has been killed.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private boolean mKilled;

    /**
     * The timestamp in uptime when this process was killed.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private long mKillTime;

    /**
     * Process is waiting to be killed when in the bg, and reason.
     */
    @GuardedBy("mService")
    private String mWaitingToKill;

    /**
     * Whether this process should be killed and removed from process list.
     * It is set when the package is force-stopped or the process has crashed too many times.
     */
    private volatile boolean mRemoved;

    /**
     * Was app launched for debugging?
     */
    @GuardedBy("mService")
    private boolean mDebugging;

    /**
     * Has process show wait for debugger dialog?
     */
    @GuardedBy("mProcLock")
    private boolean mWaitedForDebugger;

    /**
     * For managing the LRU list.
     */
    @CompositeRWLock({"mService", "mProcLock"})
    private long mLastActivityTime;

    /**
     * Set to true when process was launched with a wrapper attached.
     */
    @GuardedBy("mService")
    private boolean mUsingWrapper;

    /**
     * Sequence id for identifying LRU update cycles.
     */
    @GuardedBy("mService")
    private int mLruSeq;

    /**
     * Class to run on start if this is a special isolated process.
     */
    @GuardedBy("mService")
    private String mIsolatedEntryPoint;

    /**
     * Arguments to pass to isolatedEntryPoint's main().
     */
    @GuardedBy("mService")
    private String[] mIsolatedEntryPointArgs;

    /**
     * Process is currently hosting a backup agent for backup or restore.
     */
    @GuardedBy("mService")
    private boolean mInFullBackup;

    /**
     * A set of tokens that currently contribute to this process being temporarily allowed
     * to start certain components (eg. activities or foreground services) even if it's not
     * in the foreground.
     */
    @GuardedBy("mBackgroundStartPrivileges")
    private final ArrayMap<Binder, BackgroundStartPrivileges> mBackgroundStartPrivileges =
            new ArrayMap<>();

    /**
     * The merged BackgroundStartPrivileges based on what's in {@link #mBackgroundStartPrivileges}.
     * This is lazily generated using {@link #getBackgroundStartPrivileges()}.
     */
    @Nullable
    @GuardedBy("mBackgroundStartPrivileges")
    private BackgroundStartPrivileges mBackgroundStartPrivilegesMerged =
            BackgroundStartPrivileges.NONE;

    /**
     * Controller for driving the process state on the window manager side.
     */
    private final WindowProcessController mWindowProcessController;

    /**
     * Profiling info of the process, such as PSS, cpu, etc.
     */
    final ProcessProfileRecord mProfile;

    /**
     * All about the services in this process.
     */
    final ProcessServiceRecord mServices;

    /**
     * All about the providers in this process.
     */
    final ProcessProviderRecord mProviders;

    /**
     * All about the receivers in this process.
     */
    final ProcessReceiverRecord mReceivers;

    /**
     * All about the error state(crash, ANR) in this process.
     */
    final ProcessErrorStateRecord mErrorState;

    /**
     * All about the process state info (proc state, oom adj score) in this process.
     */
    ProcessStateRecord mState;

    /**
     * All about the state info of the optimizer when the process is cached.
     */
    final ProcessCachedOptimizerRecord mOptRecord;

    /**
     * The preceding instance of the process, which would exist when the previous process is killed
     * but not fully dead yet; in this case, the new instance of the process should be held until
     * this preceding instance is fully dead.
     */
    volatile ProcessRecord mPredecessor;

    /**
     * The succeeding instance of the process, which is going to be started after this process
     * is killed successfully.
     */
    volatile ProcessRecord mSuccessor;

    /**
     * The routine to start its successor process.
     *
     * <p>Note: It should be accessed from process start thread only.</p>
     */
    Runnable mSuccessorStartRunnable;

    /**
     * Whether or not the process group of this process has been created.
     */
    volatile boolean mProcessGroupCreated;

    /**
     * Whether or not we should skip the process group creation.
     */
    volatile boolean mSkipProcessGroupCreation;

    final ProcessRecordNode[] mLinkedNodes = new ProcessRecordNode[NUM_NODE_TYPE];

    /** Whether the app was launched from a stopped state and is being unstopped. */
    @GuardedBy("mService")
    volatile boolean mWasForceStopped;

    void setStartParams(int startUid, HostingRecord hostingRecord, String seInfo,
            long startUptime, long startElapsedTime) {
        this.mStartUid = startUid;
        this.mHostingRecord = hostingRecord;
        this.mSeInfo = seInfo;
        this.mStartUptime = startUptime;
        this.mStartElapsedTime = startElapsedTime;
    }

    @GuardedBy({"mService", "mProcLock"})
    void dump(PrintWriter pw, String prefix) {
        final long nowUptime = SystemClock.uptimeMillis();
        final long nowElapsedTime = SystemClock.elapsedRealtime();

        pw.print(prefix); pw.print("user #"); pw.print(userId);
                pw.print(" uid="); pw.print(info.uid);
        if (uid != info.uid) {
            pw.print(" ISOLATED uid="); pw.print(uid);
        }
        pw.print(" gids={");
        if (mGids != null) {
            for (int gi = 0; gi < mGids.length; gi++) {
                if (gi != 0) pw.print(", ");
                pw.print(mGids[gi]);

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
        pw.print(" instructionSet="); pw.println(mInstructionSet);
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
        mPkgList.dump(pw, prefix);
        if (mPkgDeps != null) {
            pw.print(prefix); pw.print("packageDependencies={");
            for (int i = 0; i < mPkgDeps.size(); i++) {
                if (i > 0) pw.print(", ");
                pw.print(mPkgDeps.valueAt(i));
            }
            pw.println("}");
        }
        pw.print(prefix); pw.print("compat="); pw.println(mCompat);
        if (mInstr != null) {
            pw.print(prefix); pw.print("mInstr="); pw.println(mInstr);
        }
        pw.print(prefix); pw.print("thread="); pw.println(mThread);
        pw.print(prefix); pw.print("pid="); pw.println(mPid);
        pw.print(prefix); pw.print("lastActivityTime=");
        TimeUtils.formatDuration(mLastActivityTime, nowUptime, pw);
        pw.print(prefix); pw.print("startUpTime=");
        TimeUtils.formatDuration(mStartUptime, nowUptime, pw);
        pw.print(prefix); pw.print("startElapsedTime=");
        TimeUtils.formatDuration(mStartElapsedTime, nowElapsedTime, pw);
        pw.println();
        if (mPersistent || mRemoved) {
            pw.print(prefix); pw.print("persistent="); pw.print(mPersistent);
            pw.print(" removed="); pw.println(mRemoved);
        }
        if (mDebugging) {
            pw.print(prefix); pw.print("mDebugging="); pw.println(mDebugging);
        }
        if (mPendingStart) {
            pw.print(prefix); pw.print("pendingStart="); pw.println(mPendingStart);
        }
        pw.print(prefix); pw.print("startSeq="); pw.println(mStartSeq);
        pw.print(prefix); pw.print("mountMode="); pw.println(
                DebugUtils.valueToString(Zygote.class, "MOUNT_EXTERNAL_", mMountMode));
        if (mKilled || mKilledByAm || mWaitingToKill != null) {
            pw.print(prefix); pw.print("killed="); pw.print(mKilled);
            pw.print(" killedByAm="); pw.print(mKilledByAm);
            pw.print(" waitingToKill="); pw.println(mWaitingToKill);
        }
        if (mIsolatedEntryPoint != null || mIsolatedEntryPointArgs != null) {
            pw.print(prefix); pw.print("isolatedEntryPoint="); pw.println(mIsolatedEntryPoint);
            pw.print(prefix); pw.print("isolatedEntryPointArgs=");
            pw.println(Arrays.toString(mIsolatedEntryPointArgs));
        }
        if (mState.getSetProcState() > ActivityManager.PROCESS_STATE_SERVICE) {
            mProfile.dumpCputime(pw, prefix);
        }
        mProfile.dumpPss(pw, prefix, nowUptime);
        mState.dump(pw, prefix, nowUptime);
        mErrorState.dump(pw, prefix, nowUptime);
        mServices.dump(pw, prefix, nowUptime);
        mProviders.dump(pw, prefix, nowUptime);
        mReceivers.dump(pw, prefix, nowUptime);
        mOptRecord.dump(pw, prefix, nowUptime);
        mWindowProcessController.dump(pw, prefix);
    }

    ProcessRecord(ActivityManagerService _service, ApplicationInfo _info, String _processName,
            int _uid) {
        this(_service, _info, _processName, _uid, null, -1, null);
    }

    ProcessRecord(ActivityManagerService _service, ApplicationInfo _info, String _processName,
            int _uid, String _sdkSandboxClientAppPackage, int _definingUid,
            String _definingProcessName) {
        mService = _service;
        mProcLock = _service.mProcLock;
        info = _info;
        ProcessInfo procInfo = null;
        if (_service.mPackageManagerInt != null) {
            if (_definingUid > 0) {
                ArrayMap<String, ProcessInfo> processes =
                        _service.mPackageManagerInt.getProcessesForUid(_definingUid);
                if (processes != null) procInfo = processes.get(_definingProcessName);
            } else {
                ArrayMap<String, ProcessInfo> processes =
                        _service.mPackageManagerInt.getProcessesForUid(_uid);
                if (processes != null) procInfo = processes.get(_processName);
            }
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
        processInfo = procInfo;
        isolated = Process.isIsolated(_uid);
        isSdkSandbox = Process.isSdkSandboxUid(_uid);
        appZygote = (UserHandle.getAppId(_uid) >= Process.FIRST_APP_ZYGOTE_ISOLATED_UID
                && UserHandle.getAppId(_uid) <= Process.LAST_APP_ZYGOTE_ISOLATED_UID);
        uid = _uid;
        userId = UserHandle.getUserId(_uid);
        processName = _processName;
        sdkSandboxClientAppPackage = _sdkSandboxClientAppPackage;
        if (isSdkSandbox) {
            final ApplicationInfo clientInfo = getClientInfoForSdkSandbox();
            sdkSandboxClientAppVolumeUuid = clientInfo != null
                    ? clientInfo.volumeUuid : null;
        } else {
            sdkSandboxClientAppVolumeUuid = null;
        }
        mPersistent = false;
        mRemoved = false;
        mProfile = new ProcessProfileRecord(this);
        mServices = new ProcessServiceRecord(this);
        mProviders = new ProcessProviderRecord(this);
        mReceivers = new ProcessReceiverRecord(this);
        mErrorState = new ProcessErrorStateRecord(this);
        mState = new ProcessStateRecord(this);
        mOptRecord = new ProcessCachedOptimizerRecord(this);
        final long now = SystemClock.uptimeMillis();
        mProfile.init(now);
        mOptRecord.init(now);
        mState.init(now);
        mWindowProcessController = new WindowProcessController(
                mService.mActivityTaskManager, info, processName, uid, userId, this, this);
        mPkgList.put(_info.packageName, new ProcessStats.ProcessStateHolder(_info.longVersionCode));
        updateProcessRecordNodes(this);
    }

    /**
     * Helper function to let test cases update the pointers.
     */
    @VisibleForTesting
    static void updateProcessRecordNodes(@NonNull ProcessRecord app) {
        if (app.mService.mConstants.ENABLE_NEW_OOMADJ) {
            for (int i = 0; i < app.mLinkedNodes.length; i++) {
                app.mLinkedNodes[i] = new ProcessRecordNode(app);
            }
        }
    }

    /**
     * Perform cleanups if the process record is going to be discarded in an early
     * stage of the process lifecycle, specifically when the process has not even
     * attached itself to the system_server.
     */
    @GuardedBy("mService")
    void doEarlyCleanupIfNecessaryLocked() {
        if (getThread() == null) {
            // It's not even attached, make sure we unlink its process nodes.
            mService.mOomAdjuster.onProcessEndLocked(this);
        } else {
            // Let the binder died callback handle the cleanup.
        }
    }

    void resetCrashingOnRestart() {
        mErrorState.setCrashing(false);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    UidRecord getUidRecord() {
        return mUidRecord;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setUidRecord(UidRecord uidRecord) {
        mUidRecord = uidRecord;
    }

    PackageList getPkgList() {
        return mPkgList;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ArraySet<String> getPkgDeps() {
        return mPkgDeps;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setPkgDeps(ArraySet<String> pkgDeps) {
        mPkgDeps = pkgDeps;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getPid() {
        return mPid;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setPid(int pid) {
        // If the pid is changing and not the first time pid is being assigned, clear stopped state
        // So if the process record is re-used for a different pid, it wouldn't keep the state.
        if (pid != mPid && mPid != 0) {
            setWasForceStopped(false);
        }
        mPid = pid;
        mWindowProcessController.setPid(pid);
        mShortStringName = null;
        mStringName = null;
        synchronized (mProfile.mProfilerLock) {
            mProfile.setPid(pid);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    int getSetAdj() {
        return mState.getSetAdj();
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    IApplicationThread getThread() {
        return mThread;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    IApplicationThread getOnewayThread() {
        return mOnewayThread;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getCurProcState() {
        return mState.getCurProcState();
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetProcState() {
        return mState.getSetProcState();
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    int getSetCapability() {
        return mState.getSetCapability();
    }

    @GuardedBy({"mService", "mProcLock"})
    public void makeActive(IApplicationThread thread, ProcessStatsService tracker) {
        mProfile.onProcessActive(thread, tracker);
        mThread = thread;
        if (mPid == Process.myPid()) {
            mOnewayThread = new SameProcessApplicationThread(thread, FgThread.getHandler());
        } else {
            mOnewayThread = thread;
        }
        mWindowProcessController.setThread(thread);
        if (mWindowProcessController.useFifoUiScheduling()) {
            mService.mSpecifiedFifoProcesses.add(this);
        }
    }

    @GuardedBy({"mService", "mProcLock"})
    public void makeInactive(ProcessStatsService tracker) {
        mThread = null;
        mOnewayThread = null;
        mWindowProcessController.setThread(null);
        if (mWindowProcessController.useFifoUiScheduling()) {
            mService.mSpecifiedFifoProcesses.remove(this);
        }
        mProfile.onProcessInactive(tracker);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean useFifoUiScheduling() {
        return mService.mUseFifoUiScheduling
                || (mService.mAllowSpecifiedFifoScheduling
                        && mWindowProcessController.useFifoUiScheduling());
    }

    @GuardedBy("mService")
    int getDyingPid() {
        return mDyingPid;
    }

    @GuardedBy("mService")
    void setDyingPid(int dyingPid) {
        mDyingPid = dyingPid;
    }

    @GuardedBy("mService")
    int[] getGids() {
        return mGids;
    }

    @GuardedBy("mService")
    void setGids(int[] gids) {
        mGids = gids;
    }

    @GuardedBy("mService")
    String getRequiredAbi() {
        return mRequiredAbi;
    }

    @GuardedBy("mService")
    void setRequiredAbi(String requiredAbi) {
        mRequiredAbi = requiredAbi;
        mWindowProcessController.setRequiredAbi(requiredAbi);
    }

    @GuardedBy("mService")
    String getInstructionSet() {
        return mInstructionSet;
    }

    @GuardedBy("mService")
    void setInstructionSet(String instructionSet) {
        mInstructionSet = instructionSet;
    }

    void setPersistent(boolean persistent) {
        mPersistent = persistent;
        mWindowProcessController.setPersistent(persistent);
    }

    boolean isPersistent() {
        return mPersistent;
    }

    @GuardedBy("mService")
    boolean isPendingStart() {
        return mPendingStart;
    }

    @GuardedBy("mService")
    void setPendingStart(boolean pendingStart) {
        mPendingStart = pendingStart;
    }

    @GuardedBy("mService")
    void setPendingFinishAttach(boolean pendingFinishAttach) {
        mPendingFinishAttach = pendingFinishAttach;
    }

    @GuardedBy("mService")
    boolean isPendingFinishAttach() {
        return mPendingFinishAttach;
    }

    @GuardedBy("mService")
    boolean isThreadReady() {
        return mThread != null && !mPendingFinishAttach;
    }

    @GuardedBy("mService")
    long getStartSeq() {
        return mStartSeq;
    }

    @GuardedBy("mService")
    void setStartSeq(long startSeq) {
        mStartSeq = startSeq;
    }

    HostingRecord getHostingRecord() {
        return mHostingRecord;
    }

    void setHostingRecord(HostingRecord hostingRecord) {
        mHostingRecord = hostingRecord;
    }

    String getSeInfo() {
        return mSeInfo;
    }

    void setSeInfo(String seInfo) {
        mSeInfo = seInfo;
    }

    long getStartUptime() {
        return mStartUptime;
    }

    /**
     * Same as {@link #getStartUptime()}.
     * @deprecated use {@link #getStartUptime()} instead for clarity.
     */
    @Deprecated
    long getStartTime() {
        return mStartUptime;
    }

    long getStartElapsedTime() {
        return mStartElapsedTime;
    }

    long getBindApplicationTime() {
        return mBindApplicationTime;
    }

    void setBindApplicationTime(long bindApplicationTime) {
        mBindApplicationTime = bindApplicationTime;
    }

    int getStartUid() {
        return mStartUid;
    }

    void setStartUid(int startUid) {
        mStartUid = startUid;
    }

    int getMountMode() {
        return mMountMode;
    }

    void setMountMode(int mountMode) {
        mMountMode = mountMode;
    }

    boolean isBindMountPending() {
        return mBindMountPending;
    }

    void setBindMountPending(boolean bindMountPending) {
        mBindMountPending = bindMountPending;
    }

    @GuardedBy("mProcLock")
    boolean isUnlocked() {
        return mUnlocked;
    }

    @GuardedBy("mProcLock")
    void setUnlocked(boolean unlocked) {
        mUnlocked = unlocked;
    }

    @GuardedBy("mProcLock")
    int getRenderThreadTid() {
        return mRenderThreadTid;
    }

    @GuardedBy("mProcLock")
    void setRenderThreadTid(int renderThreadTid) {
        mRenderThreadTid = renderThreadTid;
    }

    @GuardedBy("mService")
    CompatibilityInfo getCompat() {
        return mCompat;
    }

    @GuardedBy("mService")
    void setCompat(CompatibilityInfo compat) {
        mCompat = compat;
    }

    @GuardedBy("mService")
    long[] getDisabledCompatChanges() {
        return mDisabledCompatChanges;
    }

    @GuardedBy("mService")
    long[] getLoggableCompatChanges() {
        return mLoggableCompatChanges;
    }

    @GuardedBy("mService")
    void setDisabledCompatChanges(long[] disabledCompatChanges) {
        mDisabledCompatChanges = disabledCompatChanges;
    }

    @GuardedBy("mService")
    void setLoggableCompatChanges(long[] loggableCompatChanges) {
        mLoggableCompatChanges = loggableCompatChanges;
    }

    @GuardedBy("mService")
    void unlinkDeathRecipient() {
        if (mDeathRecipient != null && mThread != null) {
            mThread.asBinder().unlinkToDeath(mDeathRecipient, 0);
        }
        mDeathRecipient = null;
    }

    @GuardedBy("mService")
    void setDeathRecipient(IBinder.DeathRecipient deathRecipient) {
        mDeathRecipient = deathRecipient;
    }

    @GuardedBy("mService")
    IBinder.DeathRecipient getDeathRecipient() {
        return mDeathRecipient;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setActiveInstrumentation(ActiveInstrumentation instr) {
        mInstr = instr;
        boolean isInstrumenting = instr != null;
        mWindowProcessController.setInstrumenting(
                isInstrumenting,
                isInstrumenting ? instr.mSourceUid : -1,
                isInstrumenting && instr.mHasBackgroundActivityStartsPermission);
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    ActiveInstrumentation getActiveInstrumentation() {
        return mInstr;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isKilledByAm() {
        return mKilledByAm;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setKilledByAm(boolean killedByAm) {
        mKilledByAm = killedByAm;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    boolean isKilled() {
        return mKilled;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setKilled(boolean killed) {
        mKilled = killed;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    long getKillTime() {
        return mKillTime;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setKillTime(long killTime) {
        mKillTime = killTime;
    }

    @GuardedBy("mService")
    String getWaitingToKill() {
        return mWaitingToKill;
    }

    @GuardedBy("mService")
    void setWaitingToKill(String waitingToKill) {
        mWaitingToKill = waitingToKill;
    }

    @Override
    public boolean isRemoved() {
        return mRemoved;
    }

    void setRemoved(boolean removed) {
        mRemoved = removed;
    }

    @GuardedBy("mService")
    boolean isDebugging() {
        return mDebugging;
    }

    @Nullable
    public ApplicationInfo getClientInfoForSdkSandbox() {
        if (!isSdkSandbox || sdkSandboxClientAppPackage == null) {
            throw new IllegalStateException(
                    "getClientInfoForSdkSandbox called for non-sandbox process"
            );
        }
        PackageManagerInternal pm = mService.getPackageManagerInternal();
        return pm.getApplicationInfo(
                sdkSandboxClientAppPackage, /* flags */0, Process.SYSTEM_UID, userId);
    }

    public boolean isDebuggable() {
        if ((info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return true;
        }
        if (isSdkSandbox) {
            ApplicationInfo clientInfo = getClientInfoForSdkSandbox();
            return clientInfo != null && (clientInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        }
        return false;
    }

    @GuardedBy("mService")
    void setDebugging(boolean debugging) {
        mDebugging = debugging;
        mWindowProcessController.setDebugging(debugging);
    }

    @GuardedBy("mProcLock")
    boolean hasWaitedForDebugger() {
        return mWaitedForDebugger;
    }

    @GuardedBy("mProcLock")
    void setWaitedForDebugger(boolean waitedForDebugger) {
        mWaitedForDebugger = waitedForDebugger;
    }

    @GuardedBy(anyOf = {"mService", "mProcLock"})
    long getLastActivityTime() {
        return mLastActivityTime;
    }

    @GuardedBy({"mService", "mProcLock"})
    void setLastActivityTime(long lastActivityTime) {
        mLastActivityTime = lastActivityTime;
    }

    @GuardedBy("mService")
    boolean isUsingWrapper() {
        return mUsingWrapper;
    }

    @GuardedBy("mService")
    void setUsingWrapper(boolean usingWrapper) {
        mUsingWrapper = usingWrapper;
        mWindowProcessController.setUsingWrapper(usingWrapper);
    }

    @GuardedBy("mService")
    int getLruSeq() {
        return mLruSeq;
    }

    @GuardedBy("mService")
    void setLruSeq(int lruSeq) {
        mLruSeq = lruSeq;
    }

    @GuardedBy("mService")
    String getIsolatedEntryPoint() {
        return mIsolatedEntryPoint;
    }

    @GuardedBy("mService")
    void setIsolatedEntryPoint(String isolatedEntryPoint) {
        mIsolatedEntryPoint = isolatedEntryPoint;
    }

    @GuardedBy("mService")
    String[] getIsolatedEntryPointArgs() {
        return mIsolatedEntryPointArgs;
    }

    @GuardedBy("mService")
    void setIsolatedEntryPointArgs(String[] isolatedEntryPointArgs) {
        mIsolatedEntryPointArgs = isolatedEntryPointArgs;
    }

    @GuardedBy("mService")
    boolean isInFullBackup() {
        return mInFullBackup;
    }

    @GuardedBy("mService")
    void setInFullBackup(boolean inFullBackup) {
        mInFullBackup = inFullBackup;
    }

    @Override
    @GuardedBy("mService")
    public boolean isCached() {
        return mState.isCached();
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

    @GuardedBy("mService")
    public ApplicationInfo getApplicationInfo() {
        return info;
    }

    @GuardedBy({"mService", "mProcLock"})
    boolean onCleanupApplicationRecordLSP(ProcessStatsService processStats, boolean allowRestart,
            boolean unlinkDeath) {
        mErrorState.onCleanupApplicationRecordLSP();

        resetPackageList(processStats);
        if (unlinkDeath) {
            unlinkDeathRecipient();
        }
        makeInactive(processStats);
        setWaitingToKill(null);

        mState.onCleanupApplicationRecordLSP();
        mServices.onCleanupApplicationRecordLocked();
        mReceivers.onCleanupApplicationRecordLocked();
        mService.mOomAdjuster.onProcessEndLocked(this);

        return mProviders.onCleanupApplicationRecordLocked(allowRestart);
    }

    /**
     * This method returns true if any of the activities within the process record are interesting
     * to the user. See HistoryRecord.isInterestingToUserLocked()
     */
    public boolean isInterestingToUserLocked() {
        if (mWindowProcessController.isInterestingToUser()) {
            return true;
        }

        return mServices.hasForegroundServices();
    }

    /**
     * Let an app process throw an exception on a binder thread, which typically crashes the
     * process, unless it has an unhandled exception handler.
     *
     * See {@link ActivityThread#throwRemoteServiceException}.
     *
     * @param message exception message
     * @param exceptionTypeId ID defined in {@link android.app.RemoteServiceException} or one
     *                        of its subclasses.
     */
    @GuardedBy("mService")
    void scheduleCrashLocked(String message, int exceptionTypeId, @Nullable Bundle extras) {
        // Checking killedbyAm should keep it from showing the crash dialog if the process
        // was already dead for a good / normal reason.
        if (!mKilledByAm) {
            if (mThread != null) {
                if (mPid == Process.myPid()) {
                    Slog.w(TAG, "scheduleCrash: trying to crash system process!");
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mThread.scheduleCrash(message, exceptionTypeId, extras);
                } catch (RemoteException e) {
                    // If it's already dead our work is done. If it's wedged just kill it.
                    // We won't get the crash dialog or the error reporting.
                    killLocked("scheduleCrash for '" + message + "' failed",
                            ApplicationExitInfo.REASON_CRASH, true);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    public long getRss(int pid) {
        long[] rss = Process.getRss(pid);
        return (rss != null && rss.length > 0) ? rss[0] : 0;
    }

    @GuardedBy("mService")
    void killLocked(String reason, @Reason int reasonCode, boolean noisy) {
        killLocked(reason, reasonCode, ApplicationExitInfo.SUBREASON_UNKNOWN, noisy, true);
    }

    @GuardedBy("mService")
    void killLocked(String reason, @Reason int reasonCode, @SubReason int subReason,
            boolean noisy) {
        killLocked(reason, reason, reasonCode, subReason, noisy, true);
    }

    @GuardedBy("mService")
    void killLocked(String reason, String description, @Reason int reasonCode,
            @SubReason int subReason, boolean noisy) {
        killLocked(reason, description, reasonCode, subReason, noisy, true);
    }

    @GuardedBy("mService")
    void killLocked(String reason, @Reason int reasonCode, @SubReason int subReason,
            boolean noisy, boolean asyncKPG) {
        killLocked(reason, reason, reasonCode, subReason, noisy, asyncKPG);
    }

    @GuardedBy("mService")
    void killLocked(String reason, String description, @Reason int reasonCode,
            @SubReason int subReason, boolean noisy, boolean asyncKPG) {
        if (!mKilledByAm) {
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "kill");
            if (reasonCode == ApplicationExitInfo.REASON_ANR
                    && mErrorState.getAnrAnnotation() != null) {
                description = description + ": " + mErrorState.getAnrAnnotation();
            }
            if (mService != null && (noisy || info.uid == mService.mCurOomAdjUid)) {
                mService.reportUidInfoMessageLocked(TAG,
                        "Killing " + toShortString() + " (adj " + mState.getSetAdj()
                        + "): " + reason, info.uid);
            }
            // Since the process is getting killed, reset the freezable related state.
            mOptRecord.setPendingFreeze(false);
            mOptRecord.setFrozen(false);
            if (mPid > 0) {
                mService.mProcessList.noteAppKill(this, reasonCode, subReason, description);
                EventLog.writeEvent(EventLogTags.AM_KILL,
                        userId, mPid, processName, mState.getSetAdj(), reason, getRss(mPid));
                Process.killProcessQuiet(mPid);
                killProcessGroupIfNecessaryLocked(asyncKPG);
            } else {
                mPendingStart = false;
            }
            if (!mPersistent) {
                synchronized (mProcLock) {
                    mKilled = true;
                    mKilledByAm = true;
                    mKillTime = SystemClock.uptimeMillis();
                }
            }
            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
        }
    }

    @GuardedBy("mService")
    void killProcessGroupIfNecessaryLocked(boolean async) {
        final boolean killProcessGroup;
        if (mHostingRecord != null
                && (mHostingRecord.usesWebviewZygote() || mHostingRecord.usesAppZygote())) {
            synchronized (ProcessRecord.this) {
                killProcessGroup = mProcessGroupCreated;
                if (!killProcessGroup) {
                    // The process group hasn't been created, request to skip it.
                    mSkipProcessGroupCreation = true;
                }
            }
        } else {
            killProcessGroup = true;
        }
        if (killProcessGroup) {
            if (!async) {
                Process.sendSignalToProcessGroup(uid, mPid, OsConstants.SIGKILL);
            }
            ProcessList.killProcessGroup(uid, mPid);
        }
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        dumpDebug(proto, fieldId, -1);
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId, int lruIndex) {
        long token = proto.start(fieldId);
        proto.write(ProcessRecordProto.PID, mPid);
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
        final String shortStringName = mShortStringName;
        if (shortStringName != null) {
            return shortStringName;
        }
        StringBuilder sb = new StringBuilder(128);
        toShortString(sb);
        return mShortStringName = sb.toString();
    }

    void toShortString(StringBuilder sb) {
        sb.append(mPid);
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
        final String stringName = mStringName;
        if (stringName != null) {
            return stringName;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("ProcessRecord{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        toShortString(sb);
        sb.append('}');
        return mStringName = sb.toString();
    }

    /*
     *  Return true if package has been added false if not
     */
    public boolean addPackage(String pkg, long versionCode, ProcessStatsService tracker) {
        synchronized (tracker.mLock) {
            synchronized (mPkgList) {
                if (!mPkgList.containsKey(pkg)) {
                    ProcessStats.ProcessStateHolder holder = new ProcessStats.ProcessStateHolder(
                            versionCode);
                    final ProcessState baseProcessTracker = mProfile.getBaseProcessTracker();
                    if (baseProcessTracker != null) {
                        tracker.updateProcessStateHolderLocked(holder, pkg, info.uid, versionCode,
                                processName);
                        mPkgList.put(pkg, holder);
                        if (holder.state != baseProcessTracker) {
                            holder.state.makeActive();
                        }
                    } else {
                        mPkgList.put(pkg, holder);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    void onProcessFrozen() {
        mProfile.onProcessFrozen();
    }

    void onProcessUnfrozen() {
        mProfile.onProcessUnfrozen();
        mServices.onProcessUnfrozen();
    }

    void onProcessFrozenCancelled() {
        mServices.onProcessFrozenCancelled();
    }

    /*
     *  Delete all packages from list except the package indicated in info
     */
    public void resetPackageList(ProcessStatsService tracker) {
        synchronized (tracker.mLock) {
            final ProcessState baseProcessTracker = mProfile.getBaseProcessTracker();
            synchronized (mPkgList) {
                final int numOfPkgs = mPkgList.size();
                if (baseProcessTracker != null) {
                    long now = SystemClock.uptimeMillis();
                    baseProcessTracker.setState(ProcessStats.STATE_NOTHING,
                            tracker.getMemFactorLocked(), now, mPkgList.getPackageListLocked());
                    if (numOfPkgs != 1) {
                        mPkgList.forEachPackageProcessStats(holder -> {
                            if (holder.state != null && holder.state != baseProcessTracker) {
                                holder.state.makeInactive();
                            }
                        });
                        mPkgList.clear();
                        ProcessStats.ProcessStateHolder holder =
                                new ProcessStats.ProcessStateHolder(info.longVersionCode);
                        tracker.updateProcessStateHolderLocked(holder, info.packageName, info.uid,
                                info.longVersionCode, processName);
                        mPkgList.put(info.packageName, holder);
                        if (holder.state != baseProcessTracker) {
                            holder.state.makeActive();
                        }
                    }
                } else if (numOfPkgs != 1) {
                    mPkgList.clear();
                    mPkgList.put(info.packageName,
                            new ProcessStats.ProcessStateHolder(info.longVersionCode));
                }
            }
        }
    }

    String[] getPackageList() {
        return mPkgList.getPackageList();
    }

    List<VersionedPackage> getPackageListWithVersionCode() {
        return mPkgList.getPackageListWithVersionCode();
    }

    WindowProcessController getWindowProcessController() {
        return mWindowProcessController;
    }

    /**
     * Allows background activity starts using token {@param entity}. Optionally, you can provide
     * {@param originatingToken} if you have one such originating token, this is useful for tracing
     * back the grant in the case of the notification token.
     */
    void addOrUpdateBackgroundStartPrivileges(@NonNull Binder entity,
            @NonNull BackgroundStartPrivileges backgroundStartPrivileges) {
        requireNonNull(entity, "entity");
        requireNonNull(backgroundStartPrivileges, "backgroundStartPrivileges");
        checkArgument(backgroundStartPrivileges.allowsAny(),
                "backgroundStartPrivileges does not allow anything");
        mWindowProcessController.addOrUpdateBackgroundStartPrivileges(entity,
                backgroundStartPrivileges);
        setBackgroundStartPrivileges(entity, backgroundStartPrivileges);
    }

    void removeBackgroundStartPrivileges(@NonNull Binder entity) {
        requireNonNull(entity, "entity");
        mWindowProcessController.removeBackgroundStartPrivileges(entity);
        setBackgroundStartPrivileges(entity, null);
    }

    @NonNull
    BackgroundStartPrivileges getBackgroundStartPrivileges() {
        synchronized (mBackgroundStartPrivileges) {
            if (mBackgroundStartPrivilegesMerged == null) {
                // Lazily generate the merged version when it's actually needed.
                mBackgroundStartPrivilegesMerged = BackgroundStartPrivileges.NONE;
                for (int i = mBackgroundStartPrivileges.size() - 1; i >= 0; --i) {
                    mBackgroundStartPrivilegesMerged =
                            mBackgroundStartPrivilegesMerged.merge(
                                    mBackgroundStartPrivileges.valueAt(i));
                }
            }
            return mBackgroundStartPrivilegesMerged;
        }
    }

    private void setBackgroundStartPrivileges(@NonNull Binder entity,
            @Nullable BackgroundStartPrivileges backgroundStartPrivileges) {
        synchronized (mBackgroundStartPrivileges) {
            final boolean changed;
            if (backgroundStartPrivileges == null) {
                changed = mBackgroundStartPrivileges.remove(entity) != null;
            } else {
                final BackgroundStartPrivileges oldBsp =
                        mBackgroundStartPrivileges.put(entity, backgroundStartPrivileges);
                // BackgroundStartPrivileges tries to reuse the same object and avoid creating
                // additional objects. For now, we just compare the reference to see if something
                // has changed.
                // TODO: actually compare the individual values to see if there's a change
                changed = backgroundStartPrivileges != oldBsp;
            }
            if (changed) {
                mBackgroundStartPrivilegesMerged = null;
            }
        }
    }

    @Override
    public void clearProfilerIfNeeded() {
        synchronized (mService.mAppProfiler.mProfilerLock) {
            mService.mAppProfiler.clearProfilerLPf();
        }
    }

    @Override
    public void updateServiceConnectionActivities() {
        synchronized (mService) {
            mService.mServices.updateServiceConnectionActivitiesLocked(mServices);
        }
    }

    @Override
    public void setPendingUiClean(boolean pendingUiClean) {
        synchronized (mProcLock) {
            mProfile.setPendingUiClean(pendingUiClean);
        }
    }

    @Override
    public void setPendingUiCleanAndForceProcessStateUpTo(int newState) {
        synchronized (mService) {
            setPendingUiClean(true);
            mState.forceProcessStateUpTo(newState);
        }
    }

    @Override
    public void updateProcessInfo(boolean updateServiceConnectionActivities, boolean activityChange,
            boolean updateOomAdj) {
        synchronized (mService) {
            if (updateServiceConnectionActivities) {
                mService.mServices.updateServiceConnectionActivitiesLocked(mServices);
            }
            if (mThread == null) {
                // Only update lru and oom-adj if the process is alive. Because it may be called
                // when cleaning up the last activity from handling process died, the dead process
                // should not be added to lru list again.
                return;
            }
            mService.updateLruProcessLocked(this, activityChange, null /* client */);
            if (updateOomAdj) {
                mService.updateOomAdjLocked(this, OOM_ADJ_REASON_ACTIVITY);
            }
        }
    }

    /**
     * Returns the total time (in milliseconds) spent executing in both user and system code.
     * Safe to call without lock held.
     */
    @Override
    public long getCpuTime() {
        return mService.mAppProfiler.getCpuTimeForPid(mPid);
    }

    public long getCpuDelayTime() {
        return mService.mAppProfiler.getCpuDelayTimeForPid(mPid);
    }

    @Override
    public void onStartActivity(int topProcessState, boolean setProfileProc, String packageName,
            long versionCode) {
        synchronized (mService) {
            mWaitingToKill = null;
            if (setProfileProc) {
                synchronized (mService.mAppProfiler.mProfilerLock) {
                    mService.mAppProfiler.setProfileProcLPf(this);
                }
            }
            if (packageName != null) {
                addPackage(packageName, versionCode, mService.mProcessStats);
            }

            // Update oom adj first, we don't want the additional states are involved in this round.
            updateProcessInfo(false /* updateServiceConnectionActivities */,
                    true /* activityChange */, true /* updateOomAdj */);
            setPendingUiClean(true);
            mState.setHasShownUi(true);
            mState.forceProcessStateUpTo(topProcessState);
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
        if (mPid == Process.myPid()) {
            Slog.wtf(TAG, "system can't run remote animation");
            return;
        }
        synchronized (mService) {
            mState.setRunningRemoteAnimation(runningRemoteAnimation);
        }
    }

    public long getInputDispatchingTimeoutMillis() {
        return mWindowProcessController.getInputDispatchingTimeoutMillis();
    }

    public int getProcessClassEnum() {
        if (mPid == MY_PID) {
            return ServerProtoEnums.SYSTEM_SERVER;
        }
        if (info == null) {
            return ServerProtoEnums.ERROR_SOURCE_UNKNOWN;
        }
        return (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? ServerProtoEnums.SYSTEM_APP :
            ServerProtoEnums.DATA_APP;
    }

    /** Non-private access is for tests only. */
    @VisibleForTesting
    List<ProcessRecord> getLruProcessList() {
        return mService.mProcessList.getLruProcessesLOSP();
    }

    public void setWasForceStopped(boolean stopped) {
        mWasForceStopped = stopped;
    }

    public boolean wasForceStopped() {
        return mWasForceStopped;
    }

    boolean isFreezable() {
        return mService.mOomAdjuster.mCachedAppOptimizer.useFreezer()
                && !mOptRecord.isFreezeExempt()
                && !mOptRecord.shouldNotFreeze()
                && mState.getCurAdj() >= ProcessList.FREEZER_CUTOFF_ADJ;
    }

    public void forEachConnectionHost(Consumer<ProcessRecord> consumer) {
        for (int i = mServices.numberOfConnections() - 1; i >= 0; i--) {
            final ConnectionRecord cr = mServices.getConnectionAt(i);
            final ProcessRecord service = cr.binding.service.app;
            consumer.accept(service);
        }
        for (int i = mServices.numberOfSdkSandboxConnections() - 1; i >= 0; i--) {
            final ConnectionRecord cr = mServices.getSdkSandboxConnectionAt(i);
            final ProcessRecord service = cr.binding.service.app;
            consumer.accept(service);
        }
        for (int i = mProviders.numberOfProviderConnections() - 1; i >= 0; i--) {
            ContentProviderConnection cpc = mProviders.getProviderConnectionAt(i);
            ProcessRecord provider = cpc.provider.proc;
            consumer.accept(provider);
        }
    }
}
