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

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.os.Debug;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.OnPropertyChangedListener;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.StatsLog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Random;

public final class AppCompactor {

    // Flags stored in the DeviceConfig API.
    @VisibleForTesting static final String KEY_USE_COMPACTION = "use_compaction";
    @VisibleForTesting static final String KEY_COMPACT_ACTION_1 = "compact_action_1";
    @VisibleForTesting static final String KEY_COMPACT_ACTION_2 = "compact_action_2";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_1 = "compact_throttle_1";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_2 = "compact_throttle_2";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_3 = "compact_throttle_3";
    @VisibleForTesting static final String KEY_COMPACT_THROTTLE_4 = "compact_throttle_4";
    @VisibleForTesting static final String KEY_COMPACT_STATSD_SAMPLE_RATE =
            "compact_statsd_sample_rate";

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
    // The sampling rate to push app compaction events into statsd for upload.
    @VisibleForTesting static final float DEFAULT_STATSD_SAMPLE_RATE = 0.1f;

    @VisibleForTesting
    interface PropertyChangedCallbackForTest {
        void onPropertyChanged();
    }
    private PropertyChangedCallbackForTest mTestCallback;

    // Handler constants.
    static final int COMPACT_PROCESS_SOME = 1;
    static final int COMPACT_PROCESS_FULL = 2;
    static final int COMPACT_PROCESS_MSG = 1;

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
    private final OnPropertyChangedListener mOnFlagsChangedListener =
            new OnPropertyChangedListener() {
                @Override
                public void onPropertyChanged(String namespace, String name, String value) {
                    synchronized (mPhenotypeFlagLock) {
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
    private volatile boolean mUseCompaction = DEFAULT_USE_COMPACTION;

    private final Random mRandom = new Random();
    @GuardedBy("mPhenotypeFlagLock")
    @VisibleForTesting volatile float mStatsdSampleRate = DEFAULT_STATSD_SAMPLE_RATE;

    // Handler on which compaction runs.
    private Handler mCompactionHandler;

    public AppCompactor(ActivityManagerService am) {
        mAm = am;
        mCompactionThread = new ServiceThread("CompactionThread",
                THREAD_PRIORITY_FOREGROUND, true);
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
        DeviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                ActivityThread.currentApplication().getMainExecutor(), mOnFlagsChangedListener);
        synchronized (mPhenotypeFlagLock) {
            updateUseCompaction();
            updateCompactionActions();
            updateCompactionThrottles();
            updateStatsdSampleRate();
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
            pw.println("  " + KEY_COMPACT_STATSD_SAMPLE_RATE + "=" + mStatsdSampleRate);
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

    /**
     * Reads the flag value from DeviceConfig to determine whether app compaction
     * should be enabled, and starts/stops the compaction thread as needed.
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

        if (TextUtils.isEmpty(throttleSomeSomeFlag) || TextUtils.isEmpty(throttleSomeFullFlag)
                || TextUtils.isEmpty(throttleFullSomeFlag)
                || TextUtils.isEmpty(throttleFullFullFlag)) {
            // Set defaults for all if any are not set.
            useThrottleDefaults = true;
        } else {
            try {
                mCompactThrottleSomeSome = Integer.parseInt(throttleSomeSomeFlag);
                mCompactThrottleSomeFull = Integer.parseInt(throttleSomeFullFlag);
                mCompactThrottleFullSome = Integer.parseInt(throttleFullSomeFlag);
                mCompactThrottleFullFull = Integer.parseInt(throttleFullFullFlag);
            } catch (NumberFormatException e) {
                useThrottleDefaults = true;
            }
        }

        if (useThrottleDefaults) {
            mCompactThrottleSomeSome = DEFAULT_COMPACT_THROTTLE_1;
            mCompactThrottleSomeFull = DEFAULT_COMPACT_THROTTLE_2;
            mCompactThrottleFullSome = DEFAULT_COMPACT_THROTTLE_3;
            mCompactThrottleFullFull = DEFAULT_COMPACT_THROTTLE_4;
        }
    }

    @GuardedBy("mPhenotypeFlagLock")
    private void updateStatsdSampleRate() {
        mStatsdSampleRate = DeviceConfig.getFloat(DeviceConfig.NAMESPACE_ACTIVITY_MANAGER,
                KEY_COMPACT_STATSD_SAMPLE_RATE, DEFAULT_STATSD_SAMPLE_RATE);
        mStatsdSampleRate = Math.min(1.0f, Math.max(0.0f, mStatsdSampleRate));
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
                    synchronized (mAm) {
                        proc = mPendingCompactionProcesses.remove(0);

                        // don't compact if the process has returned to perceptible
                        if (proc.setAdj <= ProcessList.PERCEPTIBLE_APP_ADJ) {
                            return;
                        }

                        pid = proc.pid;
                        name = proc.processName;
                        pendingAction = proc.reqCompactAction;
                        lastCompactAction = proc.lastCompactAction;
                        lastCompactTime = proc.lastCompactTime;
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
                    if (pendingAction == COMPACT_PROCESS_SOME) {
                        if ((lastCompactAction == COMPACT_PROCESS_SOME
                                  && (start - lastCompactTime < mCompactThrottleSomeSome))
                                  || (lastCompactAction == COMPACT_PROCESS_FULL
                                      && (start - lastCompactTime
                                          < mCompactThrottleSomeFull))) {
                            return;
                        }
                    } else {
                        if ((lastCompactAction == COMPACT_PROCESS_SOME
                                  && (start - lastCompactTime < mCompactThrottleFullSome))
                                  || (lastCompactAction == COMPACT_PROCESS_FULL
                                      && (start - lastCompactTime
                                          < mCompactThrottleFullFull))) {
                            return;
                        }
                    }

                    if (pendingAction == COMPACT_PROCESS_SOME) {
                        action = mCompactActionSome;
                    } else {
                        action = mCompactActionFull;
                    }

                    if (action.equals(COMPACT_ACTION_NONE)) {
                        return;
                    }

                    try {
                        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "Compact "
                                + ((pendingAction == COMPACT_PROCESS_SOME) ? "some" : "full")
                                + ": " + name);
                        long zramFreeKbBefore = Debug.getZramFreeKb();
                        long[] rssBefore = Process.getRss(pid);
                        FileOutputStream fos = new FileOutputStream("/proc/" + pid + "/reclaim");
                        fos.write(action.getBytes());
                        fos.close();
                        long[] rssAfter = Process.getRss(pid);
                        long end = SystemClock.uptimeMillis();
                        long time = end - start;
                        long zramFreeKbAfter = Debug.getZramFreeKb();
                        EventLog.writeEvent(EventLogTags.AM_COMPACT, pid, name, action,
                                rssBefore[0], rssBefore[1], rssBefore[2], rssBefore[3],
                                rssAfter[0], rssAfter[1], rssAfter[2], rssAfter[3], time,
                                lastCompactAction, lastCompactTime, msg.arg1, msg.arg2,
                                zramFreeKbBefore, zramFreeKbAfter);
                        // Note that as above not taking mPhenoTypeFlagLock here to avoid locking
                        // on every single compaction for a flag that will seldom change and the
                        // impact of reading the wrong value here is low.
                        if (mRandom.nextFloat() < mStatsdSampleRate) {
                            StatsLog.write(StatsLog.APP_COMPACTED, pid, name, pendingAction,
                                    rssBefore[0], rssBefore[1], rssBefore[2], rssBefore[3],
                                    rssAfter[0], rssAfter[1], rssAfter[2], rssAfter[3], time,
                                    lastCompactAction, lastCompactTime, msg.arg1,
                                    ActivityManager.processStateAmToProto(msg.arg2),
                                    zramFreeKbBefore, zramFreeKbAfter);
                        }
                        synchronized (mAm) {
                            proc.lastCompactTime = end;
                            proc.lastCompactAction = pendingAction;
                        }
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    } catch (Exception e) {
                        // nothing to do, presumably the process died
                        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);
                    }
                }
            }
        }
    }
}
