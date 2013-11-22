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

import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.RestrictionEntry;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.util.Log;

import com.android.internal.R;

import java.util.List;

/**
 * Manages users and user details on a multi-user system.
 */
public class UserManager {

    private static String TAG = "UserManager";
    private final IUserManager mService;
    private final Context mContext;

    /**
     * Key for user restrictions. Specifies if a user is disallowed from adding and removing
     * accounts.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_MODIFY_ACCOUNTS = "no_modify_accounts";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from changing Wi-Fi
     * access points.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_WIFI = "no_config_wifi";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from installing applications.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_INSTALL_APPS = "no_install_apps";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from uninstalling applications.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_UNINSTALL_APPS = "no_uninstall_apps";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from toggling location sharing.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */

    public static final String DISALLOW_SHARE_LOCATION = "no_share_location";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from enabling the
     * "Unknown Sources" setting, that allows installation of apps from unknown sources.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_INSTALL_UNKNOWN_SOURCES = "no_install_unknown_sources";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from configuring bluetooth.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_BLUETOOTH = "no_config_bluetooth";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from transferring files over
     * USB. The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_USB_FILE_TRANSFER = "no_usb_file_transfer";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from configuring user
     * credentials. The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_CREDENTIALS = "no_config_credentials";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from removing users.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_REMOVE_USER = "no_remove_user";

    /** @hide */
    public static final int PIN_VERIFICATION_FAILED_INCORRECT = -3;
    /** @hide */
    public static final int PIN_VERIFICATION_FAILED_NOT_SET = -2;
    /** @hide */
    public static final int PIN_VERIFICATION_SUCCESS = -1;

    private static UserManager sInstance = null;

    /** @hide */
    public synchronized static UserManager get(Context context) {
        if (sInstance == null) {
            sInstance = (UserManager) context.getSystemService(Context.USER_SERVICE);
        }
        return sInstance;
    }

    /** @hide */
    public UserManager(Context context, IUserManager service) {
        mService = service;
        mContext = context;
    }

    /**
     * Returns whether the system supports multiple users.
     * @return true if multiple users can be created, false if it is a single user device.
     * @hide
     */
    public static boolean supportsMultipleUsers() {
        return getMaxSupportedUsers() > 1;
    }

    /**
     * Returns the user handle for the user that this application is running for.
     * @return the user handle of the user making this call.
     * @hide
     */
    public int getUserHandle() {
        return UserHandle.myUserId();
    }

    /**
     * Returns the user name of the user making this call.  This call is only
     * available to applications on the system image; it requires the
     * MANAGE_USERS permission.
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
     * Used to determine whether the user making this call is subject to
     * teleportations.
     * @return whether the user making this call is a goat
     */
    public boolean isUserAGoat() {
        return false;
    }

    /**
     * Used to check if the user making this call is linked to another user. Linked users may have
     * a reduced number of available apps, app restrictions and account restrictions.
     * @return whether the user making this call is a linked user
     * @hide
     */
    public boolean isLinkedUser() {
        try {
            return mService.isRestricted();
        } catch (RemoteException re) {
            Log.w(TAG, "Could not check if user is limited ", re);
            return false;
        }
    }

