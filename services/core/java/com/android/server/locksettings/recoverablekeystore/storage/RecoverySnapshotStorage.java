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

package com.android.server.locksettings.recoverablekeystore.storage;

import android.annotation.Nullable;
import android.security.keystore.RecoveryData;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

/**
 * In-memory storage for recovery snapshots.
 *
 * <p>Recovery snapshots are generated after a successful screen unlock. They are only generated if
 * the recoverable keystore has been mutated since the previous snapshot. This class stores only the
 * latest snapshot for each recovery agent.
 *
 * <p>This class is thread-safe. It is used both on the service thread and the
 * {@link com.android.server.locksettings.recoverablekeystore.KeySyncTask} thread.
 */
public class RecoverySnapshotStorage {
    @GuardedBy("this")
    private final SparseArray<RecoveryData> mSnapshotByUid = new SparseArray<>();

    /**
     * Sets the latest {@code snapshot} for the recovery agent {@code uid}.
     */
    public synchronized void put(int uid, RecoveryData snapshot) {
        mSnapshotByUid.put(uid, snapshot);
    }

    /**
     * Returns the latest snapshot for the recovery agent {@code uid}, or null if none exists.
     */
    @Nullable
    public synchronized RecoveryData get(int uid) {
        return mSnapshotByUid.get(uid);
    }

    /**
     * Removes any (if any) snapshot associated with recovery agent {@code uid}.
     */
    public synchronized void remove(int uid) {
        mSnapshotByUid.remove(uid);
    }
}
