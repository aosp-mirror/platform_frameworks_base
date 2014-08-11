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

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Bitmap.Config;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager.LayoutParams;

import com.android.internal.R;

import java.util.ArrayList;
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
     * Key for user restrictions. Specifies if a user is disallowed from removing itself and other
     * users.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_REMOVE_USER = "no_remove_user";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from enabling or
     * accessing debugging features. The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_DEBUGGING_FEATURES = "no_debugging_features";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from configuring VPN.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_VPN = "no_config_vpn";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from configuring Tethering
     * & portable hotspots. The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_TETHERING = "no_config_tethering";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from factory resetting
     * from Settings.
     * The default value is <code>false</code>.
     * <p>
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_FACTORY_RESET = "no_factory_reset";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from adding new users and
     * profiles. The default value is <code>false</code>.
     * <p>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_ADD_USER = "no_add_user";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from disabling application
     * verification. The default value is <code>false</code>.
     * <p>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String ENSURE_VERIFY_APPS = "ensure_verify_apps";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from configuring cell
     * broadcasts. The default value is <code>false</code>.
     * <p>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_CELL_BROADCASTS = "no_config_cell_broadcasts";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from configuring mobile
     * networks. The default value is <code>false</code>.
     * <p>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CONFIG_MOBILE_NETWORKS = "no_config_mobile_networks";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from modifying
     * applications in Settings or launchers. The following actions will not be allowed when this
     * restriction is enabled:
     * <li>uninstalling apps</li>
     * <li>disabling apps</li>
     * <li>clearing app caches</li>
     * <li>clearing app data</li>
     * <li>force stopping apps</li>
     * <li>clearing app defaults</li>
     * <p>
     * The default value is <code>false</code>.
     * <p>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_APPS_CONTROL = "no_control_apps";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from mounting
     * physical external media. The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_MOUNT_PHYSICAL_MEDIA = "no_physical_media";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from adjusting microphone
     * volume.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_UNMUTE_MICROPHONE = "no_unmute_microphone";

    /**
     * Key for user restrictions. Specifies if a user is disallowed from adjusting the master
     * volume.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_ADJUST_VOLUME = "no_adjust_volume";

    /**
     * Key for user restrictions. Specifies that the user is not allowed to make outgoing
     * phone calls. Emergency calls are still permitted.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_OUTGOING_CALLS = "no_outgoing_calls";

    /**
     * Key for user restrictions. Specifies that the user is not allowed to send or receive
     * SMS messages.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_SMS = "no_sms";

    /**
     * Key for user restrictions. Specifies that windows besides app windows should not be
     * created. This will block the creation of the following types of windows.
     * <li>{@link LayoutParams#TYPE_TOAST}</li>
     * <li>{@link LayoutParams#TYPE_PHONE}</li>
     * <li>{@link LayoutParams#TYPE_PRIORITY_PHONE}</li>
     * <li>{@link LayoutParams#TYPE_SYSTEM_ALERT}</li>
     * <li>{@link LayoutParams#TYPE_SYSTEM_ERROR}</li>
     * <li>{@link LayoutParams#TYPE_SYSTEM_OVERLAY}</li>
     *
     * <p>The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CREATE_WINDOWS = "no_create_windows";

    /**
     * Key for user restrictions. Specifies if what is copied in the clipboard of this profile can
     * be pasted in related profiles. Does not restrict if the clipboard of related profiles can be
     * pasted in this profile.
     * The default value is <code>false</code>.
     * <p/>
     * Type: Boolean
     * @see #setUserRestrictions(Bundle)
     * @see #getUserRestrictions()
     */
    public static final String DISALLOW_CROSS_PROFILE_COPY_PASTE = "no_cross_profile_copy_paste";

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
     * @return true if multiple users can be created by user, false if it is a single user device.
     * @hide
     */
    public static boolean supportsMultipleUsers() {
        return getMaxSupportedUsers() > 1
                && SystemProperties.getBoolean("fw.show_multiuserui",
                Resources.getSystem().getBoolean(R.bool.config_enableMultiUserUI));
    }

    /**
     * Returns the user handle for the user that the calling process is running on.
     *
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
     * Checks if the calling app is running as a guest user.
     * @return whether the caller is a guest user.
     * @hide
     */
    public boolean isGuestUser() {
        UserInfo user = getUserInfo(UserHandle.myUserId());
        return user != null ? user.isGuest() : false;
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
     * @deprecated use {@link android.app.admin.DevicePolicyManager#addUserRestriction(
     * android.content.ComponentName, String)} or
     * {@link android.app.admin.DevicePolicyManager#clearUserRestriction(
     * android.content.ComponentName, String)} instead.
     */
    @Deprecated
    public void setUserRestrictions(Bundle restrictions) {
        setUserRestrictions(restrictions, Process.myUserHandle());
    }

    /**
     * Sets all the user-wide restrictions for the specified user.
     * Requires the MANAGE_USERS permission.
     * @param restrictions the Bundle containing all the restrictions.
     * @param userHandle the UserHandle of the user for whom to set the restrictions.
     * @deprecated use {@link android.app.admin.DevicePolicyManager#addUserRestriction(
     * android.content.ComponentName, String)} or
     * {@link android.app.admin.DevicePolicyManager#clearUserRestriction(
     * android.content.ComponentName, String)} instead.
     */
    @Deprecated
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
     * @deprecated use {@link android.app.admin.DevicePolicyManager#addUserRestriction(
     * android.content.ComponentName, String)} or
     * {@link android.app.admin.DevicePolicyManager#clearUserRestriction(
     * android.content.ComponentName, String)} instead.
     */
    @Deprecated
    public void setUserRestriction(String key, boolean value) {
        Bundle bundle = getUserRestrictions();
        bundle.putBoolean(key, value);
        setUserRestrictions(bundle);
    }

    /**
     * @hide
     * Sets the value of a specific restriction on a specific user.
     * Requires the MANAGE_USERS permission.
     * @param key the key of the restriction
     * @param value the value for the restriction
     * @param userHandle the user whose restriction is to be changed.
     * @deprecated use {@link android.app.admin.DevicePolicyManager#addUserRestriction(
     * android.content.ComponentName, String)} or
     * {@link android.app.admin.DevicePolicyManager#clearUserRestriction(
     * android.content.ComponentName, String)} instead.
     */
    @Deprecated
    public void setUserRestriction(String key, boolean value, UserHandle userHandle) {
        Bundle bundle = getUserRestrictions(userHandle);
        bundle.putBoolean(key, value);
        setUserRestrictions(bundle, userHandle);
    }

    /**
     * Returns whether the current user has been disallowed from performing certain actions
     * or setting certain settings.
     *
     * @param restrictionKey The string key representing the restriction.
     * @return {@code true} if the current user has the given restriction, {@code false} otherwise.
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
     * Creates a guest user and configures it.
     * @param context an application context
     * @param name the name to set for the user
     * @hide
     */
    public UserInfo createGuest(Context context, String name) {
        UserInfo guest = createUser(name, UserInfo.FLAG_GUEST);
        if (guest != null) {
            Settings.Secure.putStringForUser(context.getContentResolver(),
                    Settings.Secure.SKIP_FIRST_USE_HINTS, "1", guest.id);
            try {
                Bundle guestRestrictions = mService.getDefaultGuestRestrictions();
                guestRestrictions.putBoolean(DISALLOW_SMS, true);
                mService.setUserRestrictions(guestRestrictions, guest.id);
            } catch (RemoteException re) {
                Log.w(TAG, "Could not update guest restrictions");
            }
        }
        return guest;
    }

    /**
     * Creates a user with the specified name and options as a profile of another user.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     *
     * @param name the user's name
     * @param flags flags that identify the type of user and other properties.
     * @see UserInfo
     * @param userHandle new user will be a profile of this use.
     *
     * @return the UserInfo object for the created user, or null if the user could not be created.
     * @hide
     */
    public UserInfo createProfileForUser(String name, int flags, int userHandle) {
        try {
            return mService.createProfileForUser(name, flags, userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not create a user", re);
            return null;
        }
    }

    /**
     * Sets the user as enabled, if such an user exists.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     * Note that the default is true, it's only that managed profiles might not be enabled.
     *
     * @param userHandle the id of the profile to enable
     * @hide
     */
    public void setUserEnabled(int userHandle) {
        try {
            mService.setUserEnabled(userHandle);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not enable the profile", e);
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
     * Returns list of the profiles of userHandle including
     * userHandle itself.
     * Note that it this returns both enabled and not enabled profiles. See
     * {@link #getUserProfiles()} if you need only the enabled ones.
     *
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     * @param userHandle profiles of this user will be returned.
     * @return the list of profiles.
     * @hide
     */
    public List<UserInfo> getProfiles(int userHandle) {
        try {
            return mService.getProfiles(userHandle, false /* enabledOnly */);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user list", re);
            return null;
        }
    }

    /**
     * Returns a list of UserHandles for profiles associated with the user that the calling process
     * is running on, including the user itself.
     *
     * @return A non-empty list of UserHandles associated with the calling user.
     */
    public List<UserHandle> getUserProfiles() {
        ArrayList<UserHandle> profiles = new ArrayList<UserHandle>();
        List<UserInfo> users = new ArrayList<UserInfo>();
        try {
            users = mService.getProfiles(UserHandle.myUserId(), true /* enabledOnly */);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get user list", re);
            return null;
        }
        for (UserInfo info : users) {
            UserHandle userHandle = new UserHandle(info.id);
            profiles.add(userHandle);
        }
        return profiles;
    }

    /**
     * Returns the parent of the profile which this method is called from
     * or null if called from a user that is not a profile.
     *
     * @hide
     */
    public UserInfo getProfileParent(int userHandle) {
        try {
            return mService.getProfileParent(userHandle);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not get profile parent", re);
            return null;
        }
    }

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a badged copy of the given
     * icon to be able to distinguish it from the original icon. For badging an
     * arbitrary drawable use {@link #getBadgedDrawableForUser(
     * android.graphics.drawable.Drawable, UserHandle, android.graphics.Rect, int)}.
     * <p>
     * If the original drawable is a BitmapDrawable and the backing bitmap is
     * mutable as per {@link android.graphics.Bitmap#isMutable()}, the bading
     * is performed in place and the original drawable is returned.
     * </p>
     *
     * @param icon The icon to badge.
     * @param user The target user.
     * @return A drawable that combines the original icon and a badge as
     *         determined by the system.
     */
    public Drawable getBadgedIconForUser(Drawable icon, UserHandle user) {
        final int badgeResId = getBadgeResIdForUser(user.getIdentifier());
        if (badgeResId == 0) {
            return icon;
        }
        Drawable badgeIcon = mContext.getPackageManager()
                .getDrawable("system", badgeResId, null);
        return getBadgedDrawable(icon, badgeIcon, null, true);
    }

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a badged copy of the given
     * icon to be able to distinguish it from the original icon.
     * <p>
     * If the original drawable is not a BitmapDrawable, then the original
     * drawable is returned.
     * </p>
     *
     * @param icon The icon to badge.
     * @param user The target user.
     * @return A drawable that combines the original icon and a badge as
     *         determined by the system.
     *
     * @deprecation Use {@link #getBadgedIconForUser(
     *     android.graphics.drawable.Drawable, UserHandle)}
     *
     * @hide
     */
    @Deprecated
    public Drawable getBadgedDrawableForUser(Drawable icon, UserHandle user) {
        int badgeResId = getBadgeResIdForUser(user.getIdentifier());
        if (badgeResId == 0) {
            return icon;
        } else {
            Drawable badgeIcon = mContext.getPackageManager()
                    .getDrawable("system", badgeResId, null);
            return getBadgedDrawable(icon, badgeIcon, null, false);
        }
    }

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a badged copy of the given
     * drawable allowing the user to distinguish it from the original drawable.
     * The caller can specify the location in the bounds of the drawable to be
     * badged where the badge should be applied as well as the density of the
     * badge to be used.
     * <p>
     * If the original drawable is a BitmapDrawable and the backing bitmap is
     * mutable as per {@link android.graphics.Bitmap#isMutable()}, the bading
     * is performed in place and the original drawable is returned.
     * </p>
     *
     * @param badgedDrawable The drawable to badge.
     * @param user The target user.
     * @param badgeLocation Where in the bounds of the badged drawable to place
     *         the badge. If not provided, the badge is applied on top of the entire
     *         drawable being badged.
     * @param badgeDensity The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If not provided,
     *         the density of the display is used.
     * @return A drawable that combines the original drawable and a badge as
     *         determined by the system.
     */
    public Drawable getBadgedDrawableForUser(Drawable badgedDrawable, UserHandle user,
            Rect badgeLocation, int badgeDensity) {
        Drawable badgeDrawable = getBadgeForUser(user, badgeDensity);
        if (badgeDrawable == null) {
            return badgedDrawable;
        }
        return getBadgedDrawable(badgedDrawable, badgeDrawable, badgeLocation, true);
    }

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a copy of the label with
     * badging for accessibility services like talkback. E.g. passing in "Email"
     * and it might return "Work Email" for Email in the work profile.
     *
     * @param label The label to change.
     * @param user The target user.
     * @return A label that combines the original label and a badge as
     *         determined by the system.
     */
    public CharSequence getBadgedLabelForUser(CharSequence label, UserHandle user) {
        UserInfo userInfo = getUserIfProfile(user.getIdentifier());
        if (userInfo != null && userInfo.isManagedProfile()) {
            return Resources.getSystem().getString(
                    R.string.managed_profile_label_badge, label);
        }
        return label;
    }

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a drawable to use as a small
     * icon to include in a view to distinguish it from the original icon.
     *
     * @param user The target user.
     * @param density The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If not provided
     *         the density of the current display is used.
     * @return the drawable or null if no drawable is required.
     * @hide
     */
    public Drawable getBadgeForUser(UserHandle user, int density) {
        UserInfo userInfo = getUserIfProfile(user.getIdentifier());
        if (userInfo != null && userInfo.isManagedProfile()) {
            if (density <= 0) {
                density = mContext.getResources().getDisplayMetrics().densityDpi;
            }
            return Resources.getSystem().getDrawableForDensity(
                    com.android.internal.R.drawable.ic_corp_badge, density);
        }
        return null;
    }

    private int getBadgeResIdForUser(int userHandle) {
        // Return the framework-provided badge.
        UserInfo userInfo = getUserIfProfile(userHandle);
        if (userInfo != null && userInfo.isManagedProfile()) {
            return com.android.internal.R.drawable.ic_corp_icon_badge;
        }
        return 0;
    }

    /**
     * @return UserInfo for userHandle if it exists and is a profile of the current
     *         user or null.
     */
    private UserInfo getUserIfProfile(int userHandle) {
        List<UserInfo> userProfiles = getProfiles(getUserHandle());
        for (UserInfo user : userProfiles) {
            if (user.id == userHandle) {
                return user;
            }
        }
        return null;
    }

    private Drawable getBadgedDrawable(Drawable badgedDrawable, Drawable badgeDrawable,
            Rect badgeLocation, boolean tryBadgeInPlace) {
        final int badgedWidth = badgedDrawable.getIntrinsicWidth();
        final int badgedHeight = badgedDrawable.getIntrinsicHeight();
        final boolean canBadgeInPlace = tryBadgeInPlace
                && (badgedDrawable instanceof BitmapDrawable)
                && ((BitmapDrawable) badgedDrawable).getBitmap().isMutable();

        final Bitmap bitmap;
        if (canBadgeInPlace) {
            bitmap = ((BitmapDrawable) badgedDrawable).getBitmap();
        } else {
            bitmap = Bitmap.createBitmap(badgedWidth, badgedHeight, Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(bitmap);

        if (!canBadgeInPlace) {
            badgedDrawable.setBounds(0, 0, badgedWidth, badgedHeight);
            badgedDrawable.draw(canvas);
        }

        if (badgeLocation != null) {
            if (badgeLocation.left < 0 || badgeLocation.top < 0
                    || badgeLocation.right > badgedWidth || badgeLocation.bottom > badgedHeight) {
                throw new IllegalArgumentException("Badge location " + badgeLocation
                        + " not in badged drawable bounds "
                        + new Rect(0, 0, badgedWidth, badgedHeight));
            }
            badgeDrawable.setBounds(0, 0, badgeLocation.width(), badgeLocation.height());

            canvas.save();
            canvas.translate(badgeLocation.left, badgeLocation.top);
            badgeDrawable.draw(canvas);
            canvas.restore();
        } else {
            badgeDrawable.setBounds(0, 0, badgedWidth, badgedHeight);
            badgeDrawable.draw(canvas);
        }

        if (!canBadgeInPlace) {
            BitmapDrawable mergedDrawable = new BitmapDrawable(mContext.getResources(), bitmap);

            if (badgedDrawable instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) badgedDrawable;
                mergedDrawable.setTargetDensity(bitmapDrawable.getBitmap().getDensity());
            }

            return mergedDrawable;
        }

        return badgedDrawable;
    }

    /**
     * Returns information for all users on this device. Requires
     * {@link android.Manifest.permission#MANAGE_USERS} permission.
     *
     * @param excludeDying specify if the list should exclude users being
     *            removed.
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
     * Returns the maximum number of users that can be created on this device. A return value
     * of 1 means that it is a single user device.
     * @hide
     * @return a value greater than or equal to 1
     */
    public static int getMaxSupportedUsers() {
        // Don't allow multiple users on certain builds
        if (android.os.Build.ID.startsWith("JVP")) return 1;
        // Svelte devices don't get multi-user.
        if (ActivityManager.isLowRamDeviceStatic()) return 1;
        return SystemProperties.getInt("fw.max_users",
                Resources.getSystem().getInteger(R.integer.config_multiuserMaximumUsers));
    }

    /**
     * Returns true if the user switcher should be shown, this will be if there
     * are multiple users that aren't managed profiles.
     * @hide
     * @return true if user switcher should be shown.
     */
    public boolean isUserSwitcherEnabled() {
        List<UserInfo> users = getUsers(true);
        if (users == null) {
           return false;
        }
        int switchableUserCount = 0;
        for (UserInfo user : users) {
            if (user.supportsSwitchTo()) {
                ++switchableUserCount;
            }
        }
        final boolean guestEnabled = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.GUEST_USER_ENABLED, 0) == 1;
        return switchableUserCount > 1 || guestEnabled;
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

    /**
     * @hide
     * Set restrictions that should apply to any future guest user that's created.
     */
    public void setDefaultGuestRestrictions(Bundle restrictions) {
        try {
            mService.setDefaultGuestRestrictions(restrictions);
        } catch (RemoteException re) {
            Log.w(TAG, "Could not set guest restrictions");
        }
    }

    /**
     * @hide
     * Gets the default guest restrictions.
     */
    public Bundle getDefaultGuestRestrictions() {
        try {
            return mService.getDefaultGuestRestrictions();
        } catch (RemoteException re) {
            Log.w(TAG, "Could not set guest restrictions");
        }
        return new Bundle();
    }
}
