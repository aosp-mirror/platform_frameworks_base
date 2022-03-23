/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.app.procstats;

import android.content.ComponentName;
import android.os.Debug;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.service.procstats.ProcessStatsAssociationProto;
import android.service.procstats.ProcessStatsAvailablePagesProto;
import android.service.procstats.ProcessStatsPackageProto;
import android.service.procstats.ProcessStatsSectionProto;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import com.android.internal.app.ProcessMap;
import com.android.internal.app.procstats.AssociationState.SourceKey;
import com.android.internal.app.procstats.AssociationState.SourceState;

import dalvik.system.VMRuntime;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProcessStats implements Parcelable {
    public static final String TAG = "ProcessStats";
    static final boolean DEBUG = false;
    static final boolean DEBUG_PARCEL = false;

    public static final String SERVICE_NAME = "procstats";

    // How often the service commits its data, giving the minimum batching
    // that is done.
    public static long COMMIT_PERIOD = 3*60*60*1000;  // Commit current stats every 3 hours

    // Minimum uptime period before committing.  If the COMMIT_PERIOD has elapsed but
    // the total uptime has not exceeded this amount, then the commit will be held until
    // it is reached.
    public static long COMMIT_UPTIME_PERIOD = 60*60*1000;  // Must have at least 1 hour elapsed

    public static final int STATE_NOTHING = -1;
    public static final int STATE_PERSISTENT = 0;
    public static final int STATE_TOP = 1;
    public static final int STATE_BOUND_TOP_OR_FGS = 2;
    public static final int STATE_FGS = 3;
    public static final int STATE_IMPORTANT_FOREGROUND = 4;
    public static final int STATE_IMPORTANT_BACKGROUND = 5;
    public static final int STATE_BACKUP = 6;
    public static final int STATE_SERVICE = 7;
    public static final int STATE_SERVICE_RESTARTING = 8;
    public static final int STATE_RECEIVER = 9;
    public static final int STATE_HEAVY_WEIGHT = 10;
    public static final int STATE_HOME = 11;
    public static final int STATE_LAST_ACTIVITY = 12;
    public static final int STATE_CACHED_ACTIVITY = 13;
    public static final int STATE_CACHED_ACTIVITY_CLIENT = 14;
    public static final int STATE_CACHED_EMPTY = 15;
    public static final int STATE_COUNT = STATE_CACHED_EMPTY+1;

    public static final int PSS_SAMPLE_COUNT = 0;
    public static final int PSS_MINIMUM = 1;
    public static final int PSS_AVERAGE = 2;
    public static final int PSS_MAXIMUM = 3;
    public static final int PSS_USS_MINIMUM = 4;
    public static final int PSS_USS_AVERAGE = 5;
    public static final int PSS_USS_MAXIMUM = 6;
    public static final int PSS_RSS_MINIMUM = 7;
    public static final int PSS_RSS_AVERAGE = 8;
    public static final int PSS_RSS_MAXIMUM = 9;
    public static final int PSS_COUNT = PSS_RSS_MAXIMUM+1;

    public static final int SYS_MEM_USAGE_SAMPLE_COUNT = 0;
    public static final int SYS_MEM_USAGE_CACHED_MINIMUM = 1;
    public static final int SYS_MEM_USAGE_CACHED_AVERAGE = 2;
    public static final int SYS_MEM_USAGE_CACHED_MAXIMUM = 3;
    public static final int SYS_MEM_USAGE_FREE_MINIMUM = 4;
    public static final int SYS_MEM_USAGE_FREE_AVERAGE = 5;
    public static final int SYS_MEM_USAGE_FREE_MAXIMUM = 6;
    public static final int SYS_MEM_USAGE_ZRAM_MINIMUM = 7;
    public static final int SYS_MEM_USAGE_ZRAM_AVERAGE = 8;
    public static final int SYS_MEM_USAGE_ZRAM_MAXIMUM = 9;
    public static final int SYS_MEM_USAGE_KERNEL_MINIMUM = 10;
    public static final int SYS_MEM_USAGE_KERNEL_AVERAGE = 11;
    public static final int SYS_MEM_USAGE_KERNEL_MAXIMUM = 12;
    public static final int SYS_MEM_USAGE_NATIVE_MINIMUM = 13;
    public static final int SYS_MEM_USAGE_NATIVE_AVERAGE = 14;
    public static final int SYS_MEM_USAGE_NATIVE_MAXIMUM = 15;
    public static final int SYS_MEM_USAGE_COUNT = SYS_MEM_USAGE_NATIVE_MAXIMUM+1;

    public static final int ADJ_NOTHING = -1;
    public static final int ADJ_MEM_FACTOR_NORMAL = 0;
    public static final int ADJ_MEM_FACTOR_MODERATE = 1;
    public static final int ADJ_MEM_FACTOR_LOW = 2;
    public static final int ADJ_MEM_FACTOR_CRITICAL = 3;
    public static final int ADJ_MEM_FACTOR_COUNT = ADJ_MEM_FACTOR_CRITICAL+1;
    public static final int ADJ_SCREEN_MOD = ADJ_MEM_FACTOR_COUNT;
    public static final int ADJ_SCREEN_OFF = 0;
    public static final int ADJ_SCREEN_ON = ADJ_SCREEN_MOD;
    public static final int ADJ_COUNT = ADJ_SCREEN_ON*2;

    public static final int FLAG_COMPLETE = 1<<0;
    public static final int FLAG_SHUTDOWN = 1<<1;
    public static final int FLAG_SYSPROPS = 1<<2;

    public static final int ADD_PSS_INTERNAL_SINGLE = 0;
    public static final int ADD_PSS_INTERNAL_ALL_MEM = 1;
    public static final int ADD_PSS_INTERNAL_ALL_POLL = 2;
    public static final int ADD_PSS_EXTERNAL = 3;
    public static final int ADD_PSS_EXTERNAL_SLOW = 4;

    public static final int[] ALL_MEM_ADJ = new int[] { ADJ_MEM_FACTOR_NORMAL,
            ADJ_MEM_FACTOR_MODERATE, ADJ_MEM_FACTOR_LOW, ADJ_MEM_FACTOR_CRITICAL };

    public static final int[] ALL_SCREEN_ADJ = new int[] { ADJ_SCREEN_OFF, ADJ_SCREEN_ON };

    public static final int[] NON_CACHED_PROC_STATES = new int[] {
            STATE_PERSISTENT, STATE_TOP, STATE_BOUND_TOP_OR_FGS, STATE_FGS,
            STATE_IMPORTANT_FOREGROUND, STATE_IMPORTANT_BACKGROUND, STATE_BACKUP,
            STATE_SERVICE, STATE_SERVICE_RESTARTING, STATE_RECEIVER, STATE_HEAVY_WEIGHT
    };

    public static final int[] BACKGROUND_PROC_STATES = new int[] {
            STATE_IMPORTANT_FOREGROUND, STATE_IMPORTANT_BACKGROUND, STATE_BACKUP,
            STATE_HEAVY_WEIGHT, STATE_SERVICE, STATE_SERVICE_RESTARTING, STATE_RECEIVER
    };

    public static final int[] ALL_PROC_STATES = new int[] { STATE_PERSISTENT,
            STATE_TOP, STATE_BOUND_TOP_OR_FGS, STATE_FGS, STATE_IMPORTANT_FOREGROUND,
            STATE_IMPORTANT_BACKGROUND, STATE_BACKUP,
            STATE_SERVICE, STATE_SERVICE_RESTARTING, STATE_RECEIVER,
            STATE_HEAVY_WEIGHT, STATE_HOME, STATE_LAST_ACTIVITY, STATE_CACHED_ACTIVITY,
            STATE_CACHED_ACTIVITY_CLIENT, STATE_CACHED_EMPTY
    };

    // Should report process stats.
    public static final int REPORT_PROC_STATS = 0x01;
    // Should report package process stats.
    public static final int REPORT_PKG_PROC_STATS = 0x02;
    // Should report package service stats.
    public static final int REPORT_PKG_SVC_STATS = 0x04;
    // Should report package association stats.
    public static final int REPORT_PKG_ASC_STATS = 0x08;
    // Should report package stats.
    public static final int REPORT_PKG_STATS = 0x0E;
    // Should report all stats.
    public static final int REPORT_ALL = 0x0F;

    public static final int[] OPTIONS =
            {REPORT_PROC_STATS, REPORT_PKG_PROC_STATS, REPORT_PKG_SVC_STATS, REPORT_PKG_ASC_STATS,
                    REPORT_PKG_STATS, REPORT_ALL};
    public static final String[] OPTIONS_STR =
            {"proc", "pkg-proc", "pkg-svc", "pkg-asc", "pkg-all", "all"};

    // Current version of the parcel format.
    private static final int PARCEL_VERSION = 40;
    // In-memory Parcel magic number, used to detect attempts to unmarshall bad data
    private static final int MAGIC = 0x50535454;

    public String mReadError;
    public String mTimePeriodStartClockStr;
    public int mFlags;

    public final ProcessMap<LongSparseArray<PackageState>> mPackages = new ProcessMap<>();
    public final ProcessMap<ProcessState> mProcesses = new ProcessMap<>();

    public final ArrayList<AssociationState.SourceState> mTrackingAssociations = new ArrayList<>();

    public final long[] mMemFactorDurations = new long[ADJ_COUNT];
    public int mMemFactor = STATE_NOTHING;
    public long mStartTime;

    // Number of individual stats that have been aggregated to create this one.
    public int mNumAggregated = 1;

    public long mTimePeriodStartClock;
    public long mTimePeriodStartRealtime;
    public long mTimePeriodEndRealtime;
    public long mTimePeriodStartUptime;
    public long mTimePeriodEndUptime;
    String mRuntime;
    boolean mRunning;

    boolean mHasSwappedOutPss;

    // Count and total time expended doing "quick" single pss computations for internal use.
    public long mInternalSinglePssCount;
    public long mInternalSinglePssTime;

    // Count and total time expended doing "quick" all mem pss computations for internal use.
    public long mInternalAllMemPssCount;
    public long mInternalAllMemPssTime;

    // Count and total time expended doing "quick" all poll pss computations for internal use.
    public long mInternalAllPollPssCount;
    public long mInternalAllPollPssTime;

    // Count and total time expended doing "quick" pss computations due to external requests.
    public long mExternalPssCount;
    public long mExternalPssTime;

    // Count and total time expended doing full/slow pss computations due to external requests.
    public long mExternalSlowPssCount;
    public long mExternalSlowPssTime;

    public final SparseMappingTable mTableData = new SparseMappingTable();

    public final long[] mSysMemUsageArgs = new long[SYS_MEM_USAGE_COUNT];
    public final SysMemUsageTable mSysMemUsage = new SysMemUsageTable(mTableData);

    // For writing parcels.
    ArrayMap<String, Integer> mCommonStringToIndex;

    // For reading parcels.
    ArrayList<String> mIndexToCommonString;

    private static final Pattern sPageTypeRegex = Pattern.compile(
            "^Node\\s+(\\d+),.* zone\\s+(\\w+),.* type\\s+(\\w+)\\s+([\\s\\d]+?)\\s*$");
    private final ArrayList<Integer> mPageTypeNodes = new ArrayList<>();
    private final ArrayList<String> mPageTypeZones = new ArrayList<>();
    private final ArrayList<String> mPageTypeLabels = new ArrayList<>();
    private final ArrayList<int[]> mPageTypeSizes = new ArrayList<>();

    public ProcessStats(boolean running) {
        mRunning = running;
        reset();
        if (running) {
            // If we are actively running, we need to determine whether the system is
            // collecting swap pss data.
            Debug.MemoryInfo info = new Debug.MemoryInfo();
            Debug.getMemoryInfo(android.os.Process.myPid(), info);
            mHasSwappedOutPss = info.hasSwappedOutPss();
        }
    }

    public ProcessStats(Parcel in) {
        reset();
        readFromParcel(in);
    }

    /**
     * No-arg constructor is for use in AIDL-derived stubs.
     *
     * <p>This defaults to the non-running state, so is equivalent to ProcessStats(false).
     */
    public ProcessStats() {
        this(false);
    }

    public void add(ProcessStats other) {
        ArrayMap<String, SparseArray<LongSparseArray<PackageState>>> pkgMap =
                other.mPackages.getMap();
        for (int ip=0; ip<pkgMap.size(); ip++) {
            final String pkgName = pkgMap.keyAt(ip);
            final SparseArray<LongSparseArray<PackageState>> uids = pkgMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                final int uid = uids.keyAt(iu);
                final LongSparseArray<PackageState> versions = uids.valueAt(iu);
                for (int iv=0; iv<versions.size(); iv++) {
                    final long vers = versions.keyAt(iv);
                    final PackageState otherState = versions.valueAt(iv);
                    final int NPROCS = otherState.mProcesses.size();
                    final int NSRVS = otherState.mServices.size();
                    final int NASCS = otherState.mAssociations.size();
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        ProcessState otherProc = otherState.mProcesses.valueAt(iproc);
                        if (otherProc.getCommonProcess() != otherProc) {
                            if (DEBUG) Slog.d(TAG, "Adding pkg " + pkgName + " uid " + uid
                                    + " vers " + vers + " proc " + otherProc.getName());
                            ProcessState thisProc = getProcessStateLocked(pkgName, uid, vers,
                                    otherProc.getName());
                            if (thisProc.getCommonProcess() == thisProc) {
                                if (DEBUG) Slog.d(TAG, "Existing process is single-package, splitting");
                                thisProc.setMultiPackage(true);
                                long now = SystemClock.uptimeMillis();
                                final PackageState pkgState = getPackageStateLocked(pkgName, uid,
                                        vers);
                                thisProc = thisProc.clone(now);
                                pkgState.mProcesses.put(thisProc.getName(), thisProc);
                            }
                            thisProc.add(otherProc);
                        }
                    }
                    for (int isvc=0; isvc<NSRVS; isvc++) {
                        ServiceState otherSvc = otherState.mServices.valueAt(isvc);
                        if (DEBUG) Slog.d(TAG, "Adding pkg " + pkgName + " uid " + uid
                                + " service " + otherSvc.getName());
                        ServiceState thisSvc = getServiceStateLocked(pkgName, uid, vers,
                                otherSvc.getProcessName(), otherSvc.getName());
                        thisSvc.add(otherSvc);
                    }
                    for (int iasc=0; iasc<NASCS; iasc++) {
                        AssociationState otherAsc = otherState.mAssociations.valueAt(iasc);
                        if (DEBUG) Slog.d(TAG, "Adding pkg " + pkgName + " uid " + uid
                                + " association " + otherAsc.getName());
                        AssociationState thisAsc = getAssociationStateLocked(pkgName, uid, vers,
                                otherAsc.getProcessName(), otherAsc.getName());
                        thisAsc.add(otherAsc);
                    }
                }
            }
        }

        ArrayMap<String, SparseArray<ProcessState>> procMap = other.mProcesses.getMap();
        for (int ip=0; ip<procMap.size(); ip++) {
            SparseArray<ProcessState> uids = procMap.valueAt(ip);
            for (int iu=0; iu<uids.size(); iu++) {
                int uid = uids.keyAt(iu);
                ProcessState otherProc = uids.valueAt(iu);
                final String name = otherProc.getName();
                final String pkg = otherProc.getPackage();
                final long vers = otherProc.getVersion();
                ProcessState thisProc = mProcesses.get(name, uid);
                if (DEBUG) Slog.d(TAG, "Adding uid " + uid + " proc " + name);
                if (thisProc == null) {
                    if (DEBUG) Slog.d(TAG, "Creating new process!");
                    thisProc = new ProcessState(this, pkg, uid, vers, name);
                    mProcesses.put(name, uid, thisProc);
                    PackageState thisState = getPackageStateLocked(pkg, uid, vers);
                    if (!thisState.mProcesses.containsKey(name)) {
                        thisState.mProcesses.put(name, thisProc);
                    }
                }
                thisProc.add(otherProc);
            }
        }

        for (int i=0; i<ADJ_COUNT; i++) {
            if (DEBUG) Slog.d(TAG, "Total duration #" + i + " inc by "
                    + other.mMemFactorDurations[i] + " from "
                    + mMemFactorDurations[i]);
            mMemFactorDurations[i] += other.mMemFactorDurations[i];
        }

        mSysMemUsage.mergeStats(other.mSysMemUsage);

        mNumAggregated += other.mNumAggregated;

        if (other.mTimePeriodStartClock < mTimePeriodStartClock) {
            mTimePeriodStartClock = other.mTimePeriodStartClock;
            mTimePeriodStartClockStr = other.mTimePeriodStartClockStr;
        }
        mTimePeriodEndRealtime += other.mTimePeriodEndRealtime - other.mTimePeriodStartRealtime;
        mTimePeriodEndUptime += other.mTimePeriodEndUptime - other.mTimePeriodStartUptime;

        mInternalSinglePssCount += other.mInternalSinglePssCount;
        mInternalSinglePssTime += other.mInternalSinglePssTime;
        mInternalAllMemPssCount += other.mInternalAllMemPssCount;
        mInternalAllMemPssTime += other.mInternalAllMemPssTime;
        mInternalAllPollPssCount += other.mInternalAllPollPssCount;
        mInternalAllPollPssTime += other.mInternalAllPollPssTime;
        mExternalPssCount += other.mExternalPssCount;
        mExternalPssTime += other.mExternalPssTime;
        mExternalSlowPssCount += other.mExternalSlowPssCount;
        mExternalSlowPssTime += other.mExternalSlowPssTime;

        mHasSwappedOutPss |= other.mHasSwappedOutPss;
    }

    public void addSysMemUsage(long cachedMem, long freeMem, long zramMem, long kernelMem,
            long nativeMem) {
        if (mMemFactor != STATE_NOTHING) {
            int state = mMemFactor * STATE_COUNT;
            mSysMemUsageArgs[SYS_MEM_USAGE_SAMPLE_COUNT] = 1;
            for (int i=0; i<3; i++) {
                mSysMemUsageArgs[SYS_MEM_USAGE_CACHED_MINIMUM + i] = cachedMem;
                mSysMemUsageArgs[SYS_MEM_USAGE_FREE_MINIMUM + i] = freeMem;
                mSysMemUsageArgs[SYS_MEM_USAGE_ZRAM_MINIMUM + i] = zramMem;
                mSysMemUsageArgs[SYS_MEM_USAGE_KERNEL_MINIMUM + i] = kernelMem;
                mSysMemUsageArgs[SYS_MEM_USAGE_NATIVE_MINIMUM + i] = nativeMem;
            }
            mSysMemUsage.mergeStats(state, mSysMemUsageArgs, 0);
        }
    }

    public static final Parcelable.Creator<ProcessStats> CREATOR
            = new Parcelable.Creator<ProcessStats>() {
        public ProcessStats createFromParcel(Parcel in) {
            return new ProcessStats(in);
        }

        public ProcessStats[] newArray(int size) {
            return new ProcessStats[size];
        }
    };

    public void computeTotalMemoryUse(TotalMemoryUseCollection data, long now) {
        data.totalTime = 0;
        for (int i=0; i<STATE_COUNT; i++) {
            data.processStateWeight[i] = 0;
            data.processStatePss[i] = 0;
            data.processStateTime[i] = 0;
            data.processStateSamples[i] = 0;
        }
        for (int i=0; i<SYS_MEM_USAGE_COUNT; i++) {
            data.sysMemUsage[i] = 0;
        }
        data.sysMemCachedWeight = 0;
        data.sysMemFreeWeight = 0;
        data.sysMemZRamWeight = 0;
        data.sysMemKernelWeight = 0;
        data.sysMemNativeWeight = 0;
        data.sysMemSamples = 0;
        final long[] totalMemUsage = mSysMemUsage.getTotalMemUsage();
        for (int is=0; is<data.screenStates.length; is++) {
            for (int im=0; im<data.memStates.length; im++) {
                int memBucket = data.screenStates[is] + data.memStates[im];
                int stateBucket = memBucket * STATE_COUNT;
                long memTime = mMemFactorDurations[memBucket];
                if (mMemFactor == memBucket) {
                    memTime += now - mStartTime;
                }
                data.totalTime += memTime;
                final int sysKey = mSysMemUsage.getKey((byte)stateBucket);
                long[] longs = totalMemUsage;
                int idx = 0;
                if (sysKey != SparseMappingTable.INVALID_KEY) {
                    final long[] tmpLongs = mSysMemUsage.getArrayForKey(sysKey);
                    final int tmpIndex = SparseMappingTable.getIndexFromKey(sysKey);
                    if (tmpLongs[tmpIndex+SYS_MEM_USAGE_SAMPLE_COUNT] >= 3) {
                        SysMemUsageTable.mergeSysMemUsage(data.sysMemUsage, 0, longs, idx);
                        longs = tmpLongs;
                        idx = tmpIndex;
                    }
                }
                data.sysMemCachedWeight += longs[idx+SYS_MEM_USAGE_CACHED_AVERAGE]
                        * (double)memTime;
                data.sysMemFreeWeight += longs[idx+SYS_MEM_USAGE_FREE_AVERAGE]
                        * (double)memTime;
                data.sysMemZRamWeight += longs[idx + SYS_MEM_USAGE_ZRAM_AVERAGE]
                        * (double) memTime;
                data.sysMemKernelWeight += longs[idx+SYS_MEM_USAGE_KERNEL_AVERAGE]
                        * (double)memTime;
                data.sysMemNativeWeight += longs[idx+SYS_MEM_USAGE_NATIVE_AVERAGE]
                        * (double)memTime;
                data.sysMemSamples += longs[idx+SYS_MEM_USAGE_SAMPLE_COUNT];
             }
        }
        data.hasSwappedOutPss = mHasSwappedOutPss;
        ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        for (int iproc=0; iproc<procMap.size(); iproc++) {
            SparseArray<ProcessState> uids = procMap.valueAt(iproc);
            for (int iu=0; iu<uids.size(); iu++) {
                final ProcessState proc = uids.valueAt(iu);
                proc.aggregatePss(data, now);
            }
        }
    }

    public void reset() {
        if (DEBUG) Slog.d(TAG, "Resetting state of " + mTimePeriodStartClockStr);
        resetCommon();
        mPackages.getMap().clear();
        mProcesses.getMap().clear();
        mMemFactor = STATE_NOTHING;
        mStartTime = 0;
        if (DEBUG) Slog.d(TAG, "State reset; now " + mTimePeriodStartClockStr);
    }

    public void resetSafely() {
        if (DEBUG) Slog.d(TAG, "Safely resetting state of " + mTimePeriodStartClockStr);
        resetCommon();

        // First initialize use count of all common processes.
        final long now = SystemClock.uptimeMillis();
        final ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        for (int ip=procMap.size()-1; ip>=0; ip--) {
            final SparseArray<ProcessState> uids = procMap.valueAt(ip);
            for (int iu=uids.size()-1; iu>=0; iu--) {
                uids.valueAt(iu).tmpNumInUse = 0;
           }
        }

        // Next reset or prune all per-package processes, and for the ones that are reset
        // track this back to the common processes.
        final ArrayMap<String, SparseArray<LongSparseArray<PackageState>>> pkgMap =
                mPackages.getMap();
        for (int ip=pkgMap.size()-1; ip>=0; ip--) {
            final SparseArray<LongSparseArray<PackageState>> uids = pkgMap.valueAt(ip);
            for (int iu=uids.size()-1; iu>=0; iu--) {
                final LongSparseArray<PackageState> vpkgs = uids.valueAt(iu);
                for (int iv=vpkgs.size()-1; iv>=0; iv--) {
                    final PackageState pkgState = vpkgs.valueAt(iv);
                    for (int iproc=pkgState.mProcesses.size()-1; iproc>=0; iproc--) {
                        final ProcessState ps = pkgState.mProcesses.valueAt(iproc);
                        if (ps.isInUse()) {
                            ps.resetSafely(now);
                            ps.getCommonProcess().tmpNumInUse++;
                            ps.getCommonProcess().tmpFoundSubProc = ps;
                        } else {
                            pkgState.mProcesses.valueAt(iproc).makeDead();
                            pkgState.mProcesses.removeAt(iproc);
                        }
                    }
                    for (int isvc=pkgState.mServices.size()-1; isvc>=0; isvc--) {
                        final ServiceState ss = pkgState.mServices.valueAt(isvc);
                        if (ss.isInUse()) {
                            ss.resetSafely(now);
                        } else {
                            pkgState.mServices.removeAt(isvc);
                        }
                    }
                    for (int iasc=pkgState.mAssociations.size()-1; iasc>=0; iasc--) {
                        final AssociationState as = pkgState.mAssociations.valueAt(iasc);
                        if (as.isInUse()) {
                            as.resetSafely(now);
                        } else {
                            pkgState.mAssociations.removeAt(iasc);
                        }
                    }
                    if (pkgState.mProcesses.size() <= 0 && pkgState.mServices.size() <= 0
                            && pkgState.mAssociations.size() <= 0) {
                        vpkgs.removeAt(iv);
                    }
                }
                if (vpkgs.size() <= 0) {
                    uids.removeAt(iu);
                }
            }
            if (uids.size() <= 0) {
                pkgMap.removeAt(ip);
            }
        }

        // Finally prune out any common processes that are no longer in use.
        for (int ip=procMap.size()-1; ip>=0; ip--) {
            final SparseArray<ProcessState> uids = procMap.valueAt(ip);
            for (int iu=uids.size()-1; iu>=0; iu--) {
                ProcessState ps = uids.valueAt(iu);
                if (ps.isInUse() || ps.tmpNumInUse > 0) {
                    // If this is a process for multiple packages, we could at this point
                    // be back down to one package.  In that case, we want to revert back
                    // to a single shared ProcessState.  We can do this by converting the
                    // current package-specific ProcessState up to the shared ProcessState,
                    // throwing away the current one we have here (because nobody else is
                    // using it).
                    if (!ps.isActive() && ps.isMultiPackage() && ps.tmpNumInUse == 1) {
                        // Here we go...
                        ps = ps.tmpFoundSubProc;
                        ps.makeStandalone();
                        uids.setValueAt(iu, ps);
                    } else {
                        ps.resetSafely(now);
                    }
                } else {
                    ps.makeDead();
                    uids.removeAt(iu);
                }
            }
            if (uids.size() <= 0) {
                procMap.removeAt(ip);
            }
        }

        mStartTime = now;
        if (DEBUG) Slog.d(TAG, "State reset; now " + mTimePeriodStartClockStr);
    }

    private void resetCommon() {
        mNumAggregated = 1;
        mTimePeriodStartClock = System.currentTimeMillis();
        buildTimePeriodStartClockStr();
        mTimePeriodStartRealtime = mTimePeriodEndRealtime = SystemClock.elapsedRealtime();
        mTimePeriodStartUptime = mTimePeriodEndUptime = SystemClock.uptimeMillis();
        mInternalSinglePssCount = 0;
        mInternalSinglePssTime = 0;
        mInternalAllMemPssCount = 0;
        mInternalAllMemPssTime = 0;
        mInternalAllPollPssCount = 0;
        mInternalAllPollPssTime = 0;
        mExternalPssCount = 0;
        mExternalPssTime = 0;
        mExternalSlowPssCount = 0;
        mExternalSlowPssTime = 0;
        mTableData.reset();
        Arrays.fill(mMemFactorDurations, 0);
        mSysMemUsage.resetTable();
        mStartTime = 0;
        mReadError = null;
        mFlags = 0;
        evaluateSystemProperties(true);
        updateFragmentation();
    }

    public boolean evaluateSystemProperties(boolean update) {
        boolean changed = false;
        String runtime = SystemProperties.get("persist.sys.dalvik.vm.lib.2",
                VMRuntime.getRuntime().vmLibrary());
        if (!Objects.equals(runtime, mRuntime)) {
            changed = true;
            if (update) {
                mRuntime = runtime;
            }
        }
        return changed;
    }

    private void buildTimePeriodStartClockStr() {
        mTimePeriodStartClockStr = DateFormat.format("yyyy-MM-dd-HH-mm-ss",
                mTimePeriodStartClock).toString();
    }

    static final int[] BAD_TABLE = new int[0];


    /**
     * Load the system's memory fragmentation info.
     */
    public void updateFragmentation() {
        // Parse /proc/pagetypeinfo and store the values.
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/pagetypeinfo"));
            final Matcher matcher = sPageTypeRegex.matcher("");
            mPageTypeNodes.clear();
            mPageTypeZones.clear();
            mPageTypeLabels.clear();
            mPageTypeSizes.clear();
            while (true) {
                final String line = reader.readLine();
                if (line == null) {
                    break;
                }
                matcher.reset(line);
                if (matcher.matches()) {
                    final Integer node = Integer.valueOf(matcher.group(1), 10);
                    if (node == null) {
                        continue;
                    }
                    mPageTypeNodes.add(node);
                    mPageTypeZones.add(matcher.group(2));
                    mPageTypeLabels.add(matcher.group(3));
                    mPageTypeSizes.add(splitAndParseNumbers(matcher.group(4)));
                }
            }
        } catch (IOException ex) {
            mPageTypeNodes.clear();
            mPageTypeZones.clear();
            mPageTypeLabels.clear();
            mPageTypeSizes.clear();
            return;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException allHopeIsLost) {
                }
            }
        }
    }

    /**
     * Split the string of digits separaed by spaces.  There must be no
     * leading or trailing spaces.  The format is ensured by the regex
     * above.
     */
    private static int[] splitAndParseNumbers(String s) {
        // These are always positive and the numbers can't be so big that we'll overflow
        // so just do the parsing inline.
        boolean digit = false;
        int count = 0;
        final int N = s.length();
        // Count the numbers
        for (int i=0; i<N; i++) {
            final char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (!digit) {
                    digit = true;
                    count++;
                }
            } else {
                digit = false;
            }
        }
        // Parse the numbers
        final int[] result = new int[count];
        int p = 0;
        int val = 0;
        for (int i=0; i<N; i++) {
            final char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (!digit) {
                    digit = true;
                    val = c - '0';
                } else {
                    val *= 10;
                    val += c - '0';
                }
            } else {
                if (digit) {
                    digit = false;
                    result[p++] = val;
                }
            }
        }
        if (count > 0) {
            result[count-1] = val;
        }
        return result;
    }


    private void writeCompactedLongArray(Parcel out, long[] array, int num) {
        for (int i=0; i<num; i++) {
            long val = array[i];
            if (val < 0) {
                Slog.w(TAG, "Time val negative: " + val);
                val = 0;
            }
            if (val <= Integer.MAX_VALUE) {
                out.writeInt((int)val);
            } else {
                int top = ~((int)((val>>32)&0x7fffffff));
                int bottom = (int)(val&0x0ffffffffL);
                out.writeInt(top);
                out.writeInt(bottom);
            }
        }
    }

    private void readCompactedLongArray(Parcel in, int version, long[] array, int num) {
        if (version <= 10) {
            in.readLongArray(array);
            return;
        }
        final int alen = array.length;
        if (num > alen) {
            throw new RuntimeException("bad array lengths: got " + num + " array is " + alen);
        }
        int i;
        for (i=0; i<num; i++) {
            int val = in.readInt();
            if (val >= 0) {
                array[i] = val;
            } else {
                int bottom = in.readInt();
                array[i] = (((long)~val)<<32) | bottom;
            }
        }
        while (i < alen) {
            array[i] = 0;
            i++;
        }
    }

    void writeCommonString(Parcel out, String name) {
        Integer index = mCommonStringToIndex.get(name);
        if (index != null) {
            out.writeInt(index);
            return;
        }
        index = mCommonStringToIndex.size();
        mCommonStringToIndex.put(name, index);
        out.writeInt(~index);
        out.writeString(name);
    }

    String readCommonString(Parcel in, int version) {
        if (version <= 9) {
            return in.readString();
        }
        int index = in.readInt();
        if (index >= 0) {
            return mIndexToCommonString.get(index);
        }
        index = ~index;
        String name = in.readString();
        while (mIndexToCommonString.size() <= index) {
            mIndexToCommonString.add(null);
        }
        mIndexToCommonString.set(index, name);
        return name;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        writeToParcel(out, SystemClock.uptimeMillis(), flags);
    }

    /** @hide */
    public void writeToParcel(Parcel out, long now, int flags) {
        out.writeInt(MAGIC);
        out.writeInt(PARCEL_VERSION);
        out.writeInt(STATE_COUNT);
        out.writeInt(ADJ_COUNT);
        out.writeInt(PSS_COUNT);
        out.writeInt(SYS_MEM_USAGE_COUNT);
        out.writeInt(SparseMappingTable.ARRAY_SIZE);

        mCommonStringToIndex = new ArrayMap<String, Integer>(mProcesses.size());

        // First commit all running times.
        ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        final int NPROC = procMap.size();
        for (int ip=0; ip<NPROC; ip++) {
            SparseArray<ProcessState> uids = procMap.valueAt(ip);
            final int NUID = uids.size();
            for (int iu=0; iu<NUID; iu++) {
                uids.valueAt(iu).commitStateTime(now);
            }
        }
        final ArrayMap<String, SparseArray<LongSparseArray<PackageState>>> pkgMap =
                mPackages.getMap();
        final int NPKG = pkgMap.size();
        for (int ip=0; ip<NPKG; ip++) {
            final SparseArray<LongSparseArray<PackageState>> uids = pkgMap.valueAt(ip);
            final int NUID = uids.size();
            for (int iu=0; iu<NUID; iu++) {
                final LongSparseArray<PackageState> vpkgs = uids.valueAt(iu);
                final int NVERS = vpkgs.size();
                for (int iv=0; iv<NVERS; iv++) {
                    PackageState pkgState = vpkgs.valueAt(iv);
                    final int NPROCS = pkgState.mProcesses.size();
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                        if (proc.getCommonProcess() != proc) {
                            proc.commitStateTime(now);
                        }
                    }
                    final int NSRVS = pkgState.mServices.size();
                    for (int isvc=0; isvc<NSRVS; isvc++) {
                        pkgState.mServices.valueAt(isvc).commitStateTime(now);
                    }
                    final int NASCS = pkgState.mAssociations.size();
                    for (int iasc=0; iasc<NASCS; iasc++) {
                        pkgState.mAssociations.valueAt(iasc).commitStateTime(now);
                    }
                }
            }
        }

        out.writeInt(mNumAggregated);
        out.writeLong(mTimePeriodStartClock);
        out.writeLong(mTimePeriodStartRealtime);
        out.writeLong(mTimePeriodEndRealtime);
        out.writeLong(mTimePeriodStartUptime);
        out.writeLong(mTimePeriodEndUptime);
        out.writeLong(mInternalSinglePssCount);
        out.writeLong(mInternalSinglePssTime);
        out.writeLong(mInternalAllMemPssCount);
        out.writeLong(mInternalAllMemPssTime);
        out.writeLong(mInternalAllPollPssCount);
        out.writeLong(mInternalAllPollPssTime);
        out.writeLong(mExternalPssCount);
        out.writeLong(mExternalPssTime);
        out.writeLong(mExternalSlowPssCount);
        out.writeLong(mExternalSlowPssTime);
        out.writeString(mRuntime);
        out.writeInt(mHasSwappedOutPss ? 1 : 0);
        out.writeInt(mFlags);

        mTableData.writeToParcel(out);

        if (mMemFactor != STATE_NOTHING) {
            mMemFactorDurations[mMemFactor] += now - mStartTime;
            mStartTime = now;
        }
        writeCompactedLongArray(out, mMemFactorDurations, mMemFactorDurations.length);

        mSysMemUsage.writeToParcel(out);

        out.writeInt(NPROC);
        for (int ip=0; ip<NPROC; ip++) {
            writeCommonString(out, procMap.keyAt(ip));
            final SparseArray<ProcessState> uids = procMap.valueAt(ip);
            final int NUID = uids.size();
            out.writeInt(NUID);
            for (int iu=0; iu<NUID; iu++) {
                out.writeInt(uids.keyAt(iu));
                final ProcessState proc = uids.valueAt(iu);
                writeCommonString(out, proc.getPackage());
                out.writeLong(proc.getVersion());
                proc.writeToParcel(out, now);
            }
        }
        out.writeInt(NPKG);
        for (int ip=0; ip<NPKG; ip++) {
            writeCommonString(out, pkgMap.keyAt(ip));
            final SparseArray<LongSparseArray<PackageState>> uids = pkgMap.valueAt(ip);
            final int NUID = uids.size();
            out.writeInt(NUID);
            for (int iu=0; iu<NUID; iu++) {
                out.writeInt(uids.keyAt(iu));
                final LongSparseArray<PackageState> vpkgs = uids.valueAt(iu);
                final int NVERS = vpkgs.size();
                out.writeInt(NVERS);
                for (int iv=0; iv<NVERS; iv++) {
                    out.writeLong(vpkgs.keyAt(iv));
                    final PackageState pkgState = vpkgs.valueAt(iv);
                    final int NPROCS = pkgState.mProcesses.size();
                    out.writeInt(NPROCS);
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        writeCommonString(out, pkgState.mProcesses.keyAt(iproc));
                        final ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                        if (proc.getCommonProcess() == proc) {
                            // This is the same as the common process we wrote above.
                            out.writeInt(0);
                        } else {
                            // There is separate data for this package's process.
                            out.writeInt(1);
                            proc.writeToParcel(out, now);
                        }
                    }
                    final int NSRVS = pkgState.mServices.size();
                    out.writeInt(NSRVS);
                    for (int isvc=0; isvc<NSRVS; isvc++) {
                        out.writeString(pkgState.mServices.keyAt(isvc));
                        final ServiceState svc = pkgState.mServices.valueAt(isvc);
                        writeCommonString(out, svc.getProcessName());
                        svc.writeToParcel(out, now);
                    }
                    final int NASCS = pkgState.mAssociations.size();
                    out.writeInt(NASCS);
                    for (int iasc=0; iasc<NASCS; iasc++) {
                        writeCommonString(out, pkgState.mAssociations.keyAt(iasc));
                        final AssociationState asc = pkgState.mAssociations.valueAt(iasc);
                        writeCommonString(out, asc.getProcessName());
                        asc.writeToParcel(this, out, now);
                    }
                }
            }
        }

        // Fragmentation info (/proc/pagetypeinfo)
        final int NPAGETYPES = mPageTypeLabels.size();
        out.writeInt(NPAGETYPES);
        for (int i=0; i<NPAGETYPES; i++) {
            out.writeInt(mPageTypeNodes.get(i));
            out.writeString(mPageTypeZones.get(i));
            out.writeString(mPageTypeLabels.get(i));
            out.writeIntArray(mPageTypeSizes.get(i));
        }

        mCommonStringToIndex = null;
    }

    private boolean readCheckedInt(Parcel in, int val, String what) {
        int got;
        if ((got=in.readInt()) != val) {
            mReadError = "bad " + what + ": " + got;
            return false;
        }
        return true;
    }

    static byte[] readFully(InputStream stream, int[] outLen) throws IOException {
        int pos = 0;
        final int initialAvail = stream.available();
        byte[] data = new byte[initialAvail > 0 ? (initialAvail+1) : 16384];
        while (true) {
            int amt = stream.read(data, pos, data.length-pos);
            if (DEBUG_PARCEL) Slog.i("foo", "Read " + amt + " bytes at " + pos
                    + " of avail " + data.length);
            if (amt < 0) {
                if (DEBUG_PARCEL) Slog.i("foo", "**** FINISHED READING: pos=" + pos
                        + " len=" + data.length);
                outLen[0] = pos;
                return data;
            }
            pos += amt;
            if (pos >= data.length) {
                byte[] newData = new byte[pos+16384];
                if (DEBUG_PARCEL) Slog.i(TAG, "Copying " + pos + " bytes to new array len "
                        + newData.length);
                System.arraycopy(data, 0, newData, 0, pos);
                data = newData;
            }
        }
    }

    public void read(InputStream stream) {
        try {
            int[] len = new int[1];
            byte[] raw = readFully(stream, len);
            Parcel in = Parcel.obtain();
            in.unmarshall(raw, 0, len[0]);
            in.setDataPosition(0);
            stream.close();

            readFromParcel(in);
        } catch (IOException e) {
            mReadError = "caught exception: " + e;
        }
    }

    public void readFromParcel(Parcel in) {
        final boolean hadData = mPackages.getMap().size() > 0
                || mProcesses.getMap().size() > 0;
        if (hadData) {
            resetSafely();
        }

        if (!readCheckedInt(in, MAGIC, "magic number")) {
            return;
        }
        int version = in.readInt();
        if (version != PARCEL_VERSION) {
            mReadError = "bad version: " + version;
            return;
        }
        if (!readCheckedInt(in, STATE_COUNT, "state count")) {
            return;
        }
        if (!readCheckedInt(in, ADJ_COUNT, "adj count")) {
            return;
        }
        if (!readCheckedInt(in, PSS_COUNT, "pss count")) {
            return;
        }
        if (!readCheckedInt(in, SYS_MEM_USAGE_COUNT, "sys mem usage count")) {
            return;
        }
        if (!readCheckedInt(in, SparseMappingTable.ARRAY_SIZE, "longs size")) {
            return;
        }

        mIndexToCommonString = new ArrayList<String>();

        mNumAggregated = in.readInt();
        mTimePeriodStartClock = in.readLong();
        buildTimePeriodStartClockStr();
        mTimePeriodStartRealtime = in.readLong();
        mTimePeriodEndRealtime = in.readLong();
        mTimePeriodStartUptime = in.readLong();
        mTimePeriodEndUptime = in.readLong();
        mInternalSinglePssCount = in.readLong();
        mInternalSinglePssTime = in.readLong();
        mInternalAllMemPssCount = in.readLong();
        mInternalAllMemPssTime = in.readLong();
        mInternalAllPollPssCount = in.readLong();
        mInternalAllPollPssTime = in.readLong();
        mExternalPssCount = in.readLong();
        mExternalPssTime = in.readLong();
        mExternalSlowPssCount = in.readLong();
        mExternalSlowPssTime = in.readLong();
        mRuntime = in.readString();
        mHasSwappedOutPss = in.readInt() != 0;
        mFlags = in.readInt();
        mTableData.readFromParcel(in);
        readCompactedLongArray(in, version, mMemFactorDurations, mMemFactorDurations.length);
        if (!mSysMemUsage.readFromParcel(in)) {
            return;
        }

        int NPROC = in.readInt();
        if (NPROC < 0) {
            mReadError = "bad process count: " + NPROC;
            return;
        }
        while (NPROC > 0) {
            NPROC--;
            final String procName = readCommonString(in, version);
            if (procName == null) {
                mReadError = "bad process name";
                return;
            }
            int NUID = in.readInt();
            if (NUID < 0) {
                mReadError = "bad uid count: " + NUID;
                return;
            }
            while (NUID > 0) {
                NUID--;
                final int uid = in.readInt();
                if (uid < 0) {
                    mReadError = "bad uid: " + uid;
                    return;
                }
                final String pkgName = readCommonString(in, version);
                if (pkgName == null) {
                    mReadError = "bad process package name";
                    return;
                }
                final long vers = in.readLong();
                ProcessState proc = hadData ? mProcesses.get(procName, uid) : null;
                if (proc != null) {
                    if (!proc.readFromParcel(in, version, false)) {
                        return;
                    }
                } else {
                    proc = new ProcessState(this, pkgName, uid, vers, procName);
                    if (!proc.readFromParcel(in, version, true)) {
                        return;
                    }
                }
                if (DEBUG_PARCEL) Slog.d(TAG, "Adding process: " + procName + " " + uid
                        + " " + proc);
                mProcesses.put(procName, uid, proc);
            }
        }

        if (DEBUG_PARCEL) Slog.d(TAG, "Read " + mProcesses.getMap().size() + " processes");

        int NPKG = in.readInt();
        if (NPKG < 0) {
            mReadError = "bad package count: " + NPKG;
            return;
        }
        while (NPKG > 0) {
            NPKG--;
            final String pkgName = readCommonString(in, version);
            if (pkgName == null) {
                mReadError = "bad package name";
                return;
            }
            int NUID = in.readInt();
            if (NUID < 0) {
                mReadError = "bad uid count: " + NUID;
                return;
            }
            while (NUID > 0) {
                NUID--;
                final int uid = in.readInt();
                if (uid < 0) {
                    mReadError = "bad uid: " + uid;
                    return;
                }
                int NVERS = in.readInt();
                if (NVERS < 0) {
                    mReadError = "bad versions count: " + NVERS;
                    return;
                }
                while (NVERS > 0) {
                    NVERS--;
                    final long vers = in.readLong();
                    PackageState pkgState = new PackageState(this, pkgName, uid, vers);
                    LongSparseArray<PackageState> vpkg = mPackages.get(pkgName, uid);
                    if (vpkg == null) {
                        vpkg = new LongSparseArray<>();
                        mPackages.put(pkgName, uid, vpkg);
                    }
                    vpkg.put(vers, pkgState);
                    int NPROCS = in.readInt();
                    if (NPROCS < 0) {
                        mReadError = "bad package process count: " + NPROCS;
                        return;
                    }
                    while (NPROCS > 0) {
                        NPROCS--;
                        String procName = readCommonString(in, version);
                        if (procName == null) {
                            mReadError = "bad package process name";
                            return;
                        }
                        int hasProc = in.readInt();
                        if (DEBUG_PARCEL) Slog.d(TAG, "Reading package " + pkgName + " " + uid
                                + " process " + procName + " hasProc=" + hasProc);
                        ProcessState commonProc = mProcesses.get(procName, uid);
                        if (DEBUG_PARCEL) Slog.d(TAG, "Got common proc " + procName + " " + uid
                                + ": " + commonProc);
                        if (commonProc == null) {
                            mReadError = "no common proc: " + procName;
                            return;
                        }
                        if (hasProc != 0) {
                            // The process for this package is unique to the package; we
                            // need to load it.  We don't need to do anything about it if
                            // it is not unique because if someone later looks for it
                            // they will find and use it from the global procs.
                            ProcessState proc = hadData ? pkgState.mProcesses.get(procName) : null;
                            if (proc != null) {
                                if (!proc.readFromParcel(in, version, false)) {
                                    return;
                                }
                            } else {
                                proc = new ProcessState(commonProc, pkgName, uid, vers, procName,
                                        0);
                                if (!proc.readFromParcel(in, version, true)) {
                                    return;
                                }
                            }
                            if (DEBUG_PARCEL) Slog.d(TAG, "Adding package " + pkgName + " process: "
                                    + procName + " " + uid + " " + proc);
                            pkgState.mProcesses.put(procName, proc);
                        } else {
                            if (DEBUG_PARCEL) Slog.d(TAG, "Adding package " + pkgName + " process: "
                                    + procName + " " + uid + " " + commonProc);
                            pkgState.mProcesses.put(procName, commonProc);
                        }
                    }
                    int NSRVS = in.readInt();
                    if (NSRVS < 0) {
                        mReadError = "bad package service count: " + NSRVS;
                        return;
                    }
                    while (NSRVS > 0) {
                        NSRVS--;
                        String serviceName = in.readString();
                        if (serviceName == null) {
                            mReadError = "bad package service name";
                            return;
                        }
                        String processName = version > 9 ? readCommonString(in, version) : null;
                        ServiceState serv = hadData ? pkgState.mServices.get(serviceName) : null;
                        if (serv == null) {
                            serv = new ServiceState(this, pkgName, serviceName, processName, null);
                        }
                        if (!serv.readFromParcel(in)) {
                            return;
                        }
                        if (DEBUG_PARCEL) Slog.d(TAG, "Adding package " + pkgName + " service: "
                                + serviceName + " " + uid + " " + serv);
                        pkgState.mServices.put(serviceName, serv);
                    }
                    int NASCS = in.readInt();
                    if (NASCS < 0) {
                        mReadError = "bad package association count: " + NASCS;
                        return;
                    }
                    while (NASCS > 0) {
                        NASCS--;
                        String associationName = readCommonString(in, version);
                        if (associationName == null) {
                            mReadError = "bad package association name";
                            return;
                        }
                        String processName = readCommonString(in, version);
                        AssociationState asc = hadData
                                ? pkgState.mAssociations.get(associationName) : null;
                        if (asc == null) {
                            asc = new AssociationState(this, pkgState, associationName,
                                    processName, null);
                        }
                        String errorMsg = asc.readFromParcel(this, in, version);
                        if (errorMsg != null) {
                            mReadError = errorMsg;
                            return;
                        }
                        if (DEBUG_PARCEL) Slog.d(TAG, "Adding package " + pkgName + " association: "
                                + associationName + " " + uid + " " + asc);
                        pkgState.mAssociations.put(associationName, asc);
                    }
                }
            }
        }

        // Fragmentation info
        final int NPAGETYPES = in.readInt();
        mPageTypeNodes.clear();
        mPageTypeNodes.ensureCapacity(NPAGETYPES);
        mPageTypeZones.clear();
        mPageTypeZones.ensureCapacity(NPAGETYPES);
        mPageTypeLabels.clear();
        mPageTypeLabels.ensureCapacity(NPAGETYPES);
        mPageTypeSizes.clear();
        mPageTypeSizes.ensureCapacity(NPAGETYPES);
        for (int i=0; i<NPAGETYPES; i++) {
            mPageTypeNodes.add(in.readInt());
            mPageTypeZones.add(in.readString());
            mPageTypeLabels.add(in.readString());
            mPageTypeSizes.add(in.createIntArray());
        }

        mIndexToCommonString = null;

        if (DEBUG_PARCEL) Slog.d(TAG, "Successfully read procstats!");
    }

    public PackageState getPackageStateLocked(String packageName, int uid, long vers) {
        LongSparseArray<PackageState> vpkg = mPackages.get(packageName, uid);
        if (vpkg == null) {
            vpkg = new LongSparseArray<>();
            mPackages.put(packageName, uid, vpkg);
        }
        PackageState as = vpkg.get(vers);
        if (as != null) {
            return as;
        }
        as = new PackageState(this, packageName, uid, vers);
        vpkg.put(vers, as);
        return as;
    }

    public ProcessState getProcessStateLocked(String packageName, int uid, long vers,
            String processName) {
        return getProcessStateLocked(getPackageStateLocked(packageName, uid, vers), processName);
    }

    public ProcessState getProcessStateLocked(PackageState pkgState, String processName) {
        ProcessState ps = pkgState.mProcesses.get(processName);
        if (ps != null) {
            return ps;
        }
        ProcessState commonProc = mProcesses.get(processName, pkgState.mUid);
        if (commonProc == null) {
            commonProc = new ProcessState(this, pkgState.mPackageName, pkgState.mUid,
                    pkgState.mVersionCode, processName);
            mProcesses.put(processName, pkgState.mUid, commonProc);
            if (DEBUG) Slog.d(TAG, "GETPROC created new common " + commonProc);
        }
        if (!commonProc.isMultiPackage()) {
            if (pkgState.mPackageName.equals(commonProc.getPackage())
                    && pkgState.mVersionCode == commonProc.getVersion()) {
                // This common process is not in use by multiple packages, and
                // is for the calling package, so we can just use it directly.
                ps = commonProc;
                if (DEBUG) Slog.d(TAG, "GETPROC also using for pkg " + commonProc);
            } else {
                if (DEBUG) Slog.d(TAG, "GETPROC need to split common proc!");
                // This common process has not been in use by multiple packages,
                // but it was created for a different package than the caller.
                // We need to convert it to a multi-package process.
                commonProc.setMultiPackage(true);
                // To do this, we need to make two new process states, one a copy
                // of the current state for the process under the original package
                // name, and the second a free new process state for it as the
                // new package name.
                long now = SystemClock.uptimeMillis();
                // First let's make a copy of the current process state and put
                // that under the now unique state for its original package name.
                final PackageState commonPkgState = getPackageStateLocked(commonProc.getPackage(),
                        pkgState.mUid, commonProc.getVersion());
                if (commonPkgState != null) {
                    ProcessState cloned = commonProc.clone(now);
                    if (DEBUG) Slog.d(TAG, "GETPROC setting clone to pkg " + commonProc.getPackage()
                            + ": " + cloned);
                    commonPkgState.mProcesses.put(commonProc.getName(), cloned);
                    // If this has active services, we need to update their process pointer
                    // to point to the new package-specific process state.
                    for (int i=commonPkgState.mServices.size()-1; i>=0; i--) {
                        ServiceState ss = commonPkgState.mServices.valueAt(i);
                        if (ss.getProcess() == commonProc) {
                            if (DEBUG) Slog.d(TAG, "GETPROC switching service to cloned: " + ss);
                            ss.setProcess(cloned);
                        } else if (DEBUG) {
                            Slog.d(TAG, "GETPROC leaving proc of " + ss);
                        }
                    }
                    // Also update active associations.
                    for (int i=commonPkgState.mAssociations.size()-1; i>=0; i--) {
                        AssociationState as = commonPkgState.mAssociations.valueAt(i);
                        if (as.getProcess() == commonProc) {
                            if (DEBUG) Slog.d(TAG, "GETPROC switching association to cloned: "
                                    + as);
                            as.setProcess(cloned);
                        } else if (DEBUG) {
                            Slog.d(TAG, "GETPROC leaving proc of " + as);
                        }
                    }
                } else {
                    Slog.w(TAG, "Cloning proc state: no package state " + commonProc.getPackage()
                            + "/" + pkgState.mUid + " for proc " + commonProc.getName());
                }
                // And now make a fresh new process state for the new package name.
                ps = new ProcessState(commonProc, pkgState.mPackageName, pkgState.mUid,
                        pkgState.mVersionCode, processName, now);
                if (DEBUG) Slog.d(TAG, "GETPROC created new pkg " + ps);
            }
        } else {
            // The common process is for multiple packages, we need to create a
            // separate object for the per-package data.
            ps = new ProcessState(commonProc, pkgState.mPackageName, pkgState.mUid,
                    pkgState.mVersionCode, processName,
                    SystemClock.uptimeMillis());
            if (DEBUG) Slog.d(TAG, "GETPROC created new pkg " + ps);
        }
        pkgState.mProcesses.put(processName, ps);
        if (DEBUG) Slog.d(TAG, "GETPROC adding new pkg " + ps);
        return ps;
    }

    public ServiceState getServiceStateLocked(String packageName, int uid, long vers,
            String processName, String className) {
        final ProcessStats.PackageState as = getPackageStateLocked(packageName, uid, vers);
        ServiceState ss = as.mServices.get(className);
        if (ss != null) {
            if (DEBUG) Slog.d(TAG, "GETSVC: returning existing " + ss);
            return ss;
        }
        final ProcessState ps = processName != null
                ? getProcessStateLocked(packageName, uid, vers, processName) : null;
        ss = new ServiceState(this, packageName, className, processName, ps);
        as.mServices.put(className, ss);
        if (DEBUG) Slog.d(TAG, "GETSVC: creating " + ss + " in " + ps);
        return ss;
    }

    public AssociationState getAssociationStateLocked(String packageName, int uid, long vers,
            String processName, String className) {
        final ProcessStats.PackageState pkgs = getPackageStateLocked(packageName, uid, vers);
        AssociationState as = pkgs.mAssociations.get(className);
        if (as != null) {
            if (DEBUG) Slog.d(TAG, "GETASC: returning existing " + as);
            return as;
        }
        final ProcessState procs = processName != null
                ? getProcessStateLocked(packageName, uid, vers, processName) : null;
        as = new AssociationState(this, pkgs, className, processName, procs);
        pkgs.mAssociations.put(className, as);
        if (DEBUG) Slog.d(TAG, "GETASC: creating " + as + " in " + procs);
        return as;
    }

    // See b/118826162 -- to avoid logspaming, we rate limit the warnings.
    private static final long INVERSE_PROC_STATE_WARNING_MIN_INTERVAL_MS = 10_000L;
    private long mNextInverseProcStateWarningUptime;
    private int mSkippedInverseProcStateWarningCount;

    public void updateTrackingAssociationsLocked(int curSeq, long now) {
        final int NUM = mTrackingAssociations.size();
        for (int i = NUM - 1; i >= 0; i--) {
            final AssociationState.SourceState act = mTrackingAssociations.get(i);
            if (act.stopActiveIfNecessary(curSeq, now)) {
                mTrackingAssociations.remove(i);
            } else {
                final AssociationState asc = act.getAssociationState();
                if (asc == null) {
                    Slog.wtf(TAG, act.toString() + " shouldn't be in the tracking list.");
                    continue;
                }
                final ProcessState proc = asc.getProcess();
                if (proc != null) {
                    final int procState = proc.getCombinedState() % STATE_COUNT;
                    if (act.mProcState == procState) {
                        act.startActive(now);
                    } else {
                        act.stopActive(now);
                        if (act.mProcState < procState) {
                            final long nowUptime = SystemClock.uptimeMillis();
                            if (mNextInverseProcStateWarningUptime > nowUptime) {
                                mSkippedInverseProcStateWarningCount++;
                            } else {
                                // TODO We still see it during boot related to GMS-core.
                                // b/118826162
                                Slog.w(TAG, "Tracking association " + act + " whose proc state "
                                        + act.mProcState + " is better than process " + proc
                                        + " proc state " + procState
                                        + " (" +  mSkippedInverseProcStateWarningCount
                                        + " skipped)");
                                mSkippedInverseProcStateWarningCount = 0;
                                mNextInverseProcStateWarningUptime =
                                        nowUptime + INVERSE_PROC_STATE_WARNING_MIN_INTERVAL_MS;
                            }
                        }
                    }
                } else {
                    // Don't need rate limiting on it.
                    Slog.wtf(TAG, "Tracking association without process: " + act
                            + " in " + asc);
                }
            }
        }
    }

    final class AssociationDumpContainer {
        final AssociationState mState;
        ArrayList<Pair<AssociationState.SourceKey, AssociationState.SourceDumpContainer>> mSources;
        long mTotalTime;
        long mActiveTime;

        AssociationDumpContainer(AssociationState state) {
            mState = state;
        }
    }

    static final Comparator<AssociationDumpContainer> ASSOCIATION_COMPARATOR = (o1, o2) -> {
        int diff = o1.mState.getProcessName().compareTo(o2.mState.getProcessName());
        if (diff != 0) {
            return diff;
        }
        if (o1.mActiveTime != o2.mActiveTime) {
            return o1.mActiveTime > o2.mActiveTime ? -1 : 1;
        }
        if (o1.mTotalTime != o2.mTotalTime) {
            return o1.mTotalTime > o2.mTotalTime ? -1 : 1;
        }
        diff = o1.mState.getName().compareTo(o2.mState.getName());
        if (diff != 0) {
            return diff;
        }
        return 0;
    };

    public void dumpLocked(PrintWriter pw, String reqPackage, long now, boolean dumpSummary,
            boolean dumpDetails, boolean dumpAll, boolean activeOnly, int section) {
        long totalTime = DumpUtils.dumpSingleTime(null, null, mMemFactorDurations, mMemFactor,
                mStartTime, now);
        pw.print("          Start time: ");
        pw.print(DateFormat.format("yyyy-MM-dd HH:mm:ss", mTimePeriodStartClock));
        pw.println();
        pw.print("        Total uptime: ");
        TimeUtils.formatDuration(
                (mRunning ? SystemClock.uptimeMillis() : mTimePeriodEndUptime)
                        - mTimePeriodStartUptime, pw);
        pw.println();
        pw.print("  Total elapsed time: ");
        TimeUtils.formatDuration(
                (mRunning ? SystemClock.elapsedRealtime() : mTimePeriodEndRealtime)
                        - mTimePeriodStartRealtime, pw);
        boolean partial = true;
        if ((mFlags & FLAG_SHUTDOWN) != 0) {
            pw.print(" (shutdown)");
            partial = false;
        }
        if ((mFlags & FLAG_SYSPROPS) != 0) {
            pw.print(" (sysprops)");
            partial = false;
        }
        if ((mFlags & FLAG_COMPLETE) != 0) {
            pw.print(" (complete)");
            partial = false;
        }
        if (partial) {
            pw.print(" (partial)");
        }
        if (mHasSwappedOutPss) {
            pw.print(" (swapped-out-pss)");
        }
        pw.print(' ');
        pw.print(mRuntime);
        pw.println();
        pw.print("     Aggregated over: ");
        pw.println(mNumAggregated);
        if (mSysMemUsage.getKeyCount() > 0) {
            pw.println();
            pw.println("System memory usage:");
            mSysMemUsage.dump(pw, "  ", ALL_SCREEN_ADJ, ALL_MEM_ADJ);
        }
        boolean printedHeader = false;
        if ((section & REPORT_PKG_STATS) != 0) {
            ArrayMap<String, SparseArray<LongSparseArray<PackageState>>> pkgMap =
                    mPackages.getMap();
            for (int ip = 0; ip < pkgMap.size(); ip++) {
                final String pkgName = pkgMap.keyAt(ip);
                final SparseArray<LongSparseArray<PackageState>> uids = pkgMap.valueAt(ip);
                for (int iu = 0; iu < uids.size(); iu++) {
                    final int uid = uids.keyAt(iu);
                    final LongSparseArray<PackageState> vpkgs = uids.valueAt(iu);
                    for (int iv = 0; iv < vpkgs.size(); iv++) {
                        final long vers = vpkgs.keyAt(iv);
                        final PackageState pkgState = vpkgs.valueAt(iv);
                        final int NPROCS = pkgState.mProcesses.size();
                        final int NSRVS = pkgState.mServices.size();
                        final int NASCS = pkgState.mAssociations.size();
                        final boolean pkgMatch = reqPackage == null || reqPackage.equals(pkgName);
                        boolean onlyAssociations = false;
                        boolean procMatch = false;
                        if (!pkgMatch) {
                            for (int iproc = 0; iproc < NPROCS; iproc++) {
                                ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                                if (reqPackage.equals(proc.getName())) {
                                    procMatch = true;
                                    break;
                                }
                            }
                            if (!procMatch) {
                                // Check if this app has any associations with the requested
                                // package, so that if so we print those.
                                for (int iasc = 0; iasc < NASCS; iasc++) {
                                    AssociationState asc = pkgState.mAssociations.valueAt(iasc);
                                    if (asc.hasProcessOrPackage(reqPackage)) {
                                        onlyAssociations = true;
                                        break;
                                    }
                                }
                                if (!onlyAssociations) {
                                    continue;
                                }
                            }
                        }
                        if (NPROCS > 0 || NSRVS > 0 || NASCS > 0) {
                            if (!printedHeader) {
                                pw.println();
                                pw.println("Per-Package Stats:");
                                printedHeader = true;
                            }
                            pw.print("  * ");
                            pw.print(pkgName);
                            pw.print(" / ");
                            UserHandle.formatUid(pw, uid);
                            pw.print(" / v");
                            pw.print(vers);
                            pw.println(":");
                        }
                        if ((section & REPORT_PKG_PROC_STATS) != 0 && !onlyAssociations) {
                            if (!dumpSummary || dumpAll) {
                                for (int iproc = 0; iproc < NPROCS; iproc++) {
                                    ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                                    if (!pkgMatch && !reqPackage.equals(proc.getName())) {
                                        continue;
                                    }
                                    if (activeOnly && !proc.isInUse()) {
                                        pw.print("      (Not active: ");
                                        pw.print(pkgState.mProcesses.keyAt(iproc));
                                        pw.println(")");
                                        continue;
                                    }
                                    pw.print("      Process ");
                                    pw.print(pkgState.mProcesses.keyAt(iproc));
                                    if (proc.getCommonProcess().isMultiPackage()) {
                                        pw.print(" (multi, ");
                                    } else {
                                        pw.print(" (unique, ");
                                    }
                                    pw.print(proc.getDurationsBucketCount());
                                    pw.print(" entries)");
                                    pw.println(":");
                                    proc.dumpProcessState(pw, "        ", ALL_SCREEN_ADJ,
                                            ALL_MEM_ADJ,
                                            ALL_PROC_STATES, now);
                                    proc.dumpPss(pw, "        ", ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                                            ALL_PROC_STATES, now);
                                    proc.dumpInternalLocked(pw, "        ", reqPackage,
                                            totalTime, now, dumpAll);
                                }
                            } else {
                                ArrayList<ProcessState> procs = new ArrayList<ProcessState>();
                                for (int iproc = 0; iproc < NPROCS; iproc++) {
                                    ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                                    if (!pkgMatch && !reqPackage.equals(proc.getName())) {
                                        continue;
                                    }
                                    if (activeOnly && !proc.isInUse()) {
                                        continue;
                                    }
                                    procs.add(proc);
                                }
                                DumpUtils.dumpProcessSummaryLocked(pw, "      ", "Prc ", procs,
                                        ALL_SCREEN_ADJ, ALL_MEM_ADJ, NON_CACHED_PROC_STATES,
                                        now, totalTime);
                            }
                        }
                        if ((section & REPORT_PKG_SVC_STATS) != 0 && !onlyAssociations) {
                            for (int isvc = 0; isvc < NSRVS; isvc++) {
                                ServiceState svc = pkgState.mServices.valueAt(isvc);
                                if (!pkgMatch && !reqPackage.equals(svc.getProcessName())) {
                                    continue;
                                }
                                if (activeOnly && !svc.isInUse()) {
                                    pw.print("      (Not active service: ");
                                    pw.print(pkgState.mServices.keyAt(isvc));
                                    pw.println(")");
                                    continue;
                                }
                                if (dumpAll) {
                                    pw.print("      Service ");
                                } else {
                                    pw.print("      * Svc ");
                                }
                                pw.print(pkgState.mServices.keyAt(isvc));
                                pw.println(":");
                                pw.print("        Process: ");
                                pw.println(svc.getProcessName());
                                svc.dumpStats(pw, "        ", "          ", "    ",
                                        now, totalTime, dumpSummary, dumpAll);
                            }
                        }
                        if ((section & REPORT_PKG_ASC_STATS) != 0) {
                            ArrayList<AssociationDumpContainer> associations =
                                    new ArrayList<>(NASCS);
                            for (int iasc = 0; iasc < NASCS; iasc++) {
                                AssociationState asc = pkgState.mAssociations.valueAt(iasc);
                                if (!pkgMatch && !reqPackage.equals(asc.getProcessName())) {
                                    if (!onlyAssociations || !asc.hasProcessOrPackage(reqPackage)) {
                                        continue;
                                    }
                                }
                                final AssociationDumpContainer cont =
                                        new AssociationDumpContainer(asc);
                                cont.mSources = AssociationState
                                        .createSortedAssociations(now, totalTime, asc.mSources);
                                cont.mTotalTime = asc.getTotalDuration(now);
                                cont.mActiveTime = asc.getActiveDuration(now);
                                associations.add(cont);
                            }
                            Collections.sort(associations, ASSOCIATION_COMPARATOR);
                            final int NCONT = associations.size();
                            for (int iasc = 0; iasc < NCONT; iasc++) {
                                final AssociationDumpContainer cont = associations.get(iasc);
                                final AssociationState asc = cont.mState;
                                if (activeOnly && !asc.isInUse()) {
                                    pw.print("      (Not active association: ");
                                    pw.print(pkgState.mAssociations.keyAt(iasc));
                                    pw.println(")");
                                    continue;
                                }
                                if (dumpAll) {
                                    pw.print("      Association ");
                                } else {
                                    pw.print("      * Asc ");
                                }
                                pw.print(cont.mState.getName());
                                pw.println(":");
                                pw.print("        Process: ");
                                pw.println(asc.getProcessName());
                                asc.dumpStats(pw, "        ", "          ", "    ",
                                        cont.mSources, now, totalTime,
                                        onlyAssociations && !pkgMatch && !procMatch
                                                && !asc.getProcessName().equals(reqPackage)
                                                ? reqPackage : null, dumpDetails, dumpAll);
                            }
                        }
                    }
                }
            }
        }

        if ((section & REPORT_PROC_STATS) != 0) {
            ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
            printedHeader = false;
            int numShownProcs = 0, numTotalProcs = 0;
            for (int ip = 0; ip < procMap.size(); ip++) {
                String procName = procMap.keyAt(ip);
                SparseArray<ProcessState> uids = procMap.valueAt(ip);
                for (int iu = 0; iu < uids.size(); iu++) {
                    int uid = uids.keyAt(iu);
                    numTotalProcs++;
                    final ProcessState proc = uids.valueAt(iu);
                    if (!proc.hasAnyData()) {
                        continue;
                    }
                    if (!proc.isMultiPackage()) {
                        continue;
                    }
                    if (reqPackage != null && !reqPackage.equals(procName)
                            && !reqPackage.equals(proc.getPackage())) {
                        continue;
                    }
                    numShownProcs++;
                    pw.println();
                    if (!printedHeader) {
                        pw.println("Multi-Package Common Processes:");
                        printedHeader = true;
                    }
                    if (activeOnly && !proc.isInUse()) {
                        pw.print("      (Not active: ");
                        pw.print(procName);
                        pw.println(")");
                        continue;
                    }
                    pw.print("  * ");
                    pw.print(procName);
                    pw.print(" / ");
                    UserHandle.formatUid(pw, uid);
                    pw.print(" (");
                    pw.print(proc.getDurationsBucketCount());
                    pw.print(" entries)");
                    pw.println(":");
                    proc.dumpProcessState(pw, "        ", ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                            ALL_PROC_STATES, now);
                    proc.dumpPss(pw, "        ", ALL_SCREEN_ADJ, ALL_MEM_ADJ, ALL_PROC_STATES, now);
                    proc.dumpInternalLocked(pw, "        ", reqPackage, totalTime, now, dumpAll);
                }
            }
            pw.print("  Total procs: "); pw.print(numShownProcs);
            pw.print(" shown of "); pw.print(numTotalProcs); pw.println(" total");
        }

        if (dumpAll) {
            pw.println();
            if (mTrackingAssociations.size() > 0) {
                pw.println();
                pw.println("Tracking associations:");
                for (int i = 0; i < mTrackingAssociations.size(); i++) {
                    final AssociationState.SourceState src = mTrackingAssociations.get(i);
                    final AssociationState asc = src.getAssociationState();
                    if (asc == null) {
                        Slog.wtf(TAG, src.toString() + " shouldn't be in the tracking list.");
                        continue;
                    }
                    pw.print("  #");
                    pw.print(i);
                    pw.print(": ");
                    pw.print(asc.getProcessName());
                    pw.print("/");
                    UserHandle.formatUid(pw, asc.getUid());
                    pw.print(" <- ");
                    pw.print(src.getProcessName());
                    pw.print("/");
                    UserHandle.formatUid(pw, src.getUid());
                    pw.println(":");
                    pw.print("    Tracking for: ");
                    TimeUtils.formatDuration(now - src.mTrackingUptime, pw);
                    pw.println();
                    pw.print("    Component: ");
                    pw.print(new ComponentName(asc.getPackage(), asc.getName())
                            .flattenToShortString());
                    pw.println();
                    pw.print("    Proc state: ");
                    if (src.mProcState != ProcessStats.STATE_NOTHING) {
                        pw.print(DumpUtils.STATE_NAMES[src.mProcState]);
                    } else {
                        pw.print("--");
                    }
                    pw.print(" #");
                    pw.println(src.mProcStateSeq);
                    pw.print("    Process: ");
                    pw.println(asc.getProcess());
                    if (src.mActiveCount > 0) {
                        pw.print("    Active count ");
                        pw.print(src.mActiveCount);
                        pw.print(": ");
                        asc.dumpActiveDurationSummary(pw, src, totalTime, now, dumpAll);
                        pw.println();
                    }
                }
            }
        }

        pw.println();
        if (dumpSummary) {
            pw.println("Process summary:");
            dumpSummaryLocked(pw, reqPackage, now, activeOnly);
        } else {
            dumpTotalsLocked(pw, now);
        }

        if (dumpAll) {
            pw.println();
            pw.println("Internal state:");
            /*
            pw.print("  Num long arrays: "); pw.println(mLongs.size());
            pw.print("  Next long entry: "); pw.println(mNextLong);
            */
            pw.print("  mRunning="); pw.println(mRunning);
        }

        if (reqPackage == null) {
            dumpFragmentationLocked(pw);
        }
    }

    public void dumpSummaryLocked(PrintWriter pw, String reqPackage, long now, boolean activeOnly) {
        long totalTime = DumpUtils.dumpSingleTime(null, null, mMemFactorDurations, mMemFactor,
                mStartTime, now);
        dumpFilteredSummaryLocked(pw, null, "  ", null, ALL_SCREEN_ADJ, ALL_MEM_ADJ,
                ALL_PROC_STATES, NON_CACHED_PROC_STATES, now, totalTime, reqPackage, activeOnly);
        pw.println();
        dumpTotalsLocked(pw, now);
    }

    private void dumpFragmentationLocked(PrintWriter pw) {
        pw.println();
        pw.println("Available pages by page size:");
        final int NPAGETYPES = mPageTypeLabels.size();
        for (int i=0; i<NPAGETYPES; i++) {
            pw.format("Node %3d Zone %7s  %14s ", mPageTypeNodes.get(i), mPageTypeZones.get(i),
                    mPageTypeLabels.get(i));
            final int[] sizes = mPageTypeSizes.get(i);
            final int N = sizes == null ? 0 : sizes.length;
            for (int j=0; j<N; j++) {
                pw.format("%6d", sizes[j]);
            }
            pw.println();
        }
    }

    long printMemoryCategory(PrintWriter pw, String prefix, String label, double memWeight,
            long totalTime, long curTotalMem, int samples) {
        if (memWeight != 0) {
            long mem = (long)(memWeight * 1024 / totalTime);
            pw.print(prefix);
            pw.print(label);
            pw.print(": ");
            DebugUtils.printSizeValue(pw, mem);
            pw.print(" (");
            pw.print(samples);
            pw.print(" samples)");
            pw.println();
            return curTotalMem + mem;
        }
        return curTotalMem;
    }

    void dumpTotalsLocked(PrintWriter pw, long now) {
        pw.println("Run time Stats:");
        DumpUtils.dumpSingleTime(pw, "  ", mMemFactorDurations, mMemFactor, mStartTime, now);
        pw.println();
        pw.println("Memory usage:");
        TotalMemoryUseCollection totalMem = new TotalMemoryUseCollection(ALL_SCREEN_ADJ,
                ALL_MEM_ADJ);
        computeTotalMemoryUse(totalMem, now);
        long totalPss = 0;
        totalPss = printMemoryCategory(pw, "  ", "Kernel ", totalMem.sysMemKernelWeight,
                totalMem.totalTime, totalPss, totalMem.sysMemSamples);
        totalPss = printMemoryCategory(pw, "  ", "Native ", totalMem.sysMemNativeWeight,
                totalMem.totalTime, totalPss, totalMem.sysMemSamples);
        for (int i=0; i<STATE_COUNT; i++) {
            // Skip restarting service state -- that is not actually a running process.
            if (i != STATE_SERVICE_RESTARTING) {
                totalPss = printMemoryCategory(pw, "  ", DumpUtils.STATE_NAMES[i],
                        totalMem.processStateWeight[i], totalMem.totalTime, totalPss,
                        totalMem.processStateSamples[i]);
            }
        }
        totalPss = printMemoryCategory(pw, "  ", "Cached ", totalMem.sysMemCachedWeight,
                totalMem.totalTime, totalPss, totalMem.sysMemSamples);
        totalPss = printMemoryCategory(pw, "  ", "Free   ", totalMem.sysMemFreeWeight,
                totalMem.totalTime, totalPss, totalMem.sysMemSamples);
        totalPss = printMemoryCategory(pw, "  ", "Z-Ram  ", totalMem.sysMemZRamWeight,
                totalMem.totalTime, totalPss, totalMem.sysMemSamples);
        pw.print("  TOTAL  : ");
        DebugUtils.printSizeValue(pw, totalPss);
        pw.println();
        printMemoryCategory(pw, "  ", DumpUtils.STATE_NAMES[STATE_SERVICE_RESTARTING],
                totalMem.processStateWeight[STATE_SERVICE_RESTARTING], totalMem.totalTime, totalPss,
                totalMem.processStateSamples[STATE_SERVICE_RESTARTING]);
        pw.println();
        pw.println("PSS collection stats:");
        pw.print("  Internal Single: ");
        pw.print(mInternalSinglePssCount);
        pw.print("x over ");
        TimeUtils.formatDuration(mInternalSinglePssTime, pw);
        pw.println();
        pw.print("  Internal All Procs (Memory Change): ");
        pw.print(mInternalAllMemPssCount);
        pw.print("x over ");
        TimeUtils.formatDuration(mInternalAllMemPssTime, pw);
        pw.println();
        pw.print("  Internal All Procs (Polling): ");
        pw.print(mInternalAllPollPssCount);
        pw.print("x over ");
        TimeUtils.formatDuration(mInternalAllPollPssTime, pw);
        pw.println();
        pw.print("  External: ");
        pw.print(mExternalPssCount);
        pw.print("x over ");
        TimeUtils.formatDuration(mExternalPssTime, pw);
        pw.println();
        pw.print("  External Slow: ");
        pw.print(mExternalSlowPssCount);
        pw.print("x over ");
        TimeUtils.formatDuration(mExternalSlowPssTime, pw);
        pw.println();
    }

    void dumpFilteredSummaryLocked(PrintWriter pw, String header, String prefix, String prcLabel,
            int[] screenStates, int[] memStates, int[] procStates,
            int[] sortProcStates, long now, long totalTime, String reqPackage, boolean activeOnly) {
        ArrayList<ProcessState> procs = collectProcessesLocked(screenStates, memStates,
                procStates, sortProcStates, now, reqPackage, activeOnly);
        if (procs.size() > 0) {
            if (header != null) {
                pw.println();
                pw.println(header);
            }
            DumpUtils.dumpProcessSummaryLocked(pw, prefix, prcLabel, procs, screenStates, memStates,
                    sortProcStates, now, totalTime);
        }
    }

    public ArrayList<ProcessState> collectProcessesLocked(int[] screenStates, int[] memStates,
            int[] procStates, int sortProcStates[], long now, String reqPackage,
            boolean activeOnly) {
        final ArraySet<ProcessState> foundProcs = new ArraySet<ProcessState>();
        final ArrayMap<String, SparseArray<LongSparseArray<PackageState>>> pkgMap =
                mPackages.getMap();
        for (int ip=0; ip<pkgMap.size(); ip++) {
            final String pkgName = pkgMap.keyAt(ip);
            final SparseArray<LongSparseArray<PackageState>> procs = pkgMap.valueAt(ip);
            for (int iu=0; iu<procs.size(); iu++) {
                final LongSparseArray<PackageState> vpkgs = procs.valueAt(iu);
                final int NVERS = vpkgs.size();
                for (int iv=0; iv<NVERS; iv++) {
                    final PackageState state = vpkgs.valueAt(iv);
                    final int NPROCS = state.mProcesses.size();
                    final boolean pkgMatch = reqPackage == null || reqPackage.equals(pkgName);
                    for (int iproc=0; iproc<NPROCS; iproc++) {
                        final ProcessState proc = state.mProcesses.valueAt(iproc);
                        if (!pkgMatch && !reqPackage.equals(proc.getName())) {
                            continue;
                        }
                        if (activeOnly && !proc.isInUse()) {
                            continue;
                        }
                        foundProcs.add(proc.getCommonProcess());
                    }
                }
            }
        }
        ArrayList<ProcessState> outProcs = new ArrayList<ProcessState>(foundProcs.size());
        for (int i=0; i<foundProcs.size(); i++) {
            ProcessState proc = foundProcs.valueAt(i);
            if (proc.computeProcessTimeLocked(screenStates, memStates, procStates, now) > 0) {
                outProcs.add(proc);
                if (procStates != sortProcStates) {
                    proc.computeProcessTimeLocked(screenStates, memStates, sortProcStates, now);
                }
            }
        }
        Collections.sort(outProcs, ProcessState.COMPARATOR);
        return outProcs;
    }

    /**
     * Prints checkin style stats dump.
     */
    public void dumpCheckinLocked(PrintWriter pw, String reqPackage, int section) {
        final long now = SystemClock.uptimeMillis();
        final ArrayMap<String, SparseArray<LongSparseArray<PackageState>>> pkgMap =
                mPackages.getMap();
        pw.println("vers,5");
        pw.print("period,"); pw.print(mTimePeriodStartClockStr);
        pw.print(","); pw.print(mTimePeriodStartRealtime); pw.print(",");
        pw.print(mRunning ? SystemClock.elapsedRealtime() : mTimePeriodEndRealtime);
        boolean partial = true;
        if ((mFlags&FLAG_SHUTDOWN) != 0) {
            pw.print(",shutdown");
            partial = false;
        }
        if ((mFlags&FLAG_SYSPROPS) != 0) {
            pw.print(",sysprops");
            partial = false;
        }
        if ((mFlags&FLAG_COMPLETE) != 0) {
            pw.print(",complete");
            partial = false;
        }
        if (partial) {
            pw.print(",partial");
        }
        if (mHasSwappedOutPss) {
            pw.print(",swapped-out-pss");
        }
        pw.println();
        pw.print("config,"); pw.println(mRuntime);

        if ((section & REPORT_PKG_STATS) != 0) {
            for (int ip = 0; ip < pkgMap.size(); ip++) {
                final String pkgName = pkgMap.keyAt(ip);
                if (reqPackage != null && !reqPackage.equals(pkgName)) {
                    continue;
                }
                final SparseArray<LongSparseArray<PackageState>> uids = pkgMap.valueAt(ip);
                for (int iu = 0; iu < uids.size(); iu++) {
                    final int uid = uids.keyAt(iu);
                    final LongSparseArray<PackageState> vpkgs = uids.valueAt(iu);
                    for (int iv = 0; iv < vpkgs.size(); iv++) {
                        final long vers = vpkgs.keyAt(iv);
                        final PackageState pkgState = vpkgs.valueAt(iv);
                        final int NPROCS = pkgState.mProcesses.size();
                        final int NSRVS = pkgState.mServices.size();
                        final int NASCS = pkgState.mAssociations.size();
                        if ((section & REPORT_PKG_PROC_STATS) != 0) {
                            for (int iproc = 0; iproc < NPROCS; iproc++) {
                                ProcessState proc = pkgState.mProcesses.valueAt(iproc);
                                proc.dumpPackageProcCheckin(pw, pkgName, uid, vers,
                                        pkgState.mProcesses.keyAt(iproc), now);
                            }
                        }
                        if ((section & REPORT_PKG_SVC_STATS) != 0) {
                            for (int isvc = 0; isvc < NSRVS; isvc++) {
                                final String serviceName = DumpUtils.collapseString(pkgName,
                                        pkgState.mServices.keyAt(isvc));
                                final ServiceState svc = pkgState.mServices.valueAt(isvc);
                                svc.dumpTimesCheckin(pw, pkgName, uid, vers, serviceName, now);
                            }
                        }
                        if ((section & REPORT_PKG_ASC_STATS) != 0) {
                            for (int iasc = 0; iasc < NASCS; iasc++) {
                                final String associationName = DumpUtils.collapseString(pkgName,
                                        pkgState.mAssociations.keyAt(iasc));
                                final AssociationState asc = pkgState.mAssociations.valueAt(iasc);
                                asc.dumpTimesCheckin(pw, pkgName, uid, vers, associationName, now);
                            }
                        }
                    }
                }
            }
        }

        if ((section & REPORT_PROC_STATS) != 0) {
            ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
            for (int ip = 0; ip < procMap.size(); ip++) {
                String procName = procMap.keyAt(ip);
                SparseArray<ProcessState> uids = procMap.valueAt(ip);
                for (int iu = 0; iu < uids.size(); iu++) {
                    final int uid = uids.keyAt(iu);
                    final ProcessState procState = uids.valueAt(iu);
                    procState.dumpProcCheckin(pw, procName, uid, now);
                }
            }
        }
        pw.print("total");
        DumpUtils.dumpAdjTimesCheckin(pw, ",", mMemFactorDurations, mMemFactor, mStartTime, now);
        pw.println();
        final int sysMemUsageCount = mSysMemUsage.getKeyCount();
        if (sysMemUsageCount > 0) {
            pw.print("sysmemusage");
            for (int i=0; i<sysMemUsageCount; i++) {
                final int key = mSysMemUsage.getKeyAt(i);
                final int type = SparseMappingTable.getIdFromKey(key);
                pw.print(",");
                DumpUtils.printProcStateTag(pw, type);
                for (int j=SYS_MEM_USAGE_SAMPLE_COUNT; j<SYS_MEM_USAGE_COUNT; j++) {
                    if (j > SYS_MEM_USAGE_CACHED_MINIMUM) {
                        pw.print(":");
                    }
                    pw.print(mSysMemUsage.getValue(key, j));
                }
            }
        }
        pw.println();
        TotalMemoryUseCollection totalMem = new TotalMemoryUseCollection(ALL_SCREEN_ADJ,
                ALL_MEM_ADJ);
        computeTotalMemoryUse(totalMem, now);
        pw.print("weights,");
        pw.print(totalMem.totalTime);
        pw.print(",");
        pw.print(totalMem.sysMemCachedWeight);
        pw.print(":");
        pw.print(totalMem.sysMemSamples);
        pw.print(",");
        pw.print(totalMem.sysMemFreeWeight);
        pw.print(":");
        pw.print(totalMem.sysMemSamples);
        pw.print(",");
        pw.print(totalMem.sysMemZRamWeight);
        pw.print(":");
        pw.print(totalMem.sysMemSamples);
        pw.print(",");
        pw.print(totalMem.sysMemKernelWeight);
        pw.print(":");
        pw.print(totalMem.sysMemSamples);
        pw.print(",");
        pw.print(totalMem.sysMemNativeWeight);
        pw.print(":");
        pw.print(totalMem.sysMemSamples);
        for (int i=0; i<STATE_COUNT; i++) {
            pw.print(",");
            pw.print(totalMem.processStateWeight[i]);
            pw.print(":");
            pw.print(totalMem.processStateSamples[i]);
        }
        pw.println();

        final int NPAGETYPES = mPageTypeLabels.size();
        for (int i=0; i<NPAGETYPES; i++) {
            pw.print("availablepages,");
            pw.print(mPageTypeLabels.get(i));
            pw.print(",");
            pw.print(mPageTypeZones.get(i));
            pw.print(",");
            // Wasn't included in original output.
            //pw.print(mPageTypeNodes.get(i));
            //pw.print(",");
            final int[] sizes = mPageTypeSizes.get(i);
            final int N = sizes == null ? 0 : sizes.length;
            for (int j=0; j<N; j++) {
                if (j != 0) {
                    pw.print(",");
                }
                pw.print(sizes[j]);
            }
            pw.println();
        }
    }

    /**
     * Writes to ProtoOutputStream.
     */
    public void dumpDebug(ProtoOutputStream proto, long now, int section) {
        dumpProtoPreamble(proto);

        final int NPAGETYPES = mPageTypeLabels.size();
        for (int i = 0; i < NPAGETYPES; i++) {
            final long token = proto.start(ProcessStatsSectionProto.AVAILABLE_PAGES);
            proto.write(ProcessStatsAvailablePagesProto.NODE, mPageTypeNodes.get(i));
            proto.write(ProcessStatsAvailablePagesProto.ZONE, mPageTypeZones.get(i));
            proto.write(ProcessStatsAvailablePagesProto.LABEL, mPageTypeLabels.get(i));
            final int[] sizes = mPageTypeSizes.get(i);
            final int N = sizes == null ? 0 : sizes.length;
            for (int j = 0; j < N; j++) {
                proto.write(ProcessStatsAvailablePagesProto.PAGES_PER_ORDER, sizes[j]);
            }
            proto.end(token);
        }

        final ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        if ((section & REPORT_PROC_STATS) != 0) {
            for (int ip = 0; ip < procMap.size(); ip++) {
                final String procName = procMap.keyAt(ip);
                final SparseArray<ProcessState> uids = procMap.valueAt(ip);
                for (int iu = 0; iu < uids.size(); iu++) {
                    final int uid = uids.keyAt(iu);
                    final ProcessState procState = uids.valueAt(iu);
                    procState.dumpDebug(proto, ProcessStatsSectionProto.PROCESS_STATS, procName,
                            uid, now);
                }
            }
        }

        if ((section & REPORT_PKG_STATS) != 0) {
            final ArrayMap<String, SparseArray<LongSparseArray<PackageState>>> pkgMap =
                    mPackages.getMap();
            for (int ip = 0; ip < pkgMap.size(); ip++) {
                final SparseArray<LongSparseArray<PackageState>> uids = pkgMap.valueAt(ip);
                for (int iu = 0; iu < uids.size(); iu++) {
                    final LongSparseArray<PackageState> vers = uids.valueAt(iu);
                    for (int iv = 0; iv < vers.size(); iv++) {
                        final PackageState pkgState = vers.valueAt(iv);
                        pkgState.dumpDebug(proto, ProcessStatsSectionProto.PACKAGE_STATS, now,
                                section);
                    }
                }
            }
        }
    }

    /** Similar to {@code #dumpDebug}, but with a reduced/aggregated subset of states. */
    public void dumpAggregatedProtoForStatsd(ProtoOutputStream[] protoStreams,
            long maxRawShardSizeBytes) {
        int shardIndex = 0;
        dumpProtoPreamble(protoStreams[shardIndex]);

        final ArrayMap<String, SparseArray<ProcessState>> procMap = mProcesses.getMap();
        final ProcessMap<ArraySet<PackageState>> procToPkgMap = new ProcessMap<>();
        final SparseArray<ArraySet<String>> uidToPkgMap = new SparseArray<>();
        collectProcessPackageMaps(null, false, procToPkgMap, uidToPkgMap);

        for (int ip = 0; ip < procMap.size(); ip++) {
            final String procName = procMap.keyAt(ip);
            if (protoStreams[shardIndex].getRawSize() > maxRawShardSizeBytes) {
                shardIndex++;
                if (shardIndex >= protoStreams.length) {
                    // We have run out of space; we'll drop the rest of the processes.
                    Slog.d(TAG, String.format("Dropping process indices from %d to %d from "
                            + "statsd proto (too large)", ip, procMap.size()));
                    break;
                }
                dumpProtoPreamble(protoStreams[shardIndex]);
            }

            final SparseArray<ProcessState> uids = procMap.valueAt(ip);
            for (int iu = 0; iu < uids.size(); iu++) {
                final int uid = uids.keyAt(iu);
                final ProcessState procState = uids.valueAt(iu);
                procState.dumpAggregatedProtoForStatsd(protoStreams[shardIndex],
                        ProcessStatsSectionProto.PROCESS_STATS,
                        procName, uid, mTimePeriodEndRealtime,
                        procToPkgMap, uidToPkgMap);
            }
        }

        for (int i = 0; i <= shardIndex; i++) {
            protoStreams[i].flush();
        }
    }

    private void dumpProtoPreamble(ProtoOutputStream proto) {
        proto.write(ProcessStatsSectionProto.START_REALTIME_MS, mTimePeriodStartRealtime);
        proto.write(ProcessStatsSectionProto.END_REALTIME_MS,
                mRunning ? SystemClock.elapsedRealtime() : mTimePeriodEndRealtime);
        proto.write(ProcessStatsSectionProto.START_UPTIME_MS, mTimePeriodStartUptime);
        proto.write(ProcessStatsSectionProto.END_UPTIME_MS, mTimePeriodEndUptime);
        proto.write(ProcessStatsSectionProto.RUNTIME, mRuntime);
        proto.write(ProcessStatsSectionProto.HAS_SWAPPED_PSS, mHasSwappedOutPss);
        boolean partial = true;
        if ((mFlags & FLAG_SHUTDOWN) != 0) {
            proto.write(ProcessStatsSectionProto.STATUS, ProcessStatsSectionProto.STATUS_SHUTDOWN);
            partial = false;
        }
        if ((mFlags & FLAG_SYSPROPS) != 0) {
            proto.write(ProcessStatsSectionProto.STATUS, ProcessStatsSectionProto.STATUS_SYSPROPS);
            partial = false;
        }
        if ((mFlags & FLAG_COMPLETE) != 0) {
            proto.write(ProcessStatsSectionProto.STATUS, ProcessStatsSectionProto.STATUS_COMPLETE);
            partial = false;
        }
        if (partial) {
            proto.write(ProcessStatsSectionProto.STATUS, ProcessStatsSectionProto.STATUS_PARTIAL);
        }
    }

    /**
     * Walk through the known processes and build up the process -> packages map if necessary.
     */
    private void collectProcessPackageMaps(String reqPackage, boolean activeOnly,
            final ProcessMap<ArraySet<PackageState>> procToPkgMap,
            final SparseArray<ArraySet<String>> uidToPkgMap) {
        final ArrayMap<String, SparseArray<LongSparseArray<PackageState>>> pkgMap =
                mPackages.getMap();
        for (int ip = pkgMap.size() - 1; ip >= 0; ip--) {
            final String pkgName = pkgMap.keyAt(ip);
            final SparseArray<LongSparseArray<PackageState>> procs = pkgMap.valueAt(ip);
            for (int iu = procs.size() - 1; iu >= 0; iu--) {
                final LongSparseArray<PackageState> vpkgs = procs.valueAt(iu);
                for (int iv = vpkgs.size() - 1; iv >= 0; iv--) {
                    final PackageState state = vpkgs.valueAt(iv);
                    final boolean pkgMatch = reqPackage == null || reqPackage.equals(pkgName);
                    for (int iproc = state.mProcesses.size() - 1; iproc >= 0; iproc--) {
                        final ProcessState proc = state.mProcesses.valueAt(iproc);
                        if (!pkgMatch && !reqPackage.equals(proc.getName())) {
                            continue;
                        }
                        if (activeOnly && !proc.isInUse()) {
                            continue;
                        }

                        final String name = proc.getName();
                        final int uid = proc.getUid();
                        ArraySet<PackageState> pkgStates = procToPkgMap.get(name, uid);
                        if (pkgStates == null) {
                            pkgStates = new ArraySet<>();
                            procToPkgMap.put(name, uid, pkgStates);
                        }
                        pkgStates.add(state);
                        ArraySet<String> packages = uidToPkgMap.get(uid);
                        if (packages == null) {
                            packages = new ArraySet<>();
                            uidToPkgMap.put(uid, packages);
                        }
                        packages.add(state.mPackageName);
                    }
                }
            }
        }
    }

    /**
     * Dump the association states related to given process into statsd.
     *
     * <p> Note: Only dump the single-package process state, or the common process state of
     * multi-package process; while the per-package process state of a multi-package process
     * should not be dumped into the statsd due to its incompletion.</p>
     *
     * @param proto     The proto output stream
     * @param fieldId   The proto output field ID
     * @param now       The timestamp when the dump was initiated.
     * @param procState The target process where its association states should be dumped.
     * @param uidToPkgMap The map between UID to packages with this UID
     */
    public void dumpFilteredAssociationStatesProtoForProc(ProtoOutputStream proto,
            long fieldId, long now, ProcessState procState,
            final SparseArray<ArraySet<String>> uidToPkgMap) {
        if (procState.isMultiPackage() && procState.getCommonProcess() != procState) {
            // It's a per-package process state, don't bother to write into statsd
            return;
        }
        final ArrayMap<SourceKey, SourceState> sources = procState.mCommonSources;
        if (sources != null && !sources.isEmpty()) {
            final IProcessStats procStatsService = IProcessStats.Stub.asInterface(
                    ServiceManager.getService(SERVICE_NAME));
            if (procStatsService != null) {
                try {
                    final long minimum = procStatsService.getMinAssociationDumpDuration();
                    for (int i = sources.size() - 1; i >= 0; i--) {
                        final SourceState src = sources.valueAt(i);
                        long duration = src.mDuration;
                        if (src.mNesting > 0) {
                            duration += now - src.mStartUptime;
                        }
                        if (duration < minimum) {
                            continue;
                        }
                        final SourceKey key = sources.keyAt(i);
                        final long token = proto.start(fieldId);
                        final int idx = uidToPkgMap.indexOfKey(key.mUid);
                        ProcessState.writeCompressedProcessName(proto,
                                ProcessStatsAssociationProto.ASSOC_PROCESS_NAME,
                                key.mProcess, key.mPackage,
                                idx >= 0 && uidToPkgMap.valueAt(idx).size() > 1);
                        proto.write(ProcessStatsAssociationProto.ASSOC_UID, key.mUid);
                        proto.write(ProcessStatsAssociationProto.TOTAL_COUNT, src.mCount);
                        proto.write(ProcessStatsAssociationProto.TOTAL_DURATION_SECS,
                                (int) (duration / 1000));
                        proto.end(token);
                    }
                } catch (RemoteException e) {
                    // ignore.
                }
            }
        }
    }

    final public static class ProcessStateHolder {
        public final long appVersion;
        public ProcessState state;
        public PackageState pkg;

        public ProcessStateHolder(long _appVersion) {
            appVersion = _appVersion;
        }
    }

    public static final class PackageState {
        public final ProcessStats mProcessStats;
        public final ArrayMap<String, ProcessState> mProcesses = new ArrayMap<>();
        public final ArrayMap<String, ServiceState> mServices = new ArrayMap<>();
        public final ArrayMap<String, AssociationState> mAssociations = new ArrayMap<>();
        public final String mPackageName;
        public final int mUid;
        public final long mVersionCode;

        public PackageState(ProcessStats procStats, String packageName, int uid, long versionCode) {
            mProcessStats = procStats;
            mUid = uid;
            mPackageName = packageName;
            mVersionCode = versionCode;
        }

        public AssociationState getAssociationStateLocked(ProcessState proc, String className) {
            AssociationState as = mAssociations.get(className);
            if (as != null) {
                if (DEBUG) Slog.d(TAG, "GETASC: returning existing " + as);
                if (proc != null) {
                    as.setProcess(proc);
                }
                return as;
            }
            as = new AssociationState(mProcessStats, this, className, proc.getName(),
                    proc);
            mAssociations.put(className, as);
            if (DEBUG) Slog.d(TAG, "GETASC: creating " + as + " in " + proc.getName());
            return as;
        }

        /**
         * Writes the containing stats into proto, with options to choose smaller sections.
         */
        public void dumpDebug(ProtoOutputStream proto, long fieldId, long now, int section) {
            final long token = proto.start(fieldId);

            proto.write(ProcessStatsPackageProto.PACKAGE, mPackageName);
            proto.write(ProcessStatsPackageProto.UID, mUid);
            proto.write(ProcessStatsPackageProto.VERSION, mVersionCode);

            if ((section & ProcessStats.REPORT_PKG_PROC_STATS) != 0) {
                for (int ip = 0; ip < mProcesses.size(); ip++) {
                    final String procName = mProcesses.keyAt(ip);
                    final ProcessState procState = mProcesses.valueAt(ip);
                    procState.dumpDebug(proto, ProcessStatsPackageProto.PROCESS_STATS, procName,
                            mUid, now);
                }
            }

            if ((section & ProcessStats.REPORT_PKG_SVC_STATS) != 0) {
                for (int is = 0; is < mServices.size(); is++) {
                    final ServiceState serviceState = mServices.valueAt(is);
                    serviceState.dumpDebug(proto, ProcessStatsPackageProto.SERVICE_STATS,
                            now);
                }
            }

            if ((section & ProcessStats.REPORT_PKG_ASC_STATS) != 0) {
                for (int ia = 0; ia < mAssociations.size(); ia++) {
                    final AssociationState ascState = mAssociations.valueAt(ia);
                    ascState.dumpDebug(proto, ProcessStatsPackageProto.ASSOCIATION_STATS,
                            now);
                }
            }

            proto.end(token);
        }
    }

    public static final class ProcessDataCollection {
        final int[] screenStates;
        final int[] memStates;
        final int[] procStates;

        public long totalTime;
        public long numPss;
        public long minPss;
        public long avgPss;
        public long maxPss;
        public long minUss;
        public long avgUss;
        public long maxUss;
        public long minRss;
        public long avgRss;
        public long maxRss;

        public ProcessDataCollection(int[] _screenStates, int[] _memStates, int[] _procStates) {
            screenStates = _screenStates;
            memStates = _memStates;
            procStates = _procStates;
        }

        void print(PrintWriter pw, long overallTime, boolean full) {
            if (totalTime > overallTime) {
                pw.print("*");
            }
            DumpUtils.printPercent(pw, (double) totalTime / (double) overallTime);
            if (numPss > 0) {
                pw.print(" (");
                DebugUtils.printSizeValue(pw, minPss * 1024);
                pw.print("-");
                DebugUtils.printSizeValue(pw, avgPss * 1024);
                pw.print("-");
                DebugUtils.printSizeValue(pw, maxPss * 1024);
                pw.print("/");
                DebugUtils.printSizeValue(pw, minUss * 1024);
                pw.print("-");
                DebugUtils.printSizeValue(pw, avgUss * 1024);
                pw.print("-");
                DebugUtils.printSizeValue(pw, maxUss * 1024);
                pw.print("/");
                DebugUtils.printSizeValue(pw, minRss * 1024);
                pw.print("-");
                DebugUtils.printSizeValue(pw, avgRss * 1024);
                pw.print("-");
                DebugUtils.printSizeValue(pw, maxRss * 1024);
                if (full) {
                    pw.print(" over ");
                    pw.print(numPss);
                }
                pw.print(")");
            }
        }
    }

    public static class TotalMemoryUseCollection {
        final int[] screenStates;
        final int[] memStates;

        public TotalMemoryUseCollection(int[] _screenStates, int[] _memStates) {
            screenStates = _screenStates;
            memStates = _memStates;
        }

        public long totalTime;
        public long[] processStatePss = new long[STATE_COUNT];
        public double[] processStateWeight = new double[STATE_COUNT];
        public long[] processStateTime = new long[STATE_COUNT];
        public int[] processStateSamples = new int[STATE_COUNT];
        public long[] sysMemUsage = new long[SYS_MEM_USAGE_COUNT];
        public double sysMemCachedWeight;
        public double sysMemFreeWeight;
        public double sysMemZRamWeight;
        public double sysMemKernelWeight;
        public double sysMemNativeWeight;
        public int sysMemSamples;
        public boolean hasSwappedOutPss;
    }

}
