/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.internal.app.procstats.ProcessStats.STATE_COUNT;
import static com.android.internal.app.procstats.ProcessStats.STATE_NOTHING;

import android.os.Parcel;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.TimeUtils;

import java.io.PrintWriter;

/**
 * The class to track the individual time-in-state of a UID.
 */
public final class UidState {
    private static final String TAG = "ProcessStats";

    private final ProcessStats mStats;
    private final int mUid;
    private final DurationsTable mDurations;

    private ArraySet<ProcessState> mProcesses = new ArraySet<>();
    private int mCurCombinedState = STATE_NOTHING;
    private long mStartTime;

    private long mTotalRunningStartTime;
    private long mTotalRunningDuration;

    /**
     * Create a new UID state. The initial state is not running.
     */
    public UidState(ProcessStats processStats, int uid) {
        mStats = processStats;
        mUid = uid;
        mDurations = new DurationsTable(processStats.mTableData);
    }

    /**
     * Create a copy of this instance.
     */
    public UidState clone() {
        UidState unew = new UidState(mStats, mUid);
        unew.mDurations.addDurations(mDurations);
        unew.mCurCombinedState = mCurCombinedState;
        unew.mStartTime = mStartTime;
        unew.mTotalRunningStartTime = mTotalRunningStartTime;
        unew.mTotalRunningDuration = mTotalRunningDuration;
        return unew;
    }

    /**
     * Update the current state of the UID, it should be a combination
     * of all running processes in this UID.
     */
    public void updateCombinedState(int state, long now) {
        if (mCurCombinedState != state) {
            updateCombinedState(now);
        }
    }

    /**
     * Update the current state of the UID, it should be a combination
     * of all running processes in this UID.
     */
    public void updateCombinedState(long now) {
        setCombinedStateInner(calcCombinedState(), now);
    }

    private int calcCombinedState() {
        int minCombined = STATE_NOTHING;
        int min = STATE_NOTHING;
        for (int i = 0, size = mProcesses.size(); i < size; i++) {
            final int combinedState = mProcesses.valueAt(i).getCombinedState();
            final int state = combinedState % STATE_COUNT;
            if (combinedState != STATE_NOTHING) {
                if (min == STATE_NOTHING || state < min) {
                    minCombined = combinedState;
                    min = state;
                }
            }
        }
        return minCombined;
    }

    /**
     * Set the combined state and commit the state.
     *
     * @param now When it's negative, the previous state won't be committed.
     */
    private void setCombinedStateInner(int state, long now) {
        if (mCurCombinedState != state) {
            if (now >= 0) {
                commitStateTime(now);
                if (state == STATE_NOTHING) {
                    // We are transitioning to a no longer running state... stop counting run time.
                    mTotalRunningDuration += now - mTotalRunningStartTime;
                } else if (mCurCombinedState == STATE_NOTHING) {
                    // We previously weren't running...  now starting again, clear out total
                    // running info.
                    mTotalRunningDuration = 0;
                }
            }
            mCurCombinedState = state;
        }
    }

    /**
     * @return The current combine state of the UID.
     */
    public int getCombinedState() {
        return mCurCombinedState;
    }

    /**
     * Commit the current state's duration into stats.
     */
    public void commitStateTime(long now) {
        if (mCurCombinedState != STATE_NOTHING) {
            long dur = now - mStartTime;
            if (dur > 0) {
                mDurations.addDuration(mCurCombinedState, dur);
            }
            mTotalRunningDuration += now - mTotalRunningStartTime;
            mTotalRunningStartTime = now;
        }
        mStartTime = now;
    }

    /**
     * Reset the UID stats safely.
     */
    public void resetSafely(long now) {
        mDurations.resetTable();
        mStartTime = now;
    }

