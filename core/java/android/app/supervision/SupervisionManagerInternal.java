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

package android.app.supervision;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.PersistableBundle;

/**
 * Local system service interface for {@link SupervisionService}.
 *
 * @hide Only for use within Android OS.
 */
public abstract class SupervisionManagerInternal {
    /**
     * Returns whether the app with given process uid is the active supervision app.
     *
     * <p>Supervision app is considered active when supervision is enabled for the user running the
     * given process uid.
     *
     * @param uid App process uid.
     * @return Whether the app is the active supervision app.
     */
    public abstract boolean isActiveSupervisionApp(int uid);

    /**
     * Returns whether supervision is enabled for the specified user.
     *
     * @param userId The user to retrieve the supervision state for.
     * @return Whether the user is supervised.
     */
    public abstract boolean isSupervisionEnabledForUser(@UserIdInt int userId);

    /** Returns whether the supervision lock screen needs to be shown. */
    public abstract boolean isSupervisionLockscreenEnabledForUser(@UserIdInt int userId);

    /**
     * Set whether supervision is enabled for the specified user.
     *
     * @param userId The user to set the supervision state for.
     * @param enabled Whether or not the user should be supervised.
     */
    public abstract void setSupervisionEnabledForUser(@UserIdInt int userId, boolean enabled);

    /**
     * Sets whether the supervision lock screen should be shown for the specified user.
     *
     * @param userId The user set the superivision state for.
     * @param enabled Whether or not the superivision lock screen needs to be shown.
     * @param options Optional configuration parameters for the supervision lock screen.
     */
    public abstract void setSupervisionLockscreenEnabledForUser(
            @UserIdInt int userId, boolean enabled, @Nullable PersistableBundle options);
}
