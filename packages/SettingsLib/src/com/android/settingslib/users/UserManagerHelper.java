/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.settingslib.users;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.util.UserIcons;

import java.util.Iterator;
import java.util.List;

/**
 * Helper class for managing users, providing methods for removing, adding and switching users.
 */
public final class UserManagerHelper {
    private static final String TAG = "UserManagerHelper";
    private final Context mContext;
    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;
    private OnUsersUpdateListener mUpdateListener;
    private final BroadcastReceiver mUserChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mUpdateListener.onUsersUpdate();
        }
    };

    public UserManagerHelper(Context context) {
        mContext = context;
        mUserManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

    /**
     * Registers a listener for updates to all users - removing, adding users or changing user info.
     *
     * @param listener Instance of {@link OnUsersUpdateListener}.
     */
    public void registerOnUsersUpdateListener(OnUsersUpdateListener listener) {
        mUpdateListener = listener;
        registerReceiver();
    }

    /**
     * Unregisters listener by unregistering {@code BroadcastReceiver}.
     */
    public void unregisterOnUsersUpdateListener() {
        unregisterReceiver();
    }

    /**
     * Gets UserInfo for the foreground user.
     *
     * Concept of foreground user is relevant for the multi-user deployment. Foreground user
     * corresponds to the currently "logged in" user.
     *
     * @return {@link UserInfo} for the foreground user.
     */
    public UserInfo getForegroundUserInfo() {
        return mUserManager.getUserInfo(getForegroundUserId());
    }

    /**
     * @return Id of the foreground user.
     */
    public int getForegroundUserId() {
        return mActivityManager.getCurrentUser();
    }

    /**
     * Gets UserInfo for the user running the caller process.
     *
     * Differentiation between foreground user and current process user is relevant for multi-user
     * deployments.
     *
     * Some multi-user aware components (like SystemUI) might run as a singleton - one component
     * for all users. Current process user is then always the same for that component, even when
     * the foreground user changes.
     *
     * @return {@link UserInfo} for the user running the current process.
     */
    public UserInfo getCurrentProcessUserInfo() {
        return mUserManager.getUserInfo(getCurrentProcessUserId());
    }

    /**
     * @return Id for the user running the current process.
     */
    public int getCurrentProcessUserId() {
        return UserHandle.myUserId();
    }

    /**
     * Gets all the other users on the system that are not the user running the current process.
     *
     * @return List of {@code UserInfo} for each user that is not the user running the process.
     */
    public List<UserInfo> getAllUsersExcludesCurrentProcessUser() {
        return getAllUsersExceptUser(getCurrentProcessUserId());
    }

    /**
     * Gets all the existing users on the system that are not the currently running as the
     * foreground user.
     *
     * @return List of {@code UserInfo} for each user that is not the foreground user.
     */
    public List<UserInfo> getAllUsersExcludesForegroundUser() {
        return getAllUsersExceptUser(getForegroundUserId());
    }

    /**
     * Gets all the other users on the system that are not the system user.
     *
     * @return List of {@code UserInfo} for each user that is not the system user.
     */
    public List<UserInfo> getAllUsersExcludesSystemUser() {
        return getAllUsersExceptUser(UserHandle.USER_SYSTEM);
    }

    /**
     * Get all the users except the one with userId passed in.
     *
     * @param userId of the user not to be returned.
     * @return All users other than user with userId.
     */
    public List<UserInfo> getAllUsersExceptUser(int userId) {
        List<UserInfo> others = getAllUsers();

        for (Iterator<UserInfo> iterator = others.iterator(); iterator.hasNext(); ) {
            UserInfo userInfo = iterator.next();
            if (userInfo.id == userId) {
                // Remove user with userId from the list.
                iterator.remove();
            }
        }
        return others;
    }

    /**
     * Gets all the users on the system that are not currently being removed.
     */
    public List<UserInfo> getAllUsers() {
        return mUserManager.getUsers(true /* excludeDying */);
    }

    // User information accessors

    /**
     * Checks whether the user is system user (admin).
     *
     * @param userInfo User to check against system user.
     * @return {@code true} if system user, {@code false} otherwise.
     */
    public boolean userIsSystemUser(UserInfo userInfo) {
        return userInfo.id == UserHandle.USER_SYSTEM;
    }

    /**
     * Returns whether this user can be removed from the system.
     *
     * @param userInfo User to be removed
     * @return {@code true} if they can be removed, {@code false} otherwise.
     */
    public boolean userCanBeRemoved(UserInfo userInfo) {
        return !userIsSystemUser(userInfo);
    }

    /**
     * Checks whether passed in user is the foreground user.
     *
     * @param userInfo User to check.
     * @return {@code true} if foreground user, {@code false} otherwise.
     */
    public boolean userIsForegroundUser(UserInfo userInfo) {
        return getForegroundUserId() == userInfo.id;
    }

    /**
     * Checks whether passed in user is the user that's running the current process.
     *
     * @param userInfo User to check.
     * @return {@code true} if user running the process, {@code false} otherwise.
     */
    public boolean userIsRunningCurrentProcess(UserInfo userInfo) {
        return getCurrentProcessUserId() == userInfo.id;
    }

    // Foreground user information accessors.

    /**
     * Checks if the foreground user is a guest user.
     */
    public boolean foregroundUserIsGuestUser() {
      return getForegroundUserInfo().isGuest();
    }

    /**
     * Return whether the foreground user has a restriction.
     *
     * @param restriction Restriction to check. Should be a UserManager.* restriction.
     * @return Whether that restriction exists for the foreground user.
     */
    public boolean foregroundUserHasUserRestriction(String restriction) {
        return mUserManager.hasUserRestriction(restriction, getForegroundUserInfo().getUserHandle());
    }

    /**
     * Checks if the foreground user can add new users.
     */
    public boolean foregroundUserCanAddUsers() {
        return !foregroundUserHasUserRestriction(UserManager.DISALLOW_ADD_USER);
    }

    // Current process user information accessors

    /**
     * Checks if the calling app is running in a demo user.
     */
    public boolean currentProcessRunningAsDemoUser() {
        return mUserManager.isDemoUser();
    }

    /**
     * Checks if the calling app is running as a guest user.
     */
    public boolean currentProcessRunningAsGuestUser() {
        return mUserManager.isGuestUser();
    }

    /**
     * Checks whether this process is running under the system user.
     */
    public boolean currentProcessRunningAsSystemUser() {
        return mUserManager.isSystemUser();
    }

    // Current process user restriction accessors

    /**
     * Return whether the user running the current process has a restriction.
     *
     * @param restriction Restriction to check. Should be a UserManager.* restriction.
     * @return Whether that restriction exists for the user running the process.
     */
    public boolean currentProcessHasUserRestriction(String restriction) {
        return mUserManager.hasUserRestriction(restriction);
    }

    /**
     * Checks if the user running the current process can add new users.
     */
    public boolean currentProcessCanAddUsers() {
        return !currentProcessHasUserRestriction(UserManager.DISALLOW_ADD_USER);
    }

    /**
     * Checks if the user running the current process can remove users.
     */
    public boolean currentProcessCanRemoveUsers() {
        return !currentProcessHasUserRestriction(UserManager.DISALLOW_REMOVE_USER);
    }

    /**
     * Checks if the user running the current process is allowed to switch to another user.
     */
    public boolean currentProcessCanSwitchUsers() {
        return !currentProcessHasUserRestriction(UserManager.DISALLOW_USER_SWITCH);
    }

    /**
     * Checks if the current process user can modify accounts. Demo and Guest users cannot modify
     * accounts even if the DISALLOW_MODIFY_ACCOUNTS restriction is not applied.
     */
    public boolean currentProcessCanModifyAccounts() {
        return !currentProcessHasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS)
                && !currentProcessRunningAsDemoUser()
                && !currentProcessRunningAsGuestUser();
    }

    // User actions

    /**
     * Creates a new user on the system.
     *
     * @param userName Name to give to the newly created user.
     * @return Newly created user.
     */
    public UserInfo createNewUser(String userName) {
        UserInfo user = mUserManager.createUser(userName, 0 /* flags */);
        if (user == null) {
            // Couldn't create user, most likely because there are too many, but we haven't
            // been able to reload the list yet.
            Log.w(TAG, "can't create user.");
            return null;
        }
        assignDefaultIcon(user);
        return user;
    }

    /**
     * Tries to remove the user that's passed in. System user cannot be removed.
     * If the user to be removed is user currently running the process,
     * it switches to the system user first, and then removes the user.
     *
     * @param userInfo User to be removed
     * @return {@code true} if user is successfully removed, {@code false} otherwise.
     */
    public boolean removeUser(UserInfo userInfo) {
        if (userIsSystemUser(userInfo)) {
            Log.w(TAG, "User " + userInfo.id + " is system user, could not be removed.");
            return false;
        }

        if (userInfo.id == getCurrentProcessUserId()) {
            switchToUserId(UserHandle.USER_SYSTEM);
        }

        return mUserManager.removeUser(userInfo.id);
    }

    /**
     * Switches (logs in) to another user.
     *
     * @param userInfo User to switch to.
     */
    public void switchToUser(UserInfo userInfo) {
        if (userInfo.id == getForegroundUserId()) {
            return;
        }

        switchToUserId(userInfo.id);
    }

    /**
     * Creates a new guest session and switches into the guest session.
     *
     * @param guestName Username for the guest user.
     */
    public void startNewGuestSession(String guestName) {
        UserInfo guest = mUserManager.createGuest(mContext, guestName);
        if (guest == null) {
            // Couldn't create user, most likely because there are too many, but we haven't
            // been able to reload the list yet.
            Log.w(TAG, "can't create user.");
            return;
        }
        assignDefaultIcon(guest);
        switchToUserId(guest.id);
    }

    /**
     * Gets an icon for the user.
     *
     * @param userInfo User for which we want to get the icon.
     * @return a Bitmap for the icon
     */
    public Bitmap getUserIcon(UserInfo userInfo) {
        Bitmap picture = mUserManager.getUserIcon(userInfo.id);

        if (picture == null) {
            return assignDefaultIcon(userInfo);
        }

        return picture;
    }

    /**
     * Method for scaling a Bitmap icon to a desirable size.
     *
     * @param icon Bitmap to scale.
     * @param desiredSize Wanted size for the icon.
     * @return Drawable for the icon, scaled to the new size.
     */
    public Drawable scaleUserIcon(Bitmap icon, int desiredSize) {
        Bitmap scaledIcon = Bitmap.createScaledBitmap(
                icon, desiredSize, desiredSize, true /* filter */);
        return new BitmapDrawable(mContext.getResources(), scaledIcon);
    }

    /**
     * Sets new Username for the user.
     *
     * @param user User whose name should be changed.
     * @param name New username.
     */
    public void setUserName(UserInfo user, String name) {
        mUserManager.setUserName(user.id, name);
    }

    /**
     * Gets a bitmap representing the user's default avatar.
     *
     * @param userInfo User whose avatar should be returned.
     * @return Default user icon
     */
    public Bitmap getUserDefaultIcon(UserInfo userInfo) {
        return UserIcons.convertToBitmap(
                UserIcons.getDefaultUserIcon(mContext.getResources(), userInfo.id, false));
    }

    /**
     * Gets a bitmap representing the default icon for a Guest user.
     *
     * @return Degault guest icon
     */
    public Bitmap getGuestDefaultIcon() {
        return UserIcons.convertToBitmap(UserIcons.getDefaultUserIcon(
                mContext.getResources(), UserHandle.USER_NULL, false));
    }

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverAsUser(mUserChangeReceiver, UserHandle.ALL, filter, null, null);
    }

    /**
     * Assigns a default icon to a user according to the user's id.
     *
     * @param userInfo User to assign a default icon to.
     * @return Bitmap that has been assigned to the user.
     */
    private Bitmap assignDefaultIcon(UserInfo userInfo) {
        Bitmap bitmap = userInfo.isGuest() ? getGuestDefaultIcon() : getUserDefaultIcon(userInfo);
        mUserManager.setUserIcon(userInfo.id, bitmap);
        return bitmap;
    }

    private void switchToUserId(int id) {
        try {
            mActivityManager.switchUser(id);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't switch user.", e);
        }
    }

    private void unregisterReceiver() {
        mContext.unregisterReceiver(mUserChangeReceiver);
    }

    /**
     * Interface for listeners that want to register for receiving updates to changes to the users
     * on the system including removing and adding users, and changing user info.
     */
    public interface OnUsersUpdateListener {
        /**
         * Method that will get called when users list has been changed.
         */
        void onUsersUpdate();
    }
}
