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

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.pm.UserManagerInternal;

import java.util.function.Consumer;
import java.util.function.IntFunction;

final class UserDataRepository {

    @GuardedBy("ImfLock.class")
    private final SparseArray<UserData> mUserData = new SparseArray<>();

    private final IntFunction<InputMethodBindingController> mBindingControllerFactory;

    @NonNull
    private final Context mContext;

    @GuardedBy("ImfLock.class")
    @NonNull
    UserData getOrCreate(@UserIdInt int userId) {
        UserData userData = mUserData.get(userId);
        if (userData == null) {
            userData = new UserData(userId, mBindingControllerFactory.apply(userId), mContext);
            mUserData.put(userId, userData);
        }
        return userData;
    }

    @GuardedBy("ImfLock.class")
    void forAllUserData(Consumer<UserData> consumer) {
        for (int i = 0; i < mUserData.size(); i++) {
            consumer.accept(mUserData.valueAt(i));
        }
    }

    UserDataRepository(
            @NonNull Context context,
            @NonNull Handler handler, @NonNull UserManagerInternal userManagerInternal,
            @NonNull IntFunction<InputMethodBindingController> bindingControllerFactory) {
        mContext = context;
        mBindingControllerFactory = bindingControllerFactory;
        userManagerInternal.addUserLifecycleListener(
                new UserManagerInternal.UserLifecycleListener() {
                    @Override
                    public void onUserRemoved(UserInfo user) {
                        final int userId = user.id;
                        handler.post(() -> {
                            synchronized (ImfLock.class) {
                                mUserData.remove(userId);
                            }
                        });
                    }

                    @Override
                    public void onUserCreated(UserInfo user, Object unusedToken) {
                        final int userId = user.id;
                        handler.post(() -> {
                            synchronized (ImfLock.class) {
                                getOrCreate(userId);
                            }
                        });
                    }
                });
    }

    /** Placeholder for all IMMS user specific fields */
    static final class UserData {
        @UserIdInt
        final int mUserId;

        @NonNull
        final InputMethodBindingController mBindingController;

        @NonNull
        final InputMethodSubtypeSwitchingController mSwitchingController;

        @NonNull
        final HardwareKeyboardShortcutController mHardwareKeyboardShortcutController;

        /**
         * Intended to be instantiated only from this file.
         */
        private UserData(@UserIdInt int userId,
                @NonNull InputMethodBindingController bindingController, @NonNull Context context) {
            mUserId = userId;
            mBindingController = bindingController;
            mSwitchingController = new InputMethodSubtypeSwitchingController(context);
            mHardwareKeyboardShortcutController = new HardwareKeyboardShortcutController();
        }

        @Override
        public String toString() {
            return "UserData{" + "mUserId=" + mUserId + '}';
        }
    }
}
