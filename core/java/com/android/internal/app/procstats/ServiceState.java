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


import android.os.Parcel;
import android.os.SystemClock;
import android.service.procstats.PackageServiceOperationStatsProto;
import android.service.procstats.PackageServiceStatsProto;
import android.service.procstats.ProcessStatsEnums;
import android.util.Slog;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import static com.android.internal.app.procstats.ProcessStats.STATE_NOTHING;

import java.io.PrintWriter;

public final class ServiceState {
    private static final String TAG = "ProcessStats";
    private static final boolean DEBUG = false;

    public static final int SERVICE_RUN = 0;
    public static final int SERVICE_STARTED = 1;
    public static final int SERVICE_BOUND = 2;
    public static final int SERVICE_EXEC = 3;
    public static final int SERVICE_FOREGROUND = 4;
    public static final int SERVICE_COUNT = 5;

    private final String mPackage;
    private final String mProcessName;
    private final String mName;
    private final DurationsTable mDurations;

    private ProcessState mProc;
    private Object mOwner;

    private int mRunCount;
    private int mRunState = STATE_NOTHING;
    private long mRunStartTime;

    private boolean mStarted;
    private boolean mRestarting;
    private int mStartedCount;
    private int mStartedState = STATE_NOTHING;
    private long mStartedStartTime;

    private int mBoundCount;
    private int mBoundState = STATE_NOTHING;
    private long mBoundStartTime;

    private int mExecCount;
    private int mExecState = STATE_NOTHING;
    private long mExecStartTime;

    private int mForegroundCount;
    private int mForegroundState = STATE_NOTHING;
    private long mForegroundStartTime;

    public ServiceState(ProcessStats processStats, String pkg, String name,
            String processName, ProcessState proc) {
        mPackage = pkg;
        mName = name;
        mProcessName = processName;
        mProc = proc;
        mDurations = new DurationsTable(processStats.mTableData);
    }

    public String getPackage() {
        return mPackage;
    }
    
    public String getProcessName() {
        return mProcessName;
    }

    public String getName() {
        return mName;
    }

    public ProcessState getProcess() {
        return mProc;
    }

    public void setProcess(ProcessState proc) {
        mProc = proc;
    }

    public void setMemFactor(int memFactor, long now) {
        if (isRestarting()) {
            setRestarting(true, memFactor, now);
        } else if (isInUse()) {
            if (mStartedState != ProcessStats.STATE_NOTHING) {
                setStarted(true, memFactor, now);
            }
            if (mBoundState != ProcessStats.STATE_NOTHING) {
                setBound(true, memFactor, now);
            }
            if (mExecState != ProcessStats.STATE_NOTHING) {
                setExecuting(true, memFactor, now);
            }
            if (mForegroundState != ProcessStats.STATE_NOTHING) {
                setForeground(true, memFactor, now);
            }
        }
    }

    public void applyNewOwner(Object newOwner) {
        if (mOwner != newOwner) {
            if (mOwner == null) {
                mOwner = newOwner;
                mProc.incActiveServices(mName);
            } else {
                // There was already an old owner, reset this object for its
                // new owner.
                mOwner = newOwner;
                if (mStarted || mBoundState != STATE_NOTHING || mExecState != STATE_NOTHING
                        || mForegroundState != STATE_NOTHING) {
                    long now = SystemClock.uptimeMillis();
                    if (mStarted) {
                        if (DEBUG) Slog.d(TAG, "Service has new owner " + newOwner
                                + " from " + mOwner + " while started: pkg="
                                + mPackage + " service=" + mName + " proc=" + mProc);
                        setStarted(false, 0, now);
                    }
                    if (mBoundState != STATE_NOTHING) {
                        if (DEBUG) Slog.d(TAG, "Service has new owner " + newOwner
                                + " from " + mOwner + " while bound: pkg="
                                + mPackage + " service=" + mName + " proc=" + mProc);
                        setBound(false, 0, now);
                    }
                    if (mExecState != STATE_NOTHING) {
                        if (DEBUG) Slog.d(TAG, "Service has new owner " + newOwner
                                + " from " + mOwner + " while executing: pkg="
                                + mPackage + " service=" + mName + " proc=" + mProc);
                        setExecuting(false, 0, now);
                    }
                    if (mForegroundState != STATE_NOTHING) {
                        if (DEBUG) Slog.d(TAG, "Service has new owner " + newOwner
                                + " from " + mOwner + " while foreground: pkg="
                                + mPackage + " service=" + mName + " proc=" + mProc);
                        setForeground(false, 0, now);
                    }
                }
            }
        }
    }

