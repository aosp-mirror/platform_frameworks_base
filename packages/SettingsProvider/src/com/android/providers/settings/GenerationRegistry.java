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

package com.android.providers.settings;

import android.os.Bundle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.MemoryIntArray;
import android.util.Slog;
import android.util.SparseIntArray;
import com.android.internal.annotations.GuardedBy;

import java.io.IOException;

/**
 * This class tracks changes for global/secure/system tables on a
 * per user basis and updates a shared memory region which client
 * processes can read to determine if their local caches are stale,
 */
final class GenerationRegistry {
    private static final String LOG_TAG = "GenerationTracker";

    private static final boolean DEBUG = false;

    private final Object mLock;

    @GuardedBy("mLock")
    private final SparseIntArray mKeyToIndexMap = new SparseIntArray();

    @GuardedBy("mLock")
    private final MemoryIntArray mImpl;

    public GenerationRegistry(Object lock) {
        mLock = lock;
        // One for the global table, two for system and secure tables for a
        // managed profile (managed profile is not included in the max user
        // count), ten for partially deleted users if users are quickly removed,
        // and twice max user count for system and secure.
        final int size = 1 + 2 + 10 + 2 * UserManager.getMaxSupportedUsers();
        MemoryIntArray impl = null;
        try {
            impl = new MemoryIntArray(size, false);
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Error creating generation tracker", e);
        }
        mImpl = impl;
    }

    public void incrementGeneration(int key) {
        synchronized (mLock) {
            if (mImpl != null) {
                try {
                    final int index = getKeyIndexLocked(key);
                    if (index >= 0) {
                        final int generation = mImpl.get(index) + 1;
                        mImpl.set(index, generation);
                    }
                } catch (IOException e) {
                    Slog.e(LOG_TAG, "Error updating generation id", e);
                }
            }
        }
    }

    public void addGenerationData(Bundle bundle, int key) {
        synchronized (mLock) {
            if (mImpl != null) {
                final int index = getKeyIndexLocked(key);
                if (index >= 0) {
                    bundle.putParcelable(Settings.CALL_METHOD_TRACK_GENERATION_KEY, mImpl);
                    bundle.putInt(Settings.CALL_METHOD_GENERATION_INDEX_KEY, index);
                    if (DEBUG) {
                        Slog.i(LOG_TAG, "Exported index:" + index + " for key:"
                                + SettingsProvider.keyToString(key));
                    }
                }
            }
        }
    }

    private int getKeyIndexLocked(int key) {
        int index = mKeyToIndexMap.get(key, -1);
        if (index < 0) {
            index = findNextEmptyIndex();
            if (index >= 0) {
                try {
                    mImpl.set(index, 1);
                    mKeyToIndexMap.append(key, index);
                    if (DEBUG) {
                        Slog.i(LOG_TAG, "Allocated index:" + index + " for key:"
                                + SettingsProvider.keyToString(key));
                    }
                } catch (IOException e) {
                    Slog.e(LOG_TAG, "Cannot write to generation memory array", e);
                }
            } else {
                Slog.e(LOG_TAG, "Could not allocate generation index");
            }
        }
        return index;
    }

    public void onUserRemoved(int userId) {
        synchronized (mLock) {
            if (mImpl != null && mKeyToIndexMap.size() > 0) {
                final int secureKey = SettingsProvider.makeKey(
                        SettingsProvider.SETTINGS_TYPE_SECURE, userId);
                resetSlotForKeyLocked(secureKey);

                final int systemKey = SettingsProvider.makeKey(
                        SettingsProvider.SETTINGS_TYPE_SYSTEM, userId);
                resetSlotForKeyLocked(systemKey);
            }
        }
    }

    private void resetSlotForKeyLocked(int key) {
        final int index = mKeyToIndexMap.get(key, -1);
        if (index >= 0) {
            mKeyToIndexMap.delete(key);
            try {
                mImpl.set(index, 0);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Freed index:" + index + " for key:"
                            + SettingsProvider.keyToString(key));
                }
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Cannot write to generation memory array", e);
            }
        }
    }

    private int findNextEmptyIndex() {
        try {
            final int size = mImpl.size();
            for (int i = 0; i < size; i++) {
                if (mImpl.get(i) == 0) {
                    return i;
                }
            }
        } catch (IOException e) {
            Slog.e(LOG_TAG, "Error reading generation memory array", e);
        }
        return -1;
    }
}