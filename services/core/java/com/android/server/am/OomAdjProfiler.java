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
    // Disable profiling for Q. Re-enable once b/130635979 is fixed.
    private static final boolean PROFILING_DISABLED = true;

    @GuardedBy("this")
    private boolean mOnBattery;
    @GuardedBy("this")
    private boolean mScreenOff;

    @GuardedBy("this")
    private long mOomAdjStartTimeMs;
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

    void batteryPowerChanged(boolean onBattery) {
        if (PROFILING_DISABLED) {
            return;
        }
        synchronized (this) {
            scheduleSystemServerCpuTimeUpdate();
            mOnBattery = onBattery;
        }
    }

    void onWakefulnessChanged(int wakefulness) {
        if (PROFILING_DISABLED) {
            return;
        }
        synchronized (this) {
            scheduleSystemServerCpuTimeUpdate();
            mScreenOff = wakefulness != PowerManagerInternal.WAKEFULNESS_AWAKE;
        }
    }

    void oomAdjStarted() {
        if (PROFILING_DISABLED) {
            return;
        }
        synchronized (this) {
            mOomAdjStartTimeMs = SystemClock.currentThreadTimeMillis();
            mOomAdjStarted = true;
        }
    }

    void oomAdjEnded() {
        if (PROFILING_DISABLED) {
            return;
        }
        synchronized (this) {
            if (!mOomAdjStarted) {
                return;
            }
            mOomAdjRunTime.addCpuTimeMs(SystemClock.currentThreadTimeMillis() - mOomAdjStartTimeMs);
        }
    }

    private void scheduleSystemServerCpuTimeUpdate() {
        if (PROFILING_DISABLED) {
            return;
        }
        synchronized (this) {
            if (mSystemServerCpuTimeUpdateScheduled) {
                return;
            }
            mSystemServerCpuTimeUpdateScheduled = true;
            BackgroundThread.getHandler().sendMessage(PooledLambda.obtainMessage(
                    OomAdjProfiler::updateSystemServerCpuTime,
                    this, mOnBattery, mScreenOff));
        }
    }

    private void updateSystemServerCpuTime(boolean onBattery, boolean screenOff) {
        if (PROFILING_DISABLED) {
            return;
        }
        final long cpuTimeMs = mProcessCpuTracker.getCpuTimeForPid(Process.myPid());
        synchronized (this) {
            mSystemServerCpuTime.addCpuTimeMs(
                    cpuTimeMs - mLastSystemServerCpuTimeMs, onBattery, screenOff);
            mLastSystemServerCpuTimeMs = cpuTimeMs;
            mSystemServerCpuTimeUpdateScheduled = false;
            notifyAll();
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
        if (PROFILING_DISABLED) {
            return;
        }
        synchronized (this) {
            if (mSystemServerCpuTimeUpdateScheduled) {
                while (mSystemServerCpuTimeUpdateScheduled) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } else {
                updateSystemServerCpuTime(mOnBattery, mScreenOff);
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
        }
    }

    private class CpuTimes {
        private long mOnBatteryTimeMs;
        private long mOnBatteryScreenOffTimeMs;

        public void addCpuTimeMs(long cpuTimeMs) {
            addCpuTimeMs(cpuTimeMs, mOnBattery, mScreenOff);
        }

        public void addCpuTimeMs(long cpuTimeMs, boolean onBattery, boolean screenOff) {
            if (onBattery) {
                mOnBatteryTimeMs += cpuTimeMs;
                if (screenOff) {
                    mOnBatteryScreenOffTimeMs += cpuTimeMs;
                }
            }
        }

        public boolean isEmpty() {
            return mOnBatteryTimeMs == 0 && mOnBatteryScreenOffTimeMs == 0;
        }

        public String toString() {
            return "[" + mOnBatteryTimeMs + "," + mOnBatteryScreenOffTimeMs + "]";
        }
    }
}
