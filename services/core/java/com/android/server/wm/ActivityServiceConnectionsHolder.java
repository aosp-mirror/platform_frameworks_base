/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.ActivityRecord.State.PAUSING;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_CLEANUP;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;

import android.util.ArraySet;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Class for tracking the connections to services on the AM side that activities on the
 * WM side (in the future) bind with for things like oom score adjustment. Would normally be one
 * instance of this per activity for tracking all services connected to that activity. AM will
 * sometimes query this to bump the OOM score for the processes with services connected to visible
 * activities.
 * <p>
 * Public methods are called in AM lock, otherwise in WM lock.
 */
public class ActivityServiceConnectionsHolder<T> {

    private final ActivityTaskManagerService mService;

    /** The activity the owns this service connection object. */
    private final ActivityRecord mActivity;

    /**
     * The service connection object bounded with the owning activity. They represent
     * ConnectionRecord on the AM side, however we don't need to know their object representation
     * on the WM side since we don't perform operations on the object. Mainly here for communication
     * and booking with the AM side.
     */
    private ArraySet<T> mConnections;

    /** Whether all connections of {@link #mActivity} are being removed. */
    private volatile boolean mIsDisconnecting;

    ActivityServiceConnectionsHolder(ActivityTaskManagerService service, ActivityRecord activity) {
        mService = service;
        mActivity = activity;
    }

    /** Adds a connection record that the activity has bound to a specific service. */
    public void addConnection(T c) {
        synchronized (mService.mGlobalLock) {
            if (mIsDisconnecting) {
                // This is unlikely to happen because the caller should create a new holder.
                if (DEBUG_CLEANUP) {
                    Slog.e(TAG_ATM, "Skip adding connection " + c + " to a disconnecting holder of "
                            + mActivity);
                }
                return;
            }
            if (mConnections == null) {
                mConnections = new ArraySet<>();
            }
            mConnections.add(c);
        }
    }

    /** Removed a connection record between the activity and a specific service. */
    public void removeConnection(T c) {
        synchronized (mService.mGlobalLock) {
            if (mConnections == null) {
                return;
            }
            if (DEBUG_CLEANUP && mIsDisconnecting) {
                Slog.v(TAG_ATM, "Remove pending disconnecting " + c + " of " + mActivity);
            }
            mConnections.remove(c);
        }
    }

    public boolean isActivityVisible() {
        synchronized (mService.mGlobalLock) {
            return mActivity.mVisibleRequested || mActivity.isState(RESUMED, PAUSING);
        }
    }

    public int getActivityPid() {
        synchronized (mService.mGlobalLock) {
            return mActivity.hasProcess() ? mActivity.app.getPid() : -1;
        }
    }

    public void forEachConnection(Consumer<T> consumer) {
        synchronized (mService.mGlobalLock) {
            if (mConnections == null || mConnections.isEmpty()) {
                return;
            }
            for (int i = mConnections.size() - 1; i >= 0; i--) {
                consumer.accept(mConnections.valueAt(i));
            }
        }
    }

    /**
     * Removes the connection between the activity and all services that were connected to it. In
     * general, this method is used to clean up if the activity didn't unbind services before it
     * is destroyed.
     */
    void disconnectActivityFromServices() {
        if (mConnections == null || mConnections.isEmpty() || mIsDisconnecting) {
            return;
        }
        // Mark as disconnecting, to guarantee that we process
        // disconnect of these specific connections exactly once even if
        // we're racing with rapid activity lifecycle churn and this
        // method is invoked more than once on this object.
        // It is possible that {@link #removeConnection} is called while the disconnect-runnable is
        // still in the message queue, so keep the reference of {@link #mConnections} to make sure
        // the connection list is up-to-date.
        mIsDisconnecting = true;
        mService.mH.post(() -> {
            mService.mAmInternal.disconnectActivityFromServices(this);
            mIsDisconnecting = false;
        });
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mService.mGlobalLock) {
            pw.println(prefix + "activity=" + mActivity);
        }
    }

    /** Used by {@link ActivityRecord#dump}. */
    @Override
    public String toString() {
        return String.valueOf(mConnections);
    }
}
