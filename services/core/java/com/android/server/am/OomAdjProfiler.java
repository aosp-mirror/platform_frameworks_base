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

import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.SystemClock;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.util.RingBuffer;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.PrintWriter;

public class OomAdjProfiler {
    private static final int MSG_UPDATE_CPU_TIME = 42;

    @GuardedBy("this")
    private boolean mOnBattery;
    @GuardedBy("this")
    private boolean mScreenOff;

    /** The value of {@link #mOnBattery} when the CPU time update was last scheduled. */
    @GuardedBy("this")
    private boolean mLastScheduledOnBattery;
    /** The value of {@link #mScreenOff} when the CPU time update was last scheduled. */
    @GuardedBy("this")
    private boolean mLastScheduledScreenOff;

    @GuardedBy("this")
    private long mOomAdjStartTimeUs;
    @GuardedBy("this")
    private boolean mOomAdjStarted;

    @GuardedBy("this")
    private CpuTimes mOomAdjRunTime = new CpuTimes();
    @GuardedBy("this")
    private CpuTimes mSystemServerCpuTime = new CpuTimes();

    @GuardedBy("this")
    private long mLastSystemServerCpuTimeMs;
    @GuardedBy("this")
    private boolean mSystemServerCpuTimeUpdateScheduled;
    private final ProcessCpuTracker mProcessCpuTracker = new ProcessCpuTracker(false);

    @GuardedBy("this")
    final RingBuffer<CpuTimes> mOomAdjRunTimesHist = new RingBuffer<>(CpuTimes.class, 10);
    @GuardedBy("this")
    final RingBuffer<CpuTimes> mSystemServerCpuTimesHist = new RingBuffer<>(CpuTimes.class, 10);

    @GuardedBy("this")
    private long mTotalOomAdjRunTimeUs;
    @GuardedBy("this")
    private int mTotalOomAdjCalls;

    void batteryPowerChanged(boolean onBattery) {
        synchronized (this) {
            scheduleSystemServerCpuTimeUpdate();
            mOnBattery = onBattery;
        }
    }

    void onWakefulnessChanged(int wakefulness) {
        synchronized (this) {
            scheduleSystemServerCpuTimeUpdate();
            mScreenOff = wakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE;
        }
    }

    void oomAdjStarted() {
        synchronized (this) {
            mOomAdjStartTimeUs = SystemClock.currentThreadTimeMicro();
            mOomAdjStarted = true;
        }
    }

    void oomAdjEnded() {
        synchronized (this) {
            if (!mOomAdjStarted) {
                return;
            }
            long elapsedUs = SystemClock.currentThreadTimeMicro() - mOomAdjStartTimeUs;
            mOomAdjRunTime.addCpuTimeUs(elapsedUs);
            mTotalOomAdjRunTimeUs += elapsedUs;
            mTotalOomAdjCalls++;
        }
    }

    private void scheduleSystemServerCpuTimeUpdate() {
        synchronized (this) {
            if (mSystemServerCpuTimeUpdateScheduled) {
                return;
            }
            mLastScheduledOnBattery = mOnBattery;
            mLastScheduledScreenOff = mScreenOff;
            mSystemServerCpuTimeUpdateScheduled = true;
            Message scheduledMessage = PooledLambda.obtainMessage(
                    OomAdjProfiler::updateSystemServerCpuTime,
                    this, mLastScheduledOnBattery, mLastScheduledScreenOff, true);
            scheduledMessage.setWhat(MSG_UPDATE_CPU_TIME);

            BackgroundThread.getHandler().sendMessage(scheduledMessage);
        }
    }

    private void updateSystemServerCpuTime(boolean onBattery, boolean screenOff,
            boolean onlyIfScheduled) {
        final long cpuTimeMs = mProcessCpuTracker.getCpuTimeForPid(Process.myPid());
        synchronized (this) {
            if (onlyIfScheduled && !mSystemServerCpuTimeUpdateScheduled) {
                return;
            }
            mSystemServerCpuTime.addCpuTimeMs(
                    cpuTimeMs - mLastSystemServerCpuTimeMs, onBattery, screenOff);
            mLastSystemServerCpuTimeMs = cpuTimeMs;
            mSystemServerCpuTimeUpdateScheduled = false;
        }
    }

