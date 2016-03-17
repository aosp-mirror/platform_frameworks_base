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

    private static final boolean DEBUG = false;
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
     * Internally how often should the monitor poll the security logs from logd.
     */
    private static final long POLLING_INTERVAL_MILLISECONDS = TimeUnit.MINUTES.toMillis(1);

    @GuardedBy("mLock")
    private Thread mMonitorThread = null;
    @GuardedBy("mLock")
    private ArrayList<SecurityEvent> mPendingLogs = new ArrayList<SecurityEvent>();
    @GuardedBy("mLock")
    private boolean mAllowedToRetrieve = false;
    // When DO will be allowed to retrieves the log, in milliseconds.
    @GuardedBy("mLock")
    private long mNextAllowedRetrivalTimeMillis = -1;

    void start() {
        mLock.lock();
        try {
            if (mMonitorThread == null) {
                mPendingLogs = new ArrayList<SecurityEvent>();
                mAllowedToRetrieve = false;
                mNextAllowedRetrivalTimeMillis = -1;

                mMonitorThread = new Thread(this);
                mMonitorThread.start();
            }
        } finally {
            mLock.unlock();
        }
    }

    void stop() {
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
                mNextAllowedRetrivalTimeMillis = -1;
                mMonitorThread = null;
            }
        } finally {
            mLock.unlock();
        }
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
                mNextAllowedRetrivalTimeMillis = System.currentTimeMillis()
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
                    if (DEBUG) Slog.d(TAG, "processing new logs");
                    mLock.lockInterruptibly();
                    try {
                        mPendingLogs.addAll(logs);
                        if (mPendingLogs.size() > BUFFER_ENTRIES_MAXIMUM_LEVEL) {
                            // Truncate buffer down to half of BUFFER_ENTRIES_MAXIMUM_LEVEL
                            mPendingLogs = new ArrayList<SecurityEvent>(mPendingLogs.subList(
                                    mPendingLogs.size() - (BUFFER_ENTRIES_MAXIMUM_LEVEL / 2),
                                    mPendingLogs.size()));
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
        if (DEBUG) Slog.d(TAG, "MonitorThread exit.");
    }

    private void notifyDeviceOwnerIfNeeded() throws InterruptedException {
        boolean shouldNotifyDO = false;
        boolean allowToRetrieveNow = false;
        mLock.lockInterruptibly();
        try {
            int logSize = mPendingLogs.size();
            if (logSize >= BUFFER_ENTRIES_NOTIFICATION_LEVEL) {
                // Allow DO to retrieve logs if too many pending logs
                allowToRetrieveNow = true;
            } else if (logSize > 0) {
                if (mNextAllowedRetrivalTimeMillis == -1 ||
                        System.currentTimeMillis() >= mNextAllowedRetrivalTimeMillis) {
                    // Rate limit reset
                    allowToRetrieveNow = true;
                }
            }
            shouldNotifyDO = (!mAllowedToRetrieve) && allowToRetrieveNow;
            mAllowedToRetrieve = allowToRetrieveNow;
        } finally {
            mLock.unlock();
        }
        if (shouldNotifyDO) {
            if (DEBUG) Slog.d(TAG, "notify DO");
            mService.sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_SECURITY_LOGS_AVAILABLE,
                    null);
        }
    }
}
