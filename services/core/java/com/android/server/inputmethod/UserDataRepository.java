/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.IntFunction;

final class UserDataRepository {

    private final ReentrantReadWriteLock mUserDataLock = new ReentrantReadWriteLock();

    @GuardedBy("mUserDataLock")
    private final SparseArray<UserData> mUserData = new SparseArray<>();

    private final IntFunction<InputMethodBindingController> mBindingControllerFactory;

    @AnyThread
    @NonNull
    UserData getOrCreate(@UserIdInt int userId) {
        mUserDataLock.writeLock().lock();
        try {
            UserData userData = mUserData.get(userId);
            if (userData == null) {
                userData = new UserData(userId, mBindingControllerFactory.apply(userId));
                mUserData.put(userId, userData);
            }
            return userData;
        } finally {
            mUserDataLock.writeLock().unlock();
        }
    }

    @AnyThread
    void forAllUserData(Consumer<UserData> consumer) {
        final SparseArray<UserData> copiedArray;
        mUserDataLock.readLock().lock();
        try {
            copiedArray = mUserData.clone();
        } finally {
            mUserDataLock.readLock().unlock();
        }
        for (int i = 0; i < copiedArray.size(); i++) {
            consumer.accept(copiedArray.valueAt(i));
        }
    }

    UserDataRepository(
            @NonNull IntFunction<InputMethodBindingController> bindingControllerFactory) {
        mBindingControllerFactory = bindingControllerFactory;
    }

    @AnyThread
    void remove(@UserIdInt int userId) {
        mUserDataLock.writeLock().lock();
        try {
            mUserData.remove(userId);
        } finally {
            mUserDataLock.writeLock().unlock();
        }
    }
}