    /**
     * @return Whether this UID stats is still being used or not.
     */
    public boolean isInUse() {
        for (int i = 0, size = mProcesses.size(); i < size; i++) {
            if (mProcesses.valueAt(i).isInUse()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return Whether the given package belongs to this UID or not.
     */
    public boolean hasPackage(String packageName) {
        for (int i = 0, size = mProcesses.size(); i < size; i++) {
            final ProcessState proc = mProcesses.valueAt(i);
            if (TextUtils.equals(packageName, proc.getName())
                    && TextUtils.equals(packageName, proc.getPackage())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add stats data from another instance to this one.
     */
    public void add(UidState other) {
        mDurations.addDurations(other.mDurations);
        mTotalRunningDuration += other.mTotalRunningDuration;
    }

    void addProcess(ProcessState proc) {
        mProcesses.add(proc);
    }

    void addProcess(ProcessState proc, long now) {
        mProcesses.add(proc);
        setCombinedStateInner(proc.getCombinedState(), now);
    }

    void removeProcess(ProcessState proc, long now) {
        mProcesses.remove(proc);
        setCombinedStateInner(proc.getCombinedState(), now);
    }

    /**
     * @return The total amount of stats it's currently tracking.
     */
    public int getDurationsBucketCount() {
        return mDurations.getKeyCount();
    }

    /**
     * @return The total running duration of this UID.
     */
    public long getTotalRunningDuration(long now) {
        return mTotalRunningDuration
                + (mTotalRunningStartTime != 0 ? (now - mTotalRunningStartTime) : 0);
    }

    /**
     * @return The duration in the given state.
     */
    public long getDuration(int state, long now) {
        long time = mDurations.getValueForId((byte) state);
        if (mCurCombinedState == state) {
            time += now - mStartTime;
        }
        return time;
    }

    /**
     * @return The durations in each process state, the mem/screen factors
     *         are consolidated into the bucket with the same process state.
     */
    public long[] getAggregatedDurationsInStates() {
        final long[] states = new long[STATE_COUNT];
        final int numOfBuckets = getDurationsBucketCount();
        for (int i = 0; i < numOfBuckets; i++) {
            final int key = mDurations.getKeyAt(i);
            final int combinedState = SparseMappingTable.getIdFromKey(key);
            states[combinedState % STATE_COUNT] += mDurations.getValue(key);
        }
        return states;
    }

    void writeToParcel(Parcel out, long now) {
        mDurations.writeToParcel(out);
        out.writeLong(getTotalRunningDuration(now));
    }

    boolean readFromParcel(Parcel in) {
        if (!mDurations.readFromParcel(in)) {
            return false;
        }
        mTotalRunningDuration = in.readLong();
        return true;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("UidState{").append(Integer.toHexString(System.identityHashCode(this)))
                .append(" ").append(UserHandle.formatUid(mUid)).append("}");
        return sb.toString();
    }

    void dumpState(PrintWriter pw, String prefix,
            int[] screenStates, int[] memStates, int[] procStates, long now) {
        long totalTime = 0;
        int printedScreen = -1;
        for (int is = 0; is < screenStates.length; is++) {
            int printedMem = -1;
            for (int im = 0; im < memStates.length; im++) {
                for (int ip = 0; ip < procStates.length; ip++) {
                    final int iscreen = screenStates[is];
                    final int imem = memStates[im];
                    final int bucket = ((iscreen + imem) * STATE_COUNT) + procStates[ip];
                    long time = mDurations.getValueForId((byte) bucket);
                    String running = "";
                    if (mCurCombinedState == bucket) {
                        running = " (running)";
                        time += now - mStartTime;
                    }
                    if (time != 0) {
                        pw.print(prefix);
                        if (screenStates.length > 1) {
                            DumpUtils.printScreenLabel(pw, printedScreen != iscreen
                                    ? iscreen : STATE_NOTHING);
                            printedScreen = iscreen;
                        }
                        if (memStates.length > 1) {
                            DumpUtils.printMemLabel(pw,
                                    printedMem != imem ? imem : STATE_NOTHING, '/');
                            printedMem = imem;
                        }
                        pw.print(DumpUtils.STATE_LABELS[procStates[ip]]); pw.print(": ");
                        TimeUtils.formatDuration(time, pw); pw.println(running);
                        totalTime += time;
                    }
                }
            }
        }
        if (totalTime != 0) {
            pw.print(prefix);
            if (screenStates.length > 1) {
                DumpUtils.printScreenLabel(pw, STATE_NOTHING);
            }
            if (memStates.length > 1) {
                DumpUtils.printMemLabel(pw, STATE_NOTHING, '/');
            }
            pw.print(DumpUtils.STATE_LABEL_TOTAL);
            pw.print(": ");
            TimeUtils.formatDuration(totalTime, pw);
            pw.println();
        }
    }
}
