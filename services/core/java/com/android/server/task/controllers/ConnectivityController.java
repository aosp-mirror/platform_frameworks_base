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
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.ConnectivityService;
import com.android.server.task.StateChangedListener;
import com.android.server.task.TaskManagerService;

import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.List;

/**
 * Handles changes in connectivity.
 * We are only interested in metered vs. unmetered networks, and we're interested in them on a
 * per-user basis.
 */
public class ConnectivityController extends StateController implements
        ConnectivityManager.OnNetworkActiveListener {
    private static final String TAG = "TaskManager.Conn";

    private final List<TaskStatus> mTrackedTasks = new LinkedList<TaskStatus>();
    private final BroadcastReceiver mConnectivityChangedReceiver =
            new ConnectivityChangedReceiver();
    /** Singleton. */
    private static ConnectivityController mSingleton;
    private static Object sCreationLock = new Object();
    /** Track whether the latest active network is metered. */
    private boolean mNetworkUnmetered;
    /** Track whether the latest active network is connected. */
    private boolean mNetworkConnected;

    public static ConnectivityController get(TaskManagerService taskManager) {
        synchronized (sCreationLock) {
            if (mSingleton == null) {
                mSingleton = new ConnectivityController(taskManager, taskManager.getContext());
            }
            return mSingleton;
        }
    }

    private ConnectivityController(StateChangedListener stateChangedListener, Context context) {
        super(stateChangedListener, context);
        // Register connectivity changed BR.
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiverAsUser(
                mConnectivityChangedReceiver, UserHandle.ALL, intentFilter, null, null);
        ConnectivityService cs =
                (ConnectivityService)ServiceManager.getService(Context.CONNECTIVITY_SERVICE);
        if (cs != null) {
            if (cs.getActiveNetworkInfo() != null) {
                mNetworkConnected = cs.getActiveNetworkInfo().isConnected();
            }
            mNetworkUnmetered = mNetworkConnected && !cs.isActiveNetworkMetered();
        }
    }

    @Override
    public void maybeStartTrackingTask(TaskStatus taskStatus) {
        if (taskStatus.hasConnectivityConstraint() || taskStatus.hasUnmeteredConstraint()) {
            synchronized (mTrackedTasks) {
                taskStatus.connectivityConstraintSatisfied.set(mNetworkConnected);
                taskStatus.unmeteredConstraintSatisfied.set(mNetworkUnmetered);
                mTrackedTasks.add(taskStatus);
            }
        }
    }

    @Override
    public void maybeStopTrackingTask(TaskStatus taskStatus) {
        if (taskStatus.hasConnectivityConstraint() || taskStatus.hasUnmeteredConstraint()) {
            synchronized (mTrackedTasks) {
                mTrackedTasks.remove(taskStatus);
            }
        }
    }

    /**
     * @param userId Id of the user for whom we are updating the connectivity state.
     */
    private void updateTrackedTasks(int userId) {
        synchronized (mTrackedTasks) {
            boolean changed = false;
            for (TaskStatus ts : mTrackedTasks) {
                if (ts.getUserId() != userId) {
                    continue;
                }
                boolean prevIsConnected =
                        ts.connectivityConstraintSatisfied.getAndSet(mNetworkConnected);
                boolean prevIsMetered = ts.unmeteredConstraintSatisfied.getAndSet(mNetworkUnmetered);
                if (prevIsConnected != mNetworkConnected || prevIsMetered != mNetworkUnmetered) {
                    changed = true;
                }
            }
            if (changed) {
                mStateChangedListener.onControllerStateChanged();
            }
        }
    }

    /**
     * We know the network has just come up. We want to run any tasks that are ready.
     */
    public synchronized void onNetworkActive() {
        synchronized (mTrackedTasks) {
            for (TaskStatus ts : mTrackedTasks) {
                if (ts.isReady()) {
                    if (DEBUG) {
                        Slog.d(TAG, "Running " + ts + " due to network activity.");
                    }
                    mStateChangedListener.onRunTaskNow(ts);
                }
            }
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
            if (DEBUG) {
                Slog.d(TAG, "Received connectivity event: " + intent.getAction() + " u"
                        + context.getUserId());
            }
            final String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                final int networkType =
                        intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE,
                                ConnectivityManager.TYPE_NONE);
                // Connectivity manager for THIS context - important!
                final ConnectivityManager connManager = (ConnectivityManager)
                        context.getSystemService(Context.CONNECTIVITY_SERVICE);
                final NetworkInfo activeNetwork = connManager.getActiveNetworkInfo();
                final int userid = context.getUserId();
                // This broadcast gets sent a lot, only update if the active network has changed.
                if (activeNetwork == null) {
                    mNetworkUnmetered = false;
                    mNetworkConnected = false;
                    updateTrackedTasks(userid);
                } else if (activeNetwork.getType() == networkType) {
                    mNetworkUnmetered = false;
                    mNetworkConnected = !intent.getBooleanExtra(
                            ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                    if (mNetworkConnected) {  // No point making the call if we know there's no conn.
                        mNetworkUnmetered = !connManager.isActiveNetworkMetered();
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

    @Override
    public void dumpControllerState(PrintWriter pw) {
        pw.println("Conn.");
        pw.println("connected: " + mNetworkConnected + " unmetered: " + mNetworkUnmetered);
        for (TaskStatus ts: mTrackedTasks) {
            pw.println(String.valueOf(ts.hashCode()).substring(0, 3) + ".."
                    + ": C=" + ts.hasConnectivityConstraint()
                    + ", UM=" + ts.hasUnmeteredConstraint());
        }
    }
}