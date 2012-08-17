/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.internal.R;
import android.content.Context;
import android.content.pm.UserInfo;
import android.util.Log;

import java.util.List;

/**
 * Manages users and user details on a multi-user system.
 */
public class UserManager {

    private static String TAG = "UserManager";
    private final IUserManager mService;
    private final Context mContext;

    /** @hide */
    public UserManager(Context context, IUserManager service) {
        mService = service;
        mContext = context;
    }

    /**
     * Returns whether the system supports multiple users.
     * @return true if multiple users can be created, false if it is a single user device.
     */
    public boolean supportsMultipleUsers() {
        return getMaxSupportedUsers() > 1;
    }

    /** 
     * Returns the user handle for the user that this application is running for.
     * @return the user handle of the user making this call.
     * @hide
     * */
    public int getUserHandle() {
        return Process.myUserHandle();
    }

    /**
     * Returns the user name of the user making this call.
     * @return the user name
     */
    public String getUserName() {
        try {
            return mService.getUserInfo(getUserHandle()).name;
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user name", re);
            return "";
        }
    }

    /**
     * Returns the UserInfo object describing a specific user.
     * @param userHandle the user handle of the user whose information is being requested.
     * @return the UserInfo object for a specific user.
     * @hide
     * */
    public UserInfo getUserInfo(int userHandle) {
        try {
            return mService.getUserInfo(userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user info", re);
            return null;
        }
    }

    /**
     * Creates a user with the specified name and options.
     *
     * @param name the user's name
     * @param flags flags that identify the type of user and other properties.
     * @see UserInfo
     *
     * @return the UserInfo object for the created user, or null if the user could not be created.
     * @hide
     */
    public UserInfo createUser(String name, int flags) {
        try {
            return mService.createUser(name, flags);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not create a user", re);
            return null;
        }
    }

    /**
     * Returns information for all users on this device.
     * @return the list of users that were created.
     * @hide
     */
    public List<UserInfo> getUsers() {
        try {
            return mService.getUsers();
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user list", re);
            return null;
        }
    }

    /**
     * Removes a user and all associated data.
     * @param userHandle the integer handle of the user, where 0 is the primary user.
     * @hide
     */
    public boolean removeUser(int userHandle) {
        try {
            return mService.removeUser(userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not remove user ", re);
            return false;
        }
    }

    /**
     * Updates the user's name.
     *
     * @param userHandle the user's integer handle
     * @param name the new name for the user
     * @hide
     */
    public void setUserName(int userHandle, String name) {
        try {
            mService.setUserName(userHandle, name);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not set the user name ", re);
        }
    }

    /**
     * Returns a file descriptor for the user's photo. PNG data can be written into this file.
     * @param userHandle the user for whom to change the photo.
     * @return a {@link ParcelFileDescriptor} to which to write the photo.
     * @hide
     */
    public ParcelFileDescriptor setUserIcon(int userHandle) {
        try {
            return mService.setUserIcon(userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not set the user icon ", re);
            return null;
        }
    }

    /**
     * Enable or disable the use of a guest account. If disabled, the existing guest account
     * will be wiped.
     * @param enable whether to enable a guest account.
     * @hide
     */
    public void setGuestEnabled(boolean enable) {
        try {
            mService.setGuestEnabled(enable);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not change guest account availability to " + enable);
        }
    }

    /**
     * Checks if a guest user is enabled for this device.
     * @return whether a guest user is enabled
     * @hide
     */
    public boolean isGuestEnabled() {
        try {
            return mService.isGuestEnabled();
        } catch (RemoteException re) {
            Log.w(TAG, "Could not retrieve guest enabled state");
            return false;
        }
    }

    /**
     * Wipes all the data for a user, but doesn't remove the user.
     * @param userHandle
     * @hide
     */
    public void wipeUser(int userHandle) {
        try {
            mService.wipeUser(userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not wipe user " + userHandle);
        }
    }

    /**
     * Returns the maximum number of users that can be created on this device. A return value
     * of 1 means that it is a single user device.
     * @hide
     * @return a value greater than or equal to 1 
     */
    public int getMaxSupportedUsers() {
        return mContext.getResources().getInteger(R.integer.config_multiuserMaximumUsers);
    }

    /**
     * Returns a serial number on this device for a given userHandle. User handles can be recycled
     * when deleting and creating users, but serial numbers are not reused until the device is wiped.
     * @param userHandle
     * @return a serial number associated with that user, or -1 if the userHandle is not valid.
     * @hide
     */
    public int getUserSerialNumber(int userHandle) {
        try {
            return mService.getUserSerialNumber(userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get serial number for user " + userHandle);
        }
        return -1;
    }

    /**
     * Returns a userHandle on this device for a given user serial number. User handles can be
     * recycled when deleting and creating users, but serial numbers are not reused until the device
     * is wiped.
     * @param userSerialNumber
     * @return the userHandle associated with that user serial number, or -1 if the serial number
     * is not valid.
     * @hide
     */
    public int getUserHandle(int userSerialNumber) {
        try {
            return mService.getUserHandle(userSerialNumber);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get userHandle for user " + userSerialNumber);
        }
        return -1;
    }


}
