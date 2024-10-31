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

package com.android.server.supervision;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.Bundle;

/**
 * Local system service interface for {@link SupervisionService}.
 *
 * @hide Only for use within Android OS.
 */
public abstract class SupervisionManagerInternal {
    /**
     * Returns whether supervision is enabled for the specified user
     *
     * @param userId The user to retrieve the supervision state for
     * @return whether the user is supervised
     */
    public abstract boolean isSupervisionEnabledForUser(@UserIdInt int userId);

    /**
     * Sets whether the supervision lock screen should be shown for the specified user
     *
     * @param userId The user set the superivision state for
     * @param enabled Whether or not the superivision lock screen needs to be shown
     * @param options Optional configuration parameters for the supervision lock screen
     */
    public abstract void setSupervisionLockscreenEnabledForUser(
            @UserIdInt int userId, boolean enabled, @Nullable Bundle options);
}
