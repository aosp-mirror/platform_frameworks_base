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

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.MemoryIntArray;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.IOException;

/**
 * This class tracks changes for config/global/secure/system tables
 * on a per-user basis and updates shared memory regions which
 * client processes can read to determine if their local caches are
 * stale.
 */
final class GenerationRegistry {
    private static final String LOG_TAG = "GenerationRegistry";

    private static final boolean DEBUG = false;

    private final Object mLock;

    // Key -> backingStore mapping
    @GuardedBy("mLock")
    private final ArrayMap<Integer, MemoryIntArray> mKeyToBackingStoreMap = new ArrayMap();

    // Key -> (String->Index map) mapping
    @GuardedBy("mLock")
    private final ArrayMap<Integer, ArrayMap<String, Integer>> mKeyToIndexMapMap = new ArrayMap<>();

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    // Maximum number of backing stores allowed
    static final int NUM_MAX_BACKING_STORE = 8;

    @GuardedBy("mLock")
    private int mNumBackingStore = 0;

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    // Maximum size of an individual backing store
    static final int MAX_BACKING_STORE_SIZE = MemoryIntArray.getMaxSize();

    // Use an empty string to track the generation number of all non-predefined, unset settings
    // The generation number is only increased when a new non-predefined setting is inserted
    private static final String DEFAULT_MAP_KEY_FOR_UNSET_SETTINGS = "";

    public GenerationRegistry(Object lock) {
        mLock = lock;
    }

    /**
     *  Increment the generation number if the setting is already cached in the backing stores.
     *  Otherwise, do nothing.
     */
    public void incrementGeneration(int key, String name) {
        final boolean isConfig =
                (SettingsState.getTypeFromKey(key) == SettingsState.SETTINGS_TYPE_CONFIG);
        // Only store the prefix if the mutated setting is a config
        final String indexMapKey = isConfig ? (name.split("/")[0] + "/") : name;
        incrementGenerationInternal(key, indexMapKey);
    }

