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

import java.util.function.Consumer;
import java.util.function.IntFunction;

final class UserDataRepository {

    private final Object mMutationLock = new Object();

    @NonNull
    private volatile ImmutableSparseArray<UserData> mUserData = ImmutableSparseArray.empty();

    @NonNull
    private final IntFunction<InputMethodBindingController> mBindingControllerFactory;
    @NonNull
    private final IntFunction<ImeVisibilityStateComputer> mVisibilityStateComputerFactory;

    @AnyThread
    @NonNull
    UserData getOrCreate(@UserIdInt int userId) {
        // Do optimistic read first for optimization.
        final var userData = mUserData.get(userId);
        if (userData != null) {
            return userData;
        }
        // Note that the below line can be called concurrently. Here we assume that
        // instantiating UserData for the same user multiple times would have no side effect.
        final var newUserData = new UserData(userId, mBindingControllerFactory.apply(userId),
                mVisibilityStateComputerFactory.apply(userId));
        synchronized (mMutationLock) {
            mUserData = mUserData.cloneWithPutOrSelf(userId, newUserData);
            return newUserData;
        }
    }

    @AnyThread
    void forAllUserData(Consumer<UserData> consumer) {
        mUserData.forEach(consumer);
    }

    UserDataRepository(@NonNull IntFunction<InputMethodBindingController> bindingControllerFactory,
            @NonNull IntFunction<ImeVisibilityStateComputer> visibilityStateComputerFactory) {
        mBindingControllerFactory = bindingControllerFactory;
        mVisibilityStateComputerFactory = visibilityStateComputerFactory;
    }

    @AnyThread
    void remove(@UserIdInt int userId) {
        synchronized (mMutationLock) {
            mUserData = mUserData.cloneWithRemoveOrSelf(userId);
        }
    }
}