    public void clearCurrentOwner(Object owner, boolean silently) {
        if (mOwner == owner) {
            mProc.decActiveServices(mName);
            if (mStarted || mBoundState != STATE_NOTHING || mExecState != STATE_NOTHING
                    || mForegroundState != STATE_NOTHING) {
                long now = SystemClock.uptimeMillis();
                if (mStarted) {
                    if (!silently) {
                        Slog.wtfStack(TAG, "Service owner " + owner
                                + " cleared while started: pkg=" + mPackage + " service="
                                + mName + " proc=" + mProc);
                    }
                    setStarted(false, 0, now);
                }
                if (mBoundState != STATE_NOTHING) {
                    if (!silently) {
                        Slog.wtfStack(TAG, "Service owner " + owner
                                + " cleared while bound: pkg=" + mPackage + " service="
                                + mName + " proc=" + mProc);
                    }
                    setBound(false, 0, now);
                }
                if (mExecState != STATE_NOTHING) {
                    if (!silently) {
                        Slog.wtfStack(TAG, "Service owner " + owner
                                + " cleared while exec: pkg=" + mPackage + " service="
                                + mName + " proc=" + mProc);
                    }
                    setExecuting(false, 0, now);
                }
                if (mForegroundState != STATE_NOTHING) {
                    if (!silently) {
                        Slog.wtfStack(TAG, "Service owner " + owner
                                + " cleared while foreground: pkg=" + mPackage + " service="
                                + mName + " proc=" + mProc);
                    }
                    setForeground(false, 0, now);
                }
            }
            mOwner = null;
        }
    }

    public boolean isInUse() {
        return mOwner != null || mRestarting;
    }

    public boolean isRestarting() {
        return mRestarting;
    }

    public void add(ServiceState other) {
        mDurations.addDurations(other.mDurations);
        mRunCount += other.mRunCount;
        mStartedCount += other.mStartedCount;
        mBoundCount += other.mBoundCount;
        mExecCount += other.mExecCount;
        mForegroundCount += other.mForegroundCount;
    }

    public void resetSafely(long now) {
        mDurations.resetTable();
        mRunCount = mRunState != STATE_NOTHING ? 1 : 0;
        mStartedCount = mStartedState != STATE_NOTHING ? 1 : 0;
        mBoundCount = mBoundState != STATE_NOTHING ? 1 : 0;
        mExecCount = mExecState != STATE_NOTHING ? 1 : 0;
        mForegroundCount = mForegroundState != STATE_NOTHING ? 1 : 0;
        mRunStartTime = mStartedStartTime = mBoundStartTime = mExecStartTime =
                mForegroundStartTime = now;
    }

    public void writeToParcel(Parcel out, long now) {
        mDurations.writeToParcel(out);
        out.writeInt(mRunCount);
        out.writeInt(mStartedCount);
        out.writeInt(mBoundCount);
        out.writeInt(mExecCount);
        out.writeInt(mForegroundCount);
    }

    public boolean readFromParcel(Parcel in) {
        if (!mDurations.readFromParcel(in)) {
            return false;
        }
        mRunCount = in.readInt();
        mStartedCount = in.readInt();
        mBoundCount = in.readInt();
        mExecCount = in.readInt();
        mForegroundCount = in.readInt();
        return true;
    }

