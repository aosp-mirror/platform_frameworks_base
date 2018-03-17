/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

/**
 * In memory storage for listeners to be notified when new recovery snapshot is available. This
 * class is thread-safe. It is used on two threads - the service thread and the thread that runs the
 * {@link KeySyncTask}.
 *
 * @hide
 */
public class RecoverySnapshotListenersStorage {
    private static final String TAG = "RecoverySnapshotLstnrs";

    @GuardedBy("this")
    private SparseArray<PendingIntent> mAgentIntents = new SparseArray<>();

    @GuardedBy("this")
    private ArraySet<Integer> mAgentsWithPendingSnapshots = new ArraySet<>();

    /**
     * Sets new listener for the recovery agent, identified by {@code uid}.
     *
     * @param recoveryAgentUid uid of the recovery agent.
     * @param intent PendingIntent which will be triggered when new snapshot is available.
     */
    public synchronized void setSnapshotListener(
            int recoveryAgentUid, @Nullable PendingIntent intent) {
        Log.i(TAG, "Registered listener for agent with uid " + recoveryAgentUid);
        mAgentIntents.put(recoveryAgentUid, intent);

        if (mAgentsWithPendingSnapshots.contains(recoveryAgentUid)) {
            Log.i(TAG, "Snapshot already created for agent. Immediately triggering intent.");
            tryToSendIntent(recoveryAgentUid, intent);
        }
    }

    /**
     * Returns {@code true} if a listener has been set for the recovery agent.
     */
    public synchronized boolean hasListener(int recoveryAgentUid) {
        return mAgentIntents.get(recoveryAgentUid) != null;
    }

    /**
     * Notifies recovery agent that new snapshot is available. If a recovery agent has not yet
     * registered a {@link PendingIntent}, remembers that a snapshot is pending for it, so that
     * when it does register, that intent is immediately triggered.
     *
     * @param recoveryAgentUid uid of recovery agent.
     */
    public synchronized void recoverySnapshotAvailable(int recoveryAgentUid) {
        PendingIntent intent = mAgentIntents.get(recoveryAgentUid);
        if (intent == null) {
            Log.i(TAG, "Snapshot available for agent " + recoveryAgentUid
                    + " but agent has not yet initialized. Will notify agent when it does.");
            mAgentsWithPendingSnapshots.add(recoveryAgentUid);
            return;
        }

        tryToSendIntent(recoveryAgentUid, intent);
    }

    /**
     * Attempts to send {@code intent} for the recovery agent. If this fails, remembers to notify
     * the recovery agent immediately if it registers a new intent.
     */
    private synchronized void tryToSendIntent(int recoveryAgentUid, PendingIntent intent) {
        try {
            intent.send();
            mAgentsWithPendingSnapshots.remove(recoveryAgentUid);
            Log.d(TAG, "Successfully notified listener.");
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG,
                    "Failed to trigger PendingIntent for " + recoveryAgentUid,
                    e);
            // As it failed to trigger, trigger immediately if a new intent is registered later.
            mAgentsWithPendingSnapshots.add(recoveryAgentUid);
        }
    }
}
