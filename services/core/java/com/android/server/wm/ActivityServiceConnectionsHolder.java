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

import static com.android.server.wm.ActivityStack.ActivityState.PAUSING;
import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.function.Consumer;

/**
 * Class for tracking the connections to services on the AM side that activities on the
 * WM side (in the future) bind with for things like oom score adjustment. Would normally be one
 * instance of this per activity for tracking all services connected to that activity. AM will
 * sometimes query this to bump the OOM score for the processes with services connected to visible
 * activities.
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
    private HashSet<T> mConnections;

    ActivityServiceConnectionsHolder(ActivityTaskManagerService service, ActivityRecord activity) {
        mService = service;
        mActivity = activity;
    }

    /** Adds a connection record that the activity has bound to a specific service. */
    public void addConnection(T c) {
        synchronized (mService.mGlobalLock) {
            if (mConnections == null) {
                mConnections = new HashSet<>();
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
            mConnections.remove(c);
        }
    }

    public boolean isActivityVisible() {
        synchronized (mService.mGlobalLock) {
            return mActivity.visible || mActivity.isState(RESUMED, PAUSING);
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
            final Iterator<T> it = mConnections.iterator();
            while (it.hasNext()) {
                T c = it.next();
                consumer.accept(c);
            }
        }
    }

    /** Removes the connection between the activity and all services that were connected to it. */
    void disconnectActivityFromServices() {
        if (mConnections == null || mConnections.isEmpty()) {
            return;
        }
        // Capture and null out mConnections, to guarantee that we process
        // disconnect of these specific connections exactly once even if
        // we're racing with rapid activity lifecycle churn and this
        // method is invoked more than once on this object.
        final Object disc = mConnections;
        mConnections = null;
        mService.mH.post(() -> mService.mAmInternal.disconnectActivityFromServices(this, disc));
    }

    public void dump(PrintWriter pw, String prefix) {
        synchronized (mService.mGlobalLock) {
            pw.println(prefix + "activity=" + mActivity);
        }
    }

}
