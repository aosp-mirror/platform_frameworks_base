/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_COMPACTION;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_FREEZER;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.ApplicationExitInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.ProcLocksReader;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.ServiceThread;

import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class CachedAppOptimizer {

    // Flags stored in the DeviceConfig API.
    @VisibleForTesting static final String KEY_USE_COMPACTION = "use_compaction";
    @VisibleForTesting static final String KEY_USE_FREEZER = "use_freezer";
    @VisibleForTesting static final String KEY_COMPACT_ACTION_1 = "compact_action_1";
    @VisibleForTesting static final String KEY_COMPACT_ACTION_2 = "compact_action_2";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_1 = "compact_throttle_1";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_2 = "compact_throttle_2";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_3 = "compact_throttle_3";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_4 = "compact_throttle_4";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_5 = "compact_throttle_5";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_6 = "compact_throttle_6";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_MIN_OOM_ADJ =
            "compact_throttle_min_oom_adj";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_MAX_OOM_ADJ =
            "compact_throttle_max_oom_adj";
    @VisibleForTesting static final String KEY_COMPACT_STATSD_SAMPLE_RATE =
            "compact_statsd_sample_rate";
    @VisibleForTesting static final String KEY_FREEZER_STATSD_SAMPLE_RATE =
            "freeze_statsd_sample_rate";
    @VisibleForTesting static final String KEY_COMPACT_FULL_RSS_THROTTLE_KB =
            "compact_full_rss_throttle_kb";
    @VisibleForTesting static final String KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB =
            "compact_full_delta_rss_throttle_kb";
    @VisibleForTesting static final String KEY_COMPACT_PROC_STATE_THROTTLE =
            "compact_proc_state_throttle";
    @VisibleForTesting static final String KEY_FREEZER_DEBOUNCE_TIMEOUT =
            "freeze_debounce_timeout";

    // RSS Indices
    private static final int RSS_TOTAL_INDEX = 0;
    private static final int RSS_FILE_INDEX = 1;
    private static final int RSS_ANON_INDEX = 2;
    private static final int RSS_SWAP_INDEX = 3;

    // Phenotype sends int configurations and we map them to the strings we'll use on device,
    // preventing a weird string value entering the kernel.
    private static final int COMPACT_ACTION_NONE = 0;
    private static final int COMPACT_ACTION_FILE = 1;
    private static final int COMPACT_ACTION_ANON = 2;
    private static final int COMPACT_ACTION_FULL = 3;

    private static final String COMPACT_ACTION_STRING[] = {"", "file", "anon", "all"};

    // Keeps these flags in sync with services/core/jni/com_android_server_am_CachedAppOptimizer.cpp
    private static final int COMPACT_ACTION_FILE_FLAG = 1;
    private static final int COMPACT_ACTION_ANON_FLAG = 2;

    private static final String ATRACE_COMPACTION_TRACK = "Compaction";

    // Defaults for phenotype flags.
    @VisibleForTesting static final Boolean DEFAULT_USE_COMPACTION = false;
    @VisibleForTesting static final Boolean DEFAULT_USE_FREEZER = true;
    @VisibleForTesting static final int DEFAULT_COMPACT_ACTION_2 = COMPACT_ACTION_FULL;
    @VisibleForTesting static final int DEFAULT_COMPACT_ACTION_1 = COMPACT_ACTION_FILE;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_1 = 5_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_2 = 10_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_3 = 500;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_4 = 10_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_5 = 10 * 60 * 1000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_6 = 10 * 60 * 1000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ =
            ProcessList.CACHED_APP_MIN_ADJ;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ =
            ProcessList.CACHED_APP_MAX_ADJ;
    // The sampling rate to push app compaction events into statsd for upload.
    @VisibleForTesting static final float DEFAULT_STATSD_SAMPLE_RATE = 0.1f;
    @VisibleForTesting static final long DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB = 12_000L;
    @VisibleForTesting static final long DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB = 8_000L;
    // Format of this string should be a comma separated list of integers.
    @VisibleForTesting static final String DEFAULT_COMPACT_PROC_STATE_THROTTLE =
            String.valueOf(ActivityManager.PROCESS_STATE_RECEIVER);
    @VisibleForTesting static final long DEFAULT_FREEZER_DEBOUNCE_TIMEOUT = 600_000L;

    @VisibleForTesting static final Uri CACHED_APP_FREEZER_ENABLED_URI = Settings.Global.getUriFor(
                Settings.Global.CACHED_APPS_FREEZER_ENABLED);

    @VisibleForTesting
    interface PropertyChangedCallbackForTest {
        void onPropertyChanged();
    }
    private PropertyChangedCallbackForTest mTestCallback;

    // This interface is for functions related to the Process object that need a different
    // implementation in the tests as we are not creating real processes when testing compaction.
    @VisibleForTesting
    interface ProcessDependencies {
        long[] getRss(int pid);
        void performCompaction(String action, int pid) throws IOException;
    }

    // Handler constants.
    static final int COMPACT_PROCESS_SOME = 1;
    static final int COMPACT_PROCESS_FULL = 2;
    static final int COMPACT_PROCESS_PERSISTENT = 3;
    static final int COMPACT_PROCESS_BFGS = 4;
    static final int COMPACT_PROCESS_MSG = 1;
    static final int COMPACT_SYSTEM_MSG = 2;
    static final int SET_FROZEN_PROCESS_MSG = 3;
    static final int REPORT_UNFREEZE_MSG = 4;

    // When free swap falls below this percentage threshold any full (file + anon)
    // compactions will be downgraded to file only compactions to reduce pressure
    // on swap resources as file.
    static final double COMPACT_DOWNGRADE_FREE_SWAP_THRESHOLD = 0.2;

    static final int DO_FREEZE = 1;
    static final int REPORT_UNFREEZE = 2;

    // Bitfield values for sync/async transactions reveived by frozen processes
    static final int SYNC_RECEIVED_WHILE_FROZEN = 1;
    static final int ASYNC_RECEIVED_WHILE_FROZEN = 2;

    // Bitfield values for sync transactions received by frozen binder threads
    static final int TXNS_PENDING_WHILE_FROZEN = 4;

    /**
     * This thread must be moved to the system background cpuset.
     * If that doesn't happen, it's probably going to draw a lot of power.
     * However, this has to happen after the first updateOomAdjLocked, because
     * that will wipe out the cpuset assignment for system_server threads.
     * Accordingly, this is in the AMS constructor.
     */
    final ServiceThread mCachedAppOptimizerThread;

    @GuardedBy("mProcLock")
    private final ArrayList<ProcessRecord> mPendingCompactionProcesses =
            new ArrayList<ProcessRecord>();

    @GuardedBy("mProcLock")
    private final SparseArray<ProcessRecord> mFrozenProcesses =
            new SparseArray<>();

    private final ActivityManagerService mAm;

    private final ActivityManagerGlobalLock mProcLock;

    private final Object mFreezerLock = new Object();

    private final OnPropertiesChangedListener mOnFlagsChangedListener =
            new OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(Properties properties) {
                    synchronized (mPhenotypeFlagLock) {
                        for (String name : properties.getKeyset()) {
                            if (KEY_USE_COMPACTION.equals(name)) {
                                updateUseCompaction();
                            } else if (KEY_COMPACT_ACTION_1.equals(name)
                                    || KEY_COMPACT_ACTION_2.equals(name)) {
                                updateCompactionActions();
                            } else if (KEY_COMPACT_THROTTLE_1.equals(name)
                                    || KEY_COMPACT_THROTTLE_2.equals(name)
                                    || KEY_COMPACT_THROTTLE_3.equals(name)
                                    || KEY_COMPACT_THROTTLE_4.equals(name)
                                    || KEY_COMPACT_THROTTLE_5.equals(name)
                                    || KEY_COMPACT_THROTTLE_6.equals(name)) {
                                updateCompactionThrottles();
                            } else if (KEY_COMPACT_STATSD_SAMPLE_RATE.equals(name)) {
                                updateCompactStatsdSampleRate();
                            } else if (KEY_FREEZER_STATSD_SAMPLE_RATE.equals(name)) {
                                updateFreezerStatsdSampleRate();
                            } else if (KEY_COMPACT_FULL_RSS_THROTTLE_KB.equals(name)) {
                                updateFullRssThrottle();
                            } else if (KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB.equals(name)) {
                                updateFullDeltaRssThrottle();
                            } else if (KEY_COMPACT_PROC_STATE_THROTTLE.equals(name)) {
                                updateProcStateThrottle();
                            } else if (KEY_COMPACT_THROTTLE_MIN_OOM_ADJ.equals(name)) {
                                updateMinOomAdjThrottle();
                            } else if (KEY_COMPACT_THROTTLE_MAX_OOM_ADJ.equals(name)) {
                                updateMaxOomAdjThrottle();
                            }
                        }
                    }
                    if (mTestCallback != null) {
                        mTestCallback.onPropertyChanged();
                    }
                }
            };

    private final OnPropertiesChangedListener mOnNativeBootFlagsChangedListener =
            new OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(Properties properties) {
                    synchronized (mPhenotypeFlagLock) {
                        for (String name : properties.getKeyset()) {
                            if (KEY_FREEZER_DEBOUNCE_TIMEOUT.equals(name)) {
                                updateFreezerDebounceTimeout();
                            }
                        }
                    }
                    if (mTestCallback != null) {
                        mTestCallback.onPropertyChanged();
                    }
                }
            };

    private final class SettingsContentObserver extends ContentObserver {
        SettingsContentObserver() {
            super(mAm.mHandler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (CACHED_APP_FREEZER_ENABLED_URI.equals(uri)) {
                synchronized (mPhenotypeFlagLock) {
                    updateUseFreezer();
                }
            }
        }
    }

    private final SettingsContentObserver mSettingsObserver;

    private final Object mPhenotypeFlagLock = new Object();

    // Configured by phenotype. Updates from the server take effect immediately.
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile String mCompactActionSome =
            compactActionIntToString(DEFAULT_COMPACT_ACTION_1);
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile String mCompactActionFull =
            compactActionIntToString(DEFAULT_COMPACT_ACTION_2);
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleSomeSome = DEFAULT_COMPACT_THROTTLE_1;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleSomeFull = DEFAULT_COMPACT_THROTTLE_2;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleFullSome = DEFAULT_COMPACT_THROTTLE_3;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleFullFull = DEFAULT_COMPACT_THROTTLE_4;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleBFGS = DEFAULT_COMPACT_THROTTLE_5;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottlePersistent = DEFAULT_COMPACT_THROTTLE_6;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleMinOomAdj =
            DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mCompactThrottleMaxOomAdj =
            DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ;
    @GuardedBy("mPhenotypeFlagLock")
    private volatile boolean mUseCompaction = DEFAULT_USE_COMPACTION;
    private volatile boolean mUseFreezer = false; // set to DEFAULT in init()
    @GuardedBy("this")
    private int mFreezerDisableCount = 1; // Freezer is initially disabled, until enabled
    private final Random mRandom = new Random();
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile float mCompactStatsdSampleRate = DEFAULT_STATSD_SAMPLE_RATE;
    @VisibleForTesting volatile float mFreezerStatsdSampleRate = DEFAULT_STATSD_SAMPLE_RATE;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mFullAnonRssThrottleKb =
            DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mFullDeltaRssThrottleKb =
            DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting final Set<Integer> mProcStateThrottle;

    // Handler on which compaction runs.
    @VisibleForTesting
    Handler mCompactionHandler;
    private Handler mFreezeHandler;
    @GuardedBy("mProcLock")
    private boolean mFreezerOverride = false;

    @VisibleForTesting volatile long mFreezerDebounceTimeout = DEFAULT_FREEZER_DEBOUNCE_TIMEOUT;

    // Maps process ID to last compaction statistics for processes that we've fully compacted. Used
    // when evaluating throttles that we only consider for "full" compaction, so we don't store
    // data for "some" compactions. Uses LinkedHashMap to ensure insertion order is kept and
    // facilitate removal of the oldest entry.
    @VisibleForTesting
    @GuardedBy("mProcLock")
    LinkedHashMap<Integer, LastCompactionStats> mLastCompactionStats =
            new LinkedHashMap<Integer, LastCompactionStats>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > 100;
                }
    };

    // Compaction Stats
    private int mSomeCompactionCount;
    private int mFullCompactionCount;
    private int mPersistentCompactionCount;
    private int mBfgsCompactionCount;
    private long mSomeCompactRequest;
    private long mFullCompactRequest;
    private long mPersistentCompactRequest;
    private long mBfgsCompactRequest;
    private long mProcCompactionsRequested;
    private long mProcCompactionsPerformed;
    private long mProcCompactionsNoPidThrottled;
    private long mProcCompactionsOomAdjThrottled;
    private long mProcCompactionsTimeThrottled;
    private long mProcCompactionsRSSThrottled;
    private long mProcCompactionsMiscThrottled;
    private long mSystemCompactionsPerformed;

    private final ProcessDependencies mProcessDependencies;
    private final ProcLocksReader mProcLocksReader;

    public CachedAppOptimizer(ActivityManagerService am) {
        this(am, null, new DefaultProcessDependencies());
    }

    @VisibleForTesting
    CachedAppOptimizer(ActivityManagerService am, PropertyChangedCallbackForTest callback,
            ProcessDependencies processDependencies) {
        mAm = am;
        mProcLock = am.mProcLock;
        mCachedAppOptimizerThread = new ServiceThread("CachedAppOptimizerThread",
            Process.THREAD_GROUP_SYSTEM, true);
        mProcStateThrottle = new HashSet<>();
        mProcessDependencies = processDependencies;
        mTestCallback = callback;
        mSettingsObserver = new SettingsContentObserver();
        mProcLocksReader = new ProcLocksReader();
    }

    /**
     * Reads phenotype config to determine whether app compaction is enabled or not and
     * starts the background thread if necessary.
     */
    public void init() {
        // TODO: initialize flags to default and only update them if values are set in DeviceConfig
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(), mOnFlagsChangedListener);
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                ActivityThread.currentApplication().getMainExecutor(),
                mOnNativeBootFlagsChangedListener);
        mAm.mContext.getContentResolver().registerContentObserver(
                CACHED_APP_FREEZER_ENABLED_URI, false, mSettingsObserver);
        synchronized (mPhenotypeFlagLock) {
            updateUseCompaction();
            updateCompactionActions();
            updateCompactionThrottles();
            updateCompactStatsdSampleRate();
            updateFreezerStatsdSampleRate();
            updateFullRssThrottle();
            updateFullDeltaRssThrottle();
            updateProcStateThrottle();
            updateUseFreezer();
            updateMinOomAdjThrottle();
            updateMaxOomAdjThrottle();
        }
    }

    /**
     * Returns whether compaction is enabled.
     */
    public boolean useCompaction() {
        synchronized (mPhenotypeFlagLock) {
            return mUseCompaction;
        }
    }

    /**
     * Returns whether freezer is enabled.
     */
    public boolean useFreezer() {
        synchronized (mPhenotypeFlagLock) {
            return mUseFreezer;
        }
    }

    @GuardedBy("mProcLock")
    void dump(PrintWriter pw) {
        pw.println("CachedAppOptimizer settings");
        synchronized (mPhenotypeFlagLock) {
            pw.println("  " + KEY_USE_COMPACTION + "=" + mUseCompaction);
            pw.println("  " + KEY_COMPACT_ACTION_1 + "=" + mCompactActionSome);
            pw.println("  " + KEY_COMPACT_ACTION_2 + "=" + mCompactActionFull);
            pw.println("  " + KEY_COMPACT_THROTTLE_1 + "=" + mCompactThrottleSomeSome);
            pw.println("  " + KEY_COMPACT_THROTTLE_2 + "=" + mCompactThrottleSomeFull);
            pw.println("  " + KEY_COMPACT_THROTTLE_3 + "=" + mCompactThrottleFullSome);
            pw.println("  " + KEY_COMPACT_THROTTLE_4 + "=" + mCompactThrottleFullFull);
            pw.println("  " + KEY_COMPACT_THROTTLE_5 + "=" + mCompactThrottleBFGS);
            pw.println("  " + KEY_COMPACT_THROTTLE_6 + "=" + mCompactThrottlePersistent);
            pw.println("  " + KEY_COMPACT_THROTTLE_MIN_OOM_ADJ + "=" + mCompactThrottleMinOomAdj);
            pw.println("  " + KEY_COMPACT_THROTTLE_MAX_OOM_ADJ + "=" + mCompactThrottleMaxOomAdj);
            pw.println("  " + KEY_COMPACT_STATSD_SAMPLE_RATE + "=" + mCompactStatsdSampleRate);
            pw.println("  " + KEY_COMPACT_FULL_RSS_THROTTLE_KB + "="
                    + mFullAnonRssThrottleKb);
            pw.println("  " + KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + "="
                    + mFullDeltaRssThrottleKb);
            pw.println("  "  + KEY_COMPACT_PROC_STATE_THROTTLE + "="
                    + Arrays.toString(mProcStateThrottle.toArray(new Integer[0])));

            pw.println(" Requested:  " + mSomeCompactRequest + " some, " + mFullCompactRequest
                    + " full, " + mPersistentCompactRequest + " persistent, "
                    + mBfgsCompactRequest + " BFGS compactions.");
            pw.println(" Performed: " + mSomeCompactionCount + " some, " + mFullCompactionCount
                    + " full, " + mPersistentCompactionCount + " persistent, "
                    + mBfgsCompactionCount + " BFGS compactions.");
            pw.println(" Process Compactions Requested: " + mProcCompactionsRequested);
            pw.println(" Process Compactions Performed: " + mProcCompactionsPerformed);
            long compactionsThrottled = mProcCompactionsRequested - mProcCompactionsPerformed;
            pw.println(" Process Compactions Throttled: " + compactionsThrottled);
            double compactThrottlePercentage =
                    (compactionsThrottled / (double) mProcCompactionsRequested) * 100.0;
            pw.println(" Process Compactions Throttle Percentage: " + compactThrottlePercentage);
            pw.println("        NoPid Throttled: " + mProcCompactionsNoPidThrottled);
            pw.println("        OomAdj Throttled: " + mProcCompactionsOomAdjThrottled);
            pw.println("        Time Throttled: " + mProcCompactionsTimeThrottled);
            pw.println("        RSS Throttled: " + mProcCompactionsRSSThrottled);
            pw.println("        Misc Throttled: " + mProcCompactionsMiscThrottled);
            long unaccountedThrottled = compactionsThrottled - mProcCompactionsNoPidThrottled
                    - mProcCompactionsOomAdjThrottled - mProcCompactionsTimeThrottled
                    - mProcCompactionsRSSThrottled - mProcCompactionsMiscThrottled;
            // Any throttle that was not part of the previous categories
            pw.println("        Unaccounted Throttled: " + unaccountedThrottled);

            pw.println(" System Compactions Performed: " + mSystemCompactionsPerformed);

            pw.println("  Tracking last compaction stats for " + mLastCompactionStats.size()
                    + " processes.");
            pw.println("  " + KEY_USE_FREEZER + "=" + mUseFreezer);
            pw.println("  " + KEY_FREEZER_STATSD_SAMPLE_RATE + "=" + mFreezerStatsdSampleRate);
            pw.println("  " + KEY_FREEZER_DEBOUNCE_TIMEOUT + "=" + mFreezerDebounceTimeout);
            synchronized (mProcLock) {
                int size = mFrozenProcesses.size();
                pw.println("  Apps frozen: " + size);
                for (int i = 0; i < size; i++) {
                    ProcessRecord app = mFrozenProcesses.valueAt(i);
                    pw.println("    " + app.mOptRecord.getFreezeUnfreezeTime()
                            + ": " + app.getPid() + " " + app.processName);
                }

                if (!mPendingCompactionProcesses.isEmpty()) {
                    pw.println("  Pending compactions:");
                    size = mPendingCompactionProcesses.size();
                    for (int i = 0; i < size; i++) {
                        ProcessRecord app = mPendingCompactionProcesses.get(i);
                        pw.println("    pid: " + app.getPid() + ". name: " + app.processName
                                + ". hasPendingCompact: " + app.mOptRecord.hasPendingCompact());
                    }
                }
            }
            if (DEBUG_COMPACTION) {
                for (Map.Entry<Integer, LastCompactionStats> entry
                        : mLastCompactionStats.entrySet()) {
                    int pid = entry.getKey();
                    LastCompactionStats stats = entry.getValue();
                    pw.println("    " + pid + ": "
                            + Arrays.toString(stats.getRssAfterCompaction()));
                }
            }
        }
    }

    @GuardedBy("mProcLock")
    void compactAppSome(ProcessRecord app, boolean force) {
        app.mOptRecord.setReqCompactAction(COMPACT_PROCESS_SOME);
        ++mSomeCompactRequest;
        compactApp(app, force, "some");
    }

    // This method returns true only if requirements are met. Note, that requirements are different
    // from throttles applied at the time a compaction is trying to be executed in the sense that
    // these are not subject to change dependent on time or memory as throttles usually do.
    @GuardedBy("mProcLock")
    boolean meetsCompactionRequirements(ProcessRecord proc) {
        if (mAm.mInternal.isPendingTopUid(proc.uid)) {
            // In case the OOM Adjust has not yet been propagated we see if this is
            // pending on becoming top app in which case we should not compact.
            if (DEBUG_COMPACTION) {
                Slog.d(TAG_AM, "Skip compaction since UID is active for  " + proc.processName);
            }
            return false;
        }

        if (proc.mState.hasForegroundActivities()) {
            if (DEBUG_COMPACTION) {
                Slog.e(TAG_AM,
                        "Skip compaction as process " + proc.processName
                                + " has foreground activities");
            }
            return false;
        }

        return true;
    }

    @GuardedBy("mProcLock")
    void compactAppFull(ProcessRecord app, boolean force) {
        boolean oomAdjEnteredCached = (app.mState.getSetAdj() < mCompactThrottleMinOomAdj
                                              || app.mState.getSetAdj() > mCompactThrottleMaxOomAdj)
                && app.mState.getCurAdj() >= mCompactThrottleMinOomAdj
                && app.mState.getCurAdj() <= mCompactThrottleMaxOomAdj;
        if (DEBUG_COMPACTION) {
            Slog.d(TAG_AM,
                    " compactAppFull requested for " + app.processName + " force: " + force
                            + " oomAdjEnteredCached: " + oomAdjEnteredCached);
        }
        ++mFullCompactRequest;
        // Apply OOM adj score throttle for Full App Compaction.
        if (force || oomAdjEnteredCached) {
            app.mOptRecord.setReqCompactAction(COMPACT_PROCESS_FULL);
            compactApp(app, force, "Full");
        } else {
            if (DEBUG_COMPACTION) {
                Slog.d(TAG_AM, "Skipping full compaction for " + app.processName
                        + " oom adj score changed from " + app.mState.getSetAdj()
                        + " to " + app.mState.getCurAdj());
            }
        }
    }

    @GuardedBy("mProcLock")
    void compactAppPersistent(ProcessRecord app) {
        app.mOptRecord.setReqCompactAction(COMPACT_PROCESS_PERSISTENT);
        ++mPersistentCompactRequest;
        compactApp(app, false, "Persistent");
    }

    @GuardedBy("mProcLock")
    boolean compactApp(ProcessRecord app, boolean force, String compactRequestType) {
        if (!app.mOptRecord.hasPendingCompact() && meetsCompactionRequirements(app)) {
            final String processName = (app.processName != null ? app.processName : "");
            if (DEBUG_COMPACTION) {
                Slog.d(TAG_AM, "compactApp " + compactRequestType + " " + processName);
            }
            Trace.instantForTrack(Trace.TRACE_TAG_ACTIVITY_MANAGER, ATRACE_COMPACTION_TRACK,
                    "compactApp " + compactRequestType + " " + processName);
            app.mOptRecord.setHasPendingCompact(true);
            app.mOptRecord.setForceCompact(force);
            mPendingCompactionProcesses.add(app);
            mCompactionHandler.sendMessage(mCompactionHandler.obtainMessage(
                    COMPACT_PROCESS_MSG, app.mState.getCurAdj(), app.mState.getSetProcState()));
            return true;
        }

        if (DEBUG_COMPACTION) {
            Slog.d(TAG_AM,
                    " compactApp Skipped for " + app.processName
                            + " pendingCompact= " + app.mOptRecord.hasPendingCompact()
                            + " meetsCompactionRequirements=" + meetsCompactionRequirements(app)
                            + ". Requested compact: " + app.mOptRecord.getReqCompactAction());
        }
        return false;
    }

    @GuardedBy("mProcLock")
    boolean shouldCompactPersistent(ProcessRecord app, long now) {
        return (app.mOptRecord.getLastCompactTime() == 0
                || (now - app.mOptRecord.getLastCompactTime()) > mCompactThrottlePersistent);
    }

    @GuardedBy("mProcLock")
    void compactAppBfgs(ProcessRecord app) {
        ++mBfgsCompactRequest;
        app.mOptRecord.setReqCompactAction(COMPACT_PROCESS_BFGS);
        compactApp(app, false, " Bfgs");
    }

    @GuardedBy("mProcLock")
    boolean shouldCompactBFGS(ProcessRecord app, long now) {
        return (app.mOptRecord.getLastCompactTime() == 0
                || (now - app.mOptRecord.getLastCompactTime()) > mCompactThrottleBFGS);
    }

    void compactAllSystem() {
        if (useCompaction()) {
            if (DEBUG_COMPACTION) {
                Slog.d(TAG_AM, "compactAllSystem");
            }
            Trace.instantForTrack(
                    Trace.TRACE_TAG_ACTIVITY_MANAGER, ATRACE_COMPACTION_TRACK, "compactAllSystem");
            mCompactionHandler.sendMessage(mCompactionHandler.obtainMessage(
                                              COMPACT_SYSTEM_MSG));
        }
    }

    private native void compactSystem();

    /**
     * Compacts a process or app
     * @param pid pid of process to compact
     * @param compactionFlags selects the compaction type as defined by COMPACT_ACTION_{TYPE}_FLAG
     *         constants
     */
    static private native void compactProcess(int pid, int compactionFlags);

    static private native void cancelCompaction();

    /**
     * Retrieves the free swap percentage.
     */
    static native double getFreeSwapPercent();

    /**
     * Reads the flag value from DeviceConfig to determine whether app compaction
     * should be enabled, and starts the freeze/compaction thread if needed.
     */
    @GuardedBy("mPhenotypeFlagLock")
    private void updateUseCompaction() {
        mUseCompaction = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_USE_COMPACTION, DEFAULT_USE_COMPACTION);

        if (mUseCompaction && mCompactionHandler == null) {
            if (!mCachedAppOptimizerThread.isAlive()) {
                mCachedAppOptimizerThread.start();
            }

            mCompactionHandler = new MemCompactionHandler();

            Process.setThreadGroupAndCpuset(mCachedAppOptimizerThread.getThreadId(),
                    Process.THREAD_GROUP_SYSTEM);
        }
    }

    /**
     * Enables or disabled the app freezer.
     * @param enable Enables the freezer if true, disables it if false.
     * @return true if the operation completed successfully, false otherwise.
     */
    public synchronized boolean enableFreezer(boolean enable) {
        if (!mUseFreezer) {
            return false;
        }

        if (enable) {
            mFreezerDisableCount--;

            if (mFreezerDisableCount > 0) {
                return true;
            } else if (mFreezerDisableCount < 0) {
                Slog.e(TAG_AM, "unbalanced call to enableFreezer, ignoring");
                mFreezerDisableCount = 0;
                return false;
            }
        } else {
            mFreezerDisableCount++;

            if (mFreezerDisableCount > 1) {
                return true;
            }
        }

        // Override is applied immediately, restore is delayed
        synchronized (mAm) {
            synchronized (mProcLock) {
                mFreezerOverride = !enable;
                Slog.d(TAG_AM, "freezer override set to " + mFreezerOverride);

                mAm.mProcessList.forEachLruProcessesLOSP(true, process -> {
                    if (process == null) {
                        return;
                    }

                    final ProcessCachedOptimizerRecord opt = process.mOptRecord;
                    if (enable && opt.hasFreezerOverride()) {
                        freezeAppAsyncLSP(process);
                        opt.setFreezerOverride(false);
                    }

                    if (!enable && opt.isFrozen()) {
                        unfreezeAppLSP(process, OomAdjuster.OOM_ADJ_REASON_NONE);

                        // Set freezerOverride *after* calling unfreezeAppLSP (it resets the flag)
                        opt.setFreezerOverride(true);
                    }
                });
            }
        }

        return true;
    }

    /**
     * Informs binder that a process is about to be frozen. If freezer is enabled on a process via
     * this method, this method will synchronously dispatch all pending transactions to the
     * specified pid. This method will not add significant latencies when unfreezing.
     * After freezing binder calls, binder will block all transaction to the frozen pid, and return
     * an error to the sending process.
     *
     * @param pid the target pid for which binder transactions are to be frozen
     * @param freeze specifies whether to flush transactions and then freeze (true) or unfreeze
     * binder for the specificed pid.
     *
     * @throws RuntimeException in case a flush/freeze operation could not complete successfully.
     * @return 0 if success, or -EAGAIN indicating there's pending transaction.
     */
    private static native int freezeBinder(int pid, boolean freeze);

    /**
     * Retrieves binder freeze info about a process.
     * @param pid the pid for which binder freeze info is to be retrieved.
     *
     * @throws RuntimeException if the operation could not complete successfully.
     * @return a bit field reporting the binder freeze info for the process.
     */
    private static native int getBinderFreezeInfo(int pid);

    /**
     * Returns the path to be checked to verify whether the freezer is supported by this system.
     * @return absolute path to the file
     */
    private static native String getFreezerCheckPath();

    /**
     * Determines whether the freezer is supported by this system
     */
    public static boolean isFreezerSupported() {
        boolean supported = false;
        FileReader fr = null;

        try {
            fr = new FileReader(getFreezerCheckPath());
            char state = (char) fr.read();

            if (state == '1' || state == '0') {
                // Also check freezer binder ioctl
                getBinderFreezeInfo(Process.myPid());
                supported = true;
            } else {
                Slog.e(TAG_AM, "unexpected value in cgroup.freeze");
            }
        } catch (java.io.FileNotFoundException e) {
            Slog.w(TAG_AM, "cgroup.freeze not present");
        } catch (RuntimeException e) {
            Slog.w(TAG_AM, "unable to read freezer info");
        } catch (Exception e) {
            Slog.w(TAG_AM, "unable to read cgroup.freeze: " + e.toString());
        }

        if (fr != null) {
            try {
                fr.close();
            } catch (java.io.IOException e) {
                Slog.e(TAG_AM, "Exception closing cgroup.freeze: " + e.toString());
            }
        }

        return supported;
    }

    /**
     * Reads the flag value from DeviceConfig to determine whether app freezer
     * should be enabled, and starts the freeze/compaction thread if needed.
     */
    @GuardedBy("mPhenotypeFlagLock")
    private void updateUseFreezer() {
        final String configOverride = Settings.Global.getString(mAm.mContext.getContentResolver(),
                Settings.Global.CACHED_APPS_FREEZER_ENABLED);

        if ("disabled".equals(configOverride)) {
            mUseFreezer = false;
        } else if ("enabled".equals(configOverride)
                || DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                    KEY_USE_FREEZER, DEFAULT_USE_FREEZER)) {
            mUseFreezer = isFreezerSupported();
            updateFreezerDebounceTimeout();
        } else {
            mUseFreezer = false;
        }

        final boolean useFreezer = mUseFreezer;
        // enableFreezer() would need the global ActivityManagerService lock, post it.
        mAm.mHandler.post(() -> {
            if (useFreezer) {
                Slog.d(TAG_AM, "Freezer enabled");
                enableFreezer(true);

                if (!mCachedAppOptimizerThread.isAlive()) {
                    mCachedAppOptimizerThread.start();
                }

                if (mFreezeHandler == null) {
                    mFreezeHandler = new FreezeHandler();
                }

                Process.setThreadGroupAndCpuset(mCachedAppOptimizerThread.getThreadId(),
                        Process.THREAD_GROUP_SYSTEM);
            } else {
                Slog.d(TAG_AM, "Freezer disabled");
                enableFreezer(false);
            }
        });
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateCompactionActions() {
        int compactAction1 = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_ACTION_1, DEFAULT_COMPACT_ACTION_1);

        int compactAction2 = DeviceConfig.getInt(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_ACTION_2, DEFAULT_COMPACT_ACTION_2);

        mCompactActionSome = compactActionIntToString(compactAction1);
        mCompactActionFull = compactActionIntToString(compactAction2);
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateCompactionThrottles() {
        boolean useThrottleDefaults = false;
        // TODO: improve efficiency by calling DeviceConfig only once for all flags.
        String throttleSomeSomeFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_1);
        String throttleSomeFullFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_2);
        String throttleFullSomeFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_3);
        String throttleFullFullFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_4);
        String throttleBFGSFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_5);
        String throttlePersistentFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_6);
        String throttleMinOomAdjFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_MIN_OOM_ADJ);
        String throttleMaxOomAdjFlag =
                DeviceConfig.getProperty(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_COMPACT_THROTTLE_MAX_OOM_ADJ);

        if (TextUtils.isEmpty(throttleSomeSomeFlag) || TextUtils.isEmpty(throttleSomeFullFlag)
                || TextUtils.isEmpty(throttleFullSomeFlag)
                || TextUtils.isEmpty(throttleFullFullFlag)
                || TextUtils.isEmpty(throttleBFGSFlag)
                || TextUtils.isEmpty(throttlePersistentFlag)
                || TextUtils.isEmpty(throttleMinOomAdjFlag)
                || TextUtils.isEmpty(throttleMaxOomAdjFlag)) {
            // Set defaults for all if any are not set.
            useThrottleDefaults = true;
        } else {
            try {
                mCompactThrottleSomeSome = Integer.parseInt(throttleSomeSomeFlag);
                mCompactThrottleSomeFull = Integer.parseInt(throttleSomeFullFlag);
                mCompactThrottleFullSome = Integer.parseInt(throttleFullSomeFlag);
                mCompactThrottleFullFull = Integer.parseInt(throttleFullFullFlag);
                mCompactThrottleBFGS = Integer.parseInt(throttleBFGSFlag);
                mCompactThrottlePersistent = Integer.parseInt(throttlePersistentFlag);
                mCompactThrottleMinOomAdj = Long.parseLong(throttleMinOomAdjFlag);
                mCompactThrottleMaxOomAdj = Long.parseLong(throttleMaxOomAdjFlag);
            } catch (NumberFormatException e) {
                useThrottleDefaults = true;
            }
        }

        if (useThrottleDefaults) {
            mCompactThrottleSomeSome = DEFAULT_COMPACT_THROTTLE_1;
            mCompactThrottleSomeFull = DEFAULT_COMPACT_THROTTLE_2;
            mCompactThrottleFullSome = DEFAULT_COMPACT_THROTTLE_3;
            mCompactThrottleFullFull = DEFAULT_COMPACT_THROTTLE_4;
            mCompactThrottleBFGS = DEFAULT_COMPACT_THROTTLE_5;
            mCompactThrottlePersistent = DEFAULT_COMPACT_THROTTLE_6;
            mCompactThrottleMinOomAdj = DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ;
            mCompactThrottleMaxOomAdj = DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateCompactStatsdSampleRate() {
        mCompactStatsdSampleRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_STATSD_SAMPLE_RATE, DEFAULT_STATSD_SAMPLE_RATE);
        mCompactStatsdSampleRate = Math.min(1.0f, Math.max(0.0f, mCompactStatsdSampleRate));
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateFreezerStatsdSampleRate() {
        mFreezerStatsdSampleRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_FREEZER_STATSD_SAMPLE_RATE, DEFAULT_STATSD_SAMPLE_RATE);
        mFreezerStatsdSampleRate = Math.min(1.0f, Math.max(0.0f, mFreezerStatsdSampleRate));
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateFullRssThrottle() {
        mFullAnonRssThrottleKb = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_FULL_RSS_THROTTLE_KB, DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB);

        // Don't allow negative values. 0 means don't apply the throttle.
        if (mFullAnonRssThrottleKb < 0) {
            mFullAnonRssThrottleKb = DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateFullDeltaRssThrottle() {
        mFullDeltaRssThrottleKb = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB, DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB);

        if (mFullDeltaRssThrottleKb < 0) {
            mFullDeltaRssThrottleKb = DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateProcStateThrottle() {
        String procStateThrottleString = DeviceConfig.getString(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER, KEY_COMPACT_PROC_STATE_THROTTLE,
                DEFAULT_COMPACT_PROC_STATE_THROTTLE);
        if (!parseProcStateThrottle(procStateThrottleString)) {
            Slog.w(TAG_AM, "Unable to parse app compact proc state throttle \""
                    + procStateThrottleString + "\" falling back to default.");
            if (!parseProcStateThrottle(DEFAULT_COMPACT_PROC_STATE_THROTTLE)) {
                Slog.wtf(TAG_AM,
                        "Unable to parse default app compact proc state throttle "
                                + DEFAULT_COMPACT_PROC_STATE_THROTTLE);
            }
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateMinOomAdjThrottle() {
        mCompactThrottleMinOomAdj = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
            KEY_COMPACT_THROTTLE_MIN_OOM_ADJ, DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ);

        // Should only compact cached processes.
        if (mCompactThrottleMinOomAdj < ProcessList.CACHED_APP_MIN_ADJ) {
            mCompactThrottleMinOomAdj = DEFAULT_COMPACT_THROTTLE_MIN_OOM_ADJ;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateMaxOomAdjThrottle() {
        mCompactThrottleMaxOomAdj = DeviceConfig.getLong(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
            KEY_COMPACT_THROTTLE_MAX_OOM_ADJ, DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ);

        // Should only compact cached processes.
        if (mCompactThrottleMaxOomAdj > ProcessList.CACHED_APP_MAX_ADJ) {
            mCompactThrottleMaxOomAdj = DEFAULT_COMPACT_THROTTLE_MAX_OOM_ADJ;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateFreezerDebounceTimeout() {
        mFreezerDebounceTimeout = DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_ACTIVITY_MANAGER_NATIVE_BOOT,
                KEY_FREEZER_DEBOUNCE_TIMEOUT, DEFAULT_FREEZER_DEBOUNCE_TIMEOUT);

        if (mFreezerDebounceTimeout < 0) {
            mFreezerDebounceTimeout = DEFAULT_FREEZER_DEBOUNCE_TIMEOUT;
        }
    }

    private boolean parseProcStateThrottle(String procStateThrottleString) {
        String[] procStates = TextUtils.split(procStateThrottleString, ",");
        mProcStateThrottle.clear();
        for (String procState : procStates) {
            try {
                mProcStateThrottle.add(Integer.parseInt(procState));
            } catch (NumberFormatException e) {
                Slog.e(TAG_AM, "Failed to parse default app compaction proc state: "
                        + procState);
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    static String compactActionIntToString(int action) {
        if (action < 0 || action >= COMPACT_ACTION_STRING.length) {
            return "";
        }

        return COMPACT_ACTION_STRING[action];
    }

    // This will ensure app will be out of the freezer for at least mFreezerDebounceTimeout.
    @GuardedBy("mAm")
    void unfreezeTemporarily(ProcessRecord app, String reason) {
        if (mUseFreezer) {
            synchronized (mProcLock) {
                if (app.mOptRecord.isFrozen() || app.mOptRecord.isPendingFreeze()) {
                    unfreezeAppLSP(app, reason);
                    freezeAppAsyncLSP(app);
                }
            }
        }
    }

    @GuardedBy({"mAm", "mProcLock"})
    void freezeAppAsyncLSP(ProcessRecord app) {
        final ProcessCachedOptimizerRecord opt = app.mOptRecord;
        if (opt.isPendingFreeze()) {
            // Skip redundant DO_FREEZE message
            return;
        }

        mFreezeHandler.sendMessageDelayed(
                mFreezeHandler.obtainMessage(
                    SET_FROZEN_PROCESS_MSG, DO_FREEZE, 0, app),
                mFreezerDebounceTimeout);
        opt.setPendingFreeze(true);
        if (DEBUG_FREEZER) {
            Slog.d(TAG_AM, "Async freezing " + app.getPid() + " " + app.processName);
        }
    }

    @GuardedBy({"mAm", "mProcLock", "mFreezerLock"})
    void unfreezeAppInternalLSP(ProcessRecord app, String reason) {
        final int pid = app.getPid();
        final ProcessCachedOptimizerRecord opt = app.mOptRecord;
        if (opt.isPendingFreeze()) {
            // Remove pending DO_FREEZE message
            mFreezeHandler.removeMessages(SET_FROZEN_PROCESS_MSG, app);
            opt.setPendingFreeze(false);
            if (DEBUG_FREEZER) {
                Slog.d(TAG_AM, "Cancel freezing " + pid + " " + app.processName);
            }
        }

        opt.setFreezerOverride(false);
        if (pid == 0 || !opt.isFrozen()) {
            return;
        }

        // Unfreeze the binder interface first, to avoid transactions triggered by timers fired
        // right after unfreezing the process to fail
        boolean processKilled = false;

        try {
            int freezeInfo = getBinderFreezeInfo(pid);

            if ((freezeInfo & SYNC_RECEIVED_WHILE_FROZEN) != 0) {
                Slog.d(TAG_AM, "pid " + pid + " " + app.processName
                        + " received sync transactions while frozen, killing");
                app.killLocked("Sync transaction while in frozen state",
                        ApplicationExitInfo.REASON_FREEZER,
                        ApplicationExitInfo.SUBREASON_FREEZER_BINDER_TRANSACTION, true);
                processKilled = true;
            }

            if ((freezeInfo & ASYNC_RECEIVED_WHILE_FROZEN) != 0 && DEBUG_FREEZER) {
                Slog.d(TAG_AM, "pid " + pid + " " + app.processName
                        + " received async transactions while frozen");
            }
        } catch (Exception e) {
            Slog.d(TAG_AM, "Unable to query binder frozen info for pid " + pid + " "
                    + app.processName + ". Killing it. Exception: " + e);
            app.killLocked("Unable to query binder frozen stats",
                    ApplicationExitInfo.REASON_FREEZER,
                    ApplicationExitInfo.SUBREASON_FREEZER_BINDER_IOCTL, true);
            processKilled = true;
        }

        if (processKilled) {
            return;
        }

        long freezeTime = opt.getFreezeUnfreezeTime();

        try {
            freezeBinder(pid, false);
        } catch (RuntimeException e) {
            Slog.e(TAG_AM, "Unable to unfreeze binder for " + pid + " " + app.processName
                    + ". Killing it");
            app.killLocked("Unable to unfreeze",
                    ApplicationExitInfo.REASON_FREEZER,
                    ApplicationExitInfo.SUBREASON_FREEZER_BINDER_IOCTL, true);
            return;
        }

        try {
            Process.setProcessFrozen(pid, app.uid, false);

            opt.setFreezeUnfreezeTime(SystemClock.uptimeMillis());
            opt.setFrozen(false);
            mFrozenProcesses.delete(pid);
        } catch (Exception e) {
            Slog.e(TAG_AM, "Unable to unfreeze " + pid + " " + app.processName
                    + ". This might cause inconsistency or UI hangs.");
        }

        if (!opt.isFrozen()) {
            Slog.d(TAG_AM, "sync unfroze " + pid + " " + app.processName);

            mFreezeHandler.sendMessage(
                    mFreezeHandler.obtainMessage(REPORT_UNFREEZE_MSG,
                        pid,
                        (int) Math.min(opt.getFreezeUnfreezeTime() - freezeTime, Integer.MAX_VALUE),
                        new Pair<String, String>(app.processName, reason)));
        }
    }

    @GuardedBy({"mAm", "mProcLock"})
    void unfreezeAppLSP(ProcessRecord app, String reason) {
        synchronized (mFreezerLock) {
            unfreezeAppInternalLSP(app, reason);
        }
    }

    /**
     * This quick function works around the race condition between WM/ATMS and AMS, allowing
     * the former to directly unfreeze a frozen process before the latter runs updateOomAdj.
     * After the race issue is solved, this workaround can be removed. (b/213288355)
     * The caller of this function should still trigger updateOomAdj for AMS to unfreeze the app.
     * @param pid pid of the process to be unfrozen
     */
    void unfreezeProcess(int pid, String reason) {
        synchronized (mFreezerLock) {
            ProcessRecord app = mFrozenProcesses.get(pid);
            if (app == null) {
                return;
            }
            Slog.d(TAG_AM, "quick sync unfreeze " + pid);
            try {
                freezeBinder(pid, false);
            } catch (RuntimeException e) {
                Slog.e(TAG_AM, "Unable to quick unfreeze binder for " + pid);
                return;
            }

            try {
                Process.setProcessFrozen(pid, app.uid, false);
            } catch (Exception e) {
                Slog.e(TAG_AM, "Unable to quick unfreeze " + pid);
            }
        }
    }

    /**
     * To be called when the given app is killed.
     */
    @GuardedBy({"mAm", "mProcLock"})
    void onCleanupApplicationRecordLocked(ProcessRecord app) {
        if (mUseFreezer) {
            final ProcessCachedOptimizerRecord opt = app.mOptRecord;
            if (opt.isPendingFreeze()) {
                // Remove pending DO_FREEZE message
                mFreezeHandler.removeMessages(SET_FROZEN_PROCESS_MSG, app);
                opt.setPendingFreeze(false);
            }

            mFrozenProcesses.delete(app.getPid());
        }
    }

    void onWakefulnessChanged(int wakefulness) {
        if(wakefulness == PowerManagerInternal.WAKEFULNESS_AWAKE) {
            // Remove any pending compaction we may have scheduled to happen while screen was off
            Slog.e(TAG_AM, "Cancel pending or running compactions as system is awake");
            cancelAllCompactions();
        }
    }

    void cancelAllCompactions() {
        synchronized (mProcLock) {
            int size = mPendingCompactionProcesses.size();
            ProcessRecord record;
            for (int i=0; i < size; ++i) {
                record = mPendingCompactionProcesses.get(i);
                // The process record is kept alive after compactions are cleared,
                // so make sure to reset the compaction state to avoid skipping any future
                // compactions due to a stale value here.
                record.mOptRecord.setHasPendingCompact(false);
            }
            mPendingCompactionProcesses.clear();
        }
        cancelCompaction();
    }

    @GuardedBy({"mService", "mProcLock"})
    void onOomAdjustChanged(int oldAdj, int newAdj, ProcessRecord app) {
        // Cancel any currently executing compactions
        // if the process moved out of cached state
        if (DefaultProcessDependencies.mPidCompacting == app.mPid && newAdj < oldAdj
                && newAdj < ProcessList.CACHED_APP_MIN_ADJ) {
            cancelCompaction();
        }

        if (oldAdj <= ProcessList.PERCEPTIBLE_APP_ADJ
                && (newAdj == ProcessList.PREVIOUS_APP_ADJ || newAdj == ProcessList.HOME_APP_ADJ)) {
            // Perform a minor compaction when a perceptible app becomes the prev/home app
            compactAppSome(app, false);
        } else if (oldAdj < ProcessList.CACHED_APP_MIN_ADJ
                && newAdj >= ProcessList.CACHED_APP_MIN_ADJ
                && newAdj <= ProcessList.CACHED_APP_MAX_ADJ) {
            // Perform a major compaction when any app enters cached
            compactAppFull(app, false);
        }
    }

    /**
     * This method resolves which compaction method we should use for the proposed compaction.
     */
    int resolveCompactionAction(int pendingAction) {
        int resolvedAction;

        switch (pendingAction) {
            case COMPACT_PROCESS_SOME:
                resolvedAction = COMPACT_ACTION_FILE;
                break;
            // For the time being, treat these as equivalent.
            case COMPACT_PROCESS_FULL:
            case COMPACT_PROCESS_PERSISTENT:
            case COMPACT_PROCESS_BFGS:
                resolvedAction = COMPACT_ACTION_FULL;
                break;
            default:
                resolvedAction = COMPACT_ACTION_NONE;
                break;
        }

        // Downgrade compaction under swap memory pressure
        if (resolvedAction == COMPACT_ACTION_FULL) {
            double swapFreePercent = getFreeSwapPercent();
            if (swapFreePercent < COMPACT_DOWNGRADE_FREE_SWAP_THRESHOLD) {
                resolvedAction = COMPACT_ACTION_FILE;
                if (DEBUG_COMPACTION) {
                    Slog.d(TAG_AM,
                            "Downgraded compaction to file only due to low swap."
                                    + " Swap Free% " + swapFreePercent);
                }
            }
        }

        return resolvedAction;
    }

    @VisibleForTesting
    static final class LastCompactionStats {
        private final long[] mRssAfterCompaction;

        LastCompactionStats(long[] rss) {
            mRssAfterCompaction = rss;
        }

        long[] getRssAfterCompaction() {
            return mRssAfterCompaction;
        }
    }

    private final class MemCompactionHandler extends Handler {
        private MemCompactionHandler() {
            super(mCachedAppOptimizerThread.getLooper());
        }

        private boolean shouldOomAdjThrottleCompaction(ProcessRecord proc, int action) {
            final String name = proc.processName;

            // don't compact if the process has returned to perceptible
            // and this is only a cached/home/prev compaction
            if ((action == COMPACT_ACTION_FILE || action == COMPACT_ACTION_FULL)
                    && (proc.mState.getSetAdj() <= ProcessList.PERCEPTIBLE_APP_ADJ)) {
                if (DEBUG_COMPACTION) {
                    Slog.d(TAG_AM,
                            "Skipping compaction as process " + name + " is "
                                    + "now perceptible.");
                }
                return true;
            }

            return false;
        }

        private boolean shouldTimeThrottleCompaction(
                ProcessRecord proc, long start, int pendingAction) {
            final ProcessCachedOptimizerRecord opt = proc.mOptRecord;
            final String name = proc.processName;

            int lastCompactAction = opt.getLastCompactAction();
            long lastCompactTime = opt.getLastCompactTime();

            // basic throttling
            // use the Phenotype flag knobs to determine whether current/prevous
            // compaction combo should be throtted or not

            // Note that we explicitly don't take mPhenotypeFlagLock here as the flags
            // should very seldom change, and taking the risk of using the wrong action is
            // preferable to taking the lock for every single compaction action.
            if (lastCompactTime != 0) {
                if (pendingAction == COMPACT_PROCESS_SOME) {
                    if ((lastCompactAction == COMPACT_PROCESS_SOME
                                && (start - lastCompactTime < mCompactThrottleSomeSome))
                            || (lastCompactAction == COMPACT_PROCESS_FULL
                                    && (start - lastCompactTime < mCompactThrottleSomeFull))) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM,
                                    "Skipping some compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleSomeSome
                                            + "/" + mCompactThrottleSomeFull
                                            + " last=" + (start - lastCompactTime) + "ms ago");
                        }
                        return true;
                    }
                } else if (pendingAction == COMPACT_PROCESS_FULL) {
                    if ((lastCompactAction == COMPACT_PROCESS_SOME
                                && (start - lastCompactTime < mCompactThrottleFullSome))
                            || (lastCompactAction == COMPACT_PROCESS_FULL
                                    && (start - lastCompactTime < mCompactThrottleFullFull))) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM,
                                    "Skipping full compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleFullSome
                                            + "/" + mCompactThrottleFullFull
                                            + " last=" + (start - lastCompactTime) + "ms ago");
                        }
                        return true;
                    }
                } else if (pendingAction == COMPACT_PROCESS_PERSISTENT) {
                    if (start - lastCompactTime < mCompactThrottlePersistent) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM,
                                    "Skipping persistent compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottlePersistent
                                            + " last=" + (start - lastCompactTime) + "ms ago");
                        }
                        return true;
                    }
                } else if (pendingAction == COMPACT_PROCESS_BFGS) {
                    if (start - lastCompactTime < mCompactThrottleBFGS) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM,
                                    "Skipping bfgs compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleBFGS
                                            + " last=" + (start - lastCompactTime) + "ms ago");
                        }
                        return true;
                    }
                }
            }

            return false;
        }

        private boolean shouldThrottleMiscCompaction(
                ProcessRecord proc, int procState, int action) {
            final String name = proc.processName;
            if (mProcStateThrottle.contains(procState)) {
                if (DEBUG_COMPACTION) {
                    Slog.d(TAG_AM,
                            "Skipping full compaction for process " + name + "; proc state is "
                                    + procState);
                }
                return true;
            }

            if (COMPACT_ACTION_NONE == action) {
                if (DEBUG_COMPACTION) {
                    Slog.d(TAG_AM,
                            "Skipping compaction for process " + name + "since action is None");
                }
                return true;
            }

            return false;
        }

        private boolean shouldRssThrottleCompaction(
                int action, int pid, String name, long[] rssBefore) {
            long anonRssBefore = rssBefore[RSS_ANON_INDEX];
            LastCompactionStats lastCompactionStats = mLastCompactionStats.get(pid);

            if (rssBefore[RSS_TOTAL_INDEX] == 0 && rssBefore[RSS_FILE_INDEX] == 0
                    && rssBefore[RSS_ANON_INDEX] == 0 && rssBefore[RSS_SWAP_INDEX] == 0) {
                if (DEBUG_COMPACTION) {
                    Slog.d(TAG_AM,
                            "Skipping compaction for"
                                    + "process " + pid + " with no memory usage. Dead?");
                }
                return true;
            }

            if (action == COMPACT_ACTION_FULL || action == COMPACT_ACTION_ANON) {
                if (mFullAnonRssThrottleKb > 0L && anonRssBefore < mFullAnonRssThrottleKb) {
                    if (DEBUG_COMPACTION) {
                        Slog.d(TAG_AM,
                                "Skipping full compaction for process " + name
                                        + "; anon RSS is too small: " + anonRssBefore + "KB.");
                    }
                    return true;
                }

                if (lastCompactionStats != null && mFullDeltaRssThrottleKb > 0L) {
                    long[] lastRss = lastCompactionStats.getRssAfterCompaction();
                    long absDelta = Math.abs(rssBefore[RSS_FILE_INDEX] - lastRss[RSS_FILE_INDEX])
                            + Math.abs(rssBefore[RSS_ANON_INDEX] - lastRss[RSS_ANON_INDEX])
                            + Math.abs(rssBefore[RSS_SWAP_INDEX] - lastRss[RSS_SWAP_INDEX]);
                    if (absDelta <= mFullDeltaRssThrottleKb) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM,
                                    "Skipping full compaction for process " + name
                                            + "; abs delta is too small: " + absDelta + "KB.");
                        }
                        return true;
                    }
                }
            }

            return false;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case COMPACT_PROCESS_MSG: {
                    long start = SystemClock.uptimeMillis();
                    ProcessRecord proc;
                    final ProcessCachedOptimizerRecord opt;
                    int pid;
                    String action;
                    final String name;
                    int requestedAction, lastCompactAction;
                    long lastCompactTime;
                    int lastOomAdj = msg.arg1;
                    int procState = msg.arg2;
                    boolean forceCompaction;
                    synchronized (mProcLock) {
                        if (mPendingCompactionProcesses.isEmpty()) {
                            if (DEBUG_COMPACTION) {
                                Slog.d(TAG_AM, "No processes pending compaction, bail out");
                            }
                            return;
                        }
                        proc = mPendingCompactionProcesses.remove(0);
                        opt = proc.mOptRecord;
                        forceCompaction = opt.isForceCompact();
                        opt.setForceCompact(false); // since this is a one-shot operation

                        requestedAction = opt.getReqCompactAction();
                        pid = proc.getPid();
                        name = proc.processName;
                        opt.setHasPendingCompact(false);
                        lastCompactAction = opt.getLastCompactAction();
                        lastCompactTime = opt.getLastCompactTime();
                    }

                    ++mProcCompactionsRequested;
                    long[] rssBefore;
                    if (pid == 0) {
                        // not a real process, either one being launched or one being killed
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM, "Compaction failed, pid is 0");
                        }
                        ++mProcCompactionsNoPidThrottled;
                        return;
                    }

                    if (!forceCompaction) {
                        if (shouldOomAdjThrottleCompaction(proc, requestedAction)) {
                            ++mProcCompactionsOomAdjThrottled;
                            return;
                        }
                        if (shouldTimeThrottleCompaction(proc, start, requestedAction)) {
                            ++mProcCompactionsTimeThrottled;
                            return;
                        }
                        if (shouldThrottleMiscCompaction(proc, procState, requestedAction)) {
                            ++mProcCompactionsMiscThrottled;
                            return;
                        }
                        rssBefore = mProcessDependencies.getRss(pid);
                        if (shouldRssThrottleCompaction(requestedAction, pid, name, rssBefore)) {
                            ++mProcCompactionsRSSThrottled;
                            return;
                        }
                    } else {
                        rssBefore = mProcessDependencies.getRss(pid);
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM, "Forcing compaction for " + name);
                        }
                    }

                    // Now we've passed through all the throttles and are going to compact, update
                    // bookkeeping.
                    switch (requestedAction) {
                        case COMPACT_PROCESS_SOME:
                            mSomeCompactionCount++;
                            break;
                        case COMPACT_PROCESS_FULL:
                            mFullCompactionCount++;
                            break;
                        case COMPACT_PROCESS_PERSISTENT:
                            mPersistentCompactionCount++;
                            break;
                        case COMPACT_PROCESS_BFGS:
                            mBfgsCompactionCount++;
                            break;
                        default:
                            break;
                    }

                    int resolvedAction = resolveCompactionAction(requestedAction);
                    action = compactActionIntToString(resolvedAction);

                    try {
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER,
                                "Compact " + action + ": " + name);
                        ++mProcCompactionsPerformed;
                        long zramFreeKbBefore = Debug.getZramFreeKb();
                        mProcessDependencies.performCompaction(action, pid);
                        long[] rssAfter = mProcessDependencies.getRss(pid);
                        long end = SystemClock.uptimeMillis();
                        long time = end - start;
                        long zramFreeKbAfter = Debug.getZramFreeKb();
                        long deltaTotalRss = rssAfter[RSS_TOTAL_INDEX] - rssBefore[RSS_TOTAL_INDEX];
                        long deltaFileRss = rssAfter[RSS_FILE_INDEX] - rssBefore[RSS_FILE_INDEX];
                        long deltaAnonRss = rssAfter[RSS_ANON_INDEX] - rssBefore[RSS_ANON_INDEX];
                        long deltaSwapRss = rssAfter[RSS_SWAP_INDEX] - rssBefore[RSS_SWAP_INDEX];
                        EventLog.writeEvent(EventLogTags.AM_COMPACT, pid, name, action,
                                rssBefore[RSS_TOTAL_INDEX], rssBefore[RSS_FILE_INDEX],
                                rssBefore[RSS_ANON_INDEX], rssBefore[RSS_SWAP_INDEX], deltaTotalRss,
                                deltaFileRss, deltaAnonRss, deltaSwapRss, time, lastCompactAction,
                                lastCompactTime, lastOomAdj, procState, zramFreeKbBefore,
                                zramFreeKbAfter - zramFreeKbBefore);
                        // Note that as above not taking mPhenoTypeFlagLock here to avoid locking
                        // on every single compaction for a flag that will seldom change and the
                        // impact of reading the wrong value here is low.
                        if (mRandom.nextFloat() < mCompactStatsdSampleRate) {
                            FrameworkStatsLog.write(FrameworkStatsLog.APP_COMPACTED, pid, name,
                                    requestedAction, rssBefore[RSS_TOTAL_INDEX],
                                    rssBefore[RSS_FILE_INDEX], rssBefore[RSS_ANON_INDEX],
                                    rssBefore[RSS_SWAP_INDEX], rssAfter[RSS_TOTAL_INDEX],
                                    rssAfter[RSS_FILE_INDEX], rssAfter[RSS_ANON_INDEX],
                                    rssAfter[RSS_SWAP_INDEX], time, lastCompactAction,
                                    lastCompactTime, lastOomAdj,
                                    ActivityManager.processStateAmToProto(procState),
                                    zramFreeKbBefore, zramFreeKbAfter);
                        }
                        synchronized (mProcLock) {
                            opt.setLastCompactTime(end);
                            opt.setLastCompactAction(resolvedAction);
                        }
                        if (resolvedAction == COMPACT_ACTION_FULL
                                || resolvedAction == COMPACT_ACTION_ANON) {
                            // Remove entry and insert again to update insertion order.
                            mLastCompactionStats.remove(pid);
                            mLastCompactionStats.put(pid, new LastCompactionStats(rssAfter));
                        }
                    } catch (Exception e) {
                        // nothing to do, presumably the process died
                        Slog.d(TAG_AM,
                                "Exception occurred while compacting pid: " + name
                                        + ". Exception:" + e.getMessage());
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }
                    break;
                }
                case COMPACT_SYSTEM_MSG: {
                    ++mSystemCompactionsPerformed;
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "compactSystem");
                    compactSystem();
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    break;
                }
            }
        }
    }

    private final class FreezeHandler extends Handler implements
            ProcLocksReader.ProcLocksReaderCallback {
        private FreezeHandler() {
            super(mCachedAppOptimizerThread.getLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case SET_FROZEN_PROCESS_MSG:
                    synchronized (mAm) {
                        freezeProcess((ProcessRecord) msg.obj);
                    }
                    break;
                case REPORT_UNFREEZE_MSG:
                    int pid = msg.arg1;
                    int frozenDuration = msg.arg2;
                    Pair<String, String> obj = (Pair<String, String>) msg.obj;
                    String processName = obj.first;
                    String reason = obj.second;

                    reportUnfreeze(pid, frozenDuration, processName, reason);
                    break;
                default:
                    return;
            }
        }

        @GuardedBy({"mAm", "mProcLock"})
        private void rescheduleFreeze(final ProcessRecord proc, final String reason) {
            Slog.d(TAG_AM, "Reschedule freeze for process " + proc.getPid()
                    + " " + proc.processName + " (" + reason + ")");
            unfreezeAppLSP(proc, OomAdjuster.OOM_ADJ_REASON_NONE);
            freezeAppAsyncLSP(proc);
        }

        @GuardedBy({"mAm"})
        private void freezeProcess(final ProcessRecord proc) {
            int pid = proc.getPid(); // Unlocked intentionally
            final String name = proc.processName;
            final long unfrozenDuration;
            final boolean frozen;
            final ProcessCachedOptimizerRecord opt = proc.mOptRecord;

            opt.setPendingFreeze(false);

            synchronized (mProcLock) {
                pid = proc.getPid();
                if (proc.mState.getCurAdj() < ProcessList.CACHED_APP_MIN_ADJ
                        || opt.shouldNotFreeze()) {
                    if (DEBUG_FREEZER) {
                        Slog.d(TAG_AM, "Skipping freeze for process " + pid
                                + " " + name + " curAdj = " + proc.mState.getCurAdj()
                                + ", shouldNotFreeze = " + opt.shouldNotFreeze());
                    }
                    return;
                }

                if (mFreezerOverride) {
                    opt.setFreezerOverride(true);
                    Slog.d(TAG_AM, "Skipping freeze for process " + pid
                            + " " + name + " curAdj = " + proc.mState.getCurAdj()
                            + "(override)");
                    return;
                }

                if (pid == 0 || opt.isFrozen()) {
                    // Already frozen or not a real process, either one being
                    // launched or one being killed
                    return;
                }

                Slog.d(TAG_AM, "freezing " + pid + " " + name);

                // Freeze binder interface before the process, to flush any
                // transactions that might be pending.
                try {
                    if (freezeBinder(pid, true) != 0) {
                        rescheduleFreeze(proc, "outstanding txns");
                        return;
                    }
                } catch (RuntimeException e) {
                    Slog.e(TAG_AM, "Unable to freeze binder for " + pid + " " + name);
                    mFreezeHandler.post(() -> {
                        synchronized (mAm) {
                            proc.killLocked("Unable to freeze binder interface",
                                    ApplicationExitInfo.REASON_FREEZER,
                                    ApplicationExitInfo.SUBREASON_FREEZER_BINDER_IOCTL, true);
                        }
                    });
                }

                long unfreezeTime = opt.getFreezeUnfreezeTime();

                try {
                    Process.setProcessFrozen(pid, proc.uid, true);

                    opt.setFreezeUnfreezeTime(SystemClock.uptimeMillis());
                    opt.setFrozen(true);
                    mFrozenProcesses.put(pid, proc);
                } catch (Exception e) {
                    Slog.w(TAG_AM, "Unable to freeze " + pid + " " + name);
                }

                unfrozenDuration = opt.getFreezeUnfreezeTime() - unfreezeTime;
                frozen = opt.isFrozen();
            }

            if (!frozen) {
                return;
            }

            EventLog.writeEvent(EventLogTags.AM_FREEZE, pid, name);

            // See above for why we're not taking mPhenotypeFlagLock here
            if (mRandom.nextFloat() < mFreezerStatsdSampleRate) {
                FrameworkStatsLog.write(FrameworkStatsLog.APP_FREEZE_CHANGED,
                        FrameworkStatsLog.APP_FREEZE_CHANGED__ACTION__FREEZE_APP,
                        pid,
                        name,
                        unfrozenDuration,
                        FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__NONE);
            }

            try {
                // post-check to prevent races
                int freezeInfo = getBinderFreezeInfo(pid);

                if ((freezeInfo & TXNS_PENDING_WHILE_FROZEN) != 0) {
                    synchronized (mProcLock) {
                        rescheduleFreeze(proc, "new pending txns");
                    }
                    return;
                }
            } catch (RuntimeException e) {
                Slog.e(TAG_AM, "Unable to freeze binder for " + pid + " " + name);
                mFreezeHandler.post(() -> {
                    synchronized (mAm) {
                        proc.killLocked("Unable to freeze binder interface",
                                ApplicationExitInfo.REASON_FREEZER,
                                ApplicationExitInfo.SUBREASON_FREEZER_BINDER_IOCTL, true);
                    }
                });
            }

            try {
                // post-check to prevent deadlock
                mProcLocksReader.handleBlockingFileLocks(this);
            } catch (Exception e) {
                Slog.e(TAG_AM, "Unable to check file locks for " + name + "(" + pid + "): " + e);
                synchronized (mProcLock) {
                    unfreezeAppLSP(proc, OomAdjuster.OOM_ADJ_REASON_NONE);
                }
            }
        }

        private void reportUnfreeze(int pid, int frozenDuration, String processName,
                String reason) {

            EventLog.writeEvent(EventLogTags.AM_UNFREEZE, pid, processName);

            // See above for why we're not taking mPhenotypeFlagLock here
            if (mRandom.nextFloat() < mFreezerStatsdSampleRate) {
                FrameworkStatsLog.write(
                        FrameworkStatsLog.APP_FREEZE_CHANGED,
                        FrameworkStatsLog.APP_FREEZE_CHANGED__ACTION__UNFREEZE_APP,
                        pid,
                        processName,
                        frozenDuration,
                        getUnfreezeReasonCode(reason));
            }
        }

        private int getUnfreezeReasonCode(String oomAdjReason) {
            switch (oomAdjReason) {
                case OomAdjuster.OOM_ADJ_REASON_ACTIVITY:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__ACTIVITY;
                case OomAdjuster.OOM_ADJ_REASON_FINISH_RECEIVER:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__FINISH_RECEIVER;
                case OomAdjuster.OOM_ADJ_REASON_START_RECEIVER:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__START_RECEIVER;
                case OomAdjuster.OOM_ADJ_REASON_BIND_SERVICE:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__BIND_SERVICE;
                case OomAdjuster.OOM_ADJ_REASON_UNBIND_SERVICE:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__UNBIND_SERVICE;
                case OomAdjuster.OOM_ADJ_REASON_START_SERVICE:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__START_SERVICE;
                case OomAdjuster.OOM_ADJ_REASON_GET_PROVIDER:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__GET_PROVIDER;
                case OomAdjuster.OOM_ADJ_REASON_REMOVE_PROVIDER:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__REMOVE_PROVIDER;
                case OomAdjuster.OOM_ADJ_REASON_UI_VISIBILITY:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__UI_VISIBILITY;
                case OomAdjuster.OOM_ADJ_REASON_ALLOWLIST:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__ALLOWLIST;
                case OomAdjuster.OOM_ADJ_REASON_PROCESS_BEGIN:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__PROCESS_BEGIN;
                case OomAdjuster.OOM_ADJ_REASON_PROCESS_END:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__PROCESS_END;
                default:
                    return FrameworkStatsLog.APP_FREEZE_CHANGED__UNFREEZE_REASON__NONE;
            }
        }

        @GuardedBy({"mAm"})
        @Override
        public void onBlockingFileLock(int pid) {
            if (DEBUG_FREEZER) {
                Slog.d(TAG_AM, "Process (pid=" + pid + ") holds blocking file lock");
            }
            synchronized (mProcLock) {
                ProcessRecord app = mFrozenProcesses.get(pid);
                if (app != null) {
                    Slog.i(TAG_AM, app.processName + " (" + pid + ") holds blocking file lock");
                    unfreezeAppLSP(app, OomAdjuster.OOM_ADJ_REASON_NONE);
                }
            }
        }
    }

    /**
     * Default implementation for ProcessDependencies, public vor visibility to OomAdjuster class.
     */
    private static final class DefaultProcessDependencies implements ProcessDependencies {
        public static volatile int mPidCompacting = -1;

        // Get memory RSS from process.
        @Override
        public long[] getRss(int pid) {
            return Process.getRss(pid);
        }

        // Compact process.
        @Override
        public void performCompaction(String action, int pid) throws IOException {
            mPidCompacting = pid;
            if (action.equals(COMPACT_ACTION_STRING[COMPACT_ACTION_FULL])) {
                compactProcess(pid, COMPACT_ACTION_FILE_FLAG | COMPACT_ACTION_ANON_FLAG);
            } else if (action.equals(COMPACT_ACTION_STRING[COMPACT_ACTION_FILE])) {
                compactProcess(pid, COMPACT_ACTION_FILE_FLAG);
            } else if (action.equals(COMPACT_ACTION_STRING[COMPACT_ACTION_ANON])) {
                compactProcess(pid, COMPACT_ACTION_ANON_FLAG);
            }
            mPidCompacting = -1;
        }
    }
}