    void reset() {
        synchronized (this) {
            if (mSystemServerCpuTime.isEmpty()) {
                return;
            }
            mOomAdjRunTimesHist.append(mOomAdjRunTime);
            mSystemServerCpuTimesHist.append(mSystemServerCpuTime);
            mOomAdjRunTime = new CpuTimes();
            mSystemServerCpuTime = new CpuTimes();
        }
    }

    void dump(PrintWriter pw) {
        synchronized (this) {
            if (mSystemServerCpuTimeUpdateScheduled) {
                // Cancel the scheduled update since we're going to update it here instead.
                BackgroundThread.getHandler().removeMessages(MSG_UPDATE_CPU_TIME);
                // Make sure the values are attributed to the right states.
                updateSystemServerCpuTime(mLastScheduledOnBattery, mLastScheduledScreenOff, false);
            } else {
                updateSystemServerCpuTime(mOnBattery, mScreenOff, false);
            }

            pw.println("System server and oomAdj runtimes (ms) in recent battery sessions "
                    + "(most recent first):");
            if (!mSystemServerCpuTime.isEmpty()) {
                pw.print("  ");
                pw.print("system_server=");
                pw.print(mSystemServerCpuTime);
                pw.print("  ");
                pw.print("oom_adj=");
                pw.println(mOomAdjRunTime);
            }
            final CpuTimes[] systemServerCpuTimes = mSystemServerCpuTimesHist.toArray();
            final CpuTimes[] oomAdjRunTimes = mOomAdjRunTimesHist.toArray();
            for (int i = oomAdjRunTimes.length - 1; i >= 0; --i) {
                pw.print("  ");
                pw.print("system_server=");
                pw.print(systemServerCpuTimes[i]);
                pw.print("  ");
                pw.print("oom_adj=");
                pw.println(oomAdjRunTimes[i]);
            }
            if (mTotalOomAdjCalls != 0) {
                pw.println("System server total oomAdj runtimes (us) since boot:");
                pw.print("  cpu time spent=");
                pw.print(mTotalOomAdjRunTimeUs);
                pw.print("  number of calls=");
                pw.print(mTotalOomAdjCalls);
                pw.print("  average=");
                pw.println(mTotalOomAdjRunTimeUs / mTotalOomAdjCalls);
            }
        }
    }

    private class CpuTimes {
        private long mOnBatteryTimeUs;
        private long mOnBatteryScreenOffTimeUs;

        public void addCpuTimeMs(long cpuTimeMs) {
            addCpuTimeUs(cpuTimeMs * 1000, mOnBattery, mScreenOff);
        }

        public void addCpuTimeMs(long cpuTimeMs, boolean onBattery, boolean screenOff) {
            addCpuTimeUs(cpuTimeMs * 1000, onBattery, screenOff);
        }

        public void addCpuTimeUs(long cpuTimeUs) {
            addCpuTimeUs(cpuTimeUs, mOnBattery, mScreenOff);
        }

        public void addCpuTimeUs(long cpuTimeUs, boolean onBattery, boolean screenOff) {
            if (onBattery) {
                mOnBatteryTimeUs += cpuTimeUs;
                if (screenOff) {
                    mOnBatteryScreenOffTimeUs += cpuTimeUs;
                }
            }
        }

        public boolean isEmpty() {
            return mOnBatteryTimeUs == 0 && mOnBatteryScreenOffTimeUs == 0;
        }

        public String toString() {
            return "[" + (mOnBatteryTimeUs / 1000) + ","
                    + (mOnBatteryScreenOffTimeUs / 1000) + "]";
        }
    }
}
