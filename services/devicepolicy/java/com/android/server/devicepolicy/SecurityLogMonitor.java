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

    @GuardedBy("mLock")
    private Thread mMonitorThread = null;
    @GuardedBy("mLock")
    private ArrayList<SecurityEvent> mPendingLogs = new ArrayList<SecurityEvent>();
    @GuardedBy("mLock")
    private boolean mAllowedToRetrieve = false;

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
                mPendingLogs = new ArrayList<SecurityEvent>();
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
                mPendingLogs = new ArrayList<SecurityEvent>();
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
        mPendingLogs = new ArrayList<SecurityEvent>();
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
                mPendingLogs = new ArrayList<SecurityEvent>();
                return result;
            } else {
                return null;
            }
        } finally {
            mLock.unlock();
        }
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        ArrayList<SecurityEvent> logs = new ArrayList<SecurityEvent>();
        // The timestamp of the latest log entry that has been read, in nanoseconds
        long lastLogTimestampNanos = -1;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(POLLING_INTERVAL_MILLISECONDS);

                if (lastLogTimestampNanos < 0) {
                    // Non-blocking read that returns all logs immediately.
                    if (DEBUG) Slog.d(TAG, "SecurityLog.readEvents");
                    SecurityLog.readEvents(logs);
                } else {
                    if (DEBUG) Slog.d(TAG,
                            "SecurityLog.readEventsSince: " + lastLogTimestampNanos);
                    // Non-blocking read that returns all logs >= the  timestamp immediately.
                    SecurityLog.readEventsSince(lastLogTimestampNanos + 1, logs);
                }
                if (!logs.isEmpty()) {
                    if (DEBUG) Slog.d(TAG, "processing new logs. Events: " + logs.size());
                    mLock.lockInterruptibly();
                    try {
                        mPendingLogs.addAll(logs);
                        if (mPendingLogs.size() > BUFFER_ENTRIES_MAXIMUM_LEVEL) {
                            // Truncate buffer down to half of BUFFER_ENTRIES_MAXIMUM_LEVEL
                            mPendingLogs = new ArrayList<SecurityEvent>(mPendingLogs.subList(
                                    mPendingLogs.size() - (BUFFER_ENTRIES_MAXIMUM_LEVEL / 2),
                                    mPendingLogs.size()));
                            Slog.i(TAG, "Pending logs buffer full. Discarding old logs.");
                        }
                    } finally {
                        mLock.unlock();
                    }
                    lastLogTimestampNanos = logs.get(logs.size() - 1).getTimeNanos();
                    logs.clear();
                }
                notifyDeviceOwnerIfNeeded();
            } catch (IOException e) {
                Log.e(TAG, "Failed to read security log", e);
            } catch (InterruptedException e) {
                Log.i(TAG, "Thread interrupted, exiting.", e);
                // We are asked to stop.
                break;
            }
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
