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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Collects aggregated telemetry data about Looper message dispatching.
 *
 * @hide Only for use within the system server.
 */
public class LooperStats implements Looper.Observer {
    public static final String DEBUG_ENTRY_PREFIX = "__DEBUG_";
    private static final int SESSION_POOL_SIZE = 50;
    private static final boolean DISABLED_SCREEN_STATE_TRACKING_VALUE = false;

    @GuardedBy("mLock")
    private final SparseArray<Entry> mEntries = new SparseArray<>(512);
    private final Object mLock = new Object();
    private final Entry mOverflowEntry = new Entry("OVERFLOW");
    private final Entry mHashCollisionEntry = new Entry("HASH_COLLISION");
    private final ConcurrentLinkedQueue<DispatchSession> mSessionPool =
            new ConcurrentLinkedQueue<>();
    private final int mEntriesSizeCap;
    private int mSamplingInterval;
    private CachedDeviceState.Readonly mDeviceState;
    private CachedDeviceState.TimeInStateStopwatch mBatteryStopwatch;
    private long mStartCurrentTime = System.currentTimeMillis();
    private long mStartElapsedTime = SystemClock.elapsedRealtime();
    private boolean mAddDebugEntries = false;
    private boolean mTrackScreenInteractive = false;

    public LooperStats(int samplingInterval, int entriesSizeCap) {
        this.mSamplingInterval = samplingInterval;
        this.mEntriesSizeCap = entriesSizeCap;
    }

    public void setDeviceState(@NonNull CachedDeviceState.Readonly deviceState) {
        if (mBatteryStopwatch != null) {
            mBatteryStopwatch.close();
        }

        mDeviceState = deviceState;
        mBatteryStopwatch = deviceState.createTimeOnBatteryStopwatch();
    }

    public void setAddDebugEntries(boolean addDebugEntries) {
        mAddDebugEntries = addDebugEntries;
    }

    @Override
    public Object messageDispatchStarting() {
        if (deviceStateAllowsCollection() && shouldCollectDetailedData()) {
            DispatchSession session = mSessionPool.poll();
            session = session == null ? new DispatchSession() : session;
            session.startTimeMicro = getElapsedRealtimeMicro();
            session.cpuStartMicro = getThreadTimeMicro();
            session.systemUptimeMillis = getSystemUptimeMillis();
            return session;
        }

        return DispatchSession.NOT_SAMPLED;
    }

    @Override
    public void messageDispatched(Object token, Message msg) {
        if (!deviceStateAllowsCollection()) {
            return;
        }

        DispatchSession session = (DispatchSession) token;
        Entry entry = findEntry(msg, /* allowCreateNew= */session != DispatchSession.NOT_SAMPLED);
        if (entry != null) {
            synchronized (entry) {
                entry.messageCount++;
                if (session != DispatchSession.NOT_SAMPLED) {
                    entry.recordedMessageCount++;
                    final long latency = getElapsedRealtimeMicro() - session.startTimeMicro;
                    final long cpuUsage = getThreadTimeMicro() - session.cpuStartMicro;
                    entry.totalLatencyMicro += latency;
                    entry.maxLatencyMicro = Math.max(entry.maxLatencyMicro, latency);
                    entry.cpuUsageMicro += cpuUsage;
                    entry.maxCpuUsageMicro = Math.max(entry.maxCpuUsageMicro, cpuUsage);
                    if (msg.getWhen() > 0) {
                        final long delay = Math.max(0L, session.systemUptimeMillis - msg.getWhen());
                        entry.delayMillis += delay;
                        entry.maxDelayMillis = Math.max(entry.maxDelayMillis, delay);
                        entry.recordedDelayMessageCount++;
                    }
                }
            }
        }

        recycleSession(session);
    }

    @Override
    public void dispatchingThrewException(Object token, Message msg, Exception exception) {
        if (!deviceStateAllowsCollection()) {
            return;
        }

        DispatchSession session = (DispatchSession) token;
        Entry entry = findEntry(msg, /* allowCreateNew= */session != DispatchSession.NOT_SAMPLED);
        if (entry != null) {
            synchronized (entry) {
                entry.exceptionCount++;
            }
        }

        recycleSession(session);
    }

