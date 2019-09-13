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
package com.android.internal.os;

import static com.android.internal.os.KernelCpuProcStringReader.asLongs;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.os.StrictMode;
import android.os.SystemClock;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.KernelCpuProcStringReader.ProcFileIterator;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reads per-UID CPU time proc files. Concrete implementations are all nested inside.
 *
 * This class uses a throttler to reject any {@link #readDelta} or {@link #readAbsolute} call
 * within {@link #mMinTimeBetweenRead}. The throttler can be enable / disabled via a param in
 * the constructor.
 *
 * This class and its subclasses are NOT thread-safe and NOT designed to be accessed by more than
 * one caller since each caller has its own view of delta.
 *
 * @param <T> The type of CPU time for the callback.
 */
public abstract class KernelCpuUidTimeReader<T> {
    protected static final boolean DEBUG = false;
    private static final long DEFAULT_MIN_TIME_BETWEEN_READ = 1000L; // In milliseconds

    final String mTag = this.getClass().getSimpleName();
    final SparseArray<T> mLastTimes = new SparseArray<>();
    final KernelCpuProcStringReader mReader;
    final boolean mThrottle;
    private long mMinTimeBetweenRead = DEFAULT_MIN_TIME_BETWEEN_READ;
    private long mLastReadTimeMs = 0;

    /**
     * Callback interface for processing each line of the proc file.
     *
     * @param <T> The type of CPU time for the callback function.
     */
    public interface Callback<T> {
        /**
         * @param uid  UID of the app
         * @param time Time spent. The exact data structure depends on subclass implementation.
         */
        void onUidCpuTime(int uid, T time);
    }

    KernelCpuUidTimeReader(KernelCpuProcStringReader reader, boolean throttle) {
        mReader = reader;
        mThrottle = throttle;
    }

    /**
     * Reads the proc file, calling into the callback with a delta of time for each UID.
     *
     * @param cb The callback to invoke for each line of the proc file. If null,the data is
     *           consumed and subsequent calls to readDelta will provide a fresh delta.
     */
    public void readDelta(@Nullable Callback<T> cb) {
        if (!mThrottle) {
            readDeltaImpl(cb);
            return;
        }
        final long currTimeMs = SystemClock.elapsedRealtime();
        if (currTimeMs < mLastReadTimeMs + mMinTimeBetweenRead) {
            if (DEBUG) {
                Slog.d(mTag, "Throttle readDelta");
            }
            return;
        }
        readDeltaImpl(cb);
        mLastReadTimeMs = currTimeMs;
    }

    /**
     * Reads the proc file, calling into the callback with cumulative time for each UID.
     *
     * @param cb The callback to invoke for each line of the proc file. It cannot be null.
     */
    public void readAbsolute(Callback<T> cb) {
        if (!mThrottle) {
            readAbsoluteImpl(cb);
            return;
        }
        final long currTimeMs = SystemClock.elapsedRealtime();
        if (currTimeMs < mLastReadTimeMs + mMinTimeBetweenRead) {
            if (DEBUG) {
                Slog.d(mTag, "Throttle readAbsolute");
            }
            return;
        }
        readAbsoluteImpl(cb);
        mLastReadTimeMs = currTimeMs;
    }

    abstract void readDeltaImpl(@Nullable Callback<T> cb);

    abstract void readAbsoluteImpl(Callback<T> callback);

    /**
     * Removes the UID from internal accounting data. This method, overridden in
     * {@link KernelCpuUidUserSysTimeReader}, also removes the UID from the kernel module.
     *
     * @param uid The UID to remove.
     * @see KernelCpuUidUserSysTimeReader#removeUid(int)
     */
    public void removeUid(int uid) {
        mLastTimes.delete(uid);
    }

    /**
     * Removes UIDs in a given range from internal accounting data. This method, overridden in
     * {@link KernelCpuUidUserSysTimeReader}, also removes the UIDs from the kernel module.
     *
     * @param startUid the first uid to remove.
     * @param endUid   the last uid to remove.
     * @see KernelCpuUidUserSysTimeReader#removeUidsInRange(int, int)
     */
    public void removeUidsInRange(int startUid, int endUid) {
        if (endUid < startUid) {
            Slog.e(mTag, "start UID " + startUid + " > end UID " + endUid);
            return;
        }
        mLastTimes.put(startUid, null);
        mLastTimes.put(endUid, null);
        final int firstIndex = mLastTimes.indexOfKey(startUid);
        final int lastIndex = mLastTimes.indexOfKey(endUid);
        mLastTimes.removeAtRange(firstIndex, lastIndex - firstIndex + 1);
    }

    /**
     * Set the minimum time in milliseconds between reads. If throttle is not enabled, this method
     * has no effect.
     *
     * @param minTimeBetweenRead The minimum time in milliseconds.
     */
    public void setThrottle(long minTimeBetweenRead) {
        if (mThrottle && minTimeBetweenRead >= 0) {
            mMinTimeBetweenRead = minTimeBetweenRead;
        }
    }

    /**
     * Reads /proc/uid_cputime/show_uid_stat which has the line format:
     *
     * uid: user_time_micro_seconds system_time_micro_seconds power_in_milli-amp-micro_seconds
     *
     * This provides the time a UID's processes spent executing in user-space and kernel-space.
     * The file contains a monotonically increasing count of time for a single boot. This class
     * maintains the previous results of a call to {@link #readDelta} in order to provide a proper
     * delta.
     *
     * The second parameter of the callback is a long[] with 2 elements, [user time in us, system
     * time in us].
     */
    public static class KernelCpuUidUserSysTimeReader extends KernelCpuUidTimeReader<long[]> {
        private static final String REMOVE_UID_PROC_FILE = "/proc/uid_cputime/remove_uid_range";

        // [uid, user_time, system_time, (maybe) power_in_milli-amp-micro_seconds]
        private final long[] mBuffer = new long[4];
        // A reusable array to hold [user_time, system_time] for the callback.
        private final long[] mUsrSysTime = new long[2];

        public KernelCpuUidUserSysTimeReader(boolean throttle) {
            super(KernelCpuProcStringReader.getUserSysTimeReaderInstance(), throttle);
        }

        @VisibleForTesting
        public KernelCpuUidUserSysTimeReader(KernelCpuProcStringReader reader, boolean throttle) {
            super(reader, throttle);
        }

        @Override
        void readDeltaImpl(@Nullable Callback<long[]> cb) {
            try (ProcFileIterator iter = mReader.open(!mThrottle)) {
                if (iter == null) {
                    return;
                }
                CharBuffer buf;
                while ((buf = iter.nextLine()) != null) {
                    if (asLongs(buf, mBuffer) < 3) {
                        Slog.wtf(mTag, "Invalid line: " + buf.toString());
                        continue;
                    }
                    final int uid = (int) mBuffer[0];
                    long[] lastTimes = mLastTimes.get(uid);
                    if (lastTimes == null) {
                        lastTimes = new long[2];
                        mLastTimes.put(uid, lastTimes);
                    }
                    final long currUsrTimeUs = mBuffer[1];
                    final long currSysTimeUs = mBuffer[2];
                    mUsrSysTime[0] = currUsrTimeUs - lastTimes[0];
                    mUsrSysTime[1] = currSysTimeUs - lastTimes[1];

                    if (mUsrSysTime[0] < 0 || mUsrSysTime[1] < 0) {
                        Slog.e(mTag, "Negative user/sys time delta for UID=" + uid
                                + "\nPrev times: u=" + lastTimes[0] + " s=" + lastTimes[1]
                                + " Curr times: u=" + currUsrTimeUs + " s=" + currSysTimeUs);
                    } else if (mUsrSysTime[0] > 0 || mUsrSysTime[1] > 0) {
                        if (cb != null) {
                            cb.onUidCpuTime(uid, mUsrSysTime);
                        }
                    }
                    lastTimes[0] = currUsrTimeUs;
                    lastTimes[1] = currSysTimeUs;
                }
            }
        }

        @Override
        void readAbsoluteImpl(Callback<long[]> cb) {
            try (ProcFileIterator iter = mReader.open(!mThrottle)) {
                if (iter == null) {
                    return;
                }
                CharBuffer buf;
                while ((buf = iter.nextLine()) != null) {
                    if (asLongs(buf, mBuffer) < 3) {
                        Slog.wtf(mTag, "Invalid line: " + buf.toString());
                        continue;
                    }
                    mUsrSysTime[0] = mBuffer[1]; // User time in microseconds
                    mUsrSysTime[1] = mBuffer[2]; // System time in microseconds
                    cb.onUidCpuTime((int) mBuffer[0], mUsrSysTime);
                }
            }
        }

        @Override
        public void removeUid(int uid) {
            super.removeUid(uid);
            removeUidsFromKernelModule(uid, uid);
        }

        @Override
        public void removeUidsInRange(int startUid, int endUid) {
            super.removeUidsInRange(startUid, endUid);
            removeUidsFromKernelModule(startUid, endUid);
        }

        /**
         * Removes UIDs in a given range from the kernel module and internal accounting data. Only
         * {@link BatteryStatsImpl} and its child processes should call this, as the change on
         * Kernel is
         * visible system wide.
         *
         * @param startUid the first uid to remove
         * @param endUid   the last uid to remove
         */
        private void removeUidsFromKernelModule(int startUid, int endUid) {
            Slog.d(mTag, "Removing uids " + startUid + "-" + endUid);
            final int oldMask = StrictMode.allowThreadDiskWritesMask();
            try (FileWriter writer = new FileWriter(REMOVE_UID_PROC_FILE)) {
                writer.write(startUid + "-" + endUid);
                writer.flush();
            } catch (IOException e) {
                Slog.e(mTag, "failed to remove uids " + startUid + " - " + endUid
                        + " from uid_cputime module", e);
            } finally {
                StrictMode.setThreadPolicyMask(oldMask);
            }
        }
    }

    /**
     * Reads /proc/uid_time_in_state which has the format:
     *
     * uid: [freq1] [freq2] [freq3] ...
     * [uid1]: [time in freq1] [time in freq2] [time in freq3] ...
     * [uid2]: [time in freq1] [time in freq2] [time in freq3] ...
     * ...
     *
     * This provides the times a UID's processes spent executing at each different cpu frequency.
     * The file contains a monotonically increasing count of time for a single boot. This class
     * maintains the previous results of a call to {@link #readDelta} in order to provide a proper
     * delta.
     */
    public static class KernelCpuUidFreqTimeReader extends KernelCpuUidTimeReader<long[]> {
        private static final String UID_TIMES_PROC_FILE = "/proc/uid_time_in_state";
        // We check the existence of proc file a few times (just in case it is not ready yet when we
        // start reading) and if it is not available, we simply ignore further read requests.
        private static final int MAX_ERROR_COUNT = 5;

        private final Path mProcFilePath;
        private long[] mBuffer;
        private long[] mCurTimes;
        private long[] mDeltaTimes;
        private long[] mCpuFreqs;

        private int mFreqCount = 0;
        private int mErrors = 0;
        private boolean mPerClusterTimesAvailable;
        private boolean mAllUidTimesAvailable = true;

        public KernelCpuUidFreqTimeReader(boolean throttle) {
            this(UID_TIMES_PROC_FILE, KernelCpuProcStringReader.getFreqTimeReaderInstance(),
                    throttle);
        }

        @VisibleForTesting
        public KernelCpuUidFreqTimeReader(String procFile, KernelCpuProcStringReader reader,
                boolean throttle) {
            super(reader, throttle);
            mProcFilePath = Paths.get(procFile);
        }

        /**
         * @return Whether per-cluster times are available.
         */
        public boolean perClusterTimesAvailable() {
            return mPerClusterTimesAvailable;
        }

        /**
         * @return Whether all-UID times are available.
         */
        public boolean allUidTimesAvailable() {
            return mAllUidTimesAvailable;
        }

        /**
         * @return A map of all UIDs to their CPU time-in-state array in milliseconds.
         */
        public SparseArray<long[]> getAllUidCpuFreqTimeMs() {
            return mLastTimes;
        }

        /**
         * Reads a list of CPU frequencies from /proc/uid_time_in_state. Uses a given PowerProfile
         * to determine if per-cluster times are available.
         *
         * @param powerProfile The PowerProfile to compare against.
         * @return A long[] of CPU frequencies in Hz.
         */
        public long[] readFreqs(@NonNull PowerProfile powerProfile) {
            checkNotNull(powerProfile);
            if (mCpuFreqs != null) {
                // No need to read cpu freqs more than once.
                return mCpuFreqs;
            }
            if (!mAllUidTimesAvailable) {
                return null;
            }
            final int oldMask = StrictMode.allowThreadDiskReadsMask();
            try (BufferedReader reader = Files.newBufferedReader(mProcFilePath)) {
                if (readFreqs(reader.readLine()) == null) {
                    return null;
                }
            } catch (IOException e) {
                if (++mErrors >= MAX_ERROR_COUNT) {
                    mAllUidTimesAvailable = false;
                }
                Slog.e(mTag, "Failed to read " + UID_TIMES_PROC_FILE + ": " + e);
                return null;
            } finally {
                StrictMode.setThreadPolicyMask(oldMask);
            }
            // Check if the freqs in the proc file correspond to per-cluster freqs.
            final IntArray numClusterFreqs = extractClusterInfoFromProcFileFreqs();
            final int numClusters = powerProfile.getNumCpuClusters();
            if (numClusterFreqs.size() == numClusters) {
                mPerClusterTimesAvailable = true;
                for (int i = 0; i < numClusters; ++i) {
                    if (numClusterFreqs.get(i) != powerProfile.getNumSpeedStepsInCpuCluster(i)) {
                        mPerClusterTimesAvailable = false;
                        break;
                    }
                }
            } else {
                mPerClusterTimesAvailable = false;
            }
            Slog.i(mTag, "mPerClusterTimesAvailable=" + mPerClusterTimesAvailable);
            return mCpuFreqs;
        }

        private long[] readFreqs(String line) {
            if (line == null) {
                return null;
            }
            final String[] lineArray = line.split(" ");
            if (lineArray.length <= 1) {
                Slog.wtf(mTag, "Malformed freq line: " + line);
                return null;
            }
            mFreqCount = lineArray.length - 1;
            mCpuFreqs = new long[mFreqCount];
            mCurTimes = new long[mFreqCount];
            mDeltaTimes = new long[mFreqCount];
            mBuffer = new long[mFreqCount + 1];
            for (int i = 0; i < mFreqCount; ++i) {
                mCpuFreqs[i] = Long.parseLong(lineArray[i + 1], 10);
            }
            return mCpuFreqs;
        }

        @Override
        void readDeltaImpl(@Nullable Callback<long[]> cb) {
            try (ProcFileIterator iter = mReader.open(!mThrottle)) {
                if (!checkPrecondition(iter)) {
                    return;
                }
                CharBuffer buf;
                while ((buf = iter.nextLine()) != null) {
                    if (asLongs(buf, mBuffer) != mBuffer.length) {
                        if (Build.IS_ENG) {
                            Slog.wtf(mTag, "Invalid line: " + buf.toString());
                        } else {
                            Slog.w(mTag, "Invalid line: " + buf.toString());
                        }
                        continue;
                    }
                    final int uid = (int) mBuffer[0];
                    long[] lastTimes = mLastTimes.get(uid);
                    if (lastTimes == null) {
                        lastTimes = new long[mFreqCount];
                        mLastTimes.put(uid, lastTimes);
                    }
                    copyToCurTimes();
                    boolean notify = false;
                    boolean valid = true;
                    for (int i = 0; i < mFreqCount; i++) {
                        // Unit is 10ms.
                        mDeltaTimes[i] = mCurTimes[i] - lastTimes[i];
                        if (mDeltaTimes[i] < 0) {
                            Slog.e(mTag, "Negative delta from freq time proc: " + mDeltaTimes[i]);
                            valid = false;
                        }
                        notify |= mDeltaTimes[i] > 0;
                    }
                    if (notify && valid) {
                        System.arraycopy(mCurTimes, 0, lastTimes, 0, mFreqCount);
                        if (cb != null) {
                            cb.onUidCpuTime(uid, mDeltaTimes);
                        }
                    }
                }
            }
        }

        @Override
        void readAbsoluteImpl(Callback<long[]> cb) {
            try (ProcFileIterator iter = mReader.open(!mThrottle)) {
                if (!checkPrecondition(iter)) {
                    return;
                }
                CharBuffer buf;
                while ((buf = iter.nextLine()) != null) {
                    if (asLongs(buf, mBuffer) != mBuffer.length) {
                        Slog.wtf(mTag, "Invalid line: " + buf.toString());
                        continue;
                    }
                    copyToCurTimes();
                    cb.onUidCpuTime((int) mBuffer[0], mCurTimes);
                }
            }
        }

        private void copyToCurTimes() {
            for (int i = 0; i < mFreqCount; i++) {
                mCurTimes[i] = mBuffer[i + 1] * 10;
            }
        }

        private boolean checkPrecondition(ProcFileIterator iter) {
            if (iter == null || !iter.hasNextLine()) {
                // Error logged in KernelCpuProcStringReader.
                return false;
            }
            CharBuffer line = iter.nextLine();
            if (mCpuFreqs != null) {
                return true;
            }
            return readFreqs(line.toString()) != null;
        }

        /**
         * Extracts no. of cpu clusters and no. of freqs in each of these clusters from the freqs
         * read from the proc file.
         *
         * We need to assume that freqs in each cluster are strictly increasing.
         * For e.g. if the freqs read from proc file are: 12, 34, 15, 45, 12, 15, 52. Then it means
         * there are 3 clusters: (12, 34), (15, 45), (12, 15, 52)
         *
         * @return an IntArray filled with no. of freqs in each cluster.
         */
        private IntArray extractClusterInfoFromProcFileFreqs() {
            final IntArray numClusterFreqs = new IntArray();
            int freqsFound = 0;
            for (int i = 0; i < mFreqCount; ++i) {
                freqsFound++;
                if (i + 1 == mFreqCount || mCpuFreqs[i + 1] <= mCpuFreqs[i]) {
                    numClusterFreqs.add(freqsFound);
                    freqsFound = 0;
                }
            }
            return numClusterFreqs;
        }
    }

    /**
     * Reads /proc/uid_concurrent_active_time and reports CPU active time to BatteryStats to
     * compute {@link PowerProfile#POWER_CPU_ACTIVE}.
     *
     * /proc/uid_concurrent_active_time has the following format:
     * cpus: n
     * uid0: time0a, time0b, ..., time0n,
     * uid1: time1a, time1b, ..., time1n,
     * uid2: time2a, time2b, ..., time2n,
     * ...
     * where n is the total number of cpus (num_possible_cpus)
     * timeXn means the CPU time that a UID X spent running concurrently with n other processes.
     *
     * The file contains a monotonically increasing count of time for a single boot. This class
     * maintains the previous results of a call to {@link #readDelta} in order to provide a
     * proper delta.
     */
    public static class KernelCpuUidActiveTimeReader extends KernelCpuUidTimeReader<Long> {
        private int mCores = 0;
        private long[] mBuffer;

        public KernelCpuUidActiveTimeReader(boolean throttle) {
            super(KernelCpuProcStringReader.getActiveTimeReaderInstance(), throttle);
        }

        @VisibleForTesting
        public KernelCpuUidActiveTimeReader(KernelCpuProcStringReader reader, boolean throttle) {
            super(reader, throttle);
        }

        @Override
        void readDeltaImpl(@Nullable Callback<Long> cb) {
            try (ProcFileIterator iter = mReader.open(!mThrottle)) {
                if (!checkPrecondition(iter)) {
                    return;
                }
                CharBuffer buf;
                while ((buf = iter.nextLine()) != null) {
                    if (asLongs(buf, mBuffer) != mBuffer.length) {
                        Slog.wtf(mTag, "Invalid line: " + buf.toString());
                        continue;
                    }
                    int uid = (int) mBuffer[0];
                    long cpuActiveTime = sumActiveTime(mBuffer);
                    if (cpuActiveTime > 0) {
                        long delta = cpuActiveTime - mLastTimes.get(uid, 0L);
                        if (delta > 0) {
                            mLastTimes.put(uid, cpuActiveTime);
                            if (cb != null) {
                                cb.onUidCpuTime(uid, delta);
                            }
                        } else if (delta < 0) {
                            Slog.e(mTag, "Negative delta from active time proc: " + delta);
                        }
                    }
                }
            }
        }

        @Override
        void readAbsoluteImpl(Callback<Long> cb) {
            try (ProcFileIterator iter = mReader.open(!mThrottle)) {
                if (!checkPrecondition(iter)) {
                    return;
                }
                CharBuffer buf;
                while ((buf = iter.nextLine()) != null) {
                    if (asLongs(buf, mBuffer) != mBuffer.length) {
                        Slog.wtf(mTag, "Invalid line: " + buf.toString());
                        continue;
                    }
                    long cpuActiveTime = sumActiveTime(mBuffer);
                    if (cpuActiveTime > 0) {
                        cb.onUidCpuTime((int) mBuffer[0], cpuActiveTime);
                    }
                }
            }
        }

        private static long sumActiveTime(long[] times) {
            // UID is stored at times[0].
            double sum = 0;
            for (int i = 1; i < times.length; i++) {
                sum += (double) times[i] * 10 / i; // Unit is 10ms.
            }
            return (long) sum;
        }

        private boolean checkPrecondition(ProcFileIterator iter) {
            if (iter == null || !iter.hasNextLine()) {
                // Error logged in KernelCpuProcStringReader.
                return false;
            }
            CharBuffer line = iter.nextLine();
            if (mCores > 0) {
                return true;
            }

            String str = line.toString();
            if (!str.startsWith("cpus:")) {
                Slog.wtf(mTag, "Malformed uid_concurrent_active_time line: " + line);
                return false;
            }
            int cores = Integer.parseInt(str.substring(5).trim(), 10);
            if (cores <= 0) {
                Slog.wtf(mTag, "Malformed uid_concurrent_active_time line: " + line);
                return false;
            }
            mCores = cores;
            mBuffer = new long[mCores + 1]; // UID is stored at mBuffer[0].
            return true;
        }
    }


    /**
     * Reads /proc/uid_concurrent_policy_time and reports CPU cluster times to BatteryStats to
     * compute cluster power. See {@link PowerProfile#getAveragePowerForCpuCluster(int)}.
     *
     * /proc/uid_concurrent_policy_time has the following format:
     * policyX: x policyY: y policyZ: z...
     * uid1, time1a, time1b, ..., time1n,
     * uid2, time2a, time2b, ..., time2n,
     * ...
     * The first line lists all policies (i.e. clusters) followed by # cores in each policy.
     * Each uid is followed by x time entries corresponding to the time it spent on clusterX
     * running concurrently with 0, 1, 2, ..., x - 1 other processes, then followed by y, z, ...
     * time entries.
     *
     * The file contains a monotonically increasing count of time for a single boot. This class
     * maintains the previous results of a call to {@link #readDelta} in order to provide a
     * proper delta.
     */
    public static class KernelCpuUidClusterTimeReader extends KernelCpuUidTimeReader<long[]> {
        private int mNumClusters;
        private int mNumCores;
        private int[] mCoresOnClusters; // # cores on each cluster.
        private long[] mBuffer; // To store data returned from ProcFileIterator.
        private long[] mCurTime;
        private long[] mDeltaTime;

        public KernelCpuUidClusterTimeReader(boolean throttle) {
            super(KernelCpuProcStringReader.getClusterTimeReaderInstance(), throttle);
        }

        @VisibleForTesting
        public KernelCpuUidClusterTimeReader(KernelCpuProcStringReader reader, boolean throttle) {
            super(reader, throttle);
        }

        @Override
        void readDeltaImpl(@Nullable Callback<long[]> cb) {
            try (ProcFileIterator iter = mReader.open(!mThrottle)) {
                if (!checkPrecondition(iter)) {
                    return;
                }
                CharBuffer buf;
                while ((buf = iter.nextLine()) != null) {
                    if (asLongs(buf, mBuffer) != mBuffer.length) {
                        Slog.wtf(mTag, "Invalid line: " + buf.toString());
                        continue;
                    }
                    int uid = (int) mBuffer[0];
                    long[] lastTimes = mLastTimes.get(uid);
                    if (lastTimes == null) {
                        lastTimes = new long[mNumClusters];
                        mLastTimes.put(uid, lastTimes);
                    }
                    sumClusterTime();
                    boolean valid = true;
                    boolean notify = false;
                    for (int i = 0; i < mNumClusters; i++) {
                        mDeltaTime[i] = mCurTime[i] - lastTimes[i];
                        if (mDeltaTime[i] < 0) {
                            Slog.e(mTag, "Negative delta from cluster time proc: " + mDeltaTime[i]);
                            valid = false;
                        }
                        notify |= mDeltaTime[i] > 0;
                    }
                    if (notify && valid) {
                        System.arraycopy(mCurTime, 0, lastTimes, 0, mNumClusters);
                        if (cb != null) {
                            cb.onUidCpuTime(uid, mDeltaTime);
                        }
                    }
                }
            }
        }

        @Override
        void readAbsoluteImpl(Callback<long[]> cb) {
            try (ProcFileIterator iter = mReader.open(!mThrottle)) {
                if (!checkPrecondition(iter)) {
                    return;
                }
                CharBuffer buf;
                while ((buf = iter.nextLine()) != null) {
                    if (asLongs(buf, mBuffer) != mBuffer.length) {
                        Slog.wtf(mTag, "Invalid line: " + buf.toString());
                        continue;
                    }
                    sumClusterTime();
                    cb.onUidCpuTime((int) mBuffer[0], mCurTime);
                }
            }
        }

        private void sumClusterTime() {
            // UID is stored at mBuffer[0].
            int core = 1;
            for (int i = 0; i < mNumClusters; i++) {
                double sum = 0;
                for (int j = 1; j <= mCoresOnClusters[i]; j++) {
                    sum += (double) mBuffer[core++] * 10 / j; // Unit is 10ms.
                }
                mCurTime[i] = (long) sum;
            }
        }

        private boolean checkPrecondition(ProcFileIterator iter) {
            if (iter == null || !iter.hasNextLine()) {
                // Error logged in KernelCpuProcStringReader.
                return false;
            }
            CharBuffer line = iter.nextLine();
            if (mNumClusters > 0) {
                return true;
            }
            // Parse # cores in clusters.
            String[] lineArray = line.toString().split(" ");
            if (lineArray.length % 2 != 0) {
                Slog.wtf(mTag, "Malformed uid_concurrent_policy_time line: " + line);
                return false;
            }
            int[] clusters = new int[lineArray.length / 2];
            int cores = 0;
            for (int i = 0; i < clusters.length; i++) {
                if (!lineArray[i * 2].startsWith("policy")) {
                    Slog.wtf(mTag, "Malformed uid_concurrent_policy_time line: " + line);
                    return false;
                }
                clusters[i] = Integer.parseInt(lineArray[i * 2 + 1], 10);
                cores += clusters[i];
            }
            mNumClusters = clusters.length;
            mNumCores = cores;
            mCoresOnClusters = clusters;
            mBuffer = new long[cores + 1];
            mCurTime = new long[mNumClusters];
            mDeltaTime = new long[mNumClusters];
            return true;
        }
    }

}
