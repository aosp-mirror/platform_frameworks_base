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

package android.app.admin;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Objects;

/**
 * Class representing the target user of a policy set by an admin
 * (set from {@link DevicePolicyManager}), this is passed in to
 * {@link PolicyUpdatesReceiver#onPolicySetResult} and
 * {@link PolicyUpdatesReceiver#onPolicyChanged}.
 */
public final class TargetUser {
    /**
     * @hide
     */
    public static final int LOCAL_USER_ID = -1;

    /**
     * @hide
     */
    public static final int PARENT_USER_ID = -2;

    /**
     * @hide
     */
    public static final int GLOBAL_USER_ID = -3;

    /**
     * @hide
     */
    public static final int UNKNOWN_USER_ID = -3;

    /**
     * Indicates that the policy relates to the user the admin is installed on.
     */
    @NonNull
    public static final TargetUser LOCAL_USER = new TargetUser(LOCAL_USER_ID);

    /**
     * For admins of profiles, this indicates that the policy relates to the parent profile.
     */
    @NonNull
    public static final TargetUser PARENT_USER = new TargetUser(PARENT_USER_ID);

    /**
     * This indicates the policy is a global policy.
     */
    @NonNull
    public static final TargetUser GLOBAL = new TargetUser(GLOBAL_USER_ID);

    /**
     * Indicates that the policy relates to some unknown user on the device. For example, if Admin1
     * has set a global policy on a device and Admin2 has set a conflicting local
     * policy on some other secondary user, Admin1 will get a policy update callback with
     * {@code UNKNOWN_USER} as the target user.
     */
    @NonNull
    public static final TargetUser UNKNOWN_USER = new TargetUser(UNKNOWN_USER_ID);

    private final int mUserId;

    /**
     * @hide
     */
    public TargetUser(int userId) {
        mUserId = userId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TargetUser other = (TargetUser) o;
        return mUserId == other.mUserId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUserId);
    }
}
