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
     * Gets {@link UserInfo} for the current user.
     *
     * @return {@link UserInfo} for the current user.
     */
    public UserInfo getCurrentUserInfo() {
        return mUserManager.getUserInfo(UserHandle.myUserId());
    }

    /**
     * Gets all the other users on the system that are not the current user.
     *
     * @return List of {@code UserInfo} for each user that is not the current user.
     */
    public List<UserInfo> getAllUsersExcludesCurrentUser() {
        List<UserInfo> others = getAllUsers();

        for (Iterator<UserInfo> iterator = others.iterator(); iterator.hasNext(); ) {
            UserInfo userInfo = iterator.next();
            if (userInfo.id == UserHandle.myUserId()) {
                // Remove current user from the list.
                iterator.remove();
            }
        }
        return others;
    }

    /**
     * Gets all the other users on the system that are not the system user.
     *
     * @return List of {@code UserInfo} for each user that is not the system user.
     */
    public List<UserInfo> getAllUsersExcludesSystemUser() {
        List<UserInfo> others = getAllUsers();

        for (Iterator<UserInfo> iterator = others.iterator(); iterator.hasNext(); ) {
            UserInfo userInfo = iterator.next();
            if (userIsSystemUser(userInfo)) {
                // Remove system user from the list.
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
     * Checks whether passed in user is the user that's currently logged in.
     *
     * @param userInfo User to check.
     * @return {@code true} if current user, {@code false} otherwise.
     */
    public boolean userIsCurrentUser(UserInfo userInfo) {
        return getCurrentUserInfo().id == userInfo.id;
    }

    // Current user information accessors

    /**
     * Checks if the current user is a demo user.
     */
    public boolean isDemoUser() {
        return mUserManager.isDemoUser();
    }

    /**
     * Checks if the current user is a guest user.
     */
    public boolean isGuestUser() {
        return mUserManager.isGuestUser();
    }

    /**
     * Checks if the current user is the system user (User 0).
     */
    public boolean isSystemUser() {
        return mUserManager.isSystemUser();
    }

    // Current user restriction accessors

    /**
     * Return whether the current user has a restriction.
     *
     * @param restriction Restriction to check. Should be a UserManager.* restriction.
     * @return Whether that restriction exists for the current user.
     */
    public boolean hasUserRestriction(String restriction) {
        return mUserManager.hasUserRestriction(restriction);
    }

    /**
     * Checks if the current user can add new users.
     */
    public boolean canAddUsers() {
        return !hasUserRestriction(UserManager.DISALLOW_ADD_USER);
    }

    /**
     * Checks if the current user can remove users.
     */
    public boolean canRemoveUsers() {
        return !hasUserRestriction(UserManager.DISALLOW_REMOVE_USER);
    }

    /**
     * Checks if the current user is allowed to switch to another user.
     */
    public boolean canSwitchUsers() {
        return !hasUserRestriction(UserManager.DISALLOW_USER_SWITCH);
    }

    /**
     * Checks if the current user can modify accounts. Demo and Guest users cannot modify accounts
     * even if the DISALLOW_MODIFY_ACCOUNTS restriction is not applied.
     */
    public boolean canModifyAccounts() {
        return !hasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS) && !isDemoUser()
                && !isGuestUser();
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
     * If the user to be removed is current user, it switches to the system user first, and then
     * removes the user.
     *
     * @param userInfo User to be removed
     * @return {@code true} if user is successfully removed, {@code false} otherwise.
     */
    public boolean removeUser(UserInfo userInfo) {
        if (userIsSystemUser(userInfo)) {
            Log.w(TAG, "User " + userInfo.id + " is system user, could not be removed.");
            return false;
        }

        if (userInfo.id == getCurrentUserInfo().id) {
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
        if (userInfo.id == getCurrentUserInfo().id) {
            return;
        }

        if (userInfo.isGuest()) {
            switchToGuest(userInfo.name);
            return;
        }

        if (UserManager.isGuestUserEphemeral()) {
            // If switching from guest, we want to bring up the guest exit dialog instead of
            // switching
            UserInfo currUserInfo = getCurrentUserInfo();
            if (currUserInfo != null && currUserInfo.isGuest()) {
                return;
            }
        }

        switchToUserId(userInfo.id);
    }

    /**
     * Creates a guest session and switches into the guest session.
     *
     * @param guestName Username for the guest user.
     */
    public void switchToGuest(String guestName) {
        UserInfo guest = mUserManager.createGuest(mContext, guestName);
        if (guest == null) {
            // Couldn't create user, most likely because there are too many, but we haven't
            // been able to reload the list yet.
            Log.w(TAG, "can't create user.");
            return;
        }
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
        Bitmap bitmap = UserIcons.convertToBitmap(
                UserIcons.getDefaultUserIcon(mContext.getResources(), userInfo.id, false));
        mUserManager.setUserIcon(userInfo.id, bitmap);
        return bitmap;
    }

    private void switchToUserId(int id) {
        try {
            final ActivityManager am = (ActivityManager)
                    mContext.getSystemService(Context.ACTIVITY_SERVICE);
            am.switchUser(id);
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

