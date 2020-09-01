/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.internal.infra;

import android.annotation.NonNull;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

/**
 * A {@link SparseArray} customized for a common use-case of storing state per-user.
 *
 * Unlike a normal {@link SparseArray} this will always create a value on {@link #get} if one is
 * not present instead of returning null.
 *
 * @param <T> user state type
 */
public abstract class PerUser<T> extends SparseArray<T> {

    /**
     * Initialize state for the given user
     */
    protected abstract @NonNull T create(int userId);

    /**
     * Same as {@link #get(int)}, renamed for readability.
     *
     * This will never return null, deferring to {@link #create} instead
     * when called for the first time.
     */
    public @NonNull T forUser(int userId) {
        return get(userId);
    }

    @Override
    public @NonNull T get(int userId) {
        T userState = super.get(userId);
        if (userState != null) {
            return userState;
        } else {
            userState = Preconditions.checkNotNull(create(userId));
            put(userId, userState);
            return userState;
        }
    }
}
