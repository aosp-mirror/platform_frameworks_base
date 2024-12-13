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

import static android.os.BatteryStats.HistoryItem.EVENT_FLAG_FINISH;
import static android.os.BatteryStats.HistoryItem.EVENT_FLAG_START;
import static android.os.BatteryStats.HistoryItem.EVENT_STATE_CHANGE;
import static android.os.Trace.TRACE_TAG_SYSTEM_SERVER;

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
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * BatteryStatsHistory encapsulates battery history files.
 * Battery history record is appended into buffer {@link #mHistoryBuffer} and backed up into
 * {@link #mActiveFragment}.
 * When {@link #mHistoryBuffer} size reaches {@link #mMaxHistoryBufferSize},
 * current mActiveFile is closed and a new mActiveFile is open.
 * History files are under directory /data/system/battery-history/.
 * History files have name &lt;num&gt;.bf. The file number &lt;num&gt; corresponds to the
 * monotonic time when the file was started.
 * The mActiveFile is always the highest numbered history file.
 * The lowest number file is always the oldest file.
 * The highest number file is always the newest file.
 * The file number grows monotonically and we never skip number.
 * When the total size of history files exceeds the maximum allowed value,
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
    private static final int VERSION = 213;

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

    // Pieces of data that are packed into the battery level int
    static final int BATTERY_LEVEL_LEVEL_MASK = 0xFF000000;
    static final int BATTERY_LEVEL_LEVEL_SHIFT = 24;
    static final int BATTERY_LEVEL_TEMP_MASK = 0x00FF8000;
    static final int BATTERY_LEVEL_TEMP_SHIFT = 15;
    static final int BATTERY_LEVEL_VOLT_MASK = 0x00007FFC;
    static final int BATTERY_LEVEL_VOLT_SHIFT = 2;
    // Flag indicating that the voltage and temperature deltas are too large to
    // store in the battery level int and full volt/temp values are instead
    // stored in a following int.
    static final int BATTERY_LEVEL_OVERFLOW_FLAG = 0x00000002;
    // We use the low bit of the battery state int to indicate that we have full details
    // from a battery level change.
    static final int BATTERY_LEVEL_DETAILS_FLAG = 0x00000001;

    // Pieces of data that are packed into the extended battery level int
    static final int BATTERY_LEVEL2_TEMP_MASK = 0xFFFF0000;
    static final int BATTERY_LEVEL2_TEMP_SHIFT = 16;
    static final int BATTERY_LEVEL2_VOLT_MASK = 0x0000FFFF;
    static final int BATTERY_LEVEL2_VOLT_SHIFT = 0;

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

    public abstract static class BatteryHistoryFragment
            implements Comparable<BatteryHistoryFragment> {
        public final long monotonicTimeMs;

        public BatteryHistoryFragment(long monotonicTimeMs) {
            this.monotonicTimeMs = monotonicTimeMs;
        }

        @Override
        public int compareTo(BatteryHistoryFragment o) {
            return Long.compare(monotonicTimeMs, o.monotonicTimeMs);
        }

        @Override
        public boolean equals(Object o) {
            return monotonicTimeMs == ((BatteryHistoryFragment) o).monotonicTimeMs;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(monotonicTimeMs);
        }
    }

    /**
     * Persistent storage for battery history fragments
     */
    public interface BatteryHistoryStore {
        /**
         * Returns the table of contents, in the chronological order.
         */
        List<BatteryHistoryFragment> getFragments();

        /**
         * Returns the earliest available fragment
         */
        @Nullable
        BatteryHistoryFragment getEarliestFragment();

        /**
         * Returns the latest available fragment
         */
        @Nullable
        BatteryHistoryFragment getLatestFragment();

        /**
         * Given a fragment, returns the earliest fragment that follows it whose monotonic
         * start time falls within the specified range. `startTimeMs` is inclusive, `endTimeMs`
         * is exclusive.
         */
        @Nullable
        BatteryHistoryFragment getNextFragment(BatteryHistoryFragment current, long startTimeMs,
                long endTimeMs);

        /**
         * Acquires a lock on the entire store.
         */
        void lock();

        /**
         * Acquires a lock unless the store is already locked by a different thread. Returns true
         * if the lock has been successfully acquired.
         */
        boolean tryLock();

        /**
         * Unlocks the store.
         */
        void unlock();

        /**
         * Returns true if the store is currently locked.
         */
        boolean isLocked();

        /**
         * Returns the total amount of storage occupied by history fragments, in bytes.
         */
        int getSize();

        /**
         * Returns true if the store contains any history fragments, excluding the currently
         * active partial fragment.
         */
        boolean hasCompletedFragments();

        /**
         * Creates a new empty history fragment starting at the specified time.
         */
        BatteryHistoryFragment createFragment(long monotonicStartTime);

        /**
         * Writes a fragment to disk as raw bytes.
         *
         * @param fragmentComplete indicates if this fragment is done or still partial.
         */
        void writeFragment(BatteryHistoryFragment fragment, @NonNull byte[] bytes,
                boolean fragmentComplete);

        /**
         * Reads a fragment as raw bytes.
         */
        @Nullable
        byte[] readFragment(BatteryHistoryFragment fragment);

        /**
         * Removes all persistent fragments
         */
        void reset();
    }

    private final Parcel mHistoryBuffer;
    private final HistoryStepDetailsCalculator mStepDetailsCalculator;
    private final Clock mClock;

    private int mMaxHistoryBufferSize;

    /**
     * The active history fragment that the history buffer is backed up into.
     */
    private BatteryHistoryFragment mActiveFragment;

    /**
     * Persistent storage of history files.
     */
    private final BatteryHistoryStore mStore;

    /**
     * A list of small history parcels, used when BatteryStatsImpl object is created from
     * deserialization of a parcel, such as Settings app or checkin file.
     */
    private List<Parcel> mHistoryParcels = null;

    /**
     * When iterating history files, the current file index.
     */
    private BatteryHistoryFragment mCurrentFragment;

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
    // Monotonic time when the last event was written to the history buffer
    private long mHistoryMonotonicEndTime;
    // Monotonically increasing size of written history
    private long mMonotonicHistorySize;
    private final ArraySet<PowerStats.Descriptor> mWrittenPowerStatsDescriptors = new ArraySet<>();
    private byte mLastHistoryStepLevel = 0;
    private boolean mMutable = true;
    private int mIteratorCookie;
    private final BatteryStatsHistory mWritableHistory;

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
     * @param maxHistoryBufferSize the most amount of RAM to used for buffering of history steps
     */
    public BatteryStatsHistory(Parcel historyBuffer, int maxHistoryBufferSize,
            @Nullable BatteryHistoryStore store, HistoryStepDetailsCalculator stepDetailsCalculator,
            Clock clock, MonotonicClock monotonicClock, TraceDelegate tracer,
            EventLogger eventLogger) {
        this(historyBuffer, maxHistoryBufferSize, store,
                stepDetailsCalculator,
                clock, monotonicClock, tracer, eventLogger, null);
    }

    private BatteryStatsHistory(@Nullable Parcel historyBuffer, int maxHistoryBufferSize,
            @Nullable BatteryHistoryStore store,
            @NonNull HistoryStepDetailsCalculator stepDetailsCalculator, @NonNull Clock clock,
            @NonNull MonotonicClock monotonicClock, @NonNull TraceDelegate tracer,
            @NonNull EventLogger eventLogger, @Nullable BatteryStatsHistory writableHistory) {
        mMaxHistoryBufferSize = maxHistoryBufferSize;
        mStepDetailsCalculator = stepDetailsCalculator;
        mTracer = tracer;
        mClock = clock;
        mMonotonicClock = monotonicClock;
        mEventLogger = eventLogger;
        mWritableHistory = writableHistory;
        if (mWritableHistory != null) {
            mMutable = false;
            mHistoryMonotonicEndTime = mWritableHistory.mHistoryMonotonicEndTime;
        }

        if (historyBuffer != null) {
            mHistoryBuffer = historyBuffer;
        } else {
            mHistoryBuffer = Parcel.obtain();
            initHistoryBuffer();
        }

        if (writableHistory != null) {
            mStore = writableHistory.mStore;
        } else {
            mStore = store;
            if (mStore != null) {
                BatteryHistoryFragment activeFile = mStore.getLatestFragment();
                if (activeFile == null) {
                    activeFile = mStore.createFragment(mMonotonicClock.monotonicTime());
                }
                setActiveFragment(activeFile);
            }
        }
    }

    /**
     * Used when BatteryStatsHistory object is created from deserialization of a BatteryUsageStats
     * parcel.
     */
    private BatteryStatsHistory(Parcel parcel) {
        mClock = Clock.SYSTEM_CLOCK;
        mTracer = null;
        mStore = null;
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
        Trace.traceBegin(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.copy");
        try {
            synchronized (this) {
                // Make a copy of battery history to avoid concurrent modification.
                Parcel historyBufferCopy = Parcel.obtain();
                historyBufferCopy.appendFrom(mHistoryBuffer, 0, mHistoryBuffer.dataSize());

                return new BatteryStatsHistory(historyBufferCopy, 0, mStore, null,
                        null, null, null, mEventLogger, this);
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_SYSTEM_SERVER);
        }
    }

    /**
     * Returns true if this instance only supports reading history.
     */
    public boolean isReadOnly() {
        return !mMutable || mActiveFragment == null || mStore == null;
    }

    /**
     * Set the active file that mHistoryBuffer is backed up into.
     */
    private void setActiveFragment(BatteryHistoryFragment file) {
        mActiveFragment = file;
        if (DEBUG) {
            Slog.d(TAG, "activeHistoryFile:" + mActiveFragment);
        }
    }

    /**
     * When {@link #mHistoryBuffer} reaches {@link #mMaxHistoryBufferSize},
     * create next history fragment.
     */
    public void startNextFragment(long elapsedRealtimeMs) {
        synchronized (this) {
            startNextFragmentLocked(elapsedRealtimeMs);
        }
    }

    @GuardedBy("this")
    private void startNextFragmentLocked(long elapsedRealtimeMs) {
        final long start = SystemClock.uptimeMillis();
        writeHistory(true /* fragmentComplete */);
        if (DEBUG) {
            Slog.d(TAG, "writeHistory took ms:" + (SystemClock.uptimeMillis() - start));
        }

        long monotonicStartTime = mMonotonicClock.monotonicTime(elapsedRealtimeMs);
        setActiveFragment(mStore.createFragment(monotonicStartTime));
        mHistoryBufferStartTime = monotonicStartTime;
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
    }

    /**
     * Returns true if it is safe to reset history. It will return false if the history is
     * currently being read.
     */
    public boolean isResetEnabled() {
        return mStore == null || !mStore.isLocked();
    }

    /**
     * Clear history buffer and delete all existing history files. Active history file start from
     * number 0 again.
     */
    public void reset() {
        synchronized (this) {
            initHistoryBuffer();
            if (mStore != null) {
                mStore.reset();
                setActiveFragment(mStore.createFragment(mHistoryBufferStartTime));
            }
        }
    }

    /**
     * Returns the monotonic clock time when the available battery history collection started.
     */
    public long getStartTime() {
        synchronized (this) {
            BatteryHistoryFragment firstFragment = mStore.getEarliestFragment();
            if (firstFragment != null) {
                return firstFragment.monotonicTimeMs;
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
        if (mMutable || mIteratorCookie != 0) {
            return copy().iterate(startTimeMs, endTimeMs);
        }

        if (mStore != null) {
            mStore.lock();
        }
        mCurrentFragment = null;
        mCurrentParcel = null;
        mCurrentParcelEnd = 0;
        mParcelIndex = 0;
        BatteryStatsHistoryIterator iterator = new BatteryStatsHistoryIterator(
                this, startTimeMs, endTimeMs);
        mIteratorCookie = System.identityHashCode(iterator);
        Trace.asyncTraceBegin(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.iterate",
                mIteratorCookie);
        return iterator;
    }

    /**
     * Finish iterating history files and history buffer.
     */
    void iteratorFinished() {
        mHistoryBuffer.setDataPosition(mHistoryBuffer.dataSize());
        if (mStore != null) {
            mStore.unlock();
        }
        Trace.asyncTraceEnd(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.iterate",
                mIteratorCookie);
        mIteratorCookie = 0;
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

        if (mStore != null) {
            BatteryHistoryFragment next = mStore.getNextFragment(mCurrentFragment, startTimeMs,
                    endTimeMs);
            while (next != null) {
                mCurrentParcel = null;
                mCurrentParcelEnd = 0;
                final Parcel p = Parcel.obtain();
                if (readFragmentToParcel(p, next)) {
                    int bufSize = p.readInt();
                    int curPos = p.dataPosition();
                    mCurrentParcelEnd = curPos + bufSize;
                    mCurrentParcel = p;
                    if (curPos < mCurrentParcelEnd) {
                        mCurrentFragment = next;
                        return mCurrentParcel;
                    }
                } else {
                    p.recycle();
                }
                next = mStore.getNextFragment(next, startTimeMs, endTimeMs);
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
                // skip monotonic end time field
                p.readLong();
                // skip monotonic size field
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
     * @param fragment the fragment to read from.
     * @return true if success, false otherwise.
     */
    public boolean readFragmentToParcel(Parcel out, BatteryHistoryFragment fragment) {
        byte[] data = mStore.readFragment(fragment);
        if (data == null) {
            return false;
        }
        out.unmarshall(data, 0, data.length);
        out.setDataPosition(0);
        if (!verifyVersion(out)) {
            return false;
        }
        // skip monotonic time field.
        out.readLong();
        // skip monotonic end time field
        out.readLong();
        // skip monotonic size field
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
        p.readLong();       // Skip monotonic end time field
        p.readLong();       // Skip monotonic size field
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
            if (mStore != null) {
                writeToParcel(out, false /* useBlobs */, 0);
            }
        }
    }

    /**
     * This is for Settings app, when Settings app receives big history parcel, it call
     * this method to parse it into list of parcels.
     *
     * @param out the output parcel
     */
    public void writeToBatteryUsageStatsParcel(Parcel out, long preferredHistoryDurationMs) {
        synchronized (this) {
            out.writeBlob(mHistoryBuffer.marshall());
            if (mStore != null) {
                writeToParcel(out, true /* useBlobs */,
                        mHistoryMonotonicEndTime - preferredHistoryDurationMs);
            }
        }
    }

    private void writeToParcel(Parcel out, boolean useBlobs,
            long preferredEarliestIncludedTimestampMs) {
        Trace.traceBegin(TRACE_TAG_SYSTEM_SERVER, "BatteryStatsHistory.writeToParcel");
        mStore.lock();
        try {
            final long start = SystemClock.uptimeMillis();
            List<BatteryHistoryFragment> fragments = mStore.getFragments();
            for (int i = 0; i < fragments.size() - 1; i++) {
                long monotonicEndTime = Long.MAX_VALUE;
                if (i < fragments.size() - 1) {
                    monotonicEndTime = fragments.get(i + 1).monotonicTimeMs;
                }

                if (monotonicEndTime < preferredEarliestIncludedTimestampMs) {
                    continue;
                }

                byte[] data = mStore.readFragment(fragments.get(i));
                if (data == null) {
                    Slog.e(TAG, "Error reading history fragment " + fragments.get(i));
                    continue;
                }

                out.writeBoolean(true);
                if (useBlobs) {
                    out.writeBlob(data, 0, data.length);
                } else {
                    // Avoiding blobs in the check-in file for compatibility
                    out.writeByteArray(data, 0, data.length);
                }
            }
            out.writeBoolean(false);
            if (DEBUG) {
                Slog.d(TAG, "writeToParcel duration ms:" + (SystemClock.uptimeMillis() - start));
            }
        } finally {
            mStore.unlock();
            Trace.traceEnd(TRACE_TAG_SYSTEM_SERVER);
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
        if (mActiveFragment == null) {
            Slog.w(TAG, "readSummary: no history file associated with this instance");
            return false;
        }

        Parcel parcel = Parcel.obtain();
        try {
            byte[] data = mStore.readFragment(mActiveFragment);
            if (data == null) {
                return false;
            }

            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            readHistoryBuffer(parcel);
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
        while (in.readBoolean()) {
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

    @VisibleForTesting
    public BatteryHistoryStore getBatteryHistoryStore() {
        return mStore;
    }

    @VisibleForTesting
    public BatteryHistoryFragment getActiveFragment() {
        return mActiveFragment;
    }

    /**
     * @return the total size of all history files and history buffer.
     */
    public int getHistoryUsedSize() {
        int ret = mStore.getSize();
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
            if (mHistoryBuffer.dataPosition() <= 0 && !mStore.hasCompletedFragments()) {
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
     * Records an event when some state flag changes to true.
     */
    public void recordStateStartEvent(long elapsedRealtimeMs, long uptimeMs, int stateFlags,
            int uid, String name) {
        synchronized (this) {
            mHistoryCur.states |= stateFlags;
            mHistoryCur.eventCode = EVENT_STATE_CHANGE | EVENT_FLAG_START;
            mHistoryCur.eventTag = mHistoryCur.localEventTag;
            mHistoryCur.eventTag.uid = uid;
            mHistoryCur.eventTag.string = name;
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
     * Records an event when some state flag changes to false.
     */
    public void recordStateStopEvent(long elapsedRealtimeMs, long uptimeMs, int stateFlags,
            int uid, String name) {
        synchronized (this) {
            mHistoryCur.states &= ~stateFlags;
            mHistoryCur.eventCode = EVENT_STATE_CHANGE | EVENT_FLAG_FINISH;
            mHistoryCur.eventTag = mHistoryCur.localEventTag;
            mHistoryCur.eventTag.uid = uid;
            mHistoryCur.eventTag.string = name;
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
     * Records an event when some state2 flag changes to true.
     */
    public void recordState2StartEvent(long elapsedRealtimeMs, long uptimeMs, int stateFlags,
            int uid, String name) {
        synchronized (this) {
            mHistoryCur.states2 |= stateFlags;
            mHistoryCur.eventCode = EVENT_STATE_CHANGE | EVENT_FLAG_START;
            mHistoryCur.eventTag = mHistoryCur.localEventTag;
            mHistoryCur.eventTag.uid = uid;
            mHistoryCur.eventTag.string = name;
            writeHistoryItem(elapsedRealtimeMs, uptimeMs);
        }
    }

    /**
     * Records an event when some state2 flag changes to false.
     */
    public void recordState2StopEvent(long elapsedRealtimeMs, long uptimeMs, int stateFlags,
            int uid, String name) {
        synchronized (this) {
            mHistoryCur.states2 &= ~stateFlags;
            mHistoryCur.eventCode = EVENT_STATE_CHANGE | EVENT_FLAG_FINISH;
            mHistoryCur.eventTag = mHistoryCur.localEventTag;
            mHistoryCur.eventTag.uid = uid;
            mHistoryCur.eventTag.string = name;
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
                    mHistoryAddTmp.powerStats = null;
                    mHistoryAddTmp.processStateChange = null;
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
        if (cur.eventCode != HistoryItem.EVENT_NONE && cur.eventTag.string == null) {
            Slog.wtfStack(TAG, "Event " + Integer.toHexString(cur.eventCode) + " without a name");
        }

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
            mMonotonicHistorySize -= (mHistoryBuffer.dataSize() - mHistoryBufferLastPos);
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

        boolean successfullyLocked = mStore.tryLock();
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
            startNextFragment(elapsedRealtimeMs);
        } finally {
            if (successfullyLocked) {
                mStore.unlock();
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
        mMonotonicHistorySize += (mHistoryBuffer.dataSize() - mHistoryBufferLastPos);
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
        █L|L|L|L|L|L|L|L█T|T|T|T|T|T|T|T█T|V|V|V|V|V|V|V█V|V|V|V|V|V|E|D█

        D: indicates that extra history details follow.
        E: indicates that the voltage delta or temperature delta is too large to fit in the
           respective V or T field of this int. If this flag is set, an extended battery level
           int containing the complete voltage and temperature values immediately follows.
        V: the signed battery voltage delta in millivolts.
        T: the signed battery temperature delta in tenths of a degree Celsius.
        L: the signed battery level delta (out of 100).

        Extended battery level int: if E in the battery level int is set,
        31              23              15               7             0
        █T|T|T|T|T|T|T|T█T|T|T|T|T|T|T|T█V|V|V|V|V|V|V|V█V|V|V|V|V|V|V|V█

        V: the current battery voltage (complete 16-bit value, not a delta).
        T: the current battery temperature (complete 16-bit value, not a delta).

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
        mHistoryMonotonicEndTime = cur.time;

        if (last == null || cur.cmd != HistoryItem.CMD_UPDATE) {
            dest.writeInt(BatteryStatsHistory.DELTA_TIME_ABS);
            cur.writeToParcel(dest, 0);
            return;
        }

        int extensionFlags = 0;
        final long deltaTime = cur.time - last.time;
        int batteryLevelInt = buildBatteryLevelInt(cur, last);
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

        final boolean batteryLevelIntChanged = batteryLevelInt != 0;
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
            boolean overflow = (batteryLevelInt & BATTERY_LEVEL_OVERFLOW_FLAG) != 0;
            int extendedBatteryLevelInt = 0;

            dest.writeInt(batteryLevelInt);
            if (overflow) {
                extendedBatteryLevelInt = buildExtendedBatteryLevelInt(cur);
                dest.writeInt(extendedBatteryLevelInt);
            }

            if (DEBUG) {
                Slog.i(TAG, "WRITE DELTA: batteryToken=0x"
                        + Integer.toHexString(batteryLevelInt)
                        + (overflow
                                ? " batteryToken2=0x" + Integer.toHexString(extendedBatteryLevelInt)
                                : "")
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

    private boolean signedValueFits(int value, int mask, int shift) {
        mask >>>= shift;
        // The original value can only be restored if all of the lost
        // high-order bits match the MSB of the packed value. Extract both the
        // MSB and the lost bits, and check if they match (i.e. they are all
        // zeros or all ones).
        int msbAndLostBitsMask = ~(mask >>> 1);
        int msbAndLostBits = value & msbAndLostBitsMask;

        return msbAndLostBits == 0 || msbAndLostBits == msbAndLostBitsMask;
    }

    private int buildBatteryLevelInt(HistoryItem cur, HistoryItem prev) {
        final int levelDelta = (int) cur.batteryLevel - (int) prev.batteryLevel;
        final int tempDelta = (int) cur.batteryTemperature - (int) prev.batteryTemperature;
        final int voltDelta = (int) cur.batteryVoltage - (int) prev.batteryVoltage;
        final boolean overflow =
                !signedValueFits(tempDelta, BATTERY_LEVEL_TEMP_MASK, BATTERY_LEVEL_VOLT_SHIFT)
                || !signedValueFits(voltDelta, BATTERY_LEVEL_VOLT_MASK, BATTERY_LEVEL_TEMP_SHIFT);

        int batt = 0;
        batt |= (levelDelta << BATTERY_LEVEL_LEVEL_SHIFT) & BATTERY_LEVEL_LEVEL_MASK;
        if (overflow) {
            batt |= BATTERY_LEVEL_OVERFLOW_FLAG;
        } else {
            batt |= (tempDelta << BATTERY_LEVEL_TEMP_SHIFT) & BATTERY_LEVEL_TEMP_MASK;
            batt |= (voltDelta << BATTERY_LEVEL_VOLT_SHIFT) & BATTERY_LEVEL_VOLT_MASK;
        }

        return batt;
    }

    private int buildExtendedBatteryLevelInt(HistoryItem cur) {
        int battExt = 0;
        battExt |= (cur.batteryTemperature << BATTERY_LEVEL2_TEMP_SHIFT) & BATTERY_LEVEL2_TEMP_MASK;
        battExt |= (cur.batteryVoltage << BATTERY_LEVEL2_VOLT_SHIFT) & BATTERY_LEVEL2_VOLT_MASK;
        return battExt;
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
            tag.string = "";
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
     * Saves the accumulated history buffer in the active file, see {@link #getActiveFragment()} .
     */
    public void writeHistory() {
        writeHistory(false /* fragmentComplete */);
    }

    private void writeHistory(boolean fragmentComplete) {
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
                writeParcelLocked(p, mActiveFragment, fragmentComplete);
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
            mHistoryMonotonicEndTime = in.readLong();
            mMonotonicHistorySize = in.readLong();

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
        out.writeLong(mHistoryMonotonicEndTime);
        out.writeLong(mMonotonicHistorySize);
        out.writeInt(mHistoryBuffer.dataSize());
        if (DEBUG) {
            Slog.i(TAG, "***************** WRITING HISTORY: "
                    + mHistoryBuffer.dataSize() + " bytes at " + out.dataPosition());
        }
        out.appendFrom(mHistoryBuffer, 0, mHistoryBuffer.dataSize());
    }

    @GuardedBy("this")
    private void writeParcelLocked(Parcel p, BatteryHistoryFragment fragment,
            boolean fragmentComplete) {
        mWriteLock.lock();
        try {
            final long startTimeMs = SystemClock.uptimeMillis();
            mStore.writeFragment(fragment, p.marshall(), fragmentComplete);
            mEventLogger.writeCommitSysConfigFile(startTimeMs);
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
     * Returns the monotonically increasing size of written history, including the buffers
     * that have already been discarded.
     */
    public long getMonotonicHistorySize() {
        return mMonotonicHistorySize;
    }

    /**
     * Prints battery stats history for debugging.
     */
    public void dump(PrintWriter pw, long startTimeMs, long endTimeMs) {
        BatteryStats.HistoryPrinter printer = new BatteryStats.HistoryPrinter();
        try (BatteryStatsHistoryIterator iterate = iterate(startTimeMs, endTimeMs)) {
            while (iterate.hasNext()) {
                HistoryItem next = iterate.next();
                printer.printNextItem(pw, next, 0, false, true);
            }
        }
        pw.flush();
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