    /**
     * Return whether the given user is actively running.  This means that
     * the user is in the "started" state, not "stopped" -- it is currently
     * allowed to run code through scheduled alarms, receiving broadcasts,
     * etc.  A started user may be either the current foreground user or a
     * background user; the result here does not distinguish between the two.
     * @param user The user to retrieve the running state for.
     */
    public boolean isUserRunning(UserHandle user) {
        try {
            return ActivityManagerNative.getDefault().isUserRunning(
                    user.getIdentifier(), false);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Return whether the given user is actively running <em>or</em> stopping.
     * This is like {@link #isUserRunning(UserHandle)}, but will also return
     * true if the user had been running but is in the process of being stopped
     * (but is not yet fully stopped, and still running some code).
     * @param user The user to retrieve the running state for.
     */
    public boolean isUserRunningOrStopping(UserHandle user) {
        try {
            return ActivityManagerNative.getDefault().isUserRunning(
                    user.getIdentifier(), true);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Returns the UserInfo object describing a specific user.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     * @param userHandle the user handle of the user whose information is being requested.
     * @return the UserInfo object for a specific user.
     * @hide
     */
    public UserInfo getUserInfo(int userHandle) {
        try {
            return mService.getUserInfo(userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user info", re);
            return null;
        }
    }

    /**
     * Returns the user-wide restrictions imposed on this user.
     * @return a Bundle containing all the restrictions.
     */
    public Bundle getUserRestrictions() {
        return getUserRestrictions(Process.myUserHandle());
    }

    /**
     * Returns the user-wide restrictions imposed on the user specified by <code>userHandle</code>.
     * @param userHandle the UserHandle of the user for whom to retrieve the restrictions.
     * @return a Bundle containing all the restrictions.
     */
    public Bundle getUserRestrictions(UserHandle userHandle) {
        try {
            return mService.getUserRestrictions(userHandle.getIdentifier());
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user restrictions", re);
            return Bundle.EMPTY;
        }
    }

    /**
     * Sets all the user-wide restrictions for this user.
     * Requires the MANAGE_USERS permission.
     * @param restrictions the Bundle containing all the restrictions.
     */
    public void setUserRestrictions(Bundle restrictions) {
        setUserRestrictions(restrictions, Process.myUserHandle());
    }

    /**
     * Sets all the user-wide restrictions for the specified user.
     * Requires the MANAGE_USERS permission.
     * @param restrictions the Bundle containing all the restrictions.
     * @param userHandle the UserHandle of the user for whom to set the restrictions.
     */
    public void setUserRestrictions(Bundle restrictions, UserHandle userHandle) {
        try {
            mService.setUserRestrictions(restrictions, userHandle.getIdentifier());
        } catch (RemoteException re) {
            Log.w(TAG, "Could not set user restrictions", re);
        }
    }

    /**
     * Sets the value of a specific restriction.
     * Requires the MANAGE_USERS permission.
     * @param key the key of the restriction
     * @param value the value for the restriction
     */
    public void setUserRestriction(String key, boolean value) {
        Bundle bundle = getUserRestrictions();
        bundle.putBoolean(key, value);
        setUserRestrictions(bundle);
    }

    /**
     * @hide
     * Sets the value of a specific restriction on a specific user.
     * Requires the {@link android.Manifest.permission#MANAGE_USERS} permission.
     * @param key the key of the restriction
     * @param value the value for the restriction
     * @param userHandle the user whose restriction is to be changed.
     */
    public void setUserRestriction(String key, boolean value, UserHandle userHandle) {
        Bundle bundle = getUserRestrictions(userHandle);
        bundle.putBoolean(key, value);
        setUserRestrictions(bundle, userHandle);
    }

    /**
     * @hide
     * Returns whether the current user has been disallowed from performing certain actions
     * or setting certain settings.
     * @param restrictionKey the string key representing the restriction
     */
    public boolean hasUserRestriction(String restrictionKey) {
        return hasUserRestriction(restrictionKey, Process.myUserHandle());
    }

    /**
     * @hide
     * Returns whether the given user has been disallowed from performing certain actions
     * or setting certain settings.
     * @param restrictionKey the string key representing the restriction
     * @param userHandle the UserHandle of the user for whom to retrieve the restrictions.
     */
    public boolean hasUserRestriction(String restrictionKey, UserHandle userHandle) {
        return getUserRestrictions(userHandle).getBoolean(restrictionKey, false);
    }

    /**
     * Return the serial number for a user.  This is a device-unique
     * number assigned to that user; if the user is deleted and then a new
     * user created, the new users will not be given the same serial number.
     * @param user The user whose serial number is to be retrieved.
     * @return The serial number of the given user; returns -1 if the
     * given UserHandle does not exist.
     * @see #getUserForSerialNumber(long)
     */
    public long getSerialNumberForUser(UserHandle user) {
        return getUserSerialNumber(user.getIdentifier());
    }

    /**
     * Return the user associated with a serial number previously
     * returned by {@link #getSerialNumberForUser(UserHandle)}.
     * @param serialNumber The serial number of the user that is being
     * retrieved.
     * @return Return the user associated with the serial number, or null
     * if there is not one.
     * @see #getSerialNumberForUser(UserHandle)
     */
    public UserHandle getUserForSerialNumber(long serialNumber) {
        int ident = getUserHandle((int)serialNumber);
        return ident >= 0 ? new UserHandle(ident) : null;
    }

    /**
     * Creates a user with the specified name and options.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
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
     * Return the number of users currently created on the device.
     */
    public int getUserCount() {
        List<UserInfo> users = getUsers();
        return users != null ? users.size() : 1;
    }

    /**
     * Returns information for all users on this device.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     * @return the list of users that were created.
     * @hide
     */
    public List<UserInfo> getUsers() {
        try {
            return mService.getUsers(false);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user list", re);
            return null;
        }
    }

    /**
     * Returns information for all users on this device.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     * @param excludeDying specify if the list should exclude users being removed.
     * @return the list of users that were created.
     * @hide
     */
    public List<UserInfo> getUsers(boolean excludeDying) {
        try {
            return mService.getUsers(excludeDying);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user list", re);
            return null;
        }
    }

    /**
     * Removes a user and all associated data.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
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
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
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
     * Sets the user's photo.
     * @param userHandle the user for whom to change the photo.
     * @param icon the bitmap to set as the photo.
     * @hide
     */
    public void setUserIcon(int userHandle, Bitmap icon) {
        try {
            mService.setUserIcon(userHandle, icon);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not set the user icon ", re);
        }
    }

    /**
     * Returns a file descriptor for the user's photo. PNG data can be read from this file.
     * @param userHandle the user whose photo we want to read.
     * @return a {@link Bitmap} of the user's photo, or null if there's no photo.
     * @hide
     */
    public Bitmap getUserIcon(int userHandle) {
        try {
            return mService.getUserIcon(userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get the user icon ", re);
            return null;
        }
    }

    /**
     * Enable or disable the use of a guest account. If disabled, the existing guest account
     * will be wiped.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
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
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
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
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
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
    public static int getMaxSupportedUsers() {
        // Don't allow multiple users on certain builds
        if (android.os.Build.ID.startsWith("JVP")) return 1;
        return SystemProperties.getInt("fw.max_users",
                Resources.getSystem().getInteger(R.integer.config_multiuserMaximumUsers));
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

    /**
     * Returns a Bundle containing any saved application restrictions for this user, for the
     * given package name. Only an application with this package name can call this method.
     * @param packageName the package name of the calling application
     * @return a Bundle with the restrictions as key/value pairs, or null if there are no
     * saved restrictions. The values can be of type Boolean, String or String[], depending
     * on the restriction type, as defined by the application.
     */
    public Bundle getApplicationRestrictions(String packageName) {
        try {
            return mService.getApplicationRestrictions(packageName);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get application restrictions for package " + packageName);
        }
        return null;
    }

    /**
     * @hide
     */
    public Bundle getApplicationRestrictions(String packageName, UserHandle user) {
        try {
            return mService.getApplicationRestrictionsForUser(packageName, user.getIdentifier());
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get application restrictions for user " + user.getIdentifier());
        }
        return null;
    }

    /**
     * @hide
     */
    public void setApplicationRestrictions(String packageName, Bundle restrictions,
            UserHandle user) {
        try {
            mService.setApplicationRestrictions(packageName, restrictions, user.getIdentifier());
        } catch (RemoteException re) {
            Log.w(TAG, "Could not set application restrictions for user " + user.getIdentifier());
        }
    }

    /**
     * Sets a new challenge PIN for restrictions. This is only for use by pre-installed
     * apps and requires the MANAGE_USERS permission.
     * @param newPin the PIN to use for challenge dialogs.
     * @return Returns true if the challenge PIN was set successfully.
     */
    public boolean setRestrictionsChallenge(String newPin) {
        try {
            return mService.setRestrictionsChallenge(newPin);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not change restrictions pin");
        }
        return false;
    }

    /**
     * @hide
     * @param pin The PIN to verify, or null to get the number of milliseconds to wait for before
     * allowing the user to enter the PIN.
     * @return Returns a positive number (including zero) for how many milliseconds before
     * you can accept another PIN, when the input is null or the input doesn't match the saved PIN.
     * Returns {@link #PIN_VERIFICATION_SUCCESS} if the input matches the saved PIN. Returns
     * {@link #PIN_VERIFICATION_FAILED_NOT_SET} if there is no PIN set.
     */
    public int checkRestrictionsChallenge(String pin) {
        try {
            return mService.checkRestrictionsChallenge(pin);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not check restrictions pin");
        }
        return PIN_VERIFICATION_FAILED_INCORRECT;
    }

    /**
     * @hide
     * Checks whether the user has restrictions that are PIN-protected. An application that
     * participates in restrictions can check if the owner has requested a PIN challenge for
     * any restricted operations. If there is a PIN in effect, the application should launch
     * the PIN challenge activity {@link android.content.Intent#ACTION_RESTRICTIONS_CHALLENGE}.
     * @see android.content.Intent#ACTION_RESTRICTIONS_CHALLENGE
     * @return whether a restrictions PIN is in effect.
     */
    public boolean hasRestrictionsChallenge() {
        try {
            return mService.hasRestrictionsChallenge();
        } catch (RemoteException re) {
            Log.w(TAG, "Could not change restrictions pin");
        }
        return false;
    }

    /** @hide */
    public void removeRestrictions() {
        try {
            mService.removeRestrictions();
        } catch (RemoteException re) {
            Log.w(TAG, "Could not change restrictions pin");
        }
    }
}
