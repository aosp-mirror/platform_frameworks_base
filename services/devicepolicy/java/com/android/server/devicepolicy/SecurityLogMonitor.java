/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.IAuditLogEventsCallback;
import android.app.admin.SecurityLog;
import android.app.admin.SecurityLog.SecurityEvent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A class managing access to the security logs. It maintains an internal buffer of pending
 * logs to be retrieved by the device owner. The logs are retrieved from the logd daemon via
 * JNI binding, and kept until device owner has retrieved to prevent loss of logs. Access to
 * the logs from the device owner is rate-limited, and device owner is notified when the logs
 * are ready to be retrieved. This happens every two hours, or when our internal buffer is
 * larger than a certain threshold.
 */
class SecurityLogMonitor implements Runnable {
    private final DevicePolicyManagerService mService;

    private final Lock mLock = new ReentrantLock();

    private int mEnabledUser;

    SecurityLogMonitor(DevicePolicyManagerService service, Handler handler) {
        mService = service;
        mId = 0;
        mLastForceNanos = System.nanoTime();
        mHandler = handler;
    }

    private static final boolean DEBUG = false;  // STOPSHIP if true.
    private static final String TAG = "SecurityLogMonitor";
    /**
     * Each log entry can hold up to 4K bytes (but as of {@link android.os.Build.VERSION_CODES#N}
     * it should be less than 100 bytes), setting 1024 entries as the threshold to notify Device
     * Owner.
     */
    @VisibleForTesting static final int BUFFER_ENTRIES_NOTIFICATION_LEVEL = 1024;
    /**
     * The maximum number of entries we should store before dropping earlier logs, to limit the
     * memory usage.
     */
    private static final int BUFFER_ENTRIES_MAXIMUM_LEVEL = BUFFER_ENTRIES_NOTIFICATION_LEVEL * 10;
    /**
     * Critical log buffer level, 90% of capacity.
     */
    private static final int BUFFER_ENTRIES_CRITICAL_LEVEL = BUFFER_ENTRIES_MAXIMUM_LEVEL * 9 / 10;
    /**
     * How often should Device Owner be notified under normal circumstances.
     */
    private static final long RATE_LIMIT_INTERVAL_MS = TimeUnit.HOURS.toMillis(2);
    /**
     * How often to retry the notification about available logs if it is ignored or missed by DO.
     */
    private static final long BROADCAST_RETRY_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30);
    /**
     * Internally how often should the monitor poll the security logs from logd.
     */
    private static final long POLLING_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    /**
     * Overlap between two subsequent log requests, required to avoid losing out of order events.
     */
    private static final long OVERLAP_NS = TimeUnit.SECONDS.toNanos(3);

    /** Minimum time between forced fetch attempts. */
    private static final long FORCE_FETCH_THROTTLE_NS = TimeUnit.SECONDS.toNanos(10);

    /**
     * Monitor thread is not null iff SecurityLogMonitor is running, i.e. started and not stopped.
     * Pausing doesn't change it.
     */
    @GuardedBy("mLock")
    private Thread mMonitorThread = null;
    @GuardedBy("mLock")
    private ArrayList<SecurityEvent> mPendingLogs = new ArrayList<>();
    @GuardedBy("mLock")
    private long mId;
    @GuardedBy("mLock")
    private boolean mAllowedToRetrieve = false;

    // Whether we have already logged the fact that log buffer reached 90%, to avoid dupes.
    @GuardedBy("mLock")
    private boolean mCriticalLevelLogged = false;

    private boolean mLegacyLogEnabled;
    private boolean mAuditLogEnabled;

    /**
     * Last events fetched from log to check for overlap between batches. We can leave it empty if
     * we are sure there will be no overlap anymore, e.g. when we get empty batch.
     */
    private final ArrayList<SecurityEvent> mLastEvents = new ArrayList<>();
    /** Timestamp of the very last event, -1 means request from the beginning of time. */
    private long mLastEventNanos = -1;

    /**
     * When DO will be allowed to retrieve the log, in milliseconds since boot (as per
     * {@link SystemClock#elapsedRealtime()}). After that it will mark the time to retry broadcast.
     */
    @GuardedBy("mLock")
    private long mNextAllowedRetrievalTimeMillis = -1;
    @GuardedBy("mLock")
    private boolean mPaused = false;

    /** Semaphore used to force log fetching on request from adb. */
    private final Semaphore mForceSemaphore = new Semaphore(0 /* permits */);

    /** The last timestamp when force fetch was used, to prevent abuse. */
    @GuardedBy("mForceSemaphore")
    private long mLastForceNanos = 0;

    /**
     * Handler shared with DPMS.
     */
    private final Handler mHandler;

    /**
     * Oldest events get purged from audit log buffer if total number exceeds this value.
     */
    private static final int MAX_AUDIT_LOG_EVENTS = 10000;
    /**
     * Events older than this get purged from audit log buffer.
     */
    private static final long MAX_AUDIT_LOG_EVENT_AGE_NS = TimeUnit.HOURS.toNanos(8);

    /**
     * Audit log callbacks keyed by UID. The code should maintain the following invariant: all
     * callbacks in this map have received (or are scheduled to receive) all events in
     * mAuditLogEventsBuffer. To ensure this, before a callback is put into this map, it must be
     * scheduled to receive all the events in the buffer, and conversely, before a new chunk of
     * events is added to the buffer, it must be scheduled to be sent to all callbacks already in
     * this list. All scheduling should happen on mHandler, so that they aren't reordered, and
     * while holding the lock. This ensures that no callback misses an event or receives a duplicate
     * or out of order events.
     */
    @GuardedBy("mLock")
    private final SparseArray<IAuditLogEventsCallback> mAuditLogCallbacks = new SparseArray<>();

    /**
     * Audit log event buffer. It is shrunk automatically whenever either there are too many events
     * or the oldest one is too old.
     */
    @GuardedBy("mLock")
    private final ArrayDeque<SecurityEvent> mAuditLogEventBuffer = new ArrayDeque<>();

    void stop() {
        Slog.i(TAG, "Stopping security logging.");
        mLock.lock();
        try {
            if (mMonitorThread != null) {
                stopMonitorThreadLocked();
                resetLegacyBufferLocked();
            }
        } finally {
            mLock.unlock();
        }
    }

    void setLoggingParams(int enabledUser, boolean legacyLogEnabled, boolean auditLogEnabled) {
        Slogf.i(TAG, "Setting logging params, user = %d -> %d, legacy: %b -> %b, audit %b -> %b",
                mEnabledUser, enabledUser, mLegacyLogEnabled, legacyLogEnabled, mAuditLogEnabled,
                auditLogEnabled);
        mLock.lock();
        try {
            mEnabledUser = enabledUser;
            if (mMonitorThread == null && (legacyLogEnabled || auditLogEnabled)) {
                startMonitorThreadLocked();
            } else if (mMonitorThread != null && !legacyLogEnabled && !auditLogEnabled) {
                stopMonitorThreadLocked();
            }

            if (mLegacyLogEnabled != legacyLogEnabled) {
                resetLegacyBufferLocked();
                mLegacyLogEnabled = legacyLogEnabled;
            }

            if (mAuditLogEnabled != auditLogEnabled) {
                resetAuditBufferLocked();
                mAuditLogEnabled = auditLogEnabled;
            }
        } finally {
            mLock.unlock();
        }

    }

    @GuardedBy("mLock")
    private void startMonitorThreadLocked() {
        mId = 0;
        mPaused = false;
        mMonitorThread = new Thread(this);
        mMonitorThread.start();
        SecurityLog.writeEvent(SecurityLog.TAG_LOGGING_STARTED);
        Slog.i(TAG, "Security log monitor thread started");
    }

    @GuardedBy("mLock")
    private void stopMonitorThreadLocked() {
        mMonitorThread.interrupt();
        try {
            mMonitorThread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for thread to stop", e);
        }
        mMonitorThread = null;
        SecurityLog.writeEvent(SecurityLog.TAG_LOGGING_STOPPED);
    }

    @GuardedBy("mLock")
    private void resetLegacyBufferLocked() {
        mPendingLogs = new ArrayList<>();
        mCriticalLevelLogged = false;
        mAllowedToRetrieve = false;
        mNextAllowedRetrievalTimeMillis = -1;
        Slog.i(TAG, "Legacy buffer reset.");
    }

    @GuardedBy("mLock")
    private void resetAuditBufferLocked() {
        mAuditLogEventBuffer.clear();
        mAuditLogCallbacks.clear();
    }

    /**
     * If logs are being collected, keep collecting them but stop notifying the device owner that
     * new logs are available (since they cannot be retrieved).
     */
    void pause() {
        Slog.i(TAG, "Paused.");

        mLock.lock();
        mPaused = true;
        mAllowedToRetrieve = false;
        mLock.unlock();
    }

    /**
     * If logs are being collected, start notifying the device owner when logs are ready to be
     * retrieved again (if it was paused).
     * <p>If logging is enabled and there are logs ready to be retrieved, this method will attempt
     * to notify the device owner. Therefore calling identity should be cleared before calling it
     * (in case the method is called from a user other than the DO's user).
     */
    void resume() {
        mLock.lock();
        try {
            if (!mPaused) {
                Log.d(TAG, "Attempted to resume, but logging is not paused.");
                return;
            }
            mPaused = false;
            mAllowedToRetrieve = false;
        } finally {
            mLock.unlock();
        }

        Slog.i(TAG, "Resumed.");
        try {
            notifyDeviceOwnerOrProfileOwnerIfNeeded(false /* force */);
        } catch (InterruptedException e) {
            Log.w(TAG, "Thread interrupted.", e);
        }
    }

    /**
     * Discard all collected logs.
     */
    void discardLogs() {
        mLock.lock();
        mAllowedToRetrieve = false;
        mPendingLogs = new ArrayList<>();
        mCriticalLevelLogged = false;
        mLock.unlock();
        Slog.i(TAG, "Discarded all logs.");
    }

    /**
     * Returns the new batch of logs since the last call to this method. Returns null if
     * rate limit is exceeded.
     */
    List<SecurityEvent> retrieveLogs() {
        mLock.lock();
        try {
            if (mAllowedToRetrieve) {
                mAllowedToRetrieve = false;
                mNextAllowedRetrievalTimeMillis = SystemClock.elapsedRealtime()
                        + RATE_LIMIT_INTERVAL_MS;
                List<SecurityEvent> result = mPendingLogs;
                mPendingLogs = new ArrayList<>();
                mCriticalLevelLogged = false;
                return result;
            } else {
                return null;
            }
        } finally {
            mLock.unlock();
        }
    }

    /**
     * Requests the next (or the first) batch of events from the log with appropriate timestamp.
     */
    private void getNextBatch(ArrayList<SecurityEvent> newLogs) throws IOException {
        if (mLastEventNanos < 0) {
            // Non-blocking read that returns all logs immediately.
            if (DEBUG) Slog.d(TAG, "SecurityLog.readEvents");
            SecurityLog.readEvents(newLogs);
        } else {
            // If we have last events from the previous batch, request log events with time overlap
            // with previously retrieved messages to avoid losing events due to reordering in logd.
            final long startNanos = mLastEvents.isEmpty()
                    ? mLastEventNanos : Math.max(0, mLastEventNanos - OVERLAP_NS);
            if (DEBUG) Slog.d(TAG, "SecurityLog.readEventsSince: " + startNanos);
            // Non-blocking read that returns all logs with timestamps >= startNanos immediately.
            SecurityLog.readEventsSince(startNanos, newLogs);
        }

        // Sometimes events may be reordered in logd due to simultaneous readers and writers. In
        // this case, we have to sort it to make overlap checking work. This is very unlikely.
        for (int i = 0; i < newLogs.size() - 1; i++) {
            if (newLogs.get(i).getTimeNanos() > newLogs.get(i+1).getTimeNanos()) {
                if (DEBUG) Slog.d(TAG, "Got out of order events, sorting.");
                // Sort using comparator that compares timestamps.
                newLogs.sort((e1, e2) -> Long.signum(e1.getTimeNanos() - e2.getTimeNanos()));
                break;
            }
        }
        SecurityLog.redactEvents(newLogs, mEnabledUser);
        if (DEBUG) Slog.d(TAG, "Got " + newLogs.size() + " new events.");
    }

    /**
     * Save the last events for overlap checking with the next batch.
     */
    private void saveLastEvents(ArrayList<SecurityEvent> newLogs) {
        mLastEvents.clear();
        if (newLogs.isEmpty()) {
            // This can happen if no events were logged yet or the buffer got cleared. In this case
            // we aren't going to have any overlap next time, leave mLastEvents events empty.
            return;
        }

        // Save the last timestamp.
        mLastEventNanos = newLogs.get(newLogs.size() - 1).getTimeNanos();
        // Position of the earliest event that has to be saved. Start from the penultimate event,
        // going backward.
        int pos = newLogs.size() - 2;
        while (pos >= 0 && mLastEventNanos - newLogs.get(pos).getTimeNanos() < OVERLAP_NS) {
            pos--;
        }
        // We either run past the start of the list or encountered an event that is too old to keep.
        pos++;
        mLastEvents.addAll(newLogs.subList(pos, newLogs.size()));
        if (DEBUG) Slog.d(TAG, mLastEvents.size() + " events saved for overlap check");
    }

    /**
     * Merges a new batch into already fetched logs and deals with overlapping and out of order
     * events.
     */
    @GuardedBy("mLock")
    private void mergeBatchLocked(final ArrayList<SecurityEvent> newLogs) {
        List<SecurityEvent> dedupedLogs = new ArrayList<>();
        // Run through the first events of the batch to check if there is an overlap with previous
        // batch and if so, skip overlapping events. Events are sorted by timestamp, so we can
        // compare it in linear time by advancing two pointers, one for each batch.
        int curPos = 0;
        int lastPos = 0;
        // For the first batch mLastEvents will be empty, so no iterations will happen.
        while (lastPos < mLastEvents.size() && curPos < newLogs.size()) {
            final SecurityEvent curEvent = newLogs.get(curPos);
            final long currentNanos = curEvent.getTimeNanos();
            if (currentNanos > mLastEventNanos) {
                // We got past the last event of the last batch, no overlap possible anymore.
                break;
            }
            final SecurityEvent lastEvent = mLastEvents.get(lastPos);
            final long lastNanos = lastEvent.getTimeNanos();
            if (lastNanos > currentNanos) {
                // New event older than the last we've seen so far, must be due to reordering.
                if (DEBUG) Slog.d(TAG, "New event in the overlap: " + currentNanos);
                dedupedLogs.add(curEvent);
                curPos++;
            } else if (lastNanos < currentNanos) {
                if (DEBUG) Slog.d(TAG, "Event disappeared from the overlap: " + lastNanos);
                lastPos++;
            } else {
                // Two events have the same timestamp, check if they are the same.
                if (lastEvent.eventEquals(curEvent)) {
                    // Actual overlap, just skip the event.
                    if (DEBUG) Slog.d(TAG, "Skipped dup event with timestamp: " + lastNanos);
                } else {
                    // Wow, what a coincidence, or probably the clock is too coarse.
                    dedupedLogs.add(curEvent);
                    if (DEBUG) Slog.d(TAG, "Event timestamp collision: " + lastNanos);
                }
                lastPos++;
                curPos++;
            }
        }
        // Assign an id to the new logs, after the overlap with mLastEvents.
        dedupedLogs.addAll(newLogs.subList(curPos, newLogs.size()));
        for (SecurityEvent event : dedupedLogs) {
            assignLogId(event);
        }

        if (mLegacyLogEnabled) {
            addToLegacyBufferLocked(dedupedLogs);
        }

        if (mAuditLogEnabled) {
            addAuditLogEventsLocked(dedupedLogs);
        }
    }

    @GuardedBy("mLock")
    private void addToLegacyBufferLocked(List<SecurityEvent> dedupedLogs) {
        // Save the rest of the new batch.
        mPendingLogs.addAll(dedupedLogs);

        checkCriticalLevel();

        if (mPendingLogs.size() > BUFFER_ENTRIES_MAXIMUM_LEVEL) {
            // Truncate buffer down to half of BUFFER_ENTRIES_MAXIMUM_LEVEL.
            mPendingLogs = new ArrayList<>(mPendingLogs.subList(
                    mPendingLogs.size() - (BUFFER_ENTRIES_MAXIMUM_LEVEL / 2),
                    mPendingLogs.size()));
            mCriticalLevelLogged = false;
            Slog.i(TAG, "Pending logs buffer full. Discarding old logs.");
        }
        if (DEBUG) {
            if (mPendingLogs.size() > 0) {
                Slog.d(TAG, mPendingLogs.size() + " pending events in the buffer after merging,"
                        + " with ids " + mPendingLogs.get(0).getId()
                        + " to " + mPendingLogs.get(mPendingLogs.size() - 1).getId());
            } else {
                Slog.d(TAG, "0 pending events in the buffer after merging");
            }
        }
    }

    @GuardedBy("mLock")
    private void checkCriticalLevel() {
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }

        if (mPendingLogs.size() >= BUFFER_ENTRIES_CRITICAL_LEVEL) {
            if (!mCriticalLevelLogged) {
                mCriticalLevelLogged = true;
                SecurityLog.writeEvent(SecurityLog.TAG_LOG_BUFFER_SIZE_CRITICAL);
            }
        }
    }

    @GuardedBy("mLock")
    private void assignLogId(SecurityEvent event) {
        event.setId(mId);
        if (mId == Long.MAX_VALUE) {
            Slog.i(TAG, "Reached maximum id value; wrapping around.");
            mId = 0;
        } else {
            mId++;
        }
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        final ArrayList<SecurityEvent> newLogs = new ArrayList<>();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                final boolean force = mForceSemaphore.tryAcquire(POLLING_INTERVAL_MS, MILLISECONDS);

                getNextBatch(newLogs);

                mLock.lockInterruptibly();
                try {
                    mergeBatchLocked(newLogs);
                } finally {
                    mLock.unlock();
                }

                saveLastEvents(newLogs);
                newLogs.clear();

                if (mLegacyLogEnabled) {
                    notifyDeviceOwnerOrProfileOwnerIfNeeded(force);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to read security log", e);
            } catch (InterruptedException e) {
                Log.i(TAG, "Thread interrupted, exiting.", e);
                // We are asked to stop.
                break;
            }
        }

        // Discard previous batch info.
        mLastEvents.clear();
        if (mLastEventNanos != -1) {
            // Make sure we don't read old events if logging is re-enabled. Since mLastEvents is
            // empty, the next request will be done without overlap, so it is enough to add 1 ns.
            mLastEventNanos += 1;
        }

        Slog.i(TAG, "MonitorThread exit.");
    }

    private void notifyDeviceOwnerOrProfileOwnerIfNeeded(boolean force)
            throws InterruptedException {
        boolean allowRetrievalAndNotifyDOOrPO = false;
        mLock.lockInterruptibly();
        try {
            if (mPaused) {
                return;
            }
            final int logSize = mPendingLogs.size();
            if (logSize >= BUFFER_ENTRIES_NOTIFICATION_LEVEL || (force && logSize > 0)) {
                // Allow DO to retrieve logs if too many pending logs or if forced.
                if (!mAllowedToRetrieve) {
                    allowRetrievalAndNotifyDOOrPO = true;
                }
                if (DEBUG) Slog.d(TAG, "Number of log entries over threshold: " + logSize);
            }
            if (logSize > 0 && SystemClock.elapsedRealtime() >= mNextAllowedRetrievalTimeMillis) {
                // Rate limit reset
                allowRetrievalAndNotifyDOOrPO = true;
                if (DEBUG) Slog.d(TAG, "Timeout reached");
            }
            if (allowRetrievalAndNotifyDOOrPO) {
                mAllowedToRetrieve = true;
                // Set the timeout to retry the notification if the DO misses it.
                mNextAllowedRetrievalTimeMillis = SystemClock.elapsedRealtime()
                        + BROADCAST_RETRY_INTERVAL_MS;
            }
        } finally {
            mLock.unlock();
        }
        if (allowRetrievalAndNotifyDOOrPO) {
            Slog.i(TAG, "notify DO or PO");
            mService.sendDeviceOwnerOrProfileOwnerCommand(
                    DeviceAdminReceiver.ACTION_SECURITY_LOGS_AVAILABLE, null, mEnabledUser);
        }
    }

    /**
     * Forces the logs to be fetched and made available. Returns 0 on success or timeout to wait
     * before attempting in milliseconds.
     */
    public long forceLogs() {
        final long nowNanos = System.nanoTime();
        // We only synchronize with another calls to this function, not with the fetching thread.
        synchronized (mForceSemaphore) {
            final long toWaitNanos = mLastForceNanos + FORCE_FETCH_THROTTLE_NS - nowNanos;
            if (toWaitNanos > 0) {
                return NANOSECONDS.toMillis(toWaitNanos) + 1; // Round up.
            }
            mLastForceNanos = nowNanos;
            // There is a race condition with the fetching thread below, but if the last permit is
            // acquired just after we do the check, logs are forced anyway and that's what we need.
            if (mForceSemaphore.availablePermits() == 0) {
                mForceSemaphore.release();
            }
            return 0;
        }
    }

    public void setAuditLogEventsCallback(int uid, IAuditLogEventsCallback callback) {
        mLock.lock();
        try {
            if (callback == null) {
                mAuditLogCallbacks.remove(uid);
                Slogf.i(TAG, "Cleared audit log callback for UID %d", uid);
                return;
            }
            // Create a copy while holding the lock, so that that new events are not added
            // resulting in duplicates.
            final List<SecurityEvent> events = new ArrayList<>(mAuditLogEventBuffer);
            scheduleSendAuditLogs(uid, callback, events);
            mAuditLogCallbacks.append(uid, callback);
        } finally {
            mLock.unlock();
        }
        Slogf.i(TAG, "Set audit log callback for UID %d", uid);
    }

    @GuardedBy("mLock")
    private void addAuditLogEventsLocked(List<SecurityEvent> events) {
        if (mPaused) {
            // TODO: maybe we need to stash the logs in some temp buffer wile paused so that
            // they can be accessed after affiliation is fixed.
            return;
        }
        if (!events.isEmpty()) {
            for (int i = 0; i < mAuditLogCallbacks.size(); i++) {
                final int uid = mAuditLogCallbacks.keyAt(i);
                scheduleSendAuditLogs(uid, mAuditLogCallbacks.valueAt(i), events);
            }
        }
        if (DEBUG) {
            Slogf.d(TAG, "Adding audit %d events to %d already present in the buffer",
                    events.size(), mAuditLogEventBuffer.size());
        }
        mAuditLogEventBuffer.addAll(events);
        trimAuditLogBufferLocked();
        if (DEBUG) {
            Slogf.d(TAG, "Audit event buffer size after trimming: %d",
                    mAuditLogEventBuffer.size());
        }
    }

    @GuardedBy("mLock")
    private void trimAuditLogBufferLocked() {
        long nowNanos = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());

        final Iterator<SecurityEvent> iterator = mAuditLogEventBuffer.iterator();
        while (iterator.hasNext()) {
            final SecurityEvent event = iterator.next();
            if (mAuditLogEventBuffer.size() <= MAX_AUDIT_LOG_EVENTS
                    && nowNanos - event.getTimeNanos() <= MAX_AUDIT_LOG_EVENT_AGE_NS) {
                break;
            }

            iterator.remove();
        }
    }

    private void scheduleSendAuditLogs(
            int uid, IAuditLogEventsCallback callback, List<SecurityEvent> events) {
        if (DEBUG) {
            Slogf.d(TAG, "Scheduling to send %d audit log events to UID %d", events.size(), uid);
        }
        mHandler.post(() -> sendAuditLogs(uid, callback, events));
    }

    private void sendAuditLogs(
            int uid, IAuditLogEventsCallback callback, List<SecurityEvent> events) {
        try {
            final int size = events.size();
            if (DEBUG) {
                Slogf.d(TAG, "Sending %d audit log events to UID %d", size, uid);
            }
            callback.onNewAuditLogEvents(events);
            if (DEBUG) {
                Slogf.d(TAG, "Sent %d audit log events to UID %d", size, uid);
            }
        } catch (RemoteException e) {
            Slogf.e(TAG, e, "Failed to invoke audit log callback for UID %d", uid);
            removeAuditLogEventsCallbackIfDead(uid, callback);
        }
    }

    private void removeAuditLogEventsCallbackIfDead(int uid, IAuditLogEventsCallback callback) {
        final IBinder binder = callback.asBinder();
        if (binder.isBinderAlive()) {
            Slog.i(TAG, "Callback binder is still alive, not removing.");
            return;
        }

        mLock.lock();
        try {
            int index = mAuditLogCallbacks.indexOfKey(uid);
            if (index < 0) {
                Slogf.i(TAG, "Callback not registered for UID %d, nothing to remove", uid);
                return;
            }

            final IBinder storedBinder = mAuditLogCallbacks.valueAt(index).asBinder();
            if (!storedBinder.equals(binder)) {
                Slogf.i(TAG, "Callback is already replaced for UID %d, not removing", uid);
                return;
            }

            Slogf.i(TAG, "Removing callback for UID %d", uid);
            mAuditLogCallbacks.removeAt(index);
        } finally {
            mLock.unlock();
        }
    }
}
