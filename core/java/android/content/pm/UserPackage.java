/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.util.SparseArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import libcore.util.EmptyArray;

import java.util.Objects;
import java.util.Random;

/**
 * POJO to represent a package for a specific user ID.
 *
 * @hide
 */
public final class UserPackage {
    private static final boolean ENABLE_CACHING = true;
    /**
     * The maximum number of entries to keep in the cache per user ID.
     * The value should ideally be high enough to cover all packages on an end-user device,
     * but low enough that stale or invalid packages would eventually (probably) get removed.
     * This should benefit components that loop through all packages on a device and use this class,
     * since being able to cache the objects for all packages on the device
     * means we don't have to keep recreating the objects.
     */
    @VisibleForTesting
    static final int MAX_NUM_CACHED_ENTRIES_PER_USER = 1000;

    @UserIdInt
    public final int userId;
    public final String packageName;

    private static final Object sCacheLock = new Object();
    @GuardedBy("sCacheLock")
    private static final SparseArrayMap<String, UserPackage> sCache = new SparseArrayMap<>();

    /**
     * Set of userIDs to cache objects for. We start off with an empty set, so there's no caching
     * by default. The system will override with a valid set of userIDs in its process so that
     * caching becomes active in the system process.
     */
    @GuardedBy("sCacheLock")
    private static int[] sUserIds = EmptyArray.INT;

    private UserPackage(int userId, String packageName) {
        this.userId = userId;
        this.packageName = packageName;
    }

    @Override
    public String toString() {
        return "<" + userId + ">" + packageName;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof UserPackage) {
            UserPackage other = (UserPackage) obj;
            return userId == other.userId && Objects.equals(packageName, other.packageName);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + userId;
        result = 31 * result + packageName.hashCode();
        return result;
    }

    /** Return an instance of this class representing the given userId + packageName combination. */
    @NonNull
    public static UserPackage of(@UserIdInt int userId, @NonNull String packageName) {
        if (!ENABLE_CACHING) {
            return new UserPackage(userId, packageName);
        }

        synchronized (sCacheLock) {
            if (!ArrayUtils.contains(sUserIds, userId)) {
                // Don't cache objects for invalid userIds.
                return new UserPackage(userId, packageName);
            }

            UserPackage up = sCache.get(userId, packageName);
            if (up == null) {
                maybePurgeRandomEntriesLocked(userId);
                packageName = packageName.intern();
                up = new UserPackage(userId, packageName);
                sCache.add(userId, packageName, up);
            }
            return up;
        }
    }

    /** Remove the specified app from the cache. */
    public static void removeFromCache(@UserIdInt int userId, @NonNull String packageName) {
        if (!ENABLE_CACHING) {
            return;
        }

        synchronized (sCacheLock) {
            sCache.delete(userId, packageName);
        }
    }

    /** Indicate the list of valid user IDs on the device. */
    public static void setValidUserIds(@NonNull int[] userIds) {
        if (!ENABLE_CACHING) {
            return;
        }

        userIds = userIds.clone();
        synchronized (sCacheLock) {
            sUserIds = userIds;

            for (int u = sCache.numMaps() - 1; u >= 0; --u) {
                final int userId = sCache.keyAt(u);
                if (!ArrayUtils.contains(userIds, userId)) {
                    sCache.deleteAt(u);
                }
            }
        }
    }

    @VisibleForTesting
    public static int numEntriesForUser(int userId) {
        synchronized (sCacheLock) {
            return sCache.numElementsForKey(userId);
        }
    }

    /** Purge a random set of entries if the cache size is too large. */
    @GuardedBy("sCacheLock")
    private static void maybePurgeRandomEntriesLocked(int userId) {
        final int uIdx = sCache.indexOfKey(userId);
        if (uIdx < 0) {
            return;
        }
        int numCached = sCache.numElementsForKeyAt(uIdx);
        if (numCached < MAX_NUM_CACHED_ENTRIES_PER_USER) {
            return;
        }
        // Purge a random set of 1% of cached elements for the userId. We don't want to use a
        // deterministic system of purging because that may cause us to repeatedly remove elements
        // that are frequently added and queried more than others. Choosing a random set
        // means we will probably eventually remove less useful elements.
        // An LRU cache is too expensive for this commonly used utility class.
        final Random rand = new Random();
        final int numToPurge = Math.max(1, MAX_NUM_CACHED_ENTRIES_PER_USER / 100);
        for (int i = 0; i < numToPurge && numCached > 0; ++i) {
            final int removeIdx = rand.nextInt(numCached--);
            sCache.deleteAt(uIdx, removeIdx);
        }
    }
}
