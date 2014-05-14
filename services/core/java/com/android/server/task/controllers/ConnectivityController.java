/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.task.controllers;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.server.task.TaskManagerService;

import java.util.LinkedList;
import java.util.List;

/**
 * Handles changes in connectivity.
 * We are only interested in metered vs. unmetered networks, and we're interested in them on a
 * per-user basis.
 */
public class ConnectivityController extends StateController {
    private static final String TAG = "TaskManager.Connectivity";
    private static final boolean DEBUG = true;

    private final List<TaskStatus> mTrackedTasks = new LinkedList<TaskStatus>();
    private final BroadcastReceiver mConnectivityChangedReceiver =
            new ConnectivityChangedReceiver();
    /** Singleton. */
    private static ConnectivityController mSingleton;

    /** Track whether the latest active network is metered. */
    private boolean mMetered;
    /** Track whether the latest active network is connected. */
    private boolean mConnectivity;

    public static synchronized ConnectivityController get(TaskManagerService taskManager) {
        if (mSingleton == null) {
            mSingleton = new ConnectivityController(taskManager);
        }
        return mSingleton;
    }

    private ConnectivityController(TaskManagerService service) {
        super(service);
        // Register connectivity changed BR.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiverAsUser(
                mConnectivityChangedReceiver, UserHandle.ALL, intentFilter, null, null);
    }

    @Override
    public synchronized void maybeStartTrackingTask(TaskStatus taskStatus) {
        if (taskStatus.hasConnectivityConstraint() || taskStatus.hasMeteredConstraint()) {
            taskStatus.connectivityConstraintSatisfied.set(mConnectivity);
            taskStatus.meteredConstraintSatisfied.set(mMetered);
            mTrackedTasks.add(taskStatus);
        }
    }

    @Override
    public synchronized void maybeStopTrackingTask(TaskStatus taskStatus) {
        mTrackedTasks.remove(taskStatus);
    }

    /**
     * @param userId Id of the user for whom we are updating the connectivity state.
     */
    private void updateTrackedTasks(int userId) {
        boolean changed = false;
        for (TaskStatus ts : mTrackedTasks) {
            if (ts.getUserId() != userId) {
                continue;
            }
            boolean prevIsConnected = ts.connectivityConstraintSatisfied.getAndSet(mConnectivity);
            boolean prevIsMetered = ts.meteredConstraintSatisfied.getAndSet(mMetered);
            if (prevIsConnected != mConnectivity || prevIsMetered != mMetered) {
                    changed = true;
            }
        }
        if (changed) {
            mStateChangedListener.onControllerStateChanged();
        }
    }

    class ConnectivityChangedReceiver extends BroadcastReceiver {
        /**
         * We'll receive connectivity changes for each user here, which we process independently.
         * We are only interested in the active network here. We're only interested in the active
         * network, b/c the end result of this will be for apps to try to hit the network.
         * @param context The Context in which the receiver is running.
         * @param intent The Intent being received.
         */
        // TODO: Test whether this will be called twice for each user.
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                final int networkType =
                        intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE,
                                ConnectivityManager.TYPE_NONE);
                // Connectivity manager for THIS context - important!
                final ConnectivityManager connManager = (ConnectivityManager)
                        context.getSystemService(Context.CONNECTIVITY_SERVICE);
                final NetworkInfo activeNetwork = connManager.getActiveNetworkInfo();
                // This broadcast gets sent a lot, only update if the active network has changed.
                if (activeNetwork != null && activeNetwork.getType() == networkType) {
                    final int userid = context.getUserId();
                    mMetered = false;
                    mConnectivity =
                            !intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                    if (mConnectivity) {  // No point making the call if we know there's no conn.
                        mMetered = connManager.isActiveNetworkMetered();
                    }
                    updateTrackedTasks(userid);
                }
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Unrecognised action in intent: " + action);
                }
            }
        }
    };
}