    public void commitStateTime(long now) {
        if (mRunState != STATE_NOTHING) {
            mDurations.addDuration(SERVICE_RUN + (mRunState*SERVICE_COUNT),
                    now - mRunStartTime);
            mRunStartTime = now;
        }
        if (mStartedState != STATE_NOTHING) {
            mDurations.addDuration(SERVICE_STARTED + (mStartedState*SERVICE_COUNT),
                    now - mStartedStartTime);
            mStartedStartTime = now;
        }
        if (mBoundState != STATE_NOTHING) {
            mDurations.addDuration(SERVICE_BOUND + (mBoundState*SERVICE_COUNT),
                    now - mBoundStartTime);
            mBoundStartTime = now;
        }
        if (mExecState != STATE_NOTHING) {
            mDurations.addDuration(SERVICE_EXEC + (mExecState*SERVICE_COUNT),
                    now - mExecStartTime);
            mExecStartTime = now;
        }
        if (mForegroundState != STATE_NOTHING) {
            mDurations.addDuration(SERVICE_FOREGROUND + (mForegroundState*SERVICE_COUNT),
                    now - mForegroundStartTime);
            mForegroundStartTime = now;
        }
    }

    private void updateRunning(int memFactor, long now) {
        final int state = (mStartedState != STATE_NOTHING || mBoundState != STATE_NOTHING
                || mExecState != STATE_NOTHING || mForegroundState != STATE_NOTHING)
                ? memFactor : STATE_NOTHING;
        if (mRunState != state) {
            if (mRunState != STATE_NOTHING) {
                mDurations.addDuration(SERVICE_RUN + (mRunState*SERVICE_COUNT),
                        now - mRunStartTime);
            } else if (state != STATE_NOTHING) {
                mRunCount++;
            }
            mRunState = state;
            mRunStartTime = now;
        }
    }

    public void setStarted(boolean started, int memFactor, long now) {
        if (mOwner == null) {
            Slog.wtf(TAG, "Starting service " + this + " without owner");
        }
        mStarted = started;
        updateStartedState(memFactor, now);
    }

    public void setRestarting(boolean restarting, int memFactor, long now) {
        mRestarting = restarting;
        updateStartedState(memFactor, now);
    }

    public void updateStartedState(int memFactor, long now) {
        final boolean wasStarted = mStartedState != STATE_NOTHING;
        final boolean started = mStarted || mRestarting;
        final int state = started ? memFactor : STATE_NOTHING;
        if (mStartedState != state) {
            if (mStartedState != STATE_NOTHING) {
                mDurations.addDuration(SERVICE_STARTED + (mStartedState*SERVICE_COUNT),
                        now - mStartedStartTime);
            } else if (started) {
                mStartedCount++;
            }
            mStartedState = state;
            mStartedStartTime = now;
            mProc = mProc.pullFixedProc(mPackage);
            if (wasStarted != started) {
                if (started) {
                    mProc.incStartedServices(memFactor, now, mName);
                } else {
                    mProc.decStartedServices(memFactor, now, mName);
                }
            }
            updateRunning(memFactor, now);
        }
    }

    public void setBound(boolean bound, int memFactor, long now) {
        if (mOwner == null) {
            Slog.wtf(TAG, "Binding service " + this + " without owner");
        }
        final int state = bound ? memFactor : STATE_NOTHING;
        if (mBoundState != state) {
            if (mBoundState != STATE_NOTHING) {
                mDurations.addDuration(SERVICE_BOUND + (mBoundState*SERVICE_COUNT),
                        now - mBoundStartTime);
            } else if (bound) {
                mBoundCount++;
            }
            mBoundState = state;
            mBoundStartTime = now;
            updateRunning(memFactor, now);
        }
    }

    public void setExecuting(boolean executing, int memFactor, long now) {
        if (mOwner == null) {
            Slog.wtf(TAG, "Executing service " + this + " without owner");
        }
        final int state = executing ? memFactor : STATE_NOTHING;
        if (mExecState != state) {
            if (mExecState != STATE_NOTHING) {
                mDurations.addDuration(SERVICE_EXEC + (mExecState*SERVICE_COUNT),
                        now - mExecStartTime);
            } else if (executing) {
                mExecCount++;
            }
            mExecState = state;
            mExecStartTime = now;
            updateRunning(memFactor, now);
        }
    }

