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

import com.android.server.pm.UserManagerInternal;

final class MultiUserUtils {
    /**
     * Not intended to be instantiated.
     */
    private MultiUserUtils() {
    }

    /**
     * Return the first user ID (a user has {@link android.content.pm.UserInfo#FLAG_MAIN} if
     * available). Otherwise, return the given default value.
     *
     * @param userManagerInternal {@link UserManagerInternal} to be used to query about users
     * @param defaultValue a user ID that will be returned when there is no main user
     * @return The first main user ID
     */
    @AnyThread
    @UserIdInt
    static int getFirstMainUserIdOrDefault(@NonNull UserManagerInternal userManagerInternal,
            @UserIdInt int defaultValue) {
        final int[] userIds = userManagerInternal.getUserIds();
        if (userIds != null) {
            for (int userId : userIds) {
                final var userInfo = userManagerInternal.getUserInfo(userId);
                if (userInfo.isMain()) {
                    return userId;
                }
            }
        }
        return defaultValue;
    }
}
