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
import android.app.admin.ConnectEvent;
import android.app.admin.DnsEvent;
import android.app.admin.NetworkEvent;
import android.net.IIpConnectivityMetrics;
import android.net.INetdEventCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
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

    // est. ~128kB of memory usage per full batch TODO(mkarpinski): fine tune based on testing data
    // If this value changes, update DevicePolicyManager#retrieveNetworkLogs() javadoc
    private static final int MAX_EVENTS_PER_BATCH = 1200;
    static final long BATCH_FINALIZATION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(90);

    static final int LOG_NETWORK_EVENT_MSG = 1;
    static final int FINALIZE_BATCH_MSG = 2;

    private final DevicePolicyManagerService mDpm;

    // threadsafe as it's Handler's thread confined
    @GuardedBy("this")
    private ArrayList<NetworkEvent> mNetworkEvents = new ArrayList<NetworkEvent>();

    @GuardedBy("this")
    private ArrayList<NetworkEvent> mFullBatch;

    @GuardedBy("this")
    private long currentFullBatchToken;

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
                    mNetworkEvents.add(networkEvent);
                    if (mNetworkEvents.size() >= MAX_EVENTS_PER_BATCH) {
                        finalizeBatchAndNotifyDeviceOwner();
                    }
                }
                break;
            }
            case FINALIZE_BATCH_MSG: {
                finalizeBatchAndNotifyDeviceOwner();
                break;
            }
        }
    }

    void scheduleBatchFinalization(long delay) {
        removeMessages(FINALIZE_BATCH_MSG);
        sendMessageDelayed(obtainMessage(FINALIZE_BATCH_MSG), delay);
    }

    private synchronized void finalizeBatchAndNotifyDeviceOwner() {
        mFullBatch = mNetworkEvents;
        // start a new batch from scratch
        mNetworkEvents = new ArrayList<NetworkEvent>();
        scheduleBatchFinalization(BATCH_FINALIZATION_TIMEOUT_MS);
        // notify DO that there's a new non-empty batch waiting
        if (mFullBatch.size() > 0) {
            currentFullBatchToken++;
            Bundle extras = new Bundle();
            extras.putLong(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_TOKEN, currentFullBatchToken);
            extras.putInt(DeviceAdminReceiver.EXTRA_NETWORK_LOGS_COUNT, mFullBatch.size());
            mDpm.sendDeviceOwnerCommand(DeviceAdminReceiver.ACTION_NETWORK_LOGS_AVAILABLE, extras);
        } else {
            mFullBatch = null;
        }
    }

    synchronized List<NetworkEvent> retrieveFullLogBatch(long batchToken) {
        if (batchToken != currentFullBatchToken) {
            return null;
        }
        return mFullBatch;
    }
}

