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

import com.android.internal.annotations.VisibleForTesting;

import java.util.Map;
import java.util.HashMap;

/**
 * In memory storage for listeners to be notified when new recovery snapshot is available.
 * Note: implementation is not thread safe and it is used to mock final {@link PendingIntent}
 * class.
 *
 * @hide
 */
public class ListenersStorage {
    private Map<Integer, PendingIntent> mAgentIntents = new HashMap<>();

    private static final ListenersStorage mInstance = new ListenersStorage();
    public static ListenersStorage getInstance() {
        return mInstance;
    }

    /**
     * Sets new listener for the recovery agent, identified by {@code uid}
     *
     * @param recoveryAgentUid uid
     * @param intent PendingIntent which will be triggered than new snapshot is available.
     */
    public void setSnapshotListener(int recoveryAgentUid, @Nullable PendingIntent intent) {
        mAgentIntents.put(recoveryAgentUid, intent);
    }

    /**
     * Notifies recovery agent, that new snapshot is available.
     * Does nothing if a listener was not registered.
     *
     * @param recoveryAgentUid uid.
     */
    public void recoverySnapshotAvailable(int recoveryAgentUid) {
        PendingIntent intent = mAgentIntents.get(recoveryAgentUid);
        if (intent != null) {
            try {
                intent.send();
            } catch (PendingIntent.CanceledException e) {
                // Ignore - sending intent is not allowed.
            }
        }
    }
}
