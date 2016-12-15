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
import android.app.admin.NetworkEvent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
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

    static final int LOG_NETWORK_EVENT_MSG = 1;
    static final int FINALIZE_BATCH_MSG = 2;

    private final DevicePolicyManagerService mDpm;

    // threadsafe as it's Handler's thread confined
    @GuardedBy("this")
    private ArrayList<NetworkEvent> mNetworkEvents = new ArrayList<NetworkEvent>();

    @GuardedBy("this")
    private ArrayList<NetworkEvent> mFullBatch;

    @GuardedBy("this")
    private boolean mPaused = false;

    // each full batch is represented by its token, which the DPC has to provide back to retrieve it
    @GuardedBy("this")
    private long mCurrentFullBatchToken;

    @GuardedBy("this")
    private long mLastRetrievedFullBatchToken;

    NetworkLoggingHandler(Looper looper, DevicePolicyManagerService dpm) {
        super(looper);
        mDpm = dpm;
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case LOG_NETWORK_EVENT_MSG: {
                NetworkEvent networkEvent = msg.getData().getParcelable(NETWORK_EVENT_KEY);
                if (networkEvent != null) {
                    synchronized (NetworkLoggingHandler.this) {
                        mNetworkEvents.add(networkEvent);
                        if (mNetworkEvents.size() >= MAX_EVENTS_PER_BATCH) {
                            finalizeBatchAndNotifyDeviceOwnerLocked();
                        }
                    }
                }
                break;
            }
            case FINALIZE_BATCH_MSG: {
                synchronized (NetworkLoggingHandler.this) {
                    finalizeBatchAndNotifyDeviceOwnerLocked();
                }
                break;
            }
        }
    }

    void scheduleBatchFinalization() {
        removeMessages(FINALIZE_BATCH_MSG);
        sendMessageDelayed(obtainMessage(FINALIZE_BATCH_MSG), BATCH_FINALIZATION_TIMEOUT_MS);
        Log.d(TAG, "Scheduled new batch finalization " + BATCH_FINALIZATION_TIMEOUT_MS
                + "ms from now.");
    }

    synchronized void pause() {
        Log.d(TAG, "Paused network logging");
        mPaused = true;
    }

    synchronized void resume() {
        if (!mPaused) {
            Log.d(TAG, "Attempted to resume network logging, but logging is not paused.");
            return;
        }

        Log.d(TAG, "Resumed network logging. Current batch="
                + mCurrentFullBatchToken + ", LastRetrievedBatch=" + mLastRetrievedFullBatchToken);
        mPaused = false;

        // If there is a full batch ready that the device owner hasn't been notified about, do it
        // now.
        if (mFullBatch != null && mFullBatch.size() > 0
                && mLastRetrievedFullBatchToken != mCurrentFullBatchToken) {
            scheduleBatchFinalization();
            notifyDeviceOwnerLocked();
        }
    }

    synchronized void discardLogs() {
        mFullBatch = null;
        mNetworkEvents = new ArrayList<NetworkEvent>();
        Log.d(TAG, "Discarded all network logs");
    }

    @GuardedBy("this")
    private void finalizeBatchAndNotifyDeviceOwnerLocked() {
        if (mNetworkEvents.size() > 0) {
            // finalize the batch and start a new one from scratch
            mFullBatch = mNetworkEvents;
            mCurrentFullBatchToken++;
            mNetworkEvents = new ArrayList<NetworkEvent>();
            if (!mPaused) {
                notifyDeviceOwnerLocked();
            }
        } else {
            // don't notify the DO, since there are no events; DPC can still retrieve
            // the last full batch if not paused.
            Log.d(TAG, "Was about to finalize the batch, but there were no events to send to"
                    + " the DPC, the batchToken of last available batch: "
                    + mCurrentFullBatchToken);
        }
        // regardless of whether the batch was non-empty schedule a new finalization after timeout
        scheduleBatchFinalization();
    }

    @GuardedBy("this")
    private void notifyDeviceOwnerLocked() {
        Bundle extras = new Bundle();
        extras.putLong(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_TOKEN, mCurrentFullBatchToken);
        extras.putInt(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_COUNT, mFullBatch.size());
        Log.d(TAG, "Sending network logging batch broadcast to device owner, batchToken: "
                + mCurrentFullBatchToken);
        mDpm.sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_NETWORK_LOGS_AVAILABLE, extras);
    }

    synchronized List<NetworkEvent> retrieveFullLogBatch(long batchToken) {
        if (batchToken != mCurrentFullBatchToken) {
            return null;
        }
        mLastRetrievedFullBatchToken = mCurrentFullBatchToken;
        return mFullBatch;
    }
}

