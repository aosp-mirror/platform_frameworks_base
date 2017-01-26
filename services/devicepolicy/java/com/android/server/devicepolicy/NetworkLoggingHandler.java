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
import android.util.Log;

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
    private static final long BATCH_FINALIZATION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(90);
    private static final long BATCH_FINALIZATION_TIMEOUT_ALARM_INTERVAL_MS =
            TimeUnit.MINUTES.toMillis(30);

    private static final String NETWORK_LOGGING_TIMEOUT_ALARM_TAG = "NetworkLogging.batchTimeout";

    private final DevicePolicyManagerService mDpm;
    private final AlarmManager mAlarmManager;

    private final OnAlarmListener mBatchTimeoutAlarmListener = new OnAlarmListener() {
        @Override
        public void onAlarm() {
            Log.d(TAG, "Received a batch finalization timeout alarm, finalizing "
                    + mNetworkEvents.size() + " pending events.");
            synchronized (NetworkLoggingHandler.this) {
                finalizeBatchAndNotifyDeviceOwnerIfNotEmpty();
            }
        }
    };

    static final int LOG_NETWORK_EVENT_MSG = 1;

    // threadsafe as it's Handler's thread confined
    @GuardedBy("this")
    private ArrayList<NetworkEvent> mNetworkEvents = new ArrayList<NetworkEvent>();

    @GuardedBy("this")
    private ArrayList<NetworkEvent> mFullBatch;

    // each full batch is represented by its token, which the DPC has to provide back to revieve it
    @GuardedBy("this")
    private long mCurrentFullBatchToken;

    NetworkLoggingHandler(Looper looper, DevicePolicyManagerService dpm) {
        super(looper);
        mDpm = dpm;
        mAlarmManager = mDpm.mInjector.getAlarmManager();
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case LOG_NETWORK_EVENT_MSG: {
                NetworkEvent networkEvent = msg.getData().getParcelable(NETWORK_EVENT_KEY);
                if (networkEvent != null) {
                    mNetworkEvents.add(networkEvent);
                    if (mNetworkEvents.size() >= MAX_EVENTS_PER_BATCH) {
                        finalizeBatchAndNotifyDeviceOwnerIfNotEmpty();
                    }
                }
                break;
            }
            default: {
                Log.d(TAG, "NetworkLoggingHandler received an unknown of message.");
                break;
            }
        }
    }

    void scheduleBatchFinalization() {
        final long when = SystemClock.elapsedRealtime() + BATCH_FINALIZATION_TIMEOUT_MS;
        mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, when,
                BATCH_FINALIZATION_TIMEOUT_ALARM_INTERVAL_MS, NETWORK_LOGGING_TIMEOUT_ALARM_TAG,
                mBatchTimeoutAlarmListener, this);
        Log.d(TAG, "Scheduled a new batch finalization alarm " + BATCH_FINALIZATION_TIMEOUT_MS
                + "ms from now.");
    }

    private synchronized void finalizeBatchAndNotifyDeviceOwnerIfNotEmpty() {
        if (mNetworkEvents.size() > 0) {
            // finalize the batch and start a new one from scratch
            mFullBatch = mNetworkEvents;
            mCurrentFullBatchToken++;
            mNetworkEvents = new ArrayList<NetworkEvent>();
            // notify DO that there's a new non-empty batch waiting
            Bundle extras = new Bundle();
            extras.putLong(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_TOKEN, mCurrentFullBatchToken);
            extras.putInt(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_COUNT, mFullBatch.size());
            Log.d(TAG, "Sending network logging batch broadcast to device owner, batchToken: "
                    + mCurrentFullBatchToken);
            mDpm.sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_NETWORK_LOGS_AVAILABLE, extras);
        } else {
            // don't notify the DO, since there are no events; DPC can still retrieve
            // the last full batch
            Log.d(TAG, "Was about to finalize the batch, but there were no events to send to"
                    + " the DPC, the batchToken of last available batch: "
                    + mCurrentFullBatchToken);
        }
        // regardless of whether the batch was non-empty schedule a new finalization after timeout
        scheduleBatchFinalization();
    }

    synchronized List<NetworkEvent> retrieveFullLogBatch(long batchToken) {
        if (batchToken != mCurrentFullBatchToken) {
            return null;
        }
        return mFullBatch;
    }
}

