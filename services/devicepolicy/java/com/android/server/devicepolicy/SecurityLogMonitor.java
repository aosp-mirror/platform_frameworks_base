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

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.SecurityLog;
import android.app.admin.SecurityLog.SecurityEvent;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.os.Process;

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

    SecurityLogMonitor(DevicePolicyManagerService service) {
        mService = service;
    }

    private static final boolean DEBUG = false;  // STOPSHIP if true.
    private static final String TAG = "SecurityLogMonitor";
    /**
     * Each log entry can hold up to 4K bytes (but as of {@link android.os.Build.VERSION_CODES#N}
     * it should be less than 100 bytes), setting 1024 entries as the threshold to notify Device
     * Owner.
     */
    private static final int BUFFER_ENTRIES_NOTIFICATION_LEVEL = 1024;
    /**
     * The maximum number of entries we should store before dropping earlier logs, to limit the
     * memory usage.
     */
    private static final int BUFFER_ENTRIES_MAXIMUM_LEVEL = BUFFER_ENTRIES_NOTIFICATION_LEVEL * 10;
    /**
     * How often should Device Owner be notified under normal circumstances.
     */
    private static final long RATE_LIMIT_INTERVAL_MILLISECONDS = TimeUnit.HOURS.toMillis(2);
    /**
     * How often to retry the notification about available logs if it is ignored or missed by DO.
     */
    private static final long BROADCAST_RETRY_INTERVAL_MILLISECONDS = TimeUnit.MINUTES.toMillis(30);
    /**
     * Internally how often should the monitor poll the security logs from logd.
     */
    private static final long POLLING_INTERVAL_MILLISECONDS = TimeUnit.MINUTES.toMillis(1);
    /**
     * Overlap between two subsequent log requests, required to avoid losing out of order events.
     */
    private static final long OVERLAP_NANOS = TimeUnit.SECONDS.toNanos(3);


    @GuardedBy("mLock")
    private Thread mMonitorThread = null;
    @GuardedBy("mLock")
    private ArrayList<SecurityEvent> mPendingLogs = new ArrayList<>();
    @GuardedBy("mLock")
    private boolean mAllowedToRetrieve = false;

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

    void start() {
        Slog.i(TAG, "Starting security logging.");
        mLock.lock();
        try {
            if (mMonitorThread == null) {
                mPendingLogs = new ArrayList<>();
                mAllowedToRetrieve = false;
                mNextAllowedRetrievalTimeMillis = -1;
                mPaused = false;

                mMonitorThread = new Thread(this);
                mMonitorThread.start();
            }
        } finally {
            mLock.unlock();
        }
    }

    void stop() {
        Slog.i(TAG, "Stopping security logging.");
        mLock.lock();
        try {
            if (mMonitorThread != null) {
                mMonitorThread.interrupt();
                try {
                    mMonitorThread.join(TimeUnit.SECONDS.toMillis(5));
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting for thread to stop", e);
                }
                // Reset state and clear buffer
                mPendingLogs = new ArrayList<>();
                mAllowedToRetrieve = false;
                mNextAllowedRetrievalTimeMillis = -1;
                mPaused = false;
                mMonitorThread = null;
            }
        } finally {
            mLock.unlock();
        }
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
            notifyDeviceOwnerIfNeeded();
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
                        + RATE_LIMIT_INTERVAL_MILLISECONDS;
                List<SecurityEvent> result = mPendingLogs;
                mPendingLogs = new ArrayList<>();
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
    private void getNextBatch(ArrayList<SecurityEvent> newLogs)
            throws IOException, InterruptedException {
        if (mLastEventNanos < 0) {
            // Non-blocking read that returns all logs immediately.
            if (DEBUG) Slog.d(TAG, "SecurityLog.readEvents");
            SecurityLog.readEvents(newLogs);
        } else {
            // If we have last events from the previous batch, request log events with time overlap
            // with previously retrieved messages to avoid losing events due to reordering in logd.
            final long startNanos = mLastEvents.isEmpty()
                    ? mLastEventNanos : Math.max(0, mLastEventNanos - OVERLAP_NANOS);
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
        while (pos >= 0 && mLastEventNanos - newLogs.get(pos).getTimeNanos() < OVERLAP_NANOS) {
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
        // Reserve capacity so that copying doesn't occur.
        mPendingLogs.ensureCapacity(mPendingLogs.size() + newLogs.size());
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
                mPendingLogs.add(curEvent);
                curPos++;
            } else if (lastNanos < currentNanos) {
                if (DEBUG) Slog.d(TAG, "Event disappeared from the overlap: " + lastNanos);
                lastPos++;
            } else {
                // Two events have the same timestamp, check if they are the same.
                if (lastEvent.equals(curEvent)) {
                    // Actual overlap, just skip the event.
                    if (DEBUG) Slog.d(TAG, "Skipped dup event with timestamp: " + lastNanos);
                } else {
                    // Wow, what a coincidence, or probably the clock is too coarse.
                    mPendingLogs.add(curEvent);
                    if (DEBUG) Slog.d(TAG, "Event timestamp collision: " + lastNanos);
                }
                lastPos++;
                curPos++;
            }
        }
        // Save the rest of the new batch.
        mPendingLogs.addAll(newLogs.subList(curPos, newLogs.size()));

        if (mPendingLogs.size() > BUFFER_ENTRIES_MAXIMUM_LEVEL) {
            // Truncate buffer down to half of BUFFER_ENTRIES_MAXIMUM_LEVEL.
            mPendingLogs = new ArrayList<>(mPendingLogs.subList(
                    mPendingLogs.size() - (BUFFER_ENTRIES_MAXIMUM_LEVEL / 2),
                    mPendingLogs.size()));
            Slog.i(TAG, "Pending logs buffer full. Discarding old logs.");
        }
        if (DEBUG) Slog.d(TAG, mPendingLogs.size() + " pending events in the buffer after merging");
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        ArrayList<SecurityEvent> newLogs = new ArrayList<>();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(POLLING_INTERVAL_MILLISECONDS);
                getNextBatch(newLogs);

                mLock.lockInterruptibly();
                try {
                    mergeBatchLocked(newLogs);
                } finally {
                    mLock.unlock();
                }

                saveLastEvents(newLogs);
                newLogs.clear();
                notifyDeviceOwnerIfNeeded();
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

    private void notifyDeviceOwnerIfNeeded() throws InterruptedException {
        boolean allowRetrievalAndNotifyDO = false;
        mLock.lockInterruptibly();
        try {
            if (mPaused) {
                return;
            }
            final int logSize = mPendingLogs.size();
            if (logSize >= BUFFER_ENTRIES_NOTIFICATION_LEVEL) {
                // Allow DO to retrieve logs if too many pending logs
                if (!mAllowedToRetrieve) {
                    allowRetrievalAndNotifyDO = true;
                }
                if (DEBUG) Slog.d(TAG, "Number of log entries over threshold: " + logSize);
            }
            if (logSize > 0 && SystemClock.elapsedRealtime() >= mNextAllowedRetrievalTimeMillis) {
                // Rate limit reset
                allowRetrievalAndNotifyDO = true;
                if (DEBUG) Slog.d(TAG, "Timeout reached");
            }
            if (allowRetrievalAndNotifyDO) {
                mAllowedToRetrieve = true;
                // Set the timeout to retry the notification if the DO misses it.
                mNextAllowedRetrievalTimeMillis = SystemClock.elapsedRealtime()
                        + BROADCAST_RETRY_INTERVAL_MILLISECONDS;
            }
        } finally {
            mLock.unlock();
        }
        if (allowRetrievalAndNotifyDO) {
            Slog.i(TAG, "notify DO");
            mService.sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_SECURITY_LOGS_AVAILABLE,
                    null);
        }
    }
}
