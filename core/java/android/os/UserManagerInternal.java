/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.os;

/**
 * @hide Only for use within the system server.
 */
public abstract class UserManagerInternal {
    /**
     * Lock that must be held when calling certain methods in this class.
     *
     * This is used to avoid dead lock between
     * {@link com.android.server.pm.UserManagerService} and
     * {@link com.android.server.devicepolicy.DevicePolicyManagerService}.  This lock should not
     * be newly taken while holding the DPMS lock, which would cause a dead lock.  Take this
     * lock first before taking the DPMS lock to avoid that.
     */
    public abstract Object getUserRestrictionsLock();

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to get
     * {@link com.android.server.pm.UserManagerService} to update effective user restrictions.
     *
     * Must be called while taking the {@link #getUserRestrictionsLock()} lock.
     */
    public abstract void updateEffectiveUserRestrictionsRL(int userId);

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to get
     * {@link com.android.server.pm.UserManagerService} to update effective user restrictions.
     *
     * Must be called while taking the {@link #getUserRestrictionsLock()} lock.
     */
    public abstract void updateEffectiveUserRestrictionsForAllUsersRL();

    /**
     * Returns the "base" user restrictions.
     *
     * Used by {@link com.android.server.devicepolicy.DevicePolicyManagerService} for upgrading
     * from MNC.
     */
    public abstract Bundle getBaseUserRestrictions(int userId);

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} for upgrading
     * from MNC.
     */
    public abstract void setBaseUserRestrictionsByDpmsForMigration(int userId,
            Bundle baseRestrictions);
}