    public void setForeground(boolean foreground, int memFactor, long now) {
        if (mOwner == null) {
            Slog.wtf(TAG, "Foregrounding service " + this + " without owner");
        }
        final int state = foreground ? memFactor : STATE_NOTHING;
        if (mForegroundState != state) {
            if (mForegroundState != STATE_NOTHING) {
                mDurations.addDuration(SERVICE_FOREGROUND + (mForegroundState*SERVICE_COUNT),
                        now - mForegroundStartTime);
            } else if (foreground) {
                mForegroundCount++;
            }
            mForegroundState = state;
            mForegroundStartTime = now;
            updateRunning(memFactor, now);
        }
    }

    public long getDuration(int opType, int curState, long startTime, int memFactor,
            long now) {
        int state = opType + (memFactor*SERVICE_COUNT);
        long time = mDurations.getValueForId((byte)state);
        if (curState == memFactor) {
            time += now - startTime;
        }
        return time;
    }

    public void dumpStats(PrintWriter pw, String prefix, String prefixInner, String headerPrefix,
            long now, long totalTime, boolean dumpSummary, boolean dumpAll) {
        dumpStats(pw, prefix, prefixInner, headerPrefix, "Running",
                mRunCount, ServiceState.SERVICE_RUN, mRunState,
                mRunStartTime, now, totalTime, !dumpSummary || dumpAll);
        dumpStats(pw, prefix, prefixInner, headerPrefix, "Started",
                mStartedCount, ServiceState.SERVICE_STARTED, mStartedState,
                mStartedStartTime, now, totalTime, !dumpSummary || dumpAll);
        dumpStats(pw, prefix, prefixInner, headerPrefix, "Foreground",
                mForegroundCount, ServiceState.SERVICE_FOREGROUND, mForegroundState,
                mForegroundStartTime, now, totalTime, !dumpSummary || dumpAll);
        dumpStats(pw, prefix, prefixInner, headerPrefix, "Bound",
                mBoundCount, ServiceState.SERVICE_BOUND, mBoundState,
                mBoundStartTime, now, totalTime, !dumpSummary || dumpAll);
        dumpStats(pw, prefix, prefixInner, headerPrefix, "Executing",
                mExecCount, ServiceState.SERVICE_EXEC, mExecState,
                mExecStartTime, now, totalTime, !dumpSummary || dumpAll);
        if (dumpAll) {
            if (mOwner != null) {
                pw.print("        mOwner="); pw.println(mOwner);
            }
            if (mStarted || mRestarting) {
                pw.print("        mStarted="); pw.print(mStarted);
                pw.print(" mRestarting="); pw.println(mRestarting);
            }
        }
    }

    private void dumpStats(PrintWriter pw, String prefix, String prefixInner,
            String headerPrefix, String header,
            int count, int serviceType, int state, long startTime, long now, long totalTime,
            boolean dumpAll) {
        if (count != 0) {
            if (dumpAll) {
                pw.print(prefix); pw.print(header);
                pw.print(" op count "); pw.print(count); pw.println(":");
                dumpTime(pw, prefixInner, serviceType, state, startTime, now);
            } else {
                long myTime = dumpTimeInternal(null, null, serviceType, state, startTime, now,
                        true);
                pw.print(prefix); pw.print(headerPrefix); pw.print(header);
                pw.print(" count "); pw.print(count);
                pw.print(" / time ");
                boolean isRunning = myTime < 0;
                if (isRunning) {
                    myTime = -myTime;
                }
                DumpUtils.printPercent(pw, (double)myTime/(double)totalTime);
                if (isRunning) {
                    pw.print(" (running)");
                }
                pw.println();
            }
        }
    }

