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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.BatteryConsumer;
import android.os.BatteryManager;
import android.os.BatteryStats;
import android.os.BatteryStats.BitDescription;
import android.os.BatteryStats.HistoryItem;
import android.os.BatteryStats.HistoryStepDetails;
import android.os.BatteryStats.HistoryTag;
import android.os.Build;
import android.os.Parcel;
import android.os.ParcelFormatException;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BatteryStatsHistory encapsulates battery history files.
 * Battery history record is appended into buffer {@link #mHistoryBuffer} and backed up into
 * {@link #mActiveFile}.
 * When {@link #mHistoryBuffer} size reaches {@link BatteryStatsImpl.Constants#MAX_HISTORY_BUFFER},
 * current mActiveFile is closed and a new mActiveFile is open.
 * History files are under directory /data/system/battery-history/.
 * History files have name battery-history-<num>.bin. The file number <num> starts from zero and
 * grows sequentially.
 * The mActiveFile is always the highest numbered history file.
 * The lowest number file is always the oldest file.
 * The highest number file is always the newest file.
 * The file number grows sequentially and we never skip number.
 * When count of history files exceeds {@link BatteryStatsImpl.Constants#MAX_HISTORY_FILES},
 * the lowest numbered file is deleted and a new file is open.
 *
 * All interfaces in BatteryStatsHistory should only be called by BatteryStatsImpl and protected by
 * locks on BatteryStatsImpl object.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class BatteryStatsHistory {
    private static final boolean DEBUG = false;
    private static final String TAG = "BatteryStatsHistory";

    // Current on-disk Parcel version. Must be updated when the format of the parcelable changes
    private static final int VERSION = 210;

    private static final String HISTORY_DIR = "battery-history";
    private static final String FILE_SUFFIX = ".bh";
    private static final int MIN_FREE_SPACE = 100 * 1024 * 1024;

    // Part of initial delta int that specifies the time delta.
    static final int DELTA_TIME_MASK = 0x7ffff;
    static final int DELTA_TIME_LONG = 0x7ffff;   // The delta is a following long
    static final int DELTA_TIME_INT = 0x7fffe;    // The delta is a following int
    static final int DELTA_TIME_ABS = 0x7fffd;    // Following is an entire abs update.
    // Flag in delta int: a new battery level int follows.
    static final int DELTA_BATTERY_LEVEL_FLAG = 0x00080000;
    // Flag in delta int: a new full state and battery status int follows.
    static final int DELTA_STATE_FLAG = 0x00100000;
    // Flag in delta int: a new full state2 int follows.
    static final int DELTA_STATE2_FLAG = 0x00200000;
    // Flag in delta int: contains a wakelock or wakeReason tag.
    static final int DELTA_WAKELOCK_FLAG = 0x00400000;
    // Flag in delta int: contains an event description.
    static final int DELTA_EVENT_FLAG = 0x00800000;
    // Flag in delta int: contains the battery charge count in uAh.
    static final int DELTA_BATTERY_CHARGE_FLAG = 0x01000000;
    // These upper bits are the frequently changing state bits.
    static final int DELTA_STATE_MASK = 0xfe000000;
    // These are the pieces of battery state that are packed in to the upper bits of
    // the state int that have been packed in to the first delta int.  They must fit
    // in STATE_BATTERY_MASK.
    static final int STATE_BATTERY_MASK = 0xff000000;
    static final int STATE_BATTERY_STATUS_MASK = 0x00000007;
    static final int STATE_BATTERY_STATUS_SHIFT = 29;
    static final int STATE_BATTERY_HEALTH_MASK = 0x00000007;
    static final int STATE_BATTERY_HEALTH_SHIFT = 26;
    static final int STATE_BATTERY_PLUG_MASK = 0x00000003;
    static final int STATE_BATTERY_PLUG_SHIFT = 24;

    // We use the low bit of the battery state int to indicate that we have full details
    // from a battery level change.
    static final int BATTERY_LEVEL_DETAILS_FLAG = 0x00000001;

    // Flag in history tag index: indicates that this is the first occurrence of this tag,
    // therefore the tag value is written in the parcel
    static final int TAG_FIRST_OCCURRENCE_FLAG = 0x8000;

    static final int EXTENSION_POWER_STATS_DESCRIPTOR_FLAG = 0x00000001;
    static final int EXTENSION_POWER_STATS_FLAG = 0x00000002;
    static final int EXTENSION_PROCESS_STATE_CHANGE_FLAG = 0x00000004;

    // For state1, trace everything except the wakelock bit (which can race with
    // suspend) and the running bit (which isn't meaningful in traces).
    static final int STATE1_TRACE_MASK = ~(HistoryItem.STATE_WAKE_LOCK_FLAG
                                          | HistoryItem.STATE_CPU_RUNNING_FLAG);
    // For state2, trace all bit changes.
    static final int STATE2_TRACE_MASK = ~0;

    /**
     * Number of overflow bytes that can be written into the history buffer if the history
     * directory is locked. This is done to prevent a long lock contention and a potential
     * kill by a watchdog.
     */
    private static final int EXTRA_BUFFER_SIZE_WHEN_DIR_LOCKED = 100_000;

    private final Parcel mHistoryBuffer;
    private final File mSystemDir;
    private final HistoryStepDetailsCalculator mStepDetailsCalculator;
    private final Clock mClock;

    private int mMaxHistoryBufferSize;

    /**
     * The active history file that the history buffer is backed up into.
     */
    private AtomicFile mActiveFile;

    /**
     * A list of history files with increasing timestamps.
     */
    private final BatteryHistoryDirectory mHistoryDir;

    /**
     * A list of small history parcels, used when BatteryStatsImpl object is created from
     * deserialization of a parcel, such as Settings app or checkin file.
     */
    private List<Parcel> mHistoryParcels = null;

    /**
     * When iterating history files, the current file index.
     */
    private BatteryHistoryFile mCurrentFile;

    /**
     * When iterating history files, the current file parcel.
     */
    private Parcel mCurrentParcel;
    /**
     * When iterating history file, the current parcel's Parcel.dataSize().
     */
    private int mCurrentParcelEnd;
    /**
     * Used when BatteryStatsImpl object is created from deserialization of a parcel,
     * such as Settings app or checkin file, to iterate over history parcels.
     */
    private int mParcelIndex = 0;

    private final ReentrantLock mWriteLock = new ReentrantLock();

    private final HistoryItem mHistoryCur = new HistoryItem();

    private boolean mHaveBatteryLevel;
    private boolean mRecordingHistory;

    static final int HISTORY_TAG_INDEX_LIMIT = 0x7ffe;
    private static final int MAX_HISTORY_TAG_STRING_LENGTH = 1024;

    private final HashMap<HistoryTag, Integer> mHistoryTagPool = new HashMap<>();
    private SparseArray<HistoryTag> mHistoryTags;
    private final HistoryItem mHistoryLastWritten = new HistoryItem();
    private final HistoryItem mHistoryLastLastWritten = new HistoryItem();
    private final HistoryItem mHistoryAddTmp = new HistoryItem();
    private int mNextHistoryTagIdx = 0;
    private int mNumHistoryTagChars = 0;
    private int mHistoryBufferLastPos = -1;
    private long mTrackRunningHistoryElapsedRealtimeMs = 0;
    private long mTrackRunningHistoryUptimeMs = 0;
    private final MonotonicClock mMonotonicClock;
    // Monotonic time when we started writing to the history buffer
    private long mHistoryBufferStartTime;
    private final ArraySet<PowerStats.Descriptor> mWrittenPowerStatsDescriptors = new ArraySet<>();
    private byte mLastHistoryStepLevel = 0;
    private boolean mMutable = true;
    private final BatteryStatsHistory mWritableHistory;

    private static class BatteryHistoryFile implements Comparable<BatteryHistoryFile> {
        public final long monotonicTimeMs;
        public final AtomicFile atomicFile;

        private BatteryHistoryFile(File directory, long monotonicTimeMs) {
            this.monotonicTimeMs = monotonicTimeMs;
            atomicFile = new AtomicFile(new File(directory, monotonicTimeMs + FILE_SUFFIX));
        }

        @Override
        public int compareTo(BatteryHistoryFile o) {
            return Long.compare(monotonicTimeMs, o.monotonicTimeMs);
        }

        @Override
        public boolean equals(Object o) {
            return monotonicTimeMs == ((BatteryHistoryFile) o).monotonicTimeMs;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(monotonicTimeMs);
        }

        @Override
        public String toString() {
            return atomicFile.getBaseFile().toString();
        }
    }

    private static class BatteryHistoryDirectory {
        private final File mDirectory;
        private final MonotonicClock mMonotonicClock;
        private int mMaxHistoryFiles;
        private final List<BatteryHistoryFile> mHistoryFiles = new ArrayList<>();
        private final ReentrantLock mLock = new ReentrantLock();
        private boolean mCleanupNeeded;

        BatteryHistoryDirectory(File directory, MonotonicClock monotonicClock,
                int maxHistoryFiles) {
            mDirectory = directory;
            mMonotonicClock = monotonicClock;
            mMaxHistoryFiles = maxHistoryFiles;
            if (mMaxHistoryFiles == 0) {
                Slog.wtf(TAG, "mMaxHistoryFiles should not be zero when writing history");
            }
        }

        void setMaxHistoryFiles(int maxHistoryFiles) {
            mMaxHistoryFiles = maxHistoryFiles;
            cleanup();
        }

        void lock() {
            mLock.lock();
        }

        boolean tryLock() {
            return mLock.tryLock();
        }

        void unlock() {
            mLock.unlock();
            if (mCleanupNeeded) {
                cleanup();
            }
        }

        boolean isLocked() {
            return mLock.isLocked();
        }

        void load() {
            mDirectory.mkdirs();
            if (!mDirectory.exists()) {
                Slog.wtf(TAG, "HistoryDir does not exist:" + mDirectory.getPath());
            }

            final List<File> toRemove = new ArrayList<>();
            final Set<BatteryHistoryFile> dedup = new ArraySet<>();
            mDirectory.listFiles((dir, name) -> {
                final int b = name.lastIndexOf(FILE_SUFFIX);
                if (b <= 0) {
                    toRemove.add(new File(dir, name));
                    return false;
                }
                try {
                    long monotonicTime = Long.parseLong(name.substring(0, b));
                    dedup.add(new BatteryHistoryFile(mDirectory, monotonicTime));
                } catch (NumberFormatException e) {
                    toRemove.add(new File(dir, name));
                    return false;
                }
                return true;
            });
            if (!dedup.isEmpty()) {
                mHistoryFiles.addAll(dedup);
                Collections.sort(mHistoryFiles);
            }
            if (!toRemove.isEmpty()) {
                // Clear out legacy history files, which did not follow the X-Y.bin naming format.
                BackgroundThread.getHandler().post(() -> {
                    lock();
                    try {
                        for (File file : toRemove) {
                            file.delete();
                        }
                    } finally {
                        unlock();
                    }
                });
            }
        }

        List<String> getFileNames() {
            lock();
            try {
                List<String> names = new ArrayList<>();
                for (BatteryHistoryFile historyFile : mHistoryFiles) {
                    names.add(historyFile.atomicFile.getBaseFile().getName());
                }
                return names;
            } finally {
                unlock();
            }
        }

        @Nullable
        BatteryHistoryFile getFirstFile() {
            lock();
            try {
                if (!mHistoryFiles.isEmpty()) {
                    return mHistoryFiles.get(0);
                }
                return null;
            } finally {
                unlock();
            }
        }

        @Nullable
        BatteryHistoryFile getLastFile() {
            lock();
            try {
                if (!mHistoryFiles.isEmpty()) {
                    return mHistoryFiles.get(mHistoryFiles.size() - 1);
                }
                return null;
            } finally {
                unlock();
            }
        }

        @Nullable
        BatteryHistoryFile getNextFile(BatteryHistoryFile current, long startTimeMs,
                long endTimeMs) {
            if (!mLock.isHeldByCurrentThread()) {
                throw new IllegalStateException("Iterating battery history without a lock");
            }

            int nextFileIndex = 0;
            int firstFileIndex = 0;
            // skip the last file because its data is in history buffer.
            int lastFileIndex = mHistoryFiles.size() - 2;
            for (int i = lastFileIndex; i >= 0; i--) {
                BatteryHistoryFile file = mHistoryFiles.get(i);
                if (current != null && file.monotonicTimeMs == current.monotonicTimeMs) {
                    nextFileIndex = i + 1;
                }
                if (file.monotonicTimeMs > endTimeMs) {
                    lastFileIndex = i - 1;
                }
                if (file.monotonicTimeMs <= startTimeMs) {
                    firstFileIndex = i;
                    break;
                }
            }

            if (nextFileIndex < firstFileIndex) {
                nextFileIndex = firstFileIndex;
            }

            if (nextFileIndex <= lastFileIndex) {
                return mHistoryFiles.get(nextFileIndex);
            }

            return null;
        }

        BatteryHistoryFile makeBatteryHistoryFile() {
            BatteryHistoryFile file = new BatteryHistoryFile(mDirectory,
                    mMonotonicClock.monotonicTime());
            lock();
            try {
                mHistoryFiles.add(file);
            } finally {
                unlock();
            }
            return file;
        }

        void writeToParcel(Parcel out, boolean useBlobs) {
            lock();
            try {
                final long start = SystemClock.uptimeMillis();
                out.writeInt(mHistoryFiles.size() - 1);
                for (int i = 0; i < mHistoryFiles.size() - 1; i++) {
                    AtomicFile file = mHistoryFiles.get(i).atomicFile;
                    byte[] raw = new byte[0];
                    try {
                        raw = file.readFully();
                    } catch (Exception e) {
                        Slog.e(TAG, "Error reading file " + file.getBaseFile().getPath(), e);
                    }
                    if (useBlobs) {
                        out.writeBlob(raw);
                    } else {
                        // Avoiding blobs in the check-in file for compatibility
                        out.writeByteArray(raw);
                    }
                }
                if (DEBUG) {
                    Slog.d(TAG,
                            "writeToParcel duration ms:" + (SystemClock.uptimeMillis() - start));
                }
            } finally {
                unlock();
            }
        }

        int getFileCount() {
            lock();
            try {
                return mHistoryFiles.size();
            } finally {
                unlock();
            }
        }

        int getSize() {
            lock();
            try {
                int ret = 0;
                for (int i = 0; i < mHistoryFiles.size() - 1; i++) {
                    ret += (int) mHistoryFiles.get(i).atomicFile.getBaseFile().length();
                }
                return ret;
            } finally {
                unlock();
            }
        }

        void reset() {
            lock();
            try {
                if (DEBUG) Slog.i(TAG, "********** CLEARING HISTORY!");
                for (BatteryHistoryFile file : mHistoryFiles) {
                    file.atomicFile.delete();
                }
                mHistoryFiles.clear();
            } finally {
                unlock();
            }
        }

        private void cleanup() {
            if (mDirectory == null) {
                return;
            }

            if (!tryLock()) {
                mCleanupNeeded = true;
                return;
            }

            mCleanupNeeded = false;
            try {
                // if free disk space is less than 100MB, delete oldest history file.
                if (!hasFreeDiskSpace(mDirectory)) {
                    BatteryHistoryFile oldest = mHistoryFiles.remove(0);
                    oldest.atomicFile.delete();
                }

                // if there are more history files than allowed, delete oldest history files.
                // mMaxHistoryFiles comes from Constants.MAX_HISTORY_FILES and
                // can be updated by DeviceConfig at run time.
                while (mHistoryFiles.size() > mMaxHistoryFiles) {
                    BatteryHistoryFile oldest = mHistoryFiles.get(0);
                    oldest.atomicFile.delete();
                    mHistoryFiles.remove(0);
                }
            } finally {
                unlock();
            }
        }
    }

    /**
     * A delegate responsible for computing additional details for a step in battery history.
     */
    public interface HistoryStepDetailsCalculator {
        /**
         * Returns additional details for the current history step or null.
         */
        @Nullable
        HistoryStepDetails getHistoryStepDetails();

        /**
         * Resets the calculator to get ready for a new battery session
         */
        void clear();
    }

    /**
     * A delegate for android.os.Trace to allow testing static calls. Due to
     * limitations in Android Tracing (b/153319140), the delegate also records
     * counter values in system properties which allows reading the value at the
     * start of a tracing session. This overhead is limited to userdebug builds.
     * On user builds, tracing still occurs but the counter value will be missing
     * until the first change occurs.
     */
    @VisibleForTesting
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    public static class TraceDelegate {
        // Note: certain tests currently run as platform_app which is not allowed
        // to set debug system properties. To ensure that system properties are set
        // only when allowed, we check the current UID.
        private final boolean mShouldSetProperty =
                Build.IS_USERDEBUG && (Process.myUid() == Process.SYSTEM_UID);

        /**
         * Returns true if trace counters should be recorded.
         */
        public boolean tracingEnabled() {
            return Trace.isTagEnabled(Trace.TRACE_TAG_POWER) || mShouldSetProperty;
        }

        /**
         * Records the counter value with the given name.
         */
        public void traceCounter(@NonNull String name, int value) {
            Trace.traceCounter(Trace.TRACE_TAG_POWER, name, value);
            if (mShouldSetProperty) {
                try {
                    SystemProperties.set("debug.tracing." + name, Integer.toString(value));
                } catch (RuntimeException e) {
                    Slog.e(TAG, "Failed to set debug.tracing." + name, e);
                }
            }
        }

        /**
         * Records an instant event (one with no duration).
         */
        public void traceInstantEvent(@NonNull String track, @NonNull String name) {
            Trace.instantForTrack(Trace.TRACE_TAG_POWER, track, name);
        }
    }

    public static class EventLogger {
        /**
         * Records a statsd event when the batterystats config file is written to disk.
         */
        public void writeCommitSysConfigFile(long startTimeMs) {
            com.android.internal.logging.EventLogTags.writeCommitSysConfigFile(
                    "batterystats", SystemClock.uptimeMillis() - startTimeMs);
        }
    }

    private TraceDelegate mTracer;
    private int mTraceLastState = 0;
    private int mTraceLastState2 = 0;
    private final EventLogger mEventLogger;

    /**
     * Constructor
     *
     * @param systemDir            typically /data/system
     * @param maxHistoryFiles      the largest number of history buffer files to keep
     * @param maxHistoryBufferSize the most amount of RAM to used for buffering of history steps
     */
    public BatteryStatsHistory(Parcel historyBuffer, File systemDir,
            int maxHistoryFiles, int maxHistoryBufferSize,
            HistoryStepDetailsCalculator stepDetailsCalculator, Clock clock,
            MonotonicClock monotonicClock, TraceDelegate tracer, EventLogger eventLogger) {
        this(historyBuffer, systemDir, maxHistoryFiles, maxHistoryBufferSize, stepDetailsCalculator,
                clock, monotonicClock, tracer, eventLogger, null);
    }

    private BatteryStatsHistory(@Nullable Parcel historyBuffer, @Nullable File systemDir,
            int maxHistoryFiles, int maxHistoryBufferSize,
            @NonNull HistoryStepDetailsCalculator stepDetailsCalculator, @NonNull Clock clock,
            @NonNull MonotonicClock monotonicClock, @NonNull TraceDelegate tracer,
            @NonNull EventLogger eventLogger, @Nullable BatteryStatsHistory writableHistory) {
        mSystemDir = systemDir;
        mMaxHistoryBufferSize = maxHistoryBufferSize;
        mStepDetailsCalculator = stepDetailsCalculator;
        mTracer = tracer;
        mClock = clock;
        mMonotonicClock = monotonicClock;
        mEventLogger = eventLogger;
        mWritableHistory = writableHistory;
        if (mWritableHistory != null) {
            mMutable = false;
        }

        if (historyBuffer != null) {
            mHistoryBuffer = historyBuffer;
        } else {
            mHistoryBuffer = Parcel.obtain();
            initHistoryBuffer();
        }

        if (writableHistory != null) {
            mHistoryDir = writableHistory.mHistoryDir;
        } else if (systemDir != null) {
            mHistoryDir = new BatteryHistoryDirectory(new File(systemDir, HISTORY_DIR),
                    monotonicClock, maxHistoryFiles);
            mHistoryDir.load();
            BatteryHistoryFile activeFile = mHistoryDir.getLastFile();
            if (activeFile == null) {
                activeFile = mHistoryDir.makeBatteryHistoryFile();
            }
            setActiveFile(activeFile);
        } else {
            mHistoryDir = null;
        }
    }

    /**
     * Used when BatteryStatsHistory object is created from deserialization of a BatteryUsageStats
     * parcel.
     */
    private BatteryStatsHistory(Parcel parcel) {
        mClock = Clock.SYSTEM_CLOCK;
        mTracer = null;
        mSystemDir = null;
        mHistoryDir = null;
        mStepDetailsCalculator = null;
        mEventLogger = new EventLogger();
        mWritableHistory = null;
        mMutable = false;

        final byte[] historyBlob = parcel.readBlob();

        mHistoryBuffer = Parcel.obtain();
        mHistoryBuffer.unmarshall(historyBlob, 0, historyBlob.length);

        mMonotonicClock = null;
        readFromParcel(parcel, true /* useBlobs */);
    }

    private void initHistoryBuffer() {
        mTrackRunningHistoryElapsedRealtimeMs = 0;
        mTrackRunningHistoryUptimeMs = 0;
        mWrittenPowerStatsDescriptors.clear();

        mHistoryBufferStartTime = mMonotonicClock.monotonicTime();
        mHistoryBuffer.setDataSize(0);
        mHistoryBuffer.setDataPosition(0);
        mHistoryBuffer.setDataCapacity(mMaxHistoryBufferSize / 2);
        mHistoryLastLastWritten.clear();
        mHistoryLastWritten.clear();
        mHistoryTagPool.clear();
        mNextHistoryTagIdx = 0;
        mNumHistoryTagChars = 0;
        mHistoryBufferLastPos = -1;
        if (mStepDetailsCalculator != null) {
            mStepDetailsCalculator.clear();
        }
    }

    /**
     * Changes the maximum number of history files to be kept.
     */
    public void setMaxHistoryFiles(int maxHistoryFiles) {
        if (mHistoryDir != null) {
            mHistoryDir.setMaxHistoryFiles(maxHistoryFiles);
        }
    }

    /**
     * Changes the maximum size of the history buffer, in bytes.
     */
    public void setMaxHistoryBufferSize(int maxHistoryBufferSize) {
        mMaxHistoryBufferSize = maxHistoryBufferSize;
    }

    /**
     * Creates a read-only copy of the battery history.  Does not copy the files stored
     * in the system directory, so it is not safe while actively writing history.
     */
    public BatteryStatsHistory copy() {
        synchronized (this) {
            // Make a copy of battery history to avoid concurrent modification.
            Parcel historyBufferCopy = Parcel.obtain();
            historyBufferCopy.appendFrom(mHistoryBuffer, 0, mHistoryBuffer.dataSize());

            return new BatteryStatsHistory(historyBufferCopy, mSystemDir, 0, 0, null, null, null,
                    null, mEventLogger, this);
        }
    }

    /**
     * Returns true if this instance only supports reading history.
     */
    public boolean isReadOnly() {
        return !mMutable || mActiveFile == null/* || mHistoryDir == null*/;
    }

    /**
     * Set the active file that mHistoryBuffer is backed up into.
     */
    private void setActiveFile(BatteryHistoryFile file) {
        mActiveFile = file.atomicFile;
        if (DEBUG) {
            Slog.d(TAG, "activeHistoryFile:" + mActiveFile.getBaseFile().getPath());
        }
    }

    /**
     * When {@link #mHistoryBuffer} reaches {@link BatteryStatsImpl.Constants#MAX_HISTORY_BUFFER},
     * create next history file.
     */
    public void startNextFile(long elapsedRealtimeMs) {
        synchronized (this) {
            startNextFileLocked(elapsedRealtimeMs);
        }
    }

    @GuardedBy("this")
    private void startNextFileLocked(long elapsedRealtimeMs) {
        final long start = SystemClock.uptimeMillis();
        writeHistory();
        if (DEBUG) {
            Slog.d(TAG, "writeHistory took ms:" + (SystemClock.uptimeMillis() - start));
        }

        setActiveFile(mHistoryDir.makeBatteryHistoryFile());
        try {
            mActiveFile.getBaseFile().createNewFile();
        } catch (IOException e) {
            Slog.e(TAG, "Could not create history file: " + mActiveFile.getBaseFile());
        }

        mHistoryBufferStartTime = mMonotonicClock.monotonicTime(elapsedRealtimeMs);
        mHistoryBuffer.setDataSize(0);
        mHistoryBuffer.setDataPosition(0);
        mHistoryBuffer.setDataCapacity(mMaxHistoryBufferSize / 2);
        mHistoryBufferLastPos = -1;
        mHistoryLastWritten.clear();
        mHistoryLastLastWritten.clear();

        // Mark every entry in the pool with a flag indicating that the tag
        // has not yet been encountered while writing the current history buffer.
        for (Map.Entry<HistoryTag, Integer> entry : mHistoryTagPool.entrySet()) {
            entry.setValue(entry.getValue() | BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG);
        }

        mWrittenPowerStatsDescriptors.clear();
        mHistoryDir.cleanup();
    }

    /**
     * Returns true if it is safe to reset history. It will return false if the history is
     * currently being read.
     */
    public boolean isResetEnabled() {
        return mHistoryDir == null || !mHistoryDir.isLocked();
    }

    /**
     * Clear history buffer and delete all existing history files. Active history file start from
     * number 0 again.
     */
    public void reset() {
        synchronized (this) {
            if (mHistoryDir != null) {
                mHistoryDir.reset();
                setActiveFile(mHistoryDir.makeBatteryHistoryFile());
            }
            initHistoryBuffer();
        }
    }

    /**
     * Returns the monotonic clock time when the available battery history collection started.
     */
    public long getStartTime() {
        synchronized (this) {
            BatteryHistoryFile file = mHistoryDir.getFirstFile();
            if (file != null) {
                return file.monotonicTimeMs;
            } else {
                return mHistoryBufferStartTime;
            }
        }
    }

    /**
     * Start iterating history files and history buffer.
     *
     * @param startTimeMs monotonic time (the HistoryItem.time field) to start iterating from,
     *                    inclusive
     * @param endTimeMs monotonic time to stop iterating, exclusive.
     *                  Pass {@link MonotonicClock#UNDEFINED} to indicate current time.
     */
    @NonNull
    public BatteryStatsHistoryIterator iterate(long startTimeMs, long endTimeMs) {
        if (mMutable) {
            return copy().iterate(startTimeMs, endTimeMs);
        }

        if (mHistoryDir != null) {
            mHistoryDir.lock();
        }
        mCurrentFile = null;
        mCurrentParcel = null;
        mCurrentParcelEnd = 0;
        mParcelIndex = 0;
        return new BatteryStatsHistoryIterator(this, startTimeMs, endTimeMs);
    }

    /**
     * Finish iterating history files and history buffer.
     */
    void iteratorFinished() {
        mHistoryBuffer.setDataPosition(mHistoryBuffer.dataSize());
        if (mHistoryDir != null) {
            mHistoryDir.unlock();
        }
    }

    /**
     * When iterating history files and history buffer, always start from the lowest numbered
     * history file, when reached the mActiveFile (highest numbered history file), do not read from
     * mActiveFile, read from history buffer instead because the buffer has more updated data.
     *
     * @return The parcel that has next record. null if finished all history files and history
     * buffer
     */
    @Nullable
    public Parcel getNextParcel(long startTimeMs, long endTimeMs) {
        checkImmutable();

        // First iterate through all records in current parcel.
        if (mCurrentParcel != null) {
            if (mCurrentParcel.dataPosition() < mCurrentParcelEnd) {
                // There are more records in current parcel.
                return mCurrentParcel;
            } else if (mHistoryBuffer == mCurrentParcel) {
                // finished iterate through all history files and history buffer.
                return null;
            } else if (mHistoryParcels == null
                    || !mHistoryParcels.contains(mCurrentParcel)) {
                // current parcel is from history file.
                mCurrentParcel.recycle();
            }
        }

        if (mHistoryDir != null) {
            BatteryHistoryFile nextFile = mHistoryDir.getNextFile(mCurrentFile, startTimeMs,
                    endTimeMs);
            while (nextFile != null) {
                mCurrentParcel = null;
                mCurrentParcelEnd = 0;
                final Parcel p = Parcel.obtain();
                AtomicFile file = nextFile.atomicFile;
                if (readFileToParcel(p, file)) {
                    int bufSize = p.readInt();
                    int curPos = p.dataPosition();
                    mCurrentParcelEnd = curPos + bufSize;
                    mCurrentParcel = p;
                    if (curPos < mCurrentParcelEnd) {
                        mCurrentFile = nextFile;
                        return mCurrentParcel;
                    }
                } else {
                    p.recycle();
                }
                nextFile = mHistoryDir.getNextFile(nextFile, startTimeMs, endTimeMs);
            }
        }

        // mHistoryParcels is created when BatteryStatsImpl object is created from deserialization
        // of a parcel, such as Settings app or checkin file.
        if (mHistoryParcels != null) {
            while (mParcelIndex < mHistoryParcels.size()) {
                final Parcel p = mHistoryParcels.get(mParcelIndex++);
                if (!verifyVersion(p)) {
                    continue;
                }
                // skip monotonic time field.
                p.readLong();

                final int bufSize = p.readInt();
                final int curPos = p.dataPosition();
                mCurrentParcelEnd = curPos + bufSize;
                mCurrentParcel = p;
                if (curPos < mCurrentParcelEnd) {
                    return mCurrentParcel;
                }
            }
        }

        // finished iterator through history files (except the last one), now history buffer.
        if (mHistoryBuffer.dataSize() <= 0) {
            // buffer is empty.
            return null;
        }
        mHistoryBuffer.setDataPosition(0);
        mCurrentParcel = mHistoryBuffer;
        mCurrentParcelEnd = mCurrentParcel.dataSize();
        return mCurrentParcel;
    }

    private void checkImmutable() {
        if (mMutable) {
            throw new IllegalStateException("Iterating over a mutable battery history");
        }
    }

    /**
     * Read history file into a parcel.
     *
     * @param out  the Parcel read into.
     * @param file the File to read from.
     * @return true if success, false otherwise.
     */
    public boolean readFileToParcel(Parcel out, AtomicFile file) {
        byte[] raw = null;
        try {
            final long start = SystemClock.uptimeMillis();
            raw = file.readFully();
            if (DEBUG) {
                Slog.d(TAG, "readFileToParcel:" + file.getBaseFile().getPath()
                        + " duration ms:" + (SystemClock.uptimeMillis() - start));
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading file " + file.getBaseFile().getPath(), e);
            return false;
        }
        out.unmarshall(raw, 0, raw.length);
        out.setDataPosition(0);
        if (!verifyVersion(out)) {
            return false;
        }
        // skip monotonic time field.
        out.readLong();
        return true;
    }

    /**
     * Verify header part of history parcel.
     *
     * @return true if version match, false if not.
     */
    private boolean verifyVersion(Parcel p) {
        p.setDataPosition(0);
        final int version = p.readInt();
        return version == VERSION;
    }

    /**
     * Extracts the monotonic time, as per {@link MonotonicClock}, from the supplied battery history
     * buffer.
     */
    public long getHistoryBufferStartTime(Parcel p) {
        int pos = p.dataPosition();
        p.setDataPosition(0);
        p.readInt();        // Skip the version field
        long monotonicTime = p.readLong();
        p.setDataPosition(pos);
        return monotonicTime;
    }

    /**
     * Writes the battery history contents for persistence.
     */
    public void writeSummaryToParcel(Parcel out, boolean inclHistory) {
        out.writeBoolean(inclHistory);
        if (inclHistory) {
            writeToParcel(out);
        }

        out.writeInt(mHistoryTagPool.size());
        for (Map.Entry<HistoryTag, Integer> ent : mHistoryTagPool.entrySet()) {
            HistoryTag tag = ent.getKey();
            out.writeInt(ent.getValue());
            out.writeString(tag.string);
            out.writeInt(tag.uid);
        }
    }

    /**
     * Reads battery history contents from a persisted parcel.
     */
    public void readSummaryFromParcel(Parcel in) {
        boolean inclHistory = in.readBoolean();
        if (inclHistory) {
            readFromParcel(in);
        }

        mHistoryTagPool.clear();
        mNextHistoryTagIdx = 0;
        mNumHistoryTagChars = 0;

        int numTags = in.readInt();
        for (int i = 0; i < numTags; i++) {
            int idx = in.readInt();
            String str = in.readString();
            int uid = in.readInt();
            HistoryTag tag = new HistoryTag();
            tag.string = str;
            tag.uid = uid;
            tag.poolIdx = idx;
            mHistoryTagPool.put(tag, idx);
            if (idx >= mNextHistoryTagIdx) {
                mNextHistoryTagIdx = idx + 1;
            }
            mNumHistoryTagChars += tag.string.length() + 1;
        }
    }

    /**
     * Read all history files and serialize into a big Parcel.
     * Checkin file calls this method.
     *
     * @param out the output parcel
     */
    public void writeToParcel(Parcel out) {
        synchronized (this) {
            writeHistoryBuffer(out);
            writeToParcel(out, false /* useBlobs */);
        }
    }

    /**
     * This is for Settings app, when Settings app receives big history parcel, it call
     * this method to parse it into list of parcels.
     *
     * @param out the output parcel
     */
    public void writeToBatteryUsageStatsParcel(Parcel out) {
        synchronized (this) {
            out.writeBlob(mHistoryBuffer.marshall());
            writeToParcel(out, true /* useBlobs */);
        }
    }

    private void writeToParcel(Parcel out, boolean useBlobs) {
        if (mHistoryDir != null) {
            mHistoryDir.writeToParcel(out, useBlobs);
        }
    }

    /**
     * Reads a BatteryStatsHistory from a parcel written with
     * the {@link #writeToBatteryUsageStatsParcel} method.
     */
    public static BatteryStatsHistory createFromBatteryUsageStatsParcel(Parcel in) {
        return new BatteryStatsHistory(in);
    }

    /**
     * Read history from a check-in file.
     */
    public boolean readSummary() {
        if (mActiveFile == null) {
            Slog.w(TAG, "readSummary: no history file associated with this instance");
            return false;
        }

        Parcel parcel = Parcel.obtain();
        try {
            final long start = SystemClock.uptimeMillis();
            if (mActiveFile.exists()) {
                byte[] raw = mActiveFile.readFully();
                if (raw.length > 0) {
                    parcel.unmarshall(raw, 0, raw.length);
                    parcel.setDataPosition(0);
                    readHistoryBuffer(parcel);
                }
                if (DEBUG) {
                    Slog.d(TAG, "read history file::"
                            + mActiveFile.getBaseFile().getPath()
                            + " bytes:" + raw.length + " took ms:" + (SystemClock.uptimeMillis()
                            - start));
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error reading battery history", e);
            reset();
            return false;
        } finally {
            parcel.recycle();
        }
        return true;
    }

    /**
     * This is for the check-in file, which has all history files embedded.
     *
     * @param in the input parcel.
     */
    public void readFromParcel(Parcel in) {
        readHistoryBuffer(in);
        readFromParcel(in, false /* useBlobs */);
    }

    private void readFromParcel(Parcel in, boolean useBlobs) {
        final long start = SystemClock.uptimeMillis();
        mHistoryParcels = new ArrayList<>();
        final int count = in.readInt();
        for (int i = 0; i < count; i++) {
            byte[] temp = useBlobs ? in.readBlob() : in.createByteArray();
            if (temp == null || temp.length == 0) {
                continue;
            }
            Parcel p = Parcel.obtain();
            p.unmarshall(temp, 0, temp.length);
            p.setDataPosition(0);
            mHistoryParcels.add(p);
        }
        if (DEBUG) {
            Slog.d(TAG, "readFromParcel duration ms:" + (SystemClock.uptimeMillis() - start));
        }
    }

    /**
     * @return true if there is more than 100MB free disk space left.
     */
    @android.ravenwood.annotation.RavenwoodReplace
    private static boolean hasFreeDiskSpace(File systemDir) {
        final StatFs stats = new StatFs(systemDir.getAbsolutePath());
        return stats.getAvailableBytes() > MIN_FREE_SPACE;
    }

    private static boolean hasFreeDiskSpace$ravenwood(File systemDir) {
        return true;
    }

    @VisibleForTesting
    public List<String> getFilesNames() {
        return mHistoryDir.getFileNames();
    }

    @VisibleForTesting
    public AtomicFile getActiveFile() {
        return mActiveFile;
    }

    /**
     * @return the total size of all history files and history buffer.
     */
    public int getHistoryUsedSize() {
        int ret = mHistoryDir.getSize();
        ret += mHistoryBuffer.dataSize();
        if (mHistoryParcels != null) {
            for (int i = 0; i < mHistoryParcels.size(); i++) {
                ret += mHistoryParcels.get(i).dataSize();
            }
        }
        return ret;
    }

    /**
     * Enables/disables recording of history.  When disabled, all "record*" calls are a no-op.
     */
    public void setHistoryRecordingEnabled(boolean enabled) {
        synchronized (this) {
            mRecordingHistory = enabled;
        }
    }

    /**
     * Returns true if history recording is enabled.
     */
    public boolean isRecordingHistory() {
        synchronized (this) {
            return mRecordingHistory;
        }
    }

    /**
     * Forces history recording regardless of charging state.
     */
    @VisibleForTesting
    public void forceRecordAllHistory() {
        synchronized (this) {
            mHaveBatteryLevel = true;
            mRecordingHistory = true;
        }
    }

    /**
     * Starts a history buffer by recording the current wall-clock time.
     */
    public void startRecordingHistory(final long elapsedRealtimeMs, final long uptimeMs,
            boolean reset) {
        synchronized (this) {
            mRecordingHistory = true;
            mHistoryCur.currentTime = mClock.currentTimeMillis();
            writeHistoryItem(elapsedRealtimeMs, uptimeMs, mHistoryCur,
                    reset ? HistoryItem.CMD_RESET : HistoryItem.CMD_CURRENT_TIME);
            mHistoryCur.currentTime = 0;
        }
    }

    /**
     * Prepares to continue recording after restoring previous history from persistent storage.
     */
    public void continueRecordingHistory() {
        synchronized (this) {
            if (mHistoryBuffer.dataPosition() <= 0 && mHistoryDir.getFileCount() <= 1) {
                return;
            }

            mRecordingHistory = true;
            final long elapsedRealtimeMs = mClock.elapsedRealtime();
            final long uptimeMs = mClock.uptimeMillis();
            writeHistoryItem(elapsedRealtimeMs, uptimeMs, mHistoryCur, HistoryItem.CMD_START);
            startRecordingHistory(elapsedRealtimeMs, uptimeMs, false);
        }
    }

    /**
     * Notes the current battery state to be reflected in the next written history item.
     */
    public void setBatteryState(boolean charging, int status, int level, int chargeUah) {
        synchronized (this) {
            mHaveBatteryLevel = true;
            setChargingState(charging);
            mHistoryCur.batteryStatus = (byte) status;
            mHistoryCur.batteryLevel = (byte) level;
            mHistoryCur.batteryChargeUah = chargeUah;
        }
    }

    /**
     * Notes the current battery state to be reflected in the next written history item.
     */
    public void setBatteryState(int status, int level, int health, int plugType, int temperature,
            int voltageMv, int chargeUah) {
        synchronized (this) {
            mHaveBatteryLevel = true;
            mHistoryCur.batteryStatus = (byte) status;
            mHistoryCur.batteryLevel = (byte) level;
            mHistoryCur.batteryHealth = (byte) health;
            mHistoryCur.batteryPlugType = (byte) plugType;
            mHistoryCur.batteryTemperature = (short) temperature;
            mHistoryCur.batteryVoltage = (short) voltageMv;
            mHistoryCur.batteryChargeUah = chargeUah;
        }
    }

    /**
     * Notes the current power plugged-in state to be reflected in the next written history item.
     */
    public void setPluggedInState(boolean pluggedIn) {
        synchronized (this) {
            if (pluggedIn) {
                mHistoryCur.states |= HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
            } else {
                mHistoryCur.states &= ~HistoryItem.STATE_BATTERY_PLUGGED_FLAG;
            }
        }
    }

    /**
     * Notes the current battery charging state to be reflected in the next written history item.
     */
    public void setChargingState(boolean charging) {
        synchronized (this) {
            if (charging) {
                mHistoryCur.states2 |= HistoryItem.STATE2_CHARGING_FLAG;
            } else {
                mHistoryCur.states2 &= ~HistoryItem.STATE2_CHARGING_FLAG;
            }
        }
    }

    /**
     * Records a history event with the given code, name and UID.
     */
    public void recordEvent(long elapsedRealtimeMs, long uptimeMs, int code, String name,
            int uid) {
        synchronized (this) {
            mHistoryCur.eventCode = code;
            mHistoryCur.eventTag = mHistoryCur.localEventTag;
            mHistoryCur.eventTag.string = name;
            mHistoryCur.eventTag.uid = uid;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a time change event.
     */
    public void recordCurrentTimeChange(long elapsedRealtimeMs, long uptimeMs, long currentTimeMs) {
        synchronized (this) {
            if (!mRecordingHistory) {
                return;
            }

            mHistoryCur.currentTime = currentTimeMs;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs, mHistoryCur,
                    HistoryItem.CMD_CURRENT_TIME);
            mHistoryCur.currentTime = 0;
        }
    }

    /**
     * Records a system shutdown event.
     */
    public void recordShutdownEvent(long elapsedRealtimeMs, long uptimeMs, long currentTimeMs) {
        synchronized (this) {
            if (!mRecordingHistory) {
                return;
            }

            mHistoryCur.currentTime = currentTimeMs;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs, mHistoryCur, HistoryItem.CMD_SHUTDOWN);
            mHistoryCur.currentTime = 0;
        }
    }

    /**
     * Records a battery state change event.
     */
    public void recordBatteryState(long elapsedRealtimeMs, long uptimeMs, int batteryLevel,
            boolean isPlugged) {
        synchronized (this) {
            mHistoryCur.batteryLevel = (byte) batteryLevel;
            setPluggedInState(isPlugged);
            if (DEBUG) {
                Slog.v(TAG, "Battery unplugged to: "
                        + Integer.toHexString(mHistoryCur.states));
            }
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a PowerStats snapshot.
     */
    public void recordPowerStats(long elapsedRealtimeMs, long uptimeMs,
            PowerStats powerStats) {
        synchronized (this) {
            mHistoryCur.powerStats = powerStats;
            mHistoryCur.states2 |= HistoryItem.STATE2_EXTENSIONS_FLAG;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records the change of a UID's proc state.
     */
    public void recordProcessStateChange(long elapsedRealtimeMs, long uptimeMs,
            int uid, @BatteryConsumer.ProcessState int processState) {
        synchronized (this) {
            mHistoryCur.processStateChange = mHistoryCur.localProcessStateChange;
            mHistoryCur.processStateChange.uid = uid;
            mHistoryCur.processStateChange.processState = processState;
            mHistoryCur.states2 |= HistoryItem.STATE2_EXTENSIONS_FLAG;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a history item with the amount of charge consumed by WiFi.  Used on certain devices
     * equipped with on-device power metering.
     */
    public void recordWifiConsumedCharge(long elapsedRealtimeMs, long uptimeMs,
            double monitoredRailChargeMah) {
        synchronized (this) {
            mHistoryCur.wifiRailChargeMah += monitoredRailChargeMah;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a wakelock start event.
     */
    public void recordWakelockStartEvent(long elapsedRealtimeMs, long uptimeMs, String historyName,
            int uid) {
        synchronized (this) {
            mHistoryCur.wakelockTag = mHistoryCur.localWakelockTag;
            mHistoryCur.wakelockTag.string = historyName;
            mHistoryCur.wakelockTag.uid = uid;
            recordStateStartEvent(elapsedRealtimeMs, uptimeMs, HistoryItem.STATE_WAKE_LOCK_FLAG);
        }
    }

    /**
     * Updates the previous history event with a wakelock name and UID.
     */
    public boolean maybeUpdateWakelockTag(long elapsedRealtimeMs, long uptimeMs, String historyName,
            int uid) {
        synchronized (this) {
            if (mHistoryLastWritten.cmd != HistoryItem.CMD_UPDATE) {
                return false;
            }
            if (mHistoryLastWritten.wakelockTag != null) {
                // We'll try to update the last tag.
                mHistoryLastWritten.wakelockTag = null;
                mHistoryCur.wakelockTag = mHistoryCur.localWakelockTag;
                mHistoryCur.wakelockTag.string = historyName;
                mHistoryCur.wakelockTag.uid = uid;
                writeHistoryItem(elapsedRealtimeMs, uptimeMs);
            }
            return true;
        }
    }

    /**
     * Records a wakelock release event.
     */
    public void recordWakelockStopEvent(long elapsedRealtimeMs, long uptimeMs, String historyName,
            int uid) {
        synchronized (this) {
            mHistoryCur.wakelockTag = mHistoryCur.localWakelockTag;
            mHistoryCur.wakelockTag.string = historyName != null ? historyName : "";
            mHistoryCur.wakelockTag.uid = uid;
            recordStateStopEvent(elapsedRealtimeMs, uptimeMs, HistoryItem.STATE_WAKE_LOCK_FLAG);
        }
    }

    /**
     * Records an event when some state flag changes to true.
     */
    public void recordStateStartEvent(long elapsedRealtimeMs, long uptimeMs, int stateFlags) {
        synchronized (this) {
            mHistoryCur.states |= stateFlags;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records an event when some state flag changes to false.
     */
    public void recordStateStopEvent(long elapsedRealtimeMs, long uptimeMs, int stateFlags) {
        synchronized (this) {
            mHistoryCur.states &= ~stateFlags;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records an event when some state flags change to true and some to false.
     */
    public void recordStateChangeEvent(long elapsedRealtimeMs, long uptimeMs, int stateStartFlags,
            int stateStopFlags) {
        synchronized (this) {
            mHistoryCur.states = (mHistoryCur.states | stateStartFlags) & ~stateStopFlags;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records an event when some state2 flag changes to true.
     */
    public void recordState2StartEvent(long elapsedRealtimeMs, long uptimeMs, int stateFlags) {
        synchronized (this) {
            mHistoryCur.states2 |= stateFlags;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records an event when some state2 flag changes to false.
     */
    public void recordState2StopEvent(long elapsedRealtimeMs, long uptimeMs, int stateFlags) {
        synchronized (this) {
            mHistoryCur.states2 &= ~stateFlags;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records an wakeup event.
     */
    public void recordWakeupEvent(long elapsedRealtimeMs, long uptimeMs, String reason) {
        synchronized (this) {
            mHistoryCur.wakeReasonTag = mHistoryCur.localWakeReasonTag;
            mHistoryCur.wakeReasonTag.string = reason;
            mHistoryCur.wakeReasonTag.uid = 0;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a screen brightness change event.
     */
    public void recordScreenBrightnessEvent(long elapsedRealtimeMs, long uptimeMs,
            int brightnessBin) {
        synchronized (this) {
            mHistoryCur.states = setBitField(mHistoryCur.states, brightnessBin,
                    HistoryItem.STATE_BRIGHTNESS_SHIFT,
                    HistoryItem.STATE_BRIGHTNESS_MASK);
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a GNSS signal level change event.
     */
    public void recordGpsSignalQualityEvent(long elapsedRealtimeMs, long uptimeMs,
            int signalLevel) {
        synchronized (this) {
            mHistoryCur.states2 = setBitField(mHistoryCur.states2, signalLevel,
                    HistoryItem.STATE2_GPS_SIGNAL_QUALITY_SHIFT,
                    HistoryItem.STATE2_GPS_SIGNAL_QUALITY_MASK);
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a device idle mode change event.
     */
    public void recordDeviceIdleEvent(long elapsedRealtimeMs, long uptimeMs, int mode) {
        synchronized (this) {
            mHistoryCur.states2 = setBitField(mHistoryCur.states2, mode,
                    HistoryItem.STATE2_DEVICE_IDLE_SHIFT,
                    HistoryItem.STATE2_DEVICE_IDLE_MASK);
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a telephony state change event.
     */
    public void recordPhoneStateChangeEvent(long elapsedRealtimeMs, long uptimeMs, int addStateFlag,
            int removeStateFlag, int state, int signalStrength) {
        synchronized (this) {
            mHistoryCur.states = (mHistoryCur.states | addStateFlag) & ~removeStateFlag;
            if (state != -1) {
                mHistoryCur.states =
                        setBitField(mHistoryCur.states, state,
                                HistoryItem.STATE_PHONE_STATE_SHIFT,
                                HistoryItem.STATE_PHONE_STATE_MASK);
            }
            if (signalStrength != -1) {
                mHistoryCur.states =
                        setBitField(mHistoryCur.states, signalStrength,
                                HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_SHIFT,
                                HistoryItem.STATE_PHONE_SIGNAL_STRENGTH_MASK);
            }
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a data connection type change event.
     */
    public void recordDataConnectionTypeChangeEvent(long elapsedRealtimeMs, long uptimeMs,
            int dataConnectionType) {
        synchronized (this) {
            mHistoryCur.states = setBitField(mHistoryCur.states, dataConnectionType,
                    HistoryItem.STATE_DATA_CONNECTION_SHIFT,
                    HistoryItem.STATE_DATA_CONNECTION_MASK);
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a data connection type change event.
     */
    public void recordNrStateChangeEvent(long elapsedRealtimeMs, long uptimeMs,
            int nrState) {
        synchronized (this) {
            mHistoryCur.states2 = setBitField(mHistoryCur.states2, nrState,
                    HistoryItem.STATE2_NR_STATE_SHIFT,
                    HistoryItem.STATE2_NR_STATE_MASK);
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a WiFi supplicant state change event.
     */
    public void recordWifiSupplicantStateChangeEvent(long elapsedRealtimeMs, long uptimeMs,
            int supplState) {
        synchronized (this) {
            mHistoryCur.states2 =
                    setBitField(mHistoryCur.states2, supplState,
                            HistoryItem.STATE2_WIFI_SUPPL_STATE_SHIFT,
                            HistoryItem.STATE2_WIFI_SUPPL_STATE_MASK);
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records a WiFi signal strength change event.
     */
    public void recordWifiSignalStrengthChangeEvent(long elapsedRealtimeMs, long uptimeMs,
            int strengthBin) {
        synchronized (this) {
            mHistoryCur.states2 =
                    setBitField(mHistoryCur.states2, strengthBin,
                            HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_SHIFT,
                            HistoryItem.STATE2_WIFI_SIGNAL_STRENGTH_MASK);
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Writes event details into Atrace.
     */
    private void recordTraceEvents(int code, HistoryTag tag) {
        if (code == HistoryItem.EVENT_NONE) return;

        final int idx = code & HistoryItem.EVENT_TYPE_MASK;
        final String prefix = (code & HistoryItem.EVENT_FLAG_START) != 0 ? "+" :
                (code & HistoryItem.EVENT_FLAG_FINISH) != 0 ? "-" : "";

        final String[] names = BatteryStats.HISTORY_EVENT_NAMES;
        if (idx < 0 || idx >= names.length) return;

        final String track = "battery_stats." + names[idx];
        final String name = prefix + names[idx] + "=" + tag.uid + ":\"" + tag.string + "\"";
        mTracer.traceInstantEvent(track, name);
    }

    /**
     * Writes changes to a HistoryItem state bitmap to Atrace.
     */
    private void recordTraceCounters(int oldval, int newval, int mask,
            BitDescription[] descriptions) {
        int diff = (oldval ^ newval) & mask;
        if (diff == 0) return;

        for (int i = 0; i < descriptions.length; i++) {
            BitDescription bd = descriptions[i];
            if ((diff & bd.mask) == 0) continue;

            int value;
            if (bd.shift < 0) {
                value = (newval & bd.mask) != 0 ? 1 : 0;
            } else {
                value = (newval & bd.mask) >> bd.shift;
            }
            mTracer.traceCounter("battery_stats." + bd.name, value);
        }
    }

    private int setBitField(int bits, int value, int shift, int mask) {
        int shiftedValue = value << shift;
        if ((shiftedValue & ~mask) != 0) {
            Slog.wtfStack(TAG, "Value " + Integer.toHexString(value)
                    + " does not fit in the bit field: " + Integer.toHexString(mask));
            shiftedValue &= mask;
        }
        return (bits & ~mask) | shiftedValue;
    }

    /**
     * Writes the current history item to history.
     */
    public void writeHistoryItem(long elapsedRealtimeMs, long uptimeMs) {
        synchronized (this) {
            if (mTrackRunningHistoryElapsedRealtimeMs != 0) {
                final long diffElapsedMs =
                        elapsedRealtimeMs - mTrackRunningHistoryElapsedRealtimeMs;
                final long diffUptimeMs = uptimeMs - mTrackRunningHistoryUptimeMs;
                if (diffUptimeMs < (diffElapsedMs - 20)) {
                    final long wakeElapsedTimeMs =
                            elapsedRealtimeMs - (diffElapsedMs - diffUptimeMs);
                    mHistoryAddTmp.setTo(mHistoryLastWritten);
                    mHistoryAddTmp.wakelockTag = null;
                    mHistoryAddTmp.wakeReasonTag = null;
                    mHistoryAddTmp.eventCode = HistoryItem.EVENT_NONE;
                    mHistoryAddTmp.states &= ~HistoryItem.STATE_CPU_RUNNING_FLAG;
                    writeHistoryItem(wakeElapsedTimeMs, uptimeMs, mHistoryAddTmp);
                }
            }
            mHistoryCur.states |= HistoryItem.STATE_CPU_RUNNING_FLAG;
            mTrackRunningHistoryElapsedRealtimeMs = elapsedRealtimeMs;
            mTrackRunningHistoryUptimeMs = uptimeMs;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs, mHistoryCur);
        }
    }

    @GuardedBy("this")
    private void writeHistoryItem(long elapsedRealtimeMs, long uptimeMs, HistoryItem cur) {
        if (mTracer != null && mTracer.tracingEnabled()) {
            recordTraceEvents(cur.eventCode, cur.eventTag);
            recordTraceCounters(mTraceLastState, cur.states, STATE1_TRACE_MASK,
                    BatteryStats.HISTORY_STATE_DESCRIPTIONS);
            recordTraceCounters(mTraceLastState2, cur.states2, STATE2_TRACE_MASK,
                    BatteryStats.HISTORY_STATE2_DESCRIPTIONS);
            mTraceLastState = cur.states;
            mTraceLastState2 = cur.states2;
        }

        if ((!mHaveBatteryLevel || !mRecordingHistory)
                && cur.powerStats == null
                && cur.processStateChange == null) {
            return;
        }

        if (!mMutable) {
            throw new ConcurrentModificationException("Battery history is not writable");
        }

        final long timeDiffMs = mMonotonicClock.monotonicTime(elapsedRealtimeMs)
                                - mHistoryLastWritten.time;
        final int diffStates = mHistoryLastWritten.states ^ cur.states;
        final int diffStates2 = mHistoryLastWritten.states2 ^ cur.states2;
        final int lastDiffStates = mHistoryLastWritten.states ^ mHistoryLastLastWritten.states;
        final int lastDiffStates2 = mHistoryLastWritten.states2 ^ mHistoryLastLastWritten.states2;
        if (DEBUG) {
            Slog.i(TAG, "ADD: tdelta=" + timeDiffMs + " diff="
                    + Integer.toHexString(diffStates) + " lastDiff="
                    + Integer.toHexString(lastDiffStates) + " diff2="
                    + Integer.toHexString(diffStates2) + " lastDiff2="
                    + Integer.toHexString(lastDiffStates2));
        }

        if (mHistoryBufferLastPos >= 0 && mHistoryLastWritten.cmd == HistoryItem.CMD_UPDATE
                && timeDiffMs < 1000 && (diffStates & lastDiffStates) == 0
                && (diffStates2 & lastDiffStates2) == 0
                && (!mHistoryLastWritten.tagsFirstOccurrence && !cur.tagsFirstOccurrence)
                && (mHistoryLastWritten.wakelockTag == null || cur.wakelockTag == null)
                && (mHistoryLastWritten.wakeReasonTag == null || cur.wakeReasonTag == null)
                && mHistoryLastWritten.stepDetails == null
                && (mHistoryLastWritten.eventCode == HistoryItem.EVENT_NONE
                || cur.eventCode == HistoryItem.EVENT_NONE)
                && mHistoryLastWritten.batteryLevel == cur.batteryLevel
                && mHistoryLastWritten.batteryStatus == cur.batteryStatus
                && mHistoryLastWritten.batteryHealth == cur.batteryHealth
                && mHistoryLastWritten.batteryPlugType == cur.batteryPlugType
                && mHistoryLastWritten.batteryTemperature == cur.batteryTemperature
                && mHistoryLastWritten.batteryVoltage == cur.batteryVoltage
                && mHistoryLastWritten.powerStats == null
                && mHistoryLastWritten.processStateChange == null) {
            // We can merge this new change in with the last one.  Merging is
            // allowed as long as only the states have changed, and within those states
            // as long as no bit has changed both between now and the last entry, as
            // well as the last entry and the one before it (so we capture any toggles).
            if (DEBUG) Slog.i(TAG, "ADD: rewinding back to " + mHistoryBufferLastPos);
            mHistoryBuffer.setDataSize(mHistoryBufferLastPos);
            mHistoryBuffer.setDataPosition(mHistoryBufferLastPos);
            mHistoryBufferLastPos = -1;

            elapsedRealtimeMs -= timeDiffMs;

            // If the last written history had a wakelock tag, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have a wakelock tag.
            if (mHistoryLastWritten.wakelockTag != null) {
                cur.wakelockTag = cur.localWakelockTag;
                cur.wakelockTag.setTo(mHistoryLastWritten.wakelockTag);
            }
            // If the last written history had a wake reason tag, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have a wakelock tag.
            if (mHistoryLastWritten.wakeReasonTag != null) {
                cur.wakeReasonTag = cur.localWakeReasonTag;
                cur.wakeReasonTag.setTo(mHistoryLastWritten.wakeReasonTag);
            }
            // If the last written history had an event, we need to retain it.
            // Note that the condition above made sure that we aren't in a case where
            // both it and the current history item have an event.
            if (mHistoryLastWritten.eventCode != HistoryItem.EVENT_NONE) {
                cur.eventCode = mHistoryLastWritten.eventCode;
                cur.eventTag = cur.localEventTag;
                cur.eventTag.setTo(mHistoryLastWritten.eventTag);
            }
            mHistoryLastWritten.setTo(mHistoryLastLastWritten);
        }

        if (maybeFlushBufferAndWriteHistoryItem(cur, elapsedRealtimeMs, uptimeMs)) {
            return;
        }

        if (mHistoryBuffer.dataSize() == 0) {
            // The history is currently empty; we need it to start with a time stamp.
            HistoryItem copy = new HistoryItem();
            copy.setTo(cur);
            copy.currentTime = mClock.currentTimeMillis();
            copy.wakelockTag = null;
            copy.wakeReasonTag = null;
            copy.eventCode = HistoryItem.EVENT_NONE;
            copy.eventTag = null;
            copy.tagsFirstOccurrence = false;
            copy.powerStats = null;
            copy.processStateChange = null;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs, copy, HistoryItem.CMD_RESET);
        }
        writeHistoryItem(elapsedRealtimeMs, uptimeMs, cur, HistoryItem.CMD_UPDATE);
    }

    @GuardedBy("this")
    private boolean maybeFlushBufferAndWriteHistoryItem(HistoryItem cur, long elapsedRealtimeMs,
            long uptimeMs) {
        int dataSize = mHistoryBuffer.dataSize();
        if (dataSize < mMaxHistoryBufferSize) {
            return false;
        }

        if (mMaxHistoryBufferSize == 0) {
            Slog.wtf(TAG, "mMaxHistoryBufferSize should not be zero when writing history");
            mMaxHistoryBufferSize = 1024;
        }

        boolean successfullyLocked = mHistoryDir.tryLock();
        if (!successfullyLocked) {      // Already locked by another thread
            // If the buffer size is below the allowed overflow limit, just keep going
            if (dataSize < mMaxHistoryBufferSize + EXTRA_BUFFER_SIZE_WHEN_DIR_LOCKED) {
                return false;
            }

            // Report the long contention as a WTF and flush the buffer anyway, potentially
            // triggering a watchdog kill, which is still better than spinning forever.
            Slog.wtf(TAG, "History buffer overflow exceeds " + EXTRA_BUFFER_SIZE_WHEN_DIR_LOCKED
                    + " bytes");
        }

        // Make a copy of mHistoryCur before starting a new file
        HistoryItem copy = new HistoryItem();
        copy.setTo(cur);

        try {
            startNextFile(elapsedRealtimeMs);
        } finally {
            if (successfullyLocked) {
                mHistoryDir.unlock();
            }
        }

        // startRecordingHistory will reset mHistoryCur.
        startRecordingHistory(elapsedRealtimeMs, uptimeMs, false);

        // Add the copy into history buffer.
        writeHistoryItem(elapsedRealtimeMs, uptimeMs, copy, HistoryItem.CMD_UPDATE);
        return true;
    }

    @GuardedBy("this")
    private void writeHistoryItem(long elapsedRealtimeMs,
            @SuppressWarnings("UnusedVariable") long uptimeMs, HistoryItem cur, byte cmd) {
        if (!mMutable) {
            throw new ConcurrentModificationException("Battery history is not writable");
        }
        mHistoryBufferLastPos = mHistoryBuffer.dataPosition();
        mHistoryLastLastWritten.setTo(mHistoryLastWritten);
        final boolean hasTags = mHistoryLastWritten.tagsFirstOccurrence || cur.tagsFirstOccurrence;
        mHistoryLastWritten.setTo(mMonotonicClock.monotonicTime(elapsedRealtimeMs), cmd, cur);
        if (mHistoryLastWritten.time < mHistoryLastLastWritten.time - 60000) {
            Slog.wtf(TAG, "Significantly earlier event written to battery history:"
                    + " time=" + mHistoryLastWritten.time
                    + " previous=" + mHistoryLastLastWritten.time);
        }
        mHistoryLastWritten.tagsFirstOccurrence = hasTags;
        writeHistoryDelta(mHistoryBuffer, mHistoryLastWritten, mHistoryLastLastWritten);
        cur.wakelockTag = null;
        cur.wakeReasonTag = null;
        cur.eventCode = HistoryItem.EVENT_NONE;
        cur.eventTag = null;
        cur.tagsFirstOccurrence = false;
        cur.powerStats = null;
        cur.processStateChange = null;
        if (DEBUG) {
            Slog.i(TAG, "Writing history buffer: was " + mHistoryBufferLastPos
                    + " now " + mHistoryBuffer.dataPosition()
                    + " size is now " + mHistoryBuffer.dataSize());
        }
    }

    /*
        The history delta format uses flags to denote further data in subsequent ints in the parcel.

        There is always the first token, which may contain the delta time, or an indicator of
        the length of the time (int or long) following this token.

        First token: always present,
        31              23              15               7             0
        █M|L|K|J|I|H|G|F█E|D|C|B|A|T|T|T█T|T|T|T|T|T|T|T█T|T|T|T|T|T|T|T█

        T: the delta time if it is <= 0x7fffd. Otherwise 0x7fffe indicates an int immediately
           follows containing the time, and 0x7ffff indicates a long immediately follows with the
           delta time.
        A: battery level changed and an int follows with battery data.
        B: state changed and an int follows with state change data.
        C: state2 has changed and an int follows with state2 change data.
        D: wakelock/wakereason has changed and an wakelock/wakereason struct follows.
        E: event data has changed and an event struct follows.
        F: battery charge in coulombs has changed and an int with the charge follows.
        G: state flag denoting that the mobile radio was active.
        H: state flag denoting that the wifi radio was active.
        I: state flag denoting that a wifi scan occurred.
        J: state flag denoting that a wifi full lock was held.
        K: state flag denoting that the gps was on.
        L: state flag denoting that a wakelock was held.
        M: state flag denoting that the cpu was running.

        Time int/long: if T in the first token is 0x7ffff or 0x7fffe, then an int or long follows
        with the time delta.

        Battery level int: if A in the first token is set,
        31              23              15               7             0
        █L|L|L|L|L|L|L|T█T|T|T|T|T|T|T|T█T|V|V|V|V|V|V|V█V|V|V|V|V|V|V|D█

        D: indicates that extra history details follow.
        V: the battery voltage.
        T: the battery temperature.
        L: the battery level (out of 100).

        State change int: if B in the first token is set,
        31              23              15               7             0
        █S|S|S|H|H|H|P|P█F|E|D|C|B| | |A█ | | | | | | | █ | | | | | | | █

        A: wifi multicast was on.
        B: battery was plugged in.
        C: screen was on.
        D: phone was scanning for signal.
        E: audio was on.
        F: a sensor was active.

        State2 change int: if C in the first token is set,
        31              23              15               7             0
        █M|L|K|J|I|H|H|G█F|E|D|C| | | | █ | | | | |O|O|N█N|B|B|B|A|A|A|A█

        A: 4 bits indicating the wifi supplicant state: {@link BatteryStats#WIFI_SUPPL_STATE_NAMES}.
        B: 3 bits indicating the wifi signal strength: 0, 1, 2, 3, 4.
        C: a bluetooth scan was active.
        D: the camera was active.
        E: bluetooth was on.
        F: a phone call was active.
        G: the device was charging.
        H: 2 bits indicating the device-idle (doze) state: off, light, full
        I: the flashlight was on.
        J: wifi was on.
        K: wifi was running.
        L: video was playing.
        M: power save mode was on.
        N: 2 bits indicating the gps signal strength: poor, good, none.
        O: 2 bits indicating nr state: none, restricted, not restricted, connected.

        Wakelock/wakereason struct: if D in the first token is set,
        Event struct: if E in the first token is set,
        History step details struct: if D in the battery level int is set,

        Battery charge int: if F in the first token is set, an int representing the battery charge
        in coulombs follows.
     */
    /**
     * Writes the delta between the previous and current history items into history buffer.
     */
    @GuardedBy("this")
    private void writeHistoryDelta(Parcel dest, HistoryItem cur, HistoryItem last) {
        if (last == null || cur.cmd != HistoryItem.CMD_UPDATE) {
            dest.writeInt(BatteryStatsHistory.DELTA_TIME_ABS);
            cur.writeToParcel(dest, 0);
            return;
        }

        int extensionFlags = 0;
        final long deltaTime = cur.time - last.time;
        final int lastBatteryLevelInt = buildBatteryLevelInt(last);
        final int lastStateInt = buildStateInt(last);

        int deltaTimeToken;
        if (deltaTime < 0 || deltaTime > Integer.MAX_VALUE) {
            deltaTimeToken = BatteryStatsHistory.DELTA_TIME_LONG;
        } else if (deltaTime >= BatteryStatsHistory.DELTA_TIME_ABS) {
            deltaTimeToken = BatteryStatsHistory.DELTA_TIME_INT;
        } else {
            deltaTimeToken = (int) deltaTime;
        }
        int firstToken = deltaTimeToken | (cur.states & BatteryStatsHistory.DELTA_STATE_MASK);
        int batteryLevelInt = buildBatteryLevelInt(cur);

        if (cur.batteryLevel < mLastHistoryStepLevel || mLastHistoryStepLevel == 0) {
            cur.stepDetails = mStepDetailsCalculator.getHistoryStepDetails();
            if (cur.stepDetails != null) {
                batteryLevelInt |= BatteryStatsHistory.BATTERY_LEVEL_DETAILS_FLAG;
                mLastHistoryStepLevel = cur.batteryLevel;
            }
        } else {
            cur.stepDetails = null;
            mLastHistoryStepLevel = cur.batteryLevel;
        }

        final boolean batteryLevelIntChanged = batteryLevelInt != lastBatteryLevelInt;
        if (batteryLevelIntChanged) {
            firstToken |= BatteryStatsHistory.DELTA_BATTERY_LEVEL_FLAG;
        }
        final int stateInt = buildStateInt(cur);
        final boolean stateIntChanged = stateInt != lastStateInt;
        if (stateIntChanged) {
            firstToken |= BatteryStatsHistory.DELTA_STATE_FLAG;
        }
        if (cur.powerStats != null) {
            extensionFlags |= BatteryStatsHistory.EXTENSION_POWER_STATS_FLAG;
            if (!mWrittenPowerStatsDescriptors.contains(cur.powerStats.descriptor)) {
                extensionFlags |= BatteryStatsHistory.EXTENSION_POWER_STATS_DESCRIPTOR_FLAG;
            }
        }
        if (cur.processStateChange != null) {
            extensionFlags |= BatteryStatsHistory.EXTENSION_PROCESS_STATE_CHANGE_FLAG;
        }
        if (extensionFlags != 0) {
            cur.states2 |= HistoryItem.STATE2_EXTENSIONS_FLAG;
        } else {
            cur.states2 &= ~HistoryItem.STATE2_EXTENSIONS_FLAG;
        }
        final boolean state2IntChanged = cur.states2 != last.states2 || extensionFlags != 0;
        if (state2IntChanged) {
            firstToken |= BatteryStatsHistory.DELTA_STATE2_FLAG;
        }
        if (cur.wakelockTag != null || cur.wakeReasonTag != null) {
            firstToken |= BatteryStatsHistory.DELTA_WAKELOCK_FLAG;
        }
        if (cur.eventCode != HistoryItem.EVENT_NONE) {
            firstToken |= BatteryStatsHistory.DELTA_EVENT_FLAG;
        }

        final boolean batteryChargeChanged = cur.batteryChargeUah != last.batteryChargeUah;
        if (batteryChargeChanged) {
            firstToken |= BatteryStatsHistory.DELTA_BATTERY_CHARGE_FLAG;
        }
        dest.writeInt(firstToken);
        if (DEBUG) {
            Slog.i(TAG, "WRITE DELTA: firstToken=0x" + Integer.toHexString(firstToken)
                    + " deltaTime=" + deltaTime);
        }

        if (deltaTimeToken >= BatteryStatsHistory.DELTA_TIME_INT) {
            if (deltaTimeToken == BatteryStatsHistory.DELTA_TIME_INT) {
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: int deltaTime=" + (int) deltaTime);
                dest.writeInt((int) deltaTime);
            } else {
                if (DEBUG) Slog.i(TAG, "WRITE DELTA: long deltaTime=" + deltaTime);
                dest.writeLong(deltaTime);
            }
        }
        if (batteryLevelIntChanged) {
            dest.writeInt(batteryLevelInt);
            if (DEBUG) {
                Slog.i(TAG, "WRITE DELTA: batteryToken=0x"
                        + Integer.toHexString(batteryLevelInt)
                        + " batteryLevel=" + cur.batteryLevel
                        + " batteryTemp=" + cur.batteryTemperature
                        + " batteryVolt=" + (int) cur.batteryVoltage);
            }
        }
        if (stateIntChanged) {
            dest.writeInt(stateInt);
            if (DEBUG) {
                Slog.i(TAG, "WRITE DELTA: stateToken=0x"
                        + Integer.toHexString(stateInt)
                        + " batteryStatus=" + cur.batteryStatus
                        + " batteryHealth=" + cur.batteryHealth
                        + " batteryPlugType=" + cur.batteryPlugType
                        + " states=0x" + Integer.toHexString(cur.states));
            }
        }
        if (state2IntChanged) {
            dest.writeInt(cur.states2);
            if (DEBUG) {
                Slog.i(TAG, "WRITE DELTA: states2=0x"
                        + Integer.toHexString(cur.states2));
            }
        }
        if (cur.wakelockTag != null || cur.wakeReasonTag != null) {
            int wakeLockIndex;
            int wakeReasonIndex;
            if (cur.wakelockTag != null) {
                wakeLockIndex = writeHistoryTag(cur.wakelockTag);
                if (DEBUG) {
                    Slog.i(TAG, "WRITE DELTA: wakelockTag=#" + cur.wakelockTag.poolIdx
                            + " " + cur.wakelockTag.uid + ":" + cur.wakelockTag.string);
                }
            } else {
                wakeLockIndex = 0xffff;
            }
            if (cur.wakeReasonTag != null) {
                wakeReasonIndex = writeHistoryTag(cur.wakeReasonTag);
                if (DEBUG) {
                    Slog.i(TAG, "WRITE DELTA: wakeReasonTag=#" + cur.wakeReasonTag.poolIdx
                            + " " + cur.wakeReasonTag.uid + ":" + cur.wakeReasonTag.string);
                }
            } else {
                wakeReasonIndex = 0xffff;
            }
            dest.writeInt((wakeReasonIndex << 16) | wakeLockIndex);
            if (cur.wakelockTag != null
                    && (wakeLockIndex & BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG) != 0) {
                cur.wakelockTag.writeToParcel(dest, 0);
                cur.tagsFirstOccurrence = true;
            }
            if (cur.wakeReasonTag != null
                    && (wakeReasonIndex & BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG) != 0) {
                cur.wakeReasonTag.writeToParcel(dest, 0);
                cur.tagsFirstOccurrence = true;
            }
        }
        if (cur.eventCode != HistoryItem.EVENT_NONE) {
            final int index = writeHistoryTag(cur.eventTag);
            final int codeAndIndex = setBitField(cur.eventCode & 0xffff, index, 16, 0xFFFF0000);
            dest.writeInt(codeAndIndex);
            if ((index & BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG) != 0) {
                cur.eventTag.writeToParcel(dest, 0);
                cur.tagsFirstOccurrence = true;
            }
            if (DEBUG) {
                Slog.i(TAG, "WRITE DELTA: event=" + cur.eventCode + " tag=#"
                        + cur.eventTag.poolIdx + " " + cur.eventTag.uid + ":"
                        + cur.eventTag.string);
            }
        }

        if (cur.stepDetails != null) {
            cur.stepDetails.writeToParcel(dest);
        }

        if (batteryChargeChanged) {
            if (DEBUG) Slog.i(TAG, "WRITE DELTA: batteryChargeUah=" + cur.batteryChargeUah);
            dest.writeInt(cur.batteryChargeUah);
        }
        dest.writeDouble(cur.modemRailChargeMah);
        dest.writeDouble(cur.wifiRailChargeMah);
        if (extensionFlags != 0) {
            dest.writeInt(extensionFlags);
            if (cur.powerStats != null) {
                if ((extensionFlags & BatteryStatsHistory.EXTENSION_POWER_STATS_DESCRIPTOR_FLAG)
                        != 0) {
                    cur.powerStats.descriptor.writeSummaryToParcel(dest);
                    mWrittenPowerStatsDescriptors.add(cur.powerStats.descriptor);
                }
                cur.powerStats.writeToParcel(dest);
            }
            if (cur.processStateChange != null) {
                cur.processStateChange.writeToParcel(dest);
            }
        }
    }

    private int buildBatteryLevelInt(HistoryItem h) {
        int bits = 0;
        bits = setBitField(bits, h.batteryLevel, 25, 0xfe000000 /* 7F << 25 */);
        bits = setBitField(bits, h.batteryTemperature, 15, 0x01ff8000 /* 3FF << 15 */);
        short voltage = (short) h.batteryVoltage;
        if (voltage == -1) {
            voltage = 0x3FFF;
        }
        bits = setBitField(bits, voltage, 1, 0x00007ffe /* 3FFF << 1 */);
        return bits;
    }

    private int buildStateInt(HistoryItem h) {
        int plugType = 0;
        if ((h.batteryPlugType & BatteryManager.BATTERY_PLUGGED_AC) != 0) {
            plugType = 1;
        } else if ((h.batteryPlugType & BatteryManager.BATTERY_PLUGGED_USB) != 0) {
            plugType = 2;
        } else if ((h.batteryPlugType & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) {
            plugType = 3;
        }
        return ((h.batteryStatus & BatteryStatsHistory.STATE_BATTERY_STATUS_MASK)
                << BatteryStatsHistory.STATE_BATTERY_STATUS_SHIFT)
                | ((h.batteryHealth & BatteryStatsHistory.STATE_BATTERY_HEALTH_MASK)
                << BatteryStatsHistory.STATE_BATTERY_HEALTH_SHIFT)
                | ((plugType & BatteryStatsHistory.STATE_BATTERY_PLUG_MASK)
                << BatteryStatsHistory.STATE_BATTERY_PLUG_SHIFT)
                | (h.states & (~BatteryStatsHistory.STATE_BATTERY_MASK));
    }

    /**
     * Returns the index for the specified tag. If this is the first time the tag is encountered
     * while writing the current history buffer, the method returns
     * <code>(index | TAG_FIRST_OCCURRENCE_FLAG)</code>
     */
    @GuardedBy("this")
    private int writeHistoryTag(HistoryTag tag) {
        if (tag.string == null) {
            Slog.wtfStack(TAG, "writeHistoryTag called with null name");
        }

        final int stringLength = tag.string.length();
        if (stringLength > MAX_HISTORY_TAG_STRING_LENGTH) {
            Slog.e(TAG, "Long battery history tag: " + tag.string);
            tag.string = tag.string.substring(0, MAX_HISTORY_TAG_STRING_LENGTH);
        }

        Integer idxObj = mHistoryTagPool.get(tag);
        int idx;
        if (idxObj != null) {
            idx = idxObj;
            if ((idx & BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG) != 0) {
                mHistoryTagPool.put(tag, idx & ~BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG);
            }
            return idx;
        } else if (mNextHistoryTagIdx < HISTORY_TAG_INDEX_LIMIT) {
            idx = mNextHistoryTagIdx;
            HistoryTag key = new HistoryTag();
            key.setTo(tag);
            tag.poolIdx = idx;
            mHistoryTagPool.put(key, idx);
            mNextHistoryTagIdx++;

            mNumHistoryTagChars += stringLength + 1;
            if (mHistoryTags != null) {
                mHistoryTags.put(idx, key);
            }
            return idx | BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG;
        } else {
            tag.poolIdx = HistoryTag.HISTORY_TAG_POOL_OVERFLOW;
            // Tag pool overflow: include the tag itself in the parcel
            return HISTORY_TAG_INDEX_LIMIT | BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG;
        }
    }

    /**
     * Don't allow any more batching in to the current history event.
     */
    public void commitCurrentHistoryBatchLocked() {
        synchronized (this) {
            mHistoryLastWritten.cmd = HistoryItem.CMD_NULL;
        }
    }

    /**
     * Saves the accumulated history buffer in the active file, see {@link #getActiveFile()} .
     */
    public void writeHistory() {
        synchronized (this) {
            if (isReadOnly()) {
                Slog.w(TAG, "writeHistory: this instance instance is read-only");
                return;
            }

            // Save the monotonic time first, so that even if the history write below fails,
            // we still wouldn't end up with overlapping history timelines.
            mMonotonicClock.write();

            Parcel p = Parcel.obtain();
            try {
                final long start = SystemClock.uptimeMillis();
                writeHistoryBuffer(p);
                if (DEBUG) {
                    Slog.d(TAG, "writeHistoryBuffer duration ms:"
                            + (SystemClock.uptimeMillis() - start) + " bytes:" + p.dataSize());
                }
                writeParcelToFileLocked(p, mActiveFile);
            } finally {
                p.recycle();
            }
        }
    }

    /**
     * Reads history buffer from a persisted Parcel.
     */
    public void readHistoryBuffer(Parcel in) throws ParcelFormatException {
        synchronized (this) {
            final int version = in.readInt();
            if (version != BatteryStatsHistory.VERSION) {
                Slog.w("BatteryStats", "readHistoryBuffer: version got " + version
                        + ", expected " + BatteryStatsHistory.VERSION + "; erasing old stats");
                return;
            }

            mHistoryBufferStartTime = in.readLong();
            mHistoryBuffer.setDataSize(0);
            mHistoryBuffer.setDataPosition(0);

            int bufSize = in.readInt();
            int curPos = in.dataPosition();
            if (bufSize >= (mMaxHistoryBufferSize * 100)) {
                throw new ParcelFormatException(
                        "File corrupt: history data buffer too large " + bufSize);
            } else if ((bufSize & ~3) != bufSize) {
                throw new ParcelFormatException(
                        "File corrupt: history data buffer not aligned " + bufSize);
            } else {
                if (DEBUG) {
                    Slog.i(TAG, "***************** READING NEW HISTORY: " + bufSize
                            + " bytes at " + curPos);
                }
                mHistoryBuffer.appendFrom(in, curPos, bufSize);
                in.setDataPosition(curPos + bufSize);
            }
        }
    }

    @GuardedBy("this")
    private void writeHistoryBuffer(Parcel out) {
        out.writeInt(BatteryStatsHistory.VERSION);
        out.writeLong(mHistoryBufferStartTime);
        out.writeInt(mHistoryBuffer.dataSize());
        if (DEBUG) {
            Slog.i(TAG, "***************** WRITING HISTORY: "
                    + mHistoryBuffer.dataSize() + " bytes at " + out.dataPosition());
        }
        out.appendFrom(mHistoryBuffer, 0, mHistoryBuffer.dataSize());
    }

    @GuardedBy("this")
    private void writeParcelToFileLocked(Parcel p, AtomicFile file) {
        FileOutputStream fos = null;
        mWriteLock.lock();
        try {
            final long startTimeMs = SystemClock.uptimeMillis();
            fos = file.startWrite();
            fos.write(p.marshall());
            fos.flush();
            file.finishWrite(fos);
            if (DEBUG) {
                Slog.d(TAG, "writeParcelToFileLocked file:" + file.getBaseFile().getPath()
                        + " duration ms:" + (SystemClock.uptimeMillis() - startTimeMs)
                        + " bytes:" + p.dataSize());
            }
            mEventLogger.writeCommitSysConfigFile(startTimeMs);
        } catch (IOException e) {
            Slog.w(TAG, "Error writing battery statistics", e);
            file.failWrite(fos);
        } finally {
            mWriteLock.unlock();
        }
    }


    /**
     * Returns the total number of history tags in the tag pool.
     */
    public int getHistoryStringPoolSize() {
        synchronized (this) {
            return mHistoryTagPool.size();
        }
    }

    /**
     * Returns the total number of bytes occupied by the history tag pool.
     */
    public int getHistoryStringPoolBytes() {
        synchronized (this) {
            return mNumHistoryTagChars;
        }
    }

    /**
     * Returns the string held by the requested history tag.
     */
    public String getHistoryTagPoolString(int index) {
        synchronized (this) {
            ensureHistoryTagArray();
            HistoryTag historyTag = mHistoryTags.get(index);
            return historyTag != null ? historyTag.string : null;
        }
    }

    /**
     * Returns the UID held by the requested history tag.
     */
    public int getHistoryTagPoolUid(int index) {
        synchronized (this) {
            ensureHistoryTagArray();
            HistoryTag historyTag = mHistoryTags.get(index);
            return historyTag != null ? historyTag.uid : Process.INVALID_UID;
        }
    }

    @GuardedBy("this")
    private void ensureHistoryTagArray() {
        if (mHistoryTags != null) {
            return;
        }

        mHistoryTags = new SparseArray<>(mHistoryTagPool.size());
        for (Map.Entry<HistoryTag, Integer> entry : mHistoryTagPool.entrySet()) {
            mHistoryTags.put(entry.getValue() & ~BatteryStatsHistory.TAG_FIRST_OCCURRENCE_FLAG,
                    entry.getKey());
        }
    }

    /**
     * Writes/reads an array of longs into Parcel using a compact format, where small integers use
     * fewer bytes.  It is a bit more expensive than just writing the long into the parcel,
     * but at scale saves a lot of storage and allows recording of longer battery history.
     */
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    public static final class VarintParceler {
        /**
         * Writes an array of longs into Parcel using the varint format, see
         * https://developers.google.com/protocol-buffers/docs/encoding#varints
         */
        public void writeLongArray(Parcel parcel, long[] values) {
            if (values.length == 0) {
                return;
            }
            int out = 0;
            int shift = 0;
            for (long value : values) {
                boolean done = false;
                while (!done) {
                    final byte b;
                    if ((value & ~0x7FL) == 0) {
                        b = (byte) value;
                        done = true;
                    } else {
                        b = (byte) (((int) value & 0x7F) | 0x80);
                        value >>>= 7;
                    }
                    if (shift == 32) {
                        parcel.writeInt(out);
                        shift = 0;
                        out = 0;
                    }
                    out |= (b & 0xFF) << shift;
                    shift += 8;
                }
            }
            if (shift != 0) {
                parcel.writeInt(out);
            }
        }

        /**
         * Reads a long written with {@link #writeLongArray}
         */
        public void readLongArray(Parcel parcel, long[] values) {
            if (values.length == 0) {
                return;
            }
            int in = parcel.readInt();
            int available = 4;
            for (int i = 0; i < values.length; i++) {
                long result = 0;
                int shift;
                for (shift = 0; shift < 64; shift += 7) {
                    if (available == 0) {
                        in = parcel.readInt();
                        available = 4;
                    }
                    final byte b = (byte) in;
                    in >>= 8;
                    available--;

                    result |= (long) (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        values[i] = result;
                        break;
                    }
                }
                if (shift >= 64) {
                    throw new ParcelFormatException("Invalid varint format");
                }
            }
        }
    }
}
