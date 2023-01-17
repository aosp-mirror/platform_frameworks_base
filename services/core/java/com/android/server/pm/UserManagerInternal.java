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
package com.android.server.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.UserManager;
import android.util.DebugUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

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

    public static final int USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE = 1;
    public static final int USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE = 2;
    public static final int USER_ASSIGNMENT_RESULT_FAILURE = -1;

    private static final String PREFIX_USER_ASSIGNMENT_RESULT = "USER_ASSIGNMENT_RESULT_";
    @IntDef(flag = false, prefix = {PREFIX_USER_ASSIGNMENT_RESULT}, value = {
            USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE,
            USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE,
            USER_ASSIGNMENT_RESULT_FAILURE
    })
    public @interface UserAssignmentResult {}

    public static final int USER_START_MODE_FOREGROUND = 1;
    public static final int USER_START_MODE_BACKGROUND = 2;
    public static final int USER_START_MODE_BACKGROUND_VISIBLE = 3;

    private static final String PREFIX_USER_START_MODE = "USER_START_MODE_";
    @IntDef(flag = false, prefix = {PREFIX_USER_START_MODE}, value = {
            USER_START_MODE_FOREGROUND,
            USER_START_MODE_BACKGROUND,
            USER_START_MODE_BACKGROUND_VISIBLE
    })
    public @interface UserStartMode {}

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
     * Listener for user lifecycle events.
     *
     * <p><b>NOTE: </b>implementations MUST not block the current thread.
     */
    public interface UserLifecycleListener {

        /**
         * Called when a new user is created.
         *
         * @param user new user.
         * @param token token passed to the method that created the user.
         */
        default void onUserCreated(UserInfo user, @Nullable Object token) {}

        /** Called when an existing user is removed. */
        default void onUserRemoved(UserInfo user) {}
    }

    /**
     * Listener for {@link UserManager#isUserVisible() user visibility} changes.
     */
    public interface UserVisibilityListener {

        /**
         * Called when the {@link UserManager#isUserVisible() user visibility} changed.
         *
         * <p><b>Note:</b> this method is called independently of
         * {@link com.android.server.SystemService} callbacks; for example, the call with
         * {@code visible} {@code true} might be called before the
         * {@link com.android.server.SystemService#onUserStarting(com.android.server.SystemService.TargetUser)}
         * call.
         */
        void onUserVisibilityChanged(@UserIdInt int userId, boolean visible);
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

    /** Return a user restriction. */
    public abstract boolean getUserRestriction(int userId, String key);

    /** Adds a listener to user restriction changes. */
    public abstract void addUserRestrictionsListener(UserRestrictionsListener listener);

    /** Remove a {@link UserRestrictionsListener}. */
    public abstract void removeUserRestrictionsListener(UserRestrictionsListener listener);

    /** Adds a {@link UserLifecycleListener}. */
    public abstract void addUserLifecycleListener(UserLifecycleListener listener);

    /** Removes a {@link UserLifecycleListener}. */
    public abstract void removeUserLifecycleListener(UserLifecycleListener listener);

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to update
     * whether the device is managed by device owner.
     *
     * @deprecated Use methods in {@link android.app.admin.DevicePolicyManagerInternal}.
     */
    @Deprecated
    // TODO(b/258213147): Remove
    public abstract void setDeviceManaged(boolean isManaged);

    /**
     * Returns whether the device is managed by device owner.
     *
     * @deprecated Use methods in {@link android.app.admin.DevicePolicyManagerInternal}.
     */
    @Deprecated
    // TODO(b/258213147): Remove
    public abstract boolean isDeviceManaged();

    /**
     * Called by {@link com.android.server.devicepolicy.DevicePolicyManagerService} to update
     * whether the user is managed by profile owner.
     *
     * @deprecated Use methods in {@link android.app.admin.DevicePolicyManagerInternal}.
     */
    // TODO(b/258213147): Remove
    @Deprecated
    public abstract void setUserManaged(int userId, boolean isManaged);

    /**
     * Whether a profile owner manages this user.
     *
     * @deprecated Use methods in {@link android.app.admin.DevicePolicyManagerInternal}.
     */
    // TODO(b/258213147): Remove
    @Deprecated
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
     * createAndManageUser is called by the device owner; it uses {@code token} to block until
     * the user is created (as it will be passed back to it through
     * {@link UserLifecycleListener#onUserCreated(UserInfo, Object)});
     */
    public abstract UserInfo createUserEvenWhenDisallowed(String name, String userType,
            int flags, String[] disallowedPackages, @Nullable Object token)
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
     * Internal implementation of getUsers does not check permissions.
     * This improves performance for calls from inside system server which already have permissions
     * checked.
     */
    public abstract @NonNull List<UserInfo> getUsers(boolean excludeDying);

    /**
     * Internal implementation of getUsers does not check permissions.
     * This improves performance for calls from inside system server which already have permissions
     * checked.
     */
    public abstract @NonNull List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying,
            boolean excludePreCreated);

    /**
     * Returns an array of ids for profiles associated with the specified user including the user
     * itself.
     * <p>Note that this includes all profile types (not including Restricted profiles).
     *
     * @param userId      id of the user to return profiles for
     * @param enabledOnly whether return only {@link UserInfo#isEnabled() enabled} profiles
     * @return A non-empty array of ids of profiles associated with the specified user if the user
     *         exists. Otherwise, an empty array.
     */
    public abstract @NonNull int[] getProfileIds(@UserIdInt int userId, boolean enabledOnly);

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
     * Gets a {@link UserInfo} for the given {@code userId}, or {@code null} if not found.
     */
    public abstract @Nullable UserInfo getUserInfo(@UserIdInt int userId);

    /**
     * Gets all {@link UserInfo UserInfos}.
     */
    public abstract @NonNull UserInfo[] getUserInfos();

    /**
     * Sets all default cross profile intent filters between {@code parentUserId} and
     * {@code profileUserId}.
     */
    public abstract void setDefaultCrossProfileIntentFilters(
            @UserIdInt int parentUserId, @UserIdInt int profileUserId);

    /**
     * Returns {@code true} if the system should ignore errors when preparing
     * the storage directories for the user with ID {@code userId}. This will
     * return {@code false} for all new users; it will only return {@code true}
     * for users that already existed on-disk from an older version of Android.
     */
    public abstract boolean shouldIgnorePrepareStorageErrors(int userId);

    /**
     * Returns the {@link UserProperties} of the given user, or {@code null} if it is not found.
     * NB: The actual object is returned. So do NOT modify it!
     */
    public abstract @Nullable UserProperties getUserProperties(@UserIdInt int userId);

    /**
     * Assigns a user to a display when it's starting, returning whether the assignment succeeded
     * and the user is {@link UserManager#isUserVisible() visible}.
     *
     * <p><b>NOTE: </b>this method is meant to be used only by {@code UserController} (when a user
     * is started); for extra unassignments, callers should call {@link
     * #assignUserToExtraDisplay(int, int)} instead.
     *
     * <p><b>NOTE: </b>this method doesn't validate if the display exists, it's up to the caller to
     * pass a valid display id.
     */
    public abstract @UserAssignmentResult int assignUserToDisplayOnStart(@UserIdInt int userId,
            @UserIdInt int profileGroupId, @UserStartMode int userStartMode, int displayId);

    /**
     * Assigns an extra display to the given user, so the user is visible on that display.
     *
     * <p>This method is meant to be used on automotive builds where a passenger zone has more than
     * one display (for example, the "main" display and a smaller display used for input).
     *
     * <p><b>NOTE: </b>this call will be ignored on devices that do not
     * {@link UserManager#isVisibleBackgroundUsersSupported() support visible background users}.
     *
     * @return whether the operation succeeded, in which case the user would be visible on the
     * display.
     */
    public abstract boolean assignUserToExtraDisplay(@UserIdInt int userId, int displayId);

    /**
     * Unassigns a user from its current display when it's stopping.
     *
     * <p><b>NOTE: </b>this method is meant to be used only by {@code UserController} (when a user
     * is stopped); for extra unassignments, callers should call
     * {@link #unassignUserFromExtraDisplay(int, int)} instead.
     */
    public abstract void unassignUserFromDisplayOnStop(@UserIdInt int userId);

    /**
     * Unassigns the extra display from the given user.
     *
     * <p>This method is meant to be used on automotive builds where a passenger zone has more than
     * one display (for example, the "main" display and a smaller display used for input).
     *
     * <p><b>NOTE: </b>this call will be ignored on devices that do not
     * {@link UserManager#isVisibleBackgroundUsersSupported() support visible background users}.
     *
     * @return whether the operation succeeded, i.e., the user was previously
     *         {@link #assignUserToExtraDisplay(int, int) assigned to an extra display}.
     */
    public abstract boolean unassignUserFromExtraDisplay(@UserIdInt int userId, int displayId);

    /**
     * Returns {@code true} if the user is visible (as defined by
     * {@link UserManager#isUserVisible()}.
     */
    public abstract boolean isUserVisible(@UserIdInt int userId);

    /**
     * Returns {@code true} if the user is visible (as defined by
     * {@link UserManager#isUserVisible()} in the given display.
     */
    public abstract boolean isUserVisible(@UserIdInt int userId, int displayId);

    /**
     * Returns the display id assigned to the user, or {@code Display.INVALID_DISPLAY} if the
     * user is not assigned to any display.
     *
     * <p>The current foreground user and its running profiles are associated with the
     * {@link android.view.Display#DEFAULT_DISPLAY default display}, while other users would only be
     * assigned to a display if a call to {@link #assignUserToDisplay(int, int)} is made for such
     * user / display combination (for example, if the user was started with
     * {@code ActivityManager.startUserInBackgroundOnSecondaryDisplay()}, {@code UserController}
     * would make such call).
     *
     * <p>If the user is a profile and is running, it's assigned to its parent display.
     */
    public abstract int getDisplayAssignedToUser(@UserIdInt int userId);

    /**
     * Returns the main user (i.e., not a profile) that is assigned to the display, or the
     * {@link android.app.ActivityManager#getCurrentUser() current foreground user} if no user is
     * associated with the display.
     *
     * <p>The {@link android.view.Display#DEFAULT_DISPLAY default display} is always assigned to
     * the current foreground user, while other displays would only be associated with users through
     * a explicit {@link #assignUserToDisplay(int, int)} call with that user / display combination
     * (for example, if the user was started with
     * {@code ActivityManager.startUserInBackgroundOnSecondaryDisplay()}, {@code UserController}
     * would make such call).
     */
    public abstract @UserIdInt int getUserAssignedToDisplay(int displayId);

    /**
     * Gets the user-friendly representation of the {@code result} of a
     * {@link #assignUserToDisplayOnStart(int, int, boolean, int)} call.
     */
    public static String userAssignmentResultToString(@UserAssignmentResult int result) {
        return DebugUtils.constantToString(UserManagerInternal.class, PREFIX_USER_ASSIGNMENT_RESULT,
                result);
    }

    /**
     * Gets the user-friendly representation of a user start {@code mode}.
     */
    public static String userStartModeToString(@UserStartMode int mode) {
        return DebugUtils.constantToString(UserManagerInternal.class, PREFIX_USER_START_MODE, mode);
    }

    /** Adds a {@link UserVisibilityListener}. */
    public abstract void addUserVisibilityListener(UserVisibilityListener listener);

    /** Removes a {@link UserVisibilityListener}. */
    public abstract void removeUserVisibilityListener(UserVisibilityListener listener);

    // TODO(b/242195409): remove this method if not needed anymore
    /** Notify {@link UserVisibilityListener listeners} that the visibility of the
     * {@link android.os.UserHandle#USER_SYSTEM} changed. */
    public abstract void onSystemUserVisibilityChanged(boolean visible);

    /** Return the integer types of the given user IDs. Only used for reporting metrics to statsd.
     */
    public abstract int[] getUserTypesForStatsd(@UserIdInt int[] userIds);

    /**
     * Returns the user id of the main user, or {@link android.os.UserHandle#USER_NULL} if there is
     * no main user.
     *
     * @see UserManager#isMainUser()
     */
    public abstract @UserIdInt int getMainUserId();

    /**
     * Returns the id of the user which should be in the foreground after boot completes.
     *
     * <p>If a boot user has been provided by calling {@link UserManager#setBootUser}, the
     * returned value will be whatever was specified, as long as that user exists and can be
     * switched to.
     *
     * <p>Otherwise, in {@link UserManager#isHeadlessSystemUserMode() headless system user mode},
     * this will be the user who was last in the foreground on this device. If there is no
     * switchable user on the device, a new user will be created and its id will be returned.
     *
     * <p>In non-headless system user mode, the return value will be {@link UserHandle#USER_SYSTEM}.
     */
    public abstract @UserIdInt int getBootUser()
            throws UserManager.CheckedUserOperationException;
}