    private void incrementGenerationInternal(int key, @NonNull String indexMapKey) {
        if (SettingsState.isGlobalSettingsKey(key)) {
            // Global settings are shared across users, so ignore the userId in the key
            key = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
        }
        synchronized (mLock) {
            final MemoryIntArray backingStore = getBackingStoreLocked(key,
                    /* createIfNotExist= */ false);
            if (backingStore == null) {
                return;
            }
            try {
                final int index = getKeyIndexLocked(key, indexMapKey, mKeyToIndexMapMap,
                        backingStore, /* createIfNotExist= */ false);
                if (index < 0) {
                    return;
                }
                final int generation = backingStore.get(index) + 1;
                backingStore.set(index, generation);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Incremented generation for "
                            + (indexMapKey.isEmpty() ? "unset settings" : "setting:" + indexMapKey)
                            + " key:" + SettingsState.keyToString(key)
                            + " at index:" + index);
                }
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Error updating generation id", e);
                destroyBackingStoreLocked(key);
            }
        }
    }

    // A new, non-predefined setting has been inserted, increment the tracking number for all unset
    // settings
    public void incrementGenerationForUnsetSettings(int key) {
        final boolean isConfig =
                (SettingsState.getTypeFromKey(key) == SettingsState.SETTINGS_TYPE_CONFIG);
        if (isConfig) {
            // No need to track new settings for configs
            return;
        }
        incrementGenerationInternal(key, DEFAULT_MAP_KEY_FOR_UNSET_SETTINGS);
    }

    /**
     *  Return the backing store's reference, the index and the current generation number
     *  of a cached setting. If it was not in the backing store, first create the entry in it before
     *  returning the result.
     */
    public void addGenerationData(Bundle bundle, int key, String indexMapKey) {
        if (SettingsState.isGlobalSettingsKey(key)) {
            // Global settings are shared across users, so ignore the userId in the key
            key = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_GLOBAL, UserHandle.USER_SYSTEM);
        }
        synchronized (mLock) {
            final MemoryIntArray backingStore = getBackingStoreLocked(key,
                    /* createIfNotExist= */ true);
            if (backingStore == null) {
                // Error accessing existing backing store or no new backing store is available
                return;
            }
            try {
                final int index = getKeyIndexLocked(key, indexMapKey, mKeyToIndexMapMap,
                        backingStore, /* createIfNotExist= */ true);
                if (index < 0) {
                    // Should not happen unless having error accessing the backing store
                    return;
                }
                bundle.putParcelable(Settings.CALL_METHOD_TRACK_GENERATION_KEY, backingStore);
                bundle.putInt(Settings.CALL_METHOD_GENERATION_INDEX_KEY, index);
                bundle.putInt(Settings.CALL_METHOD_GENERATION_KEY, backingStore.get(index));
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Exported index:" + index + " for "
                            + (indexMapKey.isEmpty() ? "unset settings" : "setting:" + indexMapKey)
                            + " key:" + SettingsState.keyToString(key));
                }
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Error adding generation data", e);
                destroyBackingStoreLocked(key);
            }
        }
    }

    public void addGenerationDataForUnsetSettings(Bundle bundle, int key) {
        addGenerationData(bundle, key, /* indexMapKey= */ DEFAULT_MAP_KEY_FOR_UNSET_SETTINGS);
    }

    public void onUserRemoved(int userId) {
        final int secureKey = SettingsState.makeKey(
                SettingsState.SETTINGS_TYPE_SECURE, userId);
        final int systemKey = SettingsState.makeKey(
                SettingsState.SETTINGS_TYPE_SYSTEM, userId);
        synchronized (mLock) {
            if (mKeyToIndexMapMap.containsKey(secureKey)) {
                destroyBackingStoreLocked(secureKey);
                mKeyToIndexMapMap.remove(secureKey);
                mNumBackingStore = mNumBackingStore - 1;
            }
            if (mKeyToIndexMapMap.containsKey(systemKey)) {
                destroyBackingStoreLocked(systemKey);
                mKeyToIndexMapMap.remove(systemKey);
                mNumBackingStore = mNumBackingStore - 1;
            }
        }
    }

    @GuardedBy("mLock")
    private MemoryIntArray getBackingStoreLocked(int key, boolean createIfNotExist) {
        MemoryIntArray backingStore = mKeyToBackingStoreMap.get(key);
        if (!createIfNotExist) {
            return backingStore;
        }
        if (backingStore == null) {
            try {
                if (mNumBackingStore >= NUM_MAX_BACKING_STORE) {
                    if (DEBUG) {
                        Slog.e(LOG_TAG, "Error creating backing store - at capacity");
                    }
                    return null;
                }
                backingStore = new MemoryIntArray(MAX_BACKING_STORE_SIZE);
                mKeyToBackingStoreMap.put(key, backingStore);
                mNumBackingStore += 1;
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Created backing store for "
                            + SettingsState.keyToString(key) + " on user: "
                            + SettingsState.getUserIdFromKey(key));
                }
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Error creating generation tracker", e);
            }
        }
        return backingStore;
    }

    @GuardedBy("mLock")
    private void destroyBackingStoreLocked(int key) {
        MemoryIntArray backingStore = mKeyToBackingStoreMap.get(key);
        if (backingStore != null) {
            try {
                backingStore.close();
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Destroyed backing store " + backingStore);
                }
            } catch (IOException e) {
                Slog.e(LOG_TAG, "Cannot close generation memory array", e);
            }
            mKeyToBackingStoreMap.remove(key);
        }
    }

    private static int getKeyIndexLocked(int key, String indexMapKey,
            ArrayMap<Integer, ArrayMap<String, Integer>> keyToIndexMapMap,
            MemoryIntArray backingStore, boolean createIfNotExist) throws IOException {
        ArrayMap<String, Integer> nameToIndexMap = keyToIndexMapMap.get(key);
        if (nameToIndexMap == null) {
            if (!createIfNotExist) {
                return -1;
            }
            nameToIndexMap = new ArrayMap<>();
            keyToIndexMapMap.put(key, nameToIndexMap);
        }
        int index = nameToIndexMap.getOrDefault(indexMapKey, -1);
        if (index < 0) {
            if (!createIfNotExist) {
                return -1;
            }
            index = findNextEmptyIndex(backingStore);
            if (index >= 0) {
                backingStore.set(index, 1);
                nameToIndexMap.put(indexMapKey, index);
                if (DEBUG) {
                    Slog.i(LOG_TAG, "Allocated index:" + index + " for setting:" + indexMapKey
                            + " of type:" + SettingsState.keyToString(key)
                            + " on user:" + SettingsState.getUserIdFromKey(key));
                }
            } else {
                if (DEBUG) {
                    Slog.e(LOG_TAG, "Could not allocate generation index");
                }
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