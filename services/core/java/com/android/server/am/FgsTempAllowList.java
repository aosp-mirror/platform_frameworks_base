/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

import android.annotation.Nullable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import java.util.function.BiConsumer;

/**
 * List of keys that have expiration time.
 * If the expiration time is less than current elapsedRealtime, the key has expired.
 * Otherwise it is valid (or allowed).
 *
 * <p>This is used for both FGS-BG-start restriction, and FGS-while-in-use permissions check.</p>
 *
 * <p>Note: the underlying data structure is an {@link SparseArray}, for performance reason,
 * it is only suitable to hold up to hundreds of entries.</p>
 * @param <E> type of the additional optional info.
 */
public class FgsTempAllowList<E> {
    private static final int DEFAULT_MAX_SIZE = 100;

    /**
     * The value is Pair type, Pair.first is the expirationTime(an elapsedRealtime),
     * Pair.second is the optional information entry about this key.
     */
    private final SparseArray<Pair<Long, E>> mTempAllowList = new SparseArray<>();
    private int mMaxSize = DEFAULT_MAX_SIZE;
    private final Object mLock = new Object();

    public FgsTempAllowList() {
    }

    /**
     *
     * @param maxSize The max size of the list. It is only a suggestion. If the list size is
     *                larger than max size, a warning message is printed in logcat, new entry can
     *                still be added to the list. The default max size is {@link #DEFAULT_MAX_SIZE}.
     */
    public FgsTempAllowList(int maxSize) {
        if (maxSize <= 0) {
            Slog.e(TAG_AM, "Invalid FgsTempAllowList maxSize:" + maxSize
                    + ", force default maxSize:" + DEFAULT_MAX_SIZE);
            mMaxSize = DEFAULT_MAX_SIZE;
        } else {
            mMaxSize = maxSize;
        }
    }

    /**
     * Add a key and its duration with optional info into the temp allowlist.
     * @param durationMs temp-allowlisted duration in milliseconds.
     * @param entry additional optional information of this key, could be null.
     */
    public void add(int uid, long durationMs, @Nullable E entry) {
        synchronized (mLock) {
            if (durationMs <= 0) {
                Slog.e(TAG_AM, "FgsTempAllowList bad duration:" + durationMs + " key: "
                        + uid);
                return;
            }
            // The temp allowlist should be a short list with only a few entries in it.
            // for a very large list, HashMap structure should be used.
            final long now = SystemClock.elapsedRealtime();
            final int size = mTempAllowList.size();
            if (size > mMaxSize) {
                Slog.w(TAG_AM, "FgsTempAllowList length:" + size + " exceeds maxSize"
                        + mMaxSize);
                for (int index = size - 1; index >= 0; index--) {
                    if (mTempAllowList.valueAt(index).first < now) {
                        mTempAllowList.removeAt(index);
                    }
                }
            }
            final Pair<Long, E> existing = mTempAllowList.get(uid);
            final long expirationTime = now + durationMs;
            if (existing == null || existing.first < expirationTime) {
                mTempAllowList.put(uid, new Pair(expirationTime, entry));
            }
        }
    }

    /**
     * If the key has not expired (AKA allowed), return its non-null value.
     * If the key has expired, return null.
     * @return
     */
    @Nullable
    public Pair<Long, E> get(int uid) {
        synchronized (mLock) {
            final int index = mTempAllowList.indexOfKey(uid);
            if (index < 0) {
                return null;
            } else if (mTempAllowList.valueAt(index).first < SystemClock.elapsedRealtime()) {
                mTempAllowList.removeAt(index);
                return null;
            } else {
                return mTempAllowList.valueAt(index);
            }
        }
    }

    /**
     * If the key has not expired (AKA allowed), return true.
     * If the key has expired, return false.
     */
    public boolean isAllowed(int uid) {
        Pair<Long, E> entry = get(uid);
        return entry != null;
    }

    /**
     * Remove a given UID.
     */
    public void removeUid(int uid) {
        synchronized (mLock) {
            mTempAllowList.remove(uid);
        }
    }

    /**
     * Remove by appId.
     */
    public void removeAppId(int appId) {
        synchronized (mLock) {
            // Find all UIDs matching the appId.
            for (int i = mTempAllowList.size() - 1; i >= 0; i--) {
                final int uid = mTempAllowList.keyAt(i);
                if (UserHandle.getAppId(uid) == appId) {
                    mTempAllowList.removeAt(i);
                }
            }
        }
    }

    /**
     * Iterate over the entries.
     */
    public void forEach(BiConsumer<Integer, Pair<Long, E>> callback) {
        synchronized (mLock) {
            for (int i = 0; i < mTempAllowList.size(); i++) {
                final int uid = mTempAllowList.keyAt(i);
                final Pair<Long, E> entry = mTempAllowList.valueAt(i);
                if (entry != null) {
                    callback.accept(uid, entry);
                }
            }
        }
    }
}
