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

import android.app.AlarmManager;
import android.app.AlarmManager.OnAlarmListener;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.NetworkEvent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.LongSparseArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A Handler class for managing network logging on a background thread.
 */
final class NetworkLoggingHandler extends Handler {

    private static final String TAG = NetworkLoggingHandler.class.getSimpleName();

    static final String NETWORK_EVENT_KEY = "network_event";

    // If this value changes, update DevicePolicyManager#retrieveNetworkLogs() javadoc
    private static final int MAX_EVENTS_PER_BATCH = 1200;

    /**
     * Maximum number of batches to store in memory. If more batches are generated and the DO
     * doesn't fetch them, we will discard the oldest one.
     */
    private static final int MAX_BATCHES = 5;

    private static final long BATCH_FINALIZATION_TIMEOUT_MS = 90 * 60 * 1000; // 1.5h
    private static final long BATCH_FINALIZATION_TIMEOUT_ALARM_INTERVAL_MS = 30 * 60 * 1000; // 30m

    private static final String NETWORK_LOGGING_TIMEOUT_ALARM_TAG = "NetworkLogging.batchTimeout";

    /** Delay after which older batches get discarded after a retrieval. */
    private static final long RETRIEVED_BATCH_DISCARD_DELAY_MS = 5 * 60 * 1000; // 5m

    /** Do not call into mDpm with locks held */
    private final DevicePolicyManagerService mDpm;
    private final AlarmManager mAlarmManager;

    private final OnAlarmListener mBatchTimeoutAlarmListener = new OnAlarmListener() {
        @Override
        public void onAlarm() {
            Slog.d(TAG, "Received a batch finalization timeout alarm, finalizing "
                    + mNetworkEvents.size() + " pending events.");
            Bundle notificationExtras = null;
            synchronized (NetworkLoggingHandler.this) {
                notificationExtras = finalizeBatchAndBuildDeviceOwnerMessageLocked();
            }
            if (notificationExtras != null) {
                notifyDeviceOwner(notificationExtras);
            }
        }
    };

    static final int LOG_NETWORK_EVENT_MSG = 1;

    /** Network events accumulated so far to be finalized into a batch at some point. */
    @GuardedBy("this")
    private ArrayList<NetworkEvent> mNetworkEvents = new ArrayList<>();

    /**
     * Up to {@code MAX_BATCHES} finalized batches of logs ready to be retrieved by the DO. Already
     * retrieved batches are discarded after {@code RETRIEVED_BATCH_DISCARD_DELAY_MS}.
     */
    @GuardedBy("this")
    private final LongSparseArray<ArrayList<NetworkEvent>> mBatches =
            new LongSparseArray<>(MAX_BATCHES);

    @GuardedBy("this")
    private boolean mPaused = false;

    // each full batch is represented by its token, which the DPC has to provide back to retrieve it
    @GuardedBy("this")
    private long mCurrentBatchToken;

    @GuardedBy("this")
    private long mLastRetrievedBatchToken;

