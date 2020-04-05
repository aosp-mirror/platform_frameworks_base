/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;

import com.android.server.pm.RestrictionsSet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide Only for use within the system server.
 */
public abstract class UserManagerInternal {

    public static final int OWNER_TYPE_DEVICE_OWNER = 0;
    public static final int OWNER_TYPE_PROFILE_OWNER = 1;
    public static final int OWNER_TYPE_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE = 2;
    public static final int OWNER_TYPE_NO_OWNER = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {OWNER_TYPE_DEVICE_OWNER, OWNER_TYPE_PROFILE_OWNER,
            OWNER_TYPE_PROFILE_OWNER_OF_ORGANIZATION_OWNED_DEVICE, OWNER_TYPE_NO_OWNER})
    public @interface OwnerType {
    }

    public interface UserRestrictionsListener {
        /**
         * Called when a user restriction changes.
         *
         * @param userId target user id
         * @param newRestrictions new user restrictions
         * @param prevRestrictions user restrictions that were previously set
         */
        void onUserRestrictionsChanged(int userId, Bundle newRestrictions, Bundle prevRestrictions);
    }

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to set
     * restrictions enforced by the user.
     *
     * @param originatingUserId user id of the user where the restrictions originated.
     * @param global            a bundle of global user restrictions. Global restrictions are
     *                          restrictions that apply device-wide: to the managed profile,
     *                          primary profile and secondary users and any profile created in
     *                          any secondary user.
     * @param local             a restriction set of local user restrictions. The key is the user
     *                          id of the user whom the restrictions are targeting.
     * @param isDeviceOwner     whether {@code originatingUserId} corresponds to device owner
     *                          user id.
     */
    public abstract void setDevicePolicyUserRestrictions(int originatingUserId,
            @Nullable Bundle global, @Nullable RestrictionsSet local, boolean isDeviceOwner);

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

    /** Return a user restriction. */
    public abstract boolean getUserRestriction(int userId, String key);

    /** Adds a listener to user restriction changes. */
    public abstract void addUserRestrictionsListener(UserRestrictionsListener listener);

    /** Remove a {@link UserRestrictionsListener}. */
    public abstract void removeUserRestrictionsListener(UserRestrictionsListener listener);

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to update
     * whether the device is managed by device owner.
     */
    public abstract void setDeviceManaged(boolean isManaged);

    /**
     * Returns whether the device is managed by device owner.
     */
    public abstract boolean isDeviceManaged();

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to update
     * whether the user is managed by profile owner.
     */
    public abstract void setUserManaged(int userId, boolean isManaged);

    /**
     * whether a profile owner manages this user.
     */
    public abstract boolean isUserManaged(int userId);

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to omit
     * restriction check, because DevicePolicyManager must always be able to set user icon
     * regardless of any restriction.
     * Also called by {@link com.android.server.pm.UserManagerService} because the logic of setting
     * the icon is in this method.
     */
    public abstract void setUserIcon(int userId, Bitmap bitmap);

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to inform the
     * user manager whether all users should be created ephemeral.
     */
    public abstract void setForceEphemeralUsers(boolean forceEphemeralUsers);

    /**
     * Switches to the system user and deletes all other users.
     *
     * <p>Called by the {@link com.android.server.devicepolicy.DevicePolicyManagerService} when
     * the force-ephemeral-users policy is toggled on to make sure there are no pre-existing
     * non-ephemeral users left.
     */
    public abstract void removeAllUsers();

    /**
     * Called by the activity manager when the ephemeral user goes to background and its removal
     * starts as a result.
     *
     * <p>It marks the ephemeral user as disabled in order to prevent it from being re-entered
     * before its removal finishes.
     *
     * @param userId the ID of the ephemeral user.
     */
    public abstract void onEphemeralUserStop(int userId);

    /**
     * Same as UserManager.createUser(), but bypasses the check for
     * {@link UserManager#DISALLOW_ADD_USER} and {@link UserManager#DISALLOW_ADD_MANAGED_PROFILE}
     *
     * <p>Called by the {@link com.android.server.devicepolicy.DevicePolicyManagerService} when
     * createAndManageUser is called by the device owner.
     */
    public abstract UserInfo createUserEvenWhenDisallowed(String name, String userType,
            int flags, String[] disallowedPackages)
            throws UserManager.CheckedUserOperationException;

    /**
     * Same as {@link UserManager#removeUser(int userId)}, but bypasses the check for
     * {@link UserManager#DISALLOW_REMOVE_USER} and
     * {@link UserManager#DISALLOW_REMOVE_MANAGED_PROFILE} and does not require the
     * {@link android.Manifest.permission#MANAGE_USERS} permission.
     */
    public abstract boolean removeUserEvenWhenDisallowed(int userId);

    /**
     * Return whether the given user is running in an
     * {@code UserState.STATE_RUNNING_UNLOCKING} or
     * {@code UserState.STATE_RUNNING_UNLOCKED} state.
     */
    public abstract boolean isUserUnlockingOrUnlocked(int userId);

    /**
     * Return whether the given user is running in an
     * {@code UserState.STATE_RUNNING_UNLOCKED} state.
     */
    public abstract boolean isUserUnlocked(int userId);

    /**
     * Returns whether the given user is running
     */
    public abstract boolean isUserRunning(int userId);

    /**
     * Returns whether the given user is initialized
     */
    public abstract boolean isUserInitialized(int userId);

    /**
     * Returns whether the given user exists
     */
    public abstract boolean exists(int userId);

    /**
     * Set user's running state
     */
    public abstract void setUserState(int userId, int userState);

    /**
     * Remove user's running state
     */
    public abstract void removeUserState(int userId);

    /**
     * Returns an array of user ids. This array is cached in UserManagerService and passed as a
     * reference, so do not modify the returned array.
     *
     * @return the array of user ids.
     */
    public abstract int[] getUserIds();

    /**
     * Checks if the {@code callingUserId} and {@code targetUserId} are same or in same group
     * and that the {@code callingUserId} is not a profile and {@code targetUserId} is enabled.
     *
     * @return TRUE if the {@code callingUserId} can access {@code targetUserId}. FALSE
     * otherwise
     *
     * @throws SecurityException if the calling user and {@code targetUser} are not in the same
     * group and {@code throwSecurityException} is true, otherwise if will simply return false.
     */
    public abstract boolean isProfileAccessible(int callingUserId, int targetUserId,
            String debugMsg, boolean throwSecurityException);

    /**
     * If {@code userId} is of a profile, return the parent user ID. Otherwise return itself.
     */
    public abstract int getProfileParentId(int userId);

    /**
     * Checks whether changing a setting to a value is prohibited by the corresponding user
     * restriction.
     *
     * <p>See also {@link com.android.server.pm.UserRestrictionsUtils#applyUserRestriction(
     * Context, int, String, boolean)}, which should be in sync with this method.
     *
     * @return {@code true} if the change is prohibited, {@code false} if the change is allowed.
     *
     * @hide
     */
    public abstract boolean isSettingRestrictedForUser(String setting, int userId, String value,
            int callingUid);

    /** @return a specific user restriction that's in effect currently. */
    public abstract boolean hasUserRestriction(String restriction, int userId);

    /**
     * Gets an {@link UserInfo} for the given {@code userId}, or {@code null} if not
     * found.
     */
    public abstract @Nullable UserInfo getUserInfo(@UserIdInt int userId);

    /**
     * Gets all {@link UserInfo UserInfos}.
     */
    public abstract @NonNull UserInfo[] getUserInfos();
}
