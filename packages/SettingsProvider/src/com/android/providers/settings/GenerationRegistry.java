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
 * This class tracks changes for config/global/secure/system tables
 * on a per user basis and updates a shared memory region which
 * client processes can read to determine if their local caches are
 * stale.
 */
final class GenerationRegistry {
    private static final String LOG_TAG = "GenerationRegistry";

    private static final boolean DEBUG = false;

    private final Object mLock;

    @GuardedBy("mLock")
    private final SparseIntArray mKeyToIndexMap = new SparseIntArray();

    @GuardedBy("mLock")
    private MemoryIntArray mBackingStore;

    public GenerationRegistry(Object lock) {
        mLock = lock;
    }

    public void incrementGeneration(int key) {
        synchronized (mLock) {
            MemoryIntArray backingStore = getBackingStoreLocked();
            if (backingStore != null) {
                try {
                    final int index = getKeyIndexLocked(key, mKeyToIndexMap, backingStore);
                    if (index >= 0) {
                        final int generation = backingStore.get(index) + 1;
                        backingStore.set(index, generation);
                    }
                } catch (IOException e) {
                    Slog.e(LOG_TAG, "Error updating generation id", e);
                    destroyBackingStore();
                }
            }
        }
    }

    public void addGenerationData(Bundle bundle, int key) {
        synchronized (mLock) {
            MemoryIntArray backingStore = getBackingStoreLocked();
            try {
                if (backingStore != null) {
                    final int index = getKeyIndexLocked(key, mKeyToIndexMap, backingStore);
                    if (index >= 0) {
                        bundle.putParcelable(Settings.CALL_METHOD_TRACK_GENERATION_KEY,
                                backingStore);
                        bundle.putInt(Settings.CALL_METHOD_GENERATION_INDEX_KEY, index);
                        bundle.putInt(Settings.CALL_METHOD_GENERATION_KEY,
                                backingStore.get(index));
                        if (DEBUG) {
                            Slog.i(LOG_TAG, "Exported index:" + index + " for key:"
                                    + SettingsProvider.keyToString(key));
                        }
                    }
                }
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Error adding generation data", e);
                destroyBackingStore();
            }
        }
    }

    public void onUserRemoved(int userId) {
        synchronized (mLock) {
            MemoryIntArray backingStore = getBackingStoreLocked();
            if (backingStore != null && mKeyToIndexMap.size() > 0) {
                try {
                    final int secureKey = SettingsProvider.makeKey(
                            SettingsProvider.SETTINGS_TYPE_SECURE, userId);
                    resetSlotForKeyLocked(secureKey, mKeyToIndexMap, backingStore);

                    final int systemKey = SettingsProvider.makeKey(
                            SettingsProvider.SETTINGS_TYPE_SYSTEM, userId);
                    resetSlotForKeyLocked(systemKey, mKeyToIndexMap, backingStore);
                } catch (IOException e) {
                    Slog.e(LOG_TAG, "Error cleaning up for user", e);
                    destroyBackingStore();
                }
            }
        }
    }

    @GuardedBy("mLock")
    private MemoryIntArray getBackingStoreLocked() {
        if (mBackingStore == null) {
            // One for the config table, one for the global table, two for system
            // and secure tables for a managed profile (managed profile is not
            // included in the max user count), ten for partially deleted users if
            // users are quickly removed, and twice max user count for system and
            // secure.
            final int size = 1 + 1 + 2 + 10 + 2 * UserManager.getMaxSupportedUsers();
            try {
                mBackingStore = new MemoryIntArray(size);
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Created backing store " + mBackingStore);
                }
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Error creating generation tracker", e);
            }
        }
        return mBackingStore;
    }

    private void destroyBackingStore() {
        if (mBackingStore != null) {
            try {
                mBackingStore.close();
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Destroyed backing store " + mBackingStore);
                }
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Cannot close generation memory array", e);
            }
            mBackingStore = null;
        }
    }

    private static void resetSlotForKeyLocked(int key, SparseIntArray keyToIndexMap,
            MemoryIntArray backingStore) throws IOException {
        final int index = keyToIndexMap.get(key, -1);
        if (index >= 0) {
            keyToIndexMap.delete(key);
            backingStore.set(index, 0);
            if (DEBUG) {
                Slog.i(LOG_TAG, "Freed index:" + index + " for key:"
                        + SettingsProvider.keyToString(key));
            }
        }
    }

    private static int getKeyIndexLocked(int key, SparseIntArray keyToIndexMap,
            MemoryIntArray backingStore) throws IOException {
        int index = keyToIndexMap.get(key, -1);
        if (index < 0) {
            index = findNextEmptyIndex(backingStore);
            if (index >= 0) {
                backingStore.set(index, 1);
                keyToIndexMap.append(key, index);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Allocated index:" + index + " for key:"
                            + SettingsProvider.keyToString(key));
                }
            } else {
                Slog.e(LOG_TAG, "Could not allocate generation index");
            }
        }
        return index;
    }

    private static int findNextEmptyIndex(MemoryIntArray backingStore) throws IOException {
        final int size = backingStore.size();
        for (int i = 0; i < size; i++) {
            if (backingStore.get(i) == 0) {
                return i;
            }
        }
        return -1;
    }
}