    NetworkLoggingHandler(Looper looper, DevicePolicyManagerService dpm) {
        super(looper);
        mDpm = dpm;
        mAlarmManager = mDpm.mInjector.getAlarmManager();
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case LOG_NETWORK_EVENT_MSG: {
                final NetworkEvent networkEvent = msg.getData().getParcelable(NETWORK_EVENT_KEY);
                if (networkEvent != null) {
                    Bundle notificationExtras = null;
                    synchronized (NetworkLoggingHandler.this) {
                        mNetworkEvents.add(networkEvent);
                        if (mNetworkEvents.size() >= MAX_EVENTS_PER_BATCH) {
                            notificationExtras = finalizeBatchAndBuildDeviceOwnerMessageLocked();
                        }
                    }
                    if (notificationExtras != null) {
                        notifyDeviceOwner(notificationExtras);
                    }
                }
                break;
            }
            default: {
                Slog.d(TAG, "NetworkLoggingHandler received an unknown of message.");
                break;
            }
        }
    }

    void scheduleBatchFinalization() {
        final long when = SystemClock.elapsedRealtime() + BATCH_FINALIZATION_TIMEOUT_MS;
        // We use alarm manager and not just postDelayed here to ensure the batch gets finalized
        // even if the device goes to sleep.
        mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, when,
                BATCH_FINALIZATION_TIMEOUT_ALARM_INTERVAL_MS, NETWORK_LOGGING_TIMEOUT_ALARM_TAG,
                mBatchTimeoutAlarmListener, this);
        Slog.d(TAG, "Scheduled a new batch finalization alarm " + BATCH_FINALIZATION_TIMEOUT_MS
                + "ms from now.");
    }

    synchronized void pause() {
        Slog.d(TAG, "Paused network logging");
        mPaused = true;
    }

    void resume() {
        Bundle notificationExtras = null;
        synchronized (this) {
            if (!mPaused) {
                Slog.d(TAG, "Attempted to resume network logging, but logging is not paused.");
                return;
            }

            Slog.d(TAG, "Resumed network logging. Current batch=" + mCurrentBatchToken
                    + ", LastRetrievedBatch=" + mLastRetrievedBatchToken);
            mPaused = false;

            // If there is a batch ready that the device owner hasn't been notified about, do it now.
            if (mBatches.size() > 0 && mLastRetrievedBatchToken != mCurrentBatchToken) {
                scheduleBatchFinalization();
                notificationExtras = buildDeviceOwnerMessageLocked();
            }
        }
        if (notificationExtras != null) {
            notifyDeviceOwner(notificationExtras);
        }
    }

    synchronized void discardLogs() {
        mBatches.clear();
        mNetworkEvents = new ArrayList<>();
        Slog.d(TAG, "Discarded all network logs");
    }

    @GuardedBy("this")
    /** @returns extras if a message should be sent to the device owner */
    private Bundle finalizeBatchAndBuildDeviceOwnerMessageLocked() {
        Bundle notificationExtras = null;
        if (mNetworkEvents.size() > 0) {
            // Finalize the batch and start a new one from scratch.
            if (mBatches.size() >= MAX_BATCHES) {
                // Remove the oldest batch if we hit the limit.
                mBatches.removeAt(0);
            }
            mCurrentBatchToken++;
            mBatches.append(mCurrentBatchToken, mNetworkEvents);
            mNetworkEvents = new ArrayList<>();
            if (!mPaused) {
                notificationExtras = buildDeviceOwnerMessageLocked();
            }
        } else {
            // Don't notify the DO, since there are no events; DPC can still retrieve
            // the last full batch if not paused.
            Slog.d(TAG, "Was about to finalize the batch, but there were no events to send to"
                    + " the DPC, the batchToken of last available batch: " + mCurrentBatchToken);
        }
        // Regardless of whether the batch was non-empty schedule a new finalization after timeout.
        scheduleBatchFinalization();
        return notificationExtras;
    }

    @GuardedBy("this")
    /** Build extras notification to the DO. Should only be called when there
        is a batch available. */
    private Bundle buildDeviceOwnerMessageLocked() {
        final Bundle extras = new Bundle();
        final int lastBatchSize = mBatches.valueAt(mBatches.size() - 1).size();
        extras.putLong(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_TOKEN, mCurrentBatchToken);
        extras.putInt(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_COUNT, lastBatchSize);
        return extras;
    }

    /** Sends a notification to the DO. Should not hold locks as DevicePolicyManagerService may
        call into NetworkLoggingHandler. */
    private void notifyDeviceOwner(Bundle extras) {
        Slog.d(TAG, "Sending network logging batch broadcast to device owner, batchToken: "
                + extras.getLong(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_TOKEN, -1));
        if (Thread.holdsLock(this)) {
            Slog.wtfStack(TAG, "Shouldn't be called with NetworkLoggingHandler lock held");
            return;
        }
        mDpm.sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_NETWORK_LOGS_AVAILABLE, extras);
    }

    synchronized List<NetworkEvent> retrieveFullLogBatch(final long batchToken) {
        final int index = mBatches.indexOfKey(batchToken);
        if (index < 0) {
            // Invalid token or batch has already been discarded.
            return null;
        }

        // Schedule this and older batches to be discarded after a delay to lessen memory load
        // without interfering with the admin's ability to collect logs out-of-order.
        // It isn't critical and we allow it to be delayed further if the phone sleeps, so we don't
        // use the alarm manager here.
        postDelayed(() -> {
            synchronized(this) {
                while (mBatches.size() > 0 && mBatches.keyAt(0) <= batchToken) {
                    mBatches.removeAt(0);
                }
            }
        }, RETRIEVED_BATCH_DISCARD_DELAY_MS);

        mLastRetrievedBatchToken = batchToken;
        return mBatches.valueAt(index);
    }
}