    public long dumpTime(PrintWriter pw, String prefix,
            int serviceType, int curState, long curStartTime, long now) {
        return dumpTimeInternal(pw, prefix, serviceType, curState, curStartTime, now, false);
    }

    long dumpTimeInternal(PrintWriter pw, String prefix,
            int serviceType, int curState, long curStartTime, long now, boolean negativeIfRunning) {
        long totalTime = 0;
        int printedScreen = -1;
        boolean isRunning = false;
        for (int iscreen=0; iscreen<ProcessStats.ADJ_COUNT; iscreen+=ProcessStats.ADJ_SCREEN_MOD) {
            int printedMem = -1;
            for (int imem=0; imem<ProcessStats.ADJ_MEM_FACTOR_COUNT; imem++) {
                int state = imem+iscreen;
                long time = getDuration(serviceType, curState, curStartTime, state, now);
                String running = "";
                if (curState == state && pw != null) {
                    running = " (running)";
                    isRunning = true;
                }
                if (time != 0) {
                    if (pw != null) {
                        pw.print(prefix);
                        DumpUtils.printScreenLabel(pw, printedScreen != iscreen
                                ? iscreen : STATE_NOTHING);
                        printedScreen = iscreen;
                        DumpUtils.printMemLabel(pw, printedMem != imem ? imem : STATE_NOTHING,
                                (char)0);
                        printedMem = imem;
                        pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println(running);
                    }
                    totalTime += time;
                }
            }
        }
        if (totalTime != 0 && pw != null) {
            pw.print(prefix);
            pw.print("    TOTAL: ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
        return (isRunning && negativeIfRunning) ? -totalTime : totalTime;
    }

    public void dumpTimesCheckin(PrintWriter pw, String pkgName, int uid, long vers,
            String serviceName, long now) {
        dumpTimeCheckin(pw, "pkgsvc-run", pkgName, uid, vers, serviceName,
                ServiceState.SERVICE_RUN, mRunCount, mRunState, mRunStartTime, now);
        dumpTimeCheckin(pw, "pkgsvc-start", pkgName, uid, vers, serviceName,
                ServiceState.SERVICE_STARTED, mStartedCount, mStartedState, mStartedStartTime, now);
        dumpTimeCheckin(pw, "pkgsvc-fg", pkgName, uid, vers, serviceName,
                ServiceState.SERVICE_FOREGROUND, mForegroundCount, mForegroundState,
                mForegroundStartTime, now);
        dumpTimeCheckin(pw, "pkgsvc-bound", pkgName, uid, vers, serviceName,
                ServiceState.SERVICE_BOUND, mBoundCount, mBoundState, mBoundStartTime, now);
        dumpTimeCheckin(pw, "pkgsvc-exec", pkgName, uid, vers, serviceName,
                ServiceState.SERVICE_EXEC, mExecCount, mExecState, mExecStartTime, now);
    }

    private void dumpTimeCheckin(PrintWriter pw, String label, String packageName,
            int uid, long vers, String serviceName, int serviceType, int opCount,
            int curState, long curStartTime, long now) {
        if (opCount <= 0) {
            return;
        }
        pw.print(label);
        pw.print(",");
        pw.print(packageName);
        pw.print(",");
        pw.print(uid);
        pw.print(",");
        pw.print(vers);
        pw.print(",");
        pw.print(serviceName);
        pw.print(",");
        pw.print(opCount);
        boolean didCurState = false;
        final int N = mDurations.getKeyCount();
        for (int i=0; i<N; i++) {
            final int key = mDurations.getKeyAt(i);
            long time = mDurations.getValue(key);
            int type = SparseMappingTable.getIdFromKey(key);
            int memFactor = type / ServiceState.SERVICE_COUNT;
            type %= ServiceState.SERVICE_COUNT;
            if (type != serviceType) {
                continue;
            }
            if (curState == memFactor) {
                didCurState = true;
                time += now - curStartTime;
            }
            DumpUtils.printAdjTagAndValue(pw, memFactor, time);
        }
        if (!didCurState && curState != STATE_NOTHING) {
            DumpUtils.printAdjTagAndValue(pw, curState, now - curStartTime);
        }
        pw.println();
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId, long now) {
        final long token = proto.start(fieldId);
        proto.write(PackageServiceStatsProto.SERVICE_NAME, mName);

        writeTypeToProto(proto, PackageServiceStatsProto.OPERATION_STATS,
                ProcessStatsEnums.SERVICE_OPERATION_STATE_RUNNING,
                ServiceState.SERVICE_RUN, mRunCount, mRunState, mRunStartTime, now);

        writeTypeToProto(proto, PackageServiceStatsProto.OPERATION_STATS,
                ProcessStatsEnums.SERVICE_OPERATION_STATE_STARTED,
                ServiceState.SERVICE_STARTED, mStartedCount, mStartedState, mStartedStartTime, now);

        writeTypeToProto(proto, PackageServiceStatsProto.OPERATION_STATS,
                ProcessStatsEnums.SERVICE_OPERATION_STATE_FOREGROUND,
                ServiceState.SERVICE_FOREGROUND, mForegroundCount, mForegroundState,
                mForegroundStartTime, now);

        writeTypeToProto(proto, PackageServiceStatsProto.OPERATION_STATS,
                ProcessStatsEnums.SERVICE_OPERATION_STATE_BOUND,
                ServiceState.SERVICE_BOUND, mBoundCount, mBoundState, mBoundStartTime, now);

        writeTypeToProto(proto, PackageServiceStatsProto.OPERATION_STATS,
                ProcessStatsEnums.SERVICE_OPERATION_STATE_EXECUTING,
                ServiceState.SERVICE_EXEC, mExecCount, mExecState, mExecStartTime, now);

        proto.end(token);
    }

    /**
     * write the metrics to proto for each operation type.
     */
    public void writeTypeToProto(ProtoOutputStream proto, long fieldId, int opType, int serviceType,
            int opCount, int curState, long curStartTime, long now) {
        if (opCount <= 0) {
            return;
        }
        final long token = proto.start(fieldId);

        proto.write(PackageServiceOperationStatsProto.OPERATION, opType);
        proto.write(PackageServiceOperationStatsProto.COUNT, opCount);

        boolean didCurState = false;
        final int N = mDurations.getKeyCount();
        for (int i=0; i<N; i++) {
            final int key = mDurations.getKeyAt(i);
            long time = mDurations.getValue(key);
            int type = SparseMappingTable.getIdFromKey(key);
            int memFactor = type / ServiceState.SERVICE_COUNT;
            type %= ServiceState.SERVICE_COUNT;
            if (type != serviceType) {
                continue;
            }
            if (curState == memFactor) {
                didCurState = true;
                time += now - curStartTime;
            }
            final long stateToken = proto.start(PackageServiceOperationStatsProto.STATE_STATS);
            DumpUtils.printProcStateAdjTagProto(proto,
                    PackageServiceOperationStatsProto.StateStats.SCREEN_STATE,
                    PackageServiceOperationStatsProto.StateStats.MEMORY_STATE,
                    type);
            proto.write(PackageServiceOperationStatsProto.StateStats.DURATION_MS, time);
            proto.end(stateToken);
        }
        if (!didCurState && curState != STATE_NOTHING) {
            final long stateToken = proto.start(PackageServiceOperationStatsProto.STATE_STATS);
            DumpUtils.printProcStateAdjTagProto(proto,
                    PackageServiceOperationStatsProto.StateStats.SCREEN_STATE,
                    PackageServiceOperationStatsProto.StateStats.MEMORY_STATE,
                    curState);
            proto.write(PackageServiceOperationStatsProto.StateStats.DURATION_MS,
                    now - curStartTime);
            proto.end(stateToken);
        }

        proto.end(token);
    }

    public String toString() {
        return "ServiceState{" + Integer.toHexString(System.identityHashCode(this))
                + " " + mName + " pkg=" + mPackage + " proc="
                + Integer.toHexString(System.identityHashCode(mProc)) + "}";
    }
}