    private boolean deviceStateAllowsCollection() {
        // Do not collect data if on charger or the state is not set.
        return mDeviceState != null && !mDeviceState.isCharging();
    }

    /** Returns an array of {@link ExportedEntry entries} with the aggregated statistics. */
    public List<ExportedEntry> getEntries() {
        final ArrayList<ExportedEntry> exportedEntries;
        synchronized (mLock) {
            final int size = mEntries.size();
            exportedEntries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Entry entry = mEntries.valueAt(i);
                synchronized (entry) {
                    exportedEntries.add(new ExportedEntry(entry));
                }
            }
        }
        // Add the overflow and collision entries only if they have any data.
        maybeAddSpecialEntry(exportedEntries, mOverflowEntry);
        maybeAddSpecialEntry(exportedEntries, mHashCollisionEntry);
        // Debug entries added to help validate the data.
        if (mAddDebugEntries && mBatteryStopwatch != null) {
            exportedEntries.add(createDebugEntry("start_time_millis", mStartElapsedTime));
            exportedEntries.add(createDebugEntry("end_time_millis", SystemClock.elapsedRealtime()));
            exportedEntries.add(
                    createDebugEntry("battery_time_millis", mBatteryStopwatch.getMillis()));
        }
        return exportedEntries;
    }

    private ExportedEntry createDebugEntry(String variableName, long value) {
        final Entry entry = new Entry(DEBUG_ENTRY_PREFIX + variableName);
        entry.messageCount = 1;
        entry.recordedMessageCount = 1;
        entry.totalLatencyMicro = value;
        return new ExportedEntry(entry);
    }

    /** Returns a timestamp indicating when the statistics were last reset. */
    public long getStartTimeMillis() {
        return mStartCurrentTime;
    }

    public long getStartElapsedTimeMillis() {
        return mStartElapsedTime;
    }

    public long getBatteryTimeMillis() {
        return mBatteryStopwatch != null ? mBatteryStopwatch.getMillis() : 0;
    }

    private void maybeAddSpecialEntry(List<ExportedEntry> exportedEntries, Entry specialEntry) {
        synchronized (specialEntry) {
            if (specialEntry.messageCount > 0 || specialEntry.exceptionCount > 0) {
                exportedEntries.add(new ExportedEntry(specialEntry));
            }
        }
    }

    /** Removes all collected data. */
    public void reset() {
        synchronized (mLock) {
            mEntries.clear();
        }
        synchronized (mHashCollisionEntry) {
            mHashCollisionEntry.reset();
        }
        synchronized (mOverflowEntry) {
            mOverflowEntry.reset();
        }
        mStartCurrentTime = System.currentTimeMillis();
        mStartElapsedTime = SystemClock.elapsedRealtime();
        if (mBatteryStopwatch != null) {
            mBatteryStopwatch.reset();
        }
    }

    public void setSamplingInterval(int samplingInterval) {
        mSamplingInterval = samplingInterval;
    }

    public void setTrackScreenInteractive(boolean enabled) {
        mTrackScreenInteractive = enabled;
    }

    @Nullable
    private Entry findEntry(Message msg, boolean allowCreateNew) {
        final boolean isInteractive = mTrackScreenInteractive
                ? mDeviceState.isScreenInteractive()
                : DISABLED_SCREEN_STATE_TRACKING_VALUE;
        final int id = Entry.idFor(msg, isInteractive);
        Entry entry;
        synchronized (mLock) {
            entry = mEntries.get(id);
            if (entry == null) {
                if (!allowCreateNew) {
                    return null;
                } else if (mEntries.size() >= mEntriesSizeCap) {
                    // If over the size cap track totals under OVERFLOW entry.
                    return mOverflowEntry;
                } else {
                    entry = new Entry(msg, isInteractive);
                    mEntries.put(id, entry);
                }
            }
        }

        if (entry.workSourceUid != msg.workSourceUid
                || entry.handler.getClass() != msg.getTarget().getClass()
                || entry.handler.getLooper().getThread() != msg.getTarget().getLooper().getThread()
                || entry.isInteractive != isInteractive) {
            // If a hash collision happened, track totals under a single entry.
            return mHashCollisionEntry;
        }
        return entry;
    }

    private void recycleSession(DispatchSession session) {
        if (session != DispatchSession.NOT_SAMPLED && mSessionPool.size() < SESSION_POOL_SIZE) {
            mSessionPool.add(session);
        }
    }

    protected long getThreadTimeMicro() {
        return SystemClock.currentThreadTimeMicro();
    }

    protected long getElapsedRealtimeMicro() {
        return SystemClock.elapsedRealtimeNanos() / 1000;
    }

    protected long getSystemUptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    protected boolean shouldCollectDetailedData() {
        return ThreadLocalRandom.current().nextInt() % mSamplingInterval == 0;
    }

    private static class DispatchSession {
        static final DispatchSession NOT_SAMPLED = new DispatchSession();
        public long startTimeMicro;
        public long cpuStartMicro;
        public long systemUptimeMillis;
    }

    private static class Entry {
        public final int workSourceUid;
        public final Handler handler;
        public final String messageName;
        public final boolean isInteractive;
        public long messageCount;
        public long recordedMessageCount;
        public long exceptionCount;
        public long totalLatencyMicro;
        public long maxLatencyMicro;
        public long cpuUsageMicro;
        public long maxCpuUsageMicro;
        public long recordedDelayMessageCount;
        public long delayMillis;
        public long maxDelayMillis;

        Entry(Message msg, boolean isInteractive) {
            this.workSourceUid = msg.workSourceUid;
            this.handler = msg.getTarget();
            this.messageName = handler.getMessageName(msg);
            this.isInteractive = isInteractive;
        }

        Entry(String specialEntryName) {
            this.workSourceUid = Message.UID_NONE;
            this.messageName = specialEntryName;
            this.handler = null;
            this.isInteractive = false;
        }

        void reset() {
            messageCount = 0;
            recordedMessageCount = 0;
            exceptionCount = 0;
            totalLatencyMicro = 0;
            maxLatencyMicro = 0;
            cpuUsageMicro = 0;
            maxCpuUsageMicro = 0;
            delayMillis = 0;
            maxDelayMillis = 0;
            recordedDelayMessageCount = 0;
        }

        static int idFor(Message msg, boolean isInteractive) {
            int result = 7;
            result = 31 * result + msg.workSourceUid;
            result = 31 * result + msg.getTarget().getLooper().getThread().hashCode();
            result = 31 * result + msg.getTarget().getClass().hashCode();
            result = 31 * result + (isInteractive ? 1231 : 1237);
            if (msg.getCallback() != null) {
                return 31 * result + msg.getCallback().getClass().hashCode();
            } else {
                return 31 * result + msg.what;
            }
        }
    }

    /** Aggregated data of Looper message dispatching in the in the current process. */
    public static class ExportedEntry {
        public final int workSourceUid;
        public final String handlerClassName;
        public final String threadName;
        public final String messageName;
        public final boolean isInteractive;
        public final long messageCount;
        public final long recordedMessageCount;
        public final long exceptionCount;
        public final long totalLatencyMicros;
        public final long maxLatencyMicros;
        public final long cpuUsageMicros;
        public final long maxCpuUsageMicros;
        public final long maxDelayMillis;
        public final long delayMillis;
        public final long recordedDelayMessageCount;

        ExportedEntry(Entry entry) {
            this.workSourceUid = entry.workSourceUid;
            if (entry.handler != null) {
                this.handlerClassName = entry.handler.getClass().getName();
                this.threadName = entry.handler.getLooper().getThread().getName();
            } else {
                // Overflow/collision entries do not have a handler set.
                this.handlerClassName = "";
                this.threadName = "";
            }
            this.isInteractive = entry.isInteractive;
            this.messageName = entry.messageName;
            this.messageCount = entry.messageCount;
            this.recordedMessageCount = entry.recordedMessageCount;
            this.exceptionCount = entry.exceptionCount;
            this.totalLatencyMicros = entry.totalLatencyMicro;
            this.maxLatencyMicros = entry.maxLatencyMicro;
            this.cpuUsageMicros = entry.cpuUsageMicro;
            this.maxCpuUsageMicros = entry.maxCpuUsageMicro;
            this.delayMillis = entry.delayMillis;
            this.maxDelayMillis = entry.maxDelayMillis;
            this.recordedDelayMessageCount = entry.recordedDelayMessageCount;
        }
    }
}
