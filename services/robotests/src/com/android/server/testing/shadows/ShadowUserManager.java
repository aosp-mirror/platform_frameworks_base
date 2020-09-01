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

package com.android.server.testing.shadows;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.os.UserManager;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shadow for {@link UserManager}. */
@Implements(UserManager.class)
public class ShadowUserManager extends org.robolectric.shadows.ShadowUserManager {
    private final Map<Integer, Set<Integer>> profileIds = new HashMap<>();

    /** @see UserManager#isUserUnlocked() */
    @Implementation
    public boolean isUserUnlocked(@UserIdInt int userId) {
        return false;
    }

    /** @see UserManager#getProfileIds(int, boolean) () */
    @Implementation
    @NonNull
    public int[] getProfileIds(@UserIdInt int userId, boolean enabledOnly) {
        // Currently, enabledOnly is ignored.
        if (!profileIds.containsKey(userId)) {
            return new int[] {userId};
        }
        return profileIds.get(userId).stream().mapToInt(Number::intValue).toArray();
    }

    /** Add a collection of profile IDs, all within the same profile group. */
    public void addProfileIds(@UserIdInt int... userIds) {
        final Set<Integer> profileGroup = new HashSet<>();
        for (int userId : userIds) {
            profileGroup.add(userId);
            profileIds.put(userId, profileGroup);
        }
    }
}
