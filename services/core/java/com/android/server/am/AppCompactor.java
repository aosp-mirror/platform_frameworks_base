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

import static android.os.Process.THREAD_PRIORITY_FOREGROUND;

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_COMPACTION;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertiesChangedListener;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class AppCompactor {

    // Flags stored in the DeviceConfig API.
    @VisibleForTesting static final String KEY_USE_COMPACTION = "use_compaction";
    @VisibleForTesting static final String KEY_COMPACT_ACTION_1 = "compact_action_1";
    @VisibleForTesting static final String KEY_COMPACT_ACTION_2 = "compact_action_2";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_1 = "compact_throttle_1";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_2 = "compact_throttle_2";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_3 = "compact_throttle_3";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_4 = "compact_throttle_4";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_5 = "compact_throttle_5";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_6 = "compact_throttle_6";
    @VisibleForTesting static final String KEY_COMPACT_STATSD_SAMPLE_RATE =
            "compact_statsd_sample_rate";
    @VisibleForTesting static final String KEY_COMPACT_FULL_RSS_THROTTLE_KB =
            "compact_full_rss_throttle_kb";
    @VisibleForTesting static final String KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB =
            "compact_full_delta_rss_throttle_kb";
    @VisibleForTesting static final String KEY_COMPACT_PROC_STATE_THROTTLE =
            "compact_proc_state_throttle";

    // Phenotype sends int configurations and we map them to the strings we'll use on device,
    // preventing a weird string value entering the kernel.
    private static final int COMPACT_ACTION_FILE_FLAG = 1;
    private static final int COMPACT_ACTION_ANON_FLAG = 2;
    private static final int COMPACT_ACTION_FULL_FLAG = 3;
    private static final int COMPACT_ACTION_NONE_FLAG = 4;
    private static final String COMPACT_ACTION_NONE = "";
    private static final String COMPACT_ACTION_FILE = "file";
    private static final String COMPACT_ACTION_ANON = "anon";
    private static final String COMPACT_ACTION_FULL = "all";

    // Defaults for phenotype flags.
    @VisibleForTesting static final Boolean DEFAULT_USE_COMPACTION = false;
    @VisibleForTesting static final int DEFAULT_COMPACT_ACTION_1 = COMPACT_ACTION_FILE_FLAG;
    @VisibleForTesting static final int DEFAULT_COMPACT_ACTION_2 = COMPACT_ACTION_FULL_FLAG;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_1 = 5_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_2 = 10_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_3 = 500;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_4 = 10_000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_5 = 10 * 60 * 1000;
    @VisibleForTesting static final long DEFAULT_COMPACT_THROTTLE_6 = 10 * 60 * 1000;
    // The sampling rate to push app compaction events into statsd for upload.
    @VisibleForTesting static final float DEFAULT_STATSD_SAMPLE_RATE = 0.1f;
    @VisibleForTesting static final long DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB = 12_000L;
    @VisibleForTesting static final long DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB = 8_000L;
    // Format of this string should be a comma separated list of integers.
    @VisibleForTesting static final String DEFAULT_COMPACT_PROC_STATE_THROTTLE =
            String.valueOf(ActivityManager.PROCESS_STATE_RECEIVER);

    @VisibleForTesting
    interface PropertyChangedCallbackForTest {
        void onPropertyChanged();
    }
    private PropertyChangedCallbackForTest mTestCallback;

    // Handler constants.
    static final int COMPACT_PROCESS_SOME = 1;
    static final int COMPACT_PROCESS_FULL = 2;
    static final int COMPACT_PROCESS_PERSISTENT = 3;
    static final int COMPACT_PROCESS_BFGS = 4;
    static final int COMPACT_PROCESS_MSG = 1;
    static final int COMPACT_SYSTEM_MSG = 2;

    /**
     * This thread must be moved to the system background cpuset.
     * If that doesn't happen, it's probably going to draw a lot of power.
     * However, this has to happen after the first updateOomAdjLocked, because
     * that will wipe out the cpuset assignment for system_server threads.
     * Accordingly, this is in the AMS constructor.
     */
    final ServiceThread mCompactionThread;

    private final ArrayList<ProcessRecord> mPendingCompactionProcesses =
            new ArrayList<ProcessRecord>();
    private final ActivityManagerService mAm;
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
                                    || KEY_COMPACT_THROTTLE_4.equals(name)) {
                                updateCompactionThrottles();
                            } else if (KEY_COMPACT_STATSD_SAMPLE_RATE.equals(name)) {
                                updateStatsdSampleRate();
                            } else if (KEY_COMPACT_FULL_RSS_THROTTLE_KB.equals(name)) {
                                updateFullRssThrottle();
                            } else if (KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB.equals(name)) {
                                updateFullDeltaRssThrottle();
                            } else if (KEY_COMPACT_PROC_STATE_THROTTLE.equals(name)) {
                                updateProcStateThrottle();
                            }
                        }
                    }
                    if (mTestCallback != null) {
                        mTestCallback.onPropertyChanged();
                    }
                }
            };

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
    private volatile boolean mUseCompaction = DEFAULT_USE_COMPACTION;
    private final Random mRandom = new Random();
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile float mStatsdSampleRate = DEFAULT_STATSD_SAMPLE_RATE;
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile long mFullAnonRssThrottleKb =
            DEFAULT_COMPACT_FULL_RSS_THROTTLE_KB;
    @GuardedBy("mPhenoypeFlagLock")
    @VisibleForTesting volatile long mFullDeltaRssThrottleKb =
            DEFAULT_COMPACT_FULL_DELTA_RSS_THROTTLE_KB;
    @GuardedBy("mPhenoypeFlagLock")
    @VisibleForTesting final Set<Integer> mProcStateThrottle;

    // Handler on which compaction runs.
    private Handler mCompactionHandler;

    // Maps process ID to last compaction statistics for processes that we've fully compacted. Used
    // when evaluating throttles that we only consider for "full" compaction, so we don't store
    // data for "some" compactions.
    private Map<Integer, LastCompactionStats> mLastCompactionStats =
            new LinkedHashMap<Integer, LastCompactionStats>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > 100;
                }
    };

    private int mSomeCompactionCount;
    private int mFullCompactionCount;
    private int mPersistentCompactionCount;
    private int mBfgsCompactionCount;

    public AppCompactor(ActivityManagerService am) {
        mAm = am;
        mCompactionThread = new ServiceThread("CompactionThread",
                THREAD_PRIORITY_FOREGROUND, true);
        mProcStateThrottle = new HashSet<>();
    }

    @VisibleForTesting
    AppCompactor(ActivityManagerService am, PropertyChangedCallbackForTest callback) {
        this(am);
        mTestCallback = callback;
    }

    /**
     * Reads phenotype config to determine whether app compaction is enabled or not and
     * starts the background thread if necessary.
     */
    public void init() {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(), mOnFlagsChangedListener);
        synchronized (mPhenotypeFlagLock) {
            updateUseCompaction();
            updateCompactionActions();
            updateCompactionThrottles();
            updateStatsdSampleRate();
            updateFullRssThrottle();
            updateFullDeltaRssThrottle();
            updateProcStateThrottle();
        }
        Process.setThreadGroupAndCpuset(mCompactionThread.getThreadId(),
                Process.THREAD_GROUP_SYSTEM);
    }

    /**
     * Returns whether compaction is enabled.
     */
    public boolean useCompaction() {
        synchronized (mPhenotypeFlagLock) {
            return mUseCompaction;
        }
    }

    @GuardedBy("mAm")
    void dump(PrintWriter pw) {
        pw.println("AppCompactor settings");
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
            pw.println("  " + KEY_COMPACT_STATSD_SAMPLE_RATE + "=" + mStatsdSampleRate);
            pw.println("  " + KEY_COMPACT_FULL_RSS_THROTTLE_KB + "="
                    + mFullAnonRssThrottleKb);
            pw.println("  " + KEY_COMPACT_FULL_DELTA_RSS_THROTTLE_KB + "="
                    + mFullDeltaRssThrottleKb);
            pw.println("  "  + KEY_COMPACT_PROC_STATE_THROTTLE + "="
                    + Arrays.toString(mProcStateThrottle.toArray(new Integer[0])));

            pw.println("  " + mSomeCompactionCount + " some, " + mFullCompactionCount
                    + " full, " + mPersistentCompactionCount + " persistent, "
                    + mBfgsCompactionCount + " BFGS compactions.");

            pw.println("  Tracking last compaction stats for " + mLastCompactionStats.size()
                    + " processes.");
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

    @GuardedBy("mAm")
    void compactAppSome(ProcessRecord app) {
        app.reqCompactAction = COMPACT_PROCESS_SOME;
        mPendingCompactionProcesses.add(app);
        mCompactionHandler.sendMessage(
            mCompactionHandler.obtainMessage(
                COMPACT_PROCESS_MSG, app.setAdj, app.setProcState));
    }

    @GuardedBy("mAm")
    void compactAppFull(ProcessRecord app) {
        app.reqCompactAction = COMPACT_PROCESS_FULL;
        mPendingCompactionProcesses.add(app);
        mCompactionHandler.sendMessage(
            mCompactionHandler.obtainMessage(
                COMPACT_PROCESS_MSG, app.setAdj, app.setProcState));

    }

    @GuardedBy("mAm")
    void compactAppPersistent(ProcessRecord app) {
        app.reqCompactAction = COMPACT_PROCESS_PERSISTENT;
        mPendingCompactionProcesses.add(app);
        mCompactionHandler.sendMessage(
                mCompactionHandler.obtainMessage(
                    COMPACT_PROCESS_MSG, app.curAdj, app.setProcState));
    }

    @GuardedBy("mAm")
    boolean shouldCompactPersistent(ProcessRecord app, long now) {
        return (app.lastCompactTime == 0
                || (now - app.lastCompactTime) > mCompactThrottlePersistent);
    }

    @GuardedBy("mAm")
    void compactAppBfgs(ProcessRecord app) {
        app.reqCompactAction = COMPACT_PROCESS_BFGS;
        mPendingCompactionProcesses.add(app);
        mCompactionHandler.sendMessage(
                mCompactionHandler.obtainMessage(
                    COMPACT_PROCESS_MSG, app.curAdj, app.setProcState));
    }

    @GuardedBy("mAm")
    boolean shouldCompactBFGS(ProcessRecord app, long now) {
        return (app.lastCompactTime == 0
                || (now - app.lastCompactTime) > mCompactThrottleBFGS);
    }

    @GuardedBy("mAm")
    void compactAllSystem() {
        if (mUseCompaction) {
            mCompactionHandler.sendMessage(mCompactionHandler.obtainMessage(
                                              COMPACT_SYSTEM_MSG));
        }
    }

    private native void compactSystem();

    /**
     * Reads the flag value from DeviceConfig to determine whether app compaction
     * should be enabled, and starts the compaction thread if needed.
     */
    @GuardedBy("mPhenotypeFlagLock")
    private void updateUseCompaction() {
        mUseCompaction = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                    KEY_USE_COMPACTION, DEFAULT_USE_COMPACTION);
        if (mUseCompaction && !mCompactionThread.isAlive()) {
            mCompactionThread.start();
            mCompactionHandler = new MemCompactionHandler();
        }
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

        if (TextUtils.isEmpty(throttleSomeSomeFlag) || TextUtils.isEmpty(throttleSomeFullFlag)
                || TextUtils.isEmpty(throttleFullSomeFlag)
                || TextUtils.isEmpty(throttleFullFullFlag)
                || TextUtils.isEmpty(throttleBFGSFlag)
                || TextUtils.isEmpty(throttlePersistentFlag)) {
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
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateStatsdSampleRate() {
        mStatsdSampleRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_STATSD_SAMPLE_RATE, DEFAULT_STATSD_SAMPLE_RATE);
        mStatsdSampleRate = Math.min(1.0f, Math.max(0.0f, mStatsdSampleRate));
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
        switch(action) {
            case COMPACT_ACTION_NONE_FLAG:
                return COMPACT_ACTION_NONE;
            case COMPACT_ACTION_FILE_FLAG:
                return COMPACT_ACTION_FILE;
            case COMPACT_ACTION_ANON_FLAG:
                return COMPACT_ACTION_ANON;
            case COMPACT_ACTION_FULL_FLAG:
                return COMPACT_ACTION_FULL;
            default:
                return COMPACT_ACTION_NONE;
        }
    }

    private static final class LastCompactionStats {
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
            super(mCompactionThread.getLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case COMPACT_PROCESS_MSG: {
                    long start = SystemClock.uptimeMillis();
                    ProcessRecord proc;
                    int pid;
                    String action;
                    final String name;
                    int pendingAction, lastCompactAction;
                    long lastCompactTime;
                    LastCompactionStats lastCompactionStats;
                    int lastOomAdj = msg.arg1;
                    int procState = msg.arg2;
                    synchronized (mAm) {
                        proc = mPendingCompactionProcesses.remove(0);

                        pendingAction = proc.reqCompactAction;
                        pid = proc.pid;
                        name = proc.processName;

                        // don't compact if the process has returned to perceptible
                        // and this is only a cached/home/prev compaction
                        if ((pendingAction == COMPACT_PROCESS_SOME
                                || pendingAction == COMPACT_PROCESS_FULL)
                                && (proc.setAdj <= ProcessList.PERCEPTIBLE_APP_ADJ)) {
                            if (DEBUG_COMPACTION) {
                                Slog.d(TAG_AM,
                                        "Skipping compaction as process " + name + " is "
                                        + "now perceptible.");
                            }
                            return;
                        }

                        lastCompactAction = proc.lastCompactAction;
                        lastCompactTime = proc.lastCompactTime;
                        // remove rather than get so that insertion order will be updated when we
                        // put the post-compaction stats back into the map.
                        lastCompactionStats = mLastCompactionStats.remove(pid);
                    }

                    if (pid == 0) {
                        // not a real process, either one being launched or one being killed
                        return;
                    }

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
                                        && (start - lastCompactTime
                                                < mCompactThrottleSomeFull))) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping some compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleSomeSome
                                            + "/" + mCompactThrottleSomeFull + " last="
                                            + (start - lastCompactTime) + "ms ago");
                                }
                                return;
                            }
                        } else if (pendingAction == COMPACT_PROCESS_FULL) {
                            if ((lastCompactAction == COMPACT_PROCESS_SOME
                                    && (start - lastCompactTime < mCompactThrottleFullSome))
                                    || (lastCompactAction == COMPACT_PROCESS_FULL
                                        && (start - lastCompactTime
                                                < mCompactThrottleFullFull))) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping full compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleFullSome
                                            + "/" + mCompactThrottleFullFull + " last="
                                            + (start - lastCompactTime) + "ms ago");
                                }
                                return;
                            }
                        } else if (pendingAction == COMPACT_PROCESS_PERSISTENT) {
                            if (start - lastCompactTime < mCompactThrottlePersistent) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping persistent compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottlePersistent
                                            + " last=" + (start - lastCompactTime) + "ms ago");
                                }
                                return;
                            }
                        } else if (pendingAction == COMPACT_PROCESS_BFGS) {
                            if (start - lastCompactTime < mCompactThrottleBFGS) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping bfgs compaction for " + name
                                            + ": too soon. throttle=" + mCompactThrottleBFGS
                                            + " last=" + (start - lastCompactTime) + "ms ago");
                                }
                                return;
                            }
                        }
                    }

                    switch (pendingAction) {
                        case COMPACT_PROCESS_SOME:
                            action = mCompactActionSome;
                            break;
                        // For the time being, treat these as equivalent.
                        case COMPACT_PROCESS_FULL:
                        case COMPACT_PROCESS_PERSISTENT:
                        case COMPACT_PROCESS_BFGS:
                            action = mCompactActionFull;
                            break;
                        default:
                            action = COMPACT_ACTION_NONE;
                            break;
                    }

                    if (COMPACT_ACTION_NONE.equals(action)) {
                        return;
                    }

                    if (mProcStateThrottle.contains(procState)) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM, "Skipping full compaction for process " + name
                                    + "; proc state is " + procState);
                        }
                        return;
                    }

                    long[] rssBefore = Process.getRss(pid);
                    long anonRssBefore = rssBefore[2];

                    if (rssBefore[0] == 0 && rssBefore[1] == 0 && rssBefore[2] == 0
                            && rssBefore[3] == 0) {
                        if (DEBUG_COMPACTION) {
                            Slog.d(TAG_AM, "Skipping compaction for" + "process " + pid
                                    + " with no memory usage. Dead?");
                        }
                        return;
                    }

                    if (action.equals(COMPACT_ACTION_FULL) || action.equals(COMPACT_ACTION_ANON)) {
                        if (mFullAnonRssThrottleKb > 0L
                                && anonRssBefore < mFullAnonRssThrottleKb) {
                            if (DEBUG_COMPACTION) {
                                Slog.d(TAG_AM, "Skipping full compaction for process "
                                        + name + "; anon RSS is too small: " + anonRssBefore
                                        + "KB.");
                            }
                            return;
                        }

                        if (lastCompactionStats != null && mFullDeltaRssThrottleKb > 0L) {
                            long[] lastRss = lastCompactionStats.getRssAfterCompaction();
                            long absDelta = Math.abs(rssBefore[1] - lastRss[1])
                                    + Math.abs(rssBefore[2] - lastRss[2])
                                    + Math.abs(rssBefore[3] - lastRss[3]);
                            if (absDelta <= mFullDeltaRssThrottleKb) {
                                if (DEBUG_COMPACTION) {
                                    Slog.d(TAG_AM, "Skipping full compaction for process "
                                            + name + "; abs delta is too small: " + absDelta
                                            + "KB.");
                                }
                                return;
                            }
                        }
                    }

                    // Now we've passed through all the throttles and are going to compact, update
                    // bookkeeping.
                    switch (pendingAction) {
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

                    try {
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Compact "
                                + ((pendingAction == COMPACT_PROCESS_SOME) ? "some" : "full")
                                + ": " + name);
                        long zramFreeKbBefore = Debug.getZramFreeKb();
                        FileOutputStream fos = new FileOutputStream("/proc/" + pid + "/reclaim");
                        fos.write(action.getBytes());
                        fos.close();
                        long[] rssAfter = Process.getRss(pid);
                        long end = SystemClock.uptimeMillis();
                        long time = end - start;
                        long zramFreeKbAfter = Debug.getZramFreeKb();
                        EventLog.writeEvent(EventLogTags.AM_COMPACT, pid, name, action,
                                rssBefore[0], rssBefore[1], rssBefore[2], rssBefore[3],
                                rssAfter[0] - rssBefore[0], rssAfter[1] - rssBefore[1],
                                rssAfter[2] - rssBefore[2], rssAfter[3] - rssBefore[3], time,
                                lastCompactAction, lastCompactTime, lastOomAdj, procState,
                                zramFreeKbBefore, zramFreeKbAfter - zramFreeKbBefore);

                        // Note that as above not taking mPhenoTypeFlagLock here to avoid locking
                        // on every single compaction for a flag that will seldom change and the
                        // impact of reading the wrong value here is low.
                        if (mRandom.nextFloat() < mStatsdSampleRate) {
                            StatsLog.write(StatsLog.APP_COMPACTED, pid, name, pendingAction,
                                    rssBefore[0], rssBefore[1], rssBefore[2], rssBefore[3],
                                    rssAfter[0], rssAfter[1], rssAfter[2], rssAfter[3], time,
                                    lastCompactAction, lastCompactTime, lastOomAdj,
                                    ActivityManager.processStateAmToProto(procState),
                                    zramFreeKbBefore, zramFreeKbAfter);
                        }

                        synchronized (mAm) {
                            proc.lastCompactTime = end;
                            proc.lastCompactAction = pendingAction;
                        }

                        if (action.equals(COMPACT_ACTION_FULL)
                                || action.equals(COMPACT_ACTION_ANON)) {
                            mLastCompactionStats.put(pid, new LastCompactionStats(rssAfter));
                        }
                    } catch (Exception e) {
                        // nothing to do, presumably the process died
                    } finally {
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }
                    break;
                }
                case COMPACT_SYSTEM_MSG: {
                    Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "compactSystem");
                    compactSystem();
                    Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    break;
                }
            }
        }
    }
}